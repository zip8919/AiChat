package xyz.zip8919.app.aichat;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public class ConfigManager {
    private static ConfigManager instance;
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
        String content = storageManager.loadConfig();
        if (content == null || content.length() == 0) {
            createDefault();
        } else {
            try {
                parse(new JSONObject(content));
            } catch (Exception e) {
                e.printStackTrace();
                createDefault();
            }
        }
    }

    public void save() {
        try {
            JSONObject json = toJson();
            storageManager.saveConfig(json.toString());
        } catch (Exception e) {
            e.printStackTrace();
        }
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

        save();
    }

    private void addModel(String name, String provider, boolean supportsThinking) {
        ModelInfo m = new ModelInfo();
        m.name = name;
        m.provider = provider;
        m.supportsThinking = supportsThinking;
        models.add(m);
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
    }

    // ---- accessors ----

    public List<ProviderInfo> getProviders() { return providers; }

    public ProviderInfo getProvider(String name) {
        for (ProviderInfo p : providers) {
            if (p.name.equals(name)) return p;
        }
        return null;
    }

    public List<ModelInfo> getModels() { return models; }

    public ModelInfo getModel(String name) {
        for (ModelInfo m : models) {
            if (m.name.equals(name)) return m;
        }
        return null;
    }

    public String getDefaultModel() { return defaultModel; }
    public void setDefaultModel(String model) { this.defaultModel = model; }

    public boolean isThinkingEnabled() { return enableThinking; }
    public void setThinkingEnabled(boolean v) { this.enableThinking = v; }

    public String getThinkingLevel() { return thinkingLevel; }
    public void setThinkingLevel(String level) { this.thinkingLevel = level; }

    public void setProviders(List<ProviderInfo> list) { this.providers = list; }
    public void setModels(List<ModelInfo> list) { this.models = list; }
}
