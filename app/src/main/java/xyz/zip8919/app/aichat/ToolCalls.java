package xyz.zip8919.app.aichat;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * OpenAI 风格 tool_calls 的解析与序列化。
 *
 * <p>流式响应里的 tool_calls 是按 index 增量下发的：第一个分片带 id 与 name，
 * 后续分片只带 arguments 的片段。这里统一成「按 index 累积成一个对象」的模型，
 * 调用方拿到的都是完整结构。
 *
 * <p>协议上有一条硬要求：assistant(tool_calls) 里的每一个 id 都必须有对应的
 * tool 消息，否则多数端点直接返回 400。{@link #appendResult} 与
 * {@link #limitNotice} 就是为这条规则服务的。
 */
final class ToolCalls {

    private ToolCalls() {
    }

    /** 非流式响应里的 tool_calls 是完整的，转成内部结构（name/arguments 扁平化）。 */
    static void normalize(JSONArray raw, JSONArray out) throws Exception {
        if (raw == null) return;
        for (int i = 0; i < raw.length(); i++) {
            JSONObject item = raw.optJSONObject(i);
            if (item == null) continue;
            JSONObject tc = new JSONObject();
            tc.put("id", item.optString("id", ""));
            JSONObject fn = item.optJSONObject("function");
            tc.put("name", fn == null ? "" : fn.optString("name", ""));
            tc.put("arguments", fn == null ? "{}" : fn.optString("arguments", "{}"));
            out.put(tc);
        }
    }

    /** 把流式分片里的 tool_calls 增量合并到 acc（按 index 对齐，arguments 直接拼接）。 */
    static void accumulate(JSONArray acc, JSONArray deltas) throws Exception {
        if (deltas == null) return;
        for (int i = 0; i < deltas.length(); i++) {
            JSONObject d = deltas.optJSONObject(i);
            if (d == null) continue;
            int index = d.optInt("index", i);
            while (acc.length() <= index) {
                JSONObject empty = new JSONObject();
                empty.put("id", "");
                empty.put("name", "");
                empty.put("arguments", "");
                acc.put(empty);
            }
            JSONObject tc = acc.getJSONObject(index);
            if (d.has("id") && !d.isNull("id")) {
                String id = d.optString("id", "");
                if (id.length() > 0) tc.put("id", id);
            }
            JSONObject fn = d.optJSONObject("function");
            if (fn == null) continue;
            if (fn.has("name") && !fn.isNull("name")) {
                String name = fn.optString("name", "");
                if (name.length() > 0) {
                    String old = tc.optString("name", "");
                    if (old.length() == 0) tc.put("name", name);
                    else if (!old.equals(name)) tc.put("name", old + name);
                }
            }
            if (fn.has("arguments") && !fn.isNull("arguments")) {
                tc.put("arguments", tc.optString("arguments", "") + fn.optString("arguments", ""));
            }
        }
    }

    /** 至少有一个带 name 的调用才值得执行；只有空壳的分片应当忽略。 */
    static boolean hasUsable(JSONArray calls) {
        if (calls == null) return false;
        for (int i = 0; i < calls.length(); i++) {
            JSONObject tc = calls.optJSONObject(i);
            if (tc != null && tc.optString("name", "").length() > 0) return true;
        }
        return false;
    }

    /**
     * 追加一轮 assistant(tool_calls)。{@code mirror} 非空时同时写一份到工具历史，
     * 供后续轮次回放以及「从出错处重试」使用。
     */
    static void appendAssistant(JSONArray conversation, JSONArray mirror, String content,
            JSONArray calls) throws Exception {
        JSONArray payload = new JSONArray();
        for (int i = 0; i < calls.length(); i++) {
            JSONObject tc = calls.getJSONObject(i);
            String name = tc.optString("name", "");
            if (name.length() == 0) continue;
            String id = tc.optString("id", "");
            if (id.length() == 0) {
                id = "call_" + i + "_" + System.currentTimeMillis();
                tc.put("id", id);
            }
            String argText = tc.optString("arguments", "");
            if (argText.trim().length() == 0) argText = "{}";

            JSONObject out = new JSONObject();
            out.put("id", id);
            out.put("type", "function");
            JSONObject fn = new JSONObject();
            fn.put("name", name);
            fn.put("arguments", argText);
            out.put("function", fn);
            payload.put(out);
        }
        if (payload.length() == 0) return;

        JSONObject assistant = new JSONObject();
        assistant.put("role", "assistant");
        assistant.put("content", content == null || content.length() == 0 ? JSONObject.NULL : content);
        assistant.put("tool_calls", payload);
        conversation.put(assistant);
        if (mirror != null) mirror.put(assistant);
    }

    /** 追加一条 tool 结果。id 为空也要写，保证 assistant/tool 数量对齐。 */
    static void appendResult(JSONArray conversation, JSONArray mirror, String id, String content)
            throws Exception {
        JSONObject tm = new JSONObject();
        tm.put("role", "tool");
        tm.put("tool_call_id", id == null ? "" : id);
        tm.put("content", content == null ? "" : content);
        conversation.put(tm);
        if (mirror != null) mirror.put(tm);
    }

    /** 单轮调用超限时，给未执行的调用补的 tool 结果。 */
    static String limitNotice(int maxPerRound) {
        try {
            JSONObject json = new JSONObject();
            json.put("error", "每轮工具调用上限为 " + maxPerRound + "，本次调用未执行");
            json.put("limit_notice", "请基于已执行结果继续回答，或直接给出最终回答。");
            return json.toString();
        } catch (Exception e) {
            return "{\"error\":\"每轮工具调用已超上限，本次调用未执行\"}";
        }
    }

    /** 工具执行期间显示在气泡里的状态文案。 */
    static String statusText(String name, JSONObject args) {
        if (WebSearchTool.TOOL_TIME.equals(name)) {
            return "正在获取当前时间...";
        }
        if (WebSearchTool.TOOL_SEARCH.equals(name)) {
            String query = args == null ? "" : args.optString("query", "");
            return query.length() > 0 ? "正在联网搜索：" + TextKit.shorten(query, 60) : "正在联网搜索...";
        }
        if (WebSearchTool.TOOL_FETCH.equals(name)) {
            String url = args == null ? "" : args.optString("url", "");
            return url.length() > 0 ? "正在读取网页：" + TextKit.shorten(url, 60) : "正在读取网页...";
        }
        return "正在调用工具：" + TextKit.shorten(name, 40);
    }
}
