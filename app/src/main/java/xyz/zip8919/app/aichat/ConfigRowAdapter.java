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
 * 管理模型页「提供商 / 模型」两个列表共用的两行 Adapter。
 *
 * <p>勾选状态自己管（{@link #checkedKeys()}），不用 {@code CHOICE_MODE_MULTIPLE}：
 * 筛选后列表重建、测试结果刷新都会让位置变化，系统多选按位置记状态会错位，
 * 按 key 记才不会「勾了 A 删掉 B」。
 */
class ConfigRowAdapter extends BaseAdapter {

    /** 列表项快照。{@code key} 是唯一标识（提供商 / 模型 ID）。 */
    static final class Row {
        final String key;
        final String title;
        final String sub;
        String badge;
        boolean badgeError;

        Row(String key, String title, String sub, String badge) {
            this.key = key;
            this.title = title;
            this.sub = sub;
            this.badge = badge == null ? "" : badge;
        }
    }

    private static final class Holder {
        CheckBox check;
        TextView title, sub, badge;
    }

    private final Context ctx;
    private List<Row> rows = new ArrayList<Row>();
    private final Set<String> checked = new HashSet<String>();
    private boolean selectionMode;

    ConfigRowAdapter(Context ctx) {
        this.ctx = ctx;
    }

    void setRows(List<Row> r) {
        rows = r == null ? new ArrayList<Row>() : r;
        // 筛掉已经不在列表里的勾选（删除 / 筛选变化后），避免「已选 3」却只能删到 1 个
        Set<String> alive = new HashSet<String>();
        for (Row row : rows) alive.add(row.key);
        checked.retainAll(alive);
        notifyDataSetChanged();
    }

    List<Row> rows() {
        return rows;
    }

    void setSelectionMode(boolean on) {
        selectionMode = on;
        if (!on) checked.clear();
        notifyDataSetChanged();
    }

    boolean isSelectionMode() {
        return selectionMode;
    }

    Set<String> checkedKeys() {
        return checked;
    }

    void toggle(String key) {
        if (key == null) return;
        if (!checked.add(key)) checked.remove(key);
        notifyDataSetChanged();
    }

    void checkAll(boolean all) {
        checked.clear();
        if (all) {
            for (Row r : rows) checked.add(r.key);
        }
        notifyDataSetChanged();
    }

    void invert() {
        Set<String> next = new HashSet<String>();
        for (Row r : rows) {
            if (!checked.contains(r.key)) next.add(r.key);
        }
        checked.clear();
        checked.addAll(next);
        notifyDataSetChanged();
    }

    /** 只刷新某一行的徽标，避免整表重建把滚动位置弹回顶部。 */
    void setBadge(String key, String badge, boolean error) {
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (r.key.equals(key)) {
                r.badge = badge == null ? "" : badge;
                r.badgeError = error;
                notifyDataSetChanged();
                return;
            }
        }
    }

    Row rowAt(int p) {
        return p >= 0 && p < rows.size() ? rows.get(p) : null;
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
            v = LayoutInflater.from(ctx).inflate(R.layout.item_model_row, parent, false);
            h = new Holder();
            h.check = (CheckBox) v.findViewById(R.id.mrow_check);
            h.title = (TextView) v.findViewById(R.id.mrow_title);
            h.sub = (TextView) v.findViewById(R.id.mrow_sub);
            h.badge = (TextView) v.findViewById(R.id.mrow_badge);
            v.setTag(h);
        }
        Row r = rows.get(p);
        h.check.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        h.check.setChecked(checked.contains(r.key));
        h.title.setText(r.title);
        h.sub.setText(r.sub == null ? "" : r.sub);
        h.sub.setVisibility(r.sub == null || r.sub.length() == 0 ? View.GONE : View.VISIBLE);
        h.badge.setText(r.badge);
        h.badge.setVisibility(r.badge == null || r.badge.length() == 0 ? View.GONE : View.VISIBLE);
        // ListView 行是动态创建的，主题 Applier 的树遍历覆盖不到，这里直接取生效色
        int primary = ThemeTokens.get(ctx, ThemeTokens.AC_PRIMARY);
        int text = ThemeTokens.get(ctx, ThemeTokens.AC_TEXT);
        int text2 = ThemeTokens.get(ctx, ThemeTokens.AC_TEXT_SECONDARY);
        h.title.setTextColor(text);
        h.sub.setTextColor(text2);
        h.badge.setTextColor(r.badgeError ? 0xFFD32F2F : primary);
        return v;
    }
}
