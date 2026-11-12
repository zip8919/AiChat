package xyz.zip8919.app.aichat;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * 老 Android (API&lt;21) TLS 兼容层。
 * 默认 socket factory 只启用平台默认协议/密码套件子集，部分现代 CDN
 * （如 token.sensenova.cn）对这种 ClientHello 直接回
 * "sslv3 alert handshake failure"，即使 SSLContext 已指定 TLSv1.2。
 * 这里显式启用全部支持的协议（去掉 SSLv3）和全部密码套件。
 */
public final class TlsCompat {
    private static final String TAG = "TlsCompat";

    static {
        installConscrypt();
    }

    /**
     * 安装 Conscrypt provider 并置顶：平台 TLS 栈在老系统（如 4.4）上无任何
     * AES-GCM 套件，而部分现代 CDN 只接受 GCM，握手必败。Conscrypt 自带
     * BoringSSL，提供 GCM/X25519 等现代套件。失败时静默回退平台栈。
     */
    private static void installConscrypt() {
        try {
            if (java.security.Security.getProvider("Conscrypt") == null) {
                java.security.Security.insertProviderAt(org.conscrypt.Conscrypt.newProvider(), 1);
            }
            LogUtil.i(TAG, "conscrypt provider installed");
        } catch (Throwable t) {
            LogUtil.w(TAG, "conscrypt unavailable (%s), fallback to platform TLS", t.getMessage());
        }
    }

    private TlsCompat() {}

    /**
     * 应用到 HttpsURLConnection：协议/套件全开，证书校验按设置决定。
     *
     * <p><b>证书校验默认走系统信任库</b>。之前这里无条件 trust-all（空实现的
     * {@code checkServerTrusted} + 恒真的 {@code HostnameVerifier}），等于全应用的
     * HTTPS 都没有中间人防护——那是为了解决老系统握手失败顺手写下的，代价太大。
     * 真正的握手兼容性来自 {@link #configure} 里放开协议与密码套件，与是否校验证书无关。
     *
     * <p>只有在设置里显式打开「信任所有证书」时（自签证书的内网服务），才关掉校验，
     * 并打 warn 级日志留痕。
     */
    private static volatile SSLSocketFactory normalFactory;
    private static volatile SSLSocketFactory trustAllFactory;

    public static void apply(HttpsURLConnection conn) {
        boolean trustAll = NetSecurity.trustAllCerts();
        try {
            conn.setSSLSocketFactory(factory(trustAll));
            if (trustAll) {
                conn.setHostnameVerifier(ALWAYS_TRUE);
                LogUtil.w(TAG, "apply: TRUST-ALL enabled for %s — cert check disabled",
                        conn.getURL().getHost());
            } else {
                LogUtil.d(TAG, "apply: TLS compat factory installed for %s", conn.getURL().getHost());
            }
        } catch (Exception e) {
            LogUtil.e(TAG, "apply failed", e);
        }
    }

    /**
     * 两套工厂各建一次：原来每个 HTTPS 请求都重建 SSLContext + 重读系统 CA 库
     * + new 包装工厂，一轮工具调用能触发 5~10 次。工厂本身无状态、可共享。
     * trustAll 布尔每次由 apply() 实时读取，设置页切换后下一次请求即生效；
     * 因此必须缓存两套，不能只懒加载一个（那会把首见状态冻结住）。
     */
    private static SSLSocketFactory factory(boolean trustAll) throws Exception {
        SSLSocketFactory f = trustAll ? trustAllFactory : normalFactory;
        if (f != null) return f;
        synchronized (TlsCompat.class) {
            f = trustAll ? trustAllFactory : normalFactory;
            if (f != null) return f;
            SSLContext ssl = SSLContext.getInstance("TLSv1.2");
            ssl.init(null, trustAll ? TRUST_ALL : systemTrustManagers(), null);
            f = new AllEnabledFactory(ssl.getSocketFactory());
            if (trustAll) trustAllFactory = f; else normalFactory = f;
            return f;
        }
    }

    /** 系统信任库（含用户安装的 CA）。显式取一次，避免依赖 provider 的默认行为。 */
    private static TrustManager[] systemTrustManagers() throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((java.security.KeyStore) null);
        return tmf.getTrustManagers();
    }

    private static final TrustManager[] TRUST_ALL = new TrustManager[]{ new X509TrustManager() {
        public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
        public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
            return new java.security.cert.X509Certificate[0];
        }
    } };

    private static final HostnameVerifier ALWAYS_TRUE = new HostnameVerifier() {
        public boolean verify(String hostname, javax.net.ssl.SSLSession session) { return true; }
    };

    static void configure(SSLSocket socket) {
        try {
            List<String> protocols = new ArrayList<String>(Arrays.asList(socket.getSupportedProtocols()));
            protocols.remove("SSLv3");
            protocols.remove("SSLv2Hello");
            socket.setEnabledProtocols(protocols.toArray(new String[0]));

            List<String> suites = new ArrayList<String>(Arrays.asList(socket.getSupportedCipherSuites()));
            suites.remove("TLS_EMPTY_RENEGOTIATION_INFO_SCSV");
            socket.setEnabledCipherSuites(suites.toArray(new String[0]));
            LogUtil.v(TAG, "configure: protocols=%s suites=%d",
                    protocols, suites.size());
        } catch (Exception e) {
            LogUtil.w(TAG, "configure socket failed: %s", e.getMessage());
        }
    }

    private static final class AllEnabledFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;

        AllEnabledFactory(SSLSocketFactory delegate) { this.delegate = delegate; }

        private Socket wrap(Socket s) {
            if (s instanceof SSLSocket) configure((SSLSocket) s);
            return s;
        }

        @Override public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return wrap(delegate.createSocket(s, host, port, autoClose));
        }
        @Override public Socket createSocket(String host, int port) throws IOException {
            return wrap(delegate.createSocket(host, port));
        }
        @Override public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            return wrap(delegate.createSocket(host, port, localHost, localPort));
        }
        @Override public Socket createSocket(InetAddress host, int port) throws IOException {
            return wrap(delegate.createSocket(host, port));
        }
        @Override public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
            return wrap(delegate.createSocket(address, port, localAddress, localPort));
        }
        @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
    }
}
