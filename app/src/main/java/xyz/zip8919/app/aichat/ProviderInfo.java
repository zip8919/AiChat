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
    /**
     * 余额查询平台预设 id，见 {@link BalancePresets#all()}。
     *
     * <p>空值按 {@code deepseek} 处理：老配置里只有 DeepSeek 一家支持余额查询，
     * 升级后行为与以前一致，用户不必重新配置。
     */
    public String balancePreset;
    /**
     * 余额接口路径（相对 Base URL）。非空时优先于预设自带的路径，
     * 用来适配预设里没收录的平台。
     */
    public String balancePath;
    /**
     * 腾讯云 SecretId / SecretKey（可空）。
     *
     * <p>只有余额平台选「腾讯云」时才用得上：账户余额与 Token Plan 余量只存在于
     * 腾讯云云 API，必须现场签名，推理用的 API Key 拿不到。其它平台留空即可。
     * 与 {@link #apiKey} 一样明文存在 config.json 里，仅本机使用。
     */
    public String secretId;
    public String secretKey;

    /** 界面上一律用它，读不到显示名就回退唯一标识。 */
    public String label() {
        if (displayName != null && displayName.trim().length() > 0) return displayName.trim();
        return name == null ? "" : name;
    }

    /** 当前余额平台是否走云 API 签名（目前只有腾讯云）。 */
    public boolean signedBalance() {
        return BalancePresets.byId(balancePreset).signed();
    }

    /** 这个提供商实际使用的余额接口路径：用户填的优先，其次预设默认值。 */
    public String effectiveBalancePath() {
        // 签名平台没有余额接口路径，查的是云 API
        if (signedBalance()) return "";
        // 旧配置的「腾讯云 TokenHub」id 已在 BalancePresets.byId 里映射到新预设，
        // 走的也是签名通道，不会再去拼一个 404 的路径
        if (balancePath != null && balancePath.trim().length() > 0) return balancePath.trim();
        return BalancePresets.byId(balancePreset).path;
    }

    /** 是否配了余额查询（有路径才查，全空表示不支持）。 */
    public boolean hasBalance() {
        return effectiveBalancePath().length() > 0;
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
        p.balancePreset = balancePreset;
        p.balancePath = balancePath;
        p.secretId = secretId;
        p.secretKey = secretKey;
        return p;
    }
}
