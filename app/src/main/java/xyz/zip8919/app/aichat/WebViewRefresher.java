package xyz.zip8919.app.aichat;

import android.webkit.WebView;

/**
 * WebView 侧样式刷新（实现方案 §7.2 / §7.4）。
 *
 * <p>作为 AppearanceManager.ChangeListener，只做两件事：替换页面里
 * {@code <style id="ac-theme">} / {@code <style id="ac-font">} 的文本内容，
 * 以及 setTextZoom。<b>绝不 loadDataWithBaseURL 重灌整页</b>——保留滚动位置、
 * 不重置 DOM、不打断进行中的流式输出。
 *
 * <p>JS 执行复用 MainActivity.webViewEvalJs()（已按 SDK 分流
 * evaluateJavascript / loadUrl，并做线程编组），不在这里自造第二套。
 */
final class WebViewRefresher implements AppearanceManager.ChangeListener {

    private static final String TAG = "WebViewRefresher";

    /** 由 MainActivity 提供：拿到 WebView 并执行一段已转义好的 JS / 设置 zoom。 */
    interface Host {
        void evalJs(String js);
        void applyZoom(int percent);
    }

    private final Host host;
    private String lastThemeCss;
    private String lastFontCss;

    /** MainActivity 整页重灌后调用：新 DOM 的 style 是初始值，必须强制再注入一次。 */
    void reset() {
        lastThemeCss = null;
        lastFontCss = null;
    }

    WebViewRefresher(Host host) {
        this.host = host;
    }

    @Override
    public void onAppearanceChanged(AppearanceManager m) {
        if (host == null) return;
        boolean dark = m.isDark();
        String themeCss = ThemeCssBuilder.themeStyleContent(m, dark);
        String fontCss = ThemeCssBuilder.fontStyleContent(m);
        // 字号/显示大小变化也会触发 bump，但 CSS 内容一个字没变：
        // 只更新 zoom，省掉一次整段 JS 注入（长页面上是明显的帧开销）。
        // 页面重灌时 buildPage 自带 style，跳过注入不会丢样式。
        if (themeCss.equals(lastThemeCss) && fontCss.equals(lastFontCss)) {
            host.applyZoom(ScalePolicy.textZoom(m.getDisplayPercent()));
            return;
        }
        lastThemeCss = themeCss;
        lastFontCss = fontCss;
        String js = "(function(){"
                + "var t=document.getElementById('ac-theme');if(t)t.textContent='" + ThemeCssBuilder.jsString(themeCss) + "';"
                + "var f=document.getElementById('ac-font');if(f)f.textContent='" + ThemeCssBuilder.jsString(fontCss) + "';"
                + "})()";
        host.evalJs(js);
        host.applyZoom(ScalePolicy.textZoom(m.getDisplayPercent()));
        LogUtil.d(TAG, "onAppearanceChanged: themeCss=%d fontCss=%d dark=%s zoom=%d",
                themeCss.length(), fontCss.length(), dark, m.getDisplayPercent());
    }

    /** WebView 首次加载后同步一次 zoom（buildPage 已带好 style，这里只补 zoom）。 */
    static void applyInitialZoom(WebView webView, int percent) {
        if (webView == null) return;
        try {
            webView.getSettings().setTextZoom(ScalePolicy.textZoom(percent));
        } catch (Throwable t) {
            LogUtil.w(TAG, "applyInitialZoom failed: " + t.getMessage());
        }
    }
}
