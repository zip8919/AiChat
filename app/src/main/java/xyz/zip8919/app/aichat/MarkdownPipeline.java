package xyz.zip8919.app.aichat;

import android.content.Context;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableBody;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;

/**
 * Markdown → HTML 流水线：代码块保护 → SVG 保护 → 公式替换（交给
 * {@link MathRenderer}）→ 扩展语法预处理 → commonmark 解析 → 占位符回填。
 *
 * <p>从 {@link MessageHtmlRenderer} 抽出（约 280 行）。顺序是有依赖的，不能随意调换：
 * <ol>
 *   <li>先屏蔽围栏/行内代码，否则公式与扩展语法会改写代码内容；</li>
 *   <li>再屏蔽原始 SVG（commonmark 不把 {@code <svg>} 当 HTML 块，会拆散标签）；</li>
 *   <li>公式与 SVG 都以 {@code @@MATHn@@} / {@code @@SVGn@@} 占位，解析后一次性回填。</li>
 * </ol>
 */
final class MarkdownPipeline {

    private static final String TAG = "MarkdownPipeline";

    private static final Parser PARSER = Parser.builder()
            .extensions(Arrays.asList(
                    StrikethroughExtension.create(),
                    TablesExtension.create()))
            .build();

    private MarkdownPipeline() {
    }
    static String render(String text, Context ctx) {
        List<String> mathTags = new ArrayList<>();
        float density = ctx.getResources().getDisplayMetrics().density;

        // Mask fenced code blocks and inline code spans first, so the math /
        // extension preprocessing below can never rewrite their contents.
        List<String> codeBlocks = new ArrayList<>();
        text = protectCode(text, codeBlocks);

        // 0. Protect raw SVG blocks from markdown parsing — commonmark doesn't treat <svg> as HTML block
        List<String> svgBlocks = new ArrayList<>();
        text = extractAndProtectSvg(text, svgBlocks);

        // 代码块与 SVG 已在上两步换成哨兵，其中的 $ / \ 不会漏进来。
        // 两者都没有时，下面三趟逐字符预处理输出恒等于输入，流式每 200ms
        // 全量重跑纯属白做 3 次全文扫描 + 3 个 StringBuilder。
        if (text.indexOf('$') >= 0 || text.indexOf('\\') >= 0) {
            text = MathRenderer.extractAndRenderCe(text, density, mathTags);   // \ce → LaTeX → jlatexmath image
            text = MathRenderer.extractAndRenderLatex(text, density, mathTags);
            text = MathRenderer.renderBareLatexCommands(text, density, mathTags);
        }
        text = preProcessExtensions(text);
        text = restoreCode(text, codeBlocks);
        Node document = PARSER.parse(text);
        StringBuilder html = new StringBuilder();
        document.accept(new HtmlVisitor(html));
        String result = backfillPlaceholders(html.toString(), mathTags, svgBlocks);
        LogUtil.v(TAG, "renderMarkdown: %d -> %d chars, %d math formulas",
                text.length(), result.length(), mathTags.size());
        return result;
    }
    private static final char CODE_MASK_START = '\uE000';
    private static final char CODE_MASK_END = '\uE001';
    // Replace fenced code blocks (``` / ~~~) and inline code spans (`...`) with
    // opaque sentinels so preprocessing leaves them byte-for-byte intact. The
    // originals are restored just before commonmark parses, so code blocks still
    // render as code and never have their contents interpreted as markdown/math.
    private static String protectCode(String text, List<String> codeBlocks) {
        StringBuilder out = new StringBuilder(text.length() + 64);
        int i = 0, len = text.length();
        boolean inFence = false;
        char fenceChar = 0;
        int fenceLen = 0;
        int fenceStart = -1;
        while (i < len) {
            // ---- 围栏代码块内部 ----
            // 关键：块内内容一个字都不能写进 out，否则它会在 restoreCode 之后
            // 既留在原地（被 commonmark 当成 HTML 块渲染）又随哨兵重复出现。
            // 这里只在块结束时写一个哨兵，块内容整体保存在 codeBlocks 里。
            if (inFence) {
                if (i == 0 || text.charAt(i - 1) == '\n') {
                    int j = i, indent = 0;
                    while (j < len && text.charAt(j) == ' ' && indent < 3) { j++; indent++; }
                    if (j < len && text.charAt(j) == fenceChar) {
                        int run = 0, k = j;
                        while (k < len && text.charAt(k) == fenceChar) { run++; k++; }
                        int m = k;
                        while (m < len && (text.charAt(m) == ' ' || text.charAt(m) == '\t')) m++;
                        if (run >= fenceLen && (m >= len || text.charAt(m) == '\n')) {
                            int end = (m < len) ? m + 1 : m;
                            codeBlocks.add(text.substring(fenceStart, end));
                            out.append(CODE_MASK_START).append(codeBlocks.size() - 1).append(CODE_MASK_END);
                            i = end;
                            inFence = false;
                            continue;
                        }
                    }
                }
                int nl = text.indexOf('\n', i);
                i = (nl < 0) ? len : nl + 1;
                continue;
            }

            if (i == 0 || text.charAt(i - 1) == '\n') {
                int j = i, indent = 0;
                while (j < len && text.charAt(j) == ' ' && indent < 3) { j++; indent++; }
                if (j < len && (text.charAt(j) == '`' || text.charAt(j) == '~')) {
                    char cc = text.charAt(j);
                    int run = 0, k = j;
                    while (k < len && text.charAt(k) == cc) { run++; k++; }
                    if (run >= 3) {
                        inFence = true;
                        fenceChar = cc;
                        fenceLen = run;
                        fenceStart = i;
                        int nl = text.indexOf('\n', k);
                        i = (nl < 0) ? len : nl + 1;
                        continue;
                    }
                }
            }
            if (!inFence && text.charAt(i) == '`') {
                int run = 0, k = i;
                while (k < len && text.charAt(k) == '`') { run++; k++; }
                int closeEnd = -1, s = k;
                while (s < len) {
                    if (text.charAt(s) == '`') {
                        int r2 = 0, t = s;
                        while (t < len && text.charAt(t) == '`') { r2++; t++; }
                        if (r2 == run) { closeEnd = t; break; }
                        s = t;
                    } else {
                        s++;
                    }
                }
                if (closeEnd >= 0) {
                    codeBlocks.add(text.substring(i, closeEnd));
                    out.append(CODE_MASK_START).append(codeBlocks.size() - 1).append(CODE_MASK_END);
                    i = closeEnd;
                } else {
                    out.append(text, i, k);
                    i = k;
                }
                continue;
            }
            out.append(text.charAt(i));
            i++;
        }
        if (inFence && fenceStart >= 0) {
            codeBlocks.add(text.substring(fenceStart));
            out.append(CODE_MASK_START).append(codeBlocks.size() - 1).append(CODE_MASK_END);
        }
        return out.toString();
    }
    private static String restoreCode(String text, List<String> codeBlocks) {
        if (codeBlocks.isEmpty()) return text;
        StringBuilder out = new StringBuilder(text.length());
        int i = 0, len = text.length();
        while (i < len) {
            char c = text.charAt(i);
            if (c == CODE_MASK_START) {
                int j = i + 1, idx = 0;
                boolean any = false;
                while (j < len && text.charAt(j) >= '0' && text.charAt(j) <= '9') {
                    idx = idx * 10 + (text.charAt(j) - '0');
                    any = true;
                    j++;
                }
                if (any && j < len && text.charAt(j) == CODE_MASK_END && idx < codeBlocks.size()) {
                    out.append(codeBlocks.get(idx));
                    i = j + 1;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }
    // Extract <svg ...>...</svg> blocks (case-insensitive) and replace with placeholders
    // so commonmark doesn't mangle them (svg is not a recognized HTML block tag).
    // Skips SVGs inside fenced code blocks (```) and inline code spans (`).
    private static String extractAndProtectSvg(String text, List<String> svgBlocks) {
        int svgStart = svgBlocks.size();
        StringBuilder out = new StringBuilder();
        int i = 0, len = text.length();
        boolean inFence = false;
        boolean inInlineCode = false;
        while (i < len) {
            char c = text.charAt(i);
            // Track fenced code block boundaries
            if (text.startsWith("```", i)) {
                inFence = !inFence;
                out.append("```");
                i += 3;
                // skip language info on the same line
                while (i < len && text.charAt(i) != '\n') { out.append(text.charAt(i)); i++; }
                continue;
            }
            // Track inline code spans
            if (c == '`' && !inFence) {
                // Count consecutive backticks
                int bt = 0, j = i;
                while (j < len && text.charAt(j) == '`') { bt++; j++; }
                if (bt == 1) {
                    inInlineCode = !inInlineCode;
                    out.append('`');
                    i++;
                    continue;
                }
                // Multi-backtick sequences (like ``) are rare inline code delimiters;
                // just copy them through and don't toggle state for simplicity.
            }
            if (!inFence && !inInlineCode
                    && text.regionMatches(true, i, "<svg", 0, 4)
                    && (i + 4 >= len || isTagBoundary(text.charAt(i + 4)))) {
                int start = i;
                i += 4;
                int depth = 1;
                while (i < len && depth > 0) {
                    if (text.regionMatches(true, i, "<svg", 0, 4)
                            && (i + 4 >= len || isTagBoundary(text.charAt(i + 4)))) {
                        depth++;
                    } else if (text.regionMatches(true, i, "</svg>", 0, 6)) {
                        depth--;
                        if (depth == 0) { i += 6; break; }
                    }
                    i++;
                }
                svgBlocks.add(text.substring(start, i));
                out.append("@@SVG").append(svgBlocks.size() - 1).append("@@");
            } else {
                out.append(c);
                i++;
            }
        }
        if (svgBlocks.size() > svgStart) {
            LogUtil.v(TAG, "extractAndProtectSvg: extracted %d svg block(s)", svgBlocks.size() - svgStart);
        }
        return out.toString();
    }
    private static boolean isTagBoundary(char c) {
        return c == '>' || c == ' ' || c == '\n' || c == '\t' || c == '\r';
    }
    // Single-pass placeholder backfill: scans left to right for "@@" tokens and
    // replaces @@MATHn@@ / @@SVGn@@ in one pass instead of a whole-string replace()
    // per entry. Unknown tokens, out-of-range indexes and null replacements are
    // kept literally, matching the previous sequential replace() semantics.
    private static String backfillPlaceholders(String result, List<String> mathTags, List<String> svgBlocks) {
        StringBuilder out = new StringBuilder(result.length() + 256);
        int pos = 0;
        int len = result.length();
        while (pos < len) {
            int at = result.indexOf("@@", pos);
            if (at < 0) {
                out.append(result, pos, len);
                break;
            }
            out.append(result, pos, at);
            int p = at + 2;
            int q = p;
            while (q < len && Character.isLetter(result.charAt(q))) q++;
            String kind = result.substring(p, q);
            int d = q;
            while (d < len && result.charAt(d) >= '0' && result.charAt(d) <= '9') d++;
            int idx = -1;
            if ((kind.equals("MATH") || kind.equals("SVG")) && d > q && d + 1 < len
                    && result.charAt(d) == '@' && result.charAt(d + 1) == '@'
                    && (d == q + 1 || result.charAt(q) != '0')) {
                try {
                    idx = Integer.parseInt(result.substring(q, d));
                } catch (NumberFormatException nfe) {
                    idx = -1;
                }
            }
            if (idx >= 0 && idx < mathTags.size() && mathTags.get(idx) != null
                    && kind.equals("MATH")) {
                out.append(mathTags.get(idx));
                pos = d + 2;
                continue;
            }
            if (idx >= 0 && idx < svgBlocks.size() && svgBlocks.get(idx) != null
                    && kind.equals("SVG")) {
                String svgHtml = svgBlocks.get(idx);
                // Inject width constraint directly on the SVG element so API 18
                // respects it even when CSS doesn't override presentational attrs
                if (svgHtml.toLowerCase().contains(" style=\"")) {
                    svgHtml = svgHtml.replaceFirst("(?i) style=\"", " style=\"max-width:100%;width:100%;height:auto;");
                } else {
                    svgHtml = svgHtml.replaceFirst("(?i)<svg", "<svg style=\"max-width:100%;width:100%;height:auto;\"");
                }
                out.append("<div class=\"svg-scroll\">").append(svgHtml).append("</div>");
                pos = d + 2;
                continue;
            }
            out.append("@@");
            pos = at + 2;
        }
        return out.toString();
    }
    // Pre-compiled: preProcessExtensions runs on every markdown render (hot path)
    private static final Pattern P_FN_DEF =
            Pattern.compile("(?m)^\\[\\^([^\\]]+)\\]:\\s*(.+)$");
    private static final Pattern P_FN_REF = Pattern.compile("\\[\\^([^\\]]+)\\]");
    private static final Pattern P_MARK =
            Pattern.compile("==([^=\\s].*?[^=\\s]|[^=\\s])==");
    private static final Pattern P_SUP = Pattern.compile("\\^([^\\^\\s]+)\\^");
    private static final Pattern P_SUB = Pattern.compile("(?<![~])~([^~\\s]+)~(?!~)");
    private static String preProcessExtensions(String text) {
        // 五个 Pattern 分别需要 ^ / ~ / ==；都没有时 replaceAll 全是空转
        // （每个都要全文扫描 + Matcher 分配）。
        if (text.indexOf('^') < 0 && text.indexOf('~') < 0
                && text.indexOf("==") < 0) return text;
        // Footnote definitions [^id]: text at line start → hidden ref div
        text = P_FN_DEF.matcher(text).replaceAll(
                "<div class=\"fn-def\" id=\"fn-$1\"><sup><a href=\"#fnref-$1\">[$1]</a></sup> $2</div>");
        // Footnote references [^id] → superscript link
        text = P_FN_REF.matcher(text).replaceAll(
                "<sup class=\"fn-ref\" id=\"fnref-$1\"><a href=\"#fn-$1\">[$1]</a></sup>");
        // ==highlight== → <mark>
        text = P_MARK.matcher(text).replaceAll("<mark>$1</mark>");
        // ^superscript^ → <sup>
        text = P_SUP.matcher(text).replaceAll("<sup>$1</sup>");
        // ~subscript~ → <sub> (single ~ not ~~)
        text = P_SUB.matcher(text).replaceAll("<sub>$1</sub>");
        return text;
    }
    static String buildPage(String bodyHtml, int olderCount) {
        // olderCount = 尚未渲染的更早消息条数；>0 时顶部显示上滑提示
        String hint = olderCount > 0
                ? "<div id=\"older-hint\" onclick=\"loadOlderNow()\">上滑加载更早的 "
                        + olderCount + " 条消息</div>"
                : "<div id=\"older-hint\" style=\"display:none\"></div>";

        // 三段式样式（全局规则 R3）：Chromium 30 不支持 CSS 变量，主题色靠
        // 「基础 CSS → token 规则 → 用户 CSS」的追加顺序覆盖。后两段带 id，
        // WebViewRefresher 只替换它们的 textContent 即可即时换肤，不重灌整页。
        AppearanceManager m = AppearanceManager.getInstance();
        String themeCss = ThemeCssBuilder.themeStyleContent(m, m.isDark());
        String fontCss = ThemeCssBuilder.fontStyleContent(m);

        return "<!DOCTYPE html>\n<html>\n<head>\n" +
               "<meta charset=\"utf-8\">\n" +
               "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,user-scalable=no\">\n" +
               "<style>\n" + RendererAssets.CSS + "\n</style>\n" +
               "<style id=\"ac-theme\">\n" + themeCss + "\n</style>\n" +
               "<style id=\"ac-font\">\n" + fontCss + "\n</style>\n" +
               "</head>\n<body>\n" +
               hint +
               "<div id=\"msgs\">\n" + bodyHtml + "\n</div>\n" +
               "<script>\n" + RendererAssets.JS + "\n</script>\n" +
               "</body>\n</html>";
    }
    // ---- AST → HTML visitor ----

    private static class HtmlVisitor extends AbstractVisitor {
        final StringBuilder out;

        HtmlVisitor(StringBuilder out) { this.out = out; }

        @Override public void visit(Document d) { visitChildren(d); }
        @Override public void visit(Heading h) {
            String tag = "h" + h.getLevel();
            out.append('<').append(tag).append('>');
            visitChildren(h);
            out.append("</").append(tag).append('>');
        }
        @Override public void visit(Paragraph p) {
            out.append("<p>"); visitChildren(p); out.append("</p>");
        }
        @Override public void visit(Text t) { out.append(Html.esc(t.getLiteral())); }
        @Override public void visit(HtmlInline hi) { out.append(hi.getLiteral()); }
        @Override public void visit(HtmlBlock hb) { out.append(hb.getLiteral()); }
        @Override public void visit(Emphasis e) {
            out.append("<em>"); visitChildren(e); out.append("</em>");
        }
        @Override public void visit(StrongEmphasis se) {
            out.append("<strong>"); visitChildren(se); out.append("</strong>");
        }
        @Override public void visit(Code c) {
            out.append("<code>").append(Html.esc(c.getLiteral())).append("</code>");
        }
        @Override public void visit(FencedCodeBlock fcb) {
            LogUtil.v(TAG, "visit FencedCodeBlock: lang=%s", fcb.getInfo());
            out.append("<pre>");
            String info = fcb.getInfo();
            if (info != null && !info.isEmpty())
                out.append("<span class=\"lang-tag\">").append(Html.esc(info)).append("</span>");
            out.append("<code>");
            String code = fcb.getLiteral();
            if (code != null) {
                if (code.endsWith("\n")) code = code.substring(0, code.length() - 1);
                out.append(info != null && !info.isEmpty()
                    ? CodeHighlighter.highlight(code, info) : Html.esc(code));
            }
            out.append("</code></pre>");
        }
        @Override public void visit(IndentedCodeBlock icb) {
            out.append("<pre><code>");
            String code = icb.getLiteral();
            if (code != null) {
                if (code.endsWith("\n")) code = code.substring(0, code.length() - 1);
                out.append(Html.esc(code));
            }
            out.append("</code></pre>");
        }
        @Override public void visit(BlockQuote bq) {
            out.append("<blockquote>"); visitChildren(bq); out.append("</blockquote>");
        }
        @Override public void visit(BulletList bl) {
            out.append("<ul>"); visitChildren(bl); out.append("</ul>");
        }
        @Override public void visit(OrderedList ol) {
            out.append("<ol start=\"").append(ol.getStartNumber()).append("\">");
            visitChildren(ol); out.append("</ol>");
        }
        @Override public void visit(ListItem li) {
            out.append("<li>"); visitChildren(li); out.append("</li>");
        }
        @Override public void visit(ThematicBreak tb) { out.append("<hr>"); }
        @Override public void visit(Link l) {
            out.append("<a href=\"").append(Html.esc(l.getDestination())).append("\">");
            visitChildren(l); out.append("</a>");
        }
        @Override public void visit(Image img) {
            out.append("<img src=\"").append(Html.esc(img.getDestination()))
               .append("\" alt=\"").append(img.getTitle() != null ? Html.esc(img.getTitle()) : "")
               .append("\" style=\"max-width:100%;height:auto;\">");
        }
        @Override public void visit(SoftLineBreak slb) { out.append(' '); }
        @Override public void visit(HardLineBreak hlb) { out.append("<br>"); }

        // GFM extension nodes
        @Override public void visit(CustomBlock cb) {
            if (cb instanceof TableBlock) visitTable((TableBlock) cb);
            else visitChildren(cb);
        }
        @Override public void visit(CustomNode cn) {
            if (!(cn instanceof TableHead) && !(cn instanceof TableBody)
                    && !(cn instanceof TableRow) && !(cn instanceof TableCell)
                    && !(cn instanceof Strikethrough)) {
                LogUtil.v(TAG, "visit CustomNode: unknown %s", cn == null ? "null" : cn.getClass().getSimpleName());
            }
            if (cn instanceof TableHead) visitTableHead((TableHead) cn);
            else if (cn instanceof TableBody) visitTableBody((TableBody) cn);
            else if (cn instanceof TableRow) visitTableRow((TableRow) cn);
            else if (cn instanceof TableCell) visitTableCell((TableCell) cn);
            else if (cn instanceof Strikethrough) visitStrikethrough((Strikethrough) cn);
            else visitChildren(cn);
        }

        private void visitTable(TableBlock tb) {
            out.append("<div class=\"table-scroll\"><table>");
            visitChildren(tb);
            out.append("</table></div>");
        }
        private void visitTableHead(TableHead th) {
            out.append("<thead>"); visitChildren(th); out.append("</thead>");
        }
        private void visitTableBody(TableBody tb) {
            out.append("<tbody>"); visitChildren(tb); out.append("</tbody>");
        }
        private void visitTableRow(TableRow tr) {
            out.append("<tr>"); visitChildren(tr); out.append("</tr>");
        }
        private void visitTableCell(TableCell tc) {
            String tag = tc.isHeader() ? "th" : "td";
            out.append('<').append(tag).append('>');
            visitChildren(tc);
            out.append("</").append(tag).append('>');
        }
        private void visitStrikethrough(Strikethrough s) {
            out.append("<del>"); visitChildren(s); out.append("</del>");
        }
    }
}
