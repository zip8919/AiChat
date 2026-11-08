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
 * <p>网络全部跑在单线程 Executor 上：老设备 Cortex-A7 + 66MB 内存，并发 7 个 TLS
 * 连接没有收益反而容易 OOM。回调统一 post 回主线程。
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

    /** -1 未测，-2 不可用，>=0 为毫秒。 */
    public static final int NOT_TESTED = -1;
    public static final int FAILED = -2;

    /**
     * 内置镜像。前 5 个在本机（2026-11-08）用真实的 v1.13.2 asset URL 实测可通，
     * 后 2 个为常见备选但本机超时——镜像可用性随时间与网络环境变化，
     * **真机以 App 内测速结果为准**，这里只提供候选，不做「一定可用」的承诺。
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
    };

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    private static volatile HttpURLConnection active;
    private static volatile boolean cancelled;

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
        for (String[] b : BUILTIN) {
            list.add(new Mirror(b[0], b[1], false));
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
        String t = input == null ? "" : input.trim();
        if (t.length() == 0) return null;
        if (!t.startsWith("http://") && !t.startsWith("https://")) {
            t = "https://" + t;
        }
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
            sp.edit().putString(KEY_CUSTOM, arr.toString()).commit();
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
            JSONArray arr = new JSONArray(raw);
            JSONArray out = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONArray p = arr.getJSONArray(i);
                if (!template.equals(p.getString(1))) out.put(p);
            }
            sp.edit().putString(KEY_CUSTOM, out.toString()).commit();
            if (template.equals(selectedTemplate(ctx))) {
                sp.edit().putString(KEY_SELECTED, "").commit(); // 选中项被删，回退直连
            }
        } catch (Exception e) {
            LogUtil.w(TAG, "removeCustomMirror failed: " + e.getMessage());
        }
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
                .edit().putString(KEY_SELECTED, template == null ? "" : template).commit();
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
                    c = open(API_LATEST);
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
                Asset as = new Asset();
                as.name = a.optString("name", "");
                as.url = a.optString("browser_download_url", "");
                as.size = a.optLong("size", 0);
                if (as.name.length() > 0 && as.url.length() > 0) r.assets.add(as);
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

    /** 串行逐项测，回调逐项刷新 UI。取消后剩余项直接跳过。 */
    public static void probeAll(final List<Mirror> mirrors, final String assetUrl,
                                final Callback cb) {
        POOL.execute(new Runnable() {
            public void run() {
                for (int i = 0; i < mirrors.size(); i++) {
                    if (cancelled) break;
                    final int idx = i;
                    final int ms = probe(applyMirror(mirrors.get(i), assetUrl));
                    mirrors.get(i).latency = ms;
                    MAIN.post(new Runnable() {
                        public void run() {
                            if (cb != null) cb.onProbeDone(idx, ms);
                        }
                    });
                }
                MAIN.post(new Runnable() {
                    public void run() {
                        if (cb != null) cb.onProbeFinished();
                    }
                });
            }
        });
    }

    private static int probe(String url) {
        HttpURLConnection c = null;
        try {
            c = open(url);
            c.setRequestMethod("GET");
            c.setRequestProperty("Range", "bytes=0-1023");
            c.setConnectTimeout(4000);
            c.setReadTimeout(6000);
            long t0 = System.nanoTime();
            int code = c.getResponseCode();
            if (code != 200 && code != 206) return FAILED;
            // 握手成功不等于能传数据：实际读 1KB，卡住的代理在这里超时。
            InputStream in = c.getInputStream();
            byte[] buf = new byte[1024];
            int n = in.read(buf);
            try { in.close(); } catch (Throwable t) {}
            if (n < 0) return FAILED;
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
                    c = open(url);
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
                    if (expectSize > 0 && tmp.length() != expectSize) {
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

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        if (c instanceof HttpsURLConnection) {
            // API 18/19 平台 TLS 栈缺 TLS1.2 与 GCM 套件，复用项目已有的 Conscrypt 兼容层
            TlsCompat.apply((HttpsURLConnection) c);
        }
        c.setRequestProperty("User-Agent", UA); // GitHub API 不带 UA 会 403
        c.setInstanceFollowRedirects(true);
        active = c;
        return c;
    }

    private static void closeQuietly(HttpURLConnection c) {
        if (c != null) {
            try { c.disconnect(); } catch (Throwable t) {}
        }
        if (c == active) active = null;
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
