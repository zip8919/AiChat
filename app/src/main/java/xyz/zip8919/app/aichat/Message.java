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

    /** 用户消息：该次请求是否失败。仅用于 UI 提示与重试，不进入 API 请求。 */
    public boolean sendFailed;

    /** 失败详情（HTTP 状态、响应体、异常等）。仅用于错误卡片，不进入 API 请求。 */
    public String errorDetail = "";

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
