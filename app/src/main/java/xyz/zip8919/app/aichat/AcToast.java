package xyz.zip8919.app.aichat;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 跟随外观的轻提示。
 *
 * <p><b>为什么需要它</b>：系统的 {@code Toast} 背景由系统主题决定，与 App 当前外观
 * 无关 —— Holo 体系下永远是一块半透明深灰。于是「浅色模式下提示也是深色」，
 * 与整页浅色界面对不上。{@code targetSdk 18} 下 {@code setView} 不受
 * Android 11 的自定义 Toast 限制，可以自行绘制。
 *
 * <p><b>零回归</b>：完全默认外观且从未定制过时，直接走系统 Toast，外观与改动前一致；
 * 只有真正切过深浅色 / 主题之后才启用自绘气泡。
 *
 * <p><b>失败兜底</b>：个别 ROM 对自定义 Toast 有额外限制，任何异常都退回系统 Toast，
 * 保证提示不丢。
 */
final class AcToast {

    private AcToast() {
    }

    static void show(Context ctx, CharSequence text) {
        show(ctx, text, false);
    }

    static void showLong(Context ctx, CharSequence text) {
        show(ctx, text, true);
    }

    private static void show(Context ctx, CharSequence text, boolean longDuration) {
        if (ctx == null || text == null) return;
        try {
            AppearanceManager m = AppearanceManager.getInstance(ctx);
            if (m.isAppearanceDefault() && !ThemeApplier.everCustomized()) {
                Toast.makeText(ctx, text, longDuration ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
                return;
            }
            boolean dark = m.isDark();
            float density = ctx.getResources().getDisplayMetrics().density;

            TextView tv = new TextView(ctx);
            tv.setText(text);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            tv.setTextColor(m.getEffectiveColor(ThemeTokens.AC_TEXT, dark));
            tv.setPadding((int) (16 * density), (int) (10 * density),
                    (int) (16 * density), (int) (10 * density));

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(m.getEffectiveColor(ThemeTokens.AC_SURFACE, dark));
            bg.setCornerRadius(8 * density);
            bg.setStroke(Math.max(1, (int) density),
                    m.getEffectiveColor(ThemeTokens.AC_BORDER, dark));
            tv.setBackgroundDrawable(bg);

            Toast t = new Toast(ctx);
            t.setDuration(longDuration ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
            t.setView(tv);
            t.show();
        } catch (Throwable e) {
            try {
                Toast.makeText(ctx, text, longDuration ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
        }
    }
}
