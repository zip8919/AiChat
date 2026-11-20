package xyz.zip8919.app.aichat;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
    private String thinkingLevel;  // "off"，或 ThinkingLevels.ORDER 里的六档之一
    /**
     * 能力探测结论的版本号：每写回一次探测结果就加一。
     *
     * <p>探测在别的页面完成，聊天页的思考档位下拉是按模型结论生成的；
     * 没有这个计数，回到聊天页只能靠「重进应用」才能看到新的档位。
     */
    private final java.util.concurrent.atomic.AtomicInteger probeRevision =
            new java.util.concurrent.atomic.AtomicInteger(0);

    private ConfigManager() {
        this.storageManager = StorageManager.getInstance();
        // 标题生成/搜索工具在后台线程遍历这两个列表，模型配置页在主线程增删：
        // 裸 ArrayList 的增强 for 会抛 ConcurrentModificationException。
        // COW 的迭代器是快照，且外部持有的引用照样能 add/remove，行为不变。
        this.providers = new CopyOnWriteArrayList<ProviderInfo>();
        this.models = new CopyOnWriteArrayList<ModelInfo>();
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

    /** 探测结论文本变化时调用，让按结论生成的下拉列表知道该重建。 */
    void bumpProbeRevision() {
        probeRevision.incrementAndGet();
    }

    int probeRevision() {
        return probeRevision.get();
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

        // 默认不内置 DeepSeek key；商汤日日新的 key 由构建参数 -PsensenovaKey 注入（可为空）
        String snKey = BuildConfig.SENSENOVA_KEY;
        if (snKey == null) snKey = "";

        // DeepSeek provider（不内置 key，用户自行填写）
        ProviderInfo ds = new ProviderInfo();
        ds.name = "DeepSeek";
        ds.apiKey = "";
        ds.apiUrl = "https://api.deepseek.com";
        ds.chatPath = "/chat/completions";
        ds.thinkingType = "object";
        ds.thinkingParamName = "thinking";
        ds.supportsBalance = true;
        providers.add(ds);

        // 商汤日日新 provider
        ProviderInfo sn = new ProviderInfo();
        sn.name = Strings.get(R.string.configmanager_001);
        sn.apiKey = snKey;
        sn.apiUrl = "https://token.sensenova.cn";
        sn.chatPath = "/v1/chat/completions";
        sn.thinkingType = "object";
        sn.thinkingParamName = "thinking";
        sn.supportsBalance = false;
        providers.add(sn);

        // Models
        addModel("deepseek-v4-flash", Strings.get(R.string.configmanager_002), true);
        addModel("deepseek-flash", "DeepSeek", true);
        addModel("deepseek-v4-pro", "DeepSeek", true);

        // DeepSeek Flash 是多模态模型（官方图像理解文档），内置默认开图片输入；
        // 其余模型按需在模型配置里勾选
        for (ModelInfo m : models) {
            if (isVisionDefaultName(m.name)) m.supportsVision = true;
        }

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

    /**
     * DeepSeek Flash 系列是多模态模型，开箱默认开启图片输入
     * （{@code deepseek-flash} / {@code deepseek-v4-flash}，见官方图像理解文档）。
     * 其余模型一律默认关，避免给不支持图像的端点发 content 数组吃 400。
     */
    static boolean isVisionDefaultName(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        return n.contains("deepseek") && n.contains("flash");
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
            pj.put("display_name", p.displayName == null ? "" : p.displayName);
            pj.put("api_key", p.apiKey);
            pj.put("api_url", p.apiUrl);
            pj.put("chat_path", p.chatPath);
            pj.put("thinking_type", p.thinkingType);
            pj.put("thinking_param_name", p.thinkingParamName);
            pj.put("supports_balance", p.supportsBalance);
            // 余额查询改为「每个提供商都可查」之后新增的两项。
            // 老版本读到这两个字段会直接忽略，配置文件双向兼容。
            pj.put("balance_preset", p.balancePreset == null ? "" : p.balancePreset);
            pj.put("balance_path", p.balancePath == null ? "" : p.balancePath);
            provArr.put(pj);
        }
        json.put("providers", provArr);

        JSONArray modelArr = new JSONArray();
        for (ModelInfo m : models) {
            JSONObject mj = new JSONObject();
            mj.put("name", m.name);
            mj.put("display_name", m.displayName == null ? "" : m.displayName);
            mj.put("provider", m.provider);
            mj.put("supports_thinking", m.supportsThinking);
            mj.put("supports_vision", m.supportsVision);
            mj.put("thinking_levels", m.thinkingLevels == null ? "" : m.thinkingLevels);
            mj.put("probed_at", m.probedAt);
            mj.put("levels_unverifiable", m.levelsUnverifiable);
            modelArr.put(mj);
        }
        json.put("models", modelArr);

        json.put("default_model", this.defaultModel);
        json.put("enable_thinking", this.enableThinking);
        json.put("thinking_level", this.thinkingLevel);
        json.put("vision_defaults_v2", true);

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
            // 老配置没有这个字段：语义就是「不显示别名」，界面回退到名字，零迁移成本
            p.displayName = pj.optString("display_name", "");
            p.apiKey = pj.optString("api_key", "");
            p.apiUrl = pj.getString("api_url");
            p.chatPath = pj.optString("chat_path", "/chat/completions");
            p.thinkingType = pj.optString("thinking_type", "object");
            p.thinkingParamName = pj.optString("thinking_param_name", "thinking");
            p.supportsBalance = pj.optBoolean("supports_balance", false);
            // 老配置没有这两项：balancePreset 留空 → 按 deepseek 处理，与升级前一致；
            // balancePath 留空 → 用预设自带的 /user/balance。用户不需要重新配一遍。
            p.balancePreset = pj.optString("balance_preset", "");
            p.balancePath = pj.optString("balance_path", "");
            providers.add(p);
        }

        JSONArray modelArr = json.getJSONArray("models");
        for (int i = 0; i < modelArr.length(); i++) {
            JSONObject mj = modelArr.getJSONObject(i);
            ModelInfo m = new ModelInfo();
            m.name = mj.getString("name");
            m.displayName = mj.optString("display_name", "");
            m.provider = mj.getString("provider");
            m.supportsThinking = mj.optBoolean("supports_thinking", true);
            // 没有这个字段的更老配置：默认 false，行为与 1.9.0 一致（不发图）
            m.supportsVision = mj.optBoolean("supports_vision", false);
            // 老配置没有档位字段：空串表示未探测，请求侧按全局档位下发，行为不变
            m.thinkingLevels = mj.optString("thinking_levels", "");
            m.probedAt = mj.optLong("probed_at", 0L);
            m.levelsUnverifiable = mj.optBoolean("levels_unverifiable", false);
            models.add(m);
        }

        // 一次性迁移（vision_defaults_v2）：DeepSeek Flash 系列多模态默认开。
        // 只在首次读到旧配置时翻一次，之后完全以 supports_vision 为准，用户关了不会再翻回来。
        if (!json.optBoolean("vision_defaults_v2", false)) {
            for (ModelInfo m : models) {
                // 只补没探测过的：探测结果比名字规则可信
                if (m.probedAt == 0 && isVisionDefaultName(m.name)) m.supportsVision = true;
            }
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

    /** 显示名（无别名时回退唯一标识）。找不到返回空串，绝不给 null 让调用方 NPE。 */
    public String providerLabel(String name) {
        ProviderInfo p = getProvider(name);
        return p == null ? (name == null ? "" : name) : p.label();
    }

    /**
     * 检查某个唯一标识是否已被占用（可用于编辑器即时提示）。
     *
     * @param self 编辑态传当前提供商对象，新建传 null；自己不算重名
     */
    public boolean isProviderNameTaken(String name, ProviderInfo self) {
        if (name == null || name.length() == 0) return false;
        for (ProviderInfo p : providers) {
            if (p == self) continue;
            if (name.equals(p.name)) return true;
        }
        return false;
    }

    /**
     * 同一提供商下是否已有同名模型。
     *
     * <p><b>唯一性只到「提供商 + 模型 ID」这一级</b>，不再要求全局唯一：同一个 ID 挂在
     * 两个提供商上（官方与中转站、境内与境外节点）是真实存在的用法，早先的全局校验
     * 直接把它判成重名，用户只能二选一。
     *
     * @param self 编辑态传当前模型对象，新建传 null；自己不算重名
     */
    public boolean isModelNameTaken(String provider, String name, ModelInfo self) {
        if (name == null || name.length() == 0) return false;
        String p = provider == null ? "" : provider;
        for (ModelInfo m : models) {
            if (m == self) continue;
            String mp = m.provider == null ? "" : m.provider;
            if (name.equals(m.name) && p.equals(mp)) return true;
        }
        return false;
    }

    /** 该提供商名下的模型（顺序与配置一致）。 */
    public List<ModelInfo> modelsOf(String providerName) {
        List<ModelInfo> out = new ArrayList<ModelInfo>();
        for (ModelInfo m : models) {
            if (m.provider != null && m.provider.equals(providerName)) out.add(m);
        }
        return out;
    }

    public List<ModelInfo> getModels() { return models; }

    public ModelInfo getModel(String name) {
        return findModel(name);
    }

    /**
     * 按「唯一键」或「模型 ID」查模型。
     *
     * <p>先按 {@link ModelInfo#key()} 精确匹配，再退回按 name 匹配 —— 这一步是为了
     * 兼容老配置：{@code default_model} / {@code selected_model} 在 v1.15.0 之前存的
     * 都是裸模型 ID，升级后第一次读到的仍是旧格式，必须能解析出来。
     *
     * <p>只在没有 {@code \u001f} 时才走 name 兜底，避免一个恰好含分隔符的名字被误判。
     */
    public ModelInfo findModel(String keyOrName) {
        if (keyOrName == null || keyOrName.length() == 0) return null;
        boolean isKey = keyOrName.indexOf('\u001f') >= 0;
        if (isKey) {
            for (ModelInfo m : models) {
                if (keyOrName.equals(m.key())) return m;
            }
        }
        for (ModelInfo m : models) {
            if (m.name != null && m.name.equals(keyOrName)) return m;
        }
        if (isKey) LogUtil.w(TAG, "findModel('%s') -> null", keyOrName);
        return null;
    }

    /** 按「提供商 + 模型 ID」精确查一条，用于同名模型分属不同提供商的场景。 */
    public ModelInfo findModel(String providerName, String modelName) {
        if (modelName == null || modelName.length() == 0) return null;
        for (ModelInfo m : models) {
            if (!modelName.equals(m.name)) continue;
            if (providerName == null) {
                // 没给提供商：只有全局唯一时才算命中，有歧义就返回 null 让调用方自己决定
                if (countByName(modelName) == 1) return m;
                continue;
            }
            if (providerName.equals(m.provider)) return m;
        }
        return null;
    }

    private int countByName(String modelName) {
        int n = 0;
        for (ModelInfo m : models) {
            if (modelName.equals(m.name)) n++;
        }
        return n;
    }

    /** 该模型是不是当前默认模型（按唯一键比较，同名不同厂商不会串）。 */
    public boolean isDefaultModel(ModelInfo m) {
        if (m == null) return false;
        String def = defaultModel;
        if (def == null || def.length() == 0) return false;
        if (def.indexOf('\u001f') >= 0) return def.equals(m.key());
        // 老格式（裸模型 ID）：与 getDefaultModelInfo 同一口径 —— 同名有多条时
        // 只认查出来的那一条，否则列表里会并排出现两个「默认」。
        if (!def.equals(m.name)) return false;
        return m == findModel(def);
    }

    /** 默认模型对应的那一条配置；老格式且同名歧义时返回第一条。 */
    public ModelInfo getDefaultModelInfo() {
        return findModel(defaultModel);
    }

    /** 模型显示名（无别名时回退模型 ID），查不到原样返回。 */
    public String modelLabel(String name) {
        ModelInfo m = getModel(name);
        return m == null ? (name == null ? "" : name) : m.label();
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
