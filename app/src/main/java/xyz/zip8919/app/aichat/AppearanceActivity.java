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
            {Strings.get(R.string.appearanceactivity_001), "#E53935"}, {Strings.get(R.string.appearanceactivity_002), "#FB8C00"}, {Strings.get(R.string.appearanceactivity_003), "#FDD835"},
            {Strings.get(R.string.appearanceactivity_004), "#43A047"}, {Strings.get(R.string.appearanceactivity_005), "#1E88E5"}, {Strings.get(R.string.appearanceactivity_006), "#3949AB"},
            {Strings.get(R.string.appearanceactivity_007), "#8E24AA"}, {Strings.get(R.string.appearanceactivity_008), "#6D4C41"}, {Strings.get(R.string.appearanceactivity_009), "#212121"},
            {Strings.get(R.string.appearanceactivity_010), "#FAFAFA"}
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

    /** 色块的 drawable 与颜色只建一次；之后只改「选中态变了的那一格」的描边。 */
    private GradientDrawable[] swatchGd;
    private int[] swatchColor;
    private int lastSwatch = -2;

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
        if (swatchGd == null || swatchGd.length != swatches.size()) {
            // 颜色底只建一次：原来每次控件改动都 new 13 个 GradientDrawable 再
            // setBackgroundDrawable(13 次 requestLayout)，拖字号滑条 5 档就是 65 次重排。
            // 色块本身的颜色来自 PALETTE 常量，唯一会变的是选中描边。
            swatchGd = new GradientDrawable[swatches.size()];
            swatchColor = new int[swatches.size()];
            for (int i = 0; i < swatches.size(); i++) {
                int color = Color.parseColor(PALETTE[i][1]);
                swatchColor[i] = color;
                GradientDrawable gd = new GradientDrawable();
                gd.setColor(color);
                gd.setCornerRadius(dp(4));
                swatchGd[i] = gd;
                swatches.get(i).setBackgroundDrawable(gd);
            }
            lastSwatch = -2;   // 强制走一遍描边设置
        }
        if (selected == lastSwatch) return;
        for (int i = 0; i < swatchGd.length; i++) {
            if (i == selected) {
                // 3dp inset 描边：GradientDrawable 的 stroke 画在 bounds 内，不外扩撑破 222dp
                swatchGd[i].setStroke(dp(3), contrastStroke(swatchColor[i]));
            } else if (PAPER_WHITE.equalsIgnoreCase(PALETTE[i][1])) {
                // 纸白在白底上必须带 1dp 边才可见（取消选中后要还原，所以放在 else 里）
                swatchGd[i].setStroke(dp(1), 0xFFBDBDBD);
            } else {
                swatchGd[i].setStroke(0, 0);
            }
        }
        lastSwatch = selected;
    }

    private int contrastStroke(int color) {
        int lum = (Color.red(color) * 299 + Color.green(color) * 587
                + Color.blue(color) * 114) / 1000;
        return lum > 140 ? 0xFF212121 : 0xFFFFFFFF;
    }

    private String colorName(String hex) {
        if (hex == null) return Strings.get(R.string.appearanceactivity_011);
        for (int i = 0; i < PALETTE.length; i++) {
            if (PALETTE[i][1].equalsIgnoreCase(hex)) return PALETTE[i][0];
        }
        return Strings.get(R.string.appearanceactivity_012);
    }

    private void refreshColorLabel() {
        ThemeResolver.Resolved r = am.resolve(ThemeTokens.AC_PRIMARY, am.isDark());
        String hex = r.value == null ? "" : r.value;
        String name = colorName(hex);
        if (r.overridden) {
            colorSelectedTv.setText(r.themeName != null
                    ? Strings.get(R.string.appearanceactivity_014, name, hex, r.themeName)
                    : Strings.get(R.string.appearanceactivity_013, name, hex));
        } else if (r.source == ThemeResolver.SOURCE_THEME) {
            colorSelectedTv.setText(r.themeName == null
                    ? Strings.get(R.string.appearanceactivity_063, name, hex)
                    : Strings.get(R.string.appearanceactivity_015, r.themeName, name, hex));
        } else {
            colorSelectedTv.setText(Strings.get(R.string.appearanceactivity_016, name, hex));
        }
    }

    // ---- Spinner 数据 ----

    /** 上次构建 Spinner 时字体/主题列表的签名；一致就不重建适配器。 */
    private String adapterSig;

    private void buildAdapters() {
        suppress = true;

        List<FontRepository.FontItem> fonts = FontRepository.getInstance().list();
        List<Theme> allThemes = ThemeRepository.getInstance().list();
        // 签名短路：从管理页返回本页是最常走的路径（改字体/主题必经过一次），
        // 而多数时候字体和主题一个都没变。重建适配器的代价是 new 两个适配器 +
        // 两次 setAdapter（ListView/Spinner 会重新 measure 并 inflate 选中项）
        // + 两次 setSelection，全部是主线程重排；换来的是完全相同的列表内容。
        StringBuilder sigb = new StringBuilder(128);
        sigb.append("f:");
        for (int i = 0; i < fonts.size(); i++) {
            FontRepository.FontItem f = fonts.get(i);
            // 副标题取自 fileName + sizeBytes，必须进签名：否则同名文件被替换成
            // 不同大小后签名不变、走短路只补选中项，下拉里的副标题停在旧值。
            sigb.append(f.id).append(f.enabled ? '1' : '0')
                    .append(f.loadFailed ? '1' : '0').append(f.name)
                    .append(f.fileName).append(f.sizeBytes).append('|');
        }
        sigb.append("t:");
        for (int i = 0; i < allThemes.size(); i++) {
            Theme t = allThemes.get(i);
            // themeEntry 的副标题取自 author，只改作者时也必须重建适配器。
            sigb.append(t.id).append(t.name)
                    .append(t.author).append(t.fileMissing ? '1' : '0')
                    .append(t.versionIncompatible ? '1' : '0').append('|');
        }
        String sig = sigb.toString();
        if (sig.equals(adapterSig) && fontAdapter != null && themeAdapter != null) {
            // 列表没变，但当前选中的字体/主题可能被管理页改过，补一次选中项即可
            selectIfChanged(fontSpinner, indexOfValue(fontAdapter,
                    am.getEffective(ThemeTokens.FONT_UI)));
            selectIfChanged(themeSpinner, indexOfValue(themeAdapter, am.getThemeId()));
            postSuppressClear();
            return;
        }
        adapterSig = sig;

        List<AppearanceSpinnerAdapter.Entry> fe =
                new ArrayList<AppearanceSpinnerAdapter.Entry>();
        fe.add(new AppearanceSpinnerAdapter.Entry(Strings.get(R.string.appearanceactivity_017), Strings.get(R.string.appearanceactivity_018), "system", false));
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
                Strings.get(R.string.appearanceactivity_019), Strings.get(R.string.appearanceactivity_020), "", false));
        te.add(new AppearanceSpinnerAdapter.Entry(Strings.get(R.string.appearanceactivity_021), "", null, true));
        List<Theme> all = allThemes;
        boolean userHeaderAdded = false;
        for (int i = 0; i < all.size(); i++) {
            Theme t = all.get(i);
            if (!t.builtin && !userHeaderAdded) {
                te.add(new AppearanceSpinnerAdapter.Entry(Strings.get(R.string.appearanceactivity_022), "", null, true));
                userHeaderAdded = true;
            }
            te.add(themeEntry(t));
        }
        themeAdapter = new AppearanceSpinnerAdapter(this, te);
        themeSpinner.setAdapter(themeAdapter);
        themeSpinner.setSelection(indexOfValue(themeAdapter, am.getThemeId()), false);

        postSuppressClear();
    }

    /** 只在选中项真的变了才 setSelection：setSelection 即使选同一项也会触发重排。 */
    private static void selectIfChanged(Spinner sp, int index) {
        if (sp == null || index < 0) return;
        if (sp.getSelectedItemPosition() != index) sp.setSelection(index, false);
    }

    private AppearanceSpinnerAdapter.Entry themeEntry(Theme t) {
        String main = t.name;
        String sub = (t.author == null || t.author.length() == 0) ? "" : t.author;
        boolean bad = t.fileMissing || t.versionIncompatible;
        if (t.fileMissing) {
            main = main + Strings.get(R.string.appearanceactivity_023);
            sub = Strings.get(R.string.appearanceactivity_024);
        } else if (t.versionIncompatible) {
            main = main + Strings.get(R.string.appearanceactivity_025);
            sub = Strings.get(R.string.appearanceactivity_026);
        } else {
            sub = (t.builtin ? Strings.get(R.string.appearanceactivity_027) : Strings.get(R.string.appearanceactivity_028)) + sub;
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
                    AcToast.show(AppearanceActivity.this, Strings.get(R.string.appearanceactivity_029));
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
                AcToast.show(AppearanceActivity.this, Strings.get(R.string.appearanceactivity_030));
            }
        });
        btnPermanent.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                am.applyPermanent();
                refreshStatus();
                AcToast.show(AppearanceActivity.this, Strings.get(R.string.appearanceactivity_031));
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
                        .setTitle(Strings.get(R.string.appearanceactivity_032))
                        .setMessage((String) tag)
                        .setPositiveButton(Strings.get(R.string.appearanceactivity_033), null)
                        .show();
            }
        });
    }

    // ---- 改动 → 即时预览 ----

    private void applyExplicitColor(String hex) {
        boolean dark = am.isDark();
        // 两个 token 一次性写入：逐条 setExplicit 会 bump 两次，
        // 而一次 bump 就是所有存活页面各整树重刷一遍（主线程 10~30ms）
        java.util.HashMap<String, String> kv = new java.util.HashMap<String, String>(4);
        kv.put(ThemeTokens.AC_PRIMARY, hex);
        kv.put(ThemeTokens.AC_ACCENT, hex);
        am.setExplicitAll(kv, dark);
        onUserChange();
    }

    /**
     * 任一控件改动：刷新页内派生状态。
     *
     * <p>不再在这里调 {@code forceApplyAppearance()}：{@code am.setXxx()} 内部已经
     * bump() 同步回调了本页的 pageListener（见 ThemedActivity.onCreate），那条路径
     * 已经做过一次整树重刷。原来这里再刷一次，等于每次改动（含拖动滑条的每一档）
     * 都白跑一整遍树。WebView 侧的换样式由 ChangeListener 独立完成，与此无关。
     */
    private void onUserChange() {
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
            statusTv.setText(Strings.get(R.string.appearanceactivity_034));
            statusTv.setVisibility(View.VISIBLE);
        } else if (am.isDirty()) {
            statusTv.setText(Strings.get(R.string.appearanceactivity_035));
            statusTv.setVisibility(View.VISIBLE);
        } else {
            statusTv.setVisibility(View.GONE);
        }
    }

    private void refreshThemeMeta() {
        String id = am.getThemeId();
        if (id.length() == 0) {
            themeMetaTv.setText(Strings.get(R.string.appearanceactivity_036));
            themeFontMissingTv.setVisibility(View.GONE);
            return;
        }
        Theme t = ThemeRepository.getInstance().find(id);
        if (t == null) {
            themeMetaTv.setText(Strings.get(R.string.appearanceactivity_037));
            themeFontMissingTv.setVisibility(View.GONE);
            return;
        }
        StringBuilder s = new StringBuilder(t.name);
        if (t.author != null && t.author.length() > 0) s.append(" · ").append(t.author);
        if (t.description != null && t.description.length() > 0) s.append(" · ").append(t.description);
        if (t.fileMissing) s.append(Strings.get(R.string.appearanceactivity_038));
        if (t.versionIncompatible) s.append(Strings.get(R.string.appearanceactivity_039));
        themeMetaTv.setText(s.toString());

        // 主题只引用系统字体时（绝大多数内置主题）不必去扫字体目录：
        // installedIds() 内部是 listFiles + 逐文件 stat，而 refreshThemeMeta 挂在
        // 每次控件改动都会走的 refreshDerived 上，拖动滑条时就是几十次无用扫描。
        if (isSystemRef(t.fontUi) && isSystemRef(t.fontMono)) {
            themeFontMissingTv.setVisibility(View.GONE);
            return;
        }
        Set<String> installed = FontRepository.getInstance().installedIds();
        StringBuilder miss = new StringBuilder();
        if (!isSystemRef(t.fontUi) && !installed.contains(t.fontUi)) {
            miss.append(Strings.get(R.string.appearanceactivity_040)).append(t.fontUi);
        }
        if (!isSystemRef(t.fontMono) && !installed.contains(t.fontMono)) {
            if (miss.length() > 0) miss.append("、");
            miss.append(Strings.get(R.string.appearanceactivity_041)).append(t.fontMono);
        }
        if (miss.length() > 0) {
            themeFontMissingTv.setText(Strings.get(R.string.appearanceactivity_042, miss));
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
                .setTitle(Strings.get(R.string.appearanceactivity_044))
                .setMessage(Strings.get(R.string.appearanceactivity_061))
                .setPositiveButton(Strings.get(R.string.appearanceactivity_047), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        am.reset();
                        refreshAll();
                        forceApplyAppearance();
                        AcToast.show(AppearanceActivity.this, Strings.get(R.string.appearanceactivity_048));
                    }
                })
                .setNegativeButton(Strings.get(R.string.appearanceactivity_049), null)
                .show();
    }

    private void confirmFollowAll() {
        AcDialog.builder(this)
                .setTitle(Strings.get(R.string.appearanceactivity_050))
                .setMessage(Strings.get(R.string.appearanceactivity_062))
                .setPositiveButton(Strings.get(R.string.appearanceactivity_053), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        am.clearAllExplicit();
                        onUserChange();
                        AcToast.show(AppearanceActivity.this, Strings.get(R.string.appearanceactivity_054));
                    }
                })
                .setNegativeButton(Strings.get(R.string.appearanceactivity_055), null)
                .show();
    }

    @Override
    public void onBackPressed() {
        if (am == null || am.isClean()) {
            super.onBackPressed();
            return;
        }
        AcDialog.builder(this)
                .setTitle(Strings.get(R.string.appearanceactivity_056))
                .setItems(new String[]{Strings.get(R.string.appearanceactivity_057), Strings.get(R.string.appearanceactivity_058), Strings.get(R.string.appearanceactivity_059)},
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
                .setNegativeButton(Strings.get(R.string.appearanceactivity_060), null)
                .show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
