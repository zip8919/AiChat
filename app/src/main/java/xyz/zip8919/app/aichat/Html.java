package xyz.zip8919.app.aichat;

/**
 * HTML 转义。原先 {@link MessageHtmlRenderer} 与 {@link CodeHighlighter} 各写了一份，
 * 一旦哪天只修了一处，代码块和正文的转义行为就会分叉。
 */
final class Html {

    private Html() {
    }

    /** 文本节点转义：&amp; &lt; &gt; &quot;。 */
    static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
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
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
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
