package xyz.zip8919.app.aichat;

/**
 * 流式正文的逐字渐显：把一个 UTF-16 偏移之前的正文放出来。
 *
 * <p><b>为什么是「偏移」而不是「一段 HTML」</b>：第一版让 Java 侧算出「显示到这里」
 * 的 HTML 前缀再推给页面。可流式正文每一帧都是整段重排的 HTML，markdown 补全会让
 * 同一段内容在不同帧里的标签完全不一样（{@code **bo} → {@code <strong>bo</strong>}），
 * 于是前缀经常不再是上一帧前缀的延长——页面只能整段重写，读者看到的就是
 * 「字往回缩一下、再全部冒出来」。现在 Java 只记「已经放出了多少个字」，
 * 由页面自己在同一份 DOM 上按位置切，结构怎么变都不会把字缩回去。
 *
 * <p><b>不丢内容</b>：{@link #arrivedHtml()} 永远是「已到达」的全量片段，
 * 宿主每帧整段写进页面；渐显只控制显示到哪一位，不控制拿到多少。
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
    /** 每个字符（去标签后）在 {@link #arrived} 里的结尾偏移。 */
    private int[] charEnd = new int[16];
    private int charCount;
    /** 已经放出的字符数。 */
    private int shown;

    /** 已到达的正文是不是纯文本（关掉实时渲染时走这条）。 */
    private boolean plain;

    /** 宿主收尾时置位：之后 {@link #step()} 一次放完。 */
    private boolean finished;

    /**
     * 收尾追赶：生成已经结束，剩下的字按「每拍 1/4 积压」快速放完。
     *
     * <p>不是立即放完——立即放完等于没有可跳过的区间，「直接显示」也就没有意义了。
     * 也不是保持原速——几万字的积压按每拍 1 字要放十几分钟。
     */
    private boolean draining;

    /**
     * 记下这一帧到达的完整片段。
     *
     * @return 缓冲内容是否有变化；false 表示文本没长，宿主不必重扫
     */
    boolean onHtml(String html) {
        String next = html == null ? "" : html;
        if (!plain && next.equals(arrived)) return false;
        plain = false;
        arrived = next;
        scan();
        return true;
    }

    /**
     * 记下这一帧到达的纯文本（关掉实时渲染）。
     *
     * <p>不能用 {@link #onHtml}：纯文本里的 {@code <} 是字面量，而 scan 会把它当
     * 标签开头、跳到下一个 {@code >}，偏移算出来是乱的，显示量就会跳字。
     * 纯文本的每个字符都在源串里占一位，偏移直接等于字符下标 +1。
     */
    boolean onText(String text) {
        String next = text == null ? "" : text;
        if (plain && next.equals(arrived)) return false;
        boolean wasPlain = plain;
        plain = true;
        arrived = next;
        scanPlain();
        // 从 HTML 切到纯文本时引擎换了，已显示量按新口径重新夹紧
        if (!wasPlain) shown = Math.min(shown, charCount);
        return true;
    }

    /** 当前是不是纯文本模式。宿主据此选页面脚本（纯文本直接切在文本上）。 */
    boolean isPlain() {
        return plain;
    }

    /** 新一轮正文开始：清空缓冲，从零开始渐显。 */
    void beginRound() {
        arrived = "";
        charCount = 0;
        shown = 0;
        finished = false;
        draining = false;
        plain = false;
    }

    /** 生成本轮结束、显示还在追赶：进入快速放完模式，但保留「还没放完」的区间。 */
    void drain() {
        draining = true;
    }

    /** 本轮结束或用户要求直接显示：剩下的字一次性放完。 */
    void finish() {
        finished = true;
        shown = charCount;
    }

    boolean finished() {
        return finished;
    }

    /** 还有没放出来的内容。 */
    boolean hasPending() {
        return shown < charCount;
    }

    /**
     * 往前推一格。
     *
     * @return 是否放出了新内容；false 时宿主不必重画
     */
    boolean step() {
        if (shown >= charCount) return false;
        if (finished) {
            shown = charCount;
            return true;
        }
        // 落后太多时按积压比例加档追赶：慢机型一拍本身就要几十毫秒，
        // 固定小步长会让缓冲越积越多、收尾一次性甩出；追平后回落到
        // 每档 1 个字，逐字感就在这里
        int backlog = charCount - shown;
        if (draining) {
            // 追赶期：每拍放掉四分之一，长回答也会在一两秒内收口
            shown = Math.min(charCount, shown + Math.max(4, backlog / 4));
            return true;
        }
        int inc = CHARS_PER_STEP;
        if (backlog > 24) inc = Math.min(16, backlog / 8);
        else if (backlog > 8) inc = 2;
        shown = Math.min(charCount, shown + inc);
        return true;
    }

    /** 已到达的完整片段：宿主把它整段写进页面。 */
    String arrivedHtml() {
        return arrived;
    }

    /**
     * 当前应当显示到 {@link #arrivedHtml()} 的第几位。
     *
     * <p>取「已放出最后一个字的结尾偏移」：标签不占位，所以偏移会顺带把紧跟其后的
     * 标签一起带出来（{@code ab<b>} + shown=1 → 偏移停在这个字之后、标签之前），
     * 页面按前缀切时正好保留结构完整的前半段。
     */
    int shownOffset() {
        if (charCount == 0 || shown <= 0) return 0;
        return charEnd[Math.min(shown, charCount) - 1];
    }

    /** 正文已经全部放出（页面可以把占位层整段挂上）。 */
    boolean allShown() {
        return shown >= charCount;
    }

    int charCount() {
        return charCount;
    }

    int shownChars() {
        return shown;
    }

    /**
     * 重扫：记下每个可见字符在源串里的结束偏移。
     *
     * <p>只认标签之外的字符；标签整体跳过，空白照常计数（空白也是一个排版位置）。
     * 代理对按两个字算——页面侧同样按 UTF-16 长度切，两边口径必须一致。
     */
    private void scanPlain() {
        int len = arrived.length();
        if (charEnd.length < len + 1) {
            charEnd = new int[Math.max(16, len + 1)];
        }
        for (int i = 0; i < len; i++) {
            charEnd[i] = i + 1;
        }
        charCount = len;
        if (shown > charCount) shown = charCount;
    }

    private void scan() {        int len = arrived.length();
        if (charEnd.length < len + 1) {
            int[] bigger = new int[Math.max(16, len + 1)];
            System.arraycopy(charEnd, 0, bigger, 0, Math.min(charEnd.length, charCount));
            charEnd = bigger;
        }
        int n = 0;
        int i = 0;
        while (i < len) {
            char c = arrived.charAt(i);
            if (c == '<') {
                int close = arrived.indexOf('>', i + 1);
                i = close < 0 ? len : close + 1;
                continue;
            }
            // 实体（&amp; / &lt; / &#39;）在页面上只占一个字：源串里算 5 位，
            // 页面按字符数切时会被当成 5 个字，切点就跑到前面去了。
            if (c == '&') {
                int semi = arrived.indexOf(';', i + 1);
                if (semi > i + 1 && semi - i <= 8) {
                    i = semi + 1;
                    if (n == charEnd.length) {
                        int[] bigger = new int[charEnd.length * 2];
                        System.arraycopy(charEnd, 0, bigger, 0, charEnd.length);
                        charEnd = bigger;
                    }
                    charEnd[n++] = i;
                    continue;
                }
            }
            i++;
            if (n == charEnd.length) {
                int[] bigger = new int[charEnd.length * 2];
                System.arraycopy(charEnd, 0, bigger, 0, charEnd.length);
                charEnd = bigger;
            }
            charEnd[n++] = i;
        }
        charCount = n;
        if (shown > charCount) shown = charCount;
    }
}
