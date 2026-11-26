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
                includeUsage, sendThinking, true, false);
    }

    /**
     * @param offIncludeNative 关闭思考时是否带上提供商自填的字段。端点 400/422
     *                  拒收自填字段时由调用方传 false 重发：写全的家族字段继续保留，
     *                  只去掉这一份，关闭意图不丢。
     */
    static JSONObject buildRequestBody(String model, ProviderInfo provider, String thinkingLevel,
            JSONArray messages, boolean stream, JSONArray tools, boolean includeUsage,
            boolean sendThinking, boolean offIncludeNative, boolean offBoolOnly) throws Exception {
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
                // 关闭字段一次写全：同一份请求里带上各家认的那个键，谁认谁生效，
                // 不认识的一律当未知字段忽略（见 ThinkingOff 的说明）。
                // 旧版按家族只发一个、被静默忽略时不重试，是「选了关仍在思考」
                // 的主要原因。
                ThinkingOff.apply(body, provider, model, offIncludeNative, offBoolOnly);
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
                // 「关闭」原样下发，不再因为配置里的 thinkingOffBlocked 偷偷换成最低档。
                //
                // 旧版在这里把 off 改写成最低档，界面却仍显示「关闭」——用户看到的是
                // 「关了还在思考」，实际是请求压根没带关闭字段。判定「关不掉」本来就
                // 不可靠（探测条件与真实聊天不同，一次 400 也会被记成关不掉），
                // 拿它改写用户明确选择的档位，等于用一个不确定的结论覆盖确定的意图。
                //
                // 真正的兜底放在请求侧：端点拒收关闭写法时由 ChatStreamRunner 逐级
                // 降级重发，最后才按端点默认回答，并在界面上如实提示。
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
     * 拼好的请求体，附带这次请求实际下发的关键参数，供诊断记录使用。
     *
     * <p>参数不重新推导：{@link #buildRequestBody} 内部会做档位落档与协议修复，
     * 在外面再算一遍很可能与真正发出去的不一致。这里是唯一事实来源。
     */
    static final class Prepared {
        final JSONObject body;
        /** 落档后真正下发的思考档位。 */
        final String thinkingLevel;
        final boolean toolsSent;
        final boolean usageRequested;
        final boolean thinkingSent;

        Prepared(JSONObject body, String thinkingLevel, boolean toolsSent,
                boolean usageRequested, boolean thinkingSent) {
            this.body = body;
            this.thinkingLevel = thinkingLevel;
            this.toolsSent = toolsSent;
            this.usageRequested = usageRequested;
            this.thinkingSent = thinkingSent;
        }
    }

    static Prepared prepare(String model, ProviderInfo provider, String thinkingLevel,
            JSONArray messages, boolean stream, JSONArray tools, boolean includeUsage,
            boolean sendThinking) throws Exception {
        return prepare(model, provider, thinkingLevel, messages, stream, tools,
                includeUsage, sendThinking, true, false);
    }

    /**
     * @param offIncludeNative 关闭思考时是否写自填的原生字段（false 表示 400 后已去掉）
     * @param offBoolOnly      关闭思考时是否只留 {@code enable_thinking:false}
     */
    static Prepared prepare(String model, ProviderInfo provider, String thinkingLevel,
            JSONArray messages, boolean stream, JSONArray tools, boolean includeUsage,
            boolean sendThinking, boolean offIncludeNative, boolean offBoolOnly)
            throws Exception {
        JSONObject body = buildRequestBody(model, provider, thinkingLevel, messages, stream,
                tools, includeUsage, sendThinking, offIncludeNative, offBoolOnly);
        String pName = provider.thinkingParamName;
        if (pName == null || pName.trim().length() == 0) pName = "thinking";
        // 关闭思考时字段由 ThinkingOff 写全，没有 reasoning_effort 键是正常的
        boolean thinkingSent = sendThinking && body.has(pName);
        return new Prepared(body, resolveThinkingLevel(model, provider, thinkingLevel),
                tools != null && tools.length() > 0,
                stream && includeUsage,
                thinkingSent);
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
        return open(provider, beta, true);
    }

    /**
     * @param streamMode {@code body.stream} 为 true 时才声明接受事件流；非流式请求拿到的是
     *                   普通 JSON，声明 text/event-stream 会让个别网关改写响应形态。
     */
    static HttpURLConnection open(ProviderInfo provider, boolean beta, boolean streamMode)
            throws Exception {
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
        // SSE 默认会被中间代理缓冲：服务端一直在写，客户端却直到连接关闭才看到字节。
        // 表现就是「发了消息没有任何回应」，而服务端侧显示早已返回。显式声明不缓冲，
        // 让每一片都直接透传（端点不认这些头时按普通响应头忽略）。
        conn.setRequestProperty("Cache-Control", "no-cache");
        if (streamMode) conn.setRequestProperty("Accept", "text/event-stream");
        conn.setRequestProperty("X-Accel-Buffering", "no");
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

    /**
     * 读取错误响应体，最多 4KB——够看错误原因，又不至于把日志撑爆。
     *
     * <p>没有错误流、但响应声明了 Content-Length 时再读普通输入流：部分网关把错误体
     * 放在 {@code getInputStream()} 里，只读 errorStream 会让错误卡片只剩一个状态码。
     * 没有 Content-Length 时不能贸然读，可能一直等到读超时。
     */
    static String readErrorBody(HttpURLConnection conn) {
        if (conn == null) return "";
        InputStream es = null;
        try {
            es = conn.getErrorStream();
            if (es == null) {
                if (conn.getHeaderField("Content-Length") == null) return "";
                es = conn.getInputStream();
            }
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
