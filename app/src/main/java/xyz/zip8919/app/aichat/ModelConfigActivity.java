package xyz.zip8919.app.aichat;

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 管理模型：提供商 / 模型两个页签，顶部切换（与「小工具」页同一套分段交互）。
 *
 * <p>此前是一个页面上下两个 ListView、所有编辑都是 AlertDialog：模型多起来以后
 * 既看不到谁配了什么，也没法批量操作，更没法判断某个提供商到底通不通。
 *
 * <p>本页只做「列表 + 入口 + 批量」，单个对象的字段编辑都在
 * {@link ProviderEditActivity} / {@link ModelEditActivity}，避免弹窗里塞六个输入框。
 *
 * <p>数据一律现读现写 {@link ConfigManager}（内存里的 COW 列表就是唯一真相），
 * 编辑页保存后回 onResume 重新拉一遍，不做跨页缓存。
 */
public class ModelConfigActivity extends ThemedActivity {

    private static final String TAG = "ModelConfig";

    private static final int REQ_PROVIDER = 3001;
    private static final int REQ_MODEL = 3002;

    /** 排序方式。 */
    private static final int SORT_CUSTOM = 0;
    private static final int SORT_NAME = 1;
    private static final int SORT_PROVIDER = 2;
    private static final int SORT_FEATURE = 3;

    private ConfigManager cfg;

    // 页签
    private Button tabProvider, tabModel;
    private LinearLayout panelProvider, panelModel;
    private boolean modelTab;

    // 提供商
    private ListView providerList;
    private TextView providerEmpty;
    private ConfigRowAdapter providerAdapter;
    private LinearLayout providerSelBar;
    private TextView providerSelCount;
    private Button providerMulti;

    // 模型
    private ListView modelList;
    private TextView modelEmpty, modelStatus;
    private ConfigRowAdapter modelAdapter;
    private EditText modelSearch;
    private Spinner filterProvider, sortMode;
    private Button sortDir, modelTestAll, modelMulti, modelProbe, modelProbeUnprobed;
    private LinearLayout modelSelBar;
    private TextView modelSelCount;

    private boolean providerSelection;
    private boolean modelSelection;
    private boolean sortAscending = true;
    private int sortKind = SORT_NAME;
    private String filterProviderName = "";
    private String searchText = "";
    /** 批量测试进行中的标记；置 false 时当前批次的线程会自行退出。 */
    private final AtomicBoolean testing = new AtomicBoolean(false);
    private int testBatchTotal;
    private int testBatchDone;
    /** 能力探测进行中的标记；置 false 时探测在下一个请求前退出。 */
    private final AtomicBoolean probing = new AtomicBoolean(false);
    private int probeTotal;
    private int probeDone;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_model_config);
        cfg = ConfigManager.getInstance();

        bindViews();
        wireTabs();
        wireProviderPanel();
        wireModelPanel();

        showTab(false);
        LogUtil.i(TAG, "onCreate: providers=%d models=%d",
                cfg.getProviders().size(), cfg.getModels().size());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 编辑页保存后回来：列表、筛选下拉、模型页的状态都要按新数据重建。
        // 但探测/测试线程还在跑时不能重建：它们每一步都按行的唯一键刷徽标，
        // 列表换一批新 Row 对象后，正在刷的那一行就找不到了，进度会停在原地。
        refreshProviderFilter();
        reloadProviders();
        if (!probing.get() && !testing.get()) {
            reloadModels();
        }
    }

    @Override
    protected void onDestroy() {
        // 页面销毁后批量测试线程若还在跑，回调会因为 isFinishing 被丢掉，
        // 但仍会白跑一轮网络请求，这里直接叫停。
        testing.set(false);
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void bindViews() {
        findViewById(R.id.mc_back).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        tabProvider = (Button) findViewById(R.id.mc_tab_provider);
        tabModel = (Button) findViewById(R.id.mc_tab_model);
        panelProvider = (LinearLayout) findViewById(R.id.mc_panel_provider);
        panelModel = (LinearLayout) findViewById(R.id.mc_panel_model);

        providerList = (ListView) findViewById(R.id.mc_provider_list);
        providerEmpty = (TextView) findViewById(R.id.mc_provider_empty);
        providerAdapter = new ConfigRowAdapter(this);
        providerList.setAdapter(providerAdapter);
        providerSelBar = (LinearLayout) findViewById(R.id.mc_provider_sel_bar);
        providerSelCount = (TextView) findViewById(R.id.mc_provider_sel_count);
        providerMulti = (Button) findViewById(R.id.mc_provider_multi);

        modelList = (ListView) findViewById(R.id.mc_model_list);
        modelEmpty = (TextView) findViewById(R.id.mc_model_empty);
        modelStatus = (TextView) findViewById(R.id.mc_model_status);
        modelAdapter = new ConfigRowAdapter(this);
        modelList.setAdapter(modelAdapter);
        modelSearch = (EditText) findViewById(R.id.mc_model_search);
        filterProvider = (Spinner) findViewById(R.id.mc_filter_provider);
        sortMode = (Spinner) findViewById(R.id.mc_sort_mode);
        sortDir = (Button) findViewById(R.id.mc_sort_dir);
        modelTestAll = (Button) findViewById(R.id.mc_model_test_all);
        modelProbe = (Button) findViewById(R.id.mc_model_probe);
        modelProbeUnprobed = (Button) findViewById(R.id.mc_model_probe_unprobed);
        modelMulti = (Button) findViewById(R.id.mc_model_multi);
        modelSelBar = (LinearLayout) findViewById(R.id.mc_model_sel_bar);
        modelSelCount = (TextView) findViewById(R.id.mc_model_sel_count);

        // 页签按钮自身会按选中态换底色，豁免主题重着色（否则选中态被抹平）
        tabProvider.setTag(R.id.ac_no_recolor, Boolean.TRUE);
        tabModel.setTag(R.id.ac_no_recolor, Boolean.TRUE);
    }

    // ---- 页签 ----

    private void wireTabs() {
        tabProvider.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showTab(false); }
        });
        tabModel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showTab(true); }
        });
    }

    private void showTab(boolean model) {
        modelTab = model;
        panelProvider.setVisibility(model ? View.GONE : View.VISIBLE);
        panelModel.setVisibility(model ? View.VISIBLE : View.GONE);
        int primary = ThemeTokens.get(this, ThemeTokens.AC_PRIMARY);
        int surface = AppearanceManager.getInstance(this)
                .getEffectiveColor(ThemeTokens.AC_SURFACE);
        int text = AppearanceManager.getInstance(this)
                .getEffectiveColor(ThemeTokens.AC_TEXT);
        tabProvider.setBackgroundColor(model ? surface : primary);
        tabProvider.setTextColor(model ? text : 0xFFFFFFFF);
        tabModel.setBackgroundColor(model ? primary : surface);
        tabModel.setTextColor(model ? 0xFFFFFFFF : text);
        // 切换页签时退出另一页的多选：两页的多选条同时亮着会让人不知道删的是哪边
        if (model) {
            exitProviderSelection();
            reloadModels();
        } else {
            exitModelSelection();
            reloadProviders();
        }
    }

    @Override
    public void onBackPressed() {
        if (providerSelection) { exitProviderSelection(); return; }
        if (modelSelection) { exitModelSelection(); return; }
        if (modelTab) { showTab(false); return; }
        super.onBackPressed();
    }

    // ================= 提供商 =================

    private void wireProviderPanel() {
        findViewById(R.id.mc_provider_new).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openProviderEditor(null); }
        });
        providerMulti.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (providerSelection) {
                    exitProviderSelection();
                    return;
                }
                if (cfg.getProviders().isEmpty()) {
                    AcToast.show(ModelConfigActivity.this, Strings.get(R.string.modelconfigactivity_001));
                    return;
                }
                providerSelection = true;
                providerAdapter.setSelectionMode(true);
                providerMulti.setText(Strings.get(R.string.modelconfigactivity_002));
                updateProviderSelectionBar();
            }
        });
        providerList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                ConfigRowAdapter.Row r = providerAdapter.rowAt(pos);
                if (r == null) return;
                if (providerSelection) {
                    providerAdapter.toggle(r.key);
                    updateProviderSelectionBar();
                } else {
                    openProviderEditor(r.key);
                }
            }
        });
        providerList.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                ConfigRowAdapter.Row r = providerAdapter.rowAt(pos);
                if (r == null) return false;
                if (providerSelection) {
                    providerAdapter.toggle(r.key);
                    updateProviderSelectionBar();
                } else {
                    showProviderMenu(r.key);
                }
                return true;
            }
        });
        findViewById(R.id.mc_provider_sel_all).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                providerAdapter.checkAll(true);
                updateProviderSelectionBar();
            }
        });
        findViewById(R.id.mc_provider_sel_invert).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                providerAdapter.invert();
                updateProviderSelectionBar();
            }
        });
        findViewById(R.id.mc_provider_sel_delete).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { confirmBatchDeleteProviders(); }
        });
        findViewById(R.id.mc_provider_sel_exit).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { exitProviderSelection(); }
        });
    }

    private void exitProviderSelection() {
        if (!providerSelection) return;
        providerSelection = false;
        providerAdapter.setSelectionMode(false);
        providerMulti.setText(Strings.get(R.string.modelconfigactivity_003));
        providerSelBar.setVisibility(View.GONE);
    }

    private void updateProviderSelectionBar() {
        providerSelBar.setVisibility(providerSelection ? View.VISIBLE : View.GONE);
        providerSelCount.setText(Strings.get(R.string.modelconfigactivity_004, providerAdapter.checkedKeys().size()));
    }

    private void reloadProviders() {
        if (providerAdapter == null) return;
        List<ConfigRowAdapter.Row> rows = new ArrayList<ConfigRowAdapter.Row>();
        String def = defaultProviderOfDefaultModel();
        for (ProviderInfo p : cfg.getProviders()) {
            int count = cfg.modelsOf(p.name).size();
            StringBuilder sub = new StringBuilder();
            // 显示名与唯一标识不同时，把标识也带上，否则改了显示名就分不清是哪条地址
            if (!p.label().equals(p.name)) sub.append(p.name).append(" · ");
            sub.append(Endpoint.chatUrl(p));
            String keyState = (p.apiKey == null || p.apiKey.trim().length() == 0)
                    ? Strings.get(R.string.modelconfigactivity_006) : "Key ✓";
            sub.append(" · ").append(keyState).append(" · ").append(count).append(Strings.get(R.string.modelconfigactivity_007));
            String badge = p.name.equals(def) ? Strings.get(R.string.modelconfigactivity_148) : "";
            rows.add(new ConfigRowAdapter.Row(p.name, p.label(), sub.toString(), badge));
        }
        providerAdapter.setRows(rows);
        boolean has = !rows.isEmpty();
        providerEmpty.setVisibility(has ? View.GONE : View.VISIBLE);
        providerList.setVisibility(has ? View.VISIBLE : View.GONE);
        updateProviderSelectionBar();
        LogUtil.d(TAG, "reloadProviders: %d", rows.size());
    }

    /** 默认模型所属的提供商，列表里标一个「默认」，让人一眼看到当前在用的。 */
    private String defaultProviderOfDefaultModel() {
        ModelInfo m = cfg.getDefaultModelInfo();
        return m == null || m.provider == null ? "" : m.provider;
    }

    private void openProviderEditor(String name) {
        Intent it = new Intent(this, ProviderEditActivity.class);
        if (name != null) it.putExtra(ProviderEditActivity.EXTRA_PROVIDER, name);
        startActivityForResult(it, REQ_PROVIDER);
    }

    private void showProviderMenu(final String name) {
        final ProviderInfo p = cfg.getProvider(name);
        if (p == null) return;
        String[] items = {Strings.get(R.string.modelconfigactivity_008), Strings.get(R.string.modelconfigactivity_009), Strings.get(R.string.modelconfigactivity_010), Strings.get(R.string.modelconfigactivity_011), Strings.get(R.string.modelconfigactivity_012)};
        AcDialog.builder(this)
                .setTitle(p.label())
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if (w == 0) openProviderEditor(name);
                        else if (w == 1) duplicateProvider(p);
                        else if (w == 2) testProviderInline(p);
                        else if (w == 3) openModelEditor(null, name);
                        else confirmDeleteProviders(Collections.singletonList(name));
                    }
                })
                .setNegativeButton(Strings.get(R.string.modelconfigactivity_013), null)
                .show();
    }

    /**
     * 复制提供商：名称顺延（{@code name2}），Key 与地址照抄，模型<b>不复制</b>
     * （模型名是全局唯一键，硬复制必然撞名；用户要的是「再开一个同源账号」）。
     */
    private void duplicateProvider(ProviderInfo src) {
        ProviderInfo cp = src.copy();
        cp.name = nextProviderName(src.name);
        cp.displayName = src.label() + Strings.get(R.string.modelconfigactivity_014);
        cfg.getProviders().add(cp);
        cfg.save();
        reloadProviders();
        refreshProviderFilter();
        AcToast.show(this, Strings.get(R.string.modelconfigactivity_015, cp.label()));
        LogUtil.i(TAG, "duplicateProvider: %s -> %s", src.name, cp.name);
    }

    private String nextProviderName(String base) {
        String b = base == null || base.length() == 0 ? "provider" : base;
        for (int n = 2; n < 1000; n++) {
            String cand = b + n;
            if (!cfg.isProviderNameTaken(cand, null)) return cand;
        }
        return b + System.currentTimeMillis();
    }

    private void confirmBatchDeleteProviders() {
        Set<String> keys = new HashSet<String>(providerAdapter.checkedKeys());
        if (keys.isEmpty()) {
            AcToast.show(this, Strings.get(R.string.modelconfigactivity_016));
            return;
        }
        confirmDeleteProviders(new ArrayList<String>(keys));
    }

    private void confirmDeleteProviders(final List<String> names) {
        if (names == null || names.isEmpty()) return;
        final List<String> list = new ArrayList<String>(names);
        int models = 0;
        for (String n : list) models += cfg.modelsOf(n).size();
        StringBuilder sb = new StringBuilder();
        sb.append(list.size() == 1 ? Strings.get(R.string.modelconfigactivity_017) : Strings.get(R.string.modelconfigactivity_018)).append(list.size())
                .append(Strings.get(R.string.modelconfigactivity_019));
        if (models > 0) sb.append(Strings.get(R.string.modelconfigactivity_020)).append(models).append(Strings.get(R.string.modelconfigactivity_021));
        else sb.append(Strings.get(R.string.modelconfigactivity_022));
        AcDialog.builder(this)
                .setTitle(Strings.get(R.string.modelconfigactivity_023))
                .setMessage(sb.toString())
                .setPositiveButton(Strings.get(R.string.modelconfigactivity_024), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        deleteProviders(list);
                    }
                })
                .setNegativeButton(Strings.get(R.string.modelconfigactivity_025), null)
                .show();
    }

    private void deleteProviders(List<String> names) {
        int modelsRemoved = 0;
        for (String n : names) {
            ProviderInfo p = cfg.getProvider(n);
            if (p != null) {
                cfg.getProviders().remove(p);
                // 与旧版一致：提供商删掉，挂在它名下的模型就是孤儿，一并清掉
                for (ModelInfo m : cfg.modelsOf(n)) {
                    cfg.getModels().remove(m);
                    modelsRemoved++;
                }
            }
        }
        fixDefaultModel();
        cfg.save();
        exitProviderSelection();
        reloadProviders();
        refreshProviderFilter();
        reloadModels();
        AcToast.show(this, Strings.get(R.string.modelconfigactivity_026, names.size(), modelsRemoved));
        LogUtil.i(TAG, "deleteProviders: %d providers, %d models", names.size(), modelsRemoved);
    }

    /** 默认模型被删除 / 改名后可能悬空：回退到第一个，没有就清空。 */
    private void fixDefaultModel() {
        String cur = cfg.getDefaultModel();
        if (cur != null && cur.length() > 0 && cfg.findModel(cur) != null) return;
        List<ModelInfo> all = cfg.getModels();
        // 存唯一键：同名不同厂商的两条不能都变成默认
        String fallback = all.isEmpty() ? "" : all.get(0).key();
        LogUtil.i(TAG, "default model '%s' dangling, fallback to '%s'", cur, fallback);
        cfg.setDefaultModel(fallback);
    }

    /** 列表上的行内测试：结果弹一个对话框说清楚原因。 */
    private void testProviderInline(final ProviderInfo p) {
        AcToast.show(this, Strings.get(R.string.modelconfigactivity_029, p.label()));
        final ProviderInfo snap = p.copy();
        new Thread(new Runnable() {
            public void run() {
                final ProviderTester.Result r = ProviderTester.testProvider(snap);
                ui.post(new Runnable() {
                    public void run() {
                        if (isFinishing()) return;
                        AcDialog.builder(ModelConfigActivity.this)
                                .setTitle(snap.label() + (r.ok() ? Strings.get(R.string.modelconfigactivity_030) : Strings.get(R.string.modelconfigactivity_031)))
                                .setMessage(r.message)
                                .setPositiveButton(Strings.get(R.string.modelconfigactivity_032), null)
                                .show();
                    }
                });
            }
        }, "aichat-test-" + snap.name).start();
    }

    // ================= 模型 =================

    private void wireModelPanel() {
        findViewById(R.id.mc_model_new).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openModelEditor(null, null); }
        });
        modelMulti.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (modelSelection) {
                    exitModelSelection();
                    return;
                }
                if (modelAdapter.getCount() == 0) {
                    AcToast.show(ModelConfigActivity.this, Strings.get(R.string.modelconfigactivity_033));
                    return;
                }
                modelSelection = true;
                modelAdapter.setSelectionMode(true);
                modelMulti.setText(Strings.get(R.string.modelconfigactivity_034));
                updateModelSelectionBar();
            }
        });
        modelList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                ConfigRowAdapter.Row r = modelAdapter.rowAt(pos);
                if (r == null) return;
                if (modelSelection) {
                    modelAdapter.toggle(r.key);
                    updateModelSelectionBar();
                } else if (isProbed(r.key)) {
                    // 探测过的模型：点一行先摊开结论。探测到底探出了什么，不该只藏在
                    // 编辑页的一行小字里；未探测的照旧直接进编辑页，不多一步。
                    showModelSummary(r.key);
                } else {
                    openModelEditor(r.key, null);
                }
            }
        });
        modelList.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                ConfigRowAdapter.Row r = modelAdapter.rowAt(pos);
                if (r == null) return false;
                if (modelSelection) {
                    modelAdapter.toggle(r.key);
                    updateModelSelectionBar();
                } else {
                    showModelMenu(r.key);
                }
                return true;
            }
        });
        modelSearch.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) {
                searchText = s == null ? "" : s.toString().trim();
                reloadModels();
            }
        });
        sortMode.setAdapter(simpleAdapter(new String[]{Strings.get(R.string.modelconfigactivity_035), Strings.get(R.string.modelconfigactivity_036), Strings.get(R.string.modelconfigactivity_037), Strings.get(R.string.modelconfigactivity_038)}));
        sortMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                sortKind = pos;
                reloadModels();
            }
            public void onNothingSelected(AdapterView<?> p) { }
        });
        sortDir.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                sortAscending = !sortAscending;
                sortDir.setText(sortAscending ? Strings.get(R.string.modelconfigactivity_039) : Strings.get(R.string.modelconfigactivity_040));
                reloadModels();
            }
        });
        modelTestAll.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (probing.get() && !testing.get()) {
                    AcToast.show(ModelConfigActivity.this, Strings.get(R.string.modelconfigactivity_041));
                    return;
                }
                startBatchTest(null);
            }
        });
        modelProbe.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startBatchProbe(null); }
        });
        modelProbeUnprobed.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                List<String> keys = new ArrayList<String>();
                for (int i = 0; i < modelAdapter.getCount(); i++) {
                    ConfigRowAdapter.Row row = modelAdapter.rowAt(i);
                    if (row == null) continue;
                    ModelInfo m = cfg.findModel(row.key);
                    if (m != null && !m.levelsProbed()) keys.add(row.key);
                }
                if (keys.isEmpty()) {
                    AcToast.show(ModelConfigActivity.this, Strings.get(R.string.modelconfigactivity_042));
                    return;
                }
                startBatchProbe(keys);
            }
        });
        findViewById(R.id.mc_model_sel_all).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                modelAdapter.checkAll(true);
                updateModelSelectionBar();
            }
        });
        findViewById(R.id.mc_model_sel_invert).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                modelAdapter.invert();
                updateModelSelectionBar();
            }
        });
        findViewById(R.id.mc_model_sel_test).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Set<String> keys = new HashSet<String>(modelAdapter.checkedKeys());
                if (keys.isEmpty()) {
                    AcToast.show(ModelConfigActivity.this, Strings.get(R.string.modelconfigactivity_043));
                    return;
                }
                if (probing.get()) {
                    AcToast.show(ModelConfigActivity.this, Strings.get(R.string.modelconfigactivity_044));
                    return;
                }
                startBatchTest(new ArrayList<String>(keys));
            }
        });
        findViewById(R.id.mc_model_sel_probe).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Set<String> keys = new HashSet<String>(modelAdapter.checkedKeys());
                if (keys.isEmpty()) {
                    AcToast.show(ModelConfigActivity.this, Strings.get(R.string.modelconfigactivity_045));
                    return;
                }
                startBatchProbe(new ArrayList<String>(keys));
            }
        });
        findViewById(R.id.mc_model_sel_delete).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { confirmBatchDeleteModels(); }
        });
        findViewById(R.id.mc_model_sel_exit).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { exitModelSelection(); }
        });
    }

    private ArrayAdapter<String> simpleAdapter(String[] items) {
        ArrayAdapter<String> a = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return a;
    }

    /** 筛选下拉：全部 + 每个提供商（用显示名）。 */
    private void refreshProviderFilter() {
        final List<String> names = new ArrayList<String>();
        names.add("");
        List<String> labels = new ArrayList<String>();
        labels.add(Strings.get(R.string.modelconfigactivity_046));
        for (ProviderInfo p : cfg.getProviders()) {
            names.add(p.name);
            labels.add(p.label());
        }
        ArrayAdapter<String> a = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, labels);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        filterProvider.setAdapter(a);
        int target = 0;
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equals(filterProviderName)) target = i;
        }
        final List<String> keys = names;
        // 第二个参数 false：重建 adapter 时不要把用户的筛选重置回「全部」
        filterProvider.setSelection(target, false);
        filterProvider.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                filterProviderName = pos >= 0 && pos < keys.size() ? keys.get(pos) : "";
                reloadModels();
            }
            public void onNothingSelected(AdapterView<?> p) { }
        });
    }

    private void reloadModels() {
        if (modelAdapter == null) return;
        List<ModelInfo> src = new ArrayList<ModelInfo>(cfg.getModels());
        String q = searchText.toLowerCase(Locale.US);
        List<ModelInfo> kept = new ArrayList<ModelInfo>();
        for (ModelInfo m : src) {
            if (filterProviderName != null && filterProviderName.length() > 0
                    && !filterProviderName.equals(m.provider)) {
                continue;
            }
            if (q.length() > 0) {
                String hay = (m.label() + " " + m.name + " " + cfg.providerLabel(m.provider)
                        + " " + (m.provider == null ? "" : m.provider)).toLowerCase(Locale.US);
                if (hay.indexOf(q) < 0) continue;
            }
            kept.add(m);
        }
        if (sortKind != SORT_CUSTOM) {
            Collections.sort(kept, comperator(sortKind, sortAscending));
        } else if (!sortAscending) {
            Collections.reverse(kept);
        }

        List<ConfigRowAdapter.Row> rows = new ArrayList<ConfigRowAdapter.Row>();
        for (ModelInfo m : kept) {
            StringBuilder sub = new StringBuilder();
            sub.append(m.name);
            sub.append(" · ").append(cfg.providerLabel(m.provider));
            if (m.supportsVision) sub.append(Strings.get(R.string.modelconfigactivity_047));
            if (m.levelsProbed()) {
                // 探测过的按实测档位显示，未探测的沿用「无思考」的手动标记
                sub.append(Strings.get(R.string.modelconfigactivity_048)).append(ThinkingLevels.describe(m.levelList()));
                if (m.levelsUnverifiable) sub.append(Strings.get(R.string.modelconfigactivity_049));
            } else if (!m.supportsThinking) {
                sub.append(Strings.get(R.string.modelconfigactivity_050));
            }
            String badge;
            // 徽标依次回答：它是不是默认模型 → 探测下来能不能用 → 支持哪些能力。
            // 探测通过的模型显示「可用」：以前这里没有这一档，探完徽标还是空，
            // 用户以为探测没生效。默认模型仍显示「默认」，它的档位明细在同行的副标题里。
            if (cfg.isDefaultModel(m)) {
                badge = Strings.get(R.string.modelconfigactivity_051);
            } else if (m.levelsProbed()) {
                badge = Strings.get(R.string.modelconfigactivity_152);
            } else if (m.supportsVision) {
                badge = Strings.get(R.string.modelconfigactivity_052);
            } else {
                badge = "";
            }
            // 行键用唯一键：同名不同提供商的两条要能分别勾选、分别打开编辑页
            rows.add(new ConfigRowAdapter.Row(m.key(), m.label(), sub.toString(), badge));
        }
        modelAdapter.setRows(rows);
        boolean has = !rows.isEmpty();
        modelEmpty.setVisibility(has ? View.GONE : View.VISIBLE);
        modelEmpty.setText(src.isEmpty() ? Strings.get(R.string.modelconfigactivity_053)
                : Strings.get(R.string.modelconfigactivity_054));
        modelList.setVisibility(has ? View.VISIBLE : View.GONE);
        updateModelSelectionBar();
        LogUtil.d(TAG, "reloadModels: shown=%d total=%d filter='%s' q='%s' sort=%d/%s",
                rows.size(), src.size(), filterProviderName, searchText, sortKind,
                sortAscending ? "asc" : "desc");
    }

    private Comparator<ModelInfo> comperator(final int mode, final boolean asc) {
        return new Comparator<ModelInfo>() {
            public int compare(ModelInfo a, ModelInfo b) {
                int r;
                if (mode == SORT_PROVIDER) {
                    r = cfg.providerLabel(a.provider)
                            .compareToIgnoreCase(cfg.providerLabel(b.provider));
                    if (r == 0) r = a.name.compareToIgnoreCase(b.name);
                } else if (mode == SORT_FEATURE) {
                    r = featureRank(a) - featureRank(b);
                    if (r == 0) r = a.name.compareToIgnoreCase(b.name);
                } else {
                    r = a.label().compareToIgnoreCase(b.label());
                    if (r == 0) r = a.name.compareToIgnoreCase(b.name);
                }
                return asc ? r : -r;
            }
        };
    }

    /** 能力排序：视觉在前，其次思考，最后按名字。 */
    private static int featureRank(ModelInfo m) {
        if (m.supportsVision) return 0;
        if (m.supportsThinking) return 1;
        return 2;
    }

    private void exitModelSelection() {
        if (!modelSelection) return;
        modelSelection = false;
        modelAdapter.setSelectionMode(false);
        modelMulti.setText(Strings.get(R.string.modelconfigactivity_055));
        modelSelBar.setVisibility(View.GONE);
    }

    private void updateModelSelectionBar() {
        modelSelBar.setVisibility(modelSelection ? View.VISIBLE : View.GONE);
        modelSelCount.setText(Strings.get(R.string.modelconfigactivity_056, modelAdapter.checkedKeys().size()));
    }

    private void openModelEditor(String modelName, String presetProvider) {
        Intent it = new Intent(this, ModelEditActivity.class);
        if (modelName != null) it.putExtra(ModelEditActivity.EXTRA_MODEL, modelName);
        if (presetProvider != null) it.putExtra(ModelEditActivity.EXTRA_PROVIDER, presetProvider);
        startActivityForResult(it, REQ_MODEL);
    }

    private void showModelMenu(final String name) {
        final ModelInfo m = cfg.getModel(name);
        if (m == null) return;
        String[] items = {Strings.get(R.string.modelconfigactivity_058), Strings.get(R.string.modelconfigactivity_059), Strings.get(R.string.modelconfigactivity_060), Strings.get(R.string.modelconfigactivity_061), Strings.get(R.string.modelconfigactivity_062)};
        AcDialog.builder(this)
                .setTitle(m.label())
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if (w == 0) openModelEditor(name, null);
                        else if (w == 1) testModelInline(m, m.name);
                        else if (w == 2) setDefault(name);
                        else if (w == 3) duplicateModel(m);
                        else confirmDeleteModels(Collections.singletonList(name));
                    }
                })
                .setNegativeButton(Strings.get(R.string.modelconfigactivity_063), null)
                .show();
    }

    /** 模型名全局唯一，副本顺延成 {@code id2}，显示名加「副本」。 */
    private void duplicateModel(ModelInfo src) {
        ModelInfo cp = src.copy();
        String base = src.name;
        for (int n = 2; n < 1000; n++) {
            String cand = base + n;
            if (!cfg.isModelNameTaken(src.provider, cand, null)) {
                cp.name = cand;
                break;
            }
        }
        cp.displayName = src.label() + Strings.get(R.string.modelconfigactivity_064);
        cfg.getModels().add(cp);
        cfg.save();
        reloadModels();
        AcToast.show(this, Strings.get(R.string.modelconfigactivity_065, cp.label()));
    }

    private void setDefault(String key) {
        ModelInfo m = cfg.findModel(key);
        cfg.setDefaultModel(m == null ? key : m.key());
        cfg.save();
        reloadModels();
        reloadProviders();
        AcToast.show(this, Strings.get(R.string.modelconfigactivity_066, m == null ? key : m.label()));
        LogUtil.i(TAG, "setDefault: %s", key);
    }

    /** 待删列表里是否包含当前默认模型（按唯一键比，不看模型 ID）。 */
    private boolean containsDefaultModel(List<String> keys) {
        for (String k : keys) {
            ModelInfo m = cfg.findModel(k);
            if (m != null && cfg.isDefaultModel(m)) return true;
        }
        return false;
    }

    private void confirmBatchDeleteModels() {
        Set<String> keys = new HashSet<String>(modelAdapter.checkedKeys());
        if (keys.isEmpty()) {
            AcToast.show(this, Strings.get(R.string.modelconfigactivity_067));
            return;
        }
        confirmDeleteModels(new ArrayList<String>(keys));
    }

    private void confirmDeleteModels(final List<String> names) {
        if (names == null || names.isEmpty()) return;
        final List<String> list = new ArrayList<String>(names);
        StringBuilder sb = new StringBuilder();
        sb.append(Strings.get(R.string.modelconfigactivity_068)).append(list.size() == 1 ? " " : Strings.get(R.string.modelconfigactivity_069))
                .append(list.size()).append(Strings.get(R.string.modelconfigactivity_070));
        if (containsDefaultModel(list)) {
            sb.append(Strings.get(R.string.modelconfigactivity_071));
        }
        AcDialog.builder(this)
                .setTitle(Strings.get(R.string.modelconfigactivity_072))
                .setMessage(sb.toString())
                .setPositiveButton(Strings.get(R.string.modelconfigactivity_073), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        int n = 0;
                        for (String name : list) {
                            ModelInfo m = cfg.getModel(name);
                            if (m != null) {
                                cfg.getModels().remove(m);
                                n++;
                            }
                        }
                        fixDefaultModel();
                        cfg.save();
                        exitModelSelection();
                        reloadModels();
                        reloadProviders();
                        AcToast.show(ModelConfigActivity.this, Strings.get(R.string.modelconfigactivity_074, n));
                        LogUtil.i(TAG, "deleteModels: %d", n);
                    }
                })
                .setNegativeButton(Strings.get(R.string.modelconfigactivity_076), null)
                .show();
    }

    // ---- 批量测试 ----

    /**
     * 批量测试连通性：串行、逐个更新徽标。
     *
     * <p>串行而不是并发：词典笔是 4.4 的小机器，并发几个 HTTPS 握手会拖慢整机；
     * 而且限流（429）在并发下会把本来能用的模型也测成失败。
     *
     * @param only 只测这些模型名；null 表示测「当前筛选出来的全部」
     */
    private void startBatchTest(final List<String> only) {
        if (testing.get()) {
            testing.set(false);
            ui.post(new Runnable() {
                public void run() {
                    modelTestAll.setText(Strings.get(R.string.modelconfigactivity_077));
                    modelStatus.setText(Strings.get(R.string.modelconfigactivity_078, testBatchDone, testBatchTotal));
                }
            });
            return;
        }
        final List<String> targets = new ArrayList<String>();
        if (only != null) {
            targets.addAll(only);
        } else {
            for (int i = 0; i < modelAdapter.getCount(); i++) {
                ConfigRowAdapter.Row r = modelAdapter.rowAt(i);
                if (r != null) targets.add(r.key);
            }
        }
        if (targets.isEmpty()) {
            AcToast.show(this, Strings.get(R.string.modelconfigactivity_079));
            return;
        }
        testing.set(true);
        testBatchTotal = targets.size();
        testBatchDone = 0;
        modelTestAll.setText(Strings.get(R.string.modelconfigactivity_080));
        modelStatus.setText(Strings.get(R.string.modelconfigactivity_081, testBatchTotal));
        final AtomicInteger fail = new AtomicInteger(0);
        final List<String> failures = Collections.synchronizedList(new ArrayList<String>());

        new Thread(new Runnable() {
            public void run() {
                for (int i = 0; i < targets.size(); i++) {
                    if (!testing.get()) break;
                    final String name = targets.get(i);
                    final ModelInfo m = cfg.getModel(name);
                    if (m == null) continue;
                    final ProviderInfo p = cfg.getProvider(m.provider);
                    if (p == null) {
                        reportBadge(name, Strings.get(R.string.modelconfigactivity_082), true);
                        failures.add(Strings.get(R.string.modelconfigactivity_083, cfg.modelLabel(name)));
                        fail.incrementAndGet();
                    } else {
                        reportBadge(name, Strings.get(R.string.modelconfigactivity_084), false);
                        final ProviderTester.Result r = ProviderTester.testChat(p, m.name);
                        reportBadge(name, r.ok() ? "✓ " + r.ms + "ms" : Strings.get(R.string.modelconfigactivity_085), !r.ok());
                        if (!r.ok()) {
                            fail.incrementAndGet();
                            failures.add(m.label() + "：" + r.message);
                        }
                    }
                    testBatchDone++;
                    final int done = testBatchDone;
                    ui.post(new Runnable() {
                        public void run() {
                            if (testing.get()) {
                                modelStatus.setText(Strings.get(R.string.modelconfigactivity_086, done, testBatchTotal));
                            }
                        }
                    });
                }
                final boolean stopped = !testing.get();
                testing.set(false);
                ui.post(new Runnable() {
                    public void run() {
                        modelTestAll.setText(Strings.get(R.string.modelconfigactivity_087));
                        if (isFinishing()) return;
                        modelStatus.setText(Strings.get(stopped ? R.string.modelconfigactivity_088 : R.string.modelconfigactivity_089,
                                testBatchTotal, fail.get()));
                        showTestSummary(failures, fail.get(), stopped);
                    }
                });
            }
        }, "aichat-batch-test").start();
        LogUtil.i(TAG, "startBatchTest: %d targets", targets.size());
    }

    private void reportBadge(final String name, final String text, final boolean error) {
        ui.post(new Runnable() {
            public void run() {
                if (isFinishing()) return;
                modelAdapter.setBadge(name, text, error);
            }
        });
    }

    // ---- 能力探测 ----

    /**
     * 批量探测思考档位与图片输入能力。
     *
     * <p>与连通性测试一样串行：每个模型要发最多 8 次请求，并发会直接把低端机拖住，
     * 也更容易撞上 429。用户可以随时点同一个按钮停止，停止后已探测到的结论照样落库。
     *
     * @param only 只探这些模型的唯一键；null 表示「当前筛选出来的全部」
     */
    private void startBatchProbe(final List<String> only) {
        if (probing.get()) {
            probing.set(false);
            ui.post(new Runnable() {
                public void run() {
                    modelProbe.setText(Strings.get(R.string.modelconfigactivity_093));
                    modelStatus.setText(Strings.get(R.string.modelconfigactivity_094, probeDone, probeTotal));
                }
            });
            return;
        }
        if (testing.get()) {
            AcToast.show(this, Strings.get(R.string.modelconfigactivity_095));
            return;
        }
        final List<String> targets = new ArrayList<String>();
        if (only != null) {
            targets.addAll(only);
        } else {
            for (int i = 0; i < modelAdapter.getCount(); i++) {
                ConfigRowAdapter.Row r = modelAdapter.rowAt(i);
                if (r != null) targets.add(r.key);
            }
        }
        if (targets.isEmpty()) {
            AcToast.show(this, Strings.get(R.string.modelconfigactivity_096));
            return;
        }
        int valid = 0;
        for (String k : targets) {
            ModelInfo m = cfg.findModel(k);
            if (m != null && cfg.getProvider(m.provider) != null) valid++;
        }
        confirmProbeCost(valid, targets);
    }

    /** 确认之后真正开始：先定下总数，再起线程。 */
    private void runBatchProbe(final List<String> targets, int validTotal) {
        probing.set(true);
        probeTotal = validTotal;
        probeDone = 0;
        modelProbe.setText(Strings.get(R.string.modelconfigactivity_097));
        modelStatus.setText(Strings.get(R.string.modelconfigactivity_098, probeTotal));
        final AtomicInteger fail = new AtomicInteger(0);
        final List<String> details = Collections.synchronizedList(new ArrayList<String>());

        new Thread(new Runnable() {
            public void run() {
                for (int i = 0; i < targets.size(); i++) {
                    // onDestroy 会把标记置 false：页面已经没了，后面的模型不必再发请求
                    if (!probing.get() || isFinishing()) break;
                    final String key = targets.get(i);
                    final ModelInfo m = cfg.findModel(key);
                    if (m == null) continue;
                    final ProviderInfo p = cfg.getProvider(m.provider);
                    if (p == null) {
                        reportBadge(key, Strings.get(R.string.modelconfigactivity_099), true);
                        details.add(Strings.get(R.string.modelconfigactivity_100, m.label()));
                        fail.incrementAndGet();
                        probeDone++;
                        continue;
                    }
                    reportBadge(key, Strings.get(R.string.modelconfigactivity_101), false);
                    final CapabilityProbe.Report rep = CapabilityProbe.probe(p, m.name,
                            new CapabilityProbe.Stop() {
                                public boolean stopped() { return !probing.get(); }
                            });
                    // 结论写回配置：即使后面整体停止，已经探到的也不浪费
                    // 视觉与档位分开落地：不支持思考参数的模型也可能能看图
                    if (rep.vision != null) m.supportsVision = rep.vision.booleanValue();
                    if (rep.ok() && !rep.levels.isEmpty()) {
                        m.setLevelList(rep.levels);
                        m.supportsThinking = true;
                        m.levelsUnverifiable = rep.unverifiable;
                        m.probedAt = System.currentTimeMillis();
                    } else if (rep.levelsRejected) {
                        m.setLevelList(null);
                        m.supportsThinking = false;
                        m.probedAt = System.currentTimeMillis();
                    }
                    // 关闭能力：只有确认「关不掉」才改写；能关或测不出来时保持原值
                    if (rep.offBlocked) {
                        m.thinkingOffBlocked = true;
                    } else if (Boolean.TRUE.equals(rep.offSupported)) {
                        m.thinkingOffBlocked = false;
                    }
                    cfg.bumpProbeRevision();
                    cfg.save();
                    String badge = probeBadge(rep);
                    reportBadge(key, badge, !rep.ok() || rep.levels.isEmpty());
                    if (!rep.ok() || rep.levels.isEmpty()) {
                        fail.incrementAndGet();
                        details.add(m.label() + "：" + (rep.ok() ? Strings.get(R.string.modelconfigactivity_102) : rep.error));
                    } else {
                        // 成功的也留一行：结束后用户要能一眼看到每个模型探出了什么，
                        // 而不是只看到一份失败清单，再逐行点进去找。
                        details.add(m.label() + "：" + badge);
                    }
                    probeDone++;
                    final int done = probeDone;
                    ui.post(new Runnable() {
                        public void run() {
                            if (isFinishing()) return;
                            if (probing.get()) {
                                modelStatus.setText(Strings.get(R.string.modelconfigactivity_103, done, probeTotal));
                            }
                            // 每完成一个就刷一次行文案，「思考 低·高」当场可见
                            reloadModels();
                        }
                    });
                }
                final boolean stopped = !probing.get();
                probing.set(false);
                ui.post(new Runnable() {
                    public void run() {
                        // 页面已销毁：不能再弹汇总窗，但列表刷新留给重新进入时重建
                        if (isFinishing()) return;
                        modelProbe.setText(Strings.get(R.string.modelconfigactivity_104));
                        modelStatus.setText(Strings.get(stopped ? R.string.modelconfigactivity_150 : R.string.modelconfigactivity_151,
                                probeTotal, fail.get()));
                        reloadModels();
                        showProbeSummary(details, fail.get(), stopped);
                    }
                });
            }
        }, "aichat-batch-probe").start();
        LogUtil.i(TAG, "startBatchProbe: %d targets", probeTotal);
    }

    /** 探测要发真实请求，先讲清代价再执行。 */
    private void confirmProbeCost(final int n, final List<String> targets) {
        AcDialog.builder(this)
                .setTitle(Strings.get(R.string.modelconfigactivity_110))
                .setMessage(Strings.get(R.string.modelconfigactivity_111, n))
                .setPositiveButton(Strings.get(R.string.modelconfigactivity_115), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { runBatchProbe(targets, n); }
                })
                .setNegativeButton(Strings.get(R.string.modelconfigactivity_116), null)
                .show();
    }

    /** 这个模型是否已有可查看的探测结论。 */
    private boolean isProbed(String key) {
        ModelInfo m = cfg.findModel(key);
        return m != null && (m.levelsProbed() || m.supportsVision || m.probedAt > 0);
    }

    /**
     * 点一行时先给出这台模型的实际能力：思考档位明细、图片输入、最近一次探测时间。
     *
     * <p>批量探测的结论只写进副标题和徽标，副标题在窄屏上会被省略号截断，
     * 徽标只报最高档——用户点进来才发现「看不到结果」。这个弹窗把完整结论摊开，
     * 并提供「编辑配置」与「继续探测」两个出口。
     */
    private void showModelSummary(final String key) {
        final ModelInfo m = cfg.findModel(key);
        if (m == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append(m.name).append(" · ").append(cfg.providerLabel(m.provider)).append("\n\n");
        if (m.levelsProbed()) {
            sb.append(Strings.get(R.string.modelconfigactivity_158, ThinkingLevels.describe(m.levelList())));
            if (m.levelsUnverifiable) sb.append(Strings.get(R.string.modelconfigactivity_049));
        } else if (m.supportsThinking) {
            sb.append(Strings.get(R.string.modelconfigactivity_159));
        } else {
            sb.append(Strings.get(R.string.modelconfigactivity_160));
        }
        if (m.thinkingOffBlocked) {
            sb.append("\n").append(Strings.get(R.string.capabilityprobe_024));
        }
        sb.append("\n").append(m.supportsVision
                ? Strings.get(R.string.modelconfigactivity_161)
                : Strings.get(R.string.modelconfigactivity_162));
        // 弹窗只负责「看懂结论」；想改配置或重探，走原来的长按菜单，避免一个弹窗挂三项操作
        AcDialog.builder(this)
                .setTitle(m.label())
                .setMessage(sb.toString())
                .setPositiveButton(Strings.get(R.string.modelconfigactivity_163), null)
                .show();
    }

    private static String probeBadge(CapabilityProbe.Report rep) {
        if (rep.levelsRejected) return Strings.get(R.string.modelconfigactivity_117);
        if (!rep.ok() || rep.levels.isEmpty()) return Strings.get(R.string.modelconfigactivity_118);
        StringBuilder sb = new StringBuilder();
        String top = ThinkingLevels.highest(rep.levels);
        sb.append(Strings.get(R.string.modelconfigactivity_119)).append(ThinkingLevels.label(top));
        if (Boolean.TRUE.equals(rep.vision)) sb.append(" · ").append(Strings.get(R.string.modelconfigactivity_149));
        return sb.toString();
    }

    private void showProbeSummary(List<String> details, int failCount, boolean stopped) {
        StringBuilder sb = new StringBuilder();
        sb.append(stopped ? Strings.get(R.string.modelconfigactivity_120) : Strings.get(R.string.modelconfigactivity_121));
        sb.append(Strings.get(R.string.modelconfigactivity_122)).append(probeTotal).append(Strings.get(R.string.modelconfigactivity_123))
                .append(failCount).append(Strings.get(R.string.modelconfigactivity_124));
        sb.append(Strings.get(R.string.modelconfigactivity_125));
        List<String> copy;
        synchronized (details) {
            copy = new ArrayList<String>(details);
        }
        if (!copy.isEmpty()) {
            // 明细含成功项：标题不再写「失败明细」，否则成功的行看着像失败
            sb.append(Strings.get(R.string.modelconfigactivity_164));
            int limit = Math.min(copy.size(), 12);
            for (int i = 0; i < limit; i++) {
                sb.append("· ").append(copy.get(i)).append("\n");
            }
            if (copy.size() > limit) {
                sb.append(Strings.get(R.string.modelconfigactivity_127)).append(copy.size() - limit).append(Strings.get(R.string.modelconfigactivity_128));
            }
        }
        AcDialog.builder(this)
                .setTitle(Strings.get(R.string.modelconfigactivity_129))
                .setMessage(sb.toString())
                .setPositiveButton(Strings.get(R.string.modelconfigactivity_130), null)
                .show();
    }

    private void showTestSummary(List<String> failures, int failCount, boolean stopped) {
        StringBuilder sb = new StringBuilder();
        sb.append(stopped ? Strings.get(R.string.modelconfigactivity_131) : Strings.get(R.string.modelconfigactivity_132));
        sb.append(Strings.get(R.string.modelconfigactivity_133)).append(testBatchTotal).append(Strings.get(R.string.modelconfigactivity_134))
                .append(failCount).append(Strings.get(R.string.modelconfigactivity_135));
        List<String> copy;
        synchronized (failures) {
            copy = new ArrayList<String>(failures);
        }
        if (!copy.isEmpty()) {
            sb.append(Strings.get(R.string.modelconfigactivity_136));
            int limit = Math.min(copy.size(), 12);
            for (int i = 0; i < limit; i++) {
                sb.append("· ").append(copy.get(i)).append("\n");
            }
            if (copy.size() > limit) {
                sb.append(Strings.get(R.string.modelconfigactivity_137)).append(copy.size() - limit)
                        .append(Strings.get(R.string.modelconfigactivity_138));
            }
        } else if (!stopped) {
            sb.append(Strings.get(R.string.modelconfigactivity_139));
        }
        AcDialog.builder(this)
                .setTitle(Strings.get(R.string.modelconfigactivity_140))
                .setMessage(sb.toString())
                .setPositiveButton(Strings.get(R.string.modelconfigactivity_141), null)
                .show();
    }

    // ---- 单项测试（长按菜单） ----

    private void testModelInline(final ModelInfo m, final String name) {
        final ProviderInfo p = cfg.getProvider(m.provider);
        if (p == null) {
            AcToast.show(this, Strings.get(R.string.modelconfigactivity_142));
            return;
        }
        AcToast.show(this, Strings.get(R.string.modelconfigactivity_143, m.label()));
        new Thread(new Runnable() {
            public void run() {
                final ProviderTester.Result r = ProviderTester.testChat(p, m.name);
                ui.post(new Runnable() {
                    public void run() {
                        if (isFinishing()) return;
                        modelAdapter.setBadge(name, r.ok() ? "✓ " + r.ms + "ms" : Strings.get(R.string.modelconfigactivity_144), !r.ok());
                        AcDialog.builder(ModelConfigActivity.this)
                                .setTitle(m.label() + (r.ok() ? Strings.get(R.string.modelconfigactivity_145) : Strings.get(R.string.modelconfigactivity_146)))
                                .setMessage(r.message)
                                .setPositiveButton(Strings.get(R.string.modelconfigactivity_147), null)
                                .show();
                    }
                });
            }
        }, "aichat-test-model").start();
    }
}
