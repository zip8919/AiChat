package xyz.zip8919.app.aichat;

import java.util.ArrayList;
import java.util.List;

public class Message {
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_SYSTEM = "system";

    public String role;
    public String content;
    public long timestamp;

    /**
     * 该条 AI 回复引用的来源（已按 URL 去重）。
     * 不写入 content、不参与渲染正文，也不进入后续 API 请求体。
     */
    public List<Source> sources = new ArrayList<Source>();

    /**
     * 该条消息对应的请求是否失败。用户消息：整条发送失败；AI 消息：工具结果
     * 已回传后模型请求失败（此时保留已展示的思考/工具卡片，支持「从出错处重试」）。
     */
    public boolean sendFailed;

    /** 失败详情（HTTP 状态、响应体、异常等）。仅用于错误卡片，不进入 API 请求。 */
    public String errorDetail = "";

    /**
     * 该条 AI 回复真正发给模型的正文（不含思考块、工具卡片等展示标记）。
     * 后续轮次回放历史时优先用它，避免因为正文里残留 {@code [thinking]} /
     * {@code [tool]} 标记而被整段剥离。旧会话为空时回退到清洗 content。
     */
    public String apiText = "";

    /**
     * 该条 AI 回复过程中产生的工具调用历史（JSON 数组字符串）。
     * 内容是一串 OpenAI 格式的 assistant(tool_calls) / tool 消息，用于两件事：
     * 1) 后续轮次把工具调用与结果回放给模型，模型才知道之前搜过什么；
     * 2) 工具轮次请求失败后，从失败处继续请求，而不是整条消息重发。
     */
    public String toolHistory = "";

    /**
     * 生成完成时刻（毫秒）。0 表示未知：老会话读出来就是 0，此时只显示时间不显示速度。
     * 用 finishedAt - timestamp 算端到端耗时，包含工具轮次，比单轮耗时有意义。
     */
    public long finishedAt;

    /**
     * 服务端返回的 token 用量。0 表示该端点不支持 {@code stream_options.include_usage}，
     * 此时界面上不显示 token——宁可没有，也不要拿本地估算值冒充真实用量。
     */
    public int promptTokens;
    public int completionTokens;
    public int totalTokens;

    /**
     * 命中「前缀缓存」的 token 数。0 表示端点没上报或这次没命中。
     *
     * <p>两家口径不同，解析时都要认：DeepSeek 用顶层 {@code prompt_cache_hit_tokens}，
     * OpenAI 用 {@code prompt_tokens_details.cached_tokens}。命中率 = 本值 / promptTokens。
     */
    public int cachedTokens;

    /**
     * 这条回复一共发了多少次请求。工具轮次会一轮多发，1 表示只发了一次。
     *
     * <p>用于消息底部那行汇总：「3 次请求 · 12.4k tokens · 命中 50%」。
     * 只数模型请求轮次，不含 429 重试——重试属于同一次请求的失败重发，
     * 算进来会让「一次请求」变成 4 次，用户对不上账。
     */
    public int requestCount;

    /**
     * 每一轮请求各自的用量。工具轮次会一轮一发，这里逐轮记录，
     * 消息底部才能写出「第 1 轮输入 8.0k / 输出 120」这种能对上账单的明细。
     *
     * <p>只记端点真的返回了 usage 的轮次；被打断的最后一轮没有 usage，
     * 由宿主在中断时补一条 {@code estimated=true} 的估算记录。
     * 空列表表示这个端点不报用量（老会话也是空）。
     */
    public List<RoundUsage> rounds = new ArrayList<RoundUsage>();

    /** 单轮请求的用量快照。 */
    public static class RoundUsage {
        /** 该轮请求的输入 token（含全部上下文）。 */
        public int prompt;
        /** 该轮生成的 token。 */
        public int completion;
        /** 该轮命中的前缀缓存 token。 */
        public int cached;
        /** 该轮端到端耗时（毫秒）。 */
        public long ms;
        /** 本地估算而非端点上报。 */
        public boolean estimated;

        public RoundUsage() {}

        public RoundUsage(int prompt, int completion, int cached, long ms, boolean estimated) {
            this.prompt = prompt;
            this.completion = completion;
            this.cached = cached;
            this.ms = ms;
            this.estimated = estimated;
        }
    }

    /**
     * 这条 AI 回复是被用户手动打断的（不是请求失败）。
     *
     * <p>打断时没有真实 usage，内容也不完整。渲染层据此<b>跳过本地估算</b>：
     * 对半截回答报一个精确到个位的 token 数，比不显示更容易误导人对账。
     */
    public boolean interrupted;

    /**
     * 用量是本地估算的，不是端点返回的。
     *
     * <p>端点不支持 {@code stream_options.include_usage}（或流式关闭）时拿不到真实用量，
     * 此时用正文字数粗估填进 token 字段，并置此位——渲染时加 {@code ~} 前缀，
     * 让用户知道这是估的不是账。混在一起不标，比不显示更糟。
     */
    public boolean tokensEstimated;

    /**
     * 该条用户消息附带的文本附件（文件名、字节数、正文）。
     *
     * <p>附件是<b>结构化字段</b>，不再拼进 {@link #content}：早先把「[附件] x.txt（12 KB）」
     * 直接写进正文，编辑一下正文就把这行删了，界面上附件凭空消失；而且正文里混着
     * 文件元信息，复制、搜索、重新发送都要先剥一遍。
     *
     * <p>正文只在这里存一份。发给模型的文本由 {@link AttachmentText#apiTextOf} 现场拼，
     * 不再另存一份 {@link #apiText}——两处存同一份正文，总会有一处忘同步。
     */
    public List<AttachmentText.File> attachments = new ArrayList<AttachmentText.File>();

    public Message() {}

    public Message(String role, String content) {
        this.role = role;
        this.content = content;
        this.timestamp = System.currentTimeMillis();
    }

    public boolean isUser() {
        return ROLE_USER.equals(role);
    }

    public boolean isAssistant() {
        return ROLE_ASSISTANT.equals(role);
    }

    /** 一个搜索/抓取来源。 */
    public static class Source {
        public String title;
        public String url;

        public Source() {}

        public Source(String title, String url) {
            this.title = title;
            this.url = url;
        }
    }
}
