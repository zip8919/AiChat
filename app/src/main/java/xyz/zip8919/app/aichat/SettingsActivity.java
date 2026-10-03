package xyz.zip8919.app.aichat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

public class SettingsActivity extends Activity {
    private static final String TAG = "SettingsActivity";
    private static final String PREFS_NAME = "aichat_settings";
    private static final String KEY_SYSTEM_PROMPT = "system_prompt";
    private static final String KEY_AUTO_TITLE_ENABLED = "auto_title_enabled";
    private static final String KEY_TITLE_MODEL = "title_model";
    private static final String KEY_TITLE_PROMPT = "title_prompt";
    private static final String KEY_QUICK_SCAN_ENABLED = "quick_scan_enabled";
    private static final String KEY_LOG_ENABLED = "log_enabled";
    private static final String KEY_REALTIME_RENDER = "realtime_render_enabled";
    private static final String KEY_STREAM_ENABLED = "stream_enabled";
    private static final String KEY_PRESETS = "system_presets";
    private static final String DEFAULT_TITLE_MODEL = "Qwen/Qwen3.5-397B-A17B";
    private static final String DEFAULT_TITLE_PROMPT = "你是一个标题生成助手。根据用户消息生成3-15字标题。只输出标题本身，禁止输出任何其他文字、解释、标点或换行。";

    private SharedPreferences prefs;
    private EditText systemPromptEdit, titlePromptEdit;
    private Switch autoTitleSwitch, quickScanSwitch, logSwitch, realtimeRenderSwitch, streamSwitch;
    private Spinner titleModelSpinner, presetSpinner;
    private Button saveButton, cancelButton, balanceButton, manageModelsButton;
    private Button managePresetsButton, savePresetButton;
    private AlertDialog manageDialog;
    private List<String[]> presetList; // [name, prompt]

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        this.prefs = getSharedPreferences(PREFS_NAME, 0);
        LogUtil.i(TAG, "========== onCreate ========== (%s)", LogUtil.thread());
        initViews();
        loadSettings();
        LogUtil.i(TAG, "onCreate done: systemPrompt=%d chars, autoTitle=%s, titleModel=%s",
                systemPromptEdit.getText().length(), autoTitleSwitch.isChecked(),
                prefs.getString(KEY_TITLE_MODEL, DEFAULT_TITLE_MODEL));
    }

    private void initViews() {
        systemPromptEdit = (EditText) findViewById(R.id.system_prompt_edit);
        titlePromptEdit = (EditText) findViewById(R.id.title_prompt_edit);
        autoTitleSwitch = (Switch) findViewById(R.id.auto_title_switch);
        quickScanSwitch = (Switch) findViewById(R.id.quick_scan_switch);
        logSwitch = (Switch) findViewById(R.id.log_switch);
        realtimeRenderSwitch = (Switch) findViewById(R.id.realtime_render_switch);
        streamSwitch = (Switch) findViewById(R.id.stream_switch);
        titleModelSpinner = (Spinner) findViewById(R.id.title_model_spinner);
        saveButton = (Button) findViewById(R.id.save_button);
        cancelButton = (Button) findViewById(R.id.cancel_button);
        balanceButton = (Button) findViewById(R.id.balance_button);
        manageModelsButton = (Button) findViewById(R.id.manage_models_button);
        presetSpinner = (Spinner) findViewById(R.id.preset_spinner);
        managePresetsButton = (Button) findViewById(R.id.manage_presets_button);
        savePresetButton = (Button) findViewById(R.id.save_preset_button);

        saveButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { LogUtil.d(TAG, "click: save"); saveSettings(); }
        });
        cancelButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { LogUtil.d(TAG, "click: cancel"); finish(); }
        });
        balanceButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { LogUtil.d(TAG, "click: query balance"); queryBalance(); }
        });
        manageModelsButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                LogUtil.d(TAG, "click: manage models -> ModelConfigActivity");
                startActivity(new Intent(SettingsActivity.this, ModelConfigActivity.class));
            }
        });
        managePresetsButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { LogUtil.d(TAG, "click: manage presets"); showManagePresetsDialog(); }
        });
        savePresetButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { LogUtil.d(TAG, "click: save as preset"); saveCurrentAsPreset(); }
        });
        presetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (pos > 0 && pos - 1 < presetList.size()) {
                    LogUtil.d(TAG, "preset selected: pos=%d name=%s", pos, presetList.get(pos - 1)[0]);
                    systemPromptEdit.setText(presetList.get(pos - 1)[1]);
                }
            }
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        LogUtil.d(TAG, "initViews done");
    }

    private void loadSettings() {
        systemPromptEdit.setText(prefs.getString(KEY_SYSTEM_PROMPT, ""));
        titlePromptEdit.setText(prefs.getString(KEY_TITLE_PROMPT, DEFAULT_TITLE_PROMPT));
        autoTitleSwitch.setChecked(prefs.getBoolean(KEY_AUTO_TITLE_ENABLED, true));
        quickScanSwitch.setChecked(prefs.getBoolean(KEY_QUICK_SCAN_ENABLED, false));
        logSwitch.setChecked(prefs.getBoolean(KEY_LOG_ENABLED, true));
        realtimeRenderSwitch.setChecked(prefs.getBoolean(KEY_REALTIME_RENDER, false));
        streamSwitch.setChecked(prefs.getBoolean(KEY_STREAM_ENABLED, true));
        LogUtil.ENABLED = logSwitch.isChecked();
        refreshTitleModelSpinner();
        refreshPresetSpinner();
        LogUtil.d(TAG, "loadSettings: autoTitle=%s, quickScan=%s, log=%s, titleModel=%s",
                autoTitleSwitch.isChecked(), quickScanSwitch.isChecked(), logSwitch.isChecked(),
                prefs.getString(KEY_TITLE_MODEL, DEFAULT_TITLE_MODEL));
    }

    private void refreshTitleModelSpinner() {
        ConfigManager cm = ConfigManager.getInstance();
        List<ModelInfo> models = cm.getModels();
        List<String> names = new ArrayList<String>();
        for (ModelInfo m : models) {
            names.add(m.name + " (" + m.provider + ")");
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        titleModelSpinner.setAdapter(adapter);

        String saved = prefs.getString(KEY_TITLE_MODEL, DEFAULT_TITLE_MODEL);
        LogUtil.d(TAG, "refreshTitleModelSpinner: %d models, saved=%s", models.size(), saved);
        for (int i = 0; i < models.size(); i++) {
            if (models.get(i).name.equals(saved)) {
                titleModelSpinner.setSelection(i);
                LogUtil.d(TAG, "refreshTitleModelSpinner: selected pos=%d", i);
                return;
            }
        }
        LogUtil.w(TAG, "refreshTitleModelSpinner: saved model '%s' not found", saved);
    }

    private void saveSettings() {
        String prompt = systemPromptEdit.getText().toString().trim();
        boolean autoTitle = autoTitleSwitch.isChecked();
        boolean logEnabled = logSwitch.isChecked();

        String titlePrompt = titlePromptEdit.getText().toString().trim();
        LogUtil.i(TAG, "saveSettings: promptLen=%d autoTitle=%s quickScan=%s log=%s titlePromptLen=%d",
                prompt.length(), autoTitle, quickScanSwitch.isChecked(), logEnabled, titlePrompt.length());

        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(KEY_SYSTEM_PROMPT, prompt);
        editor.putString(KEY_TITLE_PROMPT, titlePrompt);
        editor.putBoolean(KEY_AUTO_TITLE_ENABLED, autoTitle);
        editor.putBoolean(KEY_QUICK_SCAN_ENABLED, quickScanSwitch.isChecked());
        editor.putBoolean(KEY_LOG_ENABLED, logEnabled);
        editor.putBoolean(KEY_REALTIME_RENDER, realtimeRenderSwitch.isChecked());
        editor.putBoolean(KEY_STREAM_ENABLED, streamSwitch.isChecked());

        int pos = titleModelSpinner.getSelectedItemPosition();
        if (pos >= 0) {
            ConfigManager cm = ConfigManager.getInstance();
            List<ModelInfo> models = cm.getModels();
            if (pos < models.size()) {
                editor.putString(KEY_TITLE_MODEL, models.get(pos).name);
                LogUtil.d(TAG, "saveSettings: titleModel=%s", models.get(pos).name);
            } else {
                LogUtil.w(TAG, "saveSettings: title model pos=%d out of range (%d)", pos, models.size());
            }
        }
        editor.commit();
        LogUtil.ENABLED = logEnabled;
        LogUtil.i(TAG, "saveSettings: prefs committed, logging %s", logEnabled ? "enabled" : "DISABLED");

        Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void queryBalance() {
        ConfigManager configManager = ConfigManager.getInstance();
        final ProviderInfo dsProvider = configManager.getProvider("DeepSeek");
        if (dsProvider == null || dsProvider.apiKey.isEmpty()) {
            LogUtil.w(TAG, "queryBalance aborted: DeepSeek provider=%s", dsProvider == null ? "null" : "empty key");
            Toast.makeText(this, "未配置 DeepSeek API Key", Toast.LENGTH_SHORT).show();
            return;
        }
        LogUtil.i(TAG, "queryBalance: provider=%s url=%s keyLen=%d", dsProvider.name, dsProvider.apiUrl, dsProvider.apiKey.length());

        balanceButton.setEnabled(false);
        balanceButton.setText("查询中...");

        new Thread(new Runnable() {
            public void run() {
                final String result = ApiClient.queryBalance(dsProvider);
                runOnUiThread(new Runnable() {
                    public void run() {
                        balanceButton.setEnabled(true);
                        balanceButton.setText("查询 DeepSeek 余额");

                        if (result == null) {
                            LogUtil.w(TAG, "queryBalance: result is null");
                            Toast.makeText(SettingsActivity.this, "查询失败", Toast.LENGTH_SHORT).show();
                            return;
                        }

                        try {
                            JSONObject json = new JSONObject(result);
                            StringBuilder sb = new StringBuilder();
                            sb.append("账户可用: ").append(json.optBoolean("is_available", false) ? "是" : "否").append("\n\n");

                            org.json.JSONArray infos = json.optJSONArray("balance_infos");
                            if (infos != null && infos.length() > 0) {
                                JSONObject info = infos.getJSONObject(0);
                                sb.append("货币: ").append(info.optString("currency", "CNY")).append("\n");
                                sb.append("总余额: ").append(info.optString("total_balance", "0")).append("\n");
                                sb.append("充值余额: ").append(info.optString("topped_up_balance", "0")).append("\n");
                                sb.append("赠送余额: ").append(info.optString("granted_balance", "0")).append("\n");
                            }
                            LogUtil.i(TAG, "queryBalance OK: %s", sb.toString().replace("\n", " | "));

                            new AlertDialog.Builder(SettingsActivity.this)
                                    .setTitle("DeepSeek 余额")
                                    .setMessage(sb.toString())
                                    .setPositiveButton("确定", null)
                                    .show();
                        } catch (Exception e) {
                            LogUtil.e(TAG, "queryBalance parse failed: " + e.getMessage(), e);
                            Toast.makeText(SettingsActivity.this, "解析余额失败", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        LogUtil.d(TAG, "onResume");
        refreshTitleModelSpinner();
    }

    public static String getSystemPrompt(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, 0);
        return prefs.getString(KEY_SYSTEM_PROMPT, "");
    }

    public static boolean isAutoTitleEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, 0);
        return prefs.getBoolean(KEY_AUTO_TITLE_ENABLED, true);
    }

    public static boolean isQuickScanEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, 0);
        return prefs.getBoolean(KEY_QUICK_SCAN_ENABLED, false);
    }

    public static boolean isLogEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, 0);
        return prefs.getBoolean(KEY_LOG_ENABLED, true);
    }

    /** Re-render markdown/LaTeX while the reply streams. Off by default (heavier). */
    public static boolean isRealtimeRenderEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, 0);
        return prefs.getBoolean(KEY_REALTIME_RENDER, false);
    }

    /** Use stream=true on the completion request. On by default. */
    public static boolean isStreamEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, 0);
        return prefs.getBoolean(KEY_STREAM_ENABLED, true);
    }

    public static String getTitleModel(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, 0);
        return prefs.getString(KEY_TITLE_MODEL, DEFAULT_TITLE_MODEL);
    }

    public static String getTitlePrompt(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, 0);
        return prefs.getString(KEY_TITLE_PROMPT, DEFAULT_TITLE_PROMPT);
    }

    // ---- Presets ----

    private void loadPresets() {
        presetList = new ArrayList<>();
        try {
            String json = prefs.getString(KEY_PRESETS, "[]");
            org.json.JSONArray arr = new org.json.JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject obj = arr.getJSONObject(i);
                presetList.add(new String[]{obj.getString("name"), obj.getString("prompt")});
            }
            LogUtil.d(TAG, "loadPresets: %d presets", presetList.size());
        } catch (Exception e) {
            LogUtil.e(TAG, "loadPresets parse failed: " + e.getMessage(), e);
            presetList = new ArrayList<>();
        }
    }

    private void savePresets() {
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (String[] p : presetList) {
                org.json.JSONObject obj = new org.json.JSONObject();
                obj.put("name", p[0]);
                obj.put("prompt", p[1]);
                arr.put(obj);
            }
            boolean ok = prefs.edit().putString(KEY_PRESETS, arr.toString()).commit();
            LogUtil.d(TAG, "savePresets: %d presets ok=%s", presetList.size(), ok);
        } catch (Exception e) {
            LogUtil.e(TAG, "savePresets failed: " + e.getMessage(), e);
        }
    }

    private void refreshPresetSpinner() {
        loadPresets();
        List<String> names = new ArrayList<>();
        names.add("(不选择)");
        for (String[] p : presetList) names.add(p[0]);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        presetSpinner.setAdapter(adapter);
        presetSpinner.setSelection(0);
        LogUtil.d(TAG, "refreshPresetSpinner: %d presets", presetList.size());
    }

    private void saveCurrentAsPreset() {
        final String prompt = systemPromptEdit.getText().toString().trim();
        if (prompt.isEmpty()) {
            LogUtil.w(TAG, "saveCurrentAsPreset aborted: prompt empty");
            Toast.makeText(this, "系统提示词为空", Toast.LENGTH_SHORT).show();
            return;
        }
        LogUtil.d(TAG, "saveCurrentAsPreset: promptLen=%d", prompt.length());
        final EditText input = new EditText(this);
        input.setHint("预设名称");
        input.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle("保存为预设")
                .setView(input)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String name = input.getText().toString().trim();
                        if (name.isEmpty()) {
                            LogUtil.w(TAG, "saveCurrentAsPreset aborted: name empty");
                            Toast.makeText(SettingsActivity.this, "名称不能为空", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        for (String[] p : presetList) {
                            if (p[0].equals(name)) {
                                LogUtil.w(TAG, "saveCurrentAsPreset aborted: duplicate name '%s'", name);
                                Toast.makeText(SettingsActivity.this, "名称已存在", Toast.LENGTH_SHORT).show();
                                return;
                            }
                        }
                        if (presetList.size() >= 20) {
                            LogUtil.w(TAG, "saveCurrentAsPreset aborted: limit reached (%d)", presetList.size());
                            Toast.makeText(SettingsActivity.this, "最多20个预设", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        LogUtil.i(TAG, "saveCurrentAsPreset: adding '%s'", name);
                        presetList.add(new String[]{name, prompt});
                        savePresets();
                        refreshPresetSpinner();
                        Toast.makeText(SettingsActivity.this, "已保存", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showManagePresetsDialog() {
        if (presetList.isEmpty()) {
            LogUtil.d(TAG, "showManagePresetsDialog: no presets");
            Toast.makeText(this, "暂无预设", Toast.LENGTH_SHORT).show();
            return;
        }
        LogUtil.d(TAG, "showManagePresetsDialog: %d presets", presetList.size());
        final String[] names = new String[presetList.size()];
        for (int i = 0; i < presetList.size(); i++)
            names[i] = presetList.get(i)[0] + "  — " + (presetList.get(i)[1].length() > 15
                ? presetList.get(i)[1].substring(0, 15) + "..." : presetList.get(i)[1]);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("管理预设（点击选择，长按编辑/删除）");
        android.widget.ListView lv = new android.widget.ListView(this);
        lv.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names));
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                LogUtil.d(TAG, "preset clicked: pos=%d name=%s", pos, presetList.get(pos)[0]);
                presetSpinner.setSelection(pos + 1);
                systemPromptEdit.setText(presetList.get(pos)[1]);
                if (manageDialog != null) manageDialog.dismiss();
                Toast.makeText(SettingsActivity.this, "已选择: " + presetList.get(pos)[0], Toast.LENGTH_SHORT).show();
            }
        });
        lv.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> p, View v, final int pos, long id) {
                new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle(presetList.get(pos)[0])
                        .setItems(new String[]{"修改", "删除"}, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                if (w == 0) {
                                    LogUtil.d(TAG, "preset long-press action: edit pos=%d", pos);
                                    if (manageDialog != null) manageDialog.dismiss();
                                    showEditPresetDialog(pos);
                                } else {
                                    LogUtil.i(TAG, "preset long-press action: delete pos=%d name=%s", pos, presetList.get(pos)[0]);
                                    presetList.remove(pos);
                                    savePresets();
                                    refreshPresetSpinner();
                                    if (manageDialog != null) manageDialog.dismiss();
                                    if (!presetList.isEmpty()) showManagePresetsDialog();
                                    Toast.makeText(SettingsActivity.this, "已删除", Toast.LENGTH_SHORT).show();
                                }
                            }
                        }).show();
                return true;
            }
        });
        builder.setView(lv);
        builder.setPositiveButton("新建", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) {
                if (manageDialog != null) manageDialog.dismiss();
                showEditPresetDialog(-1);
            }
        });
        builder.setNegativeButton("关闭", null);
        manageDialog = builder.show();
    }

    private void showEditPresetDialog(final int pos) {
        final boolean isNew = pos < 0;
        LogUtil.d(TAG, "showEditPresetDialog: pos=%d isNew=%s", pos, isNew);
        final String oldName = isNew ? "" : presetList.get(pos)[0];
        final String oldPrompt = isNew ? "" : presetList.get(pos)[1];

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(16, 16, 16, 16);

        final EditText nameEdit = new EditText(this);
        nameEdit.setHint("预设名称");
        nameEdit.setSingleLine(true);
        if (!isNew) nameEdit.setText(oldName);
        layout.addView(nameEdit);

        final EditText promptEdit = new EditText(this);
        promptEdit.setHint("提示词内容");
        promptEdit.setMinLines(3);
        promptEdit.setMaxLines(6);
        promptEdit.setGravity(android.view.Gravity.TOP);
        promptEdit.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        if (!isNew) promptEdit.setText(oldPrompt);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 8;
        promptEdit.setLayoutParams(lp);
        layout.addView(promptEdit);

        new AlertDialog.Builder(this)
                .setTitle(isNew ? "新建预设" : "修改预设")
                .setView(layout)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String name = nameEdit.getText().toString().trim();
                        String prompt = promptEdit.getText().toString().trim();
                        if (name.isEmpty() || prompt.isEmpty()) {
                            LogUtil.w(TAG, "editPreset aborted: empty name or prompt");
                            Toast.makeText(SettingsActivity.this, "名称和提示词不能为空", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (isNew) {
                            for (String[] p : presetList) {
                                if (p[0].equals(name)) {
                                    LogUtil.w(TAG, "editPreset aborted: duplicate name '%s'", name);
                                    Toast.makeText(SettingsActivity.this, "名称已存在", Toast.LENGTH_SHORT).show();
                                    return;
                                }
                            }
                            if (presetList.size() >= 20) {
                                LogUtil.w(TAG, "editPreset aborted: limit reached (%d)", presetList.size());
                                Toast.makeText(SettingsActivity.this, "最多20个预设", Toast.LENGTH_SHORT).show();
                                return;
                            }
                            LogUtil.i(TAG, "editPreset: create '%s' (%d chars)", name, prompt.length());
                            presetList.add(new String[]{name, prompt});
                        } else {
                            if (!name.equals(oldName)) {
                                for (String[] p : presetList) {
                                    if (p[0].equals(name)) {
                                        LogUtil.w(TAG, "editPreset aborted: duplicate name '%s'", name);
                                        Toast.makeText(SettingsActivity.this, "名称已存在", Toast.LENGTH_SHORT).show();
                                        return;
                                    }
                                }
                            }
                            LogUtil.i(TAG, "editPreset: update pos=%d '%s' -> '%s'", pos, oldName, name);
                            presetList.get(pos)[0] = name;
                            presetList.get(pos)[1] = prompt;
                        }
                        savePresets();
                        refreshPresetSpinner();
                        showManagePresetsDialog();
                        Toast.makeText(SettingsActivity.this, "已保存", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
