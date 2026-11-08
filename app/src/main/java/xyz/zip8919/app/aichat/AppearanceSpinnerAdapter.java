package xyz.zip8919.app.aichat;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;
import java.util.List;

/**
 * 外观页两个 Spinner（字体 / 自定义主题）共用的两行 Adapter。
 *
 * <p>复用 spinner_item_model.xml / _dropdown.xml 的「主名 + 副名」结构：
 * 258dp 上主名单行 ellipsize=end，副名固定 10sp 不与主名抢宽度。
 * 分组标题（内置主题 / 我的主题）作为不可选项，避免 Tab 挤掉窄屏标题；
 * 文件缺失 / 版本不兼容的主题单独标记 disabled，置灰且不可选。
 */
class AppearanceSpinnerAdapter extends BaseAdapter {

    static final class Entry {
        final String main;
        final String sub;
        final String value;
        final boolean header;
        final boolean disabled;

        Entry(String main, String sub, String value, boolean header) {
            this(main, sub, value, header, false);
        }

        Entry(String main, String sub, String value, boolean header, boolean disabled) {
            this.main = main;
            this.sub = sub;
            this.value = value;
            this.header = header;
            this.disabled = disabled;
        }
    }

    private static final String TAG_SEL = "sel";
    private static final String TAG_DD = "dd";
    private final Context ctx;
    private List<Entry> entries;

    AppearanceSpinnerAdapter(Context ctx, List<Entry> entries) {
        this.ctx = ctx;
        this.entries = entries;
    }

    void setEntries(List<Entry> e) {
        this.entries = e;
        notifyDataSetChanged();
    }

    public int getCount() { return entries.size(); }
    public Object getItem(int p) { return entries.get(p); }
    public long getItemId(int p) { return p; }

    @Override
    public boolean isEnabled(int p) {
        Entry e = entries.get(p);
        return !e.header && !e.disabled;
    }

    Entry entryAt(int p) { return entries.get(p); }

    @Override
    public View getView(int p, View cv, ViewGroup parent) {
        View v = obtain(cv, parent, false);
        bind(v, entries.get(p), false);
        return v;
    }

    @Override
    public View getDropDownView(int p, View cv, ViewGroup parent) {
        View v = obtain(cv, parent, true);
        bind(v, entries.get(p), true);
        return v;
    }

    private void bind(View v, Entry e, boolean dropdown) {
        TextView main = (TextView) v.findViewById(R.id.model_item_text);
        TextView sub = (TextView) v.findViewById(R.id.model_item_provider);
        // 下拉项在深色 PopupWindow 里，原来的 #333/#888/#BDBDBD 全部融进深色底。
        // 这里按当前主题取色；颜色仍是内置默认时保持原值，零回归。
        AppearanceManager am = AppearanceManager.getInstance(v.getContext());
        boolean cDef = am.isColorDefault();
        boolean cDark = am.isDark();
        int cMain = cDef ? 0xFF333333
                : am.getEffectiveColor(ThemeTokens.AC_TEXT, cDark);
        int cSub = cDef ? 0xFF888888
                : am.getEffectiveColor(ThemeTokens.AC_TEXT_SECONDARY, cDark);
        int cDim = cDef ? 0xFFBDBDBD
                : am.getEffectiveColor(ThemeTokens.AC_TEXT_SECONDARY, cDark);
        int cHead = cDef ? 0xFF9E9E9E
                : am.getEffectiveColor(ThemeTokens.AC_TEXT_SECONDARY, cDark);
        main.setSingleLine(true);
        main.setEllipsize(android.text.TextUtils.TruncateAt.END);
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        main.setText(e.main);
        if (e.header) {
            main.setTextColor(cHead);
            sub.setVisibility(View.GONE);
        } else if (e.disabled) {
            main.setTextColor(cDim);
            sub.setVisibility(View.VISIBLE);
            sub.setText(e.sub == null ? "" : e.sub);
            sub.setTextColor(cDim);
        } else {
            main.setTextColor(cMain);
            sub.setVisibility(View.VISIBLE);
            sub.setText(e.sub == null ? "" : e.sub);
            sub.setTextColor(cSub);
        }
        if (dropdown) {
            // 48dp 要按 density 换算；直接 setMinimumHeight(48) 在 xxhdpi 上只有 16dp
            v.setMinimumHeight(Math.round(48 * ctx.getResources().getDisplayMetrics().density));
        }
    }

    private View obtain(View cv, ViewGroup parent, boolean dropdown) {
        String want = dropdown ? TAG_DD : TAG_SEL;
        if (cv != null && want.equals(cv.getTag())) return cv;
        View v = LayoutInflater.from(ctx).inflate(dropdown
                ? R.layout.spinner_item_model_dropdown : R.layout.spinner_item_model,
                parent, false);
        v.setTag(want);
        return v;
    }
}
