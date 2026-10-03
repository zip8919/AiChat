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

    private ConversationManager conversationManager;
    private StorageManager storageManager;
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
    private boolean selectionMode = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_conversation_manager);

        this.uiState = getSharedPreferences(PREFS_NAME, 0);
        this.conversationManager = ConversationManager.getInstance();
        this.storageManager = StorageManager.getInstance();
        // 此刻「当前会话」必已由主界面建立，缓存引用即可安全同步标题；
        // 之后即使在本页删掉当前会话，也不会再触发 getCurrentConversation() 的惰性创建
        this.currentConversationRef = this.conversationManager.getCurrentConversation();
        this.listView = (ListView) findViewById(R.id.conversation_list);
        this.selectionActions = (LinearLayout) findViewById(R.id.selection_actions);
        this.selectionCount = (TextView) findViewById(R.id.selection_count);
        this.selectModeButton = (Button) findViewById(R.id.select_mode_button);
        this.selectionCancelButton = (Button) findViewById(R.id.selection_cancel_button);

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
        selectionCount.setText(selectionMode
                ? ("已选 " + (adapter == null ? 0 : adapter.getSelectedCount()) + " / " + conversations.size())
                : "点击多选可批量删除");
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
        // conversationManager.deleteConversation 会就地移除它持有的同一个 List 实例（即本页的
        // conversations），所以必须在删除前计数，不能删完再扫（那样恒为 0）
        int removed = 0;
        for (int i = 0; i < ids.size(); i++) {
            int sizeBefore = conversations.size();
            conversationManager.deleteConversation(ids.get(i));
            if (conversations.size() < sizeBefore) {
                removed++;
            }
        }
        adapter.notifyDataSetChanged();
        exitSelectionMode();
        LogUtil.i(TAG, "deleteSelected done: removed=%d remaining=%d", removed, conversations.size());
        Toast.makeText(this, "已删除 " + removed + " 个对话", Toast.LENGTH_SHORT).show();
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
                        updateSelectionBar();
                        int savedPos = uiState.getInt(KEY_SCROLL_POS, 0);
                        if (savedPos > 0 && savedPos < conversations.size()) {
                            listView.setSelection(savedPos);
                        }
                    }
                });
            }
        }).start();
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
                            conv.touch();
                            conversationManager.saveCurrentConversation();
                            // conv 与缓存引用是同一对象时标题已一并更新；不再调用有副作用的
                            // getCurrentConversation()（current 为 null 时会凭空造出「新对话」）
                            if (currentConversationRef != null && currentConversationRef.id.equals(conv.id)) {
                                currentConversationRef.title = newTitle;
                            }
                            adapter.notifyDataSetChanged();
                            // Re-save to disk
                            String json = ConversationManager.toJson(conv);
                            storageManager.saveConversation(conv.id, json);
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
