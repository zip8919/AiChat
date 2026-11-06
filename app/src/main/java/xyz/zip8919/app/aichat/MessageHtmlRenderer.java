package xyz.zip8919.app.aichat;

import android.content.Context;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 消息卡片渲染：把一条 {@link Message} 变成聊天页里的一段 HTML。
 *
 * <p>职责边界：本类只管「消息结构」——思考块、工具卡片、来源列表、错误卡片，
 * 以及整页骨架的组装。Markdown 解析在 {@link MarkdownPipeline}，公式在
 * {@link MathRenderer}，CSS/JS 在 {@link RendererAssets}。
 *
 * <p>渲染缓存（{@link #renderCache}）按消息正文做 key：切设置、从别的页面返回都会
 * 重刷整页，正文没变的直接命中缓存，不必重跑一遍 markdown 流水线。
 */
public class MessageHtmlRenderer {
    private static final String TAG = "HtmlRenderer";

    /**
     * 消息菜单按钮里的三个点。用 CSS 画而不是写 U+22EF「⋯」：4.3/4.4 的系统字体没有
     * 该字形，实测会落到缺字方框，用户认不出这是按钮。样式见 RendererAssets 的 .mdot。
     * 修改时两边必须同步（appendAiDiv 里是字符串拼接，没法直接引用本常量）。
     */
    static final String MENU_DOTS =
            "<span class=\"mdot\"></span><span class=\"mdot\"></span><span class=\"mdot\"></span>";

    // Per-message HTML render cache: bounded LRU, evicted by entry count and bytes.
    // Streaming throttling produces a new key every tick with near-full text, so
    // oversized renders (e.g. base64 math images) are not cached — otherwise up
    // to 24 near-identical full-message copies stay in memory for good.
    private static final int RENDER_CACHE_MAX_HTML = 256 * 1024;
    private static final int RENDER_CACHE_MAX_ENTRIES = 24;
    private static final int RENDER_CACHE_MAX_BYTES = 2 * 1024 * 1024;
    // accessOrder=true → entrySet() 按 LRU 顺序迭代（最久未用在前）；
    // contentToHtml 在 put 之前从前端手动淘汰。
    private static final Map<String, String> renderCache =
            Collections.synchronizedMap(new LinkedHashMap<String, String>(32, 0.75f, true));

    public static String buildConversationHtml(List<Message> messages, int from, Context ctx) {
        long startTs = System.currentTimeMillis();
        int total = messages == null ? 0 : messages.size();
        int start = Math.max(0, Math.min(from, total));
        LogUtil.d(TAG, "buildConversationHtml: %d messages, render from %d (cache=%d)",
                total, start, MathRenderer.cacheSize());

        StringBuilder body = new StringBuilder();
        body.append(renderMessagesHtml(messages, start, ctx));
        String page = MarkdownPipeline.buildPage(body.toString(), start);
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
              .append("');event.stopPropagation();return false;\">")
              .append(MENU_DOTS)
              .append("</button></div></div>\n");
            return sb.toString();
        }
        String html = contentToHtml(msg.content, ctx);
        return "<div class=\"msg ai\" data-idx=\"" + idx + "\">" +
               "<div class=\"content\">" + html + "</div>" +
               // 来源区在 .content 之外，独立折叠；不参与正文，也不进入请求体
               "<div class=\"sources\">" + renderSourcesInner(msg.sources) + "</div>" +
               // 工具轮次中途失败：保留已展示的思考/工具卡片，卡片提供「从出错处重试」
               (msg.sendFailed ? renderAssistantErrorCard(msg, index) : "") +
               "<div class=\"ai-menu-wrap\"><button class=\"msg-menu-btn\" onclick=\"if(window.Android)Android.messageMenu('" + idx + "');event.stopPropagation();return false;\">" + MENU_DOTS + "</button></div></div>\n";
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
                   // 没有工具历史（手动中断、首轮就断）：给一个重新回复入口，
                   // 否则这条消息只能看不能动
                   : "<button class=\"err-btn retry\" onclick=\"if(window.Android)Android.retryInterrupted('"
                     + idx + "');event.stopPropagation();return false;\">重新回复</button>") +
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
                : MarkdownPipeline.render(text, ctx);
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
                html.append(MarkdownPipeline.render(text.substring(start), ctx));
                break;
            }
            if (thinkingNext) {
                if (ts > start) html.append(MarkdownPipeline.render(text.substring(start, ts), ctx));
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
                    .append(MarkdownPipeline.render(thinking, ctx))
                    .append("</div></div>");
                start = te + 11;
            } else {
                if (gs > start) html.append(MarkdownPipeline.render(text.substring(start, gs), ctx));
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
                if (te == -1) {
                    // 未闭合（手动打断、或模型没写结束标记）：只丢掉开标签，保留后面的
                    // 正文。原来这里直接 break，等于把「复制 / 选择文本 / 编辑」拿到的
                    // 内容砍掉一大截，和屏幕上看到的不一致。
                    out.append(content, at + 10, content.length());
                    break;
                }
                start = te + 11;
            } else {
                int ge = findToolBlockEnd(content, at + WebSearchTool.TRACE_START.length());
                if (ge == -1) {
                    // 同上：工具块没闭合时保留 payload，不要让整段回答凭空消失
                    out.append(content, at + WebSearchTool.TRACE_START.length(), content.length());
                    break;
                }
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
            boolean isLimit = "limit".equals(tool);
            out.append("<div class=\"tool-card-head\">")
               .append(isLimit ? "工具调用已达上限"
                       : (isFetch ? "网页抓取失败" : (isTime ? "获取时间失败" : "搜索失败")))
               .append("</div>")
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
    public static String esc(String s) {
        return Html.esc(s);
    }

    public static String escAttr(String s) {
        return Html.escAttr(s);
    }
}
