package xyz.zip8919.app.aichat;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup.LayoutParams;

/**
 * 全应用统一基类：承载外观注入钩子。
 *
 * <p><b>这里刻意不无条件调用 setTheme()</b>：Manifest 里 FullscreenInputActivity 与
 * CropActivity 单独声明了 {@code Theme.Holo.Light.NoActionBar}，无条件 setTheme 会
 * 覆盖它们、凭空多出一条 ActionBar，属于显示错误。深浅模式的 theme 切换由 P2 的
 * ThemeApplier 按「当前 theme 的明暗变体」处理；本基类只负责在 setContentView 之后
 * 与 onResume 时，按 revision 判断是否需要重刷当前页。
 *
 * <p>默认态（100% / 无主题 / 无显式覆盖）下 applier 为空且 revision 不变，
 * 整个基类是零行为的，P0 的验收标准「界面与现版本完全一致」由此保证。
 */
public class ThemedActivity extends Activity {

    private static final String TAG = "ThemedActivity";

    private long appliedRevision = -1;
    private AppearanceManager.ChangeListener pageListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 确保配置已载入；getInstance(Context) 会先喂给 StorageManager 一个 appContext
        AppearanceManager m = AppearanceManager.getInstance(this);
        // 原生注入器只注册一次（无状态单例）；WebView 侧的 ChangeListener 由
        // MainActivity 单独注册，因为只有它持有 conversationWebView
        m.setApplier(ThemeApplier.get());

        // 页面级监听：切深浅色时立刻重刷本页，不必等 onResume、更不必重进 APP
        pageListener = new AppearanceManager.ChangeListener() {
            public void onAppearanceChanged(AppearanceManager mm) {
                forceApplyAppearance();
            }
        };
        m.addChangeListener(pageListener);

        installInflateHook();
    }

    @Override
    protected void onDestroy() {
        AppearanceManager.getInstance(this).removeChangeListener(pageListener);
        pageListener = null;
        super.onDestroy();
    }

    /**
     * 在 inflate 现场着色。
     *
     * <p>为什么需要它：{@code ThemeApplier} 只在 setContentView / onResume 遍历一次，
     * 而下面这些 View 是之后才创建的，永远刷不到 ——
     * <ul>
     *   <li>ListView / RecyclerView 的 item（历史、存储、附件列表都异步填充）</li>
     *   <li>Spinner 下拉项（独立 PopupWindow）</li>
     *   <li>AlertDialog 的内容（独立 Window）</li>
     * </ul>
     *
     * <p>无论当前是否深色都装：默认态启动、之后才切深色时，早就装好的 Factory 仍生效。
     * 默认态由 {@link ThemeRecolor#applyTree} 内部短路，不会改动任何颜色。
     *
     * <p>返回 null 是 Factory2 的约定，表示「交给系统默认创建器」。
     * 短名（{@code TextView} 而不是 {@code android.widget.TextView}）必须按系统前缀
     * 逐个尝试，否则会全部返回 null、退化成系统默认路径（功能正常但不着色）。
     */
    private void installInflateHook() {
        final LayoutInflater inflater = getLayoutInflater();
        if (inflater == null) return;
        if (inflater.getFactory2() != null) return;   // 已被别的组件装过，不覆盖
        try {
            inflater.setFactory2(new LayoutInflater.Factory2() {
                public View onCreateView(View parent, String name, Context ctx, AttributeSet attrs) {
                    return onCreateView(name, ctx, attrs);
                }

                public View onCreateView(String name, Context ctx, AttributeSet attrs) {
                    View v = create(inflater, name, ctx, attrs);
                    if (v != null) {
                        try {
                            ThemeRecolor.applyTree(v, ctx);
                        } catch (Throwable t) {
                            LogUtil.w(TAG, "applyTree failed: " + t.getMessage());
                        }
                    }
                    return v;
                }
            });
        } catch (Throwable t) {
            // setFactory2 在已有 Factory 时会抛 IllegalStateException。
            // 退化成「只在 onResume 遍历」：功能不受影响，只是动态 View 刷不到。
            LogUtil.w(TAG, "install inflate hook failed: " + t.getMessage());
        }
    }

    private static View create(LayoutInflater inflater, String name, Context ctx,
                               AttributeSet attrs) {
        try {
            if (name.indexOf('.') >= 0) {
                return inflater.createView(name, null, attrs);
            }
            String[] prefixes = {"android.widget.", "android.view.", "android.webkit."};
            for (int i = 0; i < prefixes.length; i++) {
                try {
                    View v = inflater.createView(name, prefixes[i], attrs);
                    if (v != null) return v;
                } catch (ClassNotFoundException ignored) {
                    // 换下一个前缀
                }
            }
        } catch (Throwable ignored) {
            // 创建失败让系统默认创建器接手
        }
        return null;
    }

    @Override
    public void setContentView(int layoutResID) {
        super.setContentView(layoutResID);
        maybeApplyAppearance();
    }

    @Override
    public void setContentView(View view) {
        super.setContentView(view);
        maybeApplyAppearance();
    }

    @Override
    public void setContentView(View view, LayoutParams params) {
        super.setContentView(view, params);
        maybeApplyAppearance();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 这里**不能**用 revision 短路。
        //
        // setContentView 只保证「布局骨架」已存在；折叠组、异步列表、动态 addView
        // 的 View 都是在之后才创建的。如果 onResume 因为 revision 没变而跳过遍历，
        // 这批 View 就永远刷不到 —— 表现正是实机上的
        // 「有时切深浅色后，设置页部分控件要重进 APP 才生效」。
        // 一次性遍历几十个 View 的开销远低于用户重进 APP 的成本，故无条件刷。
        AppearanceManager m = AppearanceManager.getInstance(this);
        appliedRevision = m.getRevision();
        m.applyTo(this);
    }

    /** 只有 revision 变化才重刷，避免每次返回都遍历 View 树。 */
    protected final void maybeApplyAppearance() {
        AppearanceManager m = AppearanceManager.getInstance(this);
        if (m.getRevision() != appliedRevision) {
            appliedRevision = m.getRevision();
            m.applyTo(this);
        }
    }

    /** 外观页改动后强制刷新当前页（P3 即时预览用）。 */
    protected final void forceApplyAppearance() {
        appliedRevision = -1;
        maybeApplyAppearance();
    }
}
