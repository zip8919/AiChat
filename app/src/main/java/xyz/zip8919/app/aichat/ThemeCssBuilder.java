package xyz.zip8919.app.aichat;

/**
 * WebView 主题 CSS 生成（实现方案 §7.2，全局规则 R3）。
 *
 * <p>Chromium 30（minSdk 18 对应 WebView）不支持 CSS 变量，所以主题色不能用
 * {@code var()}，必须靠「基础 CSS → token 规则 → 用户 CSS」的<b>追加顺序</b>
 * 与相同选择器优先级来覆盖。本类只生成追加段，不改 RendererAssets.CSS 基础层。
 *
 * <p><b>tokenRules 只输出颜色</b>，不碰圆角/边框/间距等样式，保证 token 层零回归
 * 且不撑破 258dp 窄屏；「改样式」由排在它后面的用户 CSS 负责（见 ThemeValidator
 * 放宽后的沙箱）。用户 CSS 可以写 border-radius / border / background / padding /
 * box-shadow 等，实现「搜索卡片变棕色方形」「代码卡片用特定样式」。
 *
 * <p>选择器覆盖必须完整：RendererAssets.CSS 里 .tool-* / .thinking-* / .retry-btn /
 * .attach-* / .sources-* / 代码按钮等大量组件用了硬编码蓝色系，早期 tokenRules
 * 一条都没覆盖，实机表现为「深色主题下搜索工具卡片仍是浅蓝」。
 */
final class ThemeCssBuilder {

    private ThemeCssBuilder() {
    }

    /**
     * &lt;style id="ac-theme"&gt; 的内容：token 规则 + 当前主题的用户 CSS。
     *
     * <p><b>零回归的关键</b>：token 默认值与 RendererAssets.CSS 的硬编码色并不
     * 完全一致（例如 body 背景基础是 #fff，ac_bg 默认是 #F5F5F5）。若只因为
     * 「改了显示大小」就无条件输出 token 规则，会把默认白色背景改掉。
     * 因此只有<b>颜色项真的被显式/主题覆盖、或处于深色模式</b>时才输出颜色规则；
     * 完全默认态返回空串。用户 CSS 始终追加（已过沙箱过滤）。
     */
    static String themeStyleContent(AppearanceManager m, boolean dark) {
        if (m.isAppearanceDefault()) return "";
        String userCss = ThemeRepository.getInstance().activeCss(m.getThemeId(), dark);
        StringBuilder sb = new StringBuilder();
        if (dark || hasColorOverride(m, dark)) sb.append(tokenRules(m, dark));
        sb.append(userCss);
        return sb.toString();
    }

    /** 是否存在 EXPLICIT / THEME 来源的颜色 token；BUILTIN 一律不输出，交给基础 CSS。 */
    private static boolean hasColorOverride(AppearanceManager m, boolean dark) {
        String[] tokens = ThemeTokens.all();
        for (int i = 0; i < tokens.length; i++) {
            if (m.resolve(tokens[i], dark).source != ThemeResolver.SOURCE_BUILTIN) return true;
        }
        return false;
    }

    /** &lt;style id="ac-font"&gt; 的内容：@font-face（绝对 file:// 路径）。系统字体时返回空。 */
    static String fontStyleContent(AppearanceManager m) {
        if (m.isAppearanceDefault()) return "";
        FontRepository repo = FontRepository.getInstance();
        String ui = m.getEffective(ThemeTokens.FONT_UI);
        String mono = m.getEffective(ThemeTokens.FONT_MONO);
        boolean hasCustom = !isSystem(ui) || !isSystem(mono);
        if (!hasCustom) return "";   // 仅改字号/颜色时，不碰字体族
        StringBuilder sb = new StringBuilder();
        sb.append(faceBlock("ac-ui", ui, repo));
        sb.append(faceBlock("ac-mono", mono, repo));
        sb.append("body{font-family:'ac-ui',Roboto,sans-serif;}");
        sb.append("code,pre,pre code,.lang-tag{font-family:'ac-mono','Courier New',monospace;}");
        return sb.toString();
    }

    private static boolean isSystem(String ref) {
        return ref == null || ref.length() == 0 || "system".equals(ref);
    }

    private static String faceBlock(String family, String fontId, FontRepository repo) {
        if (fontId == null || "system".equals(fontId) || fontId.length() == 0) return "";
        String path = repo.fontFilePath(fontId);
        if (path == null) return "";
        // loadDataWithBaseURL(null,…) 下相对路径无效，必须用绝对 file URL；
        // setAllowFileAccessFromFileURLs(true) 已在 MainActivity.bindWebView 打开
        return "@font-face{font-family:'" + family + "';src:url('file://" + path + "');}";
    }

    /**
     * 按 16 个 token 生成对应规则。用与基础 CSS 相同（或更高）的选择器具体度，
     * 靠「后追加」取胜；不改布局属性。
     *
     * <p>这里必须把 RendererAssets.CSS 里所有硬编码蓝色组件列全：工具/搜索卡片
     * （.tool-*）、思考块（.thinking-*）、重试按钮、代码块按钮、附件、来源、
     * 脚注、表格、代码高亮。漏一条，深色主题下那一条就还是浅蓝。
     */
    private static String tokenRules(AppearanceManager m, boolean dark) {
        StringBuilder s = new StringBuilder();
        String bg = hex(m, ThemeTokens.AC_BG, dark);
        String surface = hex(m, ThemeTokens.AC_SURFACE, dark);
        String text = hex(m, ThemeTokens.AC_TEXT, dark);
        String text2 = hex(m, ThemeTokens.AC_TEXT_SECONDARY, dark);
        String bubbleUser = hex(m, ThemeTokens.AC_BUBBLE_USER, dark);
        String bubbleAi = hex(m, ThemeTokens.AC_BUBBLE_AI, dark);
        String codeBg = hex(m, ThemeTokens.AC_CODE_BG, dark);
        String codeText = hex(m, ThemeTokens.AC_CODE_TEXT, dark);
        String link = hex(m, ThemeTokens.AC_LINK, dark);
        String divider = hex(m, ThemeTokens.AC_DIVIDER, dark);
        String accent = hex(m, ThemeTokens.AC_ACCENT, dark);
        String danger = hex(m, ThemeTokens.AC_DANGER, dark);
        String border = hex(m, ThemeTokens.AC_BORDER, dark);
        String primary = hex(m, ThemeTokens.AC_PRIMARY, dark);
        String track = hex(m, ThemeTokens.AC_TRACK, dark);

        // --- 页面基底与正文 ---
        s.append("body{background:").append(bg).append(";color:").append(text).append(";}");
        s.append("p,li,h1,h2,h3,h4,h5,h6,strong,em,sub,blockquote,.msg.ai .content,")
         .append(".tool-summary,.tool-fetch-content{color:").append(text).append(";}");
        s.append("h1,h2{border-bottom-color:").append(divider).append(";}");
        s.append("h6,del,s,.msg-meta,.msg-meta-sub,.attach-sub,.attach-view,.err-summary,")
         .append(".src-arrow,#older-hint,.retry-reason,.tool-hint,.tool-meta,.tool-empty,")
         .append(".tool-trim,.fn-def,.fn-def sup a,.thinking-block,.stream-tail.plain::after,")
         .append("pre .lang-tag{color:").append(text2).append(";}");

        // --- 气泡 ---
        s.append(".msg.user .bubble{background:").append(bubbleUser).append(";color:").append(text).append(";}");
        s.append(".msg.user.failed .bubble{background:").append(bubbleUser).append(";color:").append(text).append(";}");
        s.append(".msg.ai{background:").append(bubbleAi).append(";border-top-color:").append(divider).append(";}");
        s.append(".msg.ai,.msg-meta,#older-hint{border-color:").append(divider).append(";}");

        // --- 工具 / 搜索卡片（用户点名的「搜索工具还是蓝色」）---
        s.append(".tool-card,.tool-card-head,.tool-status,.tool-fold,.tool-trim,")
         .append(".sources-block,.sources-head,.sources-body,.math-block,.attach-item,")
         .append(".error-card,.err-btn{background:").append(surface).append(";color:").append(text).append(";}");
        s.append(".tool-card,.sources-block,.tool-fold,.attach-item,.error-card,.err-btn,")
         .append(".tool-img-cell,.attach-ico,.attach-thumb{border-color:").append(border).append(";}");
        s.append(".tool-card-head,.sources-head,.tool-status{background:").append(surface)
         .append(";color:").append(text2).append(";}");
        s.append(".tool-title,.tool-arrow,.tool-card-head,.tool-status,.attach-name,")
         .append(".attach-view,.src-item a,.fn-ref a,.thinking-toggle{color:").append(link).append(";}");
        // 图片格与缩略图靠 background-image + cover 渲染，只能用 background-color
        // 改底色；写 background 简写会把图片一起抹掉（Chromium 30 同样如此）。
        s.append(".tool-item{border-top-color:").append(divider).append(";}");
        s.append(".tool-img-cell{background-color:").append(surface).append(";}");
        s.append(".tool-arrow,.tool-hint,.tool-meta,.tool-empty,.tool-summary,.tool-fetch-content,")
         .append(".tool-error{color:").append(text2).append(";}");
        s.append(".tool-error,.err-title,.retry-reason{color:").append(danger).append(";}");
        s.append(".attach-ico,.attach-thumb{background-color:").append(surface).append(";}");
        s.append(".attach-name{color:").append(link).append(";}");
        s.append(".attach-cut{color:").append(danger).append(";}");

        // --- 重试 / 错误按钮 ---
        // 重试按钮底色跟随主色，字色按主色亮度选黑/白：内置「高对比」主题的
        // ac_primary 是 #FFFFFF，写死 #fff 就是白底白字，按钮直接消失。
        // 注意不要把 .err-btn.retry 的文字也刷白：它的底色是 surface，浅色模式下
        // surface 是白色，白字会完全看不见。
        s.append(".retry-btn{background:").append(primary)
         .append(";color:").append(contrastText(primary)).append(";}");
        s.append(".retry-btn:active{background:").append(primary).append(";}");
        s.append(".err-title{color:").append(danger).append(";}");
        s.append(".err-btn.retry{background:").append(surface).append(";border-color:")
         .append(border).append(";color:").append(link).append(";}");
        s.append(".err-btn{background:").append(surface).append(";border-color:")
         .append(border).append(";color:").append(text).append(";}");

        // --- 代码块与代码块按钮 ---
        s.append("pre{background:").append(codeBg).append(";border-color:").append(border).append(";}");
        s.append("pre code,code{color:").append(codeText).append(";background:transparent;}");
        s.append("pre .lang-tag,pre .copy-btn,pre .save-btn,pre .import-btn,")
         .append("pre .preview-btn,pre .run-btn{background:").append(codeBg)
         .append(";border-color:").append(border).append(";color:").append(text2).append(";}");
        // 深色下语法高亮原色（多为深蓝/深红）在深底上不可读，统一按 token 提亮
        s.append(".tk-kw,.tk-op{color:").append(danger).append(";}");
        s.append(".tk-str,.tk-num,.tk-type,.tk-fn{color:").append(link).append(";}");
        s.append(".tk-cmt{color:").append(text2).append(";}");

        // --- 思考块 / 表格 / 引用 / 链接 / 高亮 ---
        s.append(".thinking-block{background:").append(surface).append(";border-left-color:")
         .append(border).append(";color:").append(text2).append(";}");
        s.append(".thinking-toggle{color:").append(link).append(";}");
        s.append("th,td{border-color:").append(border).append(";}");
        s.append("th{background:").append(surface).append(";color:").append(text).append(";}");
        s.append("blockquote{border-left-color:").append(accent).append(";background:")
         .append(surface).append(";color:").append(text).append(";}");
        s.append("a,.src-item a,.fn-ref a,.fn-def sup a{color:").append(link).append(";}");
        s.append("hr{background:").append(divider).append(";}");
        s.append("mark{background:").append(track).append(";color:").append(text).append(";}");
        s.append(".mdot{background:").append(text2).append(";}");
        s.append(".msg-menu-btn:active{background:").append(surface).append(";}");
        return s.toString();
    }

    private static String hex(AppearanceManager m, String token, boolean dark) {
        return hexOf(m.getEffectiveColor(token, dark));
    }

    /**
     * 给定底色选一个能看清的字色（#000000 / #FFFFFF）。
     *
     * <p>用 sRGB 通道加权亮度而非 HSL 的 L：同样 L=50% 时纯黄与纯蓝的观感亮度差一大截，
     * 按通道加权才和肉眼一致。解析失败按深底处理（白字），与历史行为一致。
     */
    static String contrastText(String hex) {
        Integer v = ThemeTokens.parseColor(hex);
        if (v == null) return "#FFFFFF";
        int c = v.intValue();
        int r = (c >> 16) & 0xFF;
        int g = (c >> 8) & 0xFF;
        int b = c & 0xFF;
        int lum = (r * 299 + g * 587 + b * 114) / 1000;
        return lum >= 160 ? "#000000" : "#FFFFFF";
    }

    /** int color → #RRGGBB（丢掉 alpha，CSS 里用不透明色最稳）。
     *  手写而非 String.format：一次主题刷新要拼 15+ 次，Android 4.4 上
     *  Formatter 的构造与解析开销明显，属于热路径上的无谓分配。 */
    static String hexOf(int color) {
        char[] buf = new char[7];
        buf[0] = '#';
        for (int i = 0; i < 6; i++) {
            int v = (color >> (20 - i * 4)) & 0x0F;
            buf[i + 1] = (char) (v < 10 ? '0' + v : 'A' + v - 10);
        }
        return new String(buf);
    }

    /**
     * 把 CSS 内容转成 JS 字符串字面量（用于 loadUrl/evaluateJavascript 时替换 style）。
     * 与 MainActivity.jsEscape 等价：后者是 private，这里自带一份纯字符串工具，
     * 不引入任何框架依赖。
     */
    static String jsString(String css) {
        if (css == null) return "";
        return css.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\u2028", "\\u2028")
                .replace("\u2029", "\\u2029");
    }
}
