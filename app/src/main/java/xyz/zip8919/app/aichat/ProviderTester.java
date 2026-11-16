package xyz.zip8919.app.aichat;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLException;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 提供商 / 模型的连通性检测，以及 OpenAI 兼容的模型列表拉取。
 *
 * <p><b>为什么提供商测试先去查 {@code /models}</b>：它零 token、能一次性验证
 * 「地址对不对 + Key 有没有效」，是失败原因最干净的诊断入口。但有些中转站压根没实现
 * {@code /models}（也有的要求 POST），此时 GET 的 404 并不代表对话端点不可用，
 * 所以再降级发一次 {@code max_tokens=8} 的最小对话——那才是聊天真正走的路。
 *
 * <p>所有方法都是<b>阻塞</b>的，调用方必须放在工作线程；UI 刷新由调用方回主线程。
 */
final class ProviderTester {

    private static final String TAG = "ProviderTester";

    static final int OK = 0;
    static final int FAIL = 1;

    private static final int TIMEOUT_MS = 15000;
    private static final int MAX_BODY = 256 * 1024;

    /** 一次检测的结果。{@code message} 永远是给人看的一句话，UI 直接显示。 */
    static final class Result {
        final int code;
        final String message;
        final long ms;

        Result(int code, String message, long ms) {
            this.code = code;
            this.message = message;
            this.ms = ms;
        }

        boolean ok() { return code == OK; }
    }

    /** 模型列表拉取结果：成功时 {@code models} 非空（可能为空数组，表示服务端没给）。 */
    static final class ModelsResult {
        final boolean ok;
        final List<String> models;
        final String message;

        ModelsResult(boolean ok, List<String> models, String message) {
            this.ok = ok;
            this.models = models == null ? new ArrayList<String>() : models;
            this.message = message;
        }
    }

    private static final class Raw {
        int httpCode;
        String body;
        String error;
    }

    private ProviderTester() {
    }

    /**
     * 提供商连通性：GET {@code /models} → 失败且不像「认证/网络」问题时降级一次最小对话。
     *
     * @param p 会先复制一份，测试期间用户改配置不影响这次请求
     */
    static Result testProvider(ProviderInfo p) {
        if (p == null) return new Result(FAIL, Strings.get(R.string.providertester_001), 0);
        ProviderInfo snap = p.copy();
        String baseError = precheck(snap);
        if (baseError != null) return new Result(FAIL, baseError, 0);

        long t0 = System.currentTimeMillis();
        Raw r = get(Endpoint.modelsUrl(snap), snap.apiKey);
        long ms = System.currentTimeMillis() - t0;

        if (r.error != null) {
            // 网络层就失败了，再发对话也是同样的错，直接报
            return new Result(FAIL, describe(snap, r, ms, null), ms);
        }
        if (r.httpCode >= 200 && r.httpCode < 300) {
            int n = countModels(r.body);
            String extra = n >= 0 ? Strings.get(R.string.providertester_002, n) : "";
            return new Result(OK, Strings.get(R.string.providertester_004, ms, extra), ms);
        }
        // 401/403：Key 问题，换对话端点也一样，不必再试
        if (r.httpCode == 401 || r.httpCode == 403) {
            return new Result(FAIL, describe(snap, r, ms, null), ms);
        }
        // 其余（404/405/429/5xx）：/models 不可用不代表对话不可用，降级验证
        String model = firstConfiguredModel(snap);
        Result chat = testChat(snap, model == null ? "" : model);
        if (chat.ok()) {
            return new Result(OK, Strings.get(R.string.providertester_005, chat.ms, r.httpCode,
                    model == null ? Strings.get(R.string.providertester_008) : ""), chat.ms);
        }
        return new Result(FAIL, "/models HTTP " + r.httpCode + "；" + chat.message, ms + chat.ms);
    }

    /**
     * 模型连通性：发 {@code max_tokens=8} 的最小对话。
     *
     * @param modelName 空串时只验证「地址 + Key」（部分端点允许空模型名才用得到）
     */
    static Result testChat(ProviderInfo p, String modelName) {
        if (p == null) return new Result(FAIL, Strings.get(R.string.providertester_009), 0);
        ProviderInfo snap = p.copy();
        String baseError = precheck(snap);
        if (baseError != null) return new Result(FAIL, baseError, 0);
        String model = modelName == null ? "" : modelName.trim();
        if (model.length() == 0) return new Result(FAIL, Strings.get(R.string.providertester_010), 0);

        long t0 = System.currentTimeMillis();
        Raw r = post(Endpoint.chatUrl(snap), snap.apiKey, minimalBody(model));
        long ms = System.currentTimeMillis() - t0;

        if (r.error == null && r.httpCode >= 200 && r.httpCode < 300) {
            return new Result(OK, Strings.get(R.string.providertester_011, ms), ms);
        }
        return new Result(FAIL, describe(snap, r, ms, model), ms);
    }

    /**
     * 拉取模型列表：兼容三处常见写法。
     *
     * <ol>
     *   <li>聊天地址的父路径 + {@code /models}（标准 OpenAI 布局）</li>
     *   <li>{@code apiUrl} 直接 + {@code /models}（把 apiUrl 指到 {@code /v1} 的写法）</li>
     *   <li>上面都 404 时试 {@code apiUrl} 根路径 + {@code /models}</li>
     * </ol>
     * 响应兼容 {@code {"data":[{"id":...}]}}、{@code {"models":[...]}}、纯数组三种。
     */
    static ModelsResult fetchModels(ProviderInfo p) {
        if (p == null) return new ModelsResult(false, null, Strings.get(R.string.providertester_012));
        ProviderInfo snap = p.copy();
        String baseError = precheck(snap);
        if (baseError != null) return new ModelsResult(false, null, baseError);

        String[] urls = Endpoint.modelsUrlCandidates(snap);
        String lastMessage = "";
        for (int i = 0; i < urls.length; i++) {
            String url = urls[i];
            boolean dup = false;
            for (int j = 0; j < i; j++) {
                if (urls[j].equalsIgnoreCase(url)) dup = true;
            }
            if (dup) continue;
            Raw r = get(url, snap.apiKey);
            if (r.error != null) {
                lastMessage = describe(snap, r, 0, null);
                continue;
            }
            if (r.httpCode >= 200 && r.httpCode < 300) {
                List<String> names = parseModels(r.body);
                if (names == null) {
                    lastMessage = Strings.get(R.string.providertester_013);
                    continue;
                }
                LogUtil.i(TAG, "fetchModels ok: %s -> %d models", url, names.size());
                return new ModelsResult(true, names, Strings.get(R.string.providertester_014, names.size()));
            }
            lastMessage = describe(snap, r, 0, null);
            LogUtil.w(TAG, "fetchModels %s -> HTTP %d", url, r.httpCode);
        }
        return new ModelsResult(false, null, lastMessage);
    }

    private static String precheck(ProviderInfo p) {
        if (!Endpoint.isHttpUrl(p.apiUrl)) {
            return Strings.get(R.string.providertester_016);
        }
        if (p.apiKey == null || p.apiKey.trim().length() == 0) {
            return Strings.get(R.string.providertester_017);
        }
        return null;
    }

    /** 优先拿用户已配好的某个模型来探测对话端点；没有就返回 null。 */
    private static String firstConfiguredModel(ProviderInfo p) {
        ConfigManager cm = ConfigManager.getInstance();
        List<ModelInfo> list = cm.modelsOf(p.name);
        return list.isEmpty() ? null : list.get(0).name;
    }

    private static String minimalBody(String model) {
        try {
            JSONObject body = new JSONObject();
            body.put("model", model);
            JSONArray msgs = new JSONArray();
            JSONObject m = new JSONObject();
            m.put("role", "user");
            m.put("content", "hi");
            msgs.put(m);
            body.put("messages", msgs);
            body.put("max_tokens", 8);
            body.put("stream", false);
            return body.toString();
        } catch (Exception e) {
            return "{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],"
                    + "\"max_tokens\":8,\"stream\":false}";
        }
    }

    // ---- HTTP ----

    private static Raw get(String url, String apiKey) {
        return request("GET", url, apiKey, null);
    }

    private static Raw post(String url, String apiKey, String body) {
        return request("POST", url, apiKey, body);
    }

    private static Raw request(String method, String url, String apiKey, String body) {
        Raw out = new Raw();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            // TLS 兼容必须赶在任何会触发建连的调用之前。getOutputStream() 一旦被调，
            // 连接（含 TLS 握手）当场建立，之后再 setSSLSocketFactory 已经晚了：
            // 走的是平台默认套件，老系统上没有 AES-GCM，现代 CDN 直接拒绝握手。
            // 之前这段排在 body 写入之后，于是「/models 探测 404 → 降级最小对话」
            // 这条路径必然报证书错误，而同一个提供商在主界面聊天是正常的。
            if (conn instanceof HttpsURLConnection) {
                TlsCompat.apply((HttpsURLConnection) conn);
            }
            conn.setRequestMethod(method);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Accept-Encoding", "identity");
            if (apiKey != null && apiKey.trim().length() > 0) {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
            }
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] bytes = body.getBytes("UTF-8");
                conn.setFixedLengthStreamingMode(bytes.length);
                OutputStream os = conn.getOutputStream();
                os.write(bytes);
                os.flush();
                os.close();
            }
            out.httpCode = conn.getResponseCode();
            InputStream in = out.httpCode >= 400 ? conn.getErrorStream() : conn.getInputStream();
            out.body = read(in);
            LogUtil.d(TAG, "%s %s -> %d (%d chars)", method, url, out.httpCode,
                    out.body == null ? 0 : out.body.length());
        } catch (Exception e) {
            out.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            LogUtil.w(TAG, "%s %s failed: %s", method, url, out.error);
        } finally {
            if (conn != null) conn.disconnect();
        }
        return out;
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        try {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) {
                sb.append(buf, 0, n);
                if (sb.length() > MAX_BODY) break;
            }
        } finally {
            r.close();
        }
        return sb.toString();
    }

    // ---- 结果解析 ----

    /** 返回模型个数，解析不出来返回 -1（只用于成功提示里的补充信息）。 */
    private static int countModels(String body) {
        List<String> l = parseModels(body);
        return l == null ? -1 : l.size();
    }

    /** 兼容 {@code data[]} / {@code models[]} / 纯数组；解析不出来返回 null。 */
    static List<String> parseModels(String body) {
        String b = Endpoint.trim(body);
        if (b.length() == 0) return null;
        List<String> out = new ArrayList<String>();
        try {
            if (b.startsWith("[")) {
                collect(new JSONArray(b), out);
                return out;
            }
            JSONObject json = new JSONObject(b);
            JSONArray arr = json.optJSONArray("data");
            if (arr == null) arr = json.optJSONArray("models");
            if (arr == null) return null;
            collect(arr, out);
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static void collect(JSONArray arr, List<String> out) {
        for (int i = 0; i < arr.length(); i++) {
            Object o = arr.opt(i);
            String id = null;
            if (o instanceof String) {
                id = ((String) o).trim();
            } else if (o instanceof JSONObject) {
                JSONObject jo = (JSONObject) o;
                id = jo.optString("id", jo.optString("name", jo.optString("model", ""))).trim();
            }
            if (id != null && id.length() > 0 && !out.contains(id)) out.add(id);
        }
    }

    /** 尽量从错误体里抠出服务端的原话（OpenAI 风格 {@code error.message}）。 */
    static String errorMessage(String body) {
        String b = Endpoint.trim(body);
        if (b.length() == 0) return "";
        try {
            JSONObject json = new JSONObject(b);
            JSONObject err = json.optJSONObject("error");
            if (err != null) {
                String m = err.optString("message", "");
                if (m.length() > 0) return m;
                String d = err.optString("detail", "");
                if (d.length() > 0) return d;
            }
            String m = json.optString("message", "");
            if (m.length() > 0) return m;
            String d = json.optString("detail", "");
            if (d.length() > 0) return d;
        } catch (Exception ignored) {
            // 不是 JSON：多半是网关的 HTML 错误页，下面截一段原文
        }
        return TextKit.shorten(b.replaceAll("<[^>]+>", " "), 120);
    }

    /**
     * 把一次失败翻译成人话，并给出下一步该改哪里。
     *
     * <p>只报「HTTP 404」对用户没有价值——他不知道是地址错了还是模型错了。
     */
    private static String describe(ProviderInfo p, Raw r, long ms, String model) {
        if (r.error != null) {
            return networkError(p, r.error);
        }
        int code = r.httpCode;
        String detail = errorMessage(r.body);
        String suffix = detail.length() > 0 ? "：" + detail : "";
        if (code == 401) return Strings.get(R.string.providertester_018, suffix);
        if (code == 403) return Strings.get(R.string.providertester_019, suffix);
        if (code == 404) {
            return Strings.get(R.string.providertester_020,
                    model == null ? "" : Strings.get(R.string.providertester_021), suffix);
        }
        if (code == 405) return Strings.get(R.string.providertester_022, suffix);
        if (code == 429) return Strings.get(R.string.providertester_023, suffix);
        if (code == 400) return Strings.get(R.string.providertester_024, suffix);
        if (code >= 500) return Strings.get(R.string.providertester_025, code, suffix);
        return Strings.get(R.string.providertester_042, code, suffix);
    }

    /**
     * 给能力探测用的公开包装：把失败翻译成人话。
     *
     * <p>{@link #describe} 是私有的，能力探测走的是另一条请求链，重复实现一份
     * 归类逻辑迟早会出现两处口径不一致，所以这里只做转发。
     */
    static String describeFailure(ProviderInfo p, int code, String body, String model) {
        Raw r = new Raw();
        r.httpCode = code;
        r.body = body;
        return describe(p, r, 0, model);
    }

    /** 网络层异常转人话（能力探测复用同一套措辞）。 */
    static String explainNetwork(ProviderInfo p, String raw) {
        return networkError(p, raw);
    }

    /** 错误体里的一句话摘要，用于探测结果的附注。 */
    static String shortError(String body) {
        String m = errorMessage(body);
        return m.length() == 0 ? Strings.get(R.string.providertester_026) : TextKit.shorten(m, 80);
    }

    private static String networkError(ProviderInfo p, String raw) {
        String r = raw == null ? "" : raw;
        String lower = r.toLowerCase(java.util.Locale.US);
        String host = Endpoint.hostOf(p.apiUrl);
        boolean local = Endpoint.isLocalHost(p.apiUrl);
        if (lower.contains("unknownhost") || lower.contains("nodename nor servname")
                || lower.contains("unable to resolve host")) {
            return Strings.get(R.string.providertester_027, host);
        }
        if (lower.contains("sockettimeout") || lower.contains("timed out") || lower.contains("timeout")) {
            return Strings.get(local ? R.string.providertester_043 : R.string.providertester_029);
        }
        if (lower.contains("certificate") || lower.contains("certpathvalidator")
                || lower.contains("trust anchor") || lower.contains("hostname")) {
            return Strings.get(R.string.providertester_031);
        }
        if (lower.contains("sslexception") || lower.contains("handshake")) {
            return Strings.get(local ? R.string.providertester_044 : R.string.providertester_045);
        }
        if (lower.contains("connectexception") || lower.contains("econnrefused")
                || lower.contains("failed to connect")) {
            return Strings.get(R.string.providertester_035);
        }
        if (lower.contains("cleartext") || lower.contains("not permitted")) {
            return Strings.get(R.string.providertester_036);
        }
        // 兜底也给原始异常，否则日志里对不上
        return Strings.get(R.string.providertester_037, TextKit.shorten(r, 120));
    }

    /** 供调试：把异常类名映射成人话（保留给将来接入 javax.net 的直接调用）。 */
    static String explainThrowable(Throwable t) {
        if (t == null) return "";
        if (t instanceof UnknownHostException) return Strings.get(R.string.providertester_038);
        if (t instanceof SocketTimeoutException) return Strings.get(R.string.providertester_039);
        if (t instanceof CertificateException) return Strings.get(R.string.providertester_040);
        if (t instanceof SSLException) return Strings.get(R.string.providertester_041);
        return t.getClass().getSimpleName();
    }
}
