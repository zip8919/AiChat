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
        if (savedPos > 0 && savedPos < conversations.size()) {
            listView.setSelection(savedPos);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        uiState.edit().putInt(KEY_SCROLL_POS, listView.getFirstVisiblePosition()).commit();
    }

    private void initButtons() {
        findViewById(R.id.new_conversation_button).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Conversation conv = conversationManager.createNewConversation();
                Intent result = new Intent();
                result.putExtra("conversation_id", conv.id);
                setResult(RESULT_OK, result);
                finish();
            }
        });

        findViewById(R.id.clear_history_button).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (conversations == null || conversations.isEmpty()) {
                    Toast.makeText(ConversationManagerActivity.this, "没有历史记录", Toast.LENGTH_SHORT).show();
                    return;
                }
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
                Conversation conv = conversations.get(pos);
                Intent result = new Intent();
                result.putExtra("conversation_id", conv.id);
                setResult(RESULT_OK, result);
                finish();
            }
        });

        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> parent, View view, int pos, long id) {
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
    }

    private void showActionDialog(final Conversation conv) {
        String[] items = {"重命名", "删除"};
        new AlertDialog.Builder(this)
                .setTitle("对话操作: " + conv.title)
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
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
                        conversationManager.deleteConversation(conv.id);
                        conversations.remove(conv);
                        adapter.notifyDataSetChanged();
                        Toast.makeText(ConversationManagerActivity.this, "已删除", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void clearAllHistory() {
        for (Conversation conv : conversations) {
            storageManager.deleteConversation(conv.id);
        }
        conversations.clear();
        adapter.notifyDataSetChanged();
        Toast.makeText(this, "已清空所有历史对话", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
    }
}
