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

    /** 界面上一律用它，读不到显示名就回退模型 ID。 */
    public String label() {
        if (displayName != null && displayName.trim().length() > 0) return displayName.trim();
        return name == null ? "" : name;
    }

    public ModelInfo copy() {
        ModelInfo m = new ModelInfo();
        m.name = name;
        m.displayName = displayName;
        m.provider = provider;
        m.supportsThinking = supportsThinking;
        m.supportsVision = supportsVision;
        return m;
    }
}
