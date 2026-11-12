package xyz.zip8919.app.aichat;

/**
 * 零依赖的文本处理。抽出来是为了两件事：让 MainActivity 少几百行，
 * 以及这些规则终于可以被单测覆盖（原来混在 4000 行的 Activity 里根本没法测）。
 */
final class TextKit {

    private TextKit() {
    }

    /** 按 UTF-16 code unit 截断，若切口落在代理对中间就退一格，避免留下孤立高代理。 */
    static String cut(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        int n = max;
        if (n > 0 && Character.isHighSurrogate(s.charAt(n - 1))) n--;
        return s.substring(0, n);
    }

    /** 单行化 + 截断，用于状态提示、标题这类要求短文本的地方。 */
    static String shorten(String text, int max) {
        if (text == null) return "";
        String one = text.replace('\n', ' ').replace('\r', ' ');
        if (one.length() <= max) return one;
        int cut = max;
        if (cut > 0 && Character.isHighSurrogate(one.charAt(cut - 1))) cut--;
        return one.substring(0, cut) + "...";
    }

    /**
     * 去掉展示标记后的纯文本，供关闭实时渲染时的流式直出使用。
     *
     * <p>未闭合的 {@code [tool]} 一律保留原文：流式中间态本来就会遇到半截标记，
     * 此前直接截断到标记位置，会把后面的正文整段吞掉。
     */
    static String plainForStream(String text) {
        if (text == null) return "";
        String s = text.replace("[thinking]", "").replace("[/thinking]", "");
        int guard = 0;
        while (guard++ < 64) {
            int i = s.indexOf("[tool]");
            if (i < 0) break;
            int j = s.indexOf("[/tool]", i);
            if (j < 0) break;
            s = s.substring(0, i) + s.substring(j + 7);
        }
        return s;
    }

    /** 去掉模型自带的 {@code <think>} 段（本地已用 [thinking] 标记，这里是双保险）。 */
    static String stripThinkTags(String s) {
        if (s == null) return "";
        String out = s.replaceAll("(?s)<think>.*?</think>", "");
        int open = out.indexOf("<think>");
        if (open != -1) out = out.substring(0, open);
        return out;
    }

    /** 标题兜底：取前 15 个字符，换行与连续空白合并成单空格。 */
    static String fallbackTitle(String text) {
        if (text == null) return "";
        String one = text.replace('\r', ' ').replace('\n', ' ');
        one = one.replaceAll("\\s+", " ").trim();
        if (one.length() > 15) {
            int cut = 15;
            if (Character.isHighSurrogate(one.charAt(cut - 1))) cut--;
            one = one.substring(0, cut);
        }
        return one;
    }

    /**
     * 给 API 18 的 {@code loadUrl("javascript:...")} 做转义。
     *
     * <p>这条路径会先按 URL 解析再交给 JS 引擎：{@code #} 之后被当 fragment 丢掉、
     * {@code %} 触发一次解码、{@code +} 变成空格、空格被规范化。聊天内容里
     * {@code #} 和 {@code %} 太常见（Markdown 标题、代码注释、百分比），
     * 不处理就会「只渲染半截」甚至整段脚本失效。
     *
     * <p>只动这几个会改变语义的字符，其余原样保留；WebView 会把 {@code %XX}
     * 还原后再交给 JS 引擎，所以字符串内容不会被打乱。
     */
    static String forJsUrl(String js) {
        if (js == null) return "";
        return js.replace("%", "%25")
                .replace("#", "%23")
                .replace("+", "%2B")
                .replace(" ", "%20");
    }
}
