package xyz.zip8919.app.aichat;

import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

/**
 * 原生界面运行时重着色（修复「主题只染 ActionBar、深色只刷文字」的核心缺陷）。
 *
 * <p><b>背景</b>：实现方案 E2 裁决「不改 23 个 layout 的 @color 引用」，靠打 tag 局部重刷。
 * 但施工时没有任何一个现有 View 打过 {@code ac_token_tag}，于是 ThemeApplier 只刷了
 * window/ActionBar 与「深色字反相」，其余界面完全没进覆盖链。表现就是实机三张图：
 * 深色模式浅底白字、主题绿色但按钮仍蓝、深色主题下搜索/扫描组件仍蓝。
 *
 * <p><b>做法</b>：不去改 layout，也不逐个 View 打 tag，而是<b>按 View 的原始颜色值反查 token</b>。
 * 全工程的硬编码色就那么十几个（primary/primary_dark/background/white/text_primary/…），
 * 首次遍历时用 {@link #sampleSolidColor(Drawable)} 取原始纯色，命中映射表就按当前生效色重刷；
 * 文字色用 {@code getCurrentTextColor()} 直接读。原始值存进 {@link BaseStyle} tag，
 * 回到默认态时原样写回，保证零回归、可反复切换。
 *
 * <p><b>零回归</b>：完全默认态由 ThemeApplier 直接 {@link #restore(View, BaseStyle)}；
 * 「只改了显示大小 / 字体、没碰颜色」时由 {@link AppearanceManager#isColorDefault()}
 * 判定，保持 layout 原始色不动 —— 否则 {@code #F0F0F0} / {@code #FFF8E1} /
 * {@code #90CAF9} 这类近似色会被拉齐成 token 默认的纯白 / 主蓝，是肉眼可见的回归。
 *
 * <p><b>不碰的东西</b>：打了 {@code ac_no_recolor} 的纯展示色块（色板 / 取色预览）、
 * WebView、透明或半透明背景（{@code ?attr/selectableItemBackground} 按压反馈）、
 * 语义色（浅黄排队条、红色错误、深色查看器浮层）。
 */
final class ThemeRecolor {

    private static final String TAG = "ThemeRecolor";

    /** 原始背景色 → 颜色 token。蓝色系统一归 AC_PRIMARY，避免 primary_dark 漏网仍蓝。 */
    private static final Map<Integer, String> BG_MAP = new HashMap<Integer, String>();
    /** 原始文字色 → 颜色 token。白色不映射：主色按钮上的反色字必须保持白。 */
    private static final Map<Integer, String> TEXT_MAP = new HashMap<Integer, String>();

    static {
        // --- 品牌蓝系：按钮 / 头部行 / 扫描 / 工具 tab / 链接 ---
        putBg(0xFF2196F3, ThemeTokens.AC_PRIMARY);   // @color/primary
        putBg(0xFF1976D2, ThemeTokens.AC_PRIMARY);   // @color/primary_dark
        putBg(0xFF1E88E5, ThemeTokens.AC_PRIMARY);   // 工具页 / 存储蓝
        putBg(0xFF1565C0, ThemeTokens.AC_PRIMARY);
        putBg(0xFF42A5F5, ThemeTokens.AC_PRIMARY);
        putBg(0xFF64B5F6, ThemeTokens.AC_PRIMARY);
        putBg(0xFF90CAF9, ThemeTokens.AC_PRIMARY);   // 发送按钮禁用态浅蓝
        // --- 页面底 / 表面 ---
        putBg(0xFFF5F5F5, ThemeTokens.AC_BG);        // @color/background
        putBg(0xFFFFFFFF, ThemeTokens.AC_SURFACE);   // @color/white、@android:color/white
        putBg(0xFFFAFAFA, ThemeTokens.AC_SURFACE);   // tools / 预览页浅底
        putBg(0xFFFAFBFC, ThemeTokens.AC_SURFACE);
        putBg(0xFFF8FBFF, ThemeTokens.AC_SURFACE);
        putBg(0xFFF0F0F0, ThemeTokens.AC_SURFACE);   // @color/light_gray
        // JS 运行器三处区块底：不刷深会让其上的深色文字被兜底反相成白字，
        // 又变成「浅底白字」，等于图1 的问题在 JS 运行器页复发。
        putBg(0xFFEEEEEE, ThemeTokens.AC_SURFACE);
        // 语义浅底（排队条 / 空态模板 / 附件提示）：必须一起刷深，否则其上的
        // 深色文字被兜底反相成白字后又会变成「浅底白字」，等于图1 的问题复发。
        putBg(0xFFFFF8E1, ThemeTokens.AC_SURFACE);
        putBg(0xFFFFF3CD, ThemeTokens.AC_SURFACE);
        putBg(0xFFF2F4F7, ThemeTokens.AC_SURFACE);
        putBg(0xFFFAFBFB, ThemeTokens.AC_SURFACE);
        // --- 气泡 / 分隔 ---
        putBg(0xFFE3F2FD, ThemeTokens.AC_BUBBLE_USER); // @color/message_user
        putBg(0xFFE0E0E0, ThemeTokens.AC_DIVIDER);
        putBg(0xFFCCCCCC, ThemeTokens.AC_BORDER);      // @color/gray

        // --- 文字 ---
        putText(0xFF2196F3, ThemeTokens.AC_PRIMARY);
        putText(0xFF1976D2, ThemeTokens.AC_PRIMARY);
        putText(0xFF1565C0, ThemeTokens.AC_PRIMARY);
        putText(0xFF1E88E5, ThemeTokens.AC_PRIMARY);
        putText(0xFF333333, ThemeTokens.AC_TEXT);         // @color/text_primary
        putText(0xFF666666, ThemeTokens.AC_TEXT_SECONDARY); // @color/text_secondary
        putText(0xFF777777, ThemeTokens.AC_TEXT_SECONDARY); // usage_bar
        putText(0xFF90A4AE, ThemeTokens.AC_TEXT_SECONDARY);
        putText(0xFF999999, ThemeTokens.AC_TEXT_SECONDARY);
        putText(0xFFBDBDBD, ThemeTokens.AC_TEXT_SECONDARY);
        // 排队条 / 多选栏的暗黄字：深色底上几乎糊成一片，改判到次级文字色
        putText(0xFF8D6E00, ThemeTokens.AC_TEXT_SECONDARY);
        // 模型下拉第二行的 provider（10/11sp 小字）：亮度 136 不会被深色兜底反相，
        // 不映射的话在深色底上会偏暗到看不清。
        putText(0xFF888888, ThemeTokens.AC_TEXT_SECONDARY);
        // 列表项右侧「›」箭头（设置页两处、小工具页一处）
        putText(0xFFB0BEC5, ThemeTokens.AC_TEXT_SECONDARY);
        // 风险提示 / 删除 / 错误红：映射到 ac_danger 才能保住「危险」语义。
        // 不映射会被深色兜底反相成普通正文色，红色警示信息就消失了。
        putText(0xFFC62828, ThemeTokens.AC_DANGER);
        putText(0xFFD32F2F, ThemeTokens.AC_DANGER);
        // 纯黑：大量「代码 new TextView 且不设 textColor」的地方会继承 Holo Light
        // 的 textColorPrimary(#000000)，深色下就是黑底黑字。检查更新的镜像名、
        // 存储页动态行都属于这一类，必须映射。
        putText(0xFF000000, ThemeTokens.AC_TEXT);
        // 0xFFFFFFFF 有意不映射：主色按钮 / 深色头部上的白字必须保持白
    }

    private static void putBg(int argb, String token) {
        BG_MAP.put(Integer.valueOf(argb), token);
    }

    private static void putText(int argb, String token) {
        TEXT_MAP.put(Integer.valueOf(argb), token);
    }

    private ThemeRecolor() {
    }

    /** View 原始外观快照，用于回默认态还原。只存标量/引用，不改原始 Drawable 本体。 */
    static final class BaseStyle {
        Integer bgColor;             // 取样得到的原始纯色；null = 透明或非纯色，跳过
        ColorStateList textColors;   // 原始文字色（含 pressed/disabled 状态）
        ColorStateList hintColors;   // 输入框 placeholder 原色，回退时用
        Drawable origSelector;       // send_button 这类被整体替换的 selector，回退时用
        Drawable origDivider;        // ListView 分割线原件，回退时用
        Drawable origInputBg;        // EditText 被换成深色圆角底之前的原件
        Drawable origBg;             // 系统按钮 / Spinner 的 9-patch 底被换成主题底之前的原件
        /**
         * 当前挂着的底是本类换上的（不是 layout 原件）。
         *
         * <p>必须显式记，不能靠「采样到的颜色深不深」反推：换过主题之后底是深色，
         * 采样会判定「已经是深色，不用动」，于是换主题时按钮颜色永远停在上一个主题上。
         */
        boolean themedBg;
        /** 换上时的主题签名（明暗 + 表面色 + 描边色）；不同就重建。 */
        String themedSig;
        // ---- 系统 tint 控件（SeekBar / ProgressBar / Switch / CheckBox / RadioButton）----
        // 这五类在 API 18 上没有 setXxxTintList，颜色由 drawable 自带（Holo 默认蓝）。
        // 只能换 progressDrawable / thumb / buttonDrawable / trackDrawable 并套色。
        Drawable origProgress;
        Drawable origIndeterminate;
        Drawable origSeekThumb;
        Drawable origCompoundBtn;
        /** 已套色时的「主色|轨道色|明暗」签名；相同就跳过，避免每帧重建 drawable。 */
        String tintSig;
        boolean sysTinted;
        boolean captured;
        /**
         * 背景采样缓存：记「从哪个 drawable 实例采到的是什么色」。
         *
         * <p>9-patch / selector 这类背景没有 {@code getColor()}，只能缩到位图上取
         * 中心像素（9x9 采不到再 48x48），一次 two 次 draw + 两次 {@code getPixel}
         * （加锁 JNI）。而列表 bind、对话框重着色都会对同一个 View 反复调 apply，
         * 背景实例没变却每次都重采——快速滑动时就是每秒几百次位图绘制。
         *
         * <p>只按<b>实例相等</b>命中：{@link #paintBackground} 换底后实例变了，
         * 缓存自然失效，不需要任何手动清理。
         */
        Drawable sampledFrom;
        Integer sampledValue;
        boolean sampled;
        /** 上一次由本类染上的 GradientDrawable 与颜色，同色同实例时跳过 setBackgroundDrawable。 */
        Drawable paintedDrawable;
        int lastPaintedColor = Integer.MIN_VALUE;
        /** 上一次设置的 ListView 分割线 / Spinner 浮层底色，相同就跳过（setDivider 会 requestLayout）。 */
        int lastDividerColor = Integer.MIN_VALUE;
        int lastPopupColor = Integer.MIN_VALUE;
    }

    /**
     * inflate 期即时着色。
     *
     * <p>为什么必须有这条路径：{@link ThemeApplier#apply} 只在 setContentView / onResume
     * 遍历一次 View 树。而下列 View 在那之后才存在，永远刷不到 ——
     * <ul>
     *   <li>ListView / RecyclerView 的 item（历史、存储、附件列表都是异步填充）</li>
     *   <li>Spinner 下拉项（独立 PopupWindow）</li>
     *   <li>AlertDialog 的内容（独立 Window）</li>
     * </ul>
     * 这就是实机上「历史页白底黑字」「下拉框白底黑字」的直接原因。
     * 由 {@link ThemedActivity} 装的 LayoutInflater.Factory2 在每个 View 创建后调用。
     */
    static void applyTree(View v, android.content.Context ctx) {
        if (v == null || ctx == null) return;
        AppearanceManager m = AppearanceManager.getInstance(ctx);
        boolean def = m.isAppearanceDefault();
        // 默认态且从未定制过：新 View 保持 layout 原色，零回归。
        if (def && !ThemeApplier.everCustomized()) return;
        walkTree(v, m, m.isDark(), def);
    }

    /**
     * Adapter / 动态 row 的着色入口。
     *
     * <p>这些 View 由 {@code LayoutInflater.from(ctx)} 或 {@code new TextView(ctx)} 创建，
     * <b>不在 Activity 的 inflater 上</b>，所以 ThemedActivity 的 Factory2 钩子够不到。
     * 必须在每次 {@code getView} 末尾主动调一次：既覆盖首次创建，也覆盖 convertView
     * 复用（否则会残留上一个主题的颜色）。
     */
    static void applyTo(View v) {
        if (v == null) return;
        try {
            applyTree(v, v.getContext());
        } catch (Throwable t) {
            LogUtil.w("ThemeRecolor", "applyTo failed: " + t.getMessage());
        }
    }

    /**
     * 列表 item 的常规底色。
     *
     * <p>历史、已保存文件、附件三个列表都在 {@code getView} 里写死
     * {@code setBackgroundColor(0xFFFFFFFF)}，每次 bind 都会把主题色盖回白色——
     * 这是实机上「列表背景还是白色」的直接原因，光靠 inflate 钩子修不掉。
     * 颜色仍处内置默认时返回原值，保证零回归。
     */
    static int listBg(android.content.Context ctx) {
        AppearanceManager m = AppearanceManager.getInstance(ctx);
        if (m.isColorDefault()) return 0xFFFFFFFF;
        return m.getEffectiveColor(ThemeTokens.AC_SURFACE, m.isDark());
    }

    /**
     * 列表 item 的选中/高亮底色。
     *
     * @param fallback layout 原来的硬编码色（选中 {@code #FFF3CD}、高亮 {@code #E3F2FD}），
     *                 颜色未定制时原样返回
     */
    static int listMarkBg(android.content.Context ctx, int fallback) {
        AppearanceManager m = AppearanceManager.getInstance(ctx);
        if (m.isColorDefault()) return fallback;
        return m.getEffectiveColor(ThemeTokens.AC_BUBBLE_USER, m.isDark());
    }

    private static void walkTree(View v, AppearanceManager m, boolean dark, boolean def) {
        if (v == null || v instanceof android.webkit.WebView) return;
        if (Boolean.TRUE.equals(v.getTag(R.id.ac_no_recolor))) return;
        apply(v, m, dark, def);
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                walkTree(g.getChildAt(i), m, dark, def);
            }
        }
    }

    /**
     * 对单个 View 应用/还原重着色。
     *
     * @param def true 表示回到内置默认外观，此时只做还原
     */
    static void apply(View v, AppearanceManager m, boolean dark, boolean def) {
        if (v == null || m == null || v instanceof android.webkit.WebView) return;
        if (Boolean.TRUE.equals(v.getTag(R.id.ac_no_recolor))) return;

        BaseStyle bs = (BaseStyle) v.getTag(R.id.ac_base_style);
        if (bs == null) {
            bs = capture(v);
            v.setTag(R.id.ac_base_style, bs);
        }
        if (def) {
            restore(v, bs);
            applyPopup(v, bs, m, dark, true);
            applySystemTint(v, bs, m, dark, true);
            return;
        }

        // 1) 精确 token 通道优先（保留 E2 的扩展能力），命中即完全接管颜色
        String token = tokenTagOf(v);
        if (token != null && isTextToken(token) && v instanceof TextView) {
            int c = keepOriginal(m, token, dark) && bs.textColors != null
                    ? bs.textColors.getDefaultColor()
                    : m.getEffectiveColor(token, dark);
            ((TextView) v).setTextColor(c);
            return;
        }
        if (token != null) {
            int c = keepOriginal(m, token, dark) && bs.bgColor != null
                    ? bs.bgColor.intValue()
                    : m.getEffectiveColor(token, dark);
            // 刷得动就结束；刷不动（9-patch / selector）必须继续往下走，
            // 交给第 3 步的系统控件换底。早期这里无条件 return，
            // 结果是「打了 token 的按钮」深色下原样保留浅底。
            if (paintBackground(v, bs, c)) return;
        }

        // 2) 背景定色 + 3) 文字定色，顺序不能反。
        //
        //    反相兜底必须以「这块底真的变深了」为前提。早期两件事各走各的，遇到
        //    刷不动的系统 9-patch 底（默认按钮、Spinner、对话框里的 EditText）就只剩
        //    文字被刷白、底还是浅灰 —— 用户看到的「按钮字是白的、底却是浅的」。
        boolean bgDark = resolveBackground(v, bs, m, dark);

        if (v instanceof TextView) {
            if (isAlertDialogTitle(v)) {
                // AlertDialog 的标题必须保持对话框主题自带的颜色。
                // 底板是 9-patch，sampleSolidColor 拿不到纯色、paintBackground 也改不动，
                // 若只把标题刷成 ac_text，标题与底板就会各走一套配色：
                // 浅色 Holo Light 底板 + 自定义主题的浅色文字 = 白底白字。
                // 反过来保留主题原色，深色底板配白字、浅色底板配黑字，永远成对。
                if (bs.textColors != null) {
                    ((TextView) v).setTextColor(bs.textColors);
                }
            } else {
                applyTextColorByMap((TextView) v, bs, m, dark, bgDark);
                applyInputHint((TextView) v, bs, m, dark);
            }
        }
        applyPopup(v, bs, m, dark, false);
        applyDivider(v, bs, m, dark, false);
        // 4) 系统 tint 控件：进度条 / 拖动条 / 开关 / 单选 / 复选
        //    这几类的颜色来自各自 drawable，setBackground 那套完全覆盖不到，
        //    深色下就会留一圈 Holo 蓝。放在最后一步统一处理。
        applySystemTint(v, bs, m, dark, false);
    }

    /**
     * 把背景刷成主题色，并回答一个必须回答的问题：<b>这块底现在是深色吗</b>。
     *
     * <p>文字要不要反相、输入框文字能不能用浅色，全看这个返回值。返回 false 时
     * 调用方必须保留原文字色，否则就是浅底白字。
     *
     * <p>之所以要单独成方法：layout 里绝大多数按钮 / Spinner 根本没有
     * {@code android:background}，用的是 Holo 自带的 9-patch + StateListDrawable。
     * 这类 drawable 既不能 setColor（API 18 读不回来），也不能只改其中一层，
     * 只能整体换成主题底 —— 换完之后必须重新算底色，不能沿用采样值。
     */
    private static boolean resolveBackground(View v, BaseStyle bs, AppearanceManager m, boolean dark) {
        // 0) 深色时被整体替换过的系统控件底，回到浅色必须先还原。
        //    注意这里不能用 restore()：restore 是「回内置默认」的整份还原，
        //    而自定义浅色主题下依然要保留主题自己刷的颜色。
        if (!dark && bs.origBg != null) {
            try {
                v.setBackgroundDrawable(bs.origBg);
            } catch (Throwable ignored) {
            }
            bs.origBg = null;
        }

        // 1) 输入框：单独一套规则（新建 drawable，绝不原地改资源）
        if (v instanceof android.widget.EditText) {
            Boolean r = applyInputBackground(v, bs, m, dark);
            if (r != null) return r.booleanValue();
            Integer cur = sampledBg(v, bs);
            return cur == null ? dark : isDarkColor(cur.intValue());
        }

        // 2) 纯色 / 形状 drawable：按原始色反查 token
        Boolean painted = null;
        if (bs.bgColor != null) {
            String bgToken = BG_MAP.get(bs.bgColor);
            if (bgToken != null) {
                int c = keepOriginal(m, bgToken, dark)
                        ? bs.bgColor.intValue()
                        : m.getEffectiveColor(bgToken, dark);
                if (paintBackground(v, bs, c)) painted = Boolean.valueOf(isDarkColor(c));
            }
        }

        if (painted != null) return painted.booleanValue();

        // 3) 系统控件底（9-patch / selector）：按控件类型整体换主题底。
        //
        //    这里刻意不拿「采样到浅色」当触发条件。Holo 的 btn_default 是
        //    9-patch 套 StateListDrawable，缩到 9x9 绘制时中心有可能落在透明
        //    像素上，sampleSolidColor 返回 null；旧代码把 null 当作「不用动」，
        //    结果深色下按钮永远是浅底 + 已经刷白的字。采样只用来排除「本来就是
        //    深色的系统控件」和「真透明浮层按钮」，不再决定要不要换。
        if (isThemedControlHost(v) && paintThemedControlBg(v, bs, m, dark)) return dark;

        // 4) 其余按实际采样判断；透明背景跟随窗口
        Integer cur = sampledBg(v, bs);
        return cur == null ? dark : isDarkColor(cur.intValue());
    }

    /** 背景来自系统主题、需要整体替换的控件类型。 */
    private static boolean isThemedControlHost(View v) {
        if (v instanceof android.widget.Spinner) return true;
        return v instanceof android.widget.Button && !(v instanceof android.widget.CompoundButton);
    }

    /**
     * 给「背景是系统 9-patch / selector」的按钮与下拉框换一套主题底。
     *
     * <p>为什么必须换：Holo 的 {@code btn_default}、{@code spinner_background_holo_light}
     * 是九宫格 + 状态列表，API 18 既读不回原色也没法只改填充。不换的后果是深色模式下
     * 一片浅色块，而文字已经被刷成白色 —— 全部不可读。
     *
     * <p>按钮换成圆角实色 + 描边（保留按下 / 禁用态）；下拉框换成圆角实色 + 右侧三角，
     * 三角必须自己画：原 9-patch 的箭头跟着底一起被替换掉了，不补一个用户就不知道能点。
     *
     * @return true 表示已换成深色主题底
     */
    private static boolean paintThemedControlBg(View v, BaseStyle bs, AppearanceManager m,
                                                boolean dark) {
        try {
            // 浅色：把深色时换掉的系统底整套还原（圆角、箭头、按下态都在原件里）
            if (!dark) {
                if (bs.themedBg) {
                    if (bs.origBg != null) {
                        try {
                            v.setBackgroundDrawable(bs.origBg);
                        } catch (Throwable ignored) {
                        }
                    }
                    bs.themedBg = false;
                    bs.themedSig = null;
                    bs.origBg = null;
                }
                return false;
            }

            String sig = darkSig(m);
            // 已经是本类换上、且是当前主题的底：不重建，避免每次 onResume 都产生垃圾
            if (bs.themedBg && sig.equals(bs.themedSig)) return true;

            Drawable bg = v.getBackground();
            // 没有背景：换成实色底之后浅色模式没有「原件」可还原（origBg 为 null），
            // 会永久留一块深色，比不换更糟。
            if (bg == null) return false;
            // 真透明（图片预览浮层上的「取消 / 完成」）：换成实色会把整页盖住，绝不能碰
            if (bg instanceof ColorDrawable
                    && Color.alpha(((ColorDrawable) bg).getColor()) < 200) return false;
            // 系统深色控件（Holo Dark 对话框里的按钮）：本来就对，重画只是丢样式。
            // 注意带上 !bs.themedBg：换主题时底已经是深色，不能因此判定「不用动」。
            Integer cur = sampleSolidColor(bg);
            if (cur != null && !isLightColor(cur.intValue()) && !bs.themedBg) return false;

            if (bs.origBg == null) bs.origBg = bg;
            float d = v.getResources().getDisplayMetrics().density;
            if (v instanceof android.widget.Spinner) {
                int l = v.getPaddingLeft(), t = v.getPaddingTop(), b = v.getPaddingBottom();
                int extra = (int) (18 * d);
                setBackgroundKeepPadding(v, new SpinnerBg(m, d, l, t, b));
                v.setPadding(l, t, Math.max(v.getPaddingRight(), extra), b);
            } else {
                setBackgroundKeepPadding(v, stateRounded(
                        m.getEffectiveColor(ThemeTokens.AC_SURFACE, true),
                        m.getEffectiveColor(ThemeTokens.AC_BORDER, true), 4 * d, d));
            }
            bs.themedBg = true;
            bs.themedSig = sig;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 深色控件底的主题签名：表面色 + 描边色，任一变化都必须重建。 */
    private static String darkSig(AppearanceManager m) {
        return Integer.toHexString(m.getEffectiveColor(ThemeTokens.AC_SURFACE, true)) + "/"
                + Integer.toHexString(m.getEffectiveColor(ThemeTokens.AC_BORDER, true));
    }

    /** {@code setBackgroundDrawable} 会用新 drawable 的 padding 覆盖 View 内边距，先存后还原。 */
    private static void setBackgroundKeepPadding(View v, Drawable d) {
        int l = v.getPaddingLeft(), t = v.getPaddingTop();
        int r = v.getPaddingRight(), b = v.getPaddingBottom();
        v.setBackgroundDrawable(d);
        v.setPadding(l, t, r, b);
    }

    private static Drawable stateRounded(int color, int border, float radius, float d) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, rounded(shade(color, 1.22f), border, radius, d));
        s.addState(new int[]{-android.R.attr.state_enabled}, rounded(shade(color, 0.7f), border, radius, d));
        s.addState(new int[]{}, rounded(color, border, radius, d));
        return s;
    }

    private static GradientDrawable rounded(int color, int border, float radius, float d) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        g.setStroke(Math.max(1, (int) d), border);
        return g;
    }

    /**
     * ListView 分割线：layout 里写的是 {@code @color/gray}(#CCCCCC)，深色底上会变成
     * 一条发亮的白线，把本来就窄的列表切得很碎。改成 {@code ac_divider}。
     *
     * <p>分割线高度不动，只换 drawable；原件在 capture 时存进快照，回退默认原样还原。
     */
    private static void applyDivider(View v, BaseStyle bs, AppearanceManager m,
                                     boolean dark, boolean def) {
        if (!(v instanceof android.widget.ListView)) return;
        try {
            android.widget.ListView lv = (android.widget.ListView) v;
            if (def) {
                // 只在真的换过的时候还原：setDivider 内部是 requestLayout + invalidate，
                // 每次 onResume 都白做一次等于整表重新 measure。
                if (bs.lastDividerColor != Integer.MIN_VALUE) {
                    lv.setDivider(bs.origDivider);
                    bs.lastDividerColor = Integer.MIN_VALUE;
                }
            } else {
                int c = m.getEffectiveColor(ThemeTokens.AC_DIVIDER, dark);
                if (c == bs.lastDividerColor) return;
                bs.lastDividerColor = c;
                lv.setDivider(new ColorDrawable(c));
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Spinner 的下拉浮层是独立 PopupWindow，背景默认取 {@code android:popupBackground}
     * （Holo 浅色下就是纯白），它的 item 虽然会被 inflate 期着色，但浮层底板仍会露出一片白。
     * {@code setPopupBackgroundDrawable} 在 API 16+ 可用，minSdk 18 安全。
     */
    private static void applyPopup(View v, BaseStyle bs, AppearanceManager m, boolean dark, boolean def) {
        if (!(v instanceof android.widget.Spinner)) return;
        try {
            android.widget.Spinner sp = (android.widget.Spinner) v;
            if (def) {
                if (bs.lastPopupColor != Integer.MIN_VALUE) {
                    sp.setPopupBackgroundDrawable(null);
                    bs.lastPopupColor = Integer.MIN_VALUE;
                }
            } else {
                int c = m.getEffectiveColor(ThemeTokens.AC_SURFACE, dark);
                if (c == bs.lastPopupColor) return;
                bs.lastPopupColor = c;
                sp.setPopupBackgroundDrawable(new android.graphics.drawable.ColorDrawable(c));
            }
        } catch (Throwable t) {
            // 个别 ROM 对 popupBackground 有自定义处理，失败不影响主体
        }
    }

    /**
     * 该 token 是否应<b>保持 View 原始色</b>：仅当来源是内置默认<b>且当前为浅色</b>。
     *
     * <p>判据必须按 token 粒度、并与 WebView 侧 {@code ThemeCssBuilder.hasColorOverride}
     * 保持一致：
     * <ul>
     *   <li>深色 → 必须刷（内置暗色与 layout 原始浅色不同，这是图 1 的修复本体）</li>
     *   <li>主题 / 显式覆盖 → 必须刷</li>
     *   <li>浅色 + 来源 BUILTIN → 保持原始色。三层覆盖里「未覆盖 = 用内置默认」，
     *       而浅色内置默认就是 layout 里那个原始色；若强行按 token 拉齐，
     *       {@code #F0F0F0}/{@code #FAFAFA}/{@code #FFF8E1}/{@code #90CAF9}
     *       会变成纯白/主蓝 —— 只改显示大小时肉眼可见的回归。</li>
     * </ul>
     * 按 token 粒度而不是全局开关，是为了「主题只定义了主色、没定义表面色」时，
     * 表面色仍保持原始浅灰，不会因为选了主题就被整体拉白。
     */
    private static boolean keepOriginal(AppearanceManager m, String token, boolean dark) {
        if (dark) return false;
        return m.resolve(token, dark).source == ThemeResolver.SOURCE_BUILTIN;
    }

    private static void applyTextColorByMap(TextView tv, BaseStyle bs, AppearanceManager m,
                                            boolean dark, boolean bgDark) {
        // 输入框不参与「按色反查」：EditText 的默认文字色来自它<b>构造时那个 Context</b> 的主题，
        // 对话框里的输入框普遍写成 new EditText(activity)，而 activity 是浅色主题 ——
        // View 一出生就带黑色，再被塞进深色 dialog 就是深底黑字。可读性不该赌启发式命中。
        if (tv instanceof android.widget.EditText) {
            tv.setTextColor(m.getEffectiveColor(ThemeTokens.AC_TEXT, dark));
            return;
        }
        if (bs.textColors == null) return;
        int base = bs.textColors.getDefaultColor();
        String token = TEXT_MAP.get(Integer.valueOf(base));
        if (token != null) {
            // 保持原始色时写回 base：既保证零回归，也能把之前刷过的颜色还原回来
            tv.setTextColor(keepOriginal(m, token, dark) ? base : m.getEffectiveColor(token, dark));
            return;
        }
        // 兜底：深色模式下，未命中映射的深色文字反相成 ac_text(dark)。
        // 【前提】背景必须真的已经是深色。BG_MAP 只覆盖十几个硬编码色，
        // 系统按钮 / Spinner / 自绘 9-patch 的底色全都查不到，此时背景原样不动，
        // 再把文字刷白就是「浅底白字」—— 比不刷还糟。
        if (dark && bgDark && isDarkColor(base) && Color.alpha(base) > 64) {
            tv.setTextColor(m.getEffectiveColor(ThemeTokens.AC_TEXT, true));
        }
    }

    /**
     * 输入框的 hint（placeholder）跟随次要文字色。
     *
     * <p>hint 此前完全没人管：{@code setHintTextColor} 不会随背景变深而自动跟随，
     * 深色下留的是浅色主题里的深灰，几乎看不见——「输入框里那句提示没了」就是这个。
     */
    private static void applyInputHint(TextView tv, BaseStyle bs, AppearanceManager m,
                                       boolean dark) {
        if (!(tv instanceof android.widget.EditText) || bs.hintColors == null) return;
        try {
            tv.setHintTextColor(m.getEffectiveColor(ThemeTokens.AC_TEXT_SECONDARY, dark));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 把输入框的底换成主题色，并返回<b>换完之后这块底是不是深色</b>。
     *
     * <p>为什么必须做：对话框里的 EditText 普遍是 {@code new EditText(activity)}，
     * activity 是 Holo Light，背景取的是白色 9-patch。文字已经按主题刷成浅色，
     * 底却还是白的 —— 结果是「白框 + 白字」，比原来的黑字更难看清。
     *
     * <p>为什么不在 {@link #paintBackground} 里一起做：那个方法对 GradientDrawable
     * 是<b>原地 mutate + setColor</b>，而 {@code edit_text_background} 是资源 drawable，
     * 全进程共享同一份 GradientState。原地改的后果是所有输入框一起变色，且快照里
     * 存的「原件」跟着变脏 —— 切回浅色时还原出来的还是深色。这里一律<b>新建</b>
     * 一个 GradientDrawable，原件只在必要时整份换回。
     *
     * @return null 表示没接管（透明底 / 深色自绘底），调用方按实际采样判断；
     *         TRUE / FALSE 表示当前底色是深还是浅
     */
    private static Boolean applyInputBackground(View v, BaseStyle bs, AppearanceManager m, boolean dark) {
        if (!(v instanceof android.widget.EditText)) return null;
        float density = v.getResources().getDisplayMetrics().density;
        Drawable cur = v.getBackground();
        // 完全没有背景的输入框（透明，只有光标）：换底之后浅色模式没有原件可还原，
        // 会永久留一块深色。直接不接管。
        if (cur == null) return null;
        // 现在挂着的还是不是原始那个 drawable —— 用来区分「原件」和「我们自己换上的」
        boolean ours = bs.origInputBg != null && cur != bs.origInputBg;

        // 浅色 + 内置默认外观：什么都不做，零回归。
        // 浅色 + 自定义主题：表面色可能不是白色，仍需跟随，走下面的替换。
        boolean needThemed = dark || !m.isColorDefault();
        if (!needThemed) {
            if (ours) {
                try {
                    v.setBackgroundDrawable(bs.origInputBg);
                } catch (Throwable ignored) {
                }
                bs.origInputBg = null;
            }
            return Boolean.FALSE;
        }

        // 已经是我们换上的那份：原地改颜色即可（这份 state 只属于当前 View，安全）
        if (ours && cur instanceof GradientDrawable) {
            GradientDrawable g = (GradientDrawable) cur;
            g.setColor(m.getEffectiveColor(ThemeTokens.AC_SURFACE, dark));
            g.setStroke(Math.max(1, (int) density), m.getEffectiveColor(ThemeTokens.AC_BORDER, dark));
            return dark ? Boolean.TRUE : Boolean.FALSE;
        }

        // 深色自绘底 / 透明底不动，避免破坏原有视觉。
        //
        // 采样失败不再一律放弃：Holo 的 edit_text 背景是 9-patch 套 selector，
        // 缩到 9x9 时中心可能落在透明像素上，采样返回 null。旧代码把 null 当成
        // 「不用动」，深色下输入框就一直是白底；文字却按 EditText 规则刷成浅色，
        // 白底白字。用类型区分：只有 ColorDrawable（真纯色、含透明）才可能是
        // 「只有下划线的输入框」，其余 drawable 一律按职责换底。
        Integer sampled = sampleSolidColor(cur);
        if (sampled == null && cur instanceof ColorDrawable) return null;
        if (sampled != null && !isLightColor(sampled.intValue())) {
            return dark ? Boolean.TRUE : Boolean.FALSE;
        }

        if (bs.origInputBg == null) bs.origInputBg = cur;
        GradientDrawable g = new GradientDrawable();
        g.setColor(m.getEffectiveColor(ThemeTokens.AC_SURFACE, dark));
        g.setCornerRadius(8 * density);
        g.setStroke(Math.max(1, (int) density), m.getEffectiveColor(ThemeTokens.AC_BORDER, dark));

        // setBackgroundDrawable 会用新 drawable 的 padding 覆盖 View 的既有内边距，
        // 先存后还原，避免文字贴边、行高跳动。
        int l = v.getPaddingLeft(), t = v.getPaddingTop();
        int r = v.getPaddingRight(), b = v.getPaddingBottom();
        v.setBackgroundDrawable(g);
        v.setPadding(l, t, r, b);
        return dark ? Boolean.TRUE : Boolean.FALSE;
    }

    private static boolean isLightColor(int color) {
        int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
        return (r * 299 + g * 587 + b * 114) / 1000 >= 160;
    }

    /** {@code android.R.id.alertTitle} 是隐藏字段，只能运行时解析；-2 表示尚未解析。 */
    private static int alertTitleId = -2;

    /**
     * 是否是 AlertDialog 的标题。
     *
     * <p>只认 {@code alertTitle}，不认通用的 {@code android.R.id.title} ——
     * 后者在普通布局里也会被使用，误判会让那些文字在深色下保持原色（黑字）。
     */
    private static boolean isAlertDialogTitle(View v) {
        int id = v.getId();
        if (id == View.NO_ID) return false;
        if (alertTitleId == -2) {
            try {
                alertTitleId = v.getResources().getIdentifier("alertTitle", "id", "android");
            } catch (Throwable t) {
                alertTitleId = 0;
            }
        }
        return alertTitleId > 0 && id == alertTitleId;
    }

    private static BaseStyle capture(View v) {
        BaseStyle bs = new BaseStyle();
        // 顺手把这次采样的结果填进缓存：capture 之后紧接着就会走 apply，
        // 同一个背景实例没必要再 draw 一遍位图。
        Drawable bg = v.getBackground();
        bs.sampledFrom = bg;
        bs.sampledValue = sampleSolidColor(bg);
        bs.sampled = true;
        bs.bgColor = bs.sampledValue;
        if (v instanceof TextView) {
            try {
                bs.textColors = ((TextView) v).getTextColors();
            } catch (Throwable ignored) {
            }
            if (v instanceof android.widget.EditText) {
                try {
                    bs.hintColors = ((android.widget.EditText) v).getHintTextColors();
                } catch (Throwable ignored) {
                }
                try {
                    bs.origInputBg = v.getBackground();
                } catch (Throwable ignored) {
                }
            }
        }
        if (v instanceof android.widget.ListView) {
            try {
                bs.origDivider = ((android.widget.ListView) v).getDivider();
            } catch (Throwable ignored) {
            }
        }
        bs.captured = true;
        return bs;
    }

    private static void restore(View v, BaseStyle bs) {
        // 控件底标记先清：下面的 origBg 分支会把它整套换回去，留着标记会让下一次
        // 深色遍历误以为「已经换过、签名也一致」，从而跳过重建。
        bs.themedBg = false;
        bs.themedSig = null;
        // 同色短路标记也必须清。GradientDrawable 那条还原路径是 mutate() 后 setColor，
        // 挂着的还是同一个实例：不清标记的话，默认 -> 切回同一套主题时
        // 「实例相同 + 颜色相同」会命中短路，底色永远停在原色上。
        bs.paintedDrawable = null;
        bs.lastPaintedColor = Integer.MIN_VALUE;
        if (bs.origSelector != null) {
            try {
                v.setBackgroundDrawable(bs.origSelector);
            } catch (Throwable ignored) {
            }
            bs.origSelector = null;
        } else if (bs.origBg != null) {
            // 系统按钮 / 下拉框的 9-patch 原件优先于下面的纯色分支：
            // 深色时它被整体换成了主题底，回浅色必须整套还原（圆角、箭头、按下态）。
            try {
                v.setBackgroundDrawable(bs.origBg);
            } catch (Throwable ignored) {
            }
            bs.origBg = null;
        } else if (bs.bgColor != null) {
            // 回默认同理：绝不 setColor 原件，否则会把共享 ConstantState 写成
            // 上一次的颜色，下一次 inflate 的 View 一出生就带着旧主题色
            Drawable d = v.getBackground();
            if (d instanceof ColorDrawable) {
                v.setBackgroundDrawable(new ColorDrawable(bs.bgColor.intValue()));
            } else if (d instanceof GradientDrawable) {
                Drawable own = d.mutate();
                ((GradientDrawable) own).setColor(bs.bgColor.intValue());
                if (own != d) v.setBackgroundDrawable(own);
            } else if (d != null) {
                try {
                    d.clearColorFilter();
                } catch (Throwable ignored) {
                }
            }
        }
        if (v instanceof TextView && bs.textColors != null) {
            ((TextView) v).setTextColor(bs.textColors);
        }
        if (v instanceof TextView && bs.hintColors != null) {
            ((TextView) v).setHintTextColor(bs.hintColors);
        }
        // 放在背景还原之后：EditText 的白色 9-patch 会被上面的纯色分支换成
        // ColorDrawable，这里再用原件覆盖回来，圆角/描边/内边距一并复原。
        if (v instanceof android.widget.EditText && bs.origInputBg != null) {
            try {
                v.setBackgroundDrawable(bs.origInputBg);
            } catch (Throwable ignored) {
            }
            bs.origInputBg = null;
        }
        if (v instanceof android.widget.ListView) {
            try {
                ((android.widget.ListView) v).setDivider(bs.origDivider);
            } catch (Throwable ignored) {
            }
            // 还原后必须清掉「已上色」标记：否则同一进程内再从默认切回
            // 同一套主题时，applyDivider 会因为颜色相等而跳过，分割线永远
            // 停在 layout 的灰色上（深色底上一条亮白线）。
            bs.lastDividerColor = Integer.MIN_VALUE;
        }
        restoreSysTint(v, bs);
    }

    /**
     * 系统 tint 控件按主题上色。
     *
     * <p><b>为什么单独一条通道</b>：{@code SeekBar} / {@code ProgressBar} / {@code Switch} /
     * {@code CheckBox} / {@code RadioButton} 的颜色不住在 {@code getBackground()} 里，而是
     * 各自的 {@code progressDrawable} / {@code thumb} / {@code trackDrawable} /
     * {@code buttonDrawable}。API 18 没有 {@code setXxxTintList}，这些 drawable 由 Holo 主题
     * 烘死成蓝色 —— 这就是「切了深色，进度条/开关还是亮的 Holo 蓝」的原因。
     *
     * <p><b>为什么用 ColorFilter 而不是自绘</b>：这些控件靠 drawable 的<b>图形</b>区分状态
     * （选中是实心勾、未选中是空框；轨道开/关 alpha 不同），{@code SRC_IN} 只替换色相、
     * 保留 alpha 与形状，状态区分自动成立。自绘一套要重新实现 thumb 位移、轨道动画，
     * 收益不抵风险。
     *
     * <p><b>零回归</b>：内置浅色默认外观下直接还原原件（原生就是蓝的，改了反而破坏一致性）。
     */
    private static void applySystemTint(View v, BaseStyle bs, AppearanceManager m,
                                        boolean dark, boolean def) {
        try {
            boolean isSysCtl = v instanceof android.widget.ProgressBar
                    || v instanceof android.widget.CompoundButton;
            if (!isSysCtl) return;

            // Switch 整体交还系统，不参与着色（含自定义主题）。
            //
            // Holo（API 14~20）的 Switch 是「轨道 + 带文字的滑块」两件套：
            // 「打开 / 关闭」两个字的底板是 thumb 里 9-patch 自带的实色矩形，
            // 与轨道各自独立绘制。只染轨道 → 轨道变椭圆主色、thumb 底板还是
            // 系统绿，比不染更难看；连 thumb 一起换 → 上面那层文字是 Switch
            // 用 mTextPaint 直绘的，换 9-patch 必然撞色。
            // 结论：这个控件在 4.x 上没有「只换颜色、不留残色」的合法改法，
            // 回退到系统默认。曾染过色的也要还原（见下）。
            if (v instanceof android.widget.Switch) {
                restoreSysTint(v, bs);
                return;
            }

            boolean nativeLook = def || m.isColorDefault();
            if (nativeLook) {
                restoreSysTint(v, bs);
                return;
            }
            int primary = m.getEffectiveColor(ThemeTokens.AC_PRIMARY, dark);
            int track = m.getEffectiveColor(ThemeTokens.AC_TRACK, dark);
            String sig = (dark ? "d" : "l") + ":" + Integer.toHexString(primary)
                    + ":" + Integer.toHexString(track);
            // 已按当前主题染过：跳过。少了这一步，每次 onResume 都会重建 drawable，
            // 老机器滚动列表时就是成片的临时对象。
            if (bs.sysTinted && sig.equals(bs.tintSig)) return;

            if (v instanceof android.widget.ProgressBar) {
                // SeekBar 也是 ProgressBar：进度通道两者通用
                tintProgress((android.widget.ProgressBar) v, bs, primary, track);
                if (v instanceof android.widget.SeekBar) {
                    tintSeekThumb((android.widget.SeekBar) v, bs, primary);
                }
            }
            if (v instanceof android.widget.CompoundButton) {
                tintCompound((android.widget.CompoundButton) v, bs, primary);
            }
            bs.sysTinted = true;
            bs.tintSig = sig;
        } catch (Throwable t) {
            // 某类 ROM 的 drawable 结构不同，单点失败不能影响整页刷色
            LogUtil.w("ThemeRecolor", "applySystemTint failed: " + t.getMessage());
        }
    }

    private static void tintProgress(android.widget.ProgressBar pb, BaseStyle bs,
                                     int primary, int track) {
        Drawable pd = pb.getProgressDrawable();
        if (pd != null) {
            if (bs.origProgress == null) bs.origProgress = pd;
            Drawable own = pd.mutate();
            if (own instanceof android.graphics.drawable.LayerDrawable) {
                // Holo 的 progress_horizontal 是「背景 + 进度 + 第二进度」三层。
                // 整体染色会把轨道也染成主色，主色进度条压在主色轨道上就分不清了。
                android.graphics.drawable.LayerDrawable ld =
                        (android.graphics.drawable.LayerDrawable) own;
                tintLayer(ld, android.R.id.background, track);
                tintLayer(ld, android.R.id.progress, primary);
                tintLayer(ld, android.R.id.secondaryProgress, primary);
            } else {
                own.setColorFilter(primary, PorterDuff.Mode.SRC_IN);
            }
            pb.setProgressDrawable(own);
        }
        Drawable ind = pb.getIndeterminateDrawable();
        if (ind != null) {
            // 不确定态是帧动画，只有一层，直接整体染主色
            if (bs.origIndeterminate == null) bs.origIndeterminate = ind;
            Drawable own = ind.mutate();
            own.setColorFilter(primary, PorterDuff.Mode.SRC_IN);
            pb.setIndeterminateDrawable(own);
        }
    }

    private static void tintLayer(android.graphics.drawable.LayerDrawable ld, int id, int color) {
        try {
            Drawable layer = ld.findDrawableByLayerId(id);
            if (layer != null) layer.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        } catch (Throwable ignored) {
        }
    }

    private static void tintSeekThumb(android.widget.SeekBar sb, BaseStyle bs, int primary) {
        try {
            // getThumb() 是 API 16+，minSdk 18 安全
            Drawable th = sb.getThumb();
            if (th == null) return;
            if (bs.origSeekThumb == null) bs.origSeekThumb = th;
            Drawable own = th.mutate();
            own.setColorFilter(primary, PorterDuff.Mode.SRC_IN);
            sb.setThumb(own);
        } catch (Throwable ignored) {
        }
    }

    private static void tintCompound(android.widget.CompoundButton cb, BaseStyle bs, int primary) {
        // getButtonDrawable() 是 API 23 才加的；4.4 上每次调用都抛
        // NoSuchMethodError 被下面 catch 吞掉，纯属白跑一趟还填异常栈。
        // 23+ 保持原有染色外观不变，4.4 直接跳过。
        if (android.os.Build.VERSION.SDK_INT < 23) return;
        try {
            Drawable bd = cb.getButtonDrawable();
            if (bd == null) return;
            if (bs.origCompoundBtn == null) bs.origCompoundBtn = bd;
            // 选中是实心勾、未选中是空框：形状本身区分状态，染色后依然分得清
            Drawable own = bd.mutate();
            own.setColorFilter(primary, PorterDuff.Mode.SRC_IN);
            cb.setButtonDrawable(own);
        } catch (Throwable ignored) {
        }
    }

    /** 回到内置默认外观：把五类控件的原件整套换回去。 */
    private static void restoreSysTint(View v, BaseStyle bs) {
        if (!bs.sysTinted && bs.tintSig == null
                && bs.origProgress == null && bs.origIndeterminate == null
                && bs.origSeekThumb == null && bs.origCompoundBtn == null) {
            return;   // 从未染过色，不做任何事（绝大多数 View 走这条）
        }
        try {
            if (v instanceof android.widget.ProgressBar) {
                android.widget.ProgressBar pb = (android.widget.ProgressBar) v;
                if (bs.origProgress != null) {
                    pb.setProgressDrawable(bs.origProgress);
                    bs.origProgress = null;
                }
                if (bs.origIndeterminate != null) {
                    pb.setIndeterminateDrawable(bs.origIndeterminate);
                    bs.origIndeterminate = null;
                }
                if (v instanceof android.widget.SeekBar && bs.origSeekThumb != null) {
                    ((android.widget.SeekBar) v).setThumb(bs.origSeekThumb);
                    bs.origSeekThumb = null;
                }
            }
            if (v instanceof android.widget.CompoundButton && bs.origCompoundBtn != null) {
                ((android.widget.CompoundButton) v).setButtonDrawable(bs.origCompoundBtn);
                bs.origCompoundBtn = null;
            }
        } catch (Throwable ignored) {
        }
        bs.sysTinted = false;
        bs.tintSig = null;
    }

    /**
     * 原地重刷背景色，<b>绝不替换 drawable 本体</b>：ColorDrawable / GradientDrawable
     * 直接 setColor 保留圆角与描边；selector（发送按钮）整体换成按主题色构建的 oval
     * selector 并把原件存进快照；其余未知类型一律跳过，避免破坏按压反馈、九宫格、
     * selectableItemBackground 等无法在 API 18 读回原状的 drawable。
     *
     * <p>这里刻意不去改 GradientDrawable 的 stroke：API 18 没有 getStroke 系列 getter，
     * 改了就再也还原不回去，属于明确回归。深色下留一道浅灰细边比丢样式安全得多。
     *
     * @return true 表示这个 View 的背景确实被改了；false 表示类型不支持、原样未动
     *         （调用方据此判断「底到底有没有变深」，不能盲目把文字刷白）
     */
    private static boolean paintBackground(View v, BaseStyle bs, int color) {
        Drawable d = v.getBackground();
        if (d instanceof ColorDrawable) {
            // 已经是目标色就不动。只改字号/显示大小时整棵树重刷一遍，颜色一个没变，
            // 但每次 setBackgroundDrawable 都会 requestLayout + invalidate(true)，
            // 拖一次显示大小滑条就是几百次整页 measure/layout（老机器上就是「整页抖」）。
            if (((ColorDrawable) d).getColor() == color) return true;
            // **必须换新实例，不能 setColor**：从资源取到的 ColorDrawable 之间共享
            // ConstantState，setColor 改的是那份共享状态。后果是同布局后续 inflate
            // 出来的 View 一出生就是「上次主题色」，capture() 采样到的也是它，
            // BG_MAP 查不到 → 永远刷不回来。表现就是
            // 「浅→深正常，深→浅不对，必须重进 APP」。
            v.setBackgroundDrawable(new ColorDrawable(color));
            return true;
        } else if (d instanceof GradientDrawable) {
            // 同色同实例：说明这层底就是上一轮本类染的，再刷只会白搭一次 requestLayout
            if (d == bs.paintedDrawable && bs.lastPaintedColor == color) return true;
            // GradientDrawable.setColor 同样会写共享的 GradientState；mutate() 先复制
            // 一份自己的状态，圆角/描边/Angle 一起带过来，既不丢样式也不污染他人。
            Drawable own = d.mutate();
            ((GradientDrawable) own).setColor(color);
            if (own != d) v.setBackgroundDrawable(own);
            bs.paintedDrawable = own;
            bs.lastPaintedColor = color;
            return true;
        } else if (v.getId() == R.id.send_button && d instanceof StateListDrawable) {
            if (bs.origSelector == null) bs.origSelector = d;
            v.setBackgroundDrawable(ovalSelector(v, color));
            return true;
        }
        // 其它类型（9-patch、自定义 selector）：这里不动，交给 resolveBackground 的
        // 「系统控件整体换底」分支处理，绝不在此处强行替换成实色矩形。
        return false;
    }

    private static StateListDrawable ovalSelector(View v, int primary) {
        int pressed = shade(primary, 0.82f);
        int disabled = blend(primary, 0xFFFFFFFF, 0.45f);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, oval(pressed));
        s.addState(new int[]{-android.R.attr.state_enabled}, oval(disabled));
        s.addState(new int[]{}, oval(primary));
        return s;
    }

    private static GradientDrawable oval(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    private static int shade(int color, float f) {
        return Color.rgb((int) (Color.red(color) * f), (int) (Color.green(color) * f),
                (int) (Color.blue(color) * f));
    }

    private static int blend(int a, int b, float ratio) {
        return Color.rgb((int) (Color.red(a) * (1 - ratio) + Color.red(b) * ratio),
                (int) (Color.green(a) * (1 - ratio) + Color.green(b) * ratio),
                (int) (Color.blue(a) * (1 - ratio) + Color.blue(b) * ratio));
    }

    /**
     * 带缓存的背景采样，见 {@link BaseStyle#sampledFrom}。
     *
     * <p>同一 drawable 实例的采样结果是纯函数，缓存不改变任何判定；
     * 换底/还原后实例变了，下一次自动重采。
     */
    private static Integer sampledBg(View v, BaseStyle bs) {
        Drawable d = v.getBackground();
        if (bs.sampled && d == bs.sampledFrom) return bs.sampledValue;
        Integer px = sampleSolidColor(d);
        bs.sampledFrom = d;
        bs.sampledValue = px;
        bs.sampled = true;
        return px;
    }

    /**
     * 取 Drawable 的原始纯色。不用反射、不用 getColor()（API 29 才有），
     * 画到 9x9 位图取中心像素，避开 GradientDrawable 的描边与圆角。
     * 透明/半透明背景（按压反馈、透明按钮）返回 null，本类一律不动。
     */
    static Integer sampleSolidColor(Drawable d) {
        if (d == null) return null;
        // 纯色快路径：列表行、被 setBackgroundColor 刷过的容器全是 ColorDrawable，
        // 直接读颜色即可。原实现为每一个这样的 View 分配一张位图 + 一个 Canvas，
        // 在 RecycleBin 里滚动时就是每秒几百次小对象分配，低端机上直接掉帧。
        if (d instanceof ColorDrawable) {
            int c = ((ColorDrawable) d).getColor();
            return Color.alpha(c) < 200 ? null : Integer.valueOf(c);
        }
        Integer px = sampleSolidColor(d, 9);
        // 9x9 对带描边 / 阴影 / padding 的 9-patch 太小：缩到这个尺寸时中心
        // 可能正好落在透明像素上，采不到颜色。放大到 48x48 再试一次，
        // 命中率明显提高，也让「系统按钮 / Spinner 是不是浅底」的判断不再靠猜。
        if (px != null) return px;
        return sampleSolidColor(d, 48);
    }

    // 采样只在主线程串行执行，9/48 两张位图可静态复用：原来每次
    // createBitmap + recycle，一轮整树遍历就是上百次位图分配。
    // 静态位图不能 recycle（recycle 后再 draw 必抛），改在每次 draw 前擦净。
    private static final Bitmap SAMPLE_9 =
            Bitmap.createBitmap(9, 9, Bitmap.Config.ARGB_8888);
    private static final Bitmap SAMPLE_48 =
            Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888);
    private static final Canvas SAMPLE_C9 = new Canvas(SAMPLE_9);
    private static final Canvas SAMPLE_C48 = new Canvas(SAMPLE_48);

    private static synchronized Integer sampleSolidColor(Drawable d, int size) {
        if (d == null) return null;
        if (size != 9 && size != 48) return null;
        Bitmap b = size == 48 ? SAMPLE_48 : SAMPLE_9;
        Canvas c = size == 48 ? SAMPLE_C48 : SAMPLE_C9;
        Rect old = new Rect(d.getBounds());
        try {
            b.eraseColor(0);
            d.setBounds(0, 0, size, size);
            d.draw(c);
            int px = b.getPixel(size / 2, size / 2);
            if (Color.alpha(px) < 200) return null;
            return Integer.valueOf(px);
        } catch (Throwable t) {
            return null;
        } finally {
            // 某些 ROM 的 9-patch draw() 会抛异常：不还原 bounds 的话
            // 这个 background 之后就只在左上角画一小块，偶现且极难查。
            d.setBounds(old);
        }
    }

    /** 与 ThemeApplier 共用：读带 key 的 token tag，支持 {@code ac_primary|bg} 形式。 */
    static String tokenTagOf(View v) {
        Object tag = v.getTag(R.id.ac_token_tag);
        if (!(tag instanceof String)) return null;
        String s = (String) tag;
        if ("mono".equals(s)) return null;
        int bar = s.indexOf('|');
        if (bar > 0) s = s.substring(0, bar);
        return ThemeTokens.isValid(s) ? s : null;
    }

    private static boolean isTextToken(String token) {
        return ThemeTokens.AC_TEXT.equals(token)
                || ThemeTokens.AC_TEXT_SECONDARY.equals(token)
                || ThemeTokens.AC_CODE_TEXT.equals(token)
                || ThemeTokens.AC_LINK.equals(token)
                || ThemeTokens.AC_DANGER.equals(token);
    }

    private static boolean isDarkColor(int color) {
        int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
        return (r * 299 + g * 587 + b * 114) / 1000 < 128;
    }

    /**
     * 深色模式下的下拉框底板：圆角实色 + 细描边 + 右侧下拉三角。
     *
     * <p>为什么不用 XML：原 9-patch 里的箭头会随底一起被换掉，用户就看不出这里能点。
     * gradientDrawable 只能画一个形状，{@code LayerDrawable} 在 API 23 以下又不支持
     * 逐层 gravity，所以直接自绘：一个 drawable、两笔绘制，几行代码比堆 XML 更可控。
     *
     * <p>{@link #getPadding(Rect)} 返回右侧留白，保证文字不会压到三角上。
     */
    private static final class SpinnerBg extends Drawable {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Path arrow = new Path();
        private final int surface, border, arrowColor;
        private final float stroke, radius, arrowW, arrowH, inset;
        /** 原 View 的左/上/下内边距：不保留的话文字会贴到边框上。 */
        private final int padL, padT, padB;

        SpinnerBg(AppearanceManager m, float d, int padL, int padT, int padB) {
            this.surface = m.getEffectiveColor(ThemeTokens.AC_SURFACE, true);
            this.border = m.getEffectiveColor(ThemeTokens.AC_BORDER, true);
            this.arrowColor = m.getEffectiveColor(ThemeTokens.AC_TEXT_SECONDARY, true);
            this.stroke = Math.max(1, (int) d);
            this.radius = 4 * d;
            this.arrowW = 10 * d;
            this.arrowH = 5 * d;
            this.inset = 8 * d;
            this.padL = padL;
            this.padT = padT;
            this.padB = padB;
        }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            rect.set(b.left + stroke / 2f, b.top + stroke / 2f,
                    b.right - stroke / 2f, b.bottom - stroke / 2f);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(surface);
            c.drawRoundRect(rect, radius, radius, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setColor(border);
            c.drawRoundRect(rect, radius, radius, paint);

            float cx = b.right - inset - arrowW / 2f;
            float cy = b.centerY();
            arrow.reset();
            arrow.moveTo(cx - arrowW / 2f, cy - arrowH / 2f);
            arrow.lineTo(cx + arrowW / 2f, cy - arrowH / 2f);
            arrow.lineTo(cx, cy + arrowH / 2f);
            arrow.close();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(arrowColor);
            c.drawPath(arrow, paint);
        }

        @Override
        public boolean getPadding(Rect p) {
            // 左侧必须保留原 padding：早期这里写 0，深色下所有下拉框的文字
            // 都贴到边框上，与浅色时明显错位。
            p.set(padL, padT, (int) (inset + arrowW + inset / 2f), padB);
            return true;
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            // 必须是 TRANSLUCENT：这是圆角矩形，四角没有绘制内容。声明 OPAQUE
            // 会让部分 ROM 的绘制路径跳过混合，四个角变成黑块。
            return PixelFormat.TRANSLUCENT;
        }
    }
}
