package xyz.zip8919.app.aichat;

import org.json.JSONObject;

/**
 * 「关闭思考」字段写法的离线验证（纯 JDK，不依赖网络与设备）。
 *
 * <p>断言的是 2026-11 在两个真实端点上实测出来的结论：
 * <ul>
 *   <li>两家都认 {@code thinking:{type:disabled}}；</li>
 *   <li>{@code enable_thinking} / {@code chat_template_kwargs} / {@code thinking_budget}
 *       被静默忽略，不该再发；</li>
 *   <li>只认 effort 的家族（o 系列 / GPT-5）只发 {@code reasoning_effort}，
 *       不能带 thinking 对象（OpenAI 官方会 400）；</li>
 *   <li>Anthropic 关闭要带 budget 0。</li>
 * </ul>
 *
 * <p>把请求体打成字符串检查，不依赖 Android 的 ProviderInfo 构造逻辑。
 */
public final class OffFieldCheck {

    private static int failed = 0;
    private static int passed = 0;

    public static void main(String[] args) {
        // 常见的 OpenAI 兼容端点（混元 / DeepSeek / Kimi / GLM / 商汤）
        ProviderInfo p = new ProviderInfo();
        p.thinkingParamName = "thinking";
        p.thinkingType = "object";

        String hy = body("hy4-flash", p);
        check("混元只发 thinking.type=disabled：" + hy,
                hy.contains("\"thinking\"") && hy.contains("\"disabled\""));
        check("不再发 enable_thinking（实测被忽略）", !hy.contains("enable_thinking"));
        check("不再发 chat_template_kwargs（实测被忽略）", !hy.contains("chat_template_kwargs"));
        check("不再发 thinking_budget（实测被忽略）", !hy.contains("thinking_budget"));

        String sn = body("sensenova-6.8-flash", p);
        check("商汤同样只发 thinking 对象", !sn.contains("enable_thinking")
                && sn.contains("\"disabled\""));

        // OpenAI o 系列：只认 reasoning_effort，带 thinking 会 400
        ProviderInfo oa = new ProviderInfo();
        oa.thinkingParamName = "thinking";
        oa.thinkingType = "object";
        String o3 = body("o3-mini", oa);
        check("o 系列只发 reasoning_effort：" + o3,
                o3.contains("reasoning_effort") && !o3.contains("\"thinking\""));
        String g5 = body("gpt-5.1", oa);
        check("gpt-5.1 发 effort=none（真正的关闭）：" + g5, g5.contains("\"none\""));

        // Anthropic：关闭必须 budget 0，且不能同时给对象与预算冲突的写法
        String claude = body("claude-sonnet-4", p);
        check("Claude 关闭带 thinking_budget=0：" + claude,
                claude.contains("thinking_budget") && claude.contains("0"));

        // 提供商自填字段（用户为某个端点手填的键）要保留
        ProviderInfo custom = new ProviderInfo();
        custom.thinkingParamName = "enable_reasoning";
        custom.thinkingType = "boolean";
        String cu = body("my-gateway-model", custom);
        check("自填字段保留：" + cu, cu.contains("enable_reasoning")
                && cu.contains("false"));

        if (failed > 0) {
            System.out.println("FAILED " + failed + " / " + (failed + passed));
            System.exit(1);
        }
        System.out.println("OK " + passed + " checks");
    }

    private static String body(String model, ProviderInfo p) {
        JSONObject b = new JSONObject();
        b.put("model", model);
        ThinkingOff.apply(b, p, model);
        return b.toString();
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  ok   " + name);
        } else {
            failed++;
            System.out.println("  FAIL " + name);
        }
    }
}
