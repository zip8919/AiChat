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

    /**
     * 完整 UA，占位符 {@code {ua}} 用。
     *
     * <p><b>必须缓存</b>：{@code WebSettings.getDefaultUserAgent} 首次调用会初始化
     * WebView 提供者，在主线程上是几十到几百毫秒的卡顿。而 {@code getSystemPrompt}
     * 每次 {@code onResume} 都会跑一次替换——不缓存等于每次回主页都卡一下。
     */
    static synchronized String webviewUserAgent(Context ctx) {
        if (uaResolved) return cachedUa;
        if (ctx == null) return "";
        try {
            String ua = WebSettings.getDefaultUserAgent(ctx);
            cachedUa = ua == null ? "" : ua;
        } catch (Throwable t) {
            // 部分 ROM 在 WebView 提供者未就绪时会抛，属于可预期失败。
            // 不置 uaResolved：下次 WebView 起来后还能再试。
            LogUtil.w("SystemInfo", "getDefaultUserAgent failed: %s", t.getMessage());
            return "";
        }
        uaResolved = true;
        return cachedUa;
    }

    /** 进程内 UA 缓存，见 {@link #webviewUserAgent}。 */
    private static String cachedUa = "";
    private static boolean uaResolved;
    /** 版本号缓存：getPackageInfo 是 IPC，没必要每次替换都走一遍。 */
    private static String cachedAppVersion;

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
        if (cachedAppVersion != null) return cachedAppVersion;
        try {
            PackageInfo info = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0);
            cachedAppVersion = info.versionName == null ? "未知" : info.versionName;
            return cachedAppVersion;
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
     * <p>支持的占位符：
     * <ul>
     *   <li>系统：{@code {android}} {@code {api}} {@code {webview}} {@code {ua}}</li>
     *   <li>设备：{@code {model}}（机型）{@code {brand}}（厂商）{@code {cpu_abi}}</li>
     *   <li>屏幕：{@code {screen}}（宽x高）{@code {density}}（dpi）</li>
     *   <li>时间：{@code {date}} {@code {time}} {@code {datetime}} {@code {weekday}} {@code {timezone}}</li>
     *   <li>应用：{@code {app_version}} {@code {app_package}} {@code {free_storage}}</li>
     *   <li>语言：{@code {locale}}</li>
     * </ul>
     * 未知占位符原样保留——用户粘贴别的模板时不该被悄悄吃掉内容。
     */
    static String fillPlaceholders(String template, Context ctx) {
        if (template == null || template.length() == 0) return "";
        // 绝大多数提示词里没有任何占位符。先看一眼有没有 '{' 就返回，
        // 免得为了一段普通文字去初始化 WebView 内核。
        if (template.indexOf('{') < 0) return template;

        String out = template;
        out = out.replace("{android}", androidVersion());
        out = out.replace("{api}", apiLevel());
        if (out.indexOf("{webview}") >= 0) {
            out = out.replace("{webview}", webviewVersion(ctx));
        }
        out = out.replace("{model}", deviceModel());
        if (out.indexOf("{app_version}") >= 0) {
            out = out.replace("{app_version}", appVersion(ctx));
        }
        if (out.indexOf("{ua}") >= 0) {
            out = out.replace("{ua}", webviewUserAgent(ctx));
        }

        // ---- 时间类：每次渲染取实时值 ----
        // 注意：{date} 每天变一次，会让 system 前缀每天断一次 prompt cache。
        // 需要「会话固定的时间」请用会话注入那一句，不要拿这里替代。
        if (out.indexOf("{date}") >= 0 || out.indexOf("{time}") >= 0
                || out.indexOf("{datetime}") >= 0 || out.indexOf("{weekday}") >= 0
                || out.indexOf("{timezone}") >= 0) {
            long now = System.currentTimeMillis();
            if (out.indexOf("{date}") >= 0) out = out.replace("{date}", formatDate(now));
            if (out.indexOf("{time}") >= 0) out = out.replace("{time}", formatTime(now));
            if (out.indexOf("{datetime}") >= 0) out = out.replace("{datetime}", formatDateTime(now));
            if (out.indexOf("{weekday}") >= 0) out = out.replace("{weekday}", weekday(now));
            if (out.indexOf("{timezone}") >= 0) out = out.replace("{timezone}", timezone(now));
        }

        // ---- 屏幕 / 系统类 ----
        if (out.indexOf("{screen}") >= 0 || out.indexOf("{density}") >= 0) {
            if (ctx != null) {
                android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
                if (out.indexOf("{screen}") >= 0) {
                    out = out.replace("{screen}", dm.widthPixels + "x" + dm.heightPixels);
                }
                if (out.indexOf("{density}") >= 0) {
                    out = out.replace("{density}", String.valueOf(dm.densityDpi));
                }
            } else {
                // 没有 Context 就退成「未知」，而不是把 {screen} 原样留在提示词里
                if (out.indexOf("{screen}") >= 0) out = out.replace("{screen}", "未知");
                if (out.indexOf("{density}") >= 0) out = out.replace("{density}", "未知");
            }
        }
        out = out.replace("{brand}", isBlank(Build.MANUFACTURER) ? "未知" : Build.MANUFACTURER.trim());
        if (out.indexOf("{cpu_abi}") >= 0) out = out.replace("{cpu_abi}", cpuAbi());
        if (out.indexOf("{locale}") >= 0) {
            out = out.replace("{locale}", java.util.Locale.getDefault().toString());
        }

        // ---- 应用类 ----
        if (out.indexOf("{app_package}") >= 0) {
            out = out.replace("{app_package}", ctx == null ? "未知" : ctx.getPackageName());
        }
        if (out.indexOf("{free_storage}") >= 0) {
            out = out.replace("{free_storage}", freeStorage(ctx));
        }
        return out;
    }

    private static String formatDate(long ts) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(new java.util.Date(ts));
    }

    private static String formatTime(long ts) {
        return new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                .format(new java.util.Date(ts));
    }

    private static String formatDateTime(long ts) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                .format(new java.util.Date(ts));
    }

    /** 固定输出「星期X」，不跟随系统语言——英文系统上 EEEE 会变成 Friday。 */
    private static String weekday(long ts) {
        String[] names = {"日", "一", "二", "三", "四", "五", "六"};
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(ts);
        int i = c.get(java.util.Calendar.DAY_OF_WEEK) - java.util.Calendar.SUNDAY;
        if (i < 0 || i > 6) i = 0;
        return "星期" + names[i];
    }

    /** 例如 {@code GMT+8} / {@code GMT+5:30}。 */
    private static String timezone(long ts) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        int offsetMin = c.getTimeZone().getOffset(ts) / 60000;
        String sign = offsetMin >= 0 ? "+" : "-";
        int abs = Math.abs(offsetMin);
        if (abs % 60 == 0) return "GMT" + sign + (abs / 60);
        return String.format(java.util.Locale.US, "GMT%s%d:%02d", sign, abs / 60, abs % 60);
    }

    private static String cpuAbi() {
        try {
            if (Build.VERSION.SDK_INT >= 21 && Build.SUPPORTED_ABIS != null
                    && Build.SUPPORTED_ABIS.length > 0) {
                return Build.SUPPORTED_ABIS[0];
            }
            return isBlank(Build.CPU_ABI) ? "未知" : Build.CPU_ABI;
        } catch (Throwable t) {
            return "未知";
        }
    }

    /** 应用私有目录所在分区的剩余空间，例如 {@code 3.2 GB}。 */
    private static String freeStorage(Context ctx) {
        if (ctx == null) return "未知";
        try {
            java.io.File dir = ctx.getFilesDir();
            if (dir == null) return "未知";
            long free = dir.getUsableSpace();
            if (free <= 0) return "未知";
            if (free >= 1024L * 1024L * 1024L) {
                return String.format(java.util.Locale.US, "%.1f GB",
                        free / (1024.0 * 1024.0 * 1024.0));
            }
            return String.format(java.util.Locale.US, "%.0f MB", free / (1024.0 * 1024.0));
        } catch (Throwable t) {
            return "未知";
        }
    }
}
