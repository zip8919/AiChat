package xyz.zip8919.app.aichat;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

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

        /** 本次读取采用的草稿剥离方式，见 {@link Park}。 */
        Park park = Park.NONE;

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
     * content 里「思考草稿 + 结束标记 + 正文」的拆分方式。
     *
     * <p><b>为什么需要</b>：部分端点的关闭开关只对档位生效，模型仍会先拟一段草稿，
     * 然后把草稿和正文一起塞进 {@code content}。此时请求里明明是
     * {@code thinking:{type:disabled}}，用户看到的回答却以「Thinking Process:」开头——
     * 这就是「关了之后思考内容被当成正文」。
     *
     * <p><b>旧做法为什么不可靠</b>：按「段首引导词」猜。实测（sensenova-6.8-flash，
     * 关闭思考跑 5 个不同问题）草稿的引导词有 {@code Thinking Process:}、
     * {@code Here's my thought process}、{@code Here's a thinking process}、
     * {@code 好的，用户要求我…}、{@code 用户让我写…} 五种，任何词表都列不全；
     * 而正文开头千变万化，词表越全越容易把正文误判成草稿。
     *
     * <p><b>现在按标记切</b>：这类端点在草稿和正文之间会写一个显式的结束标记。
     * 同一个模型 5 个问题里 5 个都带 {@code </think>}，位置就在正文前一个字符。
     * 开关的开合天然成对，比猜开头确定得多；而且只有<b>确认存在标记</b>时才动手，
     * 没有标记就一个字符都不改——不会误伤正常回答。
     */
    enum Park {
        /**
         * 不拆（默认）。开了思考、或请求里没带关闭字段时用：那时的内容就是回答，
         * 拆它会误伤「先说思路再说答案」的正常作答。
         */
        NONE,
        /** 按结束标记拆：标记之前的进思考块，之后的才是正文。 */
        MARK
    }

    /**
     * 从 content 里剥掉「草稿 + 结束标记」的拆分器。
     *
     * <p>流式下这里必须能<b>边收边判</b>：标记可能被拆在两个分片里
     * （实测 {@code "</think>"} 整个落在一片里，但不能指望所有端点都这样），
     * 所以状态机停在两种状态之一，只有见到完整标记才切换：
     * <ul>
     *   <li>{@link #STATE_DRAFT}：还没见到结束标记，收到的一切都是草稿。为避免
     *       「先显示再收回」的抖动，草稿只进 {@link Result#display} 的思考块，
     *       不进 {@link Result#content}；</li>
     *   <li>{@link #STATE_BODY}：标记已过，之后收到的一切都是正文。</li>
     * </ul>
     *
     * <p>如果整条流跑完都没见到标记，{@link #finish()} 把攒住的草稿原样当成正文
     * 交出去——此时它可能本来就是正文（端点没混草稿），不能吞掉用户的内容。
     */
    static final class Peeler {
        static final int STATE_DRAFT = 0;
        static final int STATE_BODY = 1;

        private int state = STATE_DRAFT;
        /** 还没见到标记的部分（用于跨分片匹配标记）。 */
        private final StringBuilder hold = new StringBuilder();
        /** 草稿全文（已确认是草稿的部分）。 */
        private final StringBuilder draft = new StringBuilder();
        /** 原文逐字进过来的总量，用于判断「开头够长了还没标记」。 */
        private int seen;
        /** 是否真的切下了一段草稿（区别于「开头够长、确认没有标记」）。 */
        private boolean peeled;

        boolean bodyStarted() {
            return state == STATE_BODY;
        }

        /** 是否真的发生了拆分：只有拆分才值得给用户提示。 */
        boolean peeled() {
            return peeled;
        }

        /**
         * 收一片 content。
         *
         * @param s          分片文本
         * @param thinking   草稿往这里写（会带 [thinking] 包裹）
         * @param body       正文往这里写
         * @param flushDraft 草稿是否立即上屏。不立即上屏时先攒着，
         *                   等确认有标记再一次性给出——正常回答不能先显示再消失。
         */
        void feed(String s, StringBuilder thinking, StringBuilder body) {
            if (s == null || s.length() == 0) return;
            if (state == STATE_BODY) {
                body.append(s);
                return;
            }
            seen += s.length();
            hold.append(s);
            int at = indexOfClose(hold);
            if (at >= 0) {
                // 找到标记：前面的进草稿，跳过标记，剩下的进正文
                String before = hold.substring(0, at);
                draft.append(before);
                thinking.append(before);
                int tagEnd = at + closeTagLengthAt(hold, at);
                String after = hold.substring(tagEnd);
                hold.setLength(0);
                state = STATE_BODY;
                peeled = true;
                if (after.length() > 0) body.append(after);
                return;
            }
            // 没找到标记。此时 hold 里全是草稿候选，唯一要留住的是结尾那截
            // 「可能是标记的半个字」（例如 "</thi"），它得等下一片才能判定。
            //
            // 注意这里**不能**用「开头够长就判定没有标记」的窗口：真实样本里
            // </think> 出现在 223/329、341/437、6381/6503 这些很靠后的位置，
            // 草稿动辄几千字。窗口一过就当正文，后面再见到标记也回不去了。
            int keep = suffixThatMayGrowIntoTag(hold);
            int move = hold.length() - keep;
            if (move > 0) {
                draft.append(hold, 0, move);
                hold.delete(0, move);
            }
        }

        /**
         * 流结束：还没见到标记的残留按正文交出去。
         *
         * <p>整条流都没有出现结束标记，说明这个端点这次没往 content 里混草稿
         * （或者草稿压根没写标记）。两种情况下这些字都是用户要看的回答，不能吞。
         *
         * @return 需要追加到正文的残留（可能为空串）
         */
        String finish() {
            // 没有标记时草稿候选按正文交出去：draft 里是已挪出的前缀，hold 里是
            // 还没判定的尾巴，两段都要给。只交 hold 会丢开头——上一版就是这个错：
            // finish() 只返回 hold，`abc` 这种三字回复整条丢失。
            String rest = draft.toString() + hold.toString();
            hold.setLength(0);
            draft.setLength(0);
            state = STATE_BODY;
            return rest;
        }

        /** 草稿（已确认部分）文本，供诊断用。 */
        String draftText() {
            return draft.toString();
        }

        /** 是否还在草稿候选阶段（还没见到标记）。 */
        boolean drafting() {
            return state == STATE_DRAFT;
        }

        private static int indexOfClose(CharSequence s) {
            for (String t : CLOSE_TAGS) {
                int i = indexOfIgnoreCase(s, t);
                if (i >= 0) return i;
            }
            return -1;
        }

        /** 该位置命中的是哪个标记，返回它的长度。 */
        private static int closeTagLengthAt(CharSequence s, int at) {
            for (String t : CLOSE_TAGS) {
                if (startsWithIgnoreCase(s, at, t)) return t.length();
            }
            return 0;
        }

        /**
         * 结尾有多少个字可能是一段还没收全的标记。
         *
         * <p>例如没收全的 {@code "</thi"} 必须留着等下一片，挪进草稿就再也拼不回来了。
         */
        private static int suffixThatMayGrowIntoTag(StringBuilder s) {
            int max = 0;
            for (String t : CLOSE_TAGS) {
                if (t.length() > max) max = t.length();
            }
            int n = Math.min(max, s.length());
            for (int k = n; k > 0; k--) {
                int from = s.length() - k;
                for (String t : CLOSE_TAGS) {
                    if (k <= t.length() && startsWithIgnoreCase(s, from, t.substring(0, k))) {
                        return k;
                    }
                }
            }
            return 0;
        }

        private static boolean startsWithIgnoreCase(CharSequence s, int at, String t) {
            if (at + t.length() > s.length()) return false;
            for (int i = 0; i < t.length(); i++) {
                if (Character.toLowerCase(s.charAt(at + i))
                        != Character.toLowerCase(t.charAt(i))) {
                    return false;
                }
            }
            return true;
        }

        private static int indexOfIgnoreCase(CharSequence s, String t) {
            int last = s.length() - t.length();
            for (int i = 0; i <= last; i++) {
                if (startsWithIgnoreCase(s, i, t)) return i;
            }
            return -1;
        }
    }

    /**
     * 思考草稿与正文之间的结束标记。
     *
     * <p>全部取自真实响应：{@code </think>} 是 sensenova-6.8-flash 关闭思考后
     * 写进 content 的那个（5 个问题 5 次都出现）；其余是各家常用来包思考块的
     * 写法，多列几个不花代价——没有出现在 content 里就永远匹配不到。
     */
    private static final String[] CLOSE_TAGS = {
            "</think>", "</thinking>", "</thought>", "<｜end▁of▁thinking｜>",
            "[/thinking]", "[/think]", "[/thought]",
    };

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
        r.park = park;
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
        if (r.park == Park.MARK && content.length() > 0) {
            // 非流式一次给全，直接按标记切：标记之前的进思考块，之后的是正文
            int at = indexOfCloseTag(content);
            if (at >= 0) {
                String draft = content.substring(0, at);
                String body = content.substring(at + closeTagLengthAt(content, at));
                if (draft.length() > 0) {
                    if (thinkingOpen(r)) r.display.append(draft);
                    else r.display.append("[thinking]").append(draft).append("[/thinking]");
                    r.reasoning.append(draft);
                    r.parkCount++;
                    r.parked.append(draft).append("\n");
                }
                content = body;
            }
        }
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
        Park pendingPark = park;
        Peeler peeler = park == Park.MARK ? new Peeler() : null;
        // 草稿在「确认有标记」之前不上屏：正常回答不能先显示再被收回
        StringBuilder draft = new StringBuilder();
        StringBuilder body = new StringBuilder();

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
                        if (peeler != null && !peeler.bodyStarted()) {
                            // 关闭思考的请求：先按标记判断这一片是草稿还是正文
                            peeler.feed(ct, draft, body);
                            if (peeler.bodyStarted()) {
                                // 见到标记了（或确认这条流没有标记）
                                String d = peeler.draftText();
                                if (d.length() > 0 && peeler.peeled()) {
                                    // 先把攒下的草稿一次性转进思考块，再把正文接上——
                                    // 顺序不能反，否则正文会跑到思考块前面去
                                    draft.setLength(0);
                                    park(r, d);
                                    LogUtil.i(TAG, "peeled %d chars of draft before "
                                            + "</think>-style marker", d.length());
                                }
                                r.display.append(body);
                                r.content.setLength(0);
                                r.content.append(body);
                            }
                        } else {
                            r.display.append(ct);
                            r.content.append(ct);
                        }
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
            // 流结束：还没见到标记的残留按正文交出去（短回复可能压根没有草稿）
            if (peeler != null && !peeler.bodyStarted()) {
                String rest = peeler.finish();
                if (rest.length() > 0) {
                    body.append(rest);
                    r.display.append(rest);
                    r.content.setLength(0);
                    r.content.append(body);
                }
                pendingPark = Park.NONE;
            }
            trimTail(r.display);
            trimTail(r.content);
            if (r.parkCount > 0) {
                LogUtil.i(TAG, "parked %d draft segment(s) into thinking block",
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

    /** content 里第一个结束标记的下标，没有返回 -1。 */
    private static int indexOfCloseTag(String s) {
        int best = -1;
        for (String t : CLOSE_TAGS) {
            int i = s.indexOf(t);
            if (i >= 0 && (best < 0 || i < best)) best = i;
        }
        return best;
    }

    /** 该位置命中的结束标记长度。 */
    private static int closeTagLengthAt(String s, int at) {
        for (String t : CLOSE_TAGS) {
            if (s.startsWith(t, at)) return t.length();
        }
        return 0;
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
     * <p><b>已废弃，保留只为兼容旧调用</b>：按段首引导词判断无法穷举——实测同一个
     * 模型 5 个问题给出 5 种不同开头（{@code Thinking Process:}、
     * {@code Here's my thought process}、{@code 好的，用户要求我…}、
     * {@code 用户让我写…}、{@code Here's a thinking process}），而正文开头同样
     * 层出不穷。现在统一按结束标记切分，见 {@link Peeler}。
     *
     * @return 恒为 false
     * @deprecated 改用 {@link Peeler}
     */
    @Deprecated
    static boolean thinkingIsDraft(CharSequence seg) {
        return false;
    }

    /**
     * 从一帧里取推理 token 数。
     *
     * <p>保留给测试与旧调用方；解析规则统一在 {@link UsageView}。
     */
    static int reasoningTokensOf(JSONObject frame) {
        return UsageView.of(frame).reasoning;
    }
}
