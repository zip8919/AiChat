package xyz.zip8919.app.aichat;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.json.JSONObject;

/**
 * 「关闭思考」诊断日志：把每一次请求实际发了什么、端点回了什么，按行落到
 * {@link ThinkLog} 的文件里。
 *
 * <p><b>为什么需要它</b>：关闭不生效有三种完全不同的原因，光看界面无法区分——
 * <ul>
 *   <li>请求里根本没带关闭字段（档位被配置改写、走了别的分支）；</li>
 *   <li>字段带了，端点不认识、当未知字段忽略；</li>
 *   <li>端点关了推理文本，但用量里仍有推理 token。</li>
 * </ul>
 * 这三种的处理方式完全不同，凭空改只会一轮轮来回打转。这里把判断依据原样写下来，
 * 一次复现就能定性。
 *
 * <p><b>只在 debug 构建里生效</b>（{@link #ENABLED}）。正式包不产生任何 IO，
 * 也不会把用户的对话内容写进文件——这里只记字段名、键值与推理片段，不记消息正文。
 */
final class OffDiag {

    static final boolean ENABLED = BuildConfig.DEBUG;

    /** 请求体里与思考相关的键；顺序固定，方便对照两次请求。 */
    private static final String[] KEYS = {
        "thinking", "enable_thinking", "chat_template_kwargs", "thinking_budget",
        "reasoning_effort", "reasoning", "thinking_config",
    };

    /** 推理片段最多写这么长：够看出「是不是真在推理」，又不至于把文件写满。 */
    private static final int SNIPPET = 160;

    private OffDiag() {
    }

    static void log(String msg) {
        if (!ENABLED || msg == null) return;
        ThinkLog.line(msg);
    }

    static void log(String format, Object... args) {
        if (!ENABLED) return;
        try {
            ThinkLog.line(String.format(format, args));
        } catch (Throwable t) {
            // 格式化失败不能影响对话：这类日志只是为了排查
            ThinkLog.line("（日志格式化失败）" + format);
        }
    }

    /**
     * 请求体里与思考相关的键值。
     *
     * @param extraParam 提供商自填的参数名，可能不在 {@link #KEYS} 里
     * @return 形如 {@code thinking={"type":"disabled"} enable_thinking=false}；一个都没有时返回 {@code （无）}
     */
    static String fields(JSONObject body, String extraParam) {
        if (body == null) return "（请求体为空）";
        StringBuilder sb = new StringBuilder();
        List<String> names = new ArrayList<String>();
        for (String k : KEYS) names.add(k);
        if (extraParam != null && extraParam.trim().length() > 0
                && !names.contains(extraParam.trim())) {
            names.add(extraParam.trim());
        }
        for (String k : names) {
            if (!body.has(k)) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(k).append('=').append(clip(String.valueOf(body.opt(k)), 120));
        }
        return sb.length() == 0 ? "（无）" : sb.toString();
    }

    /** 请求体里除思考字段外的规模信息：能看出是不是发错轮次、工具是否带上。 */
    static String shape(JSONObject body) {
        if (body == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("messages=").append(len(body.optJSONArray("messages")));
        sb.append(" tools=").append(len(body.optJSONArray("tools")));
        sb.append(" stream=").append(body.optBoolean("stream", false));
        return sb.toString();
    }

    /**
     * 端点：保留主机与路径，去掉查询串。
     *
     * <p>路径要留着——同一家可能有 {@code /v1} 与 {@code /v1beta} 两种入口，
     * 走错一条就足以让关闭字段失效；而查询串里常带密钥，不能落盘。
     */
    static String endpoint(String url) {
        if (url == null || url.trim().length() == 0) return "（未配置）";
        String s = url.trim();
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        return s;
    }

    /**
     * 给这一轮下结论：把「用户选了关」与「请求里到底有没有关闭字段」
     * 和「端点有没有返回推理」三者对上，直接写明属于哪一类。
     *
     * @param userWantsOff 界面上选的档位是不是「关闭」
     * @param offSent      这一次请求是否真的写入了关闭字段
     * @param resolved     实际下发用的档位（可能已被配置改写成低档）
     * @param blocked      该模型在配置里被记成了「关不掉」
     * @param thinkingText 端点返回的推理字符数
     * @param reasoningTokens 用量里报告的推理 token 数
     */
    static String verdict(boolean userWantsOff, boolean offSent, String resolved,
            boolean blocked, int thinkingText, int reasoningTokens) {
        return verdict(userWantsOff, offSent, resolved, blocked, thinkingText, reasoningTokens, 0);
    }

    /**
     * @param parked 正文里被拆进思考块的草稿段数
     */
    static String verdict(boolean userWantsOff, boolean offSent, String resolved,
            boolean blocked, int thinkingText, int reasoningTokens, int parked) {
        if (!userWantsOff) {
            return thinkingText > 0
                    ? "结论：本次选的是思考档，出现推理属正常。"
                    : "结论：本次选的是思考档。";
        }
        if (parked > 0) {
            return "结论：关闭字段已下发，端点把思考写进了正文（已拆出 " + parked
                    + " 段到思考块），这家没有真正关掉思考。";
        }
        if (!offSent) {
            return "结论：这次请求没有下发关闭字段（实际档位 " + resolved + "），所以仍会思考。";
        }
        if (thinkingText > 0) {
            return "结论：关闭字段已下发，端点仍返回推理文本，说明这家忽略了关闭字段。"
                    + (blocked ? "（配置里也记着「关不掉」，与实测一致）" : "");
        }
        if (reasoningTokens > 0) {
            return "结论：关闭字段已下发，端点没回推理文本，但用量里仍有推理 token="
                    + reasoningTokens + "，说明内部仍在推理。";
        }
        return "结论：关闭字段已下发，本轮没有推理痕迹。";
    }

    /** 推理片段：换行压成空格，避免一条日志被拆成好几行。 */
    static String snippet(CharSequence text) {
        if (text == null || text.length() == 0) return "（空）";
        String s = text.toString().replace('\n', ' ').replace('\r', ' ').trim();
        return clip(s, SNIPPET);
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static int len(org.json.JSONArray a) {
        return a == null ? 0 : a.length();
    }

    /** 调试用：把对象的所有键排成一行，排查「多发了什么」时省事。 */
    static String keys(JSONObject o) {
        if (o == null) return "（空）";
        List<String> names = new ArrayList<String>();
        Iterator<String> it = o.keys();
        while (it.hasNext()) names.add(it.next());
        java.util.Collections.sort(names);
        StringBuilder sb = new StringBuilder();
        for (String n : names) {
            if (sb.length() > 0) sb.append(',');
            sb.append(n);
        }
        return sb.toString();
    }
}
