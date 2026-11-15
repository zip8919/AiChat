package xyz.zip8919.app.aichat;

import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 模型编辑页：可直接选提供商、可当场测连通性（用户需求 Ⅱ-6）。
 *
 * <p>模型名（API ID）与显示名分开：请求里永远发 {@code name}，界面上优先显示 displayName。
 * 这个区分很关键——把显示名发出去，端点会直接 404。
 */
public class ModelEditActivity extends ThemedActivity {

    public static final String EXTRA_MODEL = "model";
    /** 新建时预选的提供商（从提供商编辑页进来）。 */
    public static final String EXTRA_PROVIDER = "provider";

    private static final String TAG = "ModelEdit";

    private ConfigManager cfg;
    private ModelInfo editing;

    private TextView title, providerInfo, testResult, probeResult;
    private Spinner providerSpinner;
    private EditText nameEt, displayEt;
    private CheckBox visionCb, thinkingCb;
    private Button testBtn, saveBtn, deleteBtn, defaultBtn, probeBtn;

    /** 探测得到的档位集合（本次编辑内的临时值）；null 表示没探过。 */
    private List<String> probedLevels;

    /** 下拉里每一项对应的提供商唯一标识；空串表示「（未指定）」。 */
    private final List<String> providerKeys = new ArrayList<String>();
    private String presetProvider;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_model_edit);
        cfg = ConfigManager.getInstance();

        title = (TextView) findViewById(R.id.me_title);
        providerInfo = (TextView) findViewById(R.id.me_provider_info);
        testResult = (TextView) findViewById(R.id.me_test_result);
        providerSpinner = (Spinner) findViewById(R.id.me_provider);
        nameEt = (EditText) findViewById(R.id.me_name);
        displayEt = (EditText) findViewById(R.id.me_display_name);
        visionCb = (CheckBox) findViewById(R.id.me_vision);
        thinkingCb = (CheckBox) findViewById(R.id.me_thinking);
        testBtn = (Button) findViewById(R.id.me_test);
        saveBtn = (Button) findViewById(R.id.me_save);
        deleteBtn = (Button) findViewById(R.id.me_delete);
        defaultBtn = (Button) findViewById(R.id.me_set_default);
        probeBtn = (Button) findViewById(R.id.me_probe);
        probeResult = (TextView) findViewById(R.id.me_probe_result);

        String modelName = getIntent().getStringExtra(EXTRA_MODEL);
        presetProvider = getIntent().getStringExtra(EXTRA_PROVIDER);
        if (modelName != null) editing = cfg.getModel(modelName);

        findViewById(R.id.me_back).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        saveBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { save(); }
        });
        // 「设为默认」= 先按当前输入落库，再把它设为默认。已保存的模型不必先点保存再来一次，
        // 新建的模型也能一步到位（否则新模型根本没有标识可设）。
        defaultBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { saveInternal(true); }
        });
        deleteBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { confirmDelete(); }
        });
        testBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { runTest(); }
        });
        probeBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { runProbe(); }
        });

        buildProviderSpinner();
        fillFields();
        providerSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                updateProviderInfo();
            }
            public void onNothingSelected(AdapterView<?> p) { }
        });
        updateProviderInfo();
        LogUtil.i(TAG, "onCreate: %s model=%s provider=%s",
                editing == null ? "new" : "edit", modelName, presetProvider);
    }

    private void buildProviderSpinner() {
        List<String> labels = new ArrayList<String>();
        providerKeys.clear();
        providerKeys.add("");
        labels.add("（未指定提供商）");
        for (ProviderInfo p : cfg.getProviders()) {
            providerKeys.add(p.name);
            labels.add(p.label());
        }
        ArrayAdapter<String> a = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, labels);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        providerSpinner.setAdapter(a);
    }

    private void fillFields() {
        if (editing == null) {
            title.setText("新建模型");
            deleteBtn.setVisibility(View.GONE);
            thinkingCb.setChecked(true);
            selectProvider(presetProvider);
        } else {
            title.setText(editing.label());
            deleteBtn.setVisibility(View.VISIBLE);
            nameEt.setText(editing.name);
            displayEt.setText(editing.displayName == null ? "" : editing.displayName);
            visionCb.setChecked(editing.supportsVision);
            thinkingCb.setChecked(editing.supportsThinking);
            selectProvider(editing.provider);
            if (editing.levelsProbed()) {
                probedLevels = editing.levelList();
                probeResult.setText("上次探测：思考可用 " + ThinkingLevels.describe(probedLevels)
                        + (editing.levelsUnverifiable ? "（端点未校验档位取值）" : ""));
            }
        }
        updateDefaultButton();
    }

    /** 已是默认模型时禁用按钮并把文案换掉，避免重复点击产生「设了没反应」的错觉。 */
    private void updateDefaultButton() {
        if (defaultBtn == null) return;
        boolean isDefault = editing != null && cfg.isDefaultModel(editing);
        defaultBtn.setEnabled(!isDefault);
        defaultBtn.setText(isDefault ? "当前为默认模型" : "设为默认模型");
    }

    private void selectProvider(String key) {
        if (key == null) return;
        for (int i = 0; i < providerKeys.size(); i++) {
            if (key.equals(providerKeys.get(i))) {
                providerSpinner.setSelection(i, false);
                return;
            }
        }
    }

    private String selectedProvider() {
        int pos = providerSpinner.getSelectedItemPosition();
        if (pos < 0 || pos >= providerKeys.size()) return "";
        return providerKeys.get(pos);
    }

    private void updateProviderInfo() {
        String key = selectedProvider();
        ProviderInfo p = key.length() == 0 ? null : cfg.getProvider(key);
        if (p == null) {
            providerInfo.setText("未选择提供商，模型无法发送请求");
            return;
        }
        String url = Endpoint.chatUrl(p);
        String keyState = (p.apiKey == null || p.apiKey.trim().length() == 0)
                ? "；未填 Key" : "";
        providerInfo.setText(url + keyState);
    }

    // ---- 测试 ----

    /** 用当前输入框的值测试：不用先保存，「填好就能验」是这个按钮存在的意义。 */
    private void runTest() {
        if (busy) return;
        String model = nameEt.getText().toString().trim();
        if (model.length() == 0) {
            AcToast.show(this, "请先填写模型 ID");
            return;
        }
        String key = selectedProvider();
        ProviderInfo p = key.length() == 0 ? null : cfg.getProvider(key);
        if (p == null) {
            AcToast.show(this, "请先选择提供商");
            return;
        }
        if (p.apiKey == null || p.apiKey.trim().length() == 0) {
            AcToast.show(this, "该提供商还没填 API Key");
            return;
        }
        busy = true;
        testBtn.setEnabled(false);
        testBtn.setText("测试中…");
        testResult.setText("正在请求 " + Endpoint.chatUrl(p) + " …");
        final ProviderInfo snap = p.copy();
        final String modelId = model;
        new Thread(new Runnable() {
            public void run() {
                final ProviderTester.Result r = ProviderTester.testChat(snap, modelId);
                ui.post(new Runnable() {
                    public void run() {
                        busy = false;
                        if (isFinishing()) return;
                        testBtn.setEnabled(true);
                        testBtn.setText("测试连通性");
                        testResult.setText((r.ok() ? "✓ " : "✗ ") + r.message);
                        testResult.setTextColor(r.ok() ? 0xFF2E7D32 : 0xFFC62828);
                        LogUtil.i(TAG, "test model %s@%s -> %s %s", modelId,
                                snap.name, r.ok() ? "OK" : "FAIL", r.message);
                    }
                });
            }
        }, "aichat-test-model").start();
    }

    // ---- 能力探测 ----

    /** 单个模型探测：用当前输入值，结果直接写回两个勾选框，保存时一起落库。 */
    private void runProbe() {
        if (busy) return;
        String model = nameEt.getText().toString().trim();
        if (model.length() == 0) {
            AcToast.show(this, "请先填写模型 ID");
            return;
        }
        String key = selectedProvider();
        ProviderInfo p = key.length() == 0 ? null : cfg.getProvider(key);
        if (p == null) {
            AcToast.show(this, "请先选择提供商");
            return;
        }
        if (p.apiKey == null || p.apiKey.trim().length() == 0) {
            AcToast.show(this, "该提供商还没填 API Key");
            return;
        }
        busy = true;
        probeBtn.setEnabled(false);
        probeBtn.setText("探测中…");
        probeResult.setText("正在逐档请求，共 6 档，可能需要十几秒…");
        final ProviderInfo snap = p.copy();
        final String modelId = model;
        new Thread(new Runnable() {
            public void run() {
                final CapabilityProbe.Report rep = CapabilityProbe.probe(snap, modelId, null);
                ui.post(new Runnable() {
                    public void run() {
                        busy = false;
                        if (isFinishing()) return;
                        probeBtn.setEnabled(true);
                        probeBtn.setText("探测思考档位与图片输入");
                        applyProbe(rep, modelId);
                    }
                });
            }
        }, "aichat-probe-model").start();
    }

    private void applyProbe(CapabilityProbe.Report rep, String modelId) {
        // 视觉与档位是两项独立结论：档位没探明白，图片输入的结果也照样落地
        if (rep.vision != null) visionCb.setChecked(rep.vision.booleanValue());

        boolean levelsOk = rep.ok() && !rep.levels.isEmpty();
        if (!levelsOk && !rep.levelsRejected && rep.vision == null) {
            // 一档都没得出，也没探到图片：认证、网络或端点不可用，配置不动
            probeResult.setTextColor(0xFFC62828);
            probeResult.setText("探测未完成：" + rep.error);
            LogUtil.w(TAG, "probe %s failed: %s", modelId, rep.error);
            return;
        }

        StringBuilder sb = new StringBuilder();
        if (levelsOk) {
            probedLevels = new ArrayList<String>(rep.levels);
            thinkingCb.setChecked(true);
            sb.append("思考可用：").append(ThinkingLevels.describe(rep.levels));
            if (rep.unverifiable) {
                sb.append("\n端点对任何档位都返回成功，无法区分具体档位，六档均视为可用。");
            }
            String global = cfg.getThinkingLevel();
            String actual = ThinkingLevels.resolve(global, probedLevels);
            if (!actual.equals(global)) {
                sb.append("\n当前全局档位「").append(ThinkingLevels.label(global))
                        .append("」不可用，使用时将自动按「").append(ThinkingLevels.label(actual))
                        .append("」发送。");
            }
        } else if (rep.levelsRejected) {
            thinkingCb.setChecked(false);
            // 空列表（不是 null）：保存时据此清掉旧的档位记录，否则列表还显示早已不可用的档位
            probedLevels = new ArrayList<String>();
            sb.append("六档全部被拒绝，该模型不接受思考参数，已把思考开关置为关闭。");
        } else {
            // 探到一半断了：开关保持原样，不拿半截结论改配置
            sb.append("思考档位未探明：").append(rep.error);
        }

        if (rep.vision != null) {
            boolean ok = rep.vision.booleanValue();
            sb.append("\n").append(ok
                    ? "图片输入可用，两张测试图都读出了数字，已勾选。"
                    : "图片输入不可用，已取消勾选。");
            if (!ok && rep.visionNote.length() > 0) {
                sb.append("判定依据：").append(rep.visionNote).append("。");
            }
        } else if (rep.visionNote.length() > 0) {
            sb.append("\n").append(rep.visionNote);
        }
        // 有可用项就按常态色显示，两项都没得出可用结论才标红
        boolean usable = levelsOk || Boolean.TRUE.equals(rep.vision);
        probeResult.setTextColor(usable
                ? ThemeTokens.get(this, ThemeTokens.AC_TEXT_SECONDARY) : 0xFFC62828);
        probeResult.setText(sb.toString());
        LogUtil.i(TAG, "probe %s done: levels=%s vision=%s unverifiable=%s rejected=%s",
                modelId, ThinkingLevels.join(probedLevels), rep.vision, rep.unverifiable,
                rep.levelsRejected);
    }

    // ---- 保存 / 删除 ----

    private void save() {
        saveInternal(false);
    }

    /**
     * 落库。
     *
     * @param makeDefault 同时设为默认模型。★ 必须先设默认再 save() ——
     *                    setDefaultModel 只改内存，早先的代码把 save 写在它前面，
     *                    改名后同步默认模型的那次修改其实一次都没落盘。
     */
    private void saveInternal(boolean makeDefault) {
        String name = nameEt.getText().toString().trim();
        String display = displayEt.getText().toString().trim();
        String provider = selectedProvider();
        if (name.length() == 0) {
            AcToast.show(this, "模型 ID 不能为空");
            nameEt.requestFocus();
            return;
        }
        if (provider.length() == 0) {
            AcToast.show(this, "请选择提供商");
            return;
        }
        if (cfg.getProvider(provider) == null) {
            AcToast.show(this, "提供商不存在：" + provider);
            return;
        }
        if (cfg.isModelNameTaken(provider, name, editing)) {
            AcToast.show(this, "该提供商下已有同名模型：" + name);
            nameEt.requestFocus();
            return;
        }
        boolean creating = editing == null;
        ModelInfo target = creating ? new ModelInfo() : editing;
        // 唯一键在改字段之前取：改完 name/provider 后旧键就还原不出来了。
        // 默认模型存的是唯一键，改名或换提供商都必须跟着更新，否则默认模型悬空。
        String oldKey = creating ? null : target.key();
        boolean wasDefault = !creating && cfg.isDefaultModel(target);
        target.name = name;
        target.displayName = display;
        target.provider = provider;
        target.supportsVision = visionCb.isChecked();
        target.supportsThinking = thinkingCb.isChecked();
        if (probedLevels != null) {
            // 探测结论先落：setLevelList 会把 supportsThinking 同步为 true，
            // 随后用户手动关掉勾选框也要能被尊重，所以再写一次
            target.setLevelList(probedLevels);
            target.supportsThinking = thinkingCb.isChecked();
            target.probedAt = System.currentTimeMillis();
        }
        if (creating) {
            cfg.getModels().add(target);
        }
        if (wasDefault && oldKey != null && !oldKey.equals(target.key())) {
            cfg.setDefaultModel(target.key());
            LogUtil.i(TAG, "default model key '%s' -> '%s'", oldKey, target.key());
        }
        if (makeDefault) {
            cfg.setDefaultModel(target.key());
            LogUtil.i(TAG, "set as default: %s", target.key());
        }
        // 默认模型的变更必须在这里落盘：上面的 setDefaultModel 只动内存
        cfg.save();
        AcToast.show(this, makeDefault ? "已保存，并设为默认模型" : "已保存");
        LogUtil.i(TAG, "save: %s@%s vision=%s thinking=%s new=%s",
                target.name, target.provider, target.supportsVision,
                target.supportsThinking, creating);
        finish();
    }

    private void confirmDelete() {
        if (editing == null) return;
        AcDialog.builder(this)
                .setTitle("删除模型")
                .setMessage("确定删除 " + editing.label() + "？")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        boolean wasDefault = cfg.isDefaultModel(editing);
                        cfg.getModels().remove(editing);
                        if (wasDefault) {
                            List<ModelInfo> all = cfg.getModels();
                            cfg.setDefaultModel(all.isEmpty() ? "" : all.get(0).key());
                            LogUtil.i(TAG, "default model removed, fallback to %s",
                                    cfg.getDefaultModel());
                        }
                        cfg.save();
                        LogUtil.i(TAG, "delete model %s", editing.name);
                        AcToast.show(ModelEditActivity.this, "已删除");
                        finish();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
