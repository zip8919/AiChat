package xyz.zip8919.app.aichat;

/**
 * AI 生成的 .actheme 文本清洗（修复「生成的经常无法导入」）。
 *
 * <p>本类刻意不依赖任何 Android API，便于桌面 JVM 单独编译验证。唯一接入点是
 * {@link ThemeValidator#validate(String, java.util.Set)}：先 {@link #clean(String)}
 * 归一成纯 JSON，再交给 {@link Theme#parse(String)}。覆盖全部导入路径：
 * <ul>
 *   <li>JsBridge.importTheme（聊天页代码块一键导入）</li>
 *   <li>ThemeEditActivity 粘贴 / 文件导入</li>
 *   <li>ThemeManagerActivity 文件导入</li>
 *   <li>ThemeRepository.loadOne（磁盘已存主题加载，兼容用户手改过的文件）</li>
 * </ul>
 * <p>JsBridge 与编辑页原本各有一套只认 ```actheme 的手写脱壳，已删除，避免
 * 两套逻辑不一致；「作为 CSS 填入」的降级路径仍使用用户粘贴的原始文本。
 *
 * <p>能容忍的 AI 常见输出偏差：
 * <ol>
 *   <li>```json / ```JSON / ```actheme / ``` / 无围栏，任意一种</li>
 *   <li>代码块前后的解释文字（「好的，这是主题…」「希望有帮助」）</li>
 *   <li>多个代码块时取第一个含 JSON 的</li>
 *   <li>JSON 里的 // 行注释与 /* *\/ 块注释（CSS 字符串内的注释原样保留）</li>
 *   <li>尾逗号 ,} / ,]</li>
 *   <li>字符串值里的裸换行/裸回车（转成空格，CSS 里等价）</li>
 *   <li>format 大小写、version 写成字符串 "1"、字段名用单引号</li>
 *   <li>BOM、UTF-8 前后空白</li>
 * </ol>
 *
 * <p><b>不做</b>：不补全缺失的引号/括号、不猜语义字段——结构真坏了就交给
 * ThemeValidator 给出明确错误，不做「猜着修」这种赌博式容错。
 */
final class ThemeText {

    private ThemeText() {
    }

    /** 把任意 AI 输出归一成可被 JSONObject 解析的纯 JSON 文本。 */
    static String clean(String raw) {
        if (raw == null) return "";
        String s = raw;
        if (s.length() > 0 && s.charAt(0) == '\uFEFF') s = s.substring(1);
        s = extractCodeBlock(s);
        s = extractJsonObject(s);
        s = sanitize(s);
        return s.trim();
    }

    /**
     * 取代码块内容。AI 常输出 ```json 而不是 ```actheme，早期脱壳只认 actheme
     * 或「整段以 ``` 开头结尾」，于是带解释文字时必然解析失败。
     *
     * <p>扫描全部围栏：优先取「同时含 format 字段与 {」的块（最可信的主题主体），
     * 退而求其次取第一个含 { 的块（防止 AI 先给一段 CSS 示例再给完整主题）。
     * 若根本没有围栏、或围栏内都没有 {，返回原文交给 extractJsonObject 处理。
     */
    private static String extractCodeBlock(String s) {
        int scan = 0;
        String firstWithBrace = null;
        while (scan < s.length()) {
            int fence = s.indexOf("```", scan);
            if (fence < 0) break;
            // 跳过围栏后的语言标记那一行
            int lineEnd = s.indexOf('\n', fence + 3);
            int start = lineEnd >= 0 ? lineEnd + 1 : fence + 3;
            int end = s.indexOf("```", start);
            if (end < 0) end = s.length();
            String block = s.substring(start, end);
            if (block.indexOf('{') >= 0) {
                String low = block.toLowerCase();
                if (low.contains("\"format\"") || low.contains("'format'")) {
                    return block;
                }
                if (firstWithBrace == null) firstWithBrace = block;
            }
            if (end >= s.length()) break;
            scan = end + 3;
        }
        return firstWithBrace != null ? firstWithBrace : s;
    }

    /**
     * 去掉 JSON 前后的解释文字：找第一个 '{'，按花括号配平取到对应的 '}'。
     * 配平时跳过字符串内的括号（同时兼容 AI 用单引号写字段/值的情况），
     * 避免 CSS 里的 { } 干扰。
     */
    private static String extractJsonObject(String s) {
        int start = s.indexOf('{');
        if (start < 0) return s;
        int depth = 0;
        char quote = 0;            // 0=不在字符串；'"'=双引号串；'\''=单引号串
        boolean esc = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '"' || c == '\'') quote = c;
            else if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return s.substring(start, i + 1);
            }
        }
        return s.substring(start);   // 不闭合：交给 JSONObject 报明确错误
    }

    /**
     * 逐字符清洗。核心是区分「字符串内 / 字符串外」：
     * 注释、尾逗号、单引号字段名只在字符串外处理；CSS 字符串里的 // 和 /* *\/
     * 必须原样保留，否则会把用户主题样式改坏。
     */
    private static String sanitize(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean inStr = false, esc = false, inSingle = false;
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);

            if (inStr) {
                if (esc) {
                    out.append(c);
                    esc = false;
                    i++;
                    continue;
                }
                if (c == '\\') {
                    out.append(c);
                    esc = true;
                    i++;
                    continue;
                }
                if (c == '"') {
                    out.append(c);
                    inStr = false;
                    i++;
                    continue;
                }
                // JSON 字符串里的裸换行/裸回车非法；CSS 中换行等价于空白，转空格
                if (c == '\n' || c == '\r' || c == '\t') out.append(' ');
                else out.append(c);
                i++;
                continue;
            }

            if (inSingle) {
                if (esc) {
                    out.append(c);
                    esc = false;
                    i++;
                    continue;
                }
                if (c == '\\') {
                    out.append(c);
                    esc = true;
                    i++;
                    continue;
                }
                if (c == '\'') {
                    out.append('"');       // 单引号字段/值统一转双引号
                    inSingle = false;
                    i++;
                    continue;
                }
                if (c == '"') out.append("\\\"");   // 单引号串里的双引号要转义
                else if (c == '\n' || c == '\r') out.append(' ');
                else out.append(c);
                i++;
                continue;
            }

            // ---- 字符串外 ----
            if (c == '"') {
                out.append(c);
                inStr = true;
                i++;
                continue;
            }
            if (c == '\'') {
                // 只把「成对且内部无双引号」的单引号串转成合法 JSON 字符串
                int close = findSingleClose(s, i + 1);
                if (close > i && s.substring(i + 1, close).indexOf('"') < 0) {
                    out.append('"');
                    inSingle = true;
                    i++;
                    continue;
                }
                out.append(c);
                i++;
                continue;
            }
            // // 行注释
            if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                i += 2;
                while (i < s.length() && s.charAt(i) != '\n') i++;
                continue;
            }
            // /* */ 块注释
            if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                int end = s.indexOf("*/", i + 2);
                i = end < 0 ? s.length() : end + 2;
                continue;
            }
            // 尾逗号：逗号后面（跳过空白）紧跟 } 或 ] 就把逗号丢掉
            if (c == ',') {
                int j = i + 1;
                while (j < s.length() && Character.isWhitespace(s.charAt(j))) j++;
                if (j < s.length() && (s.charAt(j) == '}' || s.charAt(j) == ']')) {
                    i++;
                    continue;
                }
                out.append(c);
                i++;
                continue;
            }
            out.append(c);
            i++;
        }
        String r = out.toString();
        // format 字段名与值都归一化：AI 会写 "Format":"ACTHEME"、"format":"AiChat Theme"。
        // 值里只要含 actheme（忽略大小写）就归一成标准值；字段名统一回小写，
        // 否则 Theme.parse 的 FORMAT.equals(...) 严格比较会失败。
        r = r.replaceAll("(?i)\"format\"(\\s*:\\s*)\"[^\"]*actheme[^\"]*\"",
                "\"format\"$1\"actheme\"");
        // version 字段名大小写 + 写成字符串 "1" 两种情况都接受
        r = r.replaceAll("(?i)\"version\"(\\s*:\\s*)\"(\\d+)\"",
                "\"version\"$1$2");
        return r;
    }

    private static int findSingleClose(String s, int from) {
        boolean esc = false;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (esc) { esc = false; continue; }
            if (c == '\\') { esc = true; continue; }
            if (c == '\'') return i;
            if (c == '\n' || c == '\r') return -1;
        }
        return -1;
    }
}
