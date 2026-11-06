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

    /** 实时渲染（转 HTML）的节流间隔；纯文本直出更快，用户能明显感到「在打字」。 */
    private static final long INTERVAL_HTML_MS = 200;
    private static final long INTERVAL_TEXT_MS = 120;

    /** 宿主能力：全部是动作，不是 getter，便于在测试里用假实现替换。 */
    interface Ui {
        void runOnUi(Runnable task);

        /** 执行 {@code fn('...')}，html 由实现负责转义。 */
        void evalJsHtml(String fn, String html);

        /** 执行一段无参脚本，用于 {@code streamCommitTail()} 这类纯 DOM 操作。 */
        void evalJs(String code);

        /** 通知宿主：base 层当前已对应这段文本，避免下次又整段重渲染一遍。 */
        void noteBaseRendered(String base);

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

        /** markdown → html，实现里可以放到后台线程调用。 */
        String renderHtml(String markdown);

        void setContent(int aiIndex, String content);
    }

    private final Ui ui;
    private final int aiIndex;
    private final int generation;
    private final boolean realtime;

    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private volatile long lastEmitMs;
    /** commitTail 已经发出但 UI 还没落地：这段时间内的分片直接跳过，避免写进旧的 tail。 */
    private volatile boolean committing;

    StreamRenderer(Ui ui, int aiIndex, int generation, boolean realtime) {
        this.ui = ui;
        this.aiIndex = aiIndex;
        this.generation = generation;
        this.realtime = realtime;
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
    void commitTail(String baseNow) {
        if (baseNow == null) baseNow = "";
        committing = true;
        ui.nextSeq();              // 作废正在飞的 tick，避免它回头覆盖搬好的 base
        ui.noteBaseRendered(baseNow);
        ui.runOnUi(new Runnable() {
            public void run() {
                try {
                    if (ui.isCurrent(generation)) ui.evalJs("streamCommitTail()");
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
        long interval = realtime ? INTERVAL_HTML_MS : INTERVAL_TEXT_MS;
        if (now - lastEmitMs < interval || inFlight.get()) return;
        lastEmitMs = now;
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
        new Thread(new Runnable() {
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
                    return;
                }
                final String baseHtml = bh;
                final String tailHtml = th;
                ui.runOnUi(new Runnable() {
                    public void run() {
                        // 先复位再判序号：过期分支若直接 return，这个标志会永远为 true，
                        // 后续所有 tick 都被「有渲染在飞」挡掉，界面从此不再更新。
                        inFlight.set(false);
                        if (ui.currentSeq() != mySeq) return;
                        if (!ui.isCurrent(generation)) return;
                        ui.setContent(aiIndex, baseNow + roundNow);
                        // 二次校验必须放在 UI 线程：后台渲染期间 renderTraceAsync 可能
                        // 已经用 streamAppendBase 把这一段推上去了。此时再整段替换
                        // 就是拿旧内容盖新内容——卡片会消失，然后下一轮又冒出来。
                        if (baseHtml != null && !baseNow.equals(ui.baseRendered())) {
                            ui.evalJsHtml("streamBase", baseHtml);
                            ui.noteBaseRendered(baseNow);
                        }
                        ui.evalJsHtml("streamTailHtml", tailHtml);
                    }
                });
            }
        }).start();
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
                    ui.evalJsHtml("streamTailText", tailPlain);
                }
            });
            return;
        }
        // trace 刚追加了工具卡片：重建一次 base，再续上本轮文字
        inFlight.set(true);
        new Thread(new Runnable() {
            public void run() {
                final String baseHtml;
                try {
                    baseHtml = ui.renderHtml(baseNow);
                } catch (Throwable t) {
                    // 同上：渲染失败必须复位 inFlight，否则后续分片全被挡掉
                    LogUtil.e("StreamRenderer", "plain base render failed: " + t.getMessage());
                    inFlight.set(false);
                    return;
                }
                ui.runOnUi(new Runnable() {
                    public void run() {
                        inFlight.set(false);
                        if (ui.currentSeq() != mySeq) return;
                        if (!ui.isCurrent(generation)) return;
                        ui.setContent(aiIndex, baseNow + roundNow);
                        if (!baseNow.equals(ui.baseRendered())) {
                            ui.evalJsHtml("streamBase", baseHtml);
                            ui.noteBaseRendered(baseNow);
                        }
                        ui.evalJsHtml("streamTailText", tailPlain);
                    }
                });
            }
        }).start();
    }
}
