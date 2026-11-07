package xyz.zip8919.app.aichat;

import android.content.Context;

/**
 * 运行期生效的各类上限与配额。
 *
 * <p>这些值原来全是各文件里的 {@code static final} 常量：改一个上限要改源码、重新打包。
 * 现在统一由设置页写入 SharedPreferences，进程启动/设置保存时刷进这里的静态字段，
 * 供后台线程（请求、工具、附件解析）直接读取——不用把 {@link Context} 一路传下去。
 *
 * <p><b>为什么用 volatile 而不是每次都读 prefs</b>：{@code SharedPreferences.getString}
 * 会走一次内存映射与锁，工具调用是在网络线程上按块读取的，读上限这种热路径上
 * 不适合反复走 IPC 式的取配置。这里只在启动与保存时刷新一次。
 *
 * <p>默认值与历史常量保持一致，升级后行为不变。
 */
final class Limits {

    private Limits() {
    }

    /** 附件正文送模上限（字符）。0 表示不截断。 */
    static volatile int attachMaxChars = 262144;
    /** 一次待发送附件合计字符上限。 */
    static volatile int attachTotalChars = 20 * 1000 * 1000;
    /** 单次最多附加几个文件。 */
    static volatile int maxAttachments = 5;
    /** 单张图片字节上限。 */
    static volatile int imageMaxBytes = 20 * 1024 * 1024;
    /** 单个工具结果的最大字节数（超出截断）。 */
    static volatile int toolMaxBytes = 32 * 1024;
    /** HTTP 工具的连接/读取超时。 */
    static volatile int httpTimeoutMs = 30000;
    /** 一次搜索最多返回多少条。 */
    static volatile int searchMaxCount = 10;
    /** 单条搜索摘要的最大字符数。 */
    static volatile int searchSummaryChars = 1200;
    /** 一轮里所有搜索摘要的合计字符预算。 */
    static volatile int searchTotalSummaryChars = 6000;
    /** 发给模型前，图片等比缩放的最大边。 */
    static volatile int visionMaxPx = 1600;
    /** 单轮最多带几张图给模型。 */
    static volatile int visionMaxImages = 3;

    /**
     * 从设置里刷新全部上限。幂等，可在任意线程调用。
     *
     * <p>读不到或类型异常时保留默认值——上限这种东西宁可保守，不要因为一次
     * 解析失败变成 0（0 字节截断会把所有工具结果清空）。
     */
    static void reload(Context ctx) {
        if (ctx == null) return;
        android.content.SharedPreferences p =
                ctx.getSharedPreferences("aichat_settings", 0);
        attachMaxChars = clamp(readInt(p, SettingsActivity.KEY_ATTACH_MAX_CHARS,
                262144), 0, 100000000);
        // 0 是合法值（不截断），但小于 1024 的非 0 值没有意义，回落到默认
        if (attachMaxChars != 0 && attachMaxChars < 1024) attachMaxChars = 262144;

        attachTotalChars = clamp(readInt(p, SettingsActivity.KEY_ATTACH_TOTAL_CHARS,
                2000), 100, 100000) * 10000;
        maxAttachments = clamp(readInt(p, SettingsActivity.KEY_MAX_ATTACHMENTS, 5), 1, 20);
        int mb = clamp(readInt(p, SettingsActivity.KEY_IMAGE_MAX_MB, 20), 1, 100);
        imageMaxBytes = mb * 1024 * 1024;

        int kb = clamp(readInt(p, SettingsActivity.KEY_TOOL_MAX_KB, 32), 4, 1024);
        toolMaxBytes = kb * 1024;
        httpTimeoutMs = clamp(readInt(p, SettingsActivity.KEY_HTTP_TIMEOUT_S, 30), 5, 120) * 1000;

        searchMaxCount = clamp(readInt(p, SettingsActivity.KEY_SEARCH_COUNT, 10), 1, 20);
        searchSummaryChars = clamp(readInt(p, SettingsActivity.KEY_SEARCH_SUMMARY_CHARS, 1200),
                200, 8000);
        // 合计预算至少留三倍单条，否则第二个结果开始就全是省略号
        searchTotalSummaryChars = Math.max(searchSummaryChars * 3, 6000);

        visionMaxPx = clamp(readInt(p, SettingsActivity.KEY_VISION_MAX_PX, 1600), 512, 4096);
        visionMaxImages = clamp(readInt(p, SettingsActivity.KEY_VISION_MAX_IMAGES, 3), 1, 10);

        LogUtil.i("Limits", "reload: attach=%d chars, total=%d, files=%d, img=%dMB, "
                        + "tool=%dKB, http=%dms, search=%dx%d, vision=%dpx x%d",
                attachMaxChars, attachTotalChars, maxAttachments, mb,
                kb, httpTimeoutMs, searchMaxCount, searchSummaryChars,
                visionMaxPx, visionMaxImages);
    }

    private static int readInt(android.content.SharedPreferences p, String key, int def) {
        try {
            return p.getInt(key, def);
        } catch (Exception e) {
            // 老版本可能把键存成 String，getInt 会抛 ClassCastException
            try {
                return Integer.parseInt(p.getString(key, String.valueOf(def)));
            } catch (Exception ignored) {
                return def;
            }
        }
    }

    static int clamp(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }
}
