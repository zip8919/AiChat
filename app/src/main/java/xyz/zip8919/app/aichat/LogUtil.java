package xyz.zip8919.app.aichat;

import android.util.Log;

/**
 * 统一日志工具：所有日志使用统一前缀 TAG "AiChat/<caller>"，便于 logcat 过滤。
 * 过滤方式：adb logcat -s "AiChat/*"  或  adb logcat | grep AiChat
 *
 * 日志级别约定：
 *   v - 极细粒度（流式分片的原始内容等）
 *   d - 常规流程（生命周期、点击、状态变化）
 *   i - 关键节点（请求开始/结束、保存成功）
 *   w - 可疑但可继续（空数据、回退逻辑、被忽略的异常）
 *   e - 错误（网络失败、解析失败、IO 失败）
 */
public final class LogUtil {

    /** 统一 TAG 前缀，所有日志都以它开头 */
    public static final String TAG_PREFIX = "AiChat";

    /** 单条日志最大长度（logcat 单条上限约 4000 字符，超长自动分片输出） */
    private static final int MAX_LINE = 3000;

    /** 长文本在日志中的预览长度，避免刷屏 */
    private static final int PREVIEW = 300;

    private LogUtil() {
    }

    // ---------- 级别开关 ----------
    // ENABLED：全局总开关（设置页可切换），关闭后所有日志静默，只剩一次 volatile 读开销
    // VERBOSE/DEBUG：细粒度开关，发布时可置 false 进一步减少日志量

    public static volatile boolean ENABLED = true;
    public static boolean VERBOSE_ENABLED = true;
    public static boolean DEBUG_ENABLED = true;

    // ---------- 基础输出 ----------

    public static void v(String tag, String msg) {
        if (ENABLED && VERBOSE_ENABLED) print(Log.VERBOSE, tag, msg, null);
    }

    public static void v(String tag, String msg, Throwable tr) {
        if (ENABLED && VERBOSE_ENABLED) print(Log.VERBOSE, tag, msg, tr);
    }

    public static void d(String tag, String msg) {
        if (ENABLED && DEBUG_ENABLED) print(Log.DEBUG, tag, msg, null);
    }

    public static void d(String tag, String msg, Throwable tr) {
        if (ENABLED && DEBUG_ENABLED) print(Log.DEBUG, tag, msg, tr);
    }

    public static void i(String tag, String msg) {
        if (ENABLED) print(Log.INFO, tag, msg, null);
    }

    public static void w(String tag, String msg) {
        if (ENABLED) print(Log.WARN, tag, msg, null);
    }

    public static void w(String tag, String msg, Throwable tr) {
        if (ENABLED) print(Log.WARN, tag, msg, tr);
    }

    public static void e(String tag, String msg) {
        if (ENABLED) print(Log.ERROR, tag, msg, null);
    }

    public static void e(String tag, String msg, Throwable tr) {
        if (ENABLED) print(Log.ERROR, tag, msg, tr);
    }

    /** 格式化输出，内部使用 String.format（注意参数为 null 时安全） */
    public static void v(String tag, String format, Object... args) {
        if (ENABLED && VERBOSE_ENABLED) print(Log.VERBOSE, tag, safeFormat(format, args), null);
    }

    public static void d(String tag, String format, Object... args) {
        if (ENABLED && DEBUG_ENABLED) print(Log.DEBUG, tag, safeFormat(format, args), null);
    }

    public static void i(String tag, String format, Object... args) {
        if (ENABLED) print(Log.INFO, tag, safeFormat(format, args), null);
    }

    public static void w(String tag, String format, Object... args) {
        if (ENABLED) print(Log.WARN, tag, safeFormat(format, args), null);
    }

    public static void e(String tag, String format, Object... args) {
        if (ENABLED) print(Log.ERROR, tag, safeFormat(format, args), null);
    }

    // ---------- 工具方法 ----------

    /** 把任意长文本截断成单行预览，用于打印正文/HTML/请求体 */
    public static String preview(String s) {
        if (s == null) return "<null>";
        FlatPrefix p = flattenPrefix(s, PREVIEW);
        if (!p.truncated) return "(" + s.length() + ") " + p.text;
        return "(" + s.length() + ") " + p.text + "...<truncated>";
    }

    /** 按指定长度截断 */
    public static String preview(String s, int max) {
        if (s == null) return "<null>";
        if (max < 0) {
            // 与现状一致：max<0 时 flat.length()>max 恒成立，flat.substring(0,max) 抛同一异常
            throw new StringIndexOutOfBoundsException(max);
        }
        FlatPrefix p = flattenPrefix(s, max);
        if (!p.truncated) return p.text;
        return p.text + "...";
    }

    /**
     * 单遍构建转义前缀，最多产出 maxFlatLen 个转义后字符，避免对整串做两遍 replace。
     * truncated=true 表示完整转义串长度超过 maxFlatLen，此时 text 恰为现状
     * flat.substring(0, maxFlatLen) 的内容；转义符跨边界时只保留落在边界内的部分
     * （如 "a\r"、max=2 时 flat="a\\r" 取前 2 字符得到 "a\\"）。
     *
     * 自测样例（PREVIEW=300）：
     *   preview("a\r\nb")   -> "(4) a\\r\\nb"
     *   preview(301个'a')   -> "(301) " + 300个'a' + "...<truncated>"
     *   preview("a\r", 2)   -> "a\\..."（flat 长度 3>2，截到 "a\\"）
     *   preview("a\n", 3)   -> "a\\n"（flat 长度恰为 3，不加 "..."）
     *   preview("a\nb", 3)  -> "a\\n..."
     *   preview("abc", 3)   -> "abc"；preview("abcd", 3) -> "abc..."
     *   preview("", 0)      -> ""；preview("x", 0) -> "..."
     */
    private static FlatPrefix flattenPrefix(String s, int maxFlatLen) {
        StringBuilder sb = new StringBuilder(Math.min(s.length(), maxFlatLen));
        int flatLen = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\r' || c == '\n') {
                if (flatLen + 2 > maxFlatLen) {
                    // 越过上限：fit 为 0 或 1，只保留落在上限内的部分（跨转义截断与 substring 一致）
                    int fit = maxFlatLen - flatLen;
                    if (fit > 0) sb.append('\\');
                    return new FlatPrefix(sb.toString(), true);
                }
                sb.append('\\').append(c == '\r' ? 'r' : 'n');
                flatLen += 2;
            } else {
                if (flatLen + 1 > maxFlatLen) {
                    return new FlatPrefix(sb.toString(), true);
                }
                sb.append(c);
                flatLen += 1;
            }
        }
        return new FlatPrefix(sb.toString(), false);
    }

    /** flattenPrefix 的返回值：转义后的前缀文本 + 是否发生截断 */
    private static final class FlatPrefix {
        final String text;
        final boolean truncated;

        FlatPrefix(String text, boolean truncated) {
            this.text = text;
            this.truncated = truncated;
        }
    }

    /** 打印当前线程名，便于区分主线程/网络线程 */
    public static String thread() {
        Thread t = Thread.currentThread();
        return t.getName() + "#" + t.getId();
    }

    /** 取堆栈顶层调用者类名，作为子 TAG */
    public static String tag() {
        StackTraceElement[] st = new Throwable().getStackTrace();
        if (st != null && st.length > 2) {
            String cn = st[2].getClassName();
            int dot = cn.lastIndexOf('.');
            return dot >= 0 ? cn.substring(dot + 1) : cn;
        }
        return "App";
    }

    // ---------- 内部实现 ----------

    private static void print(int level, String subTag, String msg, Throwable tr) {
        String tag = TAG_PREFIX + "/" + (subTag == null ? "App" : subTag);
        String body = msg == null ? "" : msg;
        if (tr != null) {
            body += " | " + Log.getStackTraceString(tr);
        }
        int len = body.length();
        if (len <= MAX_LINE) {
            Log.println(level, tag, body);
            return;
        }
        // 超长日志分片，每片带序号，避免被 logcat 截断丢失
        int chunks = (len + MAX_LINE - 1) / MAX_LINE;
        for (int i = 0; i < chunks; i++) {
            int end = Math.min(len, (i + 1) * MAX_LINE);
            Log.println(level, tag, "[" + (i + 1) + "/" + chunks + "] " + body.substring(i * MAX_LINE, end));
        }
    }

    private static String safeFormat(String format, Object... args) {
        if (format == null) return "";
        try {
            if (args == null || args.length == 0) return format;
            return String.format(format, args);
        } catch (Exception e) {
            // 参数不匹配时退化为拼接，保证日志本身不会抛异常
            StringBuilder sb = new StringBuilder(format);
            if (args != null) {
                sb.append(" | args=[");
                for (Object a : args) {
                    sb.append(String.valueOf(a)).append(", ");
                }
                sb.append("]");
            }
            return sb.toString();
        }
    }
}
