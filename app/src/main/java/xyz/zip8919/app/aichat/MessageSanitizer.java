package xyz.zip8919.app.aichat;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 发送前把 messages 修成协议合法顺序。
 *
 * <p>要解决的问题（线上 400 的原话）：
 * <pre>
 * An assistant message with 'tool_calls' must be followed by tool messages responding
 * to each 'tool_call_id'. (insufficient tool messages following tool_calls message)
 * </pre>
 *
 * <p>触发场景是「一轮里既跑了图片搜索、又有超限调用」：图片工具的视觉注入会往
 * 会话里插一条多模态 user 消息，而超限调用补 tool 结果是在它之后——顺序变成
 * {@code assistant(tool_calls) → tool, tool, tool → user → tool, tool}，
 * 端点认为 tool_calls 没有紧跟全部 tool 回复，直接 400。
 *
 * <p><b>为什么写入端修完还要这一层</b>：坏顺序已经落盘在会话的 {@code toolHistory}
 * 里（用户那条会话已经坏了），只修写入端的话，用户接着聊仍然 400。这里按
 * 「assistant(tool_calls) 后面必然紧跟它全部 tool 回复」重建一遍顺序：
 * <ul>
 *   <li>把夹在中间的 user 消息挪到整组 tool 之后（语义不变，模型仍然按顺序看到）；</li>
 *   <li>缺失的 tool 回复补一条占位，保证 id 全部有对应；</li>
 *   <li>找不到归属的孤儿 tool 消息直接丢弃（它自己也会引发 400）。</li>
 * </ul>
 *
 * <p>只重排、不删除业务内容；无 tool_calls 的普通消息原样透传。
 */
final class MessageSanitizer {

    private static final String TAG = "MsgSanitizer";

    private MessageSanitizer() {
    }

    static JSONArray sanitize(JSONArray in) {
        if (in == null || in.length() == 0) return in;
        try {
            JSONArray out = new JSONArray();
            int n = in.length();
            int i = 0;
            int repaired = 0;
            while (i < n) {
                JSONObject m = in.optJSONObject(i);
                i++;
                if (m == null) continue;
                String role = m.optString("role", "");
                if ("tool".equals(role)) {
                    // 走到这里说明它前面没有对应的 assistant(tool_calls)
                    repaired++;
                    continue;
                }
                JSONArray calls = m.optJSONArray("tool_calls");
                if (!"assistant".equals(role) || calls == null || calls.length() == 0) {
                    out.put(m);
                    continue;
                }

                // 1) 收集这一组 tool_calls 的 id
                JSONArray ids = new JSONArray();
                for (int c = 0; c < calls.length(); c++) {
                    JSONObject call = calls.optJSONObject(c);
                    if (call == null) continue;
                    String id = call.optString("id", "");
                    if (id.length() == 0) continue;
                    ids.put(id);
                }
                if (ids.length() == 0) {
                    out.put(m);
                    continue;
                }

                // 2) 往后扫：属于本组 id 的 tool 消息收下，夹在中间的 user 消息暂存，
                //    遇到其它角色（下一条 assistant / 真正的下一轮）就停
                JSONArray collected = new JSONArray();
                JSONArray deferred = new JSONArray();
                while (i < n) {
                    JSONObject nxt = in.optJSONObject(i);
                    if (nxt == null) { i++; continue; }
                    String r = nxt.optString("role", "");
                    if ("tool".equals(r)) {
                        String id = nxt.optString("tool_call_id", "");
                        if (indexOf(ids, id) >= 0 && !containsId(collected, id)) {
                            collected.put(nxt);
                        } else {
                            repaired++;      // 重复或孤儿 tool，丢掉
                        }
                        i++;
                        continue;
                    }
                    if ("user".equals(r)) {
                        deferred.put(nxt);
                        i++;
                        continue;
                    }
                    break;
                }

                out.put(m);
                // 3) 按 tool_calls 的原顺序补齐，缺的补占位
                for (int c = 0; c < ids.length(); c++) {
                    String id = ids.optString(c, "");
                    JSONObject hit = findById(collected, id);
                    if (hit != null) {
                        out.put(hit);
                    } else {
                        out.put(placeholder(id));
                        repaired++;
                    }
                }
                for (int c = 0; c < deferred.length(); c++) out.put(deferred.optJSONObject(c));
            }
            if (repaired > 0) {
                LogUtil.w(TAG, "repaired %d message(s) before request (in=%d out=%d)",
                        repaired, n, out.length());
            }
            return out;
        } catch (Throwable t) {
            // 修复失败绝不阻断请求：宁可原样发，让服务端报错，也不要本地崩掉
            LogUtil.w(TAG, "sanitize failed: " + t.getMessage());
            return in;
        }
    }

    private static JSONObject placeholder(String id) {
        JSONObject tm = new JSONObject();
        try {
            tm.put("role", "tool");
            tm.put("tool_call_id", id);
            tm.put("content", "{\"error\":\"工具结果缺失，已按空结果补齐\"}");
        } catch (Exception ignored) {
        }
        return tm;
    }

    private static int indexOf(JSONArray arr, String v) {
        for (int i = 0; i < arr.length(); i++) {
            if (v.equals(arr.optString(i, ""))) return i;
        }
        return -1;
    }

    private static boolean containsId(JSONArray arr, String id) {
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && id.equals(o.optString("tool_call_id", ""))) return true;
        }
        return false;
    }

    private static JSONObject findById(JSONArray arr, String id) {
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && id.equals(o.optString("tool_call_id", ""))) return o;
        }
        return null;
    }
}
