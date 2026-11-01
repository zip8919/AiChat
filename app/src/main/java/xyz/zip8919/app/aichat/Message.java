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
