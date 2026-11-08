package xyz.zip8919.app.aichat;

import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
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
        Drawable origSelector;       // send_button 这类被整体替换的 selector，回退时用
        Drawable origDivider;        // ListView 分割线原件，回退时用
        boolean captured;
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
        // 默认态且从未定制过：新 View 保持 layout 原色，零回归
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
            applyPopup(v, m, dark, true);
            return;
        }

        // 1) 精确 token 通道优先（保留 E2 的扩展能力），命中即完全接管颜色
        String token = tokenTagOf(v);
        if (token != null) {
            if (isTextToken(token) && v instanceof TextView) {
                int c = keepOriginal(m, token, dark) && bs.textColors != null
                        ? bs.textColors.getDefaultColor()
                        : m.getEffectiveColor(token, dark);
                ((TextView) v).setTextColor(c);
            } else {
                int c = keepOriginal(m, token, dark) && bs.bgColor != null
                        ? bs.bgColor.intValue()
                        : m.getEffectiveColor(token, dark);
                paintBackground(v, bs, c);
            }
            return;
        }

        // 2) 背景：按原始纯色反查
        if (bs.bgColor != null) {
            String bgToken = BG_MAP.get(bs.bgColor);
            if (bgToken != null) {
                int c = keepOriginal(m, bgToken, dark)
                        ? bs.bgColor.intValue()
                        : m.getEffectiveColor(bgToken, dark);
                paintBackground(v, bs, c);
            }
        }

        // 3) 文字：按原始色反查；未命中的深色字在深色模式下做可读性兜底
        if (v instanceof TextView) {
            applyTextColorByMap((TextView) v, bs, m, dark);
        }
        applyPopup(v, m, dark, false);
        applyDivider(v, bs, m, dark, false);
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
                lv.setDivider(bs.origDivider);
            } else {
                lv.setDivider(new ColorDrawable(m.getEffectiveColor(ThemeTokens.AC_DIVIDER, dark)));
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Spinner 的下拉浮层是独立 PopupWindow，背景默认取 {@code android:popupBackground}
     * （Holo 浅色下就是纯白），它的 item 虽然会被 inflate 期着色，但浮层底板仍会露出一片白。
     * {@code setPopupBackgroundDrawable} 在 API 16+ 可用，minSdk 18 安全。
     */
    private static void applyPopup(View v, AppearanceManager m, boolean dark, boolean def) {
        if (!(v instanceof android.widget.Spinner)) return;
        try {
            android.widget.Spinner sp = (android.widget.Spinner) v;
            if (def) {
                sp.setPopupBackgroundDrawable(null);
            } else {
                sp.setPopupBackgroundDrawable(
                        new android.graphics.drawable.ColorDrawable(
                                m.getEffectiveColor(ThemeTokens.AC_SURFACE, dark)));
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
                                            boolean dark) {
        if (bs.textColors == null) return;
        int base = bs.textColors.getDefaultColor();
        String token = TEXT_MAP.get(Integer.valueOf(base));
        if (token != null) {
            // 保持原始色时写回 base：既保证零回归，也能把之前刷过的颜色还原回来
            tv.setTextColor(keepOriginal(m, token, dark) ? base : m.getEffectiveColor(token, dark));
            return;
        }
        // 兜底：深色模式下，未命中映射的深色文字反相成 ac_text(dark)。
        // 关键前提：背景此时已由第 2 步同步刷深，不会再出现「浅底白字」。
        if (dark && isDarkColor(base) && Color.alpha(base) > 64) {
            tv.setTextColor(m.getEffectiveColor(ThemeTokens.AC_TEXT, true));
        }
    }

    private static BaseStyle capture(View v) {
        BaseStyle bs = new BaseStyle();
        bs.bgColor = sampleSolidColor(v.getBackground());
        if (v instanceof TextView) {
            try {
                bs.textColors = ((TextView) v).getTextColors();
            } catch (Throwable ignored) {
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
        if (bs.origSelector != null) {
            try {
                v.setBackgroundDrawable(bs.origSelector);
            } catch (Throwable ignored) {
            }
            bs.origSelector = null;
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
        if (v instanceof android.widget.ListView) {
            try {
                ((android.widget.ListView) v).setDivider(bs.origDivider);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 原地重刷背景色，<b>绝不替换 drawable 本体</b>：ColorDrawable / GradientDrawable
     * 直接 setColor 保留圆角与描边；selector（发送按钮）整体换成按主题色构建的 oval
     * selector 并把原件存进快照；其余未知类型一律跳过，避免破坏按压反馈、九宫格、
     * selectableItemBackground 等无法在 API 18 读回原状的 drawable。
     *
     * <p>这里刻意不去改 GradientDrawable 的 stroke：API 18 没有 getStroke 系列 getter，
     * 改了就再也还原不回去，属于明确回归。深色下留一道浅灰细边比丢样式安全得多。
     */
    private static void paintBackground(View v, BaseStyle bs, int color) {
        Drawable d = v.getBackground();
        if (d instanceof ColorDrawable) {
            // **必须换新实例，不能 setColor**：从资源取到的 ColorDrawable 之间共享
            // ConstantState，setColor 改的是那份共享状态。后果是同布局后续 inflate
            // 出来的 View 一出生就是「上次主题色」，capture() 采样到的也是它，
            // BG_MAP 查不到 → 永远刷不回来。表现就是
            // 「浅→深正常，深→浅不对，必须重进 APP」。
            v.setBackgroundDrawable(new ColorDrawable(color));
        } else if (d instanceof GradientDrawable) {
            // GradientDrawable.setColor 同样会写共享的 GradientState；mutate() 先复制
            // 一份自己的状态，圆角/描边/Angle 一起带过来，既不丢样式也不污染他人。
            Drawable own = d.mutate();
            ((GradientDrawable) own).setColor(color);
            if (own != d) v.setBackgroundDrawable(own);
        } else if (v.getId() == R.id.send_button && d instanceof StateListDrawable) {
            if (bs.origSelector == null) bs.origSelector = d;
            v.setBackgroundDrawable(ovalSelector(v, color));
        }
        // 其它类型：不动
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
     * 取 Drawable 的原始纯色。不用反射、不用 getColor()（API 29 才有），
     * 画到 9x9 位图取中心像素，避开 GradientDrawable 的描边与圆角。
     * 透明/半透明背景（按压反馈、透明按钮）返回 null，本类一律不动。
     */
    static Integer sampleSolidColor(Drawable d) {
        if (d == null) return null;
        Bitmap b = null;
        try {
            b = Bitmap.createBitmap(9, 9, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            Rect old = new Rect(d.getBounds());
            d.setBounds(0, 0, 9, 9);
            d.draw(c);
            d.setBounds(old);
            int px = b.getPixel(4, 4);
            if (Color.alpha(px) < 200) return null;
            return Integer.valueOf(px);
        } catch (Throwable t) {
            return null;
        } finally {
            if (b != null) {
                try {
                    b.recycle();
                } catch (Throwable ignored) {
                }
            }
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
}
