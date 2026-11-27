package xyz.zip8919.app.aichat;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 反馈附件里的「应用内文件」来源。
 *
 * <p>反馈页原本只有系统文件选择器：日志、会话、主题这些都在应用私有目录里，
 * 用户在文件管理器里根本找不到，等于没法提供。这里按分类把它们列出来，
 * 供反馈页直接勾选。
 *
 * <p><b>为什么配置文件要打码后再上传</b>：{@code config.json} 里是提供商的
 * API Key 与腾讯云 SecretKey，原样上传等于把账号交出去。用户勾选时看到的是
 * 「密钥已打码」的副本，磁盘上的原文件不做任何修改；打码规则由 {@link SecretMask} 负责。
 *
 * <p>枚举与副本生成都在后台线程调用（目录扫描、索引解析、文件重写），
 * 本类不碰界面，也不持有 Activity。
 */
final class FeedbackFileSource {

    private static final String TAG = "FeedbackFileSource";

    static final int CAT_CONVERSATION = 0;
    static final int CAT_FILE = 1;
    static final int CAT_IMAGE = 2;
    static final int CAT_LOG = 3;
    static final int CAT_THEME = 4;
    static final int CAT_CONFIG = 5;
    static final int CATEGORY_COUNT = 6;

    /** 打码副本的落点；提交后由系统缓存目录自然回收。 */
    static final String MASK_DIR = "feedback-masked";
    /** 解析配置的上限：正常配置只有几十 KB，超过这个尺寸说明文件异常，不做整读。 */
    private static final long MAX_MASK_BYTES = 2L * 1024 * 1024;

    private FeedbackFileSource() {
    }

    /** 一个可勾选的应用内文件。会话额外带标题与条数，主题额外带可预览标记。 */
    static final class Item {
        final int category;
        final File file;
        String title = "";
        String conversationId;
        /** 会话消息条数；-1 表示索引里没有，界面不显示条数。 */
        int messageCount = -1;
        long updatedAt;
        /** true 表示上传的是打码副本，而不是磁盘上的原文件。 */
        boolean masked;
        /** 行内补充说明的资源 id；0 表示没有。 */
        int noteRes;
        /** 主题文件能否预览（解析通过的才给入口）。 */
        boolean previewable;

        Item(int category, File file) {
            this.category = category;
            this.file = file;
            this.updatedAt = file.lastModified();
        }

        String key() {
            return file.getAbsolutePath();
        }
    }

    static String categoryName(int category) {
        switch (category) {
            case CAT_CONVERSATION:
                return Strings.get(R.string.feedbackfilepickeractivity_001);
            case CAT_FILE:
                return Strings.get(R.string.feedbackfilepickeractivity_002);
            case CAT_IMAGE:
                return Strings.get(R.string.feedbackfilepickeractivity_003);
            case CAT_LOG:
                return Strings.get(R.string.feedbackfilepickeractivity_004);
            case CAT_THEME:
                return Strings.get(R.string.feedbackfilepickeractivity_005);
            default:
                return Strings.get(R.string.feedbackfilepickeractivity_006);
        }
    }

    static String categoryHint(int category) {
        switch (category) {
            case CAT_CONVERSATION:
                return Strings.get(R.string.feedbackfilepickeractivity_007);
            case CAT_FILE:
                return Strings.get(R.string.feedbackfilepickeractivity_008);
            case CAT_IMAGE:
                return Strings.get(R.string.feedbackfilepickeractivity_009);
            case CAT_LOG:
                return Strings.get(R.string.feedbackfilepickeractivity_010);
            case CAT_THEME:
                return Strings.get(R.string.feedbackfilepickeractivity_011);
            default:
                return Strings.get(R.string.feedbackfilepickeractivity_012);
        }
    }

    static String categoryEmpty(int category) {
        switch (category) {
            case CAT_CONVERSATION:
                return Strings.get(R.string.feedbackfilepickeractivity_013);
            case CAT_FILE:
                return Strings.get(R.string.feedbackfilepickeractivity_014);
            case CAT_IMAGE:
                return Strings.get(R.string.feedbackfilepickeractivity_015);
            case CAT_LOG:
                return Strings.get(R.string.feedbackfilepickeractivity_016);
            case CAT_THEME:
                return Strings.get(R.string.feedbackfilepickeractivity_017);
            default:
                return Strings.get(R.string.feedbackfilepickeractivity_018);
        }
    }

    /** 列出一个分类下的文件。会话按最后更新倒序，其余按修改时间倒序。 */
    static List<Item> list(Context ctx, int category) {
        List<Item> out = new ArrayList<Item>();
        if (ctx == null) return out;
        try {
            switch (category) {
                case CAT_CONVERSATION:
                    listConversations(ctx, out);
                    break;
                case CAT_FILE:
                    listFiles(new File(StorageManager.getInstance(ctx).getExportsDir()), out, CAT_FILE);
                    break;
                case CAT_IMAGE:
                    listImages(ctx, out);
                    break;
                case CAT_LOG:
                    listLogs(ctx, out);
                    break;
                case CAT_THEME:
                    listThemes(ctx, out);
                    break;
                case CAT_CONFIG:
                    listConfigs(ctx, out);
                    break;
                default:
                    break;
            }
        } catch (Throwable t) {
            LogUtil.w(TAG, "list cat=%d failed: %s", category, t.getMessage());
        }
        return out;
    }

    // ------------------------------------------------------------ 各分类

    /**
     * 会话：标题与条数取索引，索引缺失时退回文件名，不为了一个列表去解析每个 JSON。
     * 条数未知显示「—」，比显示 0 诚实。
     */
    private static void listConversations(Context ctx, List<Item> out) {
        File dir = new File(StorageManager.getInstance(ctx).getConversationsPath());
        File[] files = dir.listFiles();
        if (files == null) return;
        ConversationIndex index = ConversationIndex.load(dir);
        for (int i = 0; i < files.length; i++) {
            File f = files[i];
            String name = f.getName();
            if (!f.isFile() || !name.endsWith(".json")) continue;   // .bak / .tmp / .idx 一并排除
            String id = name.substring(0, name.length() - 5);
            if (!StorageManager.isValidConversationId(id)) continue;
            Item it = new Item(CAT_CONVERSATION, f);
            it.conversationId = id;
            ConversationIndex.Entry e = index.get(id);
            if (e != null) {
                it.title = ConversationManager.normalizeTitle(e.title);
                it.messageCount = e.messageCount;
                // 索引里的时间与文件 mtime 可能差几秒，界面上用索引值，与历史列表一致
                if (e.updatedAt > 0) it.updatedAt = e.updatedAt;
            } else {
                it.title = id;
            }
            out.add(it);
        }
        Collections.sort(out, new Comparator<Item>() {
            public int compare(Item a, Item b) {
                if (b.updatedAt > a.updatedAt) return 1;
                if (b.updatedAt < a.updatedAt) return -1;
                return a.title.compareToIgnoreCase(b.title);
            }
        });
    }

    private static void listFiles(File dir, List<Item> out, int category) {
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) return;
        for (int i = 0; i < files.length; i++) {
            File f = files[i];
            if (!f.isFile() || f.getName().endsWith(".tmp")) continue;
            out.add(new Item(category, f));
        }
        sortByMtime(out);
    }

    private static void listImages(Context ctx, List<Item> out) {
        File dir = StorageManager.getInstance(ctx).getAttachmentsDir();
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) return;
        for (int i = 0; i < files.length; i++) {
            File f = files[i];
            if (!f.isFile() || !FileKinds.looksLikeImage(f.getName(), null)) continue;
            out.add(new Item(CAT_IMAGE, f));
        }
        sortByMtime(out);
    }

    /**
     * 日志：崩溃记录 + 最近几次请求记录。
     * 请求记录里的鉴权头在落盘时已遮蔽（{@link RequestDiag}），这里不再二次处理。
     */
    private static void listLogs(Context ctx, List<Item> out) {
        File crash = CrashCatcher.crashLogFile(ctx);
        if (crash != null && crash.isFile() && crash.length() > 0) {
            Item it = new Item(CAT_LOG, crash);
            it.noteRes = R.string.feedbackfilepickeractivity_019;
            out.add(it);
        }
        File reqDir = RequestDiag.logDir(ctx);
        File[] logs = reqDir == null ? null : reqDir.listFiles();
        if (logs != null) {
            for (int i = 0; i < logs.length; i++) {
                File f = logs[i];
                if (!f.isFile() || !f.getName().endsWith(".log")) continue;
                Item it = new Item(CAT_LOG, f);
                it.noteRes = R.string.feedbackfilepickeractivity_020;
                out.add(it);
            }
        }
        sortByMtime(out);
    }

    private static void listThemes(Context ctx, List<Item> out) {
        File dir = new File(StorageManager.getInstance(ctx).getThemesDir());
        File[] files = dir.listFiles();
        if (files == null) return;
        for (int i = 0; i < files.length; i++) {
            File f = files[i];
            if (!f.isFile() || !f.getName().endsWith(".actheme")) continue;
            Item it = new Item(CAT_THEME, f);
            it.previewable = true;
            out.add(it);
        }
        sortByMtime(out);
    }

    /**
     * 配置：只列数据根目录下的几个 JSON，顺序固定。
     * 只有 config.json 含密钥，需要打码副本；其余文件按原样上传，说明里写清内容是什么。
     */
    private static void listConfigs(Context ctx, List<Item> out) {
        File base = new File(StorageManager.getInstance(ctx).getBasePath());
        addConfig(base, "config.json", out, R.string.feedbackfilepickeractivity_021, true);
        addConfig(base, "appearance.json", out, R.string.feedbackfilepickeractivity_022, false);
        addConfig(base, "fonts.json", out, R.string.feedbackfilepickeractivity_023, false);
        addConfig(base, "stats.json", out, R.string.feedbackfilepickeractivity_024, false);
    }

    private static void addConfig(File base, String name, List<Item> out, int noteRes, boolean masked) {
        File f = new File(base, name);
        if (!f.isFile()) return;
        Item it = new Item(CAT_CONFIG, f);
        it.noteRes = noteRes;
        it.masked = masked;
        out.add(it);
    }

    private static void sortByMtime(List<Item> out) {
        Collections.sort(out, new Comparator<Item>() {
            public int compare(Item a, Item b) {
                if (b.updatedAt > a.updatedAt) return 1;
                if (b.updatedAt < a.updatedAt) return -1;
                return a.file.getName().compareToIgnoreCase(b.file.getName());
            }
        });
    }

    // ------------------------------------------------------------ 打码副本

    /**
     * 生成打码副本。原文件不读第二次、也不改写；副本按次落在缓存目录，
     * 文件名保持原样，上传后服务端看到的仍是 {@code config.json}。
     *
     * @return 副本文件；读取或写入失败返回 null，由调用方提示跳过。
     */
    static File maskedCopy(Context ctx, File src) {
        if (ctx == null || src == null || !src.isFile()) return null;
        String text = readText(ctx, src);
        if (text == null) return null;
        String masked = SecretMask.maskText(text);
        File root = new File(ctx.getCacheDir(), MASK_DIR);
        File dir = new File(root, System.currentTimeMillis() + "-" + Math.abs(src.getName().hashCode()));
        if (!dir.isDirectory() && !dir.mkdirs()) {
            LogUtil.w(TAG, "maskedCopy: cannot mkdir %s", dir.getAbsolutePath());
            return null;
        }
        File out = new File(dir, src.getName());
        File tmp = new File(dir, src.getName() + ".tmp");
        OutputStream os = null;
        try {
            os = new FileOutputStream(tmp);
            os.write(masked.getBytes("UTF-8"));
            os.flush();
            os.close();
            os = null;
            if (!tmp.renameTo(out)) {
                LogUtil.w(TAG, "maskedCopy: rename failed %s", out.getAbsolutePath());
                return null;
            }
            LogUtil.i(TAG, "maskedCopy: %s -> %d bytes", src.getName(), out.length());
            return out;
        } catch (Exception e) {
            LogUtil.w(TAG, "maskedCopy failed: %s : %s", src.getName(), e.getMessage());
            return null;
        } finally {
            if (os != null) {
                try {
                    os.close();
                } catch (Exception ignored) {
                    // 关闭失败不影响结果，文件已写坏时下面会删掉
                }
            }
            if (tmp.exists()) tmp.delete();
        }
    }

    private static String readText(Context ctx, File f) {
        if (f.length() <= 0 || f.length() > MAX_MASK_BYTES) {
            LogUtil.w(TAG, "readText skip: %s (%d bytes)", f.getName(), f.length());
            return null;
        }
        String text = StorageManager.getInstance(ctx).readFile(f.getAbsolutePath());
        return text == null ? "" : text;
    }
}
