package xyz.zip8919.app.aichat;

public class ProviderInfo {
    /** 唯一标识。模型用这个字段引用提供商，改名会同步所有引用。 */
    public String name;
    /**
     * 界面显示名称（可空）。
     *
     * <p>{@code name} 承担唯一键职责（自动从域名提取，形如 {@code example}），
     * 不适合当人看的文案；用户想写成「某某中转站」时填在这里。空值回退到 {@code name}。
     */
    public String displayName;
    public String apiKey;
    public String apiUrl;
    public String chatPath;
    public String thinkingType;      // "object" or "boolean"
    public String thinkingParamName; // "thinking" or "enable_thinking"
    public boolean supportsBalance;

    /** 界面上一律用它，读不到显示名就回退唯一标识。 */
    public String label() {
        if (displayName != null && displayName.trim().length() > 0) return displayName.trim();
        return name == null ? "" : name;
    }

    public ProviderInfo copy() {
        ProviderInfo p = new ProviderInfo();
        p.name = name;
        p.displayName = displayName;
        p.apiKey = apiKey;
        p.apiUrl = apiUrl;
        p.chatPath = chatPath;
        p.thinkingType = thinkingType;
        p.thinkingParamName = thinkingParamName;
        p.supportsBalance = supportsBalance;
        return p;
    }
}
