package xyz.zip8919.app.aichat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

public class ConversationManagerActivity extends Activity {
    private static final String TAG = "ConvMgrActivity";
    private static final String PREFS_NAME = "aichat_ui_state";
    private static final String KEY_SCROLL_POS = "history_scroll_pos";

    /**
     * 只有「从本页点进某个会话、再返回」才恢复上次的滚动位置。
     * 冷启动（进程重启）和新建会话都必须回到顶部，否则会停在旧位置，
     * 显得新会话/最新记录被藏起来了。
     */
    private static boolean restoreScrollNextTime = false;

    private ConversationManager conversationManager;
    // 进入本页时缓存「当前会话」引用：onCreate 时它必然已存在（由主界面建立），
    // 之后重命名用它同步标题，避免再调用有副作用的 getCurrentConversation() 造出幽灵会话
    private Conversation currentConversationRef;
    private List<Conversation> conversations = new ArrayList<Conversation>();
    private ConversationAdapter adapter;
    private ListView listView;
    private SharedPreferences uiState;
    private LinearLayout selectionActions;
    private TextView selectionCount;
    private Button selectModeButton;
    private Button selectionCancelButton;
    private TextView sortToggle;
    private boolean selectionMode = false;

    /**
     * 是否从存储管理进入。
     *
     * <p>此时点击会话不再「切换过去并关页」——那样会直接回到存储页，看起来像点了没反应，
     * 而且主界面也不在跳转链路上。改为弹出该会话的体积详情，与「看谁占空间」的语境一致。
     */
    private boolean fromStorage = false;

    /** 从「最占空间」跳进来时要定位的会话 id。 */
    private String highlightId;

    /**
     * 是否按体积排序。
     *
     * <p>只影响本页展示：排序作用在副本上，不改 ConversationManager 内部列表的顺序
     * （那个顺序被主界面的会话切换依赖）。所以排序后删除要靠 {@link #loadConversations()}
     * 重载，而不能靠「监听列表变短」判断删除成功。
     */
    private boolean sortBySize = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_conversation_manager);

        this.uiState = getSharedPreferences(PREFS_NAME, 0);
        this.fromStorage = getIntent() != null
                && getIntent().getBooleanExtra(StorageActivity.EXTRA_FROM_STORAGE, false);
        this.highlightId = getIntent() == null ? null
                : getIntent().getStringExtra(StorageActivity.EXTRA_HIGHLIGHT_ID);
        if (highlightId != null) LogUtil.i(TAG, "highlight conversation: %s", highlightId);
        this.conversationManager = ConversationManager.getInstance();
        // 此刻「当前会话」必已由主界面建立，缓存引用即可安全同步标题；
        // 之后即使在本页删掉当前会话，也不会再触发 getCurrentConversation() 的惰性创建
        this.currentConversationRef = this.conversationManager.getCurrentConversation();
        this.listView = (ListView) findViewById(R.id.conversation_list);
        this.selectionActions = (LinearLayout) findViewById(R.id.selection_actions);
        this.selectionCount = (TextView) findViewById(R.id.selection_count);
        this.selectModeButton = (Button) findViewById(R.id.select_mode_button);
        this.selectionCancelButton = (Button) findViewById(R.id.selection_cancel_button);
        this.sortToggle = (TextView) findViewById(R.id.sort_toggle);

        initButtons();
        loadConversations();
        updateSelectionBar();
    }

    @Override
    protected void onPause() {
        super.onPause();
        int first = listView.getFirstVisiblePosition();
        uiState.edit().putInt(KEY_SCROLL_POS, first).apply();
        LogUtil.d(TAG, "onPause: saved scroll pos=%d", first);
    }

    private void initButtons() {
        findViewById(R.id.new_conversation_button).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                LogUtil.d(TAG, "click: new conversation");
                Conversation conv = conversationManager.createNewConversation();
                LogUtil.i(TAG, "new conversation created: id=%s -> return to main", conv.id);
                // 新建会话后列表要回到顶部，不能停在旧位置
                restoreScrollNextTime = false;
                uiState.edit().remove(KEY_SCROLL_POS).apply();
                Intent result = new Intent();
                result.putExtra("conversation_id", conv.id);
                setResult(RESULT_OK, result);
                finish();
            }
        });

        findViewById(R.id.clear_history_button).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (selectionMode) {
                    LogUtil.d(TAG, "click: clear history ignored, selection mode on");
                    return;
                }
                if (conversations == null || conversations.isEmpty()) {
                    LogUtil.w(TAG, "click: clear history but list empty");
                    Toast.makeText(ConversationManagerActivity.this, "没有历史记录", Toast.LENGTH_SHORT).show();
                    return;
                }
                LogUtil.d(TAG, "click: clear history, %d conversations -> confirm dialog", conversations.size());
                new AlertDialog.Builder(ConversationManagerActivity.this)
                        .setTitle("清空历史")
                        .setMessage("确定要清空所有历史对话吗？\n\n此操作不可恢复！")
                        .setPositiveButton("清空", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                clearAllHistory();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });

        selectModeButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (conversations == null || conversations.isEmpty()) {
                    LogUtil.w(TAG, "click: select mode but list empty");
                    Toast.makeText(ConversationManagerActivity.this, "没有历史记录", Toast.LENGTH_SHORT).show();
                    return;
                }
                enterSelectionMode();
            }
        });

        selectionCancelButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                LogUtil.d(TAG, "click: cancel selection");
                exitSelectionMode();
            }
        });

        // 按钮文案是「下一步切到哪种」，不是「当前是什么」：
        // 显示「按体积」时点下去就变成按体积排，不用让人猜现在的顺序。
        sortToggle.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                sortBySize = !sortBySize;
                sortToggle.setText(sortBySize ? "按时间" : "按体积");
                LogUtil.d(TAG, "click: sortBySize=%s", sortBySize);
                applySort();
            }
        });

        findViewById(R.id.selection_all_button).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                LogUtil.d(TAG, "click: select all");
                adapter.selectAll();
                updateSelectionBar();
            }
        });

        findViewById(R.id.selection_invert_button).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                LogUtil.d(TAG, "click: invert selection");
                adapter.invertSelection();
                updateSelectionBar();
            }
        });

        findViewById(R.id.selection_delete_button).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                LogUtil.d(TAG, "click: delete selected");
                confirmDeleteSelected();
            }
        });

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                if (pos < 0 || pos >= conversations.size()) {
                    LogUtil.w(TAG, "item click out of range: pos=%d size=%d", pos, conversations.size());
                    return;
                }
                if (selectionMode) {
                    LogUtil.d(TAG, "item click (selection mode): pos=%d", pos);
                    adapter.toggleSelection(pos);
                    updateSelectionBar();
                    return;
                }
                Conversation conv = conversations.get(pos);
                LogUtil.i(TAG, "item click: pos=%d id=%s title=%s", pos, conv.id, conv.title);
                if (fromStorage) {
                    showStorageDetail(conv);
                    return;
                }
                // 返回本页时恢复滚动位置；冷启动/新建会话不恢复
                restoreScrollNextTime = true;
                Intent result = new Intent();
                result.putExtra("conversation_id", conv.id);
                setResult(RESULT_OK, result);
                finish();
            }
        });

        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> parent, View view, int pos, long id) {
                if (selectionMode) {
                    LogUtil.d(TAG, "item long click ignored (selection mode): pos=%d", pos);
                    return true;
                }
                LogUtil.d(TAG, "item long click: pos=%d", pos);
                showActionDialog(conversations.get(pos));
                return true;
            }
        });
    }

    private void enterSelectionMode() {
        selectionMode = true;
        adapter.setSelectionMode(true);
        updateSelectionBar();
        LogUtil.i(TAG, "enterSelectionMode: %d conversations", conversations.size());
    }

    private void exitSelectionMode() {
        selectionMode = false;
        adapter.setSelectionMode(false);
        updateSelectionBar();
        LogUtil.i(TAG, "exitSelectionMode");
    }

    private void updateSelectionBar() {
        selectionActions.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        selectModeButton.setVisibility(selectionMode ? View.GONE : View.VISIBLE);
        selectionCancelButton.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        if (sortToggle != null) {
            sortToggle.setVisibility(selectionMode ? View.GONE : View.VISIBLE);
        }
        selectionCount.setText(selectionMode
                ? ("已选 " + (adapter == null ? 0 : adapter.getSelectedCount()) + " / " + conversations.size())
                : (fromStorage ? "点击查看占用详情，长按更多操作" : "点击多选可批量删除"));
    }

    /** 按当前模式排序。作用在副本上，不动 ConversationManager 内部列表。 */
    private void applySort() {
        if (conversations == null) return;
        List<Conversation> sorted = new ArrayList<Conversation>(conversations);
        if (sortBySize) {
            java.util.Collections.sort(sorted, new java.util.Comparator<Conversation>() {
                public int compare(Conversation a, Conversation b) {
                    if (b.fileBytes > a.fileBytes) return 1;
                    if (b.fileBytes < a.fileBytes) return -1;
                    return 0;
                }
            });
        } else {
            java.util.Collections.sort(sorted, new java.util.Comparator<Conversation>() {
                public int compare(Conversation a, Conversation b) {
                    if (b.updatedAt > a.updatedAt) return 1;
                    if (b.updatedAt < a.updatedAt) return -1;
                    return 0;
                }
            });
        }
        conversations = sorted;
        if (adapter != null) {
            adapter.setConversations(conversations);
        }
    }

    /** 按 id 找列表下标。排序后位置会变，不能用「最占空间」列表里的顺序去猜。 */
    private int indexOfConversation(String id) {
        if (id == null || conversations == null) return -1;
        for (int i = 0; i < conversations.size(); i++) {
            if (id.equals(conversations.get(i).id)) return i;
        }
        return -1;
    }

    private void confirmDeleteSelected() {
        final List<String> ids = adapter.getSelectedIds();
        if (ids.isEmpty()) {
            LogUtil.w(TAG, "delete selected: nothing selected");
            Toast.makeText(this, "请先选择要删除的对话", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("删除对话")
                .setMessage("确定要删除选中的 " + ids.size() + " 个对话吗？\n\n此操作不可恢复！")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        deleteSelected(ids);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void deleteSelected(List<String> ids) {
        LogUtil.i(TAG, "deleteSelected: %d ids=%s", ids.size(), ids.toString());
        int removed = 0;
        for (int i = 0; i < ids.size(); i++) {
            conversationManager.deleteConversation(ids.get(i));
            removed++;
        }
        // 会话文件变了，存储管理页的缓存立刻作废，否则返回后还显示旧的「已用」
        StorageActivity.invalidateCache();
        exitSelectionMode();
        Toast.makeText(this, "已删除 " + removed + " 个对话", Toast.LENGTH_SHORT).show();
        // 排序后 conversations 是副本，不能再靠「列表变短」判断删除是否成功；
        // 直接按磁盘重载，顺便把体积重新算准
        loadConversations();
        LogUtil.i(TAG, "deleteSelected done: removed=%d", removed);
    }

    private void loadConversations() {
        // 会话文件可能很多/很大，逐文件读盘 + 解析 JSON 放后台线程，避免主线程 ANR
        new Thread(new Runnable() {
            public void run() {
                final List<Conversation> loaded = conversationManager.loadConversations();
                LogUtil.d(TAG, "loadConversations(background): %d conversations",
                        loaded == null ? -1 : loaded.size());
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (isFinishing()) return;
                        conversations = loaded;
                        if (adapter == null) {
                            adapter = new ConversationAdapter(ConversationManagerActivity.this, conversations);
                            listView.setAdapter(adapter);
                        } else {
                            adapter.setConversations(conversations);
                        }
                        // 重载会把列表恢复成「按时间」，用户选的按体积排序要重新应用
                        applySort();
                        updateSelectionBar();
                        if (highlightId != null) {
                            // 从「最占空间」点进来：定位并高亮那一行。找不到（已删）就正常回到顶部
                            adapter.setHighlightId(highlightId);
                            int idx = indexOfConversation(highlightId);
                            if (idx >= 0) listView.setSelection(idx);
                        }
                        int savedPos = restoreScrollNextTime ? uiState.getInt(KEY_SCROLL_POS, 0) : 0;
                        restoreScrollNextTime = false;
                        if (highlightId == null) {
                            if (savedPos > 0 && savedPos < conversations.size()) {
                                listView.setSelection(savedPos);
                            } else {
                                listView.setSelection(0);
                            }
                        }
                    }
                });
            }
        }).start();
    }

    /**
     * 从存储管理进入时的点击反馈：体积详情 + 查看 + 删除。
     *
     * <p>不在这里切换会话——本页由存储页启动，切过去再 finish 会直接弹回存储页，
     * 用户看到的是「点了一下，什么也没发生」。要真看内容就走「查看」，
     * 直接回到主界面并把会话切过去，返回键回到本页。
     */
    private void showStorageDetail(final Conversation conv) {
        int count = conv.messages == null ? 0 : conv.messages.size();
        String msg = "消息 " + count + " 条\n"
                + "占用 " + StorageReport.size(conv.fileBytes) + "\n"
                + "最后更新 " + android.text.format.DateFormat.format("yyyy-MM-dd HH:mm",
                        conv.updatedAt) + "\n\n"
                + "删除后该会话的图片若不再被别的会话使用，会在图片附件里变成「无引用」。";
        new AlertDialog.Builder(this)
                .setTitle(ConversationManager.normalizeTitle(conv.title))
                .setMessage(msg)
                .setPositiveButton("查看", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        openConversation(conv);
                    }
                })
                .setNeutralButton("删除", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        showDeleteDialog(conv);
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    /**
     * 回到主界面并切到这条会话。
     *
     * <p>用 REORDER_TO_FRONT 而不是新开一个实例：主界面持有 WebView、请求状态与消息列表，
     * 开第二个实例等于让两个界面同时监听同一份数据。它被提到栈顶后，
     * 返回键自然落回本页，符合「看完回来继续清」的操作流。
     */
    private void openConversation(Conversation conv) {
        LogUtil.i(TAG, "openConversation from storage: id=%s", conv.id);
        Intent it = new Intent(this, MainActivity.class);
        it.putExtra("conversation_id", conv.id);
        it.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(it);
    }

    private void showActionDialog(final Conversation conv) {
        String[] items = {"重命名", "删除"};
        new AlertDialog.Builder(this)
                .setTitle("对话操作: " + conv.title)
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        LogUtil.d(TAG, "action dialog: id=%s which=%d (%s)", conv.id, which,
                                which == 0 ? "重命名" : "删除");
                        if (which == 0) {
                            showRenameDialog(conv);
                        } else {
                            showDeleteDialog(conv);
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showRenameDialog(final Conversation conv) {
        final EditText input = new EditText(this);
        input.setText(conv.title);
        input.setSelectAllOnFocus(true);

        new AlertDialog.Builder(this)
                .setTitle("重命名对话")
                .setView(input)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        String newTitle = input.getText().toString().trim();
                        LogUtil.d(TAG, "rename: id=%s newTitle='%s'", conv.id, newTitle);
                        if (!newTitle.isEmpty()) {
                            conv.title = newTitle;
                            // 手动重命名后视为「标题已定稿」，避免异步自动标题回调把它覆盖
                            conv.titleGenerated = true;
                            conv.touch();
                            // conv 与缓存引用是同一对象时标题已一并更新；不再调用有副作用的
                            // getCurrentConversation()（current 为 null 时会凭空造出「新对话」）。
                            // 被重命名的会话不一定是 current：是就走统一的异步保存，
                            // 不是就单独排队写，别在 UI 线程序列化整个会话
                            if (currentConversationRef != null && currentConversationRef.id.equals(conv.id)) {
                                currentConversationRef.title = newTitle;
                                conversationManager.saveCurrentConversation();
                            } else {
                                conversationManager.saveConversationAsync(conv);
                            }
                            adapter.notifyDataSetChanged();
                            Toast.makeText(ConversationManagerActivity.this, "已重命名", Toast.LENGTH_SHORT).show();
                            LogUtil.i(TAG, "rename ok: id=%s -> '%s'", conv.id, newTitle);
                        } else {
                            Toast.makeText(ConversationManagerActivity.this, "名称不能为空", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showDeleteDialog(final Conversation conv) {
        new AlertDialog.Builder(this)
                .setTitle("删除对话")
                .setMessage("确定要删除\"" + conv.title + "\"吗？")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        LogUtil.i(TAG, "delete confirmed: id=%s title=%s", conv.id, conv.title);
                        conversationManager.deleteConversation(conv.id);
                        conversations.remove(conv);
                        adapter.notifyDataSetChanged();
                        StorageActivity.invalidateCache();
                        LogUtil.d(TAG, "delete done: remaining=%d", conversations.size());
                        Toast.makeText(ConversationManagerActivity.this, "已删除", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void clearAllHistory() {
        LogUtil.i(TAG, "clearAllHistory: deleting %d conversations", conversations.size());
        // 先收集 id，避免遍历时被 ConversationManager.deleteConversation 就地修改同一 List
        List<String> ids = new ArrayList<String>();
        for (int i = 0; i < conversations.size(); i++) {
            ids.add(conversations.get(i).id);
        }
        // 必须走 ConversationManager.deleteConversation：它会同步把 currentConversation 置空，
        // 否则回主界面后任一次保存都会把被清空的对话重新写回磁盘
        for (int i = 0; i < ids.size(); i++) {
            conversationManager.deleteConversation(ids.get(i));
        }
        conversations.clear();
        adapter.notifyDataSetChanged();
        StorageActivity.invalidateCache();
        LogUtil.i(TAG, "clearAllHistory done: %d remaining", conversations.size());
        Toast.makeText(this, "已清空所有历史对话", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onBackPressed() {
        if (selectionMode) {
            LogUtil.d(TAG, "onBackPressed: exit selection mode");
            exitSelectionMode();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        LogUtil.d(TAG, "onResume");
    }
}
