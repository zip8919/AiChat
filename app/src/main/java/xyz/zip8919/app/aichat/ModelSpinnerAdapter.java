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
 * 顶栏模型名适配器。
 *
 * <p>收起状态只显示模型名，不拼服务商：258dp 的设备上可用宽 228dp，22 个字符按 18sp 已占 87%，
 * 再拼 {@code " (商汤日日新)"} 会溢出 57dp 被硬裁（见界面方案 §7.5）。服务商放在展开项里显示。
 *
 * <p>宽度不够时按 16sp → 14sp → 12sp 三档降字号，仍然放不下才中间省略。中间省略是为了保住
 * 版本号（{@code -0731} / {@code -1120}），尾部省略会让同系列不同版本变成同一个字符串。
 */
class ModelSpinnerAdapter extends BaseAdapter {

    private static final float[] TIERS = {16f, 14f, 12f};
    private static final float DROPDOWN_SP = 14f;

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

    public View getView(int position, View convertView, ViewGroup parent) {
        TextView tv = obtain(convertView, parent);
        ModelInfo m = models.get(position);
        String name = m == null ? "" : m.name;

        int avail = parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight()
                - tv.getPaddingLeft() - tv.getPaddingRight();
        if (avail <= 0) {
            // 首帧 parent 还没测量完，宽度是 0；按 16sp 先画，量到宽度后再刷一次
            tv.setTextSize(TIERS[0]);
            tv.setEllipsize(null);
            tv.setText(name);
            if (!widthRetryPosted) {
                widthRetryPosted = true;
                parent.post(new Runnable() {
                    public void run() { notifyDataSetChanged(); }
                });
            }
            return tv;
        }

        float size = pickSize(tv, name, avail);
        tv.setTextSize(size);
        tv.setText(name);
        tv.setSingleLine(true);
        tv.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        tv.setEllipsize(tv.getMeasuredWidth() > avail
                ? android.text.TextUtils.TruncateAt.MIDDLE
                : android.text.TextUtils.TruncateAt.END);
        return tv;
    }

    /** 展开项带服务商：用户在这里区分同名模型属于哪个端点。 */
    public View getDropDownView(int position, View convertView, ViewGroup parent) {
        TextView tv = obtain(convertView, parent);
        ModelInfo m = models.get(position);
        String text = m == null ? "" : (m.provider == null || m.provider.isEmpty()
                ? m.name : m.name + " (" + m.provider + ")");
        tv.setTextSize(DROPDOWN_SP);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        tv.setText(text);
        return tv;
    }

    private TextView obtain(View convertView, ViewGroup parent) {
        if (convertView instanceof TextView) {
            return (TextView) convertView;
        }
        return (TextView) LayoutInflater.from(context)
                .inflate(R.layout.spinner_item_model, parent, false);
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
