package xyz.zip8919.app.aichat;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.webkit.WebSettings;

/**
 * 运行环境信息。给代码执行提示词做占位符替换用——模型只有知道自己在什么环境里跑，
 * 才不会写出 ES6 语法让 4.3 的 WebView 直接报错。
 *
 * <p>所有取值都做了兜底：厂商 ROM 上 {@code Build.MODEL} 可能为空，
 * {@code WebSettings.getDefaultUserAgent} 在 WebView 未初始化时可能抛异常。
 * 拿不到就返回「未知」，绝不让一个提示词把整条设置流程搞崩。
 */
final class SystemInfo {

    private SystemInfo() {
    }

    /** 例如 "4.4.2"。 */
    static String androidVersion() {
        String v = Build.VERSION.RELEASE;
        return isBlank(v) ? "未知" : v.trim();
    }

    /** 例如 "19"。 */
    static String apiLevel() {
        return String.valueOf(Build.VERSION.SDK_INT);
    }

    /**
     * WebView 内核版本，例如 "Chrome 30.0.0.0"。
     * 直接从默认 UA 里取 Chrome/xx 段——{@code WebViewFactory} 是隐藏 API，反射不可靠。
     */
    static String webviewVersion(Context ctx) {
        String ua = webviewUserAgent(ctx);
        if (isBlank(ua)) return "未知";
        int at = ua.indexOf("Chrome/");
        if (at < 0) return "未知";
        int start = at + "Chrome/".length();
        int end = start;
        while (end < ua.length()) {
            char c = ua.charAt(end);
            if ((c >= '0' && c <= '9') || c == '.') {
                end++;
            } else {
                break;
            }
        }
        return end > start ? "Chrome " + ua.substring(start, end) : "未知";
    }

    /** 完整 UA，占位符 {@code {ua}} 用。 */
    static String webviewUserAgent(Context ctx) {
        if (ctx == null) return "";
        try {
            String ua = WebSettings.getDefaultUserAgent(ctx);
            return ua == null ? "" : ua;
        } catch (Throwable t) {
            // 部分 ROM 在 WebView 提供者未就绪时会抛，属于可预期失败
            LogUtil.w("SystemInfo", "getDefaultUserAgent failed: %s", t.getMessage());
            return "";
        }
    }

    /** 例如 "Xiaomi MI 3"。 */
    static String deviceModel() {
        String brand = Build.MANUFACTURER;
        String model = Build.MODEL;
        if (isBlank(model)) return isBlank(brand) ? "未知" : brand.trim();
        if (isBlank(brand) || model.toLowerCase().startsWith(brand.toLowerCase())) {
            return model.trim();
        }
        return brand.trim() + " " + model.trim();
    }

    /** 例如 "1.8.0"。 */
    static String appVersion(Context ctx) {
        if (ctx == null) return "未知";
        try {
            PackageInfo info = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0);
            return info.versionName == null ? "未知" : info.versionName;
        } catch (Exception e) {
            LogUtil.w("SystemInfo", "appVersion failed: %s", e.getMessage());
            return "未知";
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().length() == 0;
    }

    /**
     * 把提示词模板里的占位符换成真实值。
     *
     * <p>支持的占位符：{@code {android}} {@code {api}} {@code {webview}}
     * {@code {model}} {@code {app_version}} {@code {ua}}。
     * 未知占位符原样保留——用户粘贴别的模板时不该被悄悄吃掉内容。
     */
    static String fillPlaceholders(String template, Context ctx) {
        if (template == null || template.length() == 0) return "";
        String out = template;
        out = out.replace("{android}", androidVersion());
        out = out.replace("{api}", apiLevel());
        out = out.replace("{webview}", webviewVersion(ctx));
        out = out.replace("{model}", deviceModel());
        out = out.replace("{app_version}", appVersion(ctx));
        out = out.replace("{ua}", webviewUserAgent(ctx));
        return out;
    }
}
