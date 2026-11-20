package xyz.zip8919.app.aichat;

import java.util.Locale;

/**
 * API 请求地址的规范化与拼接。
 *
 * <p><b>为什么单独抽一个类</b>：提供商配置由用户手填，{@code apiUrl} 与
 * {@code chatPath} 各自带不带斜杠完全靠运气。此前全是裸的
 * {@code provider.apiUrl + provider.chatPath}，两边都带斜杠就发出
 * {@code https://api.x.com//chat/completions}（部分网关直接 404，多数网关把路径
 * 当另一条路由），两边都不带就成了 {@code https://api.x.comchat/completions}。
 * 编辑器里改得再对，老配置和导入配置照样是错的，所以<b>请求现场也要过一遍</b>，
 * 不能只在编辑页做校验。
 *
 * <p>纯函数，不依赖 Android，便于用 javac + main 直接跑用例。
 */
final class Endpoint {

    /** 剪贴板里粘进来的地址常带首尾空白与换行。 */
    private static final char[] TRIM = {' ', '\t', '\r', '\n', '\uFEFF'};

    private Endpoint() {
    }

    static String trim(String s) {
        if (s == null) return "";
        int a = 0;
        int b = s.length();
        while (a < b && isTrim(s.charAt(a))) a++;
        while (b > a && isTrim(s.charAt(b - 1))) b--;
        return s.substring(a, b);
    }

    private static boolean isTrim(char c) {
        for (int i = 0; i < TRIM.length; i++) {
            if (TRIM[i] == c) return true;
        }
        return false;
    }

    /**
     * 拼 base 与 path，交界处保证只有一个 {@code /}。
     *
     * <p>base 尾斜杠全去掉、path 首斜杠全去掉再补一个；path 为空时只返回去掉尾斜杠的 base
     * （不补斜杠：{@code https://host} 比 {@code https://host/} 更稳妥，也便于判断托管前缀）。
     */
    static String join(String base, String path) {
        String b = trim(base);
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        String p = trim(path);
        while (p.startsWith("/")) p = p.substring(1);
        if (p.length() == 0) return b;
        if (b.length() == 0) return "/" + p;
        return b + "/" + p;
    }

    /** 聊天补全地址：{@code apiUrl + chatPath}，已归一化。 */
    static String chatUrl(ProviderInfo p) {
        if (p == null) return "";
        return join(p.apiUrl, normalizeChatPath(p.chatPath));
    }

    /**
     * 余额这类「预设自带路径」的接口地址。
     *
     * <p>与 {@link #join} 的差别只有一处：base 已经以版本段结尾（{@code /v1}）时，
     * path 里的同名版本段要丢掉。Base URL 有两种都合法的填法 ——
     * {@code https://api.siliconflow.cn/v1} 与 {@code https://api.siliconflow.cn} ——
     * 而预设路径为了在两种填法下都能用，统一写成了带 {@code /v1} 的形式。
     * 直接拼会得到 {@code /v1/v1/user/info}，服务端一律 404，
     * 也就是用户看到的「查 TokenHub 余额返回 404」。
     */
    static String joinApi(String base, String path) {
        String b = trim(base);
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        String p = trim(path);
        while (p.startsWith("/")) p = p.substring(1);
        String ver = versionSuffix(b);
        if (ver.length() > 0 && p.startsWith(ver + "/")) {
            p = p.substring(ver.length() + 1);
        }
        return join(b, p);
    }

    /**
     * 模型列表地址：取聊天地址的父路径 + {@code /models}。
     *
     * <p>{@code https://api.x.com/chat/completions} → {@code https://api.x.com/models}；
     * {@code https://api.openai.com/v1/chat/completions} → {@code .../v1/models}。
     * 兼容模式地址（{@code /compatible-mode/v1/chat/completions}）同样只退一层，
     * 拿到的是 {@code /compatible-mode/v1/models}——那正是这类网关暴露列表的位置。
     */
    static String modelsUrl(ProviderInfo p) {
        return modelsUrl(p == null ? "" : p.apiUrl, p == null ? "" : p.chatPath);
    }

    static String modelsUrl(String apiUrl, String chatPath) {
        String base = trim(apiUrl);
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        // chat 端点重复 base 里的版本段时（{@code /v1} + {@code /v1/chat}），
        // 直接拼会得到 {@code /v1/v1/models}；这里以 base 的版本段为准
        String p = trim(chatPath);
        while (p.startsWith("/")) p = p.substring(1);
        String baseVer = versionSuffix(base);
        if (baseVer.length() > 0 && p.startsWith(baseVer + "/")) {
            p = p.substring(baseVer.length() + 1);
        }
        String parent = parentOf(p);
        if (parent.endsWith("/")) parent = parent.substring(0, parent.length() - 1);
        return join(base, parent.length() > 0 ? parent + "/models" : "models");
    }

    /** base 末尾的版本段（{@code /v1} / {@code /v4}），没有返回空串。 */
    private static String versionSuffix(String base) {
        int cut = base.lastIndexOf('/');
        if (cut < 0) return "";
        String seg = base.substring(cut + 1);
        if (seg.length() < 2 || (seg.charAt(0) != 'v' && seg.charAt(0) != 'V')) return "";
        for (int i = 1; i < seg.length(); i++) {
            if (!Character.isDigit(seg.charAt(i))) return "";
        }
        return seg;
    }

    /**
     * 去掉最后一段路径：{@code a/b/c} → {@code a/b}。
     *
     * <p>特殊处理 {@code chat/completions}：OpenAI 兼容端点的版本段在 base 里
     * （{@code https://api.openai.com/v1}），chat path 只剩 {@code /chat/completions}，
     * 直接取父路径会得到 {@code /chat}，拼出来是 {@code /chat/models}。这个组合太常见，
     * 单独认一下，退到版本段（含）为止。
     */
    private static String parentOf(String path) {
        String p = path;
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        if (p.equals("chat/completions") || p.endsWith("/chat/completions")) {
            return p.substring(0, p.length() - "chat/completions".length());
        }
        int cut = p.lastIndexOf('/');
        return cut < 0 ? "" : p.substring(0, cut);
    }

    /**
     * 模型列表的备用地址：在 {@link #modelsUrl} 之外再试两处，
     * 覆盖「apiUrl 只写到域名」「apiUrl 写到 /v1」两种常见填法。
     */
    static String[] modelsUrlCandidates(ProviderInfo p) {
        if (p == null) return new String[0];
        String first = modelsUrl(p);
        String second = join(p.apiUrl, "models");
        String third = join(join(schemeOf(p.apiUrl) + hostOf(p.apiUrl), "v1"), "models");
        return new String[]{first, second, third};
    }

    private static String schemeOf(String apiUrl) {
        String u = trim(apiUrl).toLowerCase(Locale.US);
        return u.startsWith("http://") ? "http://" : "https://";
    }

    /**
     * Chat Path 缺省补齐 {@code /chat/completions}。
     *
     * <p>空值是历史遗留：老版本保存时允许空，读取后又按默认值填，
     * 但配置文件里仍可能留着 {@code ""}，请求侧必须兜住。
     */
    static String normalizeChatPath(String path) {
        String p = trim(path);
        return p.length() == 0 ? "/chat/completions" : p;
    }

    /** 编辑器用的规范化：path 补一个首斜杠，去掉多余 {@code //}。 */
    static String tidyChatPath(String path) {
        String p = trim(path);
        while (p.startsWith("/")) p = p.substring(1);
        return "/" + p;
    }

    /**
     * 从 Base URL 提取二级域名，作为提供商名称的默认值。
     *
     * <p>规则：去掉协议与路径，取 host，按 {@code .} 拆开，
     * 跳过 {@code api} / {@code www} 一类前缀；跳过公共后缀的注册后缀
     * （{@code com.cn} / {@code co.uk} 这类 ccTLD 二级后缀）后取最左的一段。
     *
     * <ul>
     *   <li>{@code https://api.example.com/v1} → {@code example}</li>
     *   <li>{@code api.example.co.uk} → {@code example}</li>
     *   <li>{@code dashscope.aliyuncs.com} → {@code ali}？否 —— 只有 {@code api}/{@code www}
     *       这类前缀被跳过，{@code dashscope} 原样保留</li>
     *   <li>纯 IP、localhost、单段 host → 空串（不做无意义的猜测）</li>
     * </ul>
     */
    static String deriveName(String url) {
        String host = hostOf(url);
        if (host.length() == 0) return "";
        if (host.indexOf(':') >= 0) host = host.substring(0, host.indexOf(':')); // 端口
        if (host.length() == 0) return "";
        // IPv4 一律放过：没有「二级域名」可言
        if (host.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")) return "";
        if (host.indexOf('.') < 0) return "";
        String[] parts = host.split("\\.");
        int n = parts.length;
        // 跳过公共后缀：co.uk / com.cn / org.au ...
        int idx = n - 2;
        if (idx > 0 && isSecondLevelSuffix(parts[idx], parts[n - 1])) idx--;
        // 跳过常见前缀（api. / www. / open.）——它们说明「这是接口入口」，不是品牌
        while (idx >= 0 && isGenericPrefix(parts[idx])) idx--;
        if (idx < 0) return "";
        String name = parts[idx];
        if (name.length() == 0) return "";
        return name;
    }

    /** 从任意 URL 里抠 host（不抛异常，解析不了返回空串）。 */
    static String hostOf(String url) {
        String u = trim(url);
        if (u.length() == 0) return "";
        int scheme = u.indexOf("://");
        if (scheme >= 0) {
            u = u.substring(scheme + 3);
        } else if (u.startsWith("//")) {
            u = u.substring(2);
        }
        int stop = u.length();
        int slash = u.indexOf('/');
        if (slash >= 0 && slash < stop) stop = slash;
        int q = u.indexOf('?');
        if (q >= 0 && q < stop) stop = q;
        int h = u.indexOf('#');
        if (h >= 0 && h < stop) stop = h;
        int at = u.indexOf('@');
        if (at >= 0 && at < stop) stop = at; // 附带账号密码的地址
        String host = u.substring(0, stop).trim();
        return host.toLowerCase(Locale.US);
    }

    private static boolean isGenericPrefix(String s) {
        String v = s == null ? "" : s.toLowerCase(Locale.US);
        return v.equals("api") || v.equals("www") || v.equals("open")
                || v.equals("gateway") || v.equals("gw");
    }

    private static boolean isSecondLevelSuffix(String second, String tld) {
        String s = second == null ? "" : second.toLowerCase(Locale.US);
        String t = tld == null ? "" : tld.toLowerCase(Locale.US);
        if (!(t.equals("cn") || t.equals("uk") || t.equals("au") || t.equals("jp")
                || t.equals("hk") || t.equals("tw") || t.equals("mo") || t.equals("kr")
                || t.equals("sg") || t.equals("in") || t.equals("br") || t.equals("nz")
                || t.equals("za") || t.equals("ru"))) {
            return false;
        }
        return s.equals("com") || s.equals("co") || s.equals("net")
                || s.equals("org") || s.equals("gov") || s.equals("edu") || s.equals("ac");
    }

    /**
     * 是否像一条可用的 HTTP(S) 地址：协议必须是 http/https，且 host 非空。
     * 只用于编辑器即时提示，不作为保存的硬门槛（保存另有一道 {@link #isHttpUrl}）。
     */
    static boolean isHttpUrl(String url) {
        String u = trim(url).toLowerCase(Locale.US);
        if (!u.startsWith("http://") && !u.startsWith("https://")) return false;
        return hostOf(u).length() > 0;
    }

    /** 本地 / 内网地址：用于测试连通性时给出「可能被网络策略拦截」的提示。 */
    static boolean isLocalHost(String url) {
        String h = hostOf(url);
        if (h.startsWith("localhost") || h.startsWith("127.") || h.startsWith("192.168.")
                || h.startsWith("10.")) {
            return true;
        }
        // 172.16.0.0 – 172.31.255.255
        if (h.startsWith("172.")) {
            try {
                int second = Integer.parseInt(h.split("\\.")[1]);
                return second >= 16 && second <= 31;
            } catch (Exception ignored) {
                return false;
            }
        }
        return false;
    }
}
