package xyz.zip8919.app.aichat;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 逐属性编辑页：16 个颜色 token + 2 个字体，全部可改，改完立刻在界面上生效。
 *
 * <p><b>两种模式</b>：
 * <ul>
 *   <li><b>覆盖模式</b>（从外观页进入）：写 {@link AppearanceManager} 的显式覆盖，
 *       优先于主题；「跟随主题」= 删掉这条覆盖。</li>
 *   <li><b>主题模式</b>（从主题编辑页进入，通过 {@link #sTheme} 传入内存对象）：
 *       写主题自己的 tokens；「跟随主题」= 从主题里删掉该项，回落到内置默认。</li>
 * </ul>
 *
 * <p><b>实时预览怎么做到</b>：两种模式都同时写一份 explicit，因为 explicit 优先级最高，
 * 界面会立刻变色。退出时把 explicit 恢复到进入前的快照 —— 预览不污染用户的外观配置。
 *
 * <p><b>取消的语义</b>：恢复进入前的覆盖表，而不是回滚到磁盘快照 —— 后者会把用户
 * 在外观页攒下的未保存改动一并冲掉。
 */
public class ThemeAttrActivity extends ThemedActivity {

    private static final String TAG = "ThemeAttr";

    /** 主题模式的目标对象。同进程静态传参，避免把整份主题塞进 Intent。 */
    static Theme sTheme;
    /** 配置变更重建时供新实例取回的主题对象；不参与全新启动的「连点两次」判定。 */
    private static Theme sResumeTheme;

    private static final String[][] LABELS = {
            {ThemeTokens.AC_PRIMARY, "主色", "顶部栏、主按钮、选中态"},
            {ThemeTokens.AC_PRIMARY_DARK, "主色·深", "按压态、状态栏"},
            {ThemeTokens.AC_ACCENT, "强调色", "强调文字、引用边"},
            {ThemeTokens.AC_BG, "页面底色", "整页背景"},
            {ThemeTokens.AC_SURFACE, "表面色", "卡片、输入框、列表"},
            {ThemeTokens.AC_TEXT, "主文字", "正文、标题"},
            {ThemeTokens.AC_TEXT_SECONDARY, "次要文字", "说明、时间戳"},
            {ThemeTokens.AC_DIVIDER, "分隔线", "列表分割、分区线"},
            {ThemeTokens.AC_BUBBLE_USER, "用户气泡", "自己发送的消息"},
            {ThemeTokens.AC_BUBBLE_AI, "AI 气泡", "模型回复"},
            {ThemeTokens.AC_DANGER, "错误色", "错误卡片、失败提示"},
            {ThemeTokens.AC_TRACK, "轨道色", "进度条轨道、高亮底"},
            {ThemeTokens.AC_CODE_BG, "代码底", "代码块背景"},
            {ThemeTokens.AC_CODE_TEXT, "代码字", "代码块文字"},
            {ThemeTokens.AC_LINK, "链接色", "超链接"},
            {ThemeTokens.AC_BORDER, "边框色", "描边、输入框边"},
    };

    private AppearanceManager am;
    /** 非 null = 主题模式：直接编辑这份主题的 tokens。 */
    private Theme theme;
    private boolean editingDark;
    private boolean originDark;

    private Map<String, String> snapLight;
    private Map<String, String> snapDark;
    // 主题模式的 tokens 快照：取消编辑时要精确回滚，不能靠「删掉本次写过的 key」——
    // 那会把主题里原本就有的值一起删掉（用户改了主色再取消，主色就变成未定义）。
    private Map<String, String> tkLight;
    private Map<String, String> tkDark;
    private String tkFontUi, tkFontMono;
    private LinearLayout list;
    private Button btnLight, btnDark;
    private final Map<String, Row> rows = new HashMap<String, Row>();

    private static final class Row {
        View swatch;
        TextView name;
        TextView value;
        TextView arrow;
        /** 上次画上去的颜色；相同就不重建 drawable（拖动取色时每秒会刷十几次）。 */
        int lastColor = Integer.MIN_VALUE;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_theme_attr);
        am = AppearanceManager.getInstance(this);
        Bundle b = savedInstanceState;
        if (b != null) {
            // 配置变更 / 被回收后重建：必须沿用进入前的回滚基准。
            // 若重新 snapshotExplicit()，拿到的是已被实时预览污染的值，
            // 之后点「取消」也回滚不掉；主题模式更不能因 sTheme 已消费而直接 finish。
            originDark = b.getBoolean("attr_origin_dark");
            editingDark = b.getBoolean("attr_editing_dark");
            snapLight = (Map<String, String>) b.getSerializable("attr_snap_light");
            snapDark = (Map<String, String>) b.getSerializable("attr_snap_dark");
            if (snapLight == null) snapLight = new HashMap<String, String>();
            if (snapDark == null) snapDark = new HashMap<String, String>();
            if (b.getBoolean("attr_theme_mode")) {
                theme = sResumeTheme;
                sResumeTheme = null;
                tkLight = (Map<String, String>) b.getSerializable("attr_tk_light");
                tkDark = (Map<String, String>) b.getSerializable("attr_tk_dark");
                tkFontUi = b.getString("attr_font_ui");
                tkFontMono = b.getString("attr_font_mono");
                // 进程被杀后静态对象丢失：am 已从磁盘重建、预览色不残留，安全退出。
                if (theme == null) { finish(); return; }
            }
        } else {
            theme = sTheme;
            sTheme = null;
            if (getIntent().getBooleanExtra("theme_mode", false) && theme == null) {
                // 静态引用已被上一次启动消费掉（连点两次）：不能悄悄退化成覆盖模式，
                // 那会让用户以为在改主题，实际改的是全局覆盖。
                finish();
                return;
            }
            originDark = am.isDark();
            editingDark = originDark;
            snapLight = am.snapshotExplicit(false);
            snapDark = am.snapshotExplicit(true);
            if (theme != null) {
                tkLight = new HashMap<String, String>(theme.lightTokens);
                tkDark = new HashMap<String, String>(theme.darkTokens);
                tkFontUi = theme.fontUi;
                tkFontMono = theme.fontMono;
            }
        }

        list = (LinearLayout) findViewById(R.id.attr_list);
        btnLight = (Button) findViewById(R.id.btn_edit_light);
        btnDark = (Button) findViewById(R.id.btn_edit_dark);

        TextView title = (TextView) findViewById(R.id.attr_title);
        TextView hint = (TextView) findViewById(R.id.attr_hint);
        if (theme != null) {
            title.setText("属性 · " + (theme.name == null || theme.name.length() == 0
                    ? "未命名主题" : theme.name));
            hint.setText("改动会直接写进主题，并立即预览。返回后记得在主题编辑页点保存。");
        }

        buildRows();
        wire();
        refreshModeButtons();
        refreshRows();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("attr_origin_dark", originDark);
        out.putBoolean("attr_editing_dark", editingDark);
        out.putSerializable("attr_snap_light", snapLight == null ? null : new HashMap<String, String>(snapLight));
        out.putSerializable("attr_snap_dark", snapDark == null ? null : new HashMap<String, String>(snapDark));
        out.putBoolean("attr_theme_mode", theme != null);
        if (theme != null) {
            sResumeTheme = theme;
            out.putSerializable("attr_tk_light", tkLight == null ? null : new HashMap<String, String>(tkLight));
            out.putSerializable("attr_tk_dark", tkDark == null ? null : new HashMap<String, String>(tkDark));
            out.putString("attr_font_ui", tkFontUi);
            out.putString("attr_font_mono", tkFontMono);
        }
    }

    @Override
    protected void onDestroy() {
        sTheme = null;
        super.onDestroy();
    }

    private void wire() {
        btnLight.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { switchMode(false); }
        });
        btnDark.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { switchMode(true); }
        });
        findViewById(R.id.btn_attr_apply).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                finishWithApply();
            }
        });
        findViewById(R.id.btn_attr_cancel).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (theme != null) restoreThemeSnapshot();
                rollbackPreview();
                finish();
            }
        });
    }

    /** 主题模式取消：把 tokens 精确恢复到进入前。 */
    private void restoreThemeSnapshot() {
        if (theme == null) return;
        theme.lightTokens.clear();
        if (tkLight != null) theme.lightTokens.putAll(tkLight);
        theme.darkTokens.clear();
        if (tkDark != null) theme.darkTokens.putAll(tkDark);
        theme.fontUi = tkFontUi;
        theme.fontMono = tkFontMono;
    }

    /**
     * 「应用」：覆盖模式保留改动（回到外观页会显示有未保存改动）；
     * 主题模式只结束预览，值已经写进主题对象。
     */
    private void finishWithApply() {
        if (theme != null) rollbackPreview();
        finish();
    }

    /** 把 explicit 恢复到进入前的快照（预览收尾），恢复全局明暗。 */
    private void rollbackPreview() {
        am.restoreExplicit(false, snapLight);
        am.restoreExplicit(true, snapDark);
        if (am.isDark() != originDark) am.setDark(originDark);
    }

    @Override
    public void onBackPressed() {
        // 返回键等同「应用」：预览期间看到的改动都留下，用户不会因为按了返回就丢半天功夫
        finishWithApply();
    }

    private void switchMode(boolean toDark) {
        if (toDark == editingDark) return;
        editingDark = toDark;
        // 全局明暗一起切，否则「编辑深色套时看到的是浅色界面」，实时预览就失去意义
        if (am.isDark() != toDark) am.setDark(toDark);
        refreshModeButtons();
        refreshRows();
    }

    private void refreshModeButtons() {
        btnLight.setText(editingDark ? "○ 浅色" : "● 浅色");
        btnDark.setText(editingDark ? "● 深色" : "○ 深色");
    }

    // ---- 取值 / 写值（两模式统一入口）----

    /** 当前显示值：主题模式读 tokens（未定义返回 null），覆盖模式读三层判定。 */
    private String currentValue(String token) {
        if (theme != null) {
            if (ThemeTokens.FONT_UI.equals(token)) {
                return theme.fontUi == null || "system".equals(theme.fontUi) ? null : theme.fontUi;
            }
            if (ThemeTokens.FONT_MONO.equals(token)) {
                return theme.fontMono == null || "system".equals(theme.fontMono) ? null : theme.fontMono;
            }
            return theme.tokenValue(token, editingDark);
        }
        String v = am.getExplicit(token, editingDark);
        if (v != null && v.length() > 0) return v;
        return null;
    }

    /** 生效色（用于色块与取色器初值）。 */
    private int effectiveColor(String token) {
        String v = currentValue(token);
        Integer c = ThemeTokens.parseColor(v);
        if (c != null) return c.intValue();
        // 主题模式未定义时回落到主题/内置
        return am.getEffectiveColor(token, editingDark);
    }

    private void setValue(String token, String value) {
        if (theme != null) {
            if (ThemeTokens.FONT_UI.equals(token)) {
                theme.fontUi = value == null ? "system" : value;
            } else if (ThemeTokens.FONT_MONO.equals(token)) {
                theme.fontMono = value == null ? "system" : value;
            } else {
                Map<String, String> m = editingDark ? theme.darkTokens : theme.lightTokens;
                if (value == null) m.remove(token);
                else m.put(token, value);
            }
            if (value == null) am.clearExplicit(token, editingDark);
            else am.setExplicit(token, value, editingDark);
        } else {
            if (value == null) am.clearExplicit(token, editingDark);
            else am.setExplicit(token, value, editingDark);
        }
        // 只刷这一行：拖动取色时每 150ms 一次，全量刷 18 行会让老机器明显掉帧
        refreshRow(token);
    }

    // ---- 行的构建与刷新 ----

    private void buildRows() {
        list.removeAllViews();
        rows.clear();

        addSection("颜色");
        for (int i = 0; i < LABELS.length; i++) {
            addColorRow(LABELS[i][0], LABELS[i][1], LABELS[i][2]);
        }
        addSection("字体");
        addFontRow(ThemeTokens.FONT_UI, "界面字体", "正文字体");
        addFontRow(ThemeTokens.FONT_MONO, "等宽字体", "代码块字体");
    }

    private void addSection(String title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(12);
        // 分组标题要随明暗刷新，打个标记方便 refreshRows 找回来
        tv.setTag("section");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        lp.bottomMargin = dp(4);
        tv.setLayoutParams(lp);
        list.addView(tv);
    }

    private void addColorRow(final String token, String name, String desc) {
        final Row r = new Row();
        LinearLayout row = baseRow();
        r.swatch = makeSwatch();
        row.addView(r.swatch);
        fillTexts(row, r, name, desc);
        list.addView(row);
        rows.put(token, r);
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openColorEditor(token); }
        });
    }

    private void addFontRow(final String token, String name, String desc) {
        Row r = new Row();
        LinearLayout row = baseRow();
        TextView mark = new TextView(this);
        mark.setText("Aa");
        mark.setTextSize(13);
        mark.setGravity(Gravity.CENTER);
        mark.setTypeface(android.graphics.Typeface.MONOSPACE);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(dp(28), dp(28));
        mlp.rightMargin = dp(10);
        mark.setLayoutParams(mlp);
        r.swatch = mark;   // 字体行没有颜色块，用 Aa 占位，随主题刷文字色
        row.addView(mark);
        fillTexts(row, r, name, desc);
        list.addView(row);
        rows.put(token, r);
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openFontPicker(token); }
        });
    }

    private LinearLayout baseRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(52));
        row.setPadding(dp(4), dp(6), dp(4), dp(6));
        row.setBackgroundResource(android.R.drawable.list_selector_background);
        row.setClickable(true);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private View makeSwatch() {
        View v = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(28), dp(28));
        lp.rightMargin = dp(10);
        v.setLayoutParams(lp);
        // 色块显示的是「这个 token 的实际颜色」，不能被通用刷色覆盖成表面色
        v.setTag(R.id.ac_no_recolor, Boolean.TRUE);
        return v;
    }

    private void fillTexts(LinearLayout row, Row r, String name, String desc) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        r.name = new TextView(this);
        r.name.setText(name);
        r.name.setTextSize(14);
        r.value = new TextView(this);
        r.value.setTextSize(11);
        r.value.setText(desc);
        box.addView(r.name);
        box.addView(r.value);
        row.addView(box);

        r.arrow = new TextView(this);
        r.arrow.setText("›");
        r.arrow.setTextSize(18);
        r.arrow.setPadding(dp(6), 0, dp(2), 0);
        row.addView(r.arrow);
    }

    private void refreshRows() {
        boolean dark = editingDark;
        int descColor = am.getEffectiveColor(ThemeTokens.AC_TEXT_SECONDARY, dark);
        for (int i = 0; i < list.getChildCount(); i++) {
            View c = list.getChildAt(i);
            if (c instanceof TextView && "section".equals(c.getTag())) {
                ((TextView) c).setTextColor(descColor);
            }
        }
        for (int i = 0; i < LABELS.length; i++) refreshRow(LABELS[i][0]);
        refreshRow(ThemeTokens.FONT_UI);
        refreshRow(ThemeTokens.FONT_MONO);
    }

    /** 刷新单行（拖动取色时只走这条，避免 18 行一起 setText 重排）。 */
    private void refreshRow(String token) {
        Row r = rows.get(token);
        if (r == null) return;
        boolean dark = editingDark;
        int nameColor = am.getEffectiveColor(ThemeTokens.AC_TEXT, dark);
        int descColor = am.getEffectiveColor(ThemeTokens.AC_TEXT_SECONDARY, dark);
        r.name.setTextColor(nameColor);
        r.value.setTextColor(descColor);
        r.arrow.setTextColor(descColor);

        if (ThemeTokens.FONT_UI.equals(token) || ThemeTokens.FONT_MONO.equals(token)) {
            String defined = currentValue(token);
            String id = defined == null ? "system" : defined;
            r.value.setText(fontLabel(id) + " · " + sourceLabel(defined));
            if (r.swatch instanceof TextView) ((TextView) r.swatch).setTextColor(nameColor);
            return;
        }

        int color = effectiveColor(token);
        if (r.lastColor != color) {
            r.swatch.setBackgroundDrawable(rounded(color, 4));
            r.lastColor = color;
        }
        r.value.setText(hexOf(color) + " · " + sourceLabel(currentValue(token)));
    }

    private String sourceLabel(String definedValue) {
        if (theme != null) {
            return definedValue != null ? "主题定义" : "未定义 · 用内置默认";
        }
        if (definedValue != null) return "已覆盖";
        String name = am.getActiveThemeName();
        return name == null ? "跟随内置默认" : "跟随主题「" + name + "」";
    }

    private String fontLabel(String id) {
        if (id == null || id.length() == 0 || "system".equals(id)) return "系统默认";
        List<FontRepository.FontItem> fonts = FontRepository.getInstance().list();
        for (int i = 0; i < fonts.size(); i++) {
            if (id.equals(fonts.get(i).id)) return fonts.get(i).name;
        }
        return id;
    }

    // ---- 颜色编辑 ----

    private void openColorEditor(final String token) {
        final int cur = effectiveColor(token);
        final float[] hsv = new float[3];
        Color.colorToHSV(cur, hsv);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        // 让容器先抢到初始焦点：否则 Dialog 会把焦点交给第一个可聚焦子 View（HEX 输入框），
        // 一弹出就顶起软键盘、把三个滑条挤到屏幕外。
        root.setFocusableInTouchMode(true);
        root.setFocusable(true);

        final View preview = new View(this);
        preview.setTag(R.id.ac_no_recolor, Boolean.TRUE);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        plp.bottomMargin = dp(10);
        preview.setLayoutParams(plp);
        root.addView(preview);

        final EditText hex = new EditText(this);
        hex.setSingleLine(true);
        hex.setTextSize(14);
        hex.setTypeface(android.graphics.Typeface.MONOSPACE);
        hex.setGravity(Gravity.CENTER);
        hex.setText(hexOf(cur));
        root.addView(hex);

        final float[] c = {hsv[0], hsv[1], hsv[2]};
        final int[] color = {cur};
        // 谁最后动过：滑条还是输入框。输入框里的 HEX 只在初始化时写一次、之后不再回写，
        // 若「确定」无条件读输入框，滑条刚调好的颜色会被初始色覆盖（用户表现为滑条完全无效）。
        final boolean[] sliderTouched = {false};
        // 预览块只建一次 drawable，之后原地改色。拖动时 onProgressChanged 每秒会来
        // 几十次，每次 new GradientDrawable 在 4.x 上就是成片的临时对象与 GC。
        final GradientDrawable previewBg = rounded(cur, 6);
        preview.setBackgroundDrawable(previewBg);

        final Runnable sync = new Runnable() {
            public void run() {
                color[0] = Color.HSVToColor(c);
                previewBg.setColor(color[0]);
            }
        };
        sync.run();

        SeekBar.OnSeekBarChangeListener l = new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (!fromUser) return;
                sliderTouched[0] = true;
                if (s.getId() == 1) c[0] = progress;
                else if (s.getId() == 2) c[1] = progress / 100f;
                else if (s.getId() == 3) c[2] = progress / 100f;
                sync.run();
                // 拖动过程中**不写全局**：setExplicit 会 bump 一次，通知进程里所有存活
                // 页面整树重刷（主界面几十个 View，每个 9-patch 底还要建位图采样），
                // 4.x 上就是「拖一格卡一下、滑条根本不跟手」。
                // 预览块已经实时跟随，抬手时（onStopTrackingTouch）再落一次值。
                // 不回写 hex 输入框：用户可能正在里面打字，抢焦点会打断输入
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {
                // 停手落值：界面整体跟着换色，作为「松手即预览」的收口
                setValue(token, hexOf(color[0]));
            }
        };
        root.addView(labeled("色相", 360, Math.round(hsv[0]), 1, l));
        root.addView(labeled("饱和度", 100, Math.round(hsv[1] * 100), 2, l));
        root.addView(labeled("明度", 100, Math.round(hsv[2] * 100), 3, l));

        hex.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int cnt) {}
            public void onTextChanged(CharSequence s, int a, int b, int cnt) {}
            public void afterTextChanged(Editable s) {
                Integer v = ThemeTokens.parseColor(s.toString());
                if (v == null) return;
                // 用户开始手输 HEX：以输入框为准，滑条此前的调整作废
                sliderTouched[0] = false;
                color[0] = v.intValue();
                Color.colorToHSV(color[0], c);
                previewBg.setColor(color[0]);
                // 同上：只更新预览，落值交给「确定」/ 松手。逐字符写全局会把输入框
                // 自己所在的对话框连同背景页一起重刷，光标都会被刷丢。
            }
        });

        final AlertDialog d = AcDialog.builder(this)
                .setTitle("编辑 · " + labelOf(token))
                .setView(root)
                .setPositiveButton("确定", null)
                .setNeutralButton("跟随主题", null)
                .setNegativeButton("取消", null)
                .create();

        AcDialog.onShow(d, new DialogInterface.OnShowListener() {
            public void onShow(DialogInterface di) {
                d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        if (sliderTouched[0]) {
                            // 滑条最后动过：用滑条结果，别再被输入框里的旧 HEX 覆盖
                            setValue(token, hexOf(color[0]));
                        } else {
                            Integer parsed = ThemeTokens.parseColor(hex.getText().toString());
                            setValue(token, hexOf(parsed == null ? color[0] : parsed.intValue()));
                        }
                        d.dismiss();
                    }
                });
                d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        setValue(token, null);
                        d.dismiss();
                    }
                });
            }
        });
        d.show();
    }

    private View labeled(String text, int max, int progress, int id,
                         SeekBar.OnSeekBarChangeListener l) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        SeekBar sb = new SeekBar(this);
        sb.setId(id);
        sb.setMax(max);
        sb.setProgress(progress);
        sb.setOnSeekBarChangeListener(l);
        box.addView(tv);
        box.addView(sb);
        return box;
    }

    // ---- 字体选择 ----

    private void openFontPicker(final String token) {
        final List<FontRepository.FontItem> fonts = FontRepository.getInstance().list();
        List<String> items = new ArrayList<String>();
        items.add("系统默认");
        final List<String> ids = new ArrayList<String>();
        ids.add("system");
        for (int i = 0; i < fonts.size(); i++) {
            FontRepository.FontItem f = fonts.get(i);
            if (!f.enabled || f.loadFailed) continue;
            items.add(f.name + " · " + (f.sizeBytes / 1024) + "KB");
            ids.add(f.id);
        }
        AcDialog.builder(this)
                .setTitle("选择 · " + labelOf(token))
                .setItems(items.toArray(new String[items.size()]),
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int which) {
                                String id = ids.get(which);
                                setValue(token, "system".equals(id) ? null : id);
                            }
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---- 工具 ----

    private String labelOf(String token) {
        for (int i = 0; i < LABELS.length; i++) {
            if (LABELS[i][0].equals(token)) return LABELS[i][1];
        }
        if (ThemeTokens.FONT_UI.equals(token)) return "界面字体";
        if (ThemeTokens.FONT_MONO.equals(token)) return "等宽字体";
        return token;
    }

    private static String hexOf(int color) {
        return String.format("#%06X", 0xFFFFFF & color);
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        // 纯白/纯黑块在相近底色上会「消失」，补一圈细边保证任何配色下都可见
        g.setStroke(Math.max(1, dp(1) / 2), 0x33888888);
        return g;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
