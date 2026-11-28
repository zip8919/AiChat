package xyz.zip8919.app.aichat;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 思考档位修复的离线验证（纯 JDK，不依赖 Android / 设备）。
 *
 * <p>覆盖三件事：
 * <ol>
 *   <li>关闭返回里混进正文的思考草稿能被拆进思考块，正常回答不被误拆；</li>
 *   <li>思考正文与推理 token 分开统计，Anthropic 的 input/output 用量能读到；</li>
 *   <li>分片跨段落边界、段间空行、多段连发时正文不丢字、不走样。</li>
 * </ol>
 *
 * <p>运行（需要 org.json，与 App 用的是同一份实现）：
 * <pre>
 * javac -encoding UTF-8 -cp json.jar -d out \
 *     tools/thinkingcheck/Stubs.java tools/thinkingcheck/ThinkingFixCheck.java \
 *     app/src/main/java/xyz/zip8919/app/aichat/{SseRound,UsageView,ToolCalls}.java
 * java -cp out:json.jar xyz.zip8919.app.aichat.ThinkingFixCheck
 * </pre>
 * 全部通过时退出码 0。只覆盖与 Android 无关的解析层：草稿识别、分片拼接、用量读取。
 */
public final class ThinkingFixCheck {

    private static int failed = 0;
    private static int passed = 0;

    public static void main(String[] args) throws Exception {
        drafts();
        normalAnswers();
        splitStream();
        usage();
        if (failed > 0) {
            System.out.println("FAILED " + failed + " / " + (failed + passed));
            System.exit(1);
        }
        System.out.println("OK " + passed + " checks");
    }

    // ---- 1. 草稿识别 ----

    private static void drafts() {
        String[] draft = {
                "思考：用户问的是价格，先打八折再算手续费。",
                "1. 题目说的是先打八折\n2. 然后加收 5%\n3. 最后减 9 元",
                "**分析**：题目要求一步一步算。",
                "让我先看一遍题目。",
                "用户想要的是最终价格，不是折扣比例。",
                "Let me work through this step by step.",
                "我们来逐步计算。",
                "根据题意，原价 128 元。",
                "Hmm, the user asks about price.",
        };
        for (String d : draft) expectDraft(d, true);
        String[] answer = {
                "最终价格是 92.28 元。",
                "- 最终价格：92.28 元\n- 折扣：八折",
                "结论：92.28 元。",
                "这里需要说明一下：原价 128 元打八折是 102.4 元。",
                "1. 先算折扣 20%，得到 102.4 元",
                "可以直接用公式：128×0.8×1.05−9。",
                "The final price is 92.28 yuan.",
                "答案是 92.28。",
        };
        for (String a : answer) expectDraft(a, false);
    }

    private static void expectDraft(String text, boolean want) {
        boolean got = SseRound.thinkingIsDraft(text);
        check((want ? "识别为草稿" : "不当草稿") + "：" + text, got == want);
    }

    // ---- 2. 整段流（模拟 SSE）----

    private static void normalAnswers() {
        // 开着思考时请求不带关闭字段：正文一个字都不拆
        String body = sse(
                chunk("delim", null, "结论：92.28 元。\n\n原价 128 元打八折是 102.4 元。"),
                chunk("delim", null, "\n\n"),
                chunk("delim", null, "再加 5% 手续费是 107.52 元，减 9 元得 98.52 元。"));
        SseRound.Result r = read(body, SseRound.Park.NONE);
        check("无关闭字段时不拆正文", r.parkCount == 0
                && r.display.indexOf("[thinking]") < 0
                && r.display.indexOf("结论：92.28") == 0);
    }

    private static void splitStream() {
        // 关闭请求：端点把草稿和回答都塞进 content
        String draft = "思考：用户要的是最终价格。\n\n先打八折：128×0.8=102.4。\n\n";
        String answer = "最终价格是 98.52 元。\n\n计算：102.4×1.05−9=98.52。";
        // 分片任意切开，模拟真实逐字下发的边界
        String body = sseOf(draft + answer, 7, "delim");
        SseRound.Result r = read(body, SseRound.Park.FIRST_PARAGRAPH);
        String display = r.display.toString();
        int thinkEnd = display.indexOf("[/thinking]");
        // 第一段是草稿，之后逐段判断：第二段「先打八折…」不是草稿，判断到此结束，
        // 它和后面的回答都是正文
        check("草稿被拆进思考块：" + preview(display),
                r.parkCount == 1 && thinkEnd > 0
                        && display.indexOf("思考：") > 0
                        && display.substring(0, thinkEnd).indexOf("先打八折") < 0);
        check("正文里不再有草稿", display.substring(thinkEnd).indexOf("思考：") < 0
                && display.indexOf("思考：") > 0);
        check("content（上报与落盘）不含草稿：" + preview(r.content.toString()),
                r.content.toString().indexOf("思考：") < 0
                        && r.content.toString().startsWith("先打八折：128×0.8=102.4。")
                        && r.content.toString().endsWith("计算：102.4×1.05−9=98.52。"));
        check("display 以草稿开头、以回答结尾",
                display.indexOf("思考：") < display.indexOf("[/thinking]")
                        && display.endsWith("计算：102.4×1.05−9=98.52。"));

        // 关闭请求但回答正常：一个字都不拆
        SseRound.Result ok = read(sseOf("最终价格是 98.52 元。\n\n先算折扣：102.4。", 5, "delim"),
                SseRound.Park.FIRST_PARAGRAPH);
        check("正常回答不被拆", ok.parkCount == 0
                && ok.display.toString().indexOf("[thinking]") < 0);

        // 关闭请求、端点正常作答且段落之间有空行：段落分隔照原样保留
        SseRound.Result blank = read(sseOf("第一段。\n\n第二段。\n\n", 4, "delim"),
                SseRound.Park.FIRST_PARAGRAPH);
        check("段落之间的空行原样保留：" + preview(blank.display.toString()),
                blank.display.toString().equals("第一段。\n\n第二段。")
                        && blank.content.toString().equals("第一段。\n\n第二段。"));

        // 一片里同时带着「草稿段 + 空行 + 正文段」：边界后的字不能丢
        SseRound.Result wide = read(sse(single("思考：先算折扣。\n\n最终价格 98.52 元。\n\n过程：102.4×1.05−9。")),
                SseRound.Park.FIRST_PARAGRAPH);
        check("跨段的一片不丢字：" + preview(wide.content.toString()),
                wide.parkCount == 1
                        && wide.content.toString().equals("最终价格 98.52 元。\n\n过程：102.4×1.05−9。"));

        // 先有 reasoning_content（真思考），正文里又混一段草稿：两段都在思考块里
        String mixed = sse(
                reasoningChunk("先想想这道题"),
                chunk("delim", null, "分析：题目要一步一步算。\n\n"),
                chunk("delim", null, "最终价格 98.52 元。\n\n"),
                chunk("delim", null, "过程如上。"));
        SseRound.Result m = read(mixed, SseRound.Park.FIRST_PARAGRAPH);
        String md = m.display.toString();
        check("真思考与正文草稿同在一个思考块：" + preview(md),
                m.reasoning.toString().equals("先想想这道题")
                        && md.indexOf("分析：题目要一步一步算") > 0
                        && md.indexOf("最终价格 98.52 元") > md.lastIndexOf("[/thinking]")
                        && m.parkCount == 1);

        // 草稿一段连到底、没有空行：流结束时也要归到思考块，不能留在正文
        SseRound.Result one = read(sse(single("思考：答案是 98.52 元。")),
                SseRound.Park.FIRST_PARAGRAPH);
        check("单段草稿流结束时归入思考块：" + preview(one.display.toString()),
                one.parkCount == 1
                        && one.display.toString().startsWith("[thinking]")
                        && one.display.toString().endsWith("[/thinking]"));
    }

    // ---- 3. 用量 ----

    private static void usage() throws Exception {
        org.json.JSONObject oai = new org.json.JSONObject(
                "{\"prompt_tokens\":100,\"completion_tokens\":40,\"total_tokens\":140,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":60},"
                + "\"completion_tokens_details\":{\"reasoning_tokens\":25}}");
        UsageView a = UsageView.of(oai);
        check("OpenAI 用量", a.input == 100 && a.output == 40 && a.cached == 60 && a.reasoning == 25);

        org.json.JSONObject anth = new org.json.JSONObject(
                "{\"input_tokens\":120,\"output_tokens\":30,\"output_tokens_details\":{\"reasoning_tokens\":8}}");
        UsageView b = UsageView.of(anth);
        check("Anthropic 用量", b.input == 120 && b.output == 30 && b.reasoning == 8);

        org.json.JSONObject none = new org.json.JSONObject("{\"foo\":1}");
        UsageView c = UsageView.of(none);
        check("没有用量时不伪造 0", c.input == -1 && c.output == -1);

        int[] p = {0}, co = {0}, t = {0}, h = {0};
        UsageView.of(anth).addTo(p, co, t, h);
        UsageView.of(oai).addTo(p, co, t, h);
        check("累计用量", p[0] == 220 && co[0] == 70 && t[0] == 290 && h[0] == 60);
    }

    // ---- 构造 SSE ----

    private static String sse(String... frames) {
        StringBuilder sb = new StringBuilder();
        for (String f : frames) sb.append("data: ").append(f).append("\n\n");
        sb.append("data: [DONE]\n\n");
        return sb.toString();
    }

    private static String chunk(String suffix, String reasoning, String content)
            throws RuntimeException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"choices\":[{\"index\":0,\"delta\":{");
        boolean comma = false;
        if (content != null) {
            sb.append("\"content\":").append(quote(content));
            comma = true;
        }
        if (reasoning != null) {
            if (comma) sb.append(',');
            sb.append("\"reasoning_content\":").append(quote(reasoning));
        }
        sb.append("}}]}");
        return sb.toString();
    }

    private static String reasoningChunk(String text) {
        return chunk(null, text, null);
    }

    private static String single(String content) {
        return chunk(null, null, content);
    }

    /** 把整段正文切成固定长度的小片，每片一帧。 */
    private static String sseOf(String text, int size, String suffix) {
        List<String> frames = new ArrayList<String>();
        for (int i = 0; i < text.length(); i += size) {
            frames.add(chunk(null, null, text.substring(i, Math.min(text.length(), i + size))));
        }
        return sse(frames.toArray(new String[0]));
    }

    /** 测试数据里的引号按 JSON 规则转义；不依赖 android.jar 里的桩实现。 */
    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static SseRound.Result read(String body, SseRound.Park park) {
        try {
            return SseRound.read(true, new ByteArrayInputStream(body.getBytes("UTF-8")),
                    null, new SseRound.Abort() {
                        public boolean isAborted() {
                            return false;
                        }
                    }, park);
        } catch (Exception e) {
            check("读取失败：" + e, false);
            return new SseRound.Result();
        }
    }

    private static String preview(String s) {
        String t = s.replace("\n", "\\n");
        return t.length() > 160 ? t.substring(0, 160) + "…" : t;
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
