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
    // 长对话分批渲染：首屏只渲染最近 N 条，滑到顶部再补一批，
    // 避免几百条消息（含公式/图片）一次性塞进 WebView 把老设备卡死。
    private static final int INITIAL_VISIBLE_MESSAGES = 40;
    private static final int OLDER_PAGE_SIZE = 30;
    // 工具轮次不再设上限：只要模型还在调用工具、用户没打断，就一直继续下去。
    // 单轮内实际执行的工具请求数由设置项 web_search_max_calls_per_round 限制。

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
    // DOM 里已渲染的最早消息在 messages 中的下标；其上滑加载时向前推进。
    // volatile：渲染线程读、UI 线程写。
    private volatile int renderedFrom = 0;
    // 防止滚动事件连续触发重复请求
    private volatile boolean loadingOlderMessages = false;

    private AtomicBoolean isRequestInProgress = new AtomicBoolean(false);
    private AtomicInteger requestGeneration = new AtomicInteger(0);
    // 打断并重试的串行化闸门：防止连点导致多个 resendUserAt 并发插入用户消息
    private volatile boolean retryInFlight = false;
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
        // 先自增代数再断开连接：在途请求看到代数变化后不再回写 UI / 落盘，
        // 否则页面已销毁，请求线程仍能把消息写进已卸载的会话文件。
        if (isRequestInProgress.get()) {
            isRequestInProgress.set(false);
            requestGeneration.incrementAndGet();
            LogUtil.i(TAG, "onDestroy: aborted in-flight request");
        }
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
            final String conversationId = data == null ? null : data.getStringExtra("conversation_id");
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
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        conversationManager.switchConversation(conversationId);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Conversation conv = conversationManager.getCurrentConversation();
                                messages = conv.messages;
                                LogUtil.i(TAG, "switched: id=%s title=%s messages=%d",
                                        conv.id, conv.title, messages.size());
                                refreshWebView();
                                Toast.makeText(MainActivity.this, "已切换到: " + conv.title,
                                        Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                }, "aichat-switch-conversation").start();
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
        if (isFirstMsg && !conv.titleGenerated) {
            // 先落一个「消息前 15 字」的兜底标题：自动标题关闭、生成失败或返回空时都保留它
            applyFallbackTitle(conv, input);
            if (autoTitle) {
                generateTitle(input);
            } else {
                LogUtil.d(TAG, "auto title disabled, keep fallback title");
            }
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
        final boolean streamEnabled = SettingsActivity.isStreamEnabled(this);
        final boolean webSearchEnabled = SettingsActivity.isWebSearchEnabled(this);
        // 搜索后端在 UI 线程先定死：Tavily 走自建网关，不需要 DeepSeek 提供商；
        // DeepSeek 后端才要求存在带 Key 的 DeepSeek 提供商。
        final boolean useTavily = SettingsActivity.isTavilySearch(this);
        // 密钥只在运行时从设置读取，不写死、不打进安装包
        final String tavilyKey = SettingsActivity.getTavilyKey(this);
        // 单轮工具调用上限（web_search + web_fetch 合计），在 UI 线程先读定，避免请求线程反复访问 prefs
        final int maxCallsPerRound = SettingsActivity.getWebSearchMaxCallsPerRound(this);
        final String chatEndpoint = provider.apiUrl + provider.chatPath;
        final String providerName = provider.name;
        final String modelName = currentModel;
        final String effectiveSystemPrompt = webSearchEnabled
                ? WebSearchTool.appendToSystemPrompt(systemPrompt) : systemPrompt;
        final ProviderInfo searchProvider = (webSearchEnabled && !useTavily)
                ? WebSearchTool.resolveProvider(configManager, provider) : null;
        // 搜索是否真的可用：Tavily 只看内置密钥；DeepSeek 需要解析到提供商
        final boolean searchReady = webSearchEnabled
                && (useTavily ? TavilyTool.hasApiKey(tavilyKey) : searchProvider != null);
        final long startMs = System.currentTimeMillis();
        LogUtil.i(TAG, ">>> sendStreamingRequest START: gen=%d url=%s%s model=%s thinking=%s webSearch=%s backend=%s searchProvider=%s",
                generation, provider.apiUrl, provider.chatPath, currentModel, thinkingLevel,
                webSearchEnabled, useTavily ? "tavily" : "deepseek",
                searchProvider == null ? "none" : searchProvider.name);
        isRequestInProgress.set(true);

        final Message aiMsg = new Message(Message.ROLE_ASSISTANT, "");
        messages.add(aiMsg);
        final int aiIndex = messages.size() - 1;
        // 在 UI 线程做一次快照：请求线程遍历时 UI 线程可能结构性修改 messages，
        // 直接遍历会抛 ConcurrentModificationException
        final List<Message> requestSnapshot = new ArrayList<Message>(messages);
        final String originalQuestion = lastUserContent(requestSnapshot);
        LogUtil.d(TAG, "AI placeholder added at index=%d, total=%d", aiIndex, messages.size());

        // Append AI placeholder div
        runOnUiThread(new Runnable() {
            public void run() {
                LogUtil.v(TAG, "webview js: appendAiDiv()");
                webViewEvalJs("appendAiDiv()");
            }
        });

        final WebSearchTool.RequestGuard searchGuard = new WebSearchTool.RequestGuard() {
            public boolean shouldAbort() {
                return !isRequestInProgress.get() || requestGeneration.get() != generation;
            }
            public void onConnection(HttpURLConnection c) {
                if (c != null) {
                    if (isRequestInProgress.get() && requestGeneration.get() == generation) {
                        currentConnection = c;
                    }
                } else if (isRequestInProgress.get() && requestGeneration.get() == generation) {
                    currentConnection = null;
                }
            }
        };

        currentRequestThread = new Thread(new Runnable() {
            public void run() {
                final StringBuilder rawContent = new StringBuilder();
                final List<String[]> searchSources = new ArrayList<String[]>();
                final boolean[] warnedNoSearchKey = {false};
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

                String finalContent = "";
                HttpURLConnection conn = null;
                // 失败详情：HTTP 分支与异常分支都会写，finally 里交给 UI 生成错误卡片
                final String[] errorDetail = {""};
                // 当前轮次：异常分支在 try 外也要读，故用单元素数组
                final int[] roundRef = {0};
                try {
                    JSONArray conversation = buildChatConversation(requestSnapshot, aiMsg, effectiveSystemPrompt);
                    JSONArray toolDefs = webSearchEnabled ? WebSearchTool.buildToolDefinitions() : null;
                    boolean toolsEnabled = webSearchEnabled;
                    boolean fallbackTried = false;
                    int round = 0;
                    // Display text kept for the whole request: thinking + tool cards from
                    // every round. Without this the reasoning emitted before a tool call
                    // was discarded and replaced by the next round / status text.
                    final StringBuilder trace = new StringBuilder();

                    while (true) {
                        roundRef[0] = round;
                        if (!isRequestInProgress.get() || requestGeneration.get() != generation) {
                            LogUtil.i(TAG, "tool loop aborted before round %d", round);
                            return;
                        }
                        if (round > 0) {
                            renderSeq.incrementAndGet();
                            showSearchStatus("正在整理搜索结果...", generation, trace.toString(), aiIndex);
                        }

                        JSONObject body = buildChatRequestBody(modelName, provider, thinkingLevel, conversation,
                                streamEnabled, toolsEnabled ? toolDefs : null);

                        LogUtil.d(TAG, "opening connection: %s%s (round=%d, thread=%s)",
                                provider.apiUrl, provider.chatPath, round, LogUtil.thread());
                        conn = openChatConnection(provider);
                        currentConnection = conn;

                        final String payload = body.toString();
                        LogUtil.d(TAG, "request body built: len=%d", payload.length());
                        LogUtil.v(TAG, "request body: %s", LogUtil.preview(payload, 1500));

                        long reqMs = System.currentTimeMillis();
                        java.io.OutputStream os = conn.getOutputStream();
                        try {
                            os.write(payload.getBytes("UTF-8"));
                        } finally {
                            os.close();
                        }
                        LogUtil.d(TAG, "request body written in %d ms", System.currentTimeMillis() - reqMs);

                        int code = conn.getResponseCode();
                        LogUtil.i(TAG, "HTTP response: code=%d in %d ms (round=%d)",
                                code, System.currentTimeMillis() - reqMs, round);
                        if (code != 200) {
                            String errBody = readErrorBody(conn);
                            LogUtil.e(TAG, "request FAILED: HTTP %d, body=%s", code, LogUtil.preview(errBody, 800));

                            // 部分 OpenAI 兼容端点不认识 tools 参数。首轮 400 时去掉工具
                            // 与搜索系统提示重试一次，保证原有普通对话不受联网开关影响。
                            if ((code == 400 || code == 422) && toolsEnabled && round == 0 && !fallbackTried) {
                                fallbackTried = true;
                                toolsEnabled = false;
                                conversation = buildChatConversation(requestSnapshot, aiMsg, systemPrompt);
                                LogUtil.w(TAG, "provider rejected tools (HTTP %d), retrying once without web search; body=%s",
                                        code, LogUtil.preview(errBody, 500));
                                runOnUiThread(new Runnable() {
                                    public void run() {
                                        Toast.makeText(MainActivity.this,
                                                "当前模型不支持联网工具，已按普通对话发送",
                                                Toast.LENGTH_SHORT).show();
                                    }
                                });
                                closeChatConnection(conn);
                                conn = null;
                                if (requestGeneration.get() == generation) currentConnection = null;
                                continue;
                            }

                            final String err = "HTTP " + code;
                            requestFailed[0] = true;
                            errorDetail[0] = buildErrorDetail(providerName, modelName, chatEndpoint,
                                    round, code, errBody, null);
                            LogUtil.e(TAG, "request FAILED (round %d): %s", round, errorDetail[0]);
                            runOnUiThread(new Runnable() {
                                public void run() { Toast.makeText(MainActivity.this, "请求失败: " + err, Toast.LENGTH_SHORT).show(); }
                            });
                            return;
                        }

                        final StringBuilder roundDisplay = new StringBuilder();
                        final StringBuilder roundContent = new StringBuilder();
                        final boolean[] roundThinkingActive = {false};
                        final boolean[] roundThinkingFinished = {false};
                        final JSONArray roundToolCalls = new JSONArray();

                        if (!streamEnabled) {
                            // Non-streaming: the body is one JSON object, no SSE frames.
                            StringBuilder sb = new StringBuilder();
                            java.io.BufferedReader reader = new java.io.BufferedReader(
                                    new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"));
                            try {
                                String line;
                                while ((line = reader.readLine()) != null) sb.append(line);
                            } finally {
                                reader.close();
                            }

                            JSONObject json = new JSONObject(sb.toString());
                            JSONArray choices = json.optJSONArray("choices");
                            JSONObject message = choices != null && choices.length() > 0
                                    ? choices.getJSONObject(0).optJSONObject("message") : null;
                            if (message == null) {
                                LogUtil.e(TAG, "non-stream response missing choices[0].message");
                                final String err = "响应解析失败";
                                requestFailed[0] = true;
                                errorDetail[0] = buildErrorDetail(providerName, modelName, chatEndpoint,
                                        round, 200, sb.toString(),
                                        new Exception("非流式响应缺少 choices[0].message"));
                                runOnUiThread(new Runnable() {
                                    public void run() { Toast.makeText(MainActivity.this, err, Toast.LENGTH_SHORT).show(); }
                                });
                                return;
                            }

                            String reasoning = message.has("reasoning_content") && !message.isNull("reasoning_content")
                                    ? message.optString("reasoning_content", "") : "";
                            if (reasoning.length() > 0) {
                                roundDisplay.append("[thinking]").append(reasoning).append("[/thinking]");
                                thinkingChunks[0]++;
                            }
                            String content = message.has("content") && !message.isNull("content")
                                    ? message.optString("content", "") : "";
                            roundDisplay.append(content);
                            roundContent.append(content);
                            if (content.length() > 0) contentChunks[0]++;
                            JSONArray rawCalls = message.optJSONArray("tool_calls");
                            if (rawCalls != null) normalizeToolCalls(rawCalls, roundToolCalls);
                            if (content.length() == 0 && reasoning.length() == 0
                                    && roundToolCalls.length() == 0) {
                                LogUtil.e(TAG, "non-stream response has no content and no tool calls");
                                final String err = "响应解析失败";
                                requestFailed[0] = true;
                                errorDetail[0] = buildErrorDetail(providerName, modelName, chatEndpoint,
                                        round, 200, sb.toString(),
                                        new Exception("响应既无 content 也无 tool_calls"));
                                runOnUiThread(new Runnable() {
                                    public void run() { Toast.makeText(MainActivity.this, err, Toast.LENGTH_SHORT).show(); }
                                });
                                return;
                            }
                            LogUtil.i(TAG, "non-stream round %d: raw=%d content=%d tools=%d",
                                    round, sb.length(), content.length(), roundToolCalls.length());
                        } else {
                            LogUtil.i(TAG, "SSE stream started (round %d)", round);
                            java.io.BufferedReader reader = new java.io.BufferedReader(
                                    new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"));
                            String line;
                            long lastUpdate = 0;
                            try {
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
                                        JSONObject json = new JSONObject(data);
                                        JSONArray choices = json.optJSONArray("choices");
                                        if (choices == null || choices.length() == 0) {
                                            LogUtil.v(TAG, "chunk#%d has no choices: %s", chunkCount[0], LogUtil.preview(data, 200));
                                            continue;
                                        }
                                        JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
                                        if (delta == null) {
                                            LogUtil.v(TAG, "chunk#%d has no delta", chunkCount[0]);
                                            continue;
                                        }

                                        if (delta.has("reasoning_content") && !delta.isNull("reasoning_content")) {
                                            String rc = delta.getString("reasoning_content");
                                            thinkingChunks[0]++;
                                            if (!roundThinkingActive[0]) {
                                                roundThinkingActive[0] = true;
                                                roundDisplay.append("[thinking]");
                                                LogUtil.d(TAG, "thinking phase START at chunk#%d", chunkCount[0]);
                                            }
                                            roundDisplay.append(rc);
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
                                            if (ct.length() > 0 && roundThinkingActive[0] && !roundThinkingFinished[0]) {
                                                roundThinkingFinished[0] = true;
                                                roundDisplay.append("[/thinking]");
                                                LogUtil.d(TAG, "thinking phase END at chunk#%d (thinkingChunks=%d)",
                                                        chunkCount[0], thinkingChunks[0]);
                                            }
                                            roundDisplay.append(ct);
                                            roundContent.append(ct);
                                            if (LogUtil.ENABLED) {
                                                LogUtil.v(TAG, "content chunk#%d: %s", contentChunks[0], LogUtil.preview(ct, 150));
                                            }
                                        }

                                        JSONArray deltaCalls = delta.optJSONArray("tool_calls");
                                        if (deltaCalls != null && deltaCalls.length() > 0) {
                                            accumulateToolCalls(roundToolCalls, deltaCalls);
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
                                            final String partial = trace.toString() + roundDisplay.toString();
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
                            } finally {
                                reader.close();
                            }
                            if (roundThinkingActive[0] && !roundThinkingFinished[0]) {
                                roundDisplay.append("[/thinking]");
                                LogUtil.d(TAG, "thinking phase left open at stream end: closed with [/thinking]");
                            }
                            LogUtil.i(TAG, "stream round %d finished: chunks=%d tools=%d contentLen=%d displayLen=%d",
                                    round, chunkCount[0], roundToolCalls.length(),
                                    roundContent.length(), roundDisplay.length());
                        }

                        closeChatConnection(conn);
                        conn = null;
                        if (requestGeneration.get() == generation) currentConnection = null;

                        if (roundToolCalls.length() > 0 && hasUsableToolCall(roundToolCalls)) {
                            // Keep this round's reasoning/partial answer visible.
                            trace.append(roundDisplay);
                            appendAssistantToolCalls(conversation, roundContent.toString(), roundToolCalls);
                            // 本轮实际执行计数：超过上限的调用只回一条占位结果，不发请求
                            int executedThisRound = 0;
                            for (int i = 0; i < roundToolCalls.length(); i++) {
                                JSONObject tc = roundToolCalls.getJSONObject(i);
                                String id = tc.optString("id", "");
                                String name = tc.optString("name", "");
                                if (name.length() == 0) continue;

                                // 一轮内所有 tool_calls 都必须有对应的 tool 结果，否则下一轮
                                // 请求的 assistant/tool 配对不完整，多数端点会直接 400。
                                if (executedThisRound >= maxCallsPerRound) {
                                    appendToolResult(conversation, id, limitResultJson(maxCallsPerRound));
                                    LogUtil.w(TAG, "tool call %d/%d NOT executed: per-round limit %d reached (tool=%s)",
                                            i + 1, roundToolCalls.length(), maxCallsPerRound, name);
                                    continue;
                                }
                                executedThisRound++;

                                String argText = tc.optString("arguments", "");
                                JSONObject args;
                                try {
                                    args = argText.trim().length() == 0 ? new JSONObject() : new JSONObject(argText);
                                } catch (Exception e) {
                                    LogUtil.w(TAG, "tool call args parse failed: %s | raw=%s",
                                            e.getMessage(), LogUtil.preview(argText, 200));
                                    args = new JSONObject();
                                }

                                renderSeq.incrementAndGet();
                                showSearchStatus(toolStatusText(name, args), generation, trace.toString(), aiIndex);
                                String toolResult;
                                if (!searchReady) {
                                    toolResult = useTavily
                                            ? "{\"error\":\"未配置 Tavily API Key，无法使用联网搜索\"}"
                                            : "{\"error\":\"未配置 DeepSeek API Key，无法使用联网搜索\"}";
                                    if (!warnedNoSearchKey[0]) {
                                        warnedNoSearchKey[0] = true;
                                        final String warnText = useTavily
                                                ? "未配置 Tavily API Key，联网搜索不可用"
                                                : "未配置 DeepSeek API Key，联网搜索不可用";
                                        runOnUiThread(new Runnable() {
                                            public void run() {
                                                Toast.makeText(MainActivity.this,
                                                        warnText, Toast.LENGTH_LONG).show();
                                            }
                                        });
                                    }
                                } else {
                                    toolResult = WebSearchTool.executeTool(
                                            searchProvider, name, args, originalQuestion,
                                            searchGuard, useTavily, tavilyKey);
                                }

                                WebSearchTool.extractSources(toolResult, searchSources);
                                appendToolResult(conversation, id, toolResult);
                                trace.append(WebSearchTool.buildTraceBlock(name, args, toolResult));
                                if (requestGeneration.get() == generation) {
                                    renderSeq.incrementAndGet(); // drop any in-flight stream render
                                    renderTraceAsync(trace.toString(), generation, null, aiIndex);
                                }

                                if (!isRequestInProgress.get() || requestGeneration.get() != generation) {
                                    LogUtil.i(TAG, "tool loop aborted after tool %s", name);
                                    return;
                                }
                            }
                            round++;
                            continue;
                        }

                        trace.append(roundDisplay);
                        rawContent.setLength(0);
                        rawContent.append(trace);
                        finalContent = rawContent.toString();
                        break;
                    }

                    // 来源独立成结构字段：不拼接正文，避免后续每次请求都把来源列表再喂回模型
                    aiMsg.sources = dedupeSources(searchSources, ConversationManager.MAX_SOURCES);
                    LogUtil.i(TAG, "sources collected: %d", aiMsg.sources.size());
                    rawContent.setLength(0);
                    rawContent.append(finalContent);
                    renderSeq.incrementAndGet(); // invalidate any in-flight stream render
                } catch (final Exception e) {
                    requestFailed[0] = true;
                    errorDetail[0] = buildErrorDetail(providerName, modelName, chatEndpoint,
                            roundRef[0], -1, "", e);
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
                                        // 失败标记打到对应的用户消息上（错误卡片 + 点击重试）
                                        markSendFailed(finalIdx, errorDetail[0], generation);
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
                                            // 来源区独立渲染在 .content 之外，默认折叠
                                            String srcHtml = MessageHtmlRenderer.renderSourcesInner(aiMsg.sources);
                                            webViewEvalJs("updateLastSources('" + jsEscape(srcHtml) + "')");
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

    private static JSONArray buildChatConversation(List<Message> snapshot, Message aiMsg,
            String sysPrompt) throws Exception {
        JSONArray msgs = new JSONArray();
        if (sysPrompt != null && !sysPrompt.isEmpty()) {
            JSONObject sm = new JSONObject();
            sm.put("role", "system");
            sm.put("content", sysPrompt);
            msgs.put(sm);
        }
        for (Message m : snapshot) {
            if (m == aiMsg) continue;
            JSONObject mm = new JSONObject();
            mm.put("role", m.role);
            String sendContent = m.isAssistant() ? ApiClient.removeThinkingContent(m.content) : m.content;
            mm.put("content", sendContent == null ? "" : sendContent);
            msgs.put(mm);
        }
        return msgs;
    }

    private JSONObject buildChatRequestBody(String model, ProviderInfo provider, String thinkingLevel,
            JSONArray msgs, boolean stream, JSONArray tools) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("stream", stream);
        body.put("messages", msgs);
        if (tools != null && tools.length() > 0) {
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        }

        if ("boolean".equals(provider.thinkingType)) {
            if (!"off".equals(thinkingLevel)) {
                body.put(provider.thinkingParamName, true);
                int budget = 4096;
                if ("low".equals(thinkingLevel)) budget = 2048;
                else if ("high".equals(thinkingLevel)) budget = 32768;
                body.put("thinking_budget", budget);
            }
        } else {
            JSONObject tObj = new JSONObject();
            tObj.put("type", "off".equals(thinkingLevel) ? "disabled" : "enabled");
            body.put(provider.thinkingParamName, tObj);
            if (!"off".equals(thinkingLevel)) {
                body.put("reasoning_effort", "high".equals(thinkingLevel) ? "max" : "high");
            }
        }
        return body;
    }

    private static HttpURLConnection openChatConnection(ProviderInfo provider) throws Exception {
        String endpoint = provider.apiUrl + provider.chatPath;
        java.net.URL url = new java.net.URL(endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + provider.apiKey);
        conn.setDoOutput(true);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(0);
        if (conn instanceof javax.net.ssl.HttpsURLConnection) {
            TlsCompat.apply((javax.net.ssl.HttpsURLConnection) conn);
        }
        return conn;
    }

    private static void closeChatConnection(HttpURLConnection conn) {
        if (conn == null) return;
        try {
            conn.disconnect();
        } catch (Exception ignored) {}
    }

    private static String readErrorBody(HttpURLConnection conn) {
        if (conn == null) return "";
        try {
            java.io.InputStream es = conn.getErrorStream();
            if (es == null) return "";
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            int total = 0;
            while (total < 4096 && (n = es.read(buf, 0, Math.min(buf.length, 4096 - total))) > 0) {
                bos.write(buf, 0, n);
                total += n;
            }
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static String lastUserContent(List<Message> snapshot) {
        if (snapshot == null) return "";
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            Message m = snapshot.get(i);
            if (m != null && m.isUser() && m.content != null) return m.content;
        }
        return "";
    }

    private static void normalizeToolCalls(JSONArray raw, JSONArray out) throws Exception {
        if (raw == null) return;
        for (int i = 0; i < raw.length(); i++) {
            JSONObject item = raw.optJSONObject(i);
            if (item == null) continue;
            JSONObject tc = new JSONObject();
            tc.put("id", item.optString("id", ""));
            JSONObject fn = item.optJSONObject("function");
            tc.put("name", fn == null ? "" : fn.optString("name", ""));
            tc.put("arguments", fn == null ? "{}" : fn.optString("arguments", "{}"));
            out.put(tc);
        }
    }

    private static void accumulateToolCalls(JSONArray acc, JSONArray deltas) throws Exception {
        if (deltas == null) return;
        for (int i = 0; i < deltas.length(); i++) {
            JSONObject d = deltas.optJSONObject(i);
            if (d == null) continue;
            int index = d.optInt("index", i);
            while (acc.length() <= index) {
                JSONObject empty = new JSONObject();
                empty.put("id", "");
                empty.put("name", "");
                empty.put("arguments", "");
                acc.put(empty);
            }
            JSONObject tc = acc.getJSONObject(index);
            if (d.has("id") && !d.isNull("id")) {
                String id = d.optString("id", "");
                if (id.length() > 0) tc.put("id", id);
            }
            JSONObject fn = d.optJSONObject("function");
            if (fn != null) {
                if (fn.has("name") && !fn.isNull("name")) {
                    String name = fn.optString("name", "");
                    if (name.length() > 0) {
                        String old = tc.optString("name", "");
                        if (old.length() == 0) tc.put("name", name);
                        else if (!old.equals(name)) tc.put("name", old + name);
                    }
                }
                if (fn.has("arguments") && !fn.isNull("arguments")) {
                    String args = fn.optString("arguments", "");
                    tc.put("arguments", tc.optString("arguments", "") + args);
                }
            }
        }
    }

    private static boolean hasUsableToolCall(JSONArray calls) {
        if (calls == null) return false;
        for (int i = 0; i < calls.length(); i++) {
            JSONObject tc = calls.optJSONObject(i);
            if (tc != null && tc.optString("name", "").length() > 0) return true;
        }
        return false;
    }

    private static void appendAssistantToolCalls(JSONArray conversation, String content,
            JSONArray calls) throws Exception {
        JSONArray payload = new JSONArray();
        for (int i = 0; i < calls.length(); i++) {
            JSONObject tc = calls.getJSONObject(i);
            String name = tc.optString("name", "");
            if (name.length() == 0) continue;
            String id = tc.optString("id", "");
            if (id.length() == 0) {
                id = "call_" + i + "_" + System.currentTimeMillis();
                tc.put("id", id);
            }
            String argText = tc.optString("arguments", "");
            if (argText.trim().length() == 0) argText = "{}";

            JSONObject out = new JSONObject();
            out.put("id", id);
            out.put("type", "function");
            JSONObject fn = new JSONObject();
            fn.put("name", name);
            fn.put("arguments", argText);
            out.put("function", fn);
            payload.put(out);
        }
        if (payload.length() == 0) return;

        JSONObject assistant = new JSONObject();
        assistant.put("role", "assistant");
        assistant.put("content", content == null || content.length() == 0 ? JSONObject.NULL : content);
        assistant.put("tool_calls", payload);
        conversation.put(assistant);
    }

    private static void appendToolResult(JSONArray conversation, String id, String content)
            throws Exception {
        JSONObject tm = new JSONObject();
        tm.put("role", "tool");
        tm.put("tool_call_id", id == null ? "" : id);
        tm.put("content", content == null ? "" : content);
        conversation.put(tm);
    }

    private static String toolStatusText(String name, JSONObject args) {
        if (WebSearchTool.TOOL_SEARCH.equals(name)) {
            String query = args == null ? "" : args.optString("query", "");
            return query.length() > 0 ? "正在联网搜索：" + shorten(query, 60) : "正在联网搜索...";
        }
        if (WebSearchTool.TOOL_FETCH.equals(name)) {
            String url = args == null ? "" : args.optString("url", "");
            return url.length() > 0 ? "正在读取网页：" + shorten(url, 60) : "正在读取网页...";
        }
        return "正在调用工具：" + shorten(name, 40);
    }

    private static String shorten(String text, int max) {
        if (text == null) return "";
        String oneLine = text.replace('\n', ' ').replace('\r', ' ');
        if (oneLine.length() <= max) return oneLine;
        return oneLine.substring(0, max) + "...";
    }

    /** 单轮工具调用超限时，为未执行的调用补的 tool 结果。 */
    private static String limitResultJson(int maxPerRound) {
        try {
            JSONObject json = new JSONObject();
            json.put("error", "每轮工具调用上限为 " + maxPerRound + "，本次调用未执行");
            json.put("limit_notice", "请基于已执行结果继续回答，或直接给出最终回答。");
            return json.toString();
        } catch (Exception e) {
            return "{\"error\":\"每轮工具调用已超上限，本次调用未执行\"}";
        }
    }

    /**
     * 汇总一次请求失败的完整上下文，供错误卡片与「查看详情」使用。
     * 这些信息只落盘用于展示，不会进入任何 API 请求体。
     */
    private static String buildErrorDetail(String providerName, String modelName, String endpoint,
            int round, int httpCode, String responseBody, Exception e) {
        StringBuilder sb = new StringBuilder();
        java.text.SimpleDateFormat fmt =
                new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US);
        sb.append("时间：").append(fmt.format(new java.util.Date())).append('\n');
        sb.append("提供商：").append(providerName == null ? "-" : providerName).append('\n');
        sb.append("模型：").append(modelName == null ? "-" : modelName).append('\n');
        sb.append("端点：").append(endpoint == null ? "-" : endpoint).append('\n');
        sb.append("轮次：第 ").append(round + 1).append(" 轮\n");
        if (httpCode > 0) {
            sb.append("HTTP 状态：").append(httpCode).append('\n');
        } else {
            sb.append("HTTP 状态：未收到响应\n");
        }
        String providerMsg = extractProviderError(responseBody);
        if (providerMsg.length() > 0) {
            sb.append("提供商错误：").append(providerMsg).append('\n');
        }
        if (e != null) {
            sb.append("异常类型：").append(e.getClass().getName()).append('\n');
            String msg = e.getMessage();
            sb.append("异常消息：").append(msg == null || msg.length() == 0 ? "(空)" : msg).append('\n');
        }
        if (responseBody != null && responseBody.trim().length() > 0) {
            String body = responseBody.length() > 2000 ? responseBody.substring(0, 2000) + "…" : responseBody;
            sb.append("\n响应体（前 2000 字）：\n").append(body);
        }
        return sb.toString();
    }

    /** 尽量从 OpenAI 风格的错误体里取出可读的 error.message。 */
    private static String extractProviderError(String body) {
        if (body == null || body.trim().length() == 0) return "";
        try {
            JSONObject json = new JSONObject(body);
            JSONObject err = json.optJSONObject("error");
            if (err != null) {
                String m = err.optString("message", "");
                if (m.length() > 0) return m;
                return err.toString();
            }
            String m = json.optString("message", "");
            if (m.length() > 0) return m;
        } catch (Exception ignored) {
        }
        String one = body.replace('\n', ' ').replace('\r', ' ').trim();
        return one.length() > 300 ? one.substring(0, 300) + "…" : one;
    }

    /** 来源按 URL 去重，保留首次出现的顺序，最多 limit 条。 */
    private static List<Message.Source> dedupeSources(List<String[]> raw, int limit) {
        List<Message.Source> out = new ArrayList<Message.Source>();
        if (raw == null || raw.isEmpty()) return out;
        List<String> seen = new ArrayList<String>();
        for (int i = 0; i < raw.size() && out.size() < limit; i++) {
            String[] row = raw.get(i);
            if (row == null || row.length < 2) continue;
            String url = row[1];
            if (url == null || url.trim().length() == 0) continue;
            url = url.trim();
            if (seen.contains(url)) continue;
            seen.add(url);
            String title = row[0];
            if (title == null || title.trim().length() == 0) title = url;
            out.add(new Message.Source(title.trim(), url));
        }
        return out;
    }

    /**
     * 把失败标记写到本次请求对应的用户消息上，并让 DOM 立刻显示错误卡片。
     * 失败标记与详情会随会话落盘，重启后仍在。
     */
    private void markSendFailed(final int aiIndex, final String detail, final int generation) {
        runOnUiThread(new Runnable() {
            public void run() {
                if (requestGeneration.get() != generation) {
                    LogUtil.d(TAG, "markSendFailed skipped: generation changed");
                    return;
                }
                int size = messages.size();
                int end = aiIndex >= 0 && aiIndex < size ? aiIndex : size;
                int userIdx = -1;
                for (int i = end - 1; i >= 0; i--) {
                    if (messages.get(i).isUser()) { userIdx = i; break; }
                }
                if (userIdx < 0) {
                    LogUtil.w(TAG, "markSendFailed: no user message before aiIndex=%d", aiIndex);
                    return;
                }
                Message um = messages.get(userIdx);
                um.sendFailed = true;
                um.errorDetail = detail == null ? "" : detail;
                LogUtil.i(TAG, "markSendFailed: userIdx=%d detailLen=%d", userIdx, um.errorDetail.length());
                String cardHtml = MessageHtmlRenderer.renderErrorCard(um, userIdx);
                webViewEvalJs("markUserFailed(" + userIdx + ",'" + jsEscape(cardHtml) + "')");
                conversationManager.saveCurrentConversation();
            }
        });
    }

    /** 标题兜底：取消息前 15 个字符，去掉换行并合并连续空白。 */
    private static String fallbackTitle(String text) {
        if (text == null) return "";
        String one = text.replace('\r', ' ').replace('\n', ' ');
        one = one.replaceAll("\\s+", " ").trim();
        if (one.length() > 15) one = one.substring(0, 15);
        return one;
    }

    /**
     * 仅当标题仍是「新对话」或空、且尚未由模型生成过标题时才设置兜底标题。
     * 用户手动改过名（titleGenerated=true）时绝不覆盖。
     */
    private void applyFallbackTitle(Conversation conv, String firstMessage) {
        if (conv == null || conv.titleGenerated) return;
        String cur = conv.title == null ? "" : conv.title.trim();
        if (cur.length() > 0 && !"新对话".equals(cur)) {
            LogUtil.d(TAG, "applyFallbackTitle skipped: title already '%s'", cur);
            return;
        }
        String ft = fallbackTitle(firstMessage);
        if (ft.length() == 0) return;
        conv.title = ft;
        conversationManager.saveCurrentConversation();
        LogUtil.i(TAG, "fallback title set: '%s'", ft);
    }

    private void showSearchStatus(final String text, final int generation, final String traceText,
            final int aiIndex) {
        renderTraceAsync(traceText, generation, text, aiIndex);
    }

    /**
     * Render the accumulated trace (thinking + tool cards) off the UI thread and
     * push it to the last AI bubble. {@code status}, when non-null, is appended as
     * a transient status line that the next render will replace.
     */
    private void renderTraceAsync(final String traceText, final int generation, final String status,
            final int aiIndex) {
        final String snapshot = traceText == null ? "" : traceText;
        new Thread(new Runnable() {
            public void run() {
                final String html = MessageHtmlRenderer.contentToHtml(snapshot, MainActivity.this)
                        + (status == null ? "" : statusHtml(status));
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (requestGeneration.get() != generation) return;
                        if (aiIndex >= 0 && aiIndex < messages.size()
                                && messages.get(aiIndex).isAssistant()) {
                            messages.get(aiIndex).content = snapshot;
                        }
                        webViewEvalJs("updateLastMsg('" + jsEscape(html) + "')");
                    }
                });
            }
        }).start();
    }

    private static String statusHtml(String status) {
        return "<div class=\"tool-status\">" + MessageHtmlRenderer.esc(status) + "</div>";
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

                                if (title.length() == 0) {
                                    // 模型返回空标题：保留发送时写入的「前 15 字」兜底标题
                                    LogUtil.i(TAG, "generateTitle: empty result, keep fallback title");
                                    return;
                                }
                                final String finalTitle = title;
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
        // 只渲染最近一批：更早的消息等用户上滑到顶部再追加
        final int from = Math.max(0, snapshot.size() - INITIAL_VISIBLE_MESSAGES);
        new Thread(new Runnable() {
            public void run() {
                final String html = MessageHtmlRenderer.buildConversationHtml(snapshot, from, MainActivity.this);
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
                        renderedFrom = from;
                        loadingOlderMessages = false;
                        conversationWebView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
                        LogUtil.d(TAG, "webview reloaded: showing %d of %d messages (from=%d)",
                                snapshot.size() - from, snapshot.size(), from);
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

    private void updateAiContent(final String content) {
        // 渲染代数守卫：异步渲染期间若 refreshWebView 已换页（如切会话），丢弃过期注入
        final int myRenderGen = renderGeneration.get();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String html = MessageHtmlRenderer.contentToHtml(content, MainActivity.this);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (renderGeneration.get() != myRenderGen) return;
                        String esc = jsEscape(html);
                        LogUtil.v(TAG, "webview js: updateLastMsg(htmlLen=%d)", esc.length());
                        webViewEvalJs("updateLastMsg('" + esc + "')");
                    }
                });
            }
        }) {{ setName("aichat-render-" + System.currentTimeMillis()); }}.start();
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

        /** 用户滑到顶部时，追加更早的一批消息（在后台线程渲染，避免卡 UI）。 */
        @JavascriptInterface
        public void loadOlderMessages() {
            handler.post(new Runnable() {
                public void run() {
                    if (loadingOlderMessages) return;
                    if (messages == null || messages.isEmpty()) {
                        webViewEvalJs("setOlderHint(0)");
                        return;
                    }
                    final int from = Math.min(renderedFrom, messages.size());
                    if (from <= 0) {
                        webViewEvalJs("setOlderHint(0)");
                        LogUtil.d(TAG, "loadOlderMessages: already at the beginning");
                        return;
                    }
                    final int start = Math.max(0, from - OLDER_PAGE_SIZE);
                    final List<Message> slice = new ArrayList<Message>(messages.subList(start, from));
                    loadingOlderMessages = true;
                    LogUtil.d(TAG, "loadOlderMessages: rendering %d messages [%d,%d)",
                            slice.size(), start, from);
                    new Thread(new Runnable() {
                        public void run() {
                            final String html = MessageHtmlRenderer.renderMessagesHtml(
                                    slice, start, MainActivity.this);
                            runOnUiThread(new Runnable() {
                                public void run() {
                                    // 期间发生过整页重建（切会话 / 设置变更）就丢弃这一批，避免错位
                                    if (renderedFrom != from) {
                                        loadingOlderMessages = false;
                                        // 这一批被丢弃，但 JS 侧的闸门必须复位，否则上滑再也触发不了
                                        webViewEvalJs("resetOlderGate()");
                                        LogUtil.d(TAG, "loadOlderMessages dropped: renderedFrom=%d, expected=%d",
                                                renderedFrom, from);
                                        return;
                                    }
                                    renderedFrom = start;
                                    loadingOlderMessages = false;
                                    webViewEvalJs("prependOlder('" + jsEscape(html) + "'," + start + ")");
                                    LogUtil.d(TAG, "loadOlderMessages: prepended, renderedFrom=%d, htmlLen=%d",
                                            start, html.length());
                                }
                            });
                        }
                    }) {{ setName("aichat-older-" + System.currentTimeMillis()); }}.start();
                }
            });
        }

        /** 点击失败的用户气泡 / 错误卡片上的「重试」：立即重发；若正在进行别的请求则先确认打断。 */
        @JavascriptInterface
        public void retryFailedUserMessage(final String idx) {
            LogUtil.d(TAG, "JsBridge.retryFailedUserMessage: idx=%s", idx);
            handler.post(new Runnable() {
                public void run() {
                    try {
                        int pos = Integer.parseInt(idx);
                        if (pos < 0 || pos >= messages.size()) return;
                        Message m = messages.get(pos);
                        if (m == null || !m.isUser() || !m.sendFailed) {
                            LogUtil.d(TAG, "retryFailedUserMessage ignored: pos=%d not a failed user msg", pos);
                            return;
                        }
                        if (isRequestInProgress.get()) {
                            confirmInterruptAndRetry(pos);
                        } else {
                            resendUserAt(pos);
                        }
                    } catch (Exception e) {
                        LogUtil.w(TAG, "retryFailedUserMessage failed: %s", e.getMessage());
                    }
                }
            });
        }

        /** 错误卡片「查看详情」：完整展示采集到的失败上下文。 */
        @JavascriptInterface
        public void showErrorDetail(final String idx) {
            LogUtil.d(TAG, "JsBridge.showErrorDetail: idx=%s", idx);
            handler.post(new Runnable() {
                public void run() {
                    try {
                        int pos = Integer.parseInt(idx);
                        if (pos < 0 || pos >= messages.size()) return;
                        String detail = messages.get(pos).errorDetail;
                        if (detail == null || detail.length() == 0) detail = "（没有更多错误信息）";
                        TextView tv = new TextView(MainActivity.this);
                        tv.setText(detail);
                        tv.setTextSize(12);
                        tv.setTextIsSelectable(true);
                        tv.setPadding(24, 24, 24, 24);
                        android.widget.ScrollView sv = new android.widget.ScrollView(MainActivity.this);
                        sv.addView(tv);
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("错误详情")
                                .setView(sv)
                                .setPositiveButton("关闭", null)
                                .show();
                    } catch (Exception e) {
                        LogUtil.w(TAG, "showErrorDetail failed: %s", e.getMessage());
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
        // 跨进程 ContentProvider 查询 + 游标读取移出主线程，结果回 UI 线程更新输入框
        new Thread(new Runnable() {
            @Override
            public void run() {
                Cursor cursor = null;
                try {
                    LogUtil.d(TAG, "loadQuickScanResult: querying content://com.jxw.wbzc/query");
                    cursor = getContentResolver().query(
                        Uri.parse("content://com.jxw.wbzc/query"),
                        null, null, null, "_id DESC");
                    if (cursor != null && cursor.moveToFirst()) {
                        int idx = cursor.getColumnIndex("content");
                        if (idx >= 0) {
                            final String text = cursor.getString(idx);
                            if (text != null && !text.isEmpty()) {
                                LogUtil.i(TAG, "quick scan result: len=%d preview=%s",
                                        text.length(), LogUtil.preview(text, 100));
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
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
                                    }
                                });
                                return;
                            }
                            LogUtil.w(TAG, "quick scan result: content column empty");
                        } else {
                            LogUtil.w(TAG, "quick scan result: no 'content' column in cursor");
                        }
                    } else {
                        LogUtil.w(TAG, "quick scan result: no row returned");
                    }
                } catch (Exception e) {
                    LogUtil.e(TAG, "quick scan result query failed", e);
                    // 补用户反馈：此前异常被静默吞掉，用户只看到输入框依旧为空
                    final String errMsg = e.getMessage();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this, "读取搜题结果失败: " + errMsg,
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                } finally {
                    if (cursor != null) cursor.close();
                }
            }
        }, "aichat-quick-scan").start();
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
                                // DOM update: only update this message's div; render off the UI thread
                                new Thread(new Runnable() {
                                    @Override
                                    public void run() {
                                        final String html = msg.isAssistant()
                                            ? MessageHtmlRenderer.contentToHtml(newContent, MainActivity.this)
                                            : "<div class=\"bubble\">" + MessageHtmlRenderer.esc(newContent) + "</div>";
                                        runOnUiThread(new Runnable() {
                                            @Override
                                            public void run() {
                                                String esc = jsEscape(html);
                                                webViewEvalJs("updateMsgAt(" + pos + ",'" + esc + "')");
                                                conversationManager.saveCurrentConversation();
                                                Toast.makeText(MainActivity.this, "已修改", Toast.LENGTH_SHORT).show();
                                            }
                                        });
                                    }
                                }) {{ setName("aichat-render-" + System.currentTimeMillis()); }}.start();
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
                // 渲染起点跟着前移，否则上滑加载会按旧下标取到错位的消息
                if (pos < renderedFrom) renderedFrom--;
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

        if (isRequestInProgress.get()) {
            // 回复进行中：弹框确认后先打断旧请求（自增代数 → 断开 → 等旧线程退出）再重试
            LogUtil.w(TAG, "retryMessage: request in progress, asking to interrupt");
            confirmInterruptAndRetry(pos);
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
                    if (renderedFrom > pos) renderedFrom = pos;
                    removeDomRange(pos);
                    execStreamingRequest();
                }
            });
        } else {
            showConfirmDialog("将删除本条及之后共 " + n + " 条消息并重新发送，确定？", new Runnable() {
                public void run() {
                    LogUtil.i(TAG, "retry(resend): truncate from pos=%d then resend", pos);
                    resendUserAt(pos);
                }
            });
        }
    }

    /**
     * 从 pos 处的用户消息重新发送：删除该条及之后的所有消息，重新插入同样的用户内容，
     * 清除失败标记，然后发起请求。点击失败气泡、长按菜单「重试」都走这里。
     */
    private void resendUserAt(final int pos) {
        if (pos < 0 || pos >= messages.size()) {
            LogUtil.w(TAG, "resendUserAt: pos=%d out of range (size=%d)", pos, messages.size());
            return;
        }
        Message src = messages.get(pos);
        if (src == null || !src.isUser()) {
            LogUtil.w(TAG, "resendUserAt: pos=%d is not a user message", pos);
            return;
        }
        final String content = src.content == null ? "" : src.content;
        LogUtil.i(TAG, "resendUserAt: pos=%d, dropping %d messages, len=%d",
                pos, messages.size() - pos, content.length());

        messages.subList(pos, messages.size()).clear();
        // 截断会把渲染起点之后的消息删掉，起点最多回退到 pos
        if (renderedFrom > pos) renderedFrom = pos;
        removeDomRange(pos);

        Message um = new Message(Message.ROLE_USER, content);
        // 新建的消息 sendFailed/errorDetail 均为默认值，失败标记自然被清除
        messages.add(um);
        conversationManager.getCurrentConversation().touch();
        String userHtml = MessageHtmlRenderer.renderMessageDiv(um, messages.size() - 1, MainActivity.this);
        appendHtml(userHtml);
        conversationManager.saveCurrentConversation();
        execStreamingRequest();
    }

    /** 回复进行中点击重试：确认 → 打断 → 等旧线程退出 → 清理旧 UI（校验代数）→ 重发。 */
    private void confirmInterruptAndRetry(final int pos) {
        new AlertDialog.Builder(this)
                .setTitle("确认")
                .setMessage("当前回复正在进行，是否打断该请求并重试？")
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        interruptAndRetry(pos);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void interruptAndRetry(final int pos) {
        if (retryInFlight) {
            LogUtil.d(TAG, "interruptAndRetry ignored: another retry is in flight");
            return;
        }
        retryInFlight = true;
        LogUtil.i(TAG, "interruptAndRetry: pos=%d", pos);

        // 顺序：先自增代数（旧线程的所有回写都会被守卫挡掉）→ 置结束 → 断开连接
        final int newGen = requestGeneration.incrementAndGet();
        isRequestInProgress.set(false);
        if (currentConnection != null) {
            try { currentConnection.disconnect(); } catch (Exception ignored) {}
            currentConnection = null;
        }

        final Thread oldThread = currentRequestThread;
        new Thread(new Runnable() {
            public void run() {
                if (oldThread != null && oldThread != Thread.currentThread()) {
                    try {
                        oldThread.join(3000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
                LogUtil.d(TAG, "interruptAndRetry: old request thread settled");
                runOnUiThread(new Runnable() {
                    public void run() {
                        try {
                            if (requestGeneration.get() != newGen) {
                                LogUtil.d(TAG, "interruptAndRetry dropped: a newer request took over");
                                return;
                            }
                            if (isFinishing()) return;
                            // 旧请求的 finally 因代数不匹配不会清理，这里补做：
                            // 未定稿的 AI 占位 div（data-idx=-1）先删，再清掉残留的空 assistant 消息
                            removeUnfinalizedAiDiv();
                            while (!messages.isEmpty()) {
                                Message last = messages.get(messages.size() - 1);
                                if (last.isAssistant()
                                        && (last.content == null || last.content.length() == 0)) {
                                    messages.remove(messages.size() - 1);
                                } else {
                                    break;
                                }
                            }
                            if (pos < messages.size() && messages.get(pos).isUser()) {
                                resendUserAt(pos);
                            } else {
                                LogUtil.w(TAG, "interruptAndRetry: pos=%d no longer valid (size=%d)",
                                        pos, messages.size());
                            }
                        } finally {
                            retryInFlight = false;
                        }
                    }
                });
            }
        }) {{ setName("aichat-retry-" + System.currentTimeMillis()); }}.start();
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
                if (renderedFrom > messages.size()) renderedFrom = messages.size();
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
                    // 来源区跟着消息一起分支，否则分支里的回答会丢掉来源
                    if (src.sources != null && !src.sources.isEmpty()) {
                        for (Message.Source s : src.sources) {
                            if (s == null) continue;
                            copy.sources.add(new Message.Source(s.title, s.url));
                        }
                    }
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
