package xyz.zip8919.app.aichat;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 主题生成提示词的模板仓库。
 *
 * <p>模板原来硬编码在 {@link ThemeEditActivity#buildPrompt(String)} 里，改一个字都得重新发版。
 * 现在放到设置里可编辑，用户能按自己的模型口味调整（比如要求少写注释、只给 dark 一套）。
 *
 * <p>占位符分两类：
 * <ul>
 *   <li>{@code {desc}} —— 用户在「你想要什么样的主题」里填的需求，唯一必填占位符；</li>
 *   <li>设备类 —— 与系统提示词共用 {@link SystemInfo#fillPlaceholders}，
 *       如 {@code {app_version}}、{@code {api}}、{@code {model}}。</li>
 * </ul>
 *
 * <p>模板写坏了不能让功能直接不可用：缺 {@code {desc}} 时自动把需求追加到末尾，
 * 空模板回退内置默认，保证「点生成一定有可用提示词」。
 */
final class ThemePromptStore {

    private static final String PREFS = "aichat_settings";
    private static final String KEY_TEMPLATE = "theme_prompt_template";

    private ThemePromptStore() {
    }

    /** 用户是否改过模板（设置页用来显示「自定义 / 内置默认」）。 */
    static boolean isCustomized(Context ctx) {
        String s = prefs(ctx).getString(KEY_TEMPLATE, "");
        return s != null && s.trim().length() > 0;
    }

    static String getTemplate(Context ctx) {
        String s = prefs(ctx).getString(KEY_TEMPLATE, "");
        if (s == null || s.trim().length() == 0) return defaultTemplate();
        return s;
    }

    static void setTemplate(Context ctx, String template) {
        String v = template == null ? "" : template;
        prefs(ctx).edit().putString(KEY_TEMPLATE, v.trim().length() == 0 ? "" : v).apply();
    }

    static void reset(Context ctx) {
        prefs(ctx).edit().remove(KEY_TEMPLATE).apply();
    }

    /**
     * 渲染最终提示词：先填 {@code {desc}}，再交给 SystemInfo 填设备类占位符。
     *
     * <p>顺序不能反：先填 desc 再填设备，是因为 desc 里可能出现用户手写的
     * {@code {api}} 之类字样——那不是占位符意图，先替换会把它吃掉；
     * 反过来先填设备、后填 desc，用户描述里的大括号就会原样留在提示词里，
     * 反而更安全。
     */
    static String render(Context ctx, String desc) {
        String tpl = getTemplate(ctx);
        String d = desc == null ? "" : desc.trim();
        String out;
        if (tpl.contains("{desc}")) {
            out = tpl.replace("{desc}", d);
        } else {
            // 自定义模板漏了占位符：需求直接补在末尾，别让用户白填一遍
            out = tpl + "\n\n【我的主题需求】\n" + d + "\n";
        }
        return SystemInfo.fillPlaceholders(out, ctx);
    }

    /** 内置默认模板：与 1.14.6 的硬编码版本一致，只把需求位置换成 {desc}。 */
    static String defaultTemplate() {
        return DEFAULT_TEMPLATE;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, 0);
    }

    private static final String DEFAULT_TEMPLATE = buildDefault();

    private static String buildDefault() {
        StringBuilder b = new StringBuilder();
        b.append("你在为一个 AI 聊天 Android 应用编写主题文件 .actheme。下面这段是该应用的源码摘要，请当作你已经读过它的代码，据此设计主题。").append('\n');
        b.append("").append('\n');
        b.append("【一、这是什么软件、界面由什么构成】").append('\n');
        b.append("一个运行在 Android 4.4（API 18/19）上的 AI 对话客户端，屏幕很窄（258dp 宽）。").append('\n');
        b.append("· 原生界面：顶部有模型选择、思考等级、扫描按钮；下面是消息区；底部是输入框。按钮、卡片、顶部栏、输入框的颜色由 16 个 token 控制，CSS 不影响它们。").append('\n');
        b.append("· 消息区：不是原生控件，而是一个 WebView，把 AI 回复的 Markdown 渲染成 HTML。内核是 Chromium 30，不支持 CSS 变量 var()、flex、gap、object-fit；请用传统写法（float / inline-block / border / border-radius / background）。").append('\n');
        b.append("· 主题分 light（浅色）与 dark（深色）两套，用户可随时切换。").append('\n');
        b.append("").append('\n');
        b.append("【二、主题能改什么（不只是颜色）】").append('\n');
        b.append("1) tokens：16 个颜色值，控制原生界面与消息区基底色。").append('\n');
        b.append("2) css：作用于消息 WebView 的任意 CSS。除了颜色，还可以改【形状与样式】：border-radius（写 0 就是方形）、border（粗细/实线虚线/颜色）、background（纯色或渐变）、padding、margin、box-shadow、font-size、font-family、font-weight、letter-spacing、line-height、text-shadow、opacity 等。").append('\n');
        b.append("   例：「搜索卡片变成棕色方形」→ .tool-card{border-radius:0;background:#6D4C41;}").append('\n');
        b.append("   例：「代码卡片用虚线边框的特定样式」→ pre{border:1px dashed #8D6E63;border-radius:2px;}").append('\n');
        b.append("3) fonts：ui / mono 字体引用，只能填 system 或已安装字体 id，不要内嵌字体文件。").append('\n');
        b.append("").append('\n');
        b.append("【三、消息 WebView 的真实 CSS 选择器（照这些名字写，别自己编）】").append('\n');
        b.append("· 页面/消息：body, .msg, .msg.user, .msg.user .bubble, .msg.user.failed .bubble, .msg.ai, .msg.ai .content, .msg-meta, .msg-meta-sub, .msg-menu-btn, .mdot, #older-hint").append('\n');
        b.append("· Markdown：h1~h6, p, li, ul, ol, blockquote, a, strong, em, hr, mark, del, s, sup, sub, img, svg, table, th, td, .table-scroll, .svg-scroll").append('\n');
        b.append("· 代码：pre, pre code, code, pre .lang-tag, pre .copy-btn, pre .save-btn, pre .import-btn, pre .preview-btn, pre .run-btn, .tk-kw, .tk-str, .tk-cmt, .tk-num, .tk-type, .tk-fn, .tk-op").append('\n');
        b.append("· 搜索/工具卡片：.tool-card, .tool-card-head, .tool-item, .tool-title, .tool-meta, .tool-summary, .tool-arrow, .tool-hint, .tool-status, .tool-trim, .tool-fold, .tool-imgs, .tool-img-cell, .tool-fetch-content, .tool-error, .tool-empty, .tool-body").append('\n');
        b.append("· 思考块/流式：.thinking-block, .thinking-toggle, .stream-tail, .stream-tail.plain").append('\n');
        b.append("· 来源/脚注：.sources-block, .sources-head, .sources-body, .src-item, .src-arrow, .fn-ref, .fn-def").append('\n');
        b.append("· 附件：.attach-list, .attach-item, .attach-ico, .attach-thumb, .attach-body, .attach-name, .attach-sub, .attach-cut, .attach-view").append('\n');
        b.append("· 错误/重试：.error-card, .err-title, .err-summary, .err-actions, .err-btn, .err-btn.retry, .retry-btn, .retry-reason").append('\n');
        b.append("· 数学：.math-block, .math-inline").append('\n');
        b.append("").append('\n');
        b.append("【四、16 个 token（值只能是 #RRGGBB）】").append('\n');
        b.append("ac_primary 顶部栏/主按钮；ac_primary_dark 按压态；ac_accent 强调/引用边；ac_bg 页面底；ac_surface 卡片/输入框表面；ac_text 主文字；ac_text_secondary 次要文字；ac_divider 分隔线；ac_bubble_user 用户气泡；ac_bubble_ai AI 消息；ac_danger 错误；ac_track 轨道/高亮；ac_code_bg 代码底；ac_code_text 代码字；ac_link 链接；ac_border 边框。").append('\n');
        b.append("").append('\n');
        b.append("【五、我的主题需求】").append('\n');
        b.append("{desc}").append('\n');
        b.append("").append('\n');
        b.append("【六、输出格式（必须严格遵守，否则无法导入）】").append('\n');
        b.append("只输出一个 ```actheme 代码块，代码块内是纯 JSON，不要任何解释文字。").append('\n');
        b.append("结构：").append('\n');
        b.append("{").append('\n');
        b.append("  \"format\": \"actheme\",").append('\n');
        b.append("  \"version\": 1,").append('\n');
        b.append("  \"meta\": {\"name\": \"主题名\", \"author\": \"\", \"description\": \"\"},").append('\n');
        b.append("  \"fonts\": {\"ui\": \"system\", \"mono\": \"system\"},").append('\n');
        b.append("  \"light\": {\"tokens\": {...}, \"css\": \"...\"},").append('\n');
        b.append("  \"dark\":  {\"tokens\": {...}, \"css\": \"...\"}").append('\n');
        b.append("}").append('\n');
        b.append("硬性要求：").append('\n');
        b.append("1. format 必须是 actheme，version 必须是数字 1，meta.name 必填且不超过 32 字。").append('\n');
        b.append("2. light 与 dark 两套都要写完整；每套的 css 必须非空，且至少包含一条改形状/样式的规则（如 border-radius 或 border），不能只给颜色。").append('\n');
        b.append("3. css 是单行字符串：CSS 内部一律不要用双引号（需要引号时用单引号），不要写真实换行，多条规则直接连写。JSON 里的双引号只用于字段名和字符串值。").append('\n');
        b.append("4. JSON 里不要写注释（// 或 /* */），不要尾逗号，不要用单引号包字段名。").append('\n');
        b.append("5. 不要用 position:fixed、@import、url(http...)、javascript:；不要给 body/pre/table/img 设固定像素 width、overflow-x、word-wrap、white-space:nowrap（会撑破 258dp 窄屏）。").append('\n');
        b.append("6. 只使用第三节列出的选择器。请输出完整闭合的 JSON，不要截断。").append('\n');
        b.append("7. 整个 JSON 必须格式化输出：每对字段单独一行，用 2 个空格缩进，token 一个一行。"
                + "只有 css 的字符串值内部保持单行（那是 CSS，不是 JSON 结构）。不要输出压成一行的 JSON。").append('\n');
        b.append("").append('\n');
        b.append("【七、完整示例（示范「棕色方形搜索卡片 + 代码卡片特定样式」，仅供格式参考，不要照抄配色）】").append('\n');
        b.append("```actheme").append('\n');
        b.append("{\n");
        b.append("  \"format\": \"actheme\",\n");
        b.append("  \"version\": 1,\n");
        b.append("  \"meta\": {\"name\": \"棕纸示例\", \"author\": \"\", \"description\": \"搜索卡片方形、代码卡片虚线框\"},\n");
        b.append("  \"fonts\": {\"ui\": \"system\", \"mono\": \"system\"},\n");
        b.append("  \"light\": {\n");
        b.append("    \"tokens\": {\n");
        b.append("      \"ac_primary\": \"#6D4C41\",\n");
        b.append("      \"ac_primary_dark\": \"#4E342E\",\n");
        b.append("      \"ac_accent\": \"#A1887F\",\n");
        b.append("      \"ac_bg\": \"#FAF6EF\",\n");
        b.append("      \"ac_surface\": \"#FFFDF7\",\n");
        b.append("      \"ac_text\": \"#3E2723\",\n");
        b.append("      \"ac_text_secondary\": \"#795548\",\n");
        b.append("      \"ac_divider\": \"#E0D7C8\",\n");
        b.append("      \"ac_bubble_user\": \"#EFE6D8\",\n");
        b.append("      \"ac_bubble_ai\": \"#FFFDF7\",\n");
        b.append("      \"ac_danger\": \"#B3261E\",\n");
        b.append("      \"ac_track\": \"#E8DED0\",\n");
        b.append("      \"ac_link\": \"#8D6E63\",\n");
        b.append("      \"ac_border\": \"#D7C4B5\",\n");
        b.append("      \"ac_code_bg\": \"#F0E7DA\",\n");
        b.append("      \"ac_code_text\": \"#3E2723\"\n");
        b.append("    },\n");
        b.append("    \"css\": \".tool-card{border-radius:0;background:#6D4C41;border:1px solid #4E342E;}"
                + ".tool-card-head{background:#5D4037;color:#FFF3E0;}.tool-title,.tool-status{color:#FFE0B2;}"
                + "pre{border-radius:2px;border:1px dashed #8D6E63;background:#F0E7DA;}\"\n");
        b.append("  },\n");
        b.append("  \"dark\": {\n");
        b.append("    \"tokens\": {\n");
        b.append("      \"ac_primary\": \"#A1887F\",\n");
        b.append("      \"ac_primary_dark\": \"#5D4037\",\n");
        b.append("      \"ac_accent\": \"#BCAAA4\",\n");
        b.append("      \"ac_bg\": \"#241C18\",\n");
        b.append("      \"ac_surface\": \"#2E241F\",\n");
        b.append("      \"ac_text\": \"#EFEBE9\",\n");
        b.append("      \"ac_text_secondary\": \"#BCAAA4\",\n");
        b.append("      \"ac_divider\": \"#4E342E\",\n");
        b.append("      \"ac_bubble_user\": \"#3E2F28\",\n");
        b.append("      \"ac_bubble_ai\": \"#2E241F\",\n");
        b.append("      \"ac_danger\": \"#F2B8B5\",\n");
        b.append("      \"ac_track\": \"#4E342E\",\n");
        b.append("      \"ac_link\": \"#BCAAA4\",\n");
        b.append("      \"ac_border\": \"#4E342E\",\n");
        b.append("      \"ac_code_bg\": \"#1E1714\",\n");
        b.append("      \"ac_code_text\": \"#EFEBE9\"\n");
        b.append("    },\n");
        b.append("    \"css\": \".tool-card{border-radius:0;background:#4E342E;border:1px solid #8D6E63;}"
                + ".tool-card-head{background:#3E2F28;color:#EFEBE9;}.tool-title,.tool-status{color:#D7CCC8;}"
                + "pre{border-radius:2px;border:1px dashed #8D6E63;background:#1E1714;}\"\n");
        b.append("  }\n");
        b.append("}\n");
        b.append("```").append('\n');
        return b.toString();
    }
}
