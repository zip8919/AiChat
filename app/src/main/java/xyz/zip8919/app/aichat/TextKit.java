package xyz.zip8919.app.aichat;

/**
 * 零依赖的文本处理。抽出来是为了两件事：让 MainActivity 少几百行，
 * 以及这些规则终于可以被单测覆盖（原来混在 4000 行的 Activity 里根本没法测）。
 */
final class TextKit {

    private TextKit() {
    }

    /** 单行化 + 截断，用于状态提示、标题这类要求短文本的地方。 */
    static String shorten(String text, int max) {
        if (text == null) return "";
        String one = text.replace('\n', ' ').replace('\r', ' ');
        if (one.length() <= max) return one;
        return one.substring(0, max) + "...";
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
        if (one.length() > 15) one = one.substring(0, 15);
        return one;
    }
}
