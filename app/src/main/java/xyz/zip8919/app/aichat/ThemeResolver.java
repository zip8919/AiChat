package xyz.zip8919.app.aichat;

/**
 * 三层覆盖判定（交互方案 §10）：用户显式覆盖 &gt; 自定义主题 &gt; 内置默认。
 *
 * <p>集中在这里是为了让「生效值」「是否被覆盖」「主题原本的值」三个结论同源，
 * UI 标注「已覆盖 · 主题为 X」与注入层取色不会出现两套口径。
 * 明暗分离由 AppearanceState 的 explicitLight / explicitDark 两张表保证。
 */
final class ThemeResolver {

    static final int SOURCE_EXPLICIT = 0;
    static final int SOURCE_THEME = 1;
    static final int SOURCE_BUILTIN = 2;

    static final class Resolved {
        public String value;
        public int source;
        public boolean overridden;
        /** 主题对该项的原始定义值，供 UI 显示「主题为 X」；无主题时为 null。 */
        public String themeValue;
        public String themeName;
    }

    private ThemeResolver() {
    }

    /** 主题层取值；未选主题 / 该项未定义 / 该明暗分组缺失，都返回 null。 */
    static String themeValue(String themeId, String key, boolean dark) {
        if (themeId == null || themeId.length() == 0) return null;
        return ThemeRepository.getInstance().themeValue(themeId, key, dark);
    }

    static String themeName(String themeId) {
        if (themeId == null || themeId.length() == 0) return null;
        Theme t = ThemeRepository.getInstance().active(themeId);
        return t == null ? null : t.name;
    }

    static Resolved resolve(AppearanceManager m, String key, boolean dark) {
        Resolved r = new Resolved();
        // 一次解析只取一次主题 id：getThemeId() 是 volatile 快照，分两次取时
        // 后台切主题会让 themeValue 与 themeName 落在不同主题上，取色与 UI 口径不一致。
        String themeId = m.getThemeId();
        String explicit = m.getExplicit(key, dark);
        if (explicit != null && explicit.length() > 0) {
            r.value = explicit;
            r.source = SOURCE_EXPLICIT;
            r.overridden = true;
        }
        // 只 active() 一次同时取 value/name：旧的 themeValue()/themeName() 各自
        // 走一遍 active → find → list，热路径上等于把仓库扫描做了两遍。
        Theme t = ThemeRepository.getInstance().active(themeId);
        if (t != null) {
            r.themeName = t.name;
            if (t.hasMode(dark)) r.themeValue = t.valueFor(key, dark);
        }
        if (r.value == null) {
            if (r.themeValue != null && r.themeValue.length() > 0) {
                r.value = r.themeValue;
                r.source = SOURCE_THEME;
            } else {
                r.value = builtinValue(key, dark);
                r.source = SOURCE_BUILTIN;
            }
        }
        return r;
    }

    /** 与 AppearanceManager.builtinValue 保持一致。 */
    static String builtinValue(String key, boolean dark) {
        if (ThemeTokens.isValid(key)) return ThemeTokens.defaultHex(key, dark);
        if (ThemeTokens.FONT_UI.equals(key) || ThemeTokens.FONT_MONO.equals(key)) return "system";
        return null;
    }

    static int resolveColor(AppearanceManager m, String token, boolean dark) {
        String v = resolve(m, token, dark).value;
        Integer c = ThemeTokens.parseColor(v);
        return c == null ? ThemeTokens.defaultColor(token, dark) : c.intValue();
    }
}
