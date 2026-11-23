package xyz.zip8919.app.aichat;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 思考块诊断日志：把「谁、在什么时刻、把 display 改成了什么」写进文件。
 *
 * <p>为什么单独做一份而不是走 logcat：这类问题发生在手机上，用户拿不到
 * logcat。写文件后直接取走 {@link #file(Context)} 返回的路径即可。
 *
 * <p>只在 debug 构建里生效（{@link #ENABLED}），正式包不产生任何 IO。
 * 单文件超过 {@link #MAX_BYTES} 后从头重写，避免长会话把存储写满。
 */
public final class ThinkLog {

    /** debug 构建开关；发布包为 false，JS 侧调用直接返回 */
    public static final boolean ENABLED = BuildConfig.DEBUG;

    private static final String NAME = "thinking_trace.log";
    private static final long MAX_BYTES = 1024 * 1024;
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private static File outFile;
    private static long written;

    private ThinkLog() {
    }

    /** 日志文件；未初始化时返回 null */
    public static synchronized File file(Context ctx) {
        if (outFile == null && ctx != null) {
            File dir = ctx.getExternalFilesDir("logs");
            if (dir == null) dir = new File(ctx.getFilesDir(), "logs");
            if (!dir.exists()) dir.mkdirs();
            outFile = new File(dir, NAME);
            written = outFile.length();
        }
        return outFile;
    }

    /** 追加一行；任何异常都吞掉——诊断日志绝不能影响主流程 */
    public static synchronized void line(Context ctx, String msg) {
        if (!ENABLED) return;
        try {
            File f = file(ctx);
            if (f == null) return;
            if (written > MAX_BYTES) {
                // 超限就重开，保留最后一段上下文的价值不大，这里直接截断
                new FileOutputStream(f, false).close();
                written = 0;
                append(f, "===== log rotated =====\n");
            }
            String row = TS.format(new Date()) + " " + msg + "\n";
            append(f, row);
            written += row.getBytes("UTF-8").length;
        } catch (Throwable ignored) {
        }
    }

    private static void append(File f, String s) throws IOException {
        FileOutputStream os = new FileOutputStream(f, true);
        try {
            os.write(s.getBytes("UTF-8"));
        } finally {
            os.close();
        }
    }
}
