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
 * <p><b>为什么单轮单图也不够</b>：没有视觉能力的模型看不到图也会顺着「图中数字是
 * 多少」编一个数。只问一次，答对只说明它猜中了，说明不了它看到了。所以判据是
 * <b>换一张图再问一次，两张图都答对</b>。两轮用同样的话术，只有图片不同。
 *
 * <p>纯字符串处理，不碰网络与 Android，便于单测。
 */
final class VisionJudge {

    /** 第一张图里的数字。 */
    static final String ANSWER_A = "93";
    static final String ANSWER_A_CN = Strings.get(R.string.visionjudge_001);
    /** 第二张图里的数字。 */
    static final String ANSWER_B = "7";
    static final String ANSWER_B_CN = Strings.get(R.string.visionjudge_002);

    /** 单轮结果。{@code correct} 为 null 表示这一轮读不出结论。 */
    static final class Round {
        Boolean correct;
        String note = "";
    }

    /** 两轮汇总结果。{@code supported} 为 null 表示未能判定。 */
    static final class Result {
        Boolean supported;
        String note = "";
    }

    /** 模型明说看不到图的话术，单独认出来，好让备注说清原因。 */
    private static final String[] NO_IMAGE_WORDS = {
            Strings.get(R.string.visionjudge_003), Strings.get(R.string.visionjudge_004), Strings.get(R.string.visionjudge_005), Strings.get(R.string.visionjudge_006), Strings.get(R.string.visionjudge_007), Strings.get(R.string.visionjudge_008),
            Strings.get(R.string.visionjudge_009), Strings.get(R.string.visionjudge_010), Strings.get(R.string.visionjudge_011), Strings.get(R.string.visionjudge_012), Strings.get(R.string.visionjudge_013), Strings.get(R.string.visionjudge_014),
            Strings.get(R.string.visionjudge_015), Strings.get(R.string.visionjudge_016),
            "cannot see", "can't see", "can not see", "unable to see", "not able to see",
            "no image", "not an image", "don't see an image", "do not see an image"
    };

    private VisionJudge() {
    }

    /**
     * 两轮汇总。
     *
     * <p>任一轮明确答错就判不支持 —— 能看图的模型不会把 93 读成别的东西。
     * 两轮都读不出正文才记未能判定（备注以「未能判定」开头），此时不动用户配置。
     */
    static Result combine(Round a, Round b) {
        Result out = new Result();
        if (Boolean.FALSE.equals(a.correct) || Boolean.FALSE.equals(b.correct)) {
            out.supported = Boolean.FALSE;
            Round bad = Boolean.FALSE.equals(a.correct) ? a : b;
            out.note = bad.note.length() > 0 ? bad.note : Strings.get(R.string.visionjudge_017);
            return out;
        }
        if (Boolean.TRUE.equals(a.correct) && Boolean.TRUE.equals(b.correct)) {
            out.supported = Boolean.TRUE;
            return out;
        }
        out.note = a.correct == null && a.note.length() > 0 ? a.note : b.note;
        return out;
    }

    /**
     * 单轮判定：回复里有没有正确报出这一轮图里的数字。
     *
     * <p>阿拉伯数字按「完整数字串」比对：{@code 93} 不能出现在 {@code 193}、{@code 9.3}、
     * {@code 93%} 里面，否则模型答个长度、百分比都会被当成读对了。
     */
    static Round judge(String body, String answer, String answerCn) {
        Round out = new Round();
        String text = replyText(body);
        if (text == null) {
            out.note = Strings.get(R.string.visionjudge_018);
            return out;
        }
        String t = text.trim();
        if (t.length() == 0) {
            out.note = Strings.get(R.string.visionjudge_019);
            return out;
        }
        if (mentionsNoImage(t)) {
            out.correct = Boolean.FALSE;
            out.note = Strings.get(R.string.visionjudge_020);
            return out;
        }
        if (containsNumber(t, answer) || t.indexOf(answerCn) >= 0) {
            out.correct = Boolean.TRUE;
            return out;
        }
        out.correct = Boolean.FALSE;
        out.note = Strings.get(R.string.visionjudge_021) + answer;
        return out;
    }

    /** 数字串前后不能紧挨着别的数字或小数点，避免 193 / 9.3 / 93.5 命中 93。 */
    static boolean containsNumber(String text, String number) {
        if (number == null || number.length() == 0) return false;
        int from = 0;
        while (true) {
            int i = text.indexOf(number, from);
            if (i < 0) return false;
            int before = i - 1;
            int after = i + number.length();
            boolean beforeOk = before < 0 || !isDigitOrDot(text.charAt(before));
            boolean afterOk = after >= text.length() || !isDigitOrDot(text.charAt(after));
            if (beforeOk && afterOk) return true;
            from = i + 1;
        }
    }

    private static boolean isDigitOrDot(char c) {
        if (c >= '0' && c <= '9') return true;
        return c == '.' || c == ',' || c == '．' || c == '，';
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
