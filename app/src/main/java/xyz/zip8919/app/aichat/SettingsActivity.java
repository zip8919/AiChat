package xyz.zip8919.app.aichat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.Toast;
import org.json.JSONObject;

public class SettingsActivity extends Activity {
    private static final String PREFS_NAME = "aichat_settings";
    private static final String KEY_SYSTEM_PROMPT = "system_prompt";
    private static final String KEY_AUTO_TITLE_ENABLED = "auto_title_enabled";

    private SharedPreferences prefs;
    private EditText systemPromptEdit;
    private Switch autoTitleSwitch;
    private Button saveButton;
    private Button cancelButton;
    private Button balanceButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        this.prefs = getSharedPreferences(PREFS_NAME, 0);
        initViews();
        loadSettings();
    }

    private void initViews() {
        systemPromptEdit = (EditText) findViewById(R.id.system_prompt_edit);
        autoTitleSwitch = (Switch) findViewById(R.id.auto_title_switch);
        saveButton = (Button) findViewById(R.id.save_button);
        cancelButton = (Button) findViewById(R.id.cancel_button);
        balanceButton = (Button) findViewById(R.id.balance_button);

        saveButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { saveSettings(); }
        });
        cancelButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        balanceButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { queryBalance(); }
        });
    }

    private void loadSettings() {
        String prompt = prefs.getString(KEY_SYSTEM_PROMPT, "");
        systemPromptEdit.setText(prompt);
        boolean autoTitle = prefs.getBoolean(KEY_AUTO_TITLE_ENABLED, true);
        autoTitleSwitch.setChecked(autoTitle);
    }

    private void saveSettings() {
        String prompt = systemPromptEdit.getText().toString().trim();
        boolean autoTitle = autoTitleSwitch.isChecked();

        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(KEY_SYSTEM_PROMPT, prompt);
        editor.putBoolean(KEY_AUTO_TITLE_ENABLED, autoTitle);
        editor.commit();

        Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void queryBalance() {
        ConfigManager configManager = ConfigManager.getInstance();
        final ProviderInfo dsProvider = configManager.getProvider("DeepSeek");
        if (dsProvider == null || dsProvider.apiKey.isEmpty()) {
            Toast.makeText(this, "未配置 DeepSeek API Key", Toast.LENGTH_SHORT).show();
            return;
        }

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

                            new AlertDialog.Builder(SettingsActivity.this)
                                    .setTitle("DeepSeek 余额")
                                    .setMessage(sb.toString())
                                    .setPositiveButton("确定", null)
                                    .show();
                        } catch (Exception e) {
                            Toast.makeText(SettingsActivity.this, "解析余额失败", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        }).start();
    }

    public static String getSystemPrompt(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, 0);
        return prefs.getString(KEY_SYSTEM_PROMPT, "");
    }

    public static boolean isAutoTitleEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, 0);
        return prefs.getBoolean(KEY_AUTO_TITLE_ENABLED, true);
    }
}
