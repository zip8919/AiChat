package xyz.zip8919.app.aichat;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 模型能力探测：逐档验证思考档位是否被端点接受，并验证图片输入是否可用。
 *
 * <p><b>判定依据是 HTTP 状态码，不是回复内容</b>：端点接受某个档位就返回 200，
 * 拒绝就是 400/422。这比「看回复里有没有 reasoning_content」可靠得多 ——
 * 很多端点开了思考也不回传推理过程，按内容判会把能用的模型判成不能用。
 *
 * <p><b>为什么每档都发一次请求</b>：用户要的是「逐档记录」。多探几档的代价是
 * 每个模型最多 6 次请求（max_tokens 都很小），换来的是「这个模型只认 max」
 * 这种真实存在的情况不会被误判。
 *
 * <p>全部方法<b>阻塞</b>，调用方放工作线程；进度回调由调用方决定在哪个线程。
 */
final class CapabilityProbe {

    private static final String TAG = "CapabilityProbe";

    private static final int TIMEOUT_MS = 20000;
    /** 思考档位探测的回复长度上限：只要端点能起来，回答多短都算通过。 */
    private static final int THINKING_MAX_TOKENS = 64;
    private static final int VISION_MAX_TOKENS = 24;

    /** 提问，逼模型直接报数字；判据见 {@link VisionJudge}。 */
    private static final String VISION_ASK = "图中的数字是多少？只回答数字，不要解释。";

    /**
     * 探测图：128×128 白底黑字「42」，灰度 PNG，约 1.1 KB。
     *
     * <p>以前用 1×1 空白图，只能证明「端点不拒绝图片块」，很多中转会把图片丢掉
     * 照样回话，于无视觉能力的模型也被判成支持。改成看图报数：读不出数字就是
     * 没看到图，判定只能靠这条内容校验，不能靠状态码。
     */
    private static final String PROBE_IMAGE_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAIAAAACACAAAAADmVT4XAAAEMklEQVR42u3bWUhUURgA4JnU"
            + "LKcxN7QpKyoQlcjAwsgpWhQEC7KFQorIVpcoKrOX6kHapKIFsgyhqCjMoqio0ZQyCulBbaGF"
            + "NgcXRmxGLcchdebvpbme4/3P1dEz14fOfbvnP3Pv59z7n+Uf1IJmZI9RGgEQAAEQAAEQAAEQ"
            + "AAEQAAH43wG+XrkqNH391tDcbG3vsPd0a/z9A8IjDFExM2cgXbW8d0Z/3tfW1rztQmMRC5LS"
            + "wmVY5vEIvUgC+wNgLc2M81P2+Swp6aU+wwa0TfQIYDftjx/cCzWlsHdQgA0ajwC7PHhMsZV9"
            + "n2OiH1zz3pv/YWlu90BpaNvmzdyDk8ltAwByLN5N/yqjRRFw76a3B6APqZ0KAOsO7w+BNekK"
            + "gKwWFQbhB0VMQGmJKtPA3lYGoDVLnXmo8whjMspsHf7Fg4zzo6PCArVW67uq8h+MThcPhWBz"
            + "gXICDGYknLav2kmEnHfmMi52HhuKLaHDA/itfSYLOvN90IvNwwArNMMB6PMsaNg0Gp0bO+SA"
            + "gaYARYD+sI01t13XYld7JAM0B5PxwDhPAL6ZLQqLhQwMUCCbDbe1kWenIj148Re/uxCuEM4P"
            + "QBq/9h8Hrjwkz1K2DP72huuV0YodJq5DGhv7pWFjEJXLjZDq0ZJM+ShFAAv7PYKt7eTZmUlc"
            + "B74lSJudfgTFj8mz5Rv5jrzBY+VtARSgYQ8ZCyniPfZHICgKsOUXNUxOUGEXFkI2XiojQyvT"
            + "uc9+bfKmGAJgziUjYYXc79+CAOb0AWDzbzKiOKYM7ahGHkp8H6CwgoysXcN//XFX3rRovDQQ"
            + "fddR7+vPf80cByLbODmgWNoZQYadWqqE8v8CznXKmnSrpN3xWSqwXmLz+wbMOrkpT9qcfqFm"
            + "KoONP8CVIr+//qd7c+raRBUULgfzfwDHn8jbDoS6CxSnqfZNBJzXN3AfWRPO7nbXBz5Ts0Rk"
            + "O3/AS2Qe0n1yFyic86mACbgDXuiRh3JLqpAU0Gsy4A4oQxJAc1Aq0XwcQ7ZP/cUdcA2rW22U"
            + "akS9CVTVrgJ4A45h6/HUHglwjApkA2eAMxtLyiRHX5XMnwxM7+QMcKRh919kJ8p01AN4DnwB"
            + "1kTs/sldwADsBr6AenSnsMwBDEBUF19AnQG7f3oPMACjXgFXQLkerf+5gAXIBa4ANP01R/t3"
            + "IxaoDq6AE1j6+10FJsDnNXAEOHOwP19vAjZgCMd2dvqvQjfQtaASwGbEusfUg0oAcwzW24hX"
            + "b7wAqEN/alntAJUATwOxvrudoBLgBlaP055kvq+8AQVY+vvfApUAzp1o3fgZqATA03/ye1AJ"
            + "gKf/rCZQCWCOxTot7QCVAG/Q9F/fDSoBKtD0z3OBWoDEoV3jtq8yYZnsJ/SEaq/X7tQ9BEAA"
            + "BEAABEAAtOLffARAAARAAARAAARAAARAAARgpAF/AepxcQuibdtNAAAAAElFTkSuQmCC";

    /** 探测结果。{@code levels} 为空且 {@code error} 非空表示整轮失败。 */
    static final class Report {
        final List<String> levels = new ArrayList<String>();
        /** 视觉：true 支持 / false 不支持 / null 未能判定 */
        Boolean vision;
        String visionNote = "";
        /** 服务端对所有档位都回 200，档位结论不可区分 */
        boolean unverifiable;
        /** 六档逐个明确被拒：可以据此认定该模型不吃思考参数 */
        boolean levelsRejected;
        /** 整轮失败原因（认证、网络、端点不可用）；为空表示至少走完了探测 */
        String error = "";
        /** 失败前的进展描述，列表里直接显示 */
        String note = "";

        boolean ok() {
            return error == null || error.length() == 0;
        }
    }

    private CapabilityProbe() {
    }

    /**
     * 完整探测：思考六档 + 图片输入。
     *
     * @param stop 每发一次请求前检查，返回 true 则尽快结束（用户点了停止）
     */
    static Report probe(ProviderInfo p, String modelName, Stop stop) {
        Report rep = new Report();
        if (p == null) {
            rep.error = "提供商为空";
            return rep;
        }
        ProviderInfo snap = p.copy();
        if (!Endpoint.isHttpUrl(snap.apiUrl)) {
            rep.error = "Base URL 不是有效的 http(s) 地址";
            return rep;
        }
        if (snap.apiKey == null || snap.apiKey.trim().length() == 0) {
            rep.error = "未填写 API Key";
            return rep;
        }
        String model = modelName == null ? "" : modelName.trim();
        if (model.length() == 0) {
            rep.error = "模型 ID 为空";
            return rep;
        }

        String url = Endpoint.chatUrl(snap);
        int rejects = 0;
        int accepts = 0;

        // ---- 思考档位，逐档 ----
        for (int i = 0; i < ThinkingLevels.ORDER.size(); i++) {
            if (stop != null && stop.stopped()) {
                rep.note = "已停止";
                return finish(rep, rep.levels, rep.vision, rep.unverifiable);
            }
            String level = ThinkingLevels.ORDER.get(i);
            Raw r = post(url, snap.apiKey, thinkingBody(snap, model, level));
            if (r.error != null) {
                // 网络层失败：后面的档位也是同样的错，直接收工
                rep.error = ProviderTester.explainNetwork(snap, r.error);
                if (rep.levels.size() > 0) rep.note = "已探测 " + rep.levels.size() + " 档后中断";
                return rep;
            }
            if (r.httpCode >= 200 && r.httpCode < 300) {
                accepts++;
                rep.levels.add(level);
                continue;
            }
            if (rejects == 0 && !isRejection(r.httpCode)) {
                // 第一个档位就撞上 401/403/404/429/5xx：不是档位问题，整轮没意义
                rep.error = ProviderTester.describeFailure(snap, r.httpCode, r.body, model);
                return rep;
            }
            rejects++;
        }

        rep.unverifiable = accepts == ThinkingLevels.ORDER.size();

        // ---- 图片输入 ----
        // 与档位结论互不影响：不支持思考参数的模型也可能能看图，所以六档全被拒绝时
        // 图片这一项照样要探，不能跟着一起作废。
        if (stop == null || !stop.stopped()) {
            probeVision(rep, url, snap, model);
        }

        if (rep.levels.isEmpty()) {
            rep.levelsRejected = true;
            rep.error = "六档全部被拒绝，该模型不支持思考参数";
        }
        return finish(rep, rep.levels, rep.vision, rep.unverifiable);
    }

    /** 图片输入探测：发图、读回复、按内容判定。 */
    private static void probeVision(Report rep, String url, ProviderInfo snap, String model) {
        Raw v = post(url, snap.apiKey, visionBody(snap, model));
        if (v.error != null) {
            rep.visionNote = "图片输入未能判定：" + ProviderTester.explainNetwork(snap, v.error);
            return;
        }
        if (v.httpCode >= 200 && v.httpCode < 300) {
            VisionJudge.Result j = VisionJudge.judge(v.body);
            rep.vision = j.supported;
            rep.visionNote = j.note;
            return;
        }
        if (visionRejection(v.httpCode)) {
            rep.vision = Boolean.FALSE;
            rep.visionNote = "图片输入被拒绝：" + ProviderTester.shortError(v.body);
            return;
        }
        rep.visionNote = "图片输入未能判定："
                + ProviderTester.describeFailure(snap, v.httpCode, v.body, model);
    }

    /** 探测收尾：把结论整理成一句人能看懂的备注。 */
    private static Report finish(Report rep, List<String> levels, Boolean vision,
            boolean unverifiable) {
        StringBuilder sb = new StringBuilder();
        if (levels != null && levels.size() > 0) {
            sb.append("思考可用：").append(ThinkingLevels.describe(levels));
            if (unverifiable) sb.append("（端点未校验档位取值，六档均被接受）");
        }
        if (vision != null) {
            if (sb.length() > 0) sb.append("；");
            sb.append(vision.booleanValue() ? "支持图片输入" : "不支持图片输入");
        }
        if (rep.visionNote.length() > 0) {
            if (sb.length() > 0) sb.append("；");
            sb.append(rep.visionNote);
        }
        if (rep.note.length() > 0) {
            if (sb.length() > 0) sb.append("；");
            sb.append(rep.note);
        }
        rep.note = sb.toString();
        if (rep.error == null) rep.error = "";
        return rep;
    }

    /** 400/422 视为「参数不被接受」；其余 4xx/5xx 不是档位本身的问题。 */
    private static boolean isRejection(int code) {
        return code == 400 || code == 422;
    }

    /** 图片请求里，这几个状态码表示「不接受图片」；其余 4xx/5xx 不归因于图片本身。 */
    private static boolean visionRejection(int code) {
        return code == 400 || code == 413 || code == 415 || code == 422;
    }

    // ---- 请求体 ----

    /**
     * 按提供商声明的思考参数形态生成单档请求体。
     *
     * <p>与 {@link ChatTransport#buildRequestBody} 保持同一套写法，否则探测通过、
     * 真实聊天却 400，结论就没意义了。
     */
    static String thinkingBody(ProviderInfo p, String model, String level) {
        try {
            JSONObject body = baseBody(model, THINKING_MAX_TOKENS);
            if ("boolean".equals(p.thinkingType)) {
                body.put(p.thinkingParamName, true);
                body.put("thinking_budget", ThinkingLevels.budget(level));
            } else {
                JSONObject t = new JSONObject();
                t.put("type", "enabled");
                body.put(p.thinkingParamName, t);
                String effort = ThinkingLevels.reasoningEffort(level);
                if (effort != null) body.put("reasoning_effort", effort);
            }
            return body.toString();
        } catch (Exception e) {
            return "{\"model\":\"" + model + "\",\"max_tokens\":" + THINKING_MAX_TOKENS
                    + ",\"stream\":false,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
        }
    }

    /** 带一张「42」图片的请求体，{@code content} 用 OpenAI 的多模态数组格式。 */
    static String visionBody(ProviderInfo p, String model) {
        try {
            JSONObject body = baseBody(model, VISION_MAX_TOKENS);
            JSONArray content = new JSONArray();
            JSONObject text = new JSONObject();
            text.put("type", "text");
            text.put("text", VISION_ASK);
            content.put(text);
            JSONObject img = new JSONObject();
            img.put("type", "image_url");
            JSONObject u = new JSONObject();
            u.put("url", "data:image/png;base64," + PROBE_IMAGE_BASE64);
            img.put("image_url", u);
            content.put(img);
            JSONObject msg = new JSONObject();
            msg.put("role", "user");
            msg.put("content", content);
            JSONArray msgs = new JSONArray();
            msgs.put(msg);
            body.put("messages", msgs);
            return body.toString();
        } catch (Exception e) {
            LogUtil.w(TAG, "build vision body failed: %s", e.getMessage());
            return null;
        }
    }

    private static JSONObject baseBody(String model, int maxTokens) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("stream", false);
        JSONArray msgs = new JSONArray();
        JSONObject m = new JSONObject();
        m.put("role", "user");
        m.put("content", "hi");
        msgs.put(m);
        body.put("messages", msgs);
        return body;
    }

    // ---- HTTP ----

    private static final class Raw {
        int httpCode;
        String body;
        String error;
    }

    private static Raw post(String url, String apiKey, String body) {
        Raw out = new Raw();
        if (body == null) {
            out.error = "IllegalArgument: 请求体为空";
            return out;
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            // TLS 兼容必须在任何建连动作之前（getOutputStream 当场握手，之后再换就晚了）
            if (conn instanceof HttpsURLConnection) {
                TlsCompat.apply((HttpsURLConnection) conn);
            }
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Accept-Encoding", "identity");
            if (apiKey != null && apiKey.trim().length() > 0) {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
            }
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] bytes = body.getBytes("UTF-8");
            conn.setFixedLengthStreamingMode(bytes.length);
            OutputStream os = conn.getOutputStream();
            os.write(bytes);
            os.flush();
            os.close();
            out.httpCode = conn.getResponseCode();
            InputStream in = out.httpCode >= 400 ? conn.getErrorStream() : conn.getInputStream();
            out.body = read(in);
            LogUtil.d(TAG, "probe POST -> %d (body %d bytes, resp %d chars)",
                    out.httpCode, bytes.length, out.body == null ? 0 : out.body.length());
        } catch (Exception e) {
            out.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            LogUtil.w(TAG, "probe POST failed: %s", out.error);
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
            char[] buf = new char[2048];
            int n;
            while ((n = r.read(buf)) > 0) {
                sb.append(buf, 0, n);
                if (sb.length() > 8192) break; // 探测只看状态码，正文留一段错误信息就够
            }
        } finally {
            r.close();
        }
        return sb.toString();
    }

    /** 用户点停止时返回 true，探测在下一个请求前退出。 */
    interface Stop {
        boolean stopped();
    }
}
