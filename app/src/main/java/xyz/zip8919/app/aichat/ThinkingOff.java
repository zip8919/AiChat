package xyz.zip8919.app.aichat;

import java.util.Locale;

import org.json.JSONObject;

/**
 * 「关闭思考」的字段写法。
 *
 * <p><b>实测结论（决定了这里怎么写）</b>
 *
 * <p>2026-11 用两个真实端点、固定题、非流式 + 流式各跑一遍，逐个字段验证：
 * <table border="1">
 *   <caption>关闭字段的实际效果</caption>
 *   <tr><th>字段</th><th>tencentmaas / hy4-flash</th><th>sensenova / 6.8-flash</th></tr>
 *   <tr><td>{@code thinking:{type:disabled}}</td><td>生效（推理 0）</td>
 *       <td>推理 0，但草稿被塞进 content</td></tr>
 *   <tr><td>{@code reasoning_effort:"none"}</td><td>生效（推理 0）</td>
 *       <td>推理 0，但草稿被塞进 content</td></tr>
 *   <tr><td>{@code enable_thinking:false}</td><td><b>静默忽略</b>（仍在推理）</td>
 *       <td><b>静默忽略</b>（推理吃满上限）</td></tr>
 *   <tr><td>{@code chat_template_kwargs.enable_thinking:false}</td>
 *       <td><b>静默忽略</b></td><td>未验证</td></tr>
 *   <tr><td>{@code thinking_budget:0}</td><td><b>静默忽略</b></td><td>未验证</td></tr>
 *   <tr><td>{@code thinking:{type:adaptive}}</td><td>—</td>
 *       <td>推理吃满 2048 上限、正文 0 字，{@code finish_reason=length}</td></tr>
 * </table>
 *
 * <p>两个关键点：
 * <ol>
 *   <li><b>不认识的字段一律静默忽略，不报错。</b>所以「发一堆关闭字段，谁认识谁生效」
 *       这种做法在这里没有收益，只有坏处：{@code enable_thinking:false} 看起来发了，
 *       实际什么都没关，反而让「关闭」这个动作看起来已经做过。</li>
 *   <li><b>真正生效的只有 {@code thinking.type=disabled} 这一条</b>（两家都认）。
 *       {@code reasoning_effort=none} 同样生效，但两家都会把推理内容改成塞进
 *       content 的草稿，语义上不如 {@code thinking} 干净，只在需要时作为补充。</li>
 * </ol>
 *
 * <p>所以现在只发<b>能生效的字段</b>：{@code thinking:{type:disabled}} 为主，
 * 仅对「只认 effort、没有关闭档」的家族（o 系列 / GPT-5 / Gemini / Grok）发 effort。
 * 端点明确拒收（400 点名某个字段）时记下「关不掉」，不再反复试错——商汤的
 * {@code glm-5.3} 就是这样：发 {@code type:disabled} 直接 400，且报错原文说明
 * 它只接受 {@code enabled|disabled|adaptive}，属于端点侧的实现缺口，客户端补不了。
 */
final class ThinkingOff {

    /**
     * 「实测关不掉」的模型：真实聊天里被端点明确拒收关闭字段时写入。
     *
     * <p>探测不下这个结论——探测用固定题、短上限、非流式，条件与聊天不同，
     * 它的「关不掉」经常是测不准。记忆只在一个进程内有效，重启即清空：
     * 端点升级、字段写法变化后，用户可以重新试。
     */
    private static final java.util.HashSet<String> CANNOT_OFF = new java.util.HashSet<String>();

    /** 记下「真实聊天里关闭字段被端点明确拒收」的模型。 */
    static void rememberCannotOff(String model) {
        if (model == null || model.length() == 0) return;
        synchronized (CANNOT_OFF) {
            CANNOT_OFF.add(model);
        }
        LogUtil.w("ThinkingOff", "model %s rejected thinking-off fields", model);
    }

    /** 本次运行里是否已被端点明确拒收关闭字段。 */
    static boolean cannotOff(String model) {
        if (model == null) return false;
        synchronized (CANNOT_OFF) {
            return CANNOT_OFF.contains(model);
        }
    }

    /** 清掉某个模型的「关不掉」结论：重新探测或换了写法后从头判断。 */
    static void forget(String model) {
        if (model == null) return;
        synchronized (CANNOT_OFF) {
            CANNOT_OFF.remove(model);
        }
    }

    private ThinkingOff() {
    }

    /** 把关闭字段写进请求体。 */
    static boolean apply(JSONObject body, ProviderInfo p, String model) {
        return apply(body, p, model, true);
    }

    /**
     * 把关闭字段写进请求体。
     *
     * @param includeNative 是否带上提供商自填的字段；端点拒收它时由调用方传 false
     * @return 下发失败返回 false
     */
    static boolean apply(JSONObject body, ProviderInfo p, String model, boolean includeNative) {
        if (body == null) return false;
        try {
            // 1) 只认 effort 的家族（o 系列、gpt-5、Gemini、Grok）：没有「关闭」这个
            //    档位，能做的只有压到最低。只写 reasoning_effort 就返回——OpenAI
            //    官方对不认识的参数直接 400，多写 thinking 换不来效果，只会多一个
            //    400 的来源。gpt-5.1 起支持 effort=none，那才是真正的关闭。
            if (needsEffort(model)) {
                body.put("reasoning_effort", supportsNone(model) ? "none" : lowestEffort(model));
                if (includeNative) applyNative(body, p);
                return true;
            }
            // 2) 其余全部用 thinking 对象。这是唯一在实测中两家都生效的字段，
            //    也是 DeepSeek / Kimi / 混元 / 豆包 / GLM / Anthropic 的通用写法。
            //
            //    不再附带 enable_thinking / chat_template_kwargs / thinking_budget：
            //    实测前两个被端点静默忽略（推理照跑），发它们只会让日志和诊断
            //    显示「已下发关闭」而实际没关，把排查方向带偏。
            JSONObject off = new JSONObject();
            off.put("type", "disabled");
            body.put("thinking", off);
            // 3) Anthropic 原生接口只认 budget，且对象与预算不能同时给
            if ("claude".equals(ReasoningProfiles.of(model).family)) {
                body.put("thinking_budget", 0);
            }
            // 4) 提供商自己声明的字段名/形态（用户为这个端点亲手填的）
            if (includeNative) applyNative(body, p);
            return true;
        } catch (Exception e) {
            LogUtil.w("ThinkingOff", "apply failed: %s", e.getMessage());
            return false;
        }
    }

    /** 是否属于「只认 reasoning_effort、没有关闭档」的家族。 */
    private static boolean needsEffort(String model) {
        String f = ReasoningProfiles.of(model).family;
        return "openai-o".equals(f) || "openai-gpt5".equals(f) || "gemini".equals(f)
                || "grok".equals(f);
    }

    /** 提供商声明的字段名与形态；与写全的字段重名时以提供商声明为准。 */
    private static void applyNative(JSONObject body, ProviderInfo p) {
        if (p == null) return;
        String name = paramName(p);
        try {
            // thinking 已经按对象写过，不覆盖：{type:disabled} 是这一大票端点共同
            // 认的写法，改成布尔 false 反而会让它们认为「类型不对」而忽略。
            if ("thinking".equals(name)) return;
            if ("boolean".equals(p.thinkingType)) {
                body.put(name, false);
            } else {
                JSONObject off = new JSONObject();
                off.put("type", "disabled");
                body.put(name, off);
            }
        } catch (Exception e) {
            LogUtil.w("ThinkingOff", "applyNative failed: %s", e.getMessage());
        }
    }

    /** 家族最低 effort：关不掉时压到最省。 */
    static String lowestEffort(String model) {
        ReasoningProfiles.Profile prof = ReasoningProfiles.of(model);
        if (prof.levels.isEmpty()) return "minimal";
        // o 系列不认 minimal（家族表里带它是为了选档，最低实际是 low）
        if ("openai-o".equals(prof.family)) return "low";
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
        return false;
    }

    /** 提供商声明的字段名；空值回退 thinking。 */
    private static String paramName(ProviderInfo p) {
        String n = p == null ? null : p.thinkingParamName;
        if (n == null || n.trim().length() == 0) return "thinking";
        return n.trim();
    }

    /**
     * 关闭字段说明：写进日志与探测报告，便于对账「到底发了什么」。
     *
     * <p>不含密钥与用户内容，可以安全写日志。
     */
    static String describe(ProviderInfo p, String model) {
        StringBuilder sb = new StringBuilder();
        String family = ReasoningProfiles.of(model).family;
        if (needsEffort(model)) {
            sb.append("reasoning_effort:")
                    .append(supportsNone(model) ? "none" : lowestEffort(model));
        } else {
            sb.append("thinking:{type:disabled}");
            if ("claude".equals(family)) sb.append(", thinking_budget:0");
        }
        if (p != null) {
            String n = paramName(p);
            if (!"thinking".equals(n)) sb.append(", ").append(n).append(":off");
        }
        return sb.toString();
    }
}
