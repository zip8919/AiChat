package xyz.zip8919.app.aichat;

import android.content.Context;
import android.os.StatFs;

import java.io.File;

/**
 * 存储扫描：把五类数据的体积、条数、可清理量算出来。
 *
 * <p>只在后台线程调用。整个扫描是串行的：外部存储上的并发 {@code listFiles}
 * 在低端机上比串行还慢，而且这里的瓶颈是磁盘 IO 不是 CPU。
 *
 * <p>时间阈值集中在这里，别的地方不要再写一套：
 * <ul>
 *   <li>导入残留 {@code tmp-*}：24 小时（与 {@code AttachmentFiles.sweepTempFiles} 一致）</li>
 *   <li>拍照/裁剪缓存：7 天（原图要么已进附件目录，要么用户不再需要）</li>
 *   <li>其他缓存与 {@code *.tmp}：1 天 / 1 小时</li>
 * </ul>
 *
 * <p>附件本身<b>没有</b>保护期：导入是同步完成的（文件写完立刻写消息），
 * 曾经那层「1 小时保护期」只会让刚删掉的会话图片赖在「使用中」一小时。
 */
final class StorageScanner {

    private static final String TAG = "StorageScanner";

    static final long HOUR = 60L * 60L * 1000L;
    static final long DAY = 24L * HOUR;

    /** 递归深度上限。防软链接成环把扫描卡死，正常目录不会超过 4 层。 */
    private static final int MAX_DEPTH = 12;

    private StorageScanner() {
    }

    static StorageReport scan(Context ctx) {
        long started = System.currentTimeMillis();
        StorageReport r = new StorageReport();
        long now = started;

        StorageManager sm = StorageManager.getInstance(ctx);
        String basePath = sm.getBasePath();
        File base = basePath == null ? null : new File(basePath);

        if (base == null || !base.isDirectory() || !base.canRead()) {
            // 外部存储被拔出/未挂载：数字只覆盖内部数据，界面必须提示而不是显示 0
            r.externalMissing = !sm.isUsingInternalStorage();
            LogUtil.w(TAG, "scan: base unreadable %s externalMissing=%s", basePath, r.externalMissing);
        }

        // ---- 1. 会话 + 引用索引 ----
        File convDir = new File(sm.getConversationsPath());
        File[] convFiles = convDir.listFiles();
        if (convFiles != null) {
            for (int i = 0; i < convFiles.length; i++) {
                File f = convFiles[i];
                if (!f.isFile()) continue;
                String name = f.getName();
                if (name.endsWith(".json")) {
                    r.addFile(f, StorageReport.CAT_CONVERSATION);
                } else if (name.endsWith(".tmp")) {
                    r.addTempFile(f, now - f.lastModified() >= HOUR);
                }
            }
        }
        // 复用上面已经列好的目录项：再调一次 StorageRefs.scan(convDir) 会重复
        // 一次 listFiles（每个目录项一次 stat），而这里紧接着就要用同一批文件。
        // convFiles == null 说明目录列不出来，仍走原路径让它标 incomplete。
        StorageRefs refs = convFiles != null
                ? StorageRefs.scanListed(convFiles)
                : StorageRefs.scan(convDir);
        r.indexIncomplete = refs.incomplete;
        r.unreadableConversations = refs.unreadable;
        LogUtil.i(TAG, "scan: %d conversations, refs=%d matched=%d incomplete=%s sample=%s",
                r.count[StorageReport.CAT_CONVERSATION], refs.size(), refs.matched,
                refs.incomplete, refs.sample);

        // ---- 2. 图片附件 ----
        File attDir = sm.getAttachmentsDir();
        File[] attFiles = attDir == null ? null : attDir.listFiles();
        if (attFiles != null) {
            for (int i = 0; i < attFiles.length; i++) {
                File f = attFiles[i];
                if (!f.isFile()) continue;
                if (f.getName().startsWith("tmp-")) {
                    r.addTempFile(f, now - f.lastModified() >= DAY);
                    continue;
                }
                r.addFile(f, StorageReport.CAT_ATTACHMENT);
                // 索引不可信时一律按「使用中」处理：宁可不清理，不可错删
                if (r.indexIncomplete) continue;
                // 草稿里的图（选好了还没发）也没有引用，但删掉就是用户发出去时静默丢图
                if (AttachmentFiles.isPendingName(f.getName())) continue;
                if (!refs.has(f.getName())) {
                    r.orphanSize += f.length();
                    r.orphanCount++;
                    r.orphans.add(f);
                }
            }
        }

        // 有图、有会话，却一条引用都没解析出来：要么用户把所有用图的会话都删了，
        // 要么索引和会话里的实际写法对不上。两种情况的界面处理必须分开——
        // 前者是正常状态，后者要明确警告，否则用户会把历史图片当垃圾清掉。
        // 「索引为空」只有在「确实扫到了会话文件」时才可疑（可能是格式对不上，要警告）。
        // 会话全被删光时索引为空是**确定事实**，这时候若仍判 indexEmpty，
        // 图片页会永久禁止删除、「一键清理」也直接跳过——用户看着「无引用 N 张」
        // 却一张都删不掉，而那个目录普通用户根本进不去，等于死路。
        r.indexEmpty = !r.indexIncomplete && refs.size() == 0
                && refs.scanned > 0
                && r.count[StorageReport.CAT_ATTACHMENT] > 0;
        if (r.indexEmpty) {
            LogUtil.w(TAG, "scan: %d images but zero refs from %d conversations — "
                            + "either all orphans or format mismatch",
                    r.count[StorageReport.CAT_ATTACHMENT],
                    r.count[StorageReport.CAT_CONVERSATION]);
        }

        // ---- 3. 已保存文件 ----
        File exportDir = new File(sm.getExportsDir());
        File[] exportFiles = exportDir.listFiles();
        if (exportFiles != null) {
            for (int i = 0; i < exportFiles.length; i++) {
                File f = exportFiles[i];
                if (!f.isFile()) continue;
                if (f.getName().endsWith(".tmp")) {
                    r.addTempFile(f, now - f.lastModified() >= HOUR);
                } else {
                    r.addFile(f, StorageReport.CAT_EXPORT);
                }
            }
        }

        // ---- 4. 临时文件（缓存目录） ----
        File cacheDir = ctx.getCacheDir();
        if (cacheDir != null) {
            scanCache(r, cacheDir, now, 0);
        }

        // ---- 5. 其他（根目录下的 config.json / stats.json 等） ----
        if (base != null && base.isDirectory()) {
            File[] roots = base.listFiles();
            if (roots != null) {
                for (int i = 0; i < roots.length; i++) {
                    File f = roots[i];
                    if (!f.isFile()) continue;
                    r.addFile(f, StorageReport.CAT_OTHER);
                    r.roots.add(new StorageReport.Entry(f, StorageReport.CAT_OTHER, f.length()));
                }
            }
        }

        // ---- 6. 可用空间 ----
        if (basePath != null) {
            try {
                StatFs fs = new StatFs(basePath);
                r.freeBytes = fs.getAvailableBlocksLong() * fs.getBlockSizeLong();
            } catch (Throwable t) {
                LogUtil.w(TAG, "scan: StatFs failed: %s", t.getMessage());
            }
        }

        r.scannedAt = System.currentTimeMillis();
        LogUtil.i(TAG, "scan done in %d ms: used=%d free=%d cleanable=%d",
                r.scannedAt - started, r.usedBytes(), r.freeBytes, r.cleanableBytes());
        return r;
    }

    /**
     * 递归统计缓存目录。
     *
     * <p>camera 子目录放的是拍照/裁剪中间产物（原图已经导入附件目录），
     * 给 7 天缓冲；其余缓存给 1 天。刚写入的文件（< 1 天）不参与清理，
     * 避免把正在用的 WebView 缓存或正在导出的文件删掉。
     */
    private static void scanCache(StorageReport r, File dir, long now, int depth) {
        if (depth > MAX_DEPTH) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        boolean camera = "camera".equals(dir.getName());
        long threshold = camera ? 7 * DAY : DAY;
        for (int i = 0; i < files.length; i++) {
            File f = files[i];
            if (f.isDirectory()) {
                scanCache(r, f, now, depth + 1);
            } else if (f.isFile()) {
                r.addTempFile(f, now - f.lastModified() >= threshold);
            }
        }
    }
}
