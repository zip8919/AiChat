package xyz.zip8919.app.aichat;

import java.util.ArrayList;
import java.util.List;

public class Conversation {
    public String id;
    public String title;
    public long createdAt;
    public long updatedAt;
    public String model;
    public String systemPrompt;
    public List<Message> messages;
    public boolean titleGenerated;

    private static long lastId = 0;

    public Conversation() {
        long now = System.currentTimeMillis();
        long idValue = now;
        if (idValue <= lastId) idValue = lastId + 1;
        lastId = idValue;
        this.id = String.valueOf(idValue);
        this.createdAt = now;
        this.updatedAt = this.createdAt;
        this.messages = new ArrayList<Message>();
        this.title = "新对话";
        this.model = "";
        this.systemPrompt = "";
        this.titleGenerated = false;
    }

    public Conversation(String id) {
        this();
        this.id = id;
    }

    public void addMessage(Message msg) {
        this.messages.add(msg);
        this.updatedAt = System.currentTimeMillis();
    }

    public void touch() {
        this.updatedAt = System.currentTimeMillis();
    }
}
