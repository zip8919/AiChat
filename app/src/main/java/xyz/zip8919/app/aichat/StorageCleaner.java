package xyz.zip8919.app.aichat;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

/**
 * 清理执行器。
 *
 * <p>三条铁律：
 * <ul>
 *   <li><b>每次都重新扫盘，不复用界面上的旧报告</b>。用户可能刚发了一张图，
 *       界面上的「无引用」是十秒前的快照；拿旧快照删等于删掉正在用的文件。</li>
 *   <li><b>索引不可信就不删</b>。任何一个会话读不出来，{@link StorageRefs#incomplete}
 *       为真，此时直接返回 0，由界面提示「部分会话无法解析，暂不能清理」。</li>
 *   <li><b>只删自己确认过的对象</b>。临时文件按「目录 + 前缀 + 年龄」三重限定，
 *       不出现「遍历某个目录全删」这种写法——那是误删的标准化路径。</li>
 * </ul>
 *
 * <p>全部方法都需要在后台线程调用。
 */
final class StorageCleaner {

    private static final String TAG = "StorageCleaner";

    /** 一次清理的结果。failed 单独计数：被占用的文件删不掉是常态，不能让用户以为全清了。 */
    static final class Result {
        int deleted;
        int failed;
        long bytes;
    }

    private StorageCleaner() {
    }

    /** 清临时文件：缓存目录、导入残留、写入中转。这些都是可再生的中间产物。 */
    static Result cleanTemp(StorageManager sm) {
        Result res = new Result();
        long now = System.currentTimeMillis();

        File attDir = sm.getAttachmentsDir();
        if (attDir != null) {
            File[] files = attDir.listFiles();
            if (files != null) {
                for (int i = 0; i < files.length; i++) {
                    File f = files[i];
                    if (f.isFile() && f.getName().startsWith("tmp-")
                            && now - f.lastModified() >= StorageScanner.DAY) {
                        delete(f, res);
                    }
                }
            }
        }

        // 写文件时先落 .tmp 再改名，进程被杀会留下 .tmp；1 小时内可能是正在写的，跳过
        deleteTmpIn(new File(sm.getConversationsPath()), now, res);
        deleteTmpIn(new File(sm.getExportsDir()), now, res);

        File cacheDir = sm.getCacheDir();
        if (cacheDir != null) {
            cleanCache(cacheDir, now, 0, res);
        }

        LogUtil.i(TAG, "cleanTemp: deleted=%d failed=%d bytes=%d", res.deleted, res.failed, res.bytes);
        return res;
    }

    /**
     * 清无引用图片。
     *
     * <p>重新建一次索引而不是复用界面数据：清理不可逆，多花一次读盘换「不会误删」值得。
     */
    static Result cleanOrphans(StorageManager sm) {
        Result res = new Result();
        File attDir = sm.getAttachmentsDir();
        if (attDir == null) return res;

        StorageRefs refs = scanAll(sm);
        if (refs.incomplete) {
            LogUtil.w(TAG, "cleanOrphans skipped: index incomplete (%d unreadable)", refs.unreadable);
            return res;
        }
        // 一条引用都没解析出来却要按「全都无引用」删：判定没有把握，一律不动。
        // 用户仍可在图片页逐张确认删除（那里会显示警告）。
        // 但「扫到了会话文件、确实零引用」是确定事实，与 Scanner/附件页的
        // indexEmpty 判定保持一致；缺 scanned>0 会让「会话目录已空 + 存在
        // 孤儿图」时一键清理直接 return，用户反复点无效。
        if (refs.size() == 0 && refs.matched == 0 && refs.scanned > 0) {
            LogUtil.w(TAG, "cleanOrphans skipped: index empty, refusing to treat all as orphans");
            return res;
        }

        File[] files = attDir.listFiles();
        if (files != null) {
            for (int i = 0; i < files.length; i++) {
                File f = files[i];
                if (!f.isFile()) continue;
                String name = f.getName();
                if (name.startsWith("tmp-")) continue;
                // 只认引用：判定依据越少越不容易漂移。保护期已移除，
                // 因为导入是同步的，那个窗口只有毫秒级，留一小时只会让清理看起来没生效。
                if (refs.has(name)) continue;
                // 输入框里还没发出去的图：没有引用，但删掉就等于用户点发送时静默丢图
                // （缩略图在内存里，界面看起来完全正常）。见 AttachmentFiles.PENDING。
                if (AttachmentFiles.isPendingName(name)) continue;
                delete(f, res);
            }
        }

        LogUtil.i(TAG, "cleanOrphans: deleted=%d failed=%d bytes=%d", res.deleted, res.failed, res.bytes);
        return res;
    }

    /**
     * 取一条会话引用过的附件文件名。必须在删除会话<b>之前</b>调用——删完文件就无从得知了。
     *
     * @return 文件名集合；会话不存在或读不出来时返回空集合（空集合等于「不回收」，安全方向）。
     */
    static Set<String> attachmentNames(StorageManager sm, String conversationId) {
        Set<String> out = new HashSet<String>();
        if (conversationId == null || conversationId.length() == 0) return out;
        File convFile = new File(sm.getConversationsPath(), conversationId + ".json");
        if (!convFile.isFile()) return out;
        StorageRefs own = new StorageRefs();
        own.scanConversation(convFile);
        if (own.incomplete) {
            LogUtil.w(TAG, "attachmentNames: unreadable %s", conversationId);
            return out;
        }
        out.addAll(own.names());
        return out;
    }

    /**
     * 回收「只被已删除会话引用」的图片。后台线程执行，失败静默。
     *
     * <p>判定必须同时满足：在本会话的候选集里、在最新全量索引里查不到。
     * 多一层全量索引是为了防止「同一张图被另一个会话引用」时被误删。
     */
    static void recycleAsync(final StorageManager sm, final Set<String> candidates) {
        if (candidates == null || candidates.isEmpty()) return;
        new Thread(new Runnable() {
            public void run() {
                try {
                    StorageRefs all = scanAll(sm);
                    if (all.incomplete) {
                        LogUtil.w(TAG, "recycle skipped: index incomplete");
                        return;
                    }
                    File attDir = sm.getAttachmentsDir();
                    if (attDir == null) return;
                    int deleted = 0;
                    for (String name : candidates) {
                        if (all.has(name)) continue;
                        File f = new File(attDir, name);
                        if (!f.isFile()) continue;
                        if (f.delete()) deleted++;
                    }
                    LogUtil.i(TAG, "recycle: candidates=%d deleted=%d", candidates.size(), deleted);
                } catch (Throwable t) {
                    LogUtil.w(TAG, "recycle failed: %s", t.getMessage());
                }
            }
        }, "storage-recycle").start();
    }

    private static StorageRefs scanAll(StorageManager sm) {
        return StorageRefs.scan(new File(sm.getConversationsPath()));
    }

    private static void deleteTmpIn(File dir, long now, Result res) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (int i = 0; i < files.length; i++) {
            File f = files[i];
            if (f.isFile() && f.getName().endsWith(".tmp")
                    && now - f.lastModified() >= StorageScanner.HOUR) {
                delete(f, res);
            }
        }
    }

    private static void cleanCache(File dir, long now, int depth, Result res) {
        if (depth > 12) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        boolean camera = "camera".equals(dir.getName());
        long threshold = camera ? 7 * StorageScanner.DAY : StorageScanner.DAY;
        for (int i = 0; i < files.length; i++) {
            File f = files[i];
            if (f.isDirectory()) {
                cleanCache(f, now, depth + 1, res);
            } else if (f.isFile() && now - f.lastModified() >= threshold) {
                delete(f, res);
            }
        }
    }

    private static void delete(File f, Result res) {
        long len = f.length();
        try {
            if (f.delete()) {
                res.deleted++;
                res.bytes += len;
            } else {
                res.failed++;
                LogUtil.w(TAG, "delete failed: %s", f.getAbsolutePath());
            }
        } catch (Throwable t) {
            res.failed++;
            LogUtil.w(TAG, "delete threw: %s : %s", f.getAbsolutePath(), t.getMessage());
        }
    }
}
