package xyz.zip8919.app.aichat;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;

/**
 * 一次流式请求的全部输入。全部字段 final —— 构造完成即不可变，
 * 请求线程只读，不会出现「跑到一半被 UI 线程改了开关」这类难复现的问题。
 *
 * <p>这些值原先散落在 {@code sendStreamingRequest} 开头的二十多个局部变量里，
 * 靠阅读顺序理解它们从哪来。现在集中在一处，加字段要显式在
 * {@link MainActivity#buildStreamJob} 里赋值，改动面一眼可见。
 */
final class StreamJob {

    /** 工具轮次中途失败后的续跑状态：保留已回放的 tool_calls，只重发最后一次请求。 */
    static final class Resume {
        final int aiIndex;
        final int round;
        final String trace;
        final JSONArray conversation;
        final List<String[]> sources;
        final String apiText;
        final String toolHistoryJson;

        Resume(int aiIndex, int round, String trace, JSONArray conversation,
                List<String[]> sources, String apiText, String toolHistoryJson) {
            this.aiIndex = aiIndex;
            this.round = round;
            this.trace = trace;
            this.conversation = conversation;
            this.sources = sources;
            this.apiText = apiText;
            this.toolHistoryJson = toolHistoryJson;
        }
    }

    final ProviderInfo provider;
    final String modelName;
    final String thinkingLevel;
    final int generation;
    final int aiIndex;
    final Message aiMessage;
    /** 发起时的对话快照：请求线程遍历期间 UI 线程可能结构性修改 messages。 */
    final List<Message> snapshot;
    /** 本轮用户问题，作为搜索的上下文。 */
    final String question;
    /** 不含工具说明的系统提示，供应商拒绝 tools 时回退用。 */
    final String systemPrompt;
    /** 含工具说明的系统提示。 */
    final String effectiveSystemPrompt;
    final String providerName;
    /** 仅用于错误详情展示，不参与发请求。 */
    final String chatEndpoint;

    final boolean streamEnabled;
    /** 是否请求服务端返回 token 用量（端点不支持时会被降级掉）。 */
    final boolean includeUsage;
    final boolean realtimeRender;
    final boolean webSearchEnabled;
    /** 是否向模型暴露 http_request 工具（默认关闭，设置页开启）。 */
    final boolean httpToolEnabled;
    /**
     * 当前模型是否支持多模态输入。开启后图片附件与图片搜索结果会以
     * {@code image_url} 块随请求发出；关闭时只写一行文字说明。
     */
    final boolean vision;
    /**
     * Application context，仅用于把图片下载/编码成 data URI（视觉注入）。
     * 传 application 而不是 Activity：请求线程会活到请求结束，持有 Activity 是泄漏。
     */
    final android.content.Context appContext;
    final boolean useTavily;
    /** 搜索是否真的可用：Tavily 看内置密钥，DeepSeek 看能否解析到提供商。 */
    final boolean searchReady;
    final int maxCallsPerRound;
    final ProviderInfo searchProvider;
    final String tavilyKey;

    final Resume resume;
    /** 429 重试策略，随请求固化，避免跑到一半参数被改。 */
    final RetryPolicy retryPolicy;

    StreamJob(Builder b) {
        this.provider = b.provider;
        this.modelName = b.modelName;
        this.thinkingLevel = b.thinkingLevel;
        this.generation = b.generation;
        this.aiIndex = b.aiIndex;
        this.aiMessage = b.aiMessage;
        this.snapshot = b.snapshot;
        this.question = b.question;
        this.systemPrompt = b.systemPrompt;
        this.effectiveSystemPrompt = b.effectiveSystemPrompt;
        this.providerName = b.providerName;
        this.chatEndpoint = b.chatEndpoint;
        this.streamEnabled = b.streamEnabled;
        this.includeUsage = b.includeUsage;
        this.realtimeRender = b.realtimeRender;
        this.webSearchEnabled = b.webSearchEnabled;
        this.httpToolEnabled = b.httpToolEnabled;
        this.vision = b.vision;
        this.appContext = b.appContext;
        this.useTavily = b.useTavily;
        this.searchReady = b.searchReady;
        this.maxCallsPerRound = b.maxCallsPerRound;
        this.searchProvider = b.searchProvider;
        this.tavilyKey = b.tavilyKey;
        this.resume = b.resume;
        this.retryPolicy = b.retryPolicy == null
                ? new RetryPolicy(false, 0, 2000L, true, 30000L) : b.retryPolicy;
    }

    /** 续跑时沿用原来的消息与轮次，否则从头开始。 */
    boolean isResume() {
        return resume != null;
    }

    List<String[]> initialSources() {
        return resume != null ? new ArrayList<String[]>(resume.sources) : new ArrayList<String[]>();
    }

    static Builder builder() {
        return new Builder();
    }

    static final class Builder {
        ProviderInfo provider;
        String modelName;
        String thinkingLevel;
        int generation;
        int aiIndex;
        Message aiMessage;
        List<Message> snapshot;
        String question;
        String systemPrompt;
        String effectiveSystemPrompt;
        String providerName;
        String chatEndpoint;
        boolean streamEnabled;
        boolean includeUsage;
        boolean realtimeRender;
        boolean webSearchEnabled;
        boolean httpToolEnabled;
        boolean vision;
        android.content.Context appContext;
        boolean useTavily;
        boolean searchReady;
        int maxCallsPerRound;
        ProviderInfo searchProvider;
        String tavilyKey;
        Resume resume;
        RetryPolicy retryPolicy;

        Builder provider(ProviderInfo v) { provider = v; return this; }
        Builder modelName(String v) { modelName = v; return this; }
        Builder thinkingLevel(String v) { thinkingLevel = v; return this; }
        Builder generation(int v) { generation = v; return this; }
        Builder aiIndex(int v) { aiIndex = v; return this; }
        Builder aiMessage(Message v) { aiMessage = v; return this; }
        Builder snapshot(List<Message> v) { snapshot = v; return this; }
        Builder question(String v) { question = v; return this; }
        Builder systemPrompt(String v) { systemPrompt = v; return this; }
        Builder effectiveSystemPrompt(String v) { effectiveSystemPrompt = v; return this; }
        Builder providerName(String v) { providerName = v; return this; }
        Builder chatEndpoint(String v) { chatEndpoint = v; return this; }
        Builder streamEnabled(boolean v) { streamEnabled = v; return this; }
        Builder includeUsage(boolean v) { includeUsage = v; return this; }
        Builder realtimeRender(boolean v) { realtimeRender = v; return this; }
        Builder webSearchEnabled(boolean v) { webSearchEnabled = v; return this; }
        Builder httpToolEnabled(boolean v) { httpToolEnabled = v; return this; }
        Builder vision(boolean v) { vision = v; return this; }
        Builder appContext(android.content.Context v) { appContext = v; return this; }
        Builder useTavily(boolean v) { useTavily = v; return this; }
        Builder searchReady(boolean v) { searchReady = v; return this; }
        Builder maxCallsPerRound(int v) { maxCallsPerRound = v; return this; }
        Builder searchProvider(ProviderInfo v) { searchProvider = v; return this; }
        Builder tavilyKey(String v) { tavilyKey = v; return this; }
        Builder resume(Resume v) { resume = v; return this; }
        Builder retryPolicy(RetryPolicy v) { retryPolicy = v; return this; }

        StreamJob build() {
            return new StreamJob(this);
        }
    }
}
