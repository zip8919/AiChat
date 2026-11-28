package xyz.zip8919.app.aichat;

import org.json.JSONObject;

/**
 * 端点返回的用量对象的统一读法。
 *
 * <p><b>为什么单独抽出来</b>：用量有两个来源（流式最后一帧、非流式响应体），
 * 又有两套字段（OpenAI 与 Anthropic：{@code prompt_tokens} /
 * {@code input_tokens}）。此前每个读取点各写一套，Anthropic 一类的端点
 * 在收费统计里恒为 0，判断「关掉思考后还有没有推理」时也读不到推理计数。
 * 这里一次收口，所有读取点共用。
 */
final class UsageView {

    /** 输入 token；端点没给返回 -1（不是 0——0 与「没上报」含义不同）。 */
    final int input;
    /** 输出 token；端点没给返回 -1。 */
    final int output;
    /** 输入里命中前缀缓存的 token；没有记录返回 0。 */
    final int cached;
    /** 输出里的推理 token；没有记录返回 0。 */
    final int reasoning;

    private UsageView(int input, int output, int cached, int reasoning) {
        this.input = input;
        this.output = output;
        this.cached = cached;
        this.reasoning = reasoning;
    }

    static final UsageView EMPTY = new UsageView(-1, -1, 0, 0);

    /** 解析用量对象；null 或无任何可用字段时返回 {@link #EMPTY}。 */
    static UsageView of(JSONObject usage) {
        if (usage == null) return EMPTY;
        int in = firstPositive(usage, "prompt_tokens", "input_tokens");
        int out = firstPositive(usage, "completion_tokens", "output_tokens");
        if (in < 0 && out < 0) return EMPTY;
        return new UsageView(in, out, cacheHitOf(usage), reasoningOf(usage));
    }

    /**
     * 把本用量加进四个累计值。
     *
     * <p>端点没上报的字段按 0 计：累计侧只有「加了没有」两种状态，
     * 保留 -1 会把整个累计值污染成 -1。命中量超过本次输入时截到输入值。
     */
    void addTo(int[] promptTokens, int[] completionTokens, int[] totalTokens, int[] cachedTokens) {
        int in = Math.max(0, input);
        int out = Math.max(0, output);
        promptTokens[0] += in;
        completionTokens[0] += out;
        totalTokens[0] += in + out;
        cachedTokens[0] += Math.min(cached, in);
    }

    /**
     * 解析「命中缓存的输入 token」。
     *
     * <p>DeepSeek 放在顶层 {@code prompt_cache_hit_tokens}；OpenAI 系放在
     * {@code prompt_tokens_details.cached_tokens}。两边都认，取第一个大于 0 的。
     * 都不认识就返回 0——宁可这条不显示，也不要拿输入量冒充命中量。
     */
    static int cacheHitOf(JSONObject usage) {
        if (usage == null) return 0;
        int hit = usage.optInt("prompt_cache_hit_tokens", 0);
        if (hit > 0) return hit;
        // 部分网关把 DeepSeek 的字段也塞进 details 里，一并认
        JSONObject d = usage.optJSONObject("prompt_tokens_details");
        if (d != null) {
            hit = d.optInt("cached_tokens", 0);
            if (hit > 0) return hit;
            hit = d.optInt("cache_hit_tokens", 0);
        }
        return Math.max(0, hit);
    }

    /**
     * 输出里的推理 token。
     *
     * <p>三种写法都见过：顶层 {@code reasoning_tokens}、{@code thinking_tokens}、
     * 以及 OpenAI 官方的 {@code completion_tokens_details.reasoning_tokens}。
     * Anthropic 不单列推理计数：它把思考算进 {@code output_tokens}，这类端点
     * 只能靠响应里的思考文本判断，不在这里估算。
     */
    static int reasoningOf(JSONObject usage) {
        if (usage == null) return 0;
        int n = usage.optInt("reasoning_tokens", 0);
        if (n <= 0) n = usage.optInt("thinking_tokens", 0);
        if (n <= 0) {
            JSONObject d = usage.optJSONObject("completion_tokens_details");
            if (d != null) n = d.optInt("reasoning_tokens", 0);
        }
        if (n <= 0) {
            JSONObject d = usage.optJSONObject("output_tokens_details");
            if (d != null) n = d.optInt("reasoning_tokens", 0);
        }
        return n > 0 ? n : 0;
    }

    private static int firstPositive(JSONObject o, String a, String b) {
        int v = o.optInt(a, 0);
        if (v > 0) return v;
        v = o.optInt(b, 0);
        return v > 0 ? v : -1;
    }
}
