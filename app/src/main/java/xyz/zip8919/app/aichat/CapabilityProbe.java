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
 * <p><b>判定依据分两层</b>：
 * <ul>
 *   <li><b>端点行为</b>：400/422 明确拒绝这一档，其余状态码不算拒绝；</li>
 *   <li><b>真实痕迹</b>：回复里有没有推理内容（{@code reasoning_content}）
 *       或推理 token 计数。端点对不认识的字段几乎一律回 200，只看状态码会把
 *       「静默忽略」判成「支持」——这正是旧版探测「怎么都不对」的原因。</li>
 * </ul>
 *
 * <p><b>为什么还要分档打请求</b>：同一句话、按档位强弱各发一次，比较推理痕迹的
 * 多少（token 数或字数）。档位之间拉不开差距，说明端点只吃了参数没真生效，
 * 这时结论写成「不可验证」，而不是把端点认的每个词都当成一档。
 *
 * <p>全部方法<b>阻塞</b>，调用方放工作线程；进度回调由调用方决定在哪个线程。
 */
final class CapabilityProbe {

    private static final String TAG = "CapabilityProbe";

    private static final int TIMEOUT_MS = 20000;
    /**
     * 思考档位探测的回复长度上限基数。
     *
     * <p>旧版固定 64：思考模型的全部额度会被推理吃掉，正文恒为空；更糟的是
     * Anthropic 要求 {@code max_tokens > thinking.budget_tokens}，预算 1024 配
     * 上限 64 必然 400，于是每个 Claude 模型都被探成「不支持思考」。现在按档位
     * 预算动态给上限（见 {@link #probeMaxTokens}），保证预算与上限不打架。
     */
    private static final int THINKING_MIN_TOKENS = 1024;
    /** 关闭探测的回复上限：只要模型肯作答，一道算术题这些额度够用。 */
    private static final int OFF_MAX_TOKENS = 1024;

    /**
     * 各档探测用的思考预算。
     *
     * <p>与真实聊天用的 {@link ThinkingLevels#budget} 分开：真实预算到 32K，
     * 探测照搬会让单次请求的输出上限达到三万 token，一轮探测既慢又贵。
     * 探测只需要「档位之间拉得开」，用 1K～6K 六档就足够看出差别。
     */
    private static int probeBudget(String level) {
        switch (ThinkingLevels.indexOf(level)) {
            case 0: return 1024;
            case 1: return 1536;
            case 2: return 2048;
            case 3: return 3072;
            case 4: return 4096;
            case 5: return 6144;
            default: return 2048;
        }
    }

    /** 输出上限必须严格大于思考预算，否则 Anthropic 一类端点直接 400。 */
    private static int probeMaxTokens(String level) {
        return probeBudget(level) + 256;
    }
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
     * 思考探测用的问题。
     *
     * <p>旧版只发一句 {@code hi}：这类寒暄模型往往不思考就直接回答，
     * 「回复里没有推理内容」也就说明不了端点没开思考。换成一道必须推算的题，
     * 开启了思考的模型才会留下推理痕迹，档位之间也才有可比较的高低。
     */
    private static final String THINK_ASK =
            "一件商品原价128元，先打八折，再在折后价基础上加收5%的手续费，最后又减了9元。"
            + "请一步一步算出最终价格，最后一行只写数字。";

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
        /** 能否关闭思考：true 能 / false 不能 / null 未能判定 */
        Boolean offSupported;
        /**
         * 关闭字段一律被忽略、且开着思考时才出推理：模型固定思考。
         *
         * <p>这种情况不写档位（写出来就是让用户选一档永远不生效的强度），
         * 思考档位里也不该出现「关闭」。
         */
        boolean offBlocked;
        /** 关闭能力这次没测出来（默认就不思考、被拒、网络问题）：不据此改动已有结论。 */
        boolean offUnverifiable;
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
        // 每次探测都从干净状态开始：上一次的结论不能顺着本次的判定继续生效。
        // 「关不掉」只在真实聊天里由端点明确拒绝时落地（见 ChatStreamRunner），
        // 探测本身只给建议，不改配置。
        ThinkingOff.forget(model);

        String url = Endpoint.chatUrl(snap);

        // ---- 思考档位 ----
        // 顺序很关键：先探「关闭」，再拿它的推理量当基线。理由：
        //   · 端点默认就开着思考时，「不设参数」的基线里也有推理，拿它比只会得出
        //     「各档都比基线少」，判不出任何结论；
        //   · 真正能关掉的模型，关闭之后的推理量才是「没有思考」的干净基线，
        //     各档与它比，多出来多少就是这一档真实加上去的推理。
        // 关闭探不出来（关不掉 / 网络问题）时，退回用「不设参数」作基线。
        int offSignal = -1;
        // 关闭探测已经发了「不设参数」那一次，它同时就是档位对照的基线。
        // 旧版在关闭探不出来时又发了一遍完全相同的请求——同一份 body、
        // 同一道题，白多一次请求，遇到限流紧的端点还会额外触发 429。
        String carryBase = null;
        if (stop == null || !stop.stopped()) {
            Intent off = probeOff(url, snap, model, stop);
            carryBase = off.baseBody;
            rep.offSupported = off.supported;
            offSignal = off.signal;
            if (Boolean.FALSE.equals(off.supported)) {
                // 关不掉思考 ≠ 没有强度档位：o 系列、Gemini Pro 这类模型本来就
                // 没有「关闭」，但 low / medium / high 是实打实支持的。
                // 只标记「关不掉」，档位结论原样保留。
                rep.offBlocked = true;
            } else if (off.supported == null) {
                rep.offUnverifiable = true;
            }
        }

        // 对照组：优先用「关闭生效」那一次的推理量，其次用不设参数的那一次
        int baseline = offSignal;
        if (baseline < 0 && carryBase != null) {
            // 关闭请求本身成功、只是判不出结论（例如两次都没有推理正文）：
            // 那次「不设参数」的响应就是基线，直接用，不再重发。
            baseline = reasoningSignal(carryBase);
            LogUtil.d(TAG, "thinking baseline from off probe signal=%d", baseline);
        }
        if (baseline < 0 && (stop == null || !stop.stopped())
                && url != null && snap.apiKey != null && snap.apiKey.trim().length() > 0) {
            Raw b = post(url, snap.apiKey, thinkingBody(snap, model, null, false));
            if (b.error == null && b.httpCode >= 200 && b.httpCode < 300) {
                baseline = reasoningSignal(b.body);
                LogUtil.d(TAG, "thinking baseline (no params) signal=%d", baseline);
            }
        }
        LogUtil.d(TAG, "thinking baseline for levels=%d (off signal=%d)", baseline, offSignal);

        ReasoningProfiles.Profile prof = ReasoningProfiles.of(model);
        List<String> candidates = prof.levels.isEmpty()
                ? new ArrayList<String>(ThinkingLevels.ORDER) : prof.levels;
        // 各档位对应的推理量：level → 推理 token 数（缺失时按推理字数估）
        List<String> accepted = new ArrayList<String>();
        List<Integer> signals = new ArrayList<Integer>();
        // 有档位的回复被输出上限截断：此时各档的推理都被削到同一个天花板，
        // 比值全一样，不能再据此判「这一档没比基线多」——见过一次就当「思考打开了」。
        boolean truncatedLevel = false;
        int anyAbove = 0;
        int tested = 0;
        for (int i = 0; i < candidates.size(); i++) {
            if (stop != null && stop.stopped()) {
                rep.error = Strings.get(R.string.capabilityprobe_006);
                return finish(rep, rep.levels, rep.vision, rep.unverifiable);
            }
            String level = candidates.get(i);
            Raw r = post(url, snap.apiKey, thinkingBody(snap, model, level));
            if (r.error != null) {
                rep.note = ProviderTester.explainNetwork(snap, r.error);
                break;
            }
            if (r.httpCode >= 200 && r.httpCode < 300) {
                // 档位强度只能比推理正文：用量里的推理 token 受温度与问题复杂度
                // 影响，同一档连发两次就能差三成；没有推理正文时它只能说明
                // 「确实在想」，说明不了「想得更多」。
                Signal ls = signalOf(r.body);
                boolean textSeen = hasText(ls.reasoningText);
                int sig = textSeen ? Math.max(1, ls.reasoningText.trim().length() * 2 / 3) : 0;
                boolean trunc = truncated(r.body, probeMaxTokens(level));
                OffDiag.log("探测·档位 %s：HTTP=%d 推理正文=%s 推理tokens=%d 正文=%d 截断=%s",
                        level, r.httpCode, textSeen ? String.valueOf(sig) : "无",
                        ls.reasoningTokens, ls.contentChars, trunc ? "是" : "否");
                if (trunc) truncatedLevel = true;
                // 这一档要么推理量跟基线明显不同（真的按档位变了），要么被输出
                // 上限截断（也是「思考确实打开了」的旁证）。两者都不满足的档位不
                // 列入：端点对不认识的档位一律回 200，光看状态码会把它们全算成支持。
                //
                // 判据必须双向。只能关掉思考时才是单向的：那时基线是 0，比 0 大
                // 就说明在推理。关不掉思考的模型（o 系列、Gemini Pro）基线是端点
                // 默认档——默认多半落在中间，比它低的档位推理更少，只判「更高」
                // 会把 minimal / low 全丢掉，最后只剩高档被报成支持。
                if (trunc || differsFrom(sig, baseline)) {
                    accepted.add(level);
                    signals.add(Integer.valueOf(sig));
                    anyAbove++;
                } else {
                    LogUtil.d(TAG, "level %s differs too little from baseline (%d vs %d), drop",
                            level, sig, baseline);
                }
                tested++;
                continue;
            }
            if (!isRejection(r.httpCode)) {
                // 401/403/404/429/5xx 不是档位问题：后面的档位也是同样的错，
                // 继续探下去只会把「认证失败」误判成「各档全被拒绝」
                rep.error = ProviderTester.describeFailure(snap, r.httpCode, r.body, model);
                return finish(rep, rep.levels, rep.vision, rep.unverifiable);
            }
        }

        if (!accepted.isEmpty()) {
            rep.levels.addAll(accepted);
            // 探过、但没有任何一档跟基线拉开差距（截断的档位除外）：端点收参数
            // 却完全无视它。这时「各档强度」没法证实，结论写成「不可验证」，
            // 而不是硬报支持。
            if (anyAbove == 0 && tested > 0 && baseline >= 0) {
                rep.unverifiable = true;
            }

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
     * 关闭思考探测：同一道题发两次——一次不带任何思考参数，一次带上写全的关闭字段。
     *
     * <p><b>为什么不再「逐个方案试」</b>：关闭字段一次写全之后，方案已经只有一种；
     * 更重要的是旧判据本身不成立。旧版只看「关掉之后还有没有推理痕迹」，于是：
     * <ul>
     *   <li>端点默认就关着思考时，任何字段都不比基线低，能关的模型被报成关不掉；</li>
     *   <li>网关把 {@code reasoning_content} 丢掉、只在用量里留推理计数时，</li>
     *   <li>端点对未知字段一律回 200，只看状态码会把忽略当成生效。</li>
     * </ul>
     *
     * <p>现在的判据是一组对照：默认那次有推理、关掉那次没有，才算「关得掉」；
     * 两次都有推理，才算「关不掉」。任一次失败（网络、限流、非 400 的错误）都
     * 不下结论——「测不出来」绝不能当成「关不掉」，否则界面会据此删掉「关闭」。
     *
     * <p>请求体与聊天路径共用 {@link #offBody}，探测发出去的就是聊天会发的那份。
     *
     * @return true 能关 / false 关不掉 / null 没能判定（网络、限流、中途停止、被拒）
     */
    private static Intent probeOff(String url, ProviderInfo snap, String model, Stop stop) {
        if (stop != null && stop.stopped()) return Intent.unknown();
        Raw base = post(url, snap.apiKey, thinkingBody(snap, model, null, false), TIMEOUT_MS);
        if (base.error != null || base.httpCode < 200 || base.httpCode >= 300) {
            LogUtil.d(TAG, "off probe: baseline unusable (code=%d, err=%s)",
                    base.httpCode, base.error);
            return Intent.unknown();
        }
        if (stop != null && stop.stopped()) return Intent.unknown();
        int baseSig = reasoningSignal(base.body);
        OffDiag.log("探测·关闭：默认请求 HTTP=%d 推理信号=%d", base.httpCode, baseSig);
        Raw off = post(url, snap.apiKey, offBody(snap, model), TIMEOUT_MS);
        if (off.error != null) {
            OffDiag.log("探测·关闭：关闭请求失败（%s），不下结论", off.error);
            return Intent.unknown();
        }
        if (off.httpCode == 400 || off.httpCode == 422) {
            // 端点拒收关闭字段：这次请求根本不成立，不下结论。
            // 旧版在这里直接判「关不掉」，随后界面就删掉了「关闭」——用户看到
            // 的「选项忽然没了」就是这么来的。真实聊天里还有「去掉自填字段重发」
            // 这一步，探测不该比聊天更早下结论。
            LogUtil.d(TAG, "off probe: fields rejected (HTTP %d), no conclusion", off.httpCode);
            OffDiag.log("探测·关闭：端点拒收关闭字段 HTTP=%d，不下结论", off.httpCode);
            return Intent.unknown();
        }
        if (off.httpCode < 200 || off.httpCode >= 300) {
            // 认证、限流、服务端错误与关闭能力无关
            return Intent.unknown();
        }
        Signal baseSignal = signalOf(base.body);
        Signal offSignal = signalOf(off.body);
        int offText = hasText(offSignal.reasoningText) ? 1 : 0;
        int baseText = hasText(baseSignal.reasoningText) ? 1 : 0;
        LogUtil.d(TAG, "off probe: baselineText=%d offText=%d baseline=%d off=%d",
                baseText, offText, reasoningSignal(base.body), reasoningSignal(off.body));
        OffDiag.log("探测·关闭：关闭请求 HTTP=%d 推理正文=%d 推理tokens=%d，结论=%s",
                off.httpCode, offText, offSignal.reasoningTokens,
                (baseText > 0 && offText == 0) ? "能关"
                        : (baseText > 0 && offText > 0) ? "关不掉"
                        : "测不出来");
        // 判据只看推理正文：用量里的推理 token 在关闭后也可能残留（有的端点在
        // 内部仍按思考模式跑一遍），拿它当「还在想」的证据会把能关的模型判成关不掉。
        if (baseText > 0 && offText == 0) return Intent.withBase(Boolean.TRUE, 0, base.body);
        // 「关不掉」是唯一会让界面删掉「关闭」这个选项的结论，必须多验一次。
        //
        // 实测同一条流里草稿是否带结束标记、端点默认档是否落在中间，都会在
        // 相邻两次请求之间变化（sensenova 首次请求完全不带标记、第二次才带），
        // 单次「两次都有推理正文」很可能是这一次的特殊情况。这里再关一次，
        // 两次都还有推理才落这个结论；第二次失败（限流、网络）就不下结论。
        if (baseText > 0 && offText > 0) {
            if (stop != null && stop.stopped()) return Intent.withBase(null, -1, base.body);
            Raw again = post(url, snap.apiKey, offBody(snap, model), TIMEOUT_MS);
            if (again.error != null || again.httpCode < 200 || again.httpCode >= 300) {
                LogUtil.d(TAG, "off probe: second confirmation unusable (code=%d)",
                        again.httpCode);
                OffDiag.log("探测·关闭：复核请求不可用（HTTP=%d），不下结论", again.httpCode);
                return Intent.withBase(null, -1, base.body);
            }
            boolean stillThinking = hasText(signalOf(again.body).reasoningText);
            OffDiag.log("探测·关闭：复核第二次 → %s", stillThinking ? "仍在推理" : "无推理");
            if (stillThinking) return Intent.withBase(Boolean.FALSE, -1, base.body);
            return Intent.withBase(Boolean.TRUE, 0, base.body);
        }
        return Intent.withBase(null, -1, base.body);
    }

    /**
     * 推理正文是否有实际内容。
     *
     * <p>「默认不思考」时（两次都没有推理正文）不下结论：这道题分不出关闭是否生效。
     */
    private static boolean hasText(String s) {
        return s != null && s.trim().length() > 0;
    }

    /** 关闭探测的结论：能否关掉 + 关掉时的推理量（用作档位对照的干净基线）。 */
    static final class Intent {
        /** true 能关 / false 关不掉 / null 没能判定。 */
        final Boolean supported;
        /** 关掉时的推理量；未能判定或关不掉时 -1。 */
        final int signal;
        /** 「不设参数」那一次的响应体（已发出，可供后续判定复用）。 */
        final String baseBody;

        private Intent(Boolean supported, int signal) {
            this(supported, signal, null);
        }

        private Intent(Boolean supported, int signal, String baseBody) {
            this.supported = supported;
            this.signal = signal;
            this.baseBody = baseBody;
        }

        static Intent yes(int signal) {
            return new Intent(Boolean.TRUE, signal);
        }

        static Intent yes(int signal, String baseBody) {
            return new Intent(Boolean.TRUE, signal, baseBody);
        }

        static Intent withBase(Boolean supported, int signal, String baseBody) {
            return new Intent(supported, signal, baseBody);
        }

        static Intent no() {
            return new Intent(Boolean.FALSE, -1);
        }

        static Intent unknown() {
            return new Intent(null, -1);
        }
    }

    /**
     * 推理量是否明显高于基线。
     *
     * <p>没有基线时只看有没有痕迹；有基线时要求高出 50% 且至少 16 token，
     * 避免端点抖动一两个字就被判成「这一档生效了」。
     */
    static boolean aboveBaseline(int signal, int baseline) {
        if (signal <= 0) return false;
        if (baseline < 0) return true;
        return signal >= baseline + Math.max(16, baseline / 2);
    }

    /**
     * 这一档是否真的按档位改变了推理量：比基线高或者低都算。
     *
     * <p>基线是「关掉思考」时测出来的（近似 0）就退化成 {@link #aboveBaseline}——
     * 那时只能往上看；基线是端点默认档时两边都要看，否则默认档以下的档位会被
     * 一律判成不支持。容差和单向判据一致，避免端点抖动一两个字就被算成生效。
     */
    static boolean differsFrom(int signal, int baseline) {
        if (baseline <= 0) return aboveBaseline(signal, baseline);
        if (signal < 0) return false;
        // 相对差与绝对差都要满足：推理量小时（十几 token）一两句客套就能差出
        // 50%，只看相对差会把端点抖动当成「这一档生效了」；推理量大时一两百
        // token 的差又说明不了档位变化，只看绝对差会误收。
        int d = Math.abs(signal - baseline);
        return d >= Math.max(16, baseline / 5) && d >= 48;
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
        if (rep.offBlocked) {
            if (sb.length() > 0) sb.append("；");
            sb.append(Strings.get(R.string.capabilityprobe_024));
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
     * 这一档的推理痕迹有多大：优先取推理 token 数，其次取推理正文的长度。
     *
     * <p>0 表示「没有任何痕迹」。分档对照时用的是这个量而不是布尔值——
     * 只看有无，端点随便抖一个字就会被判成「这一档生效了」。
     */
    static int reasoningSignal(String body) {
        if (body == null || body.length() == 0) return 0;
        Signal sig = signalOf(body);
        if (sig.reasoningTokens > 0) return sig.reasoningTokens;
        String text = sig.reasoningText;
        if (text == null || text.trim().length() == 0) return 0;
        // 没有 token 计数时按推理正文的长度估：中文约 1.5 字/token
        return Math.max(1, text.trim().length() * 2 / 3);
    }

    /**
     * 一次回复里的两样痕迹：单独的推理正文，以及用量里的推理 token。
     *
     * <p><b>为什么要分开</b>：两者是不同层级的证据。
     * <ul>
     *   <li>推理正文（{@code reasoning_content}）说明端点确实在思考并把过程透了出来；</li>
     *   <li>推理 token 只说明内部算了，正文里一个字的思考都看不到。</li>
     * </ul>
     * 档位强度只能靠推理正文比较——用量里的推理 token 受温度与问题复杂度影响，
     * 同一档连发两次就能差三成，拿它比档位等于在噪声里找信号。
     */
    static final class Signal {
        final String reasoningText;
        final int reasoningTokens;
        /** 正文（content）的长度：没有推理正文时可判断「是不是答了」。 */
        final int contentChars;

        Signal(String reasoningText, int reasoningTokens, int contentChars) {
            this.reasoningText = reasoningText;
            this.reasoningTokens = reasoningTokens;
            this.contentChars = contentChars;
        }
    }

    /** 从响应体里取两类痕迹。 */
    static Signal signalOf(String body) {
        if (body == null || body.length() == 0) return new Signal(null, 0, 0);
        int tokens = firstInt(body, "reasoning_tokens");
        if (tokens <= 0) tokens = firstInt(body, "thinking_tokens");
        String text = firstText(body, "reasoning_content");
        if (text == null) text = firstText(body, "reasoning");
        int content = 0;
        try {
            JSONObject o = new JSONObject(body);
            JSONArray ch = o.optJSONArray("choices");
            if (ch != null && ch.length() > 0) {
                JSONObject m = ch.optJSONObject(0) == null ? null
                        : ch.optJSONObject(0).optJSONObject("message");
                if (m != null) {
                    String c = m.optString("content", "");
                    content = c == null ? 0 : c.trim().length();
                }
            }
            JSONObject u = o.optJSONObject("usage");
            UsageView uv = UsageView.of(u);
            if (tokens <= 0 && uv.reasoning > 0) tokens = uv.reasoning;
        } catch (Throwable ignored) {
            // 结构不认识就按上面的字符串扫描结果处理
        }
        return new Signal(text, Math.max(0, tokens), content);
    }

    /**
     * 回复是否被输出上限截断（用量里的输出量顶到 max_tokens）。
     *
     * <p>Anthropic 一类的端点不写 {@code completion_tokens}，只写
     * {@code output_tokens}；只认前者时这类端点永远判不出截断。
     */
    private static boolean truncated(String body, int maxTokens) {
        if (body == null || body.length() == 0) return false;
        int ct = -1;
        try {
            UsageView uv = UsageView.of(new JSONObject(body).optJSONObject("usage"));
            ct = uv.output;
        } catch (Throwable ignored) {
            // 结构不认识：退回字符串扫描
        }
        if (ct <= 0) {
            ct = firstInt(body, "completion_tokens");
            if (ct <= 0) ct = firstInt(body, "output_tokens");
        }
        return ct > 0 && ct >= maxTokens - 1;
    }

    /** 各档位的痕迹量是否拉开差距：最大与最小的差达到最大值的 20%（至少 8）。 */
    static boolean spread(List<Integer> signals) {
        if (signals == null || signals.size() < 2) return false;
        int max = -1;
        int min = Integer.MAX_VALUE;
        for (int i = 0; i < signals.size(); i++) {
            int v = signals.get(i).intValue();
            if (v > max) max = v;
            if (v < min) min = v;
        }
        int need = Math.max(8, max / 5);
        return max - min >= need;
    }

    /** 取 JSON 里某个整数字段的值（不看层级，端点把它放在哪一层都认）。 */
    private static int firstInt(String body, String key) {
        int at = body.indexOf("\"" + key + "\"");
        while (at >= 0) {
            int p = at + key.length() + 2;
            while (p < body.length() && (body.charAt(p) == ' ' || body.charAt(p) == ':')) p++;
            int q = p;
            while (q < body.length() && Character.isDigit(body.charAt(q))) q++;
            if (q > p) {
                try {
                    return Integer.parseInt(body.substring(p, q));
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
            at = body.indexOf("\"" + key + "\"", at + key.length() + 2);
        }
        return 0;
    }

    /** 取 JSON 里某个字符串字段的值；没有则返回 null。 */
    private static String firstText(String body, String key) {
        int at = body.indexOf("\"" + key + "\"");
        if (at < 0) return null;
        int p = at + key.length() + 2;
        while (p < body.length() && (body.charAt(p) == ' ' || body.charAt(p) == ':')) p++;
        if (p >= body.length() || body.charAt(p) != '"') return null;
        StringBuilder sb = new StringBuilder();
        int q = p + 1;
        while (q < body.length() && body.charAt(q) != '"') {
            char c = body.charAt(q);
            if (c == '\\' && q + 1 < body.length()) {
                char n = body.charAt(q + 1);
                // 码点转义按长度折算即可，不必真的解码：这里只比较长短
                sb.append(n == 'u' ? 'x' : n);
                q += n == 'u' ? 6 : 2;
                continue;
            }
            sb.append(c);
            q++;
        }
        return sb.toString();
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

    /**
     * 一条「关闭请求」的原始响应，离线判断用的入口。
     *
     * @return 关闭请求带上的字段在响应里有没有被证明生效：
     *         响应里没有推理正文即为生效（true），有推理正文为未生效（false），
     *         响应体不可用返回 null
     */
    static Boolean offFieldsHonored(String body, int httpCode) {
        if (httpCode < 200 || httpCode >= 300 || body == null || body.length() == 0) return null;
        return !hasText(signalOf(body).reasoningText);
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
        return thinkingBody(p, model, level, true);
    }

    /**
     * @param includeThinking 是否带思考参数。带上应答格式（非流式）与探针问题，
     *                        与真实聊天一致；分析推理痕迹时用不带思考的同题请求
     *                        做基线，两者之差才是思考真正用掉的量。
     */
    static String thinkingBody(ProviderInfo p, String model, String level, boolean includeThinking) {
        try {
            JSONObject body = baseBody(model, probeMaxTokens(level));
            if (includeThinking) {
                String pName = p.thinkingParamName;
                if (pName == null || pName.trim().length() == 0) pName = "thinking";
                if ("boolean".equals(p.thinkingType)) {
                    body.put(pName, true);
                    body.put("thinking_budget", probeBudget(level));
                } else {
                    JSONObject t = new JSONObject();
                    t.put("type", "enabled");
                    body.put(pName, t);
                    // Anthropic 开启思考必须给预算，漏了会被 400 记成「档位不支持」
                    if (ReasoningProfiles.needsBudget(model)) {
                        body.put("thinking_budget", probeBudget(level));
                    }
                    String effort = ThinkingLevels.reasoningEffort(level);
                    if (effort != null) body.put("reasoning_effort", effort);
                }
            }
            JSONArray msgs = new JSONArray();
            JSONObject m = new JSONObject();
            m.put("role", "user");
            m.put("content", THINK_ASK);
            msgs.put(m);
            body.put("messages", msgs);
            return body.toString();
        } catch (Exception e) {
            return "{\"model\":\"" + model + "\",\"max_tokens\":" + THINKING_MIN_TOKENS
                    + ",\"stream\":false,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
        }
    }

    /**
     * 关闭请求体：与真实聊天同一套关闭字段，只是换成探测问题、非流式。
     */
    static String offBody(ProviderInfo p, String model) {
        try {
            JSONObject body = baseBody(model, OFF_MAX_TOKENS);
            JSONArray msgs = new JSONArray();
            JSONObject m = new JSONObject();
            m.put("role", "user");
            m.put("content", THINK_ASK);
            msgs.put(m);
            body.put("messages", msgs);
            // 与聊天完全一致：关闭字段一次写全，是否真的关掉由响应里有没有推理
            // 痕迹来判定，不靠提示词压制。
            ThinkingOff.apply(body, p, model);
            return body.toString();
        } catch (Exception e) {
            LogUtil.w(TAG, "build off body failed: %s", e.getMessage());
            return null;
        }
    }

    /** 带一张数字图的请求体，{@code content} 用 OpenAI 的多模态数组格式。 */
    static String visionBody(ProviderInfo p, String model, String imageBase64) {
        try {
            JSONObject body = baseBody(model, VISION_MAX_TOKENS);
            disableThinking(p, body, model);
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
    private static void disableThinking(ProviderInfo p, JSONObject body, String model) {
        // 与聊天共用首选关闭方案：字段形态对了才关得掉，否则看图请求会先被
        // 推理吃掉额度，返回空正文，模型被误判成「没有视觉能力」。
        if (!ThinkingOff.apply(body, p, model)) {
            LogUtil.d(TAG, "no thinking-off plan for %s, vision probe runs with defaults", model);
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
