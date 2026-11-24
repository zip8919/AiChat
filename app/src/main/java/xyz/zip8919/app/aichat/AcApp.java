package xyz.zip8919.app.aichat;

import android.app.Application;

/**
 * 进程入口：只做一件事 —— 装崩溃落盘。
 *
 * <p>放在 Application 而不是 Activity，是因为崩溃可能发生在任何页面（包括
 * 启动瞬间、后台线程），Activity 的 onCreate 覆盖不到。
 */
public class AcApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        Strings.init(this);
        CrashCatcher.install(this);
        // 诊断日志也只在 debug 构建里生效，内部按 BuildConfig.DEBUG 自行判断
        ThinkLog.init(this);
        if (OffDiag.ENABLED) {
            OffDiag.log("==== 应用启动 v%s (%d) 设备=%s API=%d ====",
                    BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE,
                    android.os.Build.MODEL, android.os.Build.VERSION.SDK_INT);
        }
    }
}
