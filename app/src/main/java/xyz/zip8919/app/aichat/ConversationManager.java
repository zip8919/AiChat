package xyz.zip8919.app.aichat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public class ConversationManager {
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
        }
        return this.currentConversation;
    }

    public void setCurrentConversation(Conversation conv) {
        this.currentConversation = conv;
    }

    public Conversation createNewConversation() {
        if (this.currentConversation != null && !this.currentConversation.messages.isEmpty()) {
            saveCurrentConversation();
        }
        this.currentConversation = new Conversation();
        this.conversations.add(0, this.currentConversation);
        return this.currentConversation;
    }

    public void saveCurrentConversation() {
        if (this.currentConversation == null || this.currentConversation.messages.isEmpty()) {
            return;
        }
        String json = toJson(this.currentConversation);
        storageManager.saveConversation(this.currentConversation.id, json);
    }

    public void switchConversation(String conversationId) {
        // Try to find in memory
        for (Conversation c : this.conversations) {
            if (c.id.equals(conversationId)) {
                this.currentConversation = c;
                return;
            }
        }

        // Load from disk
        String content = storageManager.loadConversation(conversationId);
        if (content != null) {
            Conversation conv = fromJson(content);
            this.currentConversation = conv;
            this.conversations.add(0, conv);
        } else {
            this.currentConversation = new Conversation(conversationId);
            this.conversations.add(0, this.currentConversation);
        }
    }

    public void deleteConversation(String conversationId) {
        if (this.currentConversation != null && this.currentConversation.id.equals(conversationId)) {
            this.currentConversation = null;
        }
        for (int i = 0; i < this.conversations.size(); i++) {
            if (this.conversations.get(i).id.equals(conversationId)) {
                this.conversations.remove(i);
                break;
            }
        }
        storageManager.deleteConversation(conversationId);
    }

    public List<Conversation> loadConversations() {
        List<Conversation> loaded = new ArrayList<Conversation>();
        String[] files = storageManager.getConversationFiles();
        if (files != null) {
            for (String file : files) {
                if (!file.endsWith(".json")) continue;
                String conversationId = file.substring(0, file.length() - 5);
                String content = storageManager.loadConversation(conversationId);
                if (content == null || content.length() == 0) continue;
                try {
                    loaded.add(fromJson(content));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        // Sort: newest updatedAt first
        Collections.sort(loaded, new Comparator<Conversation>() {
            public int compare(Conversation a, Conversation b) {
                return Long.compare(b.updatedAt, a.updatedAt);
            }
        });

        this.conversations = loaded;
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
            e.printStackTrace();
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
            e.printStackTrace();
        }
        return conv;
    }
}
