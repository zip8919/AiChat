package xyz.zip8919.app.aichat;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableBody;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import ru.noties.jlatexmath.JLatexMathDrawable;

public class MessageHtmlRenderer {
    private static final Parser PARSER = Parser.builder()
            .extensions(Arrays.asList(
                    StrikethroughExtension.create(),
                    TablesExtension.create()))
            .build();

    private static final Map<String, String> latexCache = new HashMap<>();

    private static final String CSS =
            "*{margin:0;padding:0;box-sizing:border-box;}" +
            "body{font-family:Roboto,sans-serif;font-size:14px;color:#1a1a1a;" +
            "line-height:1.6;background:#fff;padding:8px;word-wrap:break-word;" +
            "-webkit-text-size-adjust:100%;}" +
            ".msg{margin:8px 0;}" +
            ".msg.user{text-align:right;}" +
            ".msg.user .bubble{display:inline-block;background:#E3F2FD;" +
            "padding:8px 12px;border-radius:12px;max-width:85%;text-align:left;}" +
            ".msg.ai{border-top:1px solid #e0e0e0;padding-top:12px;margin-top:8px;}" +
            ".msg.ai .content{max-width:100%;}" +
            "h1,h2,h3,h4,h5,h6{margin:12px 0 6px;font-weight:bold;line-height:1.3;}" +
            "h1{font-size:1.6em;border-bottom:2px solid #eee;padding-bottom:4px;}" +
            "h2{font-size:1.4em;border-bottom:1px solid #eee;padding-bottom:3px;}" +
            "h3{font-size:1.2em;}" +
            "h4{font-size:1.1em;}" +
            "h5{font-size:1.0em;}" +
            "h6{font-size:0.9em;color:#666;}" +
            "p{margin:4px 0;}" +
            "blockquote{border-left:3px solid #2196F3;margin:8px 0;padding:4px 12px;" +
            "background:#f5f5f5;color:#555;}" +
            "code{font-family:'Courier New',monospace;background:#f0f0f0;" +
            "padding:1px 4px;border-radius:3px;font-size:0.9em;color:#C7254E;}" +
            "pre{position:relative;background:#f6f8fa;border:1px solid #e1e4e8;" +
            "border-radius:4px;padding:28px 12px 10px 12px;margin:8px 0;" +
            "overflow-x:auto;-webkit-overflow-scrolling:touch;}" +
            "pre code{background:transparent;padding:0;color:#333;white-space:pre;" +
            "word-wrap:normal;font-size:13px;line-height:1.5;display:block;}" +
            "pre .lang-tag{position:absolute;top:4px;left:12px;font-size:10px;" +
            "color:#999;font-family:Roboto,sans-serif;}" +
            "pre .copy-btn{position:absolute;top:4px;right:8px;padding:2px 8px;" +
            "font-size:11px;background:#e1e4e8;border:1px solid #ccc;border-radius:3px;" +
            "color:#555;font-family:Roboto,sans-serif;}" +
            "table{border-collapse:collapse;width:100%;margin:8px 0;font-size:13px;}" +
            "th,td{border:1px solid #ddd;padding:6px 10px;text-align:left;}" +
            "th{background:#f0f0f0;font-weight:bold;}" +
            "a{color:#2196F3;text-decoration:none;}" +
            "img{max-width:100%;height:auto;}" +
            ".math-block{display:block;text-align:center;margin:12px 0;padding:8px;}" +
            ".math-block img{max-width:100%;height:auto;}" +
            ".math-inline{display:inline;vertical-align:middle;}" +
            ".math-inline img{height:1.6em;vertical-align:middle;}" +
            "ul,ol{padding-left:24px;margin:4px 0;}" +
            "li{margin:2px 0;}" +
            "hr{border:none;border-top:1px solid #ddd;margin:12px 0;}" +
            "mark{background:#FFF176;padding:1px 2px;}" +
            "sup,sub{font-size:0.75em;}" +
            "del,s{color:#999;}" +
            "em{font-style:italic;}" +
            "strong{font-weight:bold;}" +
            ".thinking-block{margin:8px 0;padding:8px 12px;background:#fafafa;" +
            "border-left:3px solid #ccc;color:#888;font-style:italic;font-size:13px;}" +
            ".thinking-toggle{display:block;margin-top:8px;color:#2196F3;" +
            "font-style:normal;font-weight:bold;font-size:13px;cursor:pointer;}" +
            ".msg-menu-btn{display:inline-block;background:none;border:none;" +
            "color:#bbb;font-size:17px;padding:2px 8px;line-height:1;" +
            "-webkit-tap-highlight-color:transparent;}" +
            ".msg-menu-btn:active{color:#555;background:#e8e8e8;border-radius:12px;}" +
            ".ai-menu-wrap{text-align:left;margin-top:4px;}" +
            ".user-menu-wrap{text-align:right;margin-top:2px;}" +
            ".fn-ref{font-size:0.75em;vertical-align:super;}" +
            ".fn-ref a{color:#2196F3;text-decoration:none;}" +
            ".fn-def{font-size:12px;color:#888;border-top:1px solid #eee;" +
            "padding:4px 0;margin:6px 0;}" +
            ".fn-def sup a{color:#888;text-decoration:none;}";

    private static final String JS =
            "(function(){" +
            "function addCopyBtns(){" +
            "var pres=document.querySelectorAll('pre');" +
            "for(var i=0;i<pres.length;i++){(function(pre){" +
            "if(pre.querySelector('.copy-btn'))return;" +
            "var btn=document.createElement('button');" +
            "btn.className='copy-btn';btn.textContent='复制';" +
            "btn.onclick=function(e){e.stopPropagation();e.preventDefault();" +
            "var code=pre.querySelector('code');" +
            "var text=code?code.textContent:pre.textContent;" +
            "if(window.Android)Android.copyCode(text);" +
            "btn.textContent='已复制';" +
            "setTimeout(function(){btn.textContent='复制';},2000);};" +
            "pre.appendChild(btn);})(pres[i]);}}" +
            "var lpTimer=null,lpStartX=0,lpStartY=0,lpEl=null;" +
            "document.addEventListener('touchstart',function(e){" +
            "if(!e.touches||e.touches.length!==1)return;" +
            "var t=e.touches[0];" +
            "lpStartX=t.clientX;lpStartY=t.clientY;lpEl=e.target;" +
            "lpTimer=setTimeout(function(){" +
            "var el=lpEl;" +
            "while(el&&el!==document.body){" +
            "var idx=el.getAttribute&&el.getAttribute('data-idx');" +
            "if(idx!==null&&idx!==undefined){if(window.Android)Android.messageLongPress(idx);return;}" +
            "el=el.parentElement;}" +
            "},700);});" +
            "document.addEventListener('touchmove',function(e){" +
            "if(!lpTimer||!e.touches||e.touches.length!==1)return;" +
            "var t=e.touches[0];" +
            "if(Math.abs(t.clientX-lpStartX)>10||Math.abs(t.clientY-lpStartY)>10)" +
            "{clearTimeout(lpTimer);lpTimer=null;}" +
            "});" +
            "document.addEventListener('touchend',function(){" +
            "if(lpTimer){clearTimeout(lpTimer);lpTimer=null;}});" +
            "document.addEventListener('touchcancel',function(){" +
            "if(lpTimer){clearTimeout(lpTimer);lpTimer=null;}});" +
            "document.addEventListener('click',function(e){" +
            "var t=e.target;" +
            "while(t&&t!==document.body){" +
            "if(t.tagName==='A'){e.preventDefault();" +
            "if(window.Android)Android.openUrl(t.href);return false;}" +
            "t=t.parentElement;}" +
            "});" +
            "window.toggleThinking=function(btn){" +
            "var b=btn.nextElementSibling;if(!b)return;" +
            "if(b.style.display==='none'){b.style.display='block';" +
            "btn.textContent='── 折叠思考 ──';}" +
            "else{b.style.display='none';" +
            "btn.textContent='── 展开思考（'+b.textContent.length+'字）──';}};" +
            "window.appendMsg=function(html){" +
            "var d=document.createElement('div');d.innerHTML=html;" +
            "var el=d.firstElementChild;" +
            "if(el)document.getElementById('msgs').appendChild(el);" +
            "addCopyBtns();window.scrollTo(0,document.body.scrollHeight);};" +
            "window.updateLastMsg=function(html){" +
            "var ms=document.querySelectorAll('.msg.ai .content');" +
            "if(ms.length>0){ms[ms.length-1].innerHTML=html;}" +
            "addCopyBtns();window.scrollTo(0,document.body.scrollHeight);};" +
            "window.appendAiDiv=function(){" +
            "var d=document.createElement('div');" +
            "d.className='msg ai';d.setAttribute('data-idx','-1');" +
            "d.innerHTML='<div class=\"content\"></div>" +
            "<div class=\"ai-menu-wrap\"><button class=\"msg-menu-btn\" " +
            "onclick=\"if(window.Android)Android.messageMenu(\\'-1\\');" +
            "event.stopPropagation();return false;\">⋯</button></div>';" +
            "document.getElementById('msgs').appendChild(d);" +
            "window.scrollTo(0,document.body.scrollHeight);};" +
            "window.finalizeLast=function(idx){" +
            "var ms=document.querySelectorAll('.msg.ai');" +
            "if(ms.length>0){" +
            "var last=ms[ms.length-1];" +
            "last.setAttribute('data-idx',''+idx);" +
            "var btn=last.querySelector('.msg-menu-btn');" +
            "if(btn)btn.setAttribute('onclick'," +
            "\"if(window.Android)Android.messageMenu('\"+idx+\"');event.stopPropagation();return false;\");" +
            "}};" +
            "window.removeFromIdx=function(idx){" +
            "var msgs=document.getElementById('msgs');" +
            "var all=msgs.querySelectorAll('.msg');" +
            "for(var i=all.length-1;i>=0;i--){" +
            "var attr=all[i].getAttribute('data-idx');" +
            "if(attr&&parseInt(attr)>=idx)all[i].parentNode.removeChild(all[i]);}" +
            "};" +
            "addCopyBtns();" +
            "window.scrollTo(0,document.body.scrollHeight);" +
            "})();";

    public static String buildConversationHtml(List<Message> messages, Context ctx) {
        // Set up LaTeX cache directory
        File dir = new File(ctx.getCacheDir(), "latex_cache");
        if (!dir.exists()) dir.mkdirs();
        latexDir = dir;

        StringBuilder body = new StringBuilder();
        for (int i = 0; i < messages.size(); i++) {
            body.append(renderMessageDiv(messages.get(i), i, ctx));
        }
        return buildPage(body.toString());
    }

    public static String renderMessageDiv(Message msg, int index, Context ctx) {
        String idx = String.valueOf(index);
        if (msg.isUser()) {
            return "<div class=\"msg user\" data-idx=\"" + idx + "\">" +
                   "<div class=\"bubble\">" + esc(msg.content) + "</div>" +
                   "<div class=\"user-menu-wrap\"><button class=\"msg-menu-btn\" onclick=\"if(window.Android)Android.messageMenu('" + idx + "');event.stopPropagation();return false;\">⋯</button></div></div>\n";
        }
        String html = contentToHtml(msg.content, ctx);
        return "<div class=\"msg ai\" data-idx=\"" + idx + "\">" +
               "<div class=\"content\">" + html + "</div>" +
               "<div class=\"ai-menu-wrap\"><button class=\"msg-menu-btn\" onclick=\"if(window.Android)Android.messageMenu('" + idx + "');event.stopPropagation();return false;\">⋯</button></div></div>\n";
    }

    public static String contentToHtml(String text, Context ctx) {
        if (text == null || text.isEmpty()) return "";
        if (text.contains("[thinking]"))
            return contentWithThinking(text, ctx);
        return renderMarkdown(text, ctx);
    }

    private static String contentWithThinking(String text, Context ctx) {
        StringBuilder html = new StringBuilder();
        int start = 0;
        while (start < text.length()) {
            int ts = text.indexOf("[thinking]", start);
            if (ts == -1) {
                html.append(renderMarkdown(text.substring(start), ctx));
                break;
            }
            if (ts > start)
                html.append(renderMarkdown(text.substring(start, ts), ctx));
            int te = text.indexOf("[/thinking]", ts + 10);
            if (te == -1) {
                // Streaming: thinking not yet closed
                html.append("<div class=\"thinking-block\">")
                    .append(esc(text.substring(ts + 10)))
                    .append("</div>");
                break;
            }
            String thinking = text.substring(ts + 10, te);
            html.append("<div class=\"thinking-wrap\">")
                .append("<div class=\"thinking-toggle\" onclick=\"toggleThinking(this)\">── 展开思考（")
                .append(String.valueOf(thinking.length())).append("字）──</div>")
                .append("<div class=\"thinking-block\" style=\"display:none\">")
                .append(renderMarkdown(thinking, ctx))
                .append("</div></div>");
            start = te + 11;
        }
        return html.toString();
    }

    private static File latexDir = null;

    private static String renderMarkdown(String text, Context ctx) {
        List<String> mathTags = new ArrayList<>();
        float density = ctx.getResources().getDisplayMetrics().density;
        text = extractAndRenderLatex(text, density, latexDir, mathTags);
        text = renderBareLatexCommands(text, density, latexDir, mathTags);
        text = preProcessExtensions(text);
        Node document = PARSER.parse(text);
        StringBuilder html = new StringBuilder();
        document.accept(new HtmlVisitor(html));
        // Replace @@MATHn@@ placeholders with actual rendered HTML
        String result = html.toString();
        for (int i = 0; i < mathTags.size(); i++)
            result = result.replace("@@MATH" + i + "@@", mathTags.get(i));
        return result;
    }

    // Detect and render bare \command or \command{args} that aren't inside $...$
    private static String renderBareLatexCommands(String text, float density, File dir, List<String> mathTags) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            if (text.charAt(i) == '\\' && i + 1 < text.length()
                    && Character.isLetter(text.charAt(i + 1))) {
                int start = i;
                i++;
                while (i < text.length() && Character.isLetter(text.charAt(i))) i++;
                if (i - start < 3) { out.append(text, start, i); continue; }
                while (i < text.length() && text.charAt(i) == '{') {
                    int depth = 1; i++;
                    while (i < text.length() && depth > 0) {
                        if (text.charAt(i) == '{') depth++;
                        else if (text.charAt(i) == '}') depth--;
                        i++;
                    }
                }
                String html = renderLatexImg(text.substring(start, i), false, density, dir);
                mathTags.add(html);
                out.append("@@MATH").append(mathTags.size() - 1).append("@@");
            } else {
                out.append(text.charAt(i));
                i++;
            }
        }
        return out.toString();
    }

    private static String preProcessExtensions(String text) {
        // Footnote definitions [^id]: text at line start → hidden ref div
        text = text.replaceAll("(?m)^\\[\\^([^\\]]+)\\]:\\s*(.+)$",
                "<div class=\"fn-def\" id=\"fn-$1\"><sup><a href=\"#fnref-$1\">[$1]</a></sup> $2</div>");
        // Footnote references [^id] → superscript link
        text = text.replaceAll("\\[\\^([^\\]]+)\\]",
                "<sup class=\"fn-ref\" id=\"fnref-$1\"><a href=\"#fn-$1\">[$1]</a></sup>");
        // ==highlight== → <mark>
        text = text.replaceAll("==([^=\\s].*?[^=\\s]|[^=\\s])==", "<mark>$1</mark>");
        // ^superscript^ → <sup>
        text = text.replaceAll("\\^([^\\^\\s]+)\\^", "<sup>$1</sup>");
        // ~subscript~ → <sub> (single ~ not ~~)
        text = text.replaceAll("(?<![~])~([^~\\s]+)~(?!~)", "<sub>$1</sub>");
        return text;
    }

    private static String extractAndRenderLatex(String text, float density, File latexDir, List<String> mathTags) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            if (text.startsWith("$$", i)) {
                int end = text.indexOf("$$", i + 2);
                if (end > i) {
                    String html = renderLatexImg(text.substring(i + 2, end).trim(), true, density, latexDir);
                    mathTags.add(html);
                    out.append('\n').append("@@MATH").append(mathTags.size() - 1).append("@@\n");
                    i = end + 2; continue;
                }
            }
            if (text.startsWith("\\[", i)) {
                int end = text.indexOf("\\]", i + 2);
                if (end > i) {
                    String html = renderLatexImg(text.substring(i + 2, end).trim(), true, density, latexDir);
                    mathTags.add(html);
                    out.append('\n').append("@@MATH").append(mathTags.size() - 1).append("@@\n");
                    i = end + 2; continue;
                }
            }
            if (text.charAt(i) == '$' && i + 1 < text.length()
                    && text.charAt(i + 1) != '$'
                    && (i == 0 || text.charAt(i - 1) != '$')) {
                int end = text.indexOf('$', i + 1);
                if (end > i + 1) {
                    String html = renderLatexImg(text.substring(i + 1, end), false, density, latexDir);
                    mathTags.add(html);
                    out.append("@@MATH").append(mathTags.size() - 1).append("@@");
                    i = end + 1; continue;
                }
            }
            if (text.startsWith("\\(", i)) {
                int end = text.indexOf("\\)", i + 2);
                if (end > i + 2) {
                    String html = renderLatexImg(text.substring(i + 2, end), false, density, latexDir);
                    mathTags.add(html);
                    out.append("@@MATH").append(mathTags.size() - 1).append("@@");
                    i = end + 2; continue;
                }
            }
            out.append(text.charAt(i));
            i++;
        }
        return out.toString();
    }

    private static String renderLatexImg(String formula, boolean block, float density, File dir) {
        String key = formula + (block ? "b" : "i");
        String cached = latexCache.get(key);
        if (cached != null) return cached;

        String imgTag = null;
        float textSize = block ? 24f : 18f;

        try {
            JLatexMathDrawable d = JLatexMathDrawable.builder(formula)
                    .textSize(textSize).background(0xFFFFFFFF).build();
            int w = d.getIntrinsicWidth();
            int h = d.getIntrinsicHeight();

            if (w > 0 && h > 0) {
                int maxW = (int)(280 * density);
                if (w > maxW) { float s = (float) maxW / w;
                    d = JLatexMathDrawable.builder(formula).textSize(textSize * s)
                            .background(0xFFFFFFFF).build();
                    w = maxW; h = (int)(h * s);
                }

                d.setBounds(0, 0, w, h);
                Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(bmp);
                c.drawColor(Color.WHITE);
                d.draw(c);

                // Render to base64 data URI — no file:// access needed
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.PNG, 90, baos);
                String b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
                bmp.recycle();

                String alt = escAttr(formula);
                if (block) {
                    imgTag = "<div class=\"math-block\"><img src=\"data:image/png;base64," + b64
                        + "\" alt=\"" + alt + "\" style=\"max-width:100%;height:auto;\"></div>";
                } else {
                    imgTag = "<img class=\"math-inline\" src=\"data:image/png;base64," + b64
                        + "\" alt=\"" + alt + "\" style=\"height:1.6em;vertical-align:middle;\">";
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Clean fallback — monospace code-style, not debug-yellow
        if (imgTag == null) {
            if (block) {
                imgTag = "<div class=\"math-block\" style=\"background:#f6f8fa;padding:10px;border:1px solid #d1d5da;border-radius:4px;\">"
                    + "<code style=\"font-size:14px;color:#555;\">" + esc(formula) + "</code></div>";
            } else {
                imgTag = "<code style=\"font-size:0.9em;background:#f0f0f0;padding:1px 5px;border-radius:3px;color:#555;\">"
                    + esc(formula) + "</code>";
            }
        }

        if (latexCache.size() >= 50) {
            String first = latexCache.keySet().iterator().next();
            latexCache.remove(first);
        }
        latexCache.put(key, imgTag);
        return imgTag;
    }

    private static String buildPage(String bodyHtml) {
        return "<!DOCTYPE html>\n<html>\n<head>\n" +
               "<meta charset=\"utf-8\">\n" +
               "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,user-scalable=no\">\n" +
               "<style>\n" + CSS + "\n</style>\n" +
               "</head>\n<body>\n" +
               "<div id=\"msgs\">\n" + bodyHtml + "\n</div>\n" +
               "<script>\n" + JS + "\n</script>\n" +
               "</body>\n</html>";
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String escAttr(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("'", "&#39;");
    }

    // ---- AST → HTML visitor ----

    private static class HtmlVisitor extends AbstractVisitor {
        final StringBuilder out;

        HtmlVisitor(StringBuilder out) { this.out = out; }

        @Override public void visit(Document d) { visitChildren(d); }
        @Override public void visit(Heading h) {
            String tag = "h" + h.getLevel();
            out.append('<').append(tag).append('>');
            visitChildren(h);
            out.append("</").append(tag).append('>');
        }
        @Override public void visit(Paragraph p) {
            out.append("<p>"); visitChildren(p); out.append("</p>");
        }
        @Override public void visit(Text t) { out.append(esc(t.getLiteral())); }
        @Override public void visit(HtmlInline hi) { out.append(hi.getLiteral()); }
        @Override public void visit(HtmlBlock hb) { out.append(hb.getLiteral()); }
        @Override public void visit(Emphasis e) {
            out.append("<em>"); visitChildren(e); out.append("</em>");
        }
        @Override public void visit(StrongEmphasis se) {
            out.append("<strong>"); visitChildren(se); out.append("</strong>");
        }
        @Override public void visit(Code c) {
            out.append("<code>").append(esc(c.getLiteral())).append("</code>");
        }
        @Override public void visit(FencedCodeBlock fcb) {
            out.append("<pre>");
            String info = fcb.getInfo();
            if (info != null && !info.isEmpty())
                out.append("<span class=\"lang-tag\">").append(esc(info)).append("</span>");
            out.append("<code>");
            String code = fcb.getLiteral();
            if (code != null) {
                if (code.endsWith("\n")) code = code.substring(0, code.length() - 1);
                out.append(esc(code));
            }
            out.append("</code></pre>");
        }
        @Override public void visit(IndentedCodeBlock icb) {
            out.append("<pre><code>");
            String code = icb.getLiteral();
            if (code != null) {
                if (code.endsWith("\n")) code = code.substring(0, code.length() - 1);
                out.append(esc(code));
            }
            out.append("</code></pre>");
        }
        @Override public void visit(BlockQuote bq) {
            out.append("<blockquote>"); visitChildren(bq); out.append("</blockquote>");
        }
        @Override public void visit(BulletList bl) {
            out.append("<ul>"); visitChildren(bl); out.append("</ul>");
        }
        @Override public void visit(OrderedList ol) {
            out.append("<ol start=\"").append(ol.getStartNumber()).append("\">");
            visitChildren(ol); out.append("</ol>");
        }
        @Override public void visit(ListItem li) {
            out.append("<li>"); visitChildren(li); out.append("</li>");
        }
        @Override public void visit(ThematicBreak tb) { out.append("<hr>"); }
        @Override public void visit(Link l) {
            out.append("<a href=\"").append(esc(l.getDestination())).append("\">");
            visitChildren(l); out.append("</a>");
        }
        @Override public void visit(Image img) {
            out.append("<img src=\"").append(esc(img.getDestination()))
               .append("\" alt=\"").append(img.getTitle() != null ? esc(img.getTitle()) : "")
               .append("\" style=\"max-width:100%;height:auto;\">");
        }
        @Override public void visit(SoftLineBreak slb) { out.append(' '); }
        @Override public void visit(HardLineBreak hlb) { out.append("<br>"); }

        // GFM extension nodes
        @Override public void visit(CustomBlock cb) {
            if (cb instanceof TableBlock) visitTable((TableBlock) cb);
            else visitChildren(cb);
        }
        @Override public void visit(CustomNode cn) {
            if (cn instanceof TableHead) visitTableHead((TableHead) cn);
            else if (cn instanceof TableBody) visitTableBody((TableBody) cn);
            else if (cn instanceof TableRow) visitTableRow((TableRow) cn);
            else if (cn instanceof TableCell) visitTableCell((TableCell) cn);
            else if (cn instanceof Strikethrough) visitStrikethrough((Strikethrough) cn);
            else visitChildren(cn);
        }

        private void visitTable(TableBlock tb) {
            out.append("<table>"); visitChildren(tb); out.append("</table>");
        }
        private void visitTableHead(TableHead th) {
            out.append("<thead>"); visitChildren(th); out.append("</thead>");
        }
        private void visitTableBody(TableBody tb) {
            out.append("<tbody>"); visitChildren(tb); out.append("</tbody>");
        }
        private void visitTableRow(TableRow tr) {
            out.append("<tr>"); visitChildren(tr); out.append("</tr>");
        }
        private void visitTableCell(TableCell tc) {
            String tag = tc.isHeader() ? "th" : "td";
            out.append('<').append(tag).append('>');
            visitChildren(tc);
            out.append("</").append(tag).append('>');
        }
        private void visitStrikethrough(Strikethrough s) {
            out.append("<del>"); visitChildren(s); out.append("</del>");
        }
    }
}
