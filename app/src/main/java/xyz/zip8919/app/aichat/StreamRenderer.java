package xyz.zip8919.app.aichat;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 流式增量渲染器：把「服务端每来一片就刷一次 DOM」的节流逻辑独立出来。
 *
 * <p>为什么单独一个类：这段逻辑原先嵌在请求线程的匿名 {@code Progress} 回调里，
 * 三层 Runnable 嵌套 + 四个行内可变变量（lastEmit / inFlight / baseRendered / renderSeq）。
 * 想确认「为什么工具卡片之后正文不重绘」，必须同时读请求线程、渲染线程、UI 线程三处代码。
 * 现在它是普通对象，状态都在字段上，可以按一次 tick 单独调试。
 *
 * <p>两个关键约定，改动时不要破坏：
 * <ul>
 *   <li><b>base 与 tail 分开</b>：base 是已经完成的思考块与工具卡片（{@code trace}），
 *       tail 是本轮正在流出的正文。base 只在内容变化时整段重渲染，
 *       否则每个分片都会把上面那一大段卡片打回纯文本。</li>
 *   <li><b>同一时刻只有一个渲染在飞</b>：markdown 渲染很重，允许并发会让 UI 线程
 *       被一串过期的 html 堵住。后到的 tick 直接丢弃，不排队。</li>
 * </ul>
 */
final class StreamRenderer {

    private static final String TAG = "StreamRenderer";

    /** 实时渲染（转 HTML）的节流间隔；纯文本直出更快，用户能明显感到「在打字」。 */
    private static final long INTERVAL_HTML_MS = 200;
    private static final long INTERVAL_TEXT_MS = 120;

    /**
     * 渲染线程池。
     *
     * <p>原来每个 tick 都 {@code new Thread(...).start()}：一条长回答按 200ms 一帧算，
     * 5 分钟的输出就是 1500 个线程的创建/销毁，单次创建在 Cortex-A7 上约 0.1~0.3ms，
     * 加上线程栈的 mmap/munmap，整条回答要为此白烧几百毫秒 CPU，还制造大量内存抖动。
     *
     * <p>用 2 个线程而不是 1 个：{@code flushAndWait} 需要在 tick 还在飞的时候插进来
     * （它带 2500ms 超时，排在长渲染后面就可能超时，退回缺字的旧行为）。
     * 2 个线程既覆盖了这个并发场景，又把线程创建次数从「每 tick 一次」降到「进程一次」。
     */
    private static final java.util.concurrent.ExecutorService RENDER_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(2,
                    new java.util.concurrent.ThreadFactory() {
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "aichat-render");
                            t.setPriority(Thread.NORM_PRIORITY - 1);
                            t.setDaemon(true);
                            return t;
                        }
                    });

    /** 宿主能力：全部是动作，不是 getter，便于在测试里用假实现替换。 */
    interface Ui {
        void runOnUi(Runnable task);

        /** 执行 {@code fn('...')}，html 由实现负责转义。 */
        void evalJsHtml(String fn, String html);

        /**
         * 同上，额外把消息下标传进去：页面据此找到<b>自己那条</b>气泡，
         * 而不是「最后一个 .msg.ai」。换版/重画交错时后者会写错地方。
         */
        void evalJsHtml(String fn, String html, int idx);

        /** 执行一段无参脚本，用于 {@code streamCommitTail()} 这类纯 DOM 操作。 */
        void evalJs(String code);

        /** 通知宿主：base 层当前已对应这段文本，避免下次又整段重渲染一遍。 */
        void noteBaseRendered(String base);

        /**
         * 同上，并声明「这段 base 的渲染结果就是刚才下发的那一份 HTML」。
         *
         * <p>为什么要多这两个参数：{@code streamBase} 只在页面的闸门已释放、
         * 气泡已经在 DOM 里时才真正写入，并且写入时会把 tail 那一段正文/卡片一
         * 并搬进 base 再整体替换。宿主必须能分辨「我下发了」与「页面画上去了」，
         * 否则后续的增量追加会以一段屏幕上并不存在的 base 为前提——
         * 工具卡片就是这么「生成中消失」的。宿主据 (gen, seq) 决定是否等页面回执。
         */
        void noteBaseRendered(String base, String html, int seq);

        /**
         * base 层当前对应的文本。读写都走宿主，是为了让流式 tick 与
         * {@code renderTraceAsync}（工具卡片/状态行走的另一条渲染路径）共享同一份
         * 状态——两边各自记一份的话，一方追加、另一方整段替换，卡片就会重复或消失。
         */
        String baseRendered();

        /** 请求代数是否仍是当前这一条，false 表示已被新请求或打断取代。 */
        boolean isCurrent(int generation);

        /** 取一个新的渲染序号；旧序号回来的渲染作废。 */
        int nextSeq();

        int currentSeq();

        /**
         * 实时渲染的一帧正文已经产出（整段 HTML）。
         *
         * <p>默认实现直接写进可见的 tail；逐字渐显时由实现改成写进缓冲，
         * 由宿主按节奏放出来。渲染器本身不关心「显示了多少」，
         * 它只保证「这一段正文已经到达」。
         */
        void renderTailHtml(String baseNow, String html);

        /**
         * 非实时渲染的一帧正文（纯文本）已经产出。
         *
         * <p>与 {@link #renderTailHtml} 并列存在，而不是「纯文本也转成 HTML 再走上面」：
         * 关掉实时渲染就是为了不解析 markdown，逐字渐显时若为了统一路径又把每帧
         * 转成 HTML，等于把省下的开销又加回来。纯文本的渐显只是在一个文本节点上
         * 挪切点，比 DOM 重建便宜得多。
         */
        void renderTailText(String baseNow, String plain);

        /** 本轮正文已经全部到达：剩余内容可以一次放完。 */
        void smoothFinished(int generation);

        /** 请求被停止：停在当前显示位置，不把剩余内容甩出来。 */
        void smoothInterrupted(int generation);

        /** 用户点了「直接显示」。 */
        void smoothReveal(int generation);

        /** markdown → html，实现里可以放到后台线程调用。 */
        String renderHtml(String markdown);

        void setContent(int aiIndex, String content);
    }

    private final Ui ui;
    private final int aiIndex;
    private final int generation;
    private final boolean realtime;

    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    /** 渲染池里还没跑完的任务数（含正在跑的）。 */
    private final java.util.concurrent.atomic.AtomicInteger pending =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private volatile long lastEmitMs;
    /** commitTail 已经发出但 UI 还没落地：这段时间内的分片直接跳过，避免写进旧的 tail。 */
    private volatile boolean committing;
    /** flushAndWait 迟到落地帧的作废代数，见 commitTail。 */
    private volatile int commitEpoch = 0;
    /** 最近一次投递出去的正文片段；收尾时要把它交给渐显放完，而不是直接甩出去。 */
    private String lastTailHtml = "";

    StreamRenderer(Ui ui, int aiIndex, int generation, boolean realtime) {
        this.ui = ui;
        this.aiIndex = aiIndex;
        this.generation = generation;
        this.realtime = realtime;
        this.guard = new FrameGuard(realtime ? INTERVAL_HTML_MS : INTERVAL_TEXT_MS);
    }

    /** 帧速率统计，请求结束时写一行日志，便于对着「越写越慢」的反馈查是哪一段开始掉速。 */
    String frameSummary() {
        return guard.summary();
    }

    /**
     * 新一轮开始：清掉节流时间戳，让第一片立刻显示。
     * base 与 inFlight 不清——它们描述的是已经推到 DOM 的状态，跨轮次仍然有效。
     */
    void beginRound() {
        lastEmitMs = 0;
    }

    /**
     * 本轮结束（或要插入工具卡片）：把已经流出去的 tail 并进 base。
     *
     * <p>为什么必须做这一步：下一轮的 {@code baseNow} 是「旧 trace + 本轮正文」，
     * 而 DOM 里那段正文还在 tail 上。不搬过去，下一轮要么把 base 整段重渲染一遍
     * （长回答在低端机上是肉眼可见的一次闪烁），要么 tail 被新的分片覆盖（丢内容）。
     *
     * <p>搬移是纯 DOM 操作，不解析 markdown，所以没有重排代价。
     */
    /**
     * 强制把最后一帧推下去，并等它真正落到 DOM（最多 {@code timeoutMs}）。
     *
     * <p>为什么需要：每一帧都受 {@value #INTERVAL_HTML_MS}/{@value #INTERVAL_TEXT_MS}
     * 节流，而分片通常 20~50ms 一片——本轮结束时最后一个分片几乎必然还在节流窗口里。
     * 紧接着的 {@link #commitTail} 会把 DOM 现有的 tail 搬进 base、并把 baseRendered
     * 登记成「完整文本」，于是这一截正文永久缺失：屏幕上句子断在工具卡片处，
     * 而落盘的 content 是完整的，切走再回来又对得上。
     *
     * <p>调用方在请求线程上，可以安全阻塞；渲染仍在后台线程做，UI 线程只负责写入。
     * 超时只是退回原来的行为（可能缺十几字），不会卡住 UI。
     */
    void flushAndWait(final String baseNow, final String roundNow, long timeoutMs) {
        final String base = baseNow == null ? "" : baseNow;
        final String round = roundNow == null ? "" : roundNow;
        if (committing) return;
        final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(1);
        final String[] beforeHolder = new String[] { base };
        // flush 超时只返回、提交的任务并不取消。它若迟于 commitTail 落地，会
        // nextSeq() 作废新一轮在飞帧、把旧 base 和旧 tail 各写回一帧（末尾
        // 正文重复、DOM 与落盘 content 不符）。捕获代数以便届时丢弃。
        final int myEpoch = commitEpoch;
        // 投递时的 base 账：UI 线程上落地时用它判断「这期间有没有人改过 base」，
        // 改了就不许再用这次的结果（那时真正决定显示的是别人）
        beforeHolder[0] = ui.baseRendered();
        RENDER_POOL.execute(new Runnable() {
            public void run() {
                String bh = null;
                String th = null;
                final String before = beforeHolder[0];
                try {
                    if (!base.equals(before)) bh = ui.renderHtml(base);
                    th = ui.renderHtml(round);
                } catch (Throwable t) {
                    LogUtil.e(TAG, "flush render failed: " + t.getMessage());
                }
                final String baseHtml = bh;
                final String tailHtml = th;
                ui.runOnUi(new Runnable() {
                    public void run() {
                        try {
                            // 代数在同一请求内不变，拦不住迟到帧；再比 commitEpoch，
                            // commitTail 已执行就整帧丢弃（finally 仍 countDown）。
                            if (!ui.isCurrent(generation) || commitEpoch != myEpoch) return;
                            // 这一次落地作废所有在飞的 tick：它们带的是更旧的文本
                            ui.nextSeq();
                            inFlight.set(false);
                            ui.setContent(aiIndex, base + round);
                            if (baseHtml != null) {
                                ui.evalJsHtml("streamBase", baseHtml, aiIndex);
                                // 重载：把这个 HTML 与序号一并交给宿主记账，
                                // 宿主据此判断页面是否真的画上了（见 Ui 接口）
                                ui.noteBaseRendered(base, baseHtml, ui.currentSeq());
                            }
                            // 渐显时同样只交给缓冲：收尾的完整渲染紧接着就把这条
                            // 气泡整段重画，这里甩出去只会多闪一下
                            if (tailHtml != null) {
                                lastTailHtml = tailHtml;
                                ui.renderTailHtml(base, tailHtml);
                            }
                        } catch (Throwable t) {
                            LogUtil.e(TAG, "flush apply failed: " + t.getMessage());
                        } finally {
                            latch.countDown();
                        }
                    }
                });
            }
        });
        try {
            latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    void commitTail(String baseNow) {
        if (baseNow == null) baseNow = "";
        // 这一轮正文已经全部到达，只剩还没浮出来的部分：让宿主一次放完。
        // 必须在搬 DOM 之前，否则搬到 base 里的只是已经显示出来的那一截。
        ui.smoothFinished(generation);
        commitEpoch++;          // 作废旧 flush 的迟到落地帧
        committing = true;
        ui.nextSeq();              // 作废正在飞的 tick，避免它回头覆盖搬好的 base
        // 搬完之后 DOM 的结构是「旧 base + 本轮 tail 的节点」，并不等于 trace：
        // trace 里本轮正文是原文，搬过去的是渲染好的节点，而工具卡片此时还没写进
        // DOM（随后由 renderTraceAsync 追加）。所以这里<b>不能</b>把 baseRendered
        // 登记成 baseNow —— 登记了就等于告诉增量渲染「卡片已经在屏幕上了」，
        // 而它并不在。旧代码在这里记账，正是「工具卡片在生成过程中消失」的直接来源：
        // 后续每一帧都只追加增量，屏幕上却缺了前面那一整段，直到收尾整段重建才回来。
        // 现在只保证「不会重画同一份 base」（与调用前的记账一致），真正的推进交给
        // 页面回执（renderTraceAsync → onRenderLanded）与收尾的整段重建。
        ui.runOnUi(new Runnable() {
            public void run() {
                try {
                    // 带上气泡下标：页面在这期间被整页重建过时，只按「最后一个 AI」
                    // 找会把这一轮的 tail 搬进上一条回答里
                    if (ui.isCurrent(generation)) ui.evalJs("streamCommitTail(" + aiIndex + ")");
                } finally {
                    committing = false;
                }
            }
        });
        lastEmitMs = 0;
    }

    /**
     * 收到一个流式分片。
     *
     * @param baseNow  当前累计的思考/工具卡片文本（{@code trace}）
     * @param roundNow 本轮到目前为止的显示文本
     */
    void onProgress(String baseNow, String roundNow) {
        if (baseNow == null) baseNow = "";
        if (roundNow == null) roundNow = "";
        if (committing) return;
        long now = System.currentTimeMillis();
        // 间隔由 FrameGuard 按实际每帧耗时动态调整，见 FrameGuard 的说明
        long interval = guard.gap();
        if (now - lastEmitMs < interval || inFlight.get()) return;
        lastEmitMs = now;
        guard.dispatched(now);
        if (realtime) {
            renderHtml(baseNow, roundNow);
        } else {
            renderPlain(baseNow, roundNow);
        }
    }

    private void renderHtml(final String baseNow, final String roundNow) {
        inFlight.set(true);
        final boolean baseChanged = !baseNow.equals(ui.baseRendered());
        final int mySeq = ui.nextSeq();
        final int[] queuedRef = new int[1];
        submit(new Runnable() {
            public void run() {
                String bh = null;
                String th = null;
                try {
                    // 渲染放在这里做：markdown + 公式可能几十毫秒，不能占 UI 线程
                    bh = baseChanged ? ui.renderHtml(baseNow) : null;
                    th = ui.renderHtml(roundNow);
                } catch (Throwable t) {
                    // 渲染层异常（畸形公式、超长嵌套）不能让 inFlight 卡在 true：
                    // 那会让后续所有分片被「有渲染在飞」挡掉，界面从此不再更新。
                    LogUtil.e("StreamRenderer", "render failed, tick skipped: " + t.getMessage());
                }
                if (th == null) {
                    inFlight.set(false);
                    pending.decrementAndGet();
                    return;
                }
                final String baseHtml = bh;
                final String tailHtml = th;
                ui.runOnUi(new Runnable() {
                    public void run() {
                        // 先复位再判序号：过期分支若直接 return，这个标志会永远为 true，
                        // 后续所有 tick 都被「有渲染在飞」挡掉，界面从此不再更新。
                        inFlight.set(false);
                        pending.decrementAndGet();
                        if (ui.currentSeq() != mySeq) return;
                        if (!ui.isCurrent(generation)) return;
                        ui.setContent(aiIndex, baseNow + roundNow);
                        // 二次校验必须放在 UI 线程：后台渲染期间 renderTraceAsync 可能
                        // 已经用 streamAppendBase 把这一段推上去了。此时再整段替换
                        // 就是拿旧内容盖新内容——卡片会消失，然后下一轮又冒出来。
                        if (baseHtml != null && !baseNow.equals(ui.baseRendered())) {
                            ui.evalJsHtml("streamBase", baseHtml, aiIndex);
                            ui.noteBaseRendered(baseNow, baseHtml, ui.currentSeq());
                        }
                        ui.renderTailHtml(baseNow, tailHtml);
                        guard.landed(System.currentTimeMillis(), queuedRef[0]);
                    }
                });
            }
        }, queuedRef);
    }

    /**
     * 投递一帧渲染，把投递时渲染池里的任务数（含刚投的这一帧）写进 {@code queuedRef[0]}。
     *
     * <p>{@code pending} 是 {@code FrameGuard} 判断「产出快于消费」的依据：
     * 只看单帧耗时不够——正文变长后每帧都慢，但真正让界面卡住的是
     * 「一帧还没画完，下一帧已经排在后面」。任务退出时必须减一，
     * 渲染失败、序号过期两条路径都要减，否则计数只增不减，间隔会被永久翻倍。
     *
     * <p>用单元素数组回传而不是返回值：Runnable 里引用的是投递之后的变量，
     * 直接写 {@code int queued = submit(...)} 会被 Java 判成「可能未初始化」。
     */
    private void submit(Runnable task, int[] queuedRef) {
        queuedRef[0] = pending.incrementAndGet();
        RENDER_POOL.execute(task);
    }

    private void renderPlain(final String baseNow, final String roundNow) {
        // 只把本轮增量转成纯文本；整段转会把前面的工具卡片、思考块、markdown 全抹掉
        final String tailPlain = TextKit.plainForStream(roundNow);
        final boolean baseChanged = !baseNow.equals(ui.baseRendered());
        final int mySeq = ui.nextSeq();
        if (!baseChanged) {
            ui.runOnUi(new Runnable() {
                public void run() {
                    // 序号守卫：renderTraceAsync 刚把工具卡片渲染上去时，这个 tick
                    // 的 baseNow 还是旧的，写入会把卡片和正文一起打回纯文本
                    if (ui.currentSeq() != mySeq) return;
                    if (!ui.isCurrent(generation)) return;
                    ui.setContent(aiIndex, baseNow + roundNow);
                    ui.renderTailText(baseNow, tailPlain);
                    guard.landed(System.currentTimeMillis(), 0);
                }
            });
            return;
        }
        // trace 刚追加了工具卡片：重建一次 base，再续上本轮文字
        inFlight.set(true);
        final int[] queuedRef = new int[1];
        submit(new Runnable() {
            public void run() {
                final String baseHtml;
                try {
                    baseHtml = ui.renderHtml(baseNow);
                } catch (Throwable t) {
                    // 同上：渲染失败必须复位 inFlight，否则后续分片全被挡掉
                    LogUtil.e("StreamRenderer", "plain base render failed: " + t.getMessage());
                    inFlight.set(false);
                    pending.decrementAndGet();
                    return;
                }
                ui.runOnUi(new Runnable() {
                    public void run() {
                        inFlight.set(false);
                        pending.decrementAndGet();
                        if (ui.currentSeq() != mySeq) return;
                        if (!ui.isCurrent(generation)) return;
                        ui.setContent(aiIndex, baseNow + roundNow);
                        if (!baseNow.equals(ui.baseRendered())) {
                            ui.evalJsHtml("streamBase", baseHtml, aiIndex);
                            ui.noteBaseRendered(baseNow, baseHtml, ui.currentSeq());
                        }
                        ui.renderTailText(baseNow, tailPlain);
                        guard.landed(System.currentTimeMillis(), queuedRef[0]);
                    }
                });
            }
        }, queuedRef);
    }

    // ---- 帧速率守门 ----

    /** 单帧耗时超过「间隔的一半」就认为渲染跟不上，下一帧拉开距离。 */
    private static final long ADAPT_SLOW_RATIO = 2;

    /** 一帧落地后的总耗时（渲染线程解析 + UI 线程写入）超过它，视为本机扛不住当前频率。 */
    private static final long ADAPT_SAMPLE_MS = 40;

    /** 连续多少帧跟不上才降速；单帧抖动（公式解码、GC）不调整。 */
    private static final int ADAPT_SLOW_STREAK = 3;

    /**
     * 按机器与内容自动拉开流式帧间距。
     *
     * <p><b>为什么需要</b>：固定 200/120ms 的假设是「每帧几十毫秒」，正文越长越不成立。
     * 一帧要把本轮全部正文重新解析成 HTML，正文到两三万字时单帧就要几百毫秒，
     * 而分片仍在源源不断地进来——渲染线程的队列越压越长，用户看到的就是
     * 「开头流畅，越往后每个字都要等半天」，最后整条回答卡住不动。
     *
     * <p>做法：记下最近一帧从发起到落地花了多久。连续几帧都明显慢于当前间隔，
     * 就把间隔翻倍（至少 1200ms 为止）；连续几帧都很轻快，再逐步收回。
     * 降速后每帧处理的内容更少、间隔更长，屏幕刷新变慢但不再堆积，
     * 收尾仍由 {@code flushAndWait} 补最后一帧，不会丢内容。
     *
     * <p>每个 {@link StreamRenderer} 实例对应一次请求，状态不跨请求累积。
     */
    static final class FrameGuard {
        private final long baseMs;
        private final long maxMs;
        private long gapMs;
        private long dispatchAt;
        private int slowStreak;
        private int fastStreak;
        private final long[] stats = new long[4];

        FrameGuard(long baseMs) {
            this.baseMs = baseMs;
            this.maxMs = Math.max(baseMs * 6, 1200L);
            this.gapMs = baseMs;
        }

        /** 当前帧间距。 */
        synchronized long gap() {
            return gapMs;
        }

        /** 记下发帧时刻；只有真正发出去的帧才计时，被挡掉的帧不计。 */
        synchronized void dispatched(long now) {
            dispatchAt = now;
        }

        /**
         * 在 UI 线程写入完成后调用。
         *
         * @param now    落地时刻
         * @param queued 发出这一帧时渲染池里还有多少任务在排队（含自己）
         */
        void landed(long now, int queued) {
            long cost;
            long gap;
            synchronized (this) {
                if (dispatchAt <= 0) return;
                cost = now - dispatchAt;
                dispatchAt = 0;
                gap = gapMs;
                stats[0] = cost;
                if (cost > stats[1]) stats[1] = cost;
                stats[2] += cost;
                stats[3]++;
            }
            boolean slow = cost >= ADAPT_SAMPLE_MS && cost * ADAPT_SLOW_RATIO >= gap;
            // 排队两层以上说明上游产出快于消费，即便单帧看着不慢也要拉开
            boolean backed = queued > 1;
            synchronized (this) {
                if (slow || backed) {
                    fastStreak = 0;
                    slowStreak++;
                    if (slowStreak >= (backed ? 1 : ADAPT_SLOW_STREAK)) {
                        long next = Math.min(maxMs, gapMs * 2);
                        if (next != gapMs) {
                            LogUtil.i(TAG, "stream frame slow: cost=%dms gap=%dms queued=%d -> gap=%dms",
                                    cost, gapMs, queued, next);
                        }
                        gapMs = next;
                        slowStreak = 0;
                    }
                } else {
                    slowStreak = 0;
                    fastStreak++;
                    // 收回要保守：连续 6 帧都轻快才减一档，避免在临界点来回跳
                    if (fastStreak >= 6 && gapMs > baseMs) {
                        gapMs = Math.max(baseMs, gapMs / 2);
                        fastStreak = 0;
                    }
                }
            }
        }

        /** 日志用：最近一帧 / 最慢一帧 / 平均耗时。 */
        String summary() {
            synchronized (this) {
                if (stats[3] == 0) return "frames=0";
                return "frames=" + stats[3] + " last=" + stats[0] + "ms max=" + stats[1]
                        + "ms avg=" + (stats[2] / stats[3]) + "ms gap=" + gapMs + "ms";
            }
        }
    }

    private final FrameGuard guard;
}
