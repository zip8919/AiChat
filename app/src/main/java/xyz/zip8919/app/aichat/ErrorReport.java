package xyz.zip8919.app.aichat;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 请求失败上下文的组装。这些内容只用于错误卡片的「查看详情」与落盘排查，
 * 永远不会进入任何 API 请求体——粘进对话里既费 token，也可能把密钥带出去。
 */
final class ErrorReport {

    private static final int BODY_PREVIEW = 2000;

    private ErrorReport() {
    }

    /**
     * @param round   出错时的轮次（从 0 开始）
     * @param httpCode HTTP 状态；{@code <= 0} 表示没拿到响应（连接失败/超时）
     */
    static String build(String providerName, String modelName, String endpoint, int round,
            int httpCode, String responseBody, Exception e) {
        StringBuilder sb = new StringBuilder();
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        sb.append("时间：").append(fmt.format(new Date())).append('\n');
        sb.append("提供商：").append(providerName == null ? "-" : providerName).append('\n');
        sb.append("模型：").append(modelName == null ? "-" : modelName).append('\n');
        sb.append("端点：").append(endpoint == null ? "-" : endpoint).append('\n');
        sb.append("轮次：第 ").append(round + 1).append(" 轮\n");
        if (httpCode > 0) {
            sb.append("HTTP 状态：").append(httpCode).append('\n');
        } else {
            sb.append("HTTP 状态：未收到响应\n");
        }
        String providerMsg = providerMessage(responseBody);
        if (providerMsg.length() > 0) {
            sb.append("提供商错误：").append(providerMsg).append('\n');
        }
        if (e != null) {
            sb.append("异常类型：").append(e.getClass().getName()).append('\n');
            String msg = e.getMessage();
            sb.append("异常消息：").append(msg == null || msg.length() == 0 ? "(空)" : msg).append('\n');
        }
        String hint = sslHint(e);
        if (hint.length() > 0) {
            sb.append('\n').append(hint).append('\n');
        }
        if (responseBody != null && responseBody.trim().length() > 0) {
            String body = responseBody.length() > BODY_PREVIEW
                    ? TextKit.cut(responseBody, BODY_PREVIEW) + "…" : responseBody;
            sb.append("\n响应体（前 ").append(BODY_PREVIEW).append(" 字）：\n").append(body);
        }
        return sb.toString();
    }

    /**
     * 证书校验失败时给出可操作的下一步。
     *
     * <p>默认严格校验之后，Android 4.x 上最容易踩的坑是「系统根证书库太老」——
     * 站点用了 2015 年之后才有的根证书，握手直接失败。不提示的话用户只能看到
     * 一串 {@code javax.net.ssl.SSLHandshakeException}，无从下手。
     */
    private static String sslHint(Exception e) {
        if (e == null || NetSecurity.trustAllCerts()) return "";
        Throwable t = e;
        for (int depth = 0; t != null && depth < 6; depth++) {
            String n = t.getClass().getName();
            if (n.indexOf("SSL") >= 0 || n.indexOf("Cert") >= 0) {
                return "提示：TLS 证书校验失败。老系统（Android 4.x）的根证书库可能不认该站点的证书。"
                        + "确认端点可信后，可在「设置 → 信任所有 HTTPS 证书」中临时开启再试。";
            }
            t = t.getCause();
        }
        return "";
    }

    /** 尽量从 OpenAI 风格错误体里取出可读的 error.message。 */
    static String providerMessage(String body) {
        if (body == null || body.trim().length() == 0) return "";
        try {
            JSONObject json = new JSONObject(body);
            JSONObject err = json.optJSONObject("error");
            if (err != null) {
                String m = err.optString("message", "");
                if (m.length() > 0) return m;
                return err.toString();
            }
            String m = json.optString("message", "");
            if (m.length() > 0) return m;
        } catch (Exception ignored) {
            // 非 JSON：走下面的纯文本兜底
        }
        String one = body.replace('\n', ' ').replace('\r', ' ').trim();
        return one.length() > 300 ? TextKit.cut(one, 300) + "…" : one;
    }
}
