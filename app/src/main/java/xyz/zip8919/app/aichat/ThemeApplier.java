package xyz.zip8919.app.aichat;

import android.app.ActionBar;
import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.TextView;

/**
 * 原生界面注入（实现方案 §7.1，工程裁决 E2）。
 *
 * <p><b>覆盖链修复</b>：早期版本只刷 window/ActionBar 与「深色字反相」，其余 View
 * 因没有打 {@code ac_token_tag} 完全没进覆盖链，导致实机出现「深色模式浅底白字 /
 * 主题绿色但按钮仍蓝 / 深色主题下搜索与扫描组件仍蓝」。
 * 现由 {@link ThemeRecolor} 按 View 原始颜色值反查 token 全量接管颜色，
 * 本类只负责窗口、字号与字体。
 *
 * <p><b>深浅模式用运行时刷色，不做 setTheme + recreate</b>：Applier 运行在
 * setContentView 之后，此时 setTheme 已无效；recreate 还要保存/恢复滚动位置与
 * 流式输出状态。纯刷色天然满足「不重灌、不重置滚动、不打断流式输出」。
 *
 * <p><b>零回归</b>：默认态（100% / 标准 / 浅色 / 无主题 / 无覆盖 / 系统字体）
 * 且从未定制过时直接返回，一行都不碰现有 View；定制过再回默认态时，
 * ThemeRecolor 会按快照把每个 View 还原成原始颜色。
 */
final class ThemeApplier implements AppearanceManager.Applier {

    private static final String TAG = "ThemeApplier";
    private static final ThemeApplier INSTANCE = new ThemeApplier();

    /** 进程内是否曾进入非默认态；用于「回退默认」时把已改过的 View 恢复。 */
    private static boolean everCustomized = false;

    static ThemeApplier get() {
        return INSTANCE;
    }

    /**
     * 进程内是否曾进入非默认外观。
     *
     * <p>回退默认时需要它：{@code isAppearanceDefault()} 为 true 有两种情况 ——
     * 「从未定制」（什么都不用做）和「定制后改回默认」（必须把已刷过的 View 还原）。
     * inflate 期着色也依赖它，否则默认态会把新 View 也刷一遍。
     */
    static boolean everCustomized() {
        return everCustomized;
    }

    private ThemeApplier() {
    }

    @Override
    public void apply(Activity activity, AppearanceManager m) {
        if (activity == null || m == null) return;
        boolean def = m.isAppearanceDefault();
        if (def && !everCustomized) return;      // 零回归短路
        if (!def) everCustomized = true;

        boolean dark = m.isDark();
        applyWindow(activity, m, dark, def);

        View root = activity.findViewById(android.R.id.content);
        if (root != null) walk(root, m, dark, def);
    }

    private void applyWindow(Activity a, AppearanceManager m, boolean dark, boolean def) {
        if (def) {
            // 回到默认：写回内置默认色，**不能置 null**。
            //
            // setBackgroundDrawable(null) 只是把背景变透明，并不会让 styles.xml 里的
            // windowBackground / ActionBarStyle 重新生效。而 everCustomized 一旦置位，
            // 在本进程内不再复位——于是「打开再关掉深色」之后打开的每一个页面都会
            // 走这条分支：顶栏蓝条消失、页面底色变灰，必须杀进程才恢复。
            try {
                a.getWindow().setBackgroundDrawable(new ColorDrawable(
                        ThemeTokens.defaultColor(ThemeTokens.AC_BG, false)));
                ActionBar ab = a.getActionBar();
                if (ab != null) {
                    ab.setBackgroundDrawable(new ColorDrawable(
                            ThemeTokens.defaultColor(ThemeTokens.AC_PRIMARY, false)));
                }
            } catch (Throwable ignored) {}
            return;
        }
        try {
            a.getWindow().setBackgroundDrawable(new ColorDrawable(m.getEffectiveColor(ThemeTokens.AC_BG, dark)));
            ActionBar ab = a.getActionBar();
            if (ab != null) {
                ab.setBackgroundDrawable(new ColorDrawable(m.getEffectiveColor(ThemeTokens.AC_PRIMARY, dark)));
            }
        } catch (Throwable t) {
            LogUtil.w(TAG, "applyWindow failed: " + t.getMessage());
        }
    }

    private void walk(View v, AppearanceManager m, boolean dark, boolean def) {
        if (v instanceof WebView) return;   // WebView 走 CSS 注入，绝不进原生遍历
        // 纯展示子树（色板网格 / 取色器 / 主题预览样例）整体豁免：
        // 这些 View 的颜色本身就是「要被挑选的对象」，刷色会让取色器和预览失真，
        // 也不能按当前倍率二次缩放（预览页有自己的 percent 渲染）。
        if (Boolean.TRUE.equals(v.getTag(R.id.ac_no_recolor))) return;
        ThemeRecolor.apply(v, m, dark, def);
        if (v instanceof TextView) applyTextStyle((TextView) v, m, def);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                walk(g.getChildAt(i), m, dark, def);
            }
        }
    }

    /**
     * 字号与字体（颜色已交给 ThemeRecolor，这里绝不碰 setTextColor，避免两套逻辑打架）。
     *
     * <p>三类特殊 View 按交互方案 13.3 逐档防溢出规则单独处理，否则 250% 档必溢出：
     * 数值区（固定 56dp）完全不缩放；按钮按倍率做 12/11/10sp 降级；
     * 页标题放大封顶 20sp。
     */
    private void applyTextStyle(TextView tv, AppearanceManager m, boolean def) {
        int percent = m.getDisplayPercent();
        int step = m.getFontSizeStep();
        Object baseTag = tv.getTag(R.id.ac_base_text_size);
        float basePx;
        if (baseTag instanceof Float) {
            basePx = ((Float) baseTag).floatValue();
        } else {
            basePx = tv.getTextSize();
            tv.setTag(R.id.ac_base_text_size, Float.valueOf(basePx));
        }
        if (!Boolean.TRUE.equals(tv.getTag(R.id.ac_no_scale))) {
            boolean buttonLike = (tv instanceof Button && !(tv instanceof CompoundButton))
                    || Boolean.TRUE.equals(tv.getTag(R.id.ac_button_text));
            float targetPx;
            if (buttonLike) {
                targetPx = spToPx(tv, ScalePolicy.buttonSp(percent));
            } else if (Boolean.TRUE.equals(tv.getTag(R.id.ac_title_text))) {
                targetPx = Math.min(basePx * (percent / 100.0f) * ScalePolicy.stepFactor(step),
                        spToPx(tv, 20f));
            } else {
                targetPx = basePx * (percent / 100.0f) * ScalePolicy.stepFactor(step);
            }
            if (Math.abs(tv.getTextSize() - targetPx) > 0.5f) {
                tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, targetPx);
            }
        }

        // 字体：非默认态按引用加载，默认态恢复原始。
        // 首次 apply 时 tag 刚写入，必须同步更新局部变量，否则「自定义字体为空 →
        // 恢复原始」这一支会拿到 null，把标题粗体、数值等宽等样式丢成默认字体。
        Object baseTfTag = tv.getTag(R.id.ac_base_typeface);
        if (!(baseTfTag instanceof Typeface)) {
            Typeface orig = tv.getTypeface();
            tv.setTag(R.id.ac_base_typeface, orig);
            baseTfTag = orig;
        }
        if (def) {
            tv.setTypeface(baseTfTag instanceof Typeface ? (Typeface) baseTfTag : null);
        } else {
            boolean mono = isMonoTagged(tv);
            String ref = m.getEffective(mono ? ThemeTokens.FONT_MONO : ThemeTokens.FONT_UI);
            Typeface tf = FontRepository.getInstance().getTypeface(ref);
            // tf 为 null 表示 system / 加载失败 → 用原始字体
            tv.setTypeface(tf != null ? tf : (baseTfTag instanceof Typeface ? (Typeface) baseTfTag : null));
        }
    }

    /** sp → px：setTextSize(COMPLEX_UNIT_PX) 需要像素值，换算用 scaledDensity。 */
    private static float spToPx(TextView tv, float sp) {
        return sp * tv.getResources().getDisplayMetrics().scaledDensity;
    }

    private static boolean isMonoTagged(View v) {
        Object tag = v.getTag(R.id.ac_token_tag);
        return "mono".equals(tag);
    }
}
