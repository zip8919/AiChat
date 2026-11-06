package xyz.zip8919.app.aichat;

/**
 * 「查看文件」里的源码页 HTML。
 *
 * <p>单独成类的原因：这段字符串决定了看到的三件事——有没有颜色、会不会折行、
 * 截断提示写没写对——全是纯字符串逻辑，放在 ViewerController 里就只能靠真机眼看。
 * 现在它可以在 JVM 单测里断言。
 *
 * <p>两个上限都是防 ANR 的，不是产品限制：
 * <ul>
 *   <li>WebView 排版十万级字符的 {@code <pre>} 在 API 18 上要好几秒；</li>
 *   <li>高亮是逐字符 + 正则扫描，常数比排版小但也不是零。</li>
 * </ul>
 * 超出的部分直接不显示，正文本身仍在消息里，也照样发给模型。
 */
final class SourcePage {

    /** 一屏最多显示多少字符。 */
    static final int MAX_DISPLAY_CHARS = 400 * 1024;

    /** 超过这个长度就不高亮，只保证能滑。 */
    static final int MAX_HIGHLIGHT_CHARS = 120 * 1024;

    private SourcePage() {
    }

    /**
     * @param lang      高亮语言，空串表示纯文本（折行显示）
     * @param code      完整正文；内部按上限截断
     * @param rotateCss 旋转/尺寸补偿 CSS，可空
     * @param toolbarInset 顶部工具栏占位像素
     */
    static String build(String lang, String code, String rotateCss, int toolbarInset) {
        String safe = code == null ? "" : code;
        boolean truncated = safe.length() > MAX_DISPLAY_CHARS;
        String shown = truncated ? safe.substring(0, MAX_DISPLAY_CHARS) : safe;
        boolean highlight = lang != null && lang.length() > 0 && shown.length() <= MAX_HIGHLIGHT_CHARS;
        String body = highlight ? CodeHighlighter.highlight(shown, lang) : Html.esc(shown);
        // 纯文本（日志、md）折行；代码保持原样横向滚动，否则缩进全乱
        boolean wrap = lang == null || lang.length() == 0;

        StringBuilder sb = new StringBuilder(body.length() + 1024);
        sb.append("<!DOCTYPE html><html><head>")
          .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0,user-scalable=yes\">")
          .append("<style>")
          .append("*{margin:0;padding:0;}")
          .append("html,body{width:100%;min-height:100%;background:#FFFFFF;")
          .append("-webkit-transform-origin:0 0;transform-origin:0 0;}")
          .append("body{padding:").append(toolbarInset).append("px 10px 30px 10px;")
          .append("-webkit-box-sizing:border-box;box-sizing:border-box;}")
          .append(".doc-wrap{").append(rotateCss == null ? "" : rotateCss).append("}")
          .append(".banner{background:#fff8e1;border:1px solid #ffe082;border-radius:6px;")
          .append("padding:6px 8px;font-size:12px;color:#8d6e63;line-height:1.45;margin-bottom:8px;}")
          .append("pre{margin:0;font-family:monospace;font-size:13px;line-height:1.45;color:#24292e;")
          .append(wrap ? "white-space:pre-wrap;word-wrap:break-word;" : "white-space:pre;")
          .append("}")
          .append(".tk-kw{color:#d73a49;font-weight:bold;}")
          .append(".tk-str{color:#032f62;}")
          .append(".tk-cmt{color:#6a737d;font-style:italic;}")
          .append(".tk-num{color:#005cc5;}")
          .append(".tk-type{color:#6f42c1;}")
          .append(".tk-fn{color:#6f42c1;}")
          .append(".tk-op{color:#d73a49;}")
          .append("</style>")
          .append("<script>var vZoom=1;function viewerZoom(f){")
          .append("if(f===0){vZoom=1;}else{vZoom=Math.min(5,Math.max(0.1,vZoom*f));}")
          .append("var t='scale('+vZoom+')';")
          .append("document.body.style.webkitTransform=t;document.body.style.transform=t;}")
          .append("</script></head><body><div class=\"doc-wrap\">");
        if (truncated) {
            sb.append("<div class=\"banner\">文件较大，仅显示前 ")
              .append(MAX_DISPLAY_CHARS / 1024).append("K 字符（共 ")
              .append(safe.length()).append(" 字符）。完整内容仍在消息里，也已发给模型。</div>");
        } else if (!highlight && shown.length() > 0 && lang != null && lang.length() > 0) {
            sb.append("<div class=\"banner\">文件较大，已关闭语法高亮以保证滑动流畅。</div>");
        }
        sb.append("<pre><code>").append(body).append("</code></pre>")
          .append("</div></body></html>");
        return sb.toString();
    }
}
