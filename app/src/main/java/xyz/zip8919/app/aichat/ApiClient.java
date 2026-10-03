package xyz.zip8919.app.aichat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.json.JSONArray;
import org.json.JSONObject;

public class ApiClient {

    private static final String TAG = "ApiClient";

    public interface StreamCallback {
        void onContent(String text);
        void onThinking(String thinkingText);
        void onComplete();
        void onError(String error);
    }

    public static class CallResult {
        public String response;  // non-null on success
        public String error;     // non-null on failure (includes HTTP code + body)
    }

    /**
     * Non-streaming chat completion with detailed error info.
     */
    public static CallResult callWithError(ProviderInfo provider, String model,
            List<Message> messages, String systemPrompt, String thinkingLevel) {

        CallResult result = new CallResult();
        LogUtil.i(TAG, "callWithError -> model=%s provider=%s stream=false", model, provider.name);
        try {
            JSONObject body = buildRequestBody(model, messages, systemPrompt, thinkingLevel,
                    provider.thinkingType, provider.thinkingParamName, false);
            // 512: models that ignore all thinking-off switches burn tokens on
            // reasoning first; 50 left no room for the actual title (empty content)
            body.put("max_tokens", 512);
            LogUtil.v(TAG, "callWithError body(%d): %s", body.toString().length(), LogUtil.preview(body.toString(), 1200));
            result = doRequestWithError(provider.apiUrl + provider.chatPath, provider.apiKey, body, 20000);
        } catch (Exception e) {
            LogUtil.e(TAG, "callWithError build/request exception: " + e.getMessage(), e);
            result.error = e.getMessage();
        }
        LogUtil.i(TAG, "callWithError <- ok=%s response=%s error=%s",
                (result != null && result.response != null),
                (result != null && result.response != null) ? LogUtil.preview(result.response, 500) : "null",
                (result != null && result.error != null) ? LogUtil.preview(result.error, 500) : "null");
        return result;
    }

    /**
     * Non-streaming chat completion.
     * @return full response JSON string, or null on error
     */
    public static String call(ProviderInfo provider, String model,
            List<Message> messages, String systemPrompt,
            String thinkingLevel) throws Exception {

        JSONObject body = buildRequestBody(model, messages, systemPrompt, thinkingLevel,
                provider.thinkingType, provider.thinkingParamName, false);
        LogUtil.d(TAG, "call -> %s%s model=%s bodyLen=%d", provider.apiUrl, provider.chatPath, model, body.toString().length());

        return doRequest(provider.apiUrl + provider.chatPath, provider.apiKey, body, 60000);
    }

    /**
     * Streaming chat completion with SSE parsing.
     */
    public static void callStream(ProviderInfo provider, String model,
            List<Message> messages, String systemPrompt,
            String thinkingLevel, AtomicBoolean runningFlag, StreamCallback callback) {

        HttpURLConnection conn = null;
        long startTs = System.currentTimeMillis();
        int chunks = 0;
        LogUtil.i(TAG, "callStream START -> %s%s model=%s thinking=%s (%s)",
                provider.apiUrl, provider.chatPath, model, thinkingLevel, LogUtil.thread());
        try {
            JSONObject body = buildRequestBody(model, messages, systemPrompt, thinkingLevel,
                    provider.thinkingType, provider.thinkingParamName, true);

            String urlStr = provider.apiUrl + provider.chatPath;
            LogUtil.d(TAG, "callStream opening connection: %s bodyLen=%d", urlStr, body.toString().length());
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + provider.apiKey);
            conn.setDoOutput(true);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(0); // no timeout for streaming

            if (conn instanceof HttpsURLConnection) {
                setupTLS((HttpsURLConnection) conn);
            }

            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.close();

            int code = conn.getResponseCode();
            LogUtil.i(TAG, "callStream HTTP response: code=%d in %d ms", code, System.currentTimeMillis() - startTs);
            if (code != 200) {
                String errBody = "";
                try {
                    BufferedReader er = new BufferedReader(new InputStreamReader(conn.getErrorStream(), "UTF-8"));
                    StringBuilder esb = new StringBuilder();
                    String el;
                    while ((el = er.readLine()) != null) esb.append(el);
                    er.close();
                    errBody = esb.toString();
                } catch (Exception ignore) {}
                LogUtil.e(TAG, "callStream FAILED: HTTP %d, body=%s", code, LogUtil.preview(errBody, 800));
                callback.onError("HTTP " + code);
                return;
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
            LogUtil.d(TAG, "callStream SSE started");
            String line;
            while (runningFlag.get() && (line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("data: ")) {
                    String data = line.substring(6);
                    if ("[DONE]".equals(data)) {
                        LogUtil.d(TAG, "callStream received [DONE] after %d chunks", chunks);
                        break;
                    }
                    try {
                        JSONObject json = new JSONObject(data);
                        JSONArray choices = json.optJSONArray("choices");
                        if (choices != null && choices.length() > 0) {
                            JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
                            if (delta != null) {
                                if (delta.has("reasoning_content") && !delta.isNull("reasoning_content")) {
                                    callback.onThinking(delta.getString("reasoning_content"));
                                }
                                if (delta.has("content") && !delta.isNull("content")) {
                                    callback.onContent(delta.getString("content"));
                                }
                            } else {
                                LogUtil.v(TAG, "callStream chunk#%d: no delta object", chunks);
                            }
                        } else {
                            LogUtil.v(TAG, "callStream chunk#%d: no choices array", chunks);
                        }
                    } catch (Exception e) {
                        LogUtil.w(TAG, "callStream malformed chunk skipped: %s", LogUtil.preview(data, 200));
                    }
                    chunks++;
                }
            }
            reader.close();

            if (!runningFlag.get()) {
                LogUtil.i(TAG, "callStream INTERRUPTED after %d chunks", chunks);
                callback.onError("interrupted");
            } else {
                LogUtil.i(TAG, "callStream COMPLETE: %d chunks in %d ms", chunks, System.currentTimeMillis() - startTs);
                callback.onComplete();
            }
        } catch (Exception e) {
            LogUtil.e(TAG, "callStream EXCEPTION: " + e.getMessage(), e);
            callback.onError(e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
            LogUtil.i(TAG, "callStream END: elapsed=%d ms chunks=%d", System.currentTimeMillis() - startTs, chunks);
        }
    }

    /**
     * Query DeepSeek balance.
     * @return JSON string with balance info, or null on error
     */
    public static String queryBalance(ProviderInfo provider) {
        HttpURLConnection conn = null;
        long startTs = System.currentTimeMillis();
        String balanceUrl = provider.apiUrl + "/user/balance";
        LogUtil.i(TAG, "queryBalance -> %s (%s)", balanceUrl, LogUtil.thread());
        try {
            URL url = new URL(balanceUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + provider.apiKey);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);

            if (conn instanceof HttpsURLConnection) {
                setupTLS((HttpsURLConnection) conn);
            }

            int code = conn.getResponseCode();
            LogUtil.d(TAG, "queryBalance HTTP code=%d in %d ms", code, System.currentTimeMillis() - startTs);
            if (code != 200) {
                LogUtil.w(TAG, "queryBalance non-200: HTTP %d", code);
                return null;
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            LogUtil.i(TAG, "queryBalance <- len=%d body=%s", sb.length(), LogUtil.preview(sb.toString(), 500));
            return sb.toString();
        } catch (Exception e) {
            LogUtil.e(TAG, "queryBalance EXCEPTION: " + e.getMessage(), e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ---- private helpers ----

    private static String doRequest(String urlStr, String apiKey,
            JSONObject body, int readTimeout) throws Exception {
        HttpURLConnection conn = null;
        long startTs = System.currentTimeMillis();
        LogUtil.d(TAG, "doRequest -> %s (readTimeout=%d)", urlStr, readTimeout);
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setDoOutput(true);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(readTimeout);

            if (conn instanceof HttpsURLConnection) {
                setupTLS((HttpsURLConnection) conn);
            }

            String payload = body.toString();
            LogUtil.v(TAG, "doRequest body (%d chars): %s", payload.length(), LogUtil.preview(payload, 1500));
            OutputStream os = conn.getOutputStream();
            os.write(payload.getBytes("UTF-8"));
            os.close();

            int code = conn.getResponseCode();
            LogUtil.d(TAG, "doRequest HTTP code=%d in %d ms", code, System.currentTimeMillis() - startTs);
            if (code != 200) {
                LogUtil.e(TAG, "doRequest FAILED: HTTP %d", code);
                throw new Exception("HTTP " + code);
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            LogUtil.d(TAG, "doRequest <- %d chars in %d ms: %s", sb.length(),
                    System.currentTimeMillis() - startTs, LogUtil.preview(sb.toString(), 500));
            return sb.toString();
        } catch (Exception e) {
            LogUtil.e(TAG, "doRequest EXCEPTION: " + e.getMessage(), e);
            throw e;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static CallResult doRequestWithError(String urlStr, String apiKey,
            JSONObject body, int readTimeout) {
        CallResult result = new CallResult();
        HttpURLConnection conn = null;
        long startTs = System.currentTimeMillis();
        LogUtil.d(TAG, "doRequestWithError -> %s (readTimeout=%d)", urlStr, readTimeout);
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setDoOutput(true);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(readTimeout);

            if (conn instanceof HttpsURLConnection) {
                setupTLS((HttpsURLConnection) conn);
            }

            String payload = body.toString();
            LogUtil.v(TAG, "doRequestWithError body (%d chars): %s", payload.length(), LogUtil.preview(payload, 1500));
            OutputStream os = conn.getOutputStream();
            os.write(payload.getBytes("UTF-8"));
            os.close();

            int code = conn.getResponseCode();
            LogUtil.d(TAG, "doRequestWithError HTTP code=%d in %d ms", code, System.currentTimeMillis() - startTs);
            if (code != 200) {
                // Read error body
                try {
                    BufferedReader errReader = new BufferedReader(
                            new InputStreamReader(conn.getErrorStream(), "UTF-8"));
                    StringBuilder errBody = new StringBuilder();
                    String line;
                    while ((line = errReader.readLine()) != null) {
                        errBody.append(line);
                    }
                    errReader.close();
                    result.error = "HTTP " + code + ": " + errBody.toString();
                    LogUtil.e(TAG, "doRequestWithError FAILED: HTTP %d body=%s", code, LogUtil.preview(errBody.toString(), 800));
                } catch (Exception e) {
                    result.error = "HTTP " + code;
                    LogUtil.e(TAG, "doRequestWithError FAILED: HTTP %d (error body unreadable: %s)", code, e.getMessage());
                }
                return result;
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            result.response = sb.toString();
            LogUtil.d(TAG, "doRequestWithError <- %d chars in %d ms: %s", sb.length(),
                    System.currentTimeMillis() - startTs, LogUtil.preview(sb.toString(), 500));
        } catch (Exception e) {
            LogUtil.e(TAG, "doRequestWithError EXCEPTION: " + e.getMessage(), e);
            result.error = e.getMessage();
        } finally {
            if (conn != null) conn.disconnect();
        }
        return result;
    }

    private static JSONObject buildRequestBody(String model,
            List<Message> messages, String systemPrompt,
            String thinkingLevel, String thinkingType, String thinkingParamName,
            boolean stream) throws Exception {

        JSONObject json = new JSONObject();
        json.put("model", model);
        json.put("stream", stream);

        JSONArray msgs = new JSONArray();

        // System prompt
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            JSONObject sm = new JSONObject();
            sm.put("role", "system");
            sm.put("content", systemPrompt);
            msgs.put(sm);
        }

        // Messages (strip thinking content from assistant messages)
        for (Message m : messages) {
            JSONObject mm = new JSONObject();
            mm.put("role", m.role);
            String content = m.content;
            if (Message.ROLE_ASSISTANT.equals(m.role)) {
                content = removeThinkingContent(content);
            }
            mm.put("content", content);
            msgs.put(mm);
        }

        json.put("messages", msgs);
        LogUtil.d(TAG, "buildRequestBody: model=%s stream=%s msgs=%d(+system=%s) thinking=%s type=%s param=%s",
                model, stream, messages == null ? 0 : messages.size(),
                (systemPrompt != null && !systemPrompt.isEmpty()), thinkingLevel, thinkingType, thinkingParamName);

        // Thinking parameters (null = skip entirely, for title gen etc.)
        if (thinkingLevel != null && !"off".equals(thinkingLevel)) {
            if ("boolean".equals(thinkingType)) {
                // SiliconFlow style
                json.put(thinkingParamName, true);
                int budget = getThinkingBudget(thinkingLevel);
                if (budget > 0) {
                    json.put("thinking_budget", budget);
                }
            } else {
                // DeepSeek style (object type)
                JSONObject thinkingObj = new JSONObject();
                thinkingObj.put("type", "enabled");
                json.put(thinkingParamName, thinkingObj);
                json.put("reasoning_effort", getReasoningEffort(thinkingLevel));
            }
        } else if (thinkingLevel != null && "off".equals(thinkingLevel)) {
            if ("boolean".equals(thinkingType)) {
                json.put(thinkingParamName, false);
            } else {
                // DeepSeek style: send disabled
                JSONObject thinkingObj = new JSONObject();
                thinkingObj.put("type", "disabled");
                json.put(thinkingParamName, thinkingObj);
            }
            // Fallback params for providers that ignore the primary switch
            // (e.g. SCNet GLM-5 ignores thinking.type=disabled but honors
            // reasoning_effort=none / chat_template_kwargs.enable_thinking)
            json.put("reasoning_effort", "none");
            json.put("chat_template_kwargs", new JSONObject().put("enable_thinking", false));
        }
        // thinkingLevel == null: skip all thinking params

        return json;
    }

    private static int getThinkingBudget(String level) {
        if ("low".equals(level)) return 2048;
        if ("medium".equals(level)) return 4096;
        if ("high".equals(level)) return 32768;
        return 0;
    }

    private static String getReasoningEffort(String level) {
        if ("high".equals(level)) return "max";
        return "high"; // low/medium/off → high (API default)
    }

    /**
     * Pull choices[0].message.content out of a non-streaming completion body.
     * Returns null when the shape is not recognised, so callers can report a
     * parse failure instead of rendering an empty reply.
     */
    static String extractMessageContent(String responseBody) {
        if (responseBody == null) return null;
        try {
            JSONObject json = new JSONObject(responseBody);
            JSONArray choices = json.optJSONArray("choices");
            if (choices == null || choices.length() == 0) return null;
            JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
            if (msg == null) return null;
            return removeThinkingContent(msg.optString("content", ""));
        } catch (Exception e) {
            LogUtil.e(TAG, "extractMessageContent failed: " + e.getMessage());
            return null;
        }
    }

    static String removeThinkingContent(String content) {
        if (content == null) return "";
        StringBuilder result = new StringBuilder();
        int start = 0;
        while (start < content.length()) {
            int thinkStart = content.indexOf("[thinking]", start);
            if (thinkStart == -1) {
                result.append(content.substring(start));
                break;
            }
            if (thinkStart > start) {
                result.append(content.substring(start, thinkStart));
            }
            int thinkEnd = content.indexOf("[/thinking]", thinkStart);
            if (thinkEnd == -1) break;
            start = thinkEnd + 11; // "[/thinking]".length()
        }
        return result.toString().trim();
    }

    private static void setupTLS(HttpsURLConnection conn) {
        TlsCompat.apply(conn);
    }
}
