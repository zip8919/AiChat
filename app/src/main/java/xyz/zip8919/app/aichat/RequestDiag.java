package xyz.zip8919.app.aichat;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.text.format.DateFormat;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 请求诊断记录：把一次对话请求从发起到收尾的每一步落到本机，供导出排查。
 *
 * <p><b>为什么需要</b>：偶发的「发出去了没有任何回应、也不报错」问题，光看 logcat
 * 拿不到现场——用户手上没有 adb，事后也复现不出来。这里在请求线程上同步记一份，
 * 设置开关打开时生效，只保留最近 {@value #KEEP} 次请求，导出时整份交出去。
 *
 * <p><b>不变量</b>：
 * <ol>
 *   <li>记录本身绝不能影响请求：任何 IO / 解析异常都吞掉，方法不抛异常。</li>
 *   <li>不记密钥、不记原值之外的授权头：Authorization 一律脱敏，只留长度。</li>
 *   <li>体积有上限：单次请求正文合计 {@value #MAX_REQUEST_BODY} 字符、响应原文
 *       {@value #MAX_RESPONSE_BODY} 字符，超出截断并注明原始长度。</li>
 * </ol>
 */
final class RequestDiag {

    private static final String TAG = "RequestDiag";

    private static final String PREFS_NAME = "aichat_settings";
    private static final String KEY_ENABLED = "diagnostic_log_enabled";

    private static final String DIR_NAME = "request_logs";
    private static final String INDEX_FILE = "index.json";
    /** 最近保留几次请求；导出范围就是这些。 */
    static final int KEEP = 3;

    private static final int MAX_REQUEST_BODY = 262144;
    private static final int MAX_RESPONSE_BODY = 16384;
    private static final int MAX_LINE = 2000;
    private static final int MAX_EVENTS = 400;

    private static final Object LOCK = new Object();

    private RequestDiag() {
    }

    static boolean isEnabled(Context ctx) {
        if (ctx == null) return false;
        return ctx.getSharedPreferences(PREFS_NAME, 0).getBoolean(KEY_ENABLED, false);
    }

    static void setEnabled(Context ctx, boolean enabled) {
        if (ctx == null) return;
        SharedPreferences p = ctx.getSharedPreferences(PREFS_NAME, 0);
        p.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    // ---------- 一次请求的上下文 ----------

    /** 列表项：某一次请求的一句话摘要，导出前就知道哪条是哪条。 */
    static final class Entry {
        final String id;
        final String summary;
        final int size;

        Entry(String id, String summary, int size) {
            this.id = id;
            this.summary = summary;
            this.size = size;
        }
    }

    static Entry[] list(Context ctx) {
        if (ctx == null) return new Entry[0];
        synchronized (LOCK) {
            List<Entry> out = new ArrayList<Entry>();
            JSONArray idx = readIndex(ctx);
            for (int i = 0; i < idx.length(); i++) {
                JSONObject o = idx.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("id", "");
                if (id.length() == 0) continue;
                out.add(new Entry(id, o.optString("summary", ""), o.optInt("size", 0)));
            }
            return out.toArray(new Entry[0]);
        }
    }

    /** 全部记录的正文，按「旧 → 新」拼接。没有记录时返回空串。 */
    static String exportText(Context ctx) {
        if (ctx == null) return "";
        synchronized (LOCK) {
            StringBuilder sb = new StringBuilder();
            sb.append(Strings.get(R.string.requestdiag_001)).append('\n');
            sb.append(Strings.get(R.string.requestdiag_002)).append(deviceLine()).append('\n');
            sb.append(Strings.get(R.string.requestdiag_003)).append(
                    DateFormat.format("yyyy-MM-dd HH:mm:ss", System.currentTimeMillis())).append('\n');
            Entry[] entries = list(ctx);
            if (entries.length == 0) {
                sb.append('\n').append(Strings.get(R.string.requestdiag_004));
            }
            for (int i = 0; i < entries.length; i++) {
                sb.append('\n').append("============================================================\n");
                sb.append(entries[i].summary).append('\n');
                sb.append("============================================================\n");
                sb.append(readFile(ctx, entries[i].id));
                sb.append('\n');
            }
            return sb.toString();
        }
    }

    /** 导出用的文件名：带时间，便于多次导出放在一起区分。 */
    static String exportFileName() {
        return "request-log-"
                + DateFormat.format("MMdd-HHmmss", System.currentTimeMillis()) + ".txt";
    }

    static void clear(Context ctx) {
        if (ctx == null) return;
        synchronized (LOCK) {
            File dir = dir(ctx);
            File[] files = dir.listFiles();
            if (files != null) {
                for (int i = 0; i < files.length; i++) {
                    if (!INDEX_FILE.equals(files[i].getName())) files[i].delete();
                }
            }
            writeIndex(ctx, new JSONArray());
        }
    }

    // ---------- 记录接口（请求线程调用） ----------

    /**
     * 一次请求的诊断记录。
     *
     * <p>线程安全：内部同步，允许工具线程、请求线程同时追加。
     * {@link #flush()} 负责落盘并登记到索引，只调用一次。
     */
    static final class Recorder {
        final String id;
        private final Context app;
        private final StringBuilder sb = new StringBuilder();
        private final long startedAt = System.currentTimeMillis();
        private int events;
        private boolean truncatedEvents;
        private String status = "";
        private int httpCode = -1;
        private long totalMs;
        private boolean closed;

        Recorder(Context ctx) {
            this.app = ctx == null ? null : ctx.getApplicationContext();
            this.id = "req-" + startedAt + "-" + Integer.toHexString(hashCode() & 0xffff);
            head("==== 开始记录 ====", null);
        }

        synchronized void head(String event, String detail) {
            if (closed) return;
            sb.append('[').append(time()).append("] ").append(event);
            if (detail != null) sb.append(' ').append(detail);
            sb.append('\n');
            budget();
        }

        synchronized void kv(String key, Object value) {
            if (closed) return;
            sb.append("  ").append(key).append(": ").append(value == null ? "-" : String.valueOf(value)).append('\n');
            budget();
        }

        /** 记一个正文块（请求体、响应原文），超长截断并注明原始长度。 */
        synchronized void body(String key, String content, int max) {
            if (closed) return;
            int len = content == null ? 0 : content.length();
            sb.append("  ").append(key).append(": length=").append(len);
            if (content != null && len > max) {
                sb.append(", truncated=").append(max);
                content = TextKit.cut(content, max);
            }
            sb.append('\n').append(content == null ? "" : content).append('\n');
            budget();
        }

        synchronized void event(String event, String detail) {
            if (closed) return;
            events++;
            head(event, detail);
        }

        /**
         * 请求进入 HTTP 之前、HTTP 返回之后、收尾各调一次：把「卡在哪一段」直接写清楚。
         * 这三条是排查「没有任何回应」时最主要的信息。
         */
        synchronized void phase(String name, String detail) {
            head("phase:" + name, detail);
        }

        synchronized void end(String status, int httpCode, long totalMs) {
            this.status = status;
            this.httpCode = httpCode;
            this.totalMs = totalMs;
        }

        /** 落盘并登记索引；重复调用只生效一次。返回记录 id。 */
        synchronized String flush() {
            if (closed) return id;
            closed = true;
            if (app == null) return id;
            try {
                head("end", "status=" + status + " http=" + httpCode + " total=" + totalMs + "ms");
                String summary = summarize();
                synchronized (LOCK) {
                    File dir = dir(app);
                    if (!dir.exists()) dir.mkdirs();
                    writeRaw(new File(dir, id + ".log"), sb.toString());
                    JSONArray idx = readIndex(app);
                    JSONArray next = new JSONArray();
                    next.put(new JSONObject().put("id", id).put("summary", summary)
                            .put("size", sb.length()));
                    for (int i = 0; i < idx.length(); i++) {
                        JSONObject o = idx.optJSONObject(i);
                        if (o == null || id.equals(o.optString("id", ""))) continue;
                        next.put(o);
                        if (next.length() >= KEEP) break;
                    }
                    writeIndex(app, next);
                    // 不在索引里的文件（超出保留数或写一半残留）一并清掉
                    File[] files = dir.listFiles();
                    if (files != null) {
                        for (int i = 0; i < files.length; i++) {
                            String n = files[i].getName();
                            if (INDEX_FILE.equals(n) || !n.endsWith(".log")) continue;
                            boolean keep = false;
                            for (int k = 0; k < next.length(); k++) {
                                if ((next.optJSONObject(k).optString("id", "") + ".log").equals(n)) {
                                    keep = true;
                                    break;
                                }
                            }
                            if (!keep) files[i].delete();
                        }
                    }
                }
                LogUtil.d(TAG, "flushed %s (%d chars)", id, sb.length());
            } catch (Throwable t) {
                LogUtil.w(TAG, "flush failed: %s", t.getMessage());
            }
            return id;
        }

        private String summarize() {
            String model = "";
            String prov = "";
            try {
                String first = sb.toString();
                int a = first.indexOf("model: ");
                if (a >= 0) {
                    int b = first.indexOf('\n', a);
                    if (b < 0) b = first.length();
                    model = first.substring(a + 7, Math.min(b, a + 7 + 60));
                }
                a = first.indexOf("provider: ");
                if (a >= 0) {
                    int b = first.indexOf('\n', a);
                    if (b < 0) b = first.length();
                    prov = first.substring(a + 10, Math.min(b, a + 10 + 40));
                }
            } catch (Throwable ignored) {
            }
            return DateFormat.format("MM-dd HH:mm:ss", startedAt) + " · " + model
                    + " @ " + prov + " · " + status + " · " + totalMs + "ms";
        }

        private void budget() {
            if (sb.length() <= MAX_REQUEST_BODY + MAX_RESPONSE_BODY + 8192) return;
            if (events > MAX_EVENTS) {
                if (!truncatedEvents) {
                    truncatedEvents = true;
                    sb.append("  <日志过多，后续事件已省略>\n");
                }
                closed = true;   // 继续追加只会把文件撑大，正文已经足够定位
            }
        }

        private String time() {
            return DateFormat.format("HH:mm:ss.SSS", System.currentTimeMillis()).toString();
        }
    }

    // ---------- 内部 ----------

    private static File dir(Context ctx) {
        return new File(ctx.getApplicationContext().getFilesDir(), DIR_NAME);
    }

    /**
     * 请求记录的目录，供反馈页挑选附件使用。
     * 目录可能不存在（没开过诊断记录），调用方按空列表处理。
     */
    static File logDir(Context ctx) {
        return ctx == null ? null : dir(ctx);
    }

    private static String readFile(Context ctx, String id) {
        try {
            return readRaw(new File(dir(ctx), id + ".log"));
        } catch (Throwable t) {
            return "";
        }
    }

    private static String readRaw(File f) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            byte[] buf = new byte[(int) Math.min(f.length(), 4L * 1024 * 1024)];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n <= 0) break;
                off += n;
            }
            return new String(buf, 0, off, "UTF-8");
        } finally {
            in.close();
        }
    }

    private static void writeRaw(File f, String content) throws Exception {
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(content.getBytes("UTF-8"));
            out.flush();
        } finally {
            out.close();
        }
    }

    private static JSONArray readIndex(Context ctx) {
        try {
            File f = new File(dir(ctx), INDEX_FILE);
            if (!f.exists()) return new JSONArray();
            return new JSONArray(readRaw(f));
        } catch (Throwable t) {
            return new JSONArray();
        }
    }

    private static void writeIndex(Context ctx, JSONArray idx) {
        try {
            File dir = dir(ctx);
            if (!dir.exists()) dir.mkdirs();
            writeRaw(new File(dir, INDEX_FILE), idx.toString());
        } catch (Throwable t) {
            LogUtil.w(TAG, "write index failed: %s", t.getMessage());
        }
    }

    private static String deviceLine() {
        try {
            return Build.MODEL + " / Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")";
        } catch (Throwable t) {
            return "-";
        }
    }

    /** 脱敏后的请求头：Authorization 只保留长度。 */
    static String maskAuth(String header) {
        if (header == null) return "-";
        int len = header.length();
        if (len <= 12) return "<hidden>";
        return "<hidden len=" + len + ">";
    }

    /** 长文本压成单行，避免一条事件撑出几十行。 */
    static String line(String s) {
        if (s == null) return "-";
        String t = s.replace('\r', ' ').replace('\n', ' ');
        if (t.length() > MAX_LINE) t = t.substring(0, MAX_LINE) + "…";
        return t;
    }

    /** 详情字段里只保留原文前 N 字符，用于错误体等。 */
    static String cut(String s, int max) {
        if (s == null) return "-";
        if (s.length() <= max) return line(s);
        return line(s.substring(0, max)) + " …(共 " + s.length() + " 字符)";
    }
}
