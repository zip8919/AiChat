package xyz.zip8919.app.aichat;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
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

    /**
     * 端点「正常收尾但一个字都没有」时自动重发的次数。
     *
     * <p>这类空回复不报错：端点回了 200、收到 [DONE]，但 delta 里既没有正文也没有
     * 工具调用。用户看到的是一个空气泡，等下去什么都不会来，只能自己发现再点一次重试。
     * 一次重发就能恢复的情况占绝大多数，继续空手返回只会让用户以为应用坏了。
     *
     * <p>只重发空回复，不重发超时或断流——那两类需要用户决定是否继续等待。
     */
    private static final int MAX_EMPTY_RETRY = 1;

    /** 空回复重发前的等待：给端点一点缓存/调度上的余量，立刻重发往往同样为空。 */
    private static final long EMPTY_RETRY_DELAY_MS = 800;

    /**
     * 单次请求里允许几次「连接阶段（还没拿到 HTTP 状态）」失败后的自动重连。
     *
     * <p>这类失败没有服务端的任何回应：连接超时、域名解析失败、TLS 握手被拒、
     * 连接被重置。重发一次不会有重复计费，而偶发的握手失败重试一次基本都能过。
     * 早期版本只对 429 自动重试，这类失败直接判死，用户看到的就是「发出去了、
     * 没有任何回应」，错误提示一闪而过，随后空气泡被回滚。
     */
    private static final int MAX_CONNECT_RETRY = 2;

    /** 连接阶段重连的等待，按次数递增：1s、2s… */
    private static final long CONNECT_RETRY_BASE_MS = 1000L;

    /** 收到响应之后多久一个字节都没读到，就提示「服务端还没有返回内容」。 */
    private static final long NO_DATA_HINT_MS = 20000L;

    /** 收到响应后这么久无任何字节，直接断开并报错。低于读超时，让用户更早知道结果。 */
    private static final long STALL_LIMIT_MS = 90000L;

    /** 视图侧能力。继承 {@link StreamRenderer.Ui}，渲染器直接用同一个实现。 */
    interface Ui extends StreamRenderer.Ui {
        void toast(String msg);

        /** 「正在搜索…」这类单行状态，会替换掉上一条状态。 */
        void showStatus(String text, int generation, String trace, int aiIndex);

        /** 用完整 trace 重渲染（工具卡片追加后调用）。 */
        void renderTrace(String trace, int generation, String status, int aiIndex);

        /**
         * 把工具卡片直接写进正在流式输出的气泡（只用于关掉实时渲染的场景）。
         *
         * <p>关掉实时渲染时 tail 走纯文本、base 要等轮次切换才重渲染，中间这一段
         * 屏幕上是空的：模型调用了什么工具、执行到哪一步，用户完全看不到，中途失败
         * 的轮次更是不会留下任何记录。这条通道用纯 DOM 写卡片，开销远小于实时渲染。
         *
         * @param incrementalHtml 只含本轮新增的卡片片段
         * @param reset           true 表示这一轮第一次写，先清掉上一轮的临时片段
         */
        void renderLiveTools(String incrementalHtml, boolean reset);

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
         * 收到响应后迟迟没有内容：显示一行说明，不是错误，消息仍在等待。
         *
         * <p>没有这条提示时，慢端点与卡死从界面上完全看不出区别。
         */
        void onWaitingHint(String text, int generation, int aiIndex);

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

    /** 本次请求的最终结果码，只用于诊断记录与日志，不参与任何判断。 */
    private String outcomeCode = "";

    /** 阻塞执行，由调用方放进请求线程。 */
    void execute() {
        final long startMs = System.currentTimeMillis();
        final State st = new State(job);
        activeTrace = st.trace;
        HttpURLConnection conn = null;
        StreamRenderer renderer = null;
        diagBegin(st);
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
            /** 关闭时端点拒收了提供商自填字段：重发时不再带它，写全的家族字段继续保留。 */
            boolean offNativeDropped = false;
            // 关闭时对象形态被拒：重发只留 top-level 布尔关闭。
            // 上一次请求已经被端点拒收过的模型直接从这一档开始，省掉两次
            // 必然 400 的回合（每次都要重发完整请求体）。
            boolean offBoolOnly = ThinkingOff.rejected(job.modelName);
            // 用户是否选了「关闭」。与 thinkingOff 分开：探测/实测确认关不掉时，
            // thinkingOff 为 false（不发关闭字段，按最低档走）。此时思考内容照常
            // 展示——关不掉就如实呈现，不靠隐藏内容假装关掉了。
            boolean userWantsNoThinking = "off".equals(job.thinkingLevel);
            boolean thinkingOff = userWantsNoThinking
                    && "off".equals(ChatTransport.resolveThinkingLevel(
                            job.modelName, job.provider, job.thinkingLevel));
            // 关闭字段一次写全（见 ThinkingOff），不再有「换方案」的阶梯：
            // 端点静默忽略某个字段时，靠的不是换下一次，而是同一份请求里
            // 已经带上了它能认的那个字段。
            final String modelOriginal = job.modelName;
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
            /** 本轮空回复已经重发过几次；换轮次（工具轮）清零。 */
            int emptyRetry = 0;
            /** 已提示过「正文里混进思考」的轮次：同一轮重发不重复提示。 */
            int parkedNoticeRound = -1;
            /**
             * 连接阶段失败后的重连计数。只统计「还没拿到状态码」的失败；
             * 拿到状态码之后的错误各走各的降级链，不受它影响。
             */
            int connectRetry = 0;
            int round = job.isResume() ? job.resume.round : 0;
            // 上限按「本次 execute 实际跑的工具轮数」算，不能用绝对 round：
            // 续跑的 round 起点是上次累计轮数（可能已是 8），round+1>=MAX 会
            // 恒真 → 点「从出错处重试」后先发一次 HTTP、跑一轮工具就立刻失败，
            // 无限重试且永远拿不到最终回答（还重复记账）。
            int usedRounds = 0;
            if (job.isContinue()) {
                ui.showStatus(Strings.get(R.string.chatstreamrunner_001), job.generation, st.trace.toString(), job.aiIndex);
            } else if (job.isResume() && st.trace.length() > 0) {
                ui.showStatus(Strings.get(R.string.chatstreamrunner_002), job.generation, st.trace.toString(), job.aiIndex);
            }

            // 跨轮次复用：baseRendered 记录的是「已推到 DOM 的 trace」，
            // 每轮重新 new 会让它清零，工具卡片之后多出一整次无谓的整段重渲染
            renderer = new StreamRenderer(ui, job.aiIndex, job.generation,
                    job.realtimeRender);
            // 诊断（仅 debug 包）：把「界面选了什么、实际发什么」的前提一次写全。
            // 关闭不生效最常见的一种是「界面显示关闭，请求里根本没带关闭字段」，
            // 光看界面永远发现不了，这里先把两者摆在一起。
            boolean modelBlocked = false;
            try {
                ModelInfo dmHead = ConfigManager.getInstance()
                        .findModel(job.providerName, job.modelName);
                if (dmHead != null) modelBlocked = dmHead.thinkingOffBlocked;
            } catch (Throwable ignored) {
                // 查不到配置不影响对话
            }
            if (OffDiag.ENABLED) {
                String resolvedHead = ChatTransport.resolveThinkingLevel(
                        job.modelName, job.provider, job.thinkingLevel);
                OffDiag.log("==== 发送开始 ====");
                OffDiag.log("模型=%s 提供商=%s 端点=%s", job.modelName, job.providerName,
                        OffDiag.endpoint(job.provider == null ? null : job.provider.apiUrl));
                String cfgHead = "";
                try {
                    cfgHead = ConfigManager.getInstance().getThinkingLevel();
                } catch (Throwable ignored) {
                    // 读不到就留空，不影响日志其余部分
                }
                OffDiag.log("档位：请求=%s 配置=%s 实际=%s 关闭字段=%s", job.thinkingLevel,
                        cfgHead, resolvedHead,
                        thinkingOff ? "下发" : (userWantsNoThinking ? "不下发" : "无需"));
                if (!job.thinkingLevel.equals(cfgHead)) {
                    OffDiag.log("注意：请求档位与配置不一致（按界面选中项发送）");
                }
                OffDiag.log("模型记录：关不掉=%s 已探测档位=%s", modelBlocked,
                        modelRecord());
                OffDiag.log("提供商配置：thinkingType=%s paramName=%s",
                        job.provider == null ? "" : job.provider.thinkingType,
                        job.provider == null ? "" : job.provider.thinkingParamName);
                if (userWantsNoThinking && !sendThinking) {
                    OffDiag.log("注意：本次因续写官方前缀而不下发思考参数");
                }
            }
            int reqSeq = 0;
            while (true) {
                st.round = round;
                // 换轮次就重置 429 计数：每一轮都是一个新的 HTTP 请求，
                // 它该有自己的退避曲线和自己的重试预算
                if (round != retryRound) {
                    retryRound = round;
                    retryCount = 0;
                    emptyRetry = 0;
                    last429Body = "";  // 上一轮的配额超限原因不能串到本轮错误卡片
                }
                if (!session.isAlive(job.generation)) {
                    LogUtil.i(TAG, "round loop aborted before round %d", round);
                    if (outcomeCode.length() == 0) outcomeCode = "aborted";
                    return;
                }
                if (round > 0) {
                    // 上一轮的正文还挂在 tail 上，先原地并进 base 再挂状态行。
                    // 不搬的话，下一轮第一片会把 tail 覆盖成新的增量，
                    // 用户看到的就是「屏幕闪一下，回答少了一截」。
                    renderer.commitTail(st.trace.toString());
                    ui.showStatus(Strings.get(R.string.chatstreamrunner_003), job.generation, st.trace.toString(), job.aiIndex);
                }
                ChatTransport.Prepared prep = ChatTransport.prepare(job.modelName, job.provider,
                        job.thinkingLevel, conversation, job.streamEnabled,
                        toolsEnabled ? toolDefs : null, includeUsage, sendThinking,
                        // 注意这里不能取反：offBoolOnly 的语义是「只留布尔」，
                        // 初始 false 表示要写全。写成 !offBoolOnly 会让第一发就落到
                        // 只发 enable_thinking=false 的降级档——认 thinking 对象的
                        // 端点（混元、DeepSeek、Kimi、GLM）于是完全收不到关闭意图，
                        // 表现为「换了多少家、多少模型都关不掉」。
                        !offNativeDropped, offBoolOnly);
                JSONObject body = prep.body;
                final String payload = body.toString();
                diagRound(st, round, prep, payload);
                if (OffDiag.ENABLED) {
                    OffDiag.log("请求 #%d 轮次=%d %s", reqSeq, round, OffDiag.shape(body));
                    OffDiag.log("  思考字段：%s",
                            OffDiag.fields(body, job.provider == null
                                    ? null : job.provider.thinkingParamName));
                    if (thinkingOff && (offNativeDropped || offBoolOnly)) {
                        OffDiag.log("  降级状态：自填字段=%s 仅布尔关闭=%s",
                                offNativeDropped ? "已去掉" : "保留",
                                offBoolOnly ? "是" : "否");
                    }
                    reqSeq++;
                }
                // 关闭思考只靠协议字段：不再追加「不要思考」之类的提示词约束。
                // 提示词管不住模型内部推理，只会污染上下文、让模型输出变形。
                // 统计口径：一次 open 就是一次 HTTP 请求（含工具轮、含重试）。
                // 重试另有 retries 计数，方便区分「请求多」是工具轮还是 429。
                // 统计按「界面上的名字」记：用户看报表时要能对上模型下拉里的那一项
                UsageStats.get().onRequest(modelLabelOf(job.modelName), providerLabelOf(job.providerName));
                final long roundStartMs = System.currentTimeMillis();
                int code;
                try {
                    conn = ChatTransport.open(job.provider,
                            job.isContinue() && job.cont.officialPrefix, job.streamEnabled);
                    session.setConnection(conn);
                    diagEvent(st, "connect", "ok in " + (System.currentTimeMillis() - roundStartMs) + "ms");
                    long tWrite = System.currentTimeMillis();
                    writeBody(conn, payload);
                    diagEvent(st, "body-sent", "ok " + payload.length() + " chars in "
                            + (System.currentTimeMillis() - tWrite) + "ms");

                    long reqMs = System.currentTimeMillis();
                    code = conn.getResponseCode();
                    LogUtil.i(TAG, "HTTP %d in %d ms (round=%d)", code, System.currentTimeMillis() - reqMs, round);
                    diagEvent(st, "http", "code=" + code + " in " + (System.currentTimeMillis() - reqMs) + "ms");
                    diagHeaders(st, conn);
                } catch (Exception openFail) {
                    // 连接阶段失败：还没有拿到任何状态码，服务端必然没有处理这次请求。
                    // 逐次重连；仍失败就把原因写进错误卡片，不让请求无声无息地消失。
                    if (!session.isAlive(job.generation)) {
                        outcomeCode = "aborted";
                        return;
                    }
                    diagEvent(st, "connect-failed", openFail.getClass().getName() + ": " + openFail.getMessage());
                    LogUtil.w(TAG, "connect phase failed (round=%d, attempt=%d): %s",
                            round, connectRetry, describe(openFail));
                    ChatTransport.close(conn);
                    conn = null;
                    session.setConnection(null);
                    if (connectRetry < MAX_CONNECT_RETRY) {
                        long wait = CONNECT_RETRY_BASE_MS << connectRetry;
                        connectRetry++;
                        if (session.isCurrent(job.generation)) {
                            ui.showStatus(Strings.get(R.string.chatstreamrunner_022,
                                    connectRetry, MAX_CONNECT_RETRY), job.generation,
                                    st.trace.toString(), job.aiIndex);
                        }
                        if (!sleepInterruptible(wait, job.generation)) {
                            outcomeCode = "aborted";
                            return;
                        }
                        continue;
                    }
                    st.failed = true;
                    st.httpCode = -1;
                    st.errorDetail = ErrorReport.build(job.providerName, job.modelName,
                            job.chatEndpoint, round, -1, "", openFail);
                    if (session.isCurrent(job.generation)) {
                        ui.toast(Strings.get(R.string.chatstreamrunner_023, friendly(openFail)));
                    }
                    outcomeCode = "connect-failed";
                    return;
                }

                if (code != 200) {
                    // 429 最优先处理：此时 body 还没读，直接关掉重连最干净
                    if (code == 429 && job.retryPolicy.enabled
                            && retryCount < job.retryPolicy.maxAttempts) {
                        // 429 的具体原因（配额 / 频率 / TPM 限制）写在响应体里，旧代码直接
                        // 关连接重连，用户只看到「等待重试」，永远不知道服务端说了什么。
                        String body429 = ChatTransport.readErrorBody(conn);
                        if (body429 != null && body429.trim().length() > 0) last429Body = body429;
                        diagBody(st, "429-body", body429, 2000);
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
                    diagBody(st, "error-body", errBody, 4000);
                    if (OffDiag.ENABLED) {
                        OffDiag.log("请求 #%d 失败 HTTP=%d 响应体=%s", reqSeq - 1, code,
                                OffDiag.snippet(errBody));
                    }

                    // 降级顺序很关键：usage 排在最后。
                    // onUsageUnsupported() 会把这个 provider「不支持 stream_options」永久写进
                    // prefs，之后再也不会请求 usage（token/缓存命中统计永久丢失且不自愈）。
                    // 而 tools / thinking 被拒是最常见的 400 原因，若先试 usage 降级，
                    // 就会把「不支持 tools」误记成「不支持 usage」。
                    // 一级降级：端点不认识 tools 参数。首轮 400 时去掉工具与搜索
                    // 系统提示重试一次，保证原有普通对话不受联网开关影响。
                    if ((code == 400 || code == 422) && toolsEnabled && round == 0 && !fallbackTried) {
                        diagEvent(st, "degrade", "drop tools after HTTP " + code);
                        fallbackTried = true;
                        toolsEnabled = false;
                        conversation = buildConversation(job.systemPrompt);
                        ui.toast(Strings.get(R.string.chatstreamrunner_004));
                        ChatTransport.close(conn);
                        conn = null;
                        session.setConnection(null);
                        continue;
                    }

                    // 二级降级：端点不认思考参数（自建 provider 会被写成
                    // thinkingType=object + 参数名 thinking，官方 OpenAI 这类严格端点
                    // 直接 400）。
                    //
                    // 「关闭」分三步退，每一步都保留关闭意图，直到端点连最基本的
                    // 关闭字段都拒收才承认关不掉：
                    //   1) 去掉提供商自填的字段——它是用户按文档写的，配错了自己就是
                    //      400 的来源；写全的家族字段照常下发；
                    //   2) 只留 top-level 的布尔关闭（enable_thinking:false /
                    //      自填字段=false），去掉对象形态与模板参数——只认布尔的
                    //      端点对多余的对象字段同样会报错；
                    //   3) 整套去掉，回到端点默认。走到这里说明这家对任何关闭写法都
                    //      直接拒收，记下来，档位里不再列「关闭」。
                    if ((code == 400 || code == 422) && sendThinking && thinkingOff
                            && !offNativeDropped && !offBoolOnly) {
                        offNativeDropped = true;
                        diagEvent(st, "degrade", "thinking-off: drop provider field after HTTP " + code);
                        LogUtil.w(TAG, "thinking-off rejected, retry without provider-declared field");
                        OffDiag.log("降级：端点拒收，去掉自填字段重发（关闭意图保留）");
                        ChatTransport.close(conn);
                        conn = null;
                        session.setConnection(null);
                        continue;
                    }
                    if ((code == 400 || code == 422) && sendThinking && thinkingOff
                            && !offBoolOnly) {
                        offBoolOnly = true;
                        ThinkingOff.rememberRejected(modelOriginal);
                        diagEvent(st, "degrade", "thinking-off: boolean only after HTTP " + code);
                        LogUtil.w(TAG, "thinking-off rejected, retry with boolean switches only");
                        OffDiag.log("降级：端点仍拒收，只留布尔关闭重发");
                        ChatTransport.close(conn);
                        conn = null;
                        session.setConnection(null);
                        continue;
                    }
                    if ((code == 400 || code == 422) && sendThinking && thinkingOff) {
                        diagEvent(st, "degrade", "thinking-off rejected in every form");
                        ThinkingOff.rememberCannotOff(modelOriginal);
                        sendThinking = false;
                        thinkingOff = false;
                        LogUtil.w(TAG, "thinking-off rejected in every form, send defaults");
                        OffDiag.log("降级：所有关闭写法都被拒收，按端点默认重发（本次不再尝试关闭）");
                        if (session.isCurrent(job.generation)) {
                            ui.toast(Strings.get(R.string.chatstreamrunner_019));
                        }
                        ChatTransport.close(conn);
                        conn = null;
                        session.setConnection(null);
                        continue;
                    }

                    // 开着思考时端点不认参数：整套去掉再发一次，别让用户卡死在这里。
                    if ((code == 400 || code == 422) && sendThinking && !thinkingDowngraded) {
                        diagEvent(st, "degrade", "drop thinking params after HTTP " + code);
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
                        diagEvent(st, "degrade", "drop stream_options after HTTP " + code);
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
                    outcomeCode = "http-" + code;
                    String bodyForReport = (errBody != null && errBody.trim().length() > 0)
                            ? errBody : last429Body;
                    st.errorDetail = ErrorReport.build(job.providerName, job.modelName,
                            job.chatEndpoint, round, code, bodyForReport, null);
                    if (retryCount > 0) {
                        st.errorDetail += Strings.get(R.string.chatstreamrunner_005, retryCount);
                    }
                    LogUtil.e(TAG, "request failed (round %d): %s", round, st.errorDetail);
                    String failReason = ErrorReport.providerMessage(bodyForReport);
                    if (failReason.length() > 80) failReason = failReason.substring(0, 80) + "…";
                    if (session.isCurrent(job.generation)) {
                        ui.toast(failReason.length() > 0
                                ? Strings.get(R.string.chatstreamrunner_016, code, failReason)
                                : Strings.get(R.string.chatstreamrunner_007, code));
                    }
                    return;
                }

                // 工具轮次的「新一轮」只是同一份回答换一次请求，不是换一条消息：
                // 这里不重置渐显。重置会让已画出的字被搬进 base、渐变窗口清零，
                // 屏幕上表现为整段回答消失、再从第一个字快速长回来。
                // 真正的新回答由宿主按请求代数重新起步。
                // 收到状态码后一个字节都收不到：先提示，超过上限直接断开。
                // 没有这一段时，这类请求会静静躺到 120s 读超时，界面上没有任何信号。
                startStallWatch(st, conn, job.generation);
                SseRound.Result rr = readRound(conn, st, renderer, userWantsNoThinking);
                st.chunks = rr.chunks;
                diagEvent(st, "stream-end", "chunks=" + rr.chunks
                        + " elapsed=" + (System.currentTimeMillis() - roundStartMs) + "ms"
                        + " content=" + rr.content.length() + " reasoning=" + rr.reasoning.length()
                        + " reasoningTokens=" + rr.reasoningTokens + " tools=" + rr.toolCalls.length());
                if (OffDiag.ENABLED) {
                    OffDiag.log("响应 #%d 分片=%d 推理分片=%d 推理字符=%d 推理tokens=%d 正文=%d",
                            reqSeq - 1, rr.chunks, rr.thinkingChunks,
                            rr.reasoning.length(), rr.reasoningTokens, rr.content.length());
                    if (rr.reasoning.length() > 0) {
                        OffDiag.log("  推理片段：%s", OffDiag.snippet(rr.reasoning));
                    }
                    if (rr.parkCount > 0) {
                        OffDiag.log("  正文里拆出的思考草稿：%d 段，首段：%s",
                                rr.parkCount, OffDiag.snippet(rr.parked));
                    }
                    OffDiag.log(OffDiag.verdict(userWantsNoThinking,
                            thinkingOff && sendThinking,
                            ChatTransport.resolveThinkingLevel(job.modelName, job.provider,
                                    job.thinkingLevel),
                            modelBlocked, rr.reasoning.length(), rr.reasoningTokens,
                            rr.parkCount));
                }
                // 只留布尔开关就能过：记住这一档，下一轮别再从对象形态试起。
                // 反过来，整套字段都过、说明这个端点不挑，把旧记忆清掉。
                if (thinkingOff && sendThinking && !offBoolOnly) {
                    ThinkingOff.clearRejected(job.modelName);
                }
                if (rr.parkCount > 0 && parkedNoticeRound != round) {
                    // 端点没真正关掉思考：草稿已经转进思考块，但仍要让用户知道
                    // 这次回答不完全是「关闭」的状态，否则他只会觉得作答很奇怪。
                    parkedNoticeRound = round;
                    if (session.isCurrent(job.generation)) {
                        ui.toast(Strings.get(R.string.chatstreamrunner_030));
                    }
                }
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
                UsageStats.get().onUsage(modelLabelOf(job.modelName), providerLabelOf(job.providerName),
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
                        outcomeCode = "aborted";
                        return;
                    }
                    // 协议层失败也计费：有的端点先正常推理、再吐一个坏分片，
                    // 上面的成功分支没走到，这一轮的 token 不记就永远对不上账单
                    st.addUsage(rr.usage);
                    // 端点正常收尾却一个字都没有：先自动重发，别让用户对着空气泡等。
                    // 重发前不需要清理 DOM —— 这一轮什么都没画，tail 是空的；
                    // lastStreamTrace 也还停在上一轮结束的位置，重发会直接续上。
                    if (rr.emptyReply && emptyRetry < MAX_EMPTY_RETRY) {
                        emptyRetry++;
                        diagEvent(st, "retry", "empty reply, resend " + emptyRetry);
                        LogUtil.w(TAG, "empty reply at round %d, auto retry %d/%d",
                                round, emptyRetry, MAX_EMPTY_RETRY);
                        ChatTransport.close(conn);
                        conn = null;
                        session.setConnection(null);
                        if (!sleepInterruptible(EMPTY_RETRY_DELAY_MS, job.generation)) {
                            LogUtil.i(TAG, "empty-reply retry wait aborted by user");
                            return;
                        }
                        continue;
                    }
                    st.failed = true;
                    outcomeCode = rr.emptyReply ? "empty-reply" : "stream-error";
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
                    final int traceBefore = st.trace.length();
                    ToolExecutor.Outcome outcome = ToolExecutor.run(rr.toolCalls,
                            job.maxCallsPerRound, conversation, st.toolHistory, st.trace,
                            st.sources, job.question, toolHost);
                    if (!outcome.aborted && traceBefore != baseBefore.length()) {
                        // trace 在这一轮里被裁剪过：增量基准已经失效，补一帧整段重建
                        ui.renderTrace(st.trace.toString(), job.generation, null, job.aiIndex);
                    }
                    if (outcome.aborted) {
                        LogUtil.i(TAG, "tool loop aborted at round %d", round);
                        outcomeCode = "aborted";
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
                                new Exception(Strings.get(R.string.chatstreamrunner_008, MAX_TOOL_ROUNDS)));
                        LogUtil.w(TAG, "tool round limit %d reached, stop looping", MAX_TOOL_ROUNDS);
                        outcomeCode = "tool-round-limit";
                        diagEvent(st, "limit", "tool rounds " + MAX_TOOL_ROUNDS + " reached");
                        if (session.isCurrent(job.generation)) {
                            ui.toast(Strings.get(R.string.chatstreamrunner_010));
                        }
                        return;
                    }
                    round++;
                    continue;
                }

                // 工具调用成功但一个字都没写（只有 usage 帧）也算失败：没有它，
                // 这种情况会走到成功分支，用内容为空的结果覆盖气泡，用户看到回答凭空消失。
                if (rr.display.length() == 0 && ToolCalls.hasUsable(rr.toolCalls)) {
                    st.failed = true;
                    outcomeCode = "empty-with-tools";
                    st.errorDetail = ErrorReport.build(job.providerName, job.modelName,
                            job.chatEndpoint, round, rr.httpCode, rr.rawBody,
                            new Exception("tool_calls delta arrived without any payload fields"));
                    diagEvent(st, "anomaly", "tool_calls deltas without id/name/arguments");
                    if (session.isCurrent(job.generation)) {
                        ui.toast(Strings.get(R.string.chatstreamrunner_024));
                    }
                    return;
                }
                st.trace.append(rr.display);
                if (TraceKit.cap(st.trace)) ui.onTraceTrimmed();
                outcomeCode = "success";
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
            // 最后一轮正文不能在这里搬进 base：搬移会先让宿主「一次放完」渐显，
            // 等于把还没浮出来的正文整段甩出来，收尾的「直接显示」就没有可跳过的区间了。
            // 最后一帧已经由 readRound 的收尾帧落到 tail 并交给渐显缓冲，
            // 剩下的交给 settleStream 在追平之后整段定稿。
            LogUtil.i(TAG, "rounds done: sources=%d apiText=%d toolHistory=%d",
                    job.aiMessage.sources.size(), st.apiText.length(), st.toolHistory.length());
            ui.nextSeq();
        } catch (Exception e) {
            st.failed = true;
            // 读超时 / 连接重置 / TLS 中断分开标记，导出记录时一眼能对上是哪一类
            outcomeCode = e instanceof SocketTimeoutException ? "read-timeout" : "exception";
            st.errorDetail = ErrorReport.build(job.providerName, job.modelName,
                    job.chatEndpoint, st.round, -1, "", e);
            LogUtil.e(TAG, "streaming request exception: %s", e.getMessage(), e);
            diagEvent(st, "exception", describe(e));
            // 用户主动打断（打断已自增代数）导致的 Socket closed 不算请求失败
            if (session.isCurrent(job.generation)) {
                ui.toast(Strings.get(R.string.chatstreamrunner_011, e.getMessage()));
            } else {
                LogUtil.i(TAG, "stream exception ignored: interrupted by user");
            }
        } finally {
            st.finishedAt = System.currentTimeMillis();
            LogUtil.i(TAG, "<<< request end: elapsed=%d ms, chunks=%d, failed=%s, tokens=%d/%d cached=%d",
                    st.finishedAt - startMs, st.chunks, st.failed,
                    st.promptTokens, st.completionTokens, st.cachedTokens);
            // 帧速率：长回答「越写越慢」时，这一行能看出是哪一段开始掉速（间隔被拉开）
            if (renderer != null) LogUtil.i(TAG, "stream frames: %s", renderer.frameSummary());
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) { }
            }
            diagEnd(st, outcomeCode, startMs);
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
    /**
     * 诊断用：当前模型在配置里的档位记录。
     *
     * <p>「界面显示关闭、请求却按低档发」这类问题，根源就在这份记录里
     * （{@code thinkingOffBlocked} 会让解析后的档位从 off 落到最低档），
     * 所以每次发送都把它的原样写进日志。
     */
    private String modelRecord() {
        try {
            ModelInfo m = ConfigManager.getInstance().findModel(job.providerName, job.modelName);
            if (m == null) return "（配置里没有这条模型）";
            return "probed=" + m.levelsProbed()
                    + " levels=" + (m.thinkingLevels == null ? "" : m.thinkingLevels)
                    + " unverifiable=" + m.levelsUnverifiable;
        } catch (Throwable t) {
            return "（读取失败：" + t.getMessage() + "）";
        }
    }

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
            final StreamRenderer renderer, boolean userWantsNoThinking) throws Exception {
        // 半成品出口：流式读到一半断流时，本轮已经推到屏幕上的文本在 Result 里，
        // 不接住就会随异常一起丢掉（见下面的 catch）。
        SseRound.Result[] partial = new SseRound.Result[1];
        // 节流点从 SseRound 挪到这里，是为了让「复制 StringBuilder」只在真要渲染时发生。
        // 间隔与 StreamRenderer 的 INTERVAL_HTML/TEXT_MS 对齐：比它更密只会被下游丢掉，
        // 而那些被丢掉的帧，上游照样为 toString 付了全串复制的钱。
        final long interval = job.realtimeRender ? 200 : 120;
        final long[] lastPush = new long[] { 0 };
        try {
            InputStream in = conn.getInputStream();
            diagEvent(st, "body-open", "input stream ready");
            // 关闭思考时还要再看一层：端点收了关闭字段、但把草稿塞进 content。
            // 只有用户选的是「关闭」才拆，开了思考或续写时不碰正文。
            // 先只判第一段：第一段是回答就整段按正文，不再往下判。连接一个关不掉
            // 思考的端点时每轮都要过一遍这个判断，判得越少越不容易误伤。
            SseRound.Park park = !userWantsNoThinking ? SseRound.Park.NONE
                    : (job.isContinue() ? SseRound.Park.NONE : SseRound.Park.FIRST_PARAGRAPH);
            return SseRound.read(job.streamEnabled, in,
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
                    }, partial, new SseRound.Trace() {
                        public void onFirstByte(long now) {
                            st.firstByteSeen = true;
                            diagEvent(st, "first-byte", "at " + now);
                        }

                        public void onFirstData(long now, int chunks) {
                            diagEvent(st, "first-data", "chunk#" + chunks);
                        }

                        public void onEnd(long now, int chunks, boolean done) {
                            diagEvent(st, "stream-close", "chunks=" + chunks + " done-mark=" + done);
                        }
                    }, park);
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

    /**
     * 收到响应后等内容的看门狗。
     *
     * <p>专治「发出去了、服务端也回了状态，但之后一个字节都没有」：界面原本没有任何
     * 信号，只能等到读超时。这里在 {@value #NO_DATA_HINT_MS} 时提示一行说明，
     * 超过 {@value #STALL_LIMIT_MS} 仍未收到第一个字节就直接断开——由调用方走失败
     * 链路，错误卡片写清「服务端已响应但没有返回内容」。
     *
     * <p>只在「还没收到第一个字节」时生效：一旦开始出内容，慢是模型在思考，
     * 交给读超时判断，看门狗不再介入。
     */
    private void startStallWatch(final State st, final HttpURLConnection target, final int generation) {
        final long startAt = System.currentTimeMillis();
        Thread t = new Thread(new Runnable() {
            public void run() {
                boolean hinted = false;
                while (true) {
                    if (st.firstByteSeen) return;
                    if (!session.isAlive(generation)) return;
                    long waited = System.currentTimeMillis() - startAt;
                    if (waited >= STALL_LIMIT_MS) {
                        LogUtil.w(TAG, "no byte %d ms after response, aborting", waited);
                        diagEvent(st, "stall", "no byte in " + waited + "ms, aborted");
                        try { target.disconnect(); } catch (Exception ignored) { }
                        return;
                    }
                    if (!hinted && waited >= NO_DATA_HINT_MS && session.isCurrent(generation)) {
                        hinted = true;
                        diagEvent(st, "stall-hint", waited + "ms without body");
                        ui.onWaitingHint(Strings.get(R.string.chatstreamrunner_025,
                                waited / 1000L), generation, job.aiIndex);
                    }
                    try {
                        Thread.sleep(1000L);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "aichat-stall-watch");
        t.setDaemon(true);
        t.start();
    }

    // ---------- 诊断记录 ----------
    // 全部在请求线程上同步写；开关关掉时 job.diag 为 null，这里只剩一次判空。

    /** 请求开始：模型、提供商、档位、能力、检索配置等一次性信息。 */
    private void diagBegin(State st) {
        if (job.diag == null) return;
        RequestDiag.Recorder d = job.diag;
        String resolved = "";
        ModelInfo m = null;
        try {
            m = ConfigManager.getInstance().findModel(job.providerName, job.modelName);
            resolved = ChatTransport.resolveThinkingLevel(job.modelName, job.provider, job.thinkingLevel);
        } catch (Throwable ignored) {
        }
        d.head("request", job.isResume() ? "resume" : (job.isContinue() ? "continue" : "new"));
        d.kv("provider", job.providerName + (job.provider == null || job.provider.displayName == null
                ? "" : " (" + job.provider.displayName + ")"));
        d.kv("endpoint", job.chatEndpoint);
        d.kv("model", job.modelName);
        d.kv("modelLabel", m == null ? "-" : m.label());
        d.kv("thinkingLevel", job.thinkingLevel + " -> " + resolved);
        d.kv("thinkingType", job.provider == null ? "-" : job.provider.thinkingType
                + "/" + job.provider.thinkingParamName);
        d.kv("capability", m == null ? "-" : ("vision=" + m.supportsVision
                + " thinking=" + m.supportsThinking
                + " levels=" + (m.thinkingLevels == null || m.thinkingLevels.length() == 0
                        ? "未探测" : m.thinkingLevels)
                + (m.levelsUnverifiable ? "(不校验)" : "")));
        d.kv("stream", job.streamEnabled + " includeUsage=" + job.includeUsage
                + " realtime=" + job.realtimeRender);
        d.kv("webSearch", job.webSearchEnabled + " ready=" + job.searchReady
                + " backend=" + (job.useTavily ? "tavily" : "deepseek")
                + " httpTool=" + job.httpToolEnabled);
        d.kv("retry429", job.retryPolicy.enabled + " max=" + job.retryPolicy.maxAttempts
                + " base=" + job.retryPolicy.baseMs + "ms backoff=" + job.retryPolicy.backoff);
        d.kv("context", "messages=" + job.snapshot.size() + " aiIndex=" + job.aiIndex
                + " generation=" + job.generation
                + (job.isResume() ? " resumeRound=" + job.resume.round : ""));
    }

    /** 每轮请求：实际下发的档位、各字段是否带上，以及完整请求体（构造处已排除密钥）。 */
    private void diagRound(State st, int round, ChatTransport.Prepared prep, String payload) {
        if (job.diag == null) return;
        RequestDiag.Recorder d = job.diag;
        d.head("round", String.valueOf(round + 1));
        d.kv("thinking", prep.thinkingLevel + (prep.thinkingSent ? "" : " (未下发)"));
        d.kv("tools", prep.toolsSent ? "on" : "off");
        d.kv("stream_options", prep.usageRequested ? "on" : "off");
        d.body("request-body", payload, 200000);
    }

    private void diagHeaders(State st, HttpURLConnection conn) {
        if (job.diag == null || conn == null) return;
        try {
            job.diag.event("response-headers", "request-id=" + header(conn, "x-request-id")
                    + " server=" + header(conn, "server")
                    + " content-type=" + header(conn, "content-type")
                    + " content-length=" + header(conn, "content-length")
                    + " retry-after=" + header(conn, "retry-after"));
        } catch (Throwable ignored) {
        }
    }

    private void diagEvent(State st, String event, String detail) {
        if (job.diag != null) job.diag.event(event, detail);
    }

    private void diagBody(State st, String key, String content, int max) {
        if (job.diag != null) job.diag.body(key, content, max);
    }

    private void diagEnd(State st, String outcome, long startMs) {
        if (job.diag == null) return;
        long total = System.currentTimeMillis() - startMs;
        RequestDiag.Recorder d = job.diag;
        String code = outcome == null || outcome.length() == 0 ? "unknown" : outcome;
        d.kv("result", code + " http=" + st.httpCode + " failed=" + st.failed
                + " tokens=" + st.promptTokens + "/" + st.completionTokens
                + " cached=" + st.cachedTokens + " chunks=" + st.chunks);
        if (st.errorDetail != null && st.errorDetail.length() > 0) {
            d.body("error-detail", st.errorDetail, 4000);
        }
        d.end(code, st.httpCode, total);
        d.flush();
    }

    private static String header(HttpURLConnection conn, String name) {
        try {
            String v = conn.getHeaderField(name);
            return v == null ? "-" : v;
        } catch (Throwable t) {
            return "-";
        }
    }

    /** 异常 → 一行完整原因（含 cause 链），用于日志与诊断记录。 */
    private static String describe(Exception e) {
        if (e == null) return "-";
        StringBuilder sb = new StringBuilder(e.getClass().getName());
        if (e.getMessage() != null) sb.append(": ").append(e.getMessage());
        Throwable c = e.getCause();
        int depth = 0;
        while (c != null && depth < 3) {
            sb.append(" <- ").append(c.getClass().getName());
            if (c.getMessage() != null) sb.append(": ").append(c.getMessage());
            c = c.getCause();
            depth++;
        }
        return sb.toString();
    }

    /** 异常 → 一句用户能看懂的原因，用于重连耗尽后的提示。 */
    private static String friendly(Exception e) {
        if (e instanceof SocketTimeoutException) return Strings.get(R.string.chatstreamrunner_026);
        String n = e.getClass().getName().toLowerCase(java.util.Locale.US);
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase(java.util.Locale.US);
        if (n.indexOf("ssl") >= 0 || n.indexOf("cert") >= 0 || msg.indexOf("ssl") >= 0) {
            return Strings.get(R.string.chatstreamrunner_027);
        }
        if (n.indexOf("unknownhost") >= 0) return Strings.get(R.string.chatstreamrunner_028);
        return Strings.get(R.string.chatstreamrunner_029);
    }

    /** 工具执行：把运行期策略（能力是否就绪、key 从哪来）与 {@link ToolExecutor} 的通用逻辑隔开。 */
    private final ToolExecutor.Host toolHost = new ToolExecutor.Host() {
        public boolean isAlive() {
            return session.isAlive(job.generation);
        }

        public String execute(String name, JSONObject args, String question) {
            // 联网关掉与「没配密钥」是两件事：前者由工具自己回「联网搜索已关闭」，
            // 后者才走 unavailableResult 提示去填密钥。顺序不能反 —— 关掉联网时
            // 往往也没配密钥，先判 searchReady 会把原因说成「未配置 Key」。
            if (!job.webSearchEnabled) {
                return WebSearchTool.webOffResult();
            }
            if (!job.searchReady) return null;
            return WebSearchTool.executeTool(job.searchProvider, name, args, question,
                    guard, job.useTavily, job.tavilyKey, true);
        }

        public String unavailableResult() {
            return job.useTavily
                    ? Strings.get(R.string.chatstreamrunner_012)
                    : Strings.get(R.string.chatstreamrunner_013);
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
            // 联网本来就是关的：提示说的是开关，而不是让用户去填一个与当前操作无关的密钥
            if (!job.webSearchEnabled) {
                ui.toast(Strings.get(R.string.chatstreamrunner_018));
                return;
            }
            ui.toast(job.useTavily
                    ? Strings.get(R.string.chatstreamrunner_014)
                    : Strings.get(R.string.chatstreamrunner_015));
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
        /** 命中前缀缓存的 token 数；两家厂商字段名不同，见 {@link UsageView#cacheHitOf}。 */
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
        /** 读流是否已经收到第一段字节；停滞看门狗据此让位。 */
        volatile boolean firstByteSeen;

        /**
         * 累加一轮的 usage。工具轮次会发多次请求，逐轮相加才是这条消息的真实消耗；
         * 只取最后一轮会漏掉前面所有工具轮。
         */
        void addUsage(JSONObject u) {
            if (u == null) return;
            int[] p = {promptTokens};
            int[] c = {completionTokens};
            int[] t = {totalTokens};
            int[] hit = {cachedTokens};
            // 字段名统一在 UsageView 里认：OpenAI 的 prompt/completion 与
            // Anthropic 的 input/output 两套写法都要算，否则这类端点用量恒为 0
            UsageView.of(u).addTo(p, c, t, hit);
            promptTokens = p[0];
            completionTokens = c[0];
            totalTokens = t[0];
            cachedTokens = hit[0];
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

    /**
     * 统计口径的显示名：模型 ID → 显示名，提供商标识 → 显示名。
     *
     * <p>查不到就原样返回。统计是旁路，不能因为配置里少了条目就抛异常。
     */
    private static String modelLabelOf(String id) {
        if (id == null || id.length() == 0) return "";
        try {
            return ConfigManager.getInstance().modelLabel(id);
        } catch (Throwable t) {
            return id;
        }
    }

    private static String providerLabelOf(String id) {
        if (id == null || id.length() == 0) return "";
        try {
            return ConfigManager.getInstance().providerLabel(id);
        } catch (Throwable t) {
            return id;
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
