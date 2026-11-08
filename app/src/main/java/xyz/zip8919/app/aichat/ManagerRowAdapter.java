package xyz.zip8919.app.aichat;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 字体管理 / 主题管理两个列表页共用的两行 Adapter。
 *
 * <p>多选状态由 checked 集合自己管，不依赖 ListView.CHOICE_MODE_MULTIPLE：
 * 两行文字 + 状态徽标的自定义 item 用系统的 CheckedTextView 装不下，
 * 自己管反而能在「重命名后列表重建」时保留勾选。
 */
class ManagerRowAdapter extends BaseAdapter {

    static final class Row {
        final String key;
        final String main;
        final String sub;
        final String badge;
        final boolean header;
        final boolean disabled;

        private Row(String key, String main, String sub, String badge,
                    boolean header, boolean disabled) {
            this.key = key;
            this.main = main;
            this.sub = sub;
            this.badge = badge;
            this.header = header;
            this.disabled = disabled;
        }

        static Row header(String text) {
            return new Row(null, text, "", "", true, false);
        }

        static Row item(String key, String main, String sub, String badge, boolean disabled) {
            return new Row(key, main, sub, badge, false, disabled);
        }
    }

    private static final class Holder {
        CheckBox check;
        TextView main, sub, badge;
    }

    private final Context ctx;
    private List<Row> rows = new ArrayList<Row>();
    private final Set<String> checked = new HashSet<String>();
    private boolean selectionMode;

    ManagerRowAdapter(Context ctx) {
        this.ctx = ctx;
    }

    void setRows(List<Row> r) {
        rows = r;
        notifyDataSetChanged();
    }

    void setSelectionMode(boolean on) {
        selectionMode = on;
        if (!on) checked.clear();
        notifyDataSetChanged();
    }

    Set<String> checkedKeys() {
        return checked;
    }

    void toggle(String key) {
        if (key == null) return;
        if (!checked.add(key)) checked.remove(key);
        notifyDataSetChanged();
    }

    Row rowAt(int p) {
        return rows.get(p);
    }

    public int getCount() { return rows.size(); }
    public Object getItem(int p) { return rows.get(p); }
    public long getItemId(int p) { return p; }

    @Override
    public View getView(int p, View cv, ViewGroup parent) {
        Holder h;
        View v;
        if (cv != null && cv.getTag() instanceof Holder) {
            v = cv;
            h = (Holder) cv.getTag();
        } else {
            v = LayoutInflater.from(ctx).inflate(R.layout.item_manager_row, parent, false);
            h = new Holder();
            h.check = (CheckBox) v.findViewById(R.id.row_check);
            h.main = (TextView) v.findViewById(R.id.row_main);
            h.sub = (TextView) v.findViewById(R.id.row_sub);
            h.badge = (TextView) v.findViewById(R.id.row_badge);
            v.setTag(h);
        }
        Row r = rows.get(p);
        h.check.setVisibility(selectionMode && !r.header ? View.VISIBLE : View.GONE);
        h.check.setChecked(!r.disabled && checked.contains(r.key));
        h.main.setSingleLine(true);
        h.main.setEllipsize(android.text.TextUtils.TruncateAt.END);
        h.sub.setSingleLine(true);
        h.sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        h.main.setText(r.main);
        h.sub.setText(r.sub == null ? "" : r.sub);
        h.badge.setText(r.badge == null ? "" : r.badge);
        // ListView item 是 adapter 动态创建/复用的，Applier 的 View 树遍历覆盖不到，
        // 这里直接取当前生效色，避免主题切换或列表滚动后徽章又变回硬编码蓝。
        android.content.Context ctx = v.getContext();
        int primary = ThemeTokens.get(ctx, ThemeTokens.AC_PRIMARY);
        int text = ThemeTokens.get(ctx, ThemeTokens.AC_TEXT);
        int text2 = ThemeTokens.get(ctx, ThemeTokens.AC_TEXT_SECONDARY);
        if (r.header) {
            h.sub.setVisibility(View.GONE);
            h.badge.setVisibility(View.GONE);
            h.main.setTextColor(text2);
        } else {
            h.sub.setVisibility(View.VISIBLE);
            h.badge.setVisibility(View.VISIBLE);
            h.main.setTextColor(r.disabled ? 0xFFBDBDBD : text);
            h.badge.setTextColor(r.disabled ? 0xFFBDBDBD : primary);
        }
        return v;
    }
}
