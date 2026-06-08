package xyz.zip8919.app.aichat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

public class ModelConfigActivity extends Activity {
    private ConfigManager configManager;
    private ListView providerList, modelList;
    private ArrayAdapter<String> providerAdapter, modelAdapter;
    private List<ProviderInfo> providers;
    private List<ModelInfo> models;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_model_config);

        configManager = ConfigManager.getInstance();
        providers = configManager.getProviders();
        models = configManager.getModels();

        providerList = (ListView) findViewById(R.id.provider_list);
        modelList = (ListView) findViewById(R.id.model_list);

        refreshProviderList();
        refreshModelList();

        findViewById(R.id.add_provider_btn).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showProviderDialog(null); }
        });
        findViewById(R.id.add_model_btn).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showModelDialog(null); }
        });

        providerList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                showProviderActionDialog(pos);
            }
        });
        modelList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                showModelActionDialog(pos);
            }
        });
    }

    private void refreshProviderList() {
        List<String> items = new ArrayList<String>();
        for (ProviderInfo p : providers) {
            items.add(p.name + " | " + p.apiUrl);
        }
        if (providerAdapter == null) {
            providerAdapter = new ArrayAdapter<String>(this,
                    android.R.layout.simple_list_item_1, items);
            providerList.setAdapter(providerAdapter);
        } else {
            providerAdapter.clear();
            providerAdapter.addAll(items);
            providerAdapter.notifyDataSetChanged();
        }
    }

    private void refreshModelList() {
        List<String> items = new ArrayList<String>();
        for (ModelInfo m : models) {
            items.add(m.name + " | " + m.provider + (m.supportsThinking ? " | 思考" : ""));
        }
        if (modelAdapter == null) {
            modelAdapter = new ArrayAdapter<String>(this,
                    android.R.layout.simple_list_item_1, items);
            modelList.setAdapter(modelAdapter);
        } else {
            modelAdapter.clear();
            modelAdapter.addAll(items);
            modelAdapter.notifyDataSetChanged();
        }
    }

    // ---- Provider dialogs ----

    private void showProviderActionDialog(final int pos) {
        String[] items = {"编辑", "删除"};
        new AlertDialog.Builder(this)
                .setTitle(providers.get(pos).name)
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if (w == 0) showProviderDialog(pos);
                        else showDeleteProviderDialog(pos);
                    }
                }).setNegativeButton("取消", null).show();
    }

    private void showProviderDialog(final Integer editPos) {
        final ProviderInfo p = editPos != null ? providers.get(editPos) : null;
        final boolean isEdit = p != null;

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 16, 32, 0);

        final EditText nameEt = new EditText(this);
        nameEt.setHint("提供商名称");
        if (p != null) nameEt.setText(p.name);
        layout.addView(nameEt);

        final EditText urlEt = new EditText(this);
        urlEt.setHint("API URL (如 https://api.example.com)");
        if (p != null) urlEt.setText(p.apiUrl);
        layout.addView(urlEt);

        final EditText pathEt = new EditText(this);
        pathEt.setHint("Chat Path (如 /v1/chat/completions)");
        if (p != null) pathEt.setText(p.chatPath);
        layout.addView(pathEt);

        final EditText keyEt = new EditText(this);
        keyEt.setHint("API Key");
        if (p != null) keyEt.setText(p.apiKey);
        layout.addView(keyEt);

        new AlertDialog.Builder(this)
                .setTitle(isEdit ? "编辑提供商" : "添加提供商")
                .setView(layout)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String name = nameEt.getText().toString().trim();
                        String url = urlEt.getText().toString().trim();
                        String path = pathEt.getText().toString().trim();
                        String key = keyEt.getText().toString().trim();
                        if (name.isEmpty() || url.isEmpty()) {
                            Toast.makeText(ModelConfigActivity.this, "名称和URL不能为空", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        ProviderInfo info = isEdit ? p : new ProviderInfo();
                        info.name = name;
                        info.apiUrl = url;
                        info.chatPath = path.isEmpty() ? "/chat/completions" : path;
                        info.apiKey = key;
                        info.thinkingType = "object";
                        info.thinkingParamName = "thinking";
                        if (!isEdit) providers.add(info);
                        configManager.setProviders(providers);
                        configManager.save();
                        refreshProviderList();
                    }
                }).setNegativeButton("取消", null).show();
    }

    private void showDeleteProviderDialog(final int pos) {
        final ProviderInfo p = providers.get(pos);
        new AlertDialog.Builder(this)
                .setTitle("删除提供商")
                .setMessage("确定删除 " + p.name + "？关联的模型也将失效。")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        providers.remove(pos);
                        configManager.setProviders(providers);
                        configManager.save();
                        refreshProviderList();
                        refreshModelList();
                    }
                }).setNegativeButton("取消", null).show();
    }

    // ---- Model dialogs ----

    private void showModelActionDialog(final int pos) {
        String[] items = {"编辑", "删除"};
        new AlertDialog.Builder(this)
                .setTitle(models.get(pos).name)
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if (w == 0) showModelDialog(pos);
                        else showDeleteModelDialog(pos);
                    }
                }).setNegativeButton("取消", null).show();
    }

    private void showModelDialog(final Integer editPos) {
        final ModelInfo m = editPos != null ? models.get(editPos) : null;
        final boolean isEdit = m != null;

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 16, 32, 0);

        final EditText nameEt = new EditText(this);
        nameEt.setHint("模型名称 (如 deepseek-v4-flash)");
        if (m != null) nameEt.setText(m.name);
        layout.addView(nameEt);

        // Provider dropdown as EditText for simplicity
        final EditText provEt = new EditText(this);
        provEt.setHint("提供商名称 (如 DeepSeek)");
        if (m != null) provEt.setText(m.provider);
        layout.addView(provEt);

        new AlertDialog.Builder(this)
                .setTitle(isEdit ? "编辑模型" : "添加模型")
                .setView(layout)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String name = nameEt.getText().toString().trim();
                        String prov = provEt.getText().toString().trim();
                        if (name.isEmpty() || prov.isEmpty()) {
                            Toast.makeText(ModelConfigActivity.this, "名称和提供商不能为空", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        ModelInfo info = isEdit ? m : new ModelInfo();
                        info.name = name;
                        info.provider = prov;
                        info.supportsThinking = true;
                        if (!isEdit) models.add(info);
                        configManager.setModels(models);
                        configManager.save();
                        refreshModelList();
                    }
                }).setNegativeButton("取消", null).show();
    }

    private void showDeleteModelDialog(final int pos) {
        final ModelInfo m = models.get(pos);
        new AlertDialog.Builder(this)
                .setTitle("删除模型")
                .setMessage("确定删除 " + m.name + "？")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        models.remove(pos);
                        configManager.setModels(models);
                        configManager.save();
                        refreshModelList();
                    }
                }).setNegativeButton("取消", null).show();
    }
}
