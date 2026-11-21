package xyz.zip8919.app.aichat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
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
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends ThemedActivity {
    private static final String TAG = "MainActivity";
    /** 拍照目标文件跨 Activity 重建的保存键。 */
    private static final String STATE_CAMERA_OUTPUT = "camera_output_path";

    /**
     * Height of the viewer overlay toolbar (viewer_top_left / viewer_close are
     * 44dp + 4dp margin each side). The preview WebViews fill the whole dialog,
     * so page content must be inset by this much or it renders underneath the
     * toolbar buttons.
     */
    private static final int VIEWER_TOOLBAR_INSET_PX = 52;
    private static final String PREFS_NAME = "aichat_prefs";
    /** 公告弹窗本次运行是否已经弹过，避免切后台回来重复弹。 */
    private boolean noticeShown;
    /** 公告已读记录是否已按新规则重置过一次。 */
    private static final String KEY_NOTICE_RESET = "notice_read_reset_v2";
    /** 按 provider 记住「不支持 stream_options」，值是 boolean，缺省 false。 */
    private static final String KEY_USAGE_UNSUPPORTED_PREFIX = "usage_unsupported_";
    /** 首次启动说明与免责声明确认过没有。 */
    private static final String KEY_FIRST_LAUNCH_DONE = "first_launch_done";
    private static final int REQUEST_CONVERSATION_MANAGER = 1;
    private static final int REQUEST_SCAN = 2;
    private static final int REQUEST_PICK_TEXT = 3;
    private static final int REQUEST_FULLSCREEN = 4;
    private static final int REQUEST_PICK_IMAGE = 5;
    private static final int REQUEST_TAKE_PHOTO = 6;
    private static final int REQUEST_CROP = 7;
    /** 附件菜单项 id（单项目前用常量，后续加项改成 menu 资源）。 */
    private static final int MENU_ATTACH_TEXT = 1;
    private static final int MENU_ATTACH_IMAGE = 2;
    private static final int MENU_ATTACH_IMAGE_URL = 3;
    private static final int MENU_ATTACH_CAMERA = 4;
    /** 单文件上限 10 MB：超过直接报错，不截断——半截代码更难排查。 */
    private static final int MAX_IMPORT_BYTES = 10 * 1024 * 1024;
    // 单次附件数与合计字符上限已搬进设置，见 Limits.maxAttachments / Limits.attachTotalChars

    /**
     * 待发送附件（仅输入框这一侧；已发送的附件在 {@link Message#attachments}）。
     *
     * <p>选完文件不往输入框里塞正文——两万字的日志会把输入框撑爆、把用户自己写的问题挤到
     * 屏幕外，中途想删还得手工选中删除。这里只留文件名，正文在发送时整体交给
     * {@link Message#attachments}，气泡里画成独立卡片，不进正文。
     */
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
    /** 清空输入（输入框文字 + 待发附件），单击弹确认框。 */
    private TextView clearInputButton;
    /**
     * 输入框上方那条动作栏（清空 / 全屏）。
     * 整条只在「有文字或有附件」时出现：空输入时它完全让位，输入框拿回全部宽度。
     */
    private View inputActionBar;
    /** 回到底部气泡当前是否可见。JS 每秒能调几十次，靠它去重。 */
    private volatile boolean scrollBubbleVisible;
    /** 附件条的滚动外壳（GONE 时整条隐藏）与内部容器。 */
    private View attachmentScroll;
    private LinearLayout attachmentBar;
    private final List<AttachmentText.File> pendingAttachments = new ArrayList<AttachmentText.File>();
    /** 相机写入的目标文件；拍照回调里要用它判断拍没拍成。 */
    private File cameraOutputFile;
    // 发送键长按打断后，本次抬手的 click 不再触发发送
    private boolean sendButtonLongPressed = false;
    private WebView conversationWebView;
    /**
     * 页面内容版本与已就绪版本。
     *
     * <p>WebView 每次都用 {@code loadDataWithBaseURL(null, …)} 灌页，onPageFinished
     * 只给一个恒为 about:blank 的 URL，没法分辨回调属于哪一次加载：切会话时旧页迟到的
     * 回调会把页面判成可用，随后 appendMsg / appendAiDiv 写进即将被冲掉的旧 DOM，
     * 用户看到的就是「发完消息界面没有任何变化，重进历史才看得到」。
     *
     * <p>所以把版本号写进页面本身（{@code <body data-ac-page="N">}），回调里读回来比对。
     * 读回来的不是当前版本，说明这次回调属于已经被替换掉的旧页，直接忽略。
     */
    private final java.util.concurrent.atomic.AtomicInteger pageVersion =
            new java.util.concurrent.atomic.AtomicInteger();
    /** 已经在 WebView 里确认加载完成的页面版本；-1 表示还没有任何一版加载完成。 */
    private volatile int loadedPageVersion = -1;
    /** 有页面正在加载、还没确认就绪。 */
    private volatile boolean pageLoading = false;
    /** 版本探测轮次，用于超时补灌，避免回调始终不来时永久卡住。 */
    private int pageProbeTries = 0;
    /** 本次加载已经补灌过一次；只补一次，防止失败时无限重灌。 */
    private boolean pageReloadRetried = false;
    /** 渲染时列表被改动导致重来的次数，防止极端情况下连着重来。 */
    private int renderRetryCount = 0;
    private final java.util.ArrayDeque<String> pendingJs = new java.util.ArrayDeque<String>();
    /**
     * 每次整页重建时给「这一版页面画的那个 message 列表」发的编号。
     *
     * <p>页面版本号只回答「这一版上屏没有」，回答不了「脚本是不是发给它的」：
     * 同一版页面可以被连灌两次（补灌），也可以在不同消息列表之间来回切。
     * 追加类脚本带上这个编号，页面一比对就知道该不该处理——迟到的帧宁可丢掉，
     * 也不能写进别人的气泡。
     */
    private final java.util.concurrent.atomic.AtomicInteger msgListId =
            new java.util.concurrent.atomic.AtomicInteger();
    /** 当前页面正在画的消息列表编号（未就绪时为新一版的编号）。 */
    private volatile int curMsgListId = 0;
    /** 最近一次追加到会话尾部的消息下标：页面就绪后用它核对这条是不是真的上屏了。 */
    private volatile int lastAppendedIdx = -1;
    private boolean lastAppendCheckPending = false;
    private Spinner modelSpinner;
    private Spinner thinkingSpinner;
    private View queueBar;
    private TextView queueBarText;
    private MessageQueue queue;
    private boolean userPicked = false;
    /** 思考下拉当前的可选档位（下标与下拉项一一对应）。 */
    private final List<String> thinkingChoices = new ArrayList<String>();
    /** 上次按哪一版探测结论生成档位下拉，探测在别的页面完成时用它判断要不要重建。 */
    private int probedRev;

    private List<Message> messages;
    private List<ModelInfo> availableModels;
    // DOM 里已渲染的最早消息在 messages 中的下标；其上滑加载时向前推进。
    // volatile：渲染线程读、UI 线程写。
    private volatile int renderedFrom = 0;
    // 防止滚动事件连续触发重复请求
    private volatile boolean loadingOlderMessages = false;

    private AtomicBoolean isRequestInProgress = new AtomicBoolean(false);
    /** 公式出图颜色签名：已生效的那个。 */
    private volatile String tintSigApplied;
    /** 流式输出期间发生、但还没收尾的颜色变更签名；非空表示待补一次重灌。 */
    private volatile String tintSigPending;
    /**
     * 外观变更监听（负责清渲染缓存 + 重灌聊天页）。
     *
     * <p><b>必须存成字段</b>：AppearanceManager 的监听器列表只持弱引用，
     * 早先这里是直接 addChangeListener(new ...)，那个匿名对象没有任何强引用，
     * GC 一旦回收，切深浅色就不会再触发重灌 —— 公式停在旧颜色上，
     * 用户看到的是「要重启 App 才变色」。存字段后生命周期与本 Activity 对齐。
     */
    private AppearanceManager.ChangeListener webTintListener;
    /** WebView 侧样式刷新器；后台跳过、回前台补一次，见 {@link #bindWebView()}。 */
    private WebViewRefresher webRefresher;
    /**
     * 本页是否在前台可见。
     *
     * <p>外观编辑页拖动取色滑条时，每一下都会 bump 一次外观版本。此时聊天页在后台，
     * 若照旧「清缓存 + 重灌整页」，等于让用户每拖一格就重建一次聊天 WebView ——
     * 老机器上直接卡成幻灯片。后台时只记签名，回前台再补。
     */
    private volatile boolean uiVisible;
    /**
     * 「立即重试」信号：429 等待期间由 JS bridge 置位，请求线程每 200 ms 读一次。
     *
     * <p>不用 interrupt 打断线程——那会连带触发「停止」的语义，
     * 而这两件事的结果正好相反（一个继续、一个终止）。
     */
    private final AtomicBoolean retryNowRequested = new AtomicBoolean(false);
    private AtomicInteger requestGeneration = new AtomicInteger(0);
    // 打断并重试的串行化闸门：防止连点导致多个 resendUserAt 并发插入用户消息
    private volatile boolean retryInFlight = false;
    // 渲染代数守卫：refreshWebView 可能被连续触发，过期渲染必须丢弃
    private final AtomicInteger renderGeneration = new AtomicInteger(0);
    // 流式渲染序号：每次发起渲染就自增，慢的那次回来时序号已经过期就丢弃。
    // 提到字段上是因为 renderTraceAsync 与流式 tick 都要用它做互斥。
    private final java.util.concurrent.atomic.AtomicInteger renderSeq =
            new java.util.concurrent.atomic.AtomicInteger();
    /**
     * 本次请求是否逐字渐显，在发起时定死。
     *
     * <p>流式帧要据此决定「直接写入可见的 tail」还是「写进渐显缓冲」，
     * 生成中途去设置页改动开关时，正在跑的这一条必须保持原来的方式：
     * 一半文字已经渐显完、一半还压在缓冲里，切到另一种写法会缺字或重复。
     */
    private volatile boolean smoothStream = true;
    // trace（base 层）渲染单独一个序号：tick 的 nextSeq() 只该作废流式 tail 渲染，
    // 不该把在飞的整段工具卡片渲染判为过期。generation 守卫仍拦住跨请求的旧结果。
    private final java.util.concurrent.atomic.AtomicInteger traceRenderSeq =
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

    /** 会话切换进行中：遮罩显示期间不允许再来一次切换，避免两次加载互相覆盖。 */
    private boolean switchingConversation;
    private View loadingOverlay;
    private TextView loadingDetail, loadingPercent;
    private android.widget.ProgressBar loadingProgress;

    /**
     * 切会话渲染状态机：0=空闲，1=等后台拼整页 HTML，2=等 WebView onPageFinished。
     *
     * <p>数据读完不代表页面能看 —— 长会话最耗时的恰恰是整页 HTML 拼接、Markdown
     * 渲染与 WebView 排版。v1.14.19 在 refreshWebView() 后固定等 260ms 就收遮罩，
     * 于是「加载完成」一闪而过，剩下几秒白屏期间既没有进度也没有动画。
     * 现在遮罩的收起权交给 onPageFinished，并配超时兜底与渲染期进度脉冲。
     */
    private volatile int conversationRenderState;
    private volatile int conversationRenderGen;
    private volatile String conversationRenderTitle = "";
    private volatile int conversationRenderTickPercent;
    /** 任一阶段卡死（渲染线程异常 / WebView 不回调）都强制收起遮罩，绝不把用户锁死。 */
    private final Runnable conversationRenderTimeout = new Runnable() {
        public void run() {
            if (conversationRenderState == 0) return;
            LogUtil.w(TAG, "conversation render timeout: state=%d gen=%d, force dismiss",
                    conversationRenderState, conversationRenderGen);
            if (conversationRenderState == 1) conversationRenderState = 2;
            finishConversationRender(true);
        }
    };
    /** 渲染阶段没有天然的中间刻度，用缓慢推进的脉冲证明「还活着」，避免进度条僵在 85%。 */
    private final Runnable conversationRenderTick = new Runnable() {
        public void run() {
            if (conversationRenderState != 2) return;
            if (conversationRenderTickPercent < 98) {
                conversationRenderTickPercent++;
                updateConversationLoading(Strings.get(R.string.mainactivity_001), conversationRenderTickPercent);
            }
            handler.postDelayed(this, 240);
        }
    };
    /** 100% 停留后的延迟收起；新一轮切换开始前必须能取消，否则会误关新遮罩。 */
    private Runnable pendingRenderHide;

    // 全屏预览器（图片 / 表格 / HTML·SVG 代码），自持 dialog 与 WebView 生命周期
    private ViewerController viewer;

    // Quick scan mode state
    private boolean expectingQuickScanResult = false;

    private String currentModel;
    /**
     * 当前模型所属提供商的唯一标识。
     *
     * <p>只靠 {@link #currentModel}（模型 ID）恢复选择是不够的：同一个 ID 可能同时挂在
     * 两个提供商下，重启后按名字匹配会落到另一条上 —— 用户看到的名字一样，实际换成了
     * 另一个端点、另一把 Key。所以选中态记「ID + 提供商」两段。
     * 老配置没有这一段时按 null 处理，行为与之前一致。
     */
    private String currentProvider;
    private String systemPrompt;
    /** 输入框上方的会话用量状态栏，见 {@link #updateUsageBar()}。 */
    private TextView usageBar;
    /** 上次手动选择的模型，Activity 重建后用它恢复，而不是回落到默认模型。 */
    private static final String KEY_SELECTED_MODEL = "selected_model";
    /** 选中模型所属提供商，配合 {@link #KEY_SELECTED_MODEL} 消歧同名模型。 */
    private static final String KEY_SELECTED_PROVIDER = "selected_model_provider";

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        // 相机是内存密集操作，宿主 Activity 常被系统回收。不把目标文件路径存下来，
        // 拍完回来 cameraOutputFile 就是 null，表现为「点了确认什么都没发生」。
        if (cameraOutputFile != null) {
            outState.putString(STATE_CAMERA_OUTPUT, cameraOutputFile.getAbsolutePath());
        }
    }

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LogUtil.ENABLED = SettingsActivity.isLogEnabled(this);
        LogUtil.i(TAG, "========== onCreate: savedInstanceState=%s, thread=%s ==========",
                savedInstanceState, LogUtil.thread());
        setContentView(R.layout.activity_main);

        if (savedInstanceState != null) {
            String pendingShot = savedInstanceState.getString(STATE_CAMERA_OUTPUT);
            if (pendingShot != null && pendingShot.length() > 0) {
                cameraOutputFile = new File(pendingShot);
            }
        }

        this.prefs = getSharedPreferences(PREFS_NAME, 0);
        // 先刷一遍运行期上限：附件、工具、搜索都要读它，晚于任何一次附加就白改了
        Limits.reload(getApplicationContext());
        this.storageManager = StorageManager.getInstance(this);
        this.configManager = ConfigManager.getInstance();
        this.conversationManager = ConversationManager.getInstance();
        LogUtil.d(TAG, "managers initialized: storage=%s, config=%s, conv=%s",
                storageManager, configManager, conversationManager);

        if (!storageManager.createDirectories()) {
            LogUtil.e(TAG, "createDirectories FAILED, basePath=%s", storageManager.getBasePath());
            AcToast.showLong(this, Strings.get(R.string.mainactivity_002));
        } else {
            LogUtil.i(TAG, "storage ready: %s (conversations=%s)",
                    storageManager.getStorageInfo(), storageManager.getConversationsPath());
            AcToast.showLong(this, storageManager.getStorageInfo());
        }

        configManager.load();
        // 附件目录搬过一次家（内部私有 → 数据目录）。迁移放后台，绝不阻塞首屏；
        // 没搬完也能用：读取时按文件名兜底解析（AttachmentFiles.resolve）
        new Thread(new Runnable() {
            public void run() {
                try {
                    AttachmentFiles.migrateLegacy();
                } catch (Throwable t) {
                    LogUtil.w(TAG, "attachment migration failed: %s", t.getMessage());
                }
            }
        }, "aichat-att-migrate").start();
        // 统计必须在 StorageManager 初始化之后：它要从 basePath 读 stats.json，
        // 早于存储初始化会读到空、随后把磁盘上的累计数据覆盖掉
        UsageStats.init(getApplicationContext());
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
            fetchNotice();
        } else {
            showFirstLaunchDialog();
        }
        LogUtil.i(TAG, "========== onCreate done ==========");
    }

    /**
     * 启动拉一次公告，有没读过的就弹出来，可逐条翻看。
     *
     * <p>只弹没读过的。老版本每版都弹，已经打扰过一轮，升级后作废旧记录，
     * 上线公告只再弹一次，之后按「我知道了」的结果判定，不再重复。
     * 失败静默：公告是附赠信息，网络不通或服务异常不该打扰用户，也不该阻塞任何操作。
     */
    private void fetchNotice() {
        Notice.load(this, new FeedbackApi.Callback() {
            public void onSuccess(org.json.JSONObject data) {
                java.util.List<Notice> list = Notice.parseList(data);
                if (list.isEmpty()) {
                    NoticeReadStore.rememberLast(MainActivity.this, "");
                    return;
                }
                String latest = list.get(0).id;
                // 老版本的记录不算数：那一版每次启动都弹，已读等于没生效
                boolean upgraded = !prefs.getBoolean(KEY_NOTICE_RESET, false);
                if (upgraded) prefs.edit().putBoolean(KEY_NOTICE_RESET, true).commit();
                java.util.List<Notice> pending = upgraded ? list : NoticeReadStore.unread(MainActivity.this, list);
                if (pending.isEmpty()) return;
                showNoticeDialog(pending, latest);
            }

            public void onError(String message) {
                LogUtil.d(TAG, "notice load skipped: %s", message);
            }
        });
    }

    /**
     * 开屏公告弹窗。只放没读过的公告，多条时正文下方带「上一条 / 下一条」翻看。
     *
     * <p>标题带当前位置（第几条 / 共几条），到首条或末条时对应按钮变灰不可点。
     * 点「我知道了」才记为已读，直接返回或杀掉应用视为没看，下次继续弹。
     * 弹出后本次运行内不再重复弹。
     */
    private void showNoticeDialog(final java.util.List<Notice> list, final String latestId) {
        if (isFinishing() || list.isEmpty() || noticeShown) return;
        noticeShown = true;
        final java.util.List<Notice> items = list;
        final int total = items.size();
        final int[] index = new int[]{0};

        final View content = getLayoutInflater().inflate(R.layout.view_notice_detail, null);
        final TextView title = (TextView) content.findViewById(R.id.nd_title);
        final TextView body = (TextView) content.findViewById(R.id.nd_body);
        final TextView link = (TextView) content.findViewById(R.id.nd_link);
        final View pager = content.findViewById(R.id.nd_pager);
        final android.widget.Button prev = (android.widget.Button) content.findViewById(R.id.nd_prev);
        final android.widget.Button next = (android.widget.Button) content.findViewById(R.id.nd_next);
        pager.setVisibility(total > 1 ? View.VISIBLE : View.GONE);
        link.setText(Strings.get(R.string.mainactivity_171));
        link.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String url = items.get(index[0]).link;
                if (url != null && url.length() > 0) openNoticeLink(url);
            }
        });

        final android.app.AlertDialog dialog = AcDialog.builder(this)
                .setTitle(Strings.get(R.string.mainactivity_172))
                .setView(content)
                .setPositiveButton(Strings.get(R.string.mainactivity_173), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        // 点「我知道了」才记为已读并写盘，下次启动不再弹这几条
                        for (int i = 0; i < items.size(); i++) {
                            NoticeReadStore.markRead(MainActivity.this, items.get(i).id);
                        }
                        NoticeReadStore.rememberLast(MainActivity.this, latestId);
                    }
                })
                .create();

        prev.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (index[0] > 0) {
                    index[0]--;
                    renderNotice(index[0], items, title, body, link, prev, next);
                    dialog.setTitle(titleOf(index[0], items.size()));
                }
            }
        });
        next.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (index[0] < items.size() - 1) {
                    index[0]++;
                    renderNotice(index[0], items, title, body, link, prev, next);
                    dialog.setTitle(titleOf(index[0], items.size()));
                }
            }
        });

        AcDialog.onShow(dialog, new android.content.DialogInterface.OnShowListener() {
            public void onShow(android.content.DialogInterface d) {
                renderNotice(index[0], items, title, body, link, prev, next);
                dialog.setTitle(titleOf(index[0], items.size()));
            }
        });
        dialog.show();
        LogUtil.i(TAG, "notice dialog: %d items", total);
    }

    /** 弹窗标题，带当前第几条与总条数。 */
    private String titleOf(int i, int total) {
        return Strings.get(R.string.mainactivity_176, i + 1, total);
    }

    /** 刷新当前这一条；标题带位置，翻页按钮按是否到边界置灰。 */
    private void renderNotice(int i, java.util.List<Notice> items,
                              TextView title, TextView body, TextView link,
                              android.widget.Button prev, android.widget.Button next) {
        final Notice n = items.get(i);
        title.setText(n.title);
        body.setText(n.body);
        if (n.link != null && n.link.length() > 0) {
            link.setVisibility(View.VISIBLE);
        } else {
            link.setVisibility(View.GONE);
        }
        prev.setEnabled(i > 0);
        next.setEnabled(i < items.size() - 1);
    }

    /** 跳转链接打不开时提示地址可复制，不让按钮点了没反应。 */
    private void openNoticeLink(final String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
        } catch (Exception e) {
            LogUtil.w(TAG, "openNoticeLink failed: %s", e.getMessage());
            AcDialog.builder(this)
                    .setTitle(Strings.get(R.string.mainactivity_178))
                    .setMessage(url)
                    .setPositiveButton(Strings.get(R.string.mainactivity_179), new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            ClipUtil.copy(MainActivity.this, url);
                            AcToast.show(MainActivity.this, Strings.get(R.string.noticeactivity_010));
                        }
                    })
                    .show();
        }
    }

    /**
     * 首次启动的使用说明与免责声明。不可取消：必须明确选择「同意」或「退出」，
     * 避免用户随手点外部区域关掉后，后续再也没机会看到这段声明。
     */
    private void showFirstLaunchDialog() {
        // 文案与「关于 → 使用说明」共用一份，避免两处漂移
        String msg = HelpText.full();
        AcDialog.builder(this)
                .setTitle(Strings.get(R.string.mainactivity_003))
                .setMessage(msg)
                .setCancelable(false)
                .setPositiveButton(Strings.get(R.string.mainactivity_004), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        prefs.edit().putBoolean(KEY_FIRST_LAUNCH_DONE, true).apply();
                        LogUtil.i(TAG, "first launch accepted");
                        handlePromptIntent(getIntent());
                    }
                })
                .setNegativeButton(Strings.get(R.string.mainactivity_005), new DialogInterface.OnClickListener() {
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
        // 切会话遮罩的超时 / 脉冲回调随页面一起作废，避免销毁后还碰 View
        cancelConversationRenderTimers();
        conversationRenderState = 0;
        // 解绑外观刷新监听：它是单例上的回调，持有本 Activity 引用，不解会泄漏
        if (webTintListener != null) {
            AppearanceManager.getInstance(this).removeChangeListener(webTintListener);
            webTintListener = null;
        }
        AppearanceManager.getInstance(this).setChangeListener(null);
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
        // 兜底：onPause 之后可能又有保存入队，销毁前再清一次。
        // 这里给了 400ms 而不是默认 1.5s：onDestroy 在主线程上，而进程可能正在被系统
        // 回收，等满 1.5s 有触发 ANR 的风险；剩下的写盘由保存线程自己跑完，
        // 真的要丢也只会丢最后一句新增（onPause 已经存过一次）。
        conversationManager.flushSaves(400);
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
        bindClearButton();
        bindOfflineBanner();
        bindThinkingSpinner();
        refreshModelSpinner();
    }

    private void bindViews() {
        inputEditText = (EditText) findViewById(R.id.input_edit_text);
        sendButton = (ImageButton) findViewById(R.id.send_button);
        plusButton = (ImageButton) findViewById(R.id.plus_button);
        expandButton = (ImageButton) findViewById(R.id.expand_button);
        clearInputButton = (TextView) findViewById(R.id.clear_input_button);
        inputActionBar = findViewById(R.id.input_action_bar);
        attachmentScroll = findViewById(R.id.attachment_scroll);
        attachmentBar = (LinearLayout) findViewById(R.id.attachment_bar);
        modelSpinner = (Spinner) findViewById(R.id.model_spinner);
        thinkingSpinner = (Spinner) findViewById(R.id.thinking_spinner);
        usageBar = (TextView) findViewById(R.id.usage_bar);
        applyOrientationLayout(getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE);
    }

    /**
     * 横屏时收起「模型行」和「思考行」。
     *
     * <p>这台设备物理上是横屏笔，竖屏靠 {@code ↻} 转过来；用户点名要保留 {@code ↻}，
     * 所以方向不能锁死在 Manifest 上。横过来的时候高度只剩 258dp，两行各 48dp 就是 37% 的可用高，
     * 藏掉它们、把模型名留在标题栏，比压小字号划算。
     */
    private void applyOrientationLayout(boolean landscape) {
        View modelRow = findViewById(R.id.model_row);
        View thinkingRow = findViewById(R.id.thinking_row);
        if (modelRow != null) {
            modelRow.setVisibility(landscape ? View.GONE : View.VISIBLE);
        }
        if (thinkingRow != null) {
            thinkingRow.setVisibility(landscape ? View.GONE : View.VISIBLE);
        }
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

            @Override
            public void onPageFinished(WebView view, String url) {
                // 整页重建后强制重绘一次。硬件加速下合成层偶发不主动刷新，DOM 里
                // 内容齐全却没画出来，直到用户点开菜单/弹对话框触发窗口重绘才可见。
                view.invalidate();
                // 这里只当「可以开始确认了」的信号：同一次灌页可能来多次回调，
                // 旧页的回调也会迟到，只有读回页面自己带的版本号才能确定 DOM 是哪一版。
                probePageReady();
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description,
                                        String failingUrl) {
                LogUtil.w(TAG, "webview error: %d %s url=%s", errorCode, description, failingUrl);
                // 加载失败时页面可能没有 body 标记，探测会一直读不回来；
                // 交给 probe 的超时路径补灌一次，这里不做别的判断。
                probePageReady();
            }
        });

        // 外观设置：注册 WebView 侧样式刷新（复用 webViewEvalJs，按 SDK 分流 + 线程编组）。
        // ChangeListener 在任一外观项改动时触发，只替换 <style> 文本与 setTextZoom，
        // 绝不 loadDataWithBaseURL 重灌，因此不会打断流式输出、不重置滚动位置。
        final AppearanceManager am = AppearanceManager.getInstance(this);
        webRefresher = new WebViewRefresher(new WebViewRefresher.Host() {
            @Override
            public void evalJs(String js) {
                webViewEvalJs(js);
            }
            @Override
            public void applyZoom(int percent) {
                if (conversationWebView == null) return;
                conversationWebView.getSettings().setTextZoom(percent);
            }
        });
        // 后台直接跳过：样式替换虽然不重灌页面，但要现算整份主题 CSS（含 16 个 token
        // 的 resolve）再跨进程 eval 一次 JavaScript。逐属性编辑页拖动取色时每松一次手
        // 都会走一遍，堆在主线程队列里就是肉眼可见的卡顿。
        // 回前台由 onResume 补一次，不会漏样式。
        final WebViewRefresher refresher = webRefresher;
        am.setChangeListener(new AppearanceManager.ChangeListener() {
            public void onAppearanceChanged(AppearanceManager mm) {
                if (!uiVisible) return;
                refresher.onAppearanceChanged(mm);
            }
        });
        WebViewRefresher.applyInitialZoom(conversationWebView, am.getDisplayPercent());

        // 公式是 MathRenderer 预渲染的 PNG，字色在出图时就画死了，换 CSS 不可能变色；
        // MessageHtmlRenderer 的缓存又只按正文做 key。所以切颜色时必须「清缓存 + 重灌整页」，
        // 否则就是实机上的「公式颜色不立即变化，要切会话才变」。
        // 只比较颜色签名：显示大小/字号变化不重灌，避免白白丢滚动位置。
        tintSigApplied = tintSig(am);
        // 防御性：若 bindWebView 被二次调用，先摘掉旧监听，
        // 否则列表里会累积多个回调、同一次换色重灌多遍整页。
        if (webTintListener != null) am.removeChangeListener(webTintListener);
        webTintListener = new AppearanceManager.ChangeListener() {
            public void onAppearanceChanged(AppearanceManager mm) {
                String sig = tintSig(mm);
                if (sig.equals(tintSigApplied)) return;
                if (!uiVisible) {
                    // 后台：只记账，回前台再统一重灌（见 onResume）
                    tintSigPending = sig;
                    return;
                }
                if (isRequestInProgress.get()) {
                    // 流式输出中不能重灌整页，但**也不能把 lastSig 推进**：
                    // 推进了就等于「这次颜色变化已经处理过」，之后再没有事件来收尾，
                    // 公式会一直停在旧底色，直到手动切会话才重渲染
                    // （用户实测的「公式颜色要切会话才变」）。
                    // 记下来，等本轮结束再补一次。
                    tintSigPending = sig;
                    return;
                }
                tintSigApplied = sig;
                tintSigPending = null;
                MessageHtmlRenderer.clearRenderCache();
                refreshWebView();
            }
        };
        am.addChangeListener(webTintListener);
    }

    /** 只包含「会改变公式出图颜色」的项，避免显示大小变化也被当成颜色变更。 */
    private static String tintSig(AppearanceManager m) {
        // 统一由 AppearanceManager 计算：MessageHtmlRenderer 也用它做缓存自失效，
        // 两处各写一份迟早会漂移。
        return m.colorSignature();
    }

    private void bindQueue() {
        queueBar = findViewById(R.id.queue_bar);
        queueBarText = (TextView) findViewById(R.id.queue_bar_text);
        queue = new MessageQueue(this, new MessageQueue.Host() {
            public boolean isRequestInFlight() { return isRequestInProgress.get(); }
            public void sendQueued(String text, List<AttachmentText.File> files) {
                sendMessageText(text, files);
            }
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
    /** 有文字或有附件都能发：只挂一个文件问「这段报错什么意思」是常见用法。 */
    private boolean canSend() {
        return hasInputText() || !pendingAttachments.isEmpty();
    }

    private boolean isStopState() {
        return isRequestInProgress.get() && !canSend();
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
        boolean hasAttachment = !pendingAttachments.isEmpty();
        boolean stopping = isRequestInProgress.get() && !canSend();
        sendButton.setImageResource(stopping ? R.drawable.ic_stop : R.drawable.ic_send);
        sendButton.setContentDescription(getString(
                stopping ? R.string.stop_generating : R.string.send));
        // 空闲且既没输入也没附件时禁用，避免「点了没反应」的困惑
        sendButton.setEnabled(stopping || canSend());
        // 动作栏整条显隐：空输入时彻底隐藏，输入框拿回全部宽度——这也是把清空/全屏
        // 从输入行里挪出来的原因。两个子按钮再各自控制，避免「有附件没文字」时
        // 摆着一个点了没意义的全屏键。
        boolean hasAnything = hasText || hasAttachment;
        if (inputActionBar != null) {
            inputActionBar.setVisibility(hasAnything ? View.VISIBLE : View.GONE);
        }
        if (expandButton != null) {
            expandButton.setVisibility(hasText ? View.VISIBLE : View.GONE);
        }
        if (clearInputButton != null) {
            clearInputButton.setVisibility(hasAnything ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * 请求状态的唯一写入口。直接改 {@link #isRequestInProgress} 会让按钮停留
     * 在上一个状态（例如停止后仍显示方块），所以统一走这里带上 UI 刷新。
     */
    private void setRequestInProgress(boolean value) {
        isRequestInProgress.set(value);
        refreshSendButton();
        if (!value) flushPendingTint();
    }

    /**
     * 本轮结束后补做「流式期间被推迟」的颜色重灌。
     *
     * <p>没有这一步，切深浅色正好赶上模型输出时，公式会停在旧颜色上，
     * 一直要手动切会话才更新。
     */
    private void flushPendingTint() {
        final String sig = tintSigPending;
        if (sig == null) return;
        // 不在前台就别重灌：pending 留着，onResume 时会补。
        // 否则请求恰好在用户切走后结束，会在后台白白重建一次整页。
        if (!uiVisible) return;
        tintSigPending = null;
        tintSigApplied = sig;
        Runnable task = new Runnable() {
            public void run() {
                MessageHtmlRenderer.clearRenderCache();
                refreshWebView();
            }
        };
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) task.run();
        else runOnUiThread(task);
    }

    private void showAttachMenu() {
        // PopupMenu 是独立 Window，弹层底板由 context 主题的 panelBackground 决定，
        // 跟 Activity 里刷了多少 View 无关 —— 深色下按加号弹出一片白就是这个原因。
        // 换一个深色主题的 ContextThemeWrapper 去构造，弹层才是深底浅字。
        Context menuCtx = this;
        if (AppearanceManager.getInstance(this).isDark()) {
            menuCtx = new android.view.ContextThemeWrapper(this, android.R.style.Theme_Holo);
        }
        PopupMenu menu = new PopupMenu(menuCtx, plusButton);
        menu.getMenu().add(0, MENU_ATTACH_TEXT, 0, R.string.upload_text_file);
        menu.getMenu().add(0, MENU_ATTACH_IMAGE, 1, R.string.upload_image);
        menu.getMenu().add(0, MENU_ATTACH_CAMERA, 2, R.string.take_photo);
        menu.getMenu().add(0, MENU_ATTACH_IMAGE_URL, 3, R.string.upload_image_url);
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            public boolean onMenuItemClick(MenuItem item) {
                if (item.getItemId() == MENU_ATTACH_TEXT) {
                    pickTextFile();
                    return true;
                }
                if (item.getItemId() == MENU_ATTACH_IMAGE) {
                    pickImageFile();
                    return true;
                }
                if (item.getItemId() == MENU_ATTACH_CAMERA) {
                    takePhoto();
                    return true;
                }
                if (item.getItemId() == MENU_ATTACH_IMAGE_URL) {
                    showImageUrlDialog();
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
        // 不能写死 text/*：.json 在系统 MimeTypeMap 里是 application/json，
        // .actheme / .log / .env / 无扩展名文件更是 application/octet-stream，
        // 用 text/* 过滤时它们在选择器里是灰的，用户根本点不进去 ——
        // 「JSON、actheme 以及其他文件无法作为文本附件添加」就是这个原因。
        // 放开成 */*，改在导入时判定内容是不是文本，二进制文件给明确提示。
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(intent, getString(R.string.upload_text_file)),
                    REQUEST_PICK_TEXT);
        } catch (Exception e) {
            LogUtil.w(TAG, "pickTextFile failed: %s", e.getMessage());
            AcToast.show(this, Strings.get(R.string.mainactivity_006));
        }
    }

    /**
     * 图片单独走一条路：不做文本解码、不走 token 估算，只需要「拷进来 + 缩略图」。
     * 混进文本通道会在 {@code new String(bytes, "UTF-8")} 上把 JPEG 解成一串替换字符。
     */
    private void pickImageFile() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(intent, getString(R.string.upload_image)),
                    REQUEST_PICK_IMAGE);
        } catch (Exception e) {
            LogUtil.w(TAG, "pickImageFile failed: %s", e.getMessage());
            AcToast.show(this, Strings.get(R.string.mainactivity_007));
        }
    }

    /**
     * 拍照。
     *
     * <p>用 {@code ACTION_IMAGE_CAPTURE} + {@code EXTRA_OUTPUT} 存原图，而不是读
     * {@code data} 里那张缩略图：某些 ROM 在 data 里回的是 160×120 的预览图，
     * 拍完一看全是马赛克。
     *
     * <p><b>输出必须走 {@code content://}（{@link CameraOutputProvider}）</b>：
     * 相机是另一个 uid，{@code file://} 指向 app 私有 cache 目录它根本没有写权限，
     * 文件永远不会出现，每次都会掉回 data 里的预览图——这才是「拍照总是模糊」的
     * 根因，不是某个 ROM 的偶发行为。项目不带 support 库，所以自己实现了一个只
     * 暴露单个输出文件的极小 Provider，而不是引入 androidx 的 FileProvider。
     */
    private void takePhoto() {
        Intent intent = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
        if (intent.resolveActivity(getPackageManager()) == null) {
            AcToast.show(this, Strings.get(R.string.mainactivity_008));
            return;
        }
        try {
            File dir = new File(getCacheDir(), "camera");
            if (!dir.exists() && !dir.mkdirs()) throw new Exception(Strings.get(R.string.mainactivity_009));
            sweepOldCameraFiles(dir);
            cameraOutputFile = new File(dir, "shot-" + System.currentTimeMillis() + ".jpg");
            Uri outUri = CameraOutputProvider.uriFor(cameraOutputFile.getName());
            intent.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, outUri);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            // 部分相机只从 ClipData 取临时授权，不认 EXTRA_OUTPUT 上的 flag
            intent.setClipData(ClipData.newRawUri("", outUri));
            startActivityForResult(intent, REQUEST_TAKE_PHOTO);
        } catch (Exception e) {
            LogUtil.w(TAG, "takePhoto failed: %s", e.getMessage());
            AcToast.show(this, Strings.get(R.string.mainactivity_010));
        }
    }

    /** 少数相机忽略 EXTRA_OUTPUT，只在 data 里回一张缩略图；有总比没有强。 */
    private Bitmap extractInlinePhoto(Intent data) {
        if (data == null || data.getExtras() == null) return null;
        try {
            Object o = data.getExtras().get("data");
            return o instanceof Bitmap ? (Bitmap) o : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 把 data 里的缩略图落到私有缓存，后续仍走同一条裁剪链路。 */
    private File saveInlinePhoto(Bitmap bmp) {
        FileOutputStream fos = null;
        try {
            File dir = new File(getCacheDir(), "camera");
            if (!dir.exists() && !dir.mkdirs()) return null;
            File out = new File(dir, "shot-" + System.currentTimeMillis() + ".jpg");
            fos = new FileOutputStream(out);
            bmp.compress(Bitmap.CompressFormat.JPEG, 92, fos);
            fos.flush();
            cameraOutputFile = out;
            return out.length() > 0 ? out : null;
        } catch (Exception e) {
            LogUtil.w(TAG, "saveInlinePhoto failed: %s", e.getMessage());
            return null;
        } finally {
            if (fos != null) { try { fos.close(); } catch (Exception ignored) { } }
        }
    }

    /** 相机与裁剪的临时文件只删一天前的：正在用的那份时间戳是新的。 */
    private void sweepOldCameraFiles(File dir) {
        try {
            File[] files = dir.listFiles();
            if (files == null) return;
            long cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000;
            for (File f : files) {
                if (f.lastModified() < cutoff && f.delete()) {
                    LogUtil.d(TAG, "swept camera temp: %s", f.getName());
                }
            }
        } catch (Exception e) {
            LogUtil.w(TAG, "sweepOldCameraFiles failed: %s", e.getMessage());
        }
    }

    /** 相册选图：先拷进 cache 再进裁剪页（内容 Uri 的读权限不跨 Activity 传递）。 */
    private void stageImageForCrop(final Uri picked) {
        new Thread(new Runnable() {
            public void run() {
                File staged = null;
                String err = null;
                String name = null;
                try {
                    name = queryDisplayName(picked);
                    if (!FileKinds.looksLikeImage(name, getContentResolver().getType(picked))) {
                        throw new Exception(Strings.get(R.string.mainactivity_011));
                    }
                    staged = AttachmentFiles.stageToCache(MainActivity.this, picked,
                            AttachmentFiles.maxImageBytes());
                } catch (Exception e) {
                    err = e.getMessage();
                } catch (OutOfMemoryError e) {
                    err = Strings.get(R.string.mainactivity_012);
                }
                final File f = staged;
                final String error = err;
                final String displayName = name;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (f == null) {
                            LogUtil.w(TAG, "stageImageForCrop failed: %s", error);
                            AcToast.showLong(MainActivity.this, Strings.get(R.string.mainactivity_013,
                                    error == null ? Strings.get(R.string.mainactivity_014) : error));
                            return;
                        }
                        startCrop(f.getAbsolutePath(), displayName);
                    }
                });
            }
        }, "aichat-stage-image").start();
    }

    private void startCrop(String srcPath, String name) {
        Intent intent = new Intent(this, CropActivity.class);
        intent.putExtra(CropActivity.EXTRA_SRC_PATH, srcPath);
        intent.putExtra(CropActivity.EXTRA_NAME, name);
        try {
            startActivityForResult(intent, REQUEST_CROP);
        } catch (Exception e) {
            LogUtil.w(TAG, "startCrop failed: %s", e.getMessage());
            AcToast.show(this, Strings.get(R.string.mainactivity_015));
        }
    }

    /** 图片链接：填完直接下载导入，下载与解码都在后台。 */
    private void showImageUrlDialog() {
        final EditText input = new EditText(this);
        input.setHint(R.string.image_url_hint);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        AcDialog.builder(this)
                .setTitle(R.string.upload_image_url)
                .setView(input)
                .setPositiveButton(Strings.get(R.string.mainactivity_016), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String url = input.getText().toString().trim();
                        if (url.length() == 0) {
                            AcToast.show(MainActivity.this, Strings.get(R.string.mainactivity_017));
                            return;
                        }
                        importImageUrl(url);
                    }
                })
                .setNegativeButton(Strings.get(R.string.mainactivity_018), null)
                .show();
    }

    /**
     * 下载图片并挂成附件。
     *
     * <p>不在主线程做任何事：DNS + TLS + 下载 3 MB 在 3G 上是十几秒，
     * 放主线程直接 ANR。
     */
    private void importImageUrl(final String url) {
        AcToast.show(this, Strings.get(R.string.mainactivity_019));
        new Thread(new Runnable() {
            public void run() {
                AttachmentText.File file = null;
                String err = null;
                try {
                    file = AttachmentFiles.importUrl(MainActivity.this, url,
                            AttachmentFiles.maxImageBytes());
                } catch (Exception e) {
                    err = e.getMessage();
                } catch (OutOfMemoryError e) {
                    err = Strings.get(R.string.mainactivity_020);
                }
                final AttachmentText.File loaded = file;
                final String error = err;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (loaded == null) {
                            LogUtil.w(TAG, "importImageUrl failed: %s", error);
                            AcToast.showLong(MainActivity.this, Strings.get(R.string.mainactivity_021,
                                    error == null ? Strings.get(R.string.mainactivity_014) : error));
                            return;
                        }
                        // 链接下载的图片同样进裁剪页，与拍照/相册统一一条路
                        if (loaded.isImage() && loaded.path.length() > 0) {
                            startCrop(loaded.path, loaded.name);
                        } else {
                            addAttachment(loaded);
                        }
                    }
                });
            }
        }, "aichat-import-url").start();
    }

    private void openFullscreenInput() {
        Intent intent = new Intent(this, FullscreenInputActivity.class);
        intent.putExtra(FullscreenInputActivity.EXTRA_TEXT, inputEditText.getText().toString());
        startActivityForResult(intent, REQUEST_FULLSCREEN);
    }

    /** 后台读文件，回 UI 线程插到光标处；不覆盖用户已经打的内容。 */
    /**
     * 读文件 → 挂成待发送附件。
     *
     * <p>刻意<b>不</b>往输入框里插入正文：一份几十 KB 的日志会把输入框撑到无法编辑，
     * 用户写的问题被挤到屏幕外，想删还得手工选中。正文随消息一起存进
     * {@link Message#attachments}，气泡下方画成独立卡片（文件名 + 大小 + token 估算）。
     */
    private void importTextFile(final Uri uri) {
        new Thread(new Runnable() {
            public void run() {
                LoadedText text = null;
                String err = null;
                String name = null;
                try {
                    name = queryDisplayName(uri);
                    text = readTextLimited(uri, MAX_IMPORT_BYTES);
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final LoadedText loaded = text;
                final String error = err;
                final String fileName = name;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (loaded == null) {
                            LogUtil.w(TAG, "importTextFile failed: %s", error);
                            AcToast.showLong(MainActivity.this, Strings.get(R.string.mainactivity_023,
                                    error == null ? Strings.get(R.string.mainactivity_014) : error));
                            return;
                        }
                        addAttachment(fileName, loaded.text, loaded.bytes);
                    }
                });
            }
        }, "aichat-import-text").start();
    }

    /**
     * 读图片 → 拷进私有目录 → 挂成待发送附件。
     *
     * <p>拷贝与缩略图都在后台线程做：一张 4000×3000 的图解码采样要几十毫秒到几百毫秒，
     * 放在主线程就是一次必然的掉帧。
     */
    private void importImageFile(final Uri uri) {
        importImageFile(uri, null);
    }

    /**
     * @param preferredName 已知的显示名（裁剪/拍照的结果文件名是时间戳，直接展示很难看）；
     *                      null 时从 Uri 查
     */
    private void importImageFile(final Uri uri, final String preferredName) {
        new Thread(new Runnable() {
            public void run() {
                AttachmentText.File file = null;
                String err = null;
                try {
                    String name = preferredName != null && preferredName.length() > 0
                            ? preferredName : queryDisplayName(uri);
                    // 有些 ROM 的选择器不认 type 过滤，把 .txt 也放回来；先按扩展名/MIME 挡一道
                    if (!FileKinds.looksLikeImage(name, getContentResolver().getType(uri))) {
                        throw new Exception(Strings.get(R.string.mainactivity_025));
                    }
                    file = AttachmentFiles.importImage(MainActivity.this, uri, name,
                            AttachmentFiles.maxImageBytes());
                } catch (Exception e) {
                    err = e.getMessage();
                } catch (OutOfMemoryError e) {
                    // 超大图解码失败是这种设备上的常见结局，必须兜住，否则直接崩
                    err = Strings.get(R.string.mainactivity_026);
                    LogUtil.w(TAG, "importImageFile OOM: %s", e.getMessage());
                }
                final AttachmentText.File loaded = file;
                final String error = err;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (loaded == null) {
                            LogUtil.w(TAG, "importImageFile failed: %s", error);
                            AcToast.showLong(MainActivity.this, Strings.get(R.string.mainactivity_027,
                                    error == null ? Strings.get(R.string.mainactivity_014) : error));
                            return;
                        }
                        addAttachment(loaded);
                    }
                });
            }
        }, "aichat-import-image").start();
    }

    /** 取文件显示名；查询失败时退回 URI 末段，至少比「未命名」强。 */
    private String queryDisplayName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{"_display_name"}, null, null, null);
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && n.length() > 0) return n;
            }
        } catch (Exception e) {
            LogUtil.w(TAG, "queryDisplayName failed: %s", e.getMessage());
        } finally {
            if (c != null) {
                try { c.close(); } catch (Exception ignored) { }
            }
        }
        String p = uri == null ? null : uri.getLastPathSegment();
        if (p == null || p.length() == 0) return Strings.get(R.string.mainactivity_029);
        int slash = p.lastIndexOf('/');
        return slash >= 0 ? p.substring(slash + 1) : p;
    }

    /** 加入待发送队列（只存内存，不落盘——附件是这一次输入的一部分）。 */
    private void addAttachment(String name, String content, int bytes) {
        addAttachment(new AttachmentText.File(name, content, bytes));
    }

    private void addAttachment(AttachmentText.File f) {
        // 上限来自设置（Limits），不再写死：不同机型内存差一个数量级
        if (pendingAttachments.size() >= Limits.maxAttachments) {
            AcToast.show(this, Strings.get(R.string.mainactivity_030, Limits.maxAttachments));
            return;
        }
        int total = f.chars();
        for (int i = 0; i < pendingAttachments.size(); i++) {
            total += pendingAttachments.get(i).chars();
        }
        if (total > Limits.attachTotalChars) {
            AcToast.showLong(this, Strings.get(R.string.mainactivity_167, Limits.attachTotalChars / 10000));
            return;
        }
        pendingAttachments.add(f);
        refreshAttachmentBar();
        AcToast.show(this, Strings.get(R.string.mainactivity_034, f.name, AttachmentText.sizeAndTokens(f)));
    }

    /** 重建附件条。条目少（≤5），每次全量重建比维护增删便宜。 */
    private void refreshAttachmentBar() {
        if (attachmentBar == null || attachmentScroll == null) return;
        attachmentBar.removeAllViews();
        boolean empty = pendingAttachments.isEmpty();
        attachmentScroll.setVisibility(empty ? View.GONE : View.VISIBLE);
        // 附件也是发送条件之一：加/删之后按钮的可用状态要立刻跟上
        refreshSendButton();
        if (empty) return;

        int pad10 = dp(10);
        int pad4 = dp(4);
        int gap = dp(6);
        // 第二行用 Span 着色，setTextColor 覆盖不到它，必须在这里按当前主题取色
        int metaColor = getResources().getColor(R.color.text_secondary);
        try {
            AppearanceManager am = AppearanceManager.getInstance(this);
            if (!am.isAppearanceDefault()) {
                metaColor = am.getEffectiveColor(ThemeTokens.AC_TEXT_SECONDARY, am.isDark());
            }
        } catch (Throwable ignored) {
        }
        for (int i = 0; i < pendingAttachments.size(); i++) {
            final int index = i;
            AttachmentText.File a = pendingAttachments.get(i);
            // 两行：文件名 + 大小/token。第二行必须小一号并压暗，否则视觉上
            // 会跟文件名抢注意力，一眼看过去全是数字。
            String line1 = AttachmentText.shortName(a.name) + "  ×";
            String line2 = AttachmentText.sizeAndTokens(a);
            SpannableString label = new SpannableString(line1 + "\n" + line2);
            int metaFrom = line1.length() + 1;
            label.setSpan(new RelativeSizeSpan(0.84f), metaFrom, label.length(),
                    Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
            label.setSpan(new ForegroundColorSpan(metaColor),
                    metaFrom, label.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);

            TextView chip = new TextView(this);
            chip.setText(label);
            chip.setTextSize(12);
            chip.setTextColor(getResources().getColor(R.color.text_primary));
            chip.setBackgroundResource(R.drawable.bg_attachment_chip);
            chip.setPadding(pad10, pad4, pad10, pad4);
            chip.setSingleLine(false);
            chip.setMaxLines(2);
            chip.setContentDescription(Strings.get(R.string.mainactivity_035, a.name));
            // 图片附件在输入框上方直接给缩略图：thumb 是导入时生成的 data URI，
            // 解码失败就退回原来的纯文字卡片，绝不让整条附件挂掉。
            if (a.isImage() && a.thumb.length() > 0) {
                Bitmap thumbBmp = decodeThumb(a.thumb);
                if (thumbBmp != null) {
                    BitmapDrawable d = new BitmapDrawable(getResources(), thumbBmp);
                    int side = dp(36);
                    d.setBounds(0, 0, side, side);
                    chip.setCompoundDrawables(d, null, null, null);
                    chip.setCompoundDrawablePadding(dp(6));
                }
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = gap;
            chip.setLayoutParams(lp);
            chip.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (index < pendingAttachments.size()) {
                        AttachmentText.File gone = pendingAttachments.remove(index);
                        // 离开草稿就不再受「孤儿清理」保护：否则用户移除后又
                        // 去存储页清理，会被误判成「还在用」而清不掉。
                        if (gone.path != null) AttachmentFiles.unmarkPending(gone.path);
                        LogUtil.d(TAG, "attachment removed: %s", gone.name);
                        refreshAttachmentBar();
                    }
                }
            });
            attachmentBar.addView(chip);
        }
        // chip 是代码 new 出来的，不走 LayoutInflater，inflate 钩子覆盖不到。
        // 这里补一次即时着色：深色下浅蓝底会被刷成表面色，文字也跟着变亮。
        ThemeRecolor.applyTree(attachmentBar, this);
    }

    /** 附件条缩略图：base64 data URI → Bitmap；失败返回 null，文字卡片照常显示。 */
    private Bitmap decodeThumb(String dataUri) {
        try {
            int comma = dataUri.indexOf(',');
            if (comma < 0) return null;
            byte[] bytes = Base64.decode(dataUri.substring(comma + 1), Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception e) {
            LogUtil.w(TAG, "decodeThumb failed: %s", e.getMessage());
            return null;
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void clearAttachments() {
        if (pendingAttachments.isEmpty()) return;
        for (int i = 0; i < pendingAttachments.size(); i++) {
            AttachmentText.File f = pendingAttachments.get(i);
            if (f.path != null) AttachmentFiles.unmarkPending(f.path);
        }
        pendingAttachments.clear();
        refreshAttachmentBar();
    }

    /**
     * 读取文本文件，超过上限直接失败而不是截断——截一半的代码贴进对话比报错更难排查。
     * UTF-8 解出来要是出现替换字符（U+FFFD），说明大概率是 GBK，再试一次。
     */
    /** 读文件的结果：正文 + 原始字节数（附件条要显示真实大小，不能用字数糊弄）。 */
    private static final class LoadedText {
        final String text;
        final int bytes;

        LoadedText(String text, int bytes) {
            this.text = text;
            this.bytes = bytes;
        }
    }

    private LoadedText readTextLimited(Uri uri, int maxBytes) throws Exception {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) throw new Exception(Strings.get(R.string.mainactivity_036));
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > maxBytes) {
                    throw new Exception(Strings.get(R.string.mainactivity_037, AttachmentText.formatBytes(maxBytes)));
                }
                bos.write(buf, 0, n);
            }
            byte[] data = bos.toByteArray();
            if (looksBinary(data)) {
                throw new Exception(Strings.get(R.string.mainactivity_038));
            }
            String utf8 = new String(data, "UTF-8");
            if (utf8.indexOf('\uFFFD') < 0) return new LoadedText(utf8, total);
            String gbk = new String(data, "GBK");
            LogUtil.d(TAG, "readTextLimited: utf8 had replacement chars, fell back to GBK");
            return new LoadedText(gbk, total);
        } finally {
            try { in.close(); } catch (Exception ignored) { }
        }
    }

    /**
     * 粗判二进制：文本文件（UTF-8 / GBK / Latin-1）里不会出现 NUL 字节，
     * zip / png / apk / so 这类容器和资源文件前几 KB 一定有。
     *
     * <p>选择器放开成 {@code *&#47;*} 之后必须有一道这样的闸门，否则用户点错一个
     * 二进制文件，会得到一整屏 U+FFFD 替换字符，既占 token 又完全没法用。
     * 只扫前 8KB：判据在文件头，没必要为 20MB 的文件全量走一遍。
     */
    private static boolean looksBinary(byte[] data) {
        if (data == null) return false;
        int n = Math.min(data.length, 8192);
        for (int i = 0; i < n; i++) {
            if (data[i] == 0) return true;
        }
        return false;
    }

    /**
     * 回到底部：单击滚到底。
     *
     * <p>原来这里挂着「点击滚到底 / 长按 600ms 清空输入框」，两个毫不相关的
     * 功能挤在同一个 36dp 的圆里，长按还是一次性不可撤销的破坏性操作。
     * 清空已经挪到输入栏的独立按钮上（带确认框），这里只做一件事。
     */
    private void bindScrollButton() {
        findViewById(R.id.scroll_to_bottom_button).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                LogUtil.d(TAG, "click: scroll_to_bottom");
                conversationWebView.loadUrl("javascript:smartScrollToBottom(true)");
            }
        });
        // 「直接显示」：跳过本次逐字渐显。只对正在渐显的那一条回复有效，
        // 旧气泡上的残留点击不会动到新回复。
        findViewById(R.id.smooth_reveal_button).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                LogUtil.d(TAG, "click: smooth reveal");
                revealCurrentSmooth();
            }
        });
    }

    // ---- 无网络横幅 ----
    // 只在「无网」时出现，有网就收起。不能做成常驻控件把消息区往下挤：
    // 4.4 词典笔屏幕本来就矮，多一行之后就只剩三四行正文。
    private BroadcastReceiver netReceiver;
    /** 横幅当前是否可见。初值在 initOfflineBanner 里按真实状态校正，不写死。 */
    private boolean offlineShown;
    /** 断网轮询：部分定制 ROM 不发 CONNECTIVITY_ACTION，只能低频自查兜底。 */
    private final Runnable offlineTick = new Runnable() {
        public void run() {
            applyOfflineState();
            handler.removeCallbacks(this);
            // 2.5 秒一次：断网是需要尽快反馈的状态（用户点了发送才发现发不出去最烦），
            // 而读一次 ConnectivityManager 的开销可以忽略，不必拉到 10 秒。
            handler.postDelayed(this, 2500);
        }
    };

    private void bindOfflineBanner() {
        View banner = findViewById(R.id.offline_banner);
        if (banner == null) return;
        View.OnClickListener go = new View.OnClickListener() {
            public void onClick(View v) {
                LogUtil.d(TAG, "click: offline banner -> settings home");
                openNetworkSettings();
            }
        };
        banner.setOnClickListener(go);
        View action = findViewById(R.id.offline_banner_action);
        if (action != null) action.setOnClickListener(go);
    }

    /**
     * 进入界面时开始盯网络，离开时停掉。
     *
     * <p>初始化把 offlineShown 直接对齐真实状态（含横幅可见性），不当作「状态变化」，
     * 否则一启动就会弹一次提示——用户明明只是在断网状态下打开应用看看历史消息。
     */
    private void startOfflineWatch() {
        View banner = findViewById(R.id.offline_banner);
        if (banner == null) return;
        offlineShown = !NetworkState.isOnline(this);
        banner.setVisibility(offlineShown ? View.VISIBLE : View.GONE);
        // 一进来就断网也提示一次：横幅在顶部，用户未必注意到，
        // 而「点了发送才发现发不出去」才是真的浪费时间。
        if (offlineShown) AcToast.show(this, Strings.get(R.string.offline_toast));
        if (netReceiver == null) {
            netReceiver = NetworkState.listen(this, new Runnable() {
                public void run() {
                    handler.post(new Runnable() {
                        public void run() {
                            applyOfflineState();
                        }
                    });
                }
            });
        }
        handler.removeCallbacks(offlineTick);
        handler.postDelayed(offlineTick, 2500);
    }

    private void stopOfflineWatch() {
        handler.removeCallbacks(offlineTick);
        if (netReceiver != null) {
            NetworkState.unlisten(this, netReceiver);
            netReceiver = null;
        }
    }

    /**
     * 打开系统设置的首页。
     *
     * <p>只跳 {@code ACTION_SETTINGS}：以前优先跳 {@code ACTION_WIRELESS_SETTINGS}，
     * 在词典笔上会直接落到 WiFi 列表，用户从应用点「去设置」后找不到设置首页，
     * 也回不到别的设置项。排序上的「少点两层」不值得牺牲可预期性。
     * 个别 ROM 没有设置首页，退到无线和网络，再退到应用详情页，保证点击一定有反应。
     */
    private void openNetworkSettings() {
        String[] actions = {
                android.provider.Settings.ACTION_SETTINGS,
                android.provider.Settings.ACTION_WIRELESS_SETTINGS,
        };
        for (String a : actions) {
            try {
                Intent it = new Intent(a);
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(it);
                return;
            } catch (Throwable t) {
                LogUtil.w(TAG, "open settings %s failed: %s", a, t);
            }
        }
        AcToast.show(this, Strings.get(R.string.offline_open_settings_failed));
    }

    /** 重算一次并更新横幅。只在「有网 → 无网」这一刻提示，轮询本身不重复弹。 */
    private void applyOfflineState() {
        View banner = findViewById(R.id.offline_banner);
        if (banner == null) return;
        boolean online = NetworkState.isOnline(this);
        if (online == !offlineShown) return; // 状态没变，不动 UI
        offlineShown = !online;
        banner.setVisibility(online ? View.GONE : View.VISIBLE);
        LogUtil.d(TAG, "offline banner: %s", offlineShown ? "show" : "hide");
        if (!offlineShown) return;
        // 不再做 5 分钟节流：断网就该立刻告诉我，而不是「上次说过，这次先不说」。
        // 2.5 秒轮询也不会变成骚扰——只有状态真的翻转才走到这里。
        AcToast.show(this, Strings.get(R.string.offline_toast));
    }

    /**
     * 清空输入：文字和待发附件一起清，先弹确认框。
     *
     * <p>不做「点了就清」：输入框里可能是刚粘过来的几千字报错，清空是不可撤销的，
     * 误触一次就要重新找素材。确认框里明确写清会连附件一起清掉。
     */
    private void bindClearButton() {
        if (clearInputButton == null) return;
        clearInputButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                final boolean hasAttachment = !pendingAttachments.isEmpty();
                LogUtil.d(TAG, "click: clear_input, attachment=%s", hasAttachment);
                AcDialog.builder(MainActivity.this)
                        .setTitle(Strings.get(R.string.mainactivity_040))
                        .setMessage(hasAttachment
                                ? Strings.get(R.string.mainactivity_041, pendingAttachments.size())
                                : Strings.get(R.string.mainactivity_043))
                        .setPositiveButton(Strings.get(R.string.mainactivity_044), new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                inputEditText.setText("");
                                clearAttachments();
                                refreshSendButton();
                            }
                        })
                        .setNegativeButton(Strings.get(R.string.mainactivity_045), null)
                        .show();
            }
        });
    }

    /**
     * 思考档位下拉：按当前模型的实测档位生成选项。
     *
     * <p>以前固定四项「关 / 低 / 中 / 高」，探测出模型只支持「高」也照样列出其余三项，
     * 选了之后请求侧再悄悄落到「高」——界面和实际下发永远不一致。现在列表跟模型走：
     * 探测过的只列实测档位，未探测的列六档全集。
     *
     * <p>切换模型时整表重建并复位选中项，所以模型下拉变化后要重新调用本方法。
     */
    private void bindThinkingSpinner() {
        thinkingChoices.clear();
        List<String> labels = new ArrayList<String>();
        ModelInfo model = currentModelInfo();
        List<String> available = (model != null && model.levelsProbed())
                ? model.levelList() : new ArrayList<String>();
        thinkingChoices.addAll(ThinkingLevels.choices(available));
        for (String c : thinkingChoices) labels.add(ThinkingLevels.pickLabel(c));

        ArrayAdapter<String> thinkingAdapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, labels);
        thinkingAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        userPicked = false;
        thinkingSpinner.setAdapter(thinkingAdapter);

        String level = configManager.getThinkingLevel();
        int levelPos = ThinkingLevels.indexIn(thinkingChoices, level);
        // 先复位再去 setSelection：setSelection 会同步触发一次 onItemSelected，
        // 此时的 pos 还是旧的，不能当作用户操作写盘（见 refreshModelSpinner 的复位）
        thinkingSpinner.setSelection(levelPos);
        LogUtil.d(TAG, "thinking levels for model=%s: %s (global=%s -> pos=%d)",
                model == null ? "-" : model.name, thinkingChoices, level, levelPos);

        thinkingSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (!userPicked) return;
                if (pos < 0 || pos >= thinkingChoices.size()) return;
                String picked = thinkingChoices.get(pos);
                LogUtil.d(TAG, "thinking level selected: pos=%d -> %s", pos, picked);
                configManager.setThinkingLevel(picked);
                configManager.save();
            }

            public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    /** 当前选中的模型配置；找不到返回 null。 */
    private ModelInfo currentModelInfo() {
        if (currentModel == null) return null;
        for (ModelInfo m : availableModels) {
            if (m.name.equals(currentModel)
                    && (currentProvider == null || currentProvider.equals(m.provider))) {
                return m;
            }
        }
        return configManager.findModel(currentModel);
    }

    private void refreshModelSpinner() {
        LogUtil.d(TAG, "refreshModelSpinner");
        // 内存字段在 Activity 重建 / 进程被回收后为空，先读持久化值。
        // 老设备关屏再亮屏很容易触发重建，只靠字段就会「切回默认模型」。
        if (currentModel == null && prefs != null) {
            currentModel = prefs.getString(KEY_SELECTED_MODEL, null);
            if (currentProvider == null) {
                currentProvider = prefs.getString(KEY_SELECTED_PROVIDER, null);
            }
            if (currentModel != null) {
                LogUtil.d(TAG, "restored selected model from prefs: %s@%s",
                        currentModel, currentProvider);
            }
        }
        availableModels = configManager.getModels();
        List<String> names = new ArrayList<String>();
        for (ModelInfo m : availableModels) {
            // 日志用显示名：排查时看到的就是用户界面上的名字，对得上号
            names.add(m.label() + " (" + configManager.providerLabel(m.provider) + ")");
        }
        // 每一项两行：第一行模型名（16/14/12sp 降级 + 中间省略），第二行服务商（固定 10sp）。
        // 服务商必须常显：deepseek-flash 在 DeepSeek 与商汤各有一份，只显示名字分不清。
        ModelSpinnerAdapter adapter = new ModelSpinnerAdapter(this, availableModels);
        modelSpinner.setAdapter(adapter);
        LogUtil.d(TAG, "model spinner items=%d: %s", names.size(), names);

        // Preserve the user's selection: onResume() refreshes the spinner after
        // returning from settings/history/scan — re-selecting the default here
        // reset the user's manual choice. Keep currentModel when it still
        // exists; fall back to default on first init or if it was removed.
        int targetPos = -1;
        if (currentModel != null) {
            // 首选「ID + 提供商」全匹配；同名模型分属两个提供商时不会串到另一条
            for (int i = 0; i < availableModels.size(); i++) {
                ModelInfo m = availableModels.get(i);
                if (m.name.equals(currentModel)
                        && (currentProvider == null || currentProvider.equals(m.provider))) {
                    targetPos = i;
                    break;
                }
            }
            // 老配置没有 provider 段：退回纯 ID 匹配
            if (targetPos < 0 && currentProvider == null) {
                for (int i = 0; i < availableModels.size(); i++) {
                    if (availableModels.get(i).name.equals(currentModel)) {
                        targetPos = i;
                        break;
                    }
                }
            }
        }
        if (targetPos < 0) {
            // 默认模型存的是唯一键，按对象比较而不是按模型 ID 字符串比
            for (int i = 0; i < availableModels.size(); i++) {
                if (configManager.isDefaultModel(availableModels.get(i))) {
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
            currentProvider = model.provider;
            if (prefs != null) {
                prefs.edit()
                        .putString(KEY_SELECTED_MODEL, currentModel)
                        .putString(KEY_SELECTED_PROVIDER, currentProvider)
                        .apply();
            }
            LogUtil.i(TAG, "selectModel: pos=%d model=%s provider=%s url=%s keyLen=%d",
                    pos, model.name, provider.name, provider.apiUrl,
                    provider.apiKey == null ? 0 : provider.apiKey.length());
        } else {
            LogUtil.w(TAG, "selectModel: provider '%s' not found for model '%s'", model.provider, model.name);
        }
        // 档位选项跟着模型走：换模型后按新模型的实测档位重建，否则会留着上一个模型的选项
        if (thinkingSpinner != null) bindThinkingSpinner();
        // 探测在别的页面完成时，这里的档位列表还是旧的；切回本页要按最新结论重建
        if (thinkingSpinner != null && probedRev != configManager.probeRevision()) {
            LogUtil.d(TAG, "probe results changed: rebuild thinking levels");
            probedRev = configManager.probeRevision();
            bindThinkingSpinner();
        }
    }

    private void loadSystemPrompt() {
        systemPrompt = SettingsActivity.getSystemPrompt(this);
        LogUtil.d(TAG, "loadSystemPrompt: len=%d preview=%s",
                systemPrompt == null ? 0 : systemPrompt.length(), LogUtil.preview(systemPrompt, 120));
    }

    /**
     * 会话时间注入的后缀。第一次请求时记下时间并落盘，之后原样复用。
     *
     * <p>这就是「中途改系统提示词，时间不跟着变」的关键：每次请求都会重新读设置里的
     * 提示词，但时间是会话自己的字段，不参与重新计算。
     */
    private String conversationTimeSuffix() {
        Conversation conv = conversationManager.getCurrentConversation();
        if (conv == null) return "";
        boolean hasHistory = conv.messages != null && conv.messages.size() > 1;
        boolean hadStamp = conv.firstRequestStamp != null
                && conv.firstRequestStamp.length() > 0;
        // 刚追加的用户消息让 size 至少为 1，所以「已有历史」要 > 1：
        // 只有这一条说明是新会话，时间应该就是现在
        String stamp = ConvTime.ensure(conv, hasHistory);
        // 只有第一次写入时才落盘：每次请求都存一遍会把 updatedAt 一直往后推，
        // 历史列表的排序会变成「谁刚发过消息谁靠前」以外的噪声
        if (!hadStamp) {
            conversationManager.saveCurrentConversation();
            // 会话数按「第一次真正使用」计，且只算真正的新会话：
            // 升级后继续用老会话不能算成「今天新建了一个会话」
            if (!hasHistory) UsageStats.get().onConversationCreated();
        }
        return ConvTime.promptSuffix(stamp);
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
        // 附件属于「这一次输入」，跟着旧会话一起丢掉，别带进新对话
        clearAttachments();
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
        AcToast.show(this, Strings.get(R.string.mainactivity_050));
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
                // 打断后不再继续渐显：停在用户已经看到的位置
                stopSmooth(requestGeneration.get());
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
                            updateUsageBar();
                            // 一个字都没生成：重试入口挂到用户消息上，否则没有任何可点的东西
                            int userIdx = lastUserIndexBefore(messages.size());
                            if (userIdx >= 0) {
                                Message um = messages.get(userIdx);
                                um.sendFailed = true;
                                um.errorDetail = Strings.get(R.string.mainactivity_051);
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
                            lastMsg.content = Strings.get(R.string.mainactivity_052, content);
                            // 中断也是一种「未完成」：给这条消息挂上重试入口，
                            // 否则用户只能重新打字，已生成的内容无法续上
                            lastMsg.sendFailed = true;
                            lastMsg.errorDetail = Strings.get(R.string.mainactivity_053);
                            // 打上中断标记：渲染层据此不再对残缺回答估算 token。
                            // 只靠「totalTokens==0」判断不够——端点没给 usage 的
                            // 正常回复也是 0，那样会把好数据也一起屏蔽掉。
                            lastMsg.interrupted = true;
                            LogUtil.d(TAG, "interrupt: marking AI msg as interrupted, len=%d", content.length());

                            // 中断的用量也进统计，但标记为估算：输入侧那几万 token 是真的
                            // 消耗掉了（尤其带附件的一轮），统计里一条都不记，用户会以为
                            // 「打断了就不花钱」。输出侧按当前已生成的字数估。
                            String src = lastMsg.apiText != null && lastMsg.apiText.length() > 0
                                    ? lastMsg.apiText : lastMsg.content;
                            int estCt = MessageHtmlRenderer.estimateTokens(src);
                            // 输入侧必须算上历史上下文：这个请求是带着前文发出去的，
                            // 只报「这次生成了多少」等于把最贵的那部分藏起来。
                            // 已完成轮次（工具轮）的真实用量在 promptTokens 里，直接累加：
                            // 每一轮都按当时的全部上下文计费，相加就是真实口径。
                            int estPt = estimatePromptBefore(lastIdx);
                            if (estCt > 0 || estPt > 0) {
                                lastMsg.promptTokens += estPt;
                                lastMsg.completionTokens += estCt;
                                lastMsg.totalTokens = lastMsg.promptTokens + lastMsg.completionTokens;
                                lastMsg.tokensEstimated = true;
                                if (lastMsg.rounds == null) {
                                    lastMsg.rounds = new ArrayList<Message.RoundUsage>();
                                }
                                lastMsg.rounds.add(new Message.RoundUsage(estPt, estCt, 0,
                                        Math.max(0, System.currentTimeMillis() - lastMsg.timestamp),
                                        true));
                                lastMsg.requestCount = Math.max(1, lastMsg.requestCount + 1);
                                UsageStats.get().onUsage(configManager.modelLabel(currentModel),
                        currentProviderName(),
                                        estPt, estCt, 0,
                                        Math.max(0, System.currentTimeMillis() - lastMsg.timestamp),
                                        true);
                            }
                            updateAiContent(lastMsg.content);
                            // meta 里的 token 行要立刻重画：打断前那一版是按流式内容算的，
                            // 现在正文末尾多了「(已打断)」，且估算口径要带上 ~ 和中断标记
                            webViewEvalJs("updateLastMeta('"
                                    + jsEscape(MessageHtmlRenderer.renderMeta(lastMsg)) + "')");
                            updateUsageBar();
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
     * 当前选中服务商的显示名，只给统计用。
     *
     * <p>拿不到就返回空串（统计里归到「未知」），不抛异常：统计是旁路，
     * 没理由因为下拉框还没初始化就把一次中断处理整个打断。
     */
    private String currentProviderName() {
        try {
            int pos = modelSpinner == null ? -1 : modelSpinner.getSelectedItemPosition();
            if (pos < 0 || availableModels == null || pos >= availableModels.size()) return "";
            ProviderInfo p = configManager.getProvider(availableModels.get(pos).provider);
            return p == null ? "" : p.label();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 估算「这条 AI 消息之前」的输入侧用量：系统提示词 + 全部历史 + 附件正文 + 工具历史。
     *
     * <p>只给打断场景兜底用。真实 usage 拿不到时，输入侧也得有个数——带附件的一轮
     * 常常几万 token，漏掉它会让「这次大概花了多少」低估一个数量级。
     *
     * <p>口径刻意与 {@code ChatTransport.buildRequestBody} 对齐：历史优先用
     * {@code apiText}（真正发出去的那份），没有才回退 content；工具历史也逐字计入，
     * 它确实占上下文。每条加 8 token 是角色标记与分隔符的粗略补偿。
     */
    private int estimatePromptBefore(int aiIndex) {
        int total = 0;
        if (systemPrompt != null && systemPrompt.length() > 0) {
            total += MessageHtmlRenderer.estimateTokens(systemPrompt);
        }
        int end = Math.min(aiIndex, messages.size());
        for (int i = 0; i < end; i++) {
            Message m = messages.get(i);
            if (m == null) continue;
            if (m.isUser()) {
                total += AttachmentText.tokensOf(m);
            } else {
                String s = m.apiText != null && m.apiText.length() > 0 ? m.apiText : m.content;
                total += MessageHtmlRenderer.estimateTokens(s);
                if (m.toolHistory != null && m.toolHistory.length() > 0) {
                    total += MessageHtmlRenderer.estimateTokens(m.toolHistory);
                }
            }
            total += 8;
        }
        return total;
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

    /**
     * 「继续生成」的落点：DeepSeek 官方走 /beta 前缀续写，其他服务商把已生成内容
     * 当上下文重新请求。复用同一条 AI 消息，新内容追加在已生成正文之后。
     *
     * <p>只支持最后一条 AI 消息：所有流式 DOM 函数都作用于「最后一个 AI 气泡」，
     * 若中断后用户又继续提问，往旧气泡写会把内容灌进新回复里。那种情况让用户
     * 走错误卡片上的「重新回复」。
     */
    private void doContinueGeneration(final int pos) {
        if (isRequestInProgress.get()) {
            AcToast.show(this, Strings.get(R.string.mainactivity_054));
            return;
        }
        if (pos < 0 || pos >= messages.size()) return;
        final Message am = messages.get(pos);
        if (am == null || !am.isAssistant()) return;
        if (pos != messages.size() - 1) {
            AcToast.show(this, Strings.get(R.string.mainactivity_055));
            return;
        }
        final String displayBase = stripInterruptedSuffix(am.content);
        final String prefixText = prefixTextOf(am, displayBase);
        if (prefixText.length() == 0) {
            // 一个字都没生成，没有可续的前缀，退回从用户消息重发
            int userIdx = lastUserIndexBefore(pos);
            if (userIdx >= 0) resendUserAt(userIdx);
            else AcToast.show(this, Strings.get(R.string.mainactivity_056));
            return;
        }
        loadSystemPrompt();
        int selPos = modelSpinner.getSelectedItemPosition();
        if (selPos < 0 || availableModels == null || selPos >= availableModels.size()) {
            AcToast.show(this, Strings.get(R.string.mainactivity_057));
            return;
        }
        ProviderInfo provider = configManager.getProvider(availableModels.get(selPos).provider);
        if (provider == null) {
            AcToast.show(this, Strings.get(R.string.mainactivity_058));
            return;
        }
        final boolean official = ChatTransport.isOfficialDeepSeek(provider);
        // 复用同一条消息：清掉「已打断」标记与失败状态，正文回到干净的已生成部分。
        // DOM 的重画交给 sendStreamingRequest，避免这里和它各排一次渲染。
        am.content = displayBase;
        am.apiText = prefixText;
        am.sendFailed = false;
        am.interrupted = false;
        am.errorDetail = "";
        conversationManager.saveCurrentConversation();
        String thinkingLevel = configManager.getThinkingLevel();
        if (!configManager.isThinkingEnabled()) thinkingLevel = "off";
        int gen = requestGeneration.incrementAndGet();
        LogUtil.i(TAG, "continue generation: idx=%d prefix=%d display=%d official=%s url=%s",
                pos, prefixText.length(), displayBase.length(), official, provider.apiUrl);
        sendStreamingRequest(provider, thinkingLevel, gen, null,
                new StreamJob.Continue(pos, displayBase, prefixText, official));
    }

    /** 去掉 {@code interruptRequest} 追加的「 (已打断)」尾巴。 */
    private static String stripInterruptedSuffix(String content) {
        if (content == null) return "";
        final String suffix = Strings.get(R.string.mainactivity_059);
        return content.endsWith(suffix)
                ? content.substring(0, content.length() - suffix.length()) : content;
    }

    /** 发给模型的 assistant 前缀：优先流式留下的 apiText，否则从正文剥掉标记。 */
    private static String prefixTextOf(Message m, String displayBase) {
        if (m.apiText != null && m.apiText.trim().length() > 0) return m.apiText.trim();
        return MessageHtmlRenderer.plainTextOf(displayBase).trim();
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

    /**
     * 存储页的「查看」用它切会话：带 {@code FLAG_ACTIVITY_REORDER_TO_FRONT}，
     * 不会新开一个主界面实例，返回键落回原来的管理页。
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String conversationId = intent == null ? null : intent.getStringExtra("conversation_id");
        LogUtil.d(TAG, "onNewIntent: conversation_id=%s", conversationId);
        if (conversationId != null) {
            switchToConversation(conversationId);
        }
        // 首启引导还没确认时不能直接发：否则从别的应用「发送到 AiChat」，
        // 消息会在引导弹窗背后被发出去。等用户点「同意」时再统一处理 getIntent()。
        if (!prefs.getBoolean(KEY_FIRST_LAUNCH_DONE, false)) {
            LogUtil.d(TAG, "onNewIntent deferred: first launch not confirmed");
            return;
        }
        handlePromptIntent(intent);
    }

    /**
     * 切换到指定会话。来自会话管理页（startActivityForResult）与存储页（onNewIntent）共用。
     *
     * <p>已经是当前会话就直接返回：重复走一遍会中断正在进行的请求、
     * 丢掉排队消息，用户只是点了「查看」，不该付出这个代价。
     *
     * <p>加载在后台线程做，进度通过 {@link ConversationManager.LoadProgress} 回报，
     * 统一 post 回主线程更新遮罩。大会话读盘 + JSON 解析可能几百毫秒，
     * 没有遮罩时用户会以为点错了又点一次，于是两次切换互相覆盖。
     */
    private void switchToConversation(final String conversationId) {
        Conversation cur = conversationManager.getCurrentConversation();
        if (cur != null && conversationId.equals(cur.id)) {
            LogUtil.i(TAG, "switchToConversation: already current %s", conversationId);
            return;
        }
        if (switchingConversation) {
            if (conversationRenderState != 0) {
                LogUtil.w(TAG, "switchToConversation ignored: another switch is loading");
                return;
            }
            // 上一轮渲染其实已结束，只差 180ms 的「100% 停留」回调：直接接管，
            // 别把用户紧接着的下一次点击吞掉。
            cancelConversationRenderTimers();
            hideConversationLoading();
        }
        LogUtil.i(TAG, "switching to conversation: %s", conversationId);
        // 排队消息属于旧会话：在 interruptRequest 派发的 drain 之前丢弃，避免发进新会话
        if (queue != null) queue.clearAll();
        clearAttachments();
        if (isRequestInProgress.get()) interruptRequest();
        dropTrailingEmptyAssistant();
        conversationManager.saveCurrentConversation();

        switchingConversation = true;
        showConversationLoading();
        beginConversationRender();
        // 数据阶段只占整体 0~60%：剩下 40% 是整页 HTML 构建 + WebView 渲染。
        // 旧实现在这里就报 100%，用户看到「加载完成」却还要白屏很久。
        final ConversationManager.LoadProgress progress = new ConversationManager.LoadProgress() {
            public void onStage(String label, int percent) {
                updateConversationLoading(label, 8 + percent * 52 / 100);
            }
        };
        new Thread(new Runnable() {
            public void run() {
                try {
                    conversationManager.switchConversation(conversationId, progress);
                } catch (Throwable t) {
                    LogUtil.e(TAG, "switchConversation failed", t);
                }
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (isFinishing()) {
                            cancelConversationRenderTimers();
                            conversationRenderState = 0;
                            hideConversationLoading();
                            return;
                        }
                        final Conversation conv = conversationManager.getCurrentConversation();
                        messages = conv.messages;
                        conversationRenderTitle = conv.title == null ? "" : conv.title;
                        int count = messages == null ? 0 : messages.size();
                        updateConversationLoading(Strings.get(R.string.mainactivity_168, count), 62);
                        refreshWebView();
                        LogUtil.i(TAG, "switched: id=%s title=%s messages=%d",
                                conv.id, conv.title, count);
                        // 不再固定延时收遮罩：由 onPageFinished / onReceivedError 收起，
                        // 超时兜底负责「两个回调都不来」的极端情况。
                    }
                });
            }
        }, "aichat-switch-conversation").start();
    }

    private void showConversationLoading() {
        if (loadingOverlay == null) {
            loadingOverlay = findViewById(R.id.conversation_loading_overlay);
            loadingDetail = (TextView) findViewById(R.id.loading_detail);
            loadingPercent = (TextView) findViewById(R.id.loading_percent);
            loadingProgress = (android.widget.ProgressBar) findViewById(R.id.loading_progress);
        }
        // 遮罩是半透明覆盖层，颜色必须按当前外观取，否则深色下白底配白字看不见进度
        boolean dark = AppearanceManager.getInstance(this).isDark();
        int secondary = dark ? 0xFFB0BEC5 : getResources().getColor(R.color.text_secondary);
        loadingOverlay.setBackgroundColor(dark ? 0xE6202020 : 0xE6FFFFFF);
        ((TextView) findViewById(R.id.loading_title))
                .setTextColor(dark ? 0xFFEEEEEE : getResources().getColor(R.color.text_primary));
        loadingDetail.setTextColor(secondary);
        loadingPercent.setTextColor(secondary);
        loadingProgress.setProgress(0);
        loadingOverlay.setVisibility(View.VISIBLE);
        updateConversationLoading(Strings.get(R.string.mainactivity_062), 8);
    }

    /** 后台线程也能调：统一 post 回主线程，避免加载线程直接碰 View。 */
    private void updateConversationLoading(final String label, final int percent) {
        runOnUiThread(new Runnable() {
            public void run() {
                if (loadingOverlay == null || loadingDetail == null
                        || loadingOverlay.getVisibility() != View.VISIBLE) return;
                loadingDetail.setText(label);
                loadingProgress.setProgress(percent);
                loadingPercent.setText(percent + "%");
            }
        });
    }

    private void hideConversationLoading() {
        if (loadingOverlay != null) loadingOverlay.setVisibility(View.GONE);
        switchingConversation = false;
    }

    /** 进入一次切会话：起状态机并挂总超时，任何阶段卡死都能放用户出来。 */
    private void beginConversationRender() {
        conversationRenderTitle = "";
        conversationRenderState = 1;
        conversationRenderGen = renderGeneration.get();
        cancelConversationRenderTimers();
        handler.postDelayed(conversationRenderTimeout, 12000);
    }

    private void cancelConversationRenderTimers() {
        handler.removeCallbacks(conversationRenderTimeout);
        handler.removeCallbacks(conversationRenderTick);
        if (pendingRenderHide != null) {
            handler.removeCallbacks(pendingRenderHide);
            pendingRenderHide = null;
        }
        conversationRenderTickPercent = 85;
    }

    /** 渲染线程（后台）也能调：updateConversationLoading 内部编组回主线程。 */
    private void reportConversationRenderStage(String label, int percent) {
        if (conversationRenderState == 0) return;
        updateConversationLoading(label, percent);
    }

    /** loadDataWithBaseURL 之后调用：从此刻起只等 onPageFinished / onReceivedError。 */
    private void awaitConversationPageFinish(int gen) {
        if (conversationRenderState == 0 || conversationRenderGen != gen) return;
        conversationRenderState = 2;
        updateConversationLoading(Strings.get(R.string.mainactivity_063), 85);
        // 从真正开始灌页重新计时：整页渲染慢不等于卡死，但必须有上限。
        handler.removeCallbacks(conversationRenderTimeout);
        handler.postDelayed(conversationRenderTimeout, 10000);
        handler.postDelayed(conversationRenderTick, 240);
    }

    /**
     * 收起遮罩的唯一出口。只认 state==2：HTML 还没拼完时迟到的旧页
     * onPageFinished 不能提前把遮罩关掉。
     */
    private void finishConversationRender(final boolean timedOut) {
        if (conversationRenderState != 2) return;
        conversationRenderState = 0;
        cancelConversationRenderTimers();
        if (isFinishing()) {
            hideConversationLoading();
            return;
        }
        updateConversationLoading(timedOut ? Strings.get(R.string.mainactivity_064) : Strings.get(R.string.mainactivity_065), 100);
        final String title = conversationRenderTitle;
        // 这个延迟收起必须可取消：180ms 内用户又切一次会话时，旧回调不能把新遮罩关掉
        pendingRenderHide = new Runnable() {
            public void run() {
                pendingRenderHide = null;
                // 新一轮切换已开始（state!=0）就别插手它的遮罩
                if (isFinishing() || conversationRenderState != 0) return;
                hideConversationLoading();
                AcToast.show(MainActivity.this, timedOut
                        ? Strings.get(R.string.mainactivity_170, title)
                        : Strings.get(R.string.mainactivity_169, title));
            }
        };
        handler.postDelayed(pendingRenderHide, 180);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        LogUtil.d(TAG, "onActivityResult: requestCode=%d resultCode=%d data=%s", requestCode, resultCode, data);
        if (requestCode == REQUEST_CONVERSATION_MANAGER && resultCode == RESULT_OK) {
            final String conversationId = data == null ? null : data.getStringExtra("conversation_id");
            if (conversationId != null) {
                switchToConversation(conversationId);
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
        } else if (requestCode == REQUEST_PICK_IMAGE) {
            Uri picked = data == null ? null : data.getData();
            if (resultCode == RESULT_OK && picked != null) {
                LogUtil.i(TAG, "picked image: %s", picked);
                // 选图先进裁剪页：拍照要裁、截图要裁、相册原图更要裁，
                // 统一走同一条路，用户不用记「哪种来源能裁」
                stageImageForCrop(picked);
            } else {
                LogUtil.d(TAG, "pick image cancelled or empty");
            }
        } else if (requestCode == REQUEST_TAKE_PHOTO) {
            // 以「文件真的写出来了」为准，不再死卡 resultCode：不少 ROM 带 EXTRA_OUTPUT
            // 时返回非 RESULT_OK，甚至只在 data 里回一张缩略图。静默失败是这里最糟的表现。
            File shot = cameraOutputFile;
            if (shot != null && shot.exists() && shot.length() > 0) {
                LogUtil.i(TAG, "photo captured: %d bytes (resultCode=%d)",
                        shot.length(), resultCode);
                startCrop(shot.getAbsolutePath(), "photo.jpg");
            } else {
                Bitmap inline = extractInlinePhoto(data);
                if (inline != null) {
                    File saved = saveInlinePhoto(inline);
                    if (saved != null) {
                        LogUtil.i(TAG, "photo captured from inline thumbnail: %d bytes",
                                saved.length());
                        startCrop(saved.getAbsolutePath(), "photo.jpg");
                    } else {
                        AcToast.show(this, Strings.get(R.string.mainactivity_069));
                    }
                } else {
                    LogUtil.w(TAG, "take photo produced no file (resultCode=%d)", resultCode);
                    AcToast.show(this, Strings.get(R.string.mainactivity_070));
                }
            }
        } else if (requestCode == REQUEST_CROP) {
            String out = data == null ? null : data.getStringExtra(CropActivity.EXTRA_OUT_PATH);
            String name = data == null ? null : data.getStringExtra(CropActivity.EXTRA_NAME);
            if (resultCode == RESULT_OK && out != null && out.length() > 0) {
                LogUtil.i(TAG, "crop result: %s", out);
                importImageFile(Uri.fromFile(new java.io.File(out)), name);
            } else {
                LogUtil.d(TAG, "crop cancelled");
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
        uiVisible = true;
        // 回到前台先对一次网络状态：后台期间可能已经断网/恢复，广播不一定收得到
        startOfflineWatch();
        // 后台期间渐显停住了：回到前台立刻接着放，别让用户看到半截回答
        if (smoothRunning) {
            handler.removeCallbacks(smoothTick);
            handler.post(smoothTick);
        }
        // 在后台期间发生的外观改动只记了签名（避免每拖一格就重建整页），
        // 回到前台时补一次。必须在 invalidate 之前，否则用户先看到的是旧配色。
        if (tintSigPending != null) {
            final String sig = tintSigPending;
            if (isRequestInProgress.get()) {
                // 还在流式输出：重灌整页会把正在长的那条气泡打回空白。
                // pending 留着，等本轮结束由 flushPendingTint 收尾。
                LogUtil.d(TAG, "onResume: tint change deferred (streaming)");
            } else {
                tintSigPending = null;
                if (!sig.equals(tintSigApplied)) {
                    tintSigApplied = sig;
                    MessageHtmlRenderer.clearRenderCache();
                    refreshWebView();
                }
            }
        }
        // 设置页改完回到这里，两个网络安全开关必须立刻生效。
        // 放在 onResume 而不是 onCreate：用户可能一直不退出这个界面。
        NetSecurity.set(SettingsActivity.isTrustAllCerts(this),
                SettingsActivity.isHttpAllowPrivateHost(this));
        // 后台期间 AppearanceManager 的通知被跳过了（见 bindWebView），这里补一次
        // 样式与字号。纯 <style> 文本替换，不重灌页面、不动滚动位置。
        if (webRefresher != null) {
            webRefresher.onAppearanceChanged(AppearanceManager.getInstance(this));
        }
        loadSystemPrompt();
        refreshModelSpinner();
        // 弹对话框/切后台回来时，硬件加速合成层偶发不主动重绘，表现为
        // 「消息要交互一下才看得见」。回到前台主动 invalidate 一次。
        if (conversationWebView != null) conversationWebView.invalidate();
        if (expectingQuickScanResult) {
            expectingQuickScanResult = false;
            LogUtil.d(TAG, "onResume: quick-scan result expected -> loadQuickScanResult");
            loadQuickScanResult();
        }
    }

    @Override
    protected void onPause() {
        LogUtil.d(TAG, "onPause");
        uiVisible = false;
        super.onPause();
        // 后台不必再盯网络：广播与轮询都停掉，回前台由 onResume 重新挂上并立刻对一次状态
        stopOfflineWatch();
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
        applyOrientationLayout(newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE);
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
            AcToast.show(this, Strings.get(R.string.mainactivity_071));
            expectingQuickScanResult = false;
        } else {
            LogUtil.d(TAG, "launchScan: builtin ScanActivity mode");
            try {
                startActivityForResult(new Intent(this, ScanActivity.class), REQUEST_SCAN);
            } catch (Exception e) {
                LogUtil.e(TAG, "launch ScanActivity failed", e);
                AcToast.showLong(this, Strings.get(R.string.mainactivity_072, e.getMessage()));
            }
        }
    }

    /**
     * 发送键：空闲时直接发，正在回复时入队。
     * 队列本身（排序/插队/弹窗）在 {@link MessageQueue}，这里只管输入框这一侧。
     */
    private void sendMessage() {
        String input = inputEditText.getText().toString().trim();
        boolean hasAttachment = !pendingAttachments.isEmpty();
        // 只挂附件、不写字也允许发送：常见用法就是把文件丢进去问「这段报错什么意思」
        if (input.isEmpty() && !hasAttachment) {
            LogUtil.w(TAG, "sendMessage ignored: empty input and no attachment");
            return;
        }
        // 附件必须在清空之前定稿：队列条目与消息都按这份快照走，
        // 晚一步 clearAttachments() 之后内容就没了
        final List<AttachmentText.File> files = new ArrayList<AttachmentText.File>(pendingAttachments);
        inputEditText.setText("");
        clearAttachments();
        if (isRequestInProgress.get()) {
            queue.enqueue(input, files);
            return;
        }
        sendMessageText(input, files);
    }

    /**
     * @param input 用户原话（气泡正文）。附件不混进来，它们是 {@link Message#attachments}，
     *              由渲染层单独画成卡片、由 {@link AttachmentText} 单独拼给模型。
     */
    private void sendMessageText(final String input, final List<AttachmentText.File> files) {
        LogUtil.i(TAG, "========== sendMessage: len=%d preview=%s ==========",
                input.length(), LogUtil.preview(input, 200));

        // 外部存储被移除后所有保存会静默失败：发送前探测一次，仅提示不拦截
        if (!storageManager.isStorageReady()) {
            LogUtil.w(TAG, "sendMessage: storage not ready (%s), conversation may not persist",
                    storageManager.getStorageInfo());
            AcToast.showLong(this, Strings.get(R.string.mainactivity_073));
        }

        loadSystemPrompt();

        // 模型校验提前到入列/渲染之前：校验失败不应留下永不回复的孤儿用户消息
        int selPos = modelSpinner.getSelectedItemPosition();
        if (selPos < 0 || availableModels == null || selPos >= availableModels.size()) {
            LogUtil.e(TAG, "sendMessage aborted: invalid model selection pos=%d, models=%d",
                    selPos, availableModels == null ? -1 : availableModels.size());
            AcToast.show(this, Strings.get(R.string.mainactivity_074));
            return;
        }
        ProviderInfo provider = configManager.getProvider(availableModels.get(selPos).provider);
        if (provider == null) {
            LogUtil.e(TAG, "sendMessage aborted: provider null for model '%s'",
                    availableModels.get(selPos).name);
            AcToast.show(this, Strings.get(R.string.mainactivity_075));
            return;
        }

        // 记快照：从这一刻起这条消息的附件按此刻的字符预算发出去了。
        // 必须在渲染附件卡片之前——卡片上的「已截断 M / N」直接读这个快照，
        // 之后再改设置也不会回头改写它；只有用户重发/重试这条消息才会重新记。
        AttachmentText.recordSent(files);

        Message userMsg = new Message(Message.ROLE_USER, input);
        // 附件正文随消息走，不再另存一份 apiText：一份正文两个副本，
        // 迟早有一处忘了同步（1.8.1 的「AI 收不到文件」就是这么来的）
        if (files != null) userMsg.attachments.addAll(files);
        messages.add(userMsg);
        // 统计「轮次」：一条用户消息 = 一轮对话（含它触发的工具轮次）。
        // 重新生成/续跑不新增用户消息，所以不会重复计数。
        UsageStats.get().onRound();
        conversationManager.getCurrentConversation().touch();
        LogUtil.d(TAG, "user message appended, total messages=%d", messages.size());

        // Append user message to WebView
        int userIdx = messages.size() - 1;
        String userHtml = MessageHtmlRenderer.renderMessageDiv(userMsg, userIdx, this);
        appendHtml(userHtml, userIdx);
        // 记下「刚发出去的这一条」：页面就绪后会先查它在不在 DOM 里，不在就重画整页。
        // 这是最后一道保险——前面所有队列与版本判断只要有一处漏了，用户也不需要重进会话。
        lastAppendedIdx = userIdx;
        lastAppendCheckPending = true;

        Conversation conv = conversationManager.getCurrentConversation();
        boolean isFirstMsg = conv.messages.size() == 1;
        boolean autoTitle = SettingsActivity.isAutoTitleEnabled(this);
        LogUtil.d(TAG, "title check: isFirstMsg=%s autoTitle=%s titleGenerated=%s titleRequested=%s",
                isFirstMsg, autoTitle, conv.titleGenerated, conv.titleRequested);
        // titleRequested 在发起请求前就置位：否则「首轮回复失败回滚/删除消息」后
        // 再发一条又会满足 size==1，同一个会话会重复请求标题。
        if (isFirstMsg && !conv.titleGenerated && !conv.titleRequested) {
            conv.titleRequested = true;
            // 只发附件不写字时没有可用的标题种子，退回文件名——
            // 否则会话标题会是空白，列表里认不出来
            String seed = input;
            if (seed.length() == 0 && files != null && !files.isEmpty()) {
                seed = files.get(0).name;
            }
            // 先落一个「消息前 15 字」的兜底标题：自动标题关闭、生成失败或返回空时都保留它
            TitleGenerator.applyFallback(conversationManager, conv, seed);
            if (autoTitle) {
                TitleGenerator.generate(MainActivity.this, configManager, conversationManager, seed);
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
        sendStreamingRequest(provider, thinkingLevel, generation, resume, null);
    }

    /** 带续写状态的重载：cont 非 null 表示复用已有 AI 消息「继续生成」。 */
    private void sendStreamingRequest(ProviderInfo provider, String thinkingLevel,
            int generation, StreamJob.Resume resume, final StreamJob.Continue cont) {
        final boolean webSearchEnabled = SettingsActivity.isWebSearchEnabled(this);
        final boolean useTavily = SettingsActivity.isTavilySearch(this);
        final String tavilyKey = SettingsActivity.getTavilyKey(this);
        // 搜索后端在 UI 线程先定死：Tavily 走自建网关，不需要 DeepSeek 提供商
        final ProviderInfo searchProvider = (webSearchEnabled && !useTavily)
                ? WebSearchTool.resolveProvider(configManager, provider) : null;
        final boolean searchReady = webSearchEnabled
                && (useTavily ? TavilyTool.hasApiKey(tavilyKey) : searchProvider != null);
        // 继续生成不发 tools，系统提示也不要带工具说明，否则模型会照着提示
        // 输出 [tool] 文本卡片，把续写正文搅乱。
        final String effectiveSystemPrompt = cont != null
                ? (systemPrompt == null ? "" : systemPrompt) + conversationTimeSuffix()
                : WebSearchTool.appendToSystemPrompt(systemPrompt, webSearchEnabled)
                        + conversationTimeSuffix();
        final boolean streamEnabled = SettingsActivity.isStreamEnabled(this);
        final boolean jobRealtimeRender = SettingsActivity.isRealtimeRenderEnabled(this);
        // 模型是否支持图片输入：只有用户勾过才发 image_url 块，否则图片只写一行说明
        final ModelInfo visionModel = configManager.getModel(currentModel);
        final boolean vision = visionModel != null && visionModel.supportsVision;
        // 端点上次拒过 stream_options 就不再发，避免每次对话白吃一个 400
        final boolean includeUsage = streamEnabled && !isUsageUnsupported(provider.name);
        // 渐显只在流式、实时渲染时有意义：非流式是一次性拿到全文，纯文本 tail 也不需要渐显
        // 渐显只要求流式：实时渲染关掉时正文走纯文本 tail，同样是整段整段地到，
        // 一样需要按字浮出（以前这里要求同时开启实时渲染，关掉它就等于关掉渐显）
        smoothStream = SettingsActivity.isSmoothStreamEnabled(this) && streamEnabled;
        activeProviderName = provider.name;
        retryNowRequested.set(false);

        Message aiMsg;
        int aiIndex;
        if (resume != null) {
            // 续跑：复用已存在的那条 AI 消息，不新增占位、不重复插 DOM
            aiMsg = messages.get(resume.aiIndex);
            aiIndex = resume.aiIndex;
            LogUtil.i(TAG, "resume request: aiIndex=%d round=%d traceLen=%d",
                    aiIndex, resume.round, resume.trace == null ? 0 : resume.trace.length());
        } else if (cont != null) {
            // 继续生成：复用被中断的那条 AI 消息，正文保留已生成部分，
            // 新分片追加在后面；不新增气泡、不重置 DOM 里的 data-idx。
            aiMsg = messages.get(cont.aiIndex);
            aiIndex = cont.aiIndex;
            LogUtil.i(TAG, "continue request: aiIndex=%d prefixLen=%d official=%s",
                    aiIndex, cont.prefixText.length(), cont.officialPrefix);
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
        final boolean isContinue = cont != null;
        // base 层的内容基准：新请求从空白开始，续跑从上次的 trace 开始。
        // 不清会把上一条消息的 trace 当成「已推送」，导致工具卡片永远不出现。
        // 继续生成这里刻意留空：让第一帧走 streamBase 重建骨架，顺带把已有正文
        // 渲染进 base，后续分片只追加 tail，不会把旧内容盖掉。
        lastStreamTrace = isResume && resume.trace != null ? resume.trace : "";
        // DOM 内容基准同理：续跑时界面上已经有上次的正文，新请求从空白开始
        lastPushedContent = lastStreamTrace;
        runOnUiThread(new Runnable() {
            public void run() {
                if (isContinue) {
                    webViewEvalJs("clearAssistantFailed(" + placeholderIndex + ")");
                    updateAiContent(cont.displayBase);
                } else {
                    webViewEvalJs(isResume
                            ? "clearAssistantFailed(" + placeholderIndex + ")"
                            : "appendAiDiv(" + placeholderIndex + ")");
                }
            }
        });

        setRequestInProgress(true);
        LogUtil.i(TAG, ">>> request START: gen=%d url=%s model=%s thinking=%s webSearch=%s backend=%s",
                generation, Endpoint.chatUrl(provider), currentModel, thinkingLevel,
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
                .chatEndpoint(Endpoint.chatUrl(provider))
                .streamEnabled(streamEnabled)
                .includeUsage(includeUsage)
                .realtimeRender(jobRealtimeRender)
                .smoothStream(smoothStream)
                .webSearchEnabled(webSearchEnabled)
                .httpToolEnabled(SettingsActivity.isHttpToolEnabled(this))
                .vision(vision)
                .appContext(getApplicationContext())
                .useTavily(useTavily)
                .searchReady(searchReady)
                .maxCallsPerRound(SettingsActivity.getWebSearchMaxCallsPerRound(this))
                .searchProvider(searchProvider)
                .tavilyKey(tavilyKey)
                .retryPolicy(RetryPolicy.from(this))
                .resume(resume)
                .cont(cont)
                .build();

        final Channel channel = new Channel();
        channel.uiIdx = aiIndex;
        activeChannel = channel;
        final ChatStreamRunner runner =
                new ChatStreamRunner(job, channel, new GenerationSession(generation));
        currentRequestThread = new Thread(new Runnable() {
            public void run() {
                runner.execute();
            }
        });
        currentRequestThread.setName("aichat-stream-" + System.currentTimeMillis());
        currentRequestThread.start();
        LogUtil.d(TAG, "request thread started: %s", currentRequestThread.getName());
    }

    /** 最近一次请求的视图通道：渐显定时器在 Activity 里跑，需要它的气泡下标。 */
    private volatile Channel activeChannel;

    /** 把 Activity 的视图能力交给 {@link ChatStreamRunner}，每个方法都是一两行转发。 */
    private final class Channel implements ChatStreamRunner.Ui {
        /**
         * 这次请求当前对应的 AI 气泡下标。
         *
         * <p>流式脚本一律带上它。旧实现让页面自己找「最后一个 .msg.ai」，
         * 只要页面在这期间被整页重建过（主题、上滑加载、另一版页面更晚上屏），
         * 正文就会写进别的气泡，屏幕上看起来就是「发了消息没反应」。
         */
        private volatile int uiIdx = -1;

        public void runOnUi(Runnable task) {
            runOnUiThread(task);
        }

        public void evalJsHtml(String fn, String html) {
            webViewEvalJs(fn + "('" + jsEscape(html) + "')");
        }

        public void evalJsHtml(String fn, String html, int idx) {
            webViewEvalJs(fn + "('" + jsEscape(html) + "'," + idx + ")");
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
            traceRenderSeq.incrementAndGet(); // 同时作废在飞的 trace 渲染
            LogUtil.d(TAG, "trace trimmed: render base reset");
        }

        public void onRetryWaiting(int attempt, int maxAttempts, long waitMs, String reason,
                int generation, int aiIndex) {
            webViewEvalJs("showRetryCard(" + attempt + "," + maxAttempts + "," + waitMs
                    + ",'" + jsEscape(reason) + "')");
        }

        public void onRetryWaitEnd() {
            webViewEvalJs("hideRetryCard()");
        }

        public void onRoundUsage(final int aiIndex, final int prompt, final int completion,
                final int cached, final long ms) {
            runOnUiThread(new Runnable() {
                public void run() {
                    if (aiIndex < 0 || aiIndex >= messages.size()) return;
                    Message m = messages.get(aiIndex);
                    if (m == null || !m.isAssistant()) return;
                    m.rounds.add(new Message.RoundUsage(prompt, completion, cached, ms, false));
                    m.promptTokens += prompt;
                    m.completionTokens += completion;
                    m.cachedTokens += cached;
                    m.totalTokens = m.promptTokens + m.completionTokens;
                    m.requestCount++;
                    // 流式过程中就把底部数字刷出来：用户不用等整条回复结束才看到用量。
                    // 只在它还是最后一条时刷，避免续跑旧消息时把别人的 meta 改掉。
                    if (aiIndex == messages.size() - 1) {
                        webViewEvalJs("updateLastMeta('"
                                + jsEscape(MessageHtmlRenderer.renderMeta(m)) + "')");
                    }
                }
            });
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

        public void renderTailHtml(final String baseNow, final String html) {
            if (!smoothStream) {
                evalJsHtml("streamTailHtml", html, uiIdx);
                return;
            }
            // 渐显可能尚未起步：纯文本回答没有 trace 变化、工具轮次收轮后
            // smoothRunning 已复位，内容到了却没人推，屏幕会一直空到收尾
            final int gen = MainActivity.this.requestGeneration.get();
            if (!smoothRunning || smoothGeneration != gen) beginSmoothRound(gen);
            feedSmooth(html, gen);
        }

        public void smoothFinished(int generation) {
            finishSmooth(generation);
        }

        public void smoothInterrupted(int generation) {
            stopSmooth(generation);
        }

        public void smoothReveal(int generation) {
            revealSmooth(generation);
        }

        public boolean smooth() {
            return smoothStream;
        }

        public String renderHtml(String markdown) {
            return MessageHtmlRenderer.contentToHtml(markdown, MainActivity.this, false);
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
                    AcToast.show(MainActivity.this, msg);
                }
            });
        }

        public void showStatus(String text, int generation, String trace, int aiIndex) {
            showSearchStatus(text, generation, trace, aiIndex);
        }

        public void renderTrace(String trace, int generation, String status, int aiIndex) {
            renderTraceAsync(trace, generation, status, aiIndex);
        }

        public void renderLiveTools(final String incrementalHtml, final boolean reset) {
            final int generation = requestGeneration.get();
            if (incrementalHtml == null || incrementalHtml.length() == 0) return;
            runOnUiThread(new Runnable() {
                public void run() {
                    if (requestGeneration.get() != generation) return;
                    webViewEvalJs("liveTools('" + jsEscape(incrementalHtml) + "'," + reset
                            + "," + smoothIdx() + ")");
                }
            });
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

        public boolean consumeRetryNow() {
            return retryNowRequested.getAndSet(false);
        }

        public void onSettled(ChatStreamRunner.Settled settled) {
            settleStream(settled);
        }
    }

    /**
     * 把「定稿 HTML 预热」推到消息定稿帧之后再跑。
     *
     * <p>预热本身是纯后台计算（解析 markdown、高亮代码、渲染公式位图），但它占 CPU
     * 几百毫秒；紧挨着 UI post 执行会把定稿那一帧往后推。延后 600ms 后跑，既不影响
     * 用户看到定稿，也仍然赶在「切会话 / 换肤」之前把缓存焐热——这两类操作都在用户
     * 主动点击之后，最快也要几百毫秒。
     */
    private void scheduleWarmUp(final String content, final int generation) {
        if (content == null || content.length() == 0) return;
        handler.postDelayed(new Runnable() {
            public void run() {
                if (isFinishing() || requestGeneration.get() != generation) return;
                if (MessageHtmlRenderer.isCached(content)) return;
                new Thread(new Runnable() {
                    public void run() {
                        // 外层 Runnable 的 isFinishing 只在 600ms 那一刻判过一次；
                        // 这个线程脱离 handler.removeCallbacks 管辖，且定稿完成后
                        // onDestroy 不会自增代数，只校验 requestGeneration 拦不住：
                        // 会用已销毁的 Activity 当 Context 渲染并写进静态缓存。
                        if (isFinishing() || isDestroyed()
                                || requestGeneration.get() != generation) return;
                        try {
                            MessageHtmlRenderer.contentToHtml(content, MainActivity.this, true);
                        } catch (Throwable t) {
                            LogUtil.w("MainActivity", "warm-up render failed: " + t.getMessage());
                        }
                    }
                }, "aichat-warmup").start();
            }
        }, 600);
    }

    /**
     * 请求收尾：成功则提交正文与来源，失败则挂错误卡片或回滚空占位。
     * 渲染离开主线程，落盘与放行队列的顺序不能调换（见方法内注释）。
     */
    private void settleStream(final ChatStreamRunner.Settled settled) {
        final int finalIdx = settled.aiIndex;
        final boolean failed = settled.failed;
        renderSeq.incrementAndGet(); // 作废进行中的流式渲染
        traceRenderSeq.incrementAndGet(); // 定稿后不许旧 trace 结果再改写 base
        new Thread(new Runnable() {
            public void run() {
                try {
                    if (failed) {
                        stopSmooth(settled.generation);
                        // 工具结果已经回传给模型的轮次：保留这条 AI 消息和已展示的
                        // 思考/工具卡片，挂「从出错处重试」，不再重发整条用户消息。
                        // 400/422 说明工具格式被拒，这类失败只能整条重来。
                        if (settled.round > 0 && settled.toolHistory != null
                                && settled.toolHistory.length() > 2
                                && settled.httpCode != 400 && settled.httpCode != 422) {
                            markAssistantFailed(finalIdx, settled.errorDetail, settled.trace,
                                    settled.toolHistory, settled.apiText, settled.generation,
                                    settled.partialStart);
                            return;
                        }
                        // 首轮就失败：移除空 assistant 占位，避免空气泡落盘/进入后续请求体
                        rollbackEmptyAssistantPlaceholder(finalIdx, settled.generation);
                        markSendFailed(finalIdx, settled.errorDetail, settled.generation);
                        return;
                    }

                    // 收尾一律按完整正文重新渲染一次。
                    //
                    // 以前只要「最后一次流式帧的内容等于最终文本」就跳过重渲染，靠
                    // streamFinalize 把 tail 搬进 base。但流式帧在关掉实时渲染时走的是
                    // 纯文本 tail，搬进 base 的只是包在 .stream-plain 里的原文——markdown、
                    // 公式、代码高亮全都没有，屏幕停在一坨未渲染的文字上，切走再回来才
                    // 恢复。现在不再判断 DOM 是否「看起来已完整」，直接补一帧渲染好的 HTML。
                    final String html = MessageHtmlRenderer.contentToHtml(settled.content, MainActivity.this);
                    // 定稿 HTML 同时预热进缓存：流式路径全部 cacheable=false，不预热的话
                    // 下次切会话/换肤整页重灌必然全 miss。这份计算已在当前线程完成，
                    // 预热只负责把它写进缓存，安排在定稿帧之后（见 scheduleWarmUp）。
                    scheduleWarmUp(settled.content, settled.generation);
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
                                // 命中数来自 usage 里的 prompt cache 字段：
                                // 只有真拿到值才写，估算分支不能再拿它编数
                                done.cachedTokens = settled.cachedTokens;
                                // 工具轮次会发多次请求：round 是 0 起的轮次下标，
                                // 加一才是这次回复真正发出去的请求数
                                done.requestCount = settled.round + 1;
                                // 端点没给 usage（不支持 stream_options / 关了流式）：
                                // 用正文估一个，并标记为估算。空着不显示，用户会以为
                                // 「这个端点根本不返回 token」，实际是没开统计。
                                if (done.completionTokens <= 0 && done.content != null
                                        && done.content.length() > 0) {
                                    int est = MessageHtmlRenderer.estimateTokens(done.content);
                                    done.completionTokens = est;
                                    done.totalTokens = est;
                                    done.tokensEstimated = true;
                                }
                            }
                            // 渐显里还压着没浮出来的字：先全部放完再定稿，
                            // 否则最后一段会在同一帧里「跳」出来，前面的平滑感就断了
                            finishSmooth(settled.generation);
                            // 先收掉流式骨架（清空 tail、隐藏光标），再写入完整渲染结果。
                            // updateLastMsg 会把全文写进 base 并清空 tail，所以这里不再
                            // 单独调用 streamFinalize —— 它只做「把 tail 搬进 base」，
                            // 而 tail 里的纯文本正好是要被替换掉的那一坨。
                            webViewEvalJs("streamFinalize(" + finalIdx + ")");
                            webViewEvalJs("updateLastMsg('" + jsEscape(html) + "',"
                                    + finalIdx + ")");
                            // base 层此时等于这条消息的全文。不更新的话，续跑时增量渲染
                            // 会拿「旧 prefix」当基准，把已经显示的内容再追加一遍。
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
                            updateUsageBar();
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
            final String toolHistoryJson, final String apiText, final int generation,
            final int retryTrim) {
        if (aiIndex < 0 || aiIndex >= messages.size()) return;
        Message am = messages.get(aiIndex);
        if (am == null || !am.isAssistant()) return;
        am.content = traceText == null ? "" : traceText;
        // 断流时补进来的那半截正文要记下起点：续跑时模型会重写这一轮，
        // 不截掉就会新旧两份连在一起（界面重复、落盘也重复）。
        am.retryTrim = retryTrim;
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
        // 同一轮里 trace 追加（思考块/工具卡片）不重置渐显：beginSmoothRound
        // 会清空缓冲、擦掉已显示的前缀，每次都重来一遍就像没开流式。
        // 只有真正换段（收轮后 smoothRunning 复位）或换代才重新起步
        if (smoothStream && !snapshot.equals(lastStreamTrace)
                && (!smoothRunning || smoothGeneration != generation)) {
            beginSmoothRound(generation);
        }
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
        final boolean append = isRequestInProgress.get()
                && SettingsActivity.isRealtimeRenderEnabled(this)
                && base0.length() > 0 && snapshot.startsWith(base0);
        final String part = append ? snapshot.substring(base0.length()) : snapshot;

        final int mySeq = traceRenderSeq.incrementAndGet();
        new Thread(new Runnable() {
            public void run() {
                final String html = MessageHtmlRenderer.contentToHtml(part, MainActivity.this, false);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (requestGeneration.get() != generation) return;
                        if (traceRenderSeq.get() != mySeq) {
                            LogUtil.d(TAG, "trace render superseded: seq %d/%d", mySeq, traceRenderSeq.get());
                            return;
                        }
                        // tick 与 trace 可能并发落地，StreamRenderer 已有
                        // !baseNow.equals(baseRendered()) 二次校验兜住旧 base；
                        // 这里再 bump renderSeq 反而会把同帧刚发出的 tail tick
                        // 判过期，导致流式丢帧。只做幂等判重，不动序号。
                        if (!snapshot.equals(lastStreamTrace)) {
                            lastStreamTrace = snapshot;
                            // 追加模式在关掉实时渲染时不成立：那份 trace 的结尾是本轮
                            // 正文纯文本，DOM 里正文在 tail 上，追加进去会让屏幕上多一份。
                            // 纯文本模式下统一走 streamBase 重建，并把 tail 的正文带过来。
                            if (append) {
                                webViewEvalJs("streamAppendBase('" + jsEscape(html) + "')");
                            } else {
                                // 只写 base 层；tail 上正在流的内容原样保留
                                webViewEvalJs("streamBase('" + jsEscape(html) + "',"
                                        + aiIndex + ")");
                            }
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

    /**
     * 会话用量状态栏：把本会话所有 AI 消息的用量累加一次。
     *
     * <p>只累加端点真的报过的数（含标了 {@code ~} 的估算值），不在这里二次估算：
     * 这一行的用途是对账，编出来的数字没有对账价值。命中率分母固定用 promptTokens——
     * 一次请求里只有输入侧能被缓存。
     */
    private void updateUsageBar() {
        if (usageBar == null) return;
        long prompt = 0, completion = 0, cached = 0, total = 0, genMs = 0;
        int n = messages == null ? 0 : messages.size();
        for (int i = 0; i < n; i++) {
            Message m = messages.get(i);
            if (m == null || !m.isAssistant()) continue;
            if (m.promptTokens > 0) prompt += m.promptTokens;
            if (m.completionTokens > 0) completion += m.completionTokens;
            if (m.cachedTokens > 0) cached += m.cachedTokens;
            if (m.totalTokens > 0) total += m.totalTokens;
            // 打断的消息没有 finishedAt，不参与速度统计，否则会把速度拉低
            if (m.finishedAt > m.timestamp && !m.interrupted) {
                genMs += m.finishedAt - m.timestamp;
            }
        }
        if (total <= 0 && prompt + completion > 0) total = prompt + completion;
        if (total <= 0) {
            usageBar.setVisibility(View.GONE);
            return;
        }
        StringBuilder sb = new StringBuilder(Strings.get(R.string.mainactivity_076));
        sb.append(MessageHtmlRenderer.formatTokens(
                (int) Math.min(total, Integer.MAX_VALUE))).append(" tokens");
        // 明细单独一行：11sp 下「本会话 1.1k tokens · 命中 800（72%） · 33.4 tok/s」
        // 约 280dp，而 258dp 屏的可用宽只有 230dp，单行必被 ellipsize 截成省略号。
        // 第一行放总量（对账的主数字），第二行放命中与速度（明细），两行都不省。
        StringBuilder detail = new StringBuilder();
        if (cached > 0) {
            detail.append(Strings.get(R.string.mainactivity_077)).append(MessageHtmlRenderer.formatTokens(
                    (int) Math.min(cached, Integer.MAX_VALUE)));
            if (prompt > 0) {
                detail.append("（").append(Math.round(cached * 100f / prompt)).append("%）");
            }
        }
        if (completion > 0 && genMs >= 1000) {
            if (detail.length() > 0) detail.append(" · ");
            detail.append(String.format(Locale.US, "%.1f tok/s",
                    completion * 1000.0 / genMs));
        }
        if (detail.length() > 0) {
            sb.append("\n").append(detail);
        }
        usageBar.setText(sb.toString());
        usageBar.setVisibility(View.VISIBLE);
    }

    private void refreshWebView() {
        refreshWebView(false);
    }

    /**
     * @param recovery 这次是「页面迟迟不就绪」触发的补灌。补灌本身不再重置
     *                 {@link #pageReloadRetried}，否则补灌失败后又能补灌，会无限循环。
     */
    private void refreshWebView(final boolean recovery) {
        LogUtil.d(TAG, "refreshWebView: messages=%d recovery=%s",
                messages == null ? -1 : messages.size(), recovery);
        final long t0 = System.currentTimeMillis();
        // 渲染代数守卫 + 列表快照：两次刷新可能乱序落地，且渲染线程不能直接遍历
        // 可能被 UI 线程结构性修改的 messages
        final int myRenderGen = renderGeneration.incrementAndGet();
        // 每次灌页换一版：页面 HTML 里带着这个号，回调读回来一比就知道 DOM 是哪一版。
        // 在真正下发之前，所有追加类脚本都排队，不会写进即将被冲掉的旧 DOM。
        final int myPageVersion = pageVersion.incrementAndGet();
        // 这一版页面画的是哪个列表：追加脚本靠它认领自己的页面（见 __acMsgList）
        final int myMsgListId = msgListId.incrementAndGet();
        curMsgListId = myMsgListId;
        pageLoading = true;
        if (!recovery) pageReloadRetried = false;
        // 清掉针对旧页面排的队列：这一版页面会把当前列表整体画一遍，
        // 旧页面积压的追加操作重放出来只会重复。
        pendingJs.clear();
        // 切会话遮罩还开着时，把「等待的渲染」迁移到最新一代：期间若因主题变化、
        // 上滑加载等又触发一次重灌，应等最后一次页面就绪再收遮罩，既不能被旧回调
        // 提前关掉，也不能因为旧渲染被丢弃而永远不收。
        if (conversationRenderState != 0) conversationRenderGen = myRenderGen;
        final List<Message> listAtSnapshot = messages;
        final int sizeAtSnapshot = messages == null ? 0 : messages.size();
        final List<Message> snapshot =
                messages == null ? new ArrayList<Message>() : new ArrayList<Message>(messages);
        // 只渲染最近一批：更早的消息等用户上滑到顶部再追加
        final int from = Math.max(0, snapshot.size() - INITIAL_VISIBLE_MESSAGES);
        new Thread(new Runnable() {
            public void run() {
                if (conversationRenderState != 0 && conversationRenderGen == myRenderGen) {
                    reportConversationRenderStage(
                            Strings.get(R.string.mainactivity_078, (snapshot.size() - from)), 68);
                }
                // 渲染线程必须有兜底：一旦这里抛异常（例如公式提取遇到截断的 \ce{A ->[），
                // runOnUiThread 永不执行 → 页面永远不就绪，后续所有 JS 只会进队列，
                // 整页空白直到用户重进会话。宁可加载一张空骨架，也不能让页面卡死。
                String html = null;
                try {
                    html = MessageHtmlRenderer.buildConversationHtml(
                            snapshot, from, MainActivity.this, myPageVersion, myMsgListId);
                    LogUtil.d(TAG, "conversation html built: len=%d in %d ms",
                            html == null ? -1 : html.length(), System.currentTimeMillis() - t0);
                } catch (Throwable t) {
                    LogUtil.e(TAG, "buildConversationHtml failed, fallback to empty page: %s",
                            String.valueOf(t));
                }
                final String page = html != null
                        ? html : MarkdownPipeline.buildPage("", from, myPageVersion, myMsgListId);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (renderGeneration.get() != myRenderGen) {
                            LogUtil.d(TAG, "refreshWebView: stale render dropped (gen=%d, current=%d)",
                                    myRenderGen, renderGeneration.get());
                            return;
                        }
                        if (conversationWebView == null) return;
                        // 拼页期间列表又变了（用户在渲染窗口里发了消息）：这一版缺最后几条，
                        // 而排队的追加也已随换版被清掉。重来一次，最多连着重来三次。
                        if (renderRetryCount < 3 && (messages != listAtSnapshot
                                || (messages == null ? 0 : messages.size()) != sizeAtSnapshot)) {
                            renderRetryCount++;
                            LogUtil.d(TAG, "refreshWebView: list changed during render, retry %d",
                                    renderRetryCount);
                            refreshWebView();
                            return;
                        }
                        renderRetryCount = 0;
                        renderedFrom = from;
                        loadingOlderMessages = false;
                        // 整页重建，DOM 里不存在上一次流式留下的 base 骨架，
                        // 「已推送基准」必须作废，否则下一次 trace 渲染会误走增量
                        lastStreamTrace = "";
                        conversationWebView.loadDataWithBaseURL(null, page, "text/html", "UTF-8", null);
                        // 从现在起开始确认这一版有没有上屏。onPageFinished 一般会来，
                        // 但程序化写 DOM 时它不保证触发，所以另起一个短周期探测兜底。
                        pageProbeTries = 0;
                        handler.removeCallbacks(pageProbeTask);
                        handler.postDelayed(pageProbeTask, 60);
                        // 绝对兜底：探测本身出问题（脚本抛错、回调不执行）时也不能
                        // 把页面锁死，到点无条件放行
                        handler.removeCallbacks(pageReadyWatchdog);
                        handler.postDelayed(pageReadyWatchdog, 2500);
                        // 灌页之后把遮罩的收起权交给「确认就绪」（或超时兜底），
                        // 不再像旧版那样固定等 260ms —— 那会儿页面往往还没画完。
                        awaitConversationPageFinish(myRenderGen);
                        // 新 DOM 的 style 节点已重建，清掉 CSS 相等短路，
                        // 否则下一次外观变更会跳过注入、样式停在初始值。
                        if (webRefresher != null) webRefresher.reset();
                        updateUsageBar();
                        LogUtil.d(TAG, "webview reloaded: showing %d of %d messages (from=%d, v=%d)",
                                snapshot.size() - from, snapshot.size(), from, myPageVersion);
                    }
                });
            }
        }) {{ setName("aichat-render-" + System.currentTimeMillis()); }}.start();
    }

    /**
     * 读回 WebView 里实际是哪一版页面。
     *
     * <p>onPageFinished 的 URL 恒为 about:blank，判不出回调属于哪一次加载。
     * 页面自己带着 {@code data-ac-page}，读回来和当前版本比一次就能定论：
     * 相等说明这版已经上屏，可以下发追加脚本；不相等说明 DOM 还是上一版
     * （或新页还没解析完），继续等。
     */
    private void probePageReady() {
        if (conversationWebView == null || !pageLoading) return;
        if (android.os.Build.VERSION.SDK_INT < 19) {
            // API 18 没有带回调的 evaluateJavascript，只能直接放行
            markPageReady(pageVersion.get());
            return;
        }
        pageProbeTries = 0;
        handler.removeCallbacks(pageProbeTask);
        handler.post(pageProbeTask);
    }

    /** 兜底计时：无论探测成没成，到点都让页面可用。 */
    private final Runnable pageReadyWatchdog = new Runnable() {
        public void run() {
            if (!pageLoading) return;
            LogUtil.w(TAG, "page ready watchdog fired (v=%d)", pageVersion.get());
            markPageReady(pageVersion.get());
        }
    };

    /** 短周期探测；页面确认上屏就放行，迟迟读不回来就补灌一次。 */
    private final Runnable pageProbeTask = new Runnable() {
        public void run() {
            final WebView wv = conversationWebView;
            if (wv == null || !pageLoading) return;
            final int want = pageVersion.get();
            if (android.os.Build.VERSION.SDK_INT < 19) {
                markPageReady(want);
                return;
            }
            try {
                wv.evaluateJavascript(
                        "(function(){var b=document.body;return b&&b.getAttribute"
                                + "?(''+b.getAttribute('data-ac-page')):'';})()",
                        new android.webkit.ValueCallback<String>() {
                            public void onReceiveValue(String value) {
                                if (want != pageVersion.get()) {
                                    // 期间又换了一版：这次结果作废，但探测链不能断，
                                    // 否则新版本没人去确认，页面就又「永远不就绪」了
                                    handler.removeCallbacks(pageProbeTask);
                                    handler.postDelayed(pageProbeTask, 60);
                                    return;
                                }
                                if (pageVersionOf(value) == want) {
                                    markPageReady(want);
                                } else {
                                    retryProbe();
                                }
                            }
                        });
            } catch (Throwable t) {
                LogUtil.w(TAG, "probe page version failed: %s", String.valueOf(t));
                retryProbe();
            }
        }
    };

    /** evaluateJavascript 的回调值是 JSON：字符串带引号，取不到值是 null 或空串。 */
    private static int pageVersionOf(String raw) {
        if (raw == null) return -1;
        String v = raw.trim();
        if (v.length() >= 2 && v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"') {
            v = v.substring(1, v.length() - 1);
        }
        if (v.length() == 0 || "null".equals(v)) return -1;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void retryProbe() {
        if (!pageLoading) return;
        pageProbeTries++;
        if (pageProbeTries > 12) {
            // 约一秒还读不回自己的版本：页面多半加载失败、或被系统丢弃了。
            // 补灌一次；补灌后再等一轮还不成，也必须放行 —— 宁可少几条气泡，
            // 也不能把页面锁在「未就绪」里，那会让之后每一条消息都进队列。
            if (!pageReloadRetried) {
                pageReloadRetried = true;
                LogUtil.w(TAG, "page never became ready, reload once (v=%d)", pageVersion.get());
                refreshWebView(true);
            } else {
                LogUtil.w(TAG, "page never became ready, give up waiting (v=%d)", pageVersion.get());
                markPageReady(pageVersion.get());
            }
            return;
        }
        handler.postDelayed(pageProbeTask, 80);
    }

    /** 这一版页面确认上屏：放行队列、收起遮罩。 */
    private void markPageReady(int version) {
        if (version != pageVersion.get()) return;
        if (loadedPageVersion == version && !pageLoading) return;
        loadedPageVersion = version;
        pageLoading = false;
        pageProbeTries = 0;
        pageReloadRetried = false;
        handler.removeCallbacks(pageProbeTask);
        handler.removeCallbacks(pageReadyWatchdog);
        LogUtil.d(TAG, "page ready: v=%d, replay %d deferred js", version, pendingJs.size());
        flushPendingJs();
        finishConversationRender(false);
        verifyLastAppend();
        if (conversationWebView != null) conversationWebView.invalidate();
    }

    /**
     * 核对「刚发出去的那条消息」是否真的落进了 DOM。
     *
     * <p>前面已经有三道闸门（版本号、队列重放、页面归属），但闸门只保证「脚本发给了
     * 正确的页面」，保证不了「脚本确实执行了」——页面此时可能正好被系统丢弃、
     * 元素被同一帧的整页重建冲掉。这里读回一次 DOM 做最终确认，不认账就整页重画
     * 一次（此时消息已在 messages 里，重画必然带上它）。
     *
     * <p>只核对一次：重画之后无论结果如何都收手，避免出错时死循环。
     */
    private void verifyLastAppend() {
        if (!lastAppendCheckPending) return;
        lastAppendCheckPending = false;
        final int idx = lastAppendedIdx;
        if (idx < 0 || android.os.Build.VERSION.SDK_INT < 19) return;
        final WebView wv = conversationWebView;
        if (wv == null) return;
        final int versionAtCheck = pageVersion.get();
        handler.postDelayed(new Runnable() {
            public void run() {
                if (versionAtCheck != pageVersion.get() || wv != conversationWebView) return;
                try {
                    wv.evaluateJavascript(
                            "(function(){return document.querySelector('.msg[data-idx=\""
                                    + idx + "\"]')?'1':'0';})()",
                            new android.webkit.ValueCallback<String>() {
                                public void onReceiveValue(String value) {
                                    // evaluateJavascript 回传的是 JSON：'1' / '0'
                                    boolean present = "1".equals(cleanJson(value));
                                    LogUtil.d(TAG, "verify append idx=%d present=%s", idx, present);
                                    if (present) return;
                                    // 还不在这版页面上：整页重画一次，消息列表里已经有它了
                                    LogUtil.w(TAG, "appended message missing from DOM, redraw once");
                                    refreshWebView();
                                }
                            });
                } catch (Throwable t) {
                    LogUtil.w(TAG, "verify append failed: %s", String.valueOf(t));
                }
            }
        }, 260);
    }

    /** evaluateJavascript 的回调值是 JSON：字符串带引号，null/undefined 各有写法。 */
    private static String cleanJson(String raw) {
        if (raw == null) return "";
        String v = raw.trim();
        if (v.length() >= 2 && v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"') {
            v = v.substring(1, v.length() - 1);
        }
        return "null".equals(v) ? "" : v;
    }

    /** 页面当前这版内容已经就绪：追加类脚本可以直接下发。 */
    private boolean pageReady() {
        return !pageLoading && loadedPageVersion == pageVersion.get();
    }

    // ============ 逐字渐显 ============

    /**
     * 本次请求的渐显缓冲与节奏。
     *
     * <p>为什么节奏放在 Java 侧而不是 CSS 动画里：流式片段每帧都会整段替换，
     * CSS 动画会跟着被重置，长回答越到后面越像「一顿一顿地蹦字」。
     * 由一个定时器按固定间隔往前推，帧率与内容量无关，低端机上也不会越写越慢。
     */
    private final SmoothTail smoothTail = new SmoothTail();
    private volatile boolean smoothRunning;
    /** 手指按在气泡上：由页面脚本在抬手/取消时复位。 */
    private volatile boolean smoothPaused;
    /** 渐显正在服务的那次请求代数：旧气泡上的「直接显示」不能打到新回复上。 */
    private volatile int smoothGeneration;
    /** 上一拍发给页面的整段片段：只推进度时不必重发同一份 HTML。 */
    private String smoothPushedHtml = "";
    /** 上一拍已经显示到的字符数（页面侧同一份片段上的切点）。 */
    private int smoothShownChars = -1;
    /** 显示已经追上到达，页面侧暂时不必再收新片段。 */
    private boolean toldPageStop;
    /** 「直接显示」挂出前的等待：至少要浮出一小段，否则看着像误触。 */
    private static final long SMOOTH_HINT_DELAY_MS = 700;

    /**
     * 一次渐显推进。
     *
     * <p>顺序有讲究：先取尚未显示的完整片段写进透明占位层，再从缓冲里取前缀
     * 写进可见层。中间任何一帧被丢弃，下一次推进都会把这两层重新写成完整状态，
     * 不会留下半截内容。
     */
    private final Runnable smoothTick = new Runnable() {
        public void run() {
            if (!smoothRunning) return;
            if (!uiVisible || !pageReady()) {
                // 后台或页面不可用：先不推进，回到前台再继续，不能让内容压在缓冲里
                handler.postDelayed(this, 250);
                return;
            }
            // 手指按在气泡上（阅读、选字、拖表格）：停一下，抬手后接着浮
            if (smoothPaused) {
                handler.postDelayed(this, 120);
                return;
            }
            smoothTail.step();
            pushSmoothFrame();
            if (smoothTail.hasPending()) {
                handler.postDelayed(this, 1000L / SmoothTail.STEPS_PER_SECOND);
                return;
            }
            // 显示追上到达：等着下一帧，别空转
            if (smoothTail.finished()) {
                smoothRunning = false;
                setRevealBubble(false);
                return;
            }
            handler.postDelayed(this, 60);
        }
    };

    /**
     * 把「已到达的片段 + 已显示到第几字」推给页面。
     *
     * <p>片段没变时只发进度（页面按位置挪切点，不重建 DOM）；片段变了才整段重发。
     * 两件事必须一起下发：只发进度会让页面在旧片段上切，只发片段会把进度丢掉。
     */
    private void pushSmoothFrame() {
        final int idx = smoothIdx();
        final String arrived = smoothTail.arrivedHtml();
        final int adv = smoothTail.shownOffset();
        if (arrived.equals(smoothPushedHtml)) {
            if (adv == smoothShownChars) return;
            smoothShownChars = adv;
            webViewEvalJs("__smoothStep(" + adv + "," + idx + ")");
            return;
        }
        smoothPushedHtml = arrived;
        smoothShownChars = adv;
        webViewEvalJs("__smoothPush('" + jsEscape(arrived) + "'," + adv + "," + idx + ")");
    }

    /**
     * 新的一段正文开始渐显：清空缓冲，让第一屏内容立刻出现。
     *
     * <p>同时把定时器拉起来。只有真正有文字到达时才会拿到这个调用，
     * 所以不必在发请求那一刻就空转。
     */
    private void beginSmoothRound(final int generation) {
        if (!smoothStream || requestGeneration.get() != generation) return;
        smoothGeneration = generation;
        smoothTail.beginRound();
        smoothPushedHtml = "";
        smoothShownChars = -1;
        webViewEvalJs("__smoothStart(" + smoothIdx() + ")");
        if (!smoothRunning) {
            smoothRunning = true;
            handler.removeCallbacks(smoothTick);
            handler.postDelayed(smoothTick, 1000L / SmoothTail.STEPS_PER_SECOND);
        }
    }

    /** 「直接显示」提示是否已经排在一秒后挂出来；只在第一次进入时调度。 */
    private boolean showHintScheduled;

    /** 把这一帧已到达的正文交给渐显：只记账，显示多少由定时器决定。 */
    private void feedSmooth(final String html, final int generation) {
        if (!smoothStream || requestGeneration.get() != generation) return;
        if (!smoothRunning) {
            // 上一段已经放完（定时器退出）或还没起步：内容到了就得把节奏拉起来，
            // 否则这一段会压在缓冲里，直到收尾才一次性冒出来
            smoothRunning = true;
            handler.removeCallbacks(smoothTick);
        }
        if (!smoothTail.onHtml(html)) return;
        // 新片段立刻交给页面：占位层先把版式撑开，读起来不会「先变短再变长」
        pushSmoothFrame();
        handler.removeCallbacks(smoothTick);
        handler.postDelayed(smoothTick, 1000L / SmoothTail.STEPS_PER_SECOND);
        // 至少要浮出一小段才值得给出口：刚出第一个字就挂气泡，看着像误触
        if (!showHintScheduled && smoothTail.shownChars() > 0
                && smoothTail.charCount() > 0) {
            showHintScheduled = true;
            handler.removeCallbacks(smoothHintTask);
            handler.postDelayed(smoothHintTask, SMOOTH_HINT_DELAY_MS);
        }
    }

    /** 页面脚本与「直接显示」气泡共用的入口：只在 UI 线程动定时器与 View。 */
    private void revealCurrentSmooth() {
        runOnUiThread(new Runnable() {
            public void run() {
                // 只对「还在跑的那一条」生效：旧气泡上的残留按钮不碰当前回复
                if (requestGeneration.get() != smoothGeneration) return;
                revealSmooth(smoothGeneration);
            }
        });
    }

    /**
     * 跳过本次渐显：剩下的字一次显示完，气泡收起，正文一个不少。
     */
    private void revealSmooth(final int generation) {
        if (!smoothStream || smoothGeneration != generation) return;
        handler.removeCallbacks(smoothTick);
        handler.removeCallbacks(smoothHintTask);
        showHintScheduled = false;
        smoothTail.finish();
        smoothRunning = false;
        setRevealBubble(false);
        pushSmoothFrame();
        webViewEvalJs("__smoothFinish('" + jsEscape(smoothTail.arrivedHtml()) + "',"
                + smoothIdx() + ")");
    }

    /**
     * 「直接显示」的挂出时机。
     *
     * <p>等文字已经全部到达、只剩浮出动画时才挂，才是真正「点了有区别」的时刻；
     * 提前挂出来，用户点完看到的还是同一段慢慢浮的字，会觉得没反应。
     * 一律从当前一拍开始算延迟，快速连续的小帧不会反复重置。
     */
    private final Runnable smoothHintTask = new Runnable() {
        public void run() {
            showHintScheduled = false;
            if (!smoothRunning || smoothTail.finished()) return;
            setRevealBubble(true);
        }
    };

    /** 「直接显示」气泡是否已经挂出来：只在状态真的变化时才动 View。 */
    private volatile boolean revealBubbleShown;

    /** 「直接显示」气泡的显隐。与「回到底部」同一套外观，浮在输入框左上方。 */
    private void setRevealBubble(final boolean show) {
        if (revealBubbleShown == show) return;
        revealBubbleShown = show;
        runOnUiThread(new Runnable() {
            public void run() {
                View b = findViewById(R.id.smooth_reveal_button);
                if (b != null) b.setVisibility(revealBubbleShown ? View.VISIBLE : View.GONE);
            }
        });
    }

    /** 停止渐显：停在当前显示的位置，不把剩下的字一次性甩出来。 */
    private void stopSmooth(final int generation) {
        handler.removeCallbacks(smoothTick);
        handler.removeCallbacks(smoothHintTask);
        smoothRunning = false;
        showHintScheduled = false;
        smoothPushedHtml = "";
        smoothShownChars = -1;
        setRevealBubble(false);
        webViewEvalJs("__smoothStop()");
    }

    /** 收尾：剩下的全部放完，随后由完整渲染接管这条气泡。 */
    private void finishSmooth(final int generation) {
        if (!smoothStream) return;
        handler.removeCallbacks(smoothTick);
        handler.removeCallbacks(smoothHintTask);
        smoothRunning = false;
        showHintScheduled = false;
        smoothTail.finish();
        smoothPushedHtml = "";
        smoothShownChars = -1;
        setRevealBubble(false);
        webViewEvalJs("__smoothFinish('" + jsEscape(smoothTail.arrivedHtml()) + "',"
                + smoothIdx() + ")");
    }

    /** 渐显当前服务的气泡下标；拿不到（尚未起请求）时给 -1，由页面按最后一条兜底。 */
    private int smoothIdx() {
        Channel c = activeChannel;
        return c == null ? -1 : c.uiIdx;
    }

    private void appendHtml(String msgHtml) {
        appendHtml(msgHtml, -1);
    }

    /** @param idx 这条气泡的 data-idx；-1 表示不指定 */
    private void appendHtml(String msgHtml, int idx) {
        String esc = jsEscape(msgHtml);
        LogUtil.v(TAG, "webview js: appendMsg(idx=%d, len=%d)", idx, esc.length());
        evalAppendJs("appendMsg('" + esc + "')", idx);
    }

    private void updateAiContent(final String content) {
        // 渲染代数守卫：异步渲染期间若 refreshWebView 已换页（如切会话），丢弃过期注入
        final int myRenderGen = renderGeneration.get();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String html = MessageHtmlRenderer.contentToHtml(content, MainActivity.this, false);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (renderGeneration.get() != myRenderGen) return;
                        String esc = jsEscape(html);
                        LogUtil.v(TAG, "webview js: updateLastMsg(htmlLen=%d)", esc.length());
                        webViewEvalJs("updateLastMsg('" + esc + "'," + smoothIdx() + ")");
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

    /**
     * 往消息列表末尾追加的脚本（{@code appendMsg} 一类）。
     *
     * <p>只有「当前这一版页面已经确认上屏」时才直接写。否则先排队：页面可能正在
     * 被新一版 HTML 替换，此刻 eval 的结果会随旧文档一起丢掉 —— 表现就是
     * 「发消息后界面一点变化都没有，重进历史才看得到」。
     *
     * <p>排队顺序必须保留：{@code appendMsg} 一定排在紧随其后的
     * {@code appendAiDiv} 之前，否则 AI 气泡会跑到用户气泡上面。
     */
    private void evalAppendJs(final String js) {
        evalAppendJs(js, -1);
    }

    /**
     * @param idx 目标气泡的 data-idx；-1 表示由页面按「最后一条 AI」自己定位。
     *            带上它之后，即使页面在这条消息之后又被整页重建过，脚本也能找到
     *            正确的气泡——旧实现只认「最后一个 .msg.ai」，迟到一帧就写错地方。
     */
    private void evalAppendJs(final String js, final int idx) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            handler.post(new Runnable() {
                public void run() {
                    evalAppendJs(js, idx);
                }
            });
            return;
        }
        if (conversationWebView == null) return;
        final String owned = ownedJs(js, idx);
        if (!pageReady()) {
            // 没有任何一版页面在加载（首次进入、上次加载被系统丢弃）：补一次整页灌入，
            // 否则这条气泡会一直排在队列里，直到用户手动重进会话才出现
            if (!pageLoading) {
                LogUtil.w(TAG, "evalAppendJs: no page loaded, reloading conversation");
                refreshWebView();
            }
            if (pendingJs.size() < 300) pendingJs.add(owned);
            LogUtil.d(TAG, "evalAppendJs: queued (loading=%s, loaded=%d, current=%d)",
                    pageLoading, loadedPageVersion, pageVersion.get());
            return;
        }
        evalJsNow(conversationWebView, owned);
    }

    /**
     * 把脚本包一层「页面归属」判断：只在这一版页面确实画着当前消息列表时才执行。
     *
     * <p>为什么必须包：换版瞬间（loadDataWithBaseURL 之后到新页解析完之前）下发的脚本
     * 会落在即将被替换掉的旧文档上，或者在两版页面之间串台。脚本执行了但 DOM 随后
     * 被整页冲掉，用户看到的就是「发完消息毫无变化」。
     */
    private String ownedJs(String js, int idx) {
        int list = curMsgListId;
        String call = idx >= 0 ? js + "," + idx : js;
        return "(function(){var m=window.__acMsgList;"
                + "if(m===undefined||m===" + list + "){" + call + ";}})()";
    }

    // Execute JS in the conversation WebView: evaluateJavascript (API 19+) avoids a
    // full page navigation per call; loadUrl fallback for API 18.
    private void webViewEvalJs(final String js) {
        // WebView 的所有方法都必须在创建它的线程（主线程）上调用，否则抛
        // RuntimeException("A WebView method was called on thread ...")。
        // 请求线程会通过 Ui.onStatus / onTrace 回调直接走到这里（第一轮 trace 为空时
        // renderTraceAsync 只更新状态行，那条快路径就在 stream 线程上），
        // 所以在唯一的出口做线程编组，而不是指望每个调用点自己记得 post。
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            handler.post(new Runnable() {
                public void run() {
                    webViewEvalJs(js);
                }
            });
            return;
        }
        // onDestroy 后 WebView 已 destroy 并置空，迟到的回调直接丢弃
        WebView wv = conversationWebView;
        if (wv == null) return;
        // 页面换版期间下发的脚本会被随后的导航冲掉，先排队等确认就绪再重放
        if (!pageReady()) {
            // 没有正在进行的加载：先补一次整页灌入（它自带版本自增与队列清空，
            // 所以必须在入队之前调用），再把这条排在后面
            if (!pageLoading) {
                LogUtil.w(TAG, "webViewEvalJs: no page loaded, reloading conversation");
                refreshWebView();
            }
            if (pendingJs.size() < 300) pendingJs.add(ownedJs(js, -1));
            return;
        }
        evalJsNow(wv, ownedJs(js, -1));
    }

    /** 真正下发一段脚本；调用方负责线程与就绪判断。 */
    private void evalJsNow(WebView wv, String js) {
        if (android.os.Build.VERSION.SDK_INT >= 19) {
            try {
                wv.evaluateJavascript(js, null);
            } catch (Throwable t) {
                // 页面正在销毁 / WebView 已 detach：这几帧本来也写不进去了，
                // 抛出去会让调用方（UI 回调、定时器）整条链断掉
                LogUtil.w(TAG, "evaluateJavascript failed: %s", String.valueOf(t));
            }
        } else {
            wv.loadUrl("javascript:" + TextKit.forJsUrl(js));
        }
    }

    /** onPageFinished / onReceivedError 后把加载期间排队的脚本按原顺序重放。 */
    private void flushPendingJs() {
        if (pendingJs.isEmpty()) return;
        String[] all = pendingJs.toArray(new String[0]);
        pendingJs.clear();
        LogUtil.d(TAG, "flushPendingJs: replay %d deferred js", all.length);
        for (String js : all) {
            webViewEvalJs(js);
        }
    }

    private static String jsEscape(String s) {
        if (s == null) return "";
        // 单遍扫描，等价于原先 6 次 String.replace 的链式结果：
        // 每类字符只按原字符映射一次，不会二次转义新插入的反斜杠。
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': out.append("\\\\"); break;
                case '\'': out.append("\\'"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                // ES5 里 U+2028/U+2029 仍是行终止符，字面量里必须转成转义序列
                case '\u2028': out.append("\\u2028"); break;
                case '\u2029': out.append("\\u2029"); break;
                default: out.append(c);
            }
        }
        return out.toString();
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
                        AcToast.show(MainActivity.this, Strings.get(R.string.mainactivity_080));
                    } catch (Exception e1) {
                        LogUtil.w(TAG, "copyCode via ClipboardManager failed: %s, trying legacy API", e1.getMessage());
                        try {
                            android.text.ClipboardManager oldCm = (android.text.ClipboardManager)
                                    getSystemService(Context.CLIPBOARD_SERVICE);
                            oldCm.setText(code);
                            AcToast.show(MainActivity.this, Strings.get(R.string.mainactivity_081));
                        } catch (Exception e2) {
                            LogUtil.e(TAG, "copyCode failed entirely: " + e2.getMessage(), e2);
                            AcToast.show(MainActivity.this, Strings.get(R.string.mainactivity_082, e2.getMessage()));
                        }
                    }
                }
            });
        }

        /** 「直接显示」：跳过本次渐显，立刻显示全文。 */
        @JavascriptInterface
        public void smoothReveal() {
            LogUtil.d(TAG, "JsBridge.smoothReveal");
            revealCurrentSmooth();
        }

        /** 手指按在气泡上：暂停往外放字，抬手后继续。 */
        @JavascriptInterface
        public void smoothPause(final boolean paused) {
            smoothPaused = paused;
        }

        /** 429 等待卡片上的「立即重试」。只置位，等待循环下一轮就读到。 */
        @JavascriptInterface
        public void retryNow() {
            LogUtil.i(TAG, "JsBridge.retryNow: skip 429 wait");
            retryNowRequested.set(true);
        }

        /**
         * 回到底部气泡的显隐。页面每滚一下都会调，所以这里必须去重：
         * Chromium 30 的 scroll 事件在惯性滑动时能到每秒几十次，
         * 每次都 setVisibility 会让主线程忙于重排。
         */
        @JavascriptInterface
        public void setScrollBubble(final boolean show) {
            if (show == scrollBubbleVisible) return;
            scrollBubbleVisible = show;
            handler.post(new Runnable() {
                public void run() {
                    View b = findViewById(R.id.scroll_to_bottom_button);
                    if (b != null) b.setVisibility(show ? View.VISIBLE : View.GONE);
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
                AcToast.show(MainActivity.this, Strings.get(R.string.mainactivity_083));
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

        /**
         * 中断后继续生成：保留已生成的正文，从断点接着往下写。
         * 官方 DeepSeek 走 /beta 前缀续写，其余服务商降级为上下文续写。
         */
        @JavascriptInterface
        public void continueGeneration(final String idx) {
            LogUtil.d(TAG, "JsBridge.continueGeneration: idx=%s", idx);
            handler.post(new Runnable() {
                public void run() {
                    try {
                        doContinueGeneration(Integer.parseInt(idx));
                    } catch (Exception e) {
                        LogUtil.w(TAG, "continueGeneration failed: %s", e.getMessage());
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
                            AcToast.show(MainActivity.this, Strings.get(R.string.mainactivity_084));
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
                            AcToast.show(MainActivity.this, Strings.get(R.string.mainactivity_085));
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
                        if (detail == null || detail.length() == 0) detail = Strings.get(R.string.mainactivity_086);
                        TextView tv = new TextView(MainActivity.this);
                        tv.setText(detail);
                        tv.setTextSize(12);
                        tv.setTextIsSelectable(true);
                        tv.setPadding(24, 24, 24, 24);
                        android.widget.ScrollView sv = new android.widget.ScrollView(MainActivity.this);
                        sv.addView(tv);
                        AcDialog.builder(MainActivity.this)
                                .setTitle(Strings.get(R.string.mainactivity_087))
                                .setView(sv)
                                .setPositiveButton(Strings.get(R.string.mainactivity_088), null)
                                .show();
                    } catch (Exception e) {
                        LogUtil.w(TAG, "showErrorDetail failed: %s", e.getMessage());
                    }
                }
            });
        }

        /**
         * 图片搜索结果点开：下载 → 采样 → data URI → 交给图片查看器。
         *
         * <p>不把远端 URL 直接喂给 WebView：查看器用的是 {@code loadDataWithBaseURL(null,…)}，
         * 页面 origin 是 null，加载外链图片会被当跨源资源拦掉，表现为「点开一片空白」。
         */
        @JavascriptInterface
        public void openRemoteImage(final String url, final String title) {
            if (url == null || url.length() == 0) return;
            LogUtil.d(TAG, "JsBridge.openRemoteImage: %s", LogUtil.preview(url, 120));
            handler.post(new Runnable() {
                public void run() {
                    AcToast.show(MainActivity.this, Strings.get(R.string.mainactivity_089));
                    new Thread(new Runnable() {
                        public void run() {
                            String src = null;
                            String err = null;
                            try {
                                src = AttachmentFiles.remoteToDataUri(url, 1600);
                            } catch (Exception e) {
                                err = e.getMessage();
                            } catch (OutOfMemoryError e) {
                                err = Strings.get(R.string.mainactivity_090);
                            }
                            final String data = src;
                            final String error = err;
                            runOnUiThread(new Runnable() {
                                public void run() {
                                    if (viewer == null) return;
                                    if (data == null) {
                                        AcToast.showLong(MainActivity.this, Strings.get(R.string.mainactivity_091,
                                            error == null ? Strings.get(R.string.mainactivity_014) : error));
                                        return;
                                    }
                                    try {
                                        JSONArray arr = new JSONArray();
                                        JSONObject o = new JSONObject();
                                        o.put("src", data);
                                        o.put("alt", title == null ? "" : title);
                                        o.put("type", "raster");
                                        arr.put(o);
                                        viewer.showImage(0, arr.toString());
                                    } catch (Exception e) {
                                        LogUtil.w(TAG, "openRemoteImage render failed: %s", e.getMessage());
                                    }
                                }
                            });
                        }
                    }, "aichat-open-remote").start();
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
                        AcToast.show(MainActivity.this, Strings.get(R.string.mainactivity_093));
                    }
                }
            });
        }

        /** 附件卡片上的「查看」：正文 / 图片两条路，具体分派在 {@link #openAttachmentAt}。 */
        @JavascriptInterface
        public void openAttachment(final String idxStr, final String attStr) {
            LogUtil.d(TAG, "JsBridge.openAttachment: idx=%s att=%s", idxStr, attStr);
            handler.post(new Runnable() {
                public void run() {
                    try {
                        openAttachmentAt(Integer.parseInt(idxStr), Integer.parseInt(attStr));
                    } catch (Exception e) {
                        LogUtil.w(TAG, "openAttachment failed: %s", e.getMessage());
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

        /**
         * actheme 代码块「导入主题」：照 saveCode 模板回主线程，三级校验（内部统一
         * 清洗 AI 输出）→ 原子写 themes/。只落盘并提示，不自动切换当前外观、
         * 不发网络、不读聊天记录。
         */
        @JavascriptInterface
        public void importTheme(final String text) {
            LogUtil.d(TAG, "JsBridge.importTheme: len=%s",
                    text == null ? "null" : text.length());
            handler.post(new Runnable() {
                public void run() {
                    try {
                        // 脱壳/清洗统一交给 ThemeValidator → ThemeText.clean()，
                        // 不再在这里手写只认 ```actheme 的围栏解析。
                        ThemeValidator.Result vr = ThemeValidator.validate(
                                text, FontRepository.getInstance().installedIds());
                        if (vr.blocked || vr.theme == null) {
                            AcToast.showLong(MainActivity.this, Strings.get(R.string.mainactivity_094, vr.blockReason));
                            return;
                        }
                        // 名称补名/截断已在 ThemeValidator 统一处理（自动补名 + 警告）
                        Theme t = vr.theme;
                        t.id = "";
                        t.builtin = false;
                        String id = ThemeRepository.getInstance().save(t);
                        if (id == null) {
                            AcToast.showLong(MainActivity.this, Strings.get(R.string.mainactivity_095));
                            return;
                        }
                        ThemeRepository.getInstance().invalidate();
                        String tip = vr.warnings.isEmpty()
                                ? Strings.get(R.string.mainactivity_096, t.name)
                                : Strings.get(R.string.mainactivity_098, t.name, vr.warnings.size());
                        AcToast.showLong(MainActivity.this, tip);
                    } catch (Throwable e) {
                        LogUtil.w(TAG, "importTheme failed: %s", e.getMessage());
                        AcToast.showLong(MainActivity.this, Strings.get(R.string.mainactivity_101, e.getMessage()));
                    }
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
                            AcToast.showLong(MainActivity.this, Strings.get(R.string.mainactivity_102, errMsg));
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
        String[] items = {Strings.get(R.string.mainactivity_103), Strings.get(R.string.mainactivity_104), Strings.get(R.string.mainactivity_105), Strings.get(R.string.mainactivity_106), Strings.get(R.string.mainactivity_107), Strings.get(R.string.mainactivity_108), Strings.get(R.string.mainactivity_109)};
        LogUtil.d(TAG, "showMessageMenu: pos=%d role=%s len=%s", pos, msg.role,
                msg.content == null ? "null" : msg.content.length());

        AcDialog.builder(this)
                .setTitle(Strings.get(R.string.mainactivity_110))
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        // 菜单挂起期间列表可能被删除/新增/打断清理移动，按对象标识重新定位
                        int cur = messages.indexOf(msg);
                        if (cur < 0) {
                            LogUtil.w(TAG, "message menu action dropped: target message no longer present");
                            AcToast.show(MainActivity.this, Strings.get(R.string.mainactivity_111));
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
                .setNegativeButton(Strings.get(R.string.mainactivity_112), null)
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
        String text = textWithAttachments(messages.get(pos));
        if (text == null || text.isEmpty()) {
            LogUtil.w(TAG, "copyMessage: empty content at pos=%d", pos);
            AcToast.show(this, Strings.get(R.string.mainactivity_113));
            return;
        }
        copyToClipboard(text);
    }

    /**
     * 复制/选择文本用的完整文本：原话 + 附件清单。
     *
     * <p>单独一个方法而不是塞进 {@link #getMessageText}：后者还给「修改消息」的
     * 输入框预填，把附件清单写进去，用户一保存就等于把附件又抄回了正文。
     */
    private String textWithAttachments(Message msg) {
        String text = getMessageText(msg);
        if (msg != null && msg.isUser()) {
            if (text == null) text = "";
            text += AttachmentText.copySuffix(msg.attachments);
        }
        return text;
    }

    // ========== 附件查看 ==========

    /**
     * 把消息下标与附件下标读成对象再打开。容器可能是跨线程的（回调来自 WebView），
     * 所以先 post 到主线程，再按「点开时此刻」的列表取值——期间用户可能已经删了这条消息。
     */
    private void openAttachmentAt(int pos, int attIdx) {
        if (viewer == null) return;
        if (pos < 0 || messages == null || pos >= messages.size()) {
            LogUtil.w(TAG, "openAttachmentAt: bad pos=%d", pos);
            return;
        }
        Message m = messages.get(pos);
        List<AttachmentText.File> files = m == null ? null : m.attachments;
        if (files == null || attIdx < 0 || attIdx >= files.size()) {
            LogUtil.w(TAG, "openAttachmentAt: bad att=%d (size=%s)",
                    attIdx, files == null ? "null" : String.valueOf(files.size()));
            return;
        }
        final AttachmentText.File f = files.get(attIdx);
        if (f == null) return;
        if (f.isImage()) {
            openImageAttachment(f);
            return;
        }
        String lang = FileKinds.languageOf(f.name);
        LogUtil.i(TAG, "openAttachment: name=%s lang=%s len=%d",
                f.name, lang.length() == 0 ? "(plain)" : lang, f.chars());
        viewer.showDocument(f.name, lang, f.content);
    }

    /**
     * 图片要走一遍解码 → 采样 → 压缩 → Base64，必须离开主线程。
     * 一张原图在主线程做这件事就是几百毫秒的白屏。
     */
    private void openImageAttachment(final AttachmentText.File f) {
        if (f.path.length() == 0) {
            AcToast.show(this, Strings.get(R.string.mainactivity_114));
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                final String src = AttachmentFiles.viewDataUri(f.path);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (viewer == null) return;
                        if (src == null) {
                            AcToast.showLong(MainActivity.this, Strings.get(R.string.mainactivity_115));
                            return;
                        }
                        try {
                            JSONArray arr = new JSONArray();
                            JSONObject o = new JSONObject();
                            o.put("src", src);
                            o.put("alt", f.name);
                            o.put("type", "raster");
                            arr.put(o);
                            viewer.showImage(0, arr.toString());
                        } catch (Exception e) {
                            LogUtil.w(TAG, "openImageAttachment failed: %s", e.getMessage());
                            AcToast.show(MainActivity.this, Strings.get(R.string.mainactivity_116));
                        }
                    }
                });
            }
        }, "aichat-open-image").start();
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
            AcToast.show(this, Strings.get(R.string.mainactivity_117));
        } catch (Exception e) {
            LogUtil.e(TAG, "copyToClipboard failed: " + e.getMessage(), e);
            AcToast.showLong(this, Strings.get(R.string.mainactivity_118, e.getMessage()));
        }
    }

    // 2. 选择文本
    private void selectText(int pos) {
        String text = textWithAttachments(messages.get(pos));
        LogUtil.d(TAG, "selectText: pos=%d len=%s", pos, text == null ? "null" : text.length());
        showSelectableText(Strings.get(R.string.mainactivity_119), text);
    }

    /** 弹一个可长按选区的只读文本框。流式中的气泡也用这个，只是没下标可取。 */
    private void showSelectableText(String title, String text) {
        final TextView tv = new TextView(this);
        tv.setText(text == null ? "" : text);
        tv.setTextIsSelectable(true);
        tv.setPadding(32, 32, 32, 32);
        tv.setTextSize(14);
        AcDialog.builder(this)
                .setTitle(title)
                .setView(tv)
                .setPositiveButton(Strings.get(R.string.mainactivity_120), null)
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
        final String[] items = {Strings.get(R.string.mainactivity_121), Strings.get(R.string.mainactivity_122), Strings.get(R.string.mainactivity_123), Strings.get(R.string.mainactivity_124)};
        AcDialog.builder(this)
                .setTitle(Strings.get(R.string.mainactivity_125))
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            copyStreamingText();
                        } else if (which == 1) {
                            String text = streamingText();
                            if (text.isEmpty()) {
                                AcToast.show(MainActivity.this, Strings.get(R.string.mainactivity_126));
                            } else {
                                showSelectableText(Strings.get(R.string.mainactivity_127), text);
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
            AcToast.show(this, Strings.get(R.string.mainactivity_128));
            return;
        }
        int pos = lastUserIndexBefore(messages.size());
        if (pos < 0) {
            LogUtil.w(TAG, "retryStreaming: no user message to retry");
            AcToast.show(this, Strings.get(R.string.mainactivity_129));
            return;
        }
        LogUtil.i(TAG, "retryStreaming: retry from user pos=%d", pos);
        confirmInterruptAndRetry(pos);
    }

    private void copyStreamingText() {
        String text = streamingText();
        if (text.isEmpty()) {
            AcToast.show(this, Strings.get(R.string.mainactivity_130));
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

        AcDialog.builder(this)
                .setTitle(Strings.get(R.string.mainactivity_131))
                .setView(input)
                .setPositiveButton(Strings.get(R.string.mainactivity_132), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        final String newContent = input.getText().toString().trim();
                        if (newContent.isEmpty()) {
                            LogUtil.w(TAG, "editMessage: empty new content, ignored");
                            return;
                        }
                        LogUtil.i(TAG, "editMessage: pos=%d oldLen=%d newLen=%d", pos,
                                oldContent == null ? 0 : oldContent.length(), newContent.length());
                        showConfirmDialog(Strings.get(R.string.mainactivity_133), new Runnable() {
                            public void run() {
                                msg.content = newContent;
                                if (msg.isAssistant()) {
                                    // 手改过正文后，旧的工具历史/纯文本不再对应当前内容
                                    msg.apiText = newContent;
                                    msg.toolHistory = "";
                                    msg.sendFailed = false;
                                    msg.errorDetail = "";
                                } else {
                                    // 用户消息：附件是独立字段，改正文不该动它。
                                    // apiText 是老会话的正文副本，正文已变，留着就是错的。
                                    msg.apiText = "";
                                }
                                // DOM update: only update this message's div; render off the UI thread
                                new Thread(new Runnable() {
                                    @Override
                                    public void run() {
                                        // 用户消息整条替换：附件卡片在 .bubble 之外，
                                        // 只换气泡的话「改完附件没了 / 加了附件不显示」都会复现
                                        final String html = msg.isAssistant()
                                            ? MessageHtmlRenderer.contentToHtml(newContent, MainActivity.this)
                                            : MessageHtmlRenderer.renderMessageDiv(msg, pos, MainActivity.this);
                                        final boolean wholeDiv = !msg.isAssistant();
                                        runOnUiThread(new Runnable() {
                                            @Override
                                            public void run() {
                                                String esc = jsEscape(html);
                                                webViewEvalJs((wholeDiv ? "replaceMsg(" : "updateMsgAt(")
                                                        + pos + ",'" + esc + "')");
                                                conversationManager.saveCurrentConversation();
                                                AcToast.show(MainActivity.this, Strings.get(R.string.mainactivity_134));
                                            }
                                        });
                                    }
                                }) {{ setName("aichat-render-" + System.currentTimeMillis()); }}.start();
                            }
                        });
                    }
                })
                .setNegativeButton(Strings.get(R.string.mainactivity_135), null)
                .show();
    }

    /**
     * 生成中禁止结构性改动 messages。
     *
     * <p>流式线程按 aiIndex 往 messages 里写，删除/回溯一旦截断列表，
     * 写入就落到越界位置：setContent 丢弃、本轮回复不落盘，finalizeLast 还会
     * 往不存在的 data-idx 上写。这些操作本身只需用户先点一下「停止」，不值得
     * 为它们再写一套打断-等待-续做的状态机，直接拒绝最省事也最安全。
     */
    private boolean rejectWhileStreaming(String action) {
        if (!isRequestInProgress.get()) return false;
        AcToast.show(this, Strings.get(R.string.mainactivity_136, action));
        return true;
    }

    // 4. 删除
    private void deleteMessage(final int pos) {
        if (rejectWhileStreaming(Strings.get(R.string.mainactivity_137))) return;
        if (pos < 0 || pos >= messages.size()) {
            LogUtil.w(TAG, "deleteMessage ignored: pos=%d size=%d", pos, messages.size());
            return;
        }
        Message msg = messages.get(pos);
        String preview = getMessageText(msg);
        if (preview == null) preview = "";
        if (preview.length() > 30) preview = preview.substring(0, 30) + "...";
        showConfirmDialog(Strings.get(R.string.mainactivity_138, preview), new Runnable() {
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
                AcToast.show(MainActivity.this, Strings.get(R.string.mainactivity_139));
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
                AcToast.show(this, Strings.get(R.string.mainactivity_140));
                return;
            }
            showConfirmDialog(Strings.get(R.string.mainactivity_141, n), new Runnable() {
                public void run() {
                    LogUtil.i(TAG, "retry(regenerate): truncating from pos=%d", pos);
                    messages.subList(pos, messages.size()).clear();
                    if (renderedFrom > pos) renderedFrom = pos;
                    removeDomRange(pos);
                    execStreamingRequest();
                }
            });
        } else {
            showConfirmDialog(Strings.get(R.string.mainactivity_143, n), new Runnable() {
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
            AcToast.show(this, Strings.get(R.string.mainactivity_145));
            return;
        }
        ProviderInfo provider = configManager.getProvider(availableModels.get(selPos).provider);
        if (provider == null) {
            AcToast.show(this, Strings.get(R.string.mainactivity_146));
            return;
        }
        try {
            boolean webSearchEnabled = SettingsActivity.isWebSearchEnabled(this);
            String sysPrompt = WebSearchTool.appendToSystemPrompt(systemPrompt, webSearchEnabled)
                    + conversationTimeSuffix();
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
            // 上次断流补进来的半截正文不属于「已完成的轮次」，续跑基准要把它截掉
            String resumeTrace = am.content == null ? "" : am.content;
            if (am.retryTrim >= 0 && am.retryTrim <= resumeTrace.length()) {
                resumeTrace = resumeTrace.substring(0, am.retryTrim);
            }
            am.retryTrim = -1;
            sendStreamingRequest(provider, thinkingLevel, gen,
                    new StreamJob.Resume(pos, rounds, resumeTrace,
                            conversation, sources, am.apiText == null ? "" : am.apiText,
                            am.toolHistory == null ? "" : am.toolHistory));
        } catch (Exception e) {
            LogUtil.e(TAG, "resumeAssistantRequest failed: %s", e.getMessage(), e);
            AcToast.show(this, Strings.get(R.string.mainactivity_147));
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
        // 附件要跟着重发：只复制正文的话，用户点一次「重试」，文件就从这条消息里消失了，
        // 模型下一轮也就读不到文件——这正是「附件改一下就没了」的同源问题
        if (src.attachments != null) um.attachments.addAll(src.attachments);
        // 重试是这个功能的例外：它按此刻的配置重新发，所以快照要重记一次，
        // 卡片随之显示新的截断量。除此之外任何路径都不许改快照。
        // 必须在 renderMessageDiv 之前——卡片渲染直接读快照。
        AttachmentText.recordSent(um.attachments);
        // 新建的消息 sendFailed/errorDetail 均为默认值，失败标记自然被清除
        messages.add(um);
        conversationManager.getCurrentConversation().touch();
        int umIdx = messages.size() - 1;
        String userHtml = MessageHtmlRenderer.renderMessageDiv(um, umIdx, MainActivity.this);
        appendHtml(userHtml, umIdx);
        lastAppendedIdx = umIdx;
        lastAppendCheckPending = true;
        conversationManager.saveCurrentConversation();
        execStreamingRequest();
    }

    /** 回复进行中点击重试：确认 → 打断 → 等旧线程退出 → 清理旧 UI（校验代数）→ 重发。 */
    private void confirmInterruptAndRetry(final int pos) {
        AcDialog.builder(this)
                .setTitle(Strings.get(R.string.mainactivity_148))
                .setMessage(Strings.get(R.string.mainactivity_149))
                .setPositiveButton(Strings.get(R.string.mainactivity_150), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        interruptAndRetry(pos);
                    }
                })
                .setNegativeButton(Strings.get(R.string.mainactivity_151), null)
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
            AcToast.show(this, Strings.get(R.string.mainactivity_152));
            return;
        }
        ProviderInfo provider = configManager.getProvider(availableModels.get(selPos).provider);
        if (provider == null) {
            LogUtil.e(TAG, "execStreamingRequest aborted: provider null for '%s'",
                    availableModels.get(selPos).name);
            AcToast.show(this, Strings.get(R.string.mainactivity_153));
            return;
        }
        String thinkingLevel = configManager.getThinkingLevel();
        if (!configManager.isThinkingEnabled()) thinkingLevel = "off";
        int gen = requestGeneration.incrementAndGet();
        sendStreamingRequest(provider, thinkingLevel, gen);
    }

    // 6. 回溯到此处
    private void rollbackTo(int pos) {
        if (rejectWhileStreaming(Strings.get(R.string.mainactivity_154))) return;
        if (pos >= messages.size() - 1) {
            LogUtil.d(TAG, "rollbackTo: already latest (pos=%d, size=%d)", pos, messages.size());
            AcToast.show(this, Strings.get(R.string.mainactivity_155));
            return;
        }
        final int n = messages.size() - pos - 1;
        showConfirmDialog(Strings.get(R.string.mainactivity_156, n), new Runnable() {
            public void run() {
                LogUtil.i(TAG, "rollbackTo: removing %d messages after pos=%d", n, pos);
                messages.subList(messages.size() - n, messages.size()).clear();
                if (renderedFrom > messages.size()) renderedFrom = messages.size();
                removeDomRange(messages.size());
                conversationManager.saveCurrentConversation();
                AcToast.show(MainActivity.this, Strings.get(R.string.mainactivity_158));
            }
        });
    }

    // 7. 创建分支
    private void branchAt(final int pos) {
        final int n = pos + 1;
        showConfirmDialog(Strings.get(R.string.mainactivity_159, n), new Runnable() {
            public void run() {
                // 与新建/切换会话同一套收口：排队消息属于旧会话，在途请求会继续往
                // messages 里写。不先停掉，分支切过去后收尾逻辑会按旧下标写到新列表。
                if (queue != null) queue.clearAll();
                clearAttachments();
                if (isRequestInProgress.get()) interruptRequest();
                dropTrailingEmptyAssistant();
                // 清空占位后 pos 可能已经越界（最后一条正是那条空 AI 占位）
                int last = Math.min(pos, messages.size() - 1);
                if (last < 0) {
                    AcToast.show(MainActivity.this, Strings.get(R.string.mainactivity_161));
                    return;
                }
                Conversation current = conversationManager.getCurrentConversation();
                Conversation branch = new Conversation();
                branch.title = ConversationManager.normalizeTitle(current.title) + Strings.get(R.string.mainactivity_162);
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
                    copy.tokensEstimated = src.tokensEstimated;
                    // 命中与中断标记也要带走：分支里少这两项，命中率会凭空掉、
                    // 中断过的回答又会重新被估算出一个 token 数
                    copy.cachedTokens = src.cachedTokens;
                    copy.interrupted = src.interrupted;
                    copy.requestCount = src.requestCount;
                    // 逐轮明细是值对象，直接复制列表（不是引用），
                    // 否则分支里再续跑一轮会改到原会话的记录
                    if (src.rounds != null && !src.rounds.isEmpty()) {
                        for (Message.RoundUsage r : src.rounds) {
                            if (r == null) continue;
                            copy.rounds.add(new Message.RoundUsage(
                                    r.prompt, r.completion, r.cached, r.ms, r.estimated));
                        }
                    }
                    // 纯文本/工具历史也要一起带走，否则分支里的 AI 回复又退回「只有正文」
                    copy.apiText = src.apiText;
                    copy.toolHistory = src.toolHistory;
                    // 附件跟着分支走，否则分支里的用户消息只剩一句「看这个文件」
                    if (src.attachments != null) copy.attachments.addAll(src.attachments);
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
                // 换了列表对象：整页重灌，页面版本自增会关掉旧的追加通道
                refreshWebView();
                AcToast.show(MainActivity.this, Strings.get(R.string.mainactivity_163, branch.title));
            }
        });
    }

    private void showConfirmDialog(String message, final Runnable onConfirm) {
        AcDialog.builder(this)
                .setTitle(Strings.get(R.string.mainactivity_164))
                .setMessage(message)
                .setPositiveButton(Strings.get(R.string.mainactivity_165), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { onConfirm.run(); }
                })
                .setNegativeButton(Strings.get(R.string.mainactivity_166), null)
                .show();
    }

}
