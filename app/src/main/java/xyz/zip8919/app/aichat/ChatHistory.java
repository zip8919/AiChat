package xyz.zip8919.app.aichat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地会话历史 → chat completions 请求体的 messages 数组。
 *
 * <p>这里做的全是「怎么把本地存的对话还原成模型能看懂的上下文」这类纯逻辑，
 * 不碰 UI、不碰网络，可以直接用 JVM 单测验证。MainActivity 只负责在合适的
 * 时机调用 {@link #build}。
 *
 * <p>两条约束贯穿全文件：
 * <ul>
 *   <li>回放格式必须符合 OpenAI 的 tool_calls / tool 配对协议，少一条对应的
 *       tool 结果就会被多数端点直接 400。</li>
 *   <li>压缩必须是纯函数（同输入 → 同字节输出）。历史属于请求前缀，前缀变一个
 *       字，后面的 prompt 缓存就全部失效。</li>
 * </ul>
 */
final class ChatHistory {

    private static final String TAG = "ChatHistory";

    /** 只回放最近几轮回复的工具历史：更早的只保留正文，避免长会话把上下文撑爆。 */
    private static final int TOOL_TURNS = 3;

    /** 单条摘要上限（字）。标题、链接、站点、日期一律保留。 */
    private static final int SUMMARY_MAX = 250;

    /** 所有条目摘要的合计预算：用完后剩余条目只留标题与链接。 */
    private static final int SUMMARY_TOTAL = 1500;

    /** 网页抓取的正文上限（字）。 */
    private static final int FETCH_MAX = 600;

    /** 序列化后的总长兜底，防止条目极多时把上下文撑爆。 */
    private static final int RESULT_MAX = 3000;

    // 单条消息最多带几张图已搬进设置（Limits.visionMaxImages，默认 3）：
    // 视觉请求体积 = 张数 × base64 长度，多一张在慢网上就是十几秒上传

    /** 把本地图片变成可放进 {@code image_url.url} 的 data URI；返回 null 表示取不到。 */
    interface ImageProvider {
        String dataUri(AttachmentText.File f);
    }

    private ChatHistory() {
    }

    /**
     * 构造请求体的 messages（纯文本，图片只写一行说明）。
     *
     * @param snapshot 当前会话消息快照（调用方保证不在请求线程遍历 UI 集合）
     * @param skip     要跳过的消息（本轮正在生成的 AI 占位），可为 null
     * @param sysPrompt 系统提示词，空则不插入 system 消息
     */
    static JSONArray build(List<Message> snapshot, Message skip, String sysPrompt) throws Exception {
        return build(snapshot, skip, sysPrompt, false, null);
    }

    /**
     * 构造请求体的 messages。
     *
     * <p>{@code vision} 为 true 时，带图片附件的用户消息会装成 OpenAI 多模态格式：
     * <pre>{"role":"user","content":[{"type":"text","text":...},
     *   {"type":"image_url","image_url":{"url":"data:image/jpeg;base64,..."}}]}</pre>
     * 这正是 DeepSeek / OpenAI 文档里的形状。取不到任何一张图时退回纯文本，
     * 而不是发一个只有文字的 content 数组——部分端点对空图片数组会直接 400。
     */
    static JSONArray build(List<Message> snapshot, Message skip, String sysPrompt,
            boolean vision, ImageProvider images) throws Exception {
        JSONArray msgs = new JSONArray();
        if (sysPrompt != null && !sysPrompt.isEmpty()) {
            JSONObject sm = new JSONObject();
            sm.put("role", "system");
            sm.put("content", sysPrompt);
            msgs.put(sm);
        }
        if (snapshot == null) return msgs;

        int aiTotal = 0;
        for (Message m : snapshot) {
            if (m != skip && m.isAssistant()) aiTotal++;
        }
        int aiSeen = 0;
        for (Message m : snapshot) {
            if (m == skip) continue;
            if (m.isAssistant()) {
                boolean replayTools = (aiTotal - aiSeen) <= TOOL_TURNS;
                aiSeen++;
                appendAssistant(msgs, m, replayTools);
                continue;
            }
            JSONObject mm = new JSONObject();
            mm.put("role", m.role);
            // 用户消息必须带上附件正文：content 只有用户原话，附件在 attachments 里，
            // 由 AttachmentText 现拼成「原话 + <附件>…</附件>」。只发 content 的话
            // 模型收不到文件内容，表现为「明明附了文件，AI 却说看不到」。
            if (vision && m.isUser() && images != null) {
                Object multimodal = multimodalContent(m, images);
                if (multimodal != null) {
                    mm.put("content", multimodal);
                    msgs.put(mm);
                    continue;
                }
            }
            mm.put("content", apiContentOf(m, vision));
            msgs.put(mm);
        }
        return msgs;
    }

    /**
     * 这条消息该以什么文本发给模型。
     *
     * <p>用户消息的正文 = 原话 + 附件正文，由 {@link AttachmentText#apiTextOf} 现拼：
     * {@code content} 现在只存用户原话，附件在 {@link Message#attachments} 里。
     * 1.8.2 及以前的老会话没有这个字段，附件正文整段存在 {@code apiText}，那里继续兜底。
     *
     * <p>AI 消息的 {@code apiText} 是「去掉 [thinking]/[tool] 标记的正文」，空则回退气泡文本。
     */
    private static String apiContentOf(Message m) {
        return apiContentOf(m, false);
    }

    private static String apiContentOf(Message m, boolean vision) {
        if (m.isUser()) return AttachmentText.apiTextOf(m, vision);
        String api = m.apiText;
        if (api != null && api.trim().length() > 0) return api;
        return m.content == null ? "" : m.content;
    }

    /**
     * 把一条带图片的用户消息装成多模态 content 数组；没有可用图片时返回 null
     * （调用方退回纯文本）。
     *
     * <p>顺序不能反：text 块在前，图片在后。个别端点会把图片块之前的文本当作
     * 「图片描述」并丢掉，反过来则不触发这个问题。
     */
    private static Object multimodalContent(Message m, ImageProvider images) {
        if (m.attachments == null || m.attachments.isEmpty()) return null;
        boolean hasImage = false;
        for (int i = 0; i < m.attachments.size(); i++) {
            AttachmentText.File f = m.attachments.get(i);
            if (f != null && f.isImage()) { hasImage = true; break; }
        }
        if (!hasImage) return null;

        try {
            JSONArray parts = new JSONArray();
            JSONObject text = new JSONObject();
            text.put("type", "text");
            text.put("text", AttachmentText.apiTextOf(m, true));
            parts.put(text);

            int added = 0;
            // 张数上限来自设置（默认 3）：多一张就是多 160 KB base64，慢网上直接卡住
            final int maxVision = Limits.visionMaxImages;
            for (int i = 0; i < m.attachments.size() && added < maxVision; i++) {
                AttachmentText.File f = m.attachments.get(i);
                if (f == null || !f.isImage()) continue;
                String uri = images.dataUri(f);
                if (uri == null || uri.length() == 0) {
                    LogUtil.w(TAG, "vision: image unavailable, skipped: %s", f.name);
                    continue;
                }
                JSONObject img = new JSONObject();
                img.put("type", "image_url");
                JSONObject url = new JSONObject();
                url.put("url", uri);
                img.put("image_url", url);
                parts.put(img);
                added++;
            }
            if (added == 0) return null;
            LogUtil.d(TAG, "vision: %d image(s) attached for msg len=%d",
                    added, m.content == null ? 0 : m.content.length());
            return parts;
        } catch (Exception e) {
            LogUtil.w(TAG, "multimodalContent failed, fallback to text: %s", e.getMessage());
            return null;
        }
    }

    /**
     * 回放一条历史 AI 回复：先 assistant(tool_calls) + tool(结果) 的配对，最后是正文。
     * 工具历史是旧版本没有的新字段，缺失时退化成纯文本，与旧版本行为一致。
     */
    private static void appendAssistant(JSONArray msgs, Message m, boolean replayTools)
            throws Exception {
        String hist = replayTools ? m.toolHistory : "";
        if (replayTools && (hist == null || hist.length() <= 2)) {
            // 旧会话没有 toolHistory 字段，但正文里的 [tool] 卡片还在。
            // 不回放的话模型完全不知道上一轮搜过什么，是这类会话「失忆」的根因。
            hist = ToolHistoryRecovery.recover(m.content);
        }
        if (hist != null && hist.length() > 2) {
            try {
                appendToolHistory(msgs, hist);
            } catch (Exception e) {
                // 存坏了就退回纯文本，不要让整个请求挂掉
                LogUtil.w(TAG, "bad toolHistory, fallback to text: %s", e.getMessage());
            }
        }

        String text = m.apiText != null && m.apiText.trim().length() > 0
                ? m.apiText.trim()
                : MessageHtmlRenderer.plainTextOf(m.content);
        // 正文为空就别发 assistant：空 content 会被部分端点判为非法消息（400），
        // 而工具轮次里「只有 tool_calls、没有最终正文」本身也是合法状态。
        if (text.length() > 0) {
            JSONObject mm = new JSONObject();
            mm.put("role", "assistant");
            mm.put("content", text);
            msgs.put(mm);
        }
    }

    /**
     * 回放一组工具历史，并保证 assistant(tool_calls) 与 tool 结果严格配对。
     *
     * <p>保存可能发生在工具执行中途（用户打断、请求失败、进程被杀），此时
     * toolHistory 末尾会留下「有 tool_calls、没有对应 tool 结果」的半截记录。
     * 直接发出去会被多数端点拒绝（400），所以这里只提交已经配齐的组。
     *
     * @return 是否至少提交了一条消息
     */
    private static boolean appendToolHistory(JSONArray msgs, String hist) throws Exception {
        JSONArray arr = new JSONArray(hist);
        JSONObject pendingAssistant = null;
        List<String> pendingIds = new ArrayList<String>();
        JSONArray pendingTools = new JSONArray();
        boolean any = false;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String role = o.optString("role", "");
            if ("assistant".equals(role)) {
                JSONArray calls = o.optJSONArray("tool_calls");
                if (calls != null && calls.length() > 0) {
                    // 上一组没配齐就来了新的一组：丢掉上一组，只保留能配对的
                    pendingAssistant = o;
                    pendingIds.clear();
                    pendingTools = new JSONArray();
                    for (int k = 0; k < calls.length(); k++) {
                        JSONObject c = calls.optJSONObject(k);
                        if (c != null) pendingIds.add(c.optString("id", ""));
                    }
                    if (pendingIds.isEmpty()) {
                        msgs.put(o);
                        pendingAssistant = null;
                        any = true;
                    }
                    continue;
                }
                msgs.put(o);
                any = true;
            } else if ("tool".equals(role)) {
                if (pendingAssistant == null) continue;   // 孤立的 tool 结果
                String id = o.optString("tool_call_id", "");
                if (id.length() == 0 && !pendingIds.isEmpty()) {
                    id = pendingIds.get(0);   // 老数据没写 id：按顺序补一个，保证配对
                }
                JSONObject t = new JSONObject();
                t.put("role", "tool");
                t.put("tool_call_id", id);
                t.put("content", compactToolResult(o.optString("content", "")));
                pendingTools.put(t);
                pendingIds.remove(id);
                if (pendingIds.isEmpty()) {
                    msgs.put(pendingAssistant);
                    for (int k = 0; k < pendingTools.length(); k++) msgs.put(pendingTools.get(k));
                    pendingAssistant = null;
                    any = true;
                }
            } else {
                msgs.put(o);
                any = true;
            }
        }
        // 末尾没配齐的那组不提交：宁可少一轮工具上下文，也不要整个请求 400
        return any;
    }

    /**
     * 压缩一条历史工具结果。
     *
     * <p>按结构压缩而不是硬截断：硬截断会把 JSON 砍成半截，后半段的链接全部丢失，
     * 模型既读不懂也拿不到来源。这里保留所有条目的 title/url/site/date，只压
     * summary；只有总长仍超预算时才按条丢弃，并注明剩余条数。
     */
    static String compactToolResult(String result) {
        if (result == null || result.length() == 0) return "";
        try {
            JSONObject json = new JSONObject(result);
            JSONArray results = json.optJSONArray("results");
            if (results == null) {
                // 网页抓取：正文太长，留开头即可
                String content = json.optString("content", "");
                if (content.length() > FETCH_MAX) {
                    json.put("content", content.substring(0, FETCH_MAX) + "…（正文已截断）");
                }
                return fitLimit(json);
            }
            int budget = SUMMARY_TOTAL;
            for (int i = 0; i < results.length(); i++) {
                JSONObject item = results.optJSONObject(i);
                if (item == null) continue;
                String summary = item.optString("summary", "");
                int allow = Math.min(SUMMARY_MAX, budget);
                if (allow <= 0) {
                    item.put("summary", "");
                    continue;
                }
                if (summary.length() > allow) summary = summary.substring(0, allow) + "…";
                budget -= summary.length();
                item.put("summary", summary);
            }
            return fitLimit(json);
        } catch (Exception e) {
            // 不是合法 JSON（极少见）：退化为定长截断
            return result.length() <= RESULT_MAX
                    ? result : result.substring(0, RESULT_MAX) + "…（历史结果已压缩）";
        }
    }

    /** 序列化并按条兜底：超过总长上限时保留能放下的条目，注明被省略的条数。 */
    private static String fitLimit(JSONObject json) {
        String s = json.toString();
        if (s.length() <= RESULT_MAX) return s;
        try {
            JSONArray results = json.optJSONArray("results");
            if (results == null) {
                return s.substring(0, RESULT_MAX) + "…（历史结果已压缩）";
            }
            JSONArray kept = new JSONArray();
            int total = 0;
            for (int i = 0; i < results.length(); i++) {
                JSONObject item = results.optJSONObject(i);
                if (item == null) continue;
                String one = item.toString();
                if (total + one.length() > RESULT_MAX) break;
                kept.put(item);
                total += one.length();
            }
            int dropped = results.length() - kept.length();
            JSONObject out = new JSONObject();
            out.put("query", json.optString("query", ""));
            out.put("results", kept);
            if (dropped > 0) out.put("truncated", "另有 " + dropped + " 条结果未列出");
            return out.toString();
        } catch (Exception e) {
            return s.substring(0, RESULT_MAX) + "…（历史结果已压缩）";
        }
    }

    /** 最后一条用户消息的原文，供搜索工具改写查询时参考。 */
    static String lastUserContent(List<Message> snapshot) {
        if (snapshot == null) return "";
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            Message m = snapshot.get(i);
            if (m != null && m.isUser() && m.content != null) return m.content;
        }
        return "";
    }
}
