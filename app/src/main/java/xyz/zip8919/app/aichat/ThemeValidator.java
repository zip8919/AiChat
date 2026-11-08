package xyz.zip8919.app.aichat;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;

/**
 * .actheme 导入校验（交互方案 §12.3 三级）+ CSS 沙箱过滤（§11.4）。
 *
 * <p>三级：
 * <ul>
 *   <li>阻断：JSON 非法 / format 不符 / version 不兼容 / 明暗都缺 /
 *       文件超 64KB / css 超 64KB —— 不落盘，给具体原因</li>
 *   <li>警告：未知 token、非法颜色值、只提供一套明暗、字体引用未安装、
 *       CSS 命中黑名单被忽略 —— 弹汇总清单，用户确认后落盘</li>
 *   <li>静默：未知可选字段 —— 保留但忽略，不提示</li>
 * </ul>
 *
 * <p><b>CSS 沙箱放宽（用户要求「主题能改样式，不只是颜色」）</b>：
 * 早期版本把 position / max-width / min-width / white-space / word-wrap 等
 * 一律列为保护属性，结果主题只能换色，写不出「搜索卡片棕色方形」
 * 「代码卡片特定样式」这类改动。现在改为<b>按需精确拦截</b>：只拦
 * position:fixed、外链资源、脚本注入，以及会真正把 258dp 窄屏撑破的
 * 少数声明（固定像素宽度 / overflow-x / white-space:nowrap）。
 * border-radius / border / background / padding / margin / box-shadow /
 * font-* / text-* / opacity / transform 等样式属性全部放行。
 *
 * <p>注意：缺 meta.name 已从「阻断」降为「自动补名 + 警告」——AI 生成时
 * 偶尔漏写，一刀切阻断是「生成的经常无法导入」的主因之一。
 */
final class ThemeValidator {

    private static final String TAG = "ThemeValidator";

    /** 命中即整条规则剔除：脚本注入、外链资源、fixed 定位。 */
    private static final String[] RULE_BLACKLIST = {
            "javascript:", "expression(", "@import", "position:fixed", "position: fixed",
            "behavior:", "-moz-binding", "<script", "</script", "url(http", "url(https"
    };

    /**
     * 窄屏保护：仅拦「会把 258dp 窄屏撑破」的声明，且只在窄屏敏感选择器上生效。
     * 键是属性名，值为需要剔除的<b>危险值</b>子串；值为 null 表示该属性一律剔除。
     * 其余样式属性（圆角、边框、背景、间距、阴影、字体、透明度…）一律放行。
     */
    private static final String[][] PROTECTED_DECLS = {
            {"position", "fixed"},          // 只拦 fixed，relative/absolute 放行
            {"width", "px"},               // 只拦固定像素宽度（撑破窄屏）
            {"min-width", "px"},
            {"overflow-x", null},          // 改掉会破坏代码块横向滚动
            {"word-wrap", null},           // 改掉会让长 URL/长代码撑破
            {"white-space", "nowrap"}      // 只拦 nowrap，pre-wrap 放行
    };

    static final class Result {
        public boolean blocked = false;
        public String blockReason = "";
        public final List<String> warnings = new ArrayList<String>();
        public Theme theme = null;
        public int ignoredCssRules = 0;
        public int ignoredTokens = 0;

        public boolean hasWarnings() {
            return !warnings.isEmpty();
        }
    }

    private ThemeValidator() {
    }

    static Result validate(String text) {
        return validate(text, null);
    }

    /**
     * @param installedFontIds 已安装字体 id 集合（可为 null，表示不检查字体引用）
     */
    static Result validate(String text, Set<String> installedFontIds) {
        Result r = new Result();
        if (text == null || text.trim().length() == 0) {
            return block(r, "文件内容为空");
        }
        // 极端大输入先挡掉，避免清洗器被拖垮（正常主题/AI 输出远小于此）
        if (text.getBytes().length > Theme.MAX_FILE_BYTES * 8) {
            return block(r, "文件超过 512KB");
        }

        // 统一清洗入口：所有导入路径（代码块一键导入 / 编辑页粘贴 / 文件导入 /
        // 磁盘已存主题加载）都汇聚到这里，先归一成纯 JSON 再解析。
        // 覆盖 AI 常见的 ```json 围栏、前后解释文字、注释、尾逗号、裸换行、
        // 单引号字段、format/version 大小写与类型偏差。详见 ThemeText。
        String json = ThemeText.clean(text);
        if (json.length() == 0) {
            return block(r, "未找到有效的主题 JSON（内容里没有 { } 主体）");
        }
        if (json.getBytes().length > Theme.MAX_FILE_BYTES) {
            return block(r, "文件超过 64KB");
        }

        Theme t;
        try {
            t = Theme.parse(json);
        } catch (Exception e) {
            return block(r, "JSON 非法或不完整：" + shortMsg(e));
        }

        if (t.version != Theme.VERSION) {
            return block(r, t.version > Theme.VERSION
                    ? "主题版本高于本应用支持（v" + t.version + "）"
                    : "缺少或无效的版本号");
        }
        // AI 生成时常漏写 meta.name 或名字超长。以前一刀切阻断，是「生成的经常
        // 无法导入」的主因之一；改为自动补名/截断 + 警告，保证主题一定能落盘。
        if (t.name == null || t.name.trim().length() == 0) {
            t.name = "未命名主题";
            r.warnings.add("缺少 meta.name，已自动命名为「未命名主题」");
        }
        if (t.name.length() > 32) {
            t.name = t.name.substring(0, 32);
            r.warnings.add("主题名称超过 32 字，已自动截断");
        }
        if (!t.hasLight && !t.hasDark) {
            return block(r, "明暗两套配置都缺失");
        }
        if (byteLen(t.lightCss) > Theme.MAX_CSS_BYTES || byteLen(t.darkCss) > Theme.MAX_CSS_BYTES) {
            return block(r, "CSS 超过 64KB");
        }

        // 只提供一套 → 警告，另一套回退内置
        if (!t.hasLight || !t.hasDark) {
            r.warnings.add("只提供了" + (t.hasLight ? "浅色" : "深色") + "配置，另一套将回退内置默认");
        }

        // token 白名单 + 颜色值校验
        sanitizeTokens(t.lightTokens, "浅色", r);
        sanitizeTokens(t.darkTokens, "深色", r);

        // 字体引用
        if (installedFontIds != null) {
            checkFontRef(t.fontUi, "界面字体", installedFontIds, r);
            checkFontRef(t.fontMono, "等宽字体", installedFontIds, r);
        }

        // CSS 沙箱
        CssSanitized sl;
        CssSanitized sd;
        try {
            sl = sanitizeCss(t.lightCss);
            sd = sanitizeCss(t.darkCss);
        } catch (RuntimeException e) {
            // 兜底：沙箱自身出问题时不能让整次导入失败（用户看到过
            // 「导入失败：Syntax error in regexp pattern」这类裸异常）。
            // 宁可不过滤，也要让主题落盘。
            LogUtil.w(TAG, "sanitizeCss failed, keep raw css", e);
            r.warnings.add("CSS 预检异常，已跳过过滤直接保留原样式");
            sl = new CssSanitized(t.lightCss, 0);
            sd = new CssSanitized(t.darkCss, 0);
        }
        t.lightCss = sl.css;
        t.darkCss = sd.css;
        r.ignoredCssRules = sl.ignored + sd.ignored;
        if (r.ignoredCssRules > 0) {
            r.warnings.add("已忽略 " + r.ignoredCssRules
                    + " 条 CSS 规则（脚本 / 外链 / fixed / 会撑破 258dp 窄屏的声明）");
        }

        r.theme = t;
        return r;
    }

    private static void sanitizeTokens(Map<String, String> tokens, String label, Result r) {
        Iterator<Map.Entry<String, String>> it = tokens.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, String> e = it.next();
            String k = e.getKey();
            String v = e.getValue();
            if (!ThemeTokens.isValid(k)) {
                it.remove();
                r.ignoredTokens++;
                r.warnings.add(label + "：忽略未知 token「" + k + "」");
                continue;
            }
            if (!ThemeTokens.isValidColor(v)) {
                it.remove();
                r.ignoredTokens++;
                r.warnings.add(label + "：忽略非法颜色值 " + k + "=" + v);
            }
        }
    }

    private static void checkFontRef(String ref, String label, Set<String> installed, Result r) {
        if (ref == null || "system".equals(ref) || ref.length() == 0) return;
        if (!installed.contains(ref)) {
            r.warnings.add(label + "引用的字体「" + ref + "」未安装，将回退系统默认");
        }
    }

    // ---- CSS 沙箱 ----

    private static final class CssSanitized {
        String css;
        int ignored;
        CssSanitized(String c, int n) { css = c; ignored = n; }
    }

    /**
     * 按 {@code } } 切规则块，逐块检查黑名单；块内按 {@code ;} 拆声明，
     * 命中窄屏保护属性的声明剔除。其余原样保留，不重排、不改选择器。
     *
     * <p><b>这里刻意不用 {@code String.split}。</b> 早期实现写的是
     * {@code src.split("}", -1)}，在桌面 OpenJDK 上完全正常（JDK 容忍孤立的
     * {@code }}），但 Android 4.4 的 Harmony 正则引擎把它判为语法错误：
     * {@code PatternSyntaxException: Syntax error in regexp pattern near index 1}。
     * 结果是「桌面验证通过、真机导入必失败」——只要主题带 CSS 就复现。
     * 改为逐字符扫描后，两个平台行为一致。
     */
    static CssSanitized sanitizeCss(String src) {
        if (src == null || src.length() == 0) return new CssSanitized("", 0);
        StringBuilder out = new StringBuilder();
        int ignored = 0;
        List<String> blocks = splitOnChar(src, '}');
        for (int b = 0; b < blocks.size(); b++) {
            String block = blocks.get(b);
            if (block.trim().length() == 0) continue;
            int brace = block.indexOf('{');
            String selector = brace >= 0 ? block.substring(0, brace) : "";
            String decls = brace >= 0 ? block.substring(brace + 1) : block;

            if (containsBlacklist(block)) {
                ignored++;
                continue;
            }

            List<String> parts = splitOnChar(decls, ';');
            StringBuilder kept = new StringBuilder();
            boolean droppedDecl = false;
            for (int i = 0; i < parts.size(); i++) {
                String d = parts.get(i);
                if (d.trim().length() == 0) continue;
                if (isProtectedDecl(d, selector)) {
                    droppedDecl = true;
                    ignored++;
                    continue;
                }
                if (kept.length() > 0) kept.append(';');
                kept.append(d);
            }
            if (droppedDecl && kept.length() == 0) {
                // 整块只剩保护属性，直接丢掉这条规则
                continue;
            }
            out.append(selector);
            if (brace >= 0) out.append('{');
            out.append(kept);
            if (b < blocks.size() - 1 || brace >= 0) out.append('}');
            if (brace < 0 && kept.length() > 0) out.append(';');
        }
        return new CssSanitized(out.toString(), ignored);
    }

    /**
     * 等价于 {@code s.split(String.valueOf(sep), -1)}（含尾部空段），但不用正则。
     * 见 {@link #sanitizeCss} 里关于 Android 4.4 正则差异的说明。
     */
    private static List<String> splitOnChar(String s, char sep) {
        List<String> out = new ArrayList<String>();
        int from = 0;
        int n = s.length();
        for (int i = 0; i < n; i++) {
            if (s.charAt(i) == sep) {
                out.add(s.substring(from, i));
                from = i + 1;
            }
        }
        out.add(s.substring(from));
        return out;
    }

    private static boolean containsBlacklist(String s) {
        String low = s.toLowerCase();
        for (int i = 0; i < RULE_BLACKLIST.length; i++) {
            if (low.contains(RULE_BLACKLIST[i])) return true;
        }
        return false;
    }

    /**
     * 只对窄屏敏感选择器（body / pre / table / .table-scroll / img / svg / pre code）
     * 检查保护声明；.tool-card / .bubble / code 等局部组件不受限，允许自由改样式。
     */
    private static boolean isProtectedDecl(String decl, String selector) {
        String low = decl.toLowerCase();
        String sel = selector.toLowerCase();
        boolean narrowScope = sel.contains("body") || sel.contains("pre")
                || sel.contains(".table-scroll") || sel.contains("img")
                || sel.contains("svg") || sel.contains("table");
        if (!narrowScope) return false;
        int colon = low.indexOf(':');
        if (colon < 0) return false;
        String prop = low.substring(0, colon).trim();
        String val = low.substring(colon + 1).trim();
        for (int i = 0; i < PROTECTED_DECLS.length; i++) {
            if (!prop.equals(PROTECTED_DECLS[i][0])) continue;
            String danger = PROTECTED_DECLS[i][1];
            if (danger == null || val.contains(danger)) return true;
        }
        return false;
    }

    // ---- helpers ----

    private static Result block(Result r, String reason) {
        r.blocked = true;
        r.blockReason = reason;
        r.theme = null;
        return r;
    }

    private static int byteLen(String s) {
        return s == null ? 0 : s.getBytes().length;
    }

    private static String shortMsg(Exception e) {
        String m = e.getMessage();
        if (m == null) return e.getClass().getSimpleName();
        return m.length() > 80 ? m.substring(0, 80) : m;
    }
}
