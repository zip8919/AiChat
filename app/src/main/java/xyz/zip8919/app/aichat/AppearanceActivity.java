package xyz.zip8919.app.aichat;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * F1 外观设置主页（交互方案 4、实现方案 8.1）。
 *
 * <p>UI 层只跟 AppearanceManager 打交道：控件改动写 working 态（不落盘），
 * 立刻触发即时预览；三按钮决定「本次运行生效 / 写盘 / 回内置默认」。
 *
 * <p>三个老设备上的实现约束：
 * <ul>
 *   <li>Spinner 的程序化 setSelection 会触发 OnItemSelectedListener，
 *       用 suppress + post 清除 + 幂等比较三层防护，避免把初始化误判成用户改动；</li>
 *   <li>显示大小 SeekBar 只在跨 25% 档时写 Manager，一次拖动最多 8 次重刷，
 *       不在每个 progress 上遍历 View 树 / 触发 WebView JS；</li>
 *   <li>数值区打 ac_no_scale、页标题打 ac_title_text，按钮由 Applier 自动降级，
 *       保证 250% 档不溢出（交互方案 13.3）。</li>
 * </ul>
 */
public class AppearanceActivity extends ThemedActivity {

    private static final String TAG = "AppearanceActivity";

    /** 内置 10 色（交互方案 3.2）：5 列 x 2 行网格，总宽 222dp，不横滑。 */
    private static final String[][] PALETTE = {
            {"朱红", "#E53935"}, {"橙", "#FB8C00"}, {"琥珀", "#FDD835"},
            {"绿", "#43A047"}, {"蓝", "#1E88E5"}, {"靛", "#3949AB"},
            {"紫", "#8E24AA"}, {"棕", "#6D4C41"}, {"墨黑", "#212121"},
            {"纸白", "#FAFAFA"}
    };
    private static final String PAPER_WHITE = "#FAFAFA";

    private AppearanceManager am;
    /** 回填 UI 期间抑制监听器；用 post 清除以覆盖 Spinner 的异步选中回调。 */
    private boolean suppress;
    private boolean firstResume = true;

    private TextView statusTv, colorSelectedTv, scaleValueTv, scaleWarningTv,
            fontStepValueTv, themeMetaTv, themeFontMissingTv;
    private Switch darkSwitch;
    private SeekBar scaleSeek, fontStepSeek;
    private Spinner fontSpinner, themeSpinner;
    private LinearLayout colorGrid;
    private Button btnReset, btnTemp, btnPermanent, scalePreviewBtn;
    private final List<View> swatches = new ArrayList<View>();
    private AppearanceSpinnerAdapter fontAdapter, themeAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_appearance);
        am = AppearanceManager.getInstance(this);
        bindViews();
        markSpecialViews();
        buildColorGrid();
        buildAdapters();
        wireListeners();
        refreshAll();
        // setContentView 那一轮 Applier 跑在打标记之前，末尾强制再跑一次，
        // 让「数值区不缩放 / 按钮降级 / 标题封顶」三条防溢出规则真正生效。
        forceApplyAppearance();
        LogUtil.i(TAG, "onCreate: percent=%d step=%d dark=%s theme=%s",
                am.getDisplayPercent(), am.getFontSizeStep(), am.isDark(), am.getThemeId());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (firstResume) {
            firstResume = false;
            return;
        }
        // 从管理页返回时字体/主题可能增删，重扫目录并重建两个 Spinner。
        // 不再 invalidate()：save/delete/rename/copy 自己会清 cache，
        // list() 的 500ms 目录签名也能兜住外部改动。每次返回都强制重扫，
        // 等于主线程重新读全部主题文件 + JSON 解析 + CSS 清洗（10 个 8KB 主题几十 ms）。
        buildAdapters();
        refreshAll();
    }

    private void bindViews() {
        statusTv = (TextView) findViewById(R.id.appearance_status);
        colorSelectedTv = (TextView) findViewById(R.id.color_selected);
        scaleValueTv = (TextView) findViewById(R.id.scale_value);
        scaleWarningTv = (TextView) findViewById(R.id.scale_warning);
        fontStepValueTv = (TextView) findViewById(R.id.font_step_value);
        themeMetaTv = (TextView) findViewById(R.id.theme_meta);
        themeFontMissingTv = (TextView) findViewById(R.id.theme_font_missing);
        darkSwitch = (Switch) findViewById(R.id.dark_switch);
        scaleSeek = (SeekBar) findViewById(R.id.scale_seek);
        fontStepSeek = (SeekBar) findViewById(R.id.font_step_seek);
        fontSpinner = (Spinner) findViewById(R.id.font_spinner);
        themeSpinner = (Spinner) findViewById(R.id.theme_spinner);
        colorGrid = (LinearLayout) findViewById(R.id.color_grid);
        btnReset = (Button) findViewById(R.id.btn_reset);
        btnTemp = (Button) findViewById(R.id.btn_temp);
        btnPermanent = (Button) findViewById(R.id.btn_permanent);
        scalePreviewBtn = (Button) findViewById(R.id.scale_preview);
    }

    private void markSpecialViews() {
        findViewById(R.id.appearance_title).setTag(R.id.ac_title_text, Boolean.TRUE);
        scaleValueTv.setTag(R.id.ac_no_scale, Boolean.TRUE);
        fontStepValueTv.setTag(R.id.ac_no_scale, Boolean.TRUE);
        // 数值区固定 56dp，被首轮 Applier 放大后不会再自动回退，这里显式恢复基准字号。
        scaleValueTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        fontStepValueTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
    }

    // ---- 色板网格 ----

    private void buildColorGrid() {
        // 色板的 10 个色块是「供挑选的颜色对象」，不是品牌色。整块豁免，
        // 否则深色模式下它们会被重着色层统一刷成 surface 灰，取色功能直接失真。
        colorGrid.setTag(R.id.ac_no_recolor, Boolean.TRUE);
        colorGrid.removeAllViews();
        swatches.clear();
        int cell = dp(38);
        int gap = dp(8);
        for (int row = 0; row < 2; row++) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            rowLp.bottomMargin = gap;
            line.setLayoutParams(rowLp);
            for (int col = 0; col < 5; col++) {
                final int idx = row * 5 + col;
                View sw = new View(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cell, cell);
                if (col < 4) lp.rightMargin = gap;
                sw.setLayoutParams(lp);
                sw.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        applyExplicitColor(PALETTE[idx][1]);
                    }
                });
                line.addView(sw);
                swatches.add(sw);
            }
            colorGrid.addView(line);
        }
    }

    private void refreshSwatches() {
        ThemeResolver.Resolved r = am.resolve(ThemeTokens.AC_PRIMARY, am.isDark());
        int selected = -1;
        if (r.overridden && r.value != null) {
            for (int i = 0; i < PALETTE.length; i++) {
                if (PALETTE[i][1].equalsIgnoreCase(r.value)) {
                    selected = i;
                    break;
                }
            }
        }
        for (int i = 0; i < swatches.size(); i++) {
            View sw = swatches.get(i);
            int color = Color.parseColor(PALETTE[i][1]);
            GradientDrawable gd = new GradientDrawable();
            gd.setColor(color);
            gd.setCornerRadius(dp(4));
            if (i == selected) {
                // 3dp inset 描边：GradientDrawable 的 stroke 画在 bounds 内，不外扩撑破 222dp
                gd.setStroke(dp(3), contrastStroke(color));
            } else if (PAPER_WHITE.equalsIgnoreCase(PALETTE[i][1])) {
                // 纸白在白底上必须带 1dp 边才可见
                gd.setStroke(dp(1), 0xFFBDBDBD);
            }
            sw.setBackgroundDrawable(gd);
        }
    }

    private int contrastStroke(int color) {
        int lum = (Color.red(color) * 299 + Color.green(color) * 587
                + Color.blue(color) * 114) / 1000;
        return lum > 140 ? 0xFF212121 : 0xFFFFFFFF;
    }

    private String colorName(String hex) {
        if (hex == null) return "自定义";
        for (int i = 0; i < PALETTE.length; i++) {
            if (PALETTE[i][1].equalsIgnoreCase(hex)) return PALETTE[i][0];
        }
        return "自定义";
    }

    private void refreshColorLabel() {
        ThemeResolver.Resolved r = am.resolve(ThemeTokens.AC_PRIMARY, am.isDark());
        String hex = r.value == null ? "" : r.value;
        String name = colorName(hex);
        if (r.overridden) {
            colorSelectedTv.setText("已覆盖 · " + name + " " + hex
                    + (r.themeName != null ? " · 主题为 " + r.themeName : ""));
        } else if (r.source == ThemeResolver.SOURCE_THEME) {
            colorSelectedTv.setText("跟随主题 · "
                    + (r.themeName == null ? "" : r.themeName) + " · " + name + " " + hex);
        } else {
            colorSelectedTv.setText("跟随主题 · 内置默认 · " + name + " " + hex);
        }
    }

    // ---- Spinner 数据 ----

    private void buildAdapters() {
        suppress = true;

        List<AppearanceSpinnerAdapter.Entry> fe =
                new ArrayList<AppearanceSpinnerAdapter.Entry>();
        fe.add(new AppearanceSpinnerAdapter.Entry("系统默认", "使用系统 Roboto 字体", "system", false));
        List<FontRepository.FontItem> fonts = FontRepository.getInstance().list();
        for (int i = 0; i < fonts.size(); i++) {
            FontRepository.FontItem f = fonts.get(i);
            if (!f.enabled || f.loadFailed) continue;
            String sub = f.fileName + " · " + (f.sizeBytes / 1024) + "KB";
            fe.add(new AppearanceSpinnerAdapter.Entry(f.name, sub, f.id, false));
        }
        fontAdapter = new AppearanceSpinnerAdapter(this, fe);
        fontSpinner.setAdapter(fontAdapter);
        fontSpinner.setSelection(indexOfValue(fontAdapter, am.getEffective(ThemeTokens.FONT_UI)), false);

        List<AppearanceSpinnerAdapter.Entry> te =
                new ArrayList<AppearanceSpinnerAdapter.Entry>();
        te.add(new AppearanceSpinnerAdapter.Entry(
                "不使用自定义主题", "使用内置默认外观", "", false));
        te.add(new AppearanceSpinnerAdapter.Entry("内置主题", "", null, true));
        List<Theme> all = ThemeRepository.getInstance().list();
        boolean userHeaderAdded = false;
        for (int i = 0; i < all.size(); i++) {
            Theme t = all.get(i);
            if (!t.builtin && !userHeaderAdded) {
                te.add(new AppearanceSpinnerAdapter.Entry("我的主题", "", null, true));
                userHeaderAdded = true;
            }
            te.add(themeEntry(t));
        }
        themeAdapter = new AppearanceSpinnerAdapter(this, te);
        themeSpinner.setAdapter(themeAdapter);
        themeSpinner.setSelection(indexOfValue(themeAdapter, am.getThemeId()), false);

        postSuppressClear();
    }

    private AppearanceSpinnerAdapter.Entry themeEntry(Theme t) {
        String main = t.name;
        String sub = (t.author == null || t.author.length() == 0) ? "" : t.author;
        boolean bad = t.fileMissing || t.versionIncompatible;
        if (t.fileMissing) {
            main = main + " [文件缺失]";
            sub = "不可选，文件已丢失";
        } else if (t.versionIncompatible) {
            main = main + " [版本不兼容]";
            sub = "不可选，版本不受支持";
        } else {
            sub = (t.builtin ? "内置 · " : "自定义 · ") + sub;
        }
        return new AppearanceSpinnerAdapter.Entry(main, sub, t.id, false, bad);
    }

    private int indexOfValue(AppearanceSpinnerAdapter adapter, String value) {
        if (adapter == null || value == null) return 0;
        for (int i = 0; i < adapter.getCount(); i++) {
            AppearanceSpinnerAdapter.Entry e = adapter.entryAt(i);
            if (!e.header && !e.disabled && e.value != null && e.value.equals(value)) return i;
        }
        return 0;
    }

    /** Spinner 的选中回调可能 post 到下一帧，必须延后清除 suppress，否则初始化会被当成改动。 */
    private void postSuppressClear() {
        colorGrid.post(new Runnable() {
            public void run() { suppress = false; }
        });
    }

    // ---- 监听器 ----

    private void wireListeners() {
        darkSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean isChecked) {
                if (suppress) return;
                am.setDark(isChecked);
                onUserChange();
            }
        });

        scaleSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (!fromUser || suppress) return;
                int raw = progress + 50;   // SeekBar max=200 对应 50~250%
                // 档位制：数值区直接显示吸附后的档位，避免「显示 137% 实际生效 150%」的误导。
                // 只在跨档时才写 Manager，一次拖动最多 8 次重刷，Cortex-A7 上不卡。
                int snapped = ScalePolicy.snap(raw);
                scaleValueTv.setText(snapped + "%");
                boolean warn = ScalePolicy.needsWarning(snapped);
                scaleWarningTv.setVisibility(warn ? View.VISIBLE : View.GONE);
                scalePreviewBtn.setVisibility(warn ? View.VISIBLE : View.GONE);
                if (snapped != am.getDisplayPercent()) {
                    am.setDisplayPercent(snapped);
                    onUserChange();
                }
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {
                int p = am.getDisplayPercent();
                suppress = true;
                s.setProgress(p - 50);
                scaleValueTv.setText(p + "%");
                suppress = false;
            }
        });

        fontStepSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (!fromUser || suppress) return;
                fontStepValueTv.setText(ScalePolicy.stepLabel(progress));
                am.setFontSizeStep(progress);
                onUserChange();
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });

        fontSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (suppress || fontAdapter == null) return;
                AppearanceSpinnerAdapter.Entry e = fontAdapter.entryAt(pos);
                if (e == null || e.header || e.disabled || e.value == null) return;
                // 幂等：与当前生效值相同就不写覆盖，避免异步回调把初始化当改动。
                String cur = am.getEffective(ThemeTokens.FONT_UI);
                if (e.value.equals(cur)) return;
                am.setExplicit(ThemeTokens.FONT_UI, e.value, am.isDark());
                onUserChange();
            }
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        themeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (suppress || themeAdapter == null) return;
                AppearanceSpinnerAdapter.Entry e = themeAdapter.entryAt(pos);
                if (e == null || e.header || e.value == null) return;
                if (e.disabled) {
                    AcToast.show(AppearanceActivity.this, "该主题文件缺失或版本不兼容，不可选用");
                    return;
                }
                if (e.value.equals(am.getThemeId())) return;
                am.setThemeId(e.value);
                onUserChange();
            }
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        findViewById(R.id.color_custom).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                ColorPickerDialog.show(AppearanceActivity.this,
                        am.getEffective(ThemeTokens.AC_PRIMARY),
                        new ColorPickerDialog.OnPicked() {
                            public void onPicked(String hex) { applyExplicitColor(hex); }
                        });
            }
        });

        findViewById(R.id.attr_edit).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(AppearanceActivity.this, ThemeAttrActivity.class));
            }
        });

        findViewById(R.id.color_follow).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean dark = am.isDark();
                am.clearExplicit(ThemeTokens.AC_PRIMARY, dark);
                am.clearExplicit(ThemeTokens.AC_ACCENT, dark);
                onUserChange();
            }
        });

        findViewById(R.id.font_follow).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                am.clearExplicit(ThemeTokens.FONT_UI, am.isDark());
                onUserChange();
            }
        });

        findViewById(R.id.manage_fonts).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(AppearanceActivity.this, FontManagerActivity.class));
            }
        });
        findViewById(R.id.manage_themes).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(AppearanceActivity.this, ThemeManagerActivity.class));
            }
        });
        scalePreviewBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent i = new Intent(AppearanceActivity.this, ThemePreviewActivity.class);
                i.putExtra("percent", am.getDisplayPercent());
                startActivity(i);
            }
        });
        findViewById(R.id.follow_all).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { confirmFollowAll(); }
        });

        btnReset.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { confirmReset(); }
        });
        btnTemp.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                am.applyTemp();
                refreshStatus();
                AcToast.show(AppearanceActivity.this, "仅本次运行生效，重启后恢复上次保存");
            }
        });
        btnPermanent.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                am.applyPermanent();
                refreshStatus();
                AcToast.show(AppearanceActivity.this, "已永久保存");
            }
        });

        wireInfo(R.id.color_info);
        wireInfo(R.id.scale_info);
        wireInfo(R.id.font_size_info);
        wireInfo(R.id.theme_info);
        wireInfo(R.id.save_info);
    }

    /** 沿用 SettingsActivity 的 android:tag + 点击弹层模式。 */
    private void wireInfo(int id) {
        final TextView tv = (TextView) findViewById(id);
        if (tv == null) return;
        final Object tag = tv.getTag();
        if (!(tag instanceof String)) return;
        tv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                AcDialog.builder(AppearanceActivity.this)
                        .setTitle("说明")
                        .setMessage((String) tag)
                        .setPositiveButton("知道了", null)
                        .show();
            }
        });
    }

    // ---- 改动 → 即时预览 ----

    private void applyExplicitColor(String hex) {
        boolean dark = am.isDark();
        am.setExplicit(ThemeTokens.AC_PRIMARY, hex, dark);
        am.setExplicit(ThemeTokens.AC_ACCENT, hex, dark);
        onUserChange();
    }

    /** 任一控件改动：先刷原生当前页，再刷新页内派生状态；WebView 侧由 ChangeListener 自动换样式。 */
    private void onUserChange() {
        forceApplyAppearance();
        refreshDerived();
    }

    // ---- 状态与派生 UI ----

    private void refreshAll() {
        suppress = true;
        darkSwitch.setChecked(am.isDark());
        int percent = am.getDisplayPercent();
        scaleSeek.setProgress(percent - 50);
        scaleValueTv.setText(percent + "%");
        int step = am.getFontSizeStep();
        fontStepSeek.setProgress(step);
        fontStepValueTv.setText(ScalePolicy.stepLabel(step));
        boolean warn = ScalePolicy.needsWarning(percent);
        scaleWarningTv.setVisibility(warn ? View.VISIBLE : View.GONE);
        scalePreviewBtn.setVisibility(warn ? View.VISIBLE : View.GONE);
        fontSpinner.setSelection(indexOfValue(fontAdapter, am.getEffective(ThemeTokens.FONT_UI)), false);
        themeSpinner.setSelection(indexOfValue(themeAdapter, am.getThemeId()), false);
        refreshDerived();
        postSuppressClear();
    }

    private void refreshDerived() {
        refreshStatus();
        refreshSwatches();
        refreshColorLabel();
        refreshThemeMeta();
        refreshActionButtons();
    }

    private void refreshStatus() {
        if (am.isTempApplied()) {
            statusTv.setText("● 临时生效，重启后恢复上次保存");
            statusTv.setVisibility(View.VISIBLE);
        } else if (am.isDirty()) {
            statusTv.setText("● 有未保存的改动");
            statusTv.setVisibility(View.VISIBLE);
        } else {
            statusTv.setVisibility(View.GONE);
        }
    }

    private void refreshThemeMeta() {
        String id = am.getThemeId();
        if (id.length() == 0) {
            themeMetaTv.setText("当前：内置默认外观");
            themeFontMissingTv.setVisibility(View.GONE);
            return;
        }
        Theme t = ThemeRepository.getInstance().find(id);
        if (t == null) {
            themeMetaTv.setText("当前主题文件缺失，已回退内置默认");
            themeFontMissingTv.setVisibility(View.GONE);
            return;
        }
        StringBuilder s = new StringBuilder(t.name);
        if (t.author != null && t.author.length() > 0) s.append(" · ").append(t.author);
        if (t.description != null && t.description.length() > 0) s.append(" · ").append(t.description);
        if (t.fileMissing) s.append(" [文件缺失]");
        if (t.versionIncompatible) s.append(" [版本不兼容]");
        themeMetaTv.setText(s.toString());

        Set<String> installed = FontRepository.getInstance().installedIds();
        StringBuilder miss = new StringBuilder();
        if (!isSystemRef(t.fontUi) && !installed.contains(t.fontUi)) {
            miss.append("界面字体 ").append(t.fontUi);
        }
        if (!isSystemRef(t.fontMono) && !installed.contains(t.fontMono)) {
            if (miss.length() > 0) miss.append("、");
            miss.append("等宽字体 ").append(t.fontMono);
        }
        if (miss.length() > 0) {
            themeFontMissingTv.setText("字体缺失：" + miss + "，已回退系统默认");
            themeFontMissingTv.setVisibility(View.VISIBLE);
        } else {
            themeFontMissingTv.setVisibility(View.GONE);
        }
    }

    private boolean isSystemRef(String s) {
        return s == null || s.length() == 0 || "system".equals(s);
    }

    private void refreshActionButtons() {
        int primary = am.getEffectiveColor(ThemeTokens.AC_PRIMARY);
        int text = am.getEffectiveColor(ThemeTokens.AC_TEXT);

        GradientDrawable solid = new GradientDrawable();
        solid.setColor(primary);
        solid.setCornerRadius(dp(2));
        btnPermanent.setBackgroundDrawable(solid);
        // 字色按主色亮度取黑/白：内置「高对比」主题的主色是 #FFFFFF，
        // 写死白字就是白底白字，「永久保存」直接看不见。
        btnPermanent.setTextColor(ThemeCssBuilder.contrastColor(primary));

        GradientDrawable soft = new GradientDrawable();
        soft.setColor(0x00000000);
        soft.setStroke(dp(1), primary);
        soft.setCornerRadius(dp(2));
        btnTemp.setBackgroundDrawable(soft);
        btnTemp.setTextColor(primary);

        // 重置用描边样式与两个主色保存按钮视觉分离，配二次确认防误触
        GradientDrawable outline = new GradientDrawable();
        outline.setColor(0x00000000);
        outline.setStroke(dp(1), 0xFFBDBDBD);
        outline.setCornerRadius(dp(2));
        btnReset.setBackgroundDrawable(outline);
        btnReset.setTextColor(text);
    }

    // ---- 二次确认与退出保护 ----

    private void confirmReset() {
        AcDialog.builder(this)
                .setTitle("重置外观")
                .setMessage("回到内置默认外观：100% 显示大小、标准字号、浅色模式、不使用自定义主题、全部跟随主题。"
                        + "已导入的字体与主题文件不会被删除。重置后仍需点「永久保存」才会写入配置。")
                .setPositiveButton("重置", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        am.reset();
                        refreshAll();
                        forceApplyAppearance();
                        AcToast.show(AppearanceActivity.this, "已重置，尚未写盘");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmFollowAll() {
        AcDialog.builder(this)
                .setTitle("全部跟随主题")
                .setMessage("清除浅色与深色两套里的所有手动覆盖项（主色、强调色、界面字体），"
                        + "改由当前主题或内置默认提供。显示大小、深色模式、所选主题本身不变。")
                .setPositiveButton("清除覆盖", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        am.clearAllExplicit();
                        onUserChange();
                        AcToast.show(AppearanceActivity.this, "已清除全部手动覆盖");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    public void onBackPressed() {
        if (am == null || am.isClean()) {
            super.onBackPressed();
            return;
        }
        AcDialog.builder(this)
                .setTitle("有未保存的外观改动")
                .setItems(new String[]{"放弃改动", "临时生效", "保存并返回"},
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int which) {
                                if (which == 0) {
                                    am.discard();       // bump 会让 WebView 同步回滚
                                    finish();
                                } else if (which == 1) {
                                    am.applyTemp();
                                    finish();
                                } else {
                                    am.applyPermanent();
                                    finish();
                                }
                            }
                        })
                .setNegativeButton("继续编辑", null)
                .show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
