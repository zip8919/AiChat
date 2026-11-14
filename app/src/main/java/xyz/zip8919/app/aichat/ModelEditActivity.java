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

    private TextView title, providerInfo, testResult;
    private Spinner providerSpinner;
    private EditText nameEt, displayEt;
    private CheckBox visionCb, thinkingCb;
    private Button testBtn, saveBtn, deleteBtn;

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

        String modelName = getIntent().getStringExtra(EXTRA_MODEL);
        presetProvider = getIntent().getStringExtra(EXTRA_PROVIDER);
        if (modelName != null) editing = cfg.getModel(modelName);

        findViewById(R.id.me_back).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        saveBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { save(); }
        });
        deleteBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { confirmDelete(); }
        });
        testBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { runTest(); }
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
        }
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

    // ---- 保存 / 删除 ----

    private void save() {
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
        if (cfg.isModelNameTaken(name, editing)) {
            AcToast.show(this, "模型 ID 已被其他模型使用：" + name);
            nameEt.requestFocus();
            return;
        }
        boolean creating = editing == null;
        ModelInfo target = creating ? new ModelInfo() : editing;
        String oldName = target.name;
        target.name = name;
        target.displayName = display;
        target.provider = provider;
        target.supportsVision = visionCb.isChecked();
        target.supportsThinking = thinkingCb.isChecked();
        if (creating) {
            cfg.getModels().add(target);
        }
        cfg.save();
        // 默认模型按名字引用，改名后要同步，否则选中态丢失
        if (!creating && oldName != null && !oldName.equals(name)
                && oldName.equals(cfg.getDefaultModel())) {
            cfg.setDefaultModel(name);
            LogUtil.i(TAG, "default model renamed '%s' -> '%s'", oldName, name);
        }
        AcToast.show(this, "已保存");
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
                        cfg.getModels().remove(editing);
                        if (editing.name.equals(cfg.getDefaultModel())) {
                            List<ModelInfo> all = cfg.getModels();
                            cfg.setDefaultModel(all.isEmpty() ? "" : all.get(0).name);
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
