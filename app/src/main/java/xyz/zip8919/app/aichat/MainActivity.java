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
import android.net.Uri;
import java.net.HttpURLConnection;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
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
    private MessageQueue queue;
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
    // 流式渲染序号：每次发起渲染就自增，慢的那次回来时序号已经过期就丢弃。
    // 提到字段上是因为 renderTraceAsync 与流式 tick 都要用它做互斥。
    private final java.util.concurrent.atomic.AtomicInteger renderSeq =
            new java.util.concurrent.atomic.AtomicInteger();
    // volatile：请求线程写、UI 线程读，打断时才能可靠 disconnect
    private volatile HttpURLConnection currentConnection;
    // volatile：后台加载线程回到 UI 线程后判断 initConversation() 是否已执行；
    // 已执行则 loadConversations() 整体替换列表时丢掉了兜底会话，需按 id 补回列表头部。
    private volatile boolean initConversationDone = false;
    private Thread currentRequestThread;
    private Handler handler = new Handler();

    // 全屏预览器（图片 / 表格 / HTML·SVG 代码），自持 dialog 与 WebView 生命周期
    private ViewerController viewer;

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
        if (viewer != null) {
            viewer.destroy();
            viewer = null;
        }
        if (queue != null) {
            queue.destroy();
            queue = null;
        }
        // 兜底：onPause 之后可能又有保存入队，销毁前再清一次
        conversationManager.flushSaves();
        super.onDestroy();
    }

    private void initViews() {
        LogUtil.d(TAG, "initViews");
        viewer = new ViewerController(this);
        inputEditText = (EditText) findViewById(R.id.input_edit_text);
        sendButton = (Button) findViewById(R.id.send_button);
        modelSpinner = (Spinner) findViewById(R.id.model_spinner);
        thinkingSpinner = (Spinner) findViewById(R.id.thinking_spinner);
        queueBar = findViewById(R.id.queue_bar);
        queueBarText = (TextView) findViewById(R.id.queue_bar_text);
        queue = new MessageQueue(this, new MessageQueue.Host() {
            public boolean isRequestInFlight() { return isRequestInProgress.get(); }
            public void sendQueued(String text) { sendMessageText(text); }
            public void interruptForQueued() { interruptRequest(); }
        }, queueBar, queueBarText);
        findViewById(R.id.queue_manage_button).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { LogUtil.d(TAG, "click: queue_manage"); queue.showManager(); }
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
        if (queue != null) queue.clearAll();
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
                if (queue != null) queue.drain();
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
                if (queue != null) queue.clearAll();
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
        viewer.showCode(lang, code);
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
        // 保存是异步的，退到后台前把队列清干净，避免进程被回收时丢掉最后一次写入
        conversationManager.flushSaves();
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
        if (viewer != null) viewer.onConfigurationChanged();
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

    /**
     * 发送键：空闲时直接发，正在回复时入队。
     * 队列本身（排序/插队/弹窗）在 {@link MessageQueue}，这里只管输入框这一侧。
     */
    private void sendMessage() {
        String input = inputEditText.getText().toString().trim();
        if (input.isEmpty()) {
            LogUtil.w(TAG, "sendMessage ignored: empty input");
            return;
        }
        inputEditText.setText("");
        if (isRequestInProgress.get()) {
            queue.enqueue(input);
            return;
        }
        sendMessageText(input);
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
        LogUtil.d(TAG, "title check: isFirstMsg=%s autoTitle=%s titleGenerated=%s titleRequested=%s",
                isFirstMsg, autoTitle, conv.titleGenerated, conv.titleRequested);
        // titleRequested 在发起请求前就置位：否则「首轮回复失败回滚/删除消息」后
        // 再发一条又会满足 size==1，同一个会话会重复请求标题。
        if (isFirstMsg && !conv.titleGenerated && !conv.titleRequested) {
            conv.titleRequested = true;
            // 先落一个「消息前 15 字」的兜底标题：自动标题关闭、生成失败或返回空时都保留它
            TitleGenerator.applyFallback(conversationManager, conv, input);
            if (autoTitle) {
                TitleGenerator.generate(MainActivity.this, configManager, conversationManager, input);
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

    /**
     * 工具轮次中途失败后的续跑状态：保留已经回放给模型的 tool_calls/tool 结果，
     * 只重新发最后一次请求，而不是把整条用户消息重发一遍。
     */
    private static class ResumeState {
        final int aiIndex;
        final int round;
        final String trace;
        final JSONArray conversation;
        final List<String[]> sources;
        final String apiText;
        final String toolHistoryJson;

        ResumeState(int aiIndex, int round, String trace, JSONArray conversation,
                List<String[]> sources, String apiText, String toolHistoryJson) {
            this.aiIndex = aiIndex;
            this.round = round;
            this.trace = trace;
            this.conversation = conversation;
            this.sources = sources;
            this.apiText = apiText;
            this.toolHistoryJson = toolHistoryJson;
        }
    }

    private void sendStreamingRequest(final ProviderInfo provider, final String thinkingLevel, final int generation) {
        sendStreamingRequest(provider, thinkingLevel, generation, null);
    }

    private void sendStreamingRequest(final ProviderInfo provider, final String thinkingLevel,
            final int generation, final ResumeState resume) {
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
        // 系统提示按后端拼装：时间工具始终说明，联网工具仅在开关打开时说明
        final String effectiveSystemPrompt =
                WebSearchTool.appendToSystemPrompt(systemPrompt, webSearchEnabled);
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

        final Message aiMsg;
        final int aiIndex;
        if (resume != null) {
            // 续跑：复用已存在的那条 AI 消息，不新增占位、不重复插 DOM
            aiMsg = messages.get(resume.aiIndex);
            aiIndex = resume.aiIndex;
            LogUtil.i(TAG, "resume request from aiIndex=%d, round=%d, traceLen=%d",
                    aiIndex, resume.round, resume.trace == null ? 0 : resume.trace.length());
        } else {
            aiMsg = new Message(Message.ROLE_ASSISTANT, "");
            messages.add(aiMsg);
            aiIndex = messages.size() - 1;
        }
        // 在 UI 线程做一次快照：请求线程遍历时 UI 线程可能结构性修改 messages，
        // 直接遍历会抛 ConcurrentModificationException
        final List<Message> requestSnapshot = new ArrayList<Message>(messages);
        final String originalQuestion = ChatHistory.lastUserContent(requestSnapshot);
        LogUtil.d(TAG, "AI message at index=%d, total=%d", aiIndex, messages.size());

        // Append AI placeholder div（续跑时页面里已经有这条消息，只需清掉错误卡片）
        runOnUiThread(new Runnable() {
            public void run() {
                if (resume != null) {
                    webViewEvalJs("clearAssistantFailed(" + aiIndex + ")");
                } else {
                    LogUtil.v(TAG, "webview js: appendAiDiv()");
                    webViewEvalJs("appendAiDiv()");
                }
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
                // 本条回复真正发给模型的纯文本（不含 thinking/tool 标记），落盘后供后续轮次回放
                final StringBuilder apiTextBuilder = new StringBuilder(
                        resume != null && resume.apiText != null ? resume.apiText : "");
                // 本条回复产生的工具调用历史（assistant tool_calls + tool 结果）
                // 续跑时先把之前已经完成的轮次搬进来，避免再次失败时历史丢失
                final JSONArray toolHistory = new JSONArray();
                if (resume != null && resume.toolHistoryJson != null
                        && resume.toolHistoryJson.length() > 2) {
                    try {
                        JSONArray prev = new JSONArray(resume.toolHistoryJson);
                        for (int i = 0; i < prev.length(); i++) toolHistory.put(prev.get(i));
                    } catch (Exception e) {
                        LogUtil.w(TAG, "resume: bad toolHistory, starting empty: %s", e.getMessage());
                    }
                }
                final List<String[]> searchSources = resume != null
                        ? new ArrayList<String[]>(resume.sources) : new ArrayList<String[]>();
                final boolean[] warnedNoSearchKey = {false};
                final int[] chunkCount = {0};
                final int[] thinkingChunks = {0};
                final int[] contentChunks = {0};
                final boolean[] renderInFlight = {false};
                // 请求是否失败（HTTP 非 200 / 解析失败 / 异常）：失败时 finally 需回滚空占位
                final boolean[] requestFailed = {false};

                String finalContent = "";
                HttpURLConnection conn = null;
                // 失败详情：HTTP 分支与异常分支都会写，finally 里交给 UI 生成错误卡片
                final String[] errorDetail = {""};
                // 当前轮次：异常分支在 try 外也要读，故用单元素数组
                final int[] roundRef = {0};
                // 最后一次 HTTP 状态（-1 表示没拿到响应）：400/422 说明工具格式被拒，
                // 这类失败「从出错处重试」也没用，应退回整条消息重发。
                final int[] lastHttpCode = {-1};
                // Display text kept for the whole request: thinking + tool cards from
                // every round. Without this the reasoning emitted before a tool call
                // was discarded and replaced by the next round / status text.
                // 声明在 try 外：失败分支在 finally 里还要用它落盘已展示的思考/工具卡片。
                final StringBuilder trace = new StringBuilder(resume != null && resume.trace != null
                        ? resume.trace : "");
                // 已经推送到 DOM 的 base（思考 + 工具卡片）文本：只有它变化时才重渲染，
                // 避免流式期间每帧都重建上面那一大段
                final String[] baseRendered = {""};
                try {
                    JSONArray conversation = resume != null
                            ? resume.conversation
                            : ChatHistory.build(requestSnapshot, aiMsg, effectiveSystemPrompt);
                    // 时间工具始终提供，所以即便联网关闭也要发送 tools
                    JSONArray toolDefs = WebSearchTool.buildToolDefinitions(webSearchEnabled);
                    boolean toolsEnabled = true;
                    boolean fallbackTried = false;
                    int round = resume != null ? resume.round : 0;
                    if (resume != null && trace.length() > 0) {
                        showSearchStatus("正在重新生成...", generation, trace.toString(), aiIndex);
                    }

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

                        JSONObject body = ChatTransport.buildRequestBody(modelName, provider, thinkingLevel, conversation,
                                streamEnabled, toolsEnabled ? toolDefs : null);

                        LogUtil.d(TAG, "opening connection: %s%s (round=%d, thread=%s)",
                                provider.apiUrl, provider.chatPath, round, LogUtil.thread());
                        conn = ChatTransport.open(provider);
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
                            String errBody = ChatTransport.readErrorBody(conn);
                            LogUtil.e(TAG, "request FAILED: HTTP %d, body=%s", code, LogUtil.preview(errBody, 800));

                            // 部分 OpenAI 兼容端点不认识 tools 参数。首轮 400 时去掉工具
                            // 与搜索系统提示重试一次，保证原有普通对话不受联网开关影响。
                            if ((code == 400 || code == 422) && toolsEnabled && round == 0 && !fallbackTried) {
                                fallbackTried = true;
                                toolsEnabled = false;
                                conversation = ChatHistory.build(requestSnapshot, aiMsg, systemPrompt);
                                LogUtil.w(TAG, "provider rejected tools (HTTP %d), retrying once without web search; body=%s",
                                        code, LogUtil.preview(errBody, 500));
                                runOnUiThread(new Runnable() {
                                    public void run() {
                                        Toast.makeText(MainActivity.this,
                                                "当前模型不支持工具调用，已按普通对话发送",
                                                Toast.LENGTH_SHORT).show();
                                    }
                                });
                                ChatTransport.close(conn);
                                conn = null;
                                if (requestGeneration.get() == generation) currentConnection = null;
                                continue;
                            }

                            final String err = "HTTP " + code;
                            requestFailed[0] = true;
                            lastHttpCode[0] = code;
                            errorDetail[0] = ErrorReport.build(providerName, modelName, chatEndpoint,
                                    round, code, errBody, null);
                            LogUtil.e(TAG, "request FAILED (round %d): %s", round, errorDetail[0]);
                            runOnUiThread(new Runnable() {
                                public void run() { Toast.makeText(MainActivity.this, "请求失败: " + err, Toast.LENGTH_SHORT).show(); }
                            });
                            return;
                        }

                        final long[] lastEmit = {0};
                        SseRound.Result rr = SseRound.read(streamEnabled, conn.getInputStream(),
                                new SseRound.Progress() {
                                    public void onProgress(String displayNow, String contentNow) {
                                        // 节流：最多 200ms 渲染一次；渲染在后台线程，
                                        // 同时在飞的渲染只保留一个，期间的 tick 直接丢弃。
                                        // base（已完成轮次的思考/工具卡片）与 tail（本轮增量）
                                        // 分开更新，否则每个分片都会把上面的卡片打回纯文本。
                                        long now = System.currentTimeMillis();
                                        if (realtimeRenderEnabled && now - lastEmit[0] > 200 && !renderInFlight[0]) {
                                            lastEmit[0] = now;
                                            renderInFlight[0] = true;
                                            final String baseNow = trace.toString();
                                            final String roundNow = displayNow;
                                            final boolean baseChanged = !baseNow.equals(baseRendered[0]);
                                            final int mySeq = renderSeq.incrementAndGet();
                                            new Thread(new Runnable() {
                                                public void run() {
                                                    final String baseHtml = baseChanged
                                                            ? MessageHtmlRenderer.contentToHtml(baseNow, MainActivity.this)
                                                            : null;
                                                    final String tailHtml =
                                                            MessageHtmlRenderer.contentToHtml(roundNow, MainActivity.this);
                                                    runOnUiThread(new Runnable() {
                                                        public void run() {
                                                            renderInFlight[0] = false;
                                                            if (renderSeq.get() != mySeq) {
                                                                return; // 被更新的渲染取代
                                                            }
                                                            if (requestGeneration.get() == generation && aiIndex < messages.size()) {
                                                                messages.get(aiIndex).content = baseNow + roundNow;
                                                                if (baseHtml != null) {
                                                                    baseRendered[0] = baseNow;
                                                                    webViewEvalJs("streamBase('" + jsEscape(baseHtml) + "')");
                                                                }
                                                                webViewEvalJs("streamTailHtml('" + jsEscape(tailHtml) + "')");
                                                            } else {
                                                                LogUtil.w(TAG, "throttled update skipped: aiIndex=%d >= size=%d",
                                                                        aiIndex, messages.size());
                                                            }
                                                        }
                                                    });
                                                }
                                            }).start();
                                        } else if (!realtimeRenderEnabled && !renderInFlight[0] && now - lastEmit[0] > 120) {
                                            lastEmit[0] = now;
                                            final String baseNow = trace.toString();
                                            final String roundNow = displayNow;
                                            // 只把本轮增量转成纯文本；整段转会把前面的
                                            // 工具卡片、思考块、markdown 全抹掉
                                            final String tailPlain = TextKit.plainForStream(roundNow);
                                            final boolean baseChanged = !baseNow.equals(baseRendered[0]);
                                            if (!baseChanged) {
                                                runOnUiThread(new Runnable() {
                                                    public void run() {
                                                        if (requestGeneration.get() == generation && aiIndex < messages.size()) {
                                                            messages.get(aiIndex).content = baseNow + roundNow;
                                                            webViewEvalJs("streamTailText('" + jsEscape(tailPlain) + "')");
                                                        }
                                                    }
                                                });
                                            } else {
                                                // trace 刚追加了工具卡片：重建一次 base，再续上本轮文字
                                                renderInFlight[0] = true;
                                                new Thread(new Runnable() {
                                                    public void run() {
                                                        final String baseHtml =
                                                                MessageHtmlRenderer.contentToHtml(baseNow, MainActivity.this);
                                                        runOnUiThread(new Runnable() {
                                                            public void run() {
                                                                renderInFlight[0] = false;
                                                                if (requestGeneration.get() == generation && aiIndex < messages.size()) {
                                                                    baseRendered[0] = baseNow;
                                                                    messages.get(aiIndex).content = baseNow + roundNow;
                                                                    webViewEvalJs("streamBase('" + jsEscape(baseHtml) + "')");
                                                                    webViewEvalJs("streamTailText('" + jsEscape(tailPlain) + "')");
                                                                }
                                                            }
                                                        });
                                                    }
                                                }).start();
                                            }
                                        }
                                    }
                                },
                                new SseRound.Abort() {
                                    public boolean isAborted() { return !isRequestInProgress.get(); }
                                });
                        final StringBuilder roundDisplay = rr.display;
                        final StringBuilder roundContent = rr.content;
                        final JSONArray roundToolCalls = rr.toolCalls;
                        chunkCount[0] = rr.chunks;
                        thinkingChunks[0] = rr.thinkingChunks;
                        contentChunks[0] = rr.contentChunks;
                        if (rr.error != null) {
                            requestFailed[0] = true;
                            errorDetail[0] = ErrorReport.build(providerName, modelName, chatEndpoint,
                                    round, rr.httpCode, rr.rawBody, rr.cause);
                            final String err = rr.error;
                            runOnUiThread(new Runnable() {
                                public void run() {
                                    Toast.makeText(MainActivity.this, err, Toast.LENGTH_SHORT).show();
                                }
                            });
                            return;
                        }

                        ChatTransport.close(conn);
                        conn = null;
                        if (requestGeneration.get() == generation) currentConnection = null;

                        if (roundToolCalls.length() > 0 && ToolCalls.hasUsable(roundToolCalls)) {
                            // Keep this round's reasoning/partial answer visible.
                            trace.append(roundDisplay);
                            // 注意：这里不把 roundContent 累进 apiText——工具轮次的正文
                            // 已经作为 assistant(tool_calls).content 存在于 toolHistory 里，
                            // 再拼一次会让模型看到重复的同一段回答。
                            ToolCalls.appendAssistant(conversation, toolHistory,
                                    roundContent.toString(), roundToolCalls);
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
                                    ToolCalls.appendResult(conversation, toolHistory, id, ToolCalls.limitNotice(maxCallsPerRound));
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
                                showSearchStatus(ToolCalls.statusText(name, args), generation, trace.toString(), aiIndex);
                                String toolResult;
                                if (WebSearchTool.TOOL_TIME.equals(name)) {
                                    // 本地工具：不依赖搜索密钥，也不受联网开关影响
                                    toolResult = WebSearchTool.currentTimeResult();
                                } else if (!searchReady) {
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
                                ToolCalls.appendResult(conversation, toolHistory, id, toolResult);
                                // 所有工具都打卡片：时间工具此前被跳过，界面上完全看不到
                                trace.append(WebSearchTool.buildTraceBlock(name, args, toolResult));
                                if (requestGeneration.get() == generation) {
                                    // renderTraceAsync 自己会自增渲染序号，作废进行中的流式渲染
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
                        // 最终回复的正文（只有这一轮要作为最后一条 assistant 消息回放）
                        apiTextBuilder.setLength(0);
                        apiTextBuilder.append(roundContent);
                        rawContent.setLength(0);
                        rawContent.append(trace);
                        finalContent = rawContent.toString();
                        break;
                    }

                    // 来源独立成结构字段：不拼接正文，避免后续每次请求都把来源列表再喂回模型
                    aiMsg.sources = dedupeSources(searchSources, ConversationManager.MAX_SOURCES);
                    // 结构化落盘：下一次请求靠 apiText 还原正文、靠 toolHistory 回放工具调用
                    aiMsg.apiText = apiTextBuilder.toString();
                    if (toolHistory.length() > 0) aiMsg.toolHistory = toolHistory.toString();
                    LogUtil.i(TAG, "sources collected: %d, apiText=%d chars, toolHistory=%d entries",
                            aiMsg.sources.size(), aiMsg.apiText.length(), toolHistory.length());
                    rawContent.setLength(0);
                    rawContent.append(finalContent);
                    renderSeq.incrementAndGet(); // invalidate any in-flight stream render
                } catch (final Exception e) {
                    requestFailed[0] = true;
                    errorDetail[0] = ErrorReport.build(providerName, modelName, chatEndpoint,
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
                                        // 工具结果已经回传给模型的轮次：保留这条 AI 消息和
                                        // 已展示的思考/工具卡片，挂「从出错处重试」，不再重发
                                        // 整条用户消息，也不必重新执行已经跑过的工具。
                                        if (roundRef[0] > 0 && toolHistory.length() > 0
                                                && lastHttpCode[0] != 400 && lastHttpCode[0] != 422) {
                                            markAssistantFailed(finalIdx, errorDetail[0],
                                                    trace.toString(), toolHistory.toString(),
                                                    apiTextBuilder.toString(), generation);
                                            return;
                                        }
                                        // 首轮就失败：移除空 assistant 占位，避免空气泡落盘/进入后续请求体
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
     * 工具轮次中途失败：保留这条 AI 消息（含思考与工具卡片），挂一张带
     * 「从出错处重试」的错误卡片。工具历史一并落盘，重启后仍可继续。
     */
    private void markAssistantFailed(final int aiIndex, final String detail, final String traceText,
            final String toolHistoryJson, final String apiText, final int generation) {
        if (aiIndex < 0 || aiIndex >= messages.size()) return;
        Message am = messages.get(aiIndex);
        if (am == null || !am.isAssistant()) return;
        am.content = traceText == null ? "" : traceText;
        am.errorDetail = detail == null ? "" : detail;
        am.apiText = apiText == null ? "" : apiText;
        am.toolHistory = toolHistoryJson == null ? "" : toolHistoryJson;
        am.sendFailed = true;
        LogUtil.i(TAG, "markAssistantFailed: idx=%d traceLen=%d tools=%d",
                aiIndex, am.content.length(), am.toolHistory.length());

        final String content = am.content;
        new Thread(new Runnable() {
            public void run() {
                final String html = MessageHtmlRenderer.contentToHtml(content, MainActivity.this);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (requestGeneration.get() != generation) return;
                        if (aiIndex >= messages.size()) return;
                        webViewEvalJs("finalizeLast(" + aiIndex + ")");
                        webViewEvalJs("updateMsgAt(" + aiIndex + ",'" + jsEscape(html) + "')");
                        String card = MessageHtmlRenderer.renderAssistantErrorCard(messages.get(aiIndex), aiIndex);
                        webViewEvalJs("markAssistantFailed(" + aiIndex + ",'" + jsEscape(card) + "')");
                        conversationManager.saveCurrentConversation();
                    }
                });
            }
        }) {{ setName("aichat-render-" + System.currentTimeMillis()); }}.start();
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
        // 两个守卫各管一件事：
        //   1) 渲染序号——迟到的渲染不能盖掉更新的那次（状态行与工具卡片会交替下发）；
        //   2) 长度单调——trace 只会变长，比当前短的快照一定是旧轮次的。
        // 旧实现只有 (2)，但状态行（status）天然比已完成正文短，「正在搜索…」
        // 这类提示因此从来没显示过。
        final int mySeq = renderSeq.incrementAndGet();
        final boolean transientStatus = status != null;
        new Thread(new Runnable() {
            public void run() {
                final String html = MessageHtmlRenderer.contentToHtml(snapshot, MainActivity.this)
                        + (status == null ? "" : statusHtml(status));
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (requestGeneration.get() != generation) return;
                        if (renderSeq.get() != mySeq) {
                            LogUtil.d(TAG, "trace render superseded: seq %d/%d", mySeq, renderSeq.get());
                            return;
                        }
                        if (!transientStatus && aiIndex >= 0 && aiIndex < messages.size()
                                && messages.get(aiIndex).isAssistant()) {
                            String cur = messages.get(aiIndex).content;
                            if (cur != null && cur.length() > snapshot.length()) {
                                LogUtil.d(TAG, "stale trace render dropped: cur=%d, snapshot=%d",
                                        cur.length(), snapshot.length());
                                return;
                            }
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
                if (queue != null) queue.drain();
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

        /** 工具轮次中途失败的 AI 消息：从出错处继续，不重发整条用户消息。 */
        @JavascriptInterface
        public void retryFailedAssistant(final String idx) {
            LogUtil.d(TAG, "JsBridge.retryFailedAssistant: idx=%s", idx);
            handler.post(new Runnable() {
                public void run() {
                    try {
                        int pos = Integer.parseInt(idx);
                        if (pos < 0 || pos >= messages.size()) return;
                        Message m = messages.get(pos);
                        if (m == null || !m.isAssistant() || !m.sendFailed) {
                            LogUtil.d(TAG, "retryFailedAssistant ignored: pos=%d not a failed AI msg", pos);
                            return;
                        }
                        if (isRequestInProgress.get()) {
                            Toast.makeText(MainActivity.this, "当前回复仍在进行，请先停止", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        resumeAssistantRequest(pos);
                    } catch (Exception e) {
                        LogUtil.w(TAG, "retryFailedAssistant failed: %s", e.getMessage());
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
                        viewer.showImage(Integer.parseInt(indexStr), imagesJson);
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
                    viewer.showTable(tableHtml);
                }
            });
        }

        @JavascriptInterface
        public void saveCode(final String lang, final String code) {
            LogUtil.d(TAG, "JsBridge.saveCode: lang=%s len=%s",
                    lang, code == null ? "null" : code.length());
            handler.post(new Runnable() {
                public void run() {
                    ExportHelper.saveCode(MainActivity.this, lang, code);
                }
            });
        }

        @JavascriptInterface
        public void previewCode(final String lang, final String code) {
            handler.post(new Runnable() {
                public void run() {
                    viewer.showCode(lang, code);
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
        if (msg.isAssistant()) {
            // 取完整正文：content 里混着 [thinking]/[tool] 展示块，用普通字符串剥离
            // 会在遇到未闭合标记时把后半段整段吃掉（长按菜单只剩第一段的根因）。
            return MessageHtmlRenderer.plainTextOf(msg.content);
        }
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
                                if (msg.isAssistant()) {
                                    // 手改过正文后，旧的工具历史/纯文本不再对应当前内容
                                    msg.apiText = newContent;
                                    msg.toolHistory = "";
                                    msg.sendFailed = false;
                                    msg.errorDetail = "";
                                }
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
     * 工具轮次中途失败后，从出错处继续：把已经完成的工具调用/结果重新拼进请求，
     * 只重发最后一次请求。已执行过的工具不会重跑，用户也不必重发整条消息。
     */
    private void resumeAssistantRequest(final int pos) {
        if (pos < 0 || pos >= messages.size()) {
            LogUtil.w(TAG, "resumeAssistantRequest: pos=%d out of range (size=%d)", pos, messages.size());
            return;
        }
        final Message am = messages.get(pos);
        if (am == null || !am.isAssistant()) {
            LogUtil.w(TAG, "resumeAssistantRequest: pos=%d is not an assistant message", pos);
            return;
        }
        loadSystemPrompt();
        int selPos = modelSpinner.getSelectedItemPosition();
        if (selPos < 0 || availableModels == null || selPos >= availableModels.size()) {
            Toast.makeText(this, "未选择模型", Toast.LENGTH_SHORT).show();
            return;
        }
        ProviderInfo provider = configManager.getProvider(availableModels.get(selPos).provider);
        if (provider == null) {
            Toast.makeText(this, "未选择模型", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            boolean webSearchEnabled = SettingsActivity.isWebSearchEnabled(this);
            String sysPrompt = WebSearchTool.appendToSystemPrompt(systemPrompt, webSearchEnabled);
            List<Message> prefix = new ArrayList<Message>(messages.subList(0, pos));
            JSONArray conversation = ChatHistory.build(prefix, null, sysPrompt);

            int rounds = 0;
            if (am.toolHistory != null && am.toolHistory.length() > 2) {
                JSONArray hist = new JSONArray(am.toolHistory);
                for (int i = 0; i < hist.length(); i++) {
                    JSONObject o = hist.optJSONObject(i);
                    if (o == null) continue;
                    if (!"tool".equals(o.optString("role", ""))) rounds++;
                    conversation.put(o);
                }
            }
            if (rounds == 0) {
                // 没有可续跑的上下文：退化成普通的整条重试
                LogUtil.i(TAG, "resumeAssistantRequest: no tool history, fallback to resend");
                resendUserAt(lastUserIndexBefore(pos));
                return;
            }

            List<String[]> sources = new ArrayList<String[]>();
            if (am.sources != null) {
                for (Message.Source s : am.sources) {
                    if (s == null || s.url == null || s.url.length() == 0) continue;
                    sources.add(new String[]{s.title == null ? "" : s.title, s.url});
                }
            }

            am.sendFailed = false;
            am.errorDetail = "";
            conversationManager.saveCurrentConversation();

            String thinkingLevel = configManager.getThinkingLevel();
            if (!configManager.isThinkingEnabled()) thinkingLevel = "off";
            int gen = requestGeneration.incrementAndGet();
            LogUtil.i(TAG, "resumeAssistantRequest: pos=%d rounds=%d traceLen=%d",
                    pos, rounds, am.content == null ? 0 : am.content.length());
            sendStreamingRequest(provider, thinkingLevel, gen,
                    new ResumeState(pos, rounds, am.content == null ? "" : am.content,
                            conversation, sources, am.apiText == null ? "" : am.apiText,
                            am.toolHistory == null ? "" : am.toolHistory));
        } catch (Exception e) {
            LogUtil.e(TAG, "resumeAssistantRequest failed: %s", e.getMessage(), e);
            Toast.makeText(this, "无法从此处继续，请重试整条消息", Toast.LENGTH_SHORT).show();
        }
    }

    /** pos 之前最近一条用户消息的下标，没有则返回 -1。 */
    private int lastUserIndexBefore(int pos) {
        for (int i = Math.min(pos, messages.size()) - 1; i >= 0; i--) {
            if (messages.get(i).isUser()) return i;
        }
        return -1;
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
                    // 纯文本/工具历史也要一起带走，否则分支里的 AI 回复又退回「只有正文」
                    copy.apiText = src.apiText;
                    copy.toolHistory = src.toolHistory;
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
                // 先切 current 再保存：saveCurrentConversation 写的就是 branch，
                // 省掉一次「先存旧会话、再直写分支」的重复序列化
                conversationManager.setCurrentConversation(branch);
                messages = branch.messages;
                conversationManager.saveCurrentConversation();
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
