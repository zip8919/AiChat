package xyz.zip8919.app.aichat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.json.JSONObject;

/**
 * 「关闭思考」的字段方案。
 *
 * <p><b>为什么单独成类</b>：关掉思考不是一个字段能解决的事。同一句
 * {@code thinking:{type:disabled}}，DeepSeek 认，Qwen 只认顶层
 * {@code enable_thinking:false}，OpenAI 只认 {@code reasoning_effort}，而严格的
 * 官方端点对不认识的字段直接 400。旧做法是「四类字段一次全发」，结果在严格端点上
 * 整条请求被拒，降级时又把关闭字段整套丢掉——用户选了关，模型反而回到默认的开。
 *
 * <p><b>现在的做法</b>（与成熟客户端的处理一致）：按模型家族挑<b>一个</b>最可能被
 * 接受的字段先发；被 400/422 拒绝时换下一种方案，而不是一次退回「什么都不发」。
 * 每种方案只写一个顶层字段，端点不认就只浪费一次请求，不会把请求体搅成一锅。
 *
 * <p>方案按可信度排序：认得家族时家族知识在前、认不得时提供商声明在前，
 * 之后才是通用组合与「不下发」。家族字段是协议规定死的，端点配错也照样有效；
 * 自建 provider 的 {@code thinkingType} 默认都是 {@code object}+{@code thinking}，
 * 对 OpenAI、Qwen 这些端点本身就是错的，所以只用来兜底不认识的模型。
 */
final class ThinkingOff {

    /**
     * 按模型家族挑字段。
     *
     * <p>排在提供商声明之后：模型挂在哪个端点上，端点自己配的开关才是真的。
     */
    static final int PLAN_FAMILY = 0;
    /**
     * 按提供商声明的字段名与形态。
     *
     * <p><b>为什么排第一</b>：这是用户为这个端点亲手填的字段名。同一份
     * OpenAI 兼容接口，Qwen 挂上去关思考要发 {@code enable_thinking}，
     * DeepSeek 挂上去要发 {@code thinking}，第三方网关又可能两者都认。
     * 只按模型名猜字段，会把「用 Qwen 自己的开关关得好好的」那些模型
     * 判成 400 被拒，再降级成「什么都不发」，思考就回到默认的开启。
     * 旧版在这一点上是对的，后来的「按家族重排」反而把它弄坏了。
     */
    static final int PLAN_NATIVE = 1;
    /** 通用组合：effort 取家族最低档 + 模板参数。 */
    static final int PLAN_GENERIC = 2;
    /** 不下发任何思考字段：最后的兜底，等于交给端点默认；仍推理就判定关不掉。 */
    static final int PLAN_NONE = 3;

    private ThinkingOff() {
    }

    /**
     * 这个端点 + 模型可以依次尝试的方案。
     *
     * <p>去重在这里完成：家族方案与提供商方案常常写出同一个字段，重复尝试只会
     * 白吃一次 400。
     *
     * <p>真实对话里验证过、且确实关掉了推理的字段排在第一位；没验证过的按
     * 提供商声明 → 家族 → 通用 → 不下发 的顺序。验证结果只影响顺序，不改变覆盖面：
     * 记忆里的方案被端点改了行为，后面的方案照样能接住。
     */
    static List<Integer> plans(ProviderInfo p, String model) {
        List<Integer> out = new ArrayList<Integer>();
        List<String> seen = new ArrayList<String>();
        String remembered = rememberedSig(model);
        if (remembered.length() > 0) {
            for (int plan = PLAN_NATIVE; plan <= PLAN_GENERIC; plan++) {
                if (!remembered.equals(signature(p, model, plan))) continue;
                if (signature(p, model, plan).length() == 0) continue;
                add(out, seen, p, model, plan);
                break;
            }
        }
        // 认得家族就先用家族字段：这些字段是协议规定死的（o 系列只认
        // reasoning_effort、Claude 只认 thinking），端点配错也不影响；
        // 认不得家族时用提供商声明的字段名——那是用户为这个端点亲手填的。
        String fam = ReasoningProfiles.of(model).family;
        boolean known = fam != null && fam.length() > 0 && !"unknown".equals(fam);
        if (known) {
            add(out, seen, p, model, PLAN_FAMILY);
            add(out, seen, p, model, PLAN_NATIVE);
        } else {
            add(out, seen, p, model, PLAN_NATIVE);
            add(out, seen, p, model, PLAN_FAMILY);
        }
        add(out, seen, p, model, PLAN_GENERIC);
        add(out, seen, p, model, PLAN_NONE);
        return out;
    }

    // ---- 实测记忆 ----

    /** 模型 ID → 已验证的关闭方案指纹。进程内即可，重启后重新验证一次不算慢。 */
    private static final java.util.HashMap<String, String> REMEMBERED =
            new java.util.HashMap<String, String>();
    /** 模型 ID → 实测确认无法关闭（所有方案都试过、推理照旧）。 */
    private static final java.util.HashMap<String, Boolean> CANNOT_OFF =
            new java.util.HashMap<String, Boolean>();

    /**
     * 记下一次真实对话里成功的关闭方案。
     *
     * @param sig 方案指纹（见 {@link #signatureOf}）
     * @param lowestLevel 当时用的最低档，仅用于日志
     */
    static void remember(String model, String sig, String lowestLevel) {
        if (model == null || sig == null || sig.length() == 0) return;
        synchronized (REMEMBERED) {
            REMEMBERED.put(model, sig);
            // 能关掉就不再是「关不掉」
            CANNOT_OFF.remove(model);
        }
        LogUtil.d("ThinkingOff", "remembered off plan for %s: %s (%s)", model, sig, lowestLevel);
    }

    /**
     * 记下「这个模型关不掉」：所有方案都被拒，或实测里推理照旧。
     *
     * <p>与配置里的 {@code thinkingOffBlocked} 两条路：配置负责跨重启、跨设备一致；
     * 这里负责当次运行的即时判断，命中后思考档位立刻不再列出「关闭」。
     */
    static void rememberCannotOff(String model) {
        if (model == null) return;
        synchronized (REMEMBERED) {
            CANNOT_OFF.put(model, Boolean.TRUE);
            REMEMBERED.remove(model);
        }
        LogUtil.w("ThinkingOff", "model %s cannot disable thinking", model);
    }

    /** 实测确认关不掉（本次运行内）。 */
    static boolean cannotOff(String model) {
        if (model == null) return false;
        synchronized (CANNOT_OFF) {
            return CANNOT_OFF.containsKey(model);
        }
    }

    private static String rememberedSig(String model) {
        if (model == null) return "";
        synchronized (REMEMBERED) {
            String s = REMEMBERED.get(model);
            return s == null ? "" : s;
        }
    }

    /** 第 {@code attempt} 次尝试对应的方案指纹；越界返回空串。 */
    static String signatureOf(ProviderInfo p, String model, int attempt) {
        List<Integer> list = plans(p, model);
        if (attempt < 0 || attempt >= list.size()) return "";
        return signature(p, model, list.get(attempt).intValue());
    }

    /** 第 {@code attempt} 次尝试是否真的会下发关闭字段。 */
    static boolean applied(ProviderInfo p, String model, int attempt) {
        return planAt(p, model, attempt) != PLAN_NONE;
    }

    static int planCount(ProviderInfo p, String model) {
        return plans(p, model).size();
    }

    /**
     * 真正会下发关闭字段的方案数（不含末尾那个「什么都不发」的兜底）。
     *
     * <p>调用方判断「还有没有别的字段可试」时要用这个：兜底方案等于什么都没做，
     * 把它算进可尝试范围，会让「换方案」变成一次注定无效的重发。
     */
    static int realPlanCount(ProviderInfo p, String model) {
        List<Integer> list = plans(p, model);
        int n = 0;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).intValue() != PLAN_NONE) n++;
        }
        return n;
    }

    /** 第 {@code attempt} 次尝试用哪个方案；越界时取最后一种（不下发）。 */
    static int planAt(ProviderInfo p, String model, int attempt) {
        List<Integer> list = plans(p, model);
        if (list.isEmpty()) return PLAN_NONE;
        int i = attempt < 0 ? 0 : attempt;
        if (i >= list.size()) i = list.size() - 1;
        return list.get(i).intValue();
    }

    /**
     * 把方案写进请求体。
     *
     * @return true 表示确实下发了一个关闭字段；false 表示这一方案不下发任何字段
     */
    static boolean apply(JSONObject body, ProviderInfo p, String model, int attempt) {
        if (body == null) return false;
        switch (planAt(p, model, attempt)) {
            case PLAN_FAMILY:
                return applyFamily(body, model);
            case PLAN_NATIVE:
                return applyNative(body, p);
            case PLAN_GENERIC:
                return applyGeneric(body, model);
            default:
                return false;
        }
    }

    /** 这一方案是否不下发任何思考字段。 */
    static boolean isSilent(ProviderInfo p, String model, int attempt) {
        return planAt(p, model, attempt) == PLAN_NONE;
    }

    /**
     * 方案阶梯是否已经走到头（真实字段都试过了）。
     *
     * <p>运行期靠它判断「还有没有别的字段可试」：都试完了就只能承认关不掉，
     * 而不是继续换方案、让用户看着请求一次次重发。
     */
    static boolean exhausted(ProviderInfo p, String model, int attempt) {
        return attempt + 1 >= realPlanCount(p, model);
    }

    // ---- 各方案 ----

    /**
     * 家族方案：只写该家族官方支持的那一个关闭字段。
     *
     * <p>认不出的模型返回 false，由下一方案接替，不硬猜。
     */
    private static boolean applyFamily(JSONObject body, String model) {
        ReasoningProfiles.Profile prof = ReasoningProfiles.of(model);
        if (prof.levels.isEmpty()) return false;
        String f = prof.family;
        try {
            if ("openai-o".equals(f)) {
                // o 系列没有「关闭」，最低是 low。用 none 会直接 400。
                body.put("reasoning_effort", "low");
                return true;
            }
            if ("openai-gpt5".equals(f)) {
                // 5.1 起支持 none；5 / 5.0 最低只到 minimal
                body.put("reasoning_effort", supportsNone(model) ? "none" : "minimal");
                return true;
            }
            if ("deepseek".equals(f) || "claude".equals(f) || "glm".equals(f)) {
                thinkingDisabled(body);
                return true;
            }
            if ("gemini".equals(f)) {
                // 兼容层最低档对 Flash 等于关思考；Pro 关不掉，由探测结论说明
                body.put("reasoning_effort", "minimal");
                return true;
            }
            if ("grok".equals(f)) {
                body.put("reasoning_effort", "low");
                return true;
            }
            if ("hunyuan".equals(f)) {
                // 混元协议是 thinking 对象：type=disabled 关掉，reasoning_effort=high
                // 才是官方文档里的高强度档。旧映射把混元归到「只认顶层 enable_thinking」
                // 一组，发的字段端点不认，于是「文档说能关、探测说关不掉」。
                thinkingDisabled(body);
                return true;
            }
            // Qwen / 豆包 / Kimi / MiniMax / 文心：顶层 enable_thinking
            body.put("enable_thinking", false);
            return true;
        } catch (Exception e) {
            LogUtil.w("ThinkingOff", "applyFamily failed: %s", e.getMessage());
            return false;
        }
    }

    /** 提供商方案：按配置里声明的字段名与形态下发。 */
    private static boolean applyNative(JSONObject body, ProviderInfo p) {
        if (p == null) return false;
        String name = paramName(p);
        try {
            if ("boolean".equals(p.thinkingType)) {
                body.put(name, false);
            } else {
                JSONObject off = new JSONObject();
                off.put("type", "disabled");
                body.put(name, off);
            }
            return true;
        } catch (Exception e) {
            LogUtil.w("ThinkingOff", "applyNative failed: %s", e.getMessage());
            return false;
        }
    }

    /**
     * 通用方案：effort 降到家族最低档，同时带模板参数。
     *
     * <p>这一档是给「认 reasoning_effort 但不认 thinking 对象」的网关用的。
     * 不写 none 除非家族确认支持，否则 400 之后又要多试一轮。
     */
    private static boolean applyGeneric(JSONObject body, String model) {
        try {
            body.put("reasoning_effort", lowestEffort(model));
            JSONObject ctk = new JSONObject();
            ctk.put("enable_thinking", false);
            body.put("chat_template_kwargs", ctk);
            return true;
        } catch (Exception e) {
            LogUtil.w("ThinkingOff", "applyGeneric failed: %s", e.getMessage());
            return false;
        }
    }

    // ---- 辅助 ----

    private static void thinkingDisabled(JSONObject body) throws Exception {
        JSONObject off = new JSONObject();
        off.put("type", "disabled");
        body.put("thinking", off);
    }

    /** 家族最低 effort：关不掉时尽量压到最省。 */
    static String lowestEffort(String model) {
        ReasoningProfiles.Profile prof = ReasoningProfiles.of(model);
        if (prof.levels.isEmpty()) return "none";
        // o 系列不认 minimal（家族表里带它是为了选档，最低实际是 low）；
        // gpt-5 系列最低 minimal，5.1 及以后才有 none
        if ("openai-o".equals(prof.family)) return "low";
        if (prof.family.startsWith("openai")) {
            return supportsNone(model) ? "none" : "minimal";
        }
        if (prof.levels.contains("minimal")) return "minimal";
        return prof.levels.get(0);
    }

    /** 该模型是否支持 {@code reasoning_effort=none}。 */
    static boolean supportsNone(String model) {
        String m = model == null ? "" : model.trim().toLowerCase(Locale.US);
        if (m.length() == 0) return false;
        // 只有 gpt-5.1 及以后的 OpenAI 模型把 none 写进文档
        if (m.startsWith("gpt-5")) {
            return m.indexOf("5.1") >= 0 || m.indexOf("5.2") >= 0
                    || m.indexOf("5.3") >= 0 || m.indexOf("5.4") >= 0;
        }
        // 非 OpenAI 家族不保证：让端点自己 400，再由方案阶梯接住
        return false;
    }

    /** 提供商声明的字段名；空值回退 thinking，避免 put(null) 抛异常。 */
    private static String paramName(ProviderInfo p) {
        String n = p == null ? null : p.thinkingParamName;
        if (n == null || n.trim().length() == 0) return "thinking";
        return n.trim();
    }

    /**
     * 方案的字段签名，用于去重。
     *
     * <p>只比较「会写出哪些顶层字段、值是什么」，同一组字段没必要试两遍。
     */
    private static String signature(ProviderInfo p, String model, int plan) {
        if (plan == PLAN_NONE) return "none";
        if (plan == PLAN_FAMILY) {
            ReasoningProfiles.Profile prof = ReasoningProfiles.of(model);
            if (prof.levels.isEmpty()) return "";
            String f = prof.family;
            if ("openai-o".equals(f)) return "effort:low";
            if ("openai-gpt5".equals(f)) {
                return "effort:" + (supportsNone(model) ? "none" : "minimal");
            }
            if ("deepseek".equals(f) || "claude".equals(f) || "glm".equals(f)
                    || "hunyuan".equals(f)) {
                return "thinking:disabled";
            }
            if ("gemini".equals(f)) return "effort:minimal";
            if ("grok".equals(f)) return "effort:low";
            return "enable_thinking:false";
        }
        if (plan == PLAN_NATIVE) {
            String name = paramName(p);
            return "boolean".equals(p == null ? null : p.thinkingType)
                    ? name + ":false" : name + ":disabled";
        }
        return "effort:" + lowestEffort(model) + "+ctk";
    }

    private static void add(List<Integer> out, List<String> seen,
            ProviderInfo p, String model, int plan) {
        String sig = signature(p, model, plan);
        // 空签名表示这一方案不适用（家族未知）
        if (plan != PLAN_NONE && sig.length() == 0) return;
        if (seen.contains(sig)) return;
        seen.add(sig);
        out.add(Integer.valueOf(plan));
    }
}
