package xyz.zip8919.app.aichat;

import android.content.Context;

/**
 * 字符串资源读取入口。
 *
 * <p>工程里不少文案位于非 Activity 的类（工具、管理器、适配器）中，那些位置没有
 * {@code getString()} 可用，因此统一从这里取。应用启动时由 {@link AcApp} 注入
 * Application 级别的 Context，不持有 Activity，不会泄漏。
 */
public final class Strings {

    private static Context app;

    private Strings() {
    }

    static void init(Context context) {
        app = context.getApplicationContext();
    }

    public static String get(int resId) {
        return app.getString(resId);
    }

    public static String get(int resId, Object... args) {
        return app.getString(resId, args);
    }
}
