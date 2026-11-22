package xyz.zip8919.app.aichat;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 对话接口的 HTTP 层：拼请求体、建连接、读错误体、关连接。
 *
 * <p>从 MainActivity 抽出。所有方法都是无状态的，抽出后请求线程只依赖本类，
 * 不再往 Activity 上挂纯协议细节。连接超时 30s、读超时 0（SSE 长连接不能设读超时，
 * 否则模型思考超过阈值就会被动断开）。
 */
final class ChatTransport {

    private static final String TAG = "ChatTransport";

    private ChatTransport() {
    }

    /**
     * OpenAI 兼容的 chat/completions 请求体。
     *
     * <p>思考参数按 provider 声明的方式二选一：{@code boolean} 型直接放开关 + budget，
     * 其余走 {@code {"type":"enabled|disabled"}} 对象 + reasoning_effort。
     * 这两种格式不通用，写错端点会直接 400。
     */
    static JSONObject buildRequestBody(String model, ProviderInfo provider, String thinkingLevel,
            JSONArray messages, boolean stream, JSONArray tools, boolean includeUsage) throws Exception {
        return buildRequestBody(model, provider, thinkingLevel, messages, stream, tools,
                includeUsage, true);
    }

    /**
     * @param sendThinking {@code false} 表示端点明确拒绝思考相关参数（400 降级后重发）：
     *                     {@code thinking}/{@code thinking_budget}/{@code reasoning_effort}
     *                     一个都不下发。自建 provider 的 thinkingType 一律被写成
     *                     {@code object}、参数名写成 {@code thinking}，而严格的 OpenAI
     *                     兼容端点（官方等）会因此每请求都 400。
     */
    static JSONObject buildRequestBody(String model, ProviderInfo provider, String thinkingLevel,
            JSONArray messages, boolean stream, JSONArray tools, boolean includeUsage,
            boolean sendThinking) throws Exception {
        return buildRequestBody(model, provider, thinkingLevel, messages, stream, tools,
                includeUsage, sendThinking, 0);
    }

    /**
     * @param offAttempt 关闭思考的第几次尝试（见 {@link ThinkingOff#plans}）。
     *                   端点 400/422 时由调用方递增，换下一种关闭字段重发；
     *                   只有「思考等级=关」时才起作用。
     */
    static JSONObject buildRequestBody(String model, ProviderInfo provider, String thinkingLevel,
            JSONArray messages, boolean stream, JSONArray tools, boolean includeUsage,
            boolean sendThinking, int offAttempt) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("stream", stream);
        // 档位落档：模型探测过档位、且不含全局档位时，换成最近可用档。
        // 必须在拼 thinking 参数之前做，否则全局档位会原样下发被端点 400 或静默忽略。
        String level = resolveThinkingLevel(model, provider, thinkingLevel);
        // 唯一出口统一做一次协议修复：把 tool_calls 与其 tool 回复重新配平，
        // 位置在最后一个出口而不是每个拼接点，坏历史（已落盘）也能自愈。
        body.put("messages", MessageSanitizer.sanitize(messages));
        if (tools != null && tools.length() > 0) {
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        }
        // 流式下 usage 默认不返回，必须显式要。严格校验参数的端点会因此 400，
        // 由 ChatStreamRunner 的降级链去掉本字段重发。
        if (stream && includeUsage) {
            JSONObject opts = new JSONObject();
            opts.put("include_usage", true);
            body.put("stream_options", opts);
        }

        if (sendThinking) {
            // 字段名兜底：老配置里 thinkingParamName 可能为空，put(null) 会抛异常
            String pName = provider.thinkingParamName;
            if (pName == null || pName.trim().length() == 0) pName = "thinking";
            if ("off".equals(level)) {
                // 关闭思考交给 ThinkingOff 按「家族 → 提供商声明 → 通用 → 不下发」
                // 逐个方案尝试。旧版把四类关闭字段一次全发，严格端点（OpenAI 官方、
                // 部分网关）对多出来的 thinking / chat_template_kwargs 直接 400，
                // 降级时又把整个关闭意图丢掉，于是「选了关，模型照样思考」。
                ThinkingOff.apply(body, provider, model, offAttempt);
            } else if ("boolean".equals(provider.thinkingType)) {
                body.put(pName, true);
                body.put("thinking_budget", ThinkingLevels.budget(level));
            } else {
                JSONObject tObj = new JSONObject();
                tObj.put("type", "enabled");
                body.put(pName, tObj);
                // Anthropic 要求开启思考时必须给预算，漏了直接 400
                if (ReasoningProfiles.needsBudget(model)) {
                    body.put("thinking_budget", ThinkingLevels.budget(level));
                }
                String effort = ThinkingLevels.reasoningEffort(level);
                if (effort != null) {
                    body.put("reasoning_effort", effort);
                }
            }
        }
        return body;
    }

    /**
     * 把全局思考档位换成这个模型真正接受的档位。
     *
     * <p>没有探测过档位的模型原样返回，行为与旧版完全一致。
     * 按「提供商 + 模型 ID」查配置：同一个模型 ID 挂在不同提供商下，档位能力可能不同。
     */
    static String resolveThinkingLevel(String model, ProviderInfo provider, String globalLevel) {
        if (globalLevel == null || globalLevel.length() == 0) return globalLevel;
        try {
            ConfigManager cfg = ConfigManager.getInstance();
            String providerName = provider == null ? null : provider.name;
            ModelInfo m = cfg.findModel(providerName, model);
            if ("off".equals(globalLevel)) {
                // 探测/实测确认这个模型关不掉思考（o 系列、Gemini Pro、推理专用模型等）：
                // 界面里不会提供「关闭」，全局配置可能还留着旧的 off。
                // 这里落到最低档，既避免明知无效还发关闭字段，也让界面与实际一致。
                //
                // 两条判据都要看：配置负责跨重启，运行期记忆负责「刚才聊天里试出来的」。
                // 只看配置的话，同一次运行里刚被否掉的模型，后面每条消息都还要再白试一轮。
                boolean blocked = (m != null && m.thinkingOffBlocked)
                        || ThinkingOff.cannotOff(model);
                if (blocked) {
                    List<String> levels = m == null ? null : m.levelList();
                    if (levels == null || levels.isEmpty()) {
                        // 没探过档位但已确认关不掉：用家族先验的最低档兜底
                        levels = ReasoningProfiles.of(model).levels;
                    }
                    if (levels != null && !levels.isEmpty()) return ThinkingLevels.lowest(levels);
                    return "low";
                }
                return globalLevel;
            }
            if (m == null || !m.levelsProbed()) {
                // 没探测过：按模型家族先验落档（xhigh → high、minimal → 家族最低档）。
                // 旧版原样下发，端点不认识的词会被静默忽略，界面上选的和实际生效的
                // 完全对不上。
                return ReasoningProfiles.clamp(globalLevel, model);
            }
            String actual = ThinkingLevels.resolve(globalLevel, m.levelList());
            if (!actual.equals(globalLevel)) {
                LogUtil.d(TAG, "thinking level %s -> %s (model %s supports %s)",
                        globalLevel, actual, model, m.thinkingLevels);
            }
            return actual;
        } catch (Throwable t) {
            return globalLevel;
        }
    }

    /**
     * 读 {@code Retry-After} 头，返回需要等待的秒数；没有或解析失败返回 -1。
     *
     * <p>两种合法格式都要支持：纯秒数（{@code 120}）与 HTTP-date
     * （{@code Wed, 21 Oct 2015 07:28:00 GMT}）。只认前者会让一半服务端的
     * 限流提示失效，白白退化成指数退避。
     */
    static int retryAfterSeconds(HttpURLConnection conn) {
        if (conn == null) return -1;
        try {
            String raw = conn.getHeaderField("Retry-After");
            if (raw == null) return -1;
            raw = raw.trim();
            if (raw.length() == 0) return -1;
            try {
                int sec = Integer.parseInt(raw);
                return sec < 0 ? -1 : sec;
            } catch (NumberFormatException ignored) {
                // 不是纯数字，继续按 HTTP-date 解析
            }
            java.text.SimpleDateFormat fmt =
                    new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz",
                            java.util.Locale.US);
            java.util.Date when = fmt.parse(raw);
            long diff = (when.getTime() - System.currentTimeMillis()) / 1000L;
            return diff > 0 ? (int) diff : 0;
        } catch (Exception e) {
            LogUtil.d(TAG, "retryAfterSeconds parse failed: %s", e.getMessage());
            return -1;
        }
    }

    /**
     * 是否官方 DeepSeek 端点。只有它支持 Chat Prefix Completion（{@code /beta}），
     * 中转站、自建网关虽然也挂着 deepseek 模型名，但地址不是官方域名，
     * 强行加 {@code prefix} 字段只会得到 400，所以一律走降级路径。
     */
    static boolean isOfficialDeepSeek(ProviderInfo provider) {
        if (provider == null || provider.apiUrl == null) return false;
        String u = provider.apiUrl.trim().toLowerCase(java.util.Locale.US);
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return "https://api.deepseek.com".equals(u)
                || "https://api.deepseek.com/beta".equals(u);
    }

    /** {@code https://api.deepseek.com} → {@code https://api.deepseek.com/beta}。 */
    private static String betaBase(String apiUrl) {
        String base = apiUrl == null ? "" : apiUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (base.endsWith("/beta")) return base;
        return base + "/beta";
    }

    static HttpURLConnection open(ProviderInfo provider) throws Exception {
        return open(provider, false);
    }

    /** @param beta 走官方前缀续写端点，仅 {@link #isOfficialDeepSeek} 为真时传 true。 */
    static HttpURLConnection open(ProviderInfo provider, boolean beta) throws Exception {
        // 走 Endpoint 统一拼接：老配置里 apiUrl 与 chatPath 常常各带一个斜杠，
        // 裸拼会发出 //chat/completions，部分网关直接 404。
        String endpoint = (beta
                ? Endpoint.join(betaBase(provider.apiUrl), provider.chatPath)
                : Endpoint.chatUrl(provider));
        LogUtil.d(TAG, "open: %s%s", endpoint, beta ? " (beta prefix)" : "");
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + provider.apiKey);
        conn.setDoOutput(true);
        conn.setConnectTimeout(30000);
        // 读超时不能为 0：SSE 本来就是长连接，但「一直等不到字节」和「正在思考」
        // 必须能区分开。设为 0 时，NAT 静默丢连接或服务端卡死会让界面永远停在
        // 「正在整理搜索结果」，只能手动打断。120s 内任何字节（含 keep-alive）
        // 都会重置计时，正常思考不会误伤。
        conn.setReadTimeout(120000);
        if (conn instanceof HttpsURLConnection) {
            TlsCompat.apply((HttpsURLConnection) conn);
        }
        return conn;
    }

    static void close(HttpURLConnection conn) {
        if (conn == null) return;
        try {
            conn.disconnect();
        } catch (Exception ignored) {
        }
    }

    /** 读取错误响应体，最多 4KB——够看错误原因，又不至于把日志撑爆。 */
    static String readErrorBody(HttpURLConnection conn) {
        if (conn == null) return "";
        InputStream es = null;
        try {
            es = conn.getErrorStream();
            if (es == null) return "";
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            int total = 0;
            while (total < 4096 && (n = es.read(buf, 0, Math.min(buf.length, 4096 - total))) > 0) {
                bos.write(buf, 0, n);
                total += n;
            }
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return "";
        } finally {
            // 不关流在复用的 keep-alive 连接上会拖住后续请求，也占着 fd
            if (es != null) {
                try { es.close(); } catch (Exception ignored) { }
            }
        }
    }
}
