package xyz.zip8919.app.aichat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * appearance.json 的读写。照 ConfigManager 的范式：单例 + 单线程异步写 + 原子落盘。
 *
 * <p>最重要的保护：文件存在但读不出 / 解析失败时，保留原文件、用内存默认，
 * 绝不写回默认值覆盖用户配置。与 ConfigManager.load() 同款，理由相同。
 */
final class AppearanceStore {

    private static final String TAG = "AppearanceStore";
    private static AppearanceStore instance;
    private static ExecutorService saveExecutor;

    private final StorageManager storage;

    private AppearanceStore() {
        this.storage = StorageManager.getInstance();
    }

    static synchronized AppearanceStore getInstance() {
        if (instance == null) instance = new AppearanceStore();
        return instance;
    }

    AppearanceState load() {
        String text = storage.loadAppearance();
        if (text == null || text.length() == 0) {
            // 文件不存在，或存在却读不出：都不写回，直接用内存默认
            LogUtil.i(TAG, "load: no readable appearance.json, using defaults");
            return AppearanceState.defaults();
        }
        try {
            AppearanceState s = AppearanceState.parse(text);
            LogUtil.i(TAG, "load: dark=%s theme=%s percent=%d step=%d explicit(l/d)=%d/%d",
                    s.dark, s.themeId, s.displayPercent, s.fontSizeStep,
                    s.explicitLight.size(), s.explicitDark.size());
            return s;
        } catch (Exception e) {
            LogUtil.e(TAG, "load: parse failed, keeping file, using defaults: " + e.getMessage(), e);
            return AppearanceState.defaults();
        }
    }

    /** 异步原子写；调用方已先更新内存态，写失败只记日志、不回滚 UI。 */
    void save(final AppearanceState state) {
        final String text;
        try {
            text = state.toJson();
        } catch (Exception e) {
            LogUtil.e(TAG, "save: serialize failed: " + e.getMessage(), e);
            return;
        }
        getExecutor().execute(new Runnable() {
            @Override
            public void run() {
                boolean ok = storage.saveAppearance(text);
                LogUtil.i(TAG, "save: %d chars ok=%s", text.length(), ok);
            }
        });
    }

    private static synchronized ExecutorService getExecutor() {
        if (saveExecutor == null) {
            saveExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    return new Thread(r, "aichat-appearance-save");
                }
            });
        }
        return saveExecutor;
    }
}
