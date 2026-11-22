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
 * <p>方案按可信度排序：家族知识 → 提供商声明 → 通用组合 → 不下发。
 * 家族知识排最前，是因为自建 provider 的 {@code thinkingType} 默认都是
 * {@code object}+{@code thinking}，对 OpenAI、Qwen 这些端点本身就是错的。
 */
final class ThinkingOff {

    /** 按模型家族挑字段。 */
    static final int PLAN_FAMILY = 0;
    /** 按提供商声明的字段名与形态。 */
    static final int PLAN_NATIVE = 1;
    /** 通用组合：effort 取家族最低档 + 模板参数。 */
    static final int PLAN_GENERIC = 2;
    /** 不下发任何思考字段，交给提示词压制。 */
    static final int PLAN_NONE = 3;

    private ThinkingOff() {
    }

    /**
     * 这个端点 + 模型可以依次尝试的方案。
     *
     * <p>去重在这里完成：家族方案与提供商方案常常写出同一个字段，重复尝试只会
     * 白吃一次 400。
     */
    static List<Integer> plans(ProviderInfo p, String model) {
        List<Integer> out = new ArrayList<Integer>();
        List<String> seen = new ArrayList<String>();
        add(out, seen, p, model, PLAN_FAMILY);
        add(out, seen, p, model, PLAN_NATIVE);
        add(out, seen, p, model, PLAN_GENERIC);
        add(out, seen, p, model, PLAN_NONE);
        return out;
    }

    static int planCount(ProviderInfo p, String model) {
        return plans(p, model).size();
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
     *         （调用方需要改用提示词压制）
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

    /** 关闭后是否完全没下发思考字段（提示词兜底的唯一判据）。 */
    static boolean isSilent(ProviderInfo p, String model, int attempt) {
        return planAt(p, model, attempt) == PLAN_NONE;
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
            // Qwen / 混元 / 豆包 / Kimi / MiniMax / 文心：顶层 enable_thinking
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
            if ("deepseek".equals(f) || "claude".equals(f) || "glm".equals(f)) {
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
