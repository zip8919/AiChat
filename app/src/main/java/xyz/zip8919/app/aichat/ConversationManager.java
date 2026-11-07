package xyz.zip8919.app.aichat;

import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

public class ConversationManager {
    private static final String TAG = "ConversationMgr";
    private static ConversationManager instance;
    private StorageManager storageManager;
    private List<Conversation> conversations;
    private Conversation currentConversation;
    /**
     * 保存队列：一次会话可能有几百条消息，序列化 + 写盘在主线程做会直接掉帧。
     * 单线程保证同一会话的多次保存按调用顺序落盘（后写覆盖前写是期望行为）。
     */
    private final ExecutorService saveExecutor = Executors.newSingleThreadExecutor();
    // 被删除的「当前会话」的消息列表：主界面 messages 仍指向它，留给下一个 current 复用，避免分裂
    private List<Message> orphanMessages;
    /**
     * 已删除的会话 id。flushSaves 有超时，队列里可能还压着这个会话的保存任务；
     * 不拦一下，删完文件后那条迟到的保存会把它重新写回来（列表里「删了又出现」）。
     */
    private final java.util.Set<String> deletedIds =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

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
            // 刚删除了「当前会话」时，界面侧仍指向旧的消息列表；让新会话复用它，
            // 否则后续消息写进旧列表而新会话 messages 为空，保存被跳过，整段对话丢失。
            if (this.orphanMessages != null) {
                this.currentConversation.messages = this.orphanMessages;
                this.orphanMessages = null;
            }
            this.conversations.add(0, this.currentConversation);
            LogUtil.d(TAG, "getCurrentConversation: created fallback '%s' (%s)",
                    this.currentConversation.id, this.currentConversation.title);
        }
        return this.currentConversation;
    }

    public void setCurrentConversation(Conversation conv) {
        LogUtil.d(TAG, "setCurrentConversation: %s", conv == null ? "null" : conv.id);
        this.currentConversation = conv;
        this.orphanMessages = null;
    }

    public Conversation createNewConversation() {
        if (this.currentConversation != null && !this.currentConversation.messages.isEmpty()) {
            LogUtil.d(TAG, "createNewConversation: saving previous '%s' first", this.currentConversation.id);
            saveCurrentConversation();
        }
        this.orphanMessages = null;
        this.currentConversation = new Conversation();
        this.conversations.add(0, this.currentConversation);
        LogUtil.i(TAG, "createNewConversation: id=%s, total=%d", this.currentConversation.id, this.conversations.size());
        return this.currentConversation;
    }

    public void saveCurrentConversation() {
        // 取一次本地引用：避免序列化 A 之后字段被换成 B，导致 A 的内容写进 B 的文件
        final Conversation conv = this.currentConversation;
        if (conv == null || conv.messages.isEmpty()) {
            LogUtil.v(TAG, "saveCurrentConversation skipped: empty conversation");
            return;
        }
        // 主线程只做一次浅拷贝，序列化与写盘都挪到后台：调用点大多在 UI 线程
        // （流式收尾、失败标记、标题回写），长会话在这里同步写会卡住主线程。
        final Conversation snapshot = snapshotOf(conv);
        if (Looper.myLooper() == Looper.getMainLooper()) {
            saveExecutor.execute(new Runnable() {
                public void run() {
                    writeConversation(snapshot);
                }
            });
        } else {
            writeConversation(snapshot);
        }
    }

    /** 序列化 + 落盘。只在保存线程（或非主线程调用方）执行。 */
    private void writeConversation(Conversation conv) {
        if (conv == null) return;
        if (deletedIds.contains(conv.id)) {
            LogUtil.i(TAG, "save skipped: conversation %s already deleted", conv.id);
            return;
        }
        String json = toJson(conv);
        // toJson 内部异常会返回 "{}"，直接落盘会覆盖整个会话文件，这里拒绝写入
        if (json == null || json.length() < 3 || "{}".equals(json)) {
            LogUtil.e(TAG, "save aborted: serialization failed for id=%s", conv.id);
            return;
        }
        boolean ok = storageManager.saveConversation(conv.id, json);
        LogUtil.d(TAG, "saveCurrentConversation: id=%s msgs=%d jsonLen=%d ok=%s",
                conv.id, conv.messages.size(), json.length(), ok);
    }

    /** 异步保存任意会话（列表页重命名等场景），不要求它是 current。 */
    public void saveConversationAsync(Conversation conv) {
        if (conv == null || conv.messages == null) return;
        final Conversation snapshot = snapshotOf(conv);
        saveExecutor.execute(new Runnable() {
            public void run() {
                writeConversation(snapshot);
            }
        });
    }

    /**
     * 等队列里的保存全部落盘。删会话前必须调用：否则队列里还挂着的保存
     * 会把刚删掉的文件又写回来。
     */
    public void flushSaves() {
        if (Looper.myLooper() != Looper.getMainLooper()) return;
        try {
            // 1.5s：够写完一个中等会话，又不至于让主线程卡到接近 ANR 阈值（5s）。
            // 超时不再是致命的——deleteConversation 已用 deletedIds 拦住迟到的写。
            saveExecutor.submit(new Runnable() {
                public void run() {
                }
            }).get(1500, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            LogUtil.w(TAG, "flushSaves timeout/failed: %s", e.getMessage());
        }
    }

    /** 拷贝会话壳与消息列表。消息对象本身共享——后台只读，最坏情况是存到一帧中间态。 */
    private static Conversation snapshotOf(Conversation src) {
        Conversation c = new Conversation(src.id);
        c.title = src.title;
        c.createdAt = src.createdAt;
        c.updatedAt = src.updatedAt;
        c.model = src.model;
        c.systemPrompt = src.systemPrompt;
        c.titleGenerated = src.titleGenerated;
        c.titleRequested = src.titleRequested;
        c.firstRequestAt = src.firstRequestAt;
        c.firstRequestStamp = src.firstRequestStamp;
        c.messages = new ArrayList<Message>(src.messages);
        return c;
    }

    public void switchConversation(String conversationId) {
        LogUtil.i(TAG, "switchConversation -> %s", conversationId);
        this.orphanMessages = null;
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
        // 附件文件名必须在删文件之前取：删完就无从知道这条会话引用过哪些图。
        // 只读一个会话文件，和下面 flushSaves 的落盘开销同量级，不会额外拖慢主线程。
        java.util.Set<String> ownAttachments =
                StorageCleaner.attachmentNames(storageManager, conversationId);
        // 先落盘队列里的写：否则删完文件后，队列里排着的保存会把它重新写回来
        flushSaves();
        // flush 可能超时（大会话序列化慢），残余任务靠这里兜底拦掉
        deletedIds.add(conversationId);
        if (this.currentConversation != null && this.currentConversation.id.equals(conversationId)) {
            // 主界面 messages 仍指向被删会话的列表，暂存给下一个 current 复用
            this.orphanMessages = this.currentConversation.messages;
            this.currentConversation = null;
        }
        for (int i = 0; i < this.conversations.size(); i++) {
            if (this.conversations.get(i).id.equals(conversationId)) {
                this.conversations.remove(i);
                break;
            }
        }
        boolean ok = storageManager.deleteConversation(conversationId);
        // 回收只被这条会话引用的图片。异步执行、失败静默：
        // 删会话本身已经成功，回收失败最多是留几张孤儿图，不影响用户操作
        StorageCleaner.recycleAsync(storageManager, ownAttachments);
        LogUtil.d(TAG, "deleteConversation: file deleted=%s, remaining=%d, attachments=%d",
                ok, this.conversations.size(), ownAttachments.size());
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
                    Conversation conv = fromJson(content);
                    // 体积取磁盘实际字节，不按正文字符数估：JSON 转义、缩略图 Base64、
                    // 中文 UTF-8 多字节都会让字符数与文件大小差出好几倍
                    conv.fileBytes = new java.io.File(
                            storageManager.getConversationsPath(), file).length();
                    loaded.add(conv);
                } catch (Exception e) {
                    LogUtil.e(TAG, "loadConversations: failed to parse '%s': %s", file, e.getMessage(), e);
                }
            }
        } else {
            LogUtil.w(TAG, "loadConversations: file list is null");
        }

        // Sort: newest updatedAt first
        // 不用 Long.compare：它是 API 19 才进 Android 的，4.3(API 18) 上会 NoSuchMethodError，
        // 而这段在每个冷启动都会跑到，等于必崩。
        Collections.sort(loaded, new Comparator<Conversation>() {
            public int compare(Conversation a, Conversation b) {
                if (b.updatedAt > a.updatedAt) return 1;
                if (b.updatedAt < a.updatedAt) return -1;
                return 0;
            }
        });

        this.conversations = loaded;
        // 列表已从磁盘重建，之前暂存的消息列表不再对应任何会话
        this.orphanMessages = null;
        LogUtil.i(TAG, "loadConversations done: %d conversations", this.conversations.size());
        return this.conversations;
    }

    public List<Conversation> getConversations() {
        return this.conversations;
    }

    // ---- JSON serialization ----

    // ---- 旧会话来源迁移 ----

    /** 来源区最多展示的条数（去重后）。 */
    public static final int MAX_SOURCES = 8;

    /** 旧版本把来源拼成 "---\n**搜索来源**\n1. [标题](url)" 追加在正文末尾。 */
    private static final String LEGACY_SOURCES_HEADER = "---\n**搜索来源**\n";
    private static final java.util.regex.Pattern LEGACY_SOURCE_LINE =
            java.util.regex.Pattern.compile("^(\\d+)\\.\\s+\\[(.*?)\\]\\(([^)]+)\\)\\s*$");

    /**
     * 把旧格式的来源列表从正文搬到 sources 字段。
     * 只有整段都能解析成来源行时才迁移；任何一行不匹配就保留原文，避免误删正文。
     *
     * @return true 表示已迁移（正文已被裁剪）
     */
    static boolean migrateLegacySources(Message m) {
        if (m == null || m.content == null || !m.isAssistant()) return false;
        String content = m.content;
        int idx = content.lastIndexOf(LEGACY_SOURCES_HEADER);
        if (idx < 0) return false;

        String tail = content.substring(idx + LEGACY_SOURCES_HEADER.length());
        List<Message.Source> parsed = new ArrayList<Message.Source>();
        List<String> seen = new ArrayList<String>();
        String[] lines = tail.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.length() == 0) continue;
            java.util.regex.Matcher mt = LEGACY_SOURCE_LINE.matcher(line);
            if (!mt.matches()) {
                LogUtil.d(TAG, "migrateLegacySources: not a source line, keep raw body: %s",
                        LogUtil.preview(line, 80));
                return false;
            }
            String title = mt.group(2).replace("\\[", "[").replace("\\]", "]").trim();
            String url = mt.group(3).trim();
            if (url.length() == 0) return false;
            if (seen.contains(url)) continue;
            seen.add(url);
            parsed.add(new Message.Source(title, url));
        }
        if (parsed.isEmpty()) return false;

        if (parsed.size() > MAX_SOURCES) {
            parsed = new ArrayList<Message.Source>(parsed.subList(0, MAX_SOURCES));
        }
        String head = content.substring(0, idx);
        while (head.endsWith("\n") || head.endsWith("\r")) {
            head = head.substring(0, head.length() - 1);
        }
        m.sources = parsed;
        m.content = head;
        LogUtil.i(TAG, "migrateLegacySources: moved %d sources out of body", parsed.size());
        return true;
    }

    /** A title must never be persisted (or read back) as null/blank/the literal string "null". */
    static String normalizeTitle(String title) {
        if (title == null) return "新对话";
        String t = title.trim();
        if (t.length() == 0 || "null".equalsIgnoreCase(t)) return "新对话";
        return t;
    }

    static String toJson(Conversation conv) {
        try {
            JSONObject json = new JSONObject();
            json.put("id", conv.id);
            json.put("title", normalizeTitle(conv.title));
            json.put("createdAt", conv.createdAt);
            json.put("updatedAt", conv.updatedAt);
            json.put("model", conv.model != null ? conv.model : "");
            json.put("systemPrompt", conv.systemPrompt != null ? conv.systemPrompt : "");
            json.put("titleGenerated", conv.titleGenerated);
            json.put("titleRequested", conv.titleRequested);
            // 首请求时间：只在有值时写，老会话不因为这个功能整体变大
            if (conv.firstRequestAt > 0) json.put("firstRequestAt", conv.firstRequestAt);
            if (conv.firstRequestStamp != null && conv.firstRequestStamp.length() > 0) {
                json.put("firstRequestStamp", conv.firstRequestStamp);
            }

            JSONArray msgs = new JSONArray();
            for (Message m : conv.messages) {
                if (m == null) continue;
                JSONObject mj = new JSONObject();
                // JSONObject.put(String, Object) 遇到 null 会写成 JSON null，
                // 而 optString() 读回来是字符串 "null"——消息会变成字面量 null。
                mj.put("role", m.role == null ? Message.ROLE_USER : m.role);
                mj.put("content", m.content == null ? "" : m.content);
                mj.put("timestamp", m.timestamp);
                // 只在有值时写：老会话不该因为加了这个功能就整体重写变大
                if (m.finishedAt > 0) mj.put("finishedAt", m.finishedAt);
                if (m.totalTokens > 0) {
                    mj.put("promptTokens", m.promptTokens);
                    mj.put("completionTokens", m.completionTokens);
                    mj.put("totalTokens", m.totalTokens);
                    // 估算标记必须一起存：只存数字不存来源，重开会话后
                    // 估算值会被当成端点返回的真实用量，用户对账时对不上。
                    if (m.tokensEstimated) mj.put("tokensEstimated", true);
                }
                // 只在有值时写：老会话不该因为加了这个功能就整体重写变大
                if (m.cachedTokens > 0) mj.put("cachedTokens", m.cachedTokens);
                if (m.requestCount > 1) mj.put("requestCount", m.requestCount);
                // 逐轮用量：只在多轮时写。单轮时 prompt/completion 已经表达了同一件事，
                // 存一份等于每条消息多几十字节，几百条会话也是白占。
                if (m.rounds != null && m.rounds.size() > 1) {
                    JSONArray ra = new JSONArray();
                    for (Message.RoundUsage r : m.rounds) {
                        if (r == null) continue;
                        JSONObject ro = new JSONObject();
                        ro.put("p", r.prompt);
                        ro.put("c", r.completion);
                        if (r.cached > 0) ro.put("h", r.cached);
                        if (r.ms > 0) ro.put("ms", r.ms);
                        if (r.estimated) ro.put("e", true);
                        ra.put(ro);
                    }
                    if (ra.length() > 0) mj.put("rounds", ra);
                }
                if (m.interrupted) mj.put("interrupted", true);
                if (m.apiText != null && m.apiText.length() > 0) {
                    mj.put("apiText", m.apiText);
                }
                // 附件与正文分开存：content 只有原话，附件正文在这里，一份就够。
                // 早先附件正文挤在 apiText 里、摘要行挤在 content 里，同一个文件存两遍。
                if (m.attachments != null && !m.attachments.isEmpty()) {
                    JSONArray aa = new JSONArray();
                    for (AttachmentText.File f : m.attachments) {
                        if (f == null) continue;
                        JSONObject fo = new JSONObject();
                        fo.put("name", f.name);
                        if (f.bytes > 0) fo.put("bytes", f.bytes);
                        if (f.isImage()) {
                            // 图片不落正文：路径 + 尺寸 + 缩略图足够重画卡片与重新打开查看器。
                            // 正文（content）对图片本来就是空串，写字面量只会让 JSON 多一行。
                            fo.put("kind", AttachmentText.KIND_IMAGE);
                            if (f.path.length() > 0) fo.put("path", f.path);
                            if (f.width > 0) fo.put("w", f.width);
                            if (f.height > 0) fo.put("h", f.height);
                            if (f.thumb.length() > 0) fo.put("thumb", f.thumb);
                        } else {
                            fo.put("content", f.content);
                        }
                        // 发送时的截断快照，见 AttachmentText.File#sentChars。
                        // 三态都要写：NOT_SENT 不写（省字节），全文写 -1，
                        // 截断写实际字符数。全文也必须写——不写的话读回会被当成
                        // 「没发过」，下一轮重拼上下文时又按当前预算重算，等于白存。
                        if (f.sentChars != AttachmentText.File.NOT_SENT) {
                            fo.put("sent", f.sentChars);
                        }
                        aa.put(fo);
                    }
                    if (aa.length() > 0) mj.put("attachments", aa);
                }
                if (m.toolHistory != null && m.toolHistory.length() > 2) {
                    mj.put("toolHistory", m.toolHistory);
                }
                // 失败标记与错误详情需要重启后仍可见，因此一并落盘；
                // 但它们只是显示用字段，不会进入请求体（见 ChatHistory.build）
                if (m.sendFailed) {
                    mj.put("sendFailed", true);
                    if (m.errorDetail != null && m.errorDetail.length() > 0) {
                        mj.put("errorDetail", m.errorDetail);
                    }
                }
                if (m.sources != null && !m.sources.isEmpty()) {
                    JSONArray sa = new JSONArray();
                    for (Message.Source s : m.sources) {
                        if (s == null) continue;
                        JSONObject so = new JSONObject();
                        so.put("title", s.title == null ? "" : s.title);
                        so.put("url", s.url == null ? "" : s.url);
                        sa.put(so);
                    }
                    if (sa.length() > 0) mj.put("sources", sa);
                }
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
            String rawId = json.optString("id", "");
            // 旧版本可能把 null 写成字符串 "null"，那样这个会话永远匹配不上
            // 任何一次 switchConversation，等于打不开
            if (rawId.length() > 0 && !"null".equalsIgnoreCase(rawId)) {
                conv.id = rawId;
            }
            conv.title = normalizeTitle(json.optString("title", null));
            // 缺失时间字段时不能用「当前时间」，否则每次加载都变成「刚刚」、排序不断漂移；
            // 用 0/createdAt 兜底，保证同一文件每次得到相同值。
            conv.createdAt = json.has("createdAt") ? json.optLong("createdAt", 0) : 0;
            conv.updatedAt = json.has("updatedAt") ? json.optLong("updatedAt", 0) : 0;
            if (conv.createdAt == 0 && conv.updatedAt != 0) conv.createdAt = conv.updatedAt;
            if (conv.updatedAt == 0) conv.updatedAt = conv.createdAt;
            conv.model = json.optString("model", "");
            conv.systemPrompt = json.optString("systemPrompt", "");
            conv.titleGenerated = json.optBoolean("titleGenerated", false);
            conv.titleRequested = json.optBoolean("titleRequested", conv.titleGenerated);
            conv.firstRequestAt = json.optLong("firstRequestAt", 0);
            conv.firstRequestStamp = json.optString("firstRequestStamp", "");

            conv.messages.clear();
            JSONArray msgs = json.optJSONArray("messages");
            if (msgs != null) {
                for (int i = 0; i < msgs.length(); i++) {
                    JSONObject mj = msgs.getJSONObject(i);
                    Message m = new Message();
                    String role = mj.optString("role", Message.ROLE_USER);
                    // 同上：role 一旦是字符串 "null"，isUser()/isAssistant() 全为 false，
                    // 这条消息既渲染不出来也进不了请求体，等于永久丢失
                    m.role = (role.length() == 0 || "null".equalsIgnoreCase(role))
                            ? Message.ROLE_USER : role;
                    m.content = mj.isNull("content") ? "" : mj.optString("content", "");
                    m.timestamp = mj.optLong("timestamp", System.currentTimeMillis());
                    m.finishedAt = mj.optLong("finishedAt", 0);
                    m.promptTokens = mj.optInt("promptTokens", 0);
                    m.completionTokens = mj.optInt("completionTokens", 0);
                    m.totalTokens = mj.optInt("totalTokens", 0);
                    m.tokensEstimated = mj.optBoolean("tokensEstimated", false);
                    // 命中数与中断标记：只存不读等于重启后丢数据，
                    // 命中率会突然从 50% 掉到 0%，中断消息又会重新出现估算值
                    m.cachedTokens = mj.optInt("cachedTokens", 0);
                    m.requestCount = mj.optInt("requestCount", 0);
                    // 逐轮明细：老会话没有这个字段，读回空列表即可——
                    // 缺明细只影响「每轮」那一行，主行的输入/输出仍然在。
                    JSONArray rra = mj.optJSONArray("rounds");
                    if (rra != null) {
                        for (int j = 0; j < rra.length(); j++) {
                            JSONObject ro = rra.optJSONObject(j);
                            if (ro == null) continue;
                            m.rounds.add(new Message.RoundUsage(
                                    ro.optInt("p", 0), ro.optInt("c", 0), ro.optInt("h", 0),
                                    ro.optLong("ms", 0), ro.optBoolean("e", false)));
                        }
                    }
                    m.interrupted = mj.optBoolean("interrupted", false);
                    m.sendFailed = mj.optBoolean("sendFailed", false);
                    m.errorDetail = mj.optString("errorDetail", "");
                    m.apiText = mj.optString("apiText", "");
                    m.toolHistory = mj.optString("toolHistory", "");
                    JSONArray aa = mj.optJSONArray("attachments");
                    if (aa != null) {
                        for (int j = 0; j < aa.length(); j++) {
                            JSONObject ao = aa.optJSONObject(j);
                            if (ao == null) continue;
                            AttachmentText.File af = new AttachmentText.File(
                                    ao.optString("name", ""),
                                    ao.isNull("content") ? "" : ao.optString("content", ""),
                                    ao.optLong("bytes", 0),
                                    ao.optString("kind", AttachmentText.KIND_TEXT),
                                    ao.optString("path", ""),
                                    ao.optInt("w", 0),
                                    ao.optInt("h", 0),
                                    ao.optString("thumb", ""));
                            // 老版本没存过这个字段，读回 NOT_SENT（= 当时的发送量未知）。
                            // 这里刻意不按当前预算补算：补算出来的数字是今天的配置，
                            // 不是当时的事实，显示它比不显示更误导。
                            af.sentChars = ao.optInt("sent", AttachmentText.File.NOT_SENT);
                            m.attachments.add(af);
                        }
                    }
                    // 1.8.2 及以前的用户消息把附件摊在正文里：读进来就地迁移成结构化字段，
                    // 老会话打开后附件也是独立卡片，编辑正文不会再把它改没。
                    if (m.attachments.isEmpty()) AttachmentText.migrateLegacy(m);
                    JSONArray sa = mj.optJSONArray("sources");
                    if (sa != null) {
                        for (int j = 0; j < sa.length(); j++) {
                            JSONObject so = sa.optJSONObject(j);
                            if (so == null) continue;
                            String u = so.optString("url", "");
                            if (u.length() == 0) continue;
                            m.sources.add(new Message.Source(so.optString("title", ""), u));
                        }
                    } else if (m.isAssistant()) {
                        // 旧会话：来源曾经以固定格式拼在正文末尾，读取时安全迁移成结构字段
                        migrateLegacySources(m);
                    }
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
