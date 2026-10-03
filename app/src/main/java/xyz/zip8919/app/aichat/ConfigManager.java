package xyz.zip8919.app.aichat;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import org.json.JSONArray;
import org.json.JSONObject;

public class ConfigManager {
    private static final String TAG = "ConfigManager";
    private static ConfigManager instance;
    private static ExecutorService saveExecutor;
    private StorageManager storageManager;
    private List<ProviderInfo> providers;
    private List<ModelInfo> models;
    private String defaultModel;
    private boolean enableThinking;
    private String thinkingLevel;  // "off", "low", "medium", "high"

    private ConfigManager() {
        this.storageManager = StorageManager.getInstance();
        this.providers = new ArrayList<ProviderInfo>();
        this.models = new ArrayList<ModelInfo>();
        this.defaultModel = "";
        this.enableThinking = true;
        this.thinkingLevel = "medium";
    }

    public static synchronized ConfigManager getInstance() {
        if (instance == null) {
            instance = new ConfigManager();
        }
        return instance;
    }

    public void load() {
        LogUtil.i(TAG, "load: reading config (%s)", LogUtil.thread());
        String content = storageManager.loadConfig();
        if (content == null || content.length() == 0) {
            // 文件存在却读不出内容属于读取/写入失败，不能重建默认值覆盖，否则用户配置被清空
            String path = storageManager.getConfigPath();
            boolean fileExists = path != null && new File(path).exists();
            if (fileExists) {
                LogUtil.w(TAG, "load: config unreadable/empty, keeping file, using in-memory defaults");
                createDefault();
            } else {
                LogUtil.i(TAG, "load: no config found, creating defaults");
                createDefault();
                save();
            }
        } else {
            LogUtil.d(TAG, "load: config found, %d chars", content.length());
            try {
                parse(new JSONObject(content));
            } catch (Exception e) {
                // 解析失败保留原文件（不 save），避免用默认值静默覆盖用户 API Key 与自定义模型
                LogUtil.e(TAG, "load: parse failed, keeping original file, using in-memory defaults: " + e.getMessage(), e);
                createDefault();
            }
        }
        LogUtil.i(TAG, "load done: providers=%d models=%d default=%s thinking=%s/%s",
                providers.size(), models.size(), defaultModel, enableThinking, thinkingLevel);
    }

    public void save() {
        try {
            final JSONObject json = toJson();
            final String s = json.toString();
            final StorageManager sm = storageManager;
            getSaveExecutor().execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        boolean ok = sm.saveConfig(s);
                        LogUtil.i(TAG, "save: %d chars ok=%s", s.length(), ok);
                    } catch (Exception e) {
                        LogUtil.e(TAG, "save failed: " + e.getMessage(), e);
                    }
                }
            });
        } catch (Exception e) {
            LogUtil.e(TAG, "save failed: " + e.getMessage(), e);
        }
    }

    private static synchronized ExecutorService getSaveExecutor() {
        if (saveExecutor == null) {
            saveExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    return new Thread(r, "aichat-config-save");
                }
            });
        }
        return saveExecutor;
    }

    private void createDefault() {
        providers.clear();
        models.clear();

        String dsKey = BuildConfig.DEEPSEEK_KEY;
        if (dsKey == null) dsKey = "";
        String sfKey = BuildConfig.SILICONFLOW_KEY;
        if (sfKey == null) sfKey = "";

        // DeepSeek provider
        ProviderInfo ds = new ProviderInfo();
        ds.name = "DeepSeek";
        ds.apiKey = dsKey;
        ds.apiUrl = "https://api.deepseek.com";
        ds.chatPath = "/chat/completions";
        ds.thinkingType = "object";
        ds.thinkingParamName = "thinking";
        ds.supportsBalance = true;
        providers.add(ds);

        // SiliconFlow provider
        ProviderInfo sf = new ProviderInfo();
        sf.name = "硅基流动";
        sf.apiKey = sfKey;
        sf.apiUrl = "https://api.siliconflow.cn";
        sf.chatPath = "/v1/chat/completions";
        sf.thinkingType = "boolean";
        sf.thinkingParamName = "enable_thinking";
        sf.supportsBalance = false;
        providers.add(sf);

        // Models
        addModel("deepseek-v4-flash", "DeepSeek", true);
        addModel("deepseek-v4-pro", "DeepSeek", true);
        addModel("Qwen/Qwen3.5-397B-A17B", "硅基流动", true);

        this.defaultModel = "deepseek-v4-flash";
        this.enableThinking = true;
        this.thinkingLevel = "medium";
        LogUtil.i(TAG, "createDefault: providers=%s models=%d default=%s",
                providerNames(), models.size(), defaultModel);
    }

    private String providerNames() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < providers.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(providers.get(i).name);
        }
        return sb.toString();
    }

    private void addModel(String name, String provider, boolean supportsThinking) {
        ModelInfo m = new ModelInfo();
        m.name = name;
        m.provider = provider;
        m.supportsThinking = supportsThinking;
        models.add(m);
        LogUtil.d(TAG, "addModel: %s <- %s (thinking=%s)", name, provider, supportsThinking);
    }

    private JSONObject toJson() throws Exception {
        JSONObject json = new JSONObject();

        JSONArray provArr = new JSONArray();
        for (ProviderInfo p : providers) {
            JSONObject pj = new JSONObject();
            pj.put("name", p.name);
            pj.put("api_key", p.apiKey);
            pj.put("api_url", p.apiUrl);
            pj.put("chat_path", p.chatPath);
            pj.put("thinking_type", p.thinkingType);
            pj.put("thinking_param_name", p.thinkingParamName);
            pj.put("supports_balance", p.supportsBalance);
            provArr.put(pj);
        }
        json.put("providers", provArr);

        JSONArray modelArr = new JSONArray();
        for (ModelInfo m : models) {
            JSONObject mj = new JSONObject();
            mj.put("name", m.name);
            mj.put("provider", m.provider);
            mj.put("supports_thinking", m.supportsThinking);
            modelArr.put(mj);
        }
        json.put("models", modelArr);

        json.put("default_model", this.defaultModel);
        json.put("enable_thinking", this.enableThinking);
        json.put("thinking_level", this.thinkingLevel);

        return json;
    }

    private void parse(JSONObject json) throws Exception {
        providers.clear();
        models.clear();

        JSONArray provArr = json.getJSONArray("providers");
        for (int i = 0; i < provArr.length(); i++) {
            JSONObject pj = provArr.getJSONObject(i);
            ProviderInfo p = new ProviderInfo();
            p.name = pj.getString("name");
            p.apiKey = pj.optString("api_key", "");
            p.apiUrl = pj.getString("api_url");
            p.chatPath = pj.optString("chat_path", "/chat/completions");
            p.thinkingType = pj.optString("thinking_type", "object");
            p.thinkingParamName = pj.optString("thinking_param_name", "thinking");
            p.supportsBalance = pj.optBoolean("supports_balance", false);
            providers.add(p);
        }

        JSONArray modelArr = json.getJSONArray("models");
        for (int i = 0; i < modelArr.length(); i++) {
            JSONObject mj = modelArr.getJSONObject(i);
            ModelInfo m = new ModelInfo();
            m.name = mj.getString("name");
            m.provider = mj.getString("provider");
            m.supportsThinking = mj.optBoolean("supports_thinking", true);
            models.add(m);
        }

        this.defaultModel = json.optString("default_model",
                models.isEmpty() ? "" : models.get(0).name);
        this.enableThinking = json.optBoolean("enable_thinking", true);
        this.thinkingLevel = json.optString("thinking_level", "medium");
        LogUtil.i(TAG, "parse: providers=%d models=%d default=%s thinking=%s/%s",
                providers.size(), models.size(), defaultModel, enableThinking, thinkingLevel);
    }

    // ---- accessors ----

    public List<ProviderInfo> getProviders() { return providers; }

    public ProviderInfo getProvider(String name) {
        for (ProviderInfo p : providers) {
            if (p.name.equals(name)) return p;
        }
        LogUtil.w(TAG, "getProvider('%s') -> null (known: %s)", name, providerNames());
        return null;
    }

    public List<ModelInfo> getModels() { return models; }

    public ModelInfo getModel(String name) {
        for (ModelInfo m : models) {
            if (m.name.equals(name)) return m;
        }
        LogUtil.w(TAG, "getModel('%s') -> null", name);
        return null;
    }

    public String getDefaultModel() { return defaultModel; }
    public void setDefaultModel(String model) {
        LogUtil.d(TAG, "setDefaultModel: %s -> %s", this.defaultModel, model);
        this.defaultModel = model;
    }

    public boolean isThinkingEnabled() { return enableThinking; }
    public void setThinkingEnabled(boolean v) {
        LogUtil.d(TAG, "setThinkingEnabled: %s -> %s", this.enableThinking, v);
        this.enableThinking = v;
    }

    public String getThinkingLevel() { return thinkingLevel; }
    public void setThinkingLevel(String level) {
        LogUtil.d(TAG, "setThinkingLevel: %s -> %s", this.thinkingLevel, level);
        this.thinkingLevel = level;
    }

    public void setProviders(List<ProviderInfo> list) {
        this.providers = list;
        LogUtil.d(TAG, "setProviders: %d entries", list == null ? 0 : list.size());
    }
    public void setModels(List<ModelInfo> list) {
        this.models = list;
        LogUtil.d(TAG, "setModels: %d entries", list == null ? 0 : list.size());
    }
}
