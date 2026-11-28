package xyz.zip8919.app.aichat;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 单轮响应解析：把 OpenAI 兼容端点的「非流式 JSON」或「SSE 分片」统一读成
 * 展示文本、正文、tool_calls 三样东西。
 *
 * <p>从 MainActivity 的请求线程里抽出（约 230 行）。这里只做协议层的事，
 * 不认识 Message、不碰 WebView、不判断重试策略——因此可以脱离 Android 单测：
 * 喂一段假 SSE 字节流，断言产出的 display/content/toolCalls 即可。
 *
 * <p>两个容易踩的点，都在这里收口：
 * <ul>
 *   <li>{@code content:""} 会跟在每个 reasoning 分片后面。只有<b>非空</b> content
 *       才算思考阶段结束，否则「展开思考（N 字）」会停在一两个字上；</li>
 *   <li>工具调用是分片累积的（id/name 只在第一片，arguments 逐片拼接），
 *       必须走 {@link ToolCalls#accumulate} 而不是直接取最后一片。</li>
 * </ul>
 */
final class SseRound {

    private static final String TAG = "SseRound";

    /** 解析结果。display 含 [thinking]/[tool] 展示块，content 是纯正文。 */
    static final class Result {
        final StringBuilder display = new StringBuilder();
        final StringBuilder content = new StringBuilder();
        /**
         * 纯推理文本（不含 [thinking] 标记）。
         * DeepSeek thinking 模式要求 assistant(tool_calls) 把这一轮的 reasoning_content
         * 原样传回，否则后续轮次直接 400；展示用的 display 带标记，不能拿来回传。
         */
        final StringBuilder reasoning = new StringBuilder();
        final JSONArray toolCalls = new JSONArray();
        /**
         * 服务端返回的用量对象（prompt_tokens / completion_tokens / total_tokens），
         * 没请求或端点不支持时为 null。流式下只出现在最后一帧。
         */
        JSONObject usage;
        int chunks;
        int thinkingChunks;
        int contentChunks;
        /**
         * 推理 token 数（来自 {@code usage.completion_tokens_details.reasoning_tokens}
         * 或 {@code thinking_tokens}）。
         *
         * <p>为什么要单独记：有的端点在关掉思考后仍然返回一个空的 reasoning 分片，
         * 有的网关干脆把 {@code reasoning_content} 丢掉、只在用量里留下推理计数。
         * 只看「有没有正文之外的文字」会把两种情况都判错。
         */
        int reasoningTokens;

        /**
         * 从正文里拆出、转进思考块的片段数。
         *
         * <p>只有 {@code thinking:disabled} 的请求才有：这类端点在关闭思考时
         * 把模型刚拟好的草稿直接写进 {@code content}（以「思考 / 分析 / 让我」
         * 一类词开头），不拆的话它会当成回答正文显示出来。
         */
        int parkCount;
        /** 被拆进思考块的正文片段（不含标记），用来告诉用户这次拆了什么。 */
        final StringBuilder parked = new StringBuilder();

        /** 协议层失败（无 choices/无 message/既无正文也无工具调用），非 null 即失败。 */
        String error;
        /** 非流式失败时保留原始响应体，用于错误卡片。 */
        String rawBody;
        int httpCode = 200;
        Exception cause;
        /**
         * 端点正常收尾（收到 [DONE]）却一个正文字、一个工具调用都没有。
         *
         * <p>这种情况在部分中转和负载高的官方端点上随机出现：通常是最后一次请求
         * 没有任何报错，界面上只留下一个空气泡，用户必须手动重试。上层据此自动重发，
         * 不用用户自己发现「刚才那条怎么什么都没回」。
         */
        boolean emptyReply;
    }

    /**
     * 增量回调：每收到一片就回调一次，<b>由调用方节流</b>。
     *
     * <p>为什么不在这里节流：这里只能传 {@code String}，而 {@code display.toString()}
     * 是一次全串复制。3 万字回答按 100ms 节流要复制上千次，其中大半会被下游
     * {@link StreamRenderer} 的 120/200ms 门槛丢掉——copy 做了，帧没画。
     * 传 {@code CharSequence} 让调用方在「确实要画」的那一刻再复制。
     */
    interface Progress {
        void onProgress(CharSequence display);
    }

    interface Abort {
        boolean isAborted();
    }

    /**
     * 「思考关了，但端点把思考正文放在 content 里」的处理位置。
     *
     * <p><b>为什么需要</b>：部分端点的关闭开关只对「强度的档位」生效，模型仍会先
     * 拟一段草稿再作答，只是把草稿一起塞进 {@code content}。此时请求里明明是
     * {@code thinking:{type:disabled}}，用户看到的回答却以「思考过程：」开头——
     * 这就是「关了之后思考内容被当成正文」。
     */
    enum Park {
        /**
         * 不拆（默认）。开了思考、或请求里没带关闭字段时用：那时的内容就是回答，
         * 拆它会误伤「先说思路再说答案」的正常作答。
         */
        NONE,
        /**
         * 逐段判断，最多判断 {@link #PARK_MAX} 段：每段落定案时看开头像不像草稿，
         * 像就整段转进思考块并继续看下一段，不像就到此为止、后面全按正文。
         *
         * <p>关闭请求用这一档。只判开头几段而不是全文判断，是因为误判一次就会
         * 把正常回答藏进思考块，代价比漏判高得多。
         */
        SEGMENTS,
        /**
         * 只看第一段（到第一个换行为止）：像草稿就转进思考块，否则整段按正文。
         *
         * <p>用在「第一段判完就够」的场景——只有一段的草稿、或回答一上来就是
         * 正文时，这样不会把两段连在一起判断。
         */
        FIRST_PARAGRAPH
    }

    /**
     * 流式正文的状态：已确定的正文、可能还算草稿的「暂存段」、以及显示到哪。
     *
     * <p>暂存段收的是原始字，遇到段落边界就交给调用方判断一次；判断通过就并进
     * 正文（上报与显示都只用正文），判成草稿则整段交给思考块。
     */
    static final class Body {
        /** 已确定的正文（含段与段之间原文的换行）。 */
        final StringBuilder text = new StringBuilder();
        /** 末尾还没定案的暂存段（还没遇到段落边界）。 */
        final StringBuilder pending = new StringBuilder();
        /**
         * 一片里越过段落边界、还没收进来的尾巴。
         *
         * <p>一个分片常常同时带着「上一段的结尾 + 空行 + 下一段的开头」，
         * 边界一到就得停下来定案，但同一片里剩下的字不能跟着丢掉——那会缺字。
         */
        final StringBuilder stash = new StringBuilder();
        /** 已经判断过至少一段。 */
        int decided;
        /** 判定放弃（超长或判满上限）：给识别代码看，调用方据此停止判断。 */
        boolean giveUp;
        /** 下一个字是否处在段落起点（段首或换行之后）。 */
        boolean atStart = true;
        /** 暂存段已经判到边界（第一个自然段见换行，或已攒到空行）。 */
        boolean complete;
        /**
         * 已经推到 display 的正文长度。
         *
         * <p>display 只做追加：判断阶段里暂存段可能被改判成草稿，所以只有
         * 「已定案正文」才上台面；这个下标就是「上台面到哪」。
         */
        int done;

        /**
         * 收下一个分片。
         *
         * <p>{@link Park#NONE} 时直接拼正文。判断阶段里只收「段落起点之后的字」：
         * 段前的空行与缩进不属于内容，扔掉即可——攒着它们会让段落边界永远判不出来。
         * {@link Park#FIRST_PARAGRAPH} 只判到第一个换行，见 {@link Park}。
         *
         * @param s    分片文本
         * @param mode 当前阶段
         */
        void append(String s, Park mode) {
            if (s == null || s.length() == 0) return;
            // 上一片没吃完的尾巴先接回来，按这一片的阶段一起处理
            if (stash.length() > 0) {
                stash.append(s);
                s = stash.toString();
                stash.setLength(0);
            }
            if (mode == Park.NONE) {
                flushPending();
                text.append(s);
                atStart = s.endsWith("\n");
                return;
            }
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (atStart && (c == '\n' || c == ' ' || c == '\t' || c == '\u3000')) {
                    continue;
                }
                pending.append(c);
                atStart = false;
                // 第一个自然段只判到这一段的末尾：段之间只隔一个换行，
                // 不能等空行才定案，否则两段会连在一起判定
                if (mode == Park.FIRST_PARAGRAPH && c == '\n') {
                    complete = true;
                }
                if (paragraphBreak(pending) || (mode == Park.FIRST_PARAGRAPH && complete)) {
                    // 边界后的字属于下一段，等定案后再收；先存着，不能丢
                    if (i + 1 < s.length()) stash.append(s, i + 1, s.length());
                    return;
                }
            }
            // 一直等不到段落边界又写了很长：当正文定下来，后面的分片直接拼正文
            if (pending.length() > PARK_GIVE_UP_CHARS) {
                flushPending();
                decided = PARK_MAX;
                giveUp = true;
            }
        }

        /** 暂存段原样并进正文（判断结束、放弃判断、或超长兜底）。 */
        private void flushPending() {
            if (pending.length() == 0) return;
            text.append(pending);
            pending.setLength(0);
            atStart = false;
        }

        /**
         * 把 {@link #stash} 里的尾巴按给定阶段收进来。
         *
         * <p>定案后必须调用一次：同一片里边界之后的字要接着按新阶段处理，
         * 否则它一直压在 stash 里，直到下一片才出现。
         */
        void drain(Park mode) {
            if (stash.length() == 0) return;
            String s = stash.toString();
            stash.setLength(0);
            append(s, mode);
        }

        /** 流结束时把 stash 折进暂存段，让「最后一段是不是草稿」能看全。 */
        void foldStash() {
            if (stash.length() == 0) return;
            pending.append(stash);
            stash.setLength(0);
        }

        /**
         * 暂存段是否已写到可以下结论的边界：段落结束、换行足够多、或明显超长。
         *
         * <p>超长时要先给出结论，否则整段回答会被压在暂存里，直到流结束才一次性
         * 出现——实时渲染就废了。
         */
        boolean segmentComplete() {
            if (pending.length() == 0) return false;
            return complete || paragraphBreak(pending)
                    || pending.length() >= PARK_DECIDE_CHARS
                    || newlines(pending) >= PARK_DECIDE_LINES;
        }

        /** 暂存段去掉段落边界（末尾的空行）：这部分只用来找边界，不属于内容。 */
        String peekSegment() {
            String s = pending.toString();
            int end = s.length();
            while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == ' '
                    || s.charAt(end - 1) == '\t' || s.charAt(end - 1) == '\u3000')) {
                end--;
            }
            return s.substring(0, end);
        }

        /**
         * 暂存段已定案：清空暂存，进入下一段。
         *
         * <p>段与段之间保留原文的换行（最多一个空行）：段落边界就写在原文里，
         * 压成一个换行会让 Markdown 把两段并成一段，排版走样。草稿不并进正文，
         * 所以上报与落盘的正文里不会出现它。
         *
         * @param seg      定案的那一段（不含末尾空行）
         * @param intoBody 这一段是否算正文（草稿为 false）
         */
        void settle(String seg, boolean intoBody) {
            if (intoBody) text.append(seg).append(boundary(pending));
            pending.setLength(0);
            atStart = true;
            complete = false;
            decided++;
        }

        /** 判断阶段：把已定案正文的增量推到 display，暂存段不上台面。 */
        void showDecided(StringBuilder display) {
            String t = text.toString();
            int from = Math.min(done, t.length());
            if (t.length() > from) display.append(t.substring(from));
            done = t.length();
        }

        /** 暂存段并进正文（判断结束，或最后一段按正文收尾）。 */
        void mergePending() {
            flushPending();
        }

        /** 只有空行的暂存段：丢掉，不占正文。 */
        void dropSegment() {
            pending.setLength(0);
            atStart = true;
            complete = false;
        }

        /**
         * 已确定正文 + 暂存段（末尾空白去掉）。
         *
         * <p>正文里已经带了段间的原文换行，这里只把还没定案的尾段接上：
         * 它是内容的开头，单独出现时不能算段落分隔。
         */
        String all() {
            StringBuilder sb = new StringBuilder(text);
            if (pending.length() > 0) sb.append(peekSegment());
            if (stash.length() > 0) sb.append(stash);
            int end = sb.length();
            while (end > 0 && Character.isWhitespace(sb.charAt(end - 1))) end--;
            return sb.substring(0, end);
        }
    }

    /**
     * 一段原文结尾的段落分隔，原样保留（最多一个空行）。
     *
     * <p>定案时这段字要被清掉，但它的换行是两段之间的分隔：丢掉它两段会粘在
     * 一起，写死成一个换行又会把空行压掉。
     */
    private static String boundary(CharSequence s) {
        if (s == null || s.length() == 0) return "";
        int nl = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            char c = s.charAt(i);
            if (c == '\n') nl++;
            else if (!isBlank(c)) break;
        }
        if (nl >= 2) return "\n\n";
        return nl == 1 ? "\n" : "";
    }

    private static boolean isBlank(char c) {
        return c == ' ' || c == '\t' || c == '\u3000' || c == '\r';
    }

    /** 暂存段超过这个长度就定案：真实的草稿很少一整个自然段写到这么长。 */
    private static final int PARK_DECIDE_CHARS = 240;
    /** 暂存段里已有两个换行（可能是一段一条的步骤清单）就提前定案。 */
    private static final int PARK_DECIDE_LINES = 2;
    /** 最多判断几段：连判几段都像草稿，说明这个端点关不掉思考，后面不再判断。 */
    private static final int PARK_MAX = 6;
    /** 一直等不到段落边界时的放弃长度：超过它就不再暂存，避免回答被压着不出。 */
    private static final int PARK_GIVE_UP_CHARS = 1200;

    /**
     * 读流过程中的真实进度，供诊断记录写「卡在哪」。
     *
     * <p>只做记录，不参与协议判断；实现方在 trace 为 null 时不会被调用。
     */
    interface Trace {
        /** 第一个字节到达。 */
        void onFirstByte(long now);

        /** 第一次出现 data 行。 */
        void onFirstData(long now, int chunks);

        /** 读流结束。[DONE] 与 EOF 分开标记。 */
        void onEnd(long now, int chunks, boolean doneMark);
    }

    private SseRound() {
    }

    static Result read(boolean stream, InputStream in, Progress progress, Abort abort)
            throws Exception {
        return read(stream, in, progress, abort, (Result[]) null);
    }

    /** @param park 关闭思考时是否把 content 里的思考草稿拆进思考块，见 {@link Park}。 */
    static Result read(boolean stream, InputStream in, Progress progress, Abort abort, Park park)
            throws Exception {
        return read(stream, in, progress, abort, null, null, park);
    }

    /**
     * 同 {@link #read(boolean, InputStream, Progress, Abort)}，但把「读了一半的 Result」
     * 通过 {@code partialOut} 交出去。
     *
     * <p>为什么需要：流式读取中途抛 IOException（超时、连接被重置）时，本轮已经
     * 解析并推到屏幕上的文本都在这个 Result 里。不交出来的话，异常一抛它就跟着
     * 被回收，调用方只能拿「本轮之前」的 trace 收尾——用户眼看着答了一半的内容
     * 被清空，落盘也少一截。
     */
    static Result read(boolean stream, InputStream in, Progress progress, Abort abort,
            Result[] partialOut) throws Exception {
        return read(stream, in, progress, abort, partialOut, null, Park.NONE);
    }

    /** @param trace 读流进度回调，可为 null。 */
    static Result read(boolean stream, InputStream in, Progress progress, Abort abort,
            Result[] partialOut, Trace trace) throws Exception {
        return read(stream, in, progress, abort, partialOut, trace, Park.NONE);
    }

    /**
     * @param trace 读流进度回调，可为 null
     * @param park  关闭思考时 content 里草稿的处理方式；非流式与 {@link Park#NONE} 等价
     */
    static Result read(boolean stream, InputStream in, Progress progress, Abort abort,
            Result[] partialOut, Trace trace, Park park) throws Exception {
        Result r = new Result();
        if (partialOut != null) partialOut[0] = r;
        if (stream) readStream(in, progress, abort, r, trace, park);
        else readWhole(in, r);
        // tool_calls 的 arguments 是分片拼接的，流中途断开会留下半截 JSON；下游
        // ToolExecutor.parseArgs 解析失败会静默返回空对象照常执行，等于把半截参数
        // 当完整参数跑工具。落库/执行前统一校验，不完整整轮按错误走错误卡片链路。
        if (r.error == null && r.toolCalls.length() > 0) validateToolArguments(r);
        return r;
    }

    /**
     * 从「HTTP 200 + JSON 错误体」里取出可读的错误信息，不是错误体返回 null。
     *
     * <p>覆盖三种常见形态：
     * <ul>
     *   <li>{@code {"error":{"message":"Insufficient Balance","type":"..."}}}（OpenAI 系）</li>
     *   <li>{@code {"error":"invalid api key"}}（部分中转）</li>
     *   <li>{@code {"message":"..."}}（部分网关直出）</li>
     * </ul>
     */
    static String errorMessageOf(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.length() == 0 || t.charAt(0) != '{') return null;
        try {
            JSONObject o = new JSONObject(t);
            Object err = o.opt("error");
            if (err == null || err == JSONObject.NULL) err = o.opt("message");
            if (err == null || err == JSONObject.NULL) return null;
            if (err instanceof JSONObject) {
                JSONObject eo = (JSONObject) err;
                String msg = eo.optString("message", "");
                if (msg.length() == 0) msg = eo.optString("type", "");
                if (msg.length() == 0) msg = eo.optString("code", "");
                return msg.length() == 0 ? null : msg;
            }
            String s = String.valueOf(err).trim();
            return s.length() == 0 ? null : s;
        } catch (Throwable t2) {
            return null;
        }
    }

    /** 每个待执行 tool_call 的 arguments 必须至少是一段语法完整的 JSON。 */
    private static void validateToolArguments(Result r) {
        for (int i = 0; i < r.toolCalls.length(); i++) {
            JSONObject tc = r.toolCalls.optJSONObject(i);
            // name 为空的调用不会被 appendAssistant / ToolExecutor 执行，无需校验
            if (tc == null || tc.optString("name", "").length() == 0) continue;
            String args = tc.optString("arguments", "");
            // 与 appendAssistant / parseArgs 一致：空参等价于 {}
            if (args.trim().length() == 0) continue;
            try {
                // 只拦「半截 JSON」。合法但非对象（null/[]/标量）保持原行为：下游
                // ToolExecutor.parseArgs 解析失败会静默当 {}，不能在这里误判成整轮错误。
                new org.json.JSONTokener(args).nextValue();
            } catch (Exception e) {
                LogUtil.e(TAG, "tool_call#%d arguments incomplete: %s",
                        i, LogUtil.preview(args, 200));
                r.error = Strings.get(R.string.sseround_001);
                r.cause = new Exception("tool_call arguments truncated: "
                        + LogUtil.preview(args, 200));
                return;
            }
        }
    }

    /** 非流式：整个响应就是一个 JSON 对象。 */
    private static void readWhole(InputStream in, Result r) throws Exception {
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        try {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        } finally {
            reader.close();
        }
        r.rawBody = ApiClient.sanitizeJson(sb.toString());

        JSONObject json;
        try {
            json = new JSONObject(r.rawBody);
        } catch (Throwable te) {
            // 非 JSON 的响应体（网关 HTML 错误页、纯文本限流提示）不能让它以
            // JSONException 的形式冒到上层：那样错误卡片只有一句 "Expected ..."，
            // 而真正的线索就在响应体里。
            String apiMsg = errorMessageOf(r.rawBody);
            r.error = apiMsg != null ? apiMsg : Strings.get(R.string.sseround_002);
            r.cause = new Exception(Strings.get(R.string.sseround_003)
                    + LogUtil.preview(r.rawBody, 160));
            LogUtil.e(TAG, "non-stream response is not JSON");
            return;
        }
        r.usage = json.optJSONObject("usage");
        r.reasoningTokens = UsageView.of(r.usage).reasoning;
        JSONArray choices = json.optJSONArray("choices");
        JSONObject message = choices != null && choices.length() > 0
                ? choices.getJSONObject(0).optJSONObject("message") : null;
        if (message == null) {
            String apiMsg = errorMessageOf(r.rawBody);
            if (apiMsg != null) {
                // HTTP 200 + 错误体（余额不足、key 无效、模型下线）：把原话交给用户
                r.error = apiMsg;
                r.cause = new Exception(Strings.get(R.string.sseround_004));
                LogUtil.e(TAG, "non-stream body is an error object: %s",
                        LogUtil.preview(apiMsg, 200));
                return;
            }
            LogUtil.e(TAG, "non-stream response missing choices[0].message");
            r.error = Strings.get(R.string.sseround_005);
            r.cause = new Exception(Strings.get(R.string.sseround_006));
            return;
        }

        String reasoning = message.has("reasoning_content") && !message.isNull("reasoning_content")
                ? message.optString("reasoning_content", "")
                : (message.has("reasoning") && !message.isNull("reasoning")
                        ? message.optString("reasoning", "") : "");
        if (reasoning.length() > 0) {
            r.display.append("[thinking]").append(reasoning).append("[/thinking]");
            r.thinkingChunks++;
            r.reasoning.append(reasoning);
        }
        String content = message.has("content") && !message.isNull("content")
                ? message.optString("content", "") : "";
        r.display.append(content);
        r.content.append(content);
        if (content.length() > 0) r.contentChunks++;
        JSONArray rawCalls = message.optJSONArray("tool_calls");
        if (rawCalls != null) ToolCalls.normalize(rawCalls, r.toolCalls);

        if (content.length() == 0 && reasoning.length() == 0 && r.toolCalls.length() == 0) {
            LogUtil.e(TAG, "non-stream response has no content and no tool calls");
            r.error = Strings.get(R.string.sseround_007);
            r.cause = new Exception(Strings.get(R.string.sseround_008));
            return;
        }
        LogUtil.i(TAG, "non-stream: raw=%d content=%d tools=%d",
                sb.length(), content.length(), r.toolCalls.length());
    }

    /** 流式：逐行读 SSE，只在 data: 行上取 JSON。 */
    private static void readStream(InputStream in, Progress progress, Abort abort, Result r)
            throws Exception {
        readStream(in, progress, abort, r, null, Park.NONE);
    }

    private static void readStream(InputStream in, Progress progress, Abort abort, Result r,
            Trace trace) throws Exception {
        readStream(in, progress, abort, r, trace, Park.NONE);
    }

    /**
     * 流式读取，并把正文按段落整理进 {@link Body}。
     *
     * <p>段落切分（暂存段 + 边界定案）与「这一段落是不是思考草稿」分开做，
     * 因为两者的判据不同，也都需要能在测试里单独喂字符串验证。
     */
    private static void readStream(InputStream in, Progress progress, Abort abort, Result r,
            Trace trace, Park park) throws Exception {
        boolean thinkingActive = false;
        boolean thinkingFinished = false;
        // 段落末尾没有换行时，靠下一段的开头判断它是不是以草稿结尾
        Park pendingPark = park;
        Body text = new Body();

        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        // 头几行原文先留着：有些网关/代理会返回 HTTP 200 + 一条 JSON 错误体
        // （{"error":{"message":"Insufficient Balance"}}），没有任何 data: 行。
        // 原来这种情况只报「服务端没有返回任何内容」，把唯一有用的那句原因丢了。
        // 只在还没见到任何 data 行时累积，正常 SSE 一进来就停手，零额外开销。
        StringBuilder head = new StringBuilder();
        boolean firstByte = false;
        boolean firstData = false;
        boolean doneMark = false;
        try {
            String line;
            while (!abort.isAborted() && (line = reader.readLine()) != null) {
                if (!firstByte) {
                    firstByte = true;
                    if (trace != null) trace.onFirstByte(System.currentTimeMillis());
                }
                line = line.trim();
                // SSE 规范里冒号后的空格可选，兼容 "data:" 与 "data: "
                if (!line.startsWith("data:")) {
                    if (line.length() > 0) {
                        if (r.chunks == 0 && head.length() < 4000) head.append(line).append('\n');
                        LogUtil.v(TAG, "skip non-data line: %s", LogUtil.preview(line, 120));
                    }
                    continue;
                }
                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) {
                    LogUtil.i(TAG, "SSE [DONE] after %d chunks", r.chunks);
                    doneMark = true;
                    break;
                }
                r.chunks++;
                if (!firstData) {
                    firstData = true;
                    if (trace != null) trace.onFirstData(System.currentTimeMillis(), r.chunks);
                }

                JSONObject json;
                try {
                    json = new JSONObject(data);
                } catch (Exception e) {
                    LogUtil.w(TAG, "failed to parse SSE chunk#%d: %s | raw=%s",
                            r.chunks, e.getMessage(), LogUtil.preview(data, 300));
                    continue;
                }
                // usage 必须在 choices 判空之前取：OpenAI 的最后一帧是
                // {"choices":[],"usage":{...}}，先 continue 掉就永远读不到。
                JSONObject usageObj = json.optJSONObject("usage");
                if (usageObj != null) {
                    r.usage = usageObj;
                    // 用量里带推理计数时以此为准：它统计的是整轮，比逐帧累加准
                    int rt = UsageView.of(usageObj).reasoning;
                    if (rt > 0) r.reasoningTokens = rt;
                }

                JSONArray choices = json.optJSONArray("choices");
                if (choices == null || choices.length() == 0) {
                    // 有些中转/网关把错误体包成 SSE data 行下发：
                    //   data: {"error":{"message":"Insufficient Balance"}}
                    // 此时 r.chunks 已经 +1，走不到末尾 chunks==0 的 JSON 错误体兜底，
                    // 又没有任何 choices/正文 —— 会被上层当成「成功但内容为空」，
                    // 收尾时用空内容覆盖气泡，用户看到回答凭空消失，且拿不到原因。
                    // 只在「无 choices 的 data 行」上识别错误对象，正常 chunk 与
                    // usage-only 末帧（{"choices":[],"usage":{...}}）都不受影响。
                    String apiMsg = errorMessageOf(data);
                    if (apiMsg != null) {
                        r.error = apiMsg;
                        r.rawBody = data;
                        r.cause = new Exception("SSE data line carries error object");
                        LogUtil.e(TAG, "SSE chunk#%d is an error object: %s",
                                r.chunks, LogUtil.preview(apiMsg, 200));
                        return;   // finally 会照常收口 thinking、关 reader
                    }
                    LogUtil.v(TAG, "chunk#%d has no choices: %s",
                            r.chunks, LogUtil.preview(data, 200));
                    continue;
                }
                JSONObject first = choices.optJSONObject(0);
                JSONObject delta = first == null ? null : first.optJSONObject("delta");
                if (delta == null) {
                    LogUtil.v(TAG, "chunk#%d has no delta", r.chunks);
                    continue;
                }

                // 从这里往下不再用 catch 兜住：解析异常应当让本轮失败并保留已输出的
                // 内容，而不是被当成「坏分片」跳过——那样工具调用会静默丢失，
                // 界面上只看到模型「想了半天没动静」。
                // 推理 token 计数：有的网关不返回 reasoning_content，只在用量里留计数
                if (r.reasoningTokens == 0) r.reasoningTokens = UsageView.of(delta).reasoning;
                // 有的网关只发 reasoning，不发 reasoning_content。只认前者时
                // 「关掉之后还有没有推理」就永远判成没有，关闭结论全靠猜。
                String rc = delta.optString("reasoning_content", "");
                if (rc.length() == 0) rc = delta.optString("reasoning", "");
                if (rc.length() > 0) {
                    r.thinkingChunks++;
                    // 思考块一旦闭合就不再往里追加：有的端点在正文开始之后
                    // 还会补一小段 reasoning（或干脆把正文混进 reasoning 流），
                    // 继续追加会把正文推到思考块里、跑到已经显示的回答下面，
                    // 屏幕上表现为「文字忽隐忽现、顺序乱掉」。
                    if (!thinkingFinished) {
                        if (!thinkingActive) {
                            thinkingActive = true;
                            r.display.append("[thinking]");
                            LogUtil.d(TAG, "thinking phase START at chunk#%d", r.chunks);
                        }
                        r.display.append(rc);
                    }
                    r.reasoning.append(rc);
                    if (LogUtil.ENABLED) {
                        LogUtil.v(TAG, "reasoning chunk#%d: %s",
                                r.thinkingChunks, LogUtil.preview(rc, 150));
                    }
                }

                if (delta.has("content") && !delta.isNull("content")) {
                    String ct = delta.optString("content", "");
                    if (ct.length() > 0) {
                        r.contentChunks++;
                        // 非空 content 才算思考结束：API 在每个 reasoning 分片后都会
                        // 附一个 "content":""，按 has() 判断会立刻结束思考阶段
                        if (thinkingActive && !thinkingFinished) {
                            closeThinking(r);
                            thinkingFinished = true;
                        }
                        // 判断阶段结束后就不再进暂存，后面的字直接并进正文
                        text.append(ct, pendingPark);
                        if (text.giveUp) pendingPark = Park.NONE;
                        // 一片里可能跨过好几个段落边界（一次吐出两段很常见）：
                        // 每定案一段就把边界后剩下的字按新阶段接着收，直到收干净
                        for (int guard = 0; guard < 8 && pendingPark != Park.NONE
                                && text.segmentComplete(); guard++) {
                            String seg = text.peekSegment();
                            if (seg.length() == 0) {
                                // 整段都是空行：不是内容，留一个分段而已
                                text.dropSegment();
                            } else {
                                boolean draft = thinkingIsDraft(seg);
                                // 定案前先收口：要落进思考块的那一段必须在正文前面
                                if (draft) closeThinking(r);
                                // 先定案（正文段直接并进正文），再把草稿交给思考块
                                text.settle(seg, !draft);
                                if (draft) {
                                    park(r, seg);
                                    // 第一段就是草稿说明这个端点在往正文里混思考：
                                    // 后面的段落都按同样的方式逐段看，判满上限为止
                                    if (text.decided >= PARK_MAX) pendingPark = Park.NONE;
                                    else if (pendingPark == Park.FIRST_PARAGRAPH) {
                                        pendingPark = Park.SEGMENTS;
                                    }
                                } else {
                                    // 这一段是正常回答：正文从这里开始
                                    pendingPark = Park.NONE;
                                }
                            }
                            text.drain(pendingPark);
                        }
                        // 展示与上报共用一份正文：判断阶段只有已定案的正文上台面
                        // （暂存段随时可能被改判成草稿），不再判断时暂存段就是正文
                        if (pendingPark == Park.NONE) text.mergePending();
                        text.showDecided(r.display);
                        r.content.setLength(0);
                        r.content.append(text.all());
                        if (LogUtil.ENABLED) {
                            LogUtil.v(TAG, "content chunk#%d: %s",
                                    r.contentChunks, LogUtil.preview(ct, 150));
                        }
                    }
                }

                JSONArray deltaCalls = delta.optJSONArray("tool_calls");
                if (deltaCalls != null && deltaCalls.length() > 0) {
                    ToolCalls.accumulate(r.toolCalls, deltaCalls);
                }

                if (progress != null) progress.onProgress(r.display);
            }
        } finally {
            if (trace != null) trace.onEnd(System.currentTimeMillis(), r.chunks, doneMark);
            // 半截思考块必须在这里收口，而不是放到 try 之后：读取中途抛 IOException
            // （超时、连接重置）时 try 之后的一段根本不会执行，未闭合的 [thinking]
            // 会让调用方把之后的正文整段当成思考内容渲染。正常结束时下面的判断
            // 因 thinkingFinished 已置位而自动跳过。
            if (thinkingActive && !thinkingFinished) {
                thinkingFinished = true;
                r.display.append("[/thinking]");
                LogUtil.d(TAG, "thinking left open at stream end: closed with [/thinking]");
            }
            // 最后一段没等到段落边界就流完了：到这里它已经是正常回答，
            // 不管前面判断过多少段，都按正文落地
            String tail = null;
            if (!thinkingFinished && pendingPark != Park.NONE) {
                // 边界后剩下的字也归到最后这一段里，别让它因为没定案而漏掉
                text.foldStash();
                tail = text.peekSegment();
            }
            if (tail != null && tail.length() > 0 && thinkingIsDraft(tail)) {
                park(r, tail);
                text.dropSegment();
                r.content.setLength(0);
                r.content.append(text.all());
            } else {
                // 最后一段没定案就当正文：整体并进正文再补齐 display
                text.mergePending();
                text.showDecided(r.display);
                r.content.setLength(0);
                r.content.append(text.all());
            }
            trimTail(r.display);
            if (r.parkCount > 0) {
                LogUtil.i(TAG, "parked %d content segment(s) into thinking block",
                        r.parkCount);
            }
            try {
                reader.close();
            } catch (Exception ignored) {
                // 已经读到断了，close 再抛也没人处理：真正的错误由上面的 readLine 抛出
            }
        }
        // 空流必须当失败：200 + 一个 data 都没有（网关超时、被中间层吞掉、配额耗尽）
        // 以前会被当成「成功但内容为空」，收尾时用空内容覆盖气泡，用户看到回答凭空消失。
        if (r.chunks == 0) {
            String raw = head.toString();
            String apiMsg = errorMessageOf(raw);
            if (apiMsg != null) {
                // 200 + JSON 错误体：把服务端原话交给用户，别再报「没有返回任何内容」
                r.error = apiMsg;
                r.rawBody = raw;
                r.cause = new Exception("HTTP 200 with JSON error body");
                LogUtil.e(TAG, "stream body is a JSON error object: %s",
                        LogUtil.preview(apiMsg, 200));
                return;
            }
            r.error = Strings.get(R.string.sseround_009);
            if (raw.length() > 0) r.rawBody = raw;
            r.cause = new Exception("empty SSE stream: no data line received");
            LogUtil.e(TAG, "stream produced no chunks at all");
            return;
        }
        if (r.display.length() == 0 && r.toolCalls.length() == 0) {
            // 标成「空回复」而不是普通失败：上层据此自动重发一次，
            // 不需要用户自己看见报错后猜要不要再点一次重试。
            r.error = Strings.get(R.string.sseround_010);
            r.emptyReply = true;
            r.cause = new Exception("stream has neither text nor tool_calls");
            LogUtil.e(TAG, "stream finished without text or tool calls (chunks=%d)", r.chunks);
            return;
        }
        LogUtil.i(TAG, "stream finished: chunks=%d tools=%d contentLen=%d displayLen=%d",
                r.chunks, r.toolCalls.length(), r.content.length(), r.display.length());
    }

    /** 思考块是否还开着（开过正文、还没收口）。 */
    private static boolean thinkingOpen(Result r) {
        return r.display.lastIndexOf("[thinking]") > r.display.lastIndexOf("[/thinking]");
    }

    /**
     * 关掉思考块。调用方负责只在「确认内容不再是思考」的地方调用：
     * 草稿转进思考块、或正文里出现 reasoning_content 之后的第一段正常文字。
     */
    private static void closeThinking(Result r) {
        if (!thinkingOpen(r)) return;
        r.display.append("[/thinking]");
    }

    /**
     * 把一段草稿转进思考块。
     *
     * <p>已经开过正文（reasoning_content）就接在它后面：端点既然会混一段草稿进
     * content，也可能先发一段真思考；两段都是「不是回答」的内容。
     */
    private static void park(Result r, String seg) {
        if (seg == null || seg.trim().length() == 0) return;
        if (!thinkingOpen(r)) r.display.append("[thinking]");
        r.display.append(seg);
        r.display.append("[/thinking]");
        r.parkCount++;
        r.parked.append(seg).append("\n");
    }

    /**
     * 去掉展示文本末尾的空白。
     *
     * <p>正文的段间换行是原样保留的，最后一段定案后可能多带一个空行；
     * 它不影响阅读，但会让「回答已结束」的位置差一行，收尾时统一去掉。
     */
    private static void trimTail(StringBuilder sb) {
        int end = sb.length();
        while (end > 0 && Character.isWhitespace(sb.charAt(end - 1))) end--;
        sb.setLength(end);
    }

    /**
     * 这一段像不像模型没写出来、直接被塞进 content 的思考草稿。
     *
     * <p><b>判据只用「开头」</b>：这类草稿几乎都以自述性的引导词起笔
     * （「思考：」「分析一下」「用户问的是…」），而行文中间出现这些词是正常
     * 中文表达。只看开头，宁可漏判，不误伤回答。
     *
     * @return 段首去掉 Markdown 标记与标点后，仍以草稿引导词开头
     */
    static boolean thinkingIsDraft(CharSequence seg) {
        if (seg == null) return false;
        String s = seg.toString();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            // 跳过 Markdown 标记、空白、成对的括号引号
            if (Character.isWhitespace(c) || c == '#' || c == '*' || c == '>' || c == '-'
                    || c == '_' || c == '"' || c == '\'' || c == '「' || c == '【'
                    || c == '（' || c == '(' || c == '`' || c == '[' || c == '＜' || c == '<') {
                i++;
                continue;
            }
            break;
        }
        // 有序列表里的条目通常是「步骤 1：…」「1. 先算折扣」这种答案草稿的骨架，
        // 剔掉编号后按正文开头判断
        while (i < s.length() && Character.isDigit(s.charAt(i)) && i < 40) {
            int j = i;
            while (j < s.length() && (Character.isDigit(s.charAt(j))
                    || s.charAt(j) == '.' || s.charAt(j) == ')' || s.charAt(j) == '、'
                    || s.charAt(j) == '．')) {
                j++;
            }
            if (j == i) break;
            while (j < s.length() && Character.isWhitespace(s.charAt(j))) j++;
            i = j;
        }
        if (i > 40) return false;
        String head = s.substring(i).toLowerCase(Locale.US);
        return startsWithAny(head, DRAFT_LEADS);
    }

    private static boolean startsWithAny(String s, String[] leads) {
        for (String l : leads) {
            if (s.startsWith(l)) return true;
        }
        return false;
    }

    /**
     * 草稿引导词。
     *
     * <p>刻意不收「首先」「1.」这类正常的作答开头：说明类回答也常这样起笔，
     * 收了就会把正文误判成草稿。
     */
    private static final String[] DRAFT_LEADS = {
            // 中文自述
            "思考", "分析", "让我", "我们来", "我们来看", "我先", "我需", "我要", "我需要",
            "我来", "我们可以", "我们需", "根据题意", "题目", "用户", "问题", "要求",
            "理解", "首先想", "步骤", "计算过程", "推导",
            // 英文与常见模型的口头禅
            "thinking", "analyze", "analysis", "let me", "let's", "we need", "we should",
            "we can", "i need", "i should", "i will", "i'll", "the user", "user asks",
            "okay", "hm", "hmm",
    };

    /**
     * 从一帧里取推理 token 数。
     *
     * <p>保留给测试与旧调用方；解析规则统一在 {@link UsageView}。
     */
    static int reasoningTokensOf(JSONObject frame) {
        return UsageView.of(frame).reasoning;
    }

    /** 正文里有两个以上换行（一段里已经列出多条），可以下结论了。 */
    private static int newlines(CharSequence s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') n++;
        }
        return n;
    }

    /**
     * 暂存段末尾是否已经是段落边界：结尾是一个空行。
     *
     * <p>段落之间只隔一个换行时，行尾的「

」是最常见的写法；
     * 段首的空行已经被 {@link Body#append} 丢掉了，所以这里只看结尾。
     */
    static boolean paragraphBreak(CharSequence s) {
        if (s == null || s.length() < 2) return false;
        return s.charAt(s.length() - 1) == '\n' && s.charAt(s.length() - 2) == '\n';
    }
}
