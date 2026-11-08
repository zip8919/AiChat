package xyz.zip8919.app.aichat;

/**
 * 显示大小与字号档位的计算口径（实现方案 E3 裁决）。
 *
 * <p>原生实际字号 = 基准 sp × percent/100 × 档位系数；
 * WebView 只用 {@code setTextZoom(percent)}，<b>不再乘档位系数</b>——
 * setTextZoom 只有一个值，两者叠加会让 WebView 双重放大，250% 档必溢出。
 *
 * <p>50~250% 是给移植到其他 DPI 设备的适配旋钮，本台 258dp 设备默认 100%
 * 且正常不动；100% 档 ±8% 磁力吸附防误触。
 */
final class ScalePolicy {

    static final int MIN_PERCENT = 50;
    static final int MAX_PERCENT = 250;
    static final int STEP_PERCENT = 25;
    static final int DEFAULT_PERCENT = 100;

    /** 字号 5 档系数：小 / 偏小 / 标准 / 偏大 / 大。 */
    private static final float[] STEP_FACTOR = {0.85f, 0.925f, 1.0f, 1.1f, 1.2f};
    private static final String[] STEP_LABEL = {"小", "偏小", "标准", "偏大", "大"};

    private ScalePolicy() {
    }

    static float stepFactor(int step) {
        int s = AppearanceState.clampStep(step);
        return STEP_FACTOR[s];
    }

    static String stepLabel(int step) {
        return STEP_LABEL[AppearanceState.clampStep(step)];
    }

    /** 原生界面文字的实际 sp。 */
    static float sp(float baseSp, int percent, int step) {
        return baseSp * (percent / 100.0f) * stepFactor(step);
    }

    /** WebView 侧只跟随 percent。 */
    static int textZoom(int percent) {
        return AppearanceState.clampPercent(percent);
    }

    /** 档位吸附：每 25% 一档；100% 档 ±8% 内强制回 100%。 */
    static int snap(int raw) {
        int v = AppearanceState.clampPercent(raw);
        if (v >= DEFAULT_PERCENT - 8 && v <= DEFAULT_PERCENT + 8) return DEFAULT_PERCENT;
        int low = MIN_PERCENT;
        while (low + STEP_PERCENT <= v) low += STEP_PERCENT;
        int high = Math.min(MAX_PERCENT, low + STEP_PERCENT);
        return (v - low) <= (high - v) ? low : high;
    }

    /** 高倍率警告阈值。 */
    static boolean needsWarning(int percent) {
        return percent > 150;
    }

    /**
     * 按钮文字按倍率手动降级：API 18 无 TextView 自动缩放，必须自己分档。
     * 基准 12sp，倍率越高字越小，给窄屏按钮留余量。
     */
    static float buttonSp(int percent) {
        float p = percent / 100.0f;
        if (p >= 2.0f) return 10f;
        if (p >= 1.5f) return 11f;
        return 12f;
    }

    /** 页标题超过 20sp 不再继续放大，改由容器换行承载（交互方案 §13.3）。 */
    static float titleSp(float baseSp, int percent) {
        float v = baseSp * (percent / 100.0f);
        return v > 20f ? 20f : v;
    }
}
