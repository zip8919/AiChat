package xyz.zip8919.app.aichat;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 崩溃现场落盘。
 *
 * <p><b>为什么要有它</b>：老设备（4.4 尤其）用户不会连 adb，线上崩溃只有一句
 * 「点开就闪退」，没有堆栈就只能靠猜。这里把未捕获异常的完整栈（含
 * {@code Caused by} 链、机型、系统版本、ABI）追加到
 * {@code Android/data/<pkg>/files/crash.log}，用户用任意文件管理器就能取出来。
 *
 * <p><b>为什么不用第三方上报</b>：本项目零依赖、零账号，且崩溃里可能带接口地址与
 * 会话片段，不做外传。文件只留在设备上，由用户自行决定是否提供。
 *
 * <p><b>失败必须无感</b>：写日志本身出任何问题（无存储权限、目录不可写、磁盘满）
 * 都不能影响原始崩溃处理链，否则会把一次崩溃变成两次。
 */
final class CrashCatcher implements Thread.UncaughtExceptionHandler {

    private static final String TAG = "CrashCatcher";
    private static final String FILE_NAME = "crash.log";
    /** 超过这个大小就整份丢弃重写，避免老设备磁盘被刷满 */
    private static final long MAX_BYTES = 128 * 1024;
    /** 只保留最近几次崩溃，前面加分隔符，便于定位「这次」还是「上次」 */
    private static final int KEEP_SESSIONS = 5;

    private static volatile Context appCtx;
    private static Thread.UncaughtExceptionHandler sDefault;
    private static volatile boolean installed;

    private CrashCatcher() {
    }

    static void install(Context ctx) {
        if (installed) return;
        installed = true;
        try {
            appCtx = ctx.getApplicationContext();
            sDefault = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(new CrashCatcher());
            LogUtil.i(TAG, "installed, crash log -> " + logFile().getAbsolutePath());
        } catch (Throwable t) {
            LogUtil.w(TAG, "install failed: " + t.getMessage());
        }
    }

    @Override
    public void uncaughtException(Thread thread, Throwable ex) {
        try {
            write(thread, ex);
        } catch (Throwable ignored) {
            // 落盘失败绝不能影响系统默认处理
        }
        if (sDefault != null) {
            sDefault.uncaughtException(thread, ex);
        }
    }

    private static File logFile() {
        Context c = appCtx;
        File dir = null;
        if (c != null) {
            try {
                dir = c.getExternalFilesDir(null);
            } catch (Throwable ignored) {
            }
            if (dir == null) {
                try {
                    dir = c.getFilesDir();
                } catch (Throwable ignored) {
                }
            }
        }
        return new File(dir == null ? new File("/sdcard") : dir, FILE_NAME);
    }

    private static synchronized void write(Thread thread, Throwable ex) {
        StringWriter sw = new StringWriter(2048);
        PrintWriter pw = new PrintWriter(sw);
        pw.println();
        pw.println("========== CRASH " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date()) + " ==========");
        pw.println("thread : " + (thread == null ? "?" : thread.getName()));
        pw.println("device : " + Build.MANUFACTURER + " " + Build.MODEL);
        pw.println("android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        pw.println("abi    : " + (Build.VERSION.SDK_INT >= 21
                ? join(Build.SUPPORTED_ABIS) : Build.CPU_ABI));
        pw.println("app    : " + appVersion());
        pw.println("trace  :");
        ex.printStackTrace(pw);
        pw.flush();
        String text = sw.toString();

        File f = logFile();
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) {
            // noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        boolean append = f.exists() && f.length() < MAX_BYTES;
        String old = "";
        if (append) {
            old = readHead(f);
            // 只留最近 KEEP_SESSIONS 段，超出从最早的开始砍
            int cut = old.indexOf("========== CRASH ");
            for (int i = 1; i < KEEP_SESSIONS && cut >= 0; i++) {
                cut = old.indexOf("========== CRASH ", cut + 1);
            }
            if (cut > 0) old = old.substring(cut);
        }
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(f, false);
            fos.write((old + text).getBytes("UTF-8"));
            fos.flush();
        } catch (Throwable t) {
            LogUtil.w(TAG, "write crash log failed: " + t.getMessage());
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (Throwable ignored) {
                }
            }
        }
        LogUtil.e(TAG, "crash captured -> " + f.getAbsolutePath());
    }

    private static String readHead(File f) {
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            byte[] buf = new byte[(int) Math.min(f.length(), MAX_BYTES)];
            int n = in.read(buf);
            return n <= 0 ? "" : new String(buf, 0, n, "UTF-8");
        } catch (Throwable t) {
            return "";
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String appVersion() {
        Context c = appCtx;
        if (c == null) return "?";
        try {
            android.content.pm.PackageInfo pi =
                    c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.versionName + " (" + pi.versionCode + ")";
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String join(String[] arr) {
        if (arr == null) return "?";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(arr[i]);
        }
        return sb.toString();
    }
}
