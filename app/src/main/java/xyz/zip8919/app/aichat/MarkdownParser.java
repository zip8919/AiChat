package xyz.zip8919.app.aichat;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.ImageSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableBody;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import ru.noties.jlatexmath.JLatexMathDrawable;

public class MarkdownParser {
    private static final Parser PARSER = Parser.builder()
            .extensions(Arrays.asList(
                    StrikethroughExtension.create(),
                    TablesExtension.create()))
            .build();
    private static final int COLOR_CODE_BG = 0xFFF0F0F0;
    private static final int COLOR_LINK = 0xFF2196F3;
    private static final int COLOR_BLOCKQUOTE = 0xFF666666;
    private static final int COLOR_HR = 0xFFCCCCCC;
    private static final Map<String, Bitmap> latexCache = new HashMap<String, Bitmap>();

    private static class MathToken {
        String formula;
        boolean block;
    }

    public static SpannableStringBuilder parse(String text, Context ctx) {
        if (text == null || text.isEmpty()) return new SpannableStringBuilder();
        if (!hasMarkdownFeatures(text)) return new SpannableStringBuilder(text);

        // 1. Extract LaTeX → replace with placeholder tokens
        List<MathToken> mathTokens = new ArrayList<MathToken>();
        String cleaned = extractMath(text, mathTokens);

        // 2. Parse markdown with commonmark
        Node document = PARSER.parse(cleaned);
        SpannableStringBuilder out = new SpannableStringBuilder();
        document.accept(new MarkdownVisitor(out, ctx));

        // 3. Replace math placeholder tokens with rendered bitmaps
        float density = ctx.getResources().getDisplayMetrics().density;
        for (int i = 0; i < mathTokens.size(); i++) {
            MathToken mt = mathTokens.get(i);
            String placeholder = "" + (char)('A' + i % 26) + (char)('a' + i / 26);
            int idx = out.toString().indexOf(placeholder);
            if (idx >= 0) {
                SpannableStringBuilder rendered = renderMath(mt, ctx, density);
                out.replace(idx, idx + placeholder.length(), rendered != null ? rendered : new SpannableStringBuilder(mt.formula));
            }
        }

        // Trim trailing newline
        if (out.length() > 0 && out.charAt(out.length() - 1) == '\n')
            out.delete(out.length() - 1, out.length());
        return out;
    }

    private static boolean hasMarkdownFeatures(String text) {
        int lim = Math.min(text.length(), 2000);
        for (int i = 0; i < lim; i++) {
            char c = text.charAt(i);
            if (c == '#' || c == '*' || c == '`' || c == '[' || c == '$'
                    || c == '>' || c == '~' || c == '-') return true;
        }
        return false;
    }

    // Extract $$block$$ and $inline$ math, replace with placeholders
    private static String extractMath(String text, List<MathToken> tokens) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            // Block math $$
            if (text.startsWith("$$", i)) {
                int end = text.indexOf("$$", i + 2);
                if (end > i) {
                    MathToken mt = new MathToken();
                    mt.block = true;
                    mt.formula = text.substring(i + 2, end).trim();
                    tokens.add(mt);
                    String ph = "" + (char)('A' + (tokens.size() - 1) % 26)
                            + (char)('a' + (tokens.size() - 1) / 26);
                    out.append(ph).append("\n\n");
                    i = end + 2;
                    continue;
                }
            }
            // Inline math $
            if (text.charAt(i) == '$' && (i + 1 < text.length() && text.charAt(i + 1) != '$')
                    && (i == 0 || text.charAt(i - 1) != '$')) {
                int end = text.indexOf('$', i + 1);
                if (end > i + 1) {
                    MathToken mt = new MathToken();
                    mt.block = false;
                    mt.formula = text.substring(i + 1, end);
                    tokens.add(mt);
                    String ph = "" + (char)('A' + (tokens.size() - 1) % 26)
                            + (char)('a' + (tokens.size() - 1) / 26);
                    out.append(ph);
                    i = end + 1;
                    continue;
                }
            }
            out.append(text.charAt(i));
            i++;
        }
        return out.toString();
    }

    // Render math token to Spannable
    private static SpannableStringBuilder renderMath(MathToken mt, Context ctx, float density) {
        try {
            String key = mt.formula + (mt.block ? "b" : "i");
            Bitmap cached = latexCache.get(key);
            if (cached == null) {
                float textSize = mt.block ? 18f : 14f;
                JLatexMathDrawable d = JLatexMathDrawable.builder(mt.formula)
                        .textSize(textSize)
                        .background(0xFFFFFFFF)
                        .build();

                int w = d.getIntrinsicWidth();
                int h = d.getIntrinsicHeight();
                if (w <= 0 || h <= 0) return null;

                int maxW = (int)(240 * density);
                if (w > maxW) {
                    float scale = (float) maxW / w;
                    textSize *= scale;
                    d = JLatexMathDrawable.builder(mt.formula)
                            .textSize(textSize)
                            .background(0xFFFFFFFF)
                            .build();
                    w = maxW;
                    h = (int)(h * scale);
                }

                d.setBounds(0, 0, w, h);
                cached = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(cached);
                c.drawColor(Color.WHITE);
                d.draw(c);

                if (latexCache.size() >= 30) {
                    String first = latexCache.keySet().iterator().next();
                    Bitmap old = latexCache.remove(first);
                    if (old != null) old.recycle();
                }
                latexCache.put(key, cached);
            }

            SpannableStringBuilder sb = new SpannableStringBuilder();
            if (mt.block) sb.append("\n");
            sb.append("M"); // placeholder char
            sb.setSpan(new ImageSpan(ctx, cached), sb.length() - 1, sb.length(), 0);
            if (mt.block) sb.append("\n");
            return sb;
        } catch (Exception e) {
            e.printStackTrace();
        }
        // Fallback: show formula text
        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (mt.block) sb.append("\n");
        int s = sb.length();
        sb.append(mt.formula);
        sb.setSpan(new TypefaceSpan("monospace"), s, sb.length(), 0);
        sb.setSpan(new ForegroundColorSpan(0xFF666666), s, sb.length(), 0);
        if (mt.block) sb.append("\n");
        return sb;
    }

    // ---- Commonmark AST → Spannable ----

    private static class MarkdownVisitor extends AbstractVisitor {
        final SpannableStringBuilder out;
        final Context ctx;
        int listDepth, orderedIdx;
        boolean tableCellFirst, inTableHeader;
        int tableCols;

        MarkdownVisitor(SpannableStringBuilder out, Context ctx) {
            this.out = out; this.ctx = ctx;
        }

        @Override public void visit(Document d) { visitChildren(d); }

        @Override public void visit(Heading h) {
            float[] sz = {2.0f, 1.5f, 1.25f, 1.1f, 1.0f, 0.9f};
            int s = out.length();
            visitChildren(h);
            out.setSpan(new RelativeSizeSpan(sz[Math.min(h.getLevel() - 1, 5)]), s, out.length(), 0);
            out.setSpan(new StyleSpan(Typeface.BOLD), s, out.length(), 0);
            out.append("\n");
        }

        @Override public void visit(Paragraph p) {
            visitChildren(p);
            if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append("\n");
        }

        @Override public void visit(BlockQuote bq) {
            int s = out.length();
            visitChildren(bq);
            out.setSpan(new ForegroundColorSpan(COLOR_BLOCKQUOTE), s, out.length(), 0);
            out.setSpan(new StyleSpan(Typeface.ITALIC), s, out.length(), 0);
            if (out.charAt(out.length() - 1) != '\n') out.append("\n");
        }

        @Override public void visit(BulletList bl) { listDepth++; visitChildren(bl); listDepth--; out.append("\n"); }

        @Override public void visit(OrderedList ol) { listDepth++; orderedIdx = ol.getStartNumber(); visitChildren(ol); listDepth--; out.append("\n"); }

        @Override public void visit(ListItem li) {
            for (int i = 0; i < listDepth - 1; i++) out.append("  ");
            if (li.getParent() instanceof OrderedList)
                out.append(String.valueOf(orderedIdx++)).append(". ");
            else
                out.append("• ");
            visitChildren(li);
            if (out.charAt(out.length() - 1) != '\n') out.append("\n");
        }

        @Override public void visit(CustomBlock cb) {
            if (cb instanceof TableBlock) visitTable((TableBlock) cb);
            else visitChildren(cb);
        }

        @Override public void visit(CustomNode cn) {
            if (cn instanceof TableHead) visitTableHead((TableHead) cn);
            else if (cn instanceof TableBody) visitTableBody((TableBody) cn);
            else if (cn instanceof TableRow) visitTableRow((TableRow) cn);
            else if (cn instanceof TableCell) visitTableCell((TableCell) cn);
            else if (cn instanceof Strikethrough) visitStrikethrough((Strikethrough) cn);
            else visitChildren(cn);
        }

        private void visitTable(TableBlock tb) {
            out.append("\n");
            int start = out.length();
            inTableHeader = true;
            tableCols = 0;
            visitChildren(tb);
            out.setSpan(new TypefaceSpan("monospace"), start, out.length(), 0);
            out.setSpan(new BackgroundColorSpan(0xFFF6F8FA), start, out.length(), 0);
            out.setSpan(new ForegroundColorSpan(0xFF333333), start, out.length(), 0);
            out.append("\n");
        }

        private void visitTableHead(TableHead th) {
            visitChildren(th);
            out.append("|");
            for (int i = 0; i < tableCols; i++) out.append("---|");
            out.append("\n");
            inTableHeader = false;
        }

        private void visitTableBody(TableBody tb) { visitChildren(tb); }

        private void visitTableRow(TableRow tr) {
            out.append("| ");
            tableCellFirst = true;
            visitChildren(tr);
            out.append(" |\n");
        }

        private void visitTableCell(TableCell tc) {
            if (!tableCellFirst) out.append(" | ");
            tableCellFirst = false;
            if (inTableHeader) tableCols++;
            int start = out.length();
            visitChildren(tc);
            if (inTableHeader)
                out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), 0);
        }

        private void visitStrikethrough(Strikethrough s) {
            int start = out.length(); visitChildren(s);
            out.setSpan(new StrikethroughSpan(), start, out.length(), 0);
        }

        @Override public void visit(FencedCodeBlock fcb) {
            out.append("\n");
            int s = out.length();
            String code = fcb.getLiteral();
            if (code != null) {
                if (code.endsWith("\n")) code = code.substring(0, code.length() - 1);
                out.append(code);
            }
            out.setSpan(new BackgroundColorSpan(COLOR_CODE_BG), s, out.length(), 0);
            out.setSpan(new TypefaceSpan("monospace"), s, out.length(), 0);
            out.setSpan(new ForegroundColorSpan(0xFF333333), s, out.length(), 0);
            out.append("\n\n");
        }

        @Override public void visit(IndentedCodeBlock icb) {
            out.append("\n");
            int s = out.length();
            String code = icb.getLiteral();
            if (code != null) {
                if (code.endsWith("\n")) code = code.substring(0, code.length() - 1);
                out.append(code);
            }
            out.setSpan(new BackgroundColorSpan(COLOR_CODE_BG), s, out.length(), 0);
            out.setSpan(new TypefaceSpan("monospace"), s, out.length(), 0);
            out.setSpan(new ForegroundColorSpan(0xFF333333), s, out.length(), 0);
            out.append("\n\n");
        }

        @Override public void visit(ThematicBreak tb) {
            int s = out.length(); out.append("────────────");
            out.setSpan(new ForegroundColorSpan(COLOR_HR), s, out.length(), 0);
            out.append("\n");
        }

        @Override public void visit(Text t) { out.append(t.getLiteral()); }
        @Override public void visit(SoftLineBreak slb) { out.append(" "); }
        @Override public void visit(HardLineBreak hlb) { out.append("\n"); }

        @Override public void visit(Code c) {
            int s = out.length(); out.append(c.getLiteral());
            out.setSpan(new BackgroundColorSpan(COLOR_CODE_BG), s, out.length(), 0);
            out.setSpan(new TypefaceSpan("monospace"), s, out.length(), 0);
            out.setSpan(new ForegroundColorSpan(0xFFC7254E), s, out.length(), 0);
        }

        @Override public void visit(Emphasis e) {
            int s = out.length(); visitChildren(e);
            out.setSpan(new StyleSpan(Typeface.ITALIC), s, out.length(), 0);
        }

        @Override public void visit(StrongEmphasis se) {
            int s = out.length(); visitChildren(se);
            out.setSpan(new StyleSpan(Typeface.BOLD), s, out.length(), 0);
        }

        @Override public void visit(Link l) {
            int s = out.length(); visitChildren(l);
            out.setSpan(new URLSpan(l.getDestination()), s, out.length(), 0);
            out.setSpan(new ForegroundColorSpan(COLOR_LINK), s, out.length(), 0);
        }

        @Override public void visit(Image img) {
            out.append("[图片]");
            int s = out.length() - 4;
            out.setSpan(new URLSpan(img.getDestination()), s, out.length(), 0);
            out.setSpan(new ForegroundColorSpan(COLOR_LINK), s, out.length(), 0);
        }
    }
}
