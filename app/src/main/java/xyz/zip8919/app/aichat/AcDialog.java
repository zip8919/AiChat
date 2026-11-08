package xyz.zip8919.app.aichat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.view.ContextThemeWrapper;

/**
 * 对话框工厂（替代直接 {@code new AlertDialog.Builder(...)}）。
 *
 * <p><b>为什么需要它</b>：AlertDialog 是独立 Window，{@link ThemedActivity} 装的
 * LayoutInflater 钩子只能刷到它的<b>内容 View</b>，刷不到窗口底板（9-patch 背景）。
 * 深色外观下底板仍是 Holo Light 的白，于是出现「白底 + 被刷亮的文字」——正是实机上
 * 「弹出的菜单和提示是白色背景」那一条。
 *
 * <p><b>做法</b>：把 Context 包一层系统自带的 {@code Theme_Holo_Dialog}（深色变体），
 * 让 dialog 背景、分割线、列表项、按钮文字一起变深。用系统主题而不是自定义 style，
 * 是为了不新增资源、也不依赖具体 ROM 的属性名。
 *
 * <p><b>零回归</b>：浅色/默认外观下原样返回 ctx，行为与改动前完全一致。
 *
 * <p><b>只包 Activity</b>：用 Application 上下文创建 Dialog 没有 Window token，
 * 会直接抛 BadTokenException；遇到非 Activity 上下文一律原样返回。
 */
final class AcDialog {

    private AcDialog() {
    }

    static AlertDialog.Builder builder(Context ctx) {
        return new ThemedBuilder(wrap(ctx));
    }

    /**
     * 在 {@code show()} 之后补刷一次内容树。
     *
     * <p>{@code Theme_Holo_Dialog} 只解决窗口底板；dialog 里的 EditText、列表行
     * 如果自己写了 {@code @color/white}，深色下仍是白块。而且 dialog 的 content
     * 常常用 {@code LayoutInflater.from(ctx)} 自建，ThemedActivity 的钩子够不到。
     */
    private static final class ThemedBuilder extends AlertDialog.Builder {
        ThemedBuilder(Context ctx) {
            super(ctx);
        }

        @Override
        public AlertDialog show() {
            AlertDialog d = super.show();
            try {
                if (d.getWindow() != null) {
                    ThemeRecolor.applyTo(d.getWindow().getDecorView());
                }
            } catch (Throwable t) {
                LogUtil.w("AcDialog", "recolor dialog failed: " + t.getMessage());
            }
            return d;
        }
    }

    /** 显式指定了主题的调用保持原样：调用方自己知道要什么外观。 */
    static AlertDialog.Builder builder(Context ctx, int themeResId) {
        return new AlertDialog.Builder(ctx, themeResId);
    }

    private static Context wrap(Context ctx) {
        if (!(ctx instanceof Activity)) return ctx;
        try {
            AppearanceManager m = AppearanceManager.getInstance(ctx);
            if (!m.isDark() && m.getThemeId().length() == 0) return ctx;
            return new ContextThemeWrapper(ctx, android.R.style.Theme_Holo_Dialog);
        } catch (Throwable t) {
            return ctx;
        }
    }
}
