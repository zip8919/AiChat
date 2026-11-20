package xyz.zip8919.app.aichat;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 使用统计里的一张表：表头 + 若干行，整体可左右滑动，点表头排序。
 *
 * <p><b>为什么自己画表而不用 TableLayout</b>：TableLayout 在 4.4 上不支持
 * 「按列固定宽度 + 整表横向滚动」的组合——列宽由内容撑开，长模型名会把整张表
 * 拉得极宽，短列则被压成一条缝。这里改成每列定宽，靠外层
 * {@link HorizontalScrollView} 滚动，列宽稳定、对齐也整齐。
 *
 * <p><b>为什么不给每列加「拖动调整列宽」</b>：词典笔屏幕只有几厘米，拖动列宽在
 * 这种尺寸上精度极差，还会和横向滑动抢手势。定宽 + 横向滑动已经够用。
 *
 * <p>排序状态存在本表内部：每张表各自独立，用户给「模型」表按 token 排序时，
 * 不该影响「服务商」表的排序。
 */
final class StatsTableView extends LinearLayout {

    /** 一列的定义。 */
    static final class Col {
        final String title;
        /** 列宽（dp）。 */
        final int widthDp;
        /** 数值列右对齐；文本列左对齐。 */
        final boolean numeric;

        Col(String title, int widthDp, boolean numeric) {
            this.title = title;
            this.widthDp = widthDp;
            this.numeric = numeric;
        }
    }

    /** 一行数据：cells 与列一一对应；sortKeys 是数值列用于排序的原始值。 */
    static final class Row {
        final String[] cells;
        final double[] sortKeys;
        /** 键列（第一列）文本，用于「文本列按字典序」排序。 */
        final String key;

        Row(String key, String[] cells, double[] sortKeys) {
            this.key = key;
            this.cells = cells;
            this.sortKeys = sortKeys;
        }
    }

    private final List<Col> cols = new ArrayList<Col>();
    private final List<Row> rows = new ArrayList<Row>();
    private final Context ctx;
    private final LinearLayout inner;
    private final HorizontalScrollView hsv;

    /** 当前排序列下标；-1 表示按原始顺序（通常是 token 降序，由调用方给）。 */
    private int sortCol = -1;
    private boolean sortAsc;

    private TextView emptyView;

    StatsTableView(Context c) {
        super(c);
        ctx = c;
        setOrientation(VERTICAL);

        hsv = new HorizontalScrollView(c);
        hsv.setHorizontalScrollBarEnabled(true);
        // 手指在表上左右滑就是滚表；纵向手势交还给外层 ScrollView（统计页整体可上下滚）
        hsv.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        addView(hsv);

        inner = new LinearLayout(c);
        inner.setOrientation(VERTICAL);
        hsv.addView(inner, new HorizontalScrollView.LayoutParams(
                HorizontalScrollView.LayoutParams.WRAP_CONTENT,
                HorizontalScrollView.LayoutParams.WRAP_CONTENT));
    }

    void setColumns(List<Col> list) {
        cols.clear();
        cols.addAll(list);
    }

    void setRows(List<Row> list) {
        rows.clear();
        rows.addAll(list);
        sortCol = -1;
        sortAsc = false;
        rebuild();
    }

    /** 数据为空时的占位文案。 */
    void setEmptyText(String text) {
        if (emptyView == null) {
            emptyView = new TextView(ctx);
            emptyView.setTextSize(12);
            emptyView.setTextColor(0xFF90A4AE);
            emptyView.setPadding(dp(4), dp(10), dp(4), dp(6));
            addView(emptyView);
        }
        emptyView.setText(text);
        emptyView.setVisibility(rows.isEmpty() ? VISIBLE : GONE);
    }

    private void rebuild() {
        inner.removeAllViews();
        if (emptyView != null) {
            emptyView.setVisibility(rows.isEmpty() ? VISIBLE : GONE);
        }
        if (cols.isEmpty()) return;
        inner.addView(buildHeader());
        List<Row> data = sortedRows();
        for (int i = 0; i < data.size(); i++) {
            inner.addView(buildRow(data.get(i), i));
        }
    }

    /** 表头：点一下切到按这列排序，再点一下反向。 */
    private View buildHeader() {
        LinearLayout h = new LinearLayout(ctx);
        h.setOrientation(HORIZONTAL);
        h.setBackgroundColor(0xFFECEFF1);
        int pv = dp(6);
        h.setPadding(0, pv, 0, pv);
        for (int i = 0; i < cols.size(); i++) {
            final int idx = i;
            Col c = cols.get(i);
            TextView t = new TextView(ctx);
            String arrow = "";
            if (sortCol == i) arrow = sortAsc ? " ▲" : " ▼";
            t.setText(c.title + arrow);
            t.setTextSize(11);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setTextColor(0xFF37474F);
            t.setGravity(c.numeric ? (Gravity.RIGHT | Gravity.CENTER_VERTICAL)
                    : (Gravity.LEFT | Gravity.CENTER_VERTICAL));
            t.setPadding(dp(4), dp(2), dp(4), dp(2));
            t.setLayoutParams(new LayoutParams(dp(c.widthDp), LayoutParams.WRAP_CONTENT));
            t.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    if (sortCol == idx) {
                        sortAsc = !sortAsc;
                    } else {
                        sortCol = idx;
                        // 数值列首选「大在前」（用户关心谁用得最多），
                        // 文本列首选字母序（找名字时更快）
                        sortAsc = !cols.get(idx).numeric;
                    }
                    rebuild();
                }
            });
            h.addView(t);
        }
        return h;
    }

    private View buildRow(Row r, int index) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(HORIZONTAL);
        // 隔行底色：宽表横向滑到后面时，靠底色才分得清哪几个格子属于同一行
        row.setBackgroundColor(index % 2 == 0 ? 0xFFFFFFFF : 0xFFF7F9FA);
        int pv = dp(6);
        row.setPadding(0, pv, 0, pv);
        for (int i = 0; i < cols.size(); i++) {
            Col c = cols.get(i);
            TextView t = new TextView(ctx);
            t.setText(r.cells != null && i < r.cells.length && r.cells[i] != null ? r.cells[i] : "");
            t.setTextSize(11);
            t.setTextColor(i == 0 ? 0xFF24292E : 0xFF546E7A);
            t.setGravity(c.numeric ? (Gravity.RIGHT | Gravity.CENTER_VERTICAL)
                    : (Gravity.LEFT | Gravity.CENTER_VERTICAL));
            t.setPadding(dp(4), 0, dp(4), 0);
            t.setSingleLine(true);
            // 模型名可能很长：省略号截断，完整值靠点击这一格弹提示看
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setLayoutParams(new LayoutParams(dp(c.widthDp), LayoutParams.WRAP_CONTENT));
            if (i == 0) t.setTypeface(Typeface.DEFAULT_BOLD);
            row.addView(t);
        }
        return row;
    }

    private List<Row> sortedRows() {
        List<Row> list = new ArrayList<Row>(rows);
        if (sortCol < 0) return list;
        final int col = sortCol;
        final boolean asc = sortAsc;
        Collections.sort(list, new Comparator<Row>() {
            public int compare(Row a, Row b) {
                int c;
                Col cc = cols.get(col);
                if (cc.numeric) {
                    double x = keyOf(a, col), y = keyOf(b, col);
                    c = Double.compare(x, y);
                } else {
                    String x = a.cells != null && col < a.cells.length ? a.cells[col] : "";
                    String y = b.cells != null && col < b.cells.length ? b.cells[col] : "";
                    c = x.compareToIgnoreCase(y);
                }
                return asc ? c : -c;
            }
        });
        return list;
    }

    private static double keyOf(Row r, int col) {
        if (r.sortKeys != null && col < r.sortKeys.length) return r.sortKeys[col];
        return 0;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
