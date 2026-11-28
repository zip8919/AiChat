package xyz.zip8919.app.aichat;

/** 离线编译用的最小桩：只为让 SseRound/ToolCalls/UsageView 脱离 Android 编译通过。 */
final class LogUtil {
    static final boolean ENABLED = false;
    static void v(String t, String f, Object... a) {}
    static void d(String t, String f, Object... a) {}
    static void i(String t, String f, Object... a) {}
    static void w(String t, String f, Object... a) {}
    static void e(String t, String f, Object... a) {}
    static String preview(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}

final class Strings {
    static String get(int id) { return "s" + id; }
}

final class TextKit {
    static String shorten(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}

final class ApiClient {
    static String sanitizeJson(String s) { return s; }
}

final class R {
    static final class string {
        static final int sseround_001 = 1, sseround_002 = 2, sseround_003 = 3, sseround_004 = 4,
                sseround_005 = 5, sseround_006 = 6, sseround_007 = 7, sseround_008 = 8,
                sseround_009 = 9, sseround_010 = 10;
        static final int toolcalls_001 = 11, toolcalls_002 = 12, toolcalls_003 = 13,
                toolcalls_004 = 14, toolcalls_005 = 15, toolcalls_006 = 16, toolcalls_007 = 17,
                toolcalls_008 = 18, toolcalls_009 = 19, toolcalls_010 = 20, toolcalls_011 = 21,
                toolcalls_012 = 22, toolcalls_013 = 23, toolcalls_014 = 24, toolcalls_015 = 25,
                toolcalls_016 = 26;
    }
}

final class WebSearchTool {
    static final String TRACE_START = "[tool]";
    static final String TRACE_END = "[/tool]";
    static final String TOOL_TIME = "get_time";
    static final String TOOL_SEARCH = "web_search";
    static final String TOOL_FETCH = "web_fetch";
}

final class HttpRequestTool {
    static final String TOOL_HTTP = "http_request";
}
