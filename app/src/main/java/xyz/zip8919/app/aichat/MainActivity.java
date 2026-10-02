package xyz.zip8919.app.aichat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import java.net.HttpURLConnection;
import java.util.regex.Matcher;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";
    private static final String PREFS_NAME = "aichat_prefs";
    private static final int REQUEST_CONVERSATION_MANAGER = 1;
    private static final int REQUEST_SCAN = 2;

    private ConfigManager configManager;
    private ConversationManager conversationManager;
    private StorageManager storageManager;
    private SharedPreferences prefs;

    private EditText inputEditText;
    private Button sendButton;
    private WebView conversationWebView;
    private Spinner modelSpinner;
    private Spinner thinkingSpinner;

    private List<Message> messages;
    private List<ModelInfo> availableModels;

    private AtomicBoolean isRequestInProgress = new AtomicBoolean(false);
    private AtomicInteger requestGeneration = new AtomicInteger(0);
    private HttpURLConnection currentConnection;
    private Thread currentRequestThread;
    private Handler handler = new Handler();

    // Image viewer dialog state
    private AlertDialog imageViewerDialog;
    private WebView imageViewerWebView;
    private TextView imageCounterText;
    private Button prevButton, nextButton, zoomOutBtn, zoomInBtn, rotateBtn, resetBtn, bgToggleBtn;
    private List<ImageInfo> currentImageList;
    private int currentImageIndex;
    private int currentRotation;
    private int currentBgColor; // 0=white, 1=gray, 2=black

    // Table viewer dialog state
    private AlertDialog tableViewerDialog;
    private WebView tableViewerWebView;
    private int tableRotation;
    private int tableBgColor; // 0=white, 1=gray, 2=black

    // Code preview dialog state (HTML / SVG)
    private AlertDialog codePreviewDialog;
    private WebView codePreviewWebView;
    private int codePreviewRotation;
    private int codePreviewBgColor;
    private String currentPreviewLang;
    private String currentPreviewCode;

    // Quick scan mode state
    private boolean expectingQuickScanResult = false;

    private String currentModel;
    private String currentApiKey;
    private String currentApiUrl;
    private String systemPrompt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LogUtil.ENABLED = SettingsActivity.isLogEnabled(this);
        LogUtil.i(TAG, "========== onCreate: savedInstanceState=%s, thread=%s ==========",
                savedInstanceState, LogUtil.thread());
        setContentView(R.layout.activity_main);

        this.prefs = getSharedPreferences(PREFS_NAME, 0);
        this.storageManager = StorageManager.getInstance(this);
        this.configManager = ConfigManager.getInstance();
        this.conversationManager = ConversationManager.getInstance();
        LogUtil.d(TAG, "managers initialized: storage=%s, config=%s, conv=%s",
                storageManager, configManager, conversationManager);

        if (!storageManager.createDirectories()) {
            LogUtil.e(TAG, "createDirectories FAILED, basePath=%s", storageManager.getBasePath());
            Toast.makeText(this, "无法访问外部存储", Toast.LENGTH_LONG).show();
        } else {
            LogUtil.i(TAG, "storage ready: %s (conversations=%s)",
                    storageManager.getStorageInfo(), storageManager.getConversationsPath());
            Toast.makeText(this, storageManager.getStorageInfo(), Toast.LENGTH_LONG).show();
        }

        configManager.load();
        LogUtil.i(TAG, "config loaded: providers=%d, models=%d, defaultModel=%s, thinking=%s/%s",
                configManager.getProviders().size(), configManager.getModels().size(),
                configManager.getDefaultModel(), configManager.isThinkingEnabled(),
                configManager.getThinkingLevel());

        List<Conversation> loaded = conversationManager.loadConversations();
        LogUtil.i(TAG, "conversations loaded: count=%d", loaded == null ? -1 : loaded.size());

        initViews();
        loadSystemPrompt();
        initConversation();
        LogUtil.i(TAG, "========== onCreate done ==========");
    }

    @Override
    protected void onDestroy() {
        LogUtil.i(TAG, "onDestroy");
        super.onDestroy();
    }

    private void initViews() {
        LogUtil.d(TAG, "initViews");
        inputEditText = (EditText) findViewById(R.id.input_edit_text);
        sendButton = (Button) findViewById(R.id.send_button);
        modelSpinner = (Spinner) findViewById(R.id.model_spinner);
        thinkingSpinner = (Spinner) findViewById(R.id.thinking_spinner);
        conversationWebView = (WebView) findViewById(R.id.message_webview);
        conversationWebView.getSettings().setJavaScriptEnabled(true);
        conversationWebView.getSettings().setDefaultTextEncodingName("UTF-8");
        conversationWebView.getSettings().setBuiltInZoomControls(false);
        conversationWebView.getSettings().setLoadWithOverviewMode(true);
        conversationWebView.getSettings().setUseWideViewPort(true);
        conversationWebView.getSettings().setAllowFileAccess(true);
        conversationWebView.getSettings().setAllowFileAccessFromFileURLs(true);
        conversationWebView.addJavascriptInterface(new JsBridge(), "Android");
        conversationWebView.setWebViewClient(new WebViewClient() {
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return true;
            }
        });

        // Buttons
        findViewById(R.id.new_conversation_button).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { LogUtil.d(TAG, "click: new_conversation"); createNewConversation(); }
        });
        findViewById(R.id.history_button).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { LogUtil.d(TAG, "click: history"); openConversationManager(); }
        });
        findViewById(R.id.settings_button).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { LogUtil.d(TAG, "click: settings"); openSettings(); }
        });
        findViewById(R.id.rotate_button).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int orient = getResources().getConfiguration().orientation;
                LogUtil.d(TAG, "click: rotate, current orientation=%d", orient);
                if (orient == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
                    setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                else
                    setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            }
        });

        // Send button
        sendButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { LogUtil.d(TAG, "click: send"); sendMessage(); }
        });
        sendButton.setOnTouchListener(new View.OnTouchListener() {
            private boolean longPressed = false;
            private Runnable longPressRunnable;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        longPressed = false;
                        longPressRunnable = new Runnable() {
                            public void run() {
                                longPressed = true;
                                LogUtil.d(TAG, "sendButton LONG press -> interruptRequest");
                                interruptRequest();
                            }
                        };
                        handler.postDelayed(longPressRunnable, 500);
                        return false;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        handler.removeCallbacks(longPressRunnable);
                        if (!longPressed && isRequestInProgress.get()) {
                            LogUtil.d(TAG, "sendButton short tap during request -> interruptRequest");
                            interruptRequest();
                        }
                        return false;
                }
                return false;
            }
        });

        // Scan button
        findViewById(R.id.scan_button).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                LogUtil.d(TAG, "click: scan -> launchScan");
                launchScan();
            }
        });

        // Scroll-to-bottom button: tap = scroll to bottom, long-press = clear input
        final Button scrollBtn = (Button) findViewById(R.id.scroll_to_bottom_button);
        scrollBtn.setOnTouchListener(new View.OnTouchListener() {
            private Runnable longPressRunnable;
            private boolean longPressed = false;
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        longPressed = false;
                        longPressRunnable = new Runnable() {
                            public void run() {
                                longPressed = true;
                                inputEditText.setText("");
                            }
                        };
                        handler.postDelayed(longPressRunnable, 600);
                        return false;
                    case MotionEvent.ACTION_UP:
                        handler.removeCallbacks(longPressRunnable);
                        if (!longPressed) {
                            conversationWebView.loadUrl(
                                "javascript:smartScrollToBottom(true)");
                        }
                        return false;
                    case MotionEvent.ACTION_CANCEL:
                        handler.removeCallbacks(longPressRunnable);
                        return false;
                }
                return false;
            }
        });

        // Thinking spinner
        String[] thinkingLevels = {"关闭", "低", "中", "高"};
        ArrayAdapter<String> thinkingAdapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, thinkingLevels);
        thinkingAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        thinkingSpinner.setAdapter(thinkingAdapter);

        // Set current thinking level
        String level = configManager.getThinkingLevel();
        int levelPos = 2;
        if ("off".equals(level)) levelPos = 0;
        else if ("low".equals(level)) levelPos = 1;
        else if ("high".equals(level)) levelPos = 3;
        thinkingSpinner.setSelection(levelPos);
        LogUtil.d(TAG, "thinking level restored: %s -> pos=%d (thinkingEnabled=%s)",
                level, levelPos, configManager.isThinkingEnabled());

        thinkingSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                String[] levels = {"off", "low", "medium", "high"};
                LogUtil.d(TAG, "thinking level selected: pos=%d -> %s", pos, levels[pos]);
                configManager.setThinkingLevel(levels[pos]);
                configManager.save();
            }
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // Model spinner
        refreshModelSpinner();
    }

    private void refreshModelSpinner() {
        LogUtil.d(TAG, "refreshModelSpinner");
        availableModels = configManager.getModels();
        List<String> names = new ArrayList<String>();
        for (ModelInfo m : availableModels) {
            names.add(m.name + " (" + m.provider + ")");
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modelSpinner.setAdapter(adapter);
        LogUtil.d(TAG, "model spinner items=%d: %s", names.size(), names);

        // Select default
        String defaultModel = configManager.getDefaultModel();
        boolean matched = false;
        for (int i = 0; i < availableModels.size(); i++) {
            if (availableModels.get(i).name.equals(defaultModel)) {
                modelSpinner.setSelection(i);
                selectModel(i);
                matched = true;
                break;
            }
        }
        if (!matched) {
            LogUtil.w(TAG, "default model '%s' not found in %d models", defaultModel, availableModels.size());
        }

        modelSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                LogUtil.d(TAG, "model selected: pos=%d", pos);
                selectModel(pos);
            }
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void selectModel(int pos) {
        if (pos < 0 || pos >= availableModels.size()) {
            LogUtil.w(TAG, "selectModel: invalid pos=%d (size=%d)", pos,
                    availableModels == null ? -1 : availableModels.size());
            return;
        }
        ModelInfo model = availableModels.get(pos);
        ProviderInfo provider = configManager.getProvider(model.provider);
        if (provider != null) {
            currentModel = model.name;
            currentApiKey = provider.apiKey;
            currentApiUrl = provider.apiUrl;
            LogUtil.i(TAG, "selectModel: pos=%d model=%s provider=%s url=%s keyLen=%d",
                    pos, model.name, provider.name, provider.apiUrl,
                    provider.apiKey == null ? 0 : provider.apiKey.length());
        } else {
            LogUtil.w(TAG, "selectModel: provider '%s' not found for model '%s'", model.provider, model.name);
        }
    }

    private void loadSystemPrompt() {
        systemPrompt = SettingsActivity.getSystemPrompt(this);
        LogUtil.d(TAG, "loadSystemPrompt: len=%d preview=%s",
                systemPrompt == null ? 0 : systemPrompt.length(), LogUtil.preview(systemPrompt, 120));
    }

    private void initConversation() {
        Conversation conv = conversationManager.getCurrentConversation();
        messages = conv.messages;
        LogUtil.i(TAG, "initConversation: id=%s title=%s messages=%d",
                conv.id, conv.title, messages.size());
        refreshWebView();
    }

    private void createNewConversation() {
        LogUtil.i(TAG, "createNewConversation (requestInProgress=%s)", isRequestInProgress.get());
        if (isRequestInProgress.get()) interruptRequest();
        loadSystemPrompt();
        Conversation conv = conversationManager.createNewConversation();
        conv.systemPrompt = systemPrompt;
        conv.model = currentModel;
        messages = conv.messages;
        LogUtil.i(TAG, "new conversation created: id=%s model=%s", conv.id, conv.model);
        refreshWebView();
        Toast.makeText(this, "已创建新对话", Toast.LENGTH_SHORT).show();
    }

    private void interruptRequest() {
        if (!isRequestInProgress.get()) return;
        requestGeneration.incrementAndGet();
        LogUtil.i(TAG, "interruptRequest: disconnecting current connection");
        isRequestInProgress.set(false);
        if (currentConnection != null) {
            try { currentConnection.disconnect(); } catch (Exception ignored) {}
            currentConnection = null;
        }
        // Safety: skip UI cleanup if activity is finishing
        if (isFinishing()) return;
        final int msgCountAtInterrupt = messages.size();
        runOnUiThread(new Runnable() {
            public void run() {
                if (isFinishing()) return;
                if (messages.size() != msgCountAtInterrupt) return;
                if (!messages.isEmpty()) {
                    Message lastMsg = messages.get(messages.size() - 1);
                    if (lastMsg.isAssistant()) {
                        String content = lastMsg.content;
                        if (content == null || content.isEmpty()) {
                            LogUtil.d(TAG, "interrupt: removing empty AI placeholder, size=%d", messages.size());
                            messages.remove(messages.size() - 1);
                            removeDomRange(messages.size());
                        } else {
                            lastMsg.content = content + " (已打断)";
                            LogUtil.d(TAG, "interrupt: marking AI msg as interrupted, len=%d", content.length());
                            updateAiContent(lastMsg.content);
                        }
                    } else {
                        LogUtil.d(TAG, "interrupt: last message is not assistant, nothing to mark");
                    }
                }
            }
        });
    }

    private void openSettings() {
        LogUtil.d(TAG, "openSettings -> SettingsActivity");
        startActivity(new Intent(this, SettingsActivity.class));
    }

    private void openConversationManager() {
        LogUtil.d(TAG, "openConversationManager -> ConversationManagerActivity");
        startActivityForResult(new Intent(this, ConversationManagerActivity.class),
                REQUEST_CONVERSATION_MANAGER);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        LogUtil.d(TAG, "onActivityResult: requestCode=%d resultCode=%d data=%s", requestCode, resultCode, data);
        if (requestCode == REQUEST_CONVERSATION_MANAGER && resultCode == RESULT_OK) {
            String conversationId = data == null ? null : data.getStringExtra("conversation_id");
            if (conversationId != null) {
                LogUtil.i(TAG, "switching to conversation: %s", conversationId);
                if (isRequestInProgress.get()) interruptRequest();
                conversationManager.saveCurrentConversation();
                conversationManager.switchConversation(conversationId);
                Conversation conv = conversationManager.getCurrentConversation();
                messages = conv.messages;
                LogUtil.i(TAG, "switched: id=%s title=%s messages=%d", conv.id, conv.title, messages.size());
                refreshWebView();
                Toast.makeText(this, "已切换到: " + conv.title, Toast.LENGTH_SHORT).show();
            } else {
                LogUtil.w(TAG, "REQUEST_CONVERSATION_MANAGER returned null conversation_id");
            }
        } else if (requestCode == REQUEST_SCAN && resultCode == RESULT_OK) {
            String text = data == null ? null : data.getStringExtra("scan_text");
            if (text != null && !text.isEmpty()) {
                LogUtil.i(TAG, "scan result: len=%d preview=%s", text.length(), LogUtil.preview(text, 120));
                inputEditText.setText(text);
                inputEditText.setSelection(text.length());
            } else {
                LogUtil.w(TAG, "REQUEST_SCAN returned empty/null text");
            }
        } else {
            LogUtil.d(TAG, "onActivityResult unhandled: requestCode=%d resultCode=%d", requestCode, resultCode);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        LogUtil.d(TAG, "onResume");
        loadSystemPrompt();
        refreshModelSpinner();
        if (expectingQuickScanResult) {
            expectingQuickScanResult = false;
            LogUtil.d(TAG, "onResume: quick-scan result expected -> loadQuickScanResult");
            loadQuickScanResult();
        }
    }

    @Override
    protected void onPause() {
        LogUtil.d(TAG, "onPause");
        super.onPause();
        conversationManager.saveCurrentConversation();
    }

    @Override
    protected void onStop() {
        LogUtil.d(TAG, "onStop");
        super.onStop();
        conversationManager.saveCurrentConversation();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        LogUtil.d(TAG, "onConfigurationChanged: orient=%s",
                newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE ? "landscape" : "portrait");
        if (imageViewerDialog != null && imageViewerDialog.isShowing()) {
            imageViewerDialog.getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
        }
        if (tableViewerDialog != null && tableViewerDialog.isShowing()) {
            tableViewerDialog.getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
        }
        if (codePreviewDialog != null && codePreviewDialog.isShowing()) {
            codePreviewDialog.getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
        }
    }

    // Scan key codes (same as ScanActivity)
    private static final int[] SCAN_KEY_CODES = {5, 27, 131, 137, 286};

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        for (int code : SCAN_KEY_CODES) {
            if (keyCode == code) {
                LogUtil.i(TAG, "onKeyDown: scan key %d -> launchScan", keyCode);
                launchScan();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private void launchScan() {
        if (SettingsActivity.isQuickScanEnabled(this)) {
            expectingQuickScanResult = true;
            LogUtil.d(TAG, "launchScan: quick-scan mode, trying external scan apps");
            try {
                Intent si = new Intent();
                si.setClassName("com.jxw.launcher", "com.jxw.launcher.SPWBZCActivity");
                startActivity(si);
                LogUtil.i(TAG, "quick scan launched: com.jxw.launcher/.SPWBZCActivity");
                return;
            } catch (Exception e) {
                LogUtil.w(TAG, "quick scan launcher unavailable: com.jxw.launcher (%s)", e.getMessage());
            }
            try {
                Intent si = new Intent();
                si.setClassName("com.jxw.wbzc", "com.jxw.wbzc.MainActivity");
                startActivity(si);
                LogUtil.i(TAG, "quick scan launched: com.jxw.wbzc/.MainActivity");
                return;
            } catch (Exception e) {
                LogUtil.w(TAG, "quick scan launcher unavailable: com.jxw.wbzc (%s)", e.getMessage());
            }
            LogUtil.w(TAG, "quick scan: no external launcher available, aborted");
            Toast.makeText(this, "请手动打开文本摘抄应用扫描", Toast.LENGTH_SHORT).show();
            expectingQuickScanResult = false;
        } else {
            LogUtil.d(TAG, "launchScan: builtin ScanActivity mode");
            try {
                startActivityForResult(new Intent(this, ScanActivity.class), REQUEST_SCAN);
            } catch (Exception e) {
                LogUtil.e(TAG, "launch ScanActivity failed", e);
                Toast.makeText(this, "无法启动扫描: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    private boolean sendMessagePending = false;

    private void sendMessage() {
        if (isRequestInProgress.get()) {
            LogUtil.w(TAG, "sendMessage ignored: request already in progress -> interrupt instead");
            interruptRequest();
            if (!sendMessagePending) {
                sendMessagePending = true;
                handler.postDelayed(new Runnable() {
                    public void run() {
                        sendMessagePending = false;
                        sendMessage();
                    }
                }, 150);
            }
            return;
        }
        String input = inputEditText.getText().toString().trim();
        if (input.isEmpty()) {
            LogUtil.w(TAG, "sendMessage ignored: empty input");
            return;
        }
        LogUtil.i(TAG, "========== sendMessage: len=%d preview=%s ==========",
                input.length(), LogUtil.preview(input, 200));

        loadSystemPrompt();

        Message userMsg = new Message(Message.ROLE_USER, input);
        messages.add(userMsg);
        conversationManager.getCurrentConversation().touch();
        inputEditText.setText("");
        LogUtil.d(TAG, "user message appended, total messages=%d", messages.size());

        // Append user message to WebView
        String userHtml = MessageHtmlRenderer.renderMessageDiv(userMsg, messages.size() - 1, this);
        appendHtml(userHtml);

        Conversation conv = conversationManager.getCurrentConversation();
        boolean isFirstMsg = conv.messages.size() == 1;
        boolean autoTitle = SettingsActivity.isAutoTitleEnabled(this);
        LogUtil.d(TAG, "title check: isFirstMsg=%s autoTitle=%s titleGenerated=%s",
                isFirstMsg, autoTitle, conv.titleGenerated);
        if (isFirstMsg && autoTitle && !conv.titleGenerated) {
            generateTitle(input);
        }

        int selPos = modelSpinner.getSelectedItemPosition();
        if (selPos < 0 || availableModels == null || selPos >= availableModels.size()) {
            LogUtil.e(TAG, "sendMessage aborted: invalid model selection pos=%d, models=%d",
                    selPos, availableModels == null ? -1 : availableModels.size());
            Toast.makeText(this, "未选择模型", Toast.LENGTH_SHORT).show();
            return;
        }
        ProviderInfo provider = configManager.getProvider(availableModels.get(selPos).provider);
        if (provider == null) {
            LogUtil.e(TAG, "sendMessage aborted: provider null for model '%s'",
                    availableModels.get(selPos).name);
            Toast.makeText(this, "未选择模型", Toast.LENGTH_SHORT).show();
            return;
        }

        String thinkingLevel = configManager.getThinkingLevel();
        if (!configManager.isThinkingEnabled()) thinkingLevel = "off";
        LogUtil.i(TAG, "request params: model=%s provider=%s url=%s thinking=%s",
                currentModel, provider.name, provider.apiUrl, thinkingLevel);

        int gen = requestGeneration.incrementAndGet();
        sendStreamingRequest(provider, thinkingLevel, gen);
    }

    private void sendStreamingRequest(final ProviderInfo provider, final String thinkingLevel, final int generation) {
        final long startMs = System.currentTimeMillis();
        LogUtil.i(TAG, ">>> sendStreamingRequest START: gen=%d url=%s%s model=%s thinking=%s (type=%s, param=%s)",
                generation, provider.apiUrl, provider.chatPath, currentModel, thinkingLevel,
                provider.thinkingType, provider.thinkingParamName);
        isRequestInProgress.set(true);

        final Message aiMsg = new Message(Message.ROLE_ASSISTANT, "");
        messages.add(aiMsg);
        final int aiIndex = messages.size() - 1;
        LogUtil.d(TAG, "AI placeholder added at index=%d, total=%d", aiIndex, messages.size());

        // Append AI placeholder div
        runOnUiThread(new Runnable() {
            public void run() {
                LogUtil.v(TAG, "webview js: appendAiDiv()");
                conversationWebView.loadUrl("javascript:appendAiDiv()");
            }
        });

        currentRequestThread = new Thread(new Runnable() {
            public void run() {
                final StringBuilder rawContent = new StringBuilder();
                final boolean[] thinkingActive = {false};
                final boolean[] thinkingFinished = {false};
                final int[] chunkCount = {0};
                final int[] thinkingChunks = {0};
                final int[] contentChunks = {0};

                HttpURLConnection conn = null;
                try {
                    String endpoint = provider.apiUrl + provider.chatPath;
                    java.net.URL url = new java.net.URL(endpoint);
                    LogUtil.d(TAG, "opening connection: %s (thread=%s)", endpoint, LogUtil.thread());
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setRequestProperty("Authorization", "Bearer " + provider.apiKey);
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(30000);
                    conn.setReadTimeout(0);

                    if (conn instanceof javax.net.ssl.HttpsURLConnection) {
                        TlsCompat.apply((javax.net.ssl.HttpsURLConnection) conn);
                    }

                    currentConnection = conn;

                    org.json.JSONObject body = new org.json.JSONObject();
                    body.put("model", currentModel);
                    body.put("stream", true);

                    org.json.JSONArray msgs = new org.json.JSONArray();
                    if (systemPrompt != null && !systemPrompt.isEmpty()) {
                        org.json.JSONObject sm = new org.json.JSONObject();
                        sm.put("role", "system");
                        sm.put("content", systemPrompt);
                        msgs.put(sm);
                    }
                    for (Message m : messages) {
                        if (m == aiMsg) continue;
                        org.json.JSONObject mm = new org.json.JSONObject();
                        mm.put("role", m.role);
                        mm.put("content", m.isAssistant() ? ApiClient.removeThinkingContent(m.content) : m.content);
                        msgs.put(mm);
                    }
                    body.put("messages", msgs);

                    if ("boolean".equals(provider.thinkingType)) {
                        if (!"off".equals(thinkingLevel)) {
                            body.put(provider.thinkingParamName, true);
                            int budget = 4096;
                            if ("low".equals(thinkingLevel)) budget = 2048;
                            else if ("high".equals(thinkingLevel)) budget = 32768;
                            body.put("thinking_budget", budget);
                        }
                    } else {
                        org.json.JSONObject tObj = new org.json.JSONObject();
                        tObj.put("type", "off".equals(thinkingLevel) ? "disabled" : "enabled");
                        body.put(provider.thinkingParamName, tObj);
                        if (!"off".equals(thinkingLevel)) {
                            body.put("reasoning_effort", "high".equals(thinkingLevel) ? "max" : "high");
                        }
                    }

                    LogUtil.d(TAG, "request body built: len=%d", body.toString().length());
                    LogUtil.v(TAG, "request body: %s", LogUtil.preview(body.toString(), 1500));

                    long reqMs = System.currentTimeMillis();
                    java.io.OutputStream os = conn.getOutputStream();
                    os.write(body.toString().getBytes("UTF-8"));
                    os.close();
                    LogUtil.d(TAG, "request body written in %d ms", System.currentTimeMillis() - reqMs);

                    int code = conn.getResponseCode();
                    LogUtil.i(TAG, "HTTP response: code=%d in %d ms", code, System.currentTimeMillis() - reqMs);
                    if (code != 200) {
                        String errBody = null;
                        try {
                            java.io.InputStream es = conn.getErrorStream();
                            if (es != null) {
                                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                                byte[] buf = new byte[2048];
                                int n;
                                while ((n = es.read(buf)) > 0) bos.write(buf, 0, n);
                                errBody = new String(bos.toByteArray(), "UTF-8");
                            }
                        } catch (Exception ignored) {}
                        LogUtil.e(TAG, "request FAILED: HTTP %d, body=%s", code, LogUtil.preview(errBody, 800));
                        final String err = "HTTP " + code;
                        runOnUiThread(new Runnable() {
                            public void run() { Toast.makeText(MainActivity.this, "请求失败: " + err, Toast.LENGTH_SHORT).show(); }
                        });
                        return;
                    }

                    java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"));
                    String line;
                    long lastUpdate = 0;
                    LogUtil.i(TAG, "SSE stream started, begin reading chunks");
                    while (isRequestInProgress.get() && (line = reader.readLine()) != null) {
                        line = line.trim();
                        if (!line.startsWith("data: ")) {
                            if (line.length() > 0) {
                                LogUtil.v(TAG, "skip non-data line: %s", LogUtil.preview(line, 120));
                            }
                            continue;
                        }
                        String data = line.substring(6);
                        if ("[DONE]".equals(data)) {
                            LogUtil.i(TAG, "SSE [DONE] received after %d chunks", chunkCount[0]);
                            break;
                        }
                        chunkCount[0]++;

                        try {
                            org.json.JSONObject json = new org.json.JSONObject(data);
                            org.json.JSONArray choices = json.optJSONArray("choices");
                            if (choices == null || choices.length() == 0) {
                                LogUtil.v(TAG, "chunk#%d has no choices: %s", chunkCount[0], LogUtil.preview(data, 200));
                                continue;
                            }
                            org.json.JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
                            if (delta == null) {
                                LogUtil.v(TAG, "chunk#%d has no delta", chunkCount[0]);
                                continue;
                            }

                            if (delta.has("reasoning_content") && !delta.isNull("reasoning_content")) {
                                String rc = delta.getString("reasoning_content");
                                thinkingChunks[0]++;
                                if (!thinkingActive[0]) {
                                    thinkingActive[0] = true;
                                    rawContent.append("[thinking]");
                                    LogUtil.d(TAG, "thinking phase START at chunk#%d", chunkCount[0]);
                                }
                                rawContent.append(rc);
                                if (LogUtil.ENABLED) {
                                    LogUtil.v(TAG, "reasoning chunk#%d: %s", thinkingChunks[0], LogUtil.preview(rc, 150));
                                }
                            }

                            if (delta.has("content") && !delta.isNull("content")) {
                                String ct = delta.getString("content");
                                contentChunks[0]++;
                                if (thinkingActive[0] && !thinkingFinished[0]) {
                                    thinkingFinished[0] = true;
                                    rawContent.append("[/thinking]");
                                    LogUtil.d(TAG, "thinking phase END at chunk#%d (thinkingChunks=%d)",
                                            chunkCount[0], thinkingChunks[0]);
                                }
                                rawContent.append(ct);
                                if (LogUtil.ENABLED) {
                                    LogUtil.v(TAG, "content chunk#%d: %s", contentChunks[0], LogUtil.preview(ct, 150));
                                }
                            }

                            // Throttle: light text update at most every 200ms
                            long now = System.currentTimeMillis();
                            if (now - lastUpdate > 200) {
                                lastUpdate = now;
                                final String content = rawContent.toString();
                                runOnUiThread(new Runnable() {
                                    public void run() {
                                        if (requestGeneration.get() == generation && aiIndex < messages.size()) {
                                            messages.get(aiIndex).content = content;
                                            String esc = jsEscape(content);
                                            conversationWebView.loadUrl("javascript:updateLastText('" + esc + "')");
                                        } else {
                                            LogUtil.w(TAG, "throttled update skipped: aiIndex=%d >= size=%d",
                                                    aiIndex, messages.size());
                                        }
                                    }
                                });
                            }
                        } catch (Exception e) {
                            LogUtil.w(TAG, "failed to parse SSE chunk#%d: %s | raw=%s",
                                    chunkCount[0], e.getMessage(), LogUtil.preview(data, 300));
                        }
                    }
                    // Final update: full render with markdown/LaTeX/highlighting
                    final String finalContent = rawContent.toString();
                    LogUtil.i(TAG, "stream finished: chunks=%d (thinking=%d, content=%d), finalLen=%d, elapsed=%d ms",
                            chunkCount[0], thinkingChunks[0], contentChunks[0],
                            finalContent.length(), System.currentTimeMillis() - startMs);
                    new Thread(new Runnable() {
                        public void run() {
                            long renderMs = System.currentTimeMillis();
                            final String html = MessageHtmlRenderer.contentToHtml(finalContent, MainActivity.this);
                            LogUtil.d(TAG, "html rendered: len=%d in %d ms", html.length(),
                                    System.currentTimeMillis() - renderMs);
                            runOnUiThread(new Runnable() {
                                public void run() {
                                    // 代数匹配且索引有效时才更新，防止旧请求覆盖新请求
                                    if (requestGeneration.get() == generation && aiIndex < messages.size()) {
                                        messages.get(aiIndex).content = finalContent;
                                        String esc = jsEscape(html);
                                        conversationWebView.loadUrl("javascript:updateLastMsg('" + esc + "')");
                                        conversationWebView.loadUrl("javascript:finalizeLast(" + aiIndex + ")");
                                    } else {
                                        LogUtil.w(TAG, "final update skipped: aiIndex=%d >= size=%d",
                                                aiIndex, messages.size());
                                    }
                                }
                            });
                        }
                    }).start();
                    reader.close();
                } catch (final Exception e) {
                    LogUtil.e(TAG, "streaming request EXCEPTION: %s", e.getMessage(), e);
                    runOnUiThread(new Runnable() {
                        public void run() {
                            Toast.makeText(MainActivity.this, "请求失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    });
                } finally {
                    LogUtil.i(TAG, "<<< sendStreamingRequest END: elapsed=%d ms, chunks=%d, interrupted=%s",
                            System.currentTimeMillis() - startMs, chunkCount[0], !isRequestInProgress.get());
                    if (conn != null) conn.disconnect();
                    // 只有当前请求代数匹配时才清理状态，防止旧线程污染新请求
                    if (requestGeneration.get() == generation) {
                        currentConnection = null;
                        isRequestInProgress.set(false);
                        conversationManager.saveCurrentConversation();
                        final int finalIdx = aiIndex;
                        runOnUiThread(new Runnable() {
                            public void run() {
                                conversationWebView.loadUrl("javascript:finalizeLast(" + finalIdx + ")");
                            }
                        });
                    }
                }
            }
        });
        currentRequestThread.setName("aichat-stream-" + System.currentTimeMillis());
        currentRequestThread.start();
        LogUtil.d(TAG, "request thread started: %s", currentRequestThread.getName());
    }

    private void generateTitle(final String firstMessage) {
        LogUtil.d(TAG, "generateTitle called for message: %s", LogUtil.preview(firstMessage, 100));
        new Thread(new Runnable() {
            public void run() {
                try {
                    // Get title model from settings, then find its provider
                    String titleModel = SettingsActivity.getTitleModel(MainActivity.this);
                    LogUtil.d(TAG, "generateTitle: titleModel=%s", titleModel);
                    ModelInfo tmi = configManager.getModel(titleModel);
                    if (tmi == null) {
                        LogUtil.w(TAG, "generateTitle aborted: model '%s' not found", titleModel);
                        return;
                    }
                    ProviderInfo titleProvider = configManager.getProvider(tmi.provider);
                    if (titleProvider == null) {
                        LogUtil.w(TAG, "generateTitle aborted: provider '%s' not found", tmi.provider);
                        return;
                    }
                    LogUtil.d(TAG, "generateTitle: using %s @ %s", tmi.name, titleProvider.name);

                    String titlePrompt = SettingsActivity.getTitlePrompt(MainActivity.this);
                    List<Message> titleMsgs = new ArrayList<Message>();
                    Message sysMsg = new Message(Message.ROLE_SYSTEM, titlePrompt);
                    Message userMsg = new Message(Message.ROLE_USER,
                            "根据以上要求，为以下对话生成标题：\n" + firstMessage);
                    titleMsgs.add(sysMsg);
                    titleMsgs.add(userMsg);

                    long titleMs = System.currentTimeMillis();
                    ApiClient.CallResult result = ApiClient.callWithError(titleProvider,
                            titleModel, titleMsgs, "", "off");
                    LogUtil.d(TAG, "generateTitle: api returned in %d ms, response=%s, error=%s",
                            System.currentTimeMillis() - titleMs,
                            LogUtil.preview(result == null ? null : result.response, 400),
                            result == null ? "null-result" : result.error);

                    if (result.response != null) {
                        org.json.JSONObject json = new org.json.JSONObject(result.response);
                        org.json.JSONArray choices = json.optJSONArray("choices");
                        if (choices != null && choices.length() > 0) {
                            org.json.JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
                            if (msg != null) {
                                String raw = stripThinkTags(msg.optString("content", ""));
                                String title = raw.trim()
                                        .replaceAll("[\"''\"'.:;,!，。：；！？]", "").trim();
                                if (title.length() > 20) title = title.substring(0, 20);
                                LogUtil.i(TAG, "generateTitle: raw=%s -> title=%s",
                                        LogUtil.preview(raw, 120), title);

                                final String finalTitle = title.length() > 0 ? title : "新对话";
                                runOnUiThread(new Runnable() {
                                    public void run() {
                                        Conversation conv = conversationManager.getCurrentConversation();
                                        if (conv != null && !conv.titleGenerated) {
                                            conv.title = finalTitle;
                                            conv.titleGenerated = true;
                                            conversationManager.saveCurrentConversation();
                                            LogUtil.i(TAG, "conversation title set to: %s", finalTitle);
                                        } else {
                                            LogUtil.d(TAG, "title not applied (conv=%s, generated=%s)",
                                                    conv, conv == null ? "-" : conv.titleGenerated);
                                        }
                                    }
                                });
                            } else {
                                LogUtil.w(TAG, "generateTitle: response has no message object");
                            }
                        } else {
                            LogUtil.w(TAG, "generateTitle: response has no choices");
                        }
                    }
                } catch (final Exception e) {
                    LogUtil.e(TAG, "generateTitle EXCEPTION: " + e.getMessage(), e);
                }
            }
        }) {{ setName("aichat-title-" + System.currentTimeMillis()); }}.start();
    }

    /** Remove inline <think>...</think> blocks some models emit in content. */
    private static String stripThinkTags(String s) {
        if (s == null) return "";
        String out = s.replaceAll("(?s)<think>.*?</think>", "");
        int open = out.indexOf("<think>");
        if (open != -1) out = out.substring(0, open); // unclosed block: drop the tail
        return out;
    }

    // ========== WebView helpers ==========

    private void refreshWebView() {
        LogUtil.d(TAG, "refreshWebView: messages=%d", messages == null ? -1 : messages.size());
        final long t0 = System.currentTimeMillis();
        new Thread(new Runnable() {
            public void run() {
                final String html = MessageHtmlRenderer.buildConversationHtml(messages, MainActivity.this);
                LogUtil.d(TAG, "conversation html built: len=%d in %d ms",
                        html == null ? -1 : html.length(), System.currentTimeMillis() - t0);
                runOnUiThread(new Runnable() {
                    public void run() {
                        conversationWebView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
                        LogUtil.d(TAG, "webview reloaded with full conversation html");
                    }
                });
            }
        }) {{ setName("aichat-render-" + System.currentTimeMillis()); }}.start();
    }

    private void appendHtml(String msgHtml) {
        String esc = jsEscape(msgHtml);
        LogUtil.v(TAG, "webview js: appendMsg(len=%d)", esc.length());
        conversationWebView.loadUrl("javascript:appendMsg('" + esc + "')");
    }

    private void updateAiContent(String content) {
        String html = MessageHtmlRenderer.contentToHtml(content, this);
        String esc = jsEscape(html);
        LogUtil.v(TAG, "webview js: updateLastMsg(htmlLen=%d)", esc.length());
        conversationWebView.loadUrl("javascript:updateLastMsg('" + esc + "')");
    }

    private void removeDomFrom(int pos) {
        LogUtil.v(TAG, "webview js: removeFromIdx(%d)", pos);
        conversationWebView.loadUrl("javascript:removeFromIdx(" + pos + ")");
    }

    private void removeDomRange(int fromPos) {
        LogUtil.v(TAG, "webview js: removeRangeFrom(%d)", fromPos);
        conversationWebView.loadUrl("javascript:removeRangeFrom(" + fromPos + ")");
    }

    private static String jsEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    // ========== Image info ==========

    private static class ImageInfo {
        String src;
        String alt;
        String svg;   // raw SVG HTML for pure vector SVGs (AI-generated)
        String type;  // "raster" or "svg"
    }

    // ========== JavaScript bridge ==========

    class JsBridge {
        @JavascriptInterface
        public void copyCode(final String code) {
            LogUtil.d(TAG, "JsBridge.copyCode: len=%s", code == null ? "null" : code.length());
            handler.post(new Runnable() {
                public void run() {
                    try {
                        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newPlainText("code", code));
                        Toast.makeText(MainActivity.this, "代码已复制", Toast.LENGTH_SHORT).show();
                    } catch (Exception e1) {
                        LogUtil.w(TAG, "copyCode via ClipboardManager failed: %s, trying legacy API", e1.getMessage());
                        try {
                            android.text.ClipboardManager oldCm = (android.text.ClipboardManager)
                                    getSystemService(Context.CLIPBOARD_SERVICE);
                            oldCm.setText(code);
                            Toast.makeText(MainActivity.this, "代码已复制", Toast.LENGTH_SHORT).show();
                        } catch (Exception e2) {
                            LogUtil.e(TAG, "copyCode failed entirely: " + e2.getMessage(), e2);
                            Toast.makeText(MainActivity.this, "复制失败: " + e2.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            });
        }

        @JavascriptInterface
        public void openUrl(String url) {
            LogUtil.d(TAG, "JsBridge.openUrl: %s", url);
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception e) {
                LogUtil.e(TAG, "openUrl failed: " + e.getMessage(), e);
                Toast.makeText(MainActivity.this, "无法打开链接", Toast.LENGTH_SHORT).show();
            }
        }

        @JavascriptInterface
        public void messageMenu(final String idx) {
            LogUtil.d(TAG, "JsBridge.messageMenu: idx=%s", idx);
            handler.post(new Runnable() {
                public void run() {
                    try {
                        showMessageMenu(Integer.parseInt(idx));
                    } catch (Exception e) {
                        LogUtil.w(TAG, "messageMenu parse failed: idx=%s err=%s", idx, e.getMessage());
                    }
                }
            });
        }

        @JavascriptInterface
        public void messageLongPress(final String idx) {
            LogUtil.d(TAG, "JsBridge.messageLongPress: idx=%s", idx);
            handler.post(new Runnable() {
                public void run() {
                    try {
                        showMessageMenu(Integer.parseInt(idx));
                    } catch (Exception e) {
                        LogUtil.w(TAG, "messageLongPress parse failed: idx=%s err=%s", idx, e.getMessage());
                    }
                }
            });
        }

        @JavascriptInterface
        public void showImageViewer(final String indexStr, final String imagesJson) {
            handler.post(new Runnable() {
                public void run() {
                    try {
                        showImageViewerDialog(Integer.parseInt(indexStr), imagesJson);
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "查看图片失败", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }

        @JavascriptInterface
        public void showTableViewer(final String tableHtml) {
            handler.post(new Runnable() {
                public void run() {
                    showTableViewerDialog(tableHtml);
                }
            });
        }

        @JavascriptInterface
        public void previewCode(final String lang, final String code) {
            handler.post(new Runnable() {
                public void run() {
                    showCodePreviewDialog(lang, code);
                }
            });
        }
    }

    // ========== 图片查看器 ==========

    private void showImageViewerDialog(int index, String imagesJson) {
        LogUtil.i(TAG, "showImageViewerDialog: open idx=%d imagesJson len=%d",
                index, imagesJson == null ? -1 : imagesJson.length());
        if (imageViewerDialog != null && imageViewerDialog.isShowing()) {
            imageViewerDialog.dismiss();
        }

        List<ImageInfo> images = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(imagesJson);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                ImageInfo info = new ImageInfo();
                info.src = obj.optString("src", "");
                info.alt = obj.optString("alt", "");
                info.type = obj.optString("type", "raster");
                info.svg = obj.optString("svg", "");
                images.add(info);
            }
        } catch (Exception e) {
            Toast.makeText(this, "无法加载图片列表", Toast.LENGTH_SHORT).show();
            return;
        }

        if (images.isEmpty() || index < 0 || index >= images.size()) {
            Toast.makeText(this, "图片不可用", Toast.LENGTH_SHORT).show();
            return;
        }

        currentImageList = images;
        currentImageIndex = index;
        currentRotation = 0;
        // Default bg: white for SVG (often dark lines on transparent), black for raster
        ImageInfo firstInfo = images.get(index);
        currentBgColor = ("svg".equals(firstInfo.type)) ? 0 : 2;

        View view = getLayoutInflater().inflate(R.layout.dialog_image_viewer, null);
        imageViewerWebView = (WebView) view.findViewById(R.id.viewer_webview);
        imageCounterText = (TextView) view.findViewById(R.id.viewer_counter);
        prevButton = (Button) view.findViewById(R.id.viewer_prev);
        nextButton = (Button) view.findViewById(R.id.viewer_next);
        zoomOutBtn = (Button) view.findViewById(R.id.viewer_zoom_out);
        zoomInBtn = (Button) view.findViewById(R.id.viewer_zoom_in);
        rotateBtn = (Button) view.findViewById(R.id.viewer_rotate);
        resetBtn = (Button) view.findViewById(R.id.viewer_reset);
        bgToggleBtn = (Button) view.findViewById(R.id.viewer_bg_toggle);
        Button closeBtn = (Button) view.findViewById(R.id.viewer_close);

        // Configure WebView for pinch-to-zoom and double-tap zoom
        imageViewerWebView.getSettings().setJavaScriptEnabled(true);
        imageViewerWebView.getSettings().setBuiltInZoomControls(true);
        imageViewerWebView.getSettings().setDisplayZoomControls(false);
        imageViewerWebView.getSettings().setUseWideViewPort(true);
        imageViewerWebView.getSettings().setLoadWithOverviewMode(true);
        imageViewerWebView.getSettings().setSupportZoom(true);
        imageViewerWebView.setBackgroundColor(Color.BLACK);
        imageViewerWebView.setWebViewClient(new WebViewClient() {
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return true;
            }
        });

        prevButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (currentImageIndex > 0) {
                    currentImageIndex--;
                    currentRotation = 0;
                    ImageInfo info = currentImageList.get(currentImageIndex);
                    currentBgColor = ("svg".equals(info.type)) ? 0 : 2;
                    loadCurrentImage();
                }
            }
        });

        nextButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (currentImageIndex < currentImageList.size() - 1) {
                    currentImageIndex++;
                    currentRotation = 0;
                    ImageInfo info = currentImageList.get(currentImageIndex);
                    currentBgColor = ("svg".equals(info.type)) ? 0 : 2;
                    loadCurrentImage();
                }
            }
        });

        zoomOutBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                viewerEvalJs(imageViewerWebView, "viewerZoom(0.8)");
            }
        });

        zoomInBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                viewerEvalJs(imageViewerWebView, "viewerZoom(1.25)");
            }
        });

        rotateBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                currentRotation = (currentRotation + 90) % 360;
                loadCurrentImage();
            }
        });

        resetBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                currentRotation = 0;
                loadCurrentImage();
            }
        });

        bgToggleBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                currentBgColor = (currentBgColor + 1) % 3;
                applyBgColor();
            }
        });

        closeBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (imageViewerDialog != null) imageViewerDialog.dismiss();
            }
        });

        imageViewerDialog = new AlertDialog.Builder(this)
                .setView(view)
                .setCancelable(true)
                .create();

        imageViewerDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            public void onDismiss(DialogInterface dialog) {
                if (imageViewerWebView != null) {
                    imageViewerWebView.destroy();
                    imageViewerWebView = null;
                }
                imageViewerDialog = null;
            }
        });

        imageViewerDialog.getWindow().setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        imageViewerDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));

        imageViewerDialog.show();
        loadAfterLayout(imageViewerWebView, new Runnable() {
            public void run() { loadCurrentImage(); }
        });
    }

    private void loadCurrentImage() {
        if (currentImageList == null || imageCounterText == null
                || imageViewerWebView == null) return;

        ImageInfo info = currentImageList.get(currentImageIndex);
        LogUtil.d(TAG, "loadCurrentImage: idx=%d/%d type=%s src=%s",
                currentImageIndex, currentImageList.size(), info.type, LogUtil.preview(info.src, 120));
        imageCounterText.setText((currentImageIndex + 1) + "/" + currentImageList.size());
        prevButton.setEnabled(currentImageIndex > 0);
        nextButton.setEnabled(currentImageIndex < currentImageList.size() - 1);

        String bgColor = getBgColorHex(currentBgColor);

        String rotateCss = currentRotation != 0
                ? "-webkit-transform:rotate(" + currentRotation + "deg);transform:rotate(" + currentRotation + "deg);"
                : "";

        String html;
        if ("svg".equals(info.type) && info.svg != null && !info.svg.isEmpty()) {
            // Pure vector SVG — no flex centering, natural flow with scrolling
            String svgHtml = info.svg;
            html = "<!DOCTYPE html><html><head>" +
                    "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0,user-scalable=yes\">" +
                    "<style>" +
                    "*{margin:0;padding:0;}" +
                    "html,body{width:100%;min-height:100%;background:" + bgColor + ";" +
                    "overflow:auto;-webkit-transform-origin:0 0;transform-origin:0 0;}" +
                    ".svg-wrap{width:100%;max-width:100%;" + rotateCss + "}" +
                    ".svg-wrap svg{width:100%;height:auto;max-width:100%;display:block;}" +
                    "</style>" +
                    "<script>var vZoom=1;function viewerZoom(f){vZoom=Math.min(5,Math.max(0.1,vZoom*f));" +
                    "var t='scale('+vZoom+')';" +
                    "document.body.style.webkitTransform=t;document.body.style.transform=t;}" +
                    "function viewerSetBg(c){document.body.style.backgroundColor=c;}</script>" +
                    "</head><body>" +
                    "<div class=\"svg-wrap\">" + svgHtml + "</div>" +
                    "</body></html>";
        } else {
            // Raster image — flex centering for single image
            String src = MessageHtmlRenderer.escAttr(info.src);
            String alt = MessageHtmlRenderer.escAttr(info.alt);
            html = "<!DOCTYPE html><html><head>" +
                    "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0,user-scalable=yes\">" +
                    "<style>" +
                    "*{margin:0;padding:0;}" +
                    "html,body{width:100%;min-height:100%;background:" + bgColor + ";" +
                    "display:flex;align-items:center;justify-content:center;" +
                    "overflow:auto;-webkit-transform-origin:0 0;transform-origin:0 0;}" +
                    "img,svg{max-width:100%;max-height:100%;" + rotateCss + "}" +
                    "</style>" +
                    "<script>var vZoom=1;function viewerZoom(f){vZoom=Math.min(5,Math.max(0.1,vZoom*f));" +
                    "var t='scale('+vZoom+')';" +
                    "document.body.style.webkitTransform=t;document.body.style.transform=t;}" +
                    "function viewerSetBg(c){document.body.style.backgroundColor=c;}</script>" +
                    "</head><body>" +
                    "<img src=\"" + src + "\" alt=\"" + alt + "\">" +
                    "</body></html>";
        }

        imageViewerWebView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    // ========== 表格查看器 ==========

    private void showTableViewerDialog(String tableHtml) {
        LogUtil.i(TAG, "showTableViewerDialog: open, html len=%d",
                tableHtml == null ? -1 : tableHtml.length());
        if (tableViewerDialog != null && tableViewerDialog.isShowing()) {
            tableViewerDialog.dismiss();
        }

        if (tableHtml == null || tableHtml.isEmpty()) {
            Toast.makeText(this, "表格内容为空", Toast.LENGTH_SHORT).show();
            return;
        }

        tableRotation = 0;
        tableBgColor = 0; // default white for table viewer

        View view = getLayoutInflater().inflate(R.layout.dialog_image_viewer, null);
        tableViewerWebView = (WebView) view.findViewById(R.id.viewer_webview);

        // Hide image-specific controls
        view.findViewById(R.id.viewer_prev).setVisibility(View.GONE);
        view.findViewById(R.id.viewer_next).setVisibility(View.GONE);
        view.findViewById(R.id.viewer_counter).setVisibility(View.GONE);
        view.findViewById(R.id.viewer_bg_toggle).setVisibility(View.GONE);

        Button zoomOutBtn = (Button) view.findViewById(R.id.viewer_zoom_out);
        Button zoomInBtn = (Button) view.findViewById(R.id.viewer_zoom_in);
        Button rotateBtn = (Button) view.findViewById(R.id.viewer_rotate);
        Button resetBtn = (Button) view.findViewById(R.id.viewer_reset);
        Button bgToggleBtn = (Button) view.findViewById(R.id.viewer_bg_toggle);
        Button closeBtn = (Button) view.findViewById(R.id.viewer_close);

        tableViewerWebView.getSettings().setJavaScriptEnabled(true);
        tableViewerWebView.getSettings().setBuiltInZoomControls(true);
        tableViewerWebView.getSettings().setDisplayZoomControls(false);
        tableViewerWebView.getSettings().setUseWideViewPort(true);
        tableViewerWebView.getSettings().setLoadWithOverviewMode(true);
        tableViewerWebView.getSettings().setSupportZoom(true);
        tableViewerWebView.setBackgroundColor(Color.WHITE);
        tableViewerWebView.setWebViewClient(new WebViewClient() {
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return true;
            }
        });

        zoomOutBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { viewerEvalJs(tableViewerWebView, "viewerZoom(0.8)"); }
        });
        zoomInBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { viewerEvalJs(tableViewerWebView, "viewerZoom(1.25)"); }
        });
        rotateBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                tableRotation = (tableRotation + 90) % 360;
                loadTableContent(tableHtml);
            }
        });
        resetBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                tableRotation = 0;
                loadTableContent(tableHtml);
            }
        });
        bgToggleBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                tableBgColor = (tableBgColor + 1) % 3;
                applyBgColor();
            }
        });
        closeBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (tableViewerDialog != null) tableViewerDialog.dismiss();
            }
        });

        tableViewerDialog = new AlertDialog.Builder(this)
                .setView(view)
                .setCancelable(true)
                .create();

        tableViewerDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            public void onDismiss(DialogInterface dialog) {
                if (tableViewerWebView != null) {
                    tableViewerWebView.destroy();
                    tableViewerWebView = null;
                }
                tableViewerDialog = null;
            }
        });

        tableViewerDialog.getWindow().setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        tableViewerDialog.show();

        loadAfterLayout(tableViewerWebView, new Runnable() {
            public void run() { loadTableContent(tableHtml); }
        });
    }

    private void loadTableContent(String tableHtml) {
        if (tableViewerWebView == null) return;

        LogUtil.d(TAG, "loadTableContent: len=%d rot=%d", tableHtml.length(), tableRotation);
        String rotateCss = "";
        String rotateJs = "";
        if (tableRotation != 0) {
            rotateCss = "-webkit-transform:rotate(" + tableRotation + "deg);"
                      + "transform:rotate(" + tableRotation + "deg);";
            // Make wrapper a square large enough to hold the rotated table,
            // so the layout box covers the full visual area (API 18 doesn't
            // add scroll overflow for CSS transforms).
            rotateJs = "<script>" +
                "(function fix(a){" +
                "var w=document.querySelector('.table-wrap');" +
                "var t=w&&w.querySelector('table');" +
                "if(!t)return;" +
                "var ow=t.offsetWidth,oh=t.offsetHeight;" +
                "if((!ow||!oh)&&a<20){setTimeout(function(){fix(a+1);},50);return;}" +
                "var s=Math.max(ow,oh);" +
                "w.style.width=s+'px';" +
                "w.style.height=s+'px';" +
                "var m=document.querySelector('meta[name=viewport]');" +
                "if(m)m.setAttribute('content','width='+s+',user-scalable=yes');" +
                "})(0);" +
                "</script>";
        }

        String bgColor = getBgColorHex(tableBgColor);

        String html = "<!DOCTYPE html><html><head>" +
                "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0,user-scalable=yes\">" +
                "<style>" +
                "*{margin:0;padding:0;}" +
                "html,body{background:" + bgColor + ";padding:8px;overflow:auto;" +
                "-webkit-transform-origin:0 0;transform-origin:0 0;}" +
                ".table-wrap{display:inline-block;" + rotateCss + "}" +
                "table{border-collapse:collapse;font-size:14px;}" +
                "th,td{border:1px solid #ddd;padding:8px 12px;text-align:left;}" +
                "th{background:#f0f0f0;font-weight:bold;white-space:nowrap;}" +
                "td{white-space:nowrap;}" +
                "</style>" +
                "<script>var vZoom=1;function viewerZoom(f){vZoom=Math.min(5,Math.max(0.1,vZoom*f));" +
                "var t='scale('+vZoom+')';" +
                "document.body.style.webkitTransform=t;document.body.style.transform=t;}" +
                "function viewerSetBg(c){document.body.style.backgroundColor=c;}</script>" +
                "</head><body>" +
                "<div class=\"table-wrap\">" + tableHtml + "</div>" +
                rotateJs +
                "</body></html>";

        tableViewerWebView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    // ========== HTML / SVG 代码预览 ==========

    private void showCodePreviewDialog(String lang, String code) {
        LogUtil.i(TAG, "showCodePreviewDialog: open lang=%s code len=%d",
                lang, code == null ? -1 : code.length());
        if (codePreviewDialog != null && codePreviewDialog.isShowing()) {
            codePreviewDialog.dismiss();
        }

        if (code == null || code.isEmpty()) {
            Toast.makeText(this, "代码内容为空", Toast.LENGTH_SHORT).show();
            return;
        }

        codePreviewRotation = 0;
        codePreviewBgColor = 0;
        currentPreviewLang = lang;
        currentPreviewCode = code;

        View view = getLayoutInflater().inflate(R.layout.dialog_image_viewer, null);
        codePreviewWebView = (WebView) view.findViewById(R.id.viewer_webview);

        // Hide image-specific controls
        view.findViewById(R.id.viewer_prev).setVisibility(View.GONE);
        view.findViewById(R.id.viewer_next).setVisibility(View.GONE);
        view.findViewById(R.id.viewer_counter).setVisibility(View.GONE);

        Button zoomOutBtn = (Button) view.findViewById(R.id.viewer_zoom_out);
        Button zoomInBtn = (Button) view.findViewById(R.id.viewer_zoom_in);
        Button rotateBtn = (Button) view.findViewById(R.id.viewer_rotate);
        Button resetBtn = (Button) view.findViewById(R.id.viewer_reset);
        Button bgToggleBtn = (Button) view.findViewById(R.id.viewer_bg_toggle);
        Button closeBtn = (Button) view.findViewById(R.id.viewer_close);

        codePreviewWebView.getSettings().setJavaScriptEnabled(true);
        codePreviewWebView.getSettings().setBuiltInZoomControls(true);
        codePreviewWebView.getSettings().setDisplayZoomControls(false);
        codePreviewWebView.getSettings().setUseWideViewPort(true);
        codePreviewWebView.getSettings().setLoadWithOverviewMode(true);
        codePreviewWebView.getSettings().setSupportZoom(true);
        codePreviewWebView.setBackgroundColor(Color.WHITE);
        codePreviewWebView.setWebViewClient(new WebViewClient() {
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return true;
            }
        });

        zoomOutBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { viewerEvalJs(codePreviewWebView, "viewerZoom(0.8)"); }
        });
        zoomInBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { viewerEvalJs(codePreviewWebView, "viewerZoom(1.25)"); }
        });
        rotateBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                codePreviewRotation = (codePreviewRotation + 90) % 360;
                loadCodePreview();
            }
        });
        resetBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                codePreviewRotation = 0;
                loadCodePreview();
            }
        });
        bgToggleBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                codePreviewBgColor = (codePreviewBgColor + 1) % 3;
                applyCodePreviewBg();
            }
        });
        closeBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (codePreviewDialog != null) codePreviewDialog.dismiss();
            }
        });

        codePreviewDialog = new AlertDialog.Builder(this)
                .setView(view)
                .setCancelable(true)
                .create();

        codePreviewDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            public void onDismiss(DialogInterface dialog) {
                if (codePreviewWebView != null) {
                    codePreviewWebView.destroy();
                    codePreviewWebView = null;
                }
                codePreviewDialog = null;
            }
        });

        codePreviewDialog.getWindow().setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        codePreviewDialog.show();

        loadAfterLayout(codePreviewWebView, new Runnable() {
            public void run() { loadCodePreview(); }
        });
    }

    /**
     * Run the initial viewer load only after the dialog WebView has been laid
     * out. Loading before the first layout pass renders the page into a 0×0
     * viewport (blank/garbled preview until the user hits reset "#", which
     * reloads at the correct size).
     */
    private void loadAfterLayout(final WebView wv, final Runnable load) {
        if (wv.getWidth() > 0 && wv.getHeight() > 0) {
            load.run();
            return;
        }
        wv.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
            public void onGlobalLayout() {
                wv.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                load.run();
            }
        });
    }

    private void loadCodePreview() {
        if (codePreviewWebView == null || currentPreviewCode == null) return;

        LogUtil.d(TAG, "loadCodePreview: lang=%s code len=%d rot=%d",
                currentPreviewLang, currentPreviewCode.length(), codePreviewRotation);
        String bgColor = getBgColorHex(codePreviewBgColor);

        String rotateCss = codePreviewRotation != 0
                ? "-webkit-transform:rotate(" + codePreviewRotation + "deg);"
                + "transform:rotate(" + codePreviewRotation + "deg);"
                : "";

        String html;
        if ("svg".equals(currentPreviewLang)) {
            html = buildCodePreviewHtml(currentPreviewCode, currentPreviewLang,
                    bgColor, rotateCss, false);
        } else {
            // HTML: detect if it's a full document or fragment
            String trimmed = currentPreviewCode.trim().toLowerCase();
            boolean isFullDoc = trimmed.startsWith("<!doctype") || trimmed.startsWith("<html");
            html = buildCodePreviewHtml(currentPreviewCode, currentPreviewLang,
                    bgColor, rotateCss, isFullDoc);
        }

        codePreviewWebView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    private String buildCodePreviewHtml(String code, String lang,
            String bgColor, String rotateCss, boolean isFullHtmlDoc) {
        String zoomJs =
                "var vZoom=1;" +
                "function viewerZoom(f){vZoom=Math.min(5,Math.max(0.1,vZoom*f));" +
                "var t='scale('+vZoom+')';" +
                "document.body.style.webkitTransform=t;document.body.style.transform=t;}" +
                "function viewerSetBg(c){document.body.style.backgroundColor=c;}";

        if ("svg".equals(lang)) {
            // Wrap SVG code in a minimal HTML page
            return "<!DOCTYPE html><html><head>" +
                    "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0,user-scalable=yes\">" +
                    "<style>" +
                    "*{margin:0;padding:0;}" +
                    "html,body{width:100%;min-height:100%;background:" + bgColor + ";" +
                    "overflow:auto;-webkit-transform-origin:0 0;transform-origin:0 0;}" +
                    ".preview-wrap{" + rotateCss + "}" +
                    ".preview-wrap svg{max-width:100%;height:auto;display:block;}" +
                    "</style>" +
                    "<script>" + zoomJs + "</script>" +
                    "</head><body>" +
                    "<div class=\"preview-wrap\">" + code + "</div>" +
                    "</body></html>";
        }

        if (isFullHtmlDoc) {
            // Inject zoom/rotate/bg scripts into existing HTML document
            String inject = "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0,user-scalable=yes\">"
                    + "<style>" +
                    "html,body{" + rotateCss + "-webkit-transform-origin:0 0;transform-origin:0 0;}"
                    + "</style>"
                    + "<script>" + zoomJs + "</script>";
            // Insert after <head> or after <html>
            if (code.toLowerCase().contains("<head>")) {
                return code.replaceFirst("(?i)<head[^>]*>", "$0" + Matcher.quoteReplacement(inject));
            } else if (code.toLowerCase().contains("<html>")) {
                return code.replaceFirst("(?i)<html[^>]*>", "$0<head>" + inject + "</head>");
            } else {
                return "<!DOCTYPE html><html><head>" + inject + "</head><body>" + code + "</body></html>";
            }
        }

        // HTML fragment — wrap in minimal page
        return "<!DOCTYPE html><html><head>" +
                "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0,user-scalable=yes\">" +
                "<style>" +
                "*{margin:0;padding:0;}" +
                "html,body{width:100%;min-height:100%;background:" + bgColor + ";" +
                "overflow:auto;-webkit-transform-origin:0 0;transform-origin:0 0;}" +
                ".preview-wrap{" + rotateCss + "}" +
                "</style>" +
                "<script>" + zoomJs + "</script>" +
                "</head><body>" +
                "<div class=\"preview-wrap\">" + code + "</div>" +
                "</body></html>";
    }

    private void applyCodePreviewBg() {
        String color = getBgColorHex(codePreviewBgColor);
        if (codePreviewWebView != null) {
            viewerEvalJs(codePreviewWebView, "viewerSetBg('" + color + "')");
        }
    }

    private String getBgColorHex(int state) {
        switch (state) {
            case 1: return "#888888";
            case 2: return "#000000";
            default: return "#FFFFFF";
        }
    }

    private void applyBgColor() {
        String color = getBgColorHex(currentBgColor);
        if (imageViewerWebView != null) {
            viewerEvalJs(imageViewerWebView, "viewerSetBg('" + color + "')");
        }
        if (tableViewerWebView != null) {
            viewerEvalJs(tableViewerWebView, "viewerSetBg('" + color + "')");
        }
    }

    // ========== 快速搜题 ==========

    private void loadQuickScanResult() {
        Cursor cursor = null;
        try {
            LogUtil.d(TAG, "loadQuickScanResult: querying content://com.jxw.wbzc/query");
            cursor = getContentResolver().query(
                Uri.parse("content://com.jxw.wbzc/query"),
                null, null, null, "_id DESC");
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex("content");
                if (idx >= 0) {
                    String text = cursor.getString(idx);
                    if (text != null && !text.isEmpty()) {
                        LogUtil.i(TAG, "quick scan result: len=%d preview=%s",
                                text.length(), LogUtil.preview(text, 100));
                        inputEditText.setText(text);
                        inputEditText.setSelection(text.length());
                    } else {
                        LogUtil.w(TAG, "quick scan result: content column empty");
                    }
                } else {
                    LogUtil.w(TAG, "quick scan result: no 'content' column in cursor");
                }
            } else {
                LogUtil.w(TAG, "quick scan result: no row returned");
            }
        } catch (Exception e) {
            LogUtil.e(TAG, "quick scan result query failed", e);
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    // Execute JS in viewer WebView, using evaluateJavascript (API 19+) when available,
    // falling back to loadUrl for API 18.
    private void viewerEvalJs(WebView wv, String js) {
        if (wv == null) return;
        if (android.os.Build.VERSION.SDK_INT >= 19) {
            wv.evaluateJavascript(js, null);
        } else {
            LogUtil.v(TAG, "viewerEvalJs: API<19 fallback loadUrl: %s", LogUtil.preview(js, 120));
            wv.loadUrl("javascript:" + js);
        }
    }

    // ========== 消息长按菜单 ==========

    private void showMessageMenu(final int pos) {
        final Message msg = messages.get(pos);
        String[] items = {"复制", "选择文本", "修改", "删除", "重试", "回溯到此处", "创建分支"};
        LogUtil.d(TAG, "showMessageMenu: pos=%d role=%s len=%s", pos, msg.role,
                msg.content == null ? "null" : msg.content.length());

        new AlertDialog.Builder(this)
                .setTitle("操作消息")
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        LogUtil.i(TAG, "message menu action: pos=%d which=%d (%s)", pos, which, items[which]);
                        switch (which) {
                            case 0: copyMessage(pos); break;
                            case 1: selectText(pos); break;
                            case 2: editMessage(pos); break;
                            case 3: deleteMessage(pos); break;
                            case 4: retryMessage(pos); break;
                            case 5: rollbackTo(pos); break;
                            case 6: branchAt(pos); break;
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String getMessageText(Message msg) {
        if (msg.isAssistant()) return ApiClient.removeThinkingContent(msg.content);
        return msg.content;
    }

    // 1. 复制
    private void copyMessage(int pos) {
        String text = getMessageText(messages.get(pos));
        if (text == null || text.isEmpty()) {
            LogUtil.w(TAG, "copyMessage: empty content at pos=%d", pos);
            Toast.makeText(this, "内容为空", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Object svc = getSystemService(Context.CLIPBOARD_SERVICE);
            if (svc instanceof ClipboardManager) {
                ClipboardManager cm = (ClipboardManager) svc;
                cm.setPrimaryClip(ClipData.newPlainText("message", text));
                LogUtil.d(TAG, "copyMessage: copied %d chars via ClipboardManager", text.length());
            } else {
                // API 18 fallback: some devices return the old ClipboardManager
                ((android.text.ClipboardManager) svc).setText(text);
                LogUtil.d(TAG, "copyMessage: copied %d chars via legacy ClipboardManager", text.length());
            }
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            LogUtil.e(TAG, "copyMessage failed: " + e.getMessage(), e);
            Toast.makeText(this, "复制失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // 2. 选择文本
    private void selectText(int pos) {
        String text = getMessageText(messages.get(pos));
        LogUtil.d(TAG, "selectText: pos=%d len=%s", pos, text == null ? "null" : text.length());
        final TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextIsSelectable(true);
        tv.setPadding(32, 32, 32, 32);
        tv.setTextSize(14);
        new AlertDialog.Builder(this)
                .setTitle("选择文本")
                .setView(tv)
                .setPositiveButton("关闭", null)
                .show();
    }

    // 3. 修改
    private void editMessage(final int pos) {
        final Message msg = messages.get(pos);
        final String oldContent = getMessageText(msg);

        final EditText input = new EditText(this);
        input.setText(oldContent);
        input.setMinLines(3);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        input.setLayoutParams(lp);

        new AlertDialog.Builder(this)
                .setTitle("修改消息")
                .setView(input)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        final String newContent = input.getText().toString().trim();
                        if (newContent.isEmpty()) {
                            LogUtil.w(TAG, "editMessage: empty new content, ignored");
                            return;
                        }
                        LogUtil.i(TAG, "editMessage: pos=%d oldLen=%d newLen=%d", pos,
                                oldContent == null ? 0 : oldContent.length(), newContent.length());
                        showConfirmDialog("确定修改本条消息？", new Runnable() {
                            public void run() {
                                msg.content = newContent;
                                // DOM update: only update this message's div
                                String html = msg.isAssistant()
                                    ? MessageHtmlRenderer.contentToHtml(newContent, MainActivity.this)
                                    : "<div class=\"bubble\">" + MessageHtmlRenderer.esc(newContent) + "</div>";
                                String esc = jsEscape(html);
                                conversationWebView.loadUrl("javascript:updateMsgAt(" + pos + ",'" + esc + "')");
                                conversationManager.saveCurrentConversation();
                                Toast.makeText(MainActivity.this, "已修改", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // 4. 删除
    private void deleteMessage(final int pos) {
        Message msg = messages.get(pos);
        String preview = getMessageText(msg);
        if (preview.length() > 30) preview = preview.substring(0, 30) + "...";
        showConfirmDialog("确定删除本条消息？\n\n" + preview, new Runnable() {
            public void run() {
                LogUtil.i(TAG, "deleteMessage: pos=%d, size %d -> %d", pos, messages.size(), messages.size() - 1);
                messages.remove(pos);
                removeDomFrom(pos);
                // Reindex DOM: update data-idx of remaining messages after pos
                conversationWebView.loadUrl("javascript:reindexFrom(" + pos + ")");
                conversationManager.saveCurrentConversation();
                Toast.makeText(MainActivity.this, "已删除", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // 5. 重试
    private void retryMessage(final int pos) {
        final Message msg = messages.get(pos);
        final int n = messages.size() - pos;
        LogUtil.i(TAG, "retryMessage: pos=%d role=%s, will drop %d messages", pos, msg.role, n);

        if (msg.isAssistant()) {
            boolean hasUserBefore = false;
            for (int i = pos - 1; i >= 0; i--) {
                if (messages.get(i).isUser()) { hasUserBefore = true; break; }
            }
            if (!hasUserBefore) {
                LogUtil.w(TAG, "retryMessage aborted: no user message before pos=%d", pos);
                Toast.makeText(this, "无法找到对应的用户消息", Toast.LENGTH_SHORT).show();
                return;
            }
            showConfirmDialog("将删除本条及之后共 " + n + " 条消息并重新生成回复，确定？", new Runnable() {
                public void run() {
                    LogUtil.i(TAG, "retry(regenerate): truncating from pos=%d", pos);
                    messages.subList(pos, messages.size()).clear();
                    removeDomRange(pos);
                    execStreamingRequest();
                }
            });
        } else {
            final String uc = msg.content;
            showConfirmDialog("将删除本条及之后共 " + n + " 条消息并重新发送，确定？", new Runnable() {
                public void run() {
                    LogUtil.i(TAG, "retry(resend): truncating from pos=%d then resending", pos);
                    messages.subList(pos, messages.size()).clear();
                    removeDomRange(pos);
                    Message um = new Message(Message.ROLE_USER, uc);
                    messages.add(um);
                    conversationManager.getCurrentConversation().touch();
                    // Append user message to DOM
                    String userHtml = MessageHtmlRenderer.renderMessageDiv(um, messages.size() - 1, MainActivity.this);
                    appendHtml(userHtml);
                    execStreamingRequest();
                }
            });
        }
    }

    private void execStreamingRequest() {
        LogUtil.d(TAG, "execStreamingRequest: selectedPos=%d", modelSpinner.getSelectedItemPosition());
        loadSystemPrompt();
        int selPos = modelSpinner.getSelectedItemPosition();
        if (selPos < 0 || availableModels == null || selPos >= availableModels.size()) {
            LogUtil.e(TAG, "execStreamingRequest aborted: invalid selection pos=%d", selPos);
            Toast.makeText(this, "未选择模型", Toast.LENGTH_SHORT).show();
            return;
        }
        ProviderInfo provider = configManager.getProvider(availableModels.get(selPos).provider);
        if (provider == null) {
            LogUtil.e(TAG, "execStreamingRequest aborted: provider null for '%s'",
                    availableModels.get(selPos).name);
            Toast.makeText(this, "未选择模型", Toast.LENGTH_SHORT).show();
            return;
        }
        String thinkingLevel = configManager.getThinkingLevel();
        if (!configManager.isThinkingEnabled()) thinkingLevel = "off";
        int gen = requestGeneration.incrementAndGet();
        sendStreamingRequest(provider, thinkingLevel, gen);
    }

    // 6. 回溯到此处
    private void rollbackTo(int pos) {
        if (pos >= messages.size() - 1) {
            LogUtil.d(TAG, "rollbackTo: already latest (pos=%d, size=%d)", pos, messages.size());
            Toast.makeText(this, "已在最新位置", Toast.LENGTH_SHORT).show();
            return;
        }
        final int n = messages.size() - pos - 1;
        showConfirmDialog("将删除本条之后共 " + n + " 条消息（保留本条），确定？", new Runnable() {
            public void run() {
                LogUtil.i(TAG, "rollbackTo: removing %d messages after pos=%d", n, pos);
                messages.subList(messages.size() - n, messages.size()).clear();
                removeDomRange(messages.size());
                conversationManager.saveCurrentConversation();
                Toast.makeText(MainActivity.this, "已回溯", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // 7. 创建分支
    private void branchAt(final int pos) {
        final int n = pos + 1;
        showConfirmDialog("将前 " + n + " 条消息复制到新对话分支，确定？", new Runnable() {
            public void run() {
                Conversation current = conversationManager.getCurrentConversation();
                Conversation branch = new Conversation();
                branch.title = current.title + "-分支";
                branch.systemPrompt = current.systemPrompt;
                branch.model = current.model;
                LogUtil.i(TAG, "branchAt: copying %d messages into branch '%s'", n, branch.title);

                for (int i = 0; i <= pos; i++) {
                    Message src = messages.get(i);
                    Message copy = new Message();
                    copy.role = src.role;
                    copy.content = src.content;
                    copy.timestamp = src.timestamp;
                    branch.messages.add(copy);
                }

                conversationManager.getConversations().add(0, branch);
                conversationManager.saveCurrentConversation();
                StorageManager.getInstance().saveConversation(branch.id, ConversationManager.toJson(branch));

                conversationManager.setCurrentConversation(branch);
                messages = branch.messages;
                refreshWebView();
                Toast.makeText(MainActivity.this, "已创建分支: " + branch.title, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showConfirmDialog(String message, final Runnable onConfirm) {
        new AlertDialog.Builder(this)
                .setTitle("确认")
                .setMessage(message)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { onConfirm.run(); }
                })
                .setNegativeButton("取消", null)
                .show();
    }

}
