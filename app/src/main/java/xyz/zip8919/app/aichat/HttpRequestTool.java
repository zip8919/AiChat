package xyz.zip8919.app.aichat;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.Iterator;
import java.util.Locale;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

/**
 * 通用 HTTP 请求工具：让模型在本机直接调用 API，而不是绕道搜索网关。
 *
 * <p><b>安全边界</b>（默认关闭，设置页开启）：模型拿到这个工具，等于拿到了本机的
 * 网络出口。因此做了三道限制：
 * <ol>
 *   <li>只允许 http/https，且 DNS 解析后拒绝环回、私有网段、链路本地、唯一本地地址——
 *       否则一句 {@code http://192.168.1.1/admin} 就能摸到用户路由器。</li>
 *   <li>不跟随重定向：3xx 原样返回给模型。跟着跳转就绕过了上面的网段校验
 *       （公网 URL 302 到 127.0.0.1 是经典绕过手法）。</li>
 *   <li>响应截断 32 KB：避免一条大响应把上下文窗口撑爆、也避免把整个网页塞进对话。</li>
 * </ol>
 */
final class HttpRequestTool {

    private static final String TAG = "HttpRequestTool";

    static final String TOOL_HTTP = "http_request";

    private static final int MAX_RESPONSE_BYTES = 32 * 1024;
    private static final int DEFAULT_TIMEOUT_MS = 15000;
    private static final int MIN_TIMEOUT_MS = 1000;
    private static final int MAX_TIMEOUT_MS = 30000;
    private static final int MAX_HEADERS = 20;

    /** 实际生效的响应上限：设置里可改，默认 32 KB。 */
    private static int responseLimit() {
        return Limits.toolMaxBytes > 0 ? Limits.toolMaxBytes : MAX_RESPONSE_BYTES;
    }

    /** 实际生效的超时上限：设置里可改，默认 30s。 */
    private static int timeoutLimit() {
        return Limits.httpTimeoutMs > 0 ? Limits.httpTimeoutMs : MAX_TIMEOUT_MS;
    }

    private HttpRequestTool() {
    }

    static JSONObject definition() throws Exception {
        JSONObject tool = new JSONObject();
        tool.put("type", "function");
        JSONObject fn = new JSONObject();
        fn.put("name", TOOL_HTTP);
        fn.put("description",
                "直接向指定 URL 发起 HTTP 请求，用于调用 REST API 或获取结构化数据。"
                        + "默认只能访问公网地址；若被拒绝，说明用户未开启内网访问。响应超过 "
                        + (responseLimit() / 1024) + " KB 会被截断，"
                        + "不会自动跟随重定向（3xx 会原样返回状态码与 Location）。");

        JSONObject params = new JSONObject();
        params.put("type", "object");
        JSONObject props = new JSONObject();

        JSONObject url = new JSONObject();
        url.put("type", "string");
        url.put("description", "完整 URL，必须以 http:// 或 https:// 开头");
        props.put("url", url);

        JSONObject method = new JSONObject();
        method.put("type", "string");
        method.put("description", "请求方法，默认 GET");
        method.put("enum", new org.json.JSONArray()
                .put("GET").put("POST").put("PUT").put("DELETE").put("PATCH").put("HEAD"));
        props.put("method", method);

        JSONObject headers = new JSONObject();
        headers.put("type", "object");
        headers.put("description", "请求头键值对，例如 {\"Authorization\":\"Bearer xxx\"}");
        props.put("headers", headers);

        JSONObject body = new JSONObject();
        body.put("type", "string");
        body.put("description", "请求体原文，POST/PUT/PATCH 时使用；JSON 请自行序列化成字符串");
        props.put("body", body);

        JSONObject timeout = new JSONObject();
        timeout.put("type", "integer");
        timeout.put("description", "超时毫秒数，1000-" + timeoutLimit() + "，默认 15000");
        props.put("timeout_ms", timeout);

        params.put("properties", props);
        params.put("required", new org.json.JSONArray().put("url"));
        fn.put("parameters", params);
        tool.put("function", fn);
        return tool;
    }

    /** 执行并返回给模型的 JSON 字符串。任何异常都转成 {"error":...}，不抛给上层。 */
    static String execute(JSONObject args) {
        return execute(args, NetSecurity.allowPrivateHost());
    }

    /**
     * @param allowPrivateHost 是否允许访问环回/私有网段。默认 false；
     *                         只有用户在设置里显式打开（自建 API 在局域网时）才为 true。
     */
    static String execute(JSONObject args, boolean allowPrivateHost) {
        String urlStr = args == null ? "" : args.optString("url", "").trim();
        if (urlStr.length() == 0) return error("缺少 url 参数");

        String method = args.optString("method", "GET").trim().toUpperCase(Locale.US);
        if (method.length() == 0) method = "GET";
        if (!isAllowedMethod(method)) return error("不支持的方法: " + method);

        int timeout = args.optInt("timeout_ms", DEFAULT_TIMEOUT_MS);
        if (timeout < MIN_TIMEOUT_MS) timeout = MIN_TIMEOUT_MS;
        if (timeout > timeoutLimit()) timeout = timeoutLimit();

        String body = args.optString("body", "");
        JSONObject headers = args.optJSONObject("headers");

        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            String protocol = url.getProtocol();
            if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
                return error("只支持 http/https，收到: " + protocol);
            }
            if (!allowPrivateHost) assertPublicHost(url);

            long start = System.currentTimeMillis();
            conn = (HttpURLConnection) url.openConnection();
            // ApiClient / ChatTransport / TavilyTool / WebSearchTool 都调了，
            // 这里漏掉：4.4 的平台 TLS 栈无 GCM 套件，HTTPS 工具必握手失败。
            if (conn instanceof HttpsURLConnection) {
                TlsCompat.apply((HttpsURLConnection) conn);
            }
            conn.setRequestMethod(method);
            conn.setConnectTimeout(timeout);
            conn.setReadTimeout(timeout);
            // 不跟随重定向：3xx 交回模型判断，避免被公网地址跳到内网
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", "AiChat");
            conn.setRequestProperty("Accept", "*/*");
            if (headers != null) {
                Iterator<String> it = headers.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    String v = headers.optString(k, "");
                    if (k == null || k.length() == 0) continue;
                    conn.setRequestProperty(k, v);
                }
            }
            if (body != null && body.length() > 0) {
                conn.setDoOutput(true);
                // getOutputStream() 同一连接只能取一次，取出后必须关掉才算写完
                java.io.OutputStream os = conn.getOutputStream();
                try {
                    os.write(body.getBytes("UTF-8"));
                } finally {
                    os.close();
                }
            }

            int code = conn.getResponseCode();
            long cost = System.currentTimeMillis() - start;
            LogUtil.i(TAG, "http_request %s %s -> %d in %d ms", method, urlStr, code, cost);

            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            int limit = responseLimit();
            byte[] raw = readLimitedBytes(in, limit);
            boolean truncated = raw.length >= limit;
            // 按「字节」截断可能正好把一个多字节 UTF-8 字符切成两半，
            // new String 会在末尾留下一个替换字符（乱码方块）。截断时
            // 回退到最后一个完整字符边界再解码，保证 body 是干净文本。
            if (truncated) raw = trimIncompleteUtf8(raw);
            String text = new String(raw, "UTF-8");

            JSONObject out = new JSONObject();
            out.put("status", code);
            out.put("elapsed_ms", cost);
            out.put("content_type", nullToEmpty(conn.getContentType()));
            if (code >= 300 && code < 400) {
                out.put("location", nullToEmpty(conn.getHeaderField("Location")));
            }
            out.put("truncated", truncated);

            JSONObject outHeaders = new JSONObject();
            int n = 0;
            for (java.util.Map.Entry<String, java.util.List<String>> e : conn.getHeaderFields().entrySet()) {
                if (e.getKey() == null || e.getValue() == null || e.getValue().isEmpty()) continue;
                if (n++ >= MAX_HEADERS) break;
                outHeaders.put(e.getKey(), e.getValue().get(0));
            }
            out.put("headers", outHeaders);
            out.put("body", text);
            return out.toString();
        } catch (Exception e) {
            LogUtil.w(TAG, "http_request failed: %s", e.getMessage());
            return error(e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) { }
            }
        }
    }

    private static boolean isAllowedMethod(String m) {
        return "GET".equals(m) || "POST".equals(m) || "PUT".equals(m)
                || "DELETE".equals(m) || "PATCH".equals(m) || "HEAD".equals(m);
    }

    /**
     * 拒绝一切指向本机/内网的地址。DNS 可能返回多个 A 记录，只要有一条落在
     * 内网就整体拒绝——只检查第一条会被绕过。
     */
    /** 包可见：图片导入（URL 附加）也要走同一道私网校验。 */
    static void assertPublicHost(URL url) throws Exception {
        String host = url.getHost();
        if (host == null || host.length() == 0) throw new Exception("URL 缺少主机名");
        // IPv6 字面量的 getHost() 带方括号，解析前要去掉
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if ("localhost".equalsIgnoreCase(host)) throw new Exception("拒绝访问本机地址");

        InetAddress[] addrs = InetAddress.getAllByName(host);
        for (InetAddress a : addrs) {
            if (a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isLinkLocalAddress()
                    || a.isSiteLocalAddress() || a.isMulticastAddress()) {
                throw new Exception("拒绝访问内网地址: " + host);
            }
            byte[] raw = a.getAddress();
            // IPv6 唯一本地地址 fc00::/7 不在 isSiteLocalAddress 覆盖范围内
            if (raw != null && raw.length == 16 && (raw[0] & 0xFE) == 0xFC) {
                throw new Exception("拒绝访问内网地址: " + host);
            }
        }
    }

    private static byte[] readLimitedBytes(InputStream in, int max) {
        if (in == null) return new byte[0];
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            int total = 0;
            while (total < max && (n = in.read(buf, 0, Math.min(buf.length, max - total))) > 0) {
                bos.write(buf, 0, n);
                total += n;
            }
            return bos.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        } finally {
            try { in.close(); } catch (Exception ignored) { }
        }
    }

    /**
     * 去掉尾部不完整的 UTF-8 序列。只在「确实发生了截断」时调用：
     * 未截断的响应本来就是完整字节流，无需回退。
     */
    private static byte[] trimIncompleteUtf8(byte[] raw) {
        int end = raw.length;
        int i = end - 1;
        int back = 0;
        while (i >= 0 && back < 4) {
            int b = raw[i] & 0xFF;
            if ((b & 0xC0) != 0x80) {          // 起始字节（非 10xxxxxx 续字节）
                int need = b < 0x80 ? 1 : b < 0xE0 ? 2 : b < 0xF0 ? 3 : 4;
                if (end - i >= need) return raw;   // 末尾这个字符是完整的
                break;                              // 被截断：从这个起始字节处切断
            }
            i--;
            back++;
        }
        if (i < 0) i = 0;
        byte[] out = new byte[i];
        System.arraycopy(raw, 0, out, 0, i);
        return out;
    }

    /**
     * 把完整 JSON 字符串缩进美化（2 空格）。非 JSON、或结构不完整（被截断
     * 到一半的响应体）一律返回 null，由调用方退回原文并标注「可能不完整」——
     * 绝不把半截 JSON 当成完整结果展示，也绝不因解析失败让卡片消失。
     *
     * <p>包可见：工具结果卡片展示时复用同一套美化规则。
     */
    static String prettyJson(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.length() < 2) return null;
        try {
            char c = t.charAt(0);
            if (c == '{') return new JSONObject(t).toString(2);
            if (c == '[') return new org.json.JSONArray(t).toString(2);
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String error(String msg) {
        try {
            return new JSONObject().put("error", msg == null ? "请求失败" : msg).toString();
        } catch (Exception e) {
            return "{\"error\":\"请求失败\"}";
        }
    }
}
