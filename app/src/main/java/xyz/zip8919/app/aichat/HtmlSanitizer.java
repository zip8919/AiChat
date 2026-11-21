package xyz.zip8919.app.aichat;

/**
 * 模型输出里「裸写」的 HTML 的安全处理。
 *
 * <p>markdown 里的原始 HTML 块（{@code HtmlBlock} / {@code HtmlInline}）过去是原样拼进
 * 聊天页的。模型写「给我做一个页面」这类回答时经常不带围栏直接输出
 * {@code <style>…</style>}、{@code <script>…</script>}、{@code <input>}，
 * 拼进来就是：
 * <ul>
 *   <li>整页样式被改写（背景变白、字号行高全变、气泡被挤变形）；</li>
 *   <li>{@code <div style="position:fixed;…">} 盖住整屏，点不到任何东西；</li>
 *   <li>{@code <script>} 直接执行，在 4.4 的 WebView 上能把页面拖死；</li>
 *   <li>{@code onerror=} / {@code href="javascript:…"} 一类注入。</li>
 * </ul>
 *
 * <p>处理原则：<b>不猜模型意图，只保证页面不变形</b>。
 * <ul>
 *   <li>块里出现成体系的页面元素（style/script/html/head/body/iframe/form/input 等）：
 *       整块转义成代码块，内容一个字不少，用户看到的就是「这段是代码」；</li>
 *   <li>其余零散标签：保留标签本身（{@code <br>}、{@code <details>} 这类有用），
 *       去掉事件属性、脚本式链接与能改布局的 style 声明；</li>
 *   <li>标签之间的文本原样带过、绝不二次转义：{@code &lt;} 再转一次会变成
 *       {@code &amp;lt;，屏幕上直接露出「&lt;div&gt;」这样的标签源码。</li>
 * </ul>
 *
 * <p><b>两条路径，别混用</b>：本类只处理「模型写在回答里的原始 HTML」。
 * 应用自己拼出来的界面 HTML（思考折叠条、工具卡片、代码块）不经过这里——
 * 那些标签是界面的骨架，套一遍过滤器会把 {@code details}/{@code summary}/带 id 的
 * 元素剥掉，折叠区与卡片就消失了。
 */
final class HtmlSanitizer {

    private HtmlSanitizer() {
    }

    /** 出现这些就说明「这是一整页」，整块按代码展示。 */
    private static final String[] PAGE_MARKS = {
            "<script", "<style", "<html", "<head", "<body", "<iframe", "<object",
            "<embed", "<form", "<meta", "<link", "<base", "<frameset", "<applet",
    };

    /** 这些标签直接丢掉（连带内容由 {@link #PAGE_MARKS} 兜住，走到这里只剩单标签）。 */
    private static final String[] DROP_TAGS = {
            "script", "style", "iframe", "object", "embed", "title", "head", "body",
            "html", "base", "link", "meta", "form", "input", "button", "textarea",
            "select", "option", "optgroup", "frame", "frameset", "applet", "basefont",
            "marquee", "param", "source", "track", "template", "noscript", "colgroup",
    };

    /**
     * 允许原样重建的标签：都是「语义 + 排版」用途，改不了页面结构与交互。
     *
     * <p>白名单而不是黑名单，是为了让未知/拼错的标签按文本展示——模型想写
     * {@code <details>} 被写坏成 {@code <detail>} 时，用户看到的是这段源码，
     * 而不是一个半残的折叠框。
     */
    private static final String[] KEEP_TAGS = {
            // 行内语义
            "b", "strong", "i", "em", "u", "s", "del", "ins", "mark", "small", "big",
            "sub", "sup", "code", "kbd", "samp", "var", "abbr", "cite", "q", "dfn",
            "span", "a", "br", "wbr", "img", "ruby", "rt", "rp", "bdi", "bdo",
            // 块级排版
            "p", "div", "blockquote", "pre", "hr", "ul", "ol", "li", "dl", "dt", "dd",
            "h1", "h2", "h3", "h4", "h5", "h6", "figure", "figcaption",
            // 表格
            "table", "caption", "thead", "tbody", "tfoot", "tr", "th", "td",
            "col", "colgroup",
            // 折叠区
            "details", "summary",
            // 媒体（只留安全 src）
            "video", "audio", "picture",
    };

    /** style 属性里允许保留的声明：纯排版，动不了页面结构。 */
    private static final String[] SAFE_STYLE_PROPS = {
            "color", "background-color", "font-size", "font-weight", "font-style",
            "font-family", "text-align", "text-decoration", "line-height",
            "vertical-align", "letter-spacing", "white-space", "word-break",
            "border-radius", "border-color", "border-style", "border-width",
            "list-style", "opacity",
    };

    /**
     * 块级原始 HTML 的安全化。
     *
     * @param raw commonmark 给的原始片段
     * @return 可以直接拼进页面 HTML 的片段
     */
    static String block(String raw) {
        if (raw == null || raw.length() == 0) return "";
        if (raw.indexOf('<') < 0) return raw;
        if (looksLikePage(raw)) {
            // 整块当代码：一个字符都不丢，但一行样式/脚本也进不了页面
            return "<pre class=\"raw-html\"><code>" + Html.esc(raw) + "</code></pre>";
        }
        return filterTags(raw);
    }

    /** 行内原始 HTML 的安全化：inline 场合不能塞 block 元素，危险内容按文本展示。 */
    static String inline(String raw) {
        if (raw == null || raw.length() == 0) return "";
        if (raw.indexOf('<') < 0) return raw;
        if (looksLikePage(raw)) return Html.esc(raw);
        return filterTags(raw);
    }

    private static boolean looksLikePage(String raw) {
        String low = raw.toLowerCase();
        for (int i = 0; i < PAGE_MARKS.length; i++) {
            if (low.indexOf(PAGE_MARKS[i]) >= 0) return true;
        }
        return false;
    }

    /**
     * 逐标签过滤：丢危险标签、丢事件属性、丢脚本式链接、丢改布局的 style 声明。
     *
     * <p>只重建标签本身，标签之间的文本原样带过（那里可能含实体，再转义一次会变成
     * {@code &amp;amp;}）。未闭合的 {@code <}（流式中间态）原样留着，浏览器按文本处理。
     */
    private static String filterTags(String raw) {
        int n = raw.length();
        StringBuilder out = new StringBuilder(n + 16);
        int i = 0;
        while (i < n) {
            int lt = raw.indexOf('<', i);
            if (lt < 0) {
                out.append(raw, i, n);
                break;
            }
            if (lt > i) out.append(raw, i, lt);
            int gt = tagEnd(raw, lt + 1);
            if (gt < 0) {
                // 还没写完的标签：留着，浏览器当文本
                out.append(raw, lt, n);
                break;
            }
            String tag = raw.substring(lt, gt + 1);
            String name = tagName(tag);
            if (name == null) {
                out.append(tag);          // 注释、doctype、<?xml>
            } else if (!isDrop(name) && isKnown(name)) {
                out.append(rebuild(tag, name));
            } else if (!isDrop(name)) {
                // 不认识的标签按文本展示：原样转义，能看见「这里原本是个标签」，
                // 又不会让浏览器按未知元素去猜结构
                out.append(Html.esc(tag));
            }
            // 丢掉危险标签（含成对标签的结束标签，如 </script>）
            i = gt + 1;
        }
        return out.toString();
    }

    private static boolean isKnown(String name) {
        for (int i = 0; i < KEEP_TAGS.length; i++) {
            if (KEEP_TAGS[i].equals(name)) return true;
        }
        return false;
    }

    private static boolean isDrop(String name) {
        for (int i = 0; i < DROP_TAGS.length; i++) {
            if (DROP_TAGS[i].equals(name)) return true;
        }
        return false;
    }

    /** 标签名（小写）；注释 / doctype / 处理指令返回 null（原样保留）。 */
    private static String tagName(String tag) {
        int n = tag.length();
        int i = 1;
        if (i < n && tag.charAt(i) == '/') i++;
        int s = i;
        while (i < n) {
            char c = tag.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9' && i > s) || c == '-' || c == ':' || c == '_') i++;
            else break;
        }
        if (i == s) return null;
        return tag.substring(s, i).toLowerCase();
    }

    /** {@code >} 的位置，跳过引号里的内容。 */
    private static int tagEnd(String s, int from) {
        char quote = 0;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                return i;
            }
        }
        return -1;
    }

    /** 重建一个标签：保留结构，过滤属性。 */
    private static String rebuild(String tag, String name) {
        StringBuilder sb = new StringBuilder(tag.length());
        sb.append('<');
        if (tag.length() > 1 && tag.charAt(1) == '/') sb.append('/');
        sb.append(name);
        int n = tag.length();
        int i = 1;
        if (i < n && tag.charAt(i) == '/') i++;
        while (i < n && tag.charAt(i) != ' ' && tag.charAt(i) != '\t'
                && tag.charAt(i) != '\n' && tag.charAt(i) != '\r') i++;

        boolean selfClose = false;
        while (i < n) {
            char c = tag.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                i++;
                continue;
            }
            if (c == '>') break;
            if (c == '/') {
                selfClose = true;
                i++;
                continue;
            }
            int nameStart = i;
            while (i < n) {
                char d = tag.charAt(i);
                if (d == '=' || d == '>' || d == '/' || d == ' ' || d == '\t'
                        || d == '\n' || d == '\r') break;
                i++;
            }
            String attr = tag.substring(nameStart, i);
            String low = attr.toLowerCase();
            String value = null;
            if (i < n && tag.charAt(i) == '=') {
                i++;
                if (i < n && (tag.charAt(i) == '"' || tag.charAt(i) == '\'')) {
                    char q = tag.charAt(i++);
                    int vs = i;
                    while (i < n && tag.charAt(i) != q) i++;
                    value = tag.substring(vs, Math.min(i, n));
                    if (i < n) i++;
                } else {
                    int vs = i;
                    while (i < n && " \t\n\r>".indexOf(tag.charAt(i)) < 0) i++;
                    value = tag.substring(vs, i);
                }
            }
            if (attr.length() == 0) continue;
            // 事件属性：onclick / onerror / onload …
            if (low.length() > 2 && low.charAt(0) == 'o' && low.charAt(1) == 'n') continue;
            if ("srcdoc".equals(low)) continue;
            String kept = attr;
            if (value != null) {
                if (isUrlAttr(low) && isScriptUrl(value)) continue;
                if ("style".equals(low)) {
                    String safe = safeStyle(value);
                    if (safe.length() == 0) continue;
                    value = safe;
                }
                kept = attr + "=\"" + value.replace("\"", "&quot;") + "\"";
            }
            sb.append(' ').append(kept);
        }
        if (selfClose) sb.append('/');
        sb.append('>');
        return sb.toString();
    }

    private static boolean isUrlAttr(String name) {
        return "href".equals(name) || "src".equals(name) || "action".equals(name)
                || "formaction".equals(name) || "xlink:href".equals(name)
                || "background".equals(name) || "poster".equals(name) || "data".equals(name);
    }

    private static boolean isScriptUrl(String value) {
        int i = 0, n = value.length();
        // 去掉前导空白与控制字符：java\tscript: 一样能跑
        StringBuilder sb = new StringBuilder(n);
        for (; i < n; i++) {
            char c = value.charAt(i);
            if (c <= ' ') continue;
            sb.append(c);
            if (sb.length() >= 12) break;
        }
        String head = sb.toString().toLowerCase();
        return head.startsWith("javascript:") || head.startsWith("vbscript:")
                || head.startsWith("data:text/html") || head.startsWith("data:image/svg");
    }

    /** style 属性只留纯排版声明。 */
    private static String safeStyle(String value) {
        StringBuilder sb = new StringBuilder();
        int i = 0, n = value.length();
        while (i < n) {
            int semi = value.indexOf(';', i);
            if (semi < 0) semi = n;
            int colon = value.indexOf(':', i);
            if (colon > i && colon < semi) {
                String prop = value.substring(i, colon).trim().toLowerCase();
                String val = value.substring(colon + 1, semi).trim();
                if (isSafeProp(prop) && val.length() > 0 && val.indexOf('(') < 0) {
                    if (sb.length() > 0) sb.append(';');
                    sb.append(prop).append(':').append(val);
                }
            }
            i = semi + 1;
        }
        return sb.toString();
    }

    private static boolean isSafeProp(String prop) {
        for (int i = 0; i < SAFE_STYLE_PROPS.length; i++) {
            if (SAFE_STYLE_PROPS[i].equals(prop)) return true;
        }
        return false;
    }
}
