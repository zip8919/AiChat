package xyz.zip8919.app.aichat;

import java.util.Locale;

import org.json.JSONObject;

/**
 * 「关闭思考」的字段写法。
 *
 * <p><b>为什么不是一个字段一套方案</b>：关掉思考不是一个字段能解决的事。
 * 同一句 {@code thinking:{type:disabled}}，DeepSeek 认，Qwen 只认顶层
 * {@code enable_thinking:false}，OpenAI 只认 {@code reasoning_effort}，
 * Anthropic 要 {@code thinking.type=disabled} 且不能带 effort。
 *
 * <p>旧做法是「按模型家族挑一个最可能被接受的字段先发，被拒再换下一个」。
 * 实测下来这条路在多个提供商、多个模型上都不成立：只要第一个方案恰好在
 * 这一家被静默忽略（端点收下参数、回 200、照旧推理），就换不到下一个方案——
 * 换方案的唯一触发条件是端点 400/422 或流里还能看到推理，而大多数 OpenAI
 * 兼容网关对不认识的字段既不报错也不理会，推理内容也只在某些下游才透出。
 * 结果是「选了关闭，回答里仍在思考」，用户反复遇到的就是这一条。
 *
 * <p><b>现在的做法</b>：把已知的关闭字段<b>一次写全</b>。这是成熟客户端
 * （Clash 这类多协议代理）处理同类问题的方式——同一份配置里把各家私有的
 * 关闭键一起写上，谁认识的谁生效，不认识的一律当未知字段忽略。OpenAI 兼容
 * 接口的请求体本就允许携带扩展字段，网关只取自己实现的那些。
 *
 * <p>与「旧版四类字段全发」的区别在<b>取舍</b>：这里只写值确定的关闭字段，
 * 不写 {@code thinking_budget} 这类与关闭相冲突的预算，也不写
 * {@code reasoning_effort} 之外会改变强度的东西。Anthropic 的 thinking 打开
 * 必须带预算、关闭则不能带，字段组合按这个约束写。
 *
 * <p>字段值全部取「关闭」语义：
 * <ul>
 *   <li>DeepSeek / Kimi / 混元 / 豆包 / GLM / Anthropic：{@code thinking:{type:"disabled"}}</li>
 *   <li>Qwen / 部分网关：顶层 {@code enable_thinking:false}</li>
 *   <li>vLLM / SGLang 部署：{@code chat_template_kwargs:{enable_thinking:false}}</li>
 *   <li>Anthropic 原生：{@code thinking_budget:0}（Anthropic 只认 0 表示关）</li>
 *   <li>OpenAI o 系列 / GPT-5 / Gemini：没有「关闭」档，压到各自最低档</li>
 * </ul>
 *
 * <p>提供商自己配置的字段名与形态仍然保留：用户填了 {@code enable_thinking}
 * 就按 boolean 发 {@code false}，填 {@code thinking} 就按对象发
 * {@code {type:disabled}}，与写全的字段不冲突时一起下发。
 */
final class ThinkingOff {

    /**
     * 「实测关不掉」的模型：只在真实聊天里被端点明确拒收关闭字段时写入。
     *
     * <p>探测不下这个结论——探测用固定题、短上限、非流式，条件与聊天不同，
     * 它的「关不掉」经常是测不准。记忆只在一个进程内有效，重启即清空：
     * 端点升级、字段写法变化后，用户可以重新试。
     */
    private static final java.util.HashSet<String> CANNOT_OFF = new java.util.HashSet<String>();
    /**
     * 「上一次关闭请求被端点直接拒收」的模型。
     *
     * <p>与 {@link #CANNOT_OFF} 分开记：被拒收说明这份字段写法在这个端点上行不通，
     * 但关闭意图本身未必做不到（去掉对象形态、只留布尔开关常常就能过）。
     * 下一次请求据此直接从「只发布尔开关」开始，省掉两个必败的回合。
     */
    private static final java.util.HashSet<String> REJECTED = new java.util.HashSet<String>();

    /** 记下「关闭字段被端点拒收」：下一轮直接走只发布尔开关的写法。 */
    static void rememberRejected(String model) {
        if (model == null || model.length() == 0) return;
        synchronized (REJECTED) {
            if (REJECTED.add(model)) {
                LogUtil.w("ThinkingOff", "model %s rejected thinking-off fields, next try bool only",
                        model);
            }
        }
    }

    /** 该模型上一次的关闭字段是否被端点拒收过。 */
    static boolean rejected(String model) {
        if (model == null) return false;
        synchronized (REJECTED) {
            return REJECTED.contains(model);
        }
    }

    /** 端点又开始接受关闭写法了：清掉「拒收」记忆。 */
    static void clearRejected(String model) {
        if (model == null) return;
        synchronized (REJECTED) {
            REJECTED.remove(model);
        }
    }

    /**
     * 记下「真实聊天里关闭字段被端点明确拒收」的模型。
     *
     * <p>命中后思考档位不再列出「关闭」，避免用户反复选一个端点直接报错的档位。
     */
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
        clearRejected(model);
    }

    private ThinkingOff() {
    }

    /**
     * 把关闭字段一次写全。
     *
     * @return 始终为 true：关闭语义一定会下发至少一个字段
     */
    static boolean apply(JSONObject body, ProviderInfo p, String model) {
        return apply(body, p, model, true, false);
    }

    /**
     * 把关闭字段写进请求体。
     *
     * @param includeNative 是否带上提供商自填的字段；端点拒收它时由调用方传 false
     * @param boolOnly      只写 top-level 布尔关闭。端点对对象形态
     *                      （{@code thinking:{type}}、{@code chat_template_kwargs}）
     *                      同样报 400 时用这一档：能关的只有布尔开关，别的都是
     *                      400 的来源。
     * @return 始终为 true：关闭语义一定会下发至少一个字段
     */
    static boolean apply(JSONObject body, ProviderInfo p, String model,
            boolean includeNative, boolean boolOnly) {
        if (body == null) return false;
        try {
            if (boolOnly) {
                // 只留布尔形态：这两处是 Qwen / vLLM / 自建网关最常见的开关名
                body.put("enable_thinking", false);
                if (includeNative && p != null && "boolean".equals(p.thinkingType)) {
                    String name = paramName(p);
                    if (!"thinking".equals(name)) body.put(name, false);
                }
                return true;
            }
            // 1) 只认 effort 的家族（o 系列、gpt-5、Gemini、Grok）：它们没有「关闭」
            //    这个档位，能做的只有压到最低。这里只写 reasoning_effort 就返回——
            //    OpenAI 官方对不认识的参数直接 400，多写 thinking /
            //    chat_template_kwargs 换不来任何效果，只会让每条消息多吃两次失败请求。
            //    gpt-5.1 起支持 effort=none，那才是真正的关闭。
            if (needsEffort(model)) {
                body.put("reasoning_effort", supportsNone(model) ? "none" : lowestEffort(model));
                if (includeNative) applyNative(body, p);
                return true;
            }
            // 2) thinking 对象：DeepSeek、Kimi、混元、豆包、GLM、Anthropic，
            //    以及大部分网关的通用写法
            JSONObject off = new JSONObject();
            off.put("type", "disabled");
            body.put("thinking", off);
            // 3) 顶层布尔与模板开关：Qwen 系、vLLM / SGLang 部署只认这几处。
            //
            //    只给可能认它的家族发：严格的 OpenAI 端点（官方、Azure）对多余
            //    参数直接 400，多写一处就等于多一个 400 的来源；而大多数网关
            //    对不认识的字段一律忽略，少发不会让它们关不掉。
            ReasoningProfiles.Profile prof = ReasoningProfiles.of(model);
            if (wantsTopLevelSwitch(prof.family)) {
                body.put("enable_thinking", false);
                JSONObject ctk = new JSONObject();
                ctk.put("enable_thinking", false);
                body.put("chat_template_kwargs", ctk);
            }
            // 4) Anthropic：budget 为 0 即关闭；顶层布尔与 thinking 对象一起发会
            //    被它判成「同时给两套开关」而 400，所以这一族只发 thinking 对象。
            if ("claude".equals(prof.family)) {
                body.put("thinking_budget", 0);
                body.remove("enable_thinking");
                body.remove("chat_template_kwargs");
            }
            // 5) 提供商自己声明的字段名/形态（用户为这个端点亲手填的）
            if (includeNative) applyNative(body, p);
            return true;
        } catch (Exception e) {
            LogUtil.w("ThinkingOff", "apply failed: %s", e.getMessage());
            return false;
        }
    }

    /**
     * 这个家族是否可能需要顶层 {@code enable_thinking} / {@code chat_template_kwargs}。
     *
     * <p>家族未知时照发：认不出的模型多半挂在自建网关或中转上，这类端点以
     * 「收下未知字段、CamelCase 布尔」为主，发过去比不发有用。
     */
    private static boolean wantsTopLevelSwitch(String family) {
        return "qwen".equals(family) || "glm".equals(family) || "kimi".equals(family)
                || "minimax".equals(family) || "ernie".equals(family)
                || "hunyuan".equals(family) || "doubao".equals(family)
                || "unknown".equals(family);
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
            // thinking 已经按对象写过，不覆盖：{type:disabled} 是 DeepSeek / Kimi /
            // 混元 / GLM 这一大票端点共同认的写法，改成布尔 false 反而会让它们
            // 认为「类型不对」而忽略。提供商若真的只认布尔，写全的
            // enable_thinking:false 已经在请求里了。
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
        sb.append("thinking:{type:disabled}");
        if (wantsTopLevelSwitch(family)) {
            sb.append(", enable_thinking:false, chat_template_kwargs.enable_thinking:false");
        }
        if ("claude".equals(family)) {
            sb.append(", thinking_budget:0");
        } else if (needsEffort(model)) {
            sb.append(", reasoning_effort:")
                    .append(supportsNone(model) ? "none" : lowestEffort(model));
        }
        if (p != null) {
            String n = paramName(p);
            if (!"thinking".equals(n)) sb.append(", ").append(n).append(":off");
        }
        return sb.toString();
    }
}
