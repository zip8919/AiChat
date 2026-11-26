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
import android.view.ViewTreeObserver;
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

public class SettingsActivity extends ThemedActivity {
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
    /** 流式输出逐字渐显。默认开启：关掉等于回到「整段文字一次跳出来」。 */
    private static final String KEY_SMOOTH_STREAM = "smooth_stream_enabled";
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
    /** 默认标题提示词。存资源 id，静态字段取值会早于 Strings.init。 */
    private static final int DEFAULT_TITLE_PROMPT_ID = R.string.settingsactivity_001;
    /**
     * 默认环境提示词。原来硬编码在 ToolsActivity 里写死「Android 4.4 / Chromium 30」，
     * 换台机器就是错的；这里改成占位符，运行时按真实设备填充。
     */
    private static final int DEFAULT_JS_ENV_PROMPT_ID = R.string.js_env_prompt_default;

    private SharedPreferences prefs;
    private EditText systemPromptEdit, titlePromptEdit;
    private EditText tavilyKeyEdit;
    private EditText webSearchMaxCallsEdit;
    private EditText jsEnvPromptEdit;
    private Switch autoTitleSwitch, quickScanSwitch, logSwitch, realtimeRenderSwitch, streamSwitch;
    private Switch smoothStreamSwitch;
    private Switch webSearchSwitch, httpToolSwitch;
    private Switch httpAllowPrivateSwitch, trustAllCertsSwitch;
    /** loadSettings 期间置 true：否则 setChecked 会当成用户操作，弹出风险确认。 */
    private boolean bindingSwitches;
    private Switch retry429Switch, retry429BackoffSwitch;
    /** 请求诊断记录开关；导出/清空按钮同组。 */
    private Switch diagLogSwitch;
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
    private List<PresetStore.Preset> presetList;
    /** 最近一次应用 / 保存的预设名，用来让下拉框停在对应项上。 */
    private String appliedPresetName = "";
    /** 系统提示词草稿：预设页的「保存当前提示词」读它，省掉「先保存设置」这一步。 */
    public static final String KEY_SYSTEM_PROMPT_DRAFT = "system_prompt_draft";
    private static final int REQ_PRESET_MANAGER = 2001;
    private static final int REQ_FULLSCREEN_SYSTEM = 2002;
    private static final int REQ_FULLSCREEN_TITLE = 2003;
    private static final int REQ_FULLSCREEN_JS = 2004;
    /** 提示词区的滚动容器：多行输入框移动光标时不让整页跟着跳。 */
    private FormScrollView scrollView;
    /** 当前获得焦点的输入框：键盘弹出后按它把整框滚回可见区域。 */
    private EditText focusedEdit;
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
        scrollView = (FormScrollView) findViewById(R.id.settings_scroll);
        systemPromptEdit = (EditText) findViewById(R.id.system_prompt_edit);
        // 清空只动输入框，不写 SharedPreferences：与页面其它设置一样，统一由「保存」落盘。
        // 二次确认不能省——这里常常是几百字，误触一下就没了，且没有撤销。
        findViewById(R.id.clear_system_prompt_button).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (systemPromptEdit.getText().length() == 0) {
                    AcToast.show(SettingsActivity.this, Strings.get(R.string.settingsactivity_008));
                    return;
                }
                final int len = systemPromptEdit.getText().length();
                AcDialog.builder(SettingsActivity.this)
                        .setTitle(Strings.get(R.string.settingsactivity_009))
                        .setMessage(Strings.get(R.string.settingsactivity_010, len))
                        .setPositiveButton(Strings.get(R.string.settingsactivity_012), new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                systemPromptEdit.setText("");
                                AcToast.show(SettingsActivity.this, Strings.get(R.string.settingsactivity_013));
                            }
                        })
                        .setNegativeButton(Strings.get(R.string.settingsactivity_014), null)
                        .show();
            }
        });
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
        diagLogSwitch = (Switch) findViewById(R.id.diag_log_switch);
        // 立即生效，不等「保存」：用户填完设置去复现问题，回来就该有记录。
        diagLogSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (bindingSwitches) return;
                RequestDiag.setEnabled(SettingsActivity.this, checked);
                LogUtil.d(TAG, "diag log -> %s", checked);
            }
        });
        findViewById(R.id.diag_export_button).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { exportRequestDiag(); }
        });
        findViewById(R.id.diag_clear_button).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { confirmClearRequestDiag(); }
        });
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
        bindPromptEditors();
        // 三个提示词输入框的占位符快插胶囊条；集合要与各输入框下方的说明文字一致
        PlaceholderChips.attach(
                (LinearLayout) findViewById(R.id.system_prompt_chips),
                systemPromptEdit, PlaceholderChips.FULL);
        PlaceholderChips.attach(
                (LinearLayout) findViewById(R.id.title_prompt_chips),
                titlePromptEdit, PlaceholderChips.DEVICE_ONLY);
        PlaceholderChips.attach(
                (LinearLayout) findViewById(R.id.js_env_prompt_chips),
                jsEnvPromptEdit, PlaceholderChips.DEVICE_ONLY);
        logSwitch = (Switch) findViewById(R.id.log_switch);
        realtimeRenderSwitch = (Switch) findViewById(R.id.realtime_render_switch);
        streamSwitch = (Switch) findViewById(R.id.stream_switch);
        smoothStreamSwitch = (Switch) findViewById(R.id.smooth_stream_switch);
        titleModelSpinner = (Spinner) findViewById(R.id.title_model_spinner);
        searchBackendSpinner = (Spinner) findViewById(R.id.search_backend_spinner);
        tavilyKeyEdit = (EditText) findViewById(R.id.tavily_key_edit);
        ArrayAdapter<String> backendAdapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item,
                new String[]{Strings.get(R.string.settingsactivity_015), "DeepSeek"});
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
        findViewById(R.id.appearance_entry).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                LogUtil.d(TAG, "click: appearance -> AppearanceActivity");
                startActivity(new Intent(SettingsActivity.this, AppearanceActivity.class));
            }
        });
        refreshAppearanceEntry();
        findViewById(R.id.theme_prompt_entry).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, ThemePromptTemplateActivity.class));
            }
        });
        refreshThemePromptEntry();
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
        // 长按进诊断：逐个接口列调用结果与原始返回。只读、不改任何配置，
        // 用来回答「为什么只显示了一部分」—— 不靠猜字段名
        balanceButton.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                LogUtil.d(TAG, "long click: query balance (diagnostic)");
                queryBalance(true);
                return true;
            }
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
            public void onClick(View v) { LogUtil.d(TAG, "click: manage presets -> PresetManagerActivity"); openPresetManager(); }
        });
        savePresetButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                LogUtil.d(TAG, "click: save as preset");
                promptSavePreset(systemPromptEdit.getText().toString());
            }
        });
        presetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (pos > 0 && presetList != null && pos - 1 < presetList.size()) {
                    PresetStore.Preset p = presetList.get(pos - 1);
                    LogUtil.d(TAG, "preset selected: pos=%d name=%s", pos, p.name);
                    appliedPresetName = p.name;
                    systemPromptEdit.setText(p.prompt == null ? "" : p.prompt);
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
                confirmRisk(Strings.get(R.string.settingsactivity_016),
                        Strings.get(R.string.settingsactivity_076), b);
            }
        });
        trustAllCertsSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (bindingSwitches || !checked) return;
                confirmRisk(Strings.get(R.string.settingsactivity_021),
                        Strings.get(R.string.settingsactivity_077), b);
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
        AcDialog.builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(Strings.get(R.string.settingsactivity_025), null)
                .setNegativeButton(Strings.get(R.string.settingsactivity_026), new DialogInterface.OnClickListener() {
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
            R.id.desc_certs, R.id.desc_retry429, R.id.desc_quota, R.id.desc_smooth_stream};

    private void initDescDetails() {
        for (int i = 0; i < DESC_IDS.length; i++) {
            android.widget.TextView tv = (android.widget.TextView) findViewById(DESC_IDS[i]);
            if (tv == null) continue;
            tv.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Object detail = v.getTag();
                    if (detail == null) return;
                    AcDialog.builder(SettingsActivity.this)
                            .setTitle(Strings.get(R.string.settingsactivity_027))
                            .setMessage(detail.toString())
                            .setPositiveButton(Strings.get(R.string.settingsactivity_028), null)
                            .show();
                }
            });
        }
    }

    private void loadSettings() {
        systemPromptEdit.setText(prefs.getString(KEY_SYSTEM_PROMPT, ""));
        titlePromptEdit.setText(prefs.getString(KEY_TITLE_PROMPT, Strings.get(DEFAULT_TITLE_PROMPT_ID)));
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
        diagLogSwitch.setChecked(RequestDiag.isEnabled(this));
        searchBackendSpinner.setSelection(
                SEARCH_BACKEND_DEEPSEEK.equals(getSearchBackend(this)) ? 1 : 0);
        tavilyKeyEdit.setText(getTavilyKey(this));
        webSearchMaxCallsEdit.setText(String.valueOf(getWebSearchMaxCallsPerRound(this)));
        // 上限与配额：全部按「实际生效值」回显，而不是回显用户上次输入的原文。
        // 上次可能填了越界值被夹住，回显原文会让人以为那个值已经生效。
        attachMaxCharsEdit.setText(String.valueOf(intPref(
                KEY_ATTACH_MAX_CHARS, DEFAULT_ATTACH_MAX_CHARS)));
        attachTotalEdit.setText(String.valueOf(intPref(
                KEY_ATTACH_TOTAL_CHARS, DEFAULT_ATTACH_TOTAL_WAN)));
        maxAttachmentsEdit.setText(String.valueOf(intPref(
                KEY_MAX_ATTACHMENTS, DEFAULT_MAX_ATTACHMENTS)));
        imageMaxMbEdit.setText(String.valueOf(intPref(
                KEY_IMAGE_MAX_MB, DEFAULT_IMAGE_MAX_MB)));
        toolMaxKbEdit.setText(String.valueOf(intPref(
                KEY_TOOL_MAX_KB, DEFAULT_TOOL_MAX_KB)));
        httpTimeoutEdit.setText(String.valueOf(intPref(
                KEY_HTTP_TIMEOUT_S, DEFAULT_HTTP_TIMEOUT_S)));
        searchCountEdit.setText(String.valueOf(intPref(
                KEY_SEARCH_COUNT, DEFAULT_SEARCH_COUNT)));
        searchSummaryEdit.setText(String.valueOf(intPref(
                KEY_SEARCH_SUMMARY_CHARS, DEFAULT_SEARCH_SUMMARY)));
        visionMaxPxEdit.setText(String.valueOf(intPref(
                KEY_VISION_MAX_PX, DEFAULT_VISION_MAX_PX)));
        visionMaxImagesEdit.setText(String.valueOf(intPref(
                KEY_VISION_MAX_IMAGES, DEFAULT_VISION_MAX_IMAGES)));
        jsEnvPromptEdit.setText(prefs.getString(KEY_JS_ENV_PROMPT, Strings.get(DEFAULT_JS_ENV_PROMPT_ID)));
        logSwitch.setChecked(prefs.getBoolean(KEY_LOG_ENABLED, true));
        realtimeRenderSwitch.setChecked(prefs.getBoolean(KEY_REALTIME_RENDER, false));
        streamSwitch.setChecked(prefs.getBoolean(KEY_STREAM_ENABLED, true));
        smoothStreamSwitch.setChecked(prefs.getBoolean(KEY_SMOOTH_STREAM, true));
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
            // 签名要带上显示名：只改别名时列表也要重建，否则下拉里还是旧名字
            sig.append(models.get(i).name).append("::").append(models.get(i).provider)
                    .append("::").append(models.get(i).label())
                    .append("::").append(cm.providerLabel(models.get(i).provider)).append(";");
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
            names.add(m.label() + " (" + cm.providerLabel(m.provider) + ")");
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        titleModelSpinner.setAdapter(adapter);

        String saved = prefs.getString(KEY_TITLE_MODEL, DEFAULT_TITLE_MODEL);
        LogUtil.d(TAG, "refreshTitleModelSpinner: %d models, saved=%s", models.size(), saved);
        // 优先按唯一键匹配（同名不同厂商的两条要分得开），再退回按模型 ID 匹配老配置
        for (int i = 0; i < models.size(); i++) {
            if (models.get(i).key().equals(saved)) {
                titleModelSpinner.setSelection(i);
                titleModelMissingPos = -1;
                LogUtil.d(TAG, "refreshTitleModelSpinner: selected pos=%d (by key)", i);
                return;
            }
        }
        for (int i = 0; i < models.size(); i++) {
            if (models.get(i).name.equals(saved)) {
                titleModelSpinner.setSelection(i);
                titleModelMissingPos = -1;
                LogUtil.d(TAG, "refreshTitleModelSpinner: selected pos=%d (by name, legacy)", i);
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
    /**
     * 导出最近 3 次请求记录到导出目录。
     *
     * <p>没有记录时不生成空文件：空文件发回去只会多一轮来回。
     */
    private void exportRequestDiag() {
        final boolean enabled = RequestDiag.isEnabled(this);
        if (RequestDiag.list(this).length == 0) {
            AcToast.show(this, Strings.get(enabled
                    ? R.string.requestdiag_006 : R.string.requestdiag_005));
            return;
        }
        final String text = RequestDiag.exportText(this);
        // 导出可能上百 KB，放后台写，写完回主线程提示路径
        final String name = RequestDiag.exportFileName();
        new Thread(new Runnable() {
            public void run() {
                final String path = StorageManager.getInstance().saveExport(name, text);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (isFinishing()) return;
                        AcToast.showLong(SettingsActivity.this, path == null
                                ? Strings.get(R.string.requestdiag_008)
                                : Strings.get(R.string.requestdiag_007, path));
                    }
                });
            }
        }, "aichat-diag-export").start();
    }

    private void confirmClearRequestDiag() {
        AcDialog.builder(this)
                .setTitle(Strings.get(R.string.requestdiag_010))
                .setMessage(Strings.get(R.string.requestdiag_011))
                .setPositiveButton(Strings.get(R.string.requestdiag_012),
                        new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        RequestDiag.clear(SettingsActivity.this);
                        AcToast.show(SettingsActivity.this, Strings.get(R.string.requestdiag_009));
                    }
                })
                .setNegativeButton(Strings.get(R.string.cancel), null)
                .show();
    }

    private void saveSettings() {
        String current = systemPromptEdit.getText().toString().trim();
        String saved = prefs.getString(KEY_SYSTEM_PROMPT, "");
        if (current.equals(saved == null ? "" : saved.trim())) {
            doSaveSettings();
            return;
        }
        AcDialog.builder(this)
                .setTitle(Strings.get(R.string.settingsactivity_029))
                .setMessage(Strings.get(R.string.settingsactivity_075))
                .setPositiveButton(Strings.get(R.string.settingsactivity_035), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        doSaveSettings();
                    }
                })
                .setNegativeButton(Strings.get(R.string.settingsactivity_036), null)
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
        editor.putBoolean(KEY_SMOOTH_STREAM, smoothStreamSwitch.isChecked());

        // 代码执行提示词留空等于恢复默认，避免用户删光后拿到一句空提示
        String jsEnvPrompt = jsEnvPromptEdit.getText().toString().trim();
        if (jsEnvPrompt.length() == 0) jsEnvPrompt = Strings.get(DEFAULT_JS_ENV_PROMPT_ID);
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
                    // 存唯一键而不是模型 ID：同一个 ID 挂在两个提供商下时要能区分
                    editor.putString(KEY_TITLE_MODEL, models.get(pos).key());
                    LogUtil.d(TAG, "saveSettings: titleModel=%s", models.get(pos).key());
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

        AcToast.show(this, Strings.get(R.string.settingsactivity_037));
        finish();
    }

    /**
     * 余额查询入口。
     *
     * <p>以前是「自动挑第一个 supportsBalance 的提供商」，用户没法选，也只能查 DeepSeek
     * 那一种结构。现在改成先让用户选提供商，再按该提供商配置的平台预设与路径去查，
     * 结果里同时给出解析后的字段与原始 JSON。
     *
     * <p>只有一个提供商时直接查，不弹选择框——只配了一家的人不该多点一次。
     */
    private void queryBalance() {
        queryBalance(false);
    }

    /**
     * @param diagnostic true 表示逐个接口列调用结果与原始返回（长按「查询余额」进入）。
     *                   排查「为什么只显示了一部分」时用，平时不打扰界面。
     */
    private void queryBalance(final boolean diagnostic) {
        final ConfigManager configManager = ConfigManager.getInstance();
        final List<ProviderInfo> providers = configManager.getProviders();
        if (providers == null || providers.isEmpty()) {
            LogUtil.w(TAG, "queryBalance aborted: no provider");
            AcToast.show(this, Strings.get(R.string.settingsactivity_038));
            return;
        }
        if (providers.size() == 1) {
            runBalanceQuery(providers.get(0), diagnostic);
            return;
        }

        // 只列出「能查余额」的：填了凭据且能查到余额。全都不能查时退回列全部，
        // 让用户点进去看到具体缺什么，比直接一句「未配置」有用。
        //
        // 不能用 hasBalance() 当唯一判据：腾讯云走云 API 签名，本来就没有余额接口路径，
        // 它的 hasBalance() 恒为 false。以前这里只认 hasBalance()，于是填好
        // SecretId / SecretKey 之后，只要还配了别的平台，腾讯云就永远不在这份名单里。
        List<ProviderInfo> usable = new ArrayList<ProviderInfo>();
        for (int i = 0; i < providers.size(); i++) {
            ProviderInfo p = providers.get(i);
            if (p != null && balanceSupported(p) && canQueryBalance(p)) usable.add(p);
        }
        if (usable.isEmpty()) usable = providers;

        final ProviderInfo[] arr = usable.toArray(new ProviderInfo[0]);
        String[] names = new String[arr.length];
        for (int i = 0; i < arr.length; i++) {
            names[i] = arr[i].label() + (balanceSupported(arr[i]) ? "" : "（未配置余额接口）");
        }
        AcDialog.builder(this)
                .setTitle("查询哪个平台的余额")
                .setItems(names, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which < 0 || which >= arr.length) return;
                        runBalanceQuery(arr[which], diagnostic);
                    }
                })
                .setNegativeButton(Strings.get(R.string.settingsactivity_045), null)
                .show();
    }

    /**
     * 这个提供商配没配「余额查询」。
     *
     * <p>普通平台看余额接口路径；腾讯云走云 API 签名，没有路径这一说，
     * 只要预选派生是签名通道就算配了（缺凭据由 {@link #canQueryBalance} 单独判）。
     */
    private static boolean balanceSupported(ProviderInfo p) {
        if (p == null) return false;
        return p.hasBalance() || p.signedBalance();
    }

    /**
     * 这个提供商现在能不能查余额。
     *
     * <p>普通平台看推理 Key；腾讯云走云 API 签名，看的是 SecretId/SecretKey，
     * 与推理 Key 无关——它有 Key 也可能没有访问密钥。
     */
    private static boolean canQueryBalance(ProviderInfo p) {
        if (p == null) return false;
        if (p.signedBalance()) {
            return p.secretId != null && p.secretId.trim().length() > 0
                    && p.secretKey != null && p.secretKey.trim().length() > 0;
        }
        return p.apiKey != null && p.apiKey.length() > 0;
    }

    /** 真正发起查询：按提供商的余额预设与路径查，结果交给 {@link BalanceDialog}。 */
    private void runBalanceQuery(final ProviderInfo provider) {
        runBalanceQuery(provider, false);
    }

    private void runBalanceQuery(final ProviderInfo provider, final boolean diagnostic) {
        if (provider == null) return;
        if (!canQueryBalance(provider)) {
            LogUtil.w(TAG, "runBalanceQuery aborted: credentials missing, provider=%s preset=%s",
                    provider.name, provider.balancePreset);
            AcToast.show(this, provider.signedBalance()
                    ? Strings.get(R.string.settingsactivity_080)
                    : Strings.get(R.string.settingsactivity_038));
            return;
        }
        if (!provider.hasBalance() && !provider.signedBalance()) {
            AcToast.show(this, Strings.get(R.string.settingsactivity_081));
            return;
        }
        final CharSequence balanceBtnText = balanceButton.getText();
        final String path = provider.effectiveBalancePath();
        LogUtil.i(TAG, "runBalanceQuery: provider=%s preset=%s path=%s keyLen=%d",
                provider.name, provider.balancePreset, path,
                provider.apiKey == null ? 0 : provider.apiKey.length());

        balanceButton.setEnabled(false);
        balanceButton.setText(Strings.get(R.string.settingsactivity_039));

        new Thread(new Runnable() {
            public void run() {
                final ApiClient.BalanceResponse resp;
                final BalancePresets.Result parsed;
                if (provider.signedBalance()) {
                    // 腾讯云：账户余额 + TokenHub 用量/计费 + Token Plan 套餐，
                    // 三组一起查再合并展示——按量付费的钱在账户余额里，
                    // 用量在 TokenHub 汇总里，套餐是补充，缺任一项都不能算查全
                    final TencentCloudApi.Result all = TencentCloudApi.collect(
                            provider.secretId, provider.secretKey, diagnostic);
                    resp = new ApiClient.BalanceResponse();
                    resp.code = all.code;
                    resp.url = all.url;
                    resp.body = all.body;
                    resp.diagnostic = diagnostic ? TencentCloudApi.diagnosticText(all) : null;
                    if (all.error != null) resp.error = all.error;
                    parsed = new BalancePresets.Result();
                    parsed.fields.addAll(all.fields);
                    parsed.raw = all.body;
                    parsed.ok = !parsed.fields.isEmpty();
                    if (!parsed.ok) {
                        parsed.error = all.error;
                    } else {
                        parsed.note = all.note;
                    }
                } else {
                    resp = ApiClient.queryBalanceFull(provider, path);
                    parsed = BalancePresets.parse(provider.balancePreset, resp.body);
                }
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        balanceButton.setEnabled(true);
                        balanceButton.setText(balanceBtnText);

                        if (resp.code <= 0 && resp.error != null && !provider.signedBalance()) {
                            // 连 HTTP 都没走到：域名解析失败 / 超时 / TLS 握手失败
                            LogUtil.w(TAG, "runBalanceQuery network error: %s", resp.error);
                        } else {
                            LogUtil.i(TAG, "runBalanceQuery OK=%s bodyLen=%d",
                                    parsed.ok ? "yes" : "no", resp.body == null ? 0 : resp.body.length());
                        }
                        BalanceDialog.show(SettingsActivity.this,
                                provider.label() + " · 余额", parsed, resp);
                    }
                });
            }
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        LogUtil.d(TAG, "onResume");
        // 草稿落盘：预设页的「保存当前提示词」要拿到设置页里还没保存的内容
        if (systemPromptEdit != null) {
            prefs.edit().putString(KEY_SYSTEM_PROMPT_DRAFT,
                    systemPromptEdit.getText().toString()).apply();
        }
        refreshTitleModelSpinner();
        refreshStorageEntry();
        refreshAppearanceEntry();
        refreshThemePromptEntry();
    }

    /** 主题生成提示词入口：副标题显示模板来源，从模板页返回后刷新。 */
    private void refreshThemePromptEntry() {
        android.widget.TextView sub =
                (android.widget.TextView) findViewById(R.id.theme_prompt_entry_sub);
        if (sub == null) return;
        sub.setText(ThemePromptStore.isCustomized(this) ? Strings.get(R.string.settingsactivity_047) : Strings.get(R.string.settingsactivity_048));
    }

    /** 外观入口副标题显示当前主题名（实现方案 Q1）；12sp 单行超长省略。 */
    private void refreshAppearanceEntry() {
        final android.widget.TextView sub =
                (android.widget.TextView) findViewById(R.id.appearance_entry_sub);
        if (sub == null) return;
        try {
            AppearanceManager am = AppearanceManager.getInstance(this);
            String name = am.getActiveThemeName();
            String text = (name == null || name.length() == 0) ? Strings.get(R.string.settingsactivity_049) : name;
            if (am.isDark()) text = Strings.get(R.string.settingsactivity_079, text);
            sub.setText(text);
        } catch (Throwable t) {
            // 外观系统异常不能拖垮设置页，最坏情况副标题显示内置默认
            sub.setText(Strings.get(R.string.settingsactivity_051));
        }
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
            storageEntrySize.setText(Strings.get(R.string.settingsactivity_052, StorageReport.size(cached.usedBytes())));
            return;
        }
        // 反复进出设置页时避免叠起多个扫描线程
        if (storageScanning) return;
        storageScanning = true;
        storageEntrySize.setText("");
        new Thread(new Runnable() {
            public void run() {
                final StorageReport r = StorageScanner.scan(getApplicationContext());
                final String text = Strings.get(R.string.settingsactivity_053, StorageReport.size(r.usedBytes()));
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

    /** 流式正文逐字渐显。关闭时恢复「每帧整段替换」。 */
    public static boolean isSmoothStreamEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, 0);
        return prefs.getBoolean(KEY_SMOOTH_STREAM, true);
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
        int v;
        try {
            v = context.getSharedPreferences(PREFS_NAME, 0).getInt(key, def);
        } catch (ClassCastException e) {
            // 旧版/外部可能把它存成 String，getInt 会直接抛崩设置页
            v = def;
        }
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    /** loadSettings 回显用：只做类型兜底、不夹取范围，键名与默认值保持原样。
     *  旧版/外部把同一 int 键写成 String 时 getInt 会抛 ClassCastException，回退默认值。 */
    private int intPref(String key, int def) {
        try {
            return prefs.getInt(key, def);
        } catch (ClassCastException e) {
            return def;
        }
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
        String template = prefs.getString(KEY_JS_ENV_PROMPT, Strings.get(DEFAULT_JS_ENV_PROMPT_ID));
        if (template == null || template.trim().length() == 0) {
            template = Strings.get(DEFAULT_JS_ENV_PROMPT_ID);
        }
        return SystemInfo.fillPlaceholders(template, context);
    }

    /** 未填充占位符的原始模板，设置页回填用。 */
    public static String getJsEnvPromptTemplate(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, 0);
        String v = prefs.getString(KEY_JS_ENV_PROMPT, Strings.get(DEFAULT_JS_ENV_PROMPT_ID));
        return v == null || v.trim().length() == 0 ? Strings.get(DEFAULT_JS_ENV_PROMPT_ID) : v;
    }

    public static String getTitleModel(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, 0);
        String v = prefs.getString(KEY_TITLE_MODEL, DEFAULT_TITLE_MODEL);
        // 存储用唯一键（provider + \u001f + 模型 ID），但发给 API 的必须是模型 ID
        if (v != null && v.indexOf('\u001f') >= 0) {
            ModelInfo m = ConfigManager.getInstance().findModel(v);
            if (m != null && m.name != null) return m.name;
            return v.substring(v.indexOf('\u001f') + 1);
        }
        return v;
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
                prefs.getString(KEY_TITLE_PROMPT, Strings.get(DEFAULT_TITLE_PROMPT_ID)), context);
    }

    // ---- Presets ----

    /**
     * 预设下拉框 + 两个按钮（一行放不下：258dp 屏上「预设 + 下拉 + 两个按钮」会全部被省略号吃掉）。
     *
     * <p>下拉只负责「看一眼现在有哪几套」，真正的管理（预览 / 应用 / 修改 / 删除）在
     * {@link PresetManagerActivity}：弹窗版的管理列表既没法展示完整提示词，
     * 也没法和列表滚动配合。
     */
    private void refreshPresetSpinner() {
        presetList = PresetStore.load(this);
        List<String> names = new ArrayList<String>();
        names.add(Strings.get(R.string.settingsactivity_054));
        for (PresetStore.Preset p : presetList) names.add(p.name);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        presetSpinner.setAdapter(adapter);
        // 直接 setSelection 会触发 onItemSelected，把提示词输入框改成预设内容 ——
        // 从预设页返回、刚应用了另一套提示词时看起来没变，但从 onResume 重建时会
        // 把用户正在编辑的草稿覆盖掉。只在用户真正点选下拉时才回填。
        presetSpinner.setSelection(presetIndexByName(appliedPresetName), false);
        LogUtil.d(TAG, "refreshPresetSpinner: %d presets", presetList.size());
    }

    private int presetIndexByName(String name) {
        if (name == null || name.length() == 0 || presetList == null) return 0;
        for (int i = 0; i < presetList.size(); i++) {
            if (name.equals(presetList.get(i).name)) return i + 1;
        }
        return 0;
    }

    /**
     * 预填名称的保存框（「保存当前」与预设页的「新建」共用同一套规则）。
     *
     * <p>名称默认「新预设」、重名自动顺延成「新预设2」…，用户几乎不用打字；
     * 想改名的仍然可以改，所以不做成「静默保存」。
     */
    private void promptSavePreset(final String presetPrompt) {
        final String prompt = presetPrompt == null ? "" : presetPrompt.trim();
        if (prompt.length() == 0) {
            LogUtil.w(TAG, "promptSavePreset aborted: prompt empty");
            AcToast.show(this, Strings.get(R.string.settingsactivity_055));
            return;
        }
        List<PresetStore.Preset> list = PresetStore.load(this);
        if (list.size() >= PresetStore.MAX_PRESETS) {
            AcToast.show(this, Strings.get(R.string.settingsactivity_056, PresetStore.MAX_PRESETS));
            return;
        }
        final EditText input = new EditText(this);
        input.setHint(Strings.get(R.string.settingsactivity_058));
        input.setSingleLine(true);
        input.setText(PresetStore.nextName(list, Strings.get(R.string.settingsactivity_059)));
        input.setSelection(input.getText().length());

        final android.app.AlertDialog dlg = AcDialog.builder(this)
                .setTitle(Strings.get(R.string.settingsactivity_060))
                .setMessage(Strings.get(R.string.settingsactivity_061, prompt.length()))
                .setView(input)
                .setPositiveButton(Strings.get(R.string.settingsactivity_063), null)
                .setNegativeButton(Strings.get(R.string.settingsactivity_064), null)
                .create();
        AcDialog.onShow(dlg, new android.content.DialogInterface.OnShowListener() {
            public void onShow(android.content.DialogInterface dialog) {
                ((android.app.AlertDialog) dialog)
                        .getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener(new View.OnClickListener() {
                            public void onClick(View v) {
                                String name = input.getText().toString().trim();
                                if (name.length() == 0) {
                                    AcToast.show(SettingsActivity.this, Strings.get(R.string.settingsactivity_065));
                                    return;
                                }
                                List<PresetStore.Preset> cur = PresetStore.load(SettingsActivity.this);
                                if (PresetStore.contains(cur, name, null)) {
                                    AcToast.show(SettingsActivity.this, Strings.get(R.string.settingsactivity_066, name));
                                    return;
                                }
                                if (cur.size() >= PresetStore.MAX_PRESETS) {
                                    AcToast.show(SettingsActivity.this,
                                            Strings.get(R.string.settingsactivity_067, PresetStore.MAX_PRESETS));
                                    return;
                                }
                                cur.add(new PresetStore.Preset(name, prompt));
                                if (!PresetStore.save(SettingsActivity.this, cur)) {
                                    AcToast.show(SettingsActivity.this, Strings.get(R.string.settingsactivity_069));
                                    return;
                                }
                                LogUtil.i(TAG, "savePreset: '%s' (%d chars), total=%d",
                                        name, prompt.length(), cur.size());
                                appliedPresetName = name;
                                refreshPresetSpinner();
                                dlg.dismiss();
                                AcToast.show(SettingsActivity.this, Strings.get(R.string.settingsactivity_070, name));
                            }
                        });
            }
        });
        dlg.show();
    }

    /**
     * 三个提示词输入框的两件配套：全屏编辑入口、键盘弹出时把整框滚回可见。
     *
     * <p>滚动为什么要单独管：多行输入框自己会滚（maxLines 限高），光标移动时
     * 系统把光标矩形交给外层 ScrollView 换算，坐标基准不一致，算出来的偏移
     * 每次偏大一截。表现是打着字整页往下跳，最后输入框被顶出屏幕。滚动请求
     * 已由 {@link FormScrollView} 拦下，这里只在「可视高度变了」（键盘弹出、
     * 分组展开）和「输入框刚获得焦点」这两个时机滚一次。
     */
    private void bindPromptEditors() {
        bindFullscreenButton(R.id.system_prompt_fullscreen, systemPromptEdit, REQ_FULLSCREEN_SYSTEM);
        bindFullscreenButton(R.id.title_prompt_fullscreen, titlePromptEdit, REQ_FULLSCREEN_TITLE);
        bindFullscreenButton(R.id.js_env_prompt_fullscreen, jsEnvPromptEdit, REQ_FULLSCREEN_JS);

        EditText[] editors = new EditText[]{systemPromptEdit, titlePromptEdit, jsEnvPromptEdit};
        for (int i = 0; i < editors.length; i++) {
            final EditText edit = editors[i];
            edit.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus) {
                        focusedEdit = edit;
                        keepEditorVisible(edit);
                    } else if (focusedEdit == edit) {
                        focusedEdit = null;
                    }
                }
            });
        }

        View content = findViewById(android.R.id.content);
        if (content != null) {
            content.getViewTreeObserver().addOnGlobalLayoutListener(
                    new ViewTreeObserver.OnGlobalLayoutListener() {
                        public void onGlobalLayout() {
                            if (scrollView == null) return;
                            int h = scrollView.getHeight();
                            // 键盘弹出、收起，分组展开收起都会改变可视高度。
                            // 变了就把当前输入框重新带回视野——只跟着「高度变」走，
                            // 打字和移光标不会触发，页面自然不跳。
                            if (h != lastScrollHeight) {
                                lastScrollHeight = h;
                                if (focusedEdit != null) keepEditorVisible(focusedEdit);
                            }
                        }
                    });
        }
    }

    /** 每次变化后的可视高度，用来判断是键盘弹出还是普通重绘。 */
    private int lastScrollHeight;

    private void bindFullscreenButton(int buttonId, final EditText edit, final int requestCode) {
        View b = findViewById(buttonId);
        if (b == null) return;
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                LogUtil.d(TAG, "click: fullscreen prompt, len=%d", edit.getText().length());
                Intent it = new Intent(SettingsActivity.this, FullscreenInputActivity.class);
                it.putExtra(FullscreenInputActivity.EXTRA_TEXT, edit.getText().toString());
                it.putExtra(FullscreenInputActivity.EXTRA_TITLE,
                        Strings.get(R.string.activity_settings_072));
                startActivityForResult(it, requestCode);
            }
        });
    }

    /** 把输入框整框带进视野。延迟一点，等键盘动画把窗口高度定下来再滚。 */
    private void keepEditorVisible(final EditText edit) {
        if (edit == null || scrollView == null) return;
        scrollView.postDelayed(new Runnable() {
            public void run() {
                if (isFinishing() || scrollView == null) return;
                scrollView.showEditor(edit);
            }
        }, 120);
    }

    /** 打开预设管理页；应用某个预设后回填输入框。 */
    private void openPresetManager() {
        Intent it = new Intent(this, PresetManagerActivity.class);
        startActivityForResult(it, REQ_PRESET_MANAGER);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && data != null
                && (requestCode == REQ_FULLSCREEN_SYSTEM
                || requestCode == REQ_FULLSCREEN_TITLE
                || requestCode == REQ_FULLSCREEN_JS)) {
            String text = data.getStringExtra(FullscreenInputActivity.EXTRA_RESULT);
            if (text == null) return;
            EditText target = requestCode == REQ_FULLSCREEN_SYSTEM ? systemPromptEdit
                    : (requestCode == REQ_FULLSCREEN_TITLE ? titlePromptEdit : jsEnvPromptEdit);
            target.setText(text);
            target.setSelection(text.length());
            LogUtil.i(TAG, "fullscreen prompt returned: len=%d", text.length());
            return;
        }
        if (requestCode == REQ_PRESET_MANAGER && resultCode == RESULT_OK && data != null) {
            String prompt = data.getStringExtra(PresetManagerActivity.EXTRA_APPLY_PROMPT);
            String name = data.getStringExtra(PresetManagerActivity.EXTRA_APPLY_NAME);
            if (prompt != null) {
                appliedPresetName = name;
                systemPromptEdit.setText(prompt);
                refreshPresetSpinner();
                LogUtil.i(TAG, "preset applied from manager: '%s' (%d chars)",
                        name, prompt.length());
            }
        }
    }
}
