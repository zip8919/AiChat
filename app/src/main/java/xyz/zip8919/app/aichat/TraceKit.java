package xyz.zip8919.app.aichat;

/**
 * trace 缓冲区（思考块 + 工具卡片）的长度治理。
 *
 * <p>为什么需要它：工具轮次不设总上限，一次长对话可以累积十几轮搜索卡片。
 * trace 既是每次渲染的输入，也会整段写进 {@link Message#content} 落盘。没有上限时：
 * <ul>
 *   <li>每追加一张卡片就把全部历史重新走一遍 markdown + 公式，成本是 O(n²)；</li>
 *   <li>单条消息几万字，会话文件越滚越大，切会话时读盘也跟着变慢。</li>
 * </ul>
 *
 * <p>裁剪只在<b>块边界</b>发生。trace 的结构是 {@code [thinking]…[/thinking]} 与
 * {@code [tool]…[/tool]} 两类自闭合块，从块边界切走前缀不会破坏任何一段的语法。
 * 找不到边界（例如整条 trace 就是一个超长块）时<b>不裁</b>——宁可长，也不能把
 * 一段正在渲染的 markdown 切成两半。
 */
final class TraceKit {

    private static final String TAG = "TraceKit";

    /**
     * 兜底上限（字符），<b>不是</b>显示上限。
     *
     * <p>这里只防一种情况：某个工具返回了几百 MB 的正文，单条消息的 trace 大到
     * 落盘和解析都会把机器拖死。5 MB ≈ 900 张工具卡片，正常对话永远碰不到。
     *
     * <p>日常「卡片太多」由渲染层折叠解决（只显示最近若干张，更早的折成一行可点开），
     * 数据一条都不删。早先这里写的是 48000，约 8~10 轮卡片就开始真删数据——
     * 用户看到的是「卡片自己消失了」，重开会话也回不来，那是错的取舍。
     */
    static final int MAX_CHARS = 5 * 1024 * 1024;

    /** 裁剪后贴在头部的说明块，见 {@link #cap}。 */
    static final String TRIM_TOOL = "trace_trimmed";

    private static final String THINKING_END = "[/thinking]";

    private TraceKit() {
    }

    /**
     * 超限时从头部裁掉若干个完整块。
     *
     * <p><b>裁掉不等于抹掉</b>：早先这里直接 {@code delete(0, cut)}，聊到中途、工具卡片
     * 攒够 48000 字符后，用户看着屏幕上前几张卡片凭空消失——没有任何提示，
     * 看起来就是「渲染坏了」。现在在剪切点插入一个说明块，明确告诉用户
     * 「更早的工具记录因为太长已收起」，并且只插入一次（重复裁剪不叠加）。
     *
     * @return 是否真的裁剪过。裁剪后文本变短，调用方必须重置「已推送到 DOM 的
     *         base」标记，否则下一次渲染会把自己当成过期快照丢掉。
     */
    static boolean cap(StringBuilder sb) {
        return cap(sb, MAX_CHARS);
    }

    static boolean cap(StringBuilder sb, int max) {
        if (sb == null || max <= 0 || sb.length() <= max) return false;
        // 说明块本身要占长度，预算里先扣掉，否则裁完还是超
        int noteLen = trimNote().length();
        int cut = safeCut(sb.toString(), sb.length() - max + noteLen);
        if (cut <= 0) {
            // 没有可用的块边界：保持原样，绝不硬切
            return false;
        }
        sb.delete(0, cut);
        // 必须在剪切之后再判断：说明块就在头部，这一刀很可能把它一起切掉了。
        // 先判断后剪切的写法只会在第一次裁剪时插入，第二次又变成静默消失。
        boolean noted = sb.indexOf(TRIM_TOOL) >= 0;
        if (!noted) sb.insert(0, trimNote());
        LogUtil.i(TAG, "trace capped: cut=%d, remain=%d, noted=%s",
                cut, sb.length(), !noted);
        return true;
    }

    /**
     * 裁剪说明块。用 tool 卡片的既有语法而不是另造标记：
     * 渲染层、纯文本剥离、落盘解析都已经认识 {@code [tool]...[/tool]}，
     * 多一种标记就要在五处同步，迟早漏一处。
     */
    private static String trimNote() {
        return WebSearchTool.TRACE_START
                + "{\"tool\":\"" + TRIM_TOOL + "\",\"note\":\"更早的工具调用记录因内容过长已收起\"}"
                + WebSearchTool.TRACE_END;
    }

    /**
     * 从 {@code from} 起找第一个块结束位置，返回该块的结束下标（切割点）。
     * 找不到返回 -1。
     */
    private static int safeCut(String s, int from) {
        int i = Math.max(0, from);
        if (i >= s.length()) return -1;
        int tool = s.indexOf(WebSearchTool.TRACE_END, i);
        int think = s.indexOf(THINKING_END, i);
        int hit;
        int len;
        if (tool < 0 && think < 0) return -1;
        if (tool < 0) {
            hit = think;
            len = THINKING_END.length();
        } else if (think < 0) {
            hit = tool;
            len = WebSearchTool.TRACE_END.length();
        } else if (tool < think) {
            hit = tool;
            len = WebSearchTool.TRACE_END.length();
        } else {
            hit = think;
            len = THINKING_END.length();
        }
        return hit + len;
    }
}
