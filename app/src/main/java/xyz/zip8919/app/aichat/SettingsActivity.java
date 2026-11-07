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
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
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
    private static final String KEY_WEB_SEARCH_ENABLED = "web_search_enabled";
    /** 搜索后端：tavily（默认，免费）/ deepseek */
    private static final String KEY_SEARCH_BACKEND = "search_backend";
    /** Tavily 密钥：仅保存在本机，源码与安装包中均不内置。 */
    private static final String KEY_TAVILY_KEY = "tavily_api_key";
    public static final String SEARCH_BACKEND_TAVILY = "tavily";
    public static final String SEARCH_BACKEND_DEEPSEEK = "deepseek";
    /** 单轮 AI 回复内最多实际执行的搜索/抓取请求数（工具轮次本身不设上限）。 */
    private static final String KEY_WEB_SEARCH_MAX_CALLS = "web_search_max_calls_per_round";
    private static final int DEFAULT_WEB_SEARCH_MAX_CALLS = 15;
    private static final int MIN_WEB_SEARCH_MAX_CALLS = 1;
    private static final int MAX_WEB_SEARCH_MAX_CALLS = 50;
    private static final String KEY_LOG_ENABLED = "log_enabled";
    private static final String KEY_REALTIME_RENDER = "realtime_render_enabled";
    private static final String KEY_STREAM_ENABLED = "stream_enabled";
    private static final String KEY_PRESETS = "system_presets";
    /** 是否向模型开放本机 HTTP 请求工具。默认关闭：等于把网络出口交给模型。 */
    private static final String KEY_HTTP_TOOL_ENABLED = "http_tool_enabled";
    /** 允许 http_request 访问环回/私有网段。默认关闭。 */
    private static final String KEY_HTTP_ALLOW_PRIVATE = "http_allow_private";
    /** 关闭 TLS 证书与主机名校验。默认关闭。 */
    private static final String KEY_TRUST_ALL_CERTS = "trust_all_certs";
    // ---- 429 重试策略 ----
    private static final String KEY_RETRY_429_ENABLED = "retry_429_enabled";
    private static final String KEY_RETRY_429_MAX = "retry_429_max";
    private static final String KEY_RETRY_429_BASE_S = "retry_429_base_s";
    private static final String KEY_RETRY_429_BACKOFF = "retry_429_backoff";
    private static final String KEY_RETRY_429_MAX_WAIT_S = "retry_429_max_wait_s";
    private static final int DEFAULT_RETRY_429_MAX = 3;
    private static final int DEFAULT_RETRY_429_BASE_S = 2;
    private static final int DEFAULT_RETRY_429_MAX_WAIT_S = 30;
    private static final int MIN_RETRY_429_MAX = 1;
    private static final int MAX_RETRY_429_MAX = 20;
    private static final int MIN_RETRY_429_BASE_S = 1;
    private static final int MAX_RETRY_429_BASE_S = 30;
    private static final int MIN_RETRY_429_MAX_WAIT_S = 1;
    private static final int MAX_RETRY_429_MAX_WAIT_S = 120;
    /** 代码执行器（JS 运行器）的环境提示词，支持 {android}/{api}/{webview} 等占位符。 */
    private static final String KEY_JS_ENV_PROMPT = "js_env_prompt";
    // ---- 上限与配额（由 Limits 统一读取，键名对外可见）----
    /** 附件正文送模上限，单位字符；0 表示不截断。 */
    static final String KEY_ATTACH_MAX_CHARS = "attach_max_chars";
    /** 附件合计上限，单位「万字符」——直接填 20000000 太长，输入框放不下。 */
    static final String KEY_ATTACH_TOTAL_CHARS = "attach_total_wan_chars";
    static final String KEY_MAX_ATTACHMENTS = "max_attachments";
    static final String KEY_IMAGE_MAX_MB = "image_max_mb";
    static final String KEY_TOOL_MAX_KB = "tool_max_kb";
    static final String KEY_HTTP_TIMEOUT_S = "http_timeout_s";
    static final String KEY_SEARCH_COUNT = "search_max_count";
    static final String KEY_SEARCH_SUMMARY_CHARS = "search_summary_chars";
    static final String KEY_VISION_MAX_PX = "vision_max_px";
    static final String KEY_VISION_MAX_IMAGES = "vision_max_images";
    private static final int DEFAULT_ATTACH_MAX_CHARS = 262144;
    private static final int DEFAULT_ATTACH_TOTAL_WAN = 2000;
    private static final int DEFAULT_MAX_ATTACHMENTS = 5;
    private static final int DEFAULT_IMAGE_MAX_MB = 20;
    private static final int DEFAULT_TOOL_MAX_KB = 32;
    private static final int DEFAULT_HTTP_TIMEOUT_S = 30;
    private static final int DEFAULT_SEARCH_COUNT = 10;
    private static final int DEFAULT_SEARCH_SUMMARY = 1200;
    private static final int DEFAULT_VISION_MAX_PX = 1600;
    private static final int DEFAULT_VISION_MAX_IMAGES = 3;
    private static final String DEFAULT_TITLE_MODEL = "Qwen/Qwen3.5-397B-A17B";
    private static final String DEFAULT_TITLE_PROMPT = "你是一个标题生成助手。根据用户消息生成3-15字标题。只输出标题本身，禁止输出任何其他文字、解释、标点或换行。";
    /**
     * 默认环境提示词。原来硬编码在 ToolsActivity 里写死「Android 4.4 / Chromium 30」，
     * 换台机器就是错的；这里改成占位符，运行时按真实设备填充。
     */
    private static final String DEFAULT_JS_ENV_PROMPT =
            "我在一个 Android {android} (API {api}) 的 WebView 里运行 JavaScript，"
            + "运行环境是 {webview} 内核，不支持 ES6 及以上语法"
            + "（不能用 let/const、箭头函数、模板字符串、class、Promise、"
            + "扩展运算符、async/await、for...of）。"
            + "为避免中文编码问题，请把所有中文文本写成 \\uXXXX 转义形式。"
            + "只输出一个 ```js 代码块，代码用 var 声明变量，可直接运行并输出结果。";

    private SharedPreferences prefs;
    private EditText systemPromptEdit, titlePromptEdit;
    private EditText tavilyKeyEdit;
    private EditText webSearchMaxCallsEdit;
    private EditText jsEnvPromptEdit;
    private Switch autoTitleSwitch, quickScanSwitch, logSwitch, realtimeRenderSwitch, streamSwitch;
    private Switch webSearchSwitch, httpToolSwitch;
    private Switch httpAllowPrivateSwitch, trustAllCertsSwitch;
    /** loadSettings 期间置 true：否则 setChecked 会当成用户操作，弹出风险确认。 */
    private boolean bindingSwitches;
    private Switch retry429Switch, retry429BackoffSwitch;
    private EditText retry429MaxEdit, retry429BaseEdit, retry429MaxWaitEdit;
    private EditText attachMaxCharsEdit, attachTotalEdit, maxAttachmentsEdit, imageMaxMbEdit;
    private EditText toolMaxKbEdit, httpTimeoutEdit, searchCountEdit, searchSummaryEdit;
    private EditText visionMaxPxEdit, visionMaxImagesEdit;
    private Spinner titleModelSpinner, presetSpinner, searchBackendSpinner;
    private Button saveButton, cancelButton, balanceButton, manageModelsButton;
    /** 设置页顶部的存储入口：右侧显示「已用 X」。 */
    private android.widget.TextView storageEntrySize;
    /** 后台扫描进行中，避免反复进出设置页叠起多个线程。 */
    private boolean storageScanning;
    private Button managePresetsButton, savePresetButton;
    private AlertDialog manageDialog;
    private List<String[]> presetList; // [name, prompt]
    private int titleModelMissingPos = -1; // saved 模型不存在时 spinner 停放的位置，-1 表示无此情况
    private boolean titleModelUserPicked;  // 用户是否手动动过标题模型 spinner（尚未保存）
    private String titleModelSignature;    // 上次构建 adapter 时的模型列表快照，用于判断是否需要重建

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        this.prefs = getSharedPreferences(PREFS_NAME, 0);
        LogUtil.i(TAG, "========== onCreate ========== (%s)", LogUtil.thread());
        initViews();
        initGroups();
        initDescDetails();
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
        webSearchSwitch = (Switch) findViewById(R.id.web_search_switch);
        httpToolSwitch = (Switch) findViewById(R.id.http_tool_switch);
        httpAllowPrivateSwitch = (Switch) findViewById(R.id.http_allow_private_switch);
        trustAllCertsSwitch = (Switch) findViewById(R.id.trust_all_certs_switch);
        retry429Switch = (Switch) findViewById(R.id.retry_429_switch);
        retry429BackoffSwitch = (Switch) findViewById(R.id.retry_429_backoff_switch);
        retry429MaxEdit = (EditText) findViewById(R.id.retry_429_max_edit);
        retry429BaseEdit = (EditText) findViewById(R.id.retry_429_base_edit);
        retry429MaxWaitEdit = (EditText) findViewById(R.id.retry_429_max_wait_edit);
        attachMaxCharsEdit = (EditText) findViewById(R.id.attach_max_chars_edit);
        attachTotalEdit = (EditText) findViewById(R.id.attach_total_edit);
        maxAttachmentsEdit = (EditText) findViewById(R.id.max_attachments_edit);
        imageMaxMbEdit = (EditText) findViewById(R.id.image_max_mb_edit);
        toolMaxKbEdit = (EditText) findViewById(R.id.tool_max_kb_edit);
        httpTimeoutEdit = (EditText) findViewById(R.id.http_timeout_edit);
        searchCountEdit = (EditText) findViewById(R.id.search_count_edit);
        searchSummaryEdit = (EditText) findViewById(R.id.search_summary_edit);
        visionMaxPxEdit = (EditText) findViewById(R.id.vision_max_px_edit);
        visionMaxImagesEdit = (EditText) findViewById(R.id.vision_max_images_edit);
        webSearchMaxCallsEdit = (EditText) findViewById(R.id.web_search_max_calls_edit);
        jsEnvPromptEdit = (EditText) findViewById(R.id.js_env_prompt_edit);
        logSwitch = (Switch) findViewById(R.id.log_switch);
        realtimeRenderSwitch = (Switch) findViewById(R.id.realtime_render_switch);
        streamSwitch = (Switch) findViewById(R.id.stream_switch);
        titleModelSpinner = (Spinner) findViewById(R.id.title_model_spinner);
        searchBackendSpinner = (Spinner) findViewById(R.id.search_backend_spinner);
        tavilyKeyEdit = (EditText) findViewById(R.id.tavily_key_edit);
        ArrayAdapter<String> backendAdapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"Tavily（免费）", "DeepSeek"});
        backendAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        searchBackendSpinner.setAdapter(backendAdapter);
        saveButton = (Button) findViewById(R.id.save_button);
        cancelButton = (Button) findViewById(R.id.cancel_button);
        balanceButton = (Button) findViewById(R.id.balance_button);
        manageModelsButton = (Button) findViewById(R.id.manage_models_button);
        storageEntrySize = (android.widget.TextView) findViewById(R.id.storage_entry_size);
        findViewById(R.id.storage_entry).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, StorageActivity.class));
            }
        });
        ((Button) findViewById(R.id.about_button)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, AboutActivity.class));
            }
        });
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
        findViewById(R.id.usage_stats_button).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                LogUtil.d(TAG, "click: usage stats -> StatsActivity");
                startActivity(new Intent(SettingsActivity.this, StatsActivity.class));
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
        // 只在用户真正点过 spinner 时才认为选择被改动（回调时序不可靠，改用触摸事件判定）
        titleModelSpinner.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) titleModelUserPicked = true;
                return false; // 不消费事件，交回 Spinner 自己处理
            }
        });
        // 两个安全开关：打开时弹一次风险确认，取消或按返回键都回滚。
        // 不做静默生效——这两个开关一旦打开，防线就没了，用户必须知道自己在做什么。
        httpAllowPrivateSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (bindingSwitches || !checked) return;
                confirmRisk("允许访问内网地址",
                        "开启后，AI 可以通过 http_request 访问 192.168.x.x、127.0.0.1、"
                                + "169.254.169.254 等地址。\n\n"
                                + "风险：路由器管理页、NAS 文件、云主机元数据凭证都可能被读到，"
                                + "而请求结果会原样发给模型服务商。",
                        b);
            }
        });
        trustAllCertsSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (bindingSwitches || !checked) return;
                confirmRisk("信任所有 HTTPS 证书",
                        "开启后不再校验证书与主机名，任何中间人都能解密你与服务器之间的流量，"
                                + "包括 API Key 和全部对话内容。\n\n"
                                + "仅建议在访问自签证书的内网服务时临时开启。",
                        b);
            }
        });

        LogUtil.d(TAG, "initViews done");
    }

    /**
     * 危险开关的二次确认。
     *
     * <p>不用「先弹框再返回 boolean」的写法：{@code show()} 是异步的，方法返回时
     * 用户还没点，拿到的永远是默认值。这里反过来——默认保持勾选，只有明确取消才回滚。
     */
    private void confirmRisk(String title, String message, final CompoundButton target) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("我已了解，继续开启", null)
                .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        target.setChecked(false);
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    public void onCancel(DialogInterface d) {
                        target.setChecked(false);
                    }
                })
                .show();
    }

    /**
     * 六个分组的折叠状态。
     * <p>默认只展开 A（常用）与 F（数据与关于）：首屏 = 4 行常用 + 6 个组标题 + 4 行导航，
     * 258×960dp 上一屏能看全，折叠组只留「组标题 + 项数」。
     * 状态写进 prefs，下次进来还是用户上次的样子。
     */
    private static final String KEY_GROUP_COLLAPSED = "ui_group_collapsed_";
    private static final String[] GROUP_KEYS = {"A", "B", "C", "D", "E", "F"};
    private static final boolean[] GROUP_DEFAULT_OPEN = {true, false, false, false, false, true};
    private static final int[] GROUP_HEADER_IDS = {
            R.id.group_header_A, R.id.group_header_B, R.id.group_header_C,
            R.id.group_header_D, R.id.group_header_E, R.id.group_header_F};
    private static final int[] GROUP_BODY_IDS = {
            R.id.group_body_A, R.id.group_body_B, R.id.group_body_C,
            R.id.group_body_D, R.id.group_body_E, R.id.group_body_F};
    private static final int[] GROUP_ARROW_IDS = {
            R.id.group_arrow_A, R.id.group_arrow_B, R.id.group_arrow_C,
            R.id.group_arrow_D, R.id.group_arrow_E, R.id.group_arrow_F};

    private void initGroups() {
        for (int i = 0; i < GROUP_KEYS.length; i++) {
            final String groupKey = GROUP_KEYS[i];
            final View body = findViewById(GROUP_BODY_IDS[i]);
            final android.widget.TextView arrow =
                    (android.widget.TextView) findViewById(GROUP_ARROW_IDS[i]);
            boolean open = !prefs.getBoolean(KEY_GROUP_COLLAPSED + groupKey,
                    !GROUP_DEFAULT_OPEN[i]);
            applyGroupState(body, arrow, open);
            View header = findViewById(GROUP_HEADER_IDS[i]);
            header.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean nowOpen = body.getVisibility() != View.VISIBLE;
                    applyGroupState(body, arrow, nowOpen);
                    prefs.edit().putBoolean(KEY_GROUP_COLLAPSED + groupKey, !nowOpen).apply();
                    LogUtil.d(TAG, "group %s -> %s", groupKey, nowOpen ? "open" : "closed");
                }
            });
        }
    }

    private static void applyGroupState(View body, android.widget.TextView arrow, boolean open) {
        body.setVisibility(open ? View.VISIBLE : View.GONE);
        arrow.setText(open ? "▾" : "▸");
    }

    /**
     * 说明文字：主页面只留一句 ≤24 字的摘要，全文放在 {@code android:tag} 里。
     * <p>258dp 宽下 12sp 一行 12 字、两行是上限；把 90 字的风险说明直接铺在开关下面，
     * 会把 6 组折叠省下来的高度又吃掉。点摘要看全文，是这次改动里观感提升最直接的一条。
     */
    private static final int[] DESC_IDS = {
            R.id.desc_web_search, R.id.desc_search_backend, R.id.desc_tavily,
            R.id.desc_tool_calls, R.id.desc_http_tool, R.id.desc_http_private,
            R.id.desc_certs, R.id.desc_retry429, R.id.desc_quota};

    private void initDescDetails() {
        for (int i = 0; i < DESC_IDS.length; i++) {
            android.widget.TextView tv = (android.widget.TextView) findViewById(DESC_IDS[i]);
            if (tv == null) continue;
            tv.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Object detail = v.getTag();
                    if (detail == null) return;
                    new AlertDialog.Builder(SettingsActivity.this)
                            .setTitle("说明")
                            .setMessage(detail.toString())
                            .setPositiveButton("知道了", null)
                            .show();
                }
            });
        }
    }

    private void loadSettings() {
        systemPromptEdit.setText(prefs.getString(KEY_SYSTEM_PROMPT, ""));
        titlePromptEdit.setText(prefs.getString(KEY_TITLE_PROMPT, DEFAULT_TITLE_PROMPT));
        autoTitleSwitch.setChecked(prefs.getBoolean(KEY_AUTO_TITLE_ENABLED, true));
        quickScanSwitch.setChecked(prefs.getBoolean(KEY_QUICK_SCAN_ENABLED, false));
        webSearchSwitch.setChecked(prefs.getBoolean(KEY_WEB_SEARCH_ENABLED, true));
        bindingSwitches = true;
        httpToolSwitch.setChecked(prefs.getBoolean(KEY_HTTP_TOOL_ENABLED, false));
        httpAllowPrivateSwitch.setChecked(prefs.getBoolean(KEY_HTTP_ALLOW_PRIVATE, false));
        trustAllCertsSwitch.setChecked(prefs.getBoolean(KEY_TRUST_ALL_CERTS, false));
        bindingSwitches = false;
        retry429Switch.setChecked(isRetry429Enabled(this));
        retry429BackoffSwitch.setChecked(isRetry429Backoff(this));
        retry429MaxEdit.setText(String.valueOf(getRetry429MaxAttempts(this)));
        retry429BaseEdit.setText(String.valueOf(getRetry429BaseSeconds(this)));
        retry429MaxWaitEdit.setText(String.valueOf(getRetry429MaxWaitSeconds(this)));
        searchBackendSpinner.setSelection(
                SEARCH_BACKEND_DEEPSEEK.equals(getSearchBackend(this)) ? 1 : 0);
        tavilyKeyEdit.setText(getTavilyKey(this));
        webSearchMaxCallsEdit.setText(String.valueOf(getWebSearchMaxCallsPerRound(this)));
        // 上限与配额：全部按「实际生效值」回显，而不是回显用户上次输入的原文。
        // 上次可能填了越界值被夹住，回显原文会让人以为那个值已经生效。
        attachMaxCharsEdit.setText(String.valueOf(prefs.getInt(
                KEY_ATTACH_MAX_CHARS, DEFAULT_ATTACH_MAX_CHARS)));
        attachTotalEdit.setText(String.valueOf(prefs.getInt(
                KEY_ATTACH_TOTAL_CHARS, DEFAULT_ATTACH_TOTAL_WAN)));
        maxAttachmentsEdit.setText(String.valueOf(prefs.getInt(
                KEY_MAX_ATTACHMENTS, DEFAULT_MAX_ATTACHMENTS)));
        imageMaxMbEdit.setText(String.valueOf(prefs.getInt(
                KEY_IMAGE_MAX_MB, DEFAULT_IMAGE_MAX_MB)));
        toolMaxKbEdit.setText(String.valueOf(prefs.getInt(
                KEY_TOOL_MAX_KB, DEFAULT_TOOL_MAX_KB)));
        httpTimeoutEdit.setText(String.valueOf(prefs.getInt(
                KEY_HTTP_TIMEOUT_S, DEFAULT_HTTP_TIMEOUT_S)));
        searchCountEdit.setText(String.valueOf(prefs.getInt(
                KEY_SEARCH_COUNT, DEFAULT_SEARCH_COUNT)));
        searchSummaryEdit.setText(String.valueOf(prefs.getInt(
                KEY_SEARCH_SUMMARY_CHARS, DEFAULT_SEARCH_SUMMARY)));
        visionMaxPxEdit.setText(String.valueOf(prefs.getInt(
                KEY_VISION_MAX_PX, DEFAULT_VISION_MAX_PX)));
        visionMaxImagesEdit.setText(String.valueOf(prefs.getInt(
                KEY_VISION_MAX_IMAGES, DEFAULT_VISION_MAX_IMAGES)));
        jsEnvPromptEdit.setText(prefs.getString(KEY_JS_ENV_PROMPT, DEFAULT_JS_ENV_PROMPT));
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

        StringBuilder sig = new StringBuilder();
        for (int i = 0; i < models.size(); i++) {
            sig.append(models.get(i).name).append("::").append(models.get(i).provider).append(";");
        }
        String signature = sig.toString();
        if (signature.equals(titleModelSignature)) {
            // 模型列表没变（例如从「管理模型」返回触发的 onResume）：
            // 保留 spinner 上用户尚未保存的选择，不要重建 adapter 把它冲掉
            LogUtil.d(TAG, "refreshTitleModelSpinner: model list unchanged, keep selection");
            return;
        }
        titleModelSignature = signature;

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
                titleModelMissingPos = -1;
                LogUtil.d(TAG, "refreshTitleModelSpinner: selected pos=%d", i);
                return;
            }
        }
        // saved 模型已不存在：仅把 spinner 停在首项用于展示，记下兜底位置供保存路径区分「用户没动过」
        titleModelMissingPos = models.isEmpty() ? -1 : 0;
        LogUtil.w(TAG, "refreshTitleModelSpinner: saved model '%s' not found", saved);
    }

    /**
     * 保存入口：先检查系统提示词有没有改，改了就先把代价讲清楚再落盘。
     *
     * <p>系统提示词拼在每次请求的最前面，服务端的前缀缓存按「从头开始相同的部分」
     * 计算命中。改一个字，从那个字开始往后的全部内容都要重算——不是「多花一点」，
     * 而是整段上下文按未命中计费。用户有权知道这件事再决定，而不是月底看账单。
     */
    private void saveSettings() {
        String current = systemPromptEdit.getText().toString().trim();
        String saved = prefs.getString(KEY_SYSTEM_PROMPT, "");
        if (current.equals(saved == null ? "" : saved.trim())) {
            doSaveSettings();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("系统提示词已修改")
                .setMessage("系统提示词位于每次请求的最前面。改动会让服务端的前缀缓存"
                        + "从改动位置起失效——下一次请求的整段上下文按未命中计费，"
                        + "之后随着对话继续才会逐步恢复。\n\n"
                        + "如果只是想让模型知道当前时间，优先用最后一条消息里的时间提示，"
                        + "而不是把时间写进系统提示词。")
                .setPositiveButton("保存并接受", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        doSaveSettings();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doSaveSettings() {
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
        editor.putBoolean(KEY_WEB_SEARCH_ENABLED, webSearchSwitch.isChecked());
        editor.putBoolean(KEY_HTTP_TOOL_ENABLED, httpToolSwitch.isChecked());
        editor.putBoolean(KEY_HTTP_ALLOW_PRIVATE, httpAllowPrivateSwitch.isChecked());
        editor.putBoolean(KEY_TRUST_ALL_CERTS, trustAllCertsSwitch.isChecked());
        editor.putBoolean(KEY_RETRY_429_ENABLED, retry429Switch.isChecked());
        editor.putBoolean(KEY_RETRY_429_BACKOFF, retry429BackoffSwitch.isChecked());
        editor.putInt(KEY_RETRY_429_MAX, clampInt(retry429MaxEdit.getText().toString(),
                DEFAULT_RETRY_429_MAX, MIN_RETRY_429_MAX, MAX_RETRY_429_MAX));
        editor.putInt(KEY_RETRY_429_BASE_S, clampInt(retry429BaseEdit.getText().toString(),
                DEFAULT_RETRY_429_BASE_S, MIN_RETRY_429_BASE_S, MAX_RETRY_429_BASE_S));
        editor.putInt(KEY_RETRY_429_MAX_WAIT_S, clampInt(retry429MaxWaitEdit.getText().toString(),
                DEFAULT_RETRY_429_MAX_WAIT_S, MIN_RETRY_429_MAX_WAIT_S, MAX_RETRY_429_MAX_WAIT_S));
        editor.putString(KEY_SEARCH_BACKEND,
                searchBackendSpinner.getSelectedItemPosition() == 1
                        ? SEARCH_BACKEND_DEEPSEEK : SEARCH_BACKEND_TAVILY);
        editor.putString(KEY_TAVILY_KEY, tavilyKeyEdit.getText().toString().trim());
        editor.putBoolean(KEY_LOG_ENABLED, logEnabled);
        editor.putBoolean(KEY_REALTIME_RENDER, realtimeRenderSwitch.isChecked());
        editor.putBoolean(KEY_STREAM_ENABLED, streamSwitch.isChecked());

        // 代码执行提示词留空等于恢复默认，避免用户删光后拿到一句空提示
        String jsEnvPrompt = jsEnvPromptEdit.getText().toString().trim();
        if (jsEnvPrompt.length() == 0) jsEnvPrompt = DEFAULT_JS_ENV_PROMPT;
        editor.putString(KEY_JS_ENV_PROMPT, jsEnvPrompt);

        // 每轮工具调用上限：空值 / 非数字 / 越界一律回退，绝不把非法值写进配置
        int maxCalls = DEFAULT_WEB_SEARCH_MAX_CALLS;
        String maxCallsText = webSearchMaxCallsEdit.getText().toString().trim();
        if (maxCallsText.length() > 0) {
            try {
                maxCalls = Integer.parseInt(maxCallsText);
            } catch (NumberFormatException nfe) {
                LogUtil.w(TAG, "saveSettings: invalid maxCalls '%s', fallback to %d",
                        maxCallsText, DEFAULT_WEB_SEARCH_MAX_CALLS);
                maxCalls = DEFAULT_WEB_SEARCH_MAX_CALLS;
            }
        }
        if (maxCalls < MIN_WEB_SEARCH_MAX_CALLS) maxCalls = MIN_WEB_SEARCH_MAX_CALLS;
        if (maxCalls > MAX_WEB_SEARCH_MAX_CALLS) maxCalls = MAX_WEB_SEARCH_MAX_CALLS;
        editor.putInt(KEY_WEB_SEARCH_MAX_CALLS, maxCalls);
        webSearchMaxCallsEdit.setText(String.valueOf(maxCalls));

        // ---- 上限与配额：全部夹到合法区间并回写输入框 ----
        // 回写是必须的：用户填了 999999 的上限，保存后输入框仍显示 999999，
        // 但实际生效的是 4096，下次进来看到两个不同的数只会以为配置丢了。
        int attachMax = clampInt(attachMaxCharsEdit.getText().toString(),
                DEFAULT_ATTACH_MAX_CHARS, 0, 100000000);
        if (attachMax != 0 && attachMax < 1024) attachMax = 1024;
        editor.putInt(KEY_ATTACH_MAX_CHARS, attachMax);
        attachMaxCharsEdit.setText(String.valueOf(attachMax));

        int attachTotal = clampInt(attachTotalEdit.getText().toString(),
                DEFAULT_ATTACH_TOTAL_WAN, 100, 100000);
        editor.putInt(KEY_ATTACH_TOTAL_CHARS, attachTotal);
        attachTotalEdit.setText(String.valueOf(attachTotal));

        int maxFiles = clampInt(maxAttachmentsEdit.getText().toString(),
                DEFAULT_MAX_ATTACHMENTS, 1, 20);
        editor.putInt(KEY_MAX_ATTACHMENTS, maxFiles);
        maxAttachmentsEdit.setText(String.valueOf(maxFiles));

        int imageMb = clampInt(imageMaxMbEdit.getText().toString(),
                DEFAULT_IMAGE_MAX_MB, 1, 100);
        editor.putInt(KEY_IMAGE_MAX_MB, imageMb);
        imageMaxMbEdit.setText(String.valueOf(imageMb));

        int toolKb = clampInt(toolMaxKbEdit.getText().toString(),
                DEFAULT_TOOL_MAX_KB, 4, 1024);
        editor.putInt(KEY_TOOL_MAX_KB, toolKb);
        toolMaxKbEdit.setText(String.valueOf(toolKb));

        int httpTimeout = clampInt(httpTimeoutEdit.getText().toString(),
                DEFAULT_HTTP_TIMEOUT_S, 5, 120);
        editor.putInt(KEY_HTTP_TIMEOUT_S, httpTimeout);
        httpTimeoutEdit.setText(String.valueOf(httpTimeout));

        int searchCount = clampInt(searchCountEdit.getText().toString(),
                DEFAULT_SEARCH_COUNT, 1, 20);
        editor.putInt(KEY_SEARCH_COUNT, searchCount);
        searchCountEdit.setText(String.valueOf(searchCount));

        int searchSummary = clampInt(searchSummaryEdit.getText().toString(),
                DEFAULT_SEARCH_SUMMARY, 200, 8000);
        editor.putInt(KEY_SEARCH_SUMMARY_CHARS, searchSummary);
        searchSummaryEdit.setText(String.valueOf(searchSummary));

        int visionPx = clampInt(visionMaxPxEdit.getText().toString(),
                DEFAULT_VISION_MAX_PX, 512, 4096);
        editor.putInt(KEY_VISION_MAX_PX, visionPx);
        visionMaxPxEdit.setText(String.valueOf(visionPx));

        int visionImages = clampInt(visionMaxImagesEdit.getText().toString(),
                DEFAULT_VISION_MAX_IMAGES, 1, 10);
        editor.putInt(KEY_VISION_MAX_IMAGES, visionImages);
        visionMaxImagesEdit.setText(String.valueOf(visionImages));

        int pos = titleModelSpinner.getSelectedItemPosition();
        if (pos >= 0) {
            ConfigManager cm = ConfigManager.getInstance();
            List<ModelInfo> models = cm.getModels();
            if (pos < models.size()) {
                if (titleModelMissingPos >= 0 && pos == titleModelMissingPos && !titleModelUserPicked) {
                    // saved 模型已不存在且用户没动过 spinner：保持原设置，不静默改写成 models[0]
                    LogUtil.w(TAG, "saveSettings: title model missing, keep '%s'",
                            prefs.getString(KEY_TITLE_MODEL, DEFAULT_TITLE_MODEL));
                } else {
                    editor.putString(KEY_TITLE_MODEL, models.get(pos).name);
                    LogUtil.d(TAG, "saveSettings: titleModel=%s", models.get(pos).name);
                }
            } else {
                LogUtil.w(TAG, "saveSettings: title model pos=%d out of range (%d)", pos, models.size());
            }
        }
        editor.apply();
        // 立刻刷新运行期上限：用户保存后直接返回聊天，下一条消息就该按新值执行，
        // 不该等到进程重启才生效
        Limits.reload(getApplicationContext());
        LogUtil.ENABLED = logEnabled;
        LogUtil.i(TAG, "saveSettings: prefs committed, logging %s", logEnabled ? "enabled" : "DISABLED");

        Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void queryBalance() {
        ConfigManager configManager = ConfigManager.getInstance();
        // 按 supportsBalance 选提供商，不再按名字硬编码（改名后按钮会永久误报未配置）
        ProviderInfo balanceProvider = null;
        List<ProviderInfo> providers = configManager.getProviders();
        for (int i = 0; i < providers.size(); i++) {
            if (providers.get(i).supportsBalance) { balanceProvider = providers.get(i); break; }
        }
        if (balanceProvider == null) balanceProvider = configManager.getProvider("DeepSeek");
        if (balanceProvider == null || balanceProvider.apiKey == null || balanceProvider.apiKey.isEmpty()) {
            LogUtil.w(TAG, "queryBalance aborted: provider=%s", balanceProvider == null ? "null" : "empty key");
            Toast.makeText(this, "未配置可查询余额的提供商 API Key", Toast.LENGTH_SHORT).show();
            return;
        }
        final ProviderInfo dsProvider = balanceProvider;
        final CharSequence balanceBtnText = balanceButton.getText();
        LogUtil.i(TAG, "queryBalance: provider=%s url=%s keyLen=%d", dsProvider.name, dsProvider.apiUrl, dsProvider.apiKey.length());

        balanceButton.setEnabled(false);
        balanceButton.setText("查询中...");

        new Thread(new Runnable() {
            public void run() {
                final String result = ApiClient.queryBalance(dsProvider);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (isFinishing() || isDestroyed()) return; // Activity 已结束，show() 会抛 BadTokenException
                        balanceButton.setEnabled(true);
                        balanceButton.setText(balanceBtnText);

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
                                    .setTitle(dsProvider.name + " 余额")
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
        refreshStorageEntry();
    }

    /**
     * 刷新存储入口右侧的「已用 X」。
     *
     * <p>有缓存直接用——这个数字只是为了让人知道「大概多少」，为了它每次进设置都扫一遍
     * 几十 MB 的目录不值得。没有缓存时后台扫一次，扫完再填，绝不在主线程碰磁盘。
     */
    private void refreshStorageEntry() {
        if (storageEntrySize == null) return;
        StorageReport cached = StorageActivity.cachedReport();
        if (cached != null) {
            storageEntrySize.setText("已用 " + StorageReport.size(cached.usedBytes()));
            return;
        }
        // 反复进出设置页时避免叠起多个扫描线程
        if (storageScanning) return;
        storageScanning = true;
        storageEntrySize.setText("");
        new Thread(new Runnable() {
            public void run() {
                final StorageReport r = StorageScanner.scan(getApplicationContext());
                final String text = "已用 " + StorageReport.size(r.usedBytes());
                runOnUiThread(new Runnable() {
                    public void run() {
                        storageScanning = false;
                        if (isFinishing() || storageEntrySize == null) return;
                        storageEntrySize.setText(text);
                    }
                });
            }
        }, "settings-storage").start();
    }

    /**
     * 系统提示词，占位符已替换成当前设备的真实值。
     *
     * <p>与代码执行器提示词共用 {@link SystemInfo#fillPlaceholders}：用户写「现在是 Android
     * {android}，WebView 是 {webview}」，模型拿到的就是这台机器的版本，不用每次手改。
     */
    public static String getSystemPrompt(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, 0);
        return SystemInfo.fillPlaceholders(prefs.getString(KEY_SYSTEM_PROMPT, ""), context);
    }

    public static boolean isAutoTitleEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, 0);
        return prefs.getBoolean(KEY_AUTO_TITLE_ENABLED, true);
    }

    public static boolean isQuickScanEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, 0);
        return prefs.getBoolean(KEY_QUICK_SCAN_ENABLED, false);
    }

    /** Allow the model to call web_search / web_fetch. On by default. */
    public static boolean isWebSearchEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, 0);
        return prefs.getBoolean(KEY_WEB_SEARCH_ENABLED, true);
    }

    /**
     * 当前搜索后端。默认 Tavily（免费），旧配置或异常值一律回落到 Tavily。
     */
    public static String getSearchBackend(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, 0);
        String v = prefs.getString(KEY_SEARCH_BACKEND, SEARCH_BACKEND_TAVILY);
        return SEARCH_BACKEND_DEEPSEEK.equals(v) ? SEARCH_BACKEND_DEEPSEEK : SEARCH_BACKEND_TAVILY;
    }

    public static boolean isTavilySearch(Context context) {
        return SEARCH_BACKEND_TAVILY.equals(getSearchBackend(context));
    }

    /**
     * Tavily 密钥。只从本机 SharedPreferences 读取——源码和安装包里都不内置，
     * 换设备或清除数据后需要重新填写。
     */
    public static String getTavilyKey(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, 0);
        String v = prefs.getString(KEY_TAVILY_KEY, "");
        return v == null ? "" : v.trim();
    }

    /**
     * 单轮 AI 回复内最多实际执行多少个搜索/抓取请求（web_search 与 web_fetch 都计入）。
     * 工具轮次本身没有上限，这个值只约束「一轮里执行几个工具」。
     * 读取时同样做越界回退，防止历史配置或类型异常导致越界值生效。
     */
    public static int getWebSearchMaxCallsPerRound(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, 0);
        int value = DEFAULT_WEB_SEARCH_MAX_CALLS;
        try {
            value = prefs.getInt(KEY_WEB_SEARCH_MAX_CALLS, DEFAULT_WEB_SEARCH_MAX_CALLS);
        } catch (Exception e) {
            // 旧版本若把该键存成 String，getInt 会抛 ClassCastException
            LogUtil.w("SettingsActivity", "maxCalls read failed: %s", e.getMessage());
            try {
                value = Integer.parseInt(
                        prefs.getString(KEY_WEB_SEARCH_MAX_CALLS, String.valueOf(DEFAULT_WEB_SEARCH_MAX_CALLS)));
            } catch (Exception ignored) {
                value = DEFAULT_WEB_SEARCH_MAX_CALLS;
            }
        }
        if (value < MIN_WEB_SEARCH_MAX_CALLS) value = MIN_WEB_SEARCH_MAX_CALLS;
        if (value > MAX_WEB_SEARCH_MAX_CALLS) value = MAX_WEB_SEARCH_MAX_CALLS;
        return value;
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

    // ---- 429 重试策略读取 ----

    public static boolean isRetry429Enabled(Context context) {
        return context.getSharedPreferences(PREFS_NAME, 0)
                .getBoolean(KEY_RETRY_429_ENABLED, true);
    }

    public static boolean isRetry429Backoff(Context context) {
        return context.getSharedPreferences(PREFS_NAME, 0)
                .getBoolean(KEY_RETRY_429_BACKOFF, true);
    }

    public static int getRetry429MaxAttempts(Context context) {
        return clampPref(context, KEY_RETRY_429_MAX, DEFAULT_RETRY_429_MAX,
                MIN_RETRY_429_MAX, MAX_RETRY_429_MAX);
    }

    public static int getRetry429BaseSeconds(Context context) {
        return clampPref(context, KEY_RETRY_429_BASE_S, DEFAULT_RETRY_429_BASE_S,
                MIN_RETRY_429_BASE_S, MAX_RETRY_429_BASE_S);
    }

    public static int getRetry429MaxWaitSeconds(Context context) {
        return clampPref(context, KEY_RETRY_429_MAX_WAIT_S, DEFAULT_RETRY_429_MAX_WAIT_S,
                MIN_RETRY_429_MAX_WAIT_S, MAX_RETRY_429_MAX_WAIT_S);
    }

    private static int clampPref(Context context, String key, int def, int min, int max) {
        int v = context.getSharedPreferences(PREFS_NAME, 0).getInt(key, def);
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    /** 输入框文本转整数并夹取范围；空/非数字一律回默认值。 */
    private static int clampInt(String text, int def, int min, int max) {
        int v = def;
        if (text != null && text.trim().length() > 0) {
            try {
                v = Integer.parseInt(text.trim());
            } catch (NumberFormatException e) {
                v = def;
            }
        }
        if (v < min) v = min;
        if (v > max) v = max;
        return v;
    }

    /** 是否向模型开放本机 HTTP 请求工具（默认关闭）。 */
    public static boolean isHttpAllowPrivateHost(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, 0);
        return prefs.getBoolean(KEY_HTTP_ALLOW_PRIVATE, false);
    }

    public static boolean isTrustAllCerts(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, 0);
        return prefs.getBoolean(KEY_TRUST_ALL_CERTS, false);
    }

    public static boolean isHttpToolEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, 0);
        return prefs.getBoolean(KEY_HTTP_TOOL_ENABLED, false);
    }

    /**
     * 代码执行器的环境提示词，已把 {android}/{api}/{webview} 等占位符换成真实值。
     * 拿不到设置时返回填充过占位符的默认文案，绝不会返回空串。
     */
    public static String getJsEnvPrompt(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, 0);
        String template = prefs.getString(KEY_JS_ENV_PROMPT, DEFAULT_JS_ENV_PROMPT);
        if (template == null || template.trim().length() == 0) {
            template = DEFAULT_JS_ENV_PROMPT;
        }
        return SystemInfo.fillPlaceholders(template, context);
    }

    /** 未填充占位符的原始模板，设置页回填用。 */
    public static String getJsEnvPromptTemplate(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, 0);
        String v = prefs.getString(KEY_JS_ENV_PROMPT, DEFAULT_JS_ENV_PROMPT);
        return v == null || v.trim().length() == 0 ? DEFAULT_JS_ENV_PROMPT : v;
    }

    public static String getTitleModel(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, 0);
        return prefs.getString(KEY_TITLE_MODEL, DEFAULT_TITLE_MODEL);
    }

    /**
     * 标题生成提示词，已把 {@code {android}} / {@code {ua}} 等占位符换成真实值。
     *
     * <p>与 {@link #getSystemPrompt} 同一套规则。注意调用方必须在<b>主线程</b>调用：
     * {@code {ua}} 首次取值要初始化 WebView 内核，WebView 只认主线程。
     */
    public static String getTitlePrompt(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, 0);
        return SystemInfo.fillPlaceholders(
                prefs.getString(KEY_TITLE_PROMPT, DEFAULT_TITLE_PROMPT), context);
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
            prefs.edit().putString(KEY_PRESETS, arr.toString()).apply();
            LogUtil.d(TAG, "savePresets: %d presets saved", presetList.size());
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
