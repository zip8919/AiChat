package xyz.zip8919.app.aichat;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.json.JSONObject;

/**
 * 外观配置的内存模型，对应 basePath/appearance.json。
 *
 * <p>只持久化「用户显式覆盖」与几个标量；FOLLOW 用「map 里没有这个 key」表示，
 * 不额外存状态枚举。明暗两套覆盖分开存，切换深色模式不会串值。
 *
 * <p>不存：临时态标记、当前选中项的显示名、字体/主题文件本体、显示大小的
 * 主题覆盖（显示大小不参与主题覆盖）。
 */
final class AppearanceState {

    static final int SCHEMA = 1;
    static final int DEFAULT_PERCENT = 100;
    static final int DEFAULT_STEP = 2;

    int schema = SCHEMA;
    boolean dark = false;
    String themeId = "";
    int displayPercent = DEFAULT_PERCENT;
    int fontSizeStep = DEFAULT_STEP;
    // 后台渲染线程会经 colorSignature() 读这两表，主线程同时在
    // setExplicit/restoreExplicit 里写。4.4 的 HashMap 并发 resize 能形成
    // 链表环导致死循环，clear+putAll 的中间态还会让颜色签名算错。
    final Map<String, String> explicitLight =
            new java.util.concurrent.ConcurrentHashMap<String, String>();
    final Map<String, String> explicitDark =
            new java.util.concurrent.ConcurrentHashMap<String, String>();

    static AppearanceState defaults() {
        return new AppearanceState();
    }

    AppearanceState copy() {
        AppearanceState s = new AppearanceState();
        s.schema = schema;
        s.dark = dark;
        s.themeId = themeId == null ? "" : themeId;
        s.displayPercent = displayPercent;
        s.fontSizeStep = fontSizeStep;
        s.explicitLight.putAll(explicitLight);
        s.explicitDark.putAll(explicitDark);
        return s;
    }

    /** 当前明暗模式对应的覆盖表。 */
    Map<String, String> explicitFor(boolean isDark) {
        return isDark ? explicitDark : explicitLight;
    }

    boolean sameAs(AppearanceState o) {
        if (o == null) return false;
        return schema == o.schema && dark == o.dark
                && eq(themeId, o.themeId)
                && displayPercent == o.displayPercent
                && fontSizeStep == o.fontSizeStep
                && explicitLight.equals(o.explicitLight)
                && explicitDark.equals(o.explicitDark);
    }

    private static boolean eq(String a, String b) {
        String x = a == null ? "" : a;
        String y = b == null ? "" : b;
        return x.equals(y);
    }

    // ---- JSON ----

    String toJson() throws Exception {
        JSONObject j = new JSONObject();
        j.put("schema", schema);
        j.put("dark", dark);
        j.put("theme_id", nullToEmpty(themeId));
        j.put("display_percent", displayPercent);
        j.put("font_size_step", fontSizeStep);
        j.put("explicit_light", mapToJson(explicitLight));
        j.put("explicit_dark", mapToJson(explicitDark));
        return j.toString();
    }

    /**
     * 解析失败一律抛异常，由 AppearanceStore 决定「保留原文件 + 用内存默认」。
     * 这里绝不返回半解析状态，也绝不在这里写盘。
     */
    static AppearanceState parse(String text) throws Exception {
        if (text == null || text.trim().length() == 0) {
            throw new IllegalArgumentException("appearance.json 为空");
        }
        JSONObject j = new JSONObject(text);
        int sc = j.optInt("schema", -1);
        if (sc != SCHEMA) {
            throw new IllegalArgumentException("不支持的 schema=" + sc);
        }
        AppearanceState s = new AppearanceState();
        s.dark = j.optBoolean("dark", false);
        s.themeId = j.optString("theme_id", "");
        s.displayPercent = clampPercent(j.optInt("display_percent", DEFAULT_PERCENT));
        s.fontSizeStep = clampStep(j.optInt("font_size_step", DEFAULT_STEP));
        jsonToMap(j.optJSONObject("explicit_light"), s.explicitLight);
        jsonToMap(j.optJSONObject("explicit_dark"), s.explicitDark);
        return s;
    }

    static int clampPercent(int v) {
        if (v < 50) return 50;
        if (v > 250) return 250;
        return v;
    }

    static int clampStep(int v) {
        if (v < 0) return 0;
        if (v > 4) return 4;
        return v;
    }

    private static JSONObject mapToJson(Map<String, String> m) throws Exception {
        JSONObject j = new JSONObject();
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                j.put(e.getKey(), e.getValue());
            }
        }
        return j;
    }

    private static void jsonToMap(JSONObject j, Map<String, String> out) throws Exception {
        if (j == null) return;
        Iterator<String> it = j.keys();
        while (it.hasNext()) {
            String k = it.next();
            String v = j.optString(k, null);
            if (k != null && v != null) out.put(k, v);
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
