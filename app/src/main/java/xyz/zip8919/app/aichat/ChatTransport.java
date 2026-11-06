package xyz.zip8919.app.aichat;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 对话接口的 HTTP 层：拼请求体、建连接、读错误体、关连接。
 *
 * <p>从 MainActivity 抽出。所有方法都是无状态的，抽出后请求线程只依赖本类，
 * 不再往 Activity 上挂纯协议细节。连接超时 30s、读超时 0（SSE 长连接不能设读超时，
 * 否则模型思考超过阈值就会被动断开）。
 */
final class ChatTransport {

    private static final String TAG = "ChatTransport";

    private ChatTransport() {
    }

    /**
     * OpenAI 兼容的 chat/completions 请求体。
     *
     * <p>思考参数按 provider 声明的方式二选一：{@code boolean} 型直接放开关 + budget，
     * 其余走 {@code {"type":"enabled|disabled"}} 对象 + reasoning_effort。
     * 这两种格式不通用，写错端点会直接 400。
     */
    static JSONObject buildRequestBody(String model, ProviderInfo provider, String thinkingLevel,
            JSONArray messages, boolean stream, JSONArray tools, boolean includeUsage) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("stream", stream);
        body.put("messages", messages);
        if (tools != null && tools.length() > 0) {
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        }
        // 流式下 usage 默认不返回，必须显式要。严格校验参数的端点会因此 400，
        // 由 ChatStreamRunner 的降级链去掉本字段重发。
        if (stream && includeUsage) {
            JSONObject opts = new JSONObject();
            opts.put("include_usage", true);
            body.put("stream_options", opts);
        }

        if ("boolean".equals(provider.thinkingType)) {
            if (!"off".equals(thinkingLevel)) {
                body.put(provider.thinkingParamName, true);
                int budget = 4096;
                if ("low".equals(thinkingLevel)) budget = 2048;
                else if ("high".equals(thinkingLevel)) budget = 32768;
                body.put("thinking_budget", budget);
            }
        } else {
            JSONObject tObj = new JSONObject();
            tObj.put("type", "off".equals(thinkingLevel) ? "disabled" : "enabled");
            body.put(provider.thinkingParamName, tObj);
            if (!"off".equals(thinkingLevel)) {
                body.put("reasoning_effort", "high".equals(thinkingLevel) ? "max" : "high");
            }
        }
        return body;
    }

    /**
     * 读 {@code Retry-After} 头，返回需要等待的秒数；没有或解析失败返回 -1。
     *
     * <p>两种合法格式都要支持：纯秒数（{@code 120}）与 HTTP-date
     * （{@code Wed, 21 Oct 2015 07:28:00 GMT}）。只认前者会让一半服务端的
     * 限流提示失效，白白退化成指数退避。
     */
    static int retryAfterSeconds(HttpURLConnection conn) {
        if (conn == null) return -1;
        try {
            String raw = conn.getHeaderField("Retry-After");
            if (raw == null) return -1;
            raw = raw.trim();
            if (raw.length() == 0) return -1;
            try {
                int sec = Integer.parseInt(raw);
                return sec < 0 ? -1 : sec;
            } catch (NumberFormatException ignored) {
                // 不是纯数字，继续按 HTTP-date 解析
            }
            java.text.SimpleDateFormat fmt =
                    new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz",
                            java.util.Locale.US);
            java.util.Date when = fmt.parse(raw);
            long diff = (when.getTime() - System.currentTimeMillis()) / 1000L;
            return diff > 0 ? (int) diff : 0;
        } catch (Exception e) {
            LogUtil.d(TAG, "retryAfterSeconds parse failed: %s", e.getMessage());
            return -1;
        }
    }

    static HttpURLConnection open(ProviderInfo provider) throws Exception {
        String endpoint = provider.apiUrl + provider.chatPath;
        LogUtil.d(TAG, "open: %s", endpoint);
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + provider.apiKey);
        conn.setDoOutput(true);
        conn.setConnectTimeout(30000);
        // 读超时不能为 0：SSE 本来就是长连接，但「一直等不到字节」和「正在思考」
        // 必须能区分开。设为 0 时，NAT 静默丢连接或服务端卡死会让界面永远停在
        // 「正在整理搜索结果」，只能手动打断。120s 内任何字节（含 keep-alive）
        // 都会重置计时，正常思考不会误伤。
        conn.setReadTimeout(120000);
        if (conn instanceof HttpsURLConnection) {
            TlsCompat.apply((HttpsURLConnection) conn);
        }
        return conn;
    }

    static void close(HttpURLConnection conn) {
        if (conn == null) return;
        try {
            conn.disconnect();
        } catch (Exception ignored) {
        }
    }

    /** 读取错误响应体，最多 4KB——够看错误原因，又不至于把日志撑爆。 */
    static String readErrorBody(HttpURLConnection conn) {
        if (conn == null) return "";
        InputStream es = null;
        try {
            es = conn.getErrorStream();
            if (es == null) return "";
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            int total = 0;
            while (total < 4096 && (n = es.read(buf, 0, Math.min(buf.length, 4096 - total))) > 0) {
                bos.write(buf, 0, n);
                total += n;
            }
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return "";
        } finally {
            // 不关流在复用的 keep-alive 连接上会拖住后续请求，也占着 fd
            if (es != null) {
                try { es.close(); } catch (Exception ignored) { }
            }
        }
    }
}
