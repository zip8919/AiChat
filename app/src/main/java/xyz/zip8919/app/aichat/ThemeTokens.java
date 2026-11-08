package xyz.zip8919.app.aichat;

import android.content.Context;
import android.graphics.Color;
import java.util.HashMap;
import java.util.Map;

/**
 * 16 个原生颜色 token。默认值与 res/values/colors.xml 实测值对齐（非推测）。
 *
 * <p>关键事实：{@code @color/xxx} 是编译期绑定，运行时改不了。自定义色靠
 * ThemeApplier 按 android:tag 覆盖 View；本类只负责「token 名 → 当前生效色」
 * 的取值与颜色字符串解析，不持有任何 View 引用。
 */
public final class ThemeTokens {

    public static final String AC_PRIMARY = "ac_primary";
    public static final String AC_PRIMARY_DARK = "ac_primary_dark";
    public static final String AC_ACCENT = "ac_accent";
    public static final String AC_BG = "ac_bg";
    public static final String AC_SURFACE = "ac_surface";
    public static final String AC_TEXT = "ac_text";
    public static final String AC_TEXT_SECONDARY = "ac_text_secondary";
    public static final String AC_DIVIDER = "ac_divider";
    public static final String AC_BUBBLE_USER = "ac_bubble_user";
    public static final String AC_BUBBLE_AI = "ac_bubble_ai";
    public static final String AC_DANGER = "ac_danger";
    public static final String AC_TRACK = "ac_track";
    public static final String AC_CODE_BG = "ac_code_bg";
    public static final String AC_CODE_TEXT = "ac_code_text";
    public static final String AC_LINK = "ac_link";
    public static final String AC_BORDER = "ac_border";

    /** 非颜色、但同样参与三层覆盖的项。 */
    public static final String FONT_UI = "font_ui";
    public static final String FONT_MONO = "font_mono";

    private static final String[] ALL = {
            AC_PRIMARY, AC_PRIMARY_DARK, AC_ACCENT, AC_BG, AC_SURFACE,
            AC_TEXT, AC_TEXT_SECONDARY, AC_DIVIDER, AC_BUBBLE_USER,
            AC_BUBBLE_AI, AC_DANGER, AC_TRACK, AC_CODE_BG, AC_CODE_TEXT,
            AC_LINK, AC_BORDER
    };

    private static final Map<String, Integer> DEFAULTS = new HashMap<String, Integer>();
    /** 深色模式且「不使用自定义主题」时的内置暗色（交互方案 §10.2 的 builtin_dark）。 */
    private static final Map<String, Integer> DARK_DEFAULTS = new HashMap<String, Integer>();

    static {
        DEFAULTS.put(AC_PRIMARY, 0xFF2196F3);
        DEFAULTS.put(AC_PRIMARY_DARK, 0xFF1976D2);
        DEFAULTS.put(AC_ACCENT, 0xFFFF5722);
        DEFAULTS.put(AC_BG, 0xFFF5F5F5);
        DEFAULTS.put(AC_SURFACE, 0xFFFFFFFF);
        DEFAULTS.put(AC_TEXT, 0xFF333333);
        DEFAULTS.put(AC_TEXT_SECONDARY, 0xFF666666);
        DEFAULTS.put(AC_DIVIDER, 0xFFF0F0F0);
        DEFAULTS.put(AC_BUBBLE_USER, 0xFFE3F2FD);
        DEFAULTS.put(AC_BUBBLE_AI, 0xFFFFFFFF);
        DEFAULTS.put(AC_DANGER, 0xFFD32F2F);
        DEFAULTS.put(AC_TRACK, 0xFFE0E0E0);
        DEFAULTS.put(AC_CODE_BG, 0xFFF6F8FA);
        DEFAULTS.put(AC_CODE_TEXT, 0xFF333333);
        DEFAULTS.put(AC_LINK, 0xFF2196F3);
        DEFAULTS.put(AC_BORDER, 0xFFCCCCCC);

        DARK_DEFAULTS.put(AC_PRIMARY, 0xFF1976D2);
        DARK_DEFAULTS.put(AC_PRIMARY_DARK, 0xFF0D47A1);
        DARK_DEFAULTS.put(AC_ACCENT, 0xFFFF8A65);
        DARK_DEFAULTS.put(AC_BG, 0xFF212121);
        DARK_DEFAULTS.put(AC_SURFACE, 0xFF303030);
        DARK_DEFAULTS.put(AC_TEXT, 0xFFEEEEEE);
        DARK_DEFAULTS.put(AC_TEXT_SECONDARY, 0xFFBDBDBD);
        DARK_DEFAULTS.put(AC_DIVIDER, 0xFF424242);
        DARK_DEFAULTS.put(AC_BUBBLE_USER, 0xFF1A3A5C);
        DARK_DEFAULTS.put(AC_BUBBLE_AI, 0xFF303030);
        DARK_DEFAULTS.put(AC_DANGER, 0xFFEF9A9A);
        DARK_DEFAULTS.put(AC_TRACK, 0xFF424242);
        DARK_DEFAULTS.put(AC_CODE_BG, 0xFF1E1E1E);
        DARK_DEFAULTS.put(AC_CODE_TEXT, 0xFFDCDCDC);
        DARK_DEFAULTS.put(AC_LINK, 0xFF64B5F6);
        DARK_DEFAULTS.put(AC_BORDER, 0xFF616161);
    }

    private ThemeTokens() {
    }

    public static String[] all() {
        return ALL.clone();
    }

    public static boolean isValid(String token) {
        return DEFAULTS.containsKey(token);
    }

    public static int defaultColor(String token) {
        return defaultColor(token, false);
    }

    public static int defaultColor(String token, boolean dark) {
        Map<String, Integer> m = dark ? DARK_DEFAULTS : DEFAULTS;
        Integer c = m.get(token);
        return c == null ? 0xFF808080 : c.intValue();
    }

    /** 颜色 token 的默认值，转成 #RRGGBB 字符串供 builtinValue 使用。 */
    public static String defaultHex(String token) {
        return defaultHex(token, false);
    }

    public static String defaultHex(String token, boolean dark) {
        int c = defaultColor(token, dark);
        return String.format("#%06X", c & 0x00FFFFFF);
    }

    /** 支持 #RRGGBB / #AARRGGBB；非法返回 null。 */
    public static Integer parseColor(String value) {
        if (value == null) return null;
        String v = value.trim();
        if (v.length() < 7 || v.charAt(0) != '#') return null;
        try {
            return Integer.valueOf(Color.parseColor(v));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static boolean isValidColor(String value) {
        return parseColor(value) != null;
    }

    /** 当前明暗模式下的生效色。 */
    public static int get(Context ctx, String token) {
        return AppearanceManager.getInstance(ctx).getEffectiveColor(token);
    }

    public static int get(Context ctx, String token, boolean dark) {
        return AppearanceManager.getInstance(ctx).getEffectiveColor(token, dark);
    }
}
