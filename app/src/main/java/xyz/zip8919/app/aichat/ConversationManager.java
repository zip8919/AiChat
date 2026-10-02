package xyz.zip8919.app.aichat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public class ConversationManager {
    private static final String TAG = "ConversationMgr";
    private static ConversationManager instance;
    private StorageManager storageManager;
    private List<Conversation> conversations;
    private Conversation currentConversation;

    private ConversationManager() {
        this.storageManager = StorageManager.getInstance();
        this.conversations = new ArrayList<Conversation>();
    }

    public static synchronized ConversationManager getInstance() {
        if (instance == null) {
            instance = new ConversationManager();
        }
        return instance;
    }

    public Conversation getCurrentConversation() {
        if (this.currentConversation == null) {
            this.currentConversation = new Conversation();
            this.conversations.add(0, this.currentConversation);
            LogUtil.d(TAG, "getCurrentConversation: created fallback '%s' (%s)",
                    this.currentConversation.id, this.currentConversation.title);
        }
        return this.currentConversation;
    }

    public void setCurrentConversation(Conversation conv) {
        LogUtil.d(TAG, "setCurrentConversation: %s", conv == null ? "null" : conv.id);
        this.currentConversation = conv;
    }

    public Conversation createNewConversation() {
        if (this.currentConversation != null && !this.currentConversation.messages.isEmpty()) {
            LogUtil.d(TAG, "createNewConversation: saving previous '%s' first", this.currentConversation.id);
            saveCurrentConversation();
        }
        this.currentConversation = new Conversation();
        this.conversations.add(0, this.currentConversation);
        LogUtil.i(TAG, "createNewConversation: id=%s, total=%d", this.currentConversation.id, this.conversations.size());
        return this.currentConversation;
    }

    public void saveCurrentConversation() {
        if (this.currentConversation == null || this.currentConversation.messages.isEmpty()) {
            LogUtil.v(TAG, "saveCurrentConversation skipped: empty conversation");
            return;
        }
        String json = toJson(this.currentConversation);
        boolean ok = storageManager.saveConversation(this.currentConversation.id, json);
        LogUtil.d(TAG, "saveCurrentConversation: id=%s msgs=%d jsonLen=%d ok=%s",
                this.currentConversation.id, this.currentConversation.messages.size(), json.length(), ok);
    }

    public void switchConversation(String conversationId) {
        LogUtil.i(TAG, "switchConversation -> %s", conversationId);
        // Try to find in memory
        for (Conversation c : this.conversations) {
            if (c.id.equals(conversationId)) {
                this.currentConversation = c;
                LogUtil.d(TAG, "switchConversation: found in memory, msgs=%d", c.messages.size());
                return;
            }
        }

        // Load from disk
        String content = storageManager.loadConversation(conversationId);
        if (content != null) {
            Conversation conv = fromJson(content);
            this.currentConversation = conv;
            this.conversations.add(0, conv);
            LogUtil.d(TAG, "switchConversation: loaded from disk, msgs=%d", conv.messages.size());
        } else {
            this.currentConversation = new Conversation(conversationId);
            this.conversations.add(0, this.currentConversation);
            LogUtil.w(TAG, "switchConversation: '%s' not on disk, created empty", conversationId);
        }
    }

    public void deleteConversation(String conversationId) {
        LogUtil.i(TAG, "deleteConversation: id=%s, before=%d", conversationId, this.conversations.size());
        if (this.currentConversation != null && this.currentConversation.id.equals(conversationId)) {
            this.currentConversation = null;
        }
        for (int i = 0; i < this.conversations.size(); i++) {
            if (this.conversations.get(i).id.equals(conversationId)) {
                this.conversations.remove(i);
                break;
            }
        }
        boolean ok = storageManager.deleteConversation(conversationId);
        LogUtil.d(TAG, "deleteConversation: file deleted=%s, remaining=%d", ok, this.conversations.size());
    }

    public List<Conversation> loadConversations() {
        LogUtil.i(TAG, "loadConversations (%s)", LogUtil.thread());
        List<Conversation> loaded = new ArrayList<Conversation>();
        String[] files = storageManager.getConversationFiles();
        if (files != null) {
            LogUtil.d(TAG, "loadConversations: %d files on disk", files.length);
            for (String file : files) {
                if (!file.endsWith(".json")) continue;
                String conversationId = file.substring(0, file.length() - 5);
                String content = storageManager.loadConversation(conversationId);
                if (content == null || content.length() == 0) continue;
                try {
                    loaded.add(fromJson(content));
                } catch (Exception e) {
                    LogUtil.e(TAG, "loadConversations: failed to parse '%s': %s", file, e.getMessage(), e);
                }
            }
        } else {
            LogUtil.w(TAG, "loadConversations: file list is null");
        }

        // Sort: newest updatedAt first
        Collections.sort(loaded, new Comparator<Conversation>() {
            public int compare(Conversation a, Conversation b) {
                return Long.compare(b.updatedAt, a.updatedAt);
            }
        });

        this.conversations = loaded;
        LogUtil.i(TAG, "loadConversations done: %d conversations", this.conversations.size());
        return this.conversations;
    }

    public List<Conversation> getConversations() {
        return this.conversations;
    }

    // ---- JSON serialization ----

    static String toJson(Conversation conv) {
        try {
            JSONObject json = new JSONObject();
            json.put("id", conv.id);
            json.put("title", conv.title);
            json.put("createdAt", conv.createdAt);
            json.put("updatedAt", conv.updatedAt);
            json.put("model", conv.model != null ? conv.model : "");
            json.put("systemPrompt", conv.systemPrompt != null ? conv.systemPrompt : "");
            json.put("titleGenerated", conv.titleGenerated);

            JSONArray msgs = new JSONArray();
            for (Message m : conv.messages) {
                JSONObject mj = new JSONObject();
                mj.put("role", m.role);
                mj.put("content", m.content);
                mj.put("timestamp", m.timestamp);
                msgs.put(mj);
            }
            json.put("messages", msgs);

            return json.toString();
        } catch (Exception e) {
            LogUtil.e(TAG, "toJson failed: " + e.getMessage(), e);
            return "{}";
        }
    }

    static Conversation fromJson(String jsonStr) {
        Conversation conv = new Conversation();
        try {
            JSONObject json = new JSONObject(jsonStr);
            conv.id = json.optString("id", conv.id);
            conv.title = json.optString("title", "新对话");
            conv.createdAt = json.optLong("createdAt", System.currentTimeMillis());
            conv.updatedAt = json.optLong("updatedAt", System.currentTimeMillis());
            conv.model = json.optString("model", "");
            conv.systemPrompt = json.optString("systemPrompt", "");
            conv.titleGenerated = json.optBoolean("titleGenerated", false);

            conv.messages.clear();
            JSONArray msgs = json.optJSONArray("messages");
            if (msgs != null) {
                for (int i = 0; i < msgs.length(); i++) {
                    JSONObject mj = msgs.getJSONObject(i);
                    Message m = new Message();
                    m.role = mj.optString("role", "user");
                    m.content = mj.optString("content", "");
                    m.timestamp = mj.optLong("timestamp", System.currentTimeMillis());
                    conv.messages.add(m);
                }
            }
        } catch (Exception e) {
            LogUtil.e(TAG, "fromJson failed: " + e.getMessage(), e);
        }
        LogUtil.v(TAG, "fromJson: id=%s title=%s msgs=%d", conv.id, conv.title, conv.messages.size());
        return conv;
    }
}
