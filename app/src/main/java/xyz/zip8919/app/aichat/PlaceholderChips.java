package xyz.zip8919.app.aichat;

import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 提示词输入框的「快捷插入占位符」胶囊条。
 *
 * <p>占位符的说明文字早就写在各输入框下方，但 {cpu_abi}、{timezone} 这类
 * 要么长得像、要么敲起来费劲——词典笔没有外接键盘，逐字输入大括号很痛苦。
 * 于是在输入框边上放一排胶囊按钮，点一下就把占位符插到光标处。
 *
 * <p>复用说明：四个提示词输入框（系统 / 标题生成 / 代码执行 / 主题生成模板）
 * 支持的占位符集合不同（见 {@link SystemInfo#fillPlaceholders} 的注释），
 * 所以用 {@link #FULL} / {@link #DEVICE_ONLY} / {@link #THEME} 三组常量区分，
 * 新增支持方时传对应的组即可，不要把全集硬塞给只支持子集的输入框。
 *
 * <p>实现取舍：胶囊用代码构建而不是 XML——四个位置只有容器 id 不同，
 * 一个 include 布局加不上「点击后插进哪个输入框」，参数化还是得走代码；
 * 外层套 HorizontalScrollView，18 个胶囊在 258dp 窄屏上放不下，必须能横滑。
 */
final class PlaceholderChips {

    private PlaceholderChips() {
    }

    /**
     * 全部 18 个设备类占位符（与 SystemInfo.fillPlaceholders 支持的完全一致）。
     * 顺序按功能分组排列，和输入框下方的说明文字一一对应，方便对照。
     */
    static final String[] FULL = {
            "{android}", "{api}", "{webview}", "{model}", "{brand}", "{cpu_abi}",
            "{app_version}", "{app_package}", "{locale}", "{ua}",
            "{screen}", "{density}",
            "{date}", "{time}", "{datetime}", "{weekday}", "{timezone}",
            "{free_storage}",
    };

    /** 标题生成提示词、代码执行提示词只支持其中 6 个（设置页说明文字写明了）。 */
    static final String[] DEVICE_ONLY = {
            "{android}", "{api}", "{webview}", "{model}", "{app_version}", "{ua}",
    };

    /** 主题生成模板：{desc} 是唯一必填占位符，设备类则全集可用。 */
    static final String[] THEME = concat(new String[]{"{desc}"}, FULL);

    /**
     * 把胶囊条挂到容器里。
     *
     * @param slot   布局里预留的纵向 LinearLayout 容器，内容由这里填充
     * @param target 点击胶囊后插入占位符的目标输入框
     * @param tokens 该输入框支持的占位符集合
     */
    static void attach(LinearLayout slot, final EditText target, String[] tokens) {
        if (slot == null || target == null) return;
        android.content.Context ctx = slot.getContext();
        int hPad = dp(ctx, 9);
        int vPad = dp(ctx, 4);

        // 外层横滑：胶囊总宽超出屏幕时靠滑动到达，不压缩不换行
        HorizontalScrollView scroll = new HorizontalScrollView(ctx);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        scroll.addView(row, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView lead = new TextView(ctx);
        lead.setText("点按插入：");
        lead.setTextSize(11);
        lead.setTextColor(ctx.getResources().getColor(android.R.color.darker_gray));
        LinearLayout.LayoutParams leadLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        leadLp.rightMargin = dp(ctx, 4);
        row.addView(lead, leadLp);

        for (final String token : tokens) {
            Button chip = new Button(ctx);
            chip.setText(token);
            chip.setTextSize(11);
            // Holo 主题下 Button 默认不转大写，但保险起见显式关掉，防止 ROM 差异
            chip.setAllCaps(false);
            chip.setTextColor(ctx.getResources().getColor(R.color.primary_dark));
            chip.setBackgroundResource(R.drawable.bg_placeholder_chip);
            // 默认 minWidth/minHeight 会把胶囊撑成大按钮，占位符本身就不长，全部归零
            chip.setMinWidth(0);
            chip.setMinimumWidth(0);
            chip.setMinHeight(0);
            chip.setMinimumHeight(0);
            chip.setPadding(hPad, vPad, hPad, vPad);
            chip.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    insertAtCursor(target, token);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(ctx, 6);
            row.addView(chip, lp);
        }

        slot.addView(scroll, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /**
     * 在光标处插入占位符，插完把光标挪到占位符末尾。
     *
     * <p>选区要 clamp：没有焦点时 getSelectionStart/End 返回 -1，
     * 直接 insert 会抛 IndexOutOfBoundsException；有拖蓝选区时插在选区起点、
     * 不清选区（插完光标在 token 后面，拖蓝自然消失，不用专门调 clear）。
     */
    private static void insertAtCursor(EditText edit, String token) {
        int len = edit.length();
        int start = edit.getSelectionStart();
        int end = edit.getSelectionEnd();
        if (start < 0 || start > len) start = len;
        if (end < 0 || end > len) end = len;
        int at = Math.min(start, end);
        edit.getText().insert(at, token);
        edit.requestFocus();
        edit.setSelection(at + token.length());
    }

    private static String[] concat(String[] a, String[] b) {
        String[] out = new String[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static int dp(android.content.Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
