package xyz.zip8919.app.aichat;

/**
 * HTML 转义。原先 {@link MessageHtmlRenderer} 与 {@link CodeHighlighter} 各写了一份，
 * 一旦哪天只修了一处，代码块和正文的转义行为就会分叉。
 */
final class Html {

    private Html() {
    }

    /**
     * 文本节点转义：&amp; &lt; &gt; &quot;。
     *
     * <p>先扫一遍找第一个需要转义的字符，没有就原样返回（String 不可变，直接复用）。
     * 代码高亮里每个标识符、每个纯文本段都要过这里，一个 4KB 代码块约 1000 次调用，
     * 其中绝大多数不含特殊字符——原来每次都要分配一个 StringBuilder + 一个结果串。
     */
    static String esc(String s) {
        if (s == null) return "";
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '&' || c == '<' || c == '>' || c == '"') break;
            i++;
        }
        if (i == n) return s;
        StringBuilder sb = new StringBuilder(n + 16);
        sb.append(s, 0, i);
        for (; i < n; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 单引号包裹的属性值也要转义 {@code '}，否则属性会被提前闭合。 */
    static String escAttr(String s) {
        if (s == null) return "";
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '&' || c == '<' || c == '>' || c == '"' || c == '\'') break;
            i++;
        }
        if (i == n) return s;
        StringBuilder sb = new StringBuilder(n + 16);
        sb.append(s, 0, i);
        for (; i < n; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&#39;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }
}
