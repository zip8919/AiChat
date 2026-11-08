package xyz.zip8919.app.aichat;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.json.JSONObject;

/**
 * 一个 .actheme 主题的内存模型，对应交互方案 §12 的 JSON schema。
 *
 * <p>只存引用与颜色/字体/CSS，不内嵌任何二进制资源。运行时元数据（id、来源、
 * 文件路径、文件缺失 / 版本不兼容标记）不写进 JSON 文件本身。
 */
final class Theme {

    static final String FORMAT = "actheme";
    static final int VERSION = 1;
    static final int MAX_CSS_BYTES = 64 * 1024;
    static final int MAX_FILE_BYTES = 64 * 1024;

    int version = VERSION;
    String name = "";
    String author = "";
    String description = "";
    String fontUi = "system";
    String fontMono = "system";

    boolean hasLight = false;
    boolean hasDark = false;
    final Map<String, String> lightTokens = new HashMap<String, String>();
    final Map<String, String> darkTokens = new HashMap<String, String>();
    String lightCss = "";
    String darkCss = "";

    // ---- 运行时元数据（不序列化） ----
    String id = "";
    boolean builtin = false;
    String filePath = null;
    boolean fileMissing = false;
    boolean versionIncompatible = false;

    static Theme blank() {
        Theme t = new Theme();
        t.name = "新建主题";
        t.hasLight = true;
        t.hasDark = true;
        return t;
    }

    /** 解析 .actheme 文本。任何结构性错误都抛异常，由 ThemeValidator 定级。 */
    static Theme parse(String text) throws Exception {
        if (text == null) throw new IllegalArgumentException("内容为空");
        JSONObject j = new JSONObject(text);
        if (!FORMAT.equals(j.optString("format", ""))) {
            throw new IllegalArgumentException("format 不是 actheme");
        }
        Theme t = new Theme();
        t.version = j.optInt("version", -1);
        JSONObject meta = j.optJSONObject("meta");
        if (meta == null) throw new IllegalArgumentException("缺 meta");
        t.name = meta.optString("name", "");
        t.author = meta.optString("author", "");
        t.description = meta.optString("description", "");
        JSONObject fonts = j.optJSONObject("fonts");
        if (fonts != null) {
            t.fontUi = emptyToSystem(fonts.optString("ui", "system"));
            t.fontMono = emptyToSystem(fonts.optString("mono", "system"));
        }
        t.hasLight = fillMode(j.optJSONObject("light"), t.lightTokens, t);
        t.hasDark = fillMode(j.optJSONObject("dark"), t.darkTokens, t);
        JSONObject l = j.optJSONObject("light");
        JSONObject d = j.optJSONObject("dark");
        t.lightCss = l == null ? "" : l.optString("css", "");
        t.darkCss = d == null ? "" : d.optString("css", "");
        return t;
    }

    private static boolean fillMode(JSONObject mode, Map<String, String> out, Theme t) {
        if (mode == null) return false;
        JSONObject tokens = mode.optJSONObject("tokens");
        if (tokens != null) {
            Iterator<String> it = tokens.keys();
            while (it.hasNext()) {
                String k = it.next();
                String v = tokens.optString(k, null);
                if (k != null && v != null) out.put(k, v);
            }
        }
        return true;
    }

    /** 该明暗分组下某个 token 的定义值；未定义返回 null。 */
    String tokenValue(String key, boolean dark) {
        Map<String, String> m = dark ? darkTokens : lightTokens;
        return m.get(key);
    }

    /** 主题级字体引用（不分明暗）。 */
    String fontValue(String key) {
        if (ThemeTokens.FONT_UI.equals(key)) return fontUi;
        if (ThemeTokens.FONT_MONO.equals(key)) return fontMono;
        return null;
    }

    /** 主题在当前明暗下对 key 的定义值：颜色 token 或字体引用。 */
    String valueFor(String key, boolean dark) {
        String font = fontValue(key);
        if (font != null && !"system".equals(font)) return font;
        return tokenValue(key, dark);
    }

    String cssFor(boolean dark) {
        return dark ? darkCss : lightCss;
    }

    boolean hasMode(boolean dark) {
        return dark ? hasDark : hasLight;
    }

    /** 序列化回 .actheme JSON（导出 / 编辑保存 / 复制用）。 */
    String toJson() throws Exception {
        JSONObject j = new JSONObject();
        j.put("format", FORMAT);
        j.put("version", version);
        JSONObject meta = new JSONObject();
        meta.put("name", name);
        meta.put("author", author);
        meta.put("description", description);
        j.put("meta", meta);
        JSONObject fonts = new JSONObject();
        fonts.put("ui", fontUi);
        fonts.put("mono", fontMono);
        j.put("fonts", fonts);
        if (hasLight) j.put("light", modeToJson(lightTokens, lightCss));
        if (hasDark) j.put("dark", modeToJson(darkTokens, darkCss));
        return j.toString();
    }

    private static JSONObject modeToJson(Map<String, String> tokens, String css) throws Exception {
        JSONObject m = new JSONObject();
        JSONObject tk = new JSONObject();
        for (Map.Entry<String, String> e : tokens.entrySet()) {
            tk.put(e.getKey(), e.getValue());
        }
        m.put("tokens", tk);
        m.put("css", css == null ? "" : css);
        return m;
    }

    Theme copy() {
        Theme t = new Theme();
        t.version = version;
        t.name = name;
        t.author = author;
        t.description = description;
        t.fontUi = fontUi;
        t.fontMono = fontMono;
        t.hasLight = hasLight;
        t.hasDark = hasDark;
        t.lightTokens.putAll(lightTokens);
        t.darkTokens.putAll(darkTokens);
        t.lightCss = lightCss;
        t.darkCss = darkCss;
        t.id = id;
        t.builtin = builtin;
        t.filePath = filePath;
        t.fileMissing = fileMissing;
        t.versionIncompatible = versionIncompatible;
        return t;
    }

    private static String emptyToSystem(String s) {
        return s == null || s.length() == 0 ? "system" : s;
    }
}
