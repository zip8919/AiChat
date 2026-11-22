package xyz.zip8919.app.aichat;

public class ModelInfo {
    /** 请求里真正发给 API 的模型 ID，同时是全局唯一键。 */
    public String name;
    /**
     * 界面显示名称（可空）。
     *
     * <p>模型 ID 常常又长又难认（{@code deepseek-v4-flash-0731}），用户想显示成「快速版」
     * 时填这里。请求体仍然发 {@link #name}，两者互不影响。空值回退到 {@code name}。
     */
    public String displayName;
    /** 提供商唯一标识（{@link ProviderInfo#name}）。 */
    public String provider;
    public boolean supportsThinking;
    /**
     * 是否支持多模态输入（{@code content} 里放 {@code image_url} 块）。
     *
     * <p>默认关闭：把图片塞进请求体是「端点不认就整条 400」的操作，不能靠猜。
     * 用户在模型配置里显式勾上才发图；没勾时图片附件只写一行说明，
     * 不会让模型对着「看不见的图」瞎编。
     */
    public boolean supportsVision;

    /**
     * 模型实际接受的思考档位，逗号分隔（如 {@code low,high,max}）。空串 = 未探测。
     *
     * <p>与 {@link #supportsThinking} 分工：那个是「开不开思考」的开关，这个是
     * 「开到哪几档」的明细。探测出档位集合后，{@code supportsThinking} 同步置为
     * 非空，两者不会互相矛盾。
     *
     * <p>请求侧按它落档（见 {@link ThinkingLevels#resolve}）：全局档位不在集合里时
     * 自动换成最近可用档，避免端点 400 或静默忽略。未探测（空串）时行为与旧版一致。
     */
    public String thinkingLevels;

    /** 能力探测时间（毫秒）。0 表示从未探测，界面显示「未探测」。 */
    public long probedAt;

    /** 探测时端点对档位取值不做校验（传什么都 200），档位结论只能算「全部接受」。 */
    public boolean levelsUnverifiable;

    /**
     * 该模型无法关闭思考（探测或实际请求里确认「关」之后仍有推理痕迹）。
     *
     * <p>为真时思考档位下拉不再列出「关闭」项：留着它，用户选了也关不掉，
     * 只会以为开关失灵。o 系列、Gemini Pro、部分推理专用模型属于这一类。
     */
    public boolean thinkingOffBlocked;

    /** 界面上一律用它，读不到显示名就回退模型 ID。 */
    public String label() {
        if (displayName != null && displayName.trim().length() > 0) return displayName.trim();
        return name == null ? "" : name;
    }

    /**
     * 唯一键：「提供商 + 模型 ID」。
     *
     * <p><b>为什么不能只用 name</b>：同一个模型 ID 在不同提供商各挂一份是常态
     * （{@code deepseek-v4-flash} 在 DeepSeek 官方与中转站都有），而
     * {@code deepseek-v4-flash} 这个名字本身并不唯一。早先用 name 当默认模型的
     * 标识，两条同名记录会<b>同时</b>在列表里显示「默认」，切换也分不清是哪一条。
     *
     * <p>分隔符用 U+001F（单元分隔符）：模型 ID 与提供商名都是可见字符，
     * 不可能出现控制字符，因此不会与真实名字产生歧义。存储层直接当普通字符串处理。
     */
    public String key() {
        return (provider == null ? "" : provider) + '\u001f' + (name == null ? "" : name);
    }

    /** 模型 ID 与提供商都相同，才算同一条配置。 */
    public boolean sameAs(ModelInfo other) {
        if (other == null) return false;
        return key().equals(other.key());
    }

    /** 可用思考档位列表（未探测为空列表）。 */
    public java.util.List<String> levelList() {
        return ThinkingLevels.parse(thinkingLevels);
    }

    public boolean levelsProbed() {
        return thinkingLevels != null && thinkingLevels.trim().length() > 0;
    }

    /** 写入档位集合（同步维护「是否支持思考」）。 */
    public void setLevelList(java.util.List<String> levels) {
        thinkingLevels = ThinkingLevels.join(levels);
        if (thinkingLevels.length() > 0) supportsThinking = true;
    }

    public ModelInfo copy() {
        ModelInfo m = new ModelInfo();
        m.name = name;
        m.displayName = displayName;
        m.provider = provider;
        m.supportsThinking = supportsThinking;
        m.supportsVision = supportsVision;
        m.thinkingLevels = thinkingLevels;
        m.probedAt = probedAt;
        m.levelsUnverifiable = levelsUnverifiable;
        m.thinkingOffBlocked = thinkingOffBlocked;
        return m;
    }
}
