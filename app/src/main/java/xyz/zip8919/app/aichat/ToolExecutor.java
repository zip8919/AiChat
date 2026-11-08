package xyz.zip8919.app.aichat;

import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 工具轮次的执行器：把模型返回的一批 {@code tool_calls} 变成
 * 「conversation 里的 tool 结果 + toolHistory 里的回放记录 + trace 里的卡片」。
 *
 * <p>从 {@code MainActivity.sendStreamingRequest} 的 while 循环里抽出。原先这段嵌在
 * 四层缩进中，与 HTTP、SSE、UI 更新混在一起，改一行「上限怎么算」要通读 500 行。
 *
 * <p>三条不变量，改这里时先确认没破坏：
 * <ol>
 *   <li><b>每个 tool_call 都必须有结果</b>。哪怕超限或参数非法，也要写回一条占位结果，
 *       否则下一轮请求的 assistant/tool 配对不完整，多数 OpenAI 兼容端点直接 400。</li>
 *   <li><b>超限的调用不执行但要有记录</b>，用户能在卡片上看到「因超出上限未执行」。</li>
 *   <li><b>工具结果先进 conversation 再进 trace</b>，顺序反了会导致卡片上的结果
 *       与真正回传给模型的内容不一致，排查时会被误导。</li>
 * </ol>
 */
final class ToolExecutor {

    /** 宿主能力：执行单个工具 + 通知界面。 */
    interface Host {
        /** 请求是否仍然有效（未被打断、未被新请求取代）。 */
        boolean isAlive();

        /**
         * 执行一个工具，返回结果 JSON 字符串。
         * 返回 {@code null} 表示当前不具备执行能力（例如未配置搜索密钥），
         * 此时用 {@link #unavailableResult()} 作为结果。
         */
        String execute(String name, JSONObject args, String question);

        /** 不具备执行能力时写回给模型的错误 JSON。 */
        String unavailableResult();

        /**
         * 当前模型是否支持多模态输入。开启后，图片搜索的结果会以
         * {@code image_url} 块追加到请求里，模型能真正看到图像。
         */
        boolean visionEnabled();

        /**
         * 把远端图片下载并编码成 data URI；失败返回 null。
         *
         * <p>用 data URI 而不是把链接直接塞给端点：国内模型访问不了境外图床，
         * 结果是「图没传上去、模型还说看不到」，比直接失败更难排查。
         */
        String imageDataUri(String url);

        /** 首次遇到「不具备执行能力」时提示用户，实现方自行去重。 */
        void onUnavailable();

        /** 开始执行某个工具，例如「正在搜索 xxx」。 */
        void onStatus(String text);

        /** 一次工具执行完毕、卡片已追加到 trace 后回调，arg 是追加后的完整 trace。 */
        void onTraceAppended(String traceSoFar);

        /**
         * trace 因超长被裁掉了前缀。调用方必须重置「base 已推送」的标记，
         * 否则下一次渲染会因为文本变短而被当成过期快照丢掉。
         */
        void onTraceTrimmed();
    }

    static final class Outcome {
        /** 真正执行了的工具数量（不含超限跳过）。 */
        final int executed;
        /** 因单轮上限被跳过、只写了占位结果的数量。 */
        final int skipped;
        /** 执行过程中请求被打断。 */
        final boolean aborted;

        Outcome(int executed, int skipped, boolean aborted) {
            this.executed = executed;
            this.skipped = skipped;
            this.aborted = aborted;
        }
    }

    private ToolExecutor() {
    }

    /** 单轮最多注入几张图片到请求体。 */
    static final int MAX_INJECTED_IMAGES = 2;

    /**
     * 执行一批工具调用。
     *
     * @param calls       模型返回的 tool_calls（已由 {@link ToolCalls#hasUsable} 判过非空）
     * @param maxPerRound 单轮执行上限，超出部分写占位结果
     * @param conversation 发给模型的对话数组，会被原地追加 assistant/tool 消息
     * @param toolHistory 落盘用的工具历史，与 conversation 同步追加
     * @param trace       界面卡片文本，会被原地追加
     * @param sources     搜索来源，按 url 去重由调用方负责
     */
    static Outcome run(JSONArray calls, int maxPerRound, JSONArray conversation,
            JSONArray toolHistory, StringBuilder trace, List<String[]> sources,
            String question, Host host) throws Exception {
        int executed = 0;
        int skipped = 0;
        /** 首个图片工具的原始结果。整轮 tool 消息补齐后才注入，见下方注释。 */
        String visionSource = null;
        int total = calls == null ? 0 : calls.length();
        for (int i = 0; i < total; i++) {
            // 打断后立刻停手：原来只在每轮末尾检查 isAlive，用户点了停止仍会把
            // 剩下的工具跑完；而工具内部会因 guard 直接返回 null，被当成
            // 「未配置搜索密钥」写进结果并弹提示，误导用户去改设置。
            if (!host.isAlive()) {
                LogUtil.i("ToolExecutor", "aborted before tool %d/%d", i + 1, total);
                // 中止也必须把剩下每个 tool_call_id 补上结果：assistant(tool_calls)
                // 后面少一条 tool 消息，下一次请求就是 400
                // （insufficient tool messages following tool_calls message）。
                backfill(conversation, toolHistory, calls, i, ToolCalls.abortNotice());
                return new Outcome(executed, skipped, true);
            }
            JSONObject call = calls.optJSONObject(i);
            if (call == null) continue;
            String id = call.optString("id", "");
            String name = call.optString("name", "");
            if (name.length() == 0) continue;

            if (executed >= maxPerRound) {
                // 超限也要回结果：配对不完整会让下一轮请求直接 400
                ToolCalls.appendResult(conversation, toolHistory, id,
                        ToolCalls.limitNotice(maxPerRound));
                // 同时要进 trace，否则界面上看不出「有个调用没执行」
                String block = ToolCalls.limitTraceBlock(maxPerRound);
                if (block.length() > 0) {
                    trace.append(block);
                    if (TraceKit.cap(trace)) host.onTraceTrimmed();
                    host.onTraceAppended(trace.toString());
                }
                skipped++;
                LogUtil.w("ToolExecutor",
                        "tool %d/%d not executed: per-round limit %d reached (name=%s)",
                        i + 1, total, maxPerRound, name);
                continue;
            }
            executed++;

            JSONObject args = parseArgs(call.optString("arguments", ""));
            host.onStatus(ToolCalls.statusText(name, args));

            String result;
            if (WebSearchTool.TOOL_TIME.equals(name)) {
                // 本地工具：不依赖搜索密钥，也不受联网开关影响
                result = WebSearchTool.currentTimeResult();
            } else if (HttpRequestTool.TOOL_HTTP.equals(name)) {
                // 本机直连：同样不依赖搜索 provider。工具定义只在设置开启时才发给模型，
                // 走到这里说明用户已经显式启用过。
                result = HttpRequestTool.execute(args);
            } else {
                result = host.execute(name, args, question);
                if (result == null) {
                    result = host.unavailableResult();
                    host.onUnavailable();
                }
            }

            // 统计工具调用：错误结果统一是 {"error":...}，据此判失败。
            // 只看有没有抛异常会把「搜索密钥没配」这类软失败算成成功。
            boolean ok = result != null && !result.trim().startsWith("{\"error\"");
            UsageStats.get().onTool(name, ok);

            WebSearchTool.extractSources(result, sources);
            ToolCalls.appendResult(conversation, toolHistory, id, result);
            // 视觉注入**不能**在这里立刻做：超限的调用（下面 i 更大的那几条）还没补
            // tool 结果，此时插一条 user 消息就会形成
            //   assistant(tool_calls) → tool, tool, tool → user → tool, tool
            // 这种顺序，端点直接 400
            // （An assistant message with 'tool_calls' must be followed by tool messages
            //   responding to each 'tool_call_id'）。
            // 改成记下首个带图的工具结果，等整轮 tool 消息全部补齐后再注入。
            if (visionSource == null && host.visionEnabled() && hasImages(result)) {
                visionSource = result;
            }
            // 所有工具都打卡片：时间工具此前被跳过，界面上完全看不到
            trace.append(WebSearchTool.buildTraceBlock(name, args, result));
            if (TraceKit.cap(trace)) host.onTraceTrimmed();
            host.onTraceAppended(trace.toString());

            if (!host.isAlive()) {
                backfill(conversation, toolHistory, calls, i + 1, ToolCalls.abortNotice());
                return new Outcome(executed, skipped, true);
            }
        }
        // 整轮 tool 消息已齐全，此时插入多模态 user 消息才是合法顺序：
        // assistant(tool_calls) → tool × N → user(图片)
        if (visionSource != null) injectImages(conversation, visionSource, host);
        return new Outcome(executed, skipped, false);
    }

    /** 结果里是否带 {@code images} 数组（图片搜索工具的标志）。 */
    private static boolean hasImages(String result) {
        if (result == null || result.length() < 2) return false;
        try {
            org.json.JSONArray imgs = new org.json.JSONObject(result).optJSONArray("images");
            return imgs != null && imgs.length() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 给 {@code from} 起尚未回结果的调用补占位 tool 消息。
     *
     * <p>只在「中止」路径用：用户点了停止后剩下的调用不再执行，但 assistant 消息里
     * 已经有全部 tool_call_id，少一条 tool 回复就是下一次请求的 400。
     */
    private static void backfill(JSONArray conversation, JSONArray toolHistory,
                                 JSONArray calls, int from, String content) {
        if (calls == null) return;
        for (int i = Math.max(0, from); i < calls.length(); i++) {
            JSONObject c = calls.optJSONObject(i);
            if (c == null) continue;
            if (c.optString("name", "").length() == 0) continue;
            String id = c.optString("id", "");
            try {
                ToolCalls.appendResult(conversation, toolHistory, id, content);
            } catch (Exception e) {
                LogUtil.w("ToolExecutor", "backfill failed: %s", e.getMessage());
            }
        }
    }

    /**
     * 把图片搜索结果装成一条多模态 user 消息，接在 tool 结果之后。
     *
     * <p>四个刻意的限制（都是为了不浪费 token 与请求体）：
     * <ul>
     *   <li>最多 {@value #MAX_INJECTED_IMAGES} 张——搜索结果里的其余图只留在工具结果的
     *       URL 列表里（纯文本，几乎不占 token），不进图像通道；</li>
     *   <li>注入的图按 {@link ImageTokens#apiSide} 的像素预算编码（≤1302²），
     *       每张最多约 994 token——预算外的像素端点也会缩掉，多发纯属浪费请求体；</li>
     *   <li>整轮只注入一次——模型有时会连搜三次，第二次再塞两张就是六张图；</li>
     *   <li>一张都下不下来就不注入，退回纯文本（模型至少还有链接与描述可用）。</li>
     * </ul>
     */
    private static boolean injectImages(JSONArray conversation, String result, Host host) {
        if (conversation == null || result == null || result.length() < 2) return false;
        JSONArray images;
        try {
            JSONObject json = new JSONObject(result);
            images = json.optJSONArray("images");
        } catch (Exception e) {
            return false;
        }
        if (images == null || images.length() == 0) return false;

        JSONArray parts = new JSONArray();
        try {
            JSONObject text = new JSONObject();
            text.put("type", "text");
            text.put("text", "以下是刚才图片搜索结果中的前 "
                    + Math.min(images.length(), MAX_INJECTED_IMAGES)
                    + " 张图片（已压缩），请结合图像内容回答用户的问题。"
                    + "其余图片只提供了链接；如果图片与问题无关，请忽略并在回答中说明。");
            parts.put(text);

            int added = 0;
            for (int i = 0; i < images.length() && added < MAX_INJECTED_IMAGES; i++) {
                JSONObject item = images.optJSONObject(i);
                if (item == null) continue;
                String url = item.optString("image_url", "");
                if (url.length() == 0) continue;
                if (!host.isAlive()) break;
                String uri = host.imageDataUri(url);
                if (uri == null || uri.length() == 0) {
                    LogUtil.w("ToolExecutor", "vision: image fetch failed, skipped: %s",
                            LogUtil.preview(url, 120));
                    continue;
                }
                JSONObject img = new JSONObject();
                img.put("type", "image_url");
                JSONObject u = new JSONObject();
                u.put("url", uri);
                img.put("image_url", u);
                parts.put(img);
                added++;
            }

            if (added == 0) return false;
            JSONObject msg = new JSONObject();
            msg.put("role", "user");
            msg.put("content", parts);
            conversation.put(msg);
            LogUtil.i("ToolExecutor", "vision: injected %d image(s)", added);
            return true;
        } catch (Exception e) {
            LogUtil.w("ToolExecutor", "injectImages failed: %s", e.getMessage());
            return false;
        }
    }

    /** 参数解析失败时按空对象处理：模型偶尔会截断 arguments，不能因此让整轮失败。 */
    static JSONObject parseArgs(String argText) throws Exception {
        if (argText == null || argText.trim().length() == 0) return new JSONObject();
        try {
            return new JSONObject(argText);
        } catch (Exception e) {
            LogUtil.w("ToolExecutor", "tool args parse failed: %s | raw=%s",
                    e.getMessage(), LogUtil.preview(argText, 200));
            return new JSONObject();
        }
    }
}
