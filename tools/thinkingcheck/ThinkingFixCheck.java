package xyz.zip8919.app.aichat;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 思考档位修复的离线验证（纯 JDK，不依赖 Android / 设备）。
 *
 * <p>覆盖三件事：
 * <ol>
 *   <li>关闭返回里混进正文的思考草稿能按结束标记拆进思考块，正常回答不被误拆；</li>
 *   <li>思考正文与推理 token 分开统计，Anthropic 的 input/output 用量能读到；</li>
 *   <li>标记被拆在两个分片里、一片里带完整草稿+标记+正文、整条流没有标记时都不丢字。</li>
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
        peelSamples();
        normalAnswers();
        usage();
        if (failed > 0) {
            System.out.println("FAILED " + failed + " / " + (failed + passed));
            System.exit(1);
        }
        System.out.println("OK " + passed + " checks");
    }

    // ---- 1. 标记切分（纯字符串，不涉及流）----

    /**
     * 真实样本：sensenova-6.8-flash 关闭思考后 content 的实际形态。
     *
     * <p>5 个不同问题跑出来 5 种不同开头，但 5 次都带 {@code </think>}，
     * 位置就在正文前一个字符——这是「按标记切」而不是「猜开头」的理由。
     */
    private static void peelSamples() {
        // 每组：草稿原文（真实样本）, 期望正文
        String[][] cases = {
            {"Thinking Process:\n\n1.  **Analyze the Request:**\n    *   Input: A word problem\n",
                    "第一步：计算原价打八折后的价格\n128 × 0.8 = 102.4"},
            {"Here's my thought process for calculating 17 × 23:\n\n1.  **Analyze",
                    "17 * 23 = 391"},
            {"Here's a thinking process that leads to the suggested answer:\n\n1. **Understand",
                    "在 Python 中，反转列表有三种最常用的方法"},
            {"好的，用户要求我用一句话介绍自己。首先，我需要明确我的身份是商汤科技的",
                    "我是由商汤科技开发的日日新融合模态大模型"},
            {"用户让我写一句押韵的广告语。我需要先确定广告的主题。既然没有具体说明，",
                    "**节能环保齐努力，幸福生活更美丽！**"},
        };
        for (String[] c : cases) {
            String raw = c[0] + "</think>" + c[1];
            SseRound.Result r = read(sseOf(raw, 7, "delim"), SseRound.Park.MARK);
            check("按标记切开（" + preview(c[0]) + "）",
                    r.parkCount == 1
                            && r.content.toString().equals(c[1])
                            && r.display.toString().equals("[thinking]" + c[0] + "[/thinking]" + c[1]));
        }

        // 没有标记 = 不拆：一个字符都不许改
        SseRound.Result plain = read(sseOf("最终价格是 98.52 元。\n\n先算折扣：102.4。", 5, "delim"),
                SseRound.Park.MARK);
        check("没有标记时原样当正文：" + preview(plain.display.toString()),
                plain.parkCount == 0
                        && plain.display.toString().indexOf("[thinking]") < 0
                        && plain.content.toString().equals("最终价格是 98.52 元。\n\n先算折扣：102.4。"));

        // 短回复（整条流只有几个字）：一个字都不能丢
        SseRound.Result tiny = read(sse(single("98.52")), SseRound.Park.MARK);
        check("短回复不丢字", tiny.display.toString().equals("98.52")
                && tiny.content.toString().equals("98.52"));

        // 标记被拆在两个分片里：不能漏判，也不能把标记留在正文里
        String draftPrefix = "前面是一段足够长的草稿内容，结尾正好卡在标记中间。";
        String[] pieces = {draftPrefix, "</thi", "nk>正文开始"};
        String[] frames = new String[pieces.length];
        for (int i = 0; i < pieces.length; i++) frames[i] = chunk(null, null, pieces[i]);
        SseRound.Result split = read(sse(frames), SseRound.Park.MARK);
        check("标记跨分片也能切：" + preview(split.display.toString()) + " park=" + split.parkCount
                + " content=" + preview(split.content.toString()),
                split.parkCount == 1
                        && split.content.toString().equals("正文开始")
                        && split.display.toString().equals(
                                "[thinking]" + draftPrefix + "[/thinking]正文开始"));

        // 一片里带完整草稿 + 标记 + 正文
        SseRound.Result one = read(sse(single("草稿很长很长的一段内容，长到超过开头探测窗口。</think>最终价格 98.52 元。")),
                SseRound.Park.MARK);
        check("一片内含完整结构", one.parkCount == 1
                && one.content.toString().equals("最终价格 98.52 元。")
                && one.display.toString().equals(
                        "[thinking]草稿很长很长的一段内容，长到超过开头探测窗口。[/thinking]最终价格 98.52 元。"));

        // 真思考（reasoning_content）+ 正文里混草稿：两段都在同一个思考块
        String mixed = sse(
                reasoningChunk("先想想这道题"),
                chunk(null, null, "再想一遍，这段草稿也要进思考块。</think>"),
                chunk(null, null, "最终价格 98.52 元。"));
        SseRound.Result m = read(mixed, SseRound.Park.MARK);
        check("真思考与正文草稿同在一个思考块：" + preview(m.display.toString()),
                m.reasoning.toString().equals("先想想这道题")
                        && m.parkCount == 1
                        && m.content.toString().equals("最终价格 98.52 元。")
                        && m.display.toString().indexOf("最终价格") > m.display.lastIndexOf("[/thinking]"));

        // 开了思考 / 不带关闭字段：即使内容里出现 </think> 也一个字不动
        SseRound.Result off = read(sse(single("正文里提到了 </think> 这个写法。")), SseRound.Park.NONE);
        check("未关闭时不处理标记", off.parkCount == 0
                && off.content.toString().equals("正文里提到了 </think> 这个写法。"));
    }

    // ---- 2. 普通回答（多段、空行）----

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

        // 关闭请求但没有标记：段落之间的空行照原样保留
        SseRound.Result blank = read(sseOf("第一段。\n\n第二段。\n\n", 4, "delim"),
                SseRound.Park.MARK);
        check("段落之间的空行原样保留：" + preview(blank.display.toString()),
                blank.parkCount == 0
                        && blank.display.toString().equals("第一段。\n\n第二段。")
                        && blank.content.toString().equals("第一段。\n\n第二段。"));
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
        for (String f : frames) {
            // 允许把一个 JSON 对象写成多行便于阅读：SSE 的 data 行不能带裸换行，
            // 多行内容按规范拆成多条 data: 行，解析侧会重新拼起来
            for (String line : f.split("\n", -1)) {
                sb.append("data: ").append(line).append("\n");
            }
            sb.append("\n");
        }
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
