package xyz.zip8919.app.aichat;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Tavily 后端（经自建网关 https://tavily.20090102.xyz 转发）。
 *
 * 与 DeepSeek Search 的差别：
 * - 鉴权分两层：网关用 URL 上的 {@code ?key=} 校验（不消耗 Tavily 配额），
 *   真正的 Tavily 密钥放在 {@code Authorization: Bearer} 头里。
 * - 搜索返回 {@code results[].content} 作为摘要（DeepSeek 是 summary/snippet），
 *   没有 date / site_name，站点名从 URL 的 host 推导。
 * - 正文提取走 {@code /extract}，返回 {@code results[].raw_content}。
 *
 * 对外仍然输出与 DeepSeek 后端一致的结构，上层（来源收集、工具卡片）无需区分。
 */
public class TavilyTool {
    private static final String TAG = "TavilyTool";

    private static final String GATEWAY_BASE = "https://tavily.20090102.xyz";
    /** 网关自身校验用的 key，不是 Tavily 密钥；校验失败网关直接 403，不消耗上游配额。 */
    private static final String GATEWAY_KEY = "zip8919";

    private static final int SEARCH_TIMEOUT_MS = 25000;
    private static final int EXTRACT_TIMEOUT_MS = 35000;
    /** 网络读取的 OOM 保护，不是业务截断。 */
    private static final int MAX_RESPONSE_CHARS = 1024 * 1024;

    private static class PostResult {
        int code;
        String body;
        String error;
    }

    /** Tavily 密钥均以 {@code tvly-} 开头；{@code sk_xxx} 之类的占位符视为未配置。 */
    public static boolean hasApiKey(String key) {
        if (key == null) return false;
        String k = key.trim();
        return k.startsWith("tvly-") && k.length() > 8;
    }

    public static String search(String query, int count, String apiKey,
            WebSearchTool.RequestGuard guard) {
        try {
            if (!hasApiKey(apiKey)) {
                return error("未配置 Tavily API Key，无法使用联网搜索。");
            }
            JSONObject body = new JSONObject();
            body.put("query", query);
            body.put("max_results", count);
            body.put("search_depth", "basic");

            LogUtil.i(TAG, "search -> query=%s count=%d", LogUtil.preview(query, 120), count);
            PostResult post = postJson("/search", body, SEARCH_TIMEOUT_MS, guard, apiKey);
            if (post.code != 200) {
                LogUtil.w(TAG, "search failed: code=%d error=%s", post.code, post.error);
                return error("联网搜索失败: " + friendly(post.code, post.error));
            }

            JSONObject json = new JSONObject(post.body);
            JSONArray results = json.optJSONArray("results");
            JSONArray out = new JSONArray();
            int rawTotal = 0;
            int keptTotal = 0;
            // 与 DeepSeek 后端共用同一套摘要预算，保证切换后端时上下文开销一致
            int budget = WebSearchTool.MAX_TOTAL_SUMMARY_CHARS;
            if (results != null) {
                for (int i = 0; i < results.length(); i++) {
                    JSONObject item = results.optJSONObject(i);
                    if (item == null) continue;
                    JSONObject row = new JSONObject();
                    String url = item.optString("url", "");
                    row.put("url", url);
                    row.put("title", item.optString("title", ""));

                    String summary = item.optString("content", "");
                    rawTotal += summary.length();
                    int allow = Math.min(WebSearchTool.MAX_SUMMARY_CHARS, budget);
                    if (allow <= 0) {
                        summary = "";
                    } else if (summary.length() > allow) {
                        summary = summary.substring(0, allow - 1) + "…";
                    }
                    budget -= summary.length();
                    keptTotal += summary.length();
                    row.put("summary", summary);

                    String site = hostOf(url);
                    if (site.length() > 0) row.put("site", site);
                    out.put(row);
                }
            }

            JSONObject result = new JSONObject();
            result.put("query", query);
            result.put("results", out);
            LogUtil.i(TAG, "search <- %d results, summary %d -> %d chars (per=%d, total=%d)",
                    out.length(), rawTotal, keptTotal,
                    WebSearchTool.MAX_SUMMARY_CHARS, WebSearchTool.MAX_TOTAL_SUMMARY_CHARS);
            return result.toString();
        } catch (Exception e) {
            LogUtil.e(TAG, "search failed: " + e.getMessage(), e);
            return error("联网搜索失败: " + messageOf(e));
        }
    }

    public static String extract(String targetUrl, String apiKey,
            WebSearchTool.RequestGuard guard) {
        try {
            if (!hasApiKey(apiKey)) {
                return error("未配置 Tavily API Key，无法使用联网搜索。");
            }
            JSONObject body = new JSONObject();
            JSONArray urls = new JSONArray();
            urls.put(targetUrl);
            body.put("urls", urls);
            body.put("extract_depth", "basic");

            LogUtil.i(TAG, "extract -> url=%s", LogUtil.preview(targetUrl, 200));
            PostResult post = postJson("/extract", body, EXTRACT_TIMEOUT_MS, guard, apiKey);
            if (post.code != 200) {
                LogUtil.w(TAG, "extract failed: code=%d error=%s", post.code, post.error);
                return error("网页抓取失败: " + friendly(post.code, post.error));
            }

            JSONObject json = new JSONObject(post.body);
            JSONArray results = json.optJSONArray("results");
            String content = "";
            String title = "";
            if (results != null && results.length() > 0) {
                JSONObject first = results.optJSONObject(0);
                if (first != null) {
                    content = first.optString("raw_content", "");
                    title = first.optString("title", "");
                }
            }
            if (content == null || content.trim().length() == 0) {
                return error("网页正文为空或抓取失败");
            }

            JSONObject result = new JSONObject();
            result.put("url", targetUrl);
            result.put("title", title);
            result.put("content", content);
            LogUtil.i(TAG, "extract <- contentLen=%d", content.length());
            return result.toString();
        } catch (Exception e) {
            LogUtil.e(TAG, "extract failed: " + e.getMessage(), e);
            return error("网页抓取失败: " + messageOf(e));
        }
    }

    private static PostResult postJson(String path, JSONObject body, int readTimeout,
            WebSearchTool.RequestGuard guard, String apiKey) {
        PostResult result = new PostResult();
        // 网关不做路径规范化，尾斜杠会被当成不同端点，这里保持无斜杠
        String urlStr = GATEWAY_BASE + path + "?key=" + GATEWAY_KEY;

        for (int attempt = 0; attempt < 2; attempt++) {
            if (guard != null && guard.shouldAbort()) {
                result.code = -1;
                result.error = "已打断";
                return result;
            }
            HttpURLConnection conn = null;
            try {
                URL url = new URL(urlStr);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
                conn.setDoOutput(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(readTimeout);
                if (conn instanceof HttpsURLConnection) {
                    TlsCompat.apply((HttpsURLConnection) conn);
                }
                if (guard != null) guard.onConnection(conn);
                if (guard != null && guard.shouldAbort()) {
                    result.code = -1;
                    result.error = "已打断";
                    return result;
                }

                String payload = body.toString();
                OutputStream os = conn.getOutputStream();
                try {
                    os.write(payload.getBytes("UTF-8"));
                } finally {
                    os.close();
                }

                int code = conn.getResponseCode();
                InputStream stream = code == 200 ? conn.getInputStream() : conn.getErrorStream();
                String response = readStream(stream, MAX_RESPONSE_CHARS);
                result.code = code;
                result.body = response;
                if (code == 200) {
                    LogUtil.d(TAG, "postJson %s <- HTTP 200, %d chars", path, response.length());
                    return result;
                }
                result.error = "HTTP " + code + (response.length() > 0
                        ? ": " + truncate(response, 300) : "");
                LogUtil.w(TAG, "postJson %s <- HTTP %d", path, code);
                // 429 配额/频率超限与 5xx 属于明确的「未成功」，可重试一次；
                // 其余状态码重试无意义
                if (attempt == 0 && (code == 429 || code >= 500)) {
                    sleep(700);
                    continue;
                }
                return result;
            } catch (Exception e) {
                result.code = -1;
                result.error = messageOf(e);
                LogUtil.e(TAG, "postJson " + path + " failed: " + e.getMessage(), e);
                return result;
            } finally {
                if (conn != null) conn.disconnect();
                if (guard != null) guard.onConnection(null);
            }
        }
        return result;
    }

    private static String readStream(InputStream stream, int maxChars) throws Exception {
        if (stream == null) return "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
                if (sb.length() > maxChars) {
                    LogUtil.w(TAG, "response exceeds %d chars, truncated", maxChars);
                    break;
                }
            }
        } finally {
            reader.close();
        }
        return sb.toString();
    }

    /** 把网关/上游状态码翻成用户能看懂的一句话。 */
    private static String friendly(int code, String raw) {
        if (code == 403) return "网关校验失败（?key= 不正确），请检查配置";
        if (code == 429) return "搜索配额或频率超限，请稍后再试";
        if (code == 401) return "Tavily 密钥无效，请到设置页更换 Tavily API Key";
        if (code == 400) return "请求被拒绝（可能是密钥无效或参数不合法）: " + raw;
        return raw;
    }

    /** 从 URL 推导展示用站点名（去掉 www.）。 */
    private static String hostOf(String url) {
        try {
            String host = new URL(url).getHost();
            if (host == null) return "";
            host = host.toLowerCase(Locale.US);
            if (host.startsWith("www.")) host = host.substring(4);
            return host;
        } catch (Exception e) {
            return "";
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        if (text.length() <= max) return text;
        return text.substring(0, max) + "...";
    }

    private static String messageOf(Exception e) {
        if (e == null) return "未知错误";
        String msg = e.getMessage();
        return msg == null || msg.length() == 0 ? e.getClass().getSimpleName() : msg;
    }

    private static String error(String message) {
        try {
            JSONObject json = new JSONObject();
            json.put("error", message);
            return json.toString();
        } catch (Exception e) {
            return "{\"error\":\"联网工具执行失败\"}";
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
