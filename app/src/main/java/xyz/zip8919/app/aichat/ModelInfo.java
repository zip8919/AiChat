package xyz.zip8919.app.aichat;

public class ModelInfo {
    public String name;
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
}
