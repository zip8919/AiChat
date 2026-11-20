package xyz.zip8919.app.aichat;

/**
 * 流式正文的逐字渐显：把「已渲染好的整块 HTML」一格一格放出来。
 *
 * <p><b>为什么需要单独一个类</b>：流式 HTML 每一帧都是整段重排好的片段，直接
 * 写进 DOM 就是「一大块字瞬间出现」。要让读者看到的是一个字一个字地落下来，
 * 就得在「已到达」和「已显示」之间放一层缓冲，按固定的节奏往前推。
 * 这层状态一旦写进 UI 代码，动画、停止、跳过三件事会互相踩：
 * 停止要能立刻停在当前位置，跳过要能一次放完，收尾要保证一个字都不少。
 * 所以它做成不碰 View、不碰线程的纯对象，由宿主按节奏调用 {@link #step()}。
 *
 * <p><b>怎么切</b>：只认 HTML 标签之外、空白之前的字符作为可显示单位。
 * 公式是 {@code <img>}、代码高亮是 {@code <span>}，按字符切会让半个标签露出来，
 * 浏览器会把后面整段当成纯文本显示，屏幕上就是一串尖括号。标签整体作为一个单位，
 * 一次放完，视觉上等同于它前面的那个字一起出现。
 *
 * <p><b>为什么不丢内容</b>：{@link #pendingHtml()} 永远是「已到达」的全量片段，
 * 宿主每帧都把它整段写进缓冲层的位置。渐显只控制{@code 显示多少}，
 * 不控制{@code 拿到多少}——动画跟不上、被跳过、被停止，都不会让正文少一段。
 */
final class SmoothTail {

    /** 一次放出的单位数：每档 1 个字，逐字感最细。 */
    static final int CHARS_PER_STEP = 1;

    /**
     * 每秒放出的单位数。
     *
     * <p>取值偏快：低端机上一帧的排版开销本身就有一二十毫秒，
     * 再慢就是「看着卡」而不是「看着流畅」。每档 1 个字、每秒 90 档，
     * 缓冲追不上时由 {@link #step()} 自动加档追平，不会越积越多。
     */
    static final int STEPS_PER_SECOND = 90;

    private String arrived = "";
    private int[] unitEnd;
    /** 每个单位边界处累计的可见字符数（标签不计）：跨帧重扫时按它找回显示进度。 */
    private int[] unitTextEnd;
    private int unitCount;
    private int shown;

    /** 宿主关掉渐显或收尾时置位：之后 {@link #step()} 一次放完。 */
    private boolean finished;

    /**
     * 记下这一帧到达的完整片段。
     *
     * @return 显示进度是否有变化；false 表示文本没长，宿主不必重画
     */
    boolean onHtml(String html) {
        String next = html == null ? "" : html;
        if (next.equals(arrived)) return false;
        // 只增不退：模型只追加，片段变短只可能是换了新一轮，由 beginRound 处理
        arrived = next;
        scan();
        return true;
    }

    /** 新一轮正文开始：清空缓冲，从零开始渐显。 */
    void beginRound() {
        arrived = "";
        unitCount = 0;
        shown = 0;
        finished = false;
    }

    /** 本轮结束或用户要求直接显示：剩下的格子一次性放完。 */
    void finish() {
        finished = true;
        shown = unitCount;
    }

    boolean finished() {
        return finished;
    }

    /** 还有没放出来的内容。 */
    boolean hasPending() {
        return shown < unitCount;
    }

    /**
     * 往前推一格。
     *
     * @return 是否放出了新内容；false 时宿主不必重画
     */
    boolean step() {
        if (finished) {
            if (shown >= unitCount) return false;
            shown = unitCount;
            return true;
        }
        if (shown >= unitCount) return false;
        // 落后太多时按积压比例加档追赶：慢机型一拍本身就要几十毫秒，
        // 固定小步长会让缓冲越积越多、收尾一次性甩出；追平后回落到
        // 每档 1 个字，逐字感就在这里
        int backlog = unitCount - shown;
        int inc = CHARS_PER_STEP;
        if (backlog > 24) inc = Math.min(16, backlog / 8);
        else if (backlog > 8) inc = 2;
        shown = Math.min(unitCount, shown + inc);
        return true;
    }

    /** 已到达的完整片段：宿主把它写进缓冲层，文字与公式都按最终排版排好。 */
    String pendingHtml() {
        return arrived;
    }

    /** 当前应当显示的前缀（按单位边界切好的 HTML）。 */
    String shownHtml() {
        if (unitCount == 0) return "";
        int end = shown <= 0 ? 0 : unitEnd[Math.min(shown, unitCount) - 1];
        return arrived.substring(0, end);
    }

    int unitCount() {
        return unitCount;
    }

    int shownUnits() {
        return shown;
    }

    /**
     * 把已到达的片段切成单位。
     *
     * <p>规则只有两条：
     * <ul>
     *   <li>{@code <} 开始到匹配的 {@code >} 是一段整体，不与相邻字符拆开；</li>
     *   <li>空白字符（空格、换行）附着在前一个单位上，不单独成格，否则渐显时
     *       会先出现一排空格，看起来像排版塌了。</li>
     * </ul>
     */
    private void scan() {
        int len = arrived.length();
        // 重扫前先把「已经显示到哪」换算成可见字符数：HTML 结构会随
        // markdown 补全而重排（**bo → <strong>bo</strong>），单位下标与
        // 字符偏移都不跨帧稳定，只有「已经显示了多少个字」是稳定刻度。
        int shownText = (shown > 0 && unitTextEnd != null && shown <= unitCount)
                ? unitTextEnd[shown - 1] : 0;
        int[] ends = new int[Math.max(8, len / 2)];
        int[] texts = new int[Math.max(8, len / 2)];
        int n = 0;
        int textCount = 0;
        int i = 0;
        while (i < len) {
            char c = arrived.charAt(i);
            if (c == '<') {
                int close = arrived.indexOf('>', i + 1);
                int end = close < 0 ? len : close + 1;
                if (n == 0) {
                    // 开头就是标签：单独占一格，否则它永远没有可附着的字
                    ends = grow(ends, n);
                    texts = grow(texts, n);
                    ends[n] = end;
                    texts[n] = textCount;
                    n++;
                } else {
                    // 标签并进前一个单位的末尾：渲染时与前面的文字同时出现
                    ends[n - 1] = end;
                }
                i = end;
                continue;
            }
            if (Character.isWhitespace(c) && n > 0) {
                // 空白跟着前一个单位走；连续空白在 Java 侧已经被合并成一帧，
                // 这里只把前缀延长到最后一个空白处
                ends[n - 1] = i + 1;
                textCount++;
                texts[n - 1] = textCount;
                i++;
                continue;
            }
            if (Character.isHighSurrogate(c) && i + 1 < len) {
                // emoji 之类：高低代理对必须一起出，拆开就是一个乱码方块
                i++;
            }
            if (n == ends.length) {
                ends = grow(ends, n);
                texts = grow(texts, n);
            }
            textCount++;
            ends[n] = i + 1;
            texts[n] = textCount;
            n++;
            i++;
        }
        unitEnd = ends;
        unitTextEnd = texts;
        unitCount = n;
        // 找回进度：取第一个「累计可见字符数 ≥ 之前显示量」的单位。
        // 宁可多露一个字（只往出的方向走），也不把已经显示的字缩回去——
        // 缩回去的那一帧就是肉眼可见的「闪一下、字变少了」。
        if (shownText <= 0) {
            shown = 0;
        } else {
            int k = 0;
            while (k < n && texts[k] < shownText) k++;
            shown = Math.min(n, k + 1);
        }
    }

    private static int[] grow(int[] a, int n) {
        if (n < a.length) return a;
        int[] b = new int[a.length * 2];
        System.arraycopy(a, 0, b, 0, a.length);
        return b;
    }
}
