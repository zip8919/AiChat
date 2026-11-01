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
import org.json.JSONArray;
import org.json.JSONObject;
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


    /**
     * 构建整页 HTML，只渲染 {@code [from, messages.size())} 这一窗口内的消息。
     * 更早的消息由 {@link #renderMessagesHtml} 在用户上滑时按批追加，避免长对话
     * 一次把所有消息都塞进 WebView。
     */
    public static String buildConversationHtml(List<Message> messages, int from, Context ctx) {
        long startTs = System.currentTimeMillis();
        int total = messages == null ? 0 : messages.size();
        int start = Math.max(0, Math.min(from, total));
        LogUtil.d(TAG, "buildConversationHtml: %d messages, render from %d (cache=%d)",
                total, start, latexCache.size());

        StringBuilder body = new StringBuilder();
        body.append(renderMessagesHtml(messages, start, ctx));
        String page = buildPage(body.toString(), start);
        LogUtil.d(TAG, "buildConversationHtml done: rendered=%d, body=%d chars, page=%d chars in %d ms",
                total - start, body.length(), page.length(), System.currentTimeMillis() - startTs);
        return page;
    }

    /**
     * 渲染一批消息为 HTML 片段（不带 html/head 外壳），data-idx 使用绝对索引，
     * 保证与 messages 下标一致——消息菜单、删除、重定位都依赖这个索引。
     */
    public static String renderMessagesHtml(List<Message> messages, int startIndex, Context ctx) {
        if (messages == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < messages.size(); i++) {
            sb.append(renderMessageDiv(messages.get(i), startIndex + i, ctx));
        }
        return sb.toString();
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
            StringBuilder sb = new StringBuilder();
            sb.append("<div class=\"msg user").append(msg.sendFailed ? " failed" : "")
              .append("\" data-idx=\"").append(idx).append("\">")
              .append("<div class=\"bubble\">").append(esc(msg.content)).append("</div>");
            // 失败卡片：摘要 + 查看详情 + 重试，紧跟在气泡下方
            if (msg.sendFailed) sb.append(renderErrorCard(msg, index));
            sb.append("<div class=\"user-menu-wrap\"><button class=\"msg-menu-btn\" onclick=\"if(window.Android)Android.messageMenu('")
              .append(idx)
              .append("');event.stopPropagation();return false;\">⋯</button></div></div>\n");
            return sb.toString();
        }
        String html = contentToHtml(msg.content, ctx);
        return "<div class=\"msg ai\" data-idx=\"" + idx + "\">" +
               "<div class=\"content\">" + html + "</div>" +
               // 来源区在 .content 之外，独立折叠；不参与正文，也不进入请求体
               "<div class=\"sources\">" + renderSourcesInner(msg.sources) + "</div>" +
               // 工具轮次中途失败：保留已展示的思考/工具卡片，卡片提供「从出错处重试」
               (msg.sendFailed ? renderAssistantErrorCard(msg, index) : "") +
               "<div class=\"ai-menu-wrap\"><button class=\"msg-menu-btn\" onclick=\"if(window.Android)Android.messageMenu('" + idx + "');event.stopPropagation();return false;\">⋯</button></div></div>\n";
    }

    /**
     * 渲染来源区内部 HTML（默认折叠，点标题栏展开）。
     * 供整页渲染与流式结束后的 {@code updateLastSources} 共用。
     */
    public static String renderSourcesInner(List<Message.Source> sources) {
        if (sources == null || sources.isEmpty()) return "";
        int n = Math.min(sources.size(), ConversationManager.MAX_SOURCES);
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"sources-block\">");
        sb.append("<div class=\"sources-head\" onclick=\"toggleSources(this);event.stopPropagation();return false;\">")
          .append("来源（").append(n).append("）<span class=\"src-arrow\">▶</span></div>");
        sb.append("<div class=\"sources-body\" style=\"display:none\">");
        int shown = 0;
        for (int i = 0; i < sources.size() && shown < n; i++) {
            Message.Source s = sources.get(i);
            if (s == null || s.url == null || s.url.length() == 0) continue;
            String title = s.title == null || s.title.length() == 0 ? s.url : s.title;
            sb.append("<div class=\"src-item\"><a href=\"").append(escAttr(s.url)).append("\">")
              .append(esc(title)).append("</a></div>");
            shown++;
        }
        sb.append("</div></div>");
        return shown == 0 ? "" : sb.toString();
    }

    /** 失败用户消息下方的错误卡片：摘要 + 「查看详情」+「重试」。 */
    public static String renderErrorCard(Message msg, int index) {
        if (msg == null || !msg.sendFailed) return "";
        String idx = String.valueOf(index);
        return "<div class=\"error-card\">" +
               "<div class=\"err-title\">请求失败</div>" +
               "<div class=\"err-summary\">" + esc(errorSummary(msg.errorDetail)) + "</div>" +
               "<div class=\"err-actions\">" +
               "<button class=\"err-btn\" onclick=\"if(window.Android)Android.showErrorDetail('" + idx +
               "');event.stopPropagation();return false;\">查看详情</button>" +
               "<button class=\"err-btn retry\" onclick=\"if(window.Android)Android.retryFailedUserMessage('" + idx +
               "');event.stopPropagation();return false;\">重试</button>" +
               "</div></div>";
    }

    /** 工具轮次中途失败时，挂在 AI 气泡下方的错误卡片（可保留上下文继续重试）。 */
    public static String renderAssistantErrorCard(Message msg, int index) {
        if (msg == null || !msg.sendFailed) return "";
        String idx = String.valueOf(index);
        boolean canResume = msg.toolHistory != null && msg.toolHistory.length() > 2;
        return "<div class=\"error-card inline\">" +
               "<div class=\"err-title\">回复中断</div>" +
               "<div class=\"err-summary\">" + esc(errorSummary(msg.errorDetail)) + "</div>" +
               "<div class=\"err-actions\">" +
               "<button class=\"err-btn\" onclick=\"if(window.Android)Android.showErrorDetail('" + idx +
               "');event.stopPropagation();return false;\">查看详情</button>" +
               (canResume
                   ? "<button class=\"err-btn retry\" onclick=\"if(window.Android)Android.retryFailedAssistant('"
                     + idx + "');event.stopPropagation();return false;\">从出错处重试</button>"
                   : "") +
               "</div></div>";
    }

    /** 从失败详情里挑一行做卡片摘要：优先 HTTP 状态 + 提供商错误，其次异常消息。 */
    private static String errorSummary(String detail) {
        if (detail == null || detail.trim().length() == 0) return "请求未完成";
        String http = "", prov = "", exc = "";
        String[] lines = detail.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            int p = l.indexOf('：');
            String v = p >= 0 ? l.substring(p + 1).trim() : "";
            if (l.startsWith("HTTP 状态")) http = v;
            else if (l.startsWith("提供商错误")) prov = v;
            else if (l.startsWith("异常消息")) exc = v;
        }
        StringBuilder sb = new StringBuilder();
        if (http.length() > 0) sb.append("HTTP ").append(http);
        String extra = prov.length() > 0 ? prov : exc;
        if (extra.length() > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(extra);
        }
        String s = sb.toString();
        if (s.length() > 120) s = s.substring(0, 120) + "…";
        return s.length() == 0 ? "请求未完成" : s;
    }

    public static String contentToHtml(String text, Context ctx) {
        if (text == null || text.isEmpty()) return "";
        String cached = renderCache.get(text);
        if (cached != null) return cached;
        long startTs = System.currentTimeMillis();
        boolean hasThinking = text.contains("[thinking]");
        boolean hasTool = text.contains(WebSearchTool.TRACE_START);
        String html = (hasThinking || hasTool)
                ? contentWithBlocks(text, ctx)
                : renderMarkdown(text, ctx);
        LogUtil.v(TAG, "contentToHtml: in=%d chars -> %d chars html in %d ms (thinking=%s, tool=%s)",
                text.length(), html.length(), System.currentTimeMillis() - startTs,
                hasThinking, hasTool);
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

    private static String contentWithBlocks(String text, Context ctx) {
        StringBuilder html = new StringBuilder();
        int start = 0;
        int blocks = 0;
        while (start < text.length()) {
            int ts = indexOfOutsideCode(text, "[thinking]", start);
            int gs = indexOfOutsideCode(text, WebSearchTool.TRACE_START, start);
            boolean thinkingNext = ts != -1 && (gs == -1 || ts < gs);
            if (ts == -1 && gs == -1) {
                html.append(renderMarkdown(text.substring(start), ctx));
                break;
            }
            if (thinkingNext) {
                if (ts > start) html.append(renderMarkdown(text.substring(start, ts), ctx));
                int te = indexOfOutsideCode(text, "[/thinking]", ts + 10);
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
            } else {
                if (gs > start) html.append(renderMarkdown(text.substring(start, gs), ctx));
                int ge = findToolBlockEnd(text, gs + WebSearchTool.TRACE_START.length());
                if (ge == -1) {
                    // Unclosed tool block (should not happen): drop the remainder.
                    LogUtil.w(TAG, "tool trace block UNCLOSED at %d, dropping tail", gs);
                    break;
                }
                String payload = text.substring(gs + WebSearchTool.TRACE_START.length(), ge);
                html.append(renderToolBlock(payload));
                blocks++;
                start = ge + WebSearchTool.TRACE_END.length();
            }
        }
        LogUtil.v(TAG, "contentWithBlocks: %d blocks rendered", blocks);
        return html.toString();
    }

    /**
     * 从 {@code from} 开始查找 {@code needle}，跳过围栏代码块与行内代码。
     * 代码示例里出现的 {@code [tool]} / {@code [thinking]} 不能被当成展示标记，
     * 否则正文会在标记处被截断。
     */
    private static int indexOfOutsideCode(String text, String needle, int from) {
        int i = Math.max(0, from);
        int len = text.length();
        boolean inFence = false, inInline = false;
        char fenceChar = 0;
        int fenceLen = 0, inlineRun = 0;
        while (i < len) {
            if (i == 0 || text.charAt(i - 1) == '\n') {
                int j = i, indent = 0;
                while (j < len && text.charAt(j) == ' ' && indent < 3) { j++; indent++; }
                if (inFence) {
                    if (j < len && text.charAt(j) == fenceChar) {
                        int run = 0, k = j;
                        while (k < len && text.charAt(k) == fenceChar) { run++; k++; }
                        int m = k;
                        while (m < len && (text.charAt(m) == ' ' || text.charAt(m) == '\t')) m++;
                        if (run >= fenceLen && (m >= len || text.charAt(m) == '\n')) {
                            i = (m < len) ? m + 1 : m;
                            inFence = false;
                            continue;
                        }
                    }
                    i++;
                    continue;
                }
                if (j < len && (text.charAt(j) == '`' || text.charAt(j) == '~')) {
                    char cc = text.charAt(j);
                    int run = 0, k = j;
                    while (k < len && text.charAt(k) == cc) { run++; k++; }
                    if (run >= 3) {
                        inFence = true;
                        fenceChar = cc;
                        fenceLen = run;
                        int nl = text.indexOf('\n', k);
                        i = (nl < 0) ? len : nl + 1;
                        continue;
                    }
                }
            }
            if (inFence) {
                i++;
                continue;
            }
            char c = text.charAt(i);
            if (c == '`') {
                int run = 0, k = i;
                while (k < len && text.charAt(k) == '`') { run++; k++; }
                if (!inInline) { inInline = true; inlineRun = run; }
                else if (run == inlineRun) { inInline = false; }
                i = k;
                continue;
            }
            if (!inInline && text.startsWith(needle, i)) return i;
            i++;
        }
        return -1;
    }

    /**
     * 从 {@code from} 开始找工具块的结束标记。工具结果的正文里可能带 {@code [/tool]}
     * 字样（旧版本数据），取到的是假的结束位置时 payload 不是合法 JSON，继续往后找。
     */
    private static int findToolBlockEnd(String text, int from) {
        int ge = indexOfOutsideCode(text, WebSearchTool.TRACE_END, from);
        while (ge != -1) {
            String payload = text.substring(from, ge).trim();
            if (payload.length() == 0) return ge;
            if (payload.charAt(0) == '{') {
                try {
                    new JSONObject(payload);
                    return ge;
                } catch (Exception ignored) {
                    // 不是完整 JSON：多半是正文里出现了 [/tool]，继续找下一个
                }
            }
            ge = indexOfOutsideCode(text, WebSearchTool.TRACE_END,
                    ge + WebSearchTool.TRACE_END.length());
        }
        return -1;
    }

    /**
     * 去掉 {@code [thinking]...[/thinking]} 与 {@code [tool]...[/tool]} 展示块，
     * 保留全部正文（不渲染）。用于复制、编辑、选择文本等「取纯文本」场景。
     * 与 {@code ApiClient.removeThinkingContent} 不同，这里跳过代码块里的标记，
     * 也不会因为某个块未闭合就把后面的正文整段丢掉。
     */
    public static String plainTextOf(String content) {
        if (content == null || content.length() == 0) return "";
        StringBuilder out = new StringBuilder(content.length());
        int start = 0;
        while (start < content.length()) {
            int ts = indexOfOutsideCode(content, "[thinking]", start);
            int gs = indexOfOutsideCode(content, WebSearchTool.TRACE_START, start);
            boolean thinkingNext = ts != -1 && (gs == -1 || ts < gs);
            if (ts == -1 && gs == -1) {
                out.append(content, start, content.length());
                break;
            }
            int at = thinkingNext ? ts : gs;
            if (at > start) out.append(content, start, at);
            if (thinkingNext) {
                int te = indexOfOutsideCode(content, "[/thinking]", at + 10);
                if (te == -1) break;          // 未闭合：丢弃剩余
                start = te + 11;
            } else {
                int ge = findToolBlockEnd(content, at + WebSearchTool.TRACE_START.length());
                if (ge == -1) break;
                start = ge + WebSearchTool.TRACE_END.length();
            }
        }
        return out.toString().trim();
    }

    /** Render one [tool]...[/tool] payload (a JSON object) as an escaped HTML card. */
    private static String renderToolBlock(String payload) {
        JSONObject json;
        try {
            json = new JSONObject(payload);
        } catch (Exception e) {
            LogUtil.w(TAG, "renderToolBlock: bad JSON len=%d", payload == null ? 0 : payload.length());
            return "";
        }
        String tool = json.optString("tool", "");
        String error = json.optString("error", "");
        boolean isFetch = WebSearchTool.TOOL_FETCH.equals(tool);
        boolean isTime = WebSearchTool.TOOL_TIME.equals(tool);

        StringBuilder out = new StringBuilder();
        out.append("<div class=\"tool-card\" data-tool-key=\"")
           .append(Integer.toHexString(payload.hashCode())).append("\">");
        if (error.length() > 0) {
            out.append("<div class=\"tool-card-head\">")
               .append(isFetch ? "网页抓取失败" : (isTime ? "获取时间失败" : "搜索失败")).append("</div>")
               .append("<div class=\"tool-error\">").append(esc(error)).append("</div>")
               .append("</div>");
            return out.toString();
        }

        // 时间工具：一行卡片，不折叠
        if (isTime) {
            String dt = json.optString("datetime", "");
            String wd = json.optString("weekday", "");
            String tz = json.optString("timezone", "");
            StringBuilder line = new StringBuilder();
            if (dt.length() > 0) line.append(dt);
            if (wd.length() > 0) { if (line.length() > 0) line.append(' '); line.append(wd); }
            if (tz.length() > 0) { if (line.length() > 0) line.append("（"); line.append(tz).append("）"); }
            out.append("<div class=\"tool-card-head\">当前时间：")
               .append(esc(line.toString())).append("</div></div>");
            return out.toString();
        }

        if (isFetch) {
            String title = json.optString("title", "");
            String url = json.optString("url", "");
            String head = "网页抓取" + (title.length() > 0 ? "：" + esc(title)
                    : (url.length() > 0 ? "：" + esc(url) : ""));
            out.append(collapsibleHead(head));
            out.append("<div class=\"tool-body\">");
            appendUrlLine(out, url, "", "");
            String content = json.optString("content", "");
            out.append("<div class=\"tool-fetch-content\">").append(esc(content));
            if (json.optBoolean("truncated", false)) out.append(" ...（已截断）");
            out.append("</div>");
            out.append("</div>");
            out.append("</div>");
            return out.toString();
        }

        String query = json.optString("query", "");
        int count = json.optInt("count", 0);
        JSONArray results = json.optJSONArray("results");
        int n = results == null ? 0 : results.length();
        out.append(collapsibleHead("搜索"
           + (query.length() > 0 ? "：" + esc(query) : "")
           + "（" + (count > 0 ? count : n) + " 条）"));
        out.append("<div class=\"tool-body\">");
        if (n == 0) {
            out.append("<div class=\"tool-empty\">没有找到结果</div>");
        } else {
            for (int i = 0; i < n; i++) {
                JSONObject item = results.optJSONObject(i);
                if (item == null) continue;
                out.append("<div class=\"tool-item\">");
                String url = item.optString("url", "");
                String title = item.optString("title", "");
                if (url.length() > 0) {
                    out.append("<a class=\"tool-title\" href=\"").append(escAttr(url)).append("\">")
                       .append(esc(title.length() > 0 ? title : url)).append("</a>");
                } else {
                    out.append("<span class=\"tool-title\">").append(esc(title)).append("</span>");
                }
                appendUrlLine(out, url, item.optString("site", ""), item.optString("date", ""));
                String summary = item.optString("summary", "");
                if (summary.length() > 0) {
                    out.append("<div class=\"tool-summary\">").append(esc(summary)).append("</div>");
                }
                out.append("</div>");
            }
        }
        out.append("</div>");
        out.append("</div>");
        return out.toString();
    }

    /** Clickable card header whose sibling .tool-body is hidden by default. */
    private static String collapsibleHead(String head) {
        return "<div class=\"tool-card-head tool-head-click\" onclick=\"toggleTool(this)\">"
                + "<span class=\"tool-arrow\">▶</span> " + head
                + " <span class=\"tool-hint\">（点击展开）</span></div>";
    }

    private static void appendUrlLine(StringBuilder out, String url, String site, String date) {
        StringBuilder meta = new StringBuilder();
        if (site.length() > 0) meta.append(site);
        if (date.length() > 0) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append(date);
        }
        if (url.length() > 0) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append(url);
        }
        if (meta.length() > 0) {
            out.append("<div class=\"tool-meta\">").append(esc(meta.toString())).append("</div>");
        }
    }

    private static String renderMarkdown(String text, Context ctx) {
        List<String> mathTags = new ArrayList<>();
        float density = ctx.getResources().getDisplayMetrics().density;

        // Mask fenced code blocks and inline code spans first, so the math /
        // extension preprocessing below can never rewrite their contents.
        List<String> codeBlocks = new ArrayList<>();
        text = protectCode(text, codeBlocks);

        // 0. Protect raw SVG blocks from markdown parsing — commonmark doesn't treat <svg> as HTML block
        List<String> svgBlocks = new ArrayList<>();
        text = extractAndProtectSvg(text, svgBlocks);

        text = extractAndRenderCe(text, density, mathTags);   // \ce → LaTeX → jlatexmath image
        text = extractAndRenderLatex(text, density, mathTags);
        text = renderBareLatexCommands(text, density, mathTags);
        text = preProcessExtensions(text);
        text = restoreCode(text, codeBlocks);
        Node document = PARSER.parse(text);
        StringBuilder html = new StringBuilder();
        document.accept(new HtmlVisitor(html));
        String result = backfillPlaceholders(html.toString(), mathTags, svgBlocks);
        LogUtil.v(TAG, "renderMarkdown: %d -> %d chars, %d math formulas",
                text.length(), result.length(), mathTags.size());
        return result;
    }

    private static final char CODE_MASK_START = '\uE000';
    private static final char CODE_MASK_END = '\uE001';

    // Replace fenced code blocks (``` / ~~~) and inline code spans (`...`) with
    // opaque sentinels so preprocessing leaves them byte-for-byte intact. The
    // originals are restored just before commonmark parses, so code blocks still
    // render as code and never have their contents interpreted as markdown/math.
    private static String protectCode(String text, List<String> codeBlocks) {
        StringBuilder out = new StringBuilder(text.length() + 64);
        int i = 0, len = text.length();
        boolean inFence = false;
        char fenceChar = 0;
        int fenceLen = 0;
        int fenceStart = -1;
        while (i < len) {
            // ---- 围栏代码块内部 ----
            // 关键：块内内容一个字都不能写进 out，否则它会在 restoreCode 之后
            // 既留在原地（被 commonmark 当成 HTML 块渲染）又随哨兵重复出现。
            // 这里只在块结束时写一个哨兵，块内容整体保存在 codeBlocks 里。
            if (inFence) {
                if (i == 0 || text.charAt(i - 1) == '\n') {
                    int j = i, indent = 0;
                    while (j < len && text.charAt(j) == ' ' && indent < 3) { j++; indent++; }
                    if (j < len && text.charAt(j) == fenceChar) {
                        int run = 0, k = j;
                        while (k < len && text.charAt(k) == fenceChar) { run++; k++; }
                        int m = k;
                        while (m < len && (text.charAt(m) == ' ' || text.charAt(m) == '\t')) m++;
                        if (run >= fenceLen && (m >= len || text.charAt(m) == '\n')) {
                            int end = (m < len) ? m + 1 : m;
                            codeBlocks.add(text.substring(fenceStart, end));
                            out.append(CODE_MASK_START).append(codeBlocks.size() - 1).append(CODE_MASK_END);
                            i = end;
                            inFence = false;
                            continue;
                        }
                    }
                }
                int nl = text.indexOf('\n', i);
                i = (nl < 0) ? len : nl + 1;
                continue;
            }

            if (i == 0 || text.charAt(i - 1) == '\n') {
                int j = i, indent = 0;
                while (j < len && text.charAt(j) == ' ' && indent < 3) { j++; indent++; }
                if (j < len && (text.charAt(j) == '`' || text.charAt(j) == '~')) {
                    char cc = text.charAt(j);
                    int run = 0, k = j;
                    while (k < len && text.charAt(k) == cc) { run++; k++; }
                    if (run >= 3) {
                        inFence = true;
                        fenceChar = cc;
                        fenceLen = run;
                        fenceStart = i;
                        int nl = text.indexOf('\n', k);
                        i = (nl < 0) ? len : nl + 1;
                        continue;
                    }
                }
            }
            if (!inFence && text.charAt(i) == '`') {
                int run = 0, k = i;
                while (k < len && text.charAt(k) == '`') { run++; k++; }
                int closeEnd = -1, s = k;
                while (s < len) {
                    if (text.charAt(s) == '`') {
                        int r2 = 0, t = s;
                        while (t < len && text.charAt(t) == '`') { r2++; t++; }
                        if (r2 == run) { closeEnd = t; break; }
                        s = t;
                    } else {
                        s++;
                    }
                }
                if (closeEnd >= 0) {
                    codeBlocks.add(text.substring(i, closeEnd));
                    out.append(CODE_MASK_START).append(codeBlocks.size() - 1).append(CODE_MASK_END);
                    i = closeEnd;
                } else {
                    out.append(text, i, k);
                    i = k;
                }
                continue;
            }
            out.append(text.charAt(i));
            i++;
        }
        if (inFence && fenceStart >= 0) {
            codeBlocks.add(text.substring(fenceStart));
            out.append(CODE_MASK_START).append(codeBlocks.size() - 1).append(CODE_MASK_END);
        }
        return out.toString();
    }

    private static String restoreCode(String text, List<String> codeBlocks) {
        if (codeBlocks.isEmpty()) return text;
        StringBuilder out = new StringBuilder(text.length());
        int i = 0, len = text.length();
        while (i < len) {
            char c = text.charAt(i);
            if (c == CODE_MASK_START) {
                int j = i + 1, idx = 0;
                boolean any = false;
                while (j < len && text.charAt(j) >= '0' && text.charAt(j) <= '9') {
                    idx = idx * 10 + (text.charAt(j) - '0');
                    any = true;
                    j++;
                }
                if (any && j < len && text.charAt(j) == CODE_MASK_END && idx < codeBlocks.size()) {
                    out.append(codeBlocks.get(idx));
                    i = j + 1;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static boolean isWhitespaceChar(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f';
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
                String svgHtml = svgBlocks.get(idx);
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
                    && !isWhitespaceChar(text.charAt(i + 1))
                    && (i == 0 || text.charAt(i - 1) != '$')) {
                // Closing delimiter must sit on the same line, and the span must
                // not begin/end with whitespace — otherwise a stray "$" would
                // swallow unrelated text up to the next unrelated "$".
                int end = i + 1;
                while (end < text.length() && text.charAt(end) != '$'
                        && text.charAt(end) != '\n') end++;
                if (end < text.length() && text.charAt(end) == '$' && end > i + 1
                        && !isWhitespaceChar(text.charAt(end - 1))) {
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

    private static String buildPage(String bodyHtml, int olderCount) {
        // olderCount = 尚未渲染的更早消息条数；>0 时顶部显示上滑提示
        String hint = olderCount > 0
                ? "<div id=\"older-hint\" onclick=\"loadOlderNow()\">上滑加载更早的 "
                        + olderCount + " 条消息</div>"
                : "<div id=\"older-hint\" style=\"display:none\"></div>";
        return "<!DOCTYPE html>\n<html>\n<head>\n" +
               "<meta charset=\"utf-8\">\n" +
               "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,user-scalable=no\">\n" +
               "<style>\n" + RendererAssets.CSS + "\n</style>\n" +
               "</head>\n<body>\n" +
               hint +
               "<div id=\"msgs\">\n" + bodyHtml + "\n</div>\n" +
               "<script>\n" + RendererAssets.JS + "\n</script>\n" +
               "</body>\n</html>";
    }

    public static String esc(String s) {
        return Html.esc(s);
    }

    public static String escAttr(String s) {
        return Html.escAttr(s);
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
