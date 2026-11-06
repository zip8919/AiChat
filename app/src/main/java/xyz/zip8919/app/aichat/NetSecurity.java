package xyz.zip8919.app.aichat;

/**
 * 网络层安全开关的进程内快照。
 *
 * <p>为什么要有这个类：TLS 与 URL 校验发生在 {@link ApiClient}、{@link ChatTransport}、
 * {@link TavilyTool}、{@link WebSearchTool}、{@link HttpRequestTool} 这些静态方法里，
 * 它们拿不到 {@link android.content.Context}，而每个请求都去读一次 SharedPreferences 又太脏。
 *
 * <p><b>这个类是纯容器，不读配置</b>：读取与刷新由 {@code MainActivity} / {@code SettingsActivity}
 * 负责（两处都必须调，漏刷的后果是「改了开关当次不生效」）。保持无依赖也让它可以进 JVM 测试。
 *
 * <p>默认值一律是严格模式。任何一处默认值改成 true，都等于把防线默认关掉。
 */
final class NetSecurity {

    /** 默认严格：信任系统 CA、校验主机名。 */
    private static volatile boolean trustAllCerts = false;

    /** 默认严格：拒绝环回 / 私有网段 / 链路本地 / 唯一本地地址。 */
    private static volatile boolean allowPrivateHost = false;

    private NetSecurity() {
    }

    static void set(boolean trustAll, boolean allowPrivate) {
        trustAllCerts = trustAll;
        allowPrivateHost = allowPrivate;
    }

    static boolean trustAllCerts() {
        return trustAllCerts;
    }

    static boolean allowPrivateHost() {
        return allowPrivateHost;
    }
}
