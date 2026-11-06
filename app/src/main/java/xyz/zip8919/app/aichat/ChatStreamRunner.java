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
         */
        void onRetryWaiting(int attempt, int maxAttempts, long waitMs, int generation, int aiIndex);

        /** 等待结束（已重发或已中止）：收起卡片。 */
        void onRetryWaitEnd();
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
        final long finishedAt;

        Settled(boolean failed, int aiIndex, int generation, int round, int httpCode,
                String errorDetail, String trace, String toolHistory, String apiText, String content,
                int promptTokens, int completionTokens, int totalTokens, long finishedAt) {
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
            this.finishedAt = finishedAt;
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

    /** 阻塞执行，由调用方放进请求线程。 */
    void execute() {
        final long startMs = System.currentTimeMillis();
        final State st = new State(job);
        activeTrace = st.trace;
        HttpURLConnection conn = null;
        try {
            JSONArray conversation = job.isResume()
                    ? job.resume.conversation
                    : ChatHistory.build(job.snapshot, job.aiMessage, job.effectiveSystemPrompt);
            JSONArray toolDefs = WebSearchTool.buildToolDefinitions(
                    job.webSearchEnabled, job.httpToolEnabled);
            boolean toolsEnabled = true;
            boolean fallbackTried = false;
            boolean includeUsage = job.includeUsage;
            boolean usageDowngraded = false;
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
            if (job.isResume() && st.trace.length() > 0) {
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
                        toolsEnabled ? toolDefs : null, includeUsage);
                conn = ChatTransport.open(job.provider);
                session.setConnection(conn);
                writeBody(conn, body.toString());

                long reqMs = System.currentTimeMillis();
                int code = conn.getResponseCode();
                LogUtil.i(TAG, "HTTP %d in %d ms (round=%d)", code, System.currentTimeMillis() - reqMs, round);

                if (code != 200) {
                    // 429 最优先处理：此时 body 还没读，直接关掉重连最干净
                    if (code == 429 && job.retryPolicy.enabled
                            && retryCount < job.retryPolicy.maxAttempts) {
                        int retryAfter = ChatTransport.retryAfterSeconds(conn);
                        long waitMs = job.retryPolicy.delayFor(retryCount, retryAfter);
                        retryCount++;
                        LogUtil.w(TAG, "HTTP 429, retry %d/%d in %d ms (retryAfter=%d)",
                                retryCount, job.retryPolicy.maxAttempts, waitMs, retryAfter);
                        ui.onRetryWaiting(retryCount, job.retryPolicy.maxAttempts, waitMs,
                                job.generation, job.aiIndex);
                        ChatTransport.close(conn);
                        conn = null;
                        session.setConnection(null);
                        boolean alive = sleepInterruptible(waitMs, job.generation);
                        ui.onRetryWaitEnd();
                        if (!alive) {
                            LogUtil.i(TAG, "429 retry wait aborted by user");
                            return;
                        }
                        continue;
                    }

                    String errBody = ChatTransport.readErrorBody(conn);
                    LogUtil.e(TAG, "request failed: HTTP %d, body=%s", code, LogUtil.preview(errBody, 800));

                    // 一级降级：端点不认 stream_options（严格校验参数的网关会直接 400）。
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

                    // 二级降级：部分 OpenAI 兼容端点不认识 tools 参数。首轮 400 时去掉工具与搜索
                    // 系统提示重试一次，保证原有普通对话不受联网开关影响。
                    if ((code == 400 || code == 422) && toolsEnabled && round == 0 && !fallbackTried) {
                        fallbackTried = true;
                        toolsEnabled = false;
                        conversation = ChatHistory.build(job.snapshot, job.aiMessage, job.systemPrompt);
                        ui.toast("当前模型不支持工具调用，已按普通对话发送");
                        ChatTransport.close(conn);
                        conn = null;
                        session.setConnection(null);
                        continue;
                    }

                    st.failed = true;
                    st.httpCode = code;
                    st.errorDetail = ErrorReport.build(job.providerName, job.modelName,
                            job.chatEndpoint, round, code, errBody, null);
                    if (retryCount > 0) {
                        st.errorDetail += "\n\n已自动重试 " + retryCount + " 次仍失败";
                    }
                    LogUtil.e(TAG, "request failed (round %d): %s", round, st.errorDetail);
                    ui.toast("请求失败: HTTP " + code);
                    return;
                }

                renderer.beginRound();
                SseRound.Result rr = readRound(conn, st, renderer);
                st.chunks = rr.chunks;
                st.addUsage(rr.usage);
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
                    st.trace.append(rr.display);
                    if (TraceKit.cap(st.trace)) ui.onTraceTrimmed();
                    st.errorDetail = ErrorReport.build(job.providerName, job.modelName,
                            job.chatEndpoint, round, rr.httpCode, rr.rawBody, rr.cause);
                    ui.toast(rr.error);
                    return;
                }

                ChatTransport.close(conn);
                conn = null;
                session.setConnection(null);

                if (rr.toolCalls.length() > 0 && ToolCalls.hasUsable(rr.toolCalls)) {
                    // 本轮思考/半截回答保留在界面上，不要被下一轮覆盖
                    st.trace.append(rr.display);
                    if (TraceKit.cap(st.trace)) ui.onTraceTrimmed();
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
                    round++;
                    continue;
                }

                st.trace.append(rr.display);
                if (TraceKit.cap(st.trace)) ui.onTraceTrimmed();
                st.apiText = rr.content.toString();
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
            LogUtil.i(TAG, "<<< request end: elapsed=%d ms, chunks=%d, failed=%s, tokens=%d/%d",
                    st.finishedAt - startMs, st.chunks, st.failed,
                    st.promptTokens, st.completionTokens);
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
        return SseRound.read(job.streamEnabled, conn.getInputStream(),
                new SseRound.Progress() {
                    public void onProgress(String display, String content) {
                        renderer.onProgress(st.trace.toString(), display);
                    }
                },
                new SseRound.Abort() {
                    public boolean isAborted() {
                        return !session.isAlive(job.generation);
                    }
                });
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
        /** 收尾时刻，用于算端到端生成速度。 */
        long finishedAt;

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
        }

        State(StreamJob job) {
            this.trace = new StringBuilder(
                    job.isResume() && job.resume.trace != null ? job.resume.trace : "");
            this.sources = job.initialSources();
            this.round = job.isResume() ? job.resume.round : 0;
            if (job.isResume() && job.resume.apiText != null) {
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
                    promptTokens, completionTokens, totalTokens, finishedAt);
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
