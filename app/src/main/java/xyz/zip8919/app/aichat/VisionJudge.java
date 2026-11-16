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
 * <p><b>只判不译</b>：本类只给结论（{@code correct} / {@code supported}）和原因码
 * （{@code REASON_*}），不产出人话。原因码翻成界面文字由调用方做。这样本类不碰
 * {@code Context}、不碰资源，纯粹是字符串与 JSON 处理，能在 JVM 里直接断言。
 *
 * <p><b>这里的字面量故意硬编码</b>：图内数字的中文写法、模型说「看不到图」的话术，
 * 都是判定依据而不是界面文案。它们一旦被当成文案改写或翻译，判定就会静默失效
 * （把「九十三」改成「93」、把「看不到」译成英文都会改变判定结果），所以不放进
 * {@code strings_ui.xml}。
 */
final class VisionJudge {

    /** 第一张图里的数字。 */
    static final String ANSWER_A = "93";
    /** 同一个数字的中文写法：模型用汉字作答时也要认。 */
    static final String ANSWER_A_CN = "九十三";
    /** 第二张图里的数字。 */
    static final String ANSWER_B = "7";
    /** 「7」的中文写法。 */
    static final String ANSWER_B_CN = "七";

    /** 没有原因可报。 */
    static final int REASON_NONE = 0;
    /** 响应里读不到回复正文。 */
    static final int REASON_NO_BODY = 1;
    /** 回复正文为空。 */
    static final int REASON_EMPTY = 2;
    /** 模型表示看不到图片。 */
    static final int REASON_NO_IMAGE = 3;
    /** 回复里没有出现图内数字。 */
    static final int REASON_NO_DIGIT = 4;

    /** 单轮结果。{@code correct} 为 null 表示这一轮读不出结论。 */
    static final class Round {
        Boolean correct;
        /** 判定原因，取值 {@code REASON_*}。 */
        int reason = REASON_NONE;
        /** 原因要用的参数：答错时是图内数字。 */
        String arg = "";
        /**
         * 调用方自己补的说明（网络失败、端点拒绝）。
         *
         * <p>本类从不填写这个字段，留着是为了让调用方在同一次传递里带上自己的原因，
         * 不必再包一层类型。
         */
        String note = "";
    }

    /** 两轮汇总结果。{@code supported} 为 null 表示未能判定。 */
    static final class Result {
        Boolean supported;
        int reason = REASON_NONE;
        String arg = "";
        String note = "";
    }

    /** 模型明说看不到图的话术，单独认出来，好让备注说清原因。 */
    private static final String[] NO_IMAGE_WORDS = {
            "看不到", "看不见", "无法查看", "无法看到", "无法识别图片", "无法读取图片",
            "没有图片", "没有收到图片", "未收到图片", "没有提供图片", "请提供图片", "请上传图片",
            "不是图片", "不是一个图片",
            "cannot see", "can't see", "can not see", "unable to see", "not able to see",
            "no image", "not an image", "don't see an image", "do not see an image"
    };

    private VisionJudge() {
    }

    /**
     * 两轮汇总。
     *
     * <p>任一轮明确答错就判不支持 —— 能看图的模型不会把 93 读成别的东西。
     * 两轮都读不出正文才记未能判定，此时不动用户配置。
     */
    static Result combine(Round a, Round b) {
        Result out = new Result();
        if (Boolean.FALSE.equals(a.correct) || Boolean.FALSE.equals(b.correct)) {
            out.supported = Boolean.FALSE;
            Round bad = Boolean.FALSE.equals(a.correct) ? a : b;
            // 没有具体原因时按「没读对」报，不然界面只剩一句「不支持」说不清为什么
            out.reason = bad.reason != REASON_NONE ? bad.reason : REASON_NO_DIGIT;
            out.arg = bad.arg;
            out.note = bad.note;
            return out;
        }
        if (Boolean.TRUE.equals(a.correct) && Boolean.TRUE.equals(b.correct)) {
            out.supported = Boolean.TRUE;
            return out;
        }
        // 至少一轮没读出结论：优先带上有依据的那一轮，两次都空就随便取一个
        Round miss = null;
        if (a.correct == null && (a.reason != REASON_NONE || a.note.length() > 0)) {
            miss = a;
        } else if (b.correct == null && (b.reason != REASON_NONE || b.note.length() > 0)) {
            miss = b;
        }
        if (miss == null) miss = a.correct == null ? a : b;
        out.reason = miss.reason;
        out.arg = miss.arg;
        out.note = miss.note;
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
            out.reason = REASON_NO_BODY;
            return out;
        }
        String t = text.trim();
        if (t.length() == 0) {
            out.reason = REASON_EMPTY;
            return out;
        }
        if (mentionsNoImage(t)) {
            out.correct = Boolean.FALSE;
            out.reason = REASON_NO_IMAGE;
            return out;
        }
        if (containsNumber(t, answer) || t.indexOf(answerCn) >= 0) {
            out.correct = Boolean.TRUE;
            return out;
        }
        out.correct = Boolean.FALSE;
        out.reason = REASON_NO_DIGIT;
        out.arg = answer;
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
