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
        int total = calls == null ? 0 : calls.length();
        for (int i = 0; i < total; i++) {
            // 打断后立刻停手：原来只在每轮末尾检查 isAlive，用户点了停止仍会把
            // 剩下的工具跑完；而工具内部会因 guard 直接返回 null，被当成
            // 「未配置搜索密钥」写进结果并弹提示，误导用户去改设置。
            if (!host.isAlive()) {
                LogUtil.i("ToolExecutor", "aborted before tool %d/%d", i + 1, total);
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
            } else {
                result = host.execute(name, args, question);
                if (result == null) {
                    result = host.unavailableResult();
                    host.onUnavailable();
                }
            }

            WebSearchTool.extractSources(result, sources);
            ToolCalls.appendResult(conversation, toolHistory, id, result);
            // 所有工具都打卡片：时间工具此前被跳过，界面上完全看不到
            trace.append(WebSearchTool.buildTraceBlock(name, args, result));
            if (TraceKit.cap(trace)) host.onTraceTrimmed();
            host.onTraceAppended(trace.toString());

            if (!host.isAlive()) {
                return new Outcome(executed, skipped, true);
            }
        }
        return new Outcome(executed, skipped, false);
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
