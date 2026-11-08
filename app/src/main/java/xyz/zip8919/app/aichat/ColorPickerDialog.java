package xyz.zip8919.app.aichat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 自定义取色器：API 18 没有系统取色控件，用 HSV 三个 SeekBar + 实时预览自绘。
 *
 * <p>不引第三方库、不用自定义 View 的 onDraw，全部是 Holo 原生控件，258dp
 * 单列放得下。输出 HEX，浅色（饱和度低且明度高）会提示配深色文字。
 */
final class ColorPickerDialog {

    interface OnPicked {
        void onPicked(String hex);
    }

    private ColorPickerDialog() {
    }

    static void show(final Activity a, String initialHex, final OnPicked cb) {
        final float[] hsv = new float[3];
        Integer init = ThemeTokens.parseColor(initialHex);
        int start = init == null ? 0xFF2196F3 : init.intValue();
        Color.colorToHSV(start, hsv);

        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(a, 16);
        root.setPadding(pad, pad, pad, pad);

        final View preview = new View(a);
        // 预览块显示的是用户正在挑的颜色，禁止被主题重着色覆盖
        preview.setTag(R.id.ac_no_recolor, Boolean.TRUE);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(a, 56));
        plp.bottomMargin = dp(a, 12);
        preview.setLayoutParams(plp);

        final TextView hexTv = new TextView(a);
        hexTv.setTextSize(14);
        hexTv.setGravity(Gravity.CENTER);
        hexTv.setTypeface(android.graphics.Typeface.MONOSPACE);
        hexTv.setPadding(0, 0, 0, dp(a, 12));
        root.addView(preview);
        root.addView(hexTv);

        final float[] cur = {hsv[0], hsv[1], hsv[2]};
        final int[] color = {start};

        final Runnable refresh = new Runnable() {
            public void run() {
                color[0] = Color.HSVToColor(cur);
                preview.setBackgroundDrawable(new ColorDrawable(color[0]));
                hexTv.setText(ThemeCssBuilder.hexOf(color[0]));
            }
        };

        SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                int id = s.getId();
                if (id == 1) cur[0] = progress;              // 色相 0..360
                else if (id == 2) cur[1] = progress / 100f;  // 饱和度 0..1
                else if (id == 3) cur[2] = progress / 100f;  // 明度 0..1
                refresh.run();
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        };

        root.addView(labeled(a, "色相", 360, Math.round(hsv[0]), 1, listener));
        root.addView(labeled(a, "饱和度", 100, Math.round(hsv[1] * 100), 2, listener));
        root.addView(labeled(a, "明度", 100, Math.round(hsv[2] * 100), 3, listener));

        refresh.run();

        AcDialog.builder(a)
                .setTitle("自定义颜色")
                .setView(root)
                .setPositiveButton("使用", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String hex = ThemeCssBuilder.hexOf(color[0]);
                        // 高亮色（低饱和 + 高明度）作强调色时需配深字，给一次明确提示
                        if (cur[1] < 0.25f && cur[2] > 0.8f) {
                            Toast.makeText(a, "这是浅色，作强调色时请配深色文字", Toast.LENGTH_LONG).show();
                        }
                        if (cb != null) cb.onPicked(hex);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static View labeled(Activity a, String text, int max, int progress,
                                int id, SeekBar.OnSeekBarChangeListener l) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView tv = new TextView(a);
        tv.setText(text);
        tv.setTextSize(12);
        SeekBar sb = new SeekBar(a);
        sb.setId(id);
        sb.setMax(max);
        sb.setProgress(progress);
        sb.setOnSeekBarChangeListener(l);
        box.addView(tv);
        box.addView(sb, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private static int dp(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }
}
