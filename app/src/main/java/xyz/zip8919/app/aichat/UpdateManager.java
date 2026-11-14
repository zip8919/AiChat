package xyz.zip8919.app.aichat;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

/**
 * 版本更新：GitHub Release 查询、镜像测速、APK 下载。
 *
 * <p><b>两条设计约束</b>
 * <ol>
 * <li><b>API 查询直连，不走镜像。</b>加速镜像代理的是 {@code github.com/.../releases/download/}
 *     这类下载链接，不代理 {@code api.github.com}；而且 API 响应只有几 KB，直连最稳。
 *     镜像只用于改写 {@code browser_download_url}。</li>
 * <li><b>「测速」是 HTTP 首字节探测，不是 ICMP ping。</b>Android 上
 *     {@code InetAddress.isReachable} 走 ICMP，移动网络/防火墙普遍丢弃，测出的数字
 *     与实际下载无关。这里对每个镜像发 {@code Range: bytes=0-1023}，记录 TTFB
 *     并实际读 1KB，既验证可用性又贴近真实下载链路。</li>
 * </ol>
 *
 * <p>网络分两个单线程 Executor：版本查询与下载共用一个（本就先后发生），测速单独
 * 一个。测速是 7 个镜像串行探测的长任务，若与下载共用线程，点「下载」会一直排队
 * 到测速跑完才真正开始。拆开后最多同时 2 个 TLS 连接，老设备 Cortex-A7 + 66MB
 * 内存仍可控（真正要避免的是 7 个并发握手）。回调统一 post 回主线程。
 */
public final class UpdateManager {

    private static final String TAG = "UpdateManager";
    public static final String REPO = "zip8919/AiChat";
    private static final String API_LATEST =
            "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String UA =
            "AiChat-Updater/1.0 (Android API " + Build.VERSION.SDK_INT + ")";
    private static final String PREFS = "aichat_update";
    private static final String KEY_CUSTOM = "custom_mirrors";
    private static final String KEY_SELECTED = "selected_mirror";
    /** 被用户「移除」的内置源模板，持久化后不出现在列表里；「恢复默认」清空它。 */
    private static final String KEY_HIDDEN = "hidden_builtin";

    /** 每个 release 内置的测速文件名与内容魔数：测速必须真的下到这个文件才算可用。 */
    public static final String SPEEDTEST_ASSET = "speedtest.txt";
    public static final String SPEEDTEST_MAGIC = "AICHAT_SPEEDTEST_V1";

    /** -1 未测，-2 不可用，>=0 为毫秒。 */
    public static final int NOT_TESTED = -1;
    public static final int FAILED = -2;

    /**
     * 内置加速源。
     *
     * <p>前 7 项是本项目原有的候选（保留模板字符串，避免老用户已保存的选中项失效），
     * 其余来自 <a href="https://github.akams.cn">github.akams.cn</a> 节点列表
     * （2026-11-14 抓取，贡献 + 测绘两类共 83 个域名，去重后固化）。
     *
     * <p>为什么不运行时去拉：该站点是 Next.js 应用，节点列表内嵌在约 800KB 的 JS
     * bundle 里、没有公开 JSON 接口。API 18 的词典笔为了几十行配置去下载并解析
     * 一个前端 bundle，既慢又随时会因为站点改版失效。固化后 App 零额外联网，
     * 站点新增节点由后续版本更新；用户自己发现的源仍可用「自定义」添加。
     *
     * <p><b>不承诺可用</b>：镜像可用性随网络环境变化，一律以 App 内测速结果为准。
     * 测速会实际下载 release 内置的测速文件并校验内容，返回错误页的源会被判为不可用。
     * 模板含 {url} 时替换，否则当作前缀直接拼接。
     */
    private static final String[][] BUILTIN = {
            {"直连 github.com", ""},
            {"gh-proxy.com", "https://gh-proxy.com/{url}"},
            {"ghfast.top", "https://ghfast.top/{url}"},
            {"ghproxy.link", "https://ghproxy.link/{url}"},
            {"ghproxy.net", "https://ghproxy.net/{url}"},
            {"mirror.ghproxy.com", "https://mirror.ghproxy.com/{url}"},
            {"hub.gitmirror.com", "https://hub.gitmirror.com/{url}"},
            {"777.z321.cc.cd", "https://777.z321.cc.cd/{url}"},
            {"cdn.akaere.online", "https://cdn.akaere.online/{url}"},
            {"cdn.gh-proxy.com", "https://cdn.gh-proxy.com/{url}"},
            {"cfgh.ikgy.top", "https://cfgh.ikgy.top/{url}"},
            {"down.mxw.qzz.io", "https://down.mxw.qzz.io/{url}"},
            {"down.mxw.xx.kg", "https://down.mxw.xx.kg/{url}"},
            {"eogh.719831.xyz", "https://eogh.719831.xyz/{url}"},
            {"g.z321.cc.cd", "https://g.z321.cc.cd/{url}"},
            {"gap.andyjin.website", "https://gap.andyjin.website/{url}"},
            {"gg.z321.cc.cd", "https://gg.z321.cc.cd/{url}"},
            {"gh.07150721.xyz", "https://gh.07150721.xyz/{url}"},
            {"gh.927223.xyz", "https://gh.927223.xyz/{url}"},
            {"gh.acmsz.top", "https://gh.acmsz.top/{url}"},
            {"gh.beimengvv.xyz", "https://gh.beimengvv.xyz/{url}"},
            {"gh.bugdey.us.kg", "https://gh.bugdey.us.kg/{url}"},
            {"gh.catmak.name", "https://gh.catmak.name/{url}"},
            {"gh.dpik.top", "https://gh.dpik.top/{url}"},
            {"gh.felicity.ac.cn", "https://gh.felicity.ac.cn/{url}"},
            {"gh.inkchills.cn", "https://gh.inkchills.cn/{url}"},
            {"gh.jjj.gv.uy", "https://gh.jjj.gv.uy/{url}"},
            {"gh.meali.top", "https://gh.meali.top/{url}"},
            {"gh.my-website.ccwu.cc", "https://gh.my-website.ccwu.cc/{url}"},
            {"gh.qfmc0721.cc.cd", "https://gh.qfmc0721.cc.cd/{url}"},
            {"gh.ruan.dpdns.org", "https://gh.ruan.dpdns.org/{url}"},
            {"gh.zhai.edu.pl", "https://gh.zhai.edu.pl/{url}"},
            {"ghfile.geekertao.top", "https://ghfile.geekertao.top/{url}"},
            {"ghgo.de5.net", "https://ghgo.de5.net/{url}"},
            {"ghm.078465.xyz", "https://ghm.078465.xyz/{url}"},
            {"ghproxy.felicity.land", "https://ghproxy.felicity.land/{url}"},
            {"ghproxy.icu", "https://ghproxy.icu/{url}"},
            {"git.yylx.win", "https://git.yylx.win/{url}"},
            {"github-cf.947563.xyz", "https://github-cf.947563.xyz/{url}"},
            {"github-proxy.memory-echoes.cn", "https://github-proxy.memory-echoes.cn/{url}"},
            {"github.dpik.top", "https://github.dpik.top/{url}"},
            {"github.gohj99.site", "https://github.gohj99.site/{url}"},
            {"github.ikgy.top", "https://github.ikgy.top/{url}"},
            {"github.mxw.qzz.io", "https://github.mxw.qzz.io/{url}"},
            {"github.nswrz.cn", "https://github.nswrz.cn/{url}"},
            {"github.starrlzy.cn", "https://github.starrlzy.cn/{url}"},
            {"github.tbap.top", "https://github.tbap.top/{url}"},
            {"githubdog.com", "https://githubdog.com/{url}"},
            {"githubproxy.gohj99.site", "https://githubproxy.gohj99.site/{url}"},
            {"gitproxy.127731.xyz", "https://gitproxy.127731.xyz/{url}"},
            {"j.1lin.dpdns.org", "https://j.1lin.dpdns.org/{url}"},
            {"j.1win.ggff.net", "https://j.1win.ggff.net/{url}"},
            {"jiashu.1win.eu.org", "https://jiashu.1win.eu.org/{url}"},
            {"js.jiangss.shop", "https://js.jiangss.shop/{url}"},
            {"tvv.tw", "https://tvv.tw/{url}"},
            {"xsadwsd.kdns.fr", "https://xsadwsd.kdns.fr/{url}"},
            {"fastgit.cc", "https://fastgit.cc/{url}"},
            {"free.cn.eu.org", "https://free.cn.eu.org/{url}"},
            {"g.blfrp.cn", "https://g.blfrp.cn/{url}"},
            {"gh.chjina.com", "https://gh.chjina.com/{url}"},
            {"gh.ddlc.top", "https://gh.ddlc.top/{url}"},
            {"gh.idayer.com", "https://gh.idayer.com/{url}"},
            {"gh.jasonzeng.dev", "https://gh.jasonzeng.dev/{url}"},
            {"gh.monlor.com", "https://gh.monlor.com/{url}"},
            {"gh.noki.icu", "https://gh.noki.icu/{url}"},
            {"gh.sixyin.com", "https://gh.sixyin.com/{url}"},
            {"gh.tryxd.cn", "https://gh.tryxd.cn/{url}"},
            {"ghp.arslantu.xyz", "https://ghp.arslantu.xyz/{url}"},
            {"ghp.keleyaa.com", "https://ghp.keleyaa.com/{url}"},
            {"ghpr.cc", "https://ghpr.cc/{url}"},
            {"ghproxy.1888866.xyz", "https://ghproxy.1888866.xyz/{url}"},
            {"ghproxy.cxkpro.top", "https://ghproxy.cxkpro.top/{url}"},
            {"ghproxy.monkeyray.net", "https://ghproxy.monkeyray.net/{url}"},
            {"ghpxy.hwinzniej.top", "https://ghpxy.hwinzniej.top/{url}"},
            {"git.669966.xyz", "https://git.669966.xyz/{url}"},
            {"github.chenc.dev", "https://github.chenc.dev/{url}"},
            {"github.ednovas.xyz", "https://github.ednovas.xyz/{url}"},
            {"github.geekery.cn", "https://github.geekery.cn/{url}"},
            {"github.tmby.shop", "https://github.tmby.shop/{url}"},
            {"github.xxlab.tech", "https://github.xxlab.tech/{url}"},
            {"gitproxy.click", "https://gitproxy.click/{url}"},
            {"gitproxy.mrhjx.cn", "https://gitproxy.mrhjx.cn/{url}"},
            {"gp.zkitefly.eu.org", "https://gp.zkitefly.eu.org/{url}"},
            {"proxy.yaoyaoling.net", "https://proxy.yaoyaoling.net/{url}"},
            {"slink.ltd", "https://slink.ltd/{url}"},
    };


    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** 版本查询 + 下载：两者先后发生，共用一个线程即可。 */
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    /**
     * 测速专用：内置源已扩到 80+ 项，串行探测要跑几分钟，用户等不了。
     * 固定 3 线程是老设备（Cortex-A7 / 66MB 堆）能扛的并发 TLS 连接上限：
     * 再多会同时握 4+ 个连接，内存和 CPU 都吃不消；单线程又太慢。
     * 与查询/下载线程池分开，测速期间点「下载」仍然立即开始。
     */
    private static final ExecutorService PROBE_POOL = Executors.newFixedThreadPool(3);
    private static volatile HttpURLConnection active;       // 查询/下载连接，cancel() 用
    /** 并发测速时要能一次断掉全部连接，单个 volatile 只记得住最后一个。 */
    private static final java.util.Set<HttpURLConnection> probeActive =
            java.util.Collections.newSetFromMap(
                    new java.util.concurrent.ConcurrentHashMap<HttpURLConnection, Boolean>());
    private static volatile boolean cancelled;
    /** 测速代际：probeAll()/cancelProbe() 递增（均在主线程），回调只认当前代。 */
    private static volatile int probeSeq;

    private UpdateManager() {}

    // ==================== 数据模型 ====================

    public static final class Asset {
        public String name;
        public String url;
        public long size;
    }

    public static final class Release {
        public String tag = "";
        public String name = "";
        public String body = "";
        public String htmlUrl = "";
        /** release 内置的测速文件下载地址；空串表示旧版本 release 没有这个文件。 */
        public String speedTestUrl = "";
        public final List<Asset> assets = new ArrayList<Asset>();
    }

    public static final class Mirror {
        public final String name;
        public final String template;
        public final boolean custom;
        public int latency = NOT_TESTED;

        Mirror(String name, String template, boolean custom) {
            this.name = name;
            this.template = template;
            this.custom = custom;
        }
    }

    public interface Callback {
        /** err 为 null 表示成功。 */
        void onRelease(Release r, String err);
        /** latencyMs 为 {@link #FAILED} 表示不可用。 */
        void onProbeDone(int index, int latencyMs);
        /** 全部测完（或被取消提前结束），用于恢复按钮与自动选最快。 */
        void onProbeFinished();
        void onProgress(long done, long total);
        /** err 为 null 且 f 非 null 表示下载完成。 */
        void onDownloaded(File f, String err);
    }

    // ==================== 镜像配置 ====================

    public static List<Mirror> loadMirrors(Context ctx) {
        List<Mirror> list = new ArrayList<Mirror>();
        java.util.Set<String> hidden = hiddenBuiltins(ctx);
        for (String[] b : BUILTIN) {
            // 内置源也能被「移除」：用户不想要的、常年不可用的源不该一直占着列表
            if (!hidden.contains(b[1])) list.add(new Mirror(b[0], b[1], false));
        }
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = sp.getString(KEY_CUSTOM, "");
        if (raw.length() > 0) {
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    JSONArray p = arr.getJSONArray(i);
                    list.add(new Mirror(p.getString(0), p.getString(1), true));
                }
            } catch (Exception e) {
                LogUtil.w(TAG, "loadMirrors: bad custom list: " + e.getMessage());
            }
        }
        return list;
    }

    /** 返回规范化后的模板（自动补 https://），名称从 host 提取，窄屏上少输一个字段。 */
    public static String addCustomMirror(Context ctx, String input) {
        String t = normalize(input);
        if (t == null) return null;
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = sp.getString(KEY_CUSTOM, "");
        try {
            JSONArray arr = raw.length() > 0 ? new JSONArray(raw) : new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                if (t.equals(arr.getJSONArray(i).getString(1))) return t; // 已存在，不重复添加
            }
            JSONArray pair = new JSONArray();
            pair.put(hostOf(t));
            pair.put(t);
            arr.put(pair);
            sp.edit().putString(KEY_CUSTOM, arr.toString()).apply();
            LogUtil.i(TAG, "addCustomMirror: " + t);
            return t;
        } catch (Exception e) {
            LogUtil.w(TAG, "addCustomMirror failed: " + e.getMessage());
            return null;
        }
    }

    public static void removeCustomMirror(Context ctx, String template) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = sp.getString(KEY_CUSTOM, "");
        try {
            JSONArray arr = raw.length() > 0 ? new JSONArray(raw) : new JSONArray();
            JSONArray out = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONArray p = arr.getJSONArray(i);
                if (!template.equals(p.getString(1))) out.put(p);
            }
            sp.edit().putString(KEY_CUSTOM, out.toString()).apply();
            if (template.equals(selectedTemplate(ctx))) {
                sp.edit().putString(KEY_SELECTED, "").apply(); // 选中项被删，回退直连
            }
        } catch (Exception e) {
            LogUtil.w(TAG, "removeCustomMirror failed: " + e.getMessage());
        }
    }

    /** 用户移除过的内置源模板集合。解析失败返回空集合（安全方向：不隐藏任何源）。 */
    private static java.util.Set<String> hiddenBuiltins(Context ctx) {
        java.util.Set<String> out = new java.util.HashSet<String>();
        String raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_HIDDEN, "");
        if (raw.length() == 0) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) out.add(arr.optString(i, ""));
        } catch (Exception e) {
            LogUtil.w(TAG, "hiddenBuiltins: bad list: " + e.getMessage());
        }
        return out;
    }

    /** 移除一个内置源；直连不允许移除——去掉它就没有兜底通道了。 */
    public static boolean hideBuiltin(Context ctx, String template) {
        if (template == null || template.length() == 0) return false;
        java.util.Set<String> set = hiddenBuiltins(ctx);
        set.add(template);
        JSONArray arr = new JSONArray();
        for (String s : set) arr.put(s);
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_HIDDEN, arr.toString()).apply();
        if (template.equals(selectedTemplate(ctx))) setSelectedTemplate(ctx, "");
        return true;
    }

    /** 「恢复默认」：把所有被移除的内置源放回来。 */
    public static void restoreBuiltins(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_HIDDEN).apply();
    }

    public static int hiddenBuiltinCount(Context ctx) {
        return hiddenBuiltins(ctx).size();
    }

    /**
     * 改一个已有源的地址。内置源不直接改（BUILTIN 是常量），由界面把编辑结果
     * 另存为自定义源并隐藏原项；这里处理自定义源的原地替换。
     */
    public static String updateCustomMirror(Context ctx, String oldTemplate, String input) {
        String t = normalize(input);
        if (t == null) return null;
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            JSONArray arr = new JSONArray(sp.getString(KEY_CUSTOM, ""));
            JSONArray out = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONArray p = arr.getJSONArray(i);
                if (!oldTemplate.equals(p.getString(1))) {
                    out.put(p);
                    continue;
                }
                JSONArray np = new JSONArray();
                np.put(hostOf(t));
                np.put(t);
                out.put(np);
            }
            sp.edit().putString(KEY_CUSTOM, out.toString()).apply();
            if (oldTemplate.equals(selectedTemplate(ctx))) setSelectedTemplate(ctx, t);
            LogUtil.i(TAG, "updateCustomMirror: %s -> %s", oldTemplate, t);
            return t;
        } catch (Exception e) {
            LogUtil.w(TAG, "updateCustomMirror failed: " + e.getMessage());
            return null;
        }
    }

    /** 地址规范化：统一补 https://，空串返回 null。添加与编辑共用，避免两处规则漂移。 */
    private static String normalize(String input) {
        String t = input == null ? "" : input.trim();
        if (t.length() == 0) return null;
        if (!t.startsWith("http://") && !t.startsWith("https://")) t = "https://" + t;
        return t;
    }

    private static String hostOf(String template) {
        String probe = template.contains("{url}")
                ? template.replace("{url}", "https://github.com/x/y") : template;
        try {
            String h = new URL(probe).getHost();
            return h == null || h.length() == 0 ? "自定义" : h;
        } catch (Exception e) {
            return "自定义镜像";
        }
    }

    public static String selectedTemplate(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_SELECTED, "");
    }

    public static void setSelectedTemplate(Context ctx, String template) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_SELECTED, template == null ? "" : template).apply();
    }

    /** 模板含 {url} 就替换，否则当作前缀。空模板 = 直连。 */
    public static String applyMirror(Mirror m, String url) {
        if (m == null || m.template == null || m.template.length() == 0) return url;
        String t = m.template.trim();
        if (t.contains("{url}")) return t.replace("{url}", url);
        return t + url;
    }

    // ==================== 版本查询 ====================

    public static void fetchLatest(final Callback cb) {
        POOL.execute(new Runnable() {
            public void run() {
                // 页面已销毁时排队中的任务直接退出，不再发请求（onDestroy 只 cancel、不 reset）
                if (cancelled) {
                    MAIN.post(new Runnable() {
                        public void run() {
                            if (cb != null) cb.onRelease(null, "已取消");
                        }
                    });
                    return;
                }
                Release r = null;
                String err = null;
                HttpURLConnection c = null;
                try {
                    c = open(API_LATEST, false);
                    c.setRequestProperty("Accept", "application/vnd.github+json");
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(15000);
                    int code = c.getResponseCode();
                    if (code != 200) {
                        err = apiError(c, code);
                    } else {
                        String body = readAll(c.getInputStream(), 256 * 1024);
                        r = parseRelease(body);
                    }
                } catch (Throwable t) {
                    err = cancelled ? "已取消" : "请求失败：" + brief(t);
                } finally {
                    closeQuietly(c);
                }
                final Release fr = r;
                final String fe = err;
                MAIN.post(new Runnable() {
                    public void run() {
                        if (cb != null) cb.onRelease(fr, fe);
                    }
                });
            }
        });
    }

    private static String apiError(HttpURLConnection c, int code) {
        if (code == 403 && "0".equals(c.getHeaderField("X-RateLimit-Remaining"))) {
            return "GitHub API 限流（未认证 60 次/小时），请稍后再试";
        }
        return "GitHub API 返回 " + code;
    }

    private static Release parseRelease(String body) throws Exception {
        JSONObject o = new JSONObject(body);
        Release r = new Release();
        r.tag = o.optString("tag_name", "");
        r.name = o.optString("name", "");
        r.body = o.optString("body", "");
        r.htmlUrl = o.optString("html_url", "");
        JSONArray arr = o.optJSONArray("assets");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject a = arr.getJSONObject(i);
                String name = a.optString("name", "");
                String url = a.optString("browser_download_url", "");
                if (name.length() == 0 || url.length() == 0) continue;
                // 测速文件不是安装包：单独记住地址，不进「切换包」列表，
                // 否则 pickAsset 兜底时可能把它当 APK 下载下来。
                if (SPEEDTEST_ASSET.equals(name)) {
                    r.speedTestUrl = url;
                    continue;
                }
                Asset as = new Asset();
                as.name = name;
                as.url = url;
                as.size = a.optLong("size", 0);
                r.assets.add(as);
            }
        }
        return r;
    }

    /** 按 ABI 挑包，挑不到退 universal，再挑不到用第一个。 */
    public static Asset pickAsset(Release r) {
        if (r == null || r.assets.isEmpty()) return null;
        String want = "app-" + abiName() + "-release.apk";
        for (Asset a : r.assets) {
            if (want.equals(a.name)) return a;
        }
        for (Asset a : r.assets) {
            if ("app-universal-release.apk".equals(a.name)) return a;
        }
        return r.assets.get(0);
    }

    private static String abiName() {
        // Build.CPU_ABI 在 API 21+ 废弃但 API 18 上仍可用；SUPPORTED_ABIS 是 API 21 才有。
        String a = Build.CPU_ABI;
        if ("armeabi-v7a".equals(a) || "armeabi".equals(a)) return "armeabi-v7a";
        if ("arm64-v8a".equals(a)) return "arm64-v8a";
        if ("x86_64".equals(a)) return "x86_64";
        if ("x86".equals(a)) return "x86";
        return "universal";
    }

    /** 逐段比较数字，忽略 v 前缀与非数字字符。/releases/latest 不返回 prerelease，无需处理 beta 语义。 */
    public static int compareVersion(String a, String b) {
        int[] x = nums(a), y = nums(b);
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int p = i < x.length ? x[i] : 0;
            int q = i < y.length ? y[i] : 0;
            if (p > q) return 1;
            if (p < q) return -1;
        }
        return 0;
    }

    private static int[] nums(String s) {
        if (s == null) return new int[0];
        List<Integer> l = new ArrayList<Integer>();
        Matcher m = Pattern.compile("\\d+").matcher(s);
        while (m.find()) {
            try {
                l.add(Integer.valueOf(Integer.parseInt(m.group())));
            } catch (NumberFormatException e) {
                // 超长数字段跳过，不影响主版本比较
            }
        }
        int[] out = new int[l.size()];
        for (int i = 0; i < out.length; i++) out[i] = l.get(i).intValue();
        return out;
    }

    // ==================== 镜像测速 ====================

    /**
     * 并发逐项测，回调逐项刷新 UI。
     *
     * <p>内置源扩到 80+ 后串行探测要跑几分钟，改成线程池并发 3 路。
     * 每次发起递增 {@link #probeSeq}：结果与回调只认当前代际，
     * cancelProbe() 或新一次 probeAll() 都让旧代作废。
     *
     * @param testUrl release 内置测速文件的地址；为空时退回旧的 APK Range 探测
     *                （兼容没有测速文件的历史 release）。
     */
    public static void probeAll(final List<Mirror> mirrors, final String assetUrl,
                                final String testUrl, final Callback cb) {
        final int seq = ++probeSeq;
        if (mirrors.isEmpty()) {
            MAIN.post(new Runnable() {
                public void run() {
                    if (seq == probeSeq && cb != null) cb.onProbeFinished();
                }
            });
            return;
        }
        final java.util.concurrent.atomic.AtomicInteger left =
                new java.util.concurrent.atomic.AtomicInteger(mirrors.size());
        for (int i = 0; i < mirrors.size(); i++) {
            final int idx = i;
            final Mirror m = mirrors.get(i);
            PROBE_POOL.execute(new Runnable() {
                public void run() {
                    int ms = FAILED;
                    if (seq == probeSeq) ms = probe(m, assetUrl, testUrl);
                    if (seq == probeSeq) m.latency = ms;
                    final int result = ms;
                    MAIN.post(new Runnable() {
                        public void run() {
                            if (seq == probeSeq && cb != null) cb.onProbeDone(idx, result);
                        }
                    });
                    if (left.decrementAndGet() == 0) {
                        MAIN.post(new Runnable() {
                            public void run() {
                                if (seq == probeSeq && cb != null) cb.onProbeFinished();
                            }
                        });
                    }
                }
            });
        }
    }

    /** 只测一个源（长按菜单里的「测速此源」），不触发全量测速的自动选最快。 */
    public static void probeOne(final Mirror m, final int index, final String assetUrl,
                                final String testUrl, final Callback cb) {
        final int seq = ++probeSeq;
        PROBE_POOL.execute(new Runnable() {
            public void run() {
                final int ms = probe(m, assetUrl, testUrl);
                m.latency = ms;
                MAIN.post(new Runnable() {
                    public void run() {
                        if (seq != probeSeq || cb == null) return;
                        cb.onProbeDone(index, ms);
                        cb.onProbeFinished();
                    }
                });
            }
        });
    }

    /**
     * 探测一个镜像：优先下载 release 内置的测速文件并<b>校验内容</b>。
     *
     * <p>只发 Range 看首字节不够——很多失效镜像会返回一个 HTTP 200 的
     * HTML 错误页 / 跳转登录页，TTFB 很好看，真下载 APK 时却是坏文件。
     * 校验魔数后，这类「假可用」会直接被判 FAILED。
     */
    private static int probe(Mirror m, String assetUrl, String testUrl) {
        boolean verify = testUrl != null && testUrl.length() > 0;
        String url = applyMirror(m, verify ? testUrl : assetUrl);
        HttpURLConnection c = null;
        try {
            c = open(url, true);
            c.setRequestMethod("GET");
            if (!verify) c.setRequestProperty("Range", "bytes=0-1023");
            c.setConnectTimeout(3000);
            c.setReadTimeout(5000);
            long t0 = System.nanoTime();
            int code = c.getResponseCode();
            if (code != 200 && code != 206) return FAILED;
            InputStream in = c.getInputStream();
            String body = readAll(in, 4096);
            try { in.close(); } catch (Throwable ignored) {}
            if (verify) {
                if (!body.startsWith(SPEEDTEST_MAGIC)) return FAILED;
            } else if (body.length() == 0) {
                return FAILED;
            }
            long ms = (System.nanoTime() - t0) / 1000000L;
            return (int) Math.min(ms, 9999L);
        } catch (Throwable t) {
            return FAILED;
        } finally {
            closeQuietly(c);
        }
    }

    // ==================== 下载 ====================

    /**
     * 流式下载，8KB 缓冲，绝不把 APK 读进内存（66MB 可用内存）。
     * 先写 .tmp 再 rename；按 API 给的 size 校验，挡住镜像返回错误页或截断。
     */
    public static void download(final String url, final File target,
                                final long expectSize, final Callback cb) {
        POOL.execute(new Runnable() {
            public void run() {
                if (cancelled) {
                    MAIN.post(new Runnable() {
                        public void run() {
                            if (cb != null) cb.onDownloaded(null, "已取消");
                        }
                    });
                    return;
                }
                File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
                HttpURLConnection c = null;
                File done = null;
                String err = null;
                try {
                    if (!target.getParentFile().isDirectory()
                            && !target.getParentFile().mkdirs()) {
                        throw new Exception("无法创建下载目录");
                    }
                    if (tmp.exists() && !tmp.delete()) throw new Exception("无法清理临时文件");
                    c = open(url, false);
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(20000);
                    int code = c.getResponseCode();
                    if (code != 200 && code != 206) throw new Exception("HTTP " + code);
                    long total = c.getContentLength();
                    if (total <= 0) total = expectSize;

                    InputStream in = c.getInputStream();
                    FileOutputStream out = new FileOutputStream(tmp);
                    byte[] buf = new byte[8192];
                    long written = 0, lastPost = 0;
                    int n;
                    try {
                        while ((n = in.read(buf)) > 0) {
                            if (cancelled) throw new Exception("已取消");
                            out.write(buf, 0, n);
                            written += n;
                            long now = System.currentTimeMillis();
                            // 节流：老设备主线程不能被每 8KB 一次 post 淹没
                            if (now - lastPost > 120 || written == total) {
                                lastPost = now;
                                postProgress(cb, written, total);
                            }
                        }
                    } finally {
                        try { out.flush(); } catch (Throwable t) {}
                        try { out.close(); } catch (Throwable t) {}
                        try { in.close(); } catch (Throwable t) {}
                    }
                    if (cancelled) throw new Exception("已取消");
                    long expected = expectSize > 0 ? expectSize : total;
                    if (expected > 0 && tmp.length() != expected) {
                        throw new Exception("文件大小不符，可能被镜像截断或返回错误页");
                    }
                    if (target.exists() && !target.delete()) throw new Exception("无法覆盖旧包");
                    if (!tmp.renameTo(target)) throw new Exception("写入失败");
                    done = target;
                    postProgress(cb, written, total > 0 ? total : written);
                } catch (Throwable t) {
                    err = cancelled ? "已取消" : brief(t);
                    if (tmp.exists()) tmp.delete();
                } finally {
                    closeQuietly(c);
                }
                final File fd = done;
                final String fe = err;
                MAIN.post(new Runnable() {
                    public void run() {
                        if (cb != null) cb.onDownloaded(fd, fe);
                    }
                });
            }
        });
    }

    private static void postProgress(final Callback cb, final long done, final long total) {
        MAIN.post(new Runnable() {
            public void run() {
                if (cb != null) cb.onProgress(done, total);
            }
        });
    }

    /** 下载前清掉旧 APK：可用存储只有 ~80MB，不能让历史包累积。 */
    public static int clearOldApks(File dir) {
        int n = 0;
        File[] fs = dir.listFiles();
        if (fs == null) return 0;
        for (File f : fs) {
            if (f.isFile() && f.getName().endsWith(".apk")) {
                if (f.delete()) n++;
            }
        }
        return n;
    }

    // ==================== 取消 / 通用 ====================

    /** 发起新任务前调用；onDestroy 不要调用，否则排队任务会重新跑起来。 */
    public static void resetCancel() {
        cancelled = false;
    }

    public static void cancel() {
        cancelled = true;
        HttpURLConnection c = active;
        if (c != null) {
            try { c.disconnect(); } catch (Throwable t) {}
        }
    }

    /**
     * 让在跑的测速尽快退出：点「下载」/切换安装包/页面销毁时调用。
     * 递增 {@link #probeSeq} 让当前代失效——排队/在跑的旧任务及其回调全部作废，
     * 不受 resetCancel()（只清 cancelled）影响，也能与新一次 probeAll() 区分代际。
     * 同时断开测速活动连接。
     */
    public static void cancelProbe() {
        ++probeSeq;
        // 并发测速可能同时挂着 3 个连接，逐个断开才能让在跑的任务立刻抛异常退出
        for (HttpURLConnection c : new ArrayList<HttpURLConnection>(probeActive)) {
            try { c.disconnect(); } catch (Throwable t) {}
        }
    }

    /** isProbe=true 登记到测速槽位：cancel()/cancelProbe() 各断各的连接，不串扰。 */
    private static HttpURLConnection open(String url, boolean isProbe) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        if (c instanceof HttpsURLConnection) {
            // API 18/19 平台 TLS 栈缺 TLS1.2 与 GCM 套件，复用项目已有的 Conscrypt 兼容层
            TlsCompat.apply((HttpsURLConnection) c);
        }
        c.setRequestProperty("User-Agent", UA); // GitHub API 不带 UA 会 403
        c.setInstanceFollowRedirects(true);
        if (isProbe) probeActive.add(c); else active = c;
        return c;
    }

    private static void closeQuietly(HttpURLConnection c) {
        if (c != null) {
            try { c.disconnect(); } catch (Throwable t) {}
        }
        if (c == active) active = null;
        probeActive.remove(c);
    }

    private static String readAll(InputStream in, int limit) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[2048];
        int n;
        while ((n = r.read(buf)) > 0) {
            sb.append(buf, 0, n);
            if (sb.length() > limit) throw new Exception("响应过大");
        }
        return sb.toString();
    }

    private static String brief(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.length() == 0) m = t.getClass().getSimpleName();
        if (m.length() > 90) m = m.substring(0, 90);
        return m;
    }
}
