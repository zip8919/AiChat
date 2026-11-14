package xyz.zip8919.app.aichat;

import android.content.Context;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
    /**
     * 条目上限必须大于「一屏条数」（{@code MainActivity.INITIAL_VISIBLE_MESSAGES} = 40）。
     *
     * <p>原来 24 < 40：任何一次整页重建（切会话、切主题、从设置页返回）都必然
     * 前 16 条 miss，命中率被硬压到 60% 且长期稳定在这个值。那 16 条一字未改，
     * 却要重新跑 markdown + 代码高亮 + 公式，200ms~1.5s 的白屏时间就出在这里。
     * 真正的内存闸门是下面 2MB 的字节上限，条目数放宽到 48（40 + 8 缓冲）不影响它。
     */
    private static final int RENDER_CACHE_MAX_ENTRIES = 48;
    private static final int RENDER_CACHE_MAX_BYTES = 2 * 1024 * 1024;
    // accessOrder=true → entrySet() 按 LRU 顺序迭代（最久未用在前）；
    // contentToHtml 在 put 之前从前端手动淘汰。
    private static final Map<String, String> renderCache =
            Collections.synchronizedMap(new LinkedHashMap<String, String>(32, 0.75f, true));

    /**
     * 缓存生成时的颜色签名。
     *
     * <p>原来靠外部（MainActivity 的 ChangeListener）在切色时显式调用
     * {@link #clearRenderCache()}，而那是个<b>匿名监听器</b>，只被 AppearanceManager
     * 的弱引用列表持有 —— 一旦 GC 回收，切色就不再触发清理，缓存里的旧 HTML
     * （含旧颜色的公式 base64）会一直命中，表现为「公式要重启 App 才变色」。
     *
     * <p>现在把签名放进缓存自身：每次取用前比对，不一致就整体作废。这样无论
     * 谁触发的、有没有触发清理，都不可能读到上一个主题的渲染结果。
     */
    private static volatile String renderCacheSig = "";

    /**
     * 若颜色签名已变，清空 HTML 缓存与公式位图缓存。
     *
     * <p>所有读取缓存的入口都必须先调它。开销是 5 次 getEffectiveColor，
     * 相对整页渲染可以忽略。
     */
    private static void ensureCacheSig(Context ctx) {
        String sig;
        try {
            // 必须带 Context：无参 getInstance() 在 StorageManager 未初始化时会用错 basePath
            // 建出单例，之后整进程的外观读的都是那个错路径。
            sig = AppearanceManager.getInstance(ctx).colorSignature();
        } catch (Throwable t) {
            // 外观未初始化（极早期调用）：不清缓存，按原样继续
            return;
        }
        if (sig.equals(renderCacheSig)) return;
        synchronized (renderCache) {
            // 进锁再判一次：两个渲染线程可能同时发现签名变化
            if (sig.equals(renderCacheSig)) return;
            renderCache.clear();
            renderCacheSig = sig;
        }
        MathRenderer.clearCache();
        LogUtil.d(TAG, "render cache invalidated by color signature: %s", sig);
    }

    /**
     * 清空 HTML 缓存。
     *
     * <p>缓存 key 只有消息正文，不含外观。公式是预渲染 PNG（字色在出图时就画死了），
     * 切深浅色后如果命中旧缓存，页面上仍是旧颜色的公式图 —— 表现就是
     * 「公式颜色不立即变化，要切换会话重进才会变」。切色时先清缓存再重灌整页。
     */
    public static void clearRenderCache() {
        synchronized (renderCache) {
            renderCache.clear();
        }
        // 公式位图缓存一并清：它虽然按字色分 key，但旧图在 2 MB 上限里白占地方，
        // 而且一旦有人改了 key 的维度却忘了这里，就会复现「公式不换色」。
        MathRenderer.clearCache();
    }

    public static String buildConversationHtml(List<Message> messages, int from, Context ctx) {
        long startTs = System.currentTimeMillis();
        ensureCacheSig(ctx);
        int total = messages == null ? 0 : messages.size();
        int start = Math.max(0, Math.min(from, total));
        LogUtil.d(TAG, "buildConversationHtml: %d messages, render from %d (cache=%d)",
                total, start, MathRenderer.cacheSize());

        StringBuilder body = new StringBuilder();
        // 只渲染 [start, total)：renderMessagesHtml 把第二个参数当作起始索引，
        // 若把整个列表传进去，它会从头渲染全部消息、却把 data-idx 标成 start+i，
        // 于是长会话（>40 条）里所有消息索引整体偏移 start —— 长按菜单、复制、
        // 修改、删除、重试全部命中错误的那一条，上滑加载还会重复渲染已在本页的消息。
        body.append(renderMessagesHtml(messages == null ? null : messages.subList(start, total), start, ctx));
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
              .append("\" data-idx=\"").append(idx).append("\">");
            // 只发附件、不写字时没有气泡：空气泡会在附件卡片上方留一块蓝色空块
            String text = msg.content == null ? "" : msg.content;
            if (text.length() > 0) {
                sb.append("<div class=\"bubble\">").append(esc(text)).append("</div>");
            }
            sb.append(renderAttachmentCards(msg.attachments, index));
            // 失败卡片：摘要 + 查看详情 + 重试，紧跟在气泡下方
            if (msg.sendFailed) sb.append(renderErrorCard(msg, index));
            sb.append(renderMeta(msg));
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
               renderMeta(msg) +
               "<div class=\"ai-menu-wrap\"><button class=\"msg-menu-btn\" onclick=\"if(window.Android)Android.messageMenu('" + idx + "');event.stopPropagation();return false;\">" + MENU_DOTS + "</button></div></div>\n";
    }

    /**
     * 用户气泡下方的附件卡片。
     *
     * <p>独立于气泡存在，编辑正文不会碰到它——这正是「改一下消息附件就没了」的修法。
     * 文件名与「大小 · tokens」分两行：塞一行时 10 MB 的日志会把文件名挤成省略号，
     * 而大小恰恰是判断「这份文件发不发得出去」的唯一线索。
     *
     * <p>整张卡片可点，点开走 {@code Android.openAttachment(msgIndex, attIndex)}：
     * 传下标而不是内容——10 MB 的正文过一次 JsBridge 要复制两遍字符串，
     * 而且 WebView 里根本没有存它的必要。
     */
    static String renderAttachmentCards(List<AttachmentText.File> files, int msgIndex) {
        if (files == null || files.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"attach-list\">");
        for (int i = 0; i < files.size(); i++) {
            AttachmentText.File f = files.get(i);
            if (f == null) continue;
            sb.append("<div class=\"attach-item\" onclick=\"if(window.Android)Android.openAttachment('")
              .append(msgIndex).append("','").append(i).append("');event.stopPropagation();return false;\">");
            if (f.isImage()) {
                // 缩略图当背景图用：Chromium 30 上 object-fit 不可靠，background-size:cover 稳
                sb.append("<span class=\"attach-thumb\"");
                if (f.thumb.length() > 0) {
                    sb.append(" style=\"background-image:url(").append(f.thumb).append(")\"");
                }
                sb.append("></span>");
            } else {
                sb.append("<span class=\"attach-ico\"></span>");
            }
            sb.append("<span class=\"attach-body\">")
              .append("<span class=\"attach-name\">").append(esc(f.name)).append("</span>")
              .append("<span class=\"attach-sub\">")
              .append(esc(AttachmentText.sizeAndTokens(f))).append("</span>")
              .append(cutNote(f))
              .append("</span>")
              .append("<span class=\"attach-view\">查看 ›</span>")
              .append("</div>");
        }
        sb.append("</div>");
        return sb.toString();
    }

    /**
     * 附件卡片第三行：那一次实际发了多少。
     *
     * <p>数据来自 {@link AttachmentText.File#sentChars}——发送前由
     * {@link AttachmentText#recordSent} 写入并落盘。这里<b>绝不能</b>拿当前预算重算：
     * 那样用户一改设置，历史消息的提示就会跟着变，把「当时只发了 20 万字」
     * 悄悄改写成「全文发送」，反过来也会给当时全文发送的老消息凭空加上「已截断」。
     *
     * <p>{@code NOT_SENT}（没发过）和 {@code SENT_ALL}（全文发过）都返回空串：
     * 没截断就不该多一行噪音，没记录过更不该编一个数字出来。
     */
    private static String cutNote(AttachmentText.File f) {
        int cut = f.sentChars;
        if (cut < 0) return "";
        int total = f.chars();
        if (cut == 0) {
            return "<span class=\"attach-cut\">⚠ 超出字符预算，本次未发送正文（共 "
                    + formatTokens(total) + " 字符）</span>";
        }
        return "<span class=\"attach-cut\">⚠ 已截断，仅发送前 " + formatTokens(cut)
                + " / 共 " + formatTokens(total) + " 字符</span>";
    }

    /**
     * 消息底部的辅助信息：时间 · token · 生成速度。
     *
     * <p>三者都拿不到时返回空串——老会话读出来 timestamp 可能是 0、token 也全是 0，
     * 不能凭空多出一行「00:00 · 0 tokens」。
     *
     * <p>用户消息没有真实用量（服务端只在 assistant 轮返回），按字符数估算并用
     * {@code ~} 标注，避免把估算值伪装成精确值。
     */
    public static String renderMeta(Message msg) {
        if (msg == null) return "";
        StringBuilder sb = new StringBuilder();
        if (msg.timestamp > 0) {
            sb.append(formatClock(msg.timestamp));
        }

        int prompt = msg.promptTokens;
        int completion = msg.completionTokens;
        boolean estimated = msg.tokensEstimated;
        boolean interrupted = msg.isAssistant() && msg.interrupted;

        // 兜底估算：端点不报 usage、老会话、以及用户消息（服务端从来没给它统计过）。
        // 早先这里只估一个「总数」，用户看不出其中多少是上下文、多少是这次生成的，
        // 对账时无从下手。现在两侧分开：用户消息只有输入侧，AI 消息按能拿到的估。
        if (prompt <= 0 && completion <= 0 && msg.timestamp > 0) {
            if (msg.isUser()) {
                // 用户消息的「真实开销」含附件正文，只算气泡里那几个字会低估上千倍。
                // 分块累加而不是先拼成一个大字符串：10 MB 的附件再复制一份就是 20 MB。
                int est = AttachmentText.tokensOf(msg);
                if (est > 0) {
                    prompt = est;
                    estimated = true;
                }
            } else {
                String src = msg.apiText != null && msg.apiText.length() > 0
                        ? msg.apiText : msg.content;
                int est = estimateTokens(src);
                if (est > 0) {
                    completion = est;
                    estimated = true;
                }
            }
        }

        // 工具轮次：一条回复可能发了好几次请求（每次工具调用一轮）。
        // 逐轮拆分（1) 8.0k→120 · 2) …）会拉成一长串，这里只留次数当背景；
        // 明细仍存在 Message.rounds 里，统计页与调试侧照旧可用。
        if (msg.requestCount > 1) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(msg.requestCount).append(" 次请求");
        }

        // 合计 = 这次回复各轮输入累加 + 各轮输出累加。
        // 多轮时输入里同一份上下文被反复计费，输入和输出两个数分开看，
        // 用户没法回答「AI 这一整条回复到底花了多少」——所以合计必须单列。
        boolean hasTotal = msg.isAssistant() && prompt > 0 && completion > 0;

        // 输入侧 = 这条回复所有请求真正发给模型的上下文累加，不是本次回答的字数。
        // 缓存附在输入侧：它的分母就是 promptTokens，换个位置就不成立。
        // 有了合计的外层括号，缓存就不再自己套括号，否则两个右括号糊在一起：
        // 「共 32.4k（输入 30.1k（缓存 21.0k · 70%） · 输出 2.3k）」
        StringBuilder io = new StringBuilder();
        if (prompt > 0) {
            io.append("输入 ");
            if (estimated) io.append('~');
            io.append(formatTokens(prompt));
            if (msg.cachedTokens > 0) {
                int pct = Math.round(msg.cachedTokens * 100f / prompt);
                if (hasTotal) {
                    io.append(" · 缓存 ").append(formatTokens(msg.cachedTokens))
                      .append('/').append(pct).append('%');
                } else {
                    io.append("（缓存 ").append(formatTokens(msg.cachedTokens))
                      .append(" · ").append(pct).append("%）");
                }
            }
        }
        if (completion > 0) {
            if (io.length() > 0) io.append(" · ");
            io.append("输出 ");
            if (estimated) io.append('~');
            io.append(formatTokens(completion));
        }

        // 合计摆最前，括号里再拆输入/输出。单轮也摆：用户要的就是「一次问答总共多少」，
        // 只在多轮才给总数，会让人以为单轮的输入输出就是全部开销（其实是相加）。
        if (io.length() > 0) {
            if (sb.length() > 0) sb.append(" · ");
            if (hasTotal) {
                sb.append("共 ");
                if (estimated) sb.append('~');
                sb.append(formatTokens(prompt + completion));
                sb.append("（").append(io).append("）");
            } else {
                sb.append(io);
            }
        }

        // 速度只看生成侧，并且用端到端耗时（含工具轮）。打断/失败没有参考价值。
        if (msg.isAssistant() && !interrupted
                && completion > 0 && msg.finishedAt > msg.timestamp) {
            long elapsed = msg.finishedAt - msg.timestamp;
            if (elapsed >= 1000) {
                double speed = completion * 1000.0 / elapsed;
                if (sb.length() > 0) sb.append(" · ");
                if (estimated) sb.append('~');
                sb.append(String.format(Locale.US, "%.1f tok/s", speed));
            }
        }

        // 中断标记放最后：前面那串数字是「已经产生的用量」，这五个字说明
        // 「它为什么只有这么点」。没有标记的话，用户会拿它跟正常回复对比，
        // 以为模型变慢了或者统计漏记了。
        if (interrupted) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("已中断");
        }

        // 逐轮明细（1) 8.0k→120 · 2) …）已经去掉：一条回复里工具调用了五六次，
        // 那行会拉成一长串，用户真正要的只是「这次一共花了多少」。
        // 轮次数保留在主行的「N 轮」里，明细数据仍在 Message.rounds 中，
        // 需要时可由别处消费（统计页聚合走的是事件流，不依赖这里渲染）。

        StringBuilder out = new StringBuilder();
        if (sb.length() > 0) {
            out.append("<div class=\"msg-meta\">").append(esc(sb.toString())).append("</div>");
        }
        return out.toString();
    }

    /** 当天只显示时分，跨天补上日期，避免昨天的消息看起来像刚刚发的。 */
    private static String formatClock(long ts) {
        Calendar now = Calendar.getInstance();
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(ts);
        boolean sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
                && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR);
        SimpleDateFormat fmt = new SimpleDateFormat(
                sameDay ? "HH:mm" : "MM-dd HH:mm", Locale.getDefault());
        return fmt.format(new Date(ts));
    }

    /** 供附件摘要复用；三处数字必须同源。 */
    static String formatTokens(int n) {
        if (n < 1000) return String.valueOf(n);
        if (n < 1000000) return String.format(Locale.US, "%.1fk", n / 1000.0);
        return String.format(Locale.US, "%.1fM", n / 1000000.0);
    }

    /**
     * 本地 token 估算。端点返回 usage 时一律以返回值为准，这里只在拿不到用量时兜底。
     *
     * <p>系数按厂商文档：中文（含全角标点）约 0.6 token/字，英文与符号约 0.3 token/字符。
     * 早先按「字数 × 2/3」一刀切，纯中文消息会被高估一倍多，纯英文代码又被低估——
     * 附件动辄几十万 token，系数差一倍就是「发得出去」和「必然超限」的区别。
     *
     * <p>不同模型分词不同，这始终是估算：调用方必须打 {@code ~}，不能当账目。
     */
    static int estimateTokens(CharSequence text) {
        if (text == null || text.length() == 0) return 0;
        int wide = 0;
        int narrow = 0;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            // 代理对（emoji、扩展区生僻字）在 UTF-16 里占两个 char，按一个宽字符算，
            // 否则一条 emoji 密集的消息会被算成两倍的 token
            if (Character.isHighSurrogate(c) && i + 1 < n
                    && Character.isLowSurrogate(text.charAt(i + 1))) {
                i++;
                wide++;
            } else if (isWideChar(c)) {
                wide++;
            } else {
                narrow++;
            }
        }
        // 按 0.1 token 为单位做整数运算：wide*6 + narrow*3，再向上取整
        int tenths = wide * 6 + narrow * 3;
        return Math.max(1, (tenths + 9) / 10);
    }

    /** 中日韩字符与全角符号：这些按 0.6 token/字，其余按 0.3。 */
    private static boolean isWideChar(char c) {
        return (c >= 0x1100 && c <= 0x115F)      // 谚文字母
                || (c >= 0x2E80 && c <= 0x303E)  // 中日韩部首、标点
                || (c >= 0x3041 && c <= 0x33FF)  // 假名、注音、兼容字符
                || (c >= 0x3400 && c <= 0x4DBF)  // 扩展 A
                || (c >= 0x4E00 && c <= 0x9FFF)  // 基本区
                || (c >= 0xA000 && c <= 0xA4CF)  // 彝文
                || (c >= 0xAC00 && c <= 0xD7A3)  // 谚文音节
                || (c >= 0xF900 && c <= 0xFAFF)  // 兼容表意
                || (c >= 0xFE30 && c <= 0xFE4F)  // 兼容标点
                || (c >= 0xFF00 && c <= 0xFF60)  // 全角字符
                || (c >= 0xFFE0 && c <= 0xFFE6);
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
        return contentToHtml(text, ctx, true);
    }

    /** 这份正文是否已经在 HTML 缓存里（供预热路径避免重复渲染，注意会加锁）。 */
    static boolean isCached(String text) {
        if (text == null || text.length() == 0) return false;
        synchronized (renderCache) {
            return renderCache.containsKey(text);
        }
    }

    /**
     * cacheable=false：流式每 200ms 的新正文都会产生一个新 key，若照常写入，
     * 24 槽的 LRU 会被中间态轮占，历史消息缓存全被挤出，整页重灌时 40 条全 miss。
     */
    public static String contentToHtml(String text, Context ctx, boolean cacheable) {
        if (text == null || text.isEmpty()) return "";
        AppearanceManager am = null;
        try { am = AppearanceManager.getInstance(ctx); } catch (Throwable t) {}
        final long revAtStart = am != null ? am.getRevision() : -1L;
        ensureCacheSig(ctx);
        final String sigAtStart = renderCacheSig;
        String cached = renderCache.get(text);
        if (cached != null) {
            // 命中路径的二次校验必须零成本：开头 ensureCacheSig 已经算过一次全 token
            // 颜色签名，这里再算一遍 colorSignature() 会拖慢流式每 200ms 的高频命中。
            // 改用 volatile revision —— 任何外观改动都走 bump() 自增，一次 volatile 读
            // 就能发现「ensureCacheSig 到 get 之间恰好换肤」这个并发窗口。
            long revNow = am != null ? am.getRevision() : -1L;
            if (revAtStart == revNow && sigAtStart.equals(renderCacheSig)) return cached;
            ensureCacheSig(ctx);       // 清缓存并把签名推进到新值（put 分支据此不写旧色）
        }
        // 流式中间帧只渲染、不写公式位图缓存：增长中的裸命令每帧 key 都不同，
        // 写进去会把 2MB 上限挤满、淘汰稳定公式（整页重灌时全 miss）。
        final boolean transientFrame = !cacheable;
        boolean prevTransient = false;
        if (transientFrame) prevTransient = MathRenderer.beginTransient();
        try {
            long startTs = System.currentTimeMillis();
            boolean hasThinking = text.contains("[thinking]");
            boolean hasTool = text.contains(WebSearchTool.TRACE_START);
            String html = (hasThinking || hasTool)
                    ? contentWithBlocks(text, ctx)
                    : MarkdownPipeline.render(text, ctx);
            LogUtil.v(TAG, "contentToHtml: in=%d chars -> %d chars html in %d ms (thinking=%s, tool=%s)",
                    text.length(), html.length(), System.currentTimeMillis() - startTs,
                    hasThinking, hasTool);
            if (cacheable && html.length() <= RENDER_CACHE_MAX_HTML) {
                synchronized (renderCache) {
                    // 后台渲染期间若换了皮肤，这次用的是旧 fgColor；签名已变，
                    // 绝不能把旧色 HTML 写回缓存（否则整页重灌永久命中旧公式）。
                    if (!sigAtStart.equals(renderCacheSig)) return html;
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
        } finally {
            if (transientFrame) MathRenderer.endTransient(prevTransient);
        }
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
                    // 工具轮次中途会留下一段尚未闭合的 [thinking]，旧代码直接输出
                    // 不带 toggle 的裸块，于是「多一份无法折叠的思考内容」。
                    // 这里复用闭合分支的同一套折叠结构，流式阶段也能展开/收起。
                    String thinking = text.substring(ts + 10);
                    int thinkTok = estimateTokens(thinking);
                    // 流式每个分片都会整段重建 HTML，思考块的展开状态必须靠一个
                    // 稳定 key 跨重建恢复。取「开头 64 字」做指纹：流式内容只在
                    // 末尾增长，前缀不变；同一段思考在「未闭合 → 闭合」两种形态
                    // 下也拿到同一个 key，展开状态不会跳变。
                    // data-think-live=1 表示这段思考还在输出（未收到 [/thinking]）。
                    // JS 侧只对「仍在 tail 里」的 live 块自动展开；等闭合标记到达、
                    // 或本轮被 commit 进 base 后，同一块自动折叠。历史消息整页重建
                    // 时块直接落在 .content 里、不在 tail 内，一律折叠，不会误展开。
                    html.append("<div class=\"thinking-wrap\" data-think-key=\"")
                        .append(thinkingKey(thinking)).append("\" data-think-live=\"1\">")
                        .append("<div class=\"thinking-toggle\" data-tok=\"").append(thinkTok)
                        .append("\" onclick=\"toggleThinking(this)\">── 展开思考（")
                        .append(formatTokens(thinkTok)).append(" tokens）──</div>")
                        .append("<div class=\"thinking-block\" style=\"display:none\">")
                        .append(MarkdownPipeline.render(thinking, ctx))
                        .append("</div></div>");
                    break;
                }
                String thinking = text.substring(ts + 10, te);
                blocks++;
                // 用 token 而不是字数：用户对「这次思考花了多少」的直觉来自
                // 消息底部的 token 用量，两处单位一致才能横向比较。
                // 数值按源码估算（中文 0.6 / 英文 0.3 每字符），并写进 data-tok，
                // 折叠时由 JS 直接读回，避免展开/收起后数字跳变。
                int thinkTok = estimateTokens(thinking);
                // 已闭合（思考结束）：live=0，JS 会自动折叠，除非用户手动展开过。
                html.append("<div class=\"thinking-wrap\" data-think-key=\"")
                    .append(thinkingKey(thinking)).append("\" data-think-live=\"0\">")
                    .append("<div class=\"thinking-toggle\" data-tok=\"").append(thinkTok)
                    .append("\" onclick=\"toggleThinking(this)\">── 展开思考（")
                    .append(formatTokens(thinkTok)).append(" tokens）──</div>")
                    .append("<div class=\"thinking-block\" style=\"display:none\">")
                    .append(MarkdownPipeline.render(thinking, ctx))
                    .append("</div></div>");
                start = te + 11;
            } else {
                if (gs > start) html.append(MarkdownPipeline.render(text.substring(start, gs), ctx));
                int ge = findToolBlockEnd(text, gs + WebSearchTool.TRACE_START.length());
                if (ge == -1) {
                    // 未闭合的 [tool]：**不能丢正文**。
                    //
                    // 原来这里直接 break，`[tool]` 之后的整段回答永久消失（定稿走同一条
                    // 路径，重进会话也不恢复），而复制/编辑走的是 plainTextOf、尾部还在，
                    // 用户复制出来的内容和屏幕上看到的对不上。
                    // 两条现实触发路径：模型在正文里原样写出 "[tool]"；或工具 payload 里
                    // 反引号计数不配对，findToolBlockEnd 的代码态机再也找不到 [/tool]。
                    // 与上面 [thinking] 分支保持一致：把剩余内容当普通 markdown 渲染。
                    LogUtil.w(TAG, "tool trace block UNCLOSED at %d, render tail as text", gs);
                    String tail = text.substring(gs + WebSearchTool.TRACE_START.length());
                    if (tail.length() > 0) html.append(MarkdownPipeline.render(tail, ctx));
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
     * 思考块的稳定标识。用「前 64 个字符」的哈希而不是全文哈希：
     * 流式输出时文本在末尾增长，全文哈希每个分片都变，展开状态就永远对不上；
     * 前缀哈希在整个输出过程中保持不变，闭合后也一致。
     * 不同轮次的思考内容不同，前缀足以区分（极端同前缀的情况只是两块共用
     * 一个展开状态，不会丢内容）。
     */
    private static String thinkingKey(String thinking) {
        String head = thinking.length() > 64 ? thinking.substring(0, 64) : thinking;
        return Integer.toHexString(head.hashCode());
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
            // 不能返回空串：那等于这张卡片凭空消失，用户会以为「刚才明明有一张卡」。
            // 也不回显 payload——里面是工具结果原文，可能几百 KB，也可能带密钥。
            LogUtil.w(TAG, "renderToolBlock: bad JSON len=%d", payload == null ? 0 : payload.length());
            return "<div class=\"tool-card\"><div class=\"tool-card-head\">工具结果</div>"
                    + "<div class=\"tool-error\">结果格式异常，无法显示（"
                    + (payload == null ? 0 : payload.length()) + " 字符）</div></div>";
        }
        String tool = json.optString("tool", "");
        String error = json.optString("error", "");
        boolean isFetch = WebSearchTool.TOOL_FETCH.equals(tool);
        boolean isTime = WebSearchTool.TOOL_TIME.equals(tool);
        boolean isImageSearch = WebSearchTool.TOOL_IMAGE.equals(tool);

        StringBuilder out = new StringBuilder();
        out.append("<div class=\"tool-card\" data-tool-key=\"")
           .append(Integer.toHexString(payload.hashCode())).append("\">");
        // trace 超长被裁剪时留下的说明，见 TraceKit.cap
        if (TraceKit.TRIM_TOOL.equals(tool)) {
            out.append("<div class=\"tool-card-head tool-trim\">")
               .append(esc(json.optString("note", "更早的工具记录已收起")))
               .append("</div></div>");
            return out.toString();
        }
        if (error.length() > 0) {
            boolean isLimit = "limit".equals(tool);
            out.append("<div class=\"tool-card-head\">")
               .append(isLimit ? "工具调用已达上限"
                       : (isFetch ? "网页抓取失败"
                       : (isTime ? "获取时间失败"
                       : (isImageSearch ? "图片搜索失败" : "搜索失败"))))
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

        // 图片搜索：默认展开，图片本身就是内容——折叠起来等于什么都没给。
        // 这里也不用 .tool-body：那个 class 被 toggleTool/applyToolStates 接管，
        // 初始渲染时会被统一置成 display:none。
        if (isImageSearch) {
            String query = json.optString("query", "");
            JSONArray imgs = json.optJSONArray("images");
            int n = imgs == null ? 0 : imgs.length();
            out.append("<div class=\"tool-card-head\">图片搜索")
               .append(query.length() > 0 ? "：" + esc(query) : "")
               .append("（").append(n).append(" 张）</div>");
            if (n == 0) {
                String note = json.optString("note", "");
                out.append("<div class=\"tool-empty\">")
                   .append(esc(note.length() > 0 ? note : "没有找到图片")).append("</div>");
            } else {
                out.append("<div class=\"tool-imgs\">");
                for (int i = 0; i < n; i++) {
                    JSONObject item = imgs.optJSONObject(i);
                    if (item == null) continue;
                    String url = item.optString("image_url", "");
                    if (url.length() == 0) continue;
                    String title = item.optString("title", "");
                    // 缩略图用 background-image + cover：Chromium 30 没有 object-fit，
                    // 直接放 <img> 会被拉伸变形。加载失败就露出底色，不出现破图标。
                    out.append("<div class=\"tool-img-cell\" style=\"background-image:url('")
                       .append(escAttr(cssUrl(url))).append("')\"")
                       .append(" onclick=\"Android.openRemoteImage('")
                       .append(jsArg(url)).append("','").append(jsArg(title)).append("')\"")
                       .append("></div>");
                }
                out.append("</div>");
            }
            out.append("</div>");
            return out.toString();
        }

        String query = json.optString("query", "");
        int count = json.optInt("count", 0);
        JSONArray results = json.optJSONArray("results");
        int n = results == null ? 0 : results.length();
        // 非搜索类工具（HTML 执行、HTTP 请求等）没有 results，旧代码一律套「搜索（0 条）」，
        // 用户看到的就是一张莫名其妙的空搜索卡片。这里按工具类型分派。
        // tool 已在方法开头解析，此处直接复用
        boolean isSearch = tool.length() == 0 || WebSearchTool.TOOL_SEARCH.equals(tool);
        String content = json.optString("content", "");
        String head = isSearch
                ? "搜索" + (query.length() > 0 ? "：" + esc(query) : "")
                        + "（" + (count > 0 ? count : n) + " 条）"
                : "工具结果：" + esc(tool.length() > 0 ? tool : "未知工具")
                        + (query.length() > 0 ? " · " + esc(query) : "");
        out.append(collapsibleHead(head));
        out.append("<div class=\"tool-body\">");
        if (!isSearch && n == 0 && content.length() > 0) {
            // HTML / 代码执行类工具的结果正文直接展示，不再伪装成搜索结果
            out.append("<div class=\"tool-fetch-content\">").append(esc(content))
               .append("</div>");
        } else if (n == 0) {
            out.append("<div class=\"tool-empty\">")
               .append(isSearch ? "没有找到结果" : "（无输出）").append("</div>");
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

    /**
     * JS 单引号字符串字面量的转义。
     *
     * <p>图片地址与标题来自搜索结果，直接拼进 {@code onclick="Android.x('...')"}
     * 时，一个单引号就能截断语句、改掉整段 DOM 的事件绑定。HTML 属性用双引号包，
     * 所以双引号要转成实体，单引号要加反斜杠。
     */
    static String jsArg(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '\'': sb.append("\\'"); break;
                case '"': sb.append("&quot;"); break;
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '\n': case '\r': case '\t': sb.append(' '); break;
                // ES5 单引号字面量里 U+2028/2029 仍是行终止符，会截断 onclick
                case '\u2028': sb.append("\\u2028"); break;
                case '\u2029': sb.append("\\u2029"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 放进 {@code url('...')} 的地址。
     *
     * <p>单引号与括号会把 CSS 的字符串提前截断，进而让后面整条内联样式失效；
     * URL 里这几个字符本来就该是百分号编码。
     */
    static String cssUrl(String url) {
        if (url == null) return "";
        return url.replace("'", "%27")
                .replace("(", "%28")
                .replace(")", "%29")
                .replace(" ", "%20");
    }
}
