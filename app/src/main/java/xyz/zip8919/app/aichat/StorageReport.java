package xyz.zip8919.app.aichat;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 一次扫描的结果。只读数据，不含任何 File 操作。
 *
 * <p>五类分开记 size 与 count：卡片上两行都要显示「68.2 MB · 42 个」，
 * 只给 size 用户不知道是「一个 68 MB 的怪物」还是「四百个会话」，
 * 而这两者的处理方式完全不同。
 */
final class StorageReport {

    /** 分类下标。顺序即界面顺序，不要重排。 */
    static final int CAT_CONVERSATION = 0;
    static final int CAT_ATTACHMENT = 1;
    static final int CAT_EXPORT = 2;
    static final int CAT_TEMP = 3;
    static final int CAT_OTHER = 4;
    static final int CAT_COUNT = 5;

    final long[] size = new long[CAT_COUNT];
    final int[] count = new int[CAT_COUNT];

    /** 图片附件里不被任何会话引用的部分。 */
    long orphanSize;
    int orphanCount;
    final List<File> orphans = new ArrayList<File>();

    /** 临时文件里可安全释放的部分（与 size[CAT_TEMP] 的差值只来自「正在写入」的短暂窗口）。 */
    long cleanableTempSize;

    /** App 数据所在分区的可用空间。 */
    long freeBytes;

    /** 外部存储不可用：数字只覆盖内部数据，界面必须明说，不能显示 0 让人以为数据没了。 */
    boolean externalMissing;

    /** 有会话没能解析，引用索引不可信 → 禁止清理无引用附件。 */
    boolean indexIncomplete;

    /**
     * 有图片、有会话，但一条引用都没解析出来。
     *
     * <p>与 {@link #indexIncomplete} 区别：这不是读失败，而是「读成功了但什么都没匹配上」。
     * 可能是用户真的把所有用图的会话删了，也可能是索引和实际存储格式对不上——
     * 后者会让全部历史图片被误判成无引用，界面必须给出警告而不是照常清理。
     */
    boolean indexEmpty;

    /** 无法读取的会话数。 */
    int unreadableConversations;

    /** 最近一次成功扫描的时刻。 */
    long scannedAt;

    /** 占空间最多的文件，已按体积降序截断到 {@link #TOP_LIMIT} 条。 */
    final List<Entry> top = new ArrayList<Entry>();

    /** 数据根目录下的普通文件（config.json / stats.json 等），用于「其他」的明细弹层。 */
    final List<Entry> roots = new ArrayList<Entry>();

    static final int TOP_LIMIT = 10;

    /** 一个待展示的文件条目。 */
    static final class Entry {
        final File file;
        final int category;
        final long bytes;

        Entry(File file, int category, long bytes) {
            this.file = file;
            this.category = category;
            this.bytes = bytes;
        }
    }

    long usedBytes() {
        long sum = 0;
        for (int i = 0; i < CAT_COUNT; i++) sum += size[i];
        return sum;
    }

    /**
     * 安全清理能释放的量：可清理临时文件 + 无引用图片。
     *
     * <p>索引不可信、或索引为空但图很多时只算临时文件：这两种情况下「无引用」的判定
     * 没有把握，一键清理的破坏半径太大。用户仍可以在图片页逐张确认后删除。
     */
    long cleanableBytes() {
        return cleanableTempSize + (indexIncomplete || indexEmpty ? 0 : orphanSize);
    }

    void add(int category, long bytes) {
        size[category] += bytes;
        count[category]++;
    }

    /** 加一个文件并顺手参与 Top 榜；榜按体积降序维护，满了就丢最小的。 */
    void addFile(File f, int category) {
        long bytes = f.length();
        add(category, bytes);
        pushTop(f, category, bytes);
    }

    /** 临时文件：除了归类与进榜，还要记住它是否已过「正在写入」窗口、能不能被安全清理。 */
    void addTempFile(File f, boolean cleanable) {
        long bytes = f.length();
        size[CAT_TEMP] += bytes;
        count[CAT_TEMP]++;
        if (cleanable) cleanableTempSize += bytes;
        pushTop(f, CAT_TEMP, bytes);
    }

    private void pushTop(File f, int category, long bytes) {
        if (bytes <= 0) return;
        int i = 0;
        while (i < top.size() && top.get(i).bytes >= bytes) i++;
        if (i >= TOP_LIMIT) return;
        top.add(i, new Entry(f, category, bytes));
        while (top.size() > TOP_LIMIT) top.remove(top.size() - 1);
    }

    static String categoryName(int category) {
        switch (category) {
            case CAT_CONVERSATION: return "对话";
            case CAT_ATTACHMENT: return "图片";
            case CAT_EXPORT: return "导出";
            case CAT_TEMP: return "临时";
            default: return "其他";
        }
    }

    /** 存储页专用的体积格式：到 GB，且小于 1 KB 时不显示小数（"0.0 KB" 没有意义）。 */
    static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024L * 1024L) {
            return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        }
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}
