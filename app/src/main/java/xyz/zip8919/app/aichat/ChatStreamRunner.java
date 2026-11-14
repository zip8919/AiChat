package xyz.zip8919.app.aichat;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 一次流式回复的完整编排：HTTP 请求 → SSE 解析 → 工具轮次循环 → 失败分类 → 收尾。
 *
 * <p>从 {@code MainActivity.sendStreamingRequest} 搬出来（原 500 行单方法）。拆分后的
 * 分工是：
 * <ul>
 *   <li>{@link ChatTransport}：请求体与连接</li>
 *   <li>{@link SseRound}：单轮协议解析</li>
 *   <li>{@link ToolExecutor}：一批 tool_calls 的执行与配对</li>
 *   <li>{@link StreamRenderer}：流式渲染节流</li>
 *   <li>本类：把它们按轮次串起来，并决定失败时怎么收尾</li>
 * </ul>
 *
 * <p><b>不变量</b>（并发正确性全靠这几条，改动前先读）：
 * <ol>
 *   <li>{@code session.isCurrent(gen)} 为 false 表示已被新请求或打断取代，
 *       此后任何 DOM 写入、messages 写入都必须是无效操作。</li>
 *   <li>续跑（{@link StreamJob.Resume}）不新增 AI 消息、不重复插 DOM，只重发最后一轮。</li>
 *   <li>HTTP 400/422 且首轮且尚未回退过：去掉 tools 重试一次。这是唯一的重试，
 *       因为多数「模型不支持工具」的端点只在这一种情况下会成功。</li>
 *   <li>工具轮次失败时保留 AI 消息挂「从出错处重试」；首轮失败则回滚空占位。
 *       判断依据是 {@code round > 0 && 已有工具历史 && 不是 400/422}。</li>
 * </ol>
 */
final class ChatStreamRunner {

    private static final String TAG = "StreamRunner";

    /**
     * 一条回复最多允许连续几轮工具调用。
     *
     * <p>必须有硬上限：模型每轮都回一个 tool_call 时，下面的 {@code while (true)}
     * 唯一的自然出口（本轮没有 tool_calls）永远不出现。搜索类工具尤其容易触发
     * 「再查一下」的循环，代价不是卡一下而是双向爆炸——conversation 与
     * st.toolHistory 每轮追加一份工具正文，下一轮再整串序列化上传。
     * 8 轮足够覆盖正常的多步检索，超限后保留已渲染内容挂错误卡片，可续跑。
     */
    private static final int MAX_TOOL_ROUNDS = 8;

    /** 视图侧能力。继承 {@link StreamRenderer.Ui}，渲染器直接用同一个实现。 */
    interface Ui extends StreamRenderer.Ui {
        void toast(String msg);

        /** 「正在搜索…」这类单行状态，会替换掉上一条状态。 */
        void showStatus(String text, int generation, String trace, int aiIndex);

        /** 用完整 trace 重渲染（工具卡片追加后调用）。 */
        void renderTrace(String trace, int generation, String status, int aiIndex);

        /** trace 因超长裁掉了前缀。渲染基准必须作废——裁剪后文本变短，
         * 仍按旧基准判断会把它当成「过期快照」永久丢弃。 */
        void onTraceTrimmed();

        /**
         * 端点拒绝了 {@code stream_options}。宿主需要把「该 provider 不支持用量统计」
         * 记下来，下次请求不再带这个字段，否则每次对话都要多吃一次 400。
         */
        void onUsageUnsupported();

        /**
         * 429 等待开始：显示倒计时卡片，右侧带「立即重试」。
         *
         * <p>不用 {@link #showStatus} 表达：状态行是纯文本，塞不进按钮，
         * 而用户最需要的恰恰是「别让我干等 30 秒」。
         * reason 是从 429 响应体解析出的服务端具体原因，常驻显示在卡片上。
         */
        void onRetryWaiting(int attempt, int maxAttempts, long waitMs, String reason,
                int generation, int aiIndex);

        /** 等待结束（已重发或已中止）：收起卡片。 */
        void onRetryWaitEnd();

        /**
         * 单轮请求结算，只在端点真的返回了 usage 时回调。
         *
         * <p>宿主拿它做两件事：把该轮用量加进消息（流式中就能看到数字在涨）、
         * 记一条逐轮明细。和 {@code UsageStats} 是两条独立链路——统计要的是全局
         * 时间序列，这里要的是「这条消息的哪一轮花了多少」。
         */
        void onRoundUsage(int aiIndex, int prompt, int completion, int cached, long ms);
    }

    /** 会话侧能力：请求代数、连接句柄、收尾。 */
    interface Session {
        /** 是否仍是当前请求（只看代数，用于 finally 判断是否还要收尾）。 */
        boolean isCurrent(int generation);

        /** 是否仍在进行中（代数匹配且未被用户打断），用于中断检测。 */
        boolean isAlive(int generation);

        void setConnection(HttpURLConnection conn);

        /**
         * 用户点了「立即重试」：读到一次 true 后必须清除。
         *
         * <p>用「消费」而不是「查询」，是因为等待循环每 200 ms 问一次，
         * 查询式会让一次点击把后面所有等待都跳过。
         */
        boolean consumeRetryNow();

        /** 请求线程退出前必然调用一次（除非代数已过期）。 */
        void onSettled(Settled settled);
    }

    /** 收尾数据。成功看 {@link #content}，失败看 {@link #errorDetail} 与 {@link #round}。 */
    static final class Settled {
        final boolean failed;
        final int aiIndex;
        final int generation;
        final int round;
        final int httpCode;
        final String errorDetail;
        final String trace;
        final String toolHistory;
        final String apiText;
        final String content;
        /** 多轮累加后的用量，全 0 表示端点没返回。 */
        final int promptTokens;
        final int completionTokens;
        final int totalTokens;
        /** 命中前缀缓存的 token 数，口径见 {@link Message#cachedTokens}。 */
        final int cachedTokens;
        final long finishedAt;
        /** 断流补进 trace 的半截正文起点，-1 表示没有；见 {@link State#partialStart}。 */
        final int partialStart;

        Settled(boolean failed, int aiIndex, int generation, int round, int httpCode,
                String errorDetail, String trace, String toolHistory, String apiText, String content,
                int promptTokens, int completionTokens, int totalTokens, int cachedTokens,
                long finishedAt, int partialStart) {
            this.failed = failed;
            this.aiIndex = aiIndex;
            this.generation = generation;
            this.round = round;
            this.httpCode = httpCode;
            this.errorDetail = errorDetail;
            this.trace = trace;
            this.toolHistory = toolHistory;
            this.apiText = apiText;
            this.content = content;
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.totalTokens = totalTokens;
            this.cachedTokens = cachedTokens;
            this.finishedAt = finishedAt;
            this.partialStart = partialStart;
        }
    }

    private final StreamJob job;
    private final Ui ui;
    private final Session session;
    private final WebSearchTool.RequestGuard guard;
    /** 未配置搜索密钥的提示只弹一次。 */
    private boolean warnedNoSearchKey;

    ChatStreamRunner(StreamJob job, Ui ui, Session session) {
        this.job = job;
        this.ui = ui;
        this.session = session;
        this.guard = new WebSearchTool.RequestGuard() {
            public boolean shouldAbort() {
                return !ChatStreamRunner.this.session.isAlive(ChatStreamRunner.this.job.generation);
            }

            public void onConnection(HttpURLConnection c) {
                if (ChatStreamRunner.this.session.isAlive(ChatStreamRunner.this.job.generation)) {
                    ChatStreamRunner.this.session.setConnection(c);
                }
            }
        };
    }

    /**
     * 把本地图片编码成请求体里的 data URI。
     *
     * <p>用 data URI 而不是远端 URL：端点未必能访问外网，尤其国内模型
     * 拿不到境外图床的地址，结果是「图没传上去、模型却说看不到」，比失败更难受。
     * 编码是同步的（一张 1024px 的图 30~80 ms），只在请求线程上跑。
     */
    private static final ChatHistory.ImageProvider VISION_IMAGES = new ChatHistory.ImageProvider() {
        public String dataUri(AttachmentText.File f) {
            return AttachmentFiles.apiDataUri(f.path);
        }
    };

    private JSONArray buildConversation(String sysPrompt) throws Exception {
        JSONArray msgs = ChatHistory.build(job.snapshot, job.aiMessage, sysPrompt,
                job.vision, VISION_IMAGES);
        // 继续生成：build() 已经 skip 掉本轮 AI 占位，这里把「已生成的那半截」
        // 接成最后一条 assistant 前缀，模型才能从断点继续而不是重新回答。
        return job.isContinue()
                ? ChatHistory.appendPrefix(msgs, job.cont.prefixText, job.cont.officialPrefix)
                : msgs;
    }

    /** 阻塞执行，由调用方放进请求线程。 */
    void execute() {
        final long startMs = System.currentTimeMillis();
        final State st = new State(job);
        activeTrace = st.trace;
        HttpURLConnection conn = null;
        try {
            JSONArray conversation = job.isResume()
                    // 续跑复用的是上一次失败时保存的 conversation，那份可能停在
                    // 「assistant(tool_calls) 已写入、tool 结果还没写」的半截状态。
                    // 原样重发必然 400，这里先做一次配对校验。
                    ? ChatHistory.sanitizeToolPairs(job.resume.conversation)
                    : buildConversation(job.effectiveSystemPrompt);
            JSONArray toolDefs = WebSearchTool.buildToolDefinitions(
                    job.webSearchEnabled, job.httpToolEnabled);
            // 继续生成不带 tools：官方 /beta 前缀续写要求最后一条就是 assistant，
            // 挂上 tool_calls 会让端点改写这一轮；降级路径的续写指令也不该被工具调用打断。
            boolean toolsEnabled = !job.isContinue();
            boolean fallbackTried = false;
            boolean includeUsage = job.includeUsage;
            boolean usageDowngraded = false;
            // 端点是否接受思考相关参数。自建 provider 一律被写成 thinkingType=object +
            // 参数名 thinking，严格的 OpenAI 兼容端点会直接 400，需要一次性降级。
            // 官方 /beta 前缀续写不认本项目的 thinking / thinking_budget 参数，
            // 直接不下发，省掉一次必然的 400 降级请求。
            boolean sendThinking = !(job.isContinue() && job.cont.officialPrefix);
            boolean thinkingDowngraded = false;
            // 最近一次 429 的响应体。重试耗尽那一轮可能已读不到 body，用它兜底进错误卡片。
            String last429Body = "";
            /**
             * 429 重试计数：按「单个 HTTP 请求」独立，不能跨轮次累计。
             *
             * <p>{@code retryRound} 记住当前计数属于哪一轮，进入下一轮（工具轮次）就清零；
             * 同一轮里的多次重试 {@code round} 不变，计数继续累加。
             *
             * <p>早期版本把 retryCount 声明在轮次循环之外，一次回复里只要第 1 轮重试过 2 次，
             * 第 2 轮遇到 429 时第一次重试就要等 {@code base×4}、第二次等 {@code base×8}，
             * 而且重试机会已经被前面那轮吃光了——用户看到的是「越到后面越卡、最后直接失败」。
             */
            int retryRound = -1;
            int retryCount = 0;
            int round = job.isResume() ? job.resume.round : 0;
            // 上限按「本次 execute 实际跑的工具轮数」算，不能用绝对 round：
            // 续跑的 round 起点是上次累计轮数（可能已是 8），round+1>=MAX 会
            // 恒真 → 点「从出错处重试」后先发一次 HTTP、跑一轮工具就立刻失败，
            // 无限重试且永远拿不到最终回答（还重复记账）。
            int usedRounds = 0;
            if (job.isContinue()) {
                ui.showStatus("正在继续生成...", job.generation, st.trace.toString(), job.aiIndex);
            } else if (job.isResume() && st.trace.length() > 0) {
                ui.showStatus("正在重新生成...", job.generation, st.trace.toString(), job.aiIndex);
            }

            // 跨轮次复用：baseRendered 记录的是「已推到 DOM 的 trace」，
            // 每轮重新 new 会让它清零，工具卡片之后多出一整次无谓的整段重渲染
            final StreamRenderer renderer = new StreamRenderer(ui, job.aiIndex, job.generation,
                    job.realtimeRender);
            while (true) {
                st.round = round;
                // 换轮次就重置 429 计数：每一轮都是一个新的 HTTP 请求，
                // 它该有自己的退避曲线和自己的重试预算
                if (round != retryRound) {
                    retryRound = round;
                    retryCount = 0;
                    last429Body = "";  // 上一轮的配额超限原因不能串到本轮错误卡片
                }
                if (!session.isAlive(job.generation)) {
                    LogUtil.i(TAG, "round loop aborted before round %d", round);
                    return;
                }
                if (round > 0) {
                    // 上一轮的正文还挂在 tail 上，先原地并进 base 再挂状态行。
                    // 不搬的话，下一轮第一片会把 tail 覆盖成新的增量，
                    // 用户看到的就是「屏幕闪一下，回答少了一截」。
                    renderer.commitTail(st.trace.toString());
                    ui.showStatus("正在整理搜索结果...", job.generation, st.trace.toString(), job.aiIndex);
                }

                JSONObject body = ChatTransport.buildRequestBody(job.modelName, job.provider,
                        job.thinkingLevel, conversation, job.streamEnabled,
                        toolsEnabled ? toolDefs : null, includeUsage, sendThinking);
                // 统计口径：一次 open 就是一次 HTTP 请求（含工具轮、含重试）。
                // 重试另有 retries 计数，方便区分「请求多」是工具轮还是 429。
                UsageStats.get().onRequest(job.modelName, job.providerName);
                final long roundStartMs = System.currentTimeMillis();
                conn = ChatTransport.open(job.provider,
                        job.isContinue() && job.cont.officialPrefix);
                session.setConnection(conn);
                writeBody(conn, body.toString());

                long reqMs = System.currentTimeMillis();
                int code = conn.getResponseCode();
                LogUtil.i(TAG, "HTTP %d in %d ms (round=%d)", code, System.currentTimeMillis() - reqMs, round);

                if (code != 200) {
                    // 429 最优先处理：此时 body 还没读，直接关掉重连最干净
                    if (code == 429 && job.retryPolicy.enabled
                            && retryCount < job.retryPolicy.maxAttempts) {
                        // 429 的具体原因（配额 / 频率 / TPM 限制）写在响应体里，旧代码直接
                        // 关连接重连，用户只看到「等待重试」，永远不知道服务端说了什么。
                        String body429 = ChatTransport.readErrorBody(conn);
                        if (body429 != null && body429.trim().length() > 0) last429Body = body429;
                        String reason429 = ErrorReport.providerMessage(last429Body);
                        if (reason429.length() > 160) reason429 = reason429.substring(0, 160) + "…";
                        int retryAfter = ChatTransport.retryAfterSeconds(conn);
                        long waitMs = job.retryPolicy.delayFor(retryCount, retryAfter);
                        retryCount++;
                        UsageStats.get().onRetry();
                        LogUtil.w(TAG, "HTTP 429, retry %d/%d in %d ms (retryAfter=%d) reason=%s",
                                retryCount, job.retryPolicy.maxAttempts, waitMs, retryAfter,
                                LogUtil.preview(reason429, 200));
                        // 原因常驻在重试卡片上，不用 Toast：一闪而过看不清，
                        // 而退避可能长达几十秒，用户需要能反复看清「到底为什么」。
                        ui.onRetryWaiting(retryCount, job.retryPolicy.maxAttempts, waitMs,
                                reason429, job.generation, job.aiIndex);
                        ChatTransport.close(conn);
                        conn = null;
                        session.setConnection(null);
                        boolean alive = sleepInterruptible(waitMs, job.generation);
                        // 收卡片：hideRetryCard 只由这个回调触发，用户停止时
                        // 代数不变仍要收；代数过期说明新请求已接管 UI，
                        // 此时不能把新请求刚显示的倒计时卡片隐掉。
                        if (session.isCurrent(job.generation)) ui.onRetryWaitEnd();
                        if (!alive) {
                            LogUtil.i(TAG, "429 retry wait aborted by user");
                            return;
                        }
                        continue;
                    }

                    String errBody = ChatTransport.readErrorBody(conn);
                    LogUtil.e(TAG, "request failed: HTTP %d, body=%s", code, LogUtil.preview(errBody, 800));

                    // 降级顺序很关键：usage 排在最后。
                    // onUsageUnsupported() 会把这个 provider「不支持 stream_options」永久写进
                    // prefs，之后再也不会请求 usage（token/缓存命中统计永久丢失且不自愈）。
                    // 而 tools / thinking 被拒是最常见的 400 原因，若先试 usage 降级，
                    // 就会把「不支持 tools」误记成「不支持 usage」。
                    // 一级降级：端点不认识 tools 参数。首轮 400 时去掉工具与搜索
                    // 系统提示重试一次，保证原有普通对话不受联网开关影响。
                    if ((code == 400 || code == 422) && toolsEnabled && round == 0 && !fallbackTried) {
                        fallbackTried = true;
                        toolsEnabled = false;
                        conversation = buildConversation(job.systemPrompt);
                        ui.toast("当前模型不支持工具调用，已按普通对话发送");
                        ChatTransport.close(conn);
                        conn = null;
                        session.setConnection(null);
                        continue;
                    }

                    // 二级降级：端点不认思考参数（自建 provider 会被写成
                    // thinkingType=object + 参数名 thinking，官方 OpenAI 这类严格端点
                    // 直接 400）。去掉 thinking 整套字段再发一次，别让用户卡死在这里。
                    if ((code == 400 || code == 422) && sendThinking && !thinkingDowngraded) {
                        thinkingDowngraded = true;
                        sendThinking = false;
                        LogUtil.w(TAG, "endpoint rejected thinking params, retry without them");
                        ChatTransport.close(conn);
                        conn = null;
                        session.setConnection(null);
                        continue;
                    }

                    // 三级降级（最后）：端点不认 stream_options。严格校验参数的网关会直接 400。
                    // 去掉它重发，并让宿主记住，避免每次对话都白吃一个 400。
                    if ((code == 400 || code == 422) && includeUsage && !usageDowngraded) {
                        usageDowngraded = true;
                        includeUsage = false;
                        LogUtil.w(TAG, "endpoint rejected stream_options, retry without usage");
                        ui.onUsageUnsupported();
                        ChatTransport.close(conn);
                        conn = null;
                        session.setConnection(null);
                        continue;
                    }

                    st.failed = true;
                    st.httpCode = code;
                    String bodyForReport = (errBody != null && errBody.trim().length() > 0)
                            ? errBody : last429Body;
                    st.errorDetail = ErrorReport.build(job.providerName, job.modelName,
                            job.chatEndpoint, round, code, bodyForReport, null);
                    if (retryCount > 0) {
                        st.errorDetail += "\n\n已自动重试 " + retryCount + " 次仍失败";
                    }
                    LogUtil.e(TAG, "request failed (round %d): %s", round, st.errorDetail);
                    String failReason = ErrorReport.providerMessage(bodyForReport);
                    if (failReason.length() > 80) failReason = failReason.substring(0, 80) + "…";
                    if (session.isCurrent(job.generation)) {
                        ui.toast("请求失败: HTTP " + code
                                + (failReason.length() > 0 ? " · " + failReason : ""));
                    }
                    return;
                }

                renderer.beginRound();
                SseRound.Result rr = readRound(conn, st, renderer);
                st.chunks = rr.chunks;
                // 统计要的是「本轮这一发」的用量，而 st 里是跨轮累计值。
                // 不做差直接记，一条带 3 次工具轮的回复会把总量算成 1+2+3=6 倍。
                final int ptBefore = st.promptTokens;
                final int ctBefore = st.completionTokens;
                final int hitBefore = st.cachedTokens;
                st.addUsage(rr.usage);
                final int dPt = st.promptTokens - ptBefore;
                final int dCt = st.completionTokens - ctBefore;
                final int dHit = st.cachedTokens - hitBefore;
                final long roundMs = System.currentTimeMillis() - roundStartMs;
                UsageStats.get().onUsage(job.modelName, job.providerName,
                        dPt, dCt, dHit, roundMs, false);
                // 逐轮明细：光看累计值对不上账单（3 轮工具调用的回复，用户不知道
                // 多出来的 token 花在哪一轮）。有真实 usage 才记，估算的轮次由宿主补。
                if (dPt > 0 || dCt > 0) {
                    ui.onRoundUsage(job.aiIndex, dPt, dCt, dHit, roundMs);
                }
                if (rr.error != null) {
                    // 用户打断时 abort 会让读循环立刻退出，chunks 可能为 0，
                    // 这里会误报「服务端没有返回任何内容」。打断不是失败，静默返回。
                    if (!session.isAlive(job.generation)) {
                        LogUtil.i(TAG, "round %d aborted by user: %s", round, rr.error);
                        return;
                    }
                    st.failed = true;
                    // 本轮流式已经吐出来的内容不能丢：错误卡片要挂在这段内容之下，
                    // 否则用户看到的是「回答突然没了，只剩一张报错卡」
                    // 与「断流 catch」一致记下截断点：SseRound 正常返回 r.error
                    // （协议层错误）不走 catch，原来 partialStart 恒为 -1，续跑
                    // 就不截断这半截内容，模型重写本轮 → 界面与落盘各两份。
                    st.partialStart = st.trace.length();
                    st.trace.append(rr.display);
                    if (TraceKit.cap(st.trace)) ui.onTraceTrimmed();
                    st.errorDetail = ErrorReport.build(job.providerName, job.modelName,
                            job.chatEndpoint, round, rr.httpCode, rr.rawBody, rr.cause);
                    if (session.isCurrent(job.generation)) ui.toast(rr.error);
                    return;
                }

                ChatTransport.close(conn);
                conn = null;
                session.setConnection(null);

                if (rr.toolCalls.length() > 0 && ToolCalls.hasUsable(rr.toolCalls)) {
                    // 本轮思考/半截回答保留在界面上，不要被下一轮覆盖
                    String baseBefore = st.trace.toString();
                    st.trace.append(rr.display);
                    if (TraceKit.cap(st.trace)) ui.onTraceTrimmed();
                    // 插入工具卡片前必须先把本轮 tail 并进 base。
                    // StreamRenderer.commitTail 的契约明写「本轮结束（或要插入工具
                    // 卡片）」，但这里原先只在下一轮 round>0 的开头调用，漏了这一次。
                    // 漏掉的后果是同一轮内容出现三份：
                    //   1) tail 里流式渲染的 display 还挂着；
                    //   2) 下面 ToolExecutor 的 onTraceAppended 触发
                    //      renderTraceAsync，它的 lastStreamTrace 仍是旧值，于是把
                    //      rr.display 当成「新增增量」streamAppendBase 追加进 base
                    //      —— 思考块和正文一起重复；
                    //   3) 下一轮 commitTail 再把 tail 那份搬进 base。
                    // 先 commit 一次：tail 归位、lastStreamTrace 推进到含 display，
                    // 后续 renderTraceAsync 的 part 就只剩工具卡片，各一份且不乱序。
                    // 先把本轮的最后一帧强制落地，再搬 tail。
                    //
                    // 每一帧都受 200/120ms 节流，而分片通常 20~50ms 一片，于是本轮
                    // 最后一个分片几乎必然还在节流窗口里没推下去。只 commitTail 的话，
                    // 它会把 DOM 现有的 tail 搬进 base、并把 baseRendered 记账成
                    // 「已等于完整文本」——那一截正文永久缺失（屏幕上句子断在工具卡片处），
                    // 而落盘的 content 是完整的，切走再回来又对得上，极难排查。
                    // base 必须是不含本轮正文的 trace（与 onProgress 契约一致）：
                    // 传 append 后的 trace 会让同一段正文既进 streamBase 又进
                    // streamTailHtml，commitTail 搬完 tail 后屏幕上出现两份。
                    renderer.flushAndWait(baseBefore, rr.display.toString(), 2500L);
                    renderer.commitTail(st.trace.toString());
                    // 注意：不把 rr.content 累进 apiText——工具轮次的正文已经作为
                    // assistant(tool_calls).content 存在于 toolHistory 里，再拼一次会让模型看到重复内容
                    ToolCalls.appendAssistant(conversation, st.toolHistory,
                            rr.content.toString(), rr.toolCalls, rr.reasoning.toString());
                    ToolExecutor.Outcome outcome = ToolExecutor.run(rr.toolCalls,
                            job.maxCallsPerRound, conversation, st.toolHistory, st.trace,
                            st.sources, job.question, toolHost);
                    if (outcome.aborted) {
                        LogUtil.i(TAG, "tool loop aborted at round %d", round);
                        return;
                    }
                    // 轮次硬上限。模型的 tool_calls 可以无限循环（尤其搜索类工具：
                    // 每轮都「再查一下」），而这个 while 的唯一出口是「本轮没有
                    // tool_calls」。没有上限时的真实代价不是卡一下：
                    // conversation 每轮追加 assistant+tool（工具结果是全量正文，
                    // 可达 MB 级），下一轮把整串重新序列化上传 —— 第 n 轮上传量
                    // 约等于 n 倍单轮体积，同时 st.toolHistory 同步膨胀并最终落盘。
                    // TraceKit.cap 只保护 trace，保护不了这两处。
                    usedRounds++;
                    if (usedRounds >= MAX_TOOL_ROUNDS) {
                        st.failed = true;
                        st.errorDetail = ErrorReport.build(job.providerName, job.modelName,
                                job.chatEndpoint, round, -1, "",
                                new Exception("工具调用连续 " + MAX_TOOL_ROUNDS + " 轮未结束，已停止"));
                        LogUtil.w(TAG, "tool round limit %d reached, stop looping", MAX_TOOL_ROUNDS);
                        if (session.isCurrent(job.generation)) {
                            ui.toast("工具调用轮次过多，已停止（已保留已有内容）");
                        }
                        return;
                    }
                    round++;
                    continue;
                }

                st.trace.append(rr.display);
                if (TraceKit.cap(st.trace)) ui.onTraceTrimmed();
                st.apiText = job.isContinue()
                        ? job.cont.prefixText + rr.content.toString()
                        : rr.content.toString();
                break;
            }

            // 成功：结构化落盘。来源独立成字段，不拼进正文，避免下次请求把它当上下文喂回去
            job.aiMessage.sources = dedupeSources(st.sources, ConversationManager.MAX_SOURCES);
            job.aiMessage.apiText = st.apiText;
            if (st.toolHistory.length() > 0) {
                job.aiMessage.toolHistory = st.toolHistory.toString();
            }
            st.content = st.trace.toString();
            LogUtil.i(TAG, "rounds done: sources=%d apiText=%d toolHistory=%d",
                    job.aiMessage.sources.size(), st.apiText.length(), st.toolHistory.length());
            ui.nextSeq();
        } catch (Exception e) {
            st.failed = true;
            st.errorDetail = ErrorReport.build(job.providerName, job.modelName,
                    job.chatEndpoint, st.round, -1, "", e);
            LogUtil.e(TAG, "streaming request exception: %s", e.getMessage(), e);
            // 用户主动打断（打断已自增代数）导致的 Socket closed 不算请求失败
            if (session.isCurrent(job.generation)) {
                ui.toast("请求失败: " + e.getMessage());
            } else {
                LogUtil.i(TAG, "stream exception ignored: interrupted by user");
            }
        } finally {
            st.finishedAt = System.currentTimeMillis();
            LogUtil.i(TAG, "<<< request end: elapsed=%d ms, chunks=%d, failed=%s, tokens=%d/%d cached=%d",
                    st.finishedAt - startMs, st.chunks, st.failed,
                    st.promptTokens, st.completionTokens, st.cachedTokens);
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) { }
            }
            // 只有代数匹配才收尾：过期线程不得污染新请求的 UI 与持久化状态
            if (session.isCurrent(job.generation)) {
                session.setConnection(null);
                session.onSettled(st.toSettled(job));
            }
        }
    }

    /**
     * 可被用户打断的等待。不能直接 {@code Thread.sleep}：单次最多要等 30 秒，
     * 这期间用户点了停止却毫无反应，看起来就是「卡死了」。
     *
     * @return false 表示等待期间请求已被取代/打断，调用方应立即返回
     */
    private boolean sleepInterruptible(long totalMs, int generation) {
        long end = System.currentTimeMillis() + Math.max(0L, totalMs);
        while (true) {
            // 「立即重试」与「停止」是两条完全相反的出口，必须先判断前者：
            // 点停止时 isAlive 也变 false，顺序反过来会把「立即重试」当成中止。
            if (session.consumeRetryNow()) {
                LogUtil.i(TAG, "429 wait skipped by user (retry now)");
                return session.isAlive(generation);
            }
            long left = end - System.currentTimeMillis();
            if (left <= 0) return session.isAlive(generation);
            if (!session.isAlive(generation)) return false;
            try {
                Thread.sleep(Math.min(200L, left));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private SseRound.Result readRound(HttpURLConnection conn, final State st,
            final StreamRenderer renderer) throws Exception {
        // 半成品出口：流式读到一半断流时，本轮已经推到屏幕上的文本在 Result 里，
        // 不接住就会随异常一起丢掉（见下面的 catch）。
        SseRound.Result[] partial = new SseRound.Result[1];
        // 节流点从 SseRound 挪到这里，是为了让「复制 StringBuilder」只在真要渲染时发生。
        // 间隔与 StreamRenderer 的 INTERVAL_HTML/TEXT_MS 对齐：比它更密只会被下游丢掉，
        // 而那些被丢掉的帧，上游照样为 toString 付了全串复制的钱。
        final long interval = job.realtimeRender ? 200 : 120;
        final long[] lastPush = new long[] { 0 };
        try {
            return SseRound.read(job.streamEnabled, conn.getInputStream(),
                    new SseRound.Progress() {
                        public void onProgress(CharSequence display) {
                            long now = System.currentTimeMillis();
                            if (now - lastPush[0] < interval) return;
                            lastPush[0] = now;
                            renderer.onProgress(st.trace.toString(), display.toString());
                        }
                    },
                    new SseRound.Abort() {
                        public boolean isAborted() {
                            return !session.isAlive(job.generation);
                        }
                    }, partial);
        } catch (Exception e) {
            // 断流（读超时、连接重置、TLS 中断）不是「什么都没发生」：调用方会走失败
            // 链路，用 st.trace 重写气泡并落盘。这里不把半截 display 补进 trace，
            // 用户已经看到的那段回答就会被抹掉，写进会话文件的内容也少一截。
            SseRound.Result r = partial[0];
            if (r != null && r.display.length() > 0) {
                st.partialStart = st.trace.length();
                st.trace.append(r.display);
                if (TraceKit.cap(st.trace)) ui.onTraceTrimmed();
                LogUtil.i(TAG, "partial round kept after %s: display=%d",
                        e.getClass().getSimpleName(), r.display.length());
            }
            if (r != null && r.content.length() > 0) {
                st.apiText = job.isContinue()
                        ? job.cont.prefixText + r.content.toString()
                        : r.content.toString();
            }
            throw e;
        }
    }

    private static void writeBody(HttpURLConnection conn, String payload) throws Exception {
        OutputStream os = conn.getOutputStream();
        try {
            os.write(payload.getBytes("UTF-8"));
        } finally {
            os.close();
        }
    }

    /** 工具执行：把运行期策略（能力是否就绪、key 从哪来）与 {@link ToolExecutor} 的通用逻辑隔开。 */
    private final ToolExecutor.Host toolHost = new ToolExecutor.Host() {
        public boolean isAlive() {
            return session.isAlive(job.generation);
        }

        public String execute(String name, JSONObject args, String question) {
            if (!job.searchReady) return null;
            return WebSearchTool.executeTool(job.searchProvider, name, args, question,
                    guard, job.useTavily, job.tavilyKey);
        }

        public String unavailableResult() {
            return job.useTavily
                    ? "{\"error\":\"未配置 Tavily API Key，无法使用联网搜索\"}"
                    : "{\"error\":\"未配置 DeepSeek API Key，无法使用联网搜索\"}";
        }

        public boolean visionEnabled() {
            return job.vision;
        }

        public String imageDataUri(String url) {
            try {
                // 按 ImageTokens.apiSide 的像素预算编码（≤1302²）：模型最多用
                // 1302² 的像素，再大也是被缩回去，白占请求体
                return AttachmentFiles.remoteToDataUri(url, AttachmentFiles.API_BUDGET);
            } catch (Exception e) {
                LogUtil.w("ChatStreamRunner", "imageDataUri failed: %s", e.getMessage());
                return null;
            }
        }

        public void onUnavailable() {
            if (warnedNoSearchKey) return;
            warnedNoSearchKey = true;
            ui.toast(job.useTavily
                    ? "未配置 Tavily API Key，联网搜索不可用"
                    : "未配置 DeepSeek API Key，联网搜索不可用");
        }

        public void onStatus(String text) {
            ui.nextSeq();
            ui.showStatus(text, job.generation, currentTrace(), job.aiIndex);
        }

        public void onTraceAppended(String traceSoFar) {
            if (session.isCurrent(job.generation)) {
                ui.renderTrace(traceSoFar, job.generation, null, job.aiIndex);
            }
        }

        public void onTraceTrimmed() {
            ui.onTraceTrimmed();
        }

        private String currentTrace() {
            return activeTrace == null ? "" : activeTrace.toString();
        }
    };

    /** 当前正在执行的请求的 trace；{@link #execute()} 单线程写入。 */
    private volatile StringBuilder activeTrace;

    /** 单次请求的可变状态。取代原方法里的六个 {@code final X[]} 单元素数组。 */
    static final class State {
        final StringBuilder trace;
        final JSONArray toolHistory = new JSONArray();
        final List<String[]> sources;
        String apiText = "";
        String errorDetail = "";
        String content = "";
        int round;
        int httpCode = -1;
        int chunks;
        boolean failed;
        int promptTokens;
        int completionTokens;
        int totalTokens;
        /** 命中前缀缓存的 token 数；两家厂商字段名不同，见 {@link #cacheHitOf}。 */
        int cachedTokens;
        /** 收尾时刻，用于算端到端生成速度。 */
        long finishedAt;
        /**
         * 断流时补进 trace 的那段「半截正文」的起点（-1 表示没有）。
         *
         * <p>续跑要从 conversation 重建请求，模型会重写整轮，所以那段半截文本
         * 必须能从续跑基准里截掉，否则新旧两份回答会连在一起。
         */
        int partialStart = -1;

        /**
         * 累加一轮的 usage。工具轮次会发多次请求，逐轮相加才是这条消息的真实消耗；
         * 只取最后一轮会漏掉前面所有工具轮。
         */
        void addUsage(JSONObject u) {
            if (u == null) return;
            int p = u.optInt("prompt_tokens", 0);
            int c = u.optInt("completion_tokens", 0);
            int t = u.optInt("total_tokens", p + c);
            promptTokens += p;
            completionTokens += c;
            totalTokens += t;
            cachedTokens += cacheHitOf(u);
        }

        /**
         * 解析「命中缓存的输入 token」。
         *
         * <p>DeepSeek 放在顶层 {@code prompt_cache_hit_tokens}；OpenAI 系放在
         * {@code prompt_tokens_details.cached_tokens}。两边都认，取第一个大于 0 的。
         * 都不认识就返回 0——宁可这条不显示，也不要拿 prompt_tokens 冒充命中量。
         */
        private static int cacheHitOf(JSONObject u) {
            int hit = u.optInt("prompt_cache_hit_tokens", 0);
            if (hit > 0) return hit;
            // 部分网关把 DeepSeek 的字段也塞进 details 里，一并认
            JSONObject d = u.optJSONObject("prompt_tokens_details");
            if (d != null) {
                hit = d.optInt("cached_tokens", 0);
                if (hit > 0) return hit;
                hit = d.optInt("cache_hit_tokens", 0);
            }
            return Math.max(0, hit);
        }

        State(StreamJob job) {
            // 继续生成：trace 从已有正文起步，流式分片才能接在它后面，
            // 而不是把屏幕上已经有的半截回答整体覆盖掉。
            String base = job.isContinue() ? job.cont.displayBase
                    : (job.isResume() && job.resume.trace != null ? job.resume.trace : "");
            this.trace = new StringBuilder(base);
            this.sources = job.initialSources();
            this.round = job.isResume() ? job.resume.round : 0;
            if (job.isContinue()) {
                // apiText 是下次进历史的干净正文，必须带上已生成的前缀；
                // 结算时若只写本轮新内容，这条回答在历史里就只剩后半截。
                this.apiText = job.cont.prefixText;
            } else if (job.isResume() && job.resume.apiText != null) {
                this.apiText = job.resume.apiText;
            }
            if (job.isResume() && job.resume.toolHistoryJson != null
                    && job.resume.toolHistoryJson.length() > 2) {
                try {
                    JSONArray prev = new JSONArray(job.resume.toolHistoryJson);
                    for (int i = 0; i < prev.length(); i++) {
                        toolHistory.put(prev.get(i));
                    }
                } catch (Exception e) {
                    LogUtil.w(TAG, "resume: bad toolHistory, starting empty: %s", e.getMessage());
                }
            }
        }

        Settled toSettled(StreamJob job) {
            return new Settled(failed, job.aiIndex, job.generation, round, httpCode,
                    errorDetail, trace.toString(), toolHistory.toString(), apiText, content,
                    promptTokens, completionTokens, totalTokens, cachedTokens, finishedAt,
                    partialStart);
        }
    }

    /** 来源按 URL 去重，保留首次出现的顺序，最多 limit 条。 */
    static List<Message.Source> dedupeSources(List<String[]> raw, int limit) {
        List<Message.Source> out = new ArrayList<Message.Source>();
        if (raw == null || raw.isEmpty()) return out;
        List<String> seen = new ArrayList<String>();
        for (int i = 0; i < raw.size() && out.size() < limit; i++) {
            String[] row = raw.get(i);
            if (row == null || row.length < 2) continue;
            String url = row[1];
            if (url == null || url.trim().length() == 0) continue;
            url = url.trim();
            if (seen.contains(url)) continue;
            seen.add(url);
            String title = row[0];
            if (title == null || title.trim().length() == 0) title = url;
            out.add(new Message.Source(title.trim(), url));
        }
        return out;
    }
}
