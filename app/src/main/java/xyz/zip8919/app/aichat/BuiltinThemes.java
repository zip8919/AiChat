package xyz.zip8919.app.aichat;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/**
 * 内置 4 套自定义主题（交互方案 §6.1）：默认、纸墨、深夜、高对比。
 *
 * <p>以 JSON 常量内嵌（工程裁决 E4）：不落盘、不参与目录扫描的删除、
 * 升级时不需要处理旧副本，也省掉首次启动的 assets 拷贝与 I/O。
 * 每套都含 light / dark 两套配置，避免「该主题未提供深色配置」的回退提示。
 */
final class BuiltinThemes {

    static final String ID_DEFAULT = "builtin_default";
    static final String ID_PAPER = "builtin_paper";
    static final String ID_NIGHT = "builtin_night";
    static final String ID_CONTRAST = "builtin_contrast";

    private BuiltinThemes() {
    }

    static List<Theme> list() {
        List<Theme> out = new ArrayList<Theme>();
        add(out, ID_DEFAULT, "默认", "跟随系统", "与原版一致的 Holo.Light 配色，作为对比基准",
                new String[]{
                        "ac_primary", "#2196F3", "ac_primary_dark", "#1976D2",
                        "ac_accent", "#FF5722", "ac_bg", "#F5F5F5",
                        "ac_surface", "#FFFFFF", "ac_text", "#333333",
                        "ac_text_secondary", "#666666", "ac_bubble_user", "#E3F2FD",
                        "ac_bubble_ai", "#FFFFFF", "ac_divider", "#F0F0F0"},
                "",
                new String[]{
                        "ac_primary", "#1976D2", "ac_primary_dark", "#0D47A1",
                        "ac_accent", "#FF8A65", "ac_bg", "#212121",
                        "ac_surface", "#303030", "ac_text", "#EEEEEE",
                        "ac_text_secondary", "#BDBDBD", "ac_bubble_user", "#1A3A5C",
                        "ac_bubble_ai", "#303030", "ac_divider", "#424242"},
                "");

        add(out, ID_PAPER, "纸墨", "内置", "暖白底 + 深灰字，长时间阅读护眼",
                new String[]{
                        "ac_primary", "#8D6E63", "ac_primary_dark", "#5D4037",
                        "ac_accent", "#A1887F", "ac_bg", "#FAF6EF",
                        "ac_surface", "#FFFDF7", "ac_text", "#3E2723",
                        "ac_text_secondary", "#795548", "ac_bubble_user", "#EFE6D8",
                        "ac_bubble_ai", "#FFFDF7", "ac_divider", "#E0D7C8"},
                "body{background:#FAF6EF;color:#3E2723;}",
                new String[]{
                        "ac_primary", "#A1887F", "ac_primary_dark", "#5D4037",
                        "ac_accent", "#BCAAA4", "ac_bg", "#241C18",
                        "ac_surface", "#2E241F", "ac_text", "#EFEBE9",
                        "ac_text_secondary", "#BCAAA4", "ac_bubble_user", "#3E2F28",
                        "ac_bubble_ai", "#2E241F", "ac_divider", "#4E342E"},
                "body{background:#241C18;color:#EFEBE9;}");

        add(out, ID_NIGHT, "深夜", "内置", "暗色低饱和，夜间弱光下不刺眼",
                new String[]{
                        "ac_primary", "#5C6BC0", "ac_primary_dark", "#3949AB",
                        "ac_accent", "#7986CB", "ac_bg", "#ECEFF1",
                        "ac_surface", "#FFFFFF", "ac_text", "#263238",
                        "ac_text_secondary", "#546E7A", "ac_bubble_user", "#E8EAF6",
                        "ac_bubble_ai", "#FFFFFF", "ac_divider", "#CFD8DC"},
                "",
                new String[]{
                        "ac_primary", "#3F51B5", "ac_primary_dark", "#283593",
                        "ac_accent", "#5C6BC0", "ac_bg", "#12161C",
                        "ac_surface", "#1C222B", "ac_text", "#CFD8DC",
                        "ac_text_secondary", "#90A4AE", "ac_bubble_user", "#222B3E",
                        "ac_bubble_ai", "#1C222B", "ac_divider", "#2C3641"},
                "body{background:#12161C;color:#CFD8DC;}");

        add(out, ID_CONTRAST, "高对比", "内置", "黑白 + 深色边框，强光下可读",
                new String[]{
                        "ac_primary", "#000000", "ac_primary_dark", "#000000",
                        "ac_accent", "#000000", "ac_bg", "#FFFFFF",
                        "ac_surface", "#FFFFFF", "ac_text", "#000000",
                        "ac_text_secondary", "#333333", "ac_bubble_user", "#FFFFFF",
                        "ac_bubble_ai", "#FFFFFF", "ac_divider", "#000000",
                        "ac_border", "#000000", "ac_code_bg", "#FFFFFF", "ac_code_text", "#000000"},
                ".msg .bubble{border:2px solid #000;}",
                new String[]{
                        "ac_primary", "#FFFFFF", "ac_primary_dark", "#FFFFFF",
                        "ac_accent", "#FFFFFF", "ac_bg", "#000000",
                        "ac_surface", "#000000", "ac_text", "#FFFFFF",
                        "ac_text_secondary", "#DDDDDD", "ac_bubble_user", "#000000",
                        "ac_bubble_ai", "#000000", "ac_divider", "#FFFFFF",
                        "ac_border", "#FFFFFF", "ac_code_bg", "#000000", "ac_code_text", "#FFFFFF"},
                ".msg .bubble{border:2px solid #FFF;}");
        return out;
    }

    private static void add(List<Theme> out, String id, String name, String author,
                            String desc, String[] lightTokens, String lightCss,
                            String[] darkTokens, String darkCss) {
        try {
            String json = build(name, author, desc, lightTokens, lightCss, darkTokens, darkCss);
            Theme t = Theme.parse(json);
            t.id = id;
            t.builtin = true;
            out.add(t);
        } catch (Exception e) {
            LogUtil.e("BuiltinThemes", "builtin theme broken: " + id + " : " + e.getMessage(), e);
        }
    }

    private static String build(String name, String author, String desc,
                                String[] lightTokens, String lightCss,
                                String[] darkTokens, String darkCss) throws Exception {
        JSONObject j = new JSONObject();
        j.put("format", Theme.FORMAT);
        j.put("version", Theme.VERSION);
        JSONObject meta = new JSONObject();
        meta.put("name", name);
        meta.put("author", author);
        meta.put("description", desc);
        j.put("meta", meta);
        JSONObject fonts = new JSONObject();
        fonts.put("ui", "system");
        fonts.put("mono", "system");
        j.put("fonts", fonts);
        j.put("light", mode(lightTokens, lightCss));
        j.put("dark", mode(darkTokens, darkCss));
        return j.toString();
    }

    private static JSONObject mode(String[] kv, String css) throws Exception {
        JSONObject m = new JSONObject();
        JSONObject tk = new JSONObject();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            tk.put(kv[i], kv[i + 1]);
        }
        m.put("tokens", tk);
        m.put("css", css == null ? "" : css);
        return m;
    }
}
