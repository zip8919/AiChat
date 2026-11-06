package xyz.zip8919.app.aichat;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.noties.jlatexmath.JLatexMathDrawable;

/**
 * 数学公式渲染：{@code $...$} / {@code $$...$$} / {@code \ce{...}} / 裸 {@code \command}
 * 统一转成 jlatexmath 位图，再包成 SVG data-URI 内联进 HTML。
 *
 * <p>从 {@link MessageHtmlRenderer} 抽出（约 700 行）。这里全部是无状态静态方法，
 * 唯一的共享状态是公式位图缓存——同一公式在长对话里会被反复渲染，缓存按字符数
 * 做上限淘汰，避免 base64 图片把堆吃掉。
 *
 * <p>本类不碰 markdown AST，也不碰消息结构；输入输出都是纯字符串，
 * 可以直接用 JVM 桩单测（{@link #ceToLatex} 已按此设计）。
 */
final class MathRenderer {

    private static final String TAG = "MathRenderer";

    // 公式位图缓存：key = formula + 块级/行内标记。按字符数而非条目数限制，
    // 因为单条 base64 HTML 可达数十 KB。
    private static final int LATEX_CACHE_MAX_BYTES = 2 * 1024 * 1024;
    private static final Map<String, String> latexCache =
            Collections.synchronizedMap(new LinkedHashMap<String, String>());

    private MathRenderer() {
    }

    /** 当前缓存的公式条数，仅用于日志。 */
    static int cacheSize() {
        return latexCache.size();
    }
    // Extract \ce{...} → convert to LaTeX → render with jlatexmath → @@MATHn@@ image.
    static String extractAndRenderCe(String text, float density, List<String> mathTags) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            if (text.charAt(i) == '\\' && i + 3 < text.length()
                    && text.charAt(i + 1) == 'c' && text.charAt(i + 2) == 'e'
                    && text.charAt(i + 3) == '{'
                    && !insideBacktickSpan(out)) {
                i += 4;
                int depth = 1, cs = i;
                while (i < text.length() && depth > 0) {
                    if (text.charAt(i) == '{') depth++;
                    else if (text.charAt(i) == '}') depth--;
                    i++;
                }
                if (depth > 0) {
                    // 未找到配对 } (流式分片/截断)：原样输出剩余文本，避免 substring 越界
                    out.append(text, cs - 4, text.length());
                    break;
                }
                int ce = i;
                String formula = text.substring(cs, ce - 1);

                int pfx = stripMathWrapOut(text, ce, out);
                // stripMathWrapOut already trimmed the output buffer

                // Convert \ce content to proper LaTeX and render as image
                String latex = ceToLatex(formula);
                boolean isBlock = (pfx == 2); // $$ → block, $ or bare → inline
                String imgHtml = renderLatexImg(latex, isBlock, density);
                if (isBlock) {
                    out.append('\n').append(imgHtml).append("\n\n");
                } else {
                    mathTags.add(imgHtml);
                    out.append("@@MATH").append(mathTags.size() - 1).append("@@");
                }

                if (pfx > 0) i = skipMathSuffix(text, i, pfx);
            } else {
                out.append(text.charAt(i));
                i++;
            }
        }
        return out.toString();
    }
    // Convert \ce{} formula content to jlatexmath-compatible LaTeX.
    // - Subscripts: H2O → H_{2}O, C6H12O6 → C_{6}H_{12}O_{6}
    // - Superscripts: Ca^2+ → Ca^{2+}
    // - Isotopes: ^{227}_{90}Th → ^{227}_{90}Th (pass through)
    // - Arrows: -> → \rightarrow, <- → \leftarrow
    // - Arrow conditions: ->[above] → \xrightarrow{above}, ->[above][below] → \xrightarrow[below]{above}
    // - <=> → \rightleftharpoons, <=>[above] → \xrightleftharpoons{above}
    // - Bare \commands like \Delta pass through unchanged
    static String ceToLatex(String formula) {
        StringBuilder out = new StringBuilder();
        int i = 0, len = formula.length();
        boolean justHadLetter = false;

        while (i < len) {
            char c = formula.charAt(i);

            // Pass through LaTeX commands like \Delta, \text{...}, etc.
            if (c == '\\' && i + 1 < len && Character.isLetter(formula.charAt(i + 1))) {
                int start = i;
                i++;
                while (i < len && Character.isLetter(formula.charAt(i))) i++;
                String cmd = formula.substring(start, i);
                // Handle \text{...} and other commands with braces
                if (i < len && formula.charAt(i) == '{') {
                    i++;
                    int bd = 1, bs = i;
                    while (i < len && bd > 0) {
                        if (formula.charAt(i) == '{') bd++;
                        else if (formula.charAt(i) == '}') bd--;
                        i++;
                    }
                    String arg = formula.substring(bs, i - 1);
                    if (cmd.equals("\\text")) {
                        out.append("\\text{").append(arg).append("}");
                    } else {
                        out.append(cmd).append("{").append(arg).append("}");
                    }
                } else {
                    out.append(cmd);
                }
                justHadLetter = false;
                continue;
            }

            // Arrow with optional conditions: ->[above][below]
            boolean isArrow = false;
            boolean isBidirectional = false;
            String arrowCmd = null;
            int arrowLen = 0;

            if (c == '-' && i + 1 < len && formula.charAt(i + 1) == '>') {
                // ->
                arrowLen = 2;
                int j = i + 2;
                if (j < len && formula.charAt(j) == '[') arrowCmd = "\\xrightarrow";
                else arrowCmd = "\\rightarrow";
                isArrow = true;
            } else if (c == '<' && i + 1 < len && formula.charAt(i + 1) == '-') {
                // <-
                arrowLen = 2;
                int j = i + 2;
                if (j < len && formula.charAt(j) == '[') arrowCmd = "\\xleftarrow";
                else arrowCmd = "\\leftarrow";
                isArrow = true;
            } else if (c == '<' && i + 2 < len
                    && formula.charAt(i + 1) == '=' && formula.charAt(i + 2) == '>') {
                // <=>  reversible reaction arrow
                arrowLen = 3;
                arrowCmd = "\\leftrightarrows";
                isArrow = true;
                isBidirectional = true;
            }

            if (isArrow) {
                i += arrowLen;
                String above = null, below = null;
                if (i < len && formula.charAt(i) == '[') {
                    above = extractBracketArg(formula, i);
                    i += above.length() + 2;
                    if (above.contains("\\")) above = ceToLatex(above);
                }
                if (i < len && formula.charAt(i) == '[') {
                    below = extractBracketArg(formula, i);
                    i += below.length() + 2;
                    if (below.contains("\\")) below = ceToLatex(below);
                }
                if (isBidirectional) {
                    // <=>: stack conditions above/below with stackrel/underset.
                    // (X^{a}_{b} puts them as super/subscripts in inline mode.)
                    if (above != null && below != null) {
                        out.append("\\underset{\\text{").append(below)
                           .append("}}{\\stackrel{\\text{").append(above)
                           .append("}}{\\leftrightarrows}}");
                    } else if (above != null) {
                        out.append("\\stackrel{\\text{").append(above)
                           .append("}}{\\leftrightarrows}");
                    } else if (below != null) {
                        out.append("\\underset{\\text{").append(below)
                           .append("}}{\\leftrightarrows}");
                    } else {
                        out.append("\\leftrightarrows");
                    }
                } else {
                    // -> or <-  — use extensible arrow with conditions
                    if (above != null && below != null) {
                        out.append(arrowCmd).append("[").append(below)
                           .append("]{").append(above).append("}");
                    } else if (above != null) {
                        out.append(arrowCmd).append("{").append(above).append("}");
                    } else {
                        out.append(arrowCmd);
                    }
                }
                justHadLetter = false;
                continue;
            }

            // Superscript ^
            if (c == '^') {
                i++;
                out.append("^{");
                while (i < len && (Character.isDigit(formula.charAt(i))
                        || formula.charAt(i) == '+' || formula.charAt(i) == '-')) {
                    out.append(formula.charAt(i));
                    i++;
                }
                out.append("}");
                justHadLetter = false;
                continue;
            }

            // Subscript with _ (already LaTeX-compatible, just pass through)
            if (c == '_') {
                i++;
                out.append("_{");
                if (i < len && formula.charAt(i) == '{') {
                    i++;
                    int bd = 1;
                    while (i < len && bd > 0) {
                        if (formula.charAt(i) == '{') bd++;
                        else if (formula.charAt(i) == '}') bd--;
                        if (bd > 0) out.append(formula.charAt(i));
                        i++;
                    }
                } else {
                    while (i < len && Character.isDigit(formula.charAt(i))) {
                        out.append(formula.charAt(i));
                        i++;
                    }
                }
                out.append("}");
                justHadLetter = false;
                continue;
            }

            // Digit after element letter → subscript
            if (Character.isDigit(c) && justHadLetter) {
                out.append("_{");
                while (i < len && Character.isDigit(formula.charAt(i))) {
                    out.append(formula.charAt(i));
                    i++;
                }
                out.append("}");
                justHadLetter = false;
                continue;
            }

            if (Character.isLetter(c)) {
                out.append(c);
                justHadLetter = true;
                i++;
            } else {
                out.append(c);
                justHadLetter = false;
                i++;
            }
        }
        return out.toString();
    }
    // Extract content inside [brackets], handling nested brackets
    private static String extractBracketArg(String s, int start) {
        // start is index of the opening '['
        int depth = 1;
        int i = start + 1;
        while (i < s.length() && depth > 0) {
            if (s.charAt(i) == '[') depth++;
            else if (s.charAt(i) == ']') depth--;
            i++;
        }
        return s.substring(start + 1, i - 1); // content between [ and ]
    }
    // Detect and render bare \command or \command{args} that aren't inside $...$ or backticks
    static String renderBareLatexCommands(String text, float density, List<String> mathTags) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        boolean inBacktick = false;
        while (i < text.length()) {
            if (text.charAt(i) == '`') {
                inBacktick = !inBacktick;
                out.append('`');
                i++;
                continue;
            }
            if (!inBacktick && text.charAt(i) == '\\' && i + 1 < text.length()
                    && Character.isLetter(text.charAt(i + 1))) {
                int start = i;
                i++;
                while (i < text.length() && Character.isLetter(text.charAt(i))) i++;
                if (i - start < 3) { out.append(text, start, i); continue; }
                while (i < text.length() && text.charAt(i) == '{') {
                    int depth = 1; i++;
                    while (i < text.length() && depth > 0) {
                        if (text.charAt(i) == '{') depth++;
                        else if (text.charAt(i) == '}') depth--;
                        i++;
                    }
                }
                String html = renderLatexImg(text.substring(start, i), false, density);
                mathTags.add(html);
                out.append("@@MATH").append(mathTags.size() - 1).append("@@");
            } else {
                out.append(text.charAt(i));
                i++;
            }
        }
        return out.toString();
    }
    static String extractAndRenderLatex(String text, float density, List<String> mathTags) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        boolean inBacktick = false;
        while (i < text.length()) {
            // Track inline code spans — don't extract math inside backticks
            if (text.charAt(i) == '`') {
                inBacktick = !inBacktick;
                out.append('`');
                i++;
                continue;
            }
            if (!inBacktick && text.startsWith("$$", i)) {
                int end = text.indexOf("$$", i + 2);
                if (end > i) {
                    String html = renderLatexImg(text.substring(i + 2, end).trim(), true, density);
                    out.append('\n').append(html).append("\n\n");
                    i = end + 2; continue;
                }
            }
            if (!inBacktick && text.startsWith("\\[", i)) {
                int end = text.indexOf("\\]", i + 2);
                if (end > i) {
                    String html = renderLatexImg(text.substring(i + 2, end).trim(), true, density);
                    out.append('\n').append(html).append("\n\n");
                    i = end + 2; continue;
                }
            }
            if (!inBacktick && text.charAt(i) == '$' && i + 1 < text.length()
                    && text.charAt(i + 1) != '$'
                    && !isWhitespaceChar(text.charAt(i + 1))
                    && (i == 0 || text.charAt(i - 1) != '$')) {
                // Closing delimiter must sit on the same line, and the span must
                // not begin/end with whitespace — otherwise a stray "$" would
                // swallow unrelated text up to the next unrelated "$".
                int end = i + 1;
                while (end < text.length() && text.charAt(end) != '$'
                        && text.charAt(end) != '\n') end++;
                if (end < text.length() && text.charAt(end) == '$' && end > i + 1
                        && !isWhitespaceChar(text.charAt(end - 1))) {
                    String html = renderLatexImg(text.substring(i + 1, end), false, density);
                    mathTags.add(html);
                    out.append("@@MATH").append(mathTags.size() - 1).append("@@");
                    i = end + 1; continue;
                }
            }
            if (!inBacktick && text.startsWith("\\(", i)) {
                int end = text.indexOf("\\)", i + 2);
                if (end > i + 2) {
                    String html = renderLatexImg(text.substring(i + 2, end), false, density);
                    mathTags.add(html);
                    out.append("@@MATH").append(mathTags.size() - 1).append("@@");
                    i = end + 2; continue;
                }
            }
            out.append(text.charAt(i));
            i++;
        }
        return out.toString();
    }
    private static String renderLatexImg(String formula, boolean block, float density) {
        String key = formula + (block ? "b" : "i");
        String cached = latexCache.get(key);
        if (cached != null) {
            LogUtil.v(TAG, "latex CACHE hit: block=%s %s", block, LogUtil.preview(formula, 80));
            return cached;
        }
        LogUtil.v(TAG, "latex render: block=%s %s", block, LogUtil.preview(formula, 80));

        String imgTag = null;
        float scale = Math.max(density, 2.5f);
        float textSize = (block ? 24f : 18f) * scale;

        try {
            JLatexMathDrawable d = JLatexMathDrawable.builder(formula)
                    .textSize(textSize).background(0xFFFFFFFF).build();
            int w = d.getIntrinsicWidth();
            int h = d.getIntrinsicHeight();

            if (w > 0 && h > 0) {
                int maxW = (int)(400 * density);
                if (w > maxW) { float s = (float) maxW / w;
                    d = JLatexMathDrawable.builder(formula).textSize(textSize * s)
                            .background(0xFFFFFFFF).build();
                    w = maxW; h = (int)(h * s);
                }

                d.setBounds(0, 0, w, h);
                Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(bmp);
                c.drawColor(Color.WHITE);
                d.draw(c);

                // Render to base64 data URI — no file:// access needed
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.PNG, 90, baos);
                String b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
                bmp.recycle();

                String alt = Html.escAttr(formula);
                // Block: explicit display dimensions (w/scale) so API 18
                // doesn't fall back to 300×150 default. viewBox maps the
                // high-res bitmap to display size.
                // Inline: no width/height attrs — CSS height:1.6em;width:auto
                String svgNs = "http://www.w3.org/2000/svg";
                String xlinkNs = "http://www.w3.org/1999/xlink";
                if (block) {
                    int dispW = Math.round((float) w / scale);
                    int dispH = Math.round((float) h / scale);
                    if (dispW < 1) dispW = 1;
                    if (dispH < 1) dispH = 1;
                    imgTag = "<div class=\"math-block\">" +
                        "<svg xmlns=\"" + svgNs + "\" xmlns:xlink=\"" + xlinkNs + "\"" +
                        " width=\"" + dispW + "\" height=\"" + dispH + "\"" +
                        " viewBox=\"0 0 " + w + " " + h + "\"" +
                        " style=\"max-width:100%;\">" +
                        "<image width=\"" + w + "\" height=\"" + h + "\"" +
                        " xlink:href=\"data:image/png;base64," + b64 + "\"/>" +
                        "</svg></div>";
                } else {
                    imgTag = "<svg xmlns=\"" + svgNs + "\" xmlns:xlink=\"" + xlinkNs + "\"" +
                        " class=\"math-inline\"" +
                        " viewBox=\"0 0 " + w + " " + h + "\"" +
                        " style=\"height:1.6em;width:auto;vertical-align:middle;\">" +
                        "<image width=\"" + w + "\" height=\"" + h + "\"" +
                        " xlink:href=\"data:image/png;base64," + b64 + "\"/>" +
                        "</svg>";
                }
            }
        } catch (Exception e) {
            LogUtil.w(TAG, "latex render FAILED for '%s': %s -> fallback", LogUtil.preview(formula, 80), e.getMessage());
        }

        // Clean fallback — monospace code-style, not debug-yellow
        if (imgTag == null) {
            if (block) {
                imgTag = "<div class=\"math-block\" style=\"background:#f6f8fa;padding:10px;border:1px solid #d1d5da;border-radius:4px;\">"
                    + "<code style=\"font-size:14px;color:#555;\">" + Html.esc(formula) + "</code></div>";
            } else {
                imgTag = "<code style=\"font-size:0.9em;background:#f0f0f0;padding:1px 5px;border-radius:3px;color:#555;\">"
                    + Html.esc(formula) + "</code>";
            }
        }

        synchronized (latexCache) {
            latexCache.put(key, imgTag);
            int totalBytes = 0;
            for (String v : latexCache.values()) totalBytes += v.length();
            int evicted = 0;
            Iterator<Map.Entry<String, String>> it = latexCache.entrySet().iterator();
            while (totalBytes > LATEX_CACHE_MAX_BYTES && it.hasNext()) {
                Map.Entry<String, String> e = it.next();
                it.remove();
                totalBytes -= e.getValue().length();
                evicted++;
            }
            if (evicted > 0) {
                LogUtil.v(TAG, "latex cache EVICT %d oldest (size now %d)",
                        evicted, latexCache.size());
            }
        }
        LogUtil.v(TAG, "latex cached (size=%d): %d chars html", latexCache.size(), imgTag.length());
        return imgTag;
    }
    // Strip leading $$, \[, or $ from output buffer and return prefix length (0/1/2).
    // Only strips if the math wrapper contains only this command and the matching
    // closer immediately follows in text at cmdEnd.
    // Looks backward past whitespace/newlines to find the delimiter,
    // and handles output buffer trimming internally.
    private static int stripMathWrapOut(String text, int cmdEnd, StringBuilder out) {
        int end = out.length();
        // Skip trailing whitespace
        while (end > 0) {
            char c = out.charAt(end - 1);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') end--;
            else break;
        }
        // Check for "$$" prefix
        if (end >= 2 && out.charAt(end-2)=='$' && out.charAt(end-1)=='$') {
            int j = cmdEnd;
            while (j < text.length() && (text.charAt(j)==' ' || text.charAt(j)=='\t')) j++;
            if (j+1 < text.length() && text.charAt(j)=='$' && text.charAt(j+1)=='$') {
                out.setLength(end - 2);
                return 2;
            }
        }
        // Check for "\[" prefix (LaTeX display math)
        if (end >= 2 && out.charAt(end-2)=='\\' && out.charAt(end-1)=='[') {
            int j = cmdEnd;
            while (j < text.length() && (text.charAt(j)==' ' || text.charAt(j)=='\t' || text.charAt(j)=='\n')) j++;
            if (j+1 < text.length() && text.charAt(j)=='\\' && text.charAt(j+1)==']') {
                out.setLength(end - 2);
                return 2;
            }
        }
        // Check for single "$" prefix (but not "$$")
        if (end >= 1 && out.charAt(end-1)=='$' && (end < 2 || out.charAt(end-2)!='$')) {
            int j = cmdEnd;
            while (j < text.length() && (text.charAt(j)==' ' || text.charAt(j)=='\t')) j++;
            if (j < text.length() && text.charAt(j)=='$'
                    && (j+1 >= text.length() || text.charAt(j+1)!='$')) {
                out.setLength(end - 1);
                return 1;
            }
        }
        return 0;
    }
    // Skip closing math delimiter in text at position i
    private static int skipMathSuffix(String text, int i, int prefixLen) {
        while (i < text.length() && (text.charAt(i)==' ' || text.charAt(i)=='\t' || text.charAt(i)=='\n')) i++;
        if (prefixLen == 2 && i+1 < text.length() && text.charAt(i)=='$' && text.charAt(i+1)=='$') i+=2;
        else if (prefixLen == 2 && i+1 < text.length() && text.charAt(i)=='\\' && text.charAt(i+1)==']') i+=2;
        else if (prefixLen == 1 && i < text.length() && text.charAt(i)=='$') i++;
        return i;
    }
    // Check if position pos is inside a backtick code span (odd number of ` before pos)
    private static boolean insideBacktickSpan(StringBuilder out) {
        int bt = 0;
        for (int k = 0; k < out.length(); k++) {
            if (out.charAt(k) == '`') bt++;
        }
        return (bt % 2) == 1;
    }
    private static boolean isWhitespaceChar(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f';
    }
}
