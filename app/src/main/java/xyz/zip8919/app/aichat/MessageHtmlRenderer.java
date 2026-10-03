package xyz.zip8919.app.aichat;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.regex.Pattern;
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
    private static final String TAG = "HtmlRenderer";

    private static final Parser PARSER = Parser.builder()
            .extensions(Arrays.asList(
                    StrikethroughExtension.create(),
                    TablesExtension.create()))
            .build();

    // Thread-safe: render threads (aichat-render-*, streaming finalize) run concurrently.
    // Insertion-ordered LinkedHashMap: byte-budget eviction removes the oldest entry first.
    private static final int LATEX_CACHE_MAX_BYTES = 2 * 1024 * 1024;
    private static final Map<String, String> latexCache =
            Collections.synchronizedMap(new LinkedHashMap<String, String>());

    // Per-message HTML render cache: refreshWebView() re-renders every message on
    // settings changes / page returns; unchanged contents hit the cache instead of
    // re-running the full markdown pipeline. Keys are the message content strings
    // already held in memory, so no extra key copies. Bounded LRU.
    // Streaming throttling produces a new key every tick with near-full text, so
    // oversized renders (e.g. base64 math images) are not cached — otherwise up
    // to 24 near-identical full-message copies stay in memory for good.
    private static final int RENDER_CACHE_MAX_HTML = 256 * 1024;
    private static final int RENDER_CACHE_MAX_ENTRIES = 24;
    private static final int RENDER_CACHE_MAX_BYTES = 2 * 1024 * 1024;
    // accessOrder=true → entrySet() iterates in LRU order (least recent first);
    // contentToHtml evicts from the front before put (manual removeEldestEntry).
    private static final Map<String, String> renderCache =
            Collections.synchronizedMap(new LinkedHashMap<String, String>(32, 0.75f, true));

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
            "pre .save-btn{position:absolute;top:4px;right:56px;padding:2px 8px;" +
            "font-size:11px;background:#e8f5e9;border:1px solid #a5d6a7;border-radius:3px;" +
            "color:#2e7d32;font-family:Roboto,sans-serif;}" +
            "pre .preview-btn{position:absolute;top:4px;right:104px;padding:2px 8px;" +
            "font-size:11px;background:#e1f5fe;border:1px solid #81d4fa;border-radius:3px;" +
            "color:#0277bd;font-family:Roboto,sans-serif;}" +
            "pre .run-btn{position:absolute;top:4px;right:104px;padding:2px 8px;" +
            "font-size:11px;background:#fff3e0;border:1px solid #ffcc80;border-radius:3px;" +
            "color:#e65100;font-family:Roboto,sans-serif;}" +
            "table{border-collapse:collapse;margin:8px 0;font-size:13px;white-space:nowrap;}" +
            "th,td{border:1px solid #ddd;padding:6px 10px;text-align:left;}" +
            "th{background:#f0f0f0;font-weight:bold;}" +
            ".table-scroll{overflow-x:auto;-webkit-overflow-scrolling:touch;" +
            "-webkit-tap-highlight-color:rgba(100,180,255,0.3);}" +
            "a{color:#2196F3;text-decoration:none;}" +
            "img{max-width:100%;height:auto;}" +
            "svg{max-width:100%;}" +
            ".msg svg{display:block;overflow:hidden;}" +
            ".svg-scroll{overflow:auto;-webkit-overflow-scrolling:touch;margin:8px 0;}" +
            ".svg-scroll svg{width:100%;height:auto;display:block;overflow:visible;}" +
            ".math-block img,.math-block svg,.math-inline,.msg img,.msg svg{-webkit-tap-highlight-color:rgba(255,255,255,0.3);}" +
            ".math-block{display:block;text-align:center;margin:8px 0;padding:4px 8px;}" +
            ".math-block img,.math-block svg{max-width:100%;}" +
            ".math-block img{height:auto;}" +
            "svg.math-inline{display:inline;vertical-align:middle;}" +
            ".math-inline img,.math-inline svg{height:1.6em;vertical-align:middle;}" +
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
            ".tk-kw{color:#d73a49;font-weight:bold;}" +
            ".tk-str{color:#032f62;}" +
            ".tk-cmt{color:#6a737d;font-style:italic;}" +
            ".tk-num{color:#005cc5;}" +
            ".tk-type{color:#6f42c1;}" +
            ".tk-fn{color:#6f42c1;}" +
            ".tk-op{color:#d73a49;}" +
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
            "pre.appendChild(btn);" +
            "var langTag=pre.querySelector('.lang-tag');" +
            "var sbtn=document.createElement('button');" +
            "sbtn.className='save-btn';sbtn.textContent='保存';" +
            "sbtn.onclick=function(e){e.stopPropagation();e.preventDefault();" +
            "var code=pre.querySelector('code');" +
            "var text=code?code.textContent:pre.textContent;" +
            "var saveLang=langTag?langTag.textContent.trim().toLowerCase():'';" +
            "if(window.Android)Android.saveCode(saveLang,text);};" +
            "pre.appendChild(sbtn);" +
            "if(langTag){" +
            "var lang=langTag.textContent.trim().toLowerCase();" +
            "if((lang==='js'||lang==='javascript')&&!pre.querySelector('.run-btn')){" +
            "var rbtn=document.createElement('button');" +
            "rbtn.className='run-btn';rbtn.textContent='运行';" +
            "rbtn.onclick=function(e){e.stopPropagation();e.preventDefault();" +
            "var code=pre.querySelector('code');" +
            "var text=code?code.textContent:'';" +
            "if(window.Android)Android.runJs(text);};" +
            "pre.appendChild(rbtn);}" +
            "if((lang==='html'||lang==='svg')&&!pre.querySelector('.preview-btn')){" +
            "var pbtn=document.createElement('button');" +
            "pbtn.className='preview-btn';pbtn.textContent='预览';" +
            "pbtn.onclick=function(e){e.stopPropagation();e.preventDefault();" +
            "var code=pre.querySelector('code');" +
            "var text=code?code.textContent:'';" +
            "if(window.Android)Android.previewCode(lang,text);};" +
            "pre.appendChild(pbtn);}}})" +
            "(pres[i]);}}" +
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
            "var t=e.target,imgEl=null,tableEl=null;" +
            "while(t&&t!==document.body){" +
            "var tag=t.tagName.toUpperCase();" +
            "if(!imgEl&&(tag==='IMG'||tag==='SVG'))imgEl=t;" +
            "if(!tableEl&&tag==='TABLE')tableEl=t;" +
            "if(!imgEl&&tag==='A'){e.preventDefault();" +
            "if(window.Android)Android.openUrl(t.href);return false;}" +
            "t=t.parentElement;}" +
            "if(tableEl&&!imgEl){" +
            "e.preventDefault();e.stopPropagation();" +
            "var wrap=tableEl.parentElement;" +
            "if(wrap&&wrap.className.indexOf('table-scroll')>=0)tableEl=wrap;" +
            "var html='';" +
            "try{html=new XMLSerializer().serializeToString(tableEl);}" +
            "catch(e3){html=tableEl.outerHTML||'';}" +
            "if(html&&window.Android)Android.showTableViewer(html);" +
            "return false;}" +
            "if(imgEl){" +
            "e.preventDefault();e.stopPropagation();" +
            "var all=[],items=document.querySelectorAll('.msg img, .msg svg'),ci=-1;" +
            "for(var i=0;i<items.length;i++){" +
            "if(items[i]===imgEl)ci=all.length;" +
            "var itag=items[i].tagName.toUpperCase();" +
            "if(itag==='IMG'){" +
            "all.push({src:items[i].src,alt:items[i].alt||'',type:'raster'});" +
            "}else{" +
            "var imgChild=items[i].querySelector('image');" +
            "if(imgChild){" +
            "var href=imgChild.getAttribute('href')||imgChild.getAttribute('xlink:href')||'';" +
            "all.push({src:href,alt:items[i].getAttribute('aria-label')||'',type:'raster'});" +
            "}else{" +
            "var svgHtml='';" +
            "try{svgHtml=new XMLSerializer().serializeToString(items[i]);}" +
            "catch(e2){svgHtml=items[i].outerHTML||'';}" +
            "all.push({src:'',alt:items[i].getAttribute('aria-label')||'',type:'svg',svg:svgHtml});" +
            "}}}" +
            "if(ci>=0&&window.Android)Android.showImageViewer(''+ci,JSON.stringify(all));" +
            "return false;}" +
            "});" +
            "window.toggleThinking=function(btn){" +
            "var b=btn.nextElementSibling;if(!b)return;" +
            "if(b.style.display==='none'){b.style.display='block';" +
            "btn.textContent='── 折叠思考 ──';}" +
            "else{b.style.display='none';" +
            "btn.textContent='── 展开思考（'+b.textContent.length+'字）──';}};" +
            "window.smartScrollToBottom=function(force){" +
            "if(!force){" +
            "var st=document.documentElement.scrollTop||document.body.scrollTop||window.pageYOffset||0;" +
            "var sh=Math.max(document.documentElement.scrollHeight||0,document.body.scrollHeight||0);" +
            "var ch=document.documentElement.clientHeight||document.body.clientHeight||window.innerHeight||0;" +
            "if(sh-st-ch>80)return;}" +                     // 距底 >80px → 用户上翻了，不滚动
            "window.scrollTo(0,Math.max(" +
            "document.documentElement.scrollHeight||0," +
            "document.body.scrollHeight||0));};" +
            "window.appendMsg=function(html){" +
            "var d=document.createElement('div');d.innerHTML=html;" +
            "var el=d.firstElementChild;" +
            "if(el)document.getElementById('msgs').appendChild(el);" +
            "addCopyBtns();smartScrollToBottom();};" +
            "window.updateLastMsg=function(html){" +
            "var ms=document.querySelectorAll('.msg.ai .content');" +
            "if(ms.length>0){ms[ms.length-1].innerHTML=html;}" +
            "addCopyBtns();smartScrollToBottom();};" +
            "window.updateLastText=function(text){" +
            "var ms=document.querySelectorAll('.msg.ai .content');" +
            "if(ms.length>0){ms[ms.length-1].textContent=text;}" +
            "smartScrollToBottom();};" +
            "window.appendAiDiv=function(){" +
            "var d=document.createElement('div');" +
            "d.className='msg ai';d.setAttribute('data-idx','-1');" +
            "d.innerHTML='<div class=\"content\"></div>" +
            "<div class=\"ai-menu-wrap\"><button class=\"msg-menu-btn\" " +
            "onclick=\"if(window.Android)Android.messageMenu(\\'-1\\');" +
            "event.stopPropagation();return false;\">⋯</button></div>';" +
            "document.getElementById('msgs').appendChild(d);" +
            "smartScrollToBottom();};" +
            "window.updateMsgAt=function(idx,html){" +
            "var all=document.querySelectorAll('.msg');" +
            "for(var i=0;i<all.length;i++){" +
            "if(all[i].getAttribute('data-idx')===''+idx){" +
            "var isAi=all[i].className.indexOf('msg ai')>=0;" +
            "if(isAi){var c=all[i].querySelector('.content');if(c)c.innerHTML=html;}" +
            "else{var b=all[i].querySelector('.bubble');if(b)b.innerHTML=html;}" +
            "addCopyBtns();return;}}};" +
            "window.reindexFrom=function(start){" +
            "var all=document.querySelectorAll('.msg');" +
            "for(var i=0;i<all.length;i++){" +
            "var cur=parseInt(all[i].getAttribute('data-idx'));" +
            "if(cur>=start)all[i].setAttribute('data-idx',''+(cur-1));}};" +
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
            "if(attr&&parseInt(attr)==idx){all[i].parentNode.removeChild(all[i]);return;}}" +
            "};" +
            "window.removeRangeFrom=function(idx){" +
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
        long startTs = System.currentTimeMillis();
        LogUtil.d(TAG, "buildConversationHtml: %d messages (cache=%d)",
                messages == null ? -1 : messages.size(), latexCache.size());

        StringBuilder body = new StringBuilder();
        for (int i = 0; i < messages.size(); i++) {
            body.append(renderMessageDiv(messages.get(i), i, ctx));
        }
        String page = buildPage(body.toString());
        LogUtil.d(TAG, "buildConversationHtml done: body=%d chars, page=%d chars in %d ms",
                body.length(), page.length(), System.currentTimeMillis() - startTs);
        return page;
    }

    public static String renderMessageDiv(Message msg, int index, Context ctx) {
        String idx = String.valueOf(index);
        if (msg == null) {
            LogUtil.w(TAG, "renderMessageDiv: null message at index=%d", index);
            return "";
        }
        LogUtil.v(TAG, "renderMessageDiv: idx=%d role=%s len=%d", index,
                msg.role, msg.content == null ? 0 : msg.content.length());
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
        String cached = renderCache.get(text);
        if (cached != null) return cached;
        long startTs = System.currentTimeMillis();
        String html = text.contains("[thinking]")
                ? contentWithThinking(text, ctx)
                : renderMarkdown(text, ctx);
        LogUtil.v(TAG, "contentToHtml: in=%d chars -> %d chars html in %d ms (thinking=%s)",
                text.length(), html.length(), System.currentTimeMillis() - startTs,
                text.contains("[thinking]"));
        if (html.length() <= RENDER_CACHE_MAX_HTML) {
            synchronized (renderCache) {
                boolean present = renderCache.containsKey(text);
                int totalBytes = html.length();
                for (String v : renderCache.values()) totalBytes += v.length();
                Iterator<Map.Entry<String, String>> it = renderCache.entrySet().iterator();
                while (it.hasNext() && ((!present && renderCache.size() >= RENDER_CACHE_MAX_ENTRIES)
                        || totalBytes > RENDER_CACHE_MAX_BYTES)) {
                    Map.Entry<String, String> e = it.next();
                    it.remove();
                    totalBytes -= e.getValue().length();
                }
                renderCache.put(text, html);
            }
        }
        return html;
    }

    private static String contentWithThinking(String text, Context ctx) {
        StringBuilder html = new StringBuilder();
        int start = 0;
        int blocks = 0;
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
                LogUtil.v(TAG, "thinking block UNCLOSED at %d, tail=%d chars", ts, text.length() - ts - 10);
                html.append("<div class=\"thinking-block\">")
                    .append(esc(text.substring(ts + 10)))
                    .append("</div>");
                break;
            }
            String thinking = text.substring(ts + 10, te);
            blocks++;
            html.append("<div class=\"thinking-wrap\">")
                .append("<div class=\"thinking-toggle\" onclick=\"toggleThinking(this)\">── 展开思考（")
                .append(String.valueOf(thinking.length())).append("字）──</div>")
                .append("<div class=\"thinking-block\" style=\"display:none\">")
                .append(renderMarkdown(thinking, ctx))
                .append("</div></div>");
            start = te + 11;
        }
        LogUtil.v(TAG, "contentWithThinking: %d thinking blocks rendered", blocks);
        return html.toString();
    }

    private static String renderMarkdown(String text, Context ctx) {
        List<String> mathTags = new ArrayList<>();
        float density = ctx.getResources().getDisplayMetrics().density;

        // 0. Protect raw SVG blocks from markdown parsing — commonmark doesn't treat <svg> as HTML block
        List<String> svgBlocks = new ArrayList<>();
        text = extractAndProtectSvg(text, svgBlocks);

        text = extractAndRenderCe(text, density, mathTags);   // \ce → LaTeX → jlatexmath image
        text = extractAndRenderLatex(text, density, mathTags);
        text = renderBareLatexCommands(text, density, mathTags);
        text = preProcessExtensions(text);
        Node document = PARSER.parse(text);
        StringBuilder html = new StringBuilder();
        document.accept(new HtmlVisitor(html));
        String result = backfillPlaceholders(html.toString(), mathTags, svgBlocks);
        LogUtil.v(TAG, "renderMarkdown: %d -> %d chars, %d math formulas",
                text.length(), result.length(), mathTags.size());
        return result;
    }

    // Single-pass placeholder backfill: scans left to right for "@@" tokens and
    // replaces @@MATHn@@ / @@SVGn@@ in one pass instead of a whole-string replace()
    // per entry. Unknown tokens, out-of-range indexes and null replacements are
    // kept literally, matching the previous sequential replace() semantics.
    private static String backfillPlaceholders(String result, List<String> mathTags, List<String> svgBlocks) {
        StringBuilder out = new StringBuilder(result.length() + 256);
        int pos = 0;
        int len = result.length();
        while (pos < len) {
            int at = result.indexOf("@@", pos);
            if (at < 0) {
                out.append(result, pos, len);
                break;
            }
            out.append(result, pos, at);
            int p = at + 2;
            int q = p;
            while (q < len && Character.isLetter(result.charAt(q))) q++;
            String kind = result.substring(p, q);
            int d = q;
            while (d < len && result.charAt(d) >= '0' && result.charAt(d) <= '9') d++;
            int idx = -1;
            if ((kind.equals("MATH") || kind.equals("SVG")) && d > q && d + 1 < len
                    && result.charAt(d) == '@' && result.charAt(d + 1) == '@'
                    && (d == q + 1 || result.charAt(q) != '0')) {
                try {
                    idx = Integer.parseInt(result.substring(q, d));
                } catch (NumberFormatException nfe) {
                    idx = -1;
                }
            }
            if (idx >= 0 && idx < mathTags.size() && mathTags.get(idx) != null
                    && kind.equals("MATH")) {
                out.append(mathTags.get(idx));
                pos = d + 2;
                continue;
            }
            if (idx >= 0 && idx < svgBlocks.size() && svgBlocks.get(idx) != null
                    && kind.equals("SVG")) {
                String svgHtml = sanitizeRawHtml(svgBlocks.get(idx));
                // Inject width constraint directly on the SVG element so API 18
                // respects it even when CSS doesn't override presentational attrs
                if (svgHtml.toLowerCase().contains(" style=\"")) {
                    svgHtml = svgHtml.replaceFirst("(?i) style=\"", " style=\"max-width:100%;width:100%;height:auto;");
                } else {
                    svgHtml = svgHtml.replaceFirst("(?i)<svg", "<svg style=\"max-width:100%;width:100%;height:auto;\"");
                }
                out.append("<div class=\"svg-scroll\">").append(svgHtml).append("</div>");
                pos = d + 2;
                continue;
            }
            out.append("@@");
            pos = at + 2;
        }
        return out.toString();
    }

    // Extract <svg ...>...</svg> blocks (case-insensitive) and replace with placeholders
    // so commonmark doesn't mangle them (svg is not a recognized HTML block tag).
    // Skips SVGs inside fenced code blocks (```) and inline code spans (`).
    private static String extractAndProtectSvg(String text, List<String> svgBlocks) {
        int svgStart = svgBlocks.size();
        StringBuilder out = new StringBuilder();
        int i = 0, len = text.length();
        boolean inFence = false;
        boolean inInlineCode = false;
        while (i < len) {
            char c = text.charAt(i);
            // Track fenced code block boundaries
            if (text.startsWith("```", i)) {
                inFence = !inFence;
                out.append("```");
                i += 3;
                // skip language info on the same line
                while (i < len && text.charAt(i) != '\n') { out.append(text.charAt(i)); i++; }
                continue;
            }
            // Track inline code spans
            if (c == '`' && !inFence) {
                // Count consecutive backticks
                int bt = 0, j = i;
                while (j < len && text.charAt(j) == '`') { bt++; j++; }
                if (bt == 1) {
                    inInlineCode = !inInlineCode;
                    out.append('`');
                    i++;
                    continue;
                }
                // Multi-backtick sequences (like ``) are rare inline code delimiters;
                // just copy them through and don't toggle state for simplicity.
            }
            if (!inFence && !inInlineCode
                    && text.regionMatches(true, i, "<svg", 0, 4)
                    && (i + 4 >= len || isTagBoundary(text.charAt(i + 4)))) {
                int start = i;
                i += 4;
                int depth = 1;
                while (i < len && depth > 0) {
                    if (text.regionMatches(true, i, "<svg", 0, 4)
                            && (i + 4 >= len || isTagBoundary(text.charAt(i + 4)))) {
                        depth++;
                    } else if (text.regionMatches(true, i, "</svg>", 0, 6)) {
                        depth--;
                        if (depth == 0) { i += 6; break; }
                    }
                    i++;
                }
                svgBlocks.add(text.substring(start, i));
                out.append("@@SVG").append(svgBlocks.size() - 1).append("@@");
            } else {
                out.append(c);
                i++;
            }
        }
        if (svgBlocks.size() > svgStart) {
            LogUtil.v(TAG, "extractAndProtectSvg: extracted %d svg block(s)", svgBlocks.size() - svgStart);
        }
        return out.toString();
    }

    private static boolean isTagBoundary(char c) {
        return c == '>' || c == ' ' || c == '\n' || c == '\t' || c == '\r';
    }

    // Strip leading $$, \[, or $ from output buffer and return prefix length (0/1/2).
    // Only strips if the math wrapper contains only this command and the matching
    // closer immediately follows in text at cmdEnd.
    // Looks backward past whitespace/newlines to find the delimiter,
    // and handles output buffer trimming internally.
    private static int stripMathWrapOut(String text, int cmdEnd, StringBuilder out) {
        int end = out.length();
        // Skip trailing whitespace
        while (end > 0) {
            char c = out.charAt(end - 1);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') end--;
            else break;
        }
        // Check for "$$" prefix
        if (end >= 2 && out.charAt(end-2)=='$' && out.charAt(end-1)=='$') {
            int j = cmdEnd;
            while (j < text.length() && (text.charAt(j)==' ' || text.charAt(j)=='\t')) j++;
            if (j+1 < text.length() && text.charAt(j)=='$' && text.charAt(j+1)=='$') {
                out.setLength(end - 2);
                return 2;
            }
        }
        // Check for "\[" prefix (LaTeX display math)
        if (end >= 2 && out.charAt(end-2)=='\\' && out.charAt(end-1)=='[') {
            int j = cmdEnd;
            while (j < text.length() && (text.charAt(j)==' ' || text.charAt(j)=='\t' || text.charAt(j)=='\n')) j++;
            if (j+1 < text.length() && text.charAt(j)=='\\' && text.charAt(j+1)==']') {
                out.setLength(end - 2);
                return 2;
            }
        }
        // Check for single "$" prefix (but not "$$")
        if (end >= 1 && out.charAt(end-1)=='$' && (end < 2 || out.charAt(end-2)!='$')) {
            int j = cmdEnd;
            while (j < text.length() && (text.charAt(j)==' ' || text.charAt(j)=='\t')) j++;
            if (j < text.length() && text.charAt(j)=='$'
                    && (j+1 >= text.length() || text.charAt(j+1)!='$')) {
                out.setLength(end - 1);
                return 1;
            }
        }
        return 0;
    }

    // Skip closing math delimiter in text at position i
    private static int skipMathSuffix(String text, int i, int prefixLen) {
        while (i < text.length() && (text.charAt(i)==' ' || text.charAt(i)=='\t' || text.charAt(i)=='\n')) i++;
        if (prefixLen == 2 && i+1 < text.length() && text.charAt(i)=='$' && text.charAt(i+1)=='$') i+=2;
        else if (prefixLen == 2 && i+1 < text.length() && text.charAt(i)=='\\' && text.charAt(i+1)==']') i+=2;
        else if (prefixLen == 1 && i < text.length() && text.charAt(i)=='$') i++;
        return i;
    }

    // Check if position pos is inside a backtick code span (odd number of ` before pos)
    private static boolean insideBacktickSpan(StringBuilder out) {
        int bt = 0;
        for (int k = 0; k < out.length(); k++) {
            if (out.charAt(k) == '`') bt++;
        }
        return (bt % 2) == 1;
    }

    // Extract \ce{...} → convert to LaTeX → render with jlatexmath → @@MATHn@@ image.
    private static String extractAndRenderCe(String text, float density, List<String> mathTags) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            if (text.charAt(i) == '\\' && i + 3 < text.length()
                    && text.charAt(i + 1) == 'c' && text.charAt(i + 2) == 'e'
                    && text.charAt(i + 3) == '{'
                    && !insideBacktickSpan(out)) {
                i += 4;
                int depth = 1, cs = i;
                while (i < text.length() && depth > 0) {
                    if (text.charAt(i) == '{') depth++;
                    else if (text.charAt(i) == '}') depth--;
                    i++;
                }
                if (depth > 0) {
                    // 未找到配对 } (流式分片/截断)：原样输出剩余文本，避免 substring 越界
                    out.append(text, cs - 4, text.length());
                    break;
                }
                int ce = i;
                String formula = text.substring(cs, ce - 1);

                int pfx = stripMathWrapOut(text, ce, out);
                // stripMathWrapOut already trimmed the output buffer

                // Convert \ce content to proper LaTeX and render as image
                String latex = ceToLatex(formula);
                boolean isBlock = (pfx == 2); // $$ → block, $ or bare → inline
                String imgHtml = renderLatexImg(latex, isBlock, density);
                if (isBlock) {
                    out.append('\n').append(imgHtml).append("\n\n");
                } else {
                    mathTags.add(imgHtml);
                    out.append("@@MATH").append(mathTags.size() - 1).append("@@");
                }

                if (pfx > 0) i = skipMathSuffix(text, i, pfx);
            } else {
                out.append(text.charAt(i));
                i++;
            }
        }
        return out.toString();
    }

    // Convert \ce{} formula content to jlatexmath-compatible LaTeX.
    // - Subscripts: H2O → H_{2}O, C6H12O6 → C_{6}H_{12}O_{6}
    // - Superscripts: Ca^2+ → Ca^{2+}
    // - Isotopes: ^{227}_{90}Th → ^{227}_{90}Th (pass through)
    // - Arrows: -> → \rightarrow, <- → \leftarrow
    // - Arrow conditions: ->[above] → \xrightarrow{above}, ->[above][below] → \xrightarrow[below]{above}
    // - <=> → \rightleftharpoons, <=>[above] → \xrightleftharpoons{above}
    // - Bare \commands like \Delta pass through unchanged
    static String ceToLatex(String formula) {
        StringBuilder out = new StringBuilder();
        int i = 0, len = formula.length();
        boolean justHadLetter = false;

        while (i < len) {
            char c = formula.charAt(i);

            // Pass through LaTeX commands like \Delta, \text{...}, etc.
            if (c == '\\' && i + 1 < len && Character.isLetter(formula.charAt(i + 1))) {
                int start = i;
                i++;
                while (i < len && Character.isLetter(formula.charAt(i))) i++;
                String cmd = formula.substring(start, i);
                // Handle \text{...} and other commands with braces
                if (i < len && formula.charAt(i) == '{') {
                    i++;
                    int bd = 1, bs = i;
                    while (i < len && bd > 0) {
                        if (formula.charAt(i) == '{') bd++;
                        else if (formula.charAt(i) == '}') bd--;
                        i++;
                    }
                    String arg = formula.substring(bs, i - 1);
                    if (cmd.equals("\\text")) {
                        out.append("\\text{").append(arg).append("}");
                    } else {
                        out.append(cmd).append("{").append(arg).append("}");
                    }
                } else {
                    out.append(cmd);
                }
                justHadLetter = false;
                continue;
            }

            // Arrow with optional conditions: ->[above][below]
            boolean isArrow = false;
            boolean isBidirectional = false;
            String arrowCmd = null;
            int arrowLen = 0;

            if (c == '-' && i + 1 < len && formula.charAt(i + 1) == '>') {
                // ->
                arrowLen = 2;
                int j = i + 2;
                if (j < len && formula.charAt(j) == '[') arrowCmd = "\\xrightarrow";
                else arrowCmd = "\\rightarrow";
                isArrow = true;
            } else if (c == '<' && i + 1 < len && formula.charAt(i + 1) == '-') {
                // <-
                arrowLen = 2;
                int j = i + 2;
                if (j < len && formula.charAt(j) == '[') arrowCmd = "\\xleftarrow";
                else arrowCmd = "\\leftarrow";
                isArrow = true;
            } else if (c == '<' && i + 2 < len
                    && formula.charAt(i + 1) == '=' && formula.charAt(i + 2) == '>') {
                // <=>  reversible reaction arrow
                arrowLen = 3;
                arrowCmd = "\\leftrightarrows";
                isArrow = true;
                isBidirectional = true;
            }

            if (isArrow) {
                i += arrowLen;
                String above = null, below = null;
                if (i < len && formula.charAt(i) == '[') {
                    above = extractBracketArg(formula, i);
                    i += above.length() + 2;
                    if (above.contains("\\")) above = ceToLatex(above);
                }
                if (i < len && formula.charAt(i) == '[') {
                    below = extractBracketArg(formula, i);
                    i += below.length() + 2;
                    if (below.contains("\\")) below = ceToLatex(below);
                }
                if (isBidirectional) {
                    // <=>: stack conditions above/below with stackrel/underset.
                    // (X^{a}_{b} puts them as super/subscripts in inline mode.)
                    if (above != null && below != null) {
                        out.append("\\underset{\\text{").append(below)
                           .append("}}{\\stackrel{\\text{").append(above)
                           .append("}}{\\leftrightarrows}}");
                    } else if (above != null) {
                        out.append("\\stackrel{\\text{").append(above)
                           .append("}}{\\leftrightarrows}");
                    } else if (below != null) {
                        out.append("\\underset{\\text{").append(below)
                           .append("}}{\\leftrightarrows}");
                    } else {
                        out.append("\\leftrightarrows");
                    }
                } else {
                    // -> or <-  — use extensible arrow with conditions
                    if (above != null && below != null) {
                        out.append(arrowCmd).append("[").append(below)
                           .append("]{").append(above).append("}");
                    } else if (above != null) {
                        out.append(arrowCmd).append("{").append(above).append("}");
                    } else {
                        out.append(arrowCmd);
                    }
                }
                justHadLetter = false;
                continue;
            }

            // Superscript ^
            if (c == '^') {
                i++;
                out.append("^{");
                while (i < len && (Character.isDigit(formula.charAt(i))
                        || formula.charAt(i) == '+' || formula.charAt(i) == '-')) {
                    out.append(formula.charAt(i));
                    i++;
                }
                out.append("}");
                justHadLetter = false;
                continue;
            }

            // Subscript with _ (already LaTeX-compatible, just pass through)
            if (c == '_') {
                i++;
                out.append("_{");
                if (i < len && formula.charAt(i) == '{') {
                    i++;
                    int bd = 1;
                    while (i < len && bd > 0) {
                        if (formula.charAt(i) == '{') bd++;
                        else if (formula.charAt(i) == '}') bd--;
                        if (bd > 0) out.append(formula.charAt(i));
                        i++;
                    }
                } else {
                    while (i < len && Character.isDigit(formula.charAt(i))) {
                        out.append(formula.charAt(i));
                        i++;
                    }
                }
                out.append("}");
                justHadLetter = false;
                continue;
            }

            // Digit after element letter → subscript
            if (Character.isDigit(c) && justHadLetter) {
                out.append("_{");
                while (i < len && Character.isDigit(formula.charAt(i))) {
                    out.append(formula.charAt(i));
                    i++;
                }
                out.append("}");
                justHadLetter = false;
                continue;
            }

            if (Character.isLetter(c)) {
                out.append(c);
                justHadLetter = true;
                i++;
            } else {
                out.append(c);
                justHadLetter = false;
                i++;
            }
        }
        return out.toString();
    }

    // Extract content inside [brackets], handling nested brackets
    private static String extractBracketArg(String s, int start) {
        // start is index of the opening '['
        int depth = 1;
        int i = start + 1;
        while (i < s.length() && depth > 0) {
            if (s.charAt(i) == '[') depth++;
            else if (s.charAt(i) == ']') depth--;
            i++;
        }
        return s.substring(start + 1, i - 1); // content between [ and ]
    }


    // Detect and render bare \command or \command{args} that aren't inside $...$ or backticks
    private static String renderBareLatexCommands(String text, float density, List<String> mathTags) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        boolean inBacktick = false;
        while (i < text.length()) {
            if (text.charAt(i) == '`') {
                inBacktick = !inBacktick;
                out.append('`');
                i++;
                continue;
            }
            if (!inBacktick && text.charAt(i) == '\\' && i + 1 < text.length()
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
                String html = renderLatexImg(text.substring(start, i), false, density);
                mathTags.add(html);
                out.append("@@MATH").append(mathTags.size() - 1).append("@@");
            } else {
                out.append(text.charAt(i));
                i++;
            }
        }
        return out.toString();
    }

    // Pre-compiled: preProcessExtensions runs on every markdown render (hot path)
    private static final Pattern P_FN_DEF =
            Pattern.compile("(?m)^\\[\\^([^\\]]+)\\]:\\s*(.+)$");
    private static final Pattern P_FN_REF = Pattern.compile("\\[\\^([^\\]]+)\\]");
    private static final Pattern P_MARK =
            Pattern.compile("==([^=\\s].*?[^=\\s]|[^=\\s])==");
    private static final Pattern P_SUP = Pattern.compile("\\^([^\\^\\s]+)\\^");
    private static final Pattern P_SUB = Pattern.compile("(?<![~])~([^~\\s]+)~(?!~)");

    private static String preProcessExtensions(String text) {
        // Footnote definitions [^id]: text at line start → hidden ref div
        text = P_FN_DEF.matcher(text).replaceAll(
                "<div class=\"fn-def\" id=\"fn-$1\"><sup><a href=\"#fnref-$1\">[$1]</a></sup> $2</div>");
        // Footnote references [^id] → superscript link
        text = P_FN_REF.matcher(text).replaceAll(
                "<sup class=\"fn-ref\" id=\"fnref-$1\"><a href=\"#fn-$1\">[$1]</a></sup>");
        // ==highlight== → <mark>
        text = P_MARK.matcher(text).replaceAll("<mark>$1</mark>");
        // ^superscript^ → <sup>
        text = P_SUP.matcher(text).replaceAll("<sup>$1</sup>");
        // ~subscript~ → <sub> (single ~ not ~~)
        text = P_SUB.matcher(text).replaceAll("<sub>$1</sub>");
        return text;
    }

    private static String extractAndRenderLatex(String text, float density, List<String> mathTags) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        boolean inBacktick = false;
        while (i < text.length()) {
            // Track inline code spans — don't extract math inside backticks
            if (text.charAt(i) == '`') {
                inBacktick = !inBacktick;
                out.append('`');
                i++;
                continue;
            }
            if (!inBacktick && text.startsWith("$$", i)) {
                int end = text.indexOf("$$", i + 2);
                if (end > i) {
                    String html = renderLatexImg(text.substring(i + 2, end).trim(), true, density);
                    out.append('\n').append(html).append("\n\n");
                    i = end + 2; continue;
                }
            }
            if (!inBacktick && text.startsWith("\\[", i)) {
                int end = text.indexOf("\\]", i + 2);
                if (end > i) {
                    String html = renderLatexImg(text.substring(i + 2, end).trim(), true, density);
                    out.append('\n').append(html).append("\n\n");
                    i = end + 2; continue;
                }
            }
            if (!inBacktick && text.charAt(i) == '$' && i + 1 < text.length()
                    && text.charAt(i + 1) != '$'
                    && (i == 0 || text.charAt(i - 1) != '$')) {
                int end = text.indexOf('$', i + 1);
                if (end > i + 1) {
                    String html = renderLatexImg(text.substring(i + 1, end), false, density);
                    mathTags.add(html);
                    out.append("@@MATH").append(mathTags.size() - 1).append("@@");
                    i = end + 1; continue;
                }
            }
            if (!inBacktick && text.startsWith("\\(", i)) {
                int end = text.indexOf("\\)", i + 2);
                if (end > i + 2) {
                    String html = renderLatexImg(text.substring(i + 2, end), false, density);
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

    private static String renderLatexImg(String formula, boolean block, float density) {
        String key = formula + (block ? "b" : "i");
        String cached = latexCache.get(key);
        if (cached != null) {
            LogUtil.v(TAG, "latex CACHE hit: block=%s %s", block, LogUtil.preview(formula, 80));
            return cached;
        }
        LogUtil.v(TAG, "latex render: block=%s %s", block, LogUtil.preview(formula, 80));

        String imgTag = null;
        float scale = Math.max(density, 2.5f);
        float textSize = (block ? 24f : 18f) * scale;

        try {
            JLatexMathDrawable d = JLatexMathDrawable.builder(formula)
                    .textSize(textSize).background(0xFFFFFFFF).build();
            int w = d.getIntrinsicWidth();
            int h = d.getIntrinsicHeight();

            if (w > 0 && h > 0) {
                int maxW = (int)(400 * density);
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
                // Block: explicit display dimensions (w/scale) so API 18
                // doesn't fall back to 300×150 default. viewBox maps the
                // high-res bitmap to display size.
                // Inline: no width/height attrs — CSS height:1.6em;width:auto
                String svgNs = "http://www.w3.org/2000/svg";
                String xlinkNs = "http://www.w3.org/1999/xlink";
                if (block) {
                    int dispW = Math.round((float) w / scale);
                    int dispH = Math.round((float) h / scale);
                    if (dispW < 1) dispW = 1;
                    if (dispH < 1) dispH = 1;
                    imgTag = "<div class=\"math-block\">" +
                        "<svg xmlns=\"" + svgNs + "\" xmlns:xlink=\"" + xlinkNs + "\"" +
                        " width=\"" + dispW + "\" height=\"" + dispH + "\"" +
                        " viewBox=\"0 0 " + w + " " + h + "\"" +
                        " style=\"max-width:100%;\">" +
                        "<image width=\"" + w + "\" height=\"" + h + "\"" +
                        " xlink:href=\"data:image/png;base64," + b64 + "\"/>" +
                        "</svg></div>";
                } else {
                    imgTag = "<svg xmlns=\"" + svgNs + "\" xmlns:xlink=\"" + xlinkNs + "\"" +
                        " class=\"math-inline\"" +
                        " viewBox=\"0 0 " + w + " " + h + "\"" +
                        " style=\"height:1.6em;width:auto;vertical-align:middle;\">" +
                        "<image width=\"" + w + "\" height=\"" + h + "\"" +
                        " xlink:href=\"data:image/png;base64," + b64 + "\"/>" +
                        "</svg>";
                }
            }
        } catch (Exception e) {
            LogUtil.w(TAG, "latex render FAILED for '%s': %s -> fallback", LogUtil.preview(formula, 80), e.getMessage());
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

        synchronized (latexCache) {
            latexCache.put(key, imgTag);
            int totalBytes = 0;
            for (String v : latexCache.values()) totalBytes += v.length();
            int evicted = 0;
            Iterator<Map.Entry<String, String>> it = latexCache.entrySet().iterator();
            while (totalBytes > LATEX_CACHE_MAX_BYTES && it.hasNext()) {
                Map.Entry<String, String> e = it.next();
                it.remove();
                totalBytes -= e.getValue().length();
                evicted++;
            }
            if (evicted > 0) {
                LogUtil.v(TAG, "latex cache EVICT %d oldest (size now %d)",
                        evicted, latexCache.size());
            }
        }
        LogUtil.v(TAG, "latex cached (size=%d): %d chars html", latexCache.size(), imgTag.length());
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

    public static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&') sb.append("&amp;");
            else if (c == '<') sb.append("&lt;");
            else if (c == '>') sb.append("&gt;");
            else if (c == '"') sb.append("&quot;");
            else sb.append(c);
        }
        return sb.toString();
    }

    public static String escAttr(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&') sb.append("&amp;");
            else if (c == '"') sb.append("&quot;");
            else if (c == '<') sb.append("&lt;");
            else if (c == '>') sb.append("&gt;");
            else if (c == '\'') sb.append("&#39;");
            else sb.append(c);
        }
        return sb.toString();
    }

    // Active-content tags / attributes stripped from model-authored raw HTML before it
    // reaches the JS-enabled conversation WebView. Benign tags (br/sub/mark/div/...) pass.
    // Removing the tags (open and close) leaves any inner JS as inert plain text.
    private static final Pattern P_DANGER_TAG = Pattern.compile(
            "(?is)<\\s*/?\\s*(script|iframe|object|embed|applet|frame|frameset|noscript|base|link|meta|form)\\b[^>]*>");
    private static final Pattern P_EVENT_ATTR = Pattern.compile(
            "(?i)\\son[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)");
    private static final Pattern P_JS_URL = Pattern.compile(
            "(?i)(href|src|xlink:href|action|formaction|background)\\s*=\\s*(\"|')?\\s*(javascript|vbscript|livescript)\\s*:[^\"'>\\s]*");

    static String sanitizeRawHtml(String html) {
        if (html == null || html.isEmpty()) return "";
        html = P_DANGER_TAG.matcher(html).replaceAll("");
        html = P_EVENT_ATTR.matcher(html).replaceAll("");
        html = P_JS_URL.matcher(html).replaceAll("$1=\"#\"");
        return html;
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
        @Override public void visit(HtmlInline hi) { out.append(sanitizeRawHtml(hi.getLiteral())); }
        @Override public void visit(HtmlBlock hb) { out.append(sanitizeRawHtml(hb.getLiteral())); }
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
            LogUtil.v(TAG, "visit FencedCodeBlock: lang=%s", fcb.getInfo());
            out.append("<pre>");
            String info = fcb.getInfo();
            if (info != null && !info.isEmpty())
                out.append("<span class=\"lang-tag\">").append(esc(info)).append("</span>");
            out.append("<code>");
            String code = fcb.getLiteral();
            if (code != null) {
                if (code.endsWith("\n")) code = code.substring(0, code.length() - 1);
                out.append(info != null && !info.isEmpty()
                    ? CodeHighlighter.highlight(code, info) : esc(code));
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
            if (!(cn instanceof TableHead) && !(cn instanceof TableBody)
                    && !(cn instanceof TableRow) && !(cn instanceof TableCell)
                    && !(cn instanceof Strikethrough)) {
                LogUtil.v(TAG, "visit CustomNode: unknown %s", cn == null ? "null" : cn.getClass().getSimpleName());
            }
            if (cn instanceof TableHead) visitTableHead((TableHead) cn);
            else if (cn instanceof TableBody) visitTableBody((TableBody) cn);
            else if (cn instanceof TableRow) visitTableRow((TableRow) cn);
            else if (cn instanceof TableCell) visitTableCell((TableCell) cn);
            else if (cn instanceof Strikethrough) visitStrikethrough((Strikethrough) cn);
            else visitChildren(cn);
        }

        private void visitTable(TableBlock tb) {
            out.append("<div class=\"table-scroll\"><table>");
            visitChildren(tb);
            out.append("</table></div>");
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
