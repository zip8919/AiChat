package xyz.zip8919.app.aichat;

/**
 * 「查看文件」里的 Markdown 预览页。
 *
 * <p>为什么单独一层 HTML 壳，而不是直接把 {@link MarkdownPipeline#render} 的结果丢给
 * WebView：commonmark 只产出裸标签，没有任何样式。裸标签在 Chromium 上会挤成
 * 一整段黑字——标题和正文一样大、代码块没有底色、表格没有边框，看起来像是
 * 「渲染失败」。这份壳就是那个缺失的样式表。
 *
 * <p>截断放在渲染<b>之前</b>：{@code MarkdownPipeline} 里有五六个正则（代码保护、
 * 公式、脚注、上下标），十万级字符跑下来在 API 18 上要好几秒，先截再渲染才救得回来。
 */
final class MarkdownPage {

    /** Markdown 渲染比纯文本贵得多，上限相应收紧。 */
    static final int MAX_CHARS = 64 * 1024;

    private MarkdownPage() {
    }

    /**
     * @param bodyHtml   已渲染好的 HTML 片段
     * @param totalChars 原文总字符数（用于截断提示）
     * @param rotateCss  旋转/尺寸补偿 CSS，可空
     * @param toolbarInset 顶部工具栏占位像素
     */
    static String build(String bodyHtml, int totalChars, String rotateCss, int toolbarInset) {
        String body = bodyHtml == null ? "" : bodyHtml;
        boolean truncated = totalChars > MAX_CHARS;

        StringBuilder sb = new StringBuilder(body.length() + 2048);
        sb.append("<!DOCTYPE html><html><head>")
          .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0,user-scalable=yes\">")
          .append("<style>")
          .append("*{margin:0;padding:0;}")
          .append("html,body{width:100%;min-height:100%;background:#FFFFFF;")
          .append("-webkit-transform-origin:0 0;transform-origin:0 0;}")
          .append("body{padding:").append(toolbarInset).append("px 12px 32px 12px;")
          .append("font-size:15px;line-height:1.65;color:#24292e;word-wrap:break-word;")
          .append("-webkit-box-sizing:border-box;box-sizing:border-box;}")
          .append(".doc-wrap{").append(rotateCss == null ? "" : rotateCss).append("}")
          .append("h1,h2,h3,h4,h5,h6{margin:18px 0 10px;font-weight:bold;line-height:1.3;}")
          .append("h1{font-size:22px;border-bottom:1px solid #eaecef;padding-bottom:6px;}")
          .append("h2{font-size:19px;border-bottom:1px solid #eaecef;padding-bottom:5px;}")
          .append("h3{font-size:17px;}h4{font-size:15px;}h5,h6{font-size:14px;color:#6a737d;}")
          .append("p{margin:10px 0;}")
          .append("ul,ol{margin:10px 0 10px 22px;}")
          .append("li{margin:4px 0;}")
          .append("blockquote{margin:10px 0;padding:6px 12px;border-left:4px solid #dfe2e5;")
          .append("color:#6a737d;background:#f6f8fa;}")
          .append("code{font-family:monospace;font-size:13px;background:#f3f4f6;")
          .append("padding:1px 4px;border-radius:3px;}")
          .append("pre{background:#f6f8fa;padding:10px;border-radius:6px;")
          .append("overflow-x:auto;margin:10px 0;}")
          .append("pre code{background:none;padding:0;font-size:12.5px;line-height:1.5;}")
          .append("table{border-collapse:collapse;margin:12px 0;max-width:100%;}")
          .append("th,td{border:1px solid #dfe2e5;padding:6px 10px;font-size:13px;text-align:left;}")
          .append("th{background:#f6f8fa;}")
          .append("img{max-width:100%;height:auto;}")
          .append("a{color:#0366d6;text-decoration:none;word-break:break-all;}")
          .append("hr{border:0;border-top:1px solid #eaecef;margin:16px 0;}")
          .append(".banner{background:#fff8e1;border:1px solid #ffe082;border-radius:6px;")
          .append("padding:6px 8px;font-size:12px;color:#8d6e63;line-height:1.45;")
          .append("margin-bottom:10px;}")
          .append(".tk-kw{color:#d73a49;font-weight:bold;}")
          .append(".tk-str{color:#032f62;}")
          .append(".tk-cmt{color:#6a737d;font-style:italic;}")
          .append(".tk-num{color:#005cc5;}")
          .append(".tk-type,.tk-fn{color:#6f42c1;}")
          .append(".tk-op{color:#d73a49;}")
          .append("</style>")
          // 与源码页共用缩放实现：查看器的放大/缩小/复位按钮直接调 viewerZoom
          .append("<script>var vZoom=1;function viewerZoom(f){")
          .append("if(f===0){vZoom=1;}else{vZoom=Math.min(5,Math.max(0.1,vZoom*f));}")
          .append("var t='scale('+vZoom+')';")
          .append("document.body.style.webkitTransform=t;document.body.style.transform=t;}")
          .append("</script></head><body><div class=\"doc-wrap\">");
        if (truncated) {
            sb.append("<div class=\"banner\">文件较大，仅显示前 ")
              .append(MAX_CHARS / 1024).append("K 字符（共 ")
              .append(totalChars).append(" 字符）。完整内容仍在消息里，也已发给模型。</div>");
        }
        sb.append(body)
          .append("</div></body></html>");
        return sb.toString();
    }
}
