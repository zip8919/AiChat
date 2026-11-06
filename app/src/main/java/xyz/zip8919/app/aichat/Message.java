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
     * 用量是本地估算的，不是端点返回的。
     *
     * <p>端点不支持 {@code stream_options.include_usage}（或流式关闭）时拿不到真实用量，
     * 此时用正文字数粗估填进 token 字段，并置此位——渲染时加 {@code ~} 前缀，
     * 让用户知道这是估的不是账。混在一起不标，比不显示更糟。
     */
    public boolean tokensEstimated;

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
