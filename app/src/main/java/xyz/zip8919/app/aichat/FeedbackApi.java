package xyz.zip8919.app.aichat;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

/**
 * 反馈中心的网络客户端。
 *
 * <p>只用 {@link HttpURLConnection} 与 {@code org.json}：项目不对接第三方网络库，
 * 老设备上也能直接跑。所有请求在工作线程执行，结果切回主线程，调用方不必自己开线程。
 *
 * <p>提交走 multipart 流式写出，附件边读边传，不把整个文件读进内存——词典笔内存紧张，
 * 五个 15MB 的文件一次性读入会直接 OOM。
 */
final class FeedbackApi {

    private static final String TAG = "FeedbackApi";

    /** 反馈中心线上地址。 */
    static final String BASE = "https://feedback.20090102.xyz";

    /** 与反馈中心 config.js 的 LIMITS 保持一致：单文件 15MB、最多 5 个。 */
    static final long MAX_FILE_BYTES = 15L * 1024 * 1024;
    static final int MAX_FILES = 5;

    private static final int CONNECT_TIMEOUT = 20000;
    private static final int READ_TIMEOUT = 60000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    interface Callback {
        void onSuccess(JSONObject data);

        void onError(String message);
    }

    /** 待上传的本地文件。size 未知时为 -1，交由服务端再校验。 */
    static final class UploadFile {
        final Uri uri;
        final String name;
        final long size;
        final String mime;
        /** true 表示这是配置文件打码后的副本，界面上要标明，避免用户以为原样上传。 */
        final boolean masked;

        UploadFile(Uri uri, String name, long size, String mime) {
            this(uri, name, size, mime, false);
        }

        UploadFile(Uri uri, String name, long size, String mime, boolean masked) {
            this.uri = uri;
            this.name = name;
            this.size = size;
            this.mime = mime;
            this.masked = masked;
        }
    }

    private FeedbackApi() {
    }

    static void list(int page, boolean fold, Callback callback) {
        get("/api/public/list?page=" + page + "&size=20" + (fold ? "" : "&fold=0"), callback);
    }

    static void detail(String id, Callback callback) {
        get("/api/public/item?id=" + Uri.encode(id), callback);
    }

    /** 公告等公开接口用，路径自带查询串。 */
    static void getPath(String path, Callback callback) {
        get(path, callback);
    }

    /**
     * 撤销刚提交的反馈。凭证是提交时拿到的编号与令牌，不需要登录。
     * 成功返回里带 draft，就是要填回编辑页的原始内容与附件地址。
     */
    static void undo(String id, String token, Callback callback) {
        final String path = "/api/feedback/undo?id=" + Uri.encode(id) + "&token=" + Uri.encode(token);
        new Thread(new Runnable() {
            public void run() {
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(BASE + path).openConnection();
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(CONNECT_TIMEOUT);
                    conn.setReadTimeout(READ_TIMEOUT);
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    conn.setRequestProperty("Accept", "application/json");
                    conn.setRequestProperty("User-Agent", "AiChat-Android");
                    byte[] body = "{}".getBytes("UTF-8");
                    conn.setFixedLengthStreamingMode(body.length);
                    OutputStream out = conn.getOutputStream();
                    try {
                        out.write(body);
                    } finally {
                        out.close();
                    }
                    int code = conn.getResponseCode();
                    String text = readAll(code >= 200 && code < 300
                            ? conn.getInputStream() : conn.getErrorStream());
                    JSONObject data = new JSONObject(text);
                    if (code < 200 || code >= 300 || !data.optBoolean("ok")) {
                        postError(callback, data.optString("message", Strings.get(R.string.feedbackcomposeactivity_033)));
                        return;
                    }
                    postSuccess(callback, data);
                } catch (Exception e) {
                    LogUtil.w(TAG, "undo %s failed: %s", id, e.getMessage());
                    postError(callback, Strings.get(R.string.feedbackcomposeactivity_033));
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        }).start();
    }

    static void submit(Context context, final String title, final String body,
                       final String nickname, final String contact,
                       final String appVersion, final String device,
                       final List<UploadFile> files, final Callback callback) {
        final Context app = context.getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                try {
                    JSONObject data = postMultipart(app, title, body, nickname, contact,
                            appVersion, device, files);
                    postSuccess(callback, data);
                } catch (Exception e) {
                    LogUtil.w(TAG, "submit failed: %s", e.getMessage());
                    // 服务端会回「描述太短」「提交过于频繁」这类可直接展示的提示，
                    // 只有读不到响应（网络层异常）时才退回统一文案
                    String msg = e.getMessage();
                    postError(callback, msg != null && msg.length() > 0
                            ? msg : Strings.get(R.string.feedbackactivity_012));
                }
            }
        }).start();
    }

    private static void get(final String path, final Callback callback) {
        new Thread(new Runnable() {
            public void run() {
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(BASE + path).openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(CONNECT_TIMEOUT);
                    conn.setReadTimeout(READ_TIMEOUT);
                    conn.setRequestProperty("Accept", "application/json");
                    conn.setRequestProperty("User-Agent", "AiChat-Android");
                    int code = conn.getResponseCode();
                    String text = readAll(code >= 200 && code < 300
                            ? conn.getInputStream() : conn.getErrorStream());
                    JSONObject data = new JSONObject(text);
                    if (code < 200 || code >= 300 || !data.optBoolean("ok")) {
                        postError(callback, data.optString("message",
                                Strings.get(R.string.feedbackactivity_012)));
                        return;
                    }
                    postSuccess(callback, data);
                } catch (Exception e) {
                    LogUtil.w(TAG, "get %s failed: %s", path, e.getMessage());
                    postError(callback, Strings.get(R.string.feedbackactivity_012));
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        }).start();
    }

    private static JSONObject postMultipart(Context context, String title, String body,
                                            String nickname, String contact, String appVersion,
                                            String device, List<UploadFile> files) throws Exception {
        String boundary = "----AiChat" + System.currentTimeMillis();
        HttpURLConnection conn = (HttpURLConnection) new URL(BASE + "/api/feedback/upload").openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "AiChat-Android");
        conn.setChunkedStreamingMode(8192);

        OutputStream os = new BufferedOutputStream(conn.getOutputStream());
        try {
            writeField(os, boundary, "title", title);
            writeField(os, boundary, "body", body);
            writeField(os, boundary, "nickname", nickname);
            writeField(os, boundary, "contact", contact);
            writeField(os, boundary, "app_version", appVersion);
            writeField(os, boundary, "device", device);
            for (int i = 0; i < files.size(); i++) {
                writeFile(os, boundary, context, files.get(i));
            }
            write(os, "--" + boundary + "--\r\n");
        } finally {
            try {
                os.close();
            } catch (IOException ignored) {
            }
        }

        int code = conn.getResponseCode();
        String text = readAll(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
        JSONObject data = new JSONObject(text);
        if (code < 200 || code >= 300 || !data.optBoolean("ok")) {
            throw new IOException(data.optString("message", "HTTP " + code));
        }
        return data;
    }

    private static void writeField(OutputStream os, String boundary, String name, String value)
            throws IOException {
        write(os, "--" + boundary + "\r\n");
        write(os, "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        write(os, value == null ? "" : value);
        write(os, "\r\n");
    }

    private static void writeFile(OutputStream os, String boundary, Context context, UploadFile file)
            throws IOException {
        write(os, "--" + boundary + "\r\n");
        write(os, "Content-Disposition: form-data; name=\"files\"; filename=\""
                + safeName(file.name) + "\"\r\n");
        String mime = file.mime;
        if (mime == null || mime.length() == 0) mime = "application/octet-stream";
        write(os, "Content-Type: " + mime + "\r\n\r\n");

        InputStream in = null;
        try {
            in = context.getContentResolver().openInputStream(file.uri);
            if (in == null) throw new IOException("openInputStream returned null");
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) {
                os.write(buffer, 0, n);
            }
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
        write(os, "\r\n");
    }

    /** 文件名里的引号与换行会破坏 multipart 头，换成安全字符。 */
    private static String safeName(String name) {
        String n = name == null || name.length() == 0 ? "file" : name;
        return n.replace('"', '\'').replace('\r', '_').replace('\n', '_');
    }

    private static void write(OutputStream os, String text) throws IOException {
        os.write(text.getBytes("UTF-8"));
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append('\n');
        }
        reader.close();
        return sb.toString();
    }

    private static void postSuccess(final Callback callback, final JSONObject data) {
        MAIN.post(new Runnable() {
            public void run() {
                callback.onSuccess(data);
            }
        });
    }

    private static void postError(final Callback callback, final String message) {
        MAIN.post(new Runnable() {
            public void run() {
                callback.onError(message);
            }
        });
    }
}
