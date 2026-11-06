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
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
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
    /** 按 provider 记住「不支持 stream_options」，值是 boolean，缺省 false。 */
    private static final String KEY_USAGE_UNSUPPORTED_PREFIX = "usage_unsupported_";
    /** 首次启动说明与免责声明确认过没有。 */
    private static final String KEY_FIRST_LAUNCH_DONE = "first_launch_done";
    private static final int REQUEST_CONVERSATION_MANAGER = 1;
    private static final int REQUEST_SCAN = 2;
    private static final int REQUEST_PICK_TEXT = 3;
    private static final int REQUEST_FULLSCREEN = 4;
    /** 附件菜单项 id（单项目前用常量，后续加项改成 menu 资源）。 */
    private static final int MENU_ATTACH_TEXT = 1;
    /** 导入文本上限：超过直接报错，不截断——半截代码更难排查。 */
    private static final int MAX_IMPORT_BYTES = 512 * 1024;
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
    private ImageButton sendButton;
    private ImageButton plusButton;
    private ImageButton expandButton;
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
    // 已经推到 WebView 的 trace（base 层）文本。流式期间状态行与工具卡片会交替
    // 下发，内容没变就只更新状态行，别再整段重渲染——那是低端机上最明显的闪烁源。
    private volatile String lastStreamTrace = "";
    // 当前气泡在 DOM 上实际显示的完整正文（base + tail）。收尾时用它判断
    // 「界面已经是最终内容」——是就只把 tail 定型进 base，省掉一次整段重渲染。
    private volatile String lastPushedContent = "";
    // volatile：请求线程写、UI 线程读，打断时才能可靠 disconnect
    private volatile HttpURLConnection currentConnection;
    /** 当前请求的 provider 名：端点拒绝 stream_options 时按它记住降级结果。 */
    private volatile String activeProviderName = "";
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
    /** 上次手动选择的模型，Activity 重建后用它恢复，而不是回落到默认模型。 */
    private static final String KEY_SELECTED_MODEL = "selected_model";

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
        // 首启先让用户读完说明与免责声明，再处理外部传入的 prompt。
        // 否则从别的应用「发送到 AiChat」时，消息会在引导弹窗背后被发出去。
        if (prefs.getBoolean(KEY_FIRST_LAUNCH_DONE, false)) {
            handlePromptIntent(getIntent());
        } else {
            showFirstLaunchDialog();
        }
        LogUtil.i(TAG, "========== onCreate done ==========");
    }

    /**
     * 首次启动的使用说明与免责声明。不可取消：必须明确选择「同意」或「退出」，
     * 避免用户随手点外部区域关掉后，后续再也没机会看到这段声明。
     */
    private void showFirstLaunchDialog() {
        String msg = "使用说明\n"
                + "1. 下方输入问题，点右侧纸飞机发送；生成中点方块可停止。\n"
                + "2. 输入框有内容时，右侧会出现全屏输入按钮。\n"
                + "3. 长按任意消息可复制、重试、修改、回溯或创建分支。\n"
                + "4. 设置页可配置模型、联网搜索、429 重试策略与网络请求权限。\n"
                + "5. 联网搜索与模型调用需要自行填写 API Key。\n\n"
                + "免责声明\n"
                + "· 模型输出由第三方服务生成，仅供参考，请自行核实。\n"
                + "· 联网搜索内容版权归原作者所有。\n"
                + "· API Key 由你自行提供，相应费用与风险由你承担。\n"
                + "· 对话内容与密钥只保存在本机，不会上传到任何自有服务器。";
        new AlertDialog.Builder(this)
                .setTitle("欢迎使用 AiChat")
                .setMessage(msg)
                .setCancelable(false)
                .setPositiveButton("同意并继续", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        prefs.edit().putBoolean(KEY_FIRST_LAUNCH_DONE, true).apply();
                        LogUtil.i(TAG, "first launch accepted");
                        handlePromptIntent(getIntent());
                    }
                })
                .setNegativeButton("退出", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        LogUtil.i(TAG, "first launch declined, finishing");
                        finish();
                    }
                })
                .show();
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

    /**
     * 视图装配。按「管什么」拆成几个小方法：想改暂停/续跑的逻辑就只看
     * {@link #bindSendButton()}，想改 WebView 能力就只看 {@link #bindWebView()}，
     * 不必在 170 行里翻。
     */
    private void initViews() {
        LogUtil.d(TAG, "initViews");
        viewer = new ViewerController(this);
        bindViews();
        bindWebView();
        bindQueue();
        bindTopBar();
        bindSendButton();
        bindScrollButton();
        bindThinkingSpinner();
        refreshModelSpinner();
    }

    private void bindViews() {
        inputEditText = (EditText) findViewById(R.id.input_edit_text);
        sendButton = (ImageButton) findViewById(R.id.send_button);
        plusButton = (ImageButton) findViewById(R.id.plus_button);
        expandButton = (ImageButton) findViewById(R.id.expand_button);
        modelSpinner = (Spinner) findViewById(R.id.model_spinner);
        thinkingSpinner = (Spinner) findViewById(R.id.thinking_spinner);
    }

    private void bindWebView() {
        conversationWebView = (WebView) findViewById(R.id.message_webview);
        WebSettings settings = conversationWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDefaultTextEncodingName("UTF-8");
        settings.setBuiltInZoomControls(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setAllowFileAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        conversationWebView.addJavascriptInterface(new JsBridge(), "Android");
        conversationWebView.setWebViewClient(new WebViewClient() {
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // 链接一律不跳转：聊天页里的外链由消息卡片自己处理
                return true;
            }
        });
    }

    private void bindQueue() {
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
    }

    /** 顶栏：新建 / 历史（长按进工具）/ 设置 / 横竖屏 / 扫描。 */
    private void bindTopBar() {
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
                if (orient == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                    setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                } else {
                    setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                }
            }
        });
        findViewById(R.id.scan_button).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                LogUtil.d(TAG, "click: scan -> launchScan");
                launchScan();
            }
        });
    }

    /**
     * 发送键的四种状态由 {@link #refreshSendButton()} 统一决定：
     * 空闲+空输入=禁用发送，空闲+有输入=发送，生成中+空输入=停止，生成中+有输入=发送（入队）。
     *
     * <p>长按打断保留：输入框有内容时按钮回到「发送」，想立刻停下只能靠长按，
     * 否则会把「停止」压在一个同时承担发送的按钮上，误触风险太高。
     * 长按后这次抬手的 click 由 {@code sendButtonLongPressed} 吞掉。
     */
    private void bindSendButton() {
        sendButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (sendButtonLongPressed) {
                    sendButtonLongPressed = false;
                    LogUtil.d(TAG, "click: send suppressed after long-press interrupt");
                    return;
                }
                if (isStopState()) {
                    LogUtil.d(TAG, "click: stop");
                    interruptRequest();
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
                        return false;
                }
                return false;
            }
        });

        // 输入框是按钮状态与全屏入口的唯一真相来源
        inputEditText.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { }
            public void afterTextChanged(Editable s) {
                refreshSendButton();
            }
        });

        plusButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showAttachMenu();
            }
        });
        expandButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                openFullscreenInput();
            }
        });

        refreshSendButton();
    }

    /** 生成中且输入框为空：此时按钮是「停止」。 */
    private boolean isStopState() {
        return isRequestInProgress.get() && !hasInputText();
    }

    private boolean hasInputText() {
        return inputEditText != null
                && inputEditText.getText().toString().trim().length() > 0;
    }

    /**
     * 按钮外观的唯一写入口。被 TextWatcher 和请求状态变化调用，必须走 UI 线程。
     * 全屏按钮用 INVISIBLE 而不是 GONE：显隐时占位不变，发送键不会跳。
     */
    private void refreshSendButton() {
        if (sendButton == null || inputEditText == null) return;
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            handler.post(new Runnable() {
                public void run() {
                    refreshSendButton();
                }
            });
            return;
        }
        boolean hasText = hasInputText();
        boolean stopping = isRequestInProgress.get() && !hasText;
        sendButton.setImageResource(stopping ? R.drawable.ic_stop : R.drawable.ic_send);
        sendButton.setContentDescription(getString(
                stopping ? R.string.stop_generating : R.string.send));
        // 空闲且一个字都没输入时禁用，避免「点了没反应」的困惑
        sendButton.setEnabled(stopping || hasText);
        if (expandButton != null) {
            expandButton.setVisibility(hasText ? View.VISIBLE : View.INVISIBLE);
        }
    }

    /**
     * 请求状态的唯一写入口。直接改 {@link #isRequestInProgress} 会让按钮停留
     * 在上一个状态（例如停止后仍显示方块），所以统一走这里带上 UI 刷新。
     */
    private void setRequestInProgress(boolean value) {
        isRequestInProgress.set(value);
        refreshSendButton();
    }

    private void showAttachMenu() {
        PopupMenu menu = new PopupMenu(this, plusButton);
        menu.getMenu().add(0, MENU_ATTACH_TEXT, 0, R.string.upload_text_file);
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            public boolean onMenuItemClick(MenuItem item) {
                if (item.getItemId() == MENU_ATTACH_TEXT) {
                    pickTextFile();
                    return true;
                }
                return false;
            }
        });
        menu.show();
    }

    /** API 18 没有 Storage Access Framework，只能用 ACTION_GET_CONTENT。 */
    private void pickTextFile() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("text/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(intent, getString(R.string.upload_text_file)),
                    REQUEST_PICK_TEXT);
        } catch (Exception e) {
            LogUtil.w(TAG, "pickTextFile failed: %s", e.getMessage());
            Toast.makeText(this, "没有可用的文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    private void openFullscreenInput() {
        Intent intent = new Intent(this, FullscreenInputActivity.class);
        intent.putExtra(FullscreenInputActivity.EXTRA_TEXT, inputEditText.getText().toString());
        startActivityForResult(intent, REQUEST_FULLSCREEN);
    }

    /** 后台读文件，回 UI 线程插到光标处；不覆盖用户已经打的内容。 */
    private void importTextFile(final Uri uri) {
        new Thread(new Runnable() {
            public void run() {
                String text = null;
                String err = null;
                try {
                    text = readTextLimited(uri, MAX_IMPORT_BYTES);
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final String loaded = text;
                final String error = err;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (loaded == null) {
                            LogUtil.w(TAG, "importTextFile failed: %s", error);
                            Toast.makeText(MainActivity.this,
                                    "读取失败: " + (error == null ? "未知错误" : error),
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        int at = inputEditText.getSelectionStart();
                        if (at < 0) at = inputEditText.getText().length();
                        inputEditText.getText().insert(at, loaded);
                        Toast.makeText(MainActivity.this,
                                "已插入 " + loaded.length() + " 字", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }, "aichat-import-text").start();
    }

    /**
     * 读取文本文件，超过上限直接失败而不是截断——截一半的代码贴进对话比报错更难排查。
     * UTF-8 解出来要是出现替换字符（U+FFFD），说明大概率是 GBK，再试一次。
     */
    private String readTextLimited(Uri uri, int maxBytes) throws Exception {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) throw new Exception("无法打开文件");
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > maxBytes) {
                    throw new Exception("文件超过 " + (maxBytes / 1024) + " KB");
                }
                bos.write(buf, 0, n);
            }
            byte[] data = bos.toByteArray();
            String utf8 = new String(data, "UTF-8");
            if (utf8.indexOf('\uFFFD') < 0) return utf8;
            String gbk = new String(data, "GBK");
            LogUtil.d(TAG, "readTextLimited: utf8 had replacement chars, fell back to GBK");
            return gbk;
        } finally {
            try { in.close(); } catch (Exception ignored) { }
        }
    }

    /** 回到底部：点击滚到底，长按 600ms 清空输入框。 */
    private void bindScrollButton() {
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
                            conversationWebView.loadUrl("javascript:smartScrollToBottom(true)");
                        }
                        return false;
                    case MotionEvent.ACTION_CANCEL:
                        handler.removeCallbacks(longPressRunnable);
                        return false;
                }
                return false;
            }
        });
    }

    private void bindThinkingSpinner() {
        String[] thinkingLevels = {"关闭", "低", "中", "高"};
        ArrayAdapter<String> thinkingAdapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, thinkingLevels);
        thinkingAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        thinkingSpinner.setAdapter(thinkingAdapter);

        String level = configManager.getThinkingLevel();
        int levelPos = 2;
        if ("off".equals(level)) levelPos = 0;
        else if ("low".equals(level)) levelPos = 1;
        else if ("high".equals(level)) levelPos = 3;
        // 先复位再去 setSelection：否则 setSelection 触发的回调会把它当成用户操作写盘
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

            public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    private void refreshModelSpinner() {
        LogUtil.d(TAG, "refreshModelSpinner");
        // 内存字段在 Activity 重建 / 进程被回收后为空，先读持久化值。
        // 老设备关屏再亮屏很容易触发重建，只靠字段就会「切回默认模型」。
        if (currentModel == null && prefs != null) {
            currentModel = prefs.getString(KEY_SELECTED_MODEL, null);
            if (currentModel != null) {
                LogUtil.d(TAG, "restored selected model from prefs: %s", currentModel);
            }
        }
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
        // 先判 null 再取 size()：反过来会在 availableModels 尚未赋值时直接 NPE
        if (availableModels == null || pos < 0 || pos >= availableModels.size()) {
            LogUtil.w(TAG, "selectModel: invalid pos=%d (size=%d)", pos,
                    availableModels == null ? -1 : availableModels.size());
            return;
        }
        ModelInfo model = availableModels.get(pos);
        ProviderInfo provider = configManager.getProvider(model.provider);
        if (provider != null) {
            currentModel = model.name;
            if (prefs != null) {
                prefs.edit().putString(KEY_SELECTED_MODEL, currentModel).apply();
            }
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
        // 打断的 UI 清理是异步的（还要校验会话没被换掉），而下面这行马上要保存旧会话：
        // 先把末尾的空 AI 占位删掉，否则旧会话文件里会留下一个永远没有内容的空气泡
        dropTrailingEmptyAssistant();
        loadSystemPrompt();
        Conversation conv = conversationManager.createNewConversation();
        conv.systemPrompt = systemPrompt;
        conv.model = currentModel;
        messages = conv.messages;
        LogUtil.i(TAG, "new conversation created: id=%s model=%s", conv.id, conv.model);
        refreshWebView();
        Toast.makeText(this, "已创建新对话", Toast.LENGTH_SHORT).show();
    }

    /**
     * 丢掉末尾连续的「空 AI 占位」。
     *
     * <p>请求刚发出、还没收到第一个字时，末尾会有一条 content 为空的 assistant。
     * 正常收尾由 settleStream / interruptRequest 负责清掉，但那两条都是异步的，
     * 且会因为「会话已经切走」被守卫跳过。新建/切换会话前必须同步清一次，
     * 否则空占位会随旧会话落盘，下次打开是个点不开的空气泡。
     */
    private void dropTrailingEmptyAssistant() {
        if (messages == null) return;
        boolean removed = false;
        while (!messages.isEmpty()) {
            Message last = messages.get(messages.size() - 1);
            if (last != null && last.isAssistant()
                    && (last.content == null || last.content.length() == 0)) {
                messages.remove(messages.size() - 1);
                removed = true;
            } else {
                break;
            }
        }
        if (removed) {
            LogUtil.d(TAG, "dropTrailingEmptyAssistant: removed, size=%d", messages.size());
            removeUnfinalizedAiDiv();
        }
    }

    private void interruptRequest() {
        if (!isRequestInProgress.get()) return;
        requestGeneration.incrementAndGet();
        LogUtil.i(TAG, "interruptRequest: disconnecting current connection");
        setRequestInProgress(false);
        if (currentConnection != null) {
            try { currentConnection.disconnect(); } catch (Exception ignored) {}
            currentConnection = null;
        }
        // Safety: skip UI cleanup if activity is finishing
        if (isFinishing()) return;
        // 记下发起时的会话。清理动作（标记「已打断」、挂重试卡片、落盘）都是针对
        // 「当前会话」的，而调用方往往紧接着就切/新建会话；不校验就会把旧会话的
        // 消息标记写到新会话的文件里。
        final Conversation convAtInterrupt = conversationManager.getCurrentConversation();
        final int msgCountAtInterrupt = messages.size();
        runOnUiThread(new Runnable() {
            public void run() {
                if (isFinishing()) return;
                if (conversationManager.getCurrentConversation() != convAtInterrupt) {
                    LogUtil.d(TAG, "interrupt cleanup skipped: conversation changed");
                    return;
                }
                if (queue != null) queue.drain();
                if (messages.size() != msgCountAtInterrupt) return;
                if (!messages.isEmpty()) {
                    Message lastMsg = messages.get(messages.size() - 1);
                    if (lastMsg.isAssistant()) {
                        final int lastIdx = messages.size() - 1;
                        String content = lastMsg.content;
                        if (content == null || content.isEmpty()) {
                            LogUtil.d(TAG, "interrupt: removing empty AI placeholder, size=%d", messages.size());
                            messages.remove(messages.size() - 1);
                            removeDomRange(messages.size());
                            // finalizeLast 被代数守卫跳过时占位 div 的 data-idx 仍为 -1，
                            // removeRangeFrom(idx>=0) 删不掉它，这里补删未定稿的占位 div
                            removeUnfinalizedAiDiv();
                            // 一个字都没生成：重试入口挂到用户消息上，否则没有任何可点的东西
                            int userIdx = lastUserIndexBefore(messages.size());
                            if (userIdx >= 0) {
                                Message um = messages.get(userIdx);
                                um.sendFailed = true;
                                um.errorDetail = "本次回复已被手动中断。";
                                String card = MessageHtmlRenderer.renderErrorCard(um, userIdx);
                                webViewEvalJs("markUserFailed(" + userIdx + ",'" + jsEscape(card) + "')");
                                conversationManager.saveCurrentConversation();
                            }
                        } else {
                            // 思考阶段被打断时要补 [/thinking] 收尾，否则未闭合内容
                            // 会被 removeThinkingContent 整段剥离，模型像没收到过该回复
                            if (content.indexOf("[thinking]") >= 0 && content.indexOf("[/thinking]") < 0) {
                                content = content + "[/thinking]";
                            }
                            lastMsg.content = content + " (已打断)";
                            // 中断也是一种「未完成」：给这条消息挂上重试入口，
                            // 否则用户只能重新打字，已生成的内容无法续上
                            lastMsg.sendFailed = true;
                            lastMsg.errorDetail = "本次回复已被手动中断。";
                            LogUtil.d(TAG, "interrupt: marking AI msg as interrupted, len=%d", content.length());
                            updateAiContent(lastMsg.content);
                            attachInterruptRetry(lastIdx);
                            conversationManager.saveCurrentConversation();
                        }
                    } else {
                        LogUtil.d(TAG, "interrupt: last message is not assistant, nothing to mark");
                    }
                }
            }
        });
    }

    /**
     * 中断后给 AI 消息挂错误卡片。不走 {@code markAssistantFailed}：那个方法有
     * 请求代数守卫，而中断本身已经把代数自增了。
     */
    private void attachInterruptRetry(final int aiIndex) {
        if (aiIndex < 0 || aiIndex >= messages.size()) return;
        Message am = messages.get(aiIndex);
        if (am == null || !am.isAssistant()) return;
        webViewEvalJs("finalizeLast(" + aiIndex + ")");
        String card = MessageHtmlRenderer.renderAssistantErrorCard(am, aiIndex);
        if (card.length() > 0) {
            webViewEvalJs("markAssistantFailed(" + aiIndex + ",'" + jsEscape(card) + "')");
        }
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
                dropTrailingEmptyAssistant();
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
        } else if (requestCode == REQUEST_PICK_TEXT) {
            Uri picked = data == null ? null : data.getData();
            if (resultCode == RESULT_OK && picked != null) {
                LogUtil.i(TAG, "picked text file: %s", picked);
                importTextFile(picked);
            } else {
                LogUtil.d(TAG, "pick text cancelled or empty");
            }
        } else if (requestCode == REQUEST_FULLSCREEN) {
            if (resultCode == RESULT_OK && data != null) {
                String text = data.getStringExtra(FullscreenInputActivity.EXTRA_RESULT);
                if (text != null) {
                    LogUtil.i(TAG, "fullscreen input returned: len=%d", text.length());
                    inputEditText.setText(text);
                    inputEditText.setSelection(text.length());
                }
            }
        } else {
            LogUtil.d(TAG, "onActivityResult unhandled: requestCode=%d resultCode=%d", requestCode, resultCode);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // 首启引导还没确认时不能直接发：否则从别的应用「发送到 AiChat」，
        // 消息会在引导弹窗背后被发出去。等用户点「同意」时再统一处理 getIntent()。
        if (!prefs.getBoolean(KEY_FIRST_LAUNCH_DONE, false)) {
            LogUtil.d(TAG, "onNewIntent deferred: first launch not confirmed");
            return;
        }
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
        // 这里不再 flushSaves：它是对主线程的阻塞等待（最长数秒），而 onPause 是
        // 用户切走的第一帧、也是 ANR 的高危窗口。保存在单线程队列里按序执行，
        // 进程真要退出还有 onDestroy 兜底，没必要在这个位置赌主线程。
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
     * 发起一次回复。
     *
     * <p>编排全部在 {@link ChatStreamRunner}：这个方法只做四件事——读设置、
     * 组一份不可变的 {@link StreamJob}、起请求线程、把 UI 与会话能力交出去。
     * 想知道「失败怎么回滚」「什么时候重试」不用再读 500 行嵌套，
     * 去 ChatStreamRunner 里按方法名找即可。
     */
    private void sendStreamingRequest(ProviderInfo provider, String thinkingLevel, int generation) {
        sendStreamingRequest(provider, thinkingLevel, generation, null);
    }

    /** 该 provider 是否已经明确拒绝过 stream_options。 */
    private boolean isUsageUnsupported(String providerName) {
        if (providerName == null || providerName.length() == 0) return true;
        return prefs.getBoolean(KEY_USAGE_UNSUPPORTED_PREFIX + providerName, false);
    }

    private void sendStreamingRequest(ProviderInfo provider, String thinkingLevel,
            int generation, StreamJob.Resume resume) {
        final boolean webSearchEnabled = SettingsActivity.isWebSearchEnabled(this);
        final boolean useTavily = SettingsActivity.isTavilySearch(this);
        final String tavilyKey = SettingsActivity.getTavilyKey(this);
        // 搜索后端在 UI 线程先定死：Tavily 走自建网关，不需要 DeepSeek 提供商
        final ProviderInfo searchProvider = (webSearchEnabled && !useTavily)
                ? WebSearchTool.resolveProvider(configManager, provider) : null;
        final boolean searchReady = webSearchEnabled
                && (useTavily ? TavilyTool.hasApiKey(tavilyKey) : searchProvider != null);
        final String effectiveSystemPrompt =
                WebSearchTool.appendToSystemPrompt(systemPrompt, webSearchEnabled);
        final boolean streamEnabled = SettingsActivity.isStreamEnabled(this);
        // 端点上次拒过 stream_options 就不再发，避免每次对话白吃一个 400
        final boolean includeUsage = streamEnabled && !isUsageUnsupported(provider.name);
        activeProviderName = provider.name;

        Message aiMsg;
        int aiIndex;
        if (resume != null) {
            // 续跑：复用已存在的那条 AI 消息，不新增占位、不重复插 DOM
            aiMsg = messages.get(resume.aiIndex);
            aiIndex = resume.aiIndex;
            LogUtil.i(TAG, "resume request: aiIndex=%d round=%d traceLen=%d",
                    aiIndex, resume.round, resume.trace == null ? 0 : resume.trace.length());
        } else {
            aiMsg = new Message(Message.ROLE_ASSISTANT, "");
            messages.add(aiMsg);
            aiIndex = messages.size() - 1;
        }
        // 在 UI 线程做一次快照：请求线程遍历时 UI 线程可能结构性修改 messages
        List<Message> snapshot = new ArrayList<Message>(messages);
        String question = ChatHistory.lastUserContent(snapshot);

        final int placeholderIndex = aiIndex;
        final boolean isResume = resume != null;
        // base 层的内容基准：新请求从空白开始，续跑从上次的 trace 开始。
        // 不清会把上一条消息的 trace 当成「已推送」，导致工具卡片永远不出现。
        lastStreamTrace = isResume && resume.trace != null ? resume.trace : "";
        // DOM 内容基准同理：续跑时界面上已经有上次的正文，新请求从空白开始
        lastPushedContent = isResume && resume.trace != null ? resume.trace : "";
        runOnUiThread(new Runnable() {
            public void run() {
                webViewEvalJs(isResume
                        ? "clearAssistantFailed(" + placeholderIndex + ")"
                        : "appendAiDiv()");
            }
        });

        setRequestInProgress(true);
        LogUtil.i(TAG, ">>> request START: gen=%d url=%s%s model=%s thinking=%s webSearch=%s backend=%s",
                generation, provider.apiUrl, provider.chatPath, currentModel, thinkingLevel,
                webSearchEnabled, useTavily ? "tavily" : "deepseek");

        StreamJob job = StreamJob.builder()
                .provider(provider)
                .modelName(currentModel)
                .thinkingLevel(thinkingLevel)
                .generation(generation)
                .aiIndex(aiIndex)
                .aiMessage(aiMsg)
                .snapshot(snapshot)
                .question(question)
                .systemPrompt(systemPrompt)
                .effectiveSystemPrompt(effectiveSystemPrompt)
                .providerName(provider.name)
                .chatEndpoint(provider.apiUrl + provider.chatPath)
                .streamEnabled(streamEnabled)
                .includeUsage(includeUsage)
                .realtimeRender(SettingsActivity.isRealtimeRenderEnabled(this))
                .webSearchEnabled(webSearchEnabled)
                .httpToolEnabled(SettingsActivity.isHttpToolEnabled(this))
                .useTavily(useTavily)
                .searchReady(searchReady)
                .maxCallsPerRound(SettingsActivity.getWebSearchMaxCallsPerRound(this))
                .searchProvider(searchProvider)
                .tavilyKey(tavilyKey)
                .retryPolicy(RetryPolicy.from(this))
                .resume(resume)
                .build();

        final ChatStreamRunner runner =
                new ChatStreamRunner(job, new Channel(), new GenerationSession(generation));
        currentRequestThread = new Thread(new Runnable() {
            public void run() {
                runner.execute();
            }
        });
        currentRequestThread.setName("aichat-stream-" + System.currentTimeMillis());
        currentRequestThread.start();
        LogUtil.d(TAG, "request thread started: %s", currentRequestThread.getName());
    }

    /** 把 Activity 的视图能力交给 {@link ChatStreamRunner}，每个方法都是一两行转发。 */
    private final class Channel implements ChatStreamRunner.Ui {
        public void runOnUi(Runnable task) {
            runOnUiThread(task);
        }

        public void evalJsHtml(String fn, String html) {
            webViewEvalJs(fn + "('" + jsEscape(html) + "')");
        }

        public void evalJs(String code) {
            webViewEvalJs(code);
        }

        public void noteBaseRendered(String base) {
            lastStreamTrace = base == null ? "" : base;
        }

        public String baseRendered() {
            return lastStreamTrace == null ? "" : lastStreamTrace;
        }

        public void onTraceTrimmed() {
            // 裁剪让 trace 变短，旧的「已推送全文」不再成立。清空基准，
            // 下一次渲染会走整段 streamBase 重建，DOM 与文本重新对齐。
            lastStreamTrace = "";
            renderSeq.incrementAndGet();
            LogUtil.d(TAG, "trace trimmed: render base reset");
        }

        public void onUsageUnsupported() {
            String name = activeProviderName;
            if (name == null || name.length() == 0) return;
            prefs.edit().putBoolean(KEY_USAGE_UNSUPPORTED_PREFIX + name, true).apply();
            LogUtil.w(TAG, "provider '%s' rejected stream_options, usage off for it", name);
        }

        public boolean isCurrent(int generation) {
            return requestGeneration.get() == generation;
        }

        public int nextSeq() {
            return renderSeq.incrementAndGet();
        }

        public int currentSeq() {
            return renderSeq.get();
        }

        public String renderHtml(String markdown) {
            return MessageHtmlRenderer.contentToHtml(markdown, MainActivity.this);
        }

        public void setContent(int aiIndex, String content) {
            if (aiIndex >= 0 && aiIndex < messages.size()) {
                messages.get(aiIndex).content = content;
            }
            // 只有真正写进 DOM 的这一次才更新：被序号守卫挡掉的 tick 不会走到这里，
            // 否则会把「没显示出来的内容」误记成已显示。
            lastPushedContent = content == null ? "" : content;
        }

        public void toast(final String msg) {
            runOnUiThread(new Runnable() {
                public void run() {
                    Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
                }
            });
        }

        public void showStatus(String text, int generation, String trace, int aiIndex) {
            showSearchStatus(text, generation, trace, aiIndex);
        }

        public void renderTrace(String trace, int generation, String status, int aiIndex) {
            renderTraceAsync(trace, generation, status, aiIndex);
        }
    }

    /** 请求代数与连接的收口：旧线程只能看到自己的代数，写不进新请求。 */
    private final class GenerationSession implements ChatStreamRunner.Session {
        private final int generation;

        GenerationSession(int generation) {
            this.generation = generation;
        }

        public boolean isCurrent(int gen) {
            return requestGeneration.get() == gen;
        }

        public boolean isAlive(int gen) {
            return isRequestInProgress.get() && requestGeneration.get() == gen;
        }

        public void setConnection(HttpURLConnection conn) {
            if (requestGeneration.get() == generation) currentConnection = conn;
        }

        public void onSettled(ChatStreamRunner.Settled settled) {
            settleStream(settled);
        }
    }

    /**
     * 请求收尾：成功则提交正文与来源，失败则挂错误卡片或回滚空占位。
     * 渲染离开主线程，落盘与放行队列的顺序不能调换（见方法内注释）。
     */
    private void settleStream(final ChatStreamRunner.Settled settled) {
        final int finalIdx = settled.aiIndex;
        final boolean failed = settled.failed;
        renderSeq.incrementAndGet(); // 作废进行中的流式渲染
        new Thread(new Runnable() {
            public void run() {
                try {
                    if (failed) {
                        // 工具结果已经回传给模型的轮次：保留这条 AI 消息和已展示的
                        // 思考/工具卡片，挂「从出错处重试」，不再重发整条用户消息。
                        // 400/422 说明工具格式被拒，这类失败只能整条重来。
                        if (settled.round > 0 && settled.toolHistory != null
                                && settled.toolHistory.length() > 2
                                && settled.httpCode != 400 && settled.httpCode != 422) {
                            markAssistantFailed(finalIdx, settled.errorDetail, settled.trace,
                                    settled.toolHistory, settled.apiText, settled.generation);
                            return;
                        }
                        // 首轮就失败：移除空 assistant 占位，避免空气泡落盘/进入后续请求体
                        rollbackEmptyAssistantPlaceholder(finalIdx, settled.generation);
                        markSendFailed(finalIdx, settled.errorDetail, settled.generation);
                        return;
                    }

                    // 收尾提交。两条路径：
                    //   1) DOM 已经是最终内容（最后一次分片成功落地）——只把 tail 定型进
                    //      base，不重新解析 markdown。长回答含代码块/公式时，整段重渲染
                    //      会让公式图片重新解码，视觉上就是「回答结束后闪一下」。
                    //   2) DOM 落后于最终内容（最后一片被节流丢弃/被序号守卫挡掉）——
                    //      必须重渲染补上，否则结尾少几十个字。
                    final boolean domComplete = settled.content != null
                            && settled.content.equals(lastPushedContent);
                    final String html = domComplete ? null
                            : MessageHtmlRenderer.contentToHtml(settled.content, MainActivity.this);
                    if (requestGeneration.get() != settled.generation) return;
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (requestGeneration.get() != settled.generation) return;
                            if (finalIdx >= 0 && finalIdx < messages.size()
                                    && messages.get(finalIdx).isAssistant()) {
                                Message done = messages.get(finalIdx);
                                done.content = settled.content;
                                // 完成时刻用于算端到端速度；用量可能是 0（端点不支持）
                                done.finishedAt = settled.finishedAt > 0
                                        ? settled.finishedAt : System.currentTimeMillis();
                                done.promptTokens = settled.promptTokens;
                                done.completionTokens = settled.completionTokens;
                                done.totalTokens = settled.totalTokens;
                            }
                            if (domComplete) {
                                webViewEvalJs("streamFinalize()");
                            } else {
                                webViewEvalJs("updateLastMsg('" + jsEscape(html) + "')");
                            }
                            // 两条分支都让 base 层等于这条消息的全文：streamFinalize 把
                            // tail 搬进了 base，updateLastMsg 直接把全文写进 base。
                            // 不更新的话，续跑时增量渲染会拿「旧 prefix」当基准，
                            // 把已经显示的内容再追加一遍。
                            lastStreamTrace = settled.content == null ? "" : settled.content;
                            // 来源区独立渲染在 .content 之外，默认折叠
                            Message am = finalIdx >= 0 && finalIdx < messages.size()
                                    ? messages.get(finalIdx) : null;
                            String srcHtml = MessageHtmlRenderer.renderSourcesInner(
                                    am == null ? null : am.sources);
                            webViewEvalJs("updateLastSources('" + jsEscape(srcHtml) + "')");
                            // 时间/用量/速度和来源一样挂在 .content 之外，
                            // 必须在 finalizeLast 之前写，之后 DOM 下标就不是 -1 了
                            webViewEvalJs("updateLastMeta('"
                                    + jsEscape(MessageHtmlRenderer.renderMeta(am)) + "')");
                            webViewEvalJs("finalizeLast(" + finalIdx + ")");
                            conversationManager.saveCurrentConversation();
                        }
                    });
                } catch (Exception e) {
                    LogUtil.e(TAG, "final commit failed: %s", e.getMessage(), e);
                } finally {
                    // 最后才放行队列：先 drain 会让新请求自增代数，使这条回复被代数守卫丢弃
                    if (requestGeneration.get() == settled.generation) finishRequestAndDrain();
                }
            }
        }).start();
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
                        // base 层现在就是这条消息的全文渲染，与 content 对齐；
                        // 不更新会让续跑的增量渲染把「已推送」当成旧前缀，重复追加。
                        lastStreamTrace = content == null ? "" : content;
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
     * 把累计的 trace（思考块 + 工具卡片）渲染到 AI 气泡的 <b>base 层</b>。
     *
     * <p>这里刻意不用 {@code updateLastMsg}：那个函数会把整个 {@code .content}
     * 的 innerHTML 换掉，正在流出的 {@code .stream-tail} 会跟着被抹——
     * 表现就是「回答闪一下，然后少了一大截」。base 与 tail 分层之后，
     * 写 base 不会影响 tail，两者拼起来才是完整回答。
     *
     * <p>{@code status} 非空时另挂一条独立状态行，它同样不碰正文。
     */
    private void renderTraceAsync(final String traceText, final int generation, final String status,
            final int aiIndex) {
        final String snapshot = traceText == null ? "" : traceText;
        final String base0 = lastStreamTrace == null ? "" : lastStreamTrace;

        // 内容没变（最常见：只换了状态文案）就只写状态行。
        // 长回答 + 代码块时整段重渲染是几百毫秒的空白帧，低端机上非常扎眼。
        if (snapshot.equals(base0)) {
            if (status != null) {
                webViewEvalJs("setStatus('" + jsEscape(status) + "')");
            }
            return;
        }
        // trace 只会变长，比已推送的更短一定是旧轮次迟到的快照。
        // 唯一的例外是 TraceKit 裁剪：那条路径会先把 lastStreamTrace 清空，
        // 所以走到这里 base0 一定还有效。
        if (snapshot.length() < base0.length()) {
            LogUtil.d(TAG, "stale trace render dropped: base=%d, snapshot=%d",
                    base0.length(), snapshot.length());
            if (status != null) {
                webViewEvalJs("setStatus('" + jsEscape(status) + "')");
            }
            return;
        }

        // 增量渲染：base 已经固化的内容正好是新快照的前缀时，只渲染尾部。
        // 工具轮次里每追加一张卡片都重渲染整段历史，成本是 O(轮次²)——
        // 十几轮搜索之后，低端机上每张卡片都要卡半秒。追加模式只解析新增的
        // 那一个块，已有的公式图片、代码高亮、展开状态全部原地不动。
        //
        // 前提由 ChatStreamRunner 保证：trace 的每次追加都以完整的
        // [thinking]…[/thinking] 或 [tool]…[/tool] 块为单位，边界处切分不会
        // 破坏 markdown 结构。
        final boolean append = base0.length() > 0 && snapshot.startsWith(base0);
        final String part = append ? snapshot.substring(base0.length()) : snapshot;

        final int mySeq = renderSeq.incrementAndGet();
        new Thread(new Runnable() {
            public void run() {
                final String html = MessageHtmlRenderer.contentToHtml(part, MainActivity.this);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (requestGeneration.get() != generation) return;
                        if (renderSeq.get() != mySeq) {
                            LogUtil.d(TAG, "trace render superseded: seq %d/%d", mySeq, renderSeq.get());
                            return;
                        }
                        lastStreamTrace = snapshot;
                        if (append) {
                            webViewEvalJs("streamAppendBase('" + jsEscape(html) + "')");
                        } else {
                            // 只写 base 层；tail 上正在流的内容原样保留
                            webViewEvalJs("streamBase('" + jsEscape(html) + "')");
                        }
                        if (status != null) {
                            webViewEvalJs("setStatus('" + jsEscape(status) + "')");
                        }
                    }
                });
            }
        }).start();
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
                        // 整页重建，DOM 里不存在上一次流式留下的 base 骨架，
                        // 「已推送基准」必须作废，否则下一次 trace 渲染会误走增量
                        lastStreamTrace = "";
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
                setRequestInProgress(false);
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
            wv.loadUrl("javascript:" + TextKit.forJsUrl(js));
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
                    openMessageMenu(idx);
                }
            });
        }

        @JavascriptInterface
        public void messageLongPress(final String idx) {
            LogUtil.d(TAG, "JsBridge.messageLongPress: idx=%s", idx);
            handler.post(new Runnable() {
                public void run() {
                    openMessageMenu(idx);
                }
            });
        }

        /**
         * 「⋯」按钮与长按共用同一条入口。两处都要支持 -1：正在生成的气泡在 DOM 上
         * 只有 -1，没有稳定下标，以前被 showMessageMenu 的 pos&lt;0 守卫直接挡掉。
         */
        private void openMessageMenu(String idx) {
            try {
                int pos = Integer.parseInt(idx);
                if (pos < 0) {
                    showStreamingMenu();
                    return;
                }
                showMessageMenu(pos);
            } catch (Exception e) {
                LogUtil.w(TAG, "openMessageMenu parse failed: idx=%s err=%s", idx, e.getMessage());
            }
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

        /** 手动中断、且没有工具历史的 AI 消息：删掉它，从对应的用户消息重新回复。 */
        @JavascriptInterface
        public void retryInterrupted(final String idx) {
            LogUtil.d(TAG, "JsBridge.retryInterrupted: idx=%s", idx);
            handler.post(new Runnable() {
                public void run() {
                    try {
                        int pos = Integer.parseInt(idx);
                        if (pos < 0 || pos >= messages.size()) return;
                        Message m = messages.get(pos);
                        if (m == null || !m.isAssistant()) return;
                        if (isRequestInProgress.get()) {
                            Toast.makeText(MainActivity.this, "当前回复仍在进行，请先停止", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        int userIdx = lastUserIndexBefore(pos);
                        if (userIdx < 0) {
                            LogUtil.w(TAG, "retryInterrupted: no user message before %d", pos);
                            return;
                        }
                        resendUserAt(userIdx);
                    } catch (Exception e) {
                        LogUtil.w(TAG, "retryInterrupted failed: %s", e.getMessage());
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
                    // onDestroy 会把 viewer 置空，而 WebView 里排队执行的 JS 仍可能回调进来
                    if (viewer == null) return;
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
                    if (viewer == null) return;
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
                    if (viewer == null) return;
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
        // pos 来自 DOM 上的 data-idx。消息可能在长按之后、菜单弹出之前被删掉
        // （流式收尾回滚空占位、另一条消息的重试截断），直接 get 会越界崩溃。
        if (messages == null || pos < 0 || pos >= messages.size()) {
            LogUtil.w(TAG, "showMessageMenu ignored: pos=%d size=%d", pos,
                    messages == null ? -1 : messages.size());
            return;
        }
        final Message msg = messages.get(pos);
        if (msg == null) return;
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
        copyToClipboard(text);
    }

    /** 写剪贴板。API 18 上有设备返回的是旧版 android.text.ClipboardManager，需要分支。 */
    private void copyToClipboard(String text) {
        try {
            Object svc = getSystemService(Context.CLIPBOARD_SERVICE);
            if (svc instanceof ClipboardManager) {
                ClipboardManager cm = (ClipboardManager) svc;
                cm.setPrimaryClip(ClipData.newPlainText("message", text));
                LogUtil.d(TAG, "copyToClipboard: %d chars via ClipboardManager", text.length());
            } else {
                // API 18 fallback: some devices return the old ClipboardManager
                ((android.text.ClipboardManager) svc).setText(text);
                LogUtil.d(TAG, "copyToClipboard: %d chars via legacy ClipboardManager", text.length());
            }
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            LogUtil.e(TAG, "copyToClipboard failed: " + e.getMessage(), e);
            Toast.makeText(this, "复制失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // 2. 选择文本
    private void selectText(int pos) {
        String text = getMessageText(messages.get(pos));
        LogUtil.d(TAG, "selectText: pos=%d len=%s", pos, text == null ? "null" : text.length());
        showSelectableText("选择文本", text);
    }

    /** 弹一个可长按选区的只读文本框。流式中的气泡也用这个，只是没下标可取。 */
    private void showSelectableText(String title, String text) {
        final TextView tv = new TextView(this);
        tv.setText(text == null ? "" : text);
        tv.setTextIsSelectable(true);
        tv.setPadding(32, 32, 32, 32);
        tv.setTextSize(14);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(tv)
                .setPositiveButton("关闭", null)
                .show();
    }

    /**
     * 正在生成的气泡：DOM 上的 data-idx 是 -1，messages 里没有稳定的下标。
     * 修改/删除/回溯/分支都要按真实下标操作，生成中调用会把请求状态机搅乱
     * （删了下标会整体前移，正在跑的那条回写就落到别人身上），所以这里只给
     * 不依赖下标的操作 + 一条「重新生成」（内部先打断再按提问重发）。
     */
    private void showStreamingMenu() {
        if (!isRequestInProgress.get()) {
            // 请求其实已经结束，只是 DOM 还挂着 -1（finalizeLast 还没跑到）。
            // 这时按常规消息处理，别弹精简菜单、也别整页重绘——后者会让用户
            // 觉得「点了一下屏幕闪一下但什么都没发生」。
            int pos = messages == null ? -1 : messages.size() - 1;
            if (pos >= 0 && messages.get(pos) != null && messages.get(pos).isAssistant()) {
                LogUtil.d(TAG, "showStreamingMenu: request finished, fall back to pos=%d", pos);
                showMessageMenu(pos);
                return;
            }
            LogUtil.w(TAG, "showStreamingMenu: no assistant message, refreshing view");
            refreshWebView();
            return;
        }
        final String[] items = {"复制已生成内容", "选择文本", "重新生成", "停止生成"};
        new AlertDialog.Builder(this)
                .setTitle("操作消息")
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            copyStreamingText();
                        } else if (which == 1) {
                            String text = streamingText();
                            if (text.isEmpty()) {
                                Toast.makeText(MainActivity.this, "还没有生成内容", Toast.LENGTH_SHORT).show();
                            } else {
                                showSelectableText("选择文本", text);
                            }
                        } else if (which == 2) {
                            retryStreaming();
                        } else {
                            interruptRequest();
                        }
                    }
                })
                .show();
    }

    /**
     * 生成中「重新生成」：丢掉这次输出，按最后一条提问整条重发。
     * 走 interruptAndRetry，它会先自增代数挡住旧线程回写、断开连接、等旧线程退出，
     * 再截断到提问处重发——不能在 UI 线程上直接改 messages，否则和正在跑的流式
     * 回调抢同一份列表，下标会错位。
     */
    private void retryStreaming() {
        if (messages == null || messages.isEmpty()) {
            LogUtil.w(TAG, "retryStreaming: no messages");
            Toast.makeText(this, "找不到对应的提问，无法重新生成", Toast.LENGTH_SHORT).show();
            return;
        }
        int pos = lastUserIndexBefore(messages.size());
        if (pos < 0) {
            LogUtil.w(TAG, "retryStreaming: no user message to retry");
            Toast.makeText(this, "找不到对应的提问，无法重新生成", Toast.LENGTH_SHORT).show();
            return;
        }
        LogUtil.i(TAG, "retryStreaming: retry from user pos=%d", pos);
        confirmInterruptAndRetry(pos);
    }

    private void copyStreamingText() {
        String text = streamingText();
        if (text.isEmpty()) {
            Toast.makeText(this, "还没有生成内容", Toast.LENGTH_SHORT).show();
            return;
        }
        copyToClipboard(text);
    }

    /** 当前正在生成的那条 AI 回复的纯文本。找不到就是空串。 */
    private String streamingText() {
        if (messages == null) return "";
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (m != null && m.isAssistant()) {
                String t = getMessageText(m);
                return t == null ? "" : t;
            }
        }
        return "";
    }

    // 3. 修改
    private void editMessage(final int pos) {
        if (pos < 0 || pos >= messages.size()) {
            LogUtil.w(TAG, "editMessage ignored: pos=%d size=%d", pos, messages.size());
            return;
        }
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
        if (pos < 0 || pos >= messages.size()) {
            LogUtil.w(TAG, "deleteMessage ignored: pos=%d size=%d", pos, messages.size());
            return;
        }
        Message msg = messages.get(pos);
        String preview = getMessageText(msg);
        if (preview == null) preview = "";
        if (preview.length() > 30) preview = preview.substring(0, 30) + "...";
        showConfirmDialog("确定删除本条消息？\n\n" + preview, new Runnable() {
            public void run() {
                // 弹窗期间列表可能已经变化（排队消息发出去、另一条被回滚）
                if (pos < 0 || pos >= messages.size()) {
                    LogUtil.w(TAG, "deleteMessage aborted: pos=%d size=%d", pos, messages.size());
                    return;
                }
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
                    new StreamJob.Resume(pos, rounds, am.content == null ? "" : am.content,
                            conversation, sources, am.apiText == null ? "" : am.apiText,
                            am.toolHistory == null ? "" : am.toolHistory));
        } catch (Exception e) {
            LogUtil.e(TAG, "resumeAssistantRequest failed: %s", e.getMessage(), e);
            Toast.makeText(this, "无法从此处继续，请重试整条消息", Toast.LENGTH_SHORT).show();
        }
    }

    /** pos 之前最近一条用户消息的下标，没有则返回 -1。 */
    private int lastUserIndexBefore(int pos) {
        if (messages == null || messages.isEmpty()) return -1;
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

        // 先暂停队列放行：否则旧请求的收尾可能把队首消息发出去，新请求自增代数后
        // 重试的代数校验就会失败，用户看到的是「点了重试但什么都没发生」。
        // 暂停不丢消息，重试真正发出后由 resume() 交还给正常的收尾流程。
        if (queue != null) queue.pause();

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
                            // 无论是正常重发、pos 失效还是被新请求取代，都要把队列交还回去：
                            // 这一路把 isRequestInProgress 置成 false 了，没人再会来叫醒 drain。
                            if (queue != null) queue.resume();
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
                // 与新建/切换会话同一套收口：排队消息属于旧会话，在途请求会继续往
                // messages 里写。不先停掉，分支切过去后收尾逻辑会按旧下标写到新列表。
                if (queue != null) queue.clearAll();
                if (isRequestInProgress.get()) interruptRequest();
                dropTrailingEmptyAssistant();
                // 清空占位后 pos 可能已经越界（最后一条正是那条空 AI 占位）
                int last = Math.min(pos, messages.size() - 1);
                if (last < 0) {
                    Toast.makeText(MainActivity.this, "没有可复制的消息", Toast.LENGTH_SHORT).show();
                    return;
                }
                Conversation current = conversationManager.getCurrentConversation();
                Conversation branch = new Conversation();
                branch.title = ConversationManager.normalizeTitle(current.title) + "-分支";
                branch.systemPrompt = current.systemPrompt;
                branch.model = current.model;
                LogUtil.i(TAG, "branchAt: copying %d messages into branch '%s'", last + 1, branch.title);

                for (int i = 0; i <= last; i++) {
                    Message src = messages.get(i);
                    Message copy = new Message();
                    copy.role = src.role;
                    copy.content = src.content;
                    copy.timestamp = src.timestamp;
                    // 时间/用量跟着走，否则分支里的消息会比原会话「少一行」
                    copy.finishedAt = src.finishedAt;
                    copy.promptTokens = src.promptTokens;
                    copy.completionTokens = src.completionTokens;
                    copy.totalTokens = src.totalTokens;
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
