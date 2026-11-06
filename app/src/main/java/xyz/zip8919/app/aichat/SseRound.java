package xyz.zip8919.app.aichat;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 单轮响应解析：把 OpenAI 兼容端点的「非流式 JSON」或「SSE 分片」统一读成
 * 展示文本、正文、tool_calls 三样东西。
 *
 * <p>从 MainActivity 的请求线程里抽出（约 230 行）。这里只做协议层的事，
 * 不认识 Message、不碰 WebView、不判断重试策略——因此可以脱离 Android 单测：
 * 喂一段假 SSE 字节流，断言产出的 display/content/toolCalls 即可。
 *
 * <p>两个容易踩的点，都在这里收口：
 * <ul>
 *   <li>{@code content:""} 会跟在每个 reasoning 分片后面。只有<b>非空</b> content
 *       才算思考阶段结束，否则「展开思考（N 字）」会停在一两个字上；</li>
 *   <li>工具调用是分片累积的（id/name 只在第一片，arguments 逐片拼接），
 *       必须走 {@link ToolCalls#accumulate} 而不是直接取最后一片。</li>
 * </ul>
 */
final class SseRound {

    private static final String TAG = "SseRound";

    /** 解析结果。display 含 [thinking]/[tool] 展示块，content 是纯正文。 */
    static final class Result {
        final StringBuilder display = new StringBuilder();
        final StringBuilder content = new StringBuilder();
        /**
         * 纯推理文本（不含 [thinking] 标记）。
         * DeepSeek thinking 模式要求 assistant(tool_calls) 把这一轮的 reasoning_content
         * 原样传回，否则后续轮次直接 400；展示用的 display 带标记，不能拿来回传。
         */
        final StringBuilder reasoning = new StringBuilder();
        final JSONArray toolCalls = new JSONArray();
        int chunks;
        int thinkingChunks;
        int contentChunks;

        /** 协议层失败（无 choices/无 message/既无正文也无工具调用），非 null 即失败。 */
        String error;
        /** 非流式失败时保留原始响应体，用于错误卡片。 */
        String rawBody;
        int httpCode = 200;
        Exception cause;
    }

    /** 增量回调：仅在文本真的变化且距上次回调至少 100ms 时触发，避免每片都做 toString。 */
    interface Progress {
        void onProgress(String display, String content);
    }

    interface Abort {
        boolean isAborted();
    }

    private SseRound() {
    }

    static Result read(boolean stream, InputStream in, Progress progress, Abort abort)
            throws Exception {
        return stream ? readStream(in, progress, abort) : readWhole(in);
    }

    /** 非流式：整个响应就是一个 JSON 对象。 */
    private static Result readWhole(InputStream in) throws Exception {
        Result r = new Result();
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        try {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        } finally {
            reader.close();
        }
        r.rawBody = sb.toString();

        JSONObject json = new JSONObject(r.rawBody);
        JSONArray choices = json.optJSONArray("choices");
        JSONObject message = choices != null && choices.length() > 0
                ? choices.getJSONObject(0).optJSONObject("message") : null;
        if (message == null) {
            LogUtil.e(TAG, "non-stream response missing choices[0].message");
            r.error = "响应解析失败";
            r.cause = new Exception("非流式响应缺少 choices[0].message");
            return r;
        }

        String reasoning = message.has("reasoning_content") && !message.isNull("reasoning_content")
                ? message.optString("reasoning_content", "") : "";
        if (reasoning.length() > 0) {
            r.display.append("[thinking]").append(reasoning).append("[/thinking]");
            r.thinkingChunks++;
            r.reasoning.append(reasoning);
        }
        String content = message.has("content") && !message.isNull("content")
                ? message.optString("content", "") : "";
        r.display.append(content);
        r.content.append(content);
        if (content.length() > 0) r.contentChunks++;
        JSONArray rawCalls = message.optJSONArray("tool_calls");
        if (rawCalls != null) ToolCalls.normalize(rawCalls, r.toolCalls);

        if (content.length() == 0 && reasoning.length() == 0 && r.toolCalls.length() == 0) {
            LogUtil.e(TAG, "non-stream response has no content and no tool calls");
            r.error = "响应解析失败";
            r.cause = new Exception("响应既无 content 也无 tool_calls");
            return r;
        }
        LogUtil.i(TAG, "non-stream: raw=%d content=%d tools=%d",
                sb.length(), content.length(), r.toolCalls.length());
        return r;
    }

    /** 流式：逐行读 SSE，只在 data: 行上取 JSON。 */
    private static Result readStream(InputStream in, Progress progress, Abort abort)
            throws Exception {
        Result r = new Result();
        boolean thinkingActive = false;
        boolean thinkingFinished = false;
        long lastEmit = 0;

        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        try {
            String line;
            while (!abort.isAborted() && (line = reader.readLine()) != null) {
                line = line.trim();
                // SSE 规范里冒号后的空格可选，兼容 "data:" 与 "data: "
                if (!line.startsWith("data:")) {
                    if (line.length() > 0) {
                        LogUtil.v(TAG, "skip non-data line: %s", LogUtil.preview(line, 120));
                    }
                    continue;
                }
                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) {
                    LogUtil.i(TAG, "SSE [DONE] after %d chunks", r.chunks);
                    break;
                }
                r.chunks++;

                try {
                    JSONObject json = new JSONObject(data);
                    JSONArray choices = json.optJSONArray("choices");
                    if (choices == null || choices.length() == 0) {
                        LogUtil.v(TAG, "chunk#%d has no choices: %s",
                                r.chunks, LogUtil.preview(data, 200));
                        continue;
                    }
                    JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
                    if (delta == null) {
                        LogUtil.v(TAG, "chunk#%d has no delta", r.chunks);
                        continue;
                    }

                    if (delta.has("reasoning_content") && !delta.isNull("reasoning_content")) {
                        String rc = delta.getString("reasoning_content");
                        r.thinkingChunks++;
                        if (!thinkingActive) {
                            thinkingActive = true;
                            r.display.append("[thinking]");
                            LogUtil.d(TAG, "thinking phase START at chunk#%d", r.chunks);
                        }
                        r.display.append(rc);
                        r.reasoning.append(rc);
                        if (LogUtil.ENABLED) {
                            LogUtil.v(TAG, "reasoning chunk#%d: %s",
                                    r.thinkingChunks, LogUtil.preview(rc, 150));
                        }
                    }

                    if (delta.has("content") && !delta.isNull("content")) {
                        String ct = delta.getString("content");
                        r.contentChunks++;
                        // 非空 content 才算思考结束：API 在每个 reasoning 分片后都会
                        // 附一个 "content":""，按 has() 判断会立刻结束思考阶段
                        if (ct.length() > 0 && thinkingActive && !thinkingFinished) {
                            thinkingFinished = true;
                            r.display.append("[/thinking]");
                            LogUtil.d(TAG, "thinking phase END at chunk#%d (chunks=%d)",
                                    r.chunks, r.thinkingChunks);
                        }
                        r.display.append(ct);
                        r.content.append(ct);
                        if (LogUtil.ENABLED) {
                            LogUtil.v(TAG, "content chunk#%d: %s",
                                    r.contentChunks, LogUtil.preview(ct, 150));
                        }
                    }

                    JSONArray deltaCalls = delta.optJSONArray("tool_calls");
                    if (deltaCalls != null && deltaCalls.length() > 0) {
                        ToolCalls.accumulate(r.toolCalls, deltaCalls);
                    }

                    if (progress != null) {
                        long now = System.currentTimeMillis();
                        if (now - lastEmit > 100) {
                            lastEmit = now;
                            progress.onProgress(r.display.toString(), r.content.toString());
                        }
                    }
                } catch (Exception e) {
                    LogUtil.w(TAG, "failed to parse SSE chunk#%d: %s | raw=%s",
                            r.chunks, e.getMessage(), LogUtil.preview(data, 300));
                }
            }
        } finally {
            reader.close();
        }

        if (thinkingActive && !thinkingFinished) {
            r.display.append("[/thinking]");
            LogUtil.d(TAG, "thinking left open at stream end: closed with [/thinking]");
        }
        LogUtil.i(TAG, "stream finished: chunks=%d tools=%d contentLen=%d displayLen=%d",
                r.chunks, r.toolCalls.length(), r.content.length(), r.display.length());
        return r;
    }
}
