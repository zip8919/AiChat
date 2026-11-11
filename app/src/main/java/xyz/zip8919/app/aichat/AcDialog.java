package xyz.zip8919.app.aichat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.view.ContextThemeWrapper;
import android.view.View;

/**
 * 对话框工厂（替代直接 {@code new AlertDialog.Builder(...)}）。
 *
 * <p><b>为什么需要它</b>：AlertDialog 是独立 Window，{@link ThemedActivity} 装的
 * LayoutInflater 钩子只能刷到它的<b>内容 View</b>，刷不到窗口底板（9-patch 背景）。
 * 深色外观下底板仍是 Holo Light 的白，于是出现「白底 + 被刷亮的文字」——正是实机上
 * 「弹出的菜单和提示是白色背景」那一条。
 *
 * <p><b>做法</b>：<b>只在深色外观下</b>把 Context 包一层系统自带的
 * {@code Theme_Holo_Dialog}，让 dialog 背景、分割线、列表项、按钮文字一起变深。
 * 浅色下原样返回 ctx。
 *
 * <p>之前这里写成「非深色但选了主题也包」，导致<b>浅色模式 + 任意自定义主题时
 * 弹窗全是深色</b>：{@code Theme_Holo_Dialog} 只有深色变体，浅色请求也套上它，
 * 弹窗底板就永远是黑的。这是本轮「浅色模式弹窗和提示也是深色」的直接原因。
 *
 * <p><b>着色时机</b>：{@code create()} 与 {@code show()} 都要刷。全工程有 11 处写成
 * {@code AcDialog.builder(this)...create()} 之后手动 {@code show()}，这类弹窗
 * 根本不经过 {@code Builder.show()}，只在 show 里刷等于整个 View 树从未着色 ——
 * 表现就是「弹窗里的输入框文字还是黑的」。create 阶段 View 尚未 layout，
 * 背景 drawable 没有 bounds，故再挂一帧补刷一次（幂等，无副作用）。
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
     *
     * <p><b>刷色时机必须是 show 之后，不能在 create 阶段</b>：4.4 上
     * {@code Dialog.getWindow().getDecorView()} 会立刻 {@code installDecor()}，
     * 把 contentParent 建出来；紧接着 {@code show()} 里的
     * {@code AlertController.installContent()} 再 requestFeature 就会抛
     * <pre>AndroidRuntimeException: requestFeature() must be called before adding content</pre>
     * 这正是「4.4 上任何弹窗一打开就闪退」的根因。5.0+ 的 AlertController 不走
     * requestFeature，所以只有老系统崩。
     */
    private static final class ThemedBuilder extends AlertDialog.Builder {
        ThemedBuilder(Context ctx) {
            super(ctx);
        }

        @Override
        public AlertDialog create() {
            AlertDialog d = super.create();
            // 只挂回调，不碰 Window。
            bindRecolor(d, null);
            return d;
        }

        @Override
        public AlertDialog show() {
            // create() 已绑好 OnShowListener，show 时自动刷，这里不能再碰 decor。
            return super.show();
        }
    }

    /**
     * 调用方要在 show 之后做自己的事（调输入法、改布局）时用这个入口。
     *
     * <p>不要直接 {@code dialog.setOnShowListener(...)}：那会覆盖掉这里的着色回调，
     * 弹窗里的输入框、列表行就回到「白底黑字配深色主题」的状态。
     */
    static void onShow(AlertDialog d, DialogInterface.OnShowListener extra) {
        if (d == null) return;
        bindRecolor(d, extra);
    }

    private static void bindRecolor(final AlertDialog d,
                                    final DialogInterface.OnShowListener extra) {
        d.setOnShowListener(new DialogInterface.OnShowListener() {
            public void onShow(DialogInterface dialog) {
                recolor(d);
                if (extra != null) extra.onShow(dialog);
            }
        });
    }

    private static void recolor(AlertDialog d) {
        try {
            if (d == null || d.getWindow() == null) return;
            final View decor = d.getWindow().getDecorView();
            if (decor == null) return;
            ThemeRecolor.applyTo(decor);
            // show 之后已经 attach，但首帧可能还没 layout：背景 drawable 的 bounds
            // 为空时 sampleSolidColor 采不到颜色。挂一帧补一次。
            // 这个 Runnable 跑在消息队列里，没有外层 catch 可接 —— 4.4 低内存机上
            // 遍历 + 采样 bitmap 失败会直接打崩主线程，必须自己兜住。
            decor.post(new Runnable() {
                public void run() {
                    try {
                        ThemeRecolor.applyTo(decor);
                    } catch (Throwable ignored) {
                        // 着色失败只是配色不理想，绝不能因此闪退
                    }
                }
            });
        } catch (Throwable t) {
            LogUtil.w("AcDialog", "recolor dialog failed: " + t.getMessage());
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
            // 只有深色才换深色对话框主题。浅色 + 自定义主题也必须走浅色对话框，
            // 否则「浅色模式下弹窗和提示是深色」会一直存在。
            if (!m.isDark()) return ctx;
            return new ContextThemeWrapper(ctx, android.R.style.Theme_Holo_Dialog);
        } catch (Throwable t) {
            LogUtil.w("AcDialog", "wrap failed: " + t.getMessage());
            return ctx;
        }
    }
}
