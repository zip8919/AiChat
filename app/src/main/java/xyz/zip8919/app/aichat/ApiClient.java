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
        try {
            JSONObject body = buildRequestBody(model, messages, systemPrompt, thinkingLevel,
                    provider.thinkingType, provider.thinkingParamName, false);
            body.put("max_tokens", 50);
            result = doRequestWithError(provider.apiUrl + provider.chatPath, provider.apiKey, body, 20000);
        } catch (Exception e) {
            result.error = e.getMessage();
        }
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

        return doRequest(provider.apiUrl + provider.chatPath, provider.apiKey, body, 60000);
    }

    /**
     * Streaming chat completion with SSE parsing.
     */
    public static void callStream(ProviderInfo provider, String model,
            List<Message> messages, String systemPrompt,
            String thinkingLevel, AtomicBoolean runningFlag, StreamCallback callback) {

        HttpURLConnection conn = null;
        try {
            JSONObject body = buildRequestBody(model, messages, systemPrompt, thinkingLevel,
                    provider.thinkingType, provider.thinkingParamName, true);

            URL url = new URL(provider.apiUrl + provider.chatPath);
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
            if (code != 200) {
                callback.onError("HTTP " + code);
                return;
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
            String line;
            while (runningFlag.get() && (line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("data: ")) {
                    String data = line.substring(6);
                    if ("[DONE]".equals(data)) break;
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
                            }
                        }
                    } catch (Exception e) {
                        // skip malformed chunks
                    }
                }
            }
            reader.close();

            if (!runningFlag.get()) {
                callback.onError("interrupted");
            } else {
                callback.onComplete();
            }
        } catch (Exception e) {
            callback.onError(e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * Query DeepSeek balance.
     * @return JSON string with balance info, or null on error
     */
    public static String queryBalance(ProviderInfo provider) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(provider.apiUrl + "/user/balance");
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
            if (code != 200) {
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
            return sb.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ---- private helpers ----

    private static String doRequest(String urlStr, String apiKey,
            JSONObject body, int readTimeout) throws Exception {
        HttpURLConnection conn = null;
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

            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.close();

            int code = conn.getResponseCode();
            if (code != 200) {
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
            return sb.toString();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static CallResult doRequestWithError(String urlStr, String apiKey,
            JSONObject body, int readTimeout) {
        CallResult result = new CallResult();
        HttpURLConnection conn = null;
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

            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.close();

            int code = conn.getResponseCode();
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
                } catch (Exception e) {
                    result.error = "HTTP " + code;
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
        } catch (Exception e) {
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
                // DeepSeek: send disabled
                JSONObject thinkingObj = new JSONObject();
                thinkingObj.put("type", "disabled");
                json.put(thinkingParamName, thinkingObj);
            }
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

    private static void setupTLS(HttpsURLConnection conn) throws Exception {
        SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
        sslContext.init(null, new TrustManager[] { new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        } }, null);
        conn.setSSLSocketFactory(sslContext.getSocketFactory());
        conn.setHostnameVerifier(new HostnameVerifier() {
            public boolean verify(String hostname, SSLSession session) { return true; }
        });
    }
}
