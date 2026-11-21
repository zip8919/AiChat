package xyz.zip8919.app.aichat;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
    /**
     * 图片探测的等待时间比档位探测长。
     *
     * <p>开着思考的模型看图会先想一段，常见 10 到 30 秒；按 20 秒掐断会把正常模型
     * 判成「回复正文为空」，所以图片请求单独放宽。
     */
    private static final int VISION_TIMEOUT_MS = 60000;
    private static final int VISION_MAX_TOKENS = 24;

    /**
     * 提问，逼模型直接报数字；判据见 {@link VisionJudge}。
     *
     * <p>两轮探测用同一句话：靠换图换答案，不靠换问法，避免模型因为提示不同而
     * 给出不同表现。
     *
     * <p>这是发给模型的文本，不属于界面文案，所以不进资源文件（与工具描述、系统
     * 提示词同一类），也避免静态字段在 {@code Strings} 初始化之前取不到值。
     */
    private static final String VISION_ASK = "图中的数字是多少？只回答数字，不要解释。";

    /**
     * 探测图 A：128×128 白底黑字「93」，灰度 PNG，约 1.4 KB；B 为「7」，约 0.7 KB。
     *
     * <p>以前用 1×1 空白图，只能证明「端点不拒绝图片块」，很多中转会把图片丢掉
     * 照样回话，于无视觉能力的模型也被判成支持。改成看图报数：读不出数字就是
     * 没看到图。
     *
     * <p><b>为什么必须两轮换图</b>：没有视觉能力的模型看不到图也会顺着问题编一个
     * 数字，个别模型还会在常见答案上碰运气。单轮单图无法区分「读到了」和
     * 「猜对了」。两张图答出两个不同的数字，靠猜同时命中的概率才够低。
     */
    static final String IMAGE_A_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAIAAAACACAAAAADmVT4XAAAFQ0lEQVR42u2ab0yVZRTAH+QK"
            + "F8Eul5syjcmfwAzk4mgWWcMtB0atD0GuDG2tEU1Xa42iaY7WlOHaoA/JcrXabEojw3KZozZN"
            + "wRoDJBTRC947hbQL9wJ2hfsP7uXtQ7bdc+77vs95X9z80PN8Yec9zznvj+fPec5z3hsjsXvb"
            + "FjEBIAAEgAAQAAJAAAgAASAABIAAEAACQAAIgHvcDGrKge4+x6jbFzAmmjKz1xWv4Xqbsdvt"
            + "znGXyxMMBOcWxyWaU9LSH7LmG9VsJKXWuyMNdX3g3YuKvaVrbbufXiH/iviNDXZFOyWAnx+T"
            + "9VXWo9D/Kmdsio9rAhjdrOjpjdu6ABh7op8O8O19Ko7S+/UBsPiDVIC9MaqOkk7qA2BsLw2g"
            + "jucn7hedAOwwBeAzvp+kXp0AS2/yAbrjCI6yPPoAWBUXYDaP5KhSJ4BxkgfQhCzu/+Ccc9bV"
            + "W5+Bnp+RBzCVvNV8avDPv0PTzq6vticTVgECCKBg9uKtOwr/21CxLhwFYCj9uCcEvTUmc+cA"
            + "ARyC3csjXoMIjkFD++OfumQ22dCDCGADB6AU9DaPR6j82UD3pERql9BBlKEO4IGHYw1QHoCu"
            + "LtAIqtGawnqYD3SFgPgykF6CfVtp6/55KCaoJyTdsHMBEC05egDQ9rGoA9iBlBsL+1qB5HCQ"
            + "AEKqPBhgBEjLUF8knyUBjKBDWR3AAyM36osO6U4SwDEolqoD+LWkkxcpnQa/gYmRVUtWPI3k"
            + "21C0Eb74Ocrh/1THScsTgeRGfSeg6Bvlvd7buH4YPNixiZOWw2V2ORyrOuY30hVfHfDdcgyd"
            + "PoXGsOwTXlq+E+r6gHIS7UrWJhP4KlUGZGtAxgBOQT60gAuoNYwcjmm6AqW2tMRzLyZokFPc"
            + "kWframy6X8MIrDngJ90L0KxuiTiOa6KcfkQGyOuk3ozex9P2X/IXiH4/20UfAUPJ0XkSwAi+"
            + "rC7b89vY7MT5hkwZr7WaFmFuG+lm9JqGZfWhxl2wzcPdBYw1JNMBjBpLAYeLXPwCxfLP6Q4T"
            + "tBYjrjw1ya+QbHmP7G+55nLI4OuUAkU11d0Zxb0Vnh6z/dj0ginapoVyO64jAgzzEtK57wux"
            + "zep5Sn3gRKr8oqsHWeHiICEpbsQHSDupQjJVIxO2S4bmwMXVSkrLW5CXV4k1ovF9MPQnVF2Q"
            + "JBt4tJ12MXgFAuQgdYxyWmPr6BkenfLFWiwri4qLljDGjmyL1DfvJK2UvkegPJlCLNPJtApt"
            + "a/BOQ7t1UD0SqjV/O8jwc4hmq6A4pb9U+7U3UqrQWQ32Mr1T4IW1gwGqHSo5dOiegnpnpLR+"
            + "LdFswAlls94RaIXVw6NUuyo0IT5aHIhqJ+HhmwdC6ju7rivZ/YBjMTcQFTaNR7sJ7UbV0+Mo"
            + "DVn03AnZyPwFrvq9yQWIZYZnmmF53X8QZ8TlMnnQ0opDbvh4/teNUUviNDcSGsKMMZb9aP7a"
            + "LJPJ6JlydXeeRXuXmS+thMnWkX//xmQVWK1ppuRk5p25Zuv96a+o9z98mZsPxBJ2druGTBC2"
            + "7yTCFHDbfkkvQJl0NwBqJb0Aq9x3A6BW0guw4oq0cID4LyW9AHlXpYUD5HZLOgFiqr3SggES"
            + "981KOgGKu6hfzZQBzHsmFOItDyCx8nelUB0diEY7Os4Nh6O/tpRufVbxLubr7zv/x1BAXpm5"
            + "qbQsSXlmZHPCgG1w6KbTOekPBKSEJeaMrNwNBdzdId2wO6673K6JmWAwGDLEGVMsqRmZuYUW"
            + "9aUhfl0vAASAABAAAkAACAABIAAEgAAQAAJAAAiA/z3AP08tP8vf9hVuAAAAAElFTkSuQmCC";

    /** 第二张图：数字 7，换图复测用。 */
    static final String IMAGE_B_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAIAAAACACAAAAADmVT4XAAACm0lEQVR42u2Z3UtUQRiH5+yu"
            + "X+WimCkaC4mE1I1ikCAF3RQkFBLSRUQIUZjmt/4VVooSRQQihCAIkhQkKBFKRgWBJLaxtLSy"
            + "BKIsiyxb+bHe/87NmXeGdyHfufyxM+/DszNz5sxxMiq7zacEQAAEQAAEQAAEQAAEQAAEQAAE"
            + "QAC8tAcOoX2Xv0AABEAADjtA8ESWAe4FswuQ0+3xhxkr7VcAx73lsaclA8O7mAx47OlYuStO"
            + "hrYhuTzHugqeYX01qDgN7FTFIan7yroPTMapM8CSgdoVCEI/A5wG5rC+6g2wGrg0D0HRelAx"
            + "GljB+qrNe30bAEMY5HaxPg3jU5jcrGQFGNnBaTWg0914Em6HkpA0vWE9kDxPkndhKwZ2q2OQ"
            + "nP3CeiSbipkJMDZQj0+dkxE/p4EF11Ovz89q4MpbCEpiRzmP5auuc899zfqGAA/RX14n64vJ"
            + "70lMbpezAoz+wxnVrz2GySRMhRKQXHvF+m74ImG4CRka2DsVhaThI+vb8XTUggATA+c+Q1D9"
            + "w8dp4D3WV/0+VgNXX0NQGivgvCEJu849HZT6dADXLlzQoTgBNl5i0nqcFWDsDw7URxyJdiWT"
            + "OobjXCde7hANjG/RX8htLMP9mggk5xeJADQDMxFbAogGGpchqFlzOA18WHbtwtT6tFXQjIOU"
            + "pclXnBQDkVlMOvPJAigAj/YhONKuOAE2JzC5U8IK8CQNgb/X5GitPWvSZTjEDZN7dn0DExvW"
            + "NiHSRpQ5HYbk4jsTAG0Ds2GrAvQNXFiC4Mw3h9PApyWXAKP62qugBftX/DX72qRpIDqDSVeu"
            + "mQBNgMd7EBS2KU6AxDgmd4tZAZ6mIAj0GNa39NmOcyMSAAEQAAEQAAEQAAEQAAEQAAEQgP8O"
            + "4ADjmqorB+8WhgAAAABJRU5ErkJggg==";


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
            rep.error = Strings.get(R.string.capabilityprobe_002);
            return rep;
        }
        ProviderInfo snap = p.copy();
        if (!Endpoint.isHttpUrl(snap.apiUrl)) {
            rep.error = Strings.get(R.string.capabilityprobe_003);
            return rep;
        }
        if (snap.apiKey == null || snap.apiKey.trim().length() == 0) {
            rep.error = Strings.get(R.string.capabilityprobe_004);
            return rep;
        }
        String model = modelName == null ? "" : modelName.trim();
        if (model.length() == 0) {
            rep.error = Strings.get(R.string.capabilityprobe_005);
            return rep;
        }

        String url = Endpoint.chatUrl(snap);

        // ---- 思考档位 ----
        // 候选档位优先取「模型家族知识」：端点对不认识的字段一律回 200，
        // 逐档打请求只能证明「没报错」，证明不了「这一档真的生效」。
        // 家族已知时只探它自家的几档；家族未知时才退回六档全集，且全部 200
        // 只记「不可验证」，不再声称六档都支持（旧版就是把 200 当成支持）。
        ReasoningProfiles.Profile prof = ReasoningProfiles.of(model);
        List<String> candidates = prof.levels.isEmpty()
                ? new ArrayList<String>(ThinkingLevels.ORDER) : prof.levels;
        int accepts = 0;
        boolean evidence = false;
        for (int i = 0; i < candidates.size(); i++) {
            if (stop != null && stop.stopped()) {
                rep.error = Strings.get(R.string.capabilityprobe_006);
                return finish(rep, rep.levels, rep.vision, rep.unverifiable);
            }
            String level = candidates.get(i);
            Raw r = post(url, snap.apiKey, thinkingBody(snap, model, level));
            if (r.error != null) {
                // 网络层失败：后面的档位也是同样的错，直接收工
                rep.error = ProviderTester.explainNetwork(snap, r.error);
                if (rep.levels.size() > 0) rep.note = Strings.get(R.string.capabilityprobe_007, rep.levels.size());
                return rep;
            }
            if (r.httpCode >= 200 && r.httpCode < 300) {
                accepts++;
                rep.levels.add(level);
                if (!evidence && hasReasoningEvidence(r.body)) evidence = true;
                continue;
            }
            if (!isRejection(r.httpCode)) {
                // 401/403/404/429/5xx 不是档位问题：后面的档位也是同样的错，
                // 继续探下去只会把「认证失败」误判成「各档全被拒绝」
                rep.error = ProviderTester.describeFailure(snap, r.httpCode, r.body, model);
                return rep;
            }
        }

        // 全部 200 且回复里没有任何推理痕迹：端点只是没报错，结论不可验证。
        // 家族已知时保留家族档位（用户仍能选到「端点认的那几个词」，
        // 只是强度未必真生效）；家族未知时不把 200 当成支持。
        if (accepts == candidates.size() && !evidence) {
            rep.unverifiable = true;
            if (prof.levels.isEmpty()) rep.levels.clear();
        }

        // ---- 图片输入 ----
        // 与档位结论互不影响：不支持思考参数的模型也可能能看图，所以六档全被拒绝时
        // 图片这一项照样要探，不能跟着一起作废。
        if (stop == null || !stop.stopped()) {
            probeVision(rep, url, snap, model, stop);
        }

        if (rep.levels.isEmpty()) {
            if (rep.unverifiable) {
                // 端点对什么都回 200、回复里也没有推理痕迹：只能说「测不出来」，
                // 不能像旧版那样报成「全部被拒绝」（用户会以为模型不能思考）。
                rep.note = Strings.get(R.string.capabilityprobe_023);
            } else {
                rep.levelsRejected = true;
                rep.error = Strings.get(R.string.capabilityprobe_009);
            }
        }
        return finish(rep, rep.levels, rep.vision, rep.unverifiable);
    }

    /**
     * 图片输入探测：同一句话、换两张图，各问一次。
     *
     * <p>一轮答对说明不了模型看到了图 —— 没视觉能力的模型也会顺着问题编数字，
     * 碰巧一致就是误判。两轮各答对一张，才算支持。
     */
    private static void probeVision(Report rep, String url, ProviderInfo snap, String model,
            Stop stop) {
        VisionJudge.Round a = askNumber(url, snap, model, IMAGE_A_BASE64,
                VisionJudge.ANSWER_A, VisionJudge.ANSWER_A_CN);
        if (Boolean.FALSE.equals(a.correct)) {
            // 第一张就读错了，不可能是视觉模型，省掉第二次请求
            rep.vision = Boolean.FALSE;
            rep.visionNote = roundReason(a);
            return;
        }
        VisionJudge.Round b;
        if (a.correct == null) {
            // 第一张没读出结论：再发一次同一张图，多给一次机会。
            // 模型先想后答时容易「思考用掉额度、正文为空」，一次空回复不足以判死。
            if (stop != null && stop.stopped()) {
                rep.visionNote = Strings.get(R.string.capabilityprobe_010, roundReason(a));
                return;
            }
            b = askNumber(url, snap, model, IMAGE_A_BASE64,
                    VisionJudge.ANSWER_A, VisionJudge.ANSWER_A_CN);
            if (b.correct == null) {
                rep.visionNote = Strings.get(R.string.capabilityprobe_010, roundReason(b));
                return;
            }
            rep.vision = b.correct;
            rep.visionNote = b.correct.booleanValue() ? "" : roundReason(b);
            return;
        }
        if (stop != null && stop.stopped()) {
            rep.visionNote = Strings.get(R.string.capabilityprobe_012);
            return;
        }
        b = askNumber(url, snap, model, IMAGE_B_BASE64,
                VisionJudge.ANSWER_B, VisionJudge.ANSWER_B_CN);
        VisionJudge.Result j = VisionJudge.combine(a, b);
        rep.vision = j.supported;
        rep.visionNote = resultReason(j);
    }

    /**
     * 把单轮的原因翻成人话。
     *
     * <p>调用方自己写的说明（网络失败、端点拒绝）优先，它比原因码具体。
     */
    private static String roundReason(VisionJudge.Round r) {
        if (r.note != null && r.note.length() > 0) return r.note;
        return reasonText(r.reason, r.arg);
    }

    /** 把两轮汇总的原因翻成人话。 */
    private static String resultReason(VisionJudge.Result r) {
        if (r.note != null && r.note.length() > 0) return r.note;
        return reasonText(r.reason, r.arg);
    }

    /** 原因码到界面文案。{@link VisionJudge} 只给码，文字在这里取。 */
    private static String reasonText(int reason, String arg) {
        switch (reason) {
            case VisionJudge.REASON_NO_BODY:
                return Strings.get(R.string.capabilityprobe_019);
            case VisionJudge.REASON_EMPTY:
                return Strings.get(R.string.capabilityprobe_020);
            case VisionJudge.REASON_NO_IMAGE:
                return Strings.get(R.string.capabilityprobe_021);
            case VisionJudge.REASON_NO_DIGIT:
                return Strings.get(R.string.capabilityprobe_022, arg);
            default:
                return "";
        }
    }

    /** 发一张图问数字，返回这一轮的结论；答复里带上端点拒绝、网络失败的原因。 */
    private static VisionJudge.Round askNumber(String url, ProviderInfo snap, String model,
            String imageBase64, String answer, String answerCn) {
        VisionJudge.Round out = new VisionJudge.Round();
        Raw v = post(url, snap.apiKey, visionBody(snap, model, imageBase64), VISION_TIMEOUT_MS);
        if (v.error != null) {
            out.note = ProviderTester.explainNetwork(snap, v.error);
            return out;
        }
        if (v.httpCode >= 200 && v.httpCode < 300) {
            return VisionJudge.judge(v.body, answer, answerCn);
        }
        if (visionRejection(v.httpCode)) {
            out.correct = Boolean.FALSE;
            out.note = Strings.get(R.string.capabilityprobe_013,
                    ProviderTester.shortError(v.body));
            return out;
        }
        out.note = ProviderTester.describeFailure(snap, v.httpCode, v.body, model);
        return out;
    }

    /** 探测收尾：把结论整理成一句人能看懂的备注。 */
    private static Report finish(Report rep, List<String> levels, Boolean vision,
            boolean unverifiable) {
        StringBuilder sb = new StringBuilder();
        if (levels != null && levels.size() > 0) {
            sb.append(Strings.get(R.string.capabilityprobe_014, ThinkingLevels.describe(levels)));
            if (unverifiable) sb.append(Strings.get(R.string.capabilityprobe_015));
        }
        if (vision != null) {
            if (sb.length() > 0) sb.append("；");
            if (vision.booleanValue()) {
                sb.append(Strings.get(R.string.capabilityprobe_016));
            } else {
                sb.append(Strings.get(R.string.capabilityprobe_017));
                if (rep.visionNote.length() > 0) {
                    sb.append("（").append(rep.visionNote).append("）");
                }
            }
        } else if (rep.visionNote.length() > 0) {
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

    /**
     * 回复里有没有「真的思考过」的痕迹。
     *
     * <p>这是逐档 200 之外唯一可用的实证：能关/开思考的端点通常在
     * {@code reasoning_content} 或 {@code completion_tokens_details.reasoning_tokens}
     * 里留下记录。两者都没有时只能说「不可验证」，不能反推支持。
     */
    static boolean hasReasoningEvidence(String body) {
        if (body == null || body.length() == 0) return false;
        String t = body.toLowerCase(Locale.US);
        int at = t.indexOf("reasoning_content");
        if (at >= 0 && fieldHasText(t, at + "reasoning_content".length())) return true;
        at = t.indexOf("\"reasoning\"");
        if (at >= 0 && fieldHasText(t, at + "\"reasoning\"".length())) return true;
        at = t.indexOf("reasoning_tokens");
        if (at >= 0) {
            int p = at + "reasoning_tokens".length();
            while (p < t.length() && (t.charAt(p) == ' ' || t.charAt(p) == ':')) p++;
            int q = p;
            while (q < t.length() && Character.isDigit(t.charAt(q))) q++;
            if (q > p) {
                try {
                    if (Integer.parseInt(t.substring(p, q)) > 0) return true;
                } catch (NumberFormatException ignored) {
                    // 数字过长按「没有」处理，不影响结论
                }
            }
        }
        return false;
    }

    /** 字段值是不是非空字符串（{@code "reasoning_content":"..."} 里有内容）。 */
    private static boolean fieldHasText(String s, int from) {
        int i = from;
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == ':')) i++;
        if (i >= s.length() || s.charAt(i) != '"') return false;
        int j = i + 1;
        while (j < s.length() && s.charAt(j) != '"') {
            if (s.charAt(j) != ' ' && s.charAt(j) != '\n') return true;
            j++;
        }
        return false;
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

    /** 带一张数字图的请求体，{@code content} 用 OpenAI 的多模态数组格式。 */
    static String visionBody(ProviderInfo p, String model, String imageBase64) {
        try {
            JSONObject body = baseBody(model, VISION_MAX_TOKENS);
            disableThinking(p, body);
            JSONArray content = new JSONArray();
            JSONObject text = new JSONObject();
            text.put("type", "text");
            text.put("text", VISION_ASK);
            content.put(text);
            JSONObject img = new JSONObject();
            img.put("type", "image_url");
            JSONObject u = new JSONObject();
            u.put("url", "data:image/png;base64," + imageBase64);
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

    /**
     * 图片探测显式关掉思考。
     *
     * <p>看图任务不需要推理，关掉能让模型直接作答，既快也避开「想完没给正文」的
     * 空回复。关不掉的端点（不认识这个参数）多半会忽略，不影响判定。
     */
    private static void disableThinking(ProviderInfo p, JSONObject body) {
        if (p.thinkingType == null || p.thinkingParamName == null
                || p.thinkingParamName.length() == 0) {
            return;
        }
        try {
            if ("boolean".equals(p.thinkingType)) {
                body.put(p.thinkingParamName, false);
            } else {
                JSONObject off = new JSONObject();
                off.put("type", "disabled");
                body.put(p.thinkingParamName, off);
            }
        } catch (Exception e) {
            LogUtil.w(TAG, "disable thinking failed: %s", e.getMessage());
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
        return post(url, apiKey, body, TIMEOUT_MS);
    }

    private static Raw post(String url, String apiKey, String body, int timeoutMs) {
        Raw out = new Raw();
        if (body == null) {
            out.error = Strings.get(R.string.capabilityprobe_018);
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
            conn.setReadTimeout(timeoutMs);
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
