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
import android.widget.ListView;
import android.widget.Toast;
import java.util.List;

public class ConversationManagerActivity extends Activity {
    private static final String TAG = "ConvMgrActivity";
    private static final String PREFS_NAME = "aichat_ui_state";
    private static final String KEY_SCROLL_POS = "history_scroll_pos";

    private ConversationManager conversationManager;
    private StorageManager storageManager;
    private List<Conversation> conversations;
    private ConversationAdapter adapter;
    private ListView listView;
    private SharedPreferences uiState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_conversation_manager);

        this.uiState = getSharedPreferences(PREFS_NAME, 0);
        this.conversationManager = ConversationManager.getInstance();
        this.storageManager = StorageManager.getInstance();
        this.listView = (ListView) findViewById(R.id.conversation_list);

        initButtons();
        loadConversations();

        int savedPos = uiState.getInt(KEY_SCROLL_POS, 0);
        LogUtil.i(TAG, "========== onCreate ========== (%s) conversations=%d savedPos=%d",
                LogUtil.thread(), conversations == null ? -1 : conversations.size(), savedPos);
        if (savedPos > 0 && savedPos < conversations.size()) {
            listView.setSelection(savedPos);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        int first = listView.getFirstVisiblePosition();
        boolean ok = uiState.edit().putInt(KEY_SCROLL_POS, first).commit();
        LogUtil.d(TAG, "onPause: saved scroll pos=%d ok=%s", first, ok);
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

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                if (pos < 0 || pos >= conversations.size()) {
                    LogUtil.w(TAG, "item click out of range: pos=%d size=%d", pos, conversations.size());
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
                LogUtil.d(TAG, "item long click: pos=%d", pos);
                showActionDialog(conversations.get(pos));
                return true;
            }
        });
    }

    private void loadConversations() {
        conversations = conversationManager.loadConversations();
        if (adapter == null) {
            adapter = new ConversationAdapter(this, conversations);
            listView.setAdapter(adapter);
        } else {
            adapter.setConversations(conversations);
        }
        LogUtil.d(TAG, "loadConversations: %d conversations", conversations == null ? -1 : conversations.size());
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
                            // Also update current conversation if it's the same
                            Conversation current = conversationManager.getCurrentConversation();
                            if (current != null && current.id.equals(conv.id)) {
                                current.title = newTitle;
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
        for (Conversation conv : conversations) {
            storageManager.deleteConversation(conv.id);
        }
        conversations.clear();
        adapter.notifyDataSetChanged();
        LogUtil.i(TAG, "clearAllHistory done: %d remaining", conversations.size());
        Toast.makeText(this, "已清空所有历史对话", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        LogUtil.d(TAG, "onResume");
    }
}
