package xyz.zip8919.app.aichat;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 从消息正文里恢复工具调用历史。
 *
 * <p>解决的问题：{@link Message#toolHistory} 是 1.7 之后才有的字段，之前的会话
 * 只存了渲染用的正文。这些会话再继续聊时，模型看不到「上一轮到底搜过什么」，
 * 用户问「你刚才查到的那个价格再结合一下」，模型只会说没查到——因为请求体里
 * 确实一条工具记录都没有。
 *
 * <p>做法：trace 块（{@code [tool]{...}[/tool]}）是自包含的展示 JSON，里面有工具名、
 * 查询参数和结果条目。把它按 OpenAI 的 assistant(tool_calls) / tool 配对格式重新
 * 拼一遍，就能当成真实历史回放给模型。**恢复出来的是展示用的精简结果**（摘要可能
 * 已被截断），不如当时落盘的原始 JSON 完整，但对「模型知道自己搜过什么」这个目的
 * 是够的。
 *
 * <p>只在 {@code toolHistory} 为空时启用，不影响新会话。
 */
final class ToolHistoryRecovery {

    private static final String TAG = "ToolHistory";

    /** 恢复出来的调用 id 前缀，便于在日志里区分真实调用与恢复项。 */
    private static final String ID_PREFIX = "recovered_";

    private ToolHistoryRecovery() {
    }

    /**
     * @return OpenAI 格式的消息数组字符串；正文里没有可恢复的工具块时返回 {@code ""}
     */
    static String recover(String content) {
        if (content == null) return "";
        int at = content.indexOf(WebSearchTool.TRACE_START);
        if (at < 0) return "";
        JSONArray out = new JSONArray();
        int seq = 0;
        int i = 0;
        int len = content.length();
        while (i < len) {
            int s = content.indexOf(WebSearchTool.TRACE_START, i);
            if (s < 0) break;
            int bodyStart = s + WebSearchTool.TRACE_START.length();
            int e = content.indexOf(WebSearchTool.TRACE_END, bodyStart);
            if (e < 0) break;                  // 未闭合：后面的内容可能还没写完
            i = e + WebSearchTool.TRACE_END.length();
            String body = content.substring(bodyStart, e);
            JSONObject block;
            try {
                block = new JSONObject(body);
            } catch (Exception bad) {
                // 代码块里偶尔出现 [tool] 字样，或者数据存坏了。解析不了就跳过，
                // 不能因为一段脏数据让整条历史都回放不了。
                LogUtil.d(TAG, "skip unparsable trace block: %s", LogUtil.preview(body, 80));
                continue;
            }
            String tool = block.optString("tool", "");
            if (tool.length() == 0) continue;
            // limit 是「本轮超限未执行」的占位卡片，不是真实调用
            if ("limit".equals(tool)) continue;
            try {
                appendPair(out, block, tool, ID_PREFIX + seq);
                seq++;
            } catch (Exception e2) {
                LogUtil.w(TAG, "append recovered pair failed: %s", e2.getMessage());
            }
        }
        if (out.length() == 0) return "";
        LogUtil.i(TAG, "recovered %d tool pair(s) from content", seq);
        return out.toString();
    }

    private static void appendPair(JSONArray out, JSONObject block, String tool, String id)
            throws Exception {
        JSONObject args = new JSONObject();
        if (block.has("query")) args.put("query", block.optString("query", ""));
        if (block.has("url")) args.put("url", block.optString("url", ""));
        if (block.has("count")) args.put("count", block.optInt("count", 0));

        JSONObject call = new JSONObject();
        call.put("id", id);
        call.put("type", "function");
        JSONObject fn = new JSONObject();
        fn.put("name", tool);
        fn.put("arguments", args.toString());
        call.put("function", fn);
        JSONArray calls = new JSONArray();
        calls.put(call);

        JSONObject assistant = new JSONObject();
        assistant.put("role", "assistant");
        // 工具轮次的 assistant 本来就没有正文；显式写 null 才符合协议，
        // 空字符串会被部分端点判为非法消息。
        assistant.put("content", JSONObject.NULL);
        assistant.put("tool_calls", calls);
        out.put(assistant);

        // 结果就是展示块本身去掉 tool 字段：query/count 属于参数，
        // 剩下的 title/url/summary/content/error 才是模型要读的结果。
        block.remove("tool");
        JSONObject result = new JSONObject();
        result.put("role", "tool");
        result.put("tool_call_id", id);
        result.put("content", block.toString());
        out.put(result);
    }
}
