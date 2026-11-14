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
        /**
         * 服务端返回的用量对象（prompt_tokens / completion_tokens / total_tokens），
         * 没请求或端点不支持时为 null。流式下只出现在最后一帧。
         */
        JSONObject usage;
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

    /**
     * 增量回调：每收到一片就回调一次，<b>由调用方节流</b>。
     *
     * <p>为什么不在这里节流：这里只能传 {@code String}，而 {@code display.toString()}
     * 是一次全串复制。3 万字回答按 100ms 节流要复制上千次，其中大半会被下游
     * {@link StreamRenderer} 的 120/200ms 门槛丢掉——copy 做了，帧没画。
     * 传 {@code CharSequence} 让调用方在「确实要画」的那一刻再复制。
     */
    interface Progress {
        void onProgress(CharSequence display);
    }

    interface Abort {
        boolean isAborted();
    }

    private SseRound() {
    }

    static Result read(boolean stream, InputStream in, Progress progress, Abort abort)
            throws Exception {
        return read(stream, in, progress, abort, null);
    }

    /**
     * 同 {@link #read(boolean, InputStream, Progress, Abort)}，但把「读了一半的 Result」
     * 通过 {@code partialOut} 交出去。
     *
     * <p>为什么需要：流式读取中途抛 IOException（超时、连接被重置）时，本轮已经
     * 解析并推到屏幕上的文本都在这个 Result 里。不交出来的话，异常一抛它就跟着
     * 被回收，调用方只能拿「本轮之前」的 trace 收尾——用户眼看着答了一半的内容
     * 被清空，落盘也少一截。
     */
    static Result read(boolean stream, InputStream in, Progress progress, Abort abort,
            Result[] partialOut) throws Exception {
        Result r = new Result();
        if (partialOut != null) partialOut[0] = r;
        if (stream) readStream(in, progress, abort, r);
        else readWhole(in, r);
        // tool_calls 的 arguments 是分片拼接的，流中途断开会留下半截 JSON；下游
        // ToolExecutor.parseArgs 解析失败会静默返回空对象照常执行，等于把半截参数
        // 当完整参数跑工具。落库/执行前统一校验，不完整整轮按错误走错误卡片链路。
        if (r.error == null && r.toolCalls.length() > 0) validateToolArguments(r);
        return r;
    }

    /**
     * 从「HTTP 200 + JSON 错误体」里取出可读的错误信息，不是错误体返回 null。
     *
     * <p>覆盖三种常见形态：
     * <ul>
     *   <li>{@code {"error":{"message":"Insufficient Balance","type":"..."}}}（OpenAI 系）</li>
     *   <li>{@code {"error":"invalid api key"}}（部分中转）</li>
     *   <li>{@code {"message":"..."}}（部分网关直出）</li>
     * </ul>
     */
    static String errorMessageOf(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.length() == 0 || t.charAt(0) != '{') return null;
        try {
            JSONObject o = new JSONObject(t);
            Object err = o.opt("error");
            if (err == null || err == JSONObject.NULL) err = o.opt("message");
            if (err == null || err == JSONObject.NULL) return null;
            if (err instanceof JSONObject) {
                JSONObject eo = (JSONObject) err;
                String msg = eo.optString("message", "");
                if (msg.length() == 0) msg = eo.optString("type", "");
                if (msg.length() == 0) msg = eo.optString("code", "");
                return msg.length() == 0 ? null : msg;
            }
            String s = String.valueOf(err).trim();
            return s.length() == 0 ? null : s;
        } catch (Throwable t2) {
            return null;
        }
    }

    /** 每个待执行 tool_call 的 arguments 必须至少是一段语法完整的 JSON。 */
    private static void validateToolArguments(Result r) {
        for (int i = 0; i < r.toolCalls.length(); i++) {
            JSONObject tc = r.toolCalls.optJSONObject(i);
            // name 为空的调用不会被 appendAssistant / ToolExecutor 执行，无需校验
            if (tc == null || tc.optString("name", "").length() == 0) continue;
            String args = tc.optString("arguments", "");
            // 与 appendAssistant / parseArgs 一致：空参等价于 {}
            if (args.trim().length() == 0) continue;
            try {
                // 只拦「半截 JSON」。合法但非对象（null/[]/标量）保持原行为：下游
                // ToolExecutor.parseArgs 解析失败会静默当 {}，不能在这里误判成整轮错误。
                new org.json.JSONTokener(args).nextValue();
            } catch (Exception e) {
                LogUtil.e(TAG, "tool_call#%d arguments incomplete: %s",
                        i, LogUtil.preview(args, 200));
                r.error = "工具调用参数不完整";
                r.cause = new Exception("tool_call arguments truncated: "
                        + LogUtil.preview(args, 200));
                return;
            }
        }
    }

    /** 非流式：整个响应就是一个 JSON 对象。 */
    private static void readWhole(InputStream in, Result r) throws Exception {
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        try {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        } finally {
            reader.close();
        }
        r.rawBody = ApiClient.sanitizeJson(sb.toString());

        JSONObject json;
        try {
            json = new JSONObject(r.rawBody);
        } catch (Throwable te) {
            // 非 JSON 的响应体（网关 HTML 错误页、纯文本限流提示）不能让它以
            // JSONException 的形式冒到上层：那样错误卡片只有一句 "Expected ..."，
            // 而真正的线索就在响应体里。
            String apiMsg = errorMessageOf(r.rawBody);
            r.error = apiMsg != null ? apiMsg : "响应解析失败";
            r.cause = new Exception("非流式响应不是 JSON："
                    + LogUtil.preview(r.rawBody, 160));
            LogUtil.e(TAG, "non-stream response is not JSON");
            return;
        }
        r.usage = json.optJSONObject("usage");
        JSONArray choices = json.optJSONArray("choices");
        JSONObject message = choices != null && choices.length() > 0
                ? choices.getJSONObject(0).optJSONObject("message") : null;
        if (message == null) {
            String apiMsg = errorMessageOf(r.rawBody);
            if (apiMsg != null) {
                // HTTP 200 + 错误体（余额不足、key 无效、模型下线）：把原话交给用户
                r.error = apiMsg;
                r.cause = new Exception("非流式响应是错误体");
                LogUtil.e(TAG, "non-stream body is an error object: %s",
                        LogUtil.preview(apiMsg, 200));
                return;
            }
            LogUtil.e(TAG, "non-stream response missing choices[0].message");
            r.error = "响应解析失败";
            r.cause = new Exception("非流式响应缺少 choices[0].message");
            return;
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
            return;
        }
        LogUtil.i(TAG, "non-stream: raw=%d content=%d tools=%d",
                sb.length(), content.length(), r.toolCalls.length());
    }

    /** 流式：逐行读 SSE，只在 data: 行上取 JSON。 */
    private static void readStream(InputStream in, Progress progress, Abort abort, Result r)
            throws Exception {
        boolean thinkingActive = false;
        boolean thinkingFinished = false;

        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        // 头几行原文先留着：有些网关/代理会返回 HTTP 200 + 一条 JSON 错误体
        // （{"error":{"message":"Insufficient Balance"}}），没有任何 data: 行。
        // 原来这种情况只报「服务端没有返回任何内容」，把唯一有用的那句原因丢了。
        // 只在还没见到任何 data 行时累积，正常 SSE 一进来就停手，零额外开销。
        StringBuilder head = new StringBuilder();
        try {
            String line;
            while (!abort.isAborted() && (line = reader.readLine()) != null) {
                line = line.trim();
                // SSE 规范里冒号后的空格可选，兼容 "data:" 与 "data: "
                if (!line.startsWith("data:")) {
                    if (line.length() > 0) {
                        if (r.chunks == 0 && head.length() < 4000) head.append(line).append('\n');
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

                JSONObject json;
                try {
                    json = new JSONObject(data);
                } catch (Exception e) {
                    LogUtil.w(TAG, "failed to parse SSE chunk#%d: %s | raw=%s",
                            r.chunks, e.getMessage(), LogUtil.preview(data, 300));
                    continue;
                }
                // usage 必须在 choices 判空之前取：OpenAI 的最后一帧是
                // {"choices":[],"usage":{...}}，先 continue 掉就永远读不到。
                JSONObject usageObj = json.optJSONObject("usage");
                if (usageObj != null) r.usage = usageObj;

                JSONArray choices = json.optJSONArray("choices");
                if (choices == null || choices.length() == 0) {
                    // 有些中转/网关把错误体包成 SSE data 行下发：
                    //   data: {"error":{"message":"Insufficient Balance"}}
                    // 此时 r.chunks 已经 +1，走不到末尾 chunks==0 的 JSON 错误体兜底，
                    // 又没有任何 choices/正文 —— 会被上层当成「成功但内容为空」，
                    // 收尾时用空内容覆盖气泡，用户看到回答凭空消失，且拿不到原因。
                    // 只在「无 choices 的 data 行」上识别错误对象，正常 chunk 与
                    // usage-only 末帧（{"choices":[],"usage":{...}}）都不受影响。
                    String apiMsg = errorMessageOf(data);
                    if (apiMsg != null) {
                        r.error = apiMsg;
                        r.rawBody = data;
                        r.cause = new Exception("SSE data line carries error object");
                        LogUtil.e(TAG, "SSE chunk#%d is an error object: %s",
                                r.chunks, LogUtil.preview(apiMsg, 200));
                        return;   // finally 会照常收口 thinking、关 reader
                    }
                    LogUtil.v(TAG, "chunk#%d has no choices: %s",
                            r.chunks, LogUtil.preview(data, 200));
                    continue;
                }
                JSONObject first = choices.optJSONObject(0);
                JSONObject delta = first == null ? null : first.optJSONObject("delta");
                if (delta == null) {
                    LogUtil.v(TAG, "chunk#%d has no delta", r.chunks);
                    continue;
                }

                // 从这里往下不再用 catch 兜住：解析异常应当让本轮失败并保留已输出的
                // 内容，而不是被当成「坏分片」跳过——那样工具调用会静默丢失，
                // 界面上只看到模型「想了半天没动静」。
                if (delta.has("reasoning_content") && !delta.isNull("reasoning_content")) {
                    String rc = delta.optString("reasoning_content", "");
                    if (rc.length() > 0) {
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
                }

                if (delta.has("content") && !delta.isNull("content")) {
                    String ct = delta.optString("content", "");
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

                if (progress != null) progress.onProgress(r.display);
            }
        } finally {
            // 半截思考块必须在这里收口，而不是放到 try 之后：读取中途抛 IOException
            // （超时、连接重置）时 try 之后的一段根本不会执行，未闭合的 [thinking]
            // 会让调用方把之后的正文整段当成思考内容渲染。正常结束时下面的判断
            // 因 thinkingFinished 已置位而自动跳过。
            if (thinkingActive && !thinkingFinished) {
                thinkingFinished = true;
                r.display.append("[/thinking]");
                LogUtil.d(TAG, "thinking left open at stream end: closed with [/thinking]");
            }
            try {
                reader.close();
            } catch (Exception ignored) {
                // 已经读到断了，close 再抛也没人处理：真正的错误由上面的 readLine 抛出
            }
        }
        // 空流必须当失败：200 + 一个 data 都没有（网关超时、被中间层吞掉、配额耗尽）
        // 以前会被当成「成功但内容为空」，收尾时用空内容覆盖气泡，用户看到回答凭空消失。
        if (r.chunks == 0) {
            String body = head.toString();
            String apiMsg = errorMessageOf(body);
            if (apiMsg != null) {
                // 200 + JSON 错误体：把服务端原话交给用户，别再报「没有返回任何内容」
                r.error = apiMsg;
                r.rawBody = body;
                r.cause = new Exception("HTTP 200 with JSON error body");
                LogUtil.e(TAG, "stream body is a JSON error object: %s",
                        LogUtil.preview(apiMsg, 200));
                return;
            }
            r.error = "服务端没有返回任何内容";
            if (body.length() > 0) r.rawBody = body;
            r.cause = new Exception("empty SSE stream: no data line received");
            LogUtil.e(TAG, "stream produced no chunks at all");
            return;
        }
        if (r.display.length() == 0 && r.toolCalls.length() == 0) {
            r.error = "响应解析失败";
            r.cause = new Exception("stream has neither text nor tool_calls");
            LogUtil.e(TAG, "stream finished without text or tool calls (chunks=%d)", r.chunks);
            return;
        }
        LogUtil.i(TAG, "stream finished: chunks=%d tools=%d contentLen=%d displayLen=%d",
                r.chunks, r.toolCalls.length(), r.content.length(), r.display.length());
    }
}
