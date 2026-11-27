package xyz.zip8919.app.aichat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把配置文本里的密钥与敏感字段打码，供反馈附件上传使用。
 *
 * <p>规则：字段值只保留前 5 位与后 3 位，中间用 {@code ***} 代替；
 * 长度不足 8 位整段替换——首尾一露就基本等于原文。
 *
 * <p>为什么先按 JSON 字段名处理、再做文本正则：{@code config.json} 的结构固定，
 * 按字段名能准确命中 {@code api_key} / {@code secret_key}，也不会误伤
 * {@code keyboard} 这类名字里带 key 的普通字段；而地址查询串、{@code sk-} 前缀
 * 的碎片藏在不固定的位置，只能靠正则兜底。两项都做完才算处理完。
 *
 * <p>纯函数、不碰 Android：这个类要能在 JVM 里直接断言，
 * 打码规则写错的代价是密钥外泄，不能只靠人工看一眼。
 */
final class SecretMask {

    /** 短于这个长度的密钥不做首尾保留，否则等于原样上传。 */
    private static final int KEEP_MIN = 8;

    private static final Pattern P_BEARER = Pattern.compile(
            "(?i)(bearer\\s+)([A-Za-z0-9._~+/=\\-]{10,})");
    private static final Pattern P_SK = Pattern.compile(
            "(sk-[A-Za-z0-9_\\-]{8,})");
    private static final Pattern P_QUERY = Pattern.compile(
            "(?i)((?:api[_-]?key|access[_-]?token|secret|token|password)=)([^&\\s\"']{6,})");

    private SecretMask() {
    }

    /**
     * 对配置文本打码：能解析成 JSON 就按字段名处理，否则退化为纯文本正则。
     * 解析失败不抛异常，保证「文件再坏也能带上」。
     */
    static String maskText(String text) {
        if (text == null || text.length() == 0) return text == null ? "" : text;
        String out = text;
        try {
            JSONObject root = new JSONObject(text);
            maskNode(root);
            out = root.toString(2);
        } catch (Throwable t) {
            // 不是合法 JSON（手工改坏 / 非 JSON 配置）：保持原文，交给下面的正则兜底
        }
        out = maskPattern(out, P_BEARER, 2);
        out = maskPattern(out, P_SK, 0);
        out = maskPattern(out, P_QUERY, 2);
        return out;
    }

    /** 保留前 5 位与后 3 位；空值保持空，短值整段替换。 */
    static String maskValue(String value) {
        if (value == null) return "";
        int n = value.length();
        if (n == 0) return "";
        if (n <= KEEP_MIN) return "***";
        return value.substring(0, 5) + "***" + value.substring(n - 3);
    }

    private static void maskNode(Object node) throws JSONException {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            List<String> keys = new ArrayList<String>();
            Iterator<String> it = o.keys();
            while (it.hasNext()) keys.add(it.next());
            for (int i = 0; i < keys.size(); i++) {
                String k = keys.get(i);
                Object v = o.opt(k);
                if (v instanceof String && isSecretKey(k)) {
                    o.put(k, maskValue((String) v));
                } else {
                    maskNode(v);
                }
            }
            return;
        }
        if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) maskNode(a.opt(i));
        }
    }

    /** 字段名归一后精确比对，避免把 {@code keyboard} 这类无关字段当成密钥。 */
    private static boolean isSecretKey(String name) {
        if (name == null) return false;
        String k = name.toLowerCase(Locale.US).replace("_", "").replace("-", "");
        return k.equals("apikey") || k.equals("key") || k.equals("token")
                || k.equals("accesstoken") || k.equals("refreshtoken")
                || k.equals("secret") || k.equals("secretkey") || k.equals("secretid")
                || k.equals("password") || k.equals("passwd")
                || k.equals("authorization") || k.equals("auth")
                || k.equals("cookie") || k.equals("session");
    }

    /** 对正则命中的分组做打码；group 是 capture group 下标，0 表示整段。 */
    private static String maskPattern(String text, Pattern p, int group) {
        Matcher m = p.matcher(text);
        StringBuffer sb = new StringBuffer(text.length());
        while (m.find()) {
            String hit = m.group(group);
            String whole = m.group();
            int start = m.start(group) - m.start();
            String replaced = whole.substring(0, start) + maskValue(hit)
                    + whole.substring(start + hit.length());
            m.appendReplacement(sb, Matcher.quoteReplacement(replaced));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
