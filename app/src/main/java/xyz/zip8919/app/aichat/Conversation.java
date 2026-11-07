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
    /**
     * 已经为本会话发起过自动标题请求。与 titleGenerated（标题真的写入成功）
     * 分开：请求失败/返回空/还在进行中时也不会被下一条消息再次触发。
     */
    public boolean titleRequested;
    /**
     * 本会话第一次真正发出 AI 请求的时刻（毫秒）。0 表示还没发过。
     *
     * <p>与 {@link #createdAt} 分开：会话可能是先建好、隔几天才用。
     */
    public long firstRequestAt;
    /**
     * 首次请求时刻的**已格式化字符串**，例如 {@code 2026-11-07 17:26 星期五（GMT+8）}。
     *
     * <p>刻意存字符串而不是每次用时间戳重新格式化：重新格式化取的是「当前时区 +
     * 当前系统时间」，用户换时区或调了系统时间，这句就会变——同一个会话里前后两条
     * 消息对模型说两个不同的「开始时间」，比不注入更糟。
     */
    public String firstRequestStamp;

    /**
     * 会话文件占用的字节数，只在 {@code loadConversations} 时按磁盘实际大小填一次。
     *
     * <p>会话语义上没有「大小」这个概念，它是文件系统属性；塞进 JSON 里既冗余
     * 又会写错（保存后大小已变）。存储管理页与按体积排序都用这个运行期值。
     */
    public long fileBytes;

    /**
     * 消息条数。列表页只显示这个数字，不需要把 {@link #messages} 反序列化出来。
     *
     * <p>从索引构造的列表项 {@code messages} 是空的，靠这个字段保证「N 条消息」显示正确；
     * {@code fromJson} 解析出来的会话也会同步填一次，两条路径取值一致。
     */
    public int messageCount;

    /**
     * {@link #messages} 是否已从磁盘读出来。
     *
     * <p>列表页为了快，只读索引，构造出来的对象这里是 {@code false}——此时 {@code messages}
     * 为空但<b>不代表会话是空的</b>。切到这条会话前必须先补读，否则界面会显示一片空白
     * 并在下一条消息保存时把原历史覆盖掉。
     */
    public boolean messagesLoaded = true;

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
        this.titleRequested = false;
        this.firstRequestAt = 0;
        this.firstRequestStamp = "";
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
