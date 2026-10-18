package xyz.zip8919.app.aichat;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Locale;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * DeepSeek Search / Fetch tooling exposed to the chat model through the
 * standard OpenAI tool_calls interface.
 *
 * Search and Fetch are billed per successful request by DeepSeek, so the
 * caller controls the master switch in Settings.
 */
public class WebSearchTool {
    private static final String TAG = "WebSearchTool";

    public static final String TOOL_SEARCH = "web_search";
    public static final String TOOL_FETCH = "web_fetch";

    private static final String SEARCH_PATH = "/v1/tools/search";
    private static final String FETCH_PATH = "/v1/tools/fetch";
    private static final int DEFAULT_COUNT = 5;
    private static final int MAX_COUNT = 10;
    private static final int MAX_RESPONSE_CHARS = 1024 * 1024;
    private static final int MAX_SUMMARY_CHARS = 1200;
    private static final int MAX_FETCH_CONTENT_CHARS = 20000;
    private static final int SEARCH_TIMEOUT_MS = 25000;
    private static final int FETCH_TIMEOUT_MS = 35000;

    /** Lets the UI interrupt a search/fetch request through the same connection slot. */
    public interface RequestGuard {
        boolean shouldAbort();
        void onConnection(HttpURLConnection conn);
    }

    private static class PostResult {
        int code;
        String body;
        String error;
    }

    public static JSONArray buildToolDefinitions() throws Exception {
        JSONArray tools = new JSONArray();

        JSONObject search = new JSONObject();
        search.put("type", "function");
        JSONObject searchFn = new JSONObject();
        searchFn.put("name", TOOL_SEARCH);
        searchFn.put("description",
                "联网搜索互联网。用于查询实时信息、新闻、价格、天气、版本、人物事件等你无法确定或可能过时的内容。");
        JSONObject searchParams = new JSONObject();
        searchParams.put("type", "object");
        JSONObject searchProps = new JSONObject();
        JSONObject query = new JSONObject();
        query.put("type", "string");
        query.put("description", "搜索关键词，1-128 个字符");
        searchProps.put("query", query);
        JSONObject count = new JSONObject();
        count.put("type", "integer");
        count.put("description", "返回结果条数，1-10，默认 5");
        count.put("minimum", 1);
        count.put("maximum", MAX_COUNT);
        searchProps.put("count", count);
        searchParams.put("properties", searchProps);
        searchParams.put("required", new JSONArray().put("query"));
        searchFn.put("parameters", searchParams);
        search.put("function", searchFn);
        tools.put(search);

        JSONObject fetch = new JSONObject();
        fetch.put("type", "function");
        JSONObject fetchFn = new JSONObject();
        fetchFn.put("name", TOOL_FETCH);
        fetchFn.put("description",
                "抓取指定网页的正文并以 Markdown 返回。适合在搜索结果中继续阅读某篇网页的详细内容。");
        JSONObject fetchParams = new JSONObject();
        fetchParams.put("type", "object");
        JSONObject fetchProps = new JSONObject();
        JSONObject url = new JSONObject();
        url.put("type", "string");
        url.put("description", "要抓取的 http/https 网页地址");
        fetchProps.put("url", url);
        fetchParams.put("properties", fetchProps);
        fetchParams.put("required", new JSONArray().put("url"));
        fetchFn.put("parameters", fetchParams);
        fetch.put("function", fetchFn);
        tools.put(fetch);

        return tools;
    }

    public static String buildSystemPrompt() {
        return "你可以使用联网工具：\n"
                + "- web_search(query, count)：搜索互联网，返回标题、链接和摘要。\n"
                + "- web_fetch(url)：抓取指定网页的正文。\n"
                + "遇到实时信息、新闻、价格、天气、版本、人物事件等你不确定或可能过时的内容时，"
                + "先调用工具检索，再基于检索结果回答。回答中请引用来源链接。"
                + "不要编造工具结果；搜索失败时请说明情况。简单常识或纯推理问题可以直接回答。";
    }

    public static String appendToSystemPrompt(String base) {
        String toolPrompt = buildSystemPrompt();
        if (base == null || base.trim().length() == 0) return toolPrompt;
        return base + "\n\n" + toolPrompt;
    }

    /**
     * Search always goes through DeepSeek's Search API. Prefer the current
     * provider when it is DeepSeek with a key, otherwise use the built-in
     * DeepSeek provider.
     */
    public static ProviderInfo resolveProvider(ConfigManager configManager, ProviderInfo current) {
        if (isDeepSeek(current) && hasApiKey(current)) return current;
        if (configManager != null) {
            List<ProviderInfo> providers = configManager.getProviders();
            for (int i = 0; i < providers.size(); i++) {
                ProviderInfo p = providers.get(i);
                if (isDeepSeek(p) && hasApiKey(p)) return p;
            }
        }
        return null;
    }

    public static boolean hasApiKey(ProviderInfo provider) {
        return provider != null && provider.apiKey != null && provider.apiKey.trim().length() > 0;
    }

    private static boolean isDeepSeek(ProviderInfo provider) {
        if (provider == null || provider.apiUrl == null) return false;
        return provider.apiUrl.toLowerCase(Locale.US).contains("deepseek.com");
    }

    public static String executeTool(ProviderInfo provider, String toolName, JSONObject args,
            String originalQuestion, RequestGuard guard) {
        try {
            if (!hasApiKey(provider)) {
                return error("未配置 DeepSeek API Key，无法使用联网搜索。");
            }
            if (TOOL_SEARCH.equals(toolName)) {
                String query = args == null ? "" : args.optString("query", "").trim();
                if (query.length() == 0) return error("搜索词不能为空");
                if (query.length() > 128) query = query.substring(0, 128);
                int count = args == null ? DEFAULT_COUNT : args.optInt("count", DEFAULT_COUNT);
                if (count < 1) count = 1;
                if (count > MAX_COUNT) count = MAX_COUNT;
                String ori = originalQuestion == null ? "" : originalQuestion.trim();
                if (ori.length() > 128) ori = ori.substring(0, 128);
                return search(provider, query, count, ori, guard);
            }
            if (TOOL_FETCH.equals(toolName)) {
                String url = args == null ? "" : args.optString("url", "").trim();
                if (!isValidHttpUrl(url)) return error("抓取地址必须是合法的 http/https URL");
                return fetch(provider, url, guard);
            }
            return error("未知工具: " + toolName);
        } catch (Exception e) {
            LogUtil.e(TAG, "executeTool failed: " + e.getMessage(), e);
            return error("联网工具执行失败: " + messageOf(e));
        }
    }

    private static String search(ProviderInfo provider, String query, int count,
            String originalQuestion, RequestGuard guard) throws Exception {
        JSONObject body = new JSONObject();
        body.put("query", query);
        body.put("count", count);
        body.put("need_content", false);
        if (originalQuestion.length() > 0) body.put("ori_question", originalQuestion);

        LogUtil.i(TAG, "search -> query=%s count=%d", LogUtil.preview(query, 120), count);
        PostResult post = postJson(provider, SEARCH_PATH, body, SEARCH_TIMEOUT_MS, guard);
        if (post.code != 200) {
            LogUtil.w(TAG, "search failed: code=%d error=%s", post.code, post.error);
            return error("联网搜索失败: " + post.error);
        }

        JSONObject json = new JSONObject(post.body);
        JSONArray results = json.optJSONArray("results");
        JSONArray out = new JSONArray();
        if (results != null) {
            for (int i = 0; i < results.length(); i++) {
                JSONObject item = results.optJSONObject(i);
                if (item == null) continue;
                JSONObject row = new JSONObject();
                row.put("title", item.optString("title", ""));
                row.put("url", item.optString("url", ""));
                String summary = item.optString("summary", "");
                if (summary.length() == 0) summary = item.optString("snippet", "");
                row.put("summary", truncate(summary, MAX_SUMMARY_CHARS));
                if (item.has("date") && !item.isNull("date")) {
                    row.put("date", item.optString("date", ""));
                }
                if (item.has("site_name") && !item.isNull("site_name")) {
                    row.put("site", item.optString("site_name", ""));
                }
                out.put(row);
            }
        }

        JSONObject result = new JSONObject();
        result.put("query", query);
        result.put("results", out);
        LogUtil.i(TAG, "search <- %d results", out.length());
        return result.toString();
    }

    private static String fetch(ProviderInfo provider, String url, RequestGuard guard) throws Exception {
        JSONObject body = new JSONObject();
        body.put("url", url);

        LogUtil.i(TAG, "fetch -> url=%s", LogUtil.preview(url, 200));
        PostResult post = postJson(provider, FETCH_PATH, body, FETCH_TIMEOUT_MS, guard);
        if (post.code != 200) {
            LogUtil.w(TAG, "fetch failed: code=%d error=%s", post.code, post.error);
            return error("网页抓取失败: " + post.error);
        }

        JSONObject json = new JSONObject(post.body);
        String content = json.optString("content", "");
        boolean truncated = false;
        if (content.length() > MAX_FETCH_CONTENT_CHARS) {
            content = content.substring(0, MAX_FETCH_CONTENT_CHARS) + "\n...(内容已截断)";
            truncated = true;
        }

        JSONObject result = new JSONObject();
        result.put("url", url);
        result.put("title", json.optString("title", ""));
        result.put("content", content);
        result.put("truncated", truncated);
        LogUtil.i(TAG, "fetch <- contentLen=%d truncated=%s", content.length(), truncated);
        return result.toString();
    }

    public static void extractSources(String toolResult, List<String[]> out) {
        if (toolResult == null || out == null) return;
        try {
            JSONObject json = new JSONObject(toolResult);
            JSONArray results = json.optJSONArray("results");
            if (results != null) {
                for (int i = 0; i < results.length(); i++) {
                    JSONObject item = results.optJSONObject(i);
                    if (item == null) continue;
                    String url = item.optString("url", "");
                    if (url.length() == 0) continue;
                    out.add(new String[]{item.optString("title", ""), url});
                }
                return;
            }
            String url = json.optString("url", "");
            if (url.length() > 0) {
                out.add(new String[]{json.optString("title", ""), url});
            }
        } catch (Exception e) {
            LogUtil.w(TAG, "extractSources failed: " + e.getMessage());
        }
    }

    private static PostResult postJson(ProviderInfo provider, String path, JSONObject body,
            int readTimeout, RequestGuard guard) {
        PostResult result = new PostResult();
        String urlStr = buildUrl(provider.apiUrl, path);

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
                conn.setRequestProperty("Authorization", "Bearer " + provider.apiKey);
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
                LogUtil.v(TAG, "postJson %s body: %s", path, LogUtil.preview(payload, 600));
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
                        ? ": " + truncate(response, 500) : "");
                LogUtil.w(TAG, "postJson %s <- HTTP %d", path, code);
                if (attempt == 0 && (code == 408 || code == 429 || code >= 500)) {
                    sleep(700);
                    continue;
                }
                return result;
            } catch (Exception e) {
                result.code = -1;
                result.error = messageOf(e);
                LogUtil.e(TAG, "postJson " + path + " failed: " + e.getMessage(), e);
                if (attempt == 0) {
                    sleep(500);
                    continue;
                }
                return result;
            } finally {
                if (conn != null) conn.disconnect();
                if (guard != null) guard.onConnection(null);
            }
        }
        return result;
    }

    private static String buildUrl(String apiUrl, String path) {
        String base = apiUrl == null ? "" : apiUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/v1")) {
            base = base.substring(0, base.length() - 3);
        }
        return base + path;
    }

    private static String readStream(InputStream stream, int maxChars) throws Exception {
        if (stream == null) return "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
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

    private static boolean isValidHttpUrl(String url) {
        if (url == null || url.length() == 0 || url.length() > 8192) return false;
        String lower = url.toLowerCase(Locale.US);
        return lower.startsWith("http://") || lower.startsWith("https://");
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
