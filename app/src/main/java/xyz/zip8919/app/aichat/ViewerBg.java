package xyz.zip8919.app.aichat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;

import java.util.Locale;

/**
 * 全屏查看器的背景色：一份持久化设置，图片 / 表格 / 代码 / 附件文件四类查看器共享。
 *
 * <p>为什么要统一：原来 {@code imageBg / tableBg / codeBg} 是三个独立 int 字段，
 * 每次 {@code showXxx()} 都重置成默认（图片按 svg/raster 给白或黑、表格代码给白），
 * 切背景只改当前 DOM 的内联样式。于是按钮旋转、屏幕旋转、重开查看器、进程重建
 * 都会把颜色"改回默认"。现在颜色写进 SharedPreferences，所有 load 路径都从这里读，
 * 一次设定处处生效，且不受任何重建影响。
 *
 * <p>色值一律是<b>不透明</b> ARGB（强制 0xFF000000）：JPEG 无 alpha 通道，背景色还要
 * 用于导出时铺底，半透明会让成品出现黑块。
 */
final class ViewerBg {

    private static final String PREF_NAME = "aichat_prefs";
    private static final String KEY_COLOR = "viewer_bg_color";

    /** 默认纯白：表格 / 代码 / 文档原本就是白底，统一后行为可预测。 */
    static final int DEFAULT = 0xFFFFFFFF;

    /**
     * 用户从未手动选过色时的哨兵值。正常颜色都是不透明 ARGB（高字节 0xFF），
     * 用 {@code Integer.MIN_VALUE} 不会与任何合法色值冲突。
     *
     * <p>为什么要区分"未设置"：图片查看器原本 SVG 用白底、位图用黑底，这是有意
     * 的观感设计。首次打开不该被全局默认色抹平；但用户一旦手动挑过色，之后所有
     * 查看器一律用这个值，不再回退到按类型默认。
     */
    static final int UNSET = Integer.MIN_VALUE;

    static final int[] PRESETS = {
        0xFFFFFFFF, // 纯白
        0xFFF5F5DC, // 米黄（护眼）
        0xFFCCE8CF, // 淡绿（护眼）
        0xFFEEEEEE, // 浅灰
        0xFF888888, // 中灰
        0xFF404040, // 深灰
        0xFF000000, // 纯黑
        0xFF1A2733, // 深蓝
    };

    static final String[] PRESET_NAMES = {
        "纯白  #FFFFFF", "米黄  #F5F5DC", "淡绿  #CCE8CF", "浅灰  #EEEEEE",
        "中灰  #888888", "深灰  #404040", "纯黑  #000000", "深蓝  #1A2733",
    };

    /**
     * 所有预览页共用的背景切换脚本。
     *
     * <p><b>只改 background，绝不改 color</b> —— 用户明确要求「表格背景变颜色，
     * 但是字体颜色不变」。表格单元格（th/td）也一并跟随，否则表头那块写死的浅灰
     * 在深色背景下会显得割裂。字体色由各页面 CSS 显式固定，这里不碰。
     *
     * <p>不用 CSS 变量（{@code --var}）：minSdk 18 对应的是老 WebKit，
     * Chromium 49+ 才支持，项目里所有 CSS 都按老内核写。
     */
    static final String SET_BG_JS =
        "function viewerSetBg(c){" +
        "document.body.style.backgroundColor=c;" +
        "document.documentElement.style.backgroundColor=c;" +
        "var ns=document.querySelectorAll('th,td');" +
        "for(var i=0;i<ns.length;i++){ns[i].style.backgroundColor=c;}}";

    private ViewerBg() {
    }

    /** 返回持久化的颜色；从未手动设置过返回 {@link #UNSET}。 */
    static int get(Context c) {
        return c.getSharedPreferences(PREF_NAME, 0).getInt(KEY_COLOR, UNSET);
    }

    static void set(Context c, int argb) {
        c.getSharedPreferences(PREF_NAME, 0).edit().putInt(KEY_COLOR, argb).apply();
    }

    /** 打开查看器时取色：设过就用持久化值，没设过用该模式原有的默认。 */
    static int effective(Context c, int defaultForMode) {
        int saved = get(c);
        return saved == UNSET ? defaultForMode : saved;
    }

    /** ARGB → {@code #RRGGBB}，供内联 CSS 与 JS 参数使用。 */
    static String hex(int argb) {
        return String.format(Locale.US, "#%06X", argb & 0x00FFFFFF);
    }

    /**
     * 解析 {@code #RGB / #RRGGBB / RRGGBB / #AARRGGBB}；非法返回 {@code null}。
     *
     * <p><b>为什么返回可空 Integer 而不是 int + -1 哨兵</b>：合法色一律被强制成不透明
     * ARGB（高字节 0xFF），在 Java 有符号 int 里就是负数（{@code 0xFFFFFFFF == -1}）。
     * 用 {@code -1} 表示非法、再用 {@code c < 0} 判断，会把**每一种**合法颜色都判成
     * 「格式无效」，纯白更是直接和哨兵撞值。用 null 表示非法是唯一不与色值域冲突的写法。
     */
    static Integer parse(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.length() == 0) return null;
        if (t.charAt(0) != '#') t = "#" + t;
        try {
            return Color.parseColor(t) | 0xFF000000;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 相对亮度 0..255。仅用于日志/提示，<b>不参与</b>字体色决策（字体色必须固定）。 */
    static int luma(int argb) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000;
    }

    interface Callback {
        void onPicked(int argb);
    }

    /** 弹出背景色选择：预设色板 + 自定义 HEX。 */
    static void showPicker(final Activity a, final int current, final Callback cb) {
        final String[] items = new String[PRESET_NAMES.length + 1];
        for (int i = 0; i < PRESET_NAMES.length; i++) {
            items[i] = (PRESETS[i] == current ? "[当前] " : "") + PRESET_NAMES[i];
        }
        items[PRESET_NAMES.length] = "自定义（输入 #RRGGBB）…";
        AcDialog.builder(a)
                .setTitle("查看器背景色")
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which < PRESETS.length) {
                            cb.onPicked(PRESETS[which]);
                        } else {
                            showCustom(a, current, cb);
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static void showCustom(final Activity a, int current, final Callback cb) {
        final EditText et = new EditText(a);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setSingleLine(true);
        et.setText(hex(current));
        et.setSelection(et.getText().length());

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * a.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(et, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // 覆盖「确定」的默认监听：校验不通过时保留对话框并在输入框上常驻报错，
        // 不用 Toast（一闪而过看不清），也不会像系统默认那样点一下就把框关掉。
        final AlertDialog dlg = AcDialog.builder(a)
                .setTitle("自定义背景色")
                .setMessage("输入 6 位十六进制，例如 #F5F5DC\n（字体颜色保持固定，不随背景反色）")
                .setView(box)
                .setPositiveButton("确定", null)
                .setNegativeButton("取消", null)
                .create();
        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            public void onShow(DialogInterface di) {
                dlg.getButton(AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        Integer c = parse(et.getText().toString());
                        if (c == null) {
                            et.setError("格式应为 #RRGGBB，例如 #F5F5DC");
                            return;
                        }
                        dlg.dismiss();
                        cb.onPicked(c);
                    }
                });
            }
        });
        dlg.show();
    }
}
