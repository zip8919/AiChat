package xyz.zip8919.app.aichat;

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 提供商编辑页。
 *
 * <p>六条规则都落在这里（用户需求 Ⅱ-3）：
 * <ol>
 *   <li>名称空着时，填 Base URL 立刻从二级域名提取（{@code api.example.com → example}）；</li>
 *   <li>Chat Path 新建时预填 {@code /chat/completions}；</li>
 *   <li>两行地址栏下方常驻「最终请求地址」，随输入实时刷新；</li>
 *   <li>Base URL 末尾与 Chat Path 开头的斜杠冲突由 {@link Endpoint#join} 自动修正，
 *       预览看到的就是真正会发出去的地址（保存时也按同一个结果落库）；</li>
 *   <li>页内可编辑该提供商的模型，支持从 OpenAI 兼容接口一键拉取并添加；</li>
 *   <li>可测试连通性，失败给出原因。</li>
 * </ol>
 *
 * <p>为什么「页内模型」要用竖排按钮而不是 ListView：外层是 ScrollView，
 * 里面套一个可滚动的 ListView 会出现两套滚动打架（4.4 上尤其明显），
 * 而一个提供商的模型通常只有几个，竖排足够。
 */
public class ProviderEditActivity extends ThemedActivity {

    /** 编辑已有提供商时传入唯一标识；不传即新建。 */
    public static final String EXTRA_PROVIDER = "provider";

    private static final String TAG = "ProviderEdit";
    private static final int REQ_MODEL = 3101;

    private ConfigManager cfg;
    private ProviderInfo editing;
    private boolean isNew;

    private TextView title;
    private EditText nameEt, displayEt, baseEt, pathEt, keyEt;
    private TextView finalUrl, testResult, modelsTitle, modelsHint, nameTip;
    private LinearLayout modelsBox;
    private Button testBtn, fetchBtn, addModelBtn, saveBtn, deleteBtn;
    /** 用户在名称框里手动改过之后，不再用域名覆盖（尊重手输，只在空值时自动填）。 */
    private boolean nameTouched;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_provider_edit);
        cfg = ConfigManager.getInstance();

        bindViews();
        String target = getIntent().getStringExtra(EXTRA_PROVIDER);
        if (target != null) {
            editing = cfg.getProvider(target);
        }
        isNew = editing == null;
        fillFields();
        LogUtil.i(TAG, "onCreate: %s target=%s", isNew ? "new" : "edit", target);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从模型编辑页回来：页内模型列表要按最新数据重画
        refreshModels();
    }

    private void bindViews() {
        title = (TextView) findViewById(R.id.pe_title);
        nameEt = (EditText) findViewById(R.id.pe_name);
        displayEt = (EditText) findViewById(R.id.pe_display_name);
        baseEt = (EditText) findViewById(R.id.pe_base_url);
        pathEt = (EditText) findViewById(R.id.pe_chat_path);
        keyEt = (EditText) findViewById(R.id.pe_api_key);
        finalUrl = (TextView) findViewById(R.id.pe_final_url);
        testResult = (TextView) findViewById(R.id.pe_test_result);
        modelsTitle = (TextView) findViewById(R.id.pe_models_title);
        modelsHint = (TextView) findViewById(R.id.pe_models_hint);
        nameTip = (TextView) findViewById(R.id.pe_name_tip);
        modelsBox = (LinearLayout) findViewById(R.id.pe_models_box);
        testBtn = (Button) findViewById(R.id.pe_test);
        fetchBtn = (Button) findViewById(R.id.pe_fetch_models);
        addModelBtn = (Button) findViewById(R.id.pe_add_model);
        saveBtn = (Button) findViewById(R.id.pe_save);
        deleteBtn = (Button) findViewById(R.id.pe_delete);

        findViewById(R.id.pe_back).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        saveBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { save(true); }
        });
        deleteBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { confirmDelete(); }
        });
        testBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { runProviderTest(); }
        });
        fetchBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { fetchRemoteModels(); }
        });
        addModelBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openModelEditor(null); }
        });

        nameEt.addTextChangedListener(new SimpleWatcher() {
            public void afterTextChanged(Editable s) {
                // 用户自己动过名称框（非自动填充）后就不再覆盖
                if (!autoFilling) nameTouched = true;
                updateNameTip();
            }
        });
        baseEt.addTextChangedListener(new SimpleWatcher() {
            public void afterTextChanged(Editable s) {
                autoFillName();
                updateFinalUrl();
            }
        });
        pathEt.addTextChangedListener(new SimpleWatcher() {
            public void afterTextChanged(Editable s) {
                updateFinalUrl();
            }
        });
    }

    /** 自动填名称期间置真，避免把「程序写进去」误判成「用户手输」。 */
    private boolean autoFilling;

    private void fillFields() {
        if (isNew) {
            title.setText(Strings.get(R.string.providereditactivity_001));
            pathEt.setText("/chat/completions");
            // 新建时名称框本来就是空的，允许域名自动填充
            nameTouched = false;
            deleteBtn.setVisibility(View.GONE);
        } else {
            title.setText(editing.label());
            autoFilling = true;
            nameEt.setText(editing.name);
            displayEt.setText(editing.displayName == null ? "" : editing.displayName);
            baseEt.setText(editing.apiUrl);
            pathEt.setText(Endpoint.normalizeChatPath(editing.chatPath));
            keyEt.setText(editing.apiKey);
            autoFilling = false;
            // 已有提供商：地址再变也不改名（改名会波及模型引用，必须是显式行为）
            nameTouched = true;
            deleteBtn.setVisibility(View.VISIBLE);
        }
        updateFinalUrl();
        updateNameTip();
        refreshModels();
    }

    /**
     * 名称自动提取：仅在「名称框为空」且「用户没手输过」时生效。
     *
     * <p>需求原话是「未填写时提取，已填写则不提取也不更改」——但用户手输一个再删掉，
     * 名称框又空了，此时继续提取是合理的；真正要避免的是「把用户写的名字冲掉」，
     * 所以用 {@link #nameTouched} 而不是单纯看输入框是否为空。
     */
    private void autoFillName() {
        if (!isNew) return;
        if (nameTouched && nameEt.getText().toString().trim().length() > 0) return;
        String suggest = Endpoint.deriveName(baseEt.getText().toString());
        if (suggest.length() == 0) return;
        autoFilling = true;
        nameEt.setText(suggest);
        nameEt.setSelection(suggest.length());
        autoFilling = false;
    }

    private void updateNameTip() {
        String nm = nameEt.getText().toString().trim();
        if (nm.length() == 0) {
            nameTip.setText(Strings.get(R.string.providereditactivity_002));
            return;
        }
        if (cfg.isProviderNameTaken(nm, editing)) {
            nameTip.setText(Strings.get(R.string.providereditactivity_003) + nm + Strings.get(R.string.providereditactivity_004));
        } else if (!isNew && editing != null && !nm.equals(editing.name)) {
            nameTip.setText(Strings.get(R.string.providereditactivity_005));
        } else {
            nameTip.setText(Strings.get(R.string.providereditactivity_006));
        }
    }

    /** 实时预览最终地址；斜杠冲突在这里被修正，预览即真相。 */
    private void updateFinalUrl() {
        String base = baseEt.getText().toString();
        String path = pathEt.getText().toString();
        String url = Endpoint.join(base, Endpoint.normalizeChatPath(path));
        if (Endpoint.trim(base).length() == 0) {
            finalUrl.setText(Strings.get(R.string.providereditactivity_007));
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(Strings.get(R.string.providereditactivity_008)).append(url);
        boolean baseSlash = Endpoint.trim(base).endsWith("/");
        boolean pathSlash = Endpoint.trim(path).startsWith("/");
        if (Endpoint.trim(path).length() > 0 && baseSlash == pathSlash) {
            sb.append(baseSlash ? Strings.get(R.string.providereditactivity_009)
                    : Strings.get(R.string.providereditactivity_010));
        }
        if (!Endpoint.isHttpUrl(base)) {
            sb.append(Strings.get(R.string.providereditactivity_011));
        }
        finalUrl.setText(sb.toString());
    }

    // ---- 保存 ----

    /**
     * 校验并落库。
     *
     * @param leaveAfter true 表示保存后返回列表；新建时需要留下继续配置模型，
     *                   所以第一次保存传 false，由调用方决定
     */
    private boolean save(boolean leaveAfter) {
        String name = nameEt.getText().toString().trim();
        String display = displayEt.getText().toString().trim();
        String base = Endpoint.trim(baseEt.getText().toString());
        String path = Endpoint.trim(pathEt.getText().toString());
        String key = keyEt.getText().toString().trim();

        if (name.length() == 0) {
            // 空名称兜底：用域名再提取一次，还不行才报错
            name = Endpoint.deriveName(base);
        }
        if (name.length() == 0) {
            AcToast.show(this, Strings.get(R.string.providereditactivity_012));
            nameEt.requestFocus();
            return false;
        }
        if (!Endpoint.isHttpUrl(base)) {
            AcToast.show(this, Strings.get(R.string.providereditactivity_013));
            baseEt.requestFocus();
            return false;
        }
        if (cfg.isProviderNameTaken(name, editing)) {
            AcToast.show(this, Strings.get(R.string.providereditactivity_014) + name);
            nameEt.requestFocus();
            return false;
        }

        boolean creating = editing == null;
        ProviderInfo target = creating ? new ProviderInfo() : editing;
        String oldName = target.name;

        target.name = name;
        target.displayName = display;
        target.apiUrl = base;
        // 落库前先归一化：预览里显示成什么样，配置里就存什么样，
        // 避免「界面看着对、实际请求多一个斜杠」
        target.chatPath = Endpoint.tidyChatPath(Endpoint.normalizeChatPath(path));
        target.apiKey = key;
        if (target.thinkingType == null || target.thinkingType.length() == 0) {
            target.thinkingType = "object";
        }
        if (target.thinkingParamName == null || target.thinkingParamName.length() == 0) {
            target.thinkingParamName = "thinking";
        }
        if (creating) {
            cfg.getProviders().add(target);
            editing = target;
        }
        // 改名要同步模型引用，否则模型按旧名找不到提供商，变成永远发不出去的孤儿
        if (!creating && oldName != null && !oldName.equals(name)) {
            int synced = 0;
            for (ModelInfo m : cfg.getModels()) {
                if (oldName.equals(m.provider)) {
                    m.provider = name;
                    synced++;
                }
            }
            LogUtil.i(TAG, "provider renamed '%s' -> '%s', %d model refs synced",
                    oldName, name, synced);
        }
        cfg.save();
        AcToast.show(this, Strings.get(R.string.providereditactivity_015));
        LogUtil.i(TAG, "save: name=%s url=%s path=%s final=%s keyLen=%d new=%s",
                target.name, target.apiUrl, target.chatPath, Endpoint.chatUrl(target),
                target.apiKey == null ? 0 : target.apiKey.length(), creating);

        if (creating) {
            // 新建后留在本页：下一步通常是「获取模型列表」，退出去就得再进来一次
            isNew = false;
            nameTouched = true;
            title.setText(target.label());
            deleteBtn.setVisibility(View.VISIBLE);
            refreshModels();
            fetchBtn.setEnabled(true);
        }
        if (leaveAfter) finish();
        return true;
    }

    private void confirmDelete() {
        if (editing == null) return;
        final int models = cfg.modelsOf(editing.name).size();
        String msg = Strings.get(R.string.providereditactivity_016) + editing.label() + "？"
                + (models > 0 ? Strings.get(R.string.providereditactivity_017) + models + Strings.get(R.string.providereditactivity_018) : "");
        AcDialog.builder(this)
                .setTitle(Strings.get(R.string.providereditactivity_019))
                .setMessage(msg)
                .setPositiveButton(Strings.get(R.string.providereditactivity_020), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        cfg.getProviders().remove(editing);
                        for (ModelInfo m : cfg.modelsOf(editing.name)) {
                            cfg.getModels().remove(m);
                        }
                        // 默认模型存的是唯一键；被删的提供商若占着它，回退到第一条
                        if (cfg.getDefaultModelInfo() == null) {
                            List<ModelInfo> all = cfg.getModels();
                            cfg.setDefaultModel(all.isEmpty() ? "" : all.get(0).key());
                        }
                        cfg.save();
                        LogUtil.i(TAG, "delete provider %s (%d models)", editing.name, models);
                        AcToast.show(ProviderEditActivity.this, Strings.get(R.string.providereditactivity_021));
                        finish();
                    }
                })
                .setNegativeButton(Strings.get(R.string.providereditactivity_022), null)
                .show();
    }

    // ---- 测试 ----

    /** 用当前输入框里的值测试，不必先保存。 */
    private void runProviderTest() {
        if (busy) return;
        final ProviderInfo probe = buildProbe();
        if (!Endpoint.isHttpUrl(probe.apiUrl)) {
            AcToast.show(this, Strings.get(R.string.providereditactivity_023));
            return;
        }
        if (probe.apiKey.trim().length() == 0) {
            AcToast.show(this, Strings.get(R.string.providereditactivity_024));
            return;
        }
        busy = true;
        testBtn.setEnabled(false);
        testBtn.setText(Strings.get(R.string.providereditactivity_025));
        testResult.setText(Strings.get(R.string.providereditactivity_026) + Endpoint.chatUrl(probe) + " …");
        new Thread(new Runnable() {
            public void run() {
                final ProviderTester.Result r = ProviderTester.testProvider(probe);
                ui.post(new Runnable() {
                    public void run() {
                        busy = false;
                        if (isFinishing()) return;
                        testBtn.setEnabled(true);
                        testBtn.setText(Strings.get(R.string.providereditactivity_027));
                        testResult.setText((r.ok() ? "✓ " : "✗ ") + r.message);
                        testResult.setTextColor(r.ok() ? 0xFF2E7D32 : 0xFFC62828);
                        LogUtil.i(TAG, "test provider %s -> %s %s",
                                probe.name, r.ok() ? "OK" : "FAIL", r.message);
                    }
                });
            }
        }, "aichat-test-provider").start();
    }

    /** 用当前界面上的值组一个临时提供商（不写配置）。 */
    private ProviderInfo buildProbe() {
        ProviderInfo p = editing != null ? editing.copy() : new ProviderInfo();
        p.name = nameEt.getText().toString().trim();
        p.apiUrl = Endpoint.trim(baseEt.getText().toString());
        p.chatPath = Endpoint.tidyChatPath(Endpoint.normalizeChatPath(
                pathEt.getText().toString()));
        p.apiKey = keyEt.getText().toString().trim();
        return p;
    }

    // ---- 拉取 / 添加模型 ----

    /**
     * 从 OpenAI 兼容的 {@code /models} 拉列表，勾选后一键添加。
     *
     * <p>拉取前会自动保存一次：模型要挂在一个「已经存在」的提供商名下，
     * 否则新建的提供商标识别都没落库，加进去的模型立刻变成孤儿。
     */
    private void fetchRemoteModels() {
        if (busy) return;
        if (editing == null) {
            if (!save(false)) return;
        } else if (!save(false)) {
            return;
        }
        final ProviderInfo probe = buildProbe();
        if (!Endpoint.isHttpUrl(probe.apiUrl)) return;
        busy = true;
        fetchBtn.setEnabled(false);
        fetchBtn.setText(Strings.get(R.string.providereditactivity_028));
        testResult.setText(Strings.get(R.string.providereditactivity_029));
        new Thread(new Runnable() {
            public void run() {
                final ProviderTester.ModelsResult r = ProviderTester.fetchModels(probe);
                ui.post(new Runnable() {
                    public void run() {
                        busy = false;
                        if (isFinishing()) return;
                        fetchBtn.setEnabled(true);
                        fetchBtn.setText(Strings.get(R.string.providereditactivity_030));
                        if (!r.ok) {
                            testResult.setText("✗ " + r.message);
                            testResult.setTextColor(0xFFC62828);
                            AcDialog.builder(ProviderEditActivity.this)
                                    .setTitle(Strings.get(R.string.providereditactivity_031))
                                    .setMessage(r.message + Strings.get(R.string.providereditactivity_061))
                                    .setPositiveButton(Strings.get(R.string.providereditactivity_034), null)
                                    .show();
                            return;
                        }
                        testResult.setText("✓ " + r.message);
                        testResult.setTextColor(0xFF2E7D32);
                        showModelPicker(r.models);
                    }
                });
            }
        }, "aichat-fetch-models").start();
    }

    /** 多选弹窗：只列「还没添加过」的模型，已存在的不必再勾一遍。 */
    private void showModelPicker(final List<String> remote) {
        if (editing == null) return;
        Set<String> existing = new HashSet<String>();
        for (ModelInfo m : cfg.modelsOf(editing.name)) existing.add(m.name);
        final List<String> candidates = new ArrayList<String>();
        for (String name : remote) {
            if (!existing.contains(name)) candidates.add(name);
        }
        if (candidates.isEmpty()) {
            AcDialog.builder(this)
                    .setTitle(Strings.get(R.string.providereditactivity_035))
                    .setMessage(Strings.get(R.string.providereditactivity_062, remote.size()))
                    .setPositiveButton(Strings.get(R.string.providereditactivity_038), null)
                    .show();
            return;
        }
        final boolean[] checked = new boolean[candidates.size()];
        final String[] labels = new String[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) labels[i] = candidates.get(i);
        AcDialog.builder(this)
                .setTitle(Strings.get(R.string.providereditactivity_039) + candidates.size() + Strings.get(R.string.providereditactivity_040))
                .setMultiChoiceItems(labels, checked, new DialogInterface.OnMultiChoiceClickListener() {
                    public void onClick(DialogInterface d, int which, boolean isChecked) {
                        checked[which] = isChecked;
                    }
                })
                .setPositiveButton(Strings.get(R.string.providereditactivity_041), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        List<String> all = new ArrayList<String>(candidates);
                        addModels(all);
                    }
                })
                .setNeutralButton(Strings.get(R.string.providereditactivity_042), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        List<String> sel = new ArrayList<String>();
                        for (int i = 0; i < candidates.size(); i++) {
                            if (checked[i]) sel.add(candidates.get(i));
                        }
                        addModels(sel);
                    }
                })
                .setNegativeButton(Strings.get(R.string.providereditactivity_043), null)
                .show();
    }

    /** 批量加入模型：跳过重名（模型名是全局唯一键），默认带思考开关。 */
    private void addModels(List<String> names) {
        if (editing == null || names.isEmpty()) {
            AcToast.show(this, Strings.get(R.string.providereditactivity_044));
            return;
        }
        int added = 0;
        int skipped = 0;
        for (String n : names) {
            String id = n == null ? "" : n.trim();
            if (id.length() == 0) continue;
            if (cfg.isModelNameTaken(editing.name, id, null)) {
                skipped++;
                continue;
            }
            ModelInfo m = new ModelInfo();
            m.name = id;
            m.provider = editing.name;
            m.supportsThinking = true;
            m.supportsVision = false;
            cfg.getModels().add(m);
            added++;
        }
        cfg.save();
        refreshModels();
        String msg = Strings.get(R.string.providereditactivity_045) + added + Strings.get(R.string.providereditactivity_046);
        if (skipped > 0) msg += Strings.get(R.string.providereditactivity_047) + skipped + Strings.get(R.string.providereditactivity_048);
        AcToast.show(this, msg);
        LogUtil.i(TAG, "addModels: added=%d skipped=%d provider=%s", added, skipped, editing.name);
    }

    // ---- 页内模型列表 ----

    private void refreshModels() {
        if (modelsBox == null) return;
        modelsBox.removeAllViews();
        if (editing == null) {
            modelsTitle.setText(Strings.get(R.string.providereditactivity_049));
            modelsHint.setText(Strings.get(R.string.providereditactivity_050));
            addModelBtn.setEnabled(false);
            fetchBtn.setEnabled(Endpoint.isHttpUrl(baseEt.getText().toString()));
            return;
        }
        addModelBtn.setEnabled(true);
        fetchBtn.setEnabled(true);
        modelsHint.setText(Strings.get(R.string.providereditactivity_051));
        final List<ModelInfo> list = cfg.modelsOf(editing.name);
        modelsTitle.setText(Strings.get(R.string.providereditactivity_052) + list.size() + "）");
        if (list.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(Strings.get(R.string.providereditactivity_053));
            empty.setTextSize(12);
            empty.setTextColor(ThemeTokens.get(this, ThemeTokens.AC_TEXT_SECONDARY));
            empty.setPadding(0, dp(6), 0, dp(6));
            modelsBox.addView(empty);
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            final ModelInfo m = list.get(i);
            TextView row = new TextView(this);
            StringBuilder sb = new StringBuilder();
            sb.append(m.label());
            if (!m.label().equals(m.name)) sb.append("  ·  ").append(m.name);
            if (m.supportsVision) sb.append(Strings.get(R.string.providereditactivity_054));
            row.setText(sb.toString());
            row.setTextSize(13);
            row.setTextColor(ThemeTokens.get(this, ThemeTokens.AC_TEXT));
            row.setSingleLine(true);
            row.setEllipsize(android.text.TextUtils.TruncateAt.END);
            // 不用 @drawable/bg_card 直接设：这行是 new 出来的，且往往在 onResume 的
            // 整树遍历之后才 addView，ThemeApplier 刷不到它 —— 深色模式下就是白底配
            // 浅灰字。cardBg() 按当前生效色现造（默认态仍返回原资源，零回归）。
            row.setBackgroundDrawable(ThemeRecolor.cardBg(this));
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(6);
            row.setLayoutParams(lp);
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { openModelEditor(m.key()); }
            });
            row.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    confirmDeleteModel(m);
                    return true;
                }
            });
            modelsBox.addView(row);
        }
    }

    private void openModelEditor(String modelName) {
        if (editing == null) {
            AcToast.show(this, Strings.get(R.string.providereditactivity_055));
            return;
        }
        Intent it = new Intent(this, ModelEditActivity.class);
        it.putExtra(ModelEditActivity.EXTRA_PROVIDER, editing.name);
        if (modelName != null) it.putExtra(ModelEditActivity.EXTRA_MODEL, modelName);
        startActivityForResult(it, REQ_MODEL);
    }

    private void confirmDeleteModel(final ModelInfo m) {
        AcDialog.builder(this)
                .setTitle(Strings.get(R.string.providereditactivity_056))
                .setMessage(Strings.get(R.string.providereditactivity_057) + m.label() + "？")
                .setPositiveButton(Strings.get(R.string.providereditactivity_058), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        boolean wasDefault = cfg.isDefaultModel(m);
                        cfg.getModels().remove(m);
                        if (wasDefault) {
                            List<ModelInfo> all = cfg.getModels();
                            cfg.setDefaultModel(all.isEmpty() ? "" : all.get(0).key());
                        }
                        cfg.save();
                        refreshModels();
                        AcToast.show(ProviderEditActivity.this, Strings.get(R.string.providereditactivity_059));
                    }
                })
                .setNegativeButton(Strings.get(R.string.providereditactivity_060), null)
                .show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 只关心 afterTextChanged 的 TextWatcher 简写。 */
    private abstract static class SimpleWatcher implements TextWatcher {
        public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
        public void onTextChanged(CharSequence s, int start, int before, int count) { }
    }
}
