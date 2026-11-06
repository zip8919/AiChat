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

    /** 单条消息 trace 的上限（字符）。约 8~10 轮完整工具卡片。 */
    static final int MAX_CHARS = 48000;

    private static final String THINKING_END = "[/thinking]";

    private TraceKit() {
    }

    /**
     * 超限时从头部裁掉若干个完整块。
     *
     * @return 是否真的裁剪过。裁剪后文本变短，调用方必须重置「已推送到 DOM 的
     *         base」标记，否则下一次渲染会把自己当成过期快照丢掉。
     */
    static boolean cap(StringBuilder sb) {
        return cap(sb, MAX_CHARS);
    }

    static boolean cap(StringBuilder sb, int max) {
        if (sb == null || max <= 0 || sb.length() <= max) return false;
        int cut = safeCut(sb.toString(), sb.length() - max);
        if (cut <= 0) {
            // 没有可用的块边界：保持原样，绝不硬切
            return false;
        }
        sb.delete(0, cut);
        LogUtil.i(TAG, "trace capped: cut=%d, remain=%d", cut, sb.length());
        return true;
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
