package xyz.zip8919.app.aichat;

import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 图片输入判定：看模型能不能读出图里的数字。
 *
 * <p><b>为什么不能只看状态码</b>：状态码只说明端点收下了请求，说明不了模型有没有
 * 看到像素。把图片悄悄丢掉、照样正常回话的端点会稳定返回 200，按状态码判就会把
 * 没有视觉能力的模型判成支持。
 *
 * <p>纯字符串处理，不碰网络与 Android，便于单测。
 */
final class VisionJudge {

    /** 探测图里写的数字，模型答出它才算真的读到了图。 */
    static final String ANSWER = "42";
    static final String ANSWER_CN = "四十二";

    /** 模型明说看不到图的话术，单独认出来，好让备注说清原因。 */
    private static final String[] NO_IMAGE_WORDS = {
            "看不到", "看不见", "无法查看", "无法看到", "无法识别图片", "无法读取图片",
            "没有图片", "没有收到图片", "未收到图片", "没有提供图片", "请提供图片", "请上传图片",
            "不是图片", "不是一个图片",
            "cannot see", "can't see", "can not see", "unable to see", "not able to see",
            "no image", "not an image", "don't see an image", "do not see an image"
    };

    /** 判定结果。{@code supported} 为 null 表示这一轮读不出结论。 */
    static final class Result {
        Boolean supported;
        String note = "";
    }

    private VisionJudge() {
    }

    /**
     * 按回复内容判定。读不出正文（响应残缺、空回复）时不下结论，
     * 有正文但对不上数字时判不支持 —— 宁可漏判，也不要把没视觉的模型标成支持。
     */
    static Result judge(String body) {
        Result out = new Result();
        String text = replyText(body);
        if (text == null) {
            out.note = "图片输入未能判定：响应里读不到回复正文";
            return out;
        }
        String t = text.trim();
        if (t.length() == 0) {
            out.note = "图片输入未能判定：回复正文为空";
            return out;
        }
        if (mentionsNoImage(t)) {
            out.supported = Boolean.FALSE;
            out.note = "模型表示看不到图片";
            return out;
        }
        if (t.indexOf(ANSWER) >= 0 || t.indexOf(ANSWER_CN) >= 0) {
            out.supported = Boolean.TRUE;
            return out;
        }
        out.supported = Boolean.FALSE;
        out.note = "回复里没有出现图内数字";
        return out;
    }

    /** 取 choices[0] 的回复正文；兼容字符串、分块数组与旧式 text 字段。 */
    static String replyText(String body) {
        if (body == null || body.length() == 0) return null;
        try {
            JSONObject o = new JSONObject(body);
            JSONArray arr = o.optJSONArray("choices");
            if (arr == null || arr.length() == 0) return null;
            JSONObject c0 = arr.optJSONObject(0);
            if (c0 == null) return null;
            JSONObject msg = c0.optJSONObject("message");
            if (msg == null) {
                String legacy = c0.optString("text", "");
                return legacy.length() > 0 ? legacy : null;
            }
            Object content = msg.opt("content");
            if (content == null || content == JSONObject.NULL) return null;
            if (content instanceof String) return (String) content;
            if (content instanceof JSONArray) {
                JSONArray parts = (JSONArray) content;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < parts.length(); i++) {
                    JSONObject part = parts.optJSONObject(i);
                    if (part == null) continue;
                    String ty = part.optString("type", "");
                    if (ty.length() > 0 && !"text".equals(ty)) continue;
                    sb.append(part.optString("text", ""));
                }
                return sb.toString();
            }
            return content.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean mentionsNoImage(String text) {
        String low = text.toLowerCase(Locale.US);
        for (int i = 0; i < NO_IMAGE_WORDS.length; i++) {
            if (low.indexOf(NO_IMAGE_WORDS[i]) >= 0) return true;
        }
        return false;
    }
}
