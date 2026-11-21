package xyz.zip8919.app.aichat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 按模型家族给出「思考档位」与「关闭方式」的先验知识。
 *
 * <p><b>为什么需要它</b>：端点对不认识的思考字段几乎一律返回 200 并静默忽略。
 * 旧探测把「HTTP 200」当成「这一档支持」，于是任何中转上都能探出六档全支持，
 * 用户选了 minimal 实际发出去的仍是端点默认值。开源客户端（Cherry Studio 等）
 * 的做法不是逐档打请求，而是<b>按模型 ID 归类到推理家族</b>，由家族决定
 * 支持哪几档、用哪个字段、档位词汇怎么映射；探测只用来确认端点收不收。
 *
 * <p>这里只做一张小巧的家族表，覆盖国内常用的几大家；认不出的模型返回空档位，
 * 由 {@link CapabilityProbe} 退回「只按端点行为判定」，不硬编结论。
 */
final class ReasoningProfiles {

    /** 一个家族的思考能力描述。 */
    static final class Profile {
        final String family;
        /** 该家族真正接受的档位（{@link ThinkingLevels} 词汇）；空表示未知。 */
        final List<String> levels;
        /**
         * 关闭思考时是否值得多发一个顶层 {@code enable_thinking:false}。
         *
         * <p>Qwen 系与部分中转只认这个顶层字段，{@code thinking.type=disabled}
         * 会被直接忽略；而严格的 OpenAI 端点对多余字段会 400，所以只对已知家族发。
         */
        final boolean topLevelEnableThinking;

        Profile(String family, List<String> levels, boolean topLevelEnableThinking) {
            this.family = family;
            this.levels = levels;
            this.topLevelEnableThinking = topLevelEnableThinking;
        }
    }

    private static final Profile UNKNOWN =
            new Profile("unknown", new ArrayList<String>(), false);

    private ReasoningProfiles() {
    }

    /**
     * 按模型 ID 归类。匹配的都是「模型名里的家族段」，不看提供商名字：
     * 同一个模型挂在不同中转下，家族不会变。
     */
    static Profile of(String modelId) {
        String m = modelId == null ? "" : modelId.trim().toLowerCase(Locale.US);
        if (m.length() == 0) return UNKNOWN;

        // OpenAI 推理系列：o1/o3/o4 只认 reasoning_effort，gpt-5 起多了 minimal
        if (starts(m, "o1") || starts(m, "o3") || starts(m, "o4")) {
            return profile("openai-o", "minimal,low,medium,high", false);
        }
        if (starts(m, "gpt-5")) {
            // 5.2 起支持 xhigh；5 / 5.1 只有到 high
            if (m.indexOf("5.2") >= 0) {
                return profile("openai-gpt5", "minimal,low,medium,high,xhigh", false);
            }
            return profile("openai-gpt5", "minimal,low,medium,high", false);
        }
        if (m.indexOf("deepseek") >= 0) {
            // V3.1 / V3.2 / reasoner：low、high、max 三个词，medium 走 high
            return profile("deepseek", "low,medium,high,max", false);
        }
        if (m.indexOf("qwen") >= 0 || m.indexOf("qwq") >= 0 || m.indexOf("qvq") >= 0) {
            // 通义系用 thinking_budget 控强度：词汇到「高」，最高预算另用 max 表示
            return profile("qwen", "minimal,low,medium,high,max", true);
        }
        if (m.indexOf("glm") >= 0) {
            return profile("glm", "low,medium,high,max", true);
        }
        if (m.indexOf("hunyuan") >= 0 || starts(m, "hy") || m.indexOf("混元") >= 0) {
            return profile("hunyuan", "minimal,low,medium,high,max", true);
        }
        if (m.indexOf("doubao") >= 0 || m.indexOf("seed-") >= 0
                || m.indexOf("seed1") >= 0 || m.indexOf("seed-oss") >= 0) {
            return profile("doubao", "minimal,low,medium,high,max", true);
        }
        if (m.indexOf("claude") >= 0 || m.indexOf("sonnet") >= 0
                || m.indexOf("opus") >= 0 || m.indexOf("haiku") >= 0) {
            // Anthropic 只有 budget，词表按预算大小落到四档
            return profile("claude", "low,medium,high,max", false);
        }
        if (m.indexOf("kimi") >= 0 || m.indexOf("moonshot") >= 0 || starts(m, "k2")) {
            return profile("kimi", "low,medium,high,max", true);
        }
        if (m.indexOf("minimax") >= 0) {
            return profile("minimax", "minimal,low,medium,high,max", true);
        }
        if (m.indexOf("grok") >= 0) {
            return profile("grok", "low,medium,high", false);
        }
        if (m.indexOf("gemini") >= 0) {
            return profile("gemini", "minimal,low,medium,high", false);
        }
        if (m.indexOf("ernie") >= 0 || m.indexOf("文心") >= 0) {
            return profile("ernie", "low,medium,high", true);
        }
        return UNKNOWN;
    }

    /** 模型是不是已知的推理家族（用于「端点没给 reasoning 证据」时的判定）。 */
    static boolean known(String modelId) {
        return of(modelId).levels.size() > 0;
    }

    /**
     * 把用户选的档位落到家族支持的档位。家族未知时原样返回，
     * 让探测结果（如果有）成为唯一依据。
     */
    static String clamp(String level, String modelId) {
        Profile p = of(modelId);
        if (p.levels.isEmpty() || level == null) return level;
        return ThinkingLevels.resolve(level, p.levels);
    }

    private static Profile profile(String family, String csv, boolean topLevel) {
        List<String> levels = new ArrayList<String>(Arrays.asList(csv.split(",")));
        ThinkingLevels.sort(levels);
        return new Profile(family, levels, topLevel);
    }

    private static boolean starts(String s, String prefix) {
        return s.startsWith(prefix);
    }
}
