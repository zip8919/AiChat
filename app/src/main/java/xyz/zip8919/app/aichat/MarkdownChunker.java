package xyz.zip8919.app.aichat;

/**
 * 把流式正文切成「已定稿的前缀」与「还在增长的尾段」，供增量渲染复用已渲染前缀。
 *
 * <p><b>为什么需要</b>：流式每一帧都要把本轮<b>全部</b>正文重新走一遍
 * markdown 流水线（屏蔽代码 → 屏蔽 SVG → 公式 → 扩展语法 → 解析 → 拼 HTML）。
 * 单帧成本随正文长度线性增长，5000 字时要十几毫秒，20000 字时在 4.4 的低端机上
 * 已经超过一帧的时间预算；而分片还在源源不断地进来，界面就表现为
 * 「开头流畅，越写越卡，最后一段几乎不动」——实时渲染开着时最明显，因为
 * 纯文本通道根本不解析 markdown。
 *
 * <p>实测（同一段正文，只渲染末段 vs 整段重渲染）：8 千字快 8~10 倍，
 * 2 万字快 35 倍，而且尾段长度有上限，所以单帧成本<b>不随正文增长</b>。
 *
 * <p><b>切点必须落在「顶层块边界 + 空行之后」</b>，理由全在正确性上：
 * <ul>
 *   <li>块内的解析状态（围栏代码、列表缩进、引用、表格、HTML 块）一旦被切断，
 *       前半段会被解析成一个「未闭合的块」，后接内容时结构就变了——
 *       典型表现是代码块突然闭合、列表缩进错乱、定稿时整段跳一下；</li>
 *   <li>只认空行（{@code \n\n}）而不认单个换行，是因为单个换行在 markdown 里
 *       属于「软换行」，前后两行同属一个段落，切开就等于插入了一个段落边界；</li>
 *   <li>围栏代码块与 {@code [thinking]} / {@code [tool]} 结构化块内部一律不切：
 *       前者要跨块配对，后者由 {@link MessageHtmlRenderer#contentWithBlocks}
 *       整体解析，被切开会出现「半个折叠块」。</li>
 * </ul>
 *
 * <p>没有可用切点时返回 -1，调用方退回整段渲染——正确性永远优先于速度。
 */
final class MarkdownChunker {

    /**
     * 尾段允许的最大长度：超过它就往前推进切点。
     *
     * <p>这个值直接决定单帧的渲染成本上限：无论正文写到几万字，每次只重新解析
     * 最后这一段。2.4 万字符在 4.4 的低端机上约几毫秒，与千字文时相当。
     */
    static final int TAIL_MAX = 2400;

    /** 推进切点时把尾段缩到的长度。留出滞后区，避免每帧都推进切点导致前缀失配。 */
    static final int TAIL_TARGET = 1200;

    private MarkdownChunker() {
    }

    /**
     * 这一行是不是「紧邻的上一个块会把它吞并」的延续行。
     *
     * <p>这是切点判定里最容易漏的一条。commonmark 里用空行分隔的两个<b>同类</b>
     * 列表会被合成同一个列表（loose list，项内裹 {@code <p>}）：
     * <pre>
     * - 项一      →  &lt;ul&gt;&lt;li&gt;&lt;p&gt;项一&lt;/p&gt;&lt;/li&gt;
     * （空行）        &lt;li&gt;&lt;p&gt;项二&lt;/p&gt;&lt;/li&gt;&lt;/ul&gt;
     * - 项二
     * </pre>
     * 在这种地方切开，前后两半会各成一个 {@code <ul>}（列表项数少的 loose list
     * 退化成 tight），HTML 结构就变了。流式上表现为「列表在切点处突然断成两截、
     * 项内段落间距跳一下」，而且结构一变页面侧的前缀复用失效，整段重画。
     *
     * <p>有序/无序不通用：{@code - 项} 后面跟 {@code 1. 项} 是两个独立列表，可以切。
     */
    private static boolean continuesPreviousBlock(String t, int prevStart, int at) {
        char cur = t.charAt(at);
        char prev = prevStart >= 0 && prevStart < at ? t.charAt(prevStart) : 0;
        if (cur == '-' || cur == '*' || cur == '+') {
            // 前一个块也是无序列表（同一套标记字符，或任意一种——保守起见全算）
            return prev == '-' || prev == '*' || prev == '+';
        }
        if (cur >= '0' && cur <= '9') {
            return prev >= '0' && prev <= '9';
        }
        if (cur == '|') return prev == '|';
        if (cur == '>') return prev == '>';
        // 缩进代码块（4 空格）会延续上一个列表项/引用块，一律不切
        return false;
    }

    /** 该行所在的块的首个非空字符（用于判断「是否与上一个块同类」）。 */
    private static int lineStart(String t, int at) {
        int p = t.lastIndexOf('\n', at - 1);
        return p < 0 ? 0 : p + 1;
    }

    /**
     * 找「前缀可以定稿」的位置：返回前缀长度（尾段从该下标开始）。
     *
     * <p><b>切点必须稳定</b>：调用方按「前缀字符串是否与上一帧相同」判断能否复用
     * 已渲染好的前缀。如果每帧都取「离末尾最近的空行」，正文一长切点就跟着往前挪，
     * 前缀永远对不上，增量等于没做（实测命中率 0%）。
     *
     * <p>所以只在尾段超出 {@link #TAIL_MAX} 时才往前推进切点，而且停在「让尾段
     * 回到 {@link #TAIL_TARGET} 以内」的那个空行上；下一次推进要等尾段再长满。
     * 这样一帧最多重渲染一次前缀，两次推进之间的每一帧都命中。
     *
     * @param text       完整正文
     * @param lastCut    上一帧用的切点（-1 表示还没有）
     * @return 切点下标；-1 表示不能切（整段都还在增长，或没有安全边界）
     */
    static int splitPoint(String text, int lastCut) {
        if (text == null) return -1;
        int n = text.length();
        // 沿用上一帧的切点：只要它还在正文里、且尾段没超上限，就继续用它。
        // 这是增量能不能命中的关键——绝大多数帧都走这一条。
        if (lastCut > 0 && lastCut <= n - 1 && n - lastCut <= TAIL_MAX) {
            return lastCut;
        }
        if (n <= TAIL_TARGET) return -1;
        // 需要（重新）选点：从「尾段缩到 TARGET 以内」的位置往前找最近的空行边界
        int floor = Math.max(0, n - TAIL_TARGET);
        // 只有在上一帧切点之后找，保证切点单调不减——
        // 否则正文被改写（重新生成、编辑）时会把已经渲染的前缀作废
        if (lastCut > floor) floor = lastCut;
        for (int p = n - 2; p >= floor; p--) {
            if (text.charAt(p) != '\n' || text.charAt(p + 1) != '\n') continue;
            // 空行之后还要有内容，否则尾段是空的，等于没切
            int q = skipBlank(text, p + 2);
            if (q >= n) continue;
            if (!isTopLevelBoundary(text, q)) continue;
            if (insideBlock(text, p)) continue;
            // 前一个块的起始行（跳过空行往前找）
            int prevStart = prevBlockStart(text, p);
            if (continuesPreviousBlock(text, prevStart, q)) continue;
            return q;
        }
        // 找不到新切点：上一帧的切点还能用就继续用（尾段变长，但结构仍然安全）
        return (lastCut > 0 && lastCut <= n - 1) ? lastCut : -1;
    }

    /** 兼容旧调用：不考虑上一帧切点，直接按当前正文选点。 */
    static int splitPoint(String text) {
        return splitPoint(text, -1);
    }

    /** 空行之前那个块的起始行首下标；找不到返回 -1。 */
    private static int prevBlockStart(String t, int blankAt) {
        int i = blankAt;
        // 退到空行之前那一行的末尾
        while (i > 0 && (t.charAt(i - 1) == '\n' || t.charAt(i - 1) == ' ' || t.charAt(i - 1) == '\t')) i--;
        if (i <= 0) return -1;
        return lineStart(t, i);
    }

    /** 跳过空白与连续空行。 */
    private static int skipBlank(String t, int i) {
        int n = t.length();
        while (i < n && (t.charAt(i) == '\n' || t.charAt(i) == ' ' || t.charAt(i) == '\t'
                || t.charAt(i) == '\r')) i++;
        return i;
    }

    /**
     * 这个位置是不是「顶层新块的起点」。
     *
     * <p>空行之后的内容如果以 {@code #}、{@code -}、{@code >}、数字列表、
     * 围栏、表格对齐行、HTML 块标签开头，说明它自己就是一个完整的块，
     * 单独解析与跟在后面解析结果一致。普通文字虽然也与前文同属新段落，
     * 但缩进（4 空格代码块）与 setext 标题（下一行 {@code ---}）会跨行生效，
     * 所以那两类要额外排除。
     */
    private static boolean isTopLevelBoundary(String t, int i) {
        int n = t.length();
        if (i >= n) return false;
        char c = t.charAt(i);
        // 4 空格缩进可能是「前一段落仍是同一个 list item 的延续内容」，
        // 单独解析会变成代码块，定稿时这段就变形了
        if (c == ' ' || c == '\t') return false;
        // setext 标题：这一段后面紧跟 === / --- 时，它属于上一段的标题行
        if (c == '=' || c == '-' || c == '+') {
            // 列表项可以，但必须紧跟空格（"- " / "* " / "+ "），否则是分隔线
            return i + 1 < n && (t.charAt(i + 1) == ' ' || t.charAt(i + 1) == '\t');
        }
        if (c == '|') return true;      // 表格
        if (c == '<') return true;      // 原始 HTML 块
        if (c == '#' || c == '>' || c == '`' || c == '~' || c == '[' || c == '*' || c == '_') {
            return true;
        }
        if (c >= '0' && c <= '9') {
            // 有序列表 "1. " / "1) "
            int j = i;
            while (j < n && t.charAt(j) >= '0' && t.charAt(j) <= '9') j++;
            return j < n && (t.charAt(j) == '.' || t.charAt(j) == ')')
                    && j + 1 < n && (t.charAt(j + 1) == ' ' || t.charAt(j + 1) == '\t');
        }
        // 普通文字：只有当下一行不是 setext 下划线时才安全
        int nl = t.indexOf('\n', i);
        if (nl < 0) return false;
        int k = skipSpaces(t, nl + 1);
        if (k < n && (t.charAt(k) == '=' || t.charAt(k) == '-')) {
            // 只看「整行都是 = 或 -」的 setext 下划线
            int e = k;
            char sc = t.charAt(k);
            while (e < n && t.charAt(e) == sc) e++;
            int e2 = skipSpaces(t, e);
            if (e2 >= n || t.charAt(e2) == '\n') return false;
        }
        return true;
    }

    private static int skipSpaces(String t, int i) {
        int n = t.length();
        while (i < n && (t.charAt(i) == ' ' || t.charAt(i) == '\t')) i++;
        return i;
    }

    /**
     * 这个位置是否落在「不能跨块切开」的结构里：围栏代码块、思考块、工具块。
     *
     * <p>扫描是 O(n) 的，但只在尾段超限时才走一次，且每帧最多一次；
     * 相较整段重渲染，这个成本可以忽略。
     */
    /**
     * 围栏代码块的扫描缓存：正文是逐帧增长的，围栏状态可以跨帧累积。
     *
     * <p>每推进一次切点都要判定「这个位置在不在围栏里」。从头扫是 O(n)，
     * 而正文越长扫描越贵——正是要消除的那类随长度增长的开销。
     * 这里记住「已经扫到哪」和「当前是否在围栏里、用的什么围栏字符」，
     * 下一次只扫新增的那一截。
     *
     * <p>正文被改写（变短、或前缀变了）时缓存失效，重头扫一次。
     */
    private static String fenceScanSrc = "";
    private static int fenceScanAt = 0;
    private static char fenceChar = 0;
    private static int fenceLen = 0;

    private static boolean insideBlock(String t, int at) {
        int n = t.length();
        if (at > n) return true;
        int i;
        if (!t.startsWith(fenceScanSrc) && !fenceScanSrc.startsWith(t)) {
            // 换了一段正文：重头扫
            i = 0;
            fenceChar = 0;
            fenceLen = 0;
        } else {
            i = Math.min(fenceScanAt, n);
        }
        // 回退到行首：围栏只在行首识别，从半行中间起扫会漏掉
        while (i > 0 && t.charAt(i - 1) != '\n') i--;
        while (i < n) {
            if (i == 0 || t.charAt(i - 1) == '\n') {
                int j = skipSpaces(t, i);
                if (j < n && (t.charAt(j) == '`' || t.charAt(j) == '~')) {
                    char cc = t.charAt(j);
                    int run = 0, k = j;
                    while (k < n && t.charAt(k) == cc) { run++; k++; }
                    if (run >= 3) {
                        if (fenceChar == 0) {
                            fenceChar = cc;
                            fenceLen = run;
                        } else if (cc == fenceChar && run >= fenceLen) {
                            fenceChar = 0;
                        }
                        int nl = t.indexOf('\n', k);
                        i = (nl < 0) ? n : nl + 1;
                        continue;
                    }
                }
            }
            i++;
        }
        fenceScanSrc = t;
        fenceScanAt = n;
        if (fenceChar != 0) return true;
        return insideStructured(t, at);
    }

    /** 落在未闭合的 {@code [thinking]} / {@code [tool]} 块里。 */
    private static boolean insideStructured(String t, int at) {
        // 这两个标记只会出现在正文里，且需要「从某个已闭合块之后」开始配对；
        // 用 lastIndexOf 找最近一次出现，落在未闭合区里就直接命中。
        // 比从头顺序配对更省，也不会把早先已闭合的块误判成未闭合。
        int thinkOpen = t.lastIndexOf("[thinking]", at);
        if (thinkOpen >= 0) {
            int thinkClose = t.lastIndexOf("[/thinking]", at);
            if (thinkClose < thinkOpen) return true;
        }
        int toolOpen = t.lastIndexOf(WebSearchTool.TRACE_START, at);
        if (toolOpen >= 0) {
            int toolClose = t.lastIndexOf(WebSearchTool.TRACE_END, at);
            if (toolClose < toolOpen) return true;
        }
        return false;
    }

    /**
     * 最后一个「未闭合的开始标记」的位置；没有则 -1。
     *
     * <p>从开头顺序配对而不是 {@code lastIndexOf}：标记可能出现在前面已闭合的
     * 块里（比如工具结果里带着一段含字面量 {@code [thinking]} 的正文），
     * 只数最后一个会误判。
     */
    private static int lastOpen(String t, String open, String close, int at) {
        int openLen = open.length();
        int closeLen = close.length();
        int i = 0;
        int lastOpenAt = -1;
        while (i < at) {
            int o = t.indexOf(open, i);
            if (o < 0 || o >= at) break;
            int c = t.indexOf(close, o + openLen);
            if (c < 0 || c >= at) {
                lastOpenAt = o;
                break;
            }
            i = c + closeLen;
        }
        return lastOpenAt;
    }
}
