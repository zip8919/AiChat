package xyz.zip8919.app.aichat;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 顶栏模型选择器适配器：每一项是「模型名 + 服务商」两行。
 *
 * <p>为什么服务商必须常显：同一台设备上同时配了 {@code deepseek-flash (DeepSeek)} 和
 * {@code deepseek-flash (商汤日日新)}，收起态只显示名字时完全无法区分当前在用哪一家。
 * 上一版为了给长模型名腾宽度，把服务商只放进展开项，方向是错的——用户分不清就是硬伤。
 *
 * <p>为什么拆两行而不是 {@code "name (provider)"}：258dp 屏上文字区约 222dp，
 * {@code "deepseek-v4-flash (商汤日日新)"} 在 14sp 已接近上限，名字再长一点就会把服务商整段挤掉；
 * 拆行后两者互不抢宽度，模型名太长时只牺牲自己。
 *
 * <p>模型名仍保留 16sp → 14sp → 12sp 三档降级，最后才中间省略。中间省略是为了保住版本号
 * （{@code -0731} / {@code -1120}），尾部省略会让同系列不同版本变成同一个字符串。
 */
class ModelSpinnerAdapter extends BaseAdapter {

    private static final float[] TIERS = {16f, 14f, 12f};
    private static final String TAG_SELECTED = "sel";
    private static final String TAG_DROPDOWN = "dd";
    /** 服务商缺失时的提示色：琥珀，提示这是配置问题，不用红色以免像报错。 */
    private static final int COLOR_PROVIDER_MISSING = 0xFFB26A00;
    private static final int COLOR_PROVIDER_NORMAL = 0xFF888888;

    private final Context context;
    private final List<ModelInfo> models;
    /** name + "@" + 可用宽 -> 选中的字号；避免每次 bind 都 measure 一遍。 */
    private final Map<String, Float> sizeCache = new HashMap<String, Float>();
    private boolean widthRetryPosted;

    ModelSpinnerAdapter(Context context, List<ModelInfo> models) {
        this.context = context;
        this.models = models;
    }

    public int getCount() { return models.size(); }

    public Object getItem(int position) { return models.get(position); }

    public long getItemId(int position) { return position; }

    /** 收起态：名字可降字号，服务商固定 10sp 一行。 */
    public View getView(int position, View convertView, ViewGroup parent) {
        View v = obtain(convertView, parent, false);
        TextView nameTv = (TextView) v.findViewById(R.id.model_item_text);
        TextView providerTv = (TextView) v.findViewById(R.id.model_item_provider);
        ModelInfo m = models.get(position);
        String name = (m == null || m.name == null) ? "" : m.name;

        bindProvider(providerTv, m);

        int avail = parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight()
                - v.getPaddingLeft() - v.getPaddingRight();
        if (avail <= 0) {
            // 首帧 parent 还没测量完，宽度是 0；先按最大档画，量到宽度后再刷一次
            nameTv.setTextSize(TIERS[0]);
            nameTv.setEllipsize(null);
            nameTv.setText(name);
            if (!widthRetryPosted) {
                widthRetryPosted = true;
                parent.post(new Runnable() {
                    public void run() { notifyDataSetChanged(); }
                });
            }
            return v;
        }

        float size = pickSize(nameTv, name, avail);
        nameTv.setTextSize(size);
        nameTv.setText(name);
        nameTv.setSingleLine(true);
        nameTv.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        nameTv.setEllipsize(nameTv.getMeasuredWidth() > avail
                ? android.text.TextUtils.TruncateAt.MIDDLE
                : android.text.TextUtils.TruncateAt.END);
        return v;
    }

    /** 展开项：同一结构、字号大一号、48dp 触控高度。 */
    public View getDropDownView(int position, View convertView, ViewGroup parent) {
        View v = obtain(convertView, parent, true);
        TextView nameTv = (TextView) v.findViewById(R.id.model_item_text);
        TextView providerTv = (TextView) v.findViewById(R.id.model_item_provider);
        ModelInfo m = models.get(position);

        nameTv.setTextSize(15f);
        nameTv.setSingleLine(true);
        nameTv.setText((m == null || m.name == null) ? "" : m.name);
        nameTv.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        bindProvider(providerTv, m);
        return v;
    }

    private static void bindProvider(TextView tv, ModelInfo m) {
        if (m == null || m.provider == null || m.provider.trim().isEmpty()) {
            tv.setText("未指定服务商");
            tv.setTextColor(COLOR_PROVIDER_MISSING);
        } else {
            tv.setText(m.provider);
            tv.setTextColor(COLOR_PROVIDER_NORMAL);
        }
    }

    /**
     * 按 tag 复用 convertView：收起态与展开态是两套布局，混用会导致行高与字号互相串。
     */
    private View obtain(View convertView, ViewGroup parent, boolean dropdown) {
        String want = dropdown ? TAG_DROPDOWN : TAG_SELECTED;
        if (convertView != null && want.equals(convertView.getTag())) {
            return convertView;
        }
        View v = LayoutInflater.from(context).inflate(
                dropdown ? R.layout.spinner_item_model_dropdown : R.layout.spinner_item_model,
                parent, false);
        v.setTag(want);
        return v;
    }

    private float pickSize(TextView tv, String name, int avail) {
        String key = name + "@" + avail;
        Float cached = sizeCache.get(key);
        if (cached != null) {
            return cached.floatValue();
        }
        float chosen = TIERS[TIERS.length - 1];
        for (int i = 0; i < TIERS.length; i++) {
            if (fits(tv, name, TIERS[i], avail)) {
                chosen = TIERS[i];
                break;
            }
        }
        if (sizeCache.size() > 64) {
            sizeCache.clear();
        }
        sizeCache.put(key, Float.valueOf(chosen));
        return chosen;
    }

    private static boolean fits(TextView tv, String text, float sp, int avail) {
        tv.setTextSize(sp);
        tv.setText(text);
        tv.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        return tv.getMeasuredWidth() <= avail;
    }
}
