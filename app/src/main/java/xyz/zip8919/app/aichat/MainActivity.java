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

    /**
     * Height of the viewer overlay toolbar (viewer_top_left / viewer_close are
     * 44dp + 4dp margin each side). The preview WebViews fill the whole dialog,
     * so page content must be inset by this much or it renders underneath the
     * toolbar buttons.
     */
    private static final int VIEWER_TOOLBAR_INSET_PX = 52;
    private static final String PREFS_NAME = "aichat_prefs";
    private static final int REQUEST_CONVERSATION_MANAGER = 1;
    private static final int REQUEST_SCAN = 2;

    private ConfigManager configManager;
    private ConversationManager conversationManager;
    private StorageManager storageManager;
    private SharedPreferences prefs;

    private EditText inputEditText;
    private Button sendButton;
    // 发送键长按打断后，本次抬手的 click 不再触发发送
    private boolean sendButtonLongPressed = false;
    private WebView conversationWebView;
    private Spinner modelSpinner;
    private Spinner thinkingSpinner;
    private View queueBar;
    private TextView queueBarText;
    private boolean userPicked = false;

    private List<Message> messages;
    private List<ModelInfo> availableModels;

    private AtomicBoolean isRequestInProgress = new AtomicBoolean(false);
    private AtomicInteger requestGeneration = new AtomicInteger(0);
    // 渲染代数守卫：refreshWebView 可能被连续触发，过期渲染必须丢弃
    private final AtomicInteger renderGeneration = new AtomicInteger(0);
    // volatile：请求线程写、UI 线程读，打断时才能可靠 disconnect
    private volatile HttpURLConnection currentConnection;
    // volatile：后台加载线程回到 UI 线程后判断 initConversation() 是否已执行；
    // 已执行则 loadConversations() 整体替换列表时丢掉了兜底会话，需按 id 补回列表头部。
    private volatile boolean initConversationDone = false;
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

        // 磁盘 IO + JSON 解析移出主线程。无竞态说明：loadConversations() 在后台只整体替换
        // ConversationManager.conversations 字段引用、不修改旧列表；主线程 initConversation()
        // 只往旧列表插入兜底会话，两条线程不会同时修改同一个 List。加载完成后回 UI 线程：
        // 若 initConversation() 已执行（兜底会话被整体替换掉了），按 id 把当前会话补回列表头部，
        // 恢复与冷启动一致的状态；若未执行，加载结果已在位，initConversation() 自然复用磁盘会话。
        final ConversationManager cm = conversationManager;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Conversation> loaded = cm.loadConversations();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        LogUtil.i(TAG, "conversations loaded: count=%d",
                                loaded == null ? -1 : loaded.size());
                        if (initConversationDone) {
                            Conversation cur = cm.getCurrentConversation();
                            List<Conversation> list = cm.getConversations();
                            boolean present = false;
                            for (int i = 0; i < list.size(); i++) {
                                if (list.get(i).id.equals(cur.id)) { present = true; break; }
                            }
                            if (!present) list.add(0, cur);
                        }
                    }
                });
            }
        }, "conv-preload").start();

        initViews();
        loadSystemPrompt();
        initConversation();
        handlePromptIntent(getIntent());
        LogUtil.i(TAG, "========== onCreate done ==========");
    }

    @Override
    protected void onDestroy() {
        LogUtil.i(TAG, "onDestroy: cleaning up handler / connection / webview");
        // 清空主线程消息队列里挂起的回调（消息菜单、长按等），避免销毁后弹窗
        handler.removeCallbacksAndMessages(null);
        if (currentConnection != null) {
            try { currentConnection.disconnect(); } catch (Exception ignored) {}
            currentConnection = null;
        }
        // 令在途渲染失效，避免其 runOnUiThread 作用在已置空的 WebView 上
        renderGeneration.incrementAndGet();
        if (conversationWebView != null) {
            conversationWebView.destroy();
            conversationWebView = null;
        }
        super.onDestroy();
    }

    private void initViews() {
        LogUtil.d(TAG, "initViews");
        inputEditText = (EditText) findViewById(R.id.input_edit_text);
        sendButton = (Button) findViewById(R.id.send_button);
        modelSpinner = (Spinner) findViewById(R.id.model_spinner);
        thinkingSpinner = (Spinner) findViewById(R.id.thinking_spinner);
        queueBar = findViewById(R.id.queue_bar);
        queueBarText = (TextView) findViewById(R.id.queue_bar_text);
        findViewById(R.id.queue_manage_button).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { LogUtil.d(TAG, "click: queue_manage"); showQueueManager(); }
        });
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
        findViewById(R.id.history_button).setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) { LogUtil.d(TAG, "longclick: history -> tools"); openTools(); return true; }
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
            public void onClick(View v) {
                // 长按已执行打断，这次抬手的 click 直接吞掉，避免把草稿立刻发出去
                if (sendButtonLongPressed) {
                    sendButtonLongPressed = false;
                    LogUtil.d(TAG, "click: send suppressed after long-press interrupt");
                    return;
                }
                LogUtil.d(TAG, "click: send");
                sendMessage();
            }
        });
        sendButton.setOnTouchListener(new View.OnTouchListener() {
            private boolean longPressed = false;
            private Runnable longPressRunnable;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        longPressed = false;
                        sendButtonLongPressed = false;
                        longPressRunnable = new Runnable() {
                            public void run() {
                                longPressed = true;
                                sendButtonLongPressed = true;
                                LogUtil.d(TAG, "sendButton LONG press -> interruptRequest");
                                interruptRequest();
                            }
                        };
                        handler.postDelayed(longPressRunnable, 500);
                        return false;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        handler.removeCallbacks(longPressRunnable);
                        // Short tap while a request is running no longer interrupts:
                        // sendMessage() queues the text instead. Long press still stops.
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
        userPicked = false;
        thinkingSpinner.setSelection(levelPos);
        LogUtil.d(TAG, "thinking level restored: %s -> pos=%d (thinkingEnabled=%s)",
                level, levelPos, configManager.isThinkingEnabled());

        thinkingSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                userPicked = true;
                String[] levels = {"off", "low", "medium", "high"};
                LogUtil.d(TAG, "thinking level selected: pos=%d -> %s", pos, levels[pos]);
                if (userPicked) {
                    configManager.setThinkingLevel(levels[pos]);
                    configManager.save();
                }
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

        // Preserve the user's selection: onResume() refreshes the spinner after
        // returning from settings/history/scan — re-selecting the default here
        // reset the user's manual choice. Keep currentModel when it still
        // exists; fall back to default on first init or if it was removed.
        int targetPos = -1;
        if (currentModel != null) {
            for (int i = 0; i < availableModels.size(); i++) {
                if (availableModels.get(i).name.equals(currentModel)) {
                    targetPos = i;
                    break;
                }
            }
        }
        if (targetPos < 0) {
            String defaultModel = configManager.getDefaultModel();
            for (int i = 0; i < availableModels.size(); i++) {
                if (availableModels.get(i).name.equals(defaultModel)) {
                    targetPos = i;
                    break;
                }
            }
        }
        if (targetPos >= 0) {
            modelSpinner.setSelection(targetPos);
            selectModel(targetPos);
        } else {
            LogUtil.w(TAG, "neither current nor default model found in %d models",
                    availableModels.size());
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
        // 供后台加载完成的 runOnUiThread 判断兜底会话是否已被 loadConversations() 整体替换
        initConversationDone = true;
    }

    private void createNewConversation() {
        LogUtil.i(TAG, "createNewConversation (requestInProgress=%s)", isRequestInProgress.get());
        // Queued messages belong to the old conversation; drop them before the
        // interrupt handler drains the queue into the new one.
        if (!pendingQueue.isEmpty()) {
            LogUtil.i(TAG, "createNewConversation: dropping %d queued message(s)", pendingQueue.size());
            pendingQueue.clear();
            dismissQueueManager();
            updateQueueBar();
        }
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
                drainPendingQueue();
                if (messages.size() != msgCountAtInterrupt) return;
                if (!messages.isEmpty()) {
                    Message lastMsg = messages.get(messages.size() - 1);
                    if (lastMsg.isAssistant()) {
                        String content = lastMsg.content;
                        if (content == null || content.isEmpty()) {
                            LogUtil.d(TAG, "interrupt: removing empty AI placeholder, size=%d", messages.size());
                            messages.remove(messages.size() - 1);
                            removeDomRange(messages.size());
                            // finalizeLast 被代数守卫跳过时占位 div 的 data-idx 仍为 -1，
                            // removeRangeFrom(idx>=0) 删不掉它，这里补删未定稿的占位 div
                            removeUnfinalizedAiDiv();
                        } else {
                            // 思考阶段被打断时要补 [/thinking] 收尾，否则未闭合内容
                            // 会被 removeThinkingContent 整段剥离，模型像没收到过该回复
                            if (content.indexOf("[thinking]") >= 0 && content.indexOf("[/thinking]") < 0) {
                                content = content + "[/thinking]";
                            }
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

    private void openTools() {
        LogUtil.d(TAG, "openTools -> ToolsActivity");
        startActivity(new Intent(this, ToolsActivity.class));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        LogUtil.d(TAG, "onActivityResult: requestCode=%d resultCode=%d data=%s", requestCode, resultCode, data);
        if (requestCode == REQUEST_CONVERSATION_MANAGER && resultCode == RESULT_OK) {
            String conversationId = data == null ? null : data.getStringExtra("conversation_id");
            if (conversationId != null) {
                LogUtil.i(TAG, "switching to conversation: %s", conversationId);
                // 排队消息属于旧会话：在 interruptRequest 派发的 drain 之前丢弃，避免发进新会话
                if (!pendingQueue.isEmpty()) {
                    LogUtil.i(TAG, "onActivityResult: dropping %d queued message(s)", pendingQueue.size());
                    pendingQueue.clear();
                    dismissQueueManager();
                    updateQueueBar();
                }
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
                String cur = inputEditText.getText().toString();
                boolean inserted = false;
                if (cur.isEmpty()) {
                    inputEditText.setText(text);
                    inserted = true;
                } else if (!cur.equals(text) && !cur.endsWith(text)) {
                    if (!cur.endsWith("\n")) {
                        inputEditText.append("\n");
                    }
                    inputEditText.append(text);
                    inserted = true;
                }
                if (inserted) {
                    inputEditText.setSelection(inputEditText.getText().length());
                }
            } else {
                LogUtil.w(TAG, "REQUEST_SCAN returned empty/null text");
            }
        } else {
            LogUtil.d(TAG, "onActivityResult unhandled: requestCode=%d resultCode=%d", requestCode, resultCode);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handlePromptIntent(intent);
    }

    private void handlePromptIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        handleCodePreviewIntent(intent);
        String prompt = intent.getStringExtra(EXTRA_PROMPT);
        if (prompt == null || prompt.isEmpty()) {
            return;
        }
        intent.removeExtra(EXTRA_PROMPT);
        boolean send = intent.getBooleanExtra(EXTRA_PROMPT_SEND, false);
        LogUtil.i(TAG, "handlePromptIntent: send=%s len=%d", send, prompt.length());
        if (inputEditText == null) {
            return;
        }
        if (send) {
            // The environment prompt is sent as a standalone message, so keep whatever
            // the user had already typed: remember it and put it back after the send.
            String draft = inputEditText.getText().toString();
            inputEditText.setText(prompt);
            inputEditText.setSelection(prompt.length());
            sendMessage();
            if (draft.length() > 0 && inputEditText.getText().length() == 0) {
                inputEditText.setText(draft);
                inputEditText.setSelection(draft.length());
                LogUtil.d(TAG, "handlePromptIntent: restored draft len=%d", draft.length());
            }
        } else {
            // Insert at the caret instead of replacing the draft.
            int start = Math.max(0, inputEditText.getSelectionStart());
            int end = Math.max(0, inputEditText.getSelectionEnd());
            if (start > end) {
                int tmp = start;
                start = end;
                end = tmp;
            }
            inputEditText.getText().replace(start, end, prompt);
            inputEditText.setSelection(start + prompt.length());
        }
    }

    private void handleCodePreviewIntent(Intent intent) {
        String code = intent.getStringExtra(EXTRA_PREVIEW_CODE);
        if (code == null || code.isEmpty()) {
            return;
        }
        intent.removeExtra(EXTRA_PREVIEW_CODE);
        String lang = intent.getStringExtra(EXTRA_PREVIEW_LANG);
        if (lang == null || lang.isEmpty()) {
            lang = detectLang(code);
        }
        LogUtil.i(TAG, "handleCodePreviewIntent: lang=%s len=%d", lang, code.length());
        showCodePreviewDialog(lang, code);
    }

    /**
     * Sniffs the renderer from the code itself rather than from a file name, so a
     * saved snippet still previews as html / svg / js after being renamed.
     */
    static String detectLang(String content) {
        if (content == null) {
            return "js";
        }
        String t = content.trim().toLowerCase();
        if (t.startsWith("<!doctype") || t.startsWith("<html")) {
            return "html";
        }
        if (t.startsWith("<svg")) {
            return "svg";
        }
        if (t.startsWith("<")) {
            return "html";
        }
        return "js";
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

    // Messages typed while a request is streaming wait here and are sent in order
    // once the current reply finishes. Touched on the UI thread only.
    // Each entry carries a stable id: dialogs hold the id, never the position, so
    // an action still lands on the message the user picked after the drain has
    // already sent one of its neighbours.
    static class QueuedMessage {
        final int id;
        final String text;

        QueuedMessage(int id, String text) {
            this.id = id;
            this.text = text;
        }
    }

    private final List<QueuedMessage> pendingQueue = new ArrayList<QueuedMessage>();
    private int nextQueueId = 1;
    private AlertDialog queueManagerDialog;
    private AlertDialog queueActionsDialog;
    /** A relist was requested while the action sheet was open; applied on the sheet's dismiss. */
    private boolean queueManagerDirty;
    /** True when the action sheet was closed by picking an action rather than by 取消. */
    private boolean queueActionTaken;

    /** Current position of the queued message with this id, or -1 when it is gone. */
    private int findQueueIndex(int id) {
        for (int i = 0; i < pendingQueue.size(); i++) {
            if (pendingQueue.get(i).id == id) return i;
        }
        return -1;
    }

    private void sendMessage() {
        if (isRequestInProgress.get()) {
            String queued = inputEditText.getText().toString().trim();
            if (queued.isEmpty()) {
                LogUtil.w(TAG, "sendMessage ignored: empty input");
                return;
            }
            pendingQueue.add(new QueuedMessage(nextQueueId++, queued));
            inputEditText.setText("");
            updateQueueBar();
            LogUtil.i(TAG, "sendMessage queued: len=%d pending=%d", queued.length(), pendingQueue.size());
            Toast.makeText(this, "已排队 " + pendingQueue.size() + " 条，将在当前回复结束后发送",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        String input = inputEditText.getText().toString().trim();
        if (input.isEmpty()) {
            LogUtil.w(TAG, "sendMessage ignored: empty input");
            return;
        }
        inputEditText.setText("");
        sendMessageText(input);
    }

    private void drainPendingQueue() {
        if (pendingQueue.isEmpty()) return;
        handler.post(new Runnable() {
            public void run() {
                if (isRequestInProgress.get() || pendingQueue.isEmpty()) {
                    LogUtil.i(TAG, "drainPendingQueue: deferred, inProgress=%s pending=%d",
                            isRequestInProgress.get(), pendingQueue.size());
                    return;
                }
                QueuedMessage next = pendingQueue.remove(0);
                updateQueueBar();
                refreshQueueManager();
                LogUtil.i(TAG, "drainPendingQueue: sending queued message id=%d len=%d remaining=%d",
                        next.id, next.text.length(), pendingQueue.size());
                sendMessageText(next.text);
            }
        });
    }

    /** Shows/hides the queued-message bar above the input row. UI thread only. */
    private void updateQueueBar() {
        if (queueBar == null || queueBarText == null) return;
        int n = pendingQueue.size();
        if (n == 0) {
            queueBar.setVisibility(View.GONE);
        } else {
            queueBar.setVisibility(View.VISIBLE);
            queueBarText.setText("排队 " + n + " 条，将在当前回复结束后依次发送");
        }
    }

    private void showQueueManager() {
        if (pendingQueue.isEmpty()) {
            Toast.makeText(this, "没有排队中的消息", Toast.LENGTH_SHORT).show();
            updateQueueBar();
            return;
        }
        final String[] items = new String[pendingQueue.size()];
        final int[] ids = new int[pendingQueue.size()];
        for (int i = 0; i < pendingQueue.size(); i++) {
            QueuedMessage qm = pendingQueue.get(i);
            ids[i] = qm.id;
            String s = qm.text.replace('\n', ' ');
            if (s.length() > 30) s = s.substring(0, 30) + "…";
            items[i] = (i + 1) + ". " + s;
        }
        queueManagerDialog = new AlertDialog.Builder(this)
                .setTitle("排队中的消息")
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        showQueueItemActions(ids[which]);
                    }
                })
                .setPositiveButton("关闭", null)
                .setNeutralButton("全部清空", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        LogUtil.i(TAG, "queue: clear all (%d)", pendingQueue.size());
                        pendingQueue.clear();
                        updateQueueBar();
                    }
                })
                .show();
    }

    /**
     * Relists the manager dialog when the queue changed underneath it (a message
     * was sent by the drain while the dialog was open). Without this the rows
     * would keep pointing at messages that are no longer at those positions.
     *
     * <p>While the per-message action sheet is open the relist is deferred: showing
     * the manager again would stack it on top of the sheet the user is using.
     * Deferring is safe because every action resolves its message by id and reports
     * 「该消息已发送」when the drain got there first.
     */
    private void refreshQueueManager() {
        if (queueActionsDialog != null && queueActionsDialog.isShowing()) {
            queueManagerDirty = true;
            return;
        }
        if (queueManagerDialog == null || !queueManagerDialog.isShowing()) return;
        queueManagerDialog.dismiss();
        queueManagerDialog = null;
        if (pendingQueue.isEmpty()) return;
        showQueueManager();
    }

    private void dismissQueueManager() {
        if (queueManagerDialog != null) {
            queueManagerDialog.dismiss();
            queueManagerDialog = null;
        }
    }

    /** Operates on a queued message by its stable id, not by its position. */
    private void showQueueItemActions(final int id) {
        final int index = findQueueIndex(id);
        if (index < 0) {
            LogUtil.i(TAG, "queue: action on id=%d dropped, already sent (pending=%d)",
                    id, pendingQueue.size());
            Toast.makeText(this, "该消息已发送", Toast.LENGTH_SHORT).show();
            refreshQueueManager();
            return;
        }
        final String[] actions = {"上移", "下移", "插队发送（打断当前回复）", "删除"};
        queueActionsDialog = new AlertDialog.Builder(this)
                .setTitle("第 " + (index + 1) + " 条")
                .setItems(actions, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        queueActionTaken = true;
                        if (which == 0) moveQueueItem(id, -1);
                        else if (which == 1) moveQueueItem(id, 1);
                        else if (which == 2) jumpQueue(id);
                        else deleteQueueItem(id);
                    }
                })
                .setNegativeButton("取消", null)
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    public void onDismiss(DialogInterface d) {
                        queueActionsDialog = null;
                        // Picking an action returns to the list so several edits can be
                        // made in a row; 取消 closes the whole stack.
                        boolean reopen = queueActionTaken;
                        queueActionTaken = false;
                        if (queueManagerDirty || (reopen && !pendingQueue.isEmpty())) {
                            queueManagerDirty = false;
                            refreshQueueManager();
                            if (reopen && (queueManagerDialog == null || !queueManagerDialog.isShowing())) {
                                showQueueManager();
                            }
                        }
                    }
                })
                .show();
    }

    private void moveQueueItem(int id, int delta) {
        int from = findQueueIndex(id);
        if (from < 0) {
            Toast.makeText(this, "该消息已发送", Toast.LENGTH_SHORT).show();
            refreshQueueManager();
            return;
        }
        int to = from + delta;
        if (to < 0 || to >= pendingQueue.size()) {
            Toast.makeText(this, delta < 0 ? "已经在最前" : "已经在最后", Toast.LENGTH_SHORT).show();
            return;
        }
        QueuedMessage moved = pendingQueue.remove(from);
        pendingQueue.add(to, moved);
        LogUtil.i(TAG, "queue: move id=%d %d -> %d (pending=%d)", id, from, to, pendingQueue.size());
        updateQueueBar();
        refreshQueueManager();
    }

    private void deleteQueueItem(int id) {
        int index = findQueueIndex(id);
        if (index < 0) {
            Toast.makeText(this, "该消息已发送", Toast.LENGTH_SHORT).show();
            refreshQueueManager();
            return;
        }
        QueuedMessage removed = pendingQueue.remove(index);
        LogUtil.i(TAG, "queue: delete id=%d idx=%d len=%d (pending=%d)",
                id, index, removed.text.length(), pendingQueue.size());
        updateQueueBar();
        refreshQueueManager();
        if (pendingQueue.isEmpty()) {
            Toast.makeText(this, "队列已清空", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Sends the selected queued message next: it moves to the head of the queue,
     * then the in-flight reply is stopped so the drain picks it up immediately.
     */
    private void jumpQueue(int id) {
        int index = findQueueIndex(id);
        if (index < 0) {
            Toast.makeText(this, "该消息已发送", Toast.LENGTH_SHORT).show();
            refreshQueueManager();
            return;
        }
        QueuedMessage moved = pendingQueue.remove(index);
        pendingQueue.add(0, moved);
        LogUtil.i(TAG, "queue: jump id=%d idx=%d to head (pending=%d, inProgress=%s)",
                id, index, pendingQueue.size(), isRequestInProgress.get());
        updateQueueBar();
        refreshQueueManager();
        if (isRequestInProgress.get()) {
            interruptRequest();
        } else {
            drainPendingQueue();
        }
    }

    private void sendMessageText(final String input) {
        LogUtil.i(TAG, "========== sendMessage: len=%d preview=%s ==========",
                input.length(), LogUtil.preview(input, 200));

        // 外部存储被移除后所有保存会静默失败：发送前探测一次，仅提示不拦截
        if (!storageManager.isStorageReady()) {
            LogUtil.w(TAG, "sendMessage: storage not ready (%s), conversation may not persist",
                    storageManager.getStorageInfo());
            Toast.makeText(this, "存储不可用，本次对话可能无法保存", Toast.LENGTH_LONG).show();
        }

        loadSystemPrompt();

        // 模型校验提前到入列/渲染之前：校验失败不应留下永不回复的孤儿用户消息
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

        Message userMsg = new Message(Message.ROLE_USER, input);
        messages.add(userMsg);
        conversationManager.getCurrentConversation().touch();
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

        String thinkingLevel = configManager.getThinkingLevel();
        if (!configManager.isThinkingEnabled()) thinkingLevel = "off";
        LogUtil.i(TAG, "request params: model=%s provider=%s url=%s thinking=%s",
                currentModel, provider.name, provider.apiUrl, thinkingLevel);

        int gen = requestGeneration.incrementAndGet();
        sendStreamingRequest(provider, thinkingLevel, gen);
    }

    private void sendStreamingRequest(final ProviderInfo provider, final String thinkingLevel, final int generation) {
        final boolean realtimeRenderEnabled = SettingsActivity.isRealtimeRenderEnabled(this);
        final long startMs = System.currentTimeMillis();
        LogUtil.i(TAG, ">>> sendStreamingRequest START: gen=%d url=%s%s model=%s thinking=%s (type=%s, param=%s)",
                generation, provider.apiUrl, provider.chatPath, currentModel, thinkingLevel,
                provider.thinkingType, provider.thinkingParamName);
        isRequestInProgress.set(true);

        final Message aiMsg = new Message(Message.ROLE_ASSISTANT, "");
        messages.add(aiMsg);
        final int aiIndex = messages.size() - 1;
        // 在 UI 线程做一次快照：请求线程遍历时 UI 线程可能结构性修改 messages，
        // 直接遍历会抛 ConcurrentModificationException
        final List<Message> requestSnapshot = new ArrayList<Message>(messages);
        LogUtil.d(TAG, "AI placeholder added at index=%d, total=%d", aiIndex, messages.size());

        // Append AI placeholder div
        runOnUiThread(new Runnable() {
            public void run() {
                LogUtil.v(TAG, "webview js: appendAiDiv()");
                webViewEvalJs("appendAiDiv()");
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
                // Bumped for every render request so a slow background render
                // cannot overwrite a newer one (or the final render).
                final java.util.concurrent.atomic.AtomicInteger renderSeq =
                        new java.util.concurrent.atomic.AtomicInteger();
                final boolean[] renderInFlight = {false};
                // 请求是否失败（HTTP 非 200 / 解析失败 / 异常）：失败时 finally 需回滚空占位
                final boolean[] requestFailed = {false};

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
                    final boolean streamEnabled = SettingsActivity.isStreamEnabled(MainActivity.this);
                    body.put("stream", streamEnabled);

                    org.json.JSONArray msgs = new org.json.JSONArray();
                    if (systemPrompt != null && !systemPrompt.isEmpty()) {
                        org.json.JSONObject sm = new org.json.JSONObject();
                        sm.put("role", "system");
                        sm.put("content", systemPrompt);
                        msgs.put(sm);
                    }
                    for (Message m : requestSnapshot) {
                        if (m == aiMsg) continue;
                        org.json.JSONObject mm = new org.json.JSONObject();
                        mm.put("role", m.role);
                        String sendContent = m.isAssistant() ? ApiClient.removeThinkingContent(m.content) : m.content;
                        mm.put("content", sendContent == null ? "" : sendContent);
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
                    try {
                        os.write(body.toString().getBytes("UTF-8"));
                    } finally {
                        os.close();
                    }
                    LogUtil.d(TAG, "request body written in %d ms", System.currentTimeMillis() - reqMs);

                    int code = conn.getResponseCode();
                    LogUtil.i(TAG, "HTTP response: code=%d in %d ms", code, System.currentTimeMillis() - reqMs);
                    if (code != 200) {
                        String errBody = null;
                        try {
                            java.io.InputStream es = conn.getErrorStream();
                            if (es != null) {
                                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                                byte[] buf = new byte[1024];
                                int n;
                                int total = 0;
                                while (total < 4096 && (n = es.read(buf, 0, Math.min(buf.length, 4096 - total))) > 0) {
                                    bos.write(buf, 0, n);
                                    total += n;
                                }
                                errBody = new String(bos.toByteArray(), "UTF-8");
                            }
                        } catch (Exception ignored) {}
                        LogUtil.e(TAG, "request FAILED: HTTP %d, body=%s", code, LogUtil.preview(errBody, 800));
                        final String err = "HTTP " + code;
                        requestFailed[0] = true;
                        runOnUiThread(new Runnable() {
                            public void run() { Toast.makeText(MainActivity.this, "请求失败: " + err, Toast.LENGTH_SHORT).show(); }
                        });
                        return;
                    }

                    java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"));
                    String line;
                    long lastUpdate = 0;

                    if (!streamEnabled) {
                        // Non-streaming: the body is one JSON object, no SSE frames.
                        StringBuilder sb = new StringBuilder();
                        try {
                            while ((line = reader.readLine()) != null) sb.append(line);
                        } finally {
                            reader.close();
                        }
                        String fullContent = ApiClient.extractMessageContent(sb.toString());
                        LogUtil.i(TAG, "non-stream response: %d chars raw -> %d chars content",
                                sb.length(), fullContent == null ? 0 : fullContent.length());
                        if (fullContent == null) {
                            final String err = "响应解析失败";
                            requestFailed[0] = true;
                            runOnUiThread(new Runnable() {
                                public void run() { Toast.makeText(MainActivity.this, err, Toast.LENGTH_SHORT).show(); }
                            });
                            return;
                        }
                        // 内容交给 finally 统一提交（先写回 messages 并完成 DOM 收尾，再放行队列），
                        // 避免这里的异步提交被 drain 引发的代数自增挡掉
                        rawContent.setLength(0);
                        rawContent.append(fullContent);
                        return;
                    }

                    LogUtil.i(TAG, "SSE stream started, begin reading chunks");
                    while (isRequestInProgress.get() && (line = reader.readLine()) != null) {
                        line = line.trim();
                        // SSE 规范里冒号后的空格可选，兼容 "data:" 与 "data: "
                        if (!line.startsWith("data:")) {
                            if (line.length() > 0) {
                                LogUtil.v(TAG, "skip non-data line: %s", LogUtil.preview(line, 120));
                            }
                            continue;
                        }
                        String data = line.substring(5).trim();
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
                                // Only a non-empty content chunk ends the thinking
                                // phase. The API emits "content":"" on every
                                // reasoning chunk, which used to end the phase on
                                // the first reasoning chunk and froze the
                                // "展开思考（N字）" count at a couple of chars.
                                if (ct.length() > 0 && thinkingActive[0] && !thinkingFinished[0]) {
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

                            // Throttle: re-render markdown at most every 200ms.
                            // Rendering takes hundreds of ms on old devices, so
                            // it runs off the UI thread and only one render is
                            // in flight at a time; ticks that arrive meanwhile
                            // are dropped. Skipped when realtime rendering is
                            // off — the reply then appears once, at the end.
                            long now = System.currentTimeMillis();
                            if (realtimeRenderEnabled && now - lastUpdate > 200 && !renderInFlight[0]) {
                                lastUpdate = now;
                                renderInFlight[0] = true;
                                final String partial = rawContent.toString();
                                final int mySeq = renderSeq.incrementAndGet();
                                new Thread(new Runnable() {
                                    public void run() {
                                        final String partialHtml =
                                                MessageHtmlRenderer.contentToHtml(partial, MainActivity.this);
                                        runOnUiThread(new Runnable() {
                                            public void run() {
                                                renderInFlight[0] = false;
                                                if (renderSeq.get() != mySeq) {
                                                    return; // superseded by a newer render
                                                }
                                                if (requestGeneration.get() == generation && aiIndex < messages.size()) {
                                                    messages.get(aiIndex).content = partial;
                                                    webViewEvalJs("updateLastMsg('" + jsEscape(partialHtml) + "')");
                                                } else {
                                                    LogUtil.w(TAG, "throttled update skipped: aiIndex=%d >= size=%d",
                                                            aiIndex, messages.size());
                                                }
                                            }
                                        });
                                    }
                                }).start();
                            }
                        } catch (Exception e) {
                            LogUtil.w(TAG, "failed to parse SSE chunk#%d: %s | raw=%s",
                                    chunkCount[0], e.getMessage(), LogUtil.preview(data, 300));
                        }
                    }
                    if (thinkingActive[0] && !thinkingFinished[0]) {
                        rawContent.append("[/thinking]");
                        LogUtil.d(TAG, "thinking phase left open at stream end: closed with [/thinking]");
                    }
                    // Final commit happens in finally: content is written back to
                    // messages (and saved) before the pending queue is released, so a
                    // drained follow-up request cannot invalidate it via the generation guard.
                    LogUtil.i(TAG, "stream finished: chunks=%d (thinking=%d, content=%d), finalLen=%d, elapsed=%d ms",
                            chunkCount[0], thinkingChunks[0], contentChunks[0],
                            rawContent.length(), System.currentTimeMillis() - startMs);
                    renderSeq.incrementAndGet(); // invalidate any in-flight stream render
                    reader.close();
                } catch (final Exception e) {
                    requestFailed[0] = true;
                    LogUtil.e(TAG, "streaming request EXCEPTION: %s", e.getMessage(), e);
                    // 用户主动打断（打断已自增代数）导致的 Socket closed 不算请求失败，不弹提示
                    if (requestGeneration.get() == generation) {
                        runOnUiThread(new Runnable() {
                            public void run() {
                                Toast.makeText(MainActivity.this, "请求失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                            }
                        });
                    } else {
                        LogUtil.i(TAG, "stream exception ignored: request was interrupted by user");
                    }
                } finally {
                    LogUtil.i(TAG, "<<< sendStreamingRequest END: elapsed=%d ms, chunks=%d, interrupted=%s",
                            System.currentTimeMillis() - startMs, chunkCount[0], !isRequestInProgress.get());
                    if (conn != null) conn.disconnect();
                    // 只有当前请求代数匹配时才清理状态，防止旧线程污染新请求
                    if (requestGeneration.get() == generation) {
                        currentConnection = null;
                        final int finalIdx = aiIndex;
                        final boolean failed = requestFailed[0];
                        final String commitContent = rawContent.toString();
                        renderSeq.incrementAndGet(); // invalidate any in-flight stream render
                        // 先渲染（离开主线程）→ 提交内容与 DOM 收尾 → 落盘 → 最后才放行队列。
                        // 若先 drain，新请求会自增代数，使这条最终回复被代数守卫丢弃。
                        new Thread(new Runnable() {
                            public void run() {
                                try {
                                    if (failed) {
                                        // 请求失败：移除空 assistant 占位，避免空气泡落盘/进入后续请求体
                                        rollbackEmptyAssistantPlaceholder(finalIdx, generation);
                                        return;
                                    }
                                    final String html =
                                            MessageHtmlRenderer.contentToHtml(commitContent, MainActivity.this);
                                    if (requestGeneration.get() != generation) return;
                                    runOnUiThread(new Runnable() {
                                        public void run() {
                                            if (requestGeneration.get() != generation) return;
                                            if (finalIdx >= 0 && finalIdx < messages.size()
                                                    && messages.get(finalIdx).isAssistant()) {
                                                messages.get(finalIdx).content = commitContent;
                                            }
                                            webViewEvalJs("updateLastMsg('" + jsEscape(html) + "')");
                                            webViewEvalJs("finalizeLast(" + finalIdx + ")");
                                            conversationManager.saveCurrentConversation();
                                        }
                                    });
                                } catch (Exception e) {
                                    LogUtil.e(TAG, "final commit failed: %s", e.getMessage(), e);
                                } finally {
                                    if (requestGeneration.get() == generation) finishRequestAndDrain();
                                }
                            }
                        }).start();
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
        // 记录发起标题生成时的会话 id：异步回调回来时若已切会话，丢弃，
        // 否则会把上一个会话的标题写到当前会话
        final String titleConvId = conversationManager.getCurrentConversation().id;
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
                                        if (conv == null || !titleConvId.equals(conv.id)) {
                                            LogUtil.d(TAG, "title dropped: conversation changed (started=%s, now=%s)",
                                                    titleConvId, conv == null ? "-" : conv.id);
                                            return;
                                        }
                                        if (!conv.titleGenerated) {
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
        // 渲染代数守卫 + 列表快照：两次刷新可能乱序落地，且渲染线程不能直接遍历
        // 可能被 UI 线程结构性修改的 messages
        final int myRenderGen = renderGeneration.incrementAndGet();
        final List<Message> snapshot =
                messages == null ? new ArrayList<Message>() : new ArrayList<Message>(messages);
        new Thread(new Runnable() {
            public void run() {
                final String html = MessageHtmlRenderer.buildConversationHtml(snapshot, MainActivity.this);
                LogUtil.d(TAG, "conversation html built: len=%d in %d ms",
                        html == null ? -1 : html.length(), System.currentTimeMillis() - t0);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (renderGeneration.get() != myRenderGen) {
                            LogUtil.d(TAG, "refreshWebView: stale render dropped (gen=%d, current=%d)",
                                    myRenderGen, renderGeneration.get());
                            return;
                        }
                        if (conversationWebView == null) return;
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
        webViewEvalJs("appendMsg('" + esc + "')");
    }

    private void updateAiContent(String content) {
        String html = MessageHtmlRenderer.contentToHtml(content, this);
        String esc = jsEscape(html);
        LogUtil.v(TAG, "webview js: updateLastMsg(htmlLen=%d)", esc.length());
        webViewEvalJs("updateLastMsg('" + esc + "')");
    }

    private void removeDomFrom(int pos) {
        LogUtil.v(TAG, "webview js: removeFromIdx(%d)", pos);
        webViewEvalJs("removeFromIdx(" + pos + ")");
    }

    private void removeDomRange(int fromPos) {
        LogUtil.v(TAG, "webview js: removeRangeFrom(%d)", fromPos);
        webViewEvalJs("removeRangeFrom(" + fromPos + ")");
    }

    // 最终回复提交完成后再收尾：先置请求结束，再放行排队消息。
    // 顺序不可颠倒——否则 drain 派发的新请求会自增代数，把本请求的最终渲染挡掉。
    private void finishRequestAndDrain() {
        runOnUiThread(new Runnable() {
            public void run() {
                isRequestInProgress.set(false);
                drainPendingQueue();
            }
        });
    }

    // 移除最后一个尚未 finalize 的 AI 占位 div（其 data-idx 仍为 -1，
    // removeRangeFrom 只处理 >=0 的索引，删不掉它）。ES5，Chromium 30 兼容。
    private void removeUnfinalizedAiDiv() {
        LogUtil.v(TAG, "webview js: remove unfinalized .msg.ai");
        webViewEvalJs("(function(){var a=document.querySelectorAll('.msg.ai');"
                + "if(a.length>0){var l=a[a.length-1];"
                + "if(l.getAttribute('data-idx')==='-1'&&l.parentNode)l.parentNode.removeChild(l);}})()");
    }

    // 请求失败时回滚仍为空的 assistant 占位：从 messages 移除并删掉对应 DOM div，
    // 避免空气泡被落盘、并出现在后续请求体里（对照 interruptRequest 的清理写法）。
    private void rollbackEmptyAssistantPlaceholder(final int aiIndex, final int generation) {
        runOnUiThread(new Runnable() {
            public void run() {
                if (requestGeneration.get() != generation) return;
                if (aiIndex >= 0 && aiIndex < messages.size()
                        && messages.get(aiIndex).isAssistant()
                        && (messages.get(aiIndex).content == null || messages.get(aiIndex).content.isEmpty())) {
                    LogUtil.d(TAG, "rollback empty AI placeholder at index=%d", aiIndex);
                    messages.remove(aiIndex);
                    removeUnfinalizedAiDiv();
                }
            }
        });
    }

    // Execute JS in the conversation WebView: evaluateJavascript (API 19+) avoids a
    // full page navigation per call; loadUrl fallback for API 18.
    private void webViewEvalJs(String js) {
        // onDestroy 后 WebView 已 destroy 并置空，迟到的回调直接丢弃
        WebView wv = conversationWebView;
        if (wv == null) return;
        if (android.os.Build.VERSION.SDK_INT >= 19) {
            wv.evaluateJavascript(js, null);
        } else {
            wv.loadUrl("javascript:" + js);
        }
    }

    private static String jsEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                // ES5 里 U+2028/U+2029 仍是行终止符，直接出现在字符串字面量中是语法错误
                .replace("\u2028", "\\u2028")
                .replace("\u2029", "\\u2029");
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
        public void saveCode(final String lang, final String code) {
            LogUtil.d(TAG, "JsBridge.saveCode: lang=%s len=%s",
                    lang, code == null ? "null" : code.length());
            handler.post(new Runnable() {
                public void run() {
                    saveCodeToExport(lang, code);
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

        @JavascriptInterface
        public void runJs(final String code) {
            LogUtil.d(TAG, "JsBridge.runJs: len=%s", code == null ? "null" : code.length());
            if (code == null || code.trim().isEmpty()) {
                return;
            }
            handler.post(new Runnable() {
                public void run() {
                    startActivity(newJsRunnerIntent(MainActivity.this, code));
                }
            });
        }
    }

    static Intent newJsRunnerIntent(Context ctx, String code) {
        return newJsRunnerIntent(ctx, code, null);
    }

    static Intent newJsRunnerIntent(Context ctx, String code, String lang) {
        Intent it = new Intent(ctx, JsRunnerActivity.class);
        it.putExtra(JsRunnerActivity.EXTRA_CODE, code);
        if (lang != null && !lang.isEmpty()) {
            it.putExtra(JsRunnerActivity.EXTRA_LANG, lang);
        }
        return it;
    }

    static Intent newPreviewIntent(Context ctx, String lang, String code) {
        Intent it = new Intent(ctx, JsRunnerActivity.class);
        it.putExtra(JsRunnerActivity.EXTRA_CODE, code);
        it.putExtra(JsRunnerActivity.EXTRA_LANG, lang);
        return it;
    }

    static Intent newPromptIntent(Context ctx, String prompt, boolean send) {
        Intent it = new Intent(ctx, MainActivity.class);
        it.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        it.putExtra(EXTRA_PROMPT, prompt);
        it.putExtra(EXTRA_PROMPT_SEND, send);
        return it;
    }

    /** Opens the zoom / background / rotate code viewer on top of the chat screen. */
    static Intent newCodePreviewIntent(Context ctx, String lang, String code) {
        Intent it = new Intent(ctx, MainActivity.class);
        it.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        it.putExtra(EXTRA_PREVIEW_LANG, lang);
        it.putExtra(EXTRA_PREVIEW_CODE, code);
        return it;
    }

    static final String EXTRA_PROMPT = "extra_prompt";
    static final String EXTRA_PROMPT_SEND = "extra_prompt_send";
    static final String EXTRA_PREVIEW_LANG = "extra_preview_lang";
    static final String EXTRA_PREVIEW_CODE = "extra_preview_code";

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
                    loadCurrentImage();
                }
            }
        });

        nextButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (currentImageIndex < currentImageList.size() - 1) {
                    currentImageIndex++;
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

        String rotateCss = rotateTransform(currentRotation);

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
            // Raster image. The image is laid out inside a swap box whose
            // width/height follow the rotation: at 90/270 the usable width is the
            // viewport HEIGHT, otherwise a wide formula (e.g. 400x42) is squeezed
            // to 95% of the 226px viewport and then clipped by the rotate
            // transform — the "truncated after rotate" bug.
            String src = MessageHtmlRenderer.escAttr(info.src);
            String alt = MessageHtmlRenderer.escAttr(info.alt);
            boolean swapped = (currentRotation == 90 || currentRotation == 270);
            int boxW = swapped ? imageViewerWebView.getHeight() : imageViewerWebView.getWidth();
            int boxH = swapped ? imageViewerWebView.getWidth() : imageViewerWebView.getHeight();
            if (boxW <= 0) boxW = 226;
            if (boxH <= 0) boxH = 871;
            float fill = swapped ? 0.62f : 0.95f;
            int maxW = Math.max(1, (int) (boxW * fill));
            int maxH = Math.max(1, (int) (boxH * fill));
            html = "<!DOCTYPE html><html><head>" +
                    "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0,user-scalable=yes\">" +
                    "<style>" +
                    "*{margin:0;padding:0;}" +
                    "html{width:100%;height:100%;}" +
                    "html,body{background:" + bgColor + ";overflow:auto;" +
                    "-webkit-transform-origin:0 0;transform-origin:0 0;}" +
                    "body{width:100%;height:100%;display:table;}" +
                    ".img-cell{display:table-cell;vertical-align:middle;text-align:center;width:100%;height:" + boxH + "px;}" +
                    "img,svg{display:inline-block;vertical-align:middle;max-width:" + maxW + "px;" +
                    "max-height:" + maxH + "px;" + rotateCss + "}" +
                    "</style>" +
                    "<script>var vZoom=1;function viewerZoom(f){vZoom=Math.min(5,Math.max(0.1,vZoom*f));" +
                    "var t='scale('+vZoom+')';" +
                    "document.body.style.webkitTransform=t;document.body.style.transform=t;}" +
                    "function viewerSetBg(c){document.body.style.backgroundColor=c;document.documentElement.style.backgroundColor=c;}" +
                    "</script>" +
                    "</head><body>" +
                    "<div class=\"img-cell\"><img src=\"" + src + "\" alt=\"" + alt + "\"></div>" +
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

    // ========== 代码块导出 ==========

    private void saveCodeToExport(final String lang, final String code) {
        if (code == null || code.trim().isEmpty()) {
            Toast.makeText(this, "代码内容为空，无法保存", Toast.LENGTH_SHORT).show();
            return;
        }
        final String ext = exportExtForLang(lang);
        final String type = exportTypeForLang(lang);
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText("code_" + type + "_" + System.currentTimeMillis());
        input.setSelection(input.getText().length());
        new AlertDialog.Builder(this)
                .setTitle("保存代码")
                .setMessage("文件名（自动追加 ." + ext + "）")
                .setView(input)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String name = sanitizeExportName(input.getText().toString());
                        if (name.isEmpty()) {
                            name = "code_" + type + "_" + System.currentTimeMillis();
                        }
                        final String fileName = name + "." + ext;
                        if (StorageManager.getInstance().exportExists(fileName)) {
                            confirmOverwrite(fileName, lang, code);
                            return;
                        }
                        doSaveExport(fileName, lang, code);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doSaveExport(String fileName, String lang, String code) {
        String path = StorageManager.getInstance().saveExport(fileName, code);
        LogUtil.i(TAG, "saveCodeToExport: lang=%s file=%s path=%s", lang, fileName, path);
        if (path == null) {
            Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "已保存到 " + path, Toast.LENGTH_LONG).show();
        }
    }

    private void confirmOverwrite(final String fileName, final String lang, final String code) {
        new AlertDialog.Builder(this)
                .setTitle("文件已存在")
                .setMessage(fileName + " 已存在，是否覆盖？")
                .setPositiveButton("覆盖", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        doSaveExport(fileName, lang, code);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    static String exportExtForLang(String lang) {
        String key = normalizeLangToken(lang);
        if (key.isEmpty()) {
            return "txt";
        }
        if ("html".equals(key) || "htm".equals(key) || "xhtml".equals(key)) {
            return "html";
        }
        if ("svg".equals(key)) {
            return "svg";
        }
        if ("js".equals(key) || "javascript".equals(key) || "node".equals(key) || "jsx".equals(key)) {
            return "js";
        }
        if ("ts".equals(key) || "typescript".equals(key)) {
            return "ts";
        }
        if ("py".equals(key) || "python".equals(key) || "python3".equals(key)) {
            return "py";
        }
        if ("kt".equals(key) || "kotlin".equals(key)) {
            return "kt";
        }
        if ("sh".equals(key) || "shell".equals(key) || "bash".equals(key)
                || "zsh".equals(key) || "console".equals(key)) {
            return "sh";
        }
        if ("c++".equals(key) || "cpp".equals(key) || "cxx".equals(key)) {
            return "cpp";
        }
        if ("c#".equals(key) || "cs".equals(key) || "csharp".equals(key)) {
            return "cs";
        }
        if ("yml".equals(key) || "yaml".equals(key)) {
            return "yaml";
        }
        if ("md".equals(key) || "markdown".equals(key)) {
            return "md";
        }
        if ("rs".equals(key) || "rust".equals(key)) {
            return "rs";
        }
        if ("rb".equals(key) || "ruby".equals(key)) {
            return "rb";
        }
        if ("pl".equals(key) || "perl".equals(key)) {
            return "pl";
        }
        if ("ps1".equals(key) || "powershell".equals(key)) {
            return "ps1";
        }
        if ("objective-c".equals(key) || "objc".equals(key)) {
            return "m";
        }
        if ("plaintext".equals(key) || "text".equals(key) || "txt".equals(key)) {
            return "txt";
        }
        // Already a usable extension token (java, json, xml, go, sql, css, php, swift,
        // dart, lua, scala, groovy, ini, properties, r, diff, patch, ...).
        if (key.matches("[a-z0-9_+-]{1,10}")) {
            return key;
        }
        return "txt";
    }

    /**
     * The type marker used in the default export file name, taken straight from the
     * code block's declared language so ```python saves as code_python_&lt;ts&gt;.py
     * instead of collapsing every unknown language into "txt".
     */
    static String exportTypeForLang(String lang) {
        String key = normalizeLangToken(lang);
        return key.isEmpty() ? "txt" : key;
    }

    private static String normalizeLangToken(String lang) {
        if (lang == null) {
            return "";
        }
        return lang.trim().toLowerCase().replaceAll("[^a-z0-9_+.#-]", "");
    }

    /** Strips path separators and characters Android's filesystem rejects. */
    static String sanitizeExportName(String name) {
        if (name == null) {
            return "";
        }
        return name.trim().replaceAll("[/\\\\:*?\"<>|]", "_");
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

        // Box the preview wrapper actually has. Needed because the rotated
        // wrapper must be sized to the swapped axis (see rotateTransformWrap).
        int boxW = codePreviewWebView.getWidth();
        int boxH = codePreviewWebView.getHeight();
        if (boxW <= 0) boxW = getResources().getDisplayMetrics().widthPixels;
        if (boxH <= 0) boxH = getResources().getDisplayMetrics().heightPixels;
        String rotateCss = rotateTransformWrap(codePreviewRotation, boxW, boxH);
        // When rotated 90/270 the wrapper's content box becomes portrait
        // (boxH wide). An SVG that declares width="600" would still be clamped
        // by its own max-width:100% against that narrower box and shrink to a
        // sliver. Tell the page how wide the rotated content box actually is so
        // the SVG can scale up to fill it.
        boolean rotatedSideways = (codePreviewRotation == 90 || codePreviewRotation == 270);

        String html;
        if ("svg".equals(currentPreviewLang)) {
            html = buildCodePreviewHtml(currentPreviewCode, currentPreviewLang,
                    bgColor, rotateCss, false, rotatedSideways ? boxH : 0);
        } else {
            // HTML: detect if it's a full document or fragment
            String trimmed = currentPreviewCode.trim().toLowerCase();
            boolean isFullDoc = trimmed.startsWith("<!doctype") || trimmed.startsWith("<html");
            html = buildCodePreviewHtml(currentPreviewCode, currentPreviewLang,
                    bgColor, rotateCss, isFullDoc, rotatedSideways ? boxH : 0);
        }

        codePreviewWebView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    private String buildCodePreviewHtml(String code, String lang,
            String bgColor, String rotateCss, boolean isFullHtmlDoc, int sideW) {
        String zoomJs =
                "var vZoom=1;" +
                "function viewerZoom(f){vZoom=Math.min(5,Math.max(0.1,vZoom*f));" +
                "var t='scale('+vZoom+')';" +
                "document.body.style.webkitTransform=t;document.body.style.transform=t;}" +
                "function viewerSetBg(c){document.body.style.backgroundColor=c;}";

        if ("svg".equals(lang)) {
            // Wrap SVG code in a minimal HTML page.
            //
            // The viewer toolbar (↻/#/◐/✕) is an overlay painted on top of this
            // WebView, so content starting at y=0 is hidden behind it — inset
            // the page by the toolbar height.
            //
            // Rotation is applied to the SVG's own wrapper. Rotating an inner
            // element while an ancestor still clips to the unrotated layout box
            // hides the rotated overflow ("truncated/blank after rotate"), so the
            // rotated box is given an explicit size on both axes instead of
            // relying on min-height:100%.
            String wrapCss = rotateCss.isEmpty() ? "" : rotateCss + "!important;";
            String svgW = sideW > 0 ? ("width:100%;max-width:none;") : "max-width:100%;";
            return "<!DOCTYPE html><html><head>" +
                    "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0,user-scalable=yes\">" +
                    "<style>" +
                    "*{margin:0;padding:0;}" +
                    "html{width:100%;height:100%;background:" + bgColor + ";" +
                    "overflow:hidden;-webkit-transform-origin:0 0;transform-origin:0 0;}" +
                    "body{width:100%;height:100%;background:" + bgColor + ";" +
                    "overflow:hidden;-webkit-transform-origin:0 0;transform-origin:0 0;" +
                    "padding:" + VIEWER_TOOLBAR_INSET_PX + "px 0 0 0;" +
                    "-webkit-box-sizing:border-box;box-sizing:border-box;}" +
                    ".preview-wrap{display:block;width:100%;overflow:visible;" + wrapCss + "}" +
                    ".preview-wrap svg{" + svgW + "height:auto;display:block;}" +
                    "</style>" +
                    "<script>" + zoomJs + "</script>" +
                    "</head><body>" +
                    "<div class=\"preview-wrap\">" + code + "</div>" +
                    "</body></html>";
        }

        if (isFullHtmlDoc) {
            // A full HTML document brings its own body/html rules, so rotating
            // body directly lets those rules fight the rotation compensation and
            // the whole document slides off-screen (blank preview after rotate).
            // Instead wrap the document content in a dedicated element whose
            // rotation geometry we fully control.
            String wrapCss = ".viewer-rot{"
                    + (rotateCss.isEmpty() ? "" : rotateCss + "!important;")
                    + "-webkit-transform-origin:0 0 !important;transform-origin:0 0 !important;"
                    + "}" +
                    // The viewer toolbar is an overlay on top of this WebView, so a
                    // document starting at y=0 renders behind it. Force the inset
                    // with !important because the document brings its own body rules.
                    "body{padding-top:" + VIEWER_TOOLBAR_INSET_PX + "px !important;" +
                    "-webkit-box-sizing:border-box !important;box-sizing:border-box !important;}";
            String inject = "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0,user-scalable=yes\">"
                    + "<style>" + wrapCss + "</style>"
                    + "<script>" + zoomJs + "</script>";
            String doc;
            String openTag;
            if (code.toLowerCase().contains("<head>")) {
                doc = code.replaceFirst("(?i)<head[^>]*>", "$0" + Matcher.quoteReplacement(inject));
                openTag = "<body[^>]*>";
            } else if (code.toLowerCase().contains("<html>")) {
                doc = code.replaceFirst("(?i)<html[^>]*>",
                        "$0<head>" + Matcher.quoteReplacement(inject) + "</head>");
                openTag = "<body[^>]*>";
            } else {
                doc = "<!DOCTYPE html><html><head>" + inject
                        + "</head><body>" + code + "</body></html>";
                openTag = "<body[^>]*>";
            }
            // Wrap the document body content so rotation applies to our element.
            if (rotateCss.isEmpty()) {
                return doc;
            }
            if (doc.matches("(?is).*<body[^>]*>.*</body>.*")) {
                doc = doc.replaceFirst("(?is)(<body[^>]*>)", "$1<div class=\"viewer-rot\">");
                doc = doc.replaceFirst("(?is)</body>", "</div></body>");
            }
            return doc;
        }

        // HTML fragment — wrap in minimal page
        return "<!DOCTYPE html><html><head>" +
                "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0,user-scalable=yes\">" +
                "<style>" +
                "*{margin:0;padding:0;}" +
                "html,body{width:100%;min-height:100%;background:" + bgColor + ";" +
                "overflow:auto;-webkit-transform-origin:0 0;transform-origin:0 0;}" +
                "body{padding:" + VIEWER_TOOLBAR_INSET_PX + "px 0 0 0;" +
                "-webkit-box-sizing:border-box;box-sizing:border-box;}" +
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

    /**
     * Rotation CSS that keeps the rotated content inside positive coordinates.
     * A plain rotate() spins around the element center (or 0 0 without
     * compensation), pushing part of the content into negative x/y where it is
     * clipped and NOT scroll-reachable — the "truncated after rotate" bug.
     * translate percentages are relative to the element's own box (W×H), so for
     * 90°/270° the wrapper must be sized to the swapped (portrait↔landscape)
     * box, otherwise the translation lands on the wrong axis and content is
     * pushed off-screen entirely.
     */
    private String rotateTransform(int deg) {
        switch (deg) {
            case 90:
                return "-webkit-transform:rotate(90deg) translateY(-100%);" +
                        "transform:rotate(90deg) translateY(-100%);" +
                        "-webkit-transform-origin:0 0;transform-origin:0 0;";
            case 180:
                return "-webkit-transform:translate(100%,100%) rotate(180deg);" +
                        "transform:translate(100%,100%) rotate(180deg);" +
                        "-webkit-transform-origin:0 0;transform-origin:0 0;";
            case 270:
                return "-webkit-transform:translateY(100%) rotate(270deg);" +
                        "transform:translateY(100%) rotate(270deg);" +
                        "-webkit-transform-origin:0 0;transform-origin:0 0;";
            default:
                return "";
        }
    }

    /**
     * Rotation CSS for the code/SVG/HTML preview wrapper.
     *
     * A wrapper div is full-width (W = viewport width, H = content height) and
     * the plain rotateTransform() above uses percentage translations relative to
     * that box. For 90°/270° the visual content must occupy the swapped box, so
     * a bare translateY(-100%) moves it by the wrapper's *height* on the rotated
     * axis — the content ends up entirely outside the visible area (the
     * "blank after rotate" bug). Compensating with explicit pixel widths on the
     * swapped axes keeps the rotated content inside positive coordinates and
     * reachable by scrolling.
     */
    private String rotateTransformWrap(int deg, int boxW, int boxH) {
        if (deg == 90) {
            // rotate(90) then pull back by the box height along the rotated Y.
            return "width:" + boxH + "px;" +
                    "-webkit-transform:rotate(90deg) translate(0,-100%);" +
                    "transform:rotate(90deg) translate(0,-100%);" +
                    "-webkit-transform-origin:0 0;transform-origin:0 0;";
        }
        if (deg == 270) {
            return "width:" + boxH + "px;" +
                    "-webkit-transform:rotate(-90deg) translate(-100%,0);" +
                    "transform:rotate(-90deg) translate(-100%,0);" +
                    "-webkit-transform-origin:0 0;transform-origin:0 0;";
        }
        if (deg == 180) {
            return "-webkit-transform:translate(100%,100%) rotate(180deg);" +
                    "transform:translate(100%,100%) rotate(180deg);" +
                    "-webkit-transform-origin:0 0;transform-origin:0 0;";
        }
        return "";
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
                        String cur = inputEditText.getText().toString();
                        boolean inserted = false;
                        if (cur.isEmpty()) {
                            inputEditText.setText(text);
                            inserted = true;
                        } else if (!cur.equals(text) && !cur.endsWith(text)) {
                            if (!cur.endsWith("\n")) {
                                inputEditText.append("\n");
                            }
                            inputEditText.append(text);
                            inserted = true;
                        }
                        if (inserted) {
                            inputEditText.setSelection(inputEditText.getText().length());
                        }
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
            // 补用户反馈：此前异常被静默吞掉，用户只看到输入框依旧为空
            Toast.makeText(this, "读取搜题结果失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
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
                        // 菜单挂起期间列表可能被删除/新增/打断清理移动，按对象标识重新定位
                        int cur = messages.indexOf(msg);
                        if (cur < 0) {
                            LogUtil.w(TAG, "message menu action dropped: target message no longer present");
                            Toast.makeText(MainActivity.this, "该消息已变化，操作已取消", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        LogUtil.i(TAG, "message menu action: curPos=%d which=%d (%s)", cur, which, items[which]);
                        switch (which) {
                            case 0: copyMessage(cur); break;
                            case 1: selectText(cur); break;
                            case 2: editMessage(cur); break;
                            case 3: deleteMessage(cur); break;
                            case 4: retryMessage(cur); break;
                            case 5: rollbackTo(cur); break;
                            case 6: branchAt(cur); break;
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
                                webViewEvalJs("updateMsgAt(" + pos + ",'" + esc + "')");
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
                webViewEvalJs("reindexFrom(" + pos + ")");
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

        // 流式进行中不允许重试，否则第二个请求线程会与在途请求并发共享 currentConnection / messages
        if (isRequestInProgress.get()) {
            LogUtil.w(TAG, "retryMessage aborted: request in progress, interrupt first");
            Toast.makeText(this, "当前回复进行中，请先打断再重试", Toast.LENGTH_SHORT).show();
            return;
        }

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
                branch.title = ConversationManager.normalizeTitle(current.title) + "-分支";
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
