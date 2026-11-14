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
    /**
     * 会话列表。
     *
     * <p>必须是并发安全容器：删除会话已挪到后台线程（deleteOne 会 remove），
     * 而主线程同时可能在 add(0) 新会话、标题生成线程在遍历。普通 ArrayList
     * 在这种组合下会抛 ConcurrentModificationException，或让列表静默丢项。
     */
    private volatile List<Conversation> conversations;
    private Conversation currentConversation;
    /**
     * 保存队列：一次会话可能有几百条消息，序列化 + 写盘在主线程做会直接掉帧。
     * 单线程保证同一会话的多次保存按调用顺序落盘（后写覆盖前写是期望行为）。
     */
    private final ExecutorService saveExecutor = Executors.newSingleThreadExecutor();

    /**
     * 待落盘快照，按会话 id 合并：同一个会话在队列里只保留最新一份。
     *
     * <p>原来每次保存都 execute 一个独立任务、各自把整条会话重新序列化并整文件重写。
     * 而一次回答的收尾路径会连着排进好几个：轮次结束、改标题、失败标记、切会话前保存。
     * 一条 300 条消息、含多张 base64 图片的会话，单次 toJson + 写盘 20~80ms，
     * 谁都没变的那几份就是纯浪费，排队期间用户在等下一次操作。
     *
     * <p>合并后语义不变：写的一定是「最后一次请求保存的时刻」的内容快照；
     * 只是中间那几份被同一份终态覆盖掉了——它们本来也会被覆盖。
     */
    private final java.util.Map<String, Conversation> pendingSaves =
            new java.util.HashMap<String, Conversation>();
    private boolean saveDraining;
    /** flushSaves 的等待者：队列排空时统一唤醒。 */
    private final java.util.List<java.util.concurrent.CountDownLatch> flushWaiters =
            new java.util.ArrayList<java.util.concurrent.CountDownLatch>();

    /** 队列排空任务：一次只写一条，写完再看队列；空则收工并唤醒 flush 等待者。 */
    private final Runnable saveDrainTask = new Runnable() {
        public void run() {
            while (true) {
                Conversation c;
                synchronized (pendingSaves) {
                    java.util.Iterator<java.util.Map.Entry<String, Conversation>> it =
                            pendingSaves.entrySet().iterator();
                    if (!it.hasNext()) {
                        saveDraining = false;
                        for (int i = 0; i < flushWaiters.size(); i++) {
                            flushWaiters.get(i).countDown();
                        }
                        flushWaiters.clear();
                        return;
                    }
                    c = it.next().getValue();
                    it.remove();
                }
                try {
                    writeConversation(c);
                } catch (Throwable t) {
                    // 单条写失败不能让整个队列停摆，否则后续所有保存都卡死在这里
                    LogUtil.e(TAG, "save failed: " + t.getMessage());
                }
            }
        }
    };

    /** 入队一次保存；同 id 覆盖，保证写的是最新内容。 */
    private void enqueueSave(Conversation snapshot) {
        if (snapshot == null) return;
        synchronized (pendingSaves) {
            pendingSaves.put(snapshot.id, snapshot);
            if (saveDraining) return;
            saveDraining = true;
        }
        saveExecutor.execute(saveDrainTask);
    }

    // 被删除的「当前会话」的消息列表：主界面 messages 仍指向它，留给下一个 current 复用，避免分裂
    private List<Message> orphanMessages;
    /**
     * 已删除的会话 id。flushSaves 有超时，队列里可能还压着这个会话的保存任务；
     * 不拦一下，删完文件后那条迟到的保存会把它重新写回来（列表里「删了又出现」）。
     */
    private final java.util.Set<String> deletedIds =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /**
     * 会话列表索引。列表页只读它拿标题/时间/条数，不再逐个解析会话文件。
     * 所有读写都在 {@link #indexLock} 内：加载在后台线程，保存在 saveExecutor，
     * 删除可能来自主线程，三边会碰同一个 map。
     */
    private ConversationIndex index;
    private String indexDirPath;
    private final Object indexLock = new Object();

    private ConversationManager() {
        this.storageManager = StorageManager.getInstance();
        this.conversations = new java.util.concurrent.CopyOnWriteArrayList<Conversation>();
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
        // 显式切回/复用某个会话：解除删除拉黑，避免 deletedIds 只增不减导致
        // 该 id 一旦被删过就永远无法再保存。
        if (conv != null) deletedIds.remove(conv.id);
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
        //
        // 非主线程同样走队列：保存线程只有一条，入队顺序就是落盘顺序；而按 id 合并
        // 能让「同一会话连排四五份」收敛成一份，避免把同一份终态内容重复序列化写盘。
        enqueueSave(snapshotOf(conv));
    }

    /** 上一次索引落盘时间，用于节流（见 writeConversation）。 */
    private volatile long lastIndexSaveAt;

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
        if (ok) {
            // 删除与这次写盘可能并发：开头那次 deletedIds 检查早于 toJson/写盘，
            // 拦不住「删除线程在我写盘途中才 add」的交错。此时文件已被删，
            // renameAtomic 目标不存在会直接成功 → 文件复活，且该 id 被
            // deletedIds 永久拉黑、后续所有保存被静默跳过。写盘后再查一次，
            // 命中就立刻删掉刚复活的文件，保证「删掉」是终态。
            if (deletedIds.contains(conv.id)) {
                storageManager.deleteConversation(conv.id);
                LogUtil.w(TAG, "save raced with delete: removed resurrected id=%s", conv.id);
                return;
            }
            // 索引跟着落盘：下次打开历史列表只读这一份元数据，不再解析本文件。
            // 必须在写完文件之后取 mtime/长度，否则记下的是旧值，下次必然判定失效。
            java.io.File dir = new java.io.File(storageManager.getConversationsPath());
            java.io.File f = new java.io.File(dir, conv.id + ".json");
            ConversationIndex idx = indexFor(dir);
            synchronized (indexLock) {
                long mtime = f.lastModified();
                long fileBytes = f.length();
                ConversationIndex.Entry old = idx.get(conv.id);
                // 「同秒 + 等长」写两次时，冷启动靠 mtime/length 判定索引
                // 失效会命中旧条目（旧标题/旧条数/旧时间）。这种场景磁盘
                // 索引必须立刻跟上，不能等 5 秒节流 —— 否则进程被杀就永久
                // 读到旧元数据。正常场景 mtime 会变，仍走节流不增加写盘。
                boolean sameStamp = old != null && old.mtime == mtime
                        && old.fileBytes == fileBytes;
                idx.put(conv, mtime, fileBytes);
                long now = System.currentTimeMillis();
                // 索引落盘按 5 秒节流，内存里始终是最新的。
                //
                // 原来每条消息保存都要把索引整份重新序列化并 rename 一次
                // （sameContent 把 mtime 也算进「变了没」，而刚写完文件 mtime 必变，
                // 所以 dirty 永远为 true）。索引 300 条时每条约 5~15ms CPU + 一次写盘。
                // 落后几秒的唯一后果是「下次冷启动多解析几个文件」——索引本身
                // 就是靠 mtime/length 比对自愈的（见 ConversationIndex 头部注释）。
                // 删除与批量路径走 saveIndexOnce()，那里不节流，保证删掉的会话不残留。
                if (idx.isDirty() && (sameStamp || now - lastIndexSaveAt >= 5000L)) {
                    idx.save(dir);
                    lastIndexSaveAt = now;
                }
            }
        }
        LogUtil.d(TAG, "saveCurrentConversation: id=%s msgs=%d jsonLen=%d ok=%s",
                conv.id, conv.messages.size(), json.length(), ok);
    }

    /** 异步保存任意会话（列表页重命名等场景），不要求它是 current。 */
    public void saveConversationAsync(final Conversation conv) {
        if (conv == null) return;
        saveExecutor.execute(new Runnable() {
            public void run() {
                // 列表项来自索引，messages 是空的。不先补读就保存，会把整段历史覆盖成空文件——
                // 重命名这种「只改标题」的操作尤其危险，用户看起来一切正常，回去后对话没了。
                ensureMessagesLoaded(conv);
                // 只拦「补读失败」：索引说有消息、却一条都没读出来，此时写盘会用空列表
                // 覆盖磁盘上的完整历史。不能用 isEmpty() 一刀切——0 条消息的会话本来就是空的，
                // 重命名它也该能落盘，否则用户改完标题重启后又变回去。
                if (conv.messages == null
                        || (conv.messages.isEmpty() && conv.messageCount > 0)) {
                    LogUtil.w(TAG, "saveConversationAsync skipped: id=%s load incomplete, refuse overwrite", conv.id);
                    return;
                }
                enqueueSave(snapshotOf(conv));
            }
        });
    }

    /**
     * 等队列里的保存全部落盘。删会话前必须调用：否则队列里还挂着的保存
     * 会把刚删掉的文件又写回来。
     */
    public void flushSaves() {
        flushSaves(1500);
    }

    /**
     * 带超时的排空。
     *
     * @param timeoutMs 最长等多久；超时返回 false，但不影响已排队的保存继续写
     *                  （删除路径已用 deletedIds 拦住迟到的写，所以超时不再致命）
     */
    public boolean flushSaves(long timeoutMs) {
        final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(1);
        synchronized (pendingSaves) {
            // 双检：队列空且没有在跑的排空任务，直接返回，一次线程切换都不花
            if (!saveDraining && pendingSaves.isEmpty()) return true;
            flushWaiters.add(latch);
            if (!saveDraining) {
                saveDraining = true;
                saveExecutor.execute(saveDrainTask);
            }
        }
        try {
            return latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
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

    /** 切换会话的分阶段进度。只在后台线程调用，宿主自行切回 UI 线程更新界面。 */
    public interface LoadProgress {
        /** @param percent 0~100 的整体完成度 */
        void onStage(String label, int percent);
    }

    public void switchConversation(String conversationId) {
        switchConversation(conversationId, null);
    }

    /**
     * 切换到指定会话，并在关键节点回报进度。
     *
     * <p>阶段是按真实工作拆的，不是假进度条：读盘、JSON 解析、恢复附件、准备界面
     * 各占一段。解析完成后把消息条数写进文案，用户能看出「大会话为什么慢」。
     */
    public void switchConversation(String conversationId, LoadProgress progress) {
        LogUtil.i(TAG, "switchConversation -> %s", conversationId);
        stage(progress, "校验会话信息", 5);
        // id 会被拼进文件路径，而它的来源可能是外部 Intent（MainActivity 是
        // exported 的）或手工改过的 JSON。非法 id 一律当作「新会话」处理，
        // 否则一次带 ../ 的 Intent 就能让后续保存覆盖到 conversations 目录之外。
        if (!StorageManager.isValidConversationId(conversationId)) {
            LogUtil.w(TAG, "switchConversation rejected: illegal id '%s'", conversationId);
            stage(progress, "会话 ID 无效", 100);
            return;
        }
        this.orphanMessages = null;

        Conversation inMemory = null;
        for (Conversation c : this.conversations) {
            if (c.id.equals(conversationId)) { inMemory = c; break; }
        }
        if (inMemory != null) {
            stage(progress, "读取会话正文", 35);
            ensureMessagesLoaded(inMemory);
            this.currentConversation = inMemory;
            stage(progress, "解析消息记录（" + inMemory.messages.size() + " 条）", 70);
            LogUtil.d(TAG, "switchConversation: found in memory, msgs=%d", inMemory.messages.size());
        } else {
            stage(progress, "读取会话文件", 30);
            String content = storageManager.loadConversation(conversationId);
            if (content != null) {
                stage(progress, "解析消息记录", 55);
                Conversation conv = fromJson(content);
                // 文件里的 id 字段可能与文件名不一致（手工改过、旧版本写错）。
                // 文件名才是这条会话的身份：不一致时必须纠正，否则之后
                // 「删除这条」删的是另一个文件，保存也会写到另一个 id 下。
                if (!conversationId.equals(conv.id)) {
                    LogUtil.w(TAG, "switchConversation: id mismatch file=%s json=%s, use file",
                            conversationId, conv.id);
                    conv.id = conversationId;
                }
                this.currentConversation = conv;
                this.conversations.add(0, conv);
                stage(progress, "解析消息记录（" + conv.messages.size() + " 条）", 75);
                LogUtil.d(TAG, "switchConversation: loaded from disk, msgs=%d", conv.messages.size());
            } else {
                this.currentConversation = new Conversation(conversationId);
                this.conversations.add(0, this.currentConversation);
                stage(progress, "新建空会话", 75);
                LogUtil.w(TAG, "switchConversation: '%s' not on disk, created empty", conversationId);
            }
        }
        stage(progress, "恢复附件与工具记录", 88);
        stage(progress, "准备界面", 95);
        stage(progress, "加载完成", 100);
    }

    private static void stage(LoadProgress p, String label, int percent) {
        if (p != null) p.onStage(label, percent);
    }

    /**
     * 补齐会话正文。
     *
     * <p>列表页为了快只读索引，构造出来的 Conversation 只有元数据，{@code messages} 是空列表
     * 但会话其实不空。切到它之前必须补读——否则界面一片空白，更糟的是下一条消息保存时
     * 会用这份空列表覆盖磁盘上的完整历史。
     */
    private synchronized void ensureMessagesLoaded(Conversation c) {
        if (c == null || c.messagesLoaded) return;
        String content = storageManager.loadConversation(c.id);
        if (content == null || content.length() == 0) {
            // 文件没了：当作空会话，至少不会崩，也不会写坏别的东西。
            // messageCount 保留索引里的原值，saveConversationAsync 据此拒绝覆盖写盘。
            c.messagesLoaded = true;
            LogUtil.w(TAG, "ensureMessagesLoaded: '%s' missing on disk, treated as empty", c.id);
            return;
        }
        Conversation full = fromJson(content);
        // 解析不完整时 fromJson 内部吞掉异常、返回带空 messages 的壳。索引里记着有消息，
        // 说明文件本该有内容：此时绝不能用空列表覆盖 c.messages，否则用户重命名一次
        // 就会把磁盘上的完整历史写成空文件。标 loaded 是为了不再反复读这个坏文件。
        if (full == null || full.messages == null
                || (full.messages.isEmpty() && c.messageCount > 0)) {
            c.messagesLoaded = true;
            LogUtil.w(TAG, "ensureMessagesLoaded: '%s' parse incomplete (index says %d msgs), keep empty, refuse overwrite",
                    c.id, c.messageCount);
            return;
        }
        // 只补正文与条数，不用磁盘值回写 title / updatedAt 等元数据。
        // 「重命名」的调用链是 改 title → saveConversationAsync → 本方法 → 写盘，
        // 这里若拿磁盘上的旧 title 覆盖，用户改的名字会在落盘前被抹掉，表现为「重命名没生效」。
        c.messages = full.messages;
        c.messageCount = full.messages.size();
        c.messagesLoaded = true;
        LogUtil.i(TAG, "ensureMessagesLoaded: %s msgs=%d", c.id, c.messages.size());
    }

    public void deleteConversation(String conversationId) {
        // 先落盘队列里的写：否则删完文件后，队列里排着的保存会把它重新写回来
        flushSaves();
        java.util.Set<String> own = new java.util.HashSet<String>();
        deleteOne(conversationId, own);
        saveIndexOnce();
        StorageCleaner.recycleAsync(storageManager, own);
    }

    /**
     * 批量删除：索引只重写一次。
     *
     * <p>原来 N 条删除会触发 N 次索引全量序列化（O(N×M)，M 为索引条数），
     * 删几十条等于把同一份文件白写几十遍。调用方需在后台线程执行（含磁盘 IO）。
     */
    public void deleteConversations(java.util.List<String> ids) {
        if (ids == null || ids.isEmpty()) return;
        flushSaves();
        // 候选附件攒成并集，整批只回收一次。
        //
        // StorageCleaner.recycleAsync 内部会把整个 conversations 目录重扫一遍
        // （它要确认某张图没有被别的会话引用）。原来每条删除各调一次，删 N 条
        // 含图会话就是 N 次全目录扫描 + N 个线程——「清空历史」在 4.4 上是分钟级
        // IO、CPU 打满，而 UI 早已弹完「已删除」。
        // 合并后判据反而更严：扫描发生在所有会话文件都删完之后，看到的引用只剩活着的。
        java.util.Set<String> candidates = new java.util.HashSet<String>();
        for (int i = 0; i < ids.size(); i++) {
            deleteOne(ids.get(i), candidates);
        }
        saveIndexOnce();
        StorageCleaner.recycleAsync(storageManager, candidates);
    }

    /**
     * 删单条：不 flush 保存队列、不写索引、不回收附件，由调用方统一收口。
     *
     * @param attachmentSink 收集本条会话引用过的附件名，由调用方合并后统一回收
     */
    private void deleteOne(String conversationId, java.util.Set<String> attachmentSink) {
        LogUtil.i(TAG, "deleteConversation: id=%s, before=%d", conversationId, this.conversations.size());
        // 先置位再读附件。读附件要扫一遍这条会话的 JSON（可能几 MB），是个长窗口；
        // 窗口里主线程若又入队一次保存（流式收尾、切后台都会），保存线程已经过了
        // writeConversation 里的 deletedIds 检查，等它 rename 完，删掉的文件就复活了。
        // flush 可能超时（大会话序列化慢），残余任务靠这里兜底拦掉。
        deletedIds.add(conversationId);
        // 附件文件名必须在删文件之前取：删完就无从知道这条会话引用过哪些图
        java.util.Set<String> ownAttachments =
                StorageCleaner.attachmentNames(storageManager, conversationId);
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
        // 索引里同步剔除，否则下次进列表这条会以「文件已不存在」的形式再被清一次，
        // 白跑一趟 retainOnly。落盘统一在 saveIndexOnce()。
        java.io.File dir = new java.io.File(storageManager.getConversationsPath());
        ConversationIndex idx = indexFor(dir);
        synchronized (indexLock) {
            idx.remove(conversationId);
        }
        boolean ok = storageManager.deleteConversation(conversationId);
        // 回收只被这条会话引用的图片：候选集交给调用方合并后统一异步回收，
        // 避免批量删除时每条都触发一次全目录重扫
        if (attachmentSink != null) attachmentSink.addAll(ownAttachments);
        LogUtil.d(TAG, "deleteConversation: file deleted=%s, remaining=%d, attachments=%d",
                ok, this.conversations.size(), ownAttachments.size());
    }

    private void saveIndexOnce() {
        java.io.File dir = new java.io.File(storageManager.getConversationsPath());
        ConversationIndex idx = indexFor(dir);
        synchronized (indexLock) {
            if (idx.isDirty()) idx.save(dir);
        }
    }

    public List<Conversation> loadConversations() {
        LogUtil.i(TAG, "loadConversations (%s)", LogUtil.thread());
        long started = System.currentTimeMillis();
        java.io.File dir = new java.io.File(storageManager.getConversationsPath());
        ConversationIndex idx = indexFor(dir);
        List<Conversation> loaded = new ArrayList<Conversation>();
        java.util.Set<String> alive = new java.util.HashSet<String>();
        int fromIndex = 0;
        int parsed = 0;
        String[] files = storageManager.getConversationFiles();
        if (files != null) {
            LogUtil.d(TAG, "loadConversations: %d files on disk", files.length);
            for (String file : files) {
                if (!file.endsWith(".json")) continue;
                String conversationId = file.substring(0, file.length() - 5);
                alive.add(conversationId);
                java.io.File f = new java.io.File(dir, file);
                long mtime = f.lastModified();
                long bytes = f.length();
                ConversationIndex.Entry hit;
                synchronized (indexLock) {
                    hit = idx.get(conversationId);
                }
                // 索引命中且文件没被动过：直接用元数据，不读文件内容。
                // mtime 之外还要比字节数——部分老文件系统 lastModified 只有秒级精度，
                // 同一秒内改过又恰好长度相同的话，光看时间会漏掉更新。
                if (hit != null && hit.mtime == mtime && hit.fileBytes == bytes) {
                    loaded.add(hit.toConversation());
                    fromIndex++;
                    continue;
                }
                String content = storageManager.loadConversation(conversationId);
                if (content == null || content.length() == 0) continue;
                try {
                    Conversation conv = fromJson(content);
                    // 以文件名为准（理由见 switchConversation）：id 与文件名不一致时，
                    // 列表里点「删除」会删到另一个文件，用户看到「删了 A，B 也没了」。
                    conv.id = conversationId;
                    // 体积取磁盘实际字节，不按正文字符数估：JSON 转义、缩略图 Base64、
                    // 中文 UTF-8 多字节都会让字符数与文件大小差出好几倍
                    conv.fileBytes = bytes;
                    conv.messagesLoaded = true;
                    synchronized (indexLock) {
                        idx.put(conv, mtime, bytes);
                    }
                    loaded.add(conv);
                    parsed++;
                } catch (Exception e) {
                    LogUtil.e(TAG, "loadConversations: failed to parse '%s': %s", file, e.getMessage(), e);
                }
            }
        } else {
            LogUtil.w(TAG, "loadConversations: file list is null");
        }

        // 索引里剩下磁盘上已经没有的 id（删掉的会话），剔除后写回
        synchronized (indexLock) {
            idx.retainOnly(alive);
            if (idx.isDirty()) idx.save(dir);
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

        // 重建后的列表同样要是并发容器：这里一替换引用，后台删除线程可能还持有旧引用，
        // 换成 ArrayList 就等于把上面的并发保证又撤掉了。
        this.conversations =
                new java.util.concurrent.CopyOnWriteArrayList<Conversation>(loaded);
        // 列表已从磁盘重建，之前暂存的消息列表不再对应任何会话
        this.orphanMessages = null;
        LogUtil.i(TAG, "loadConversations done: %d conversations (index=%d, parsed=%d, %dms)",
                this.conversations.size(), fromIndex, parsed,
                System.currentTimeMillis() - started);
        return this.conversations;
    }

    /** 索引按目录缓存：存储根切换（内部/外部）时目录变了要重新加载。 */
    private ConversationIndex indexFor(java.io.File dir) {
        synchronized (indexLock) {
            if (index == null || !dir.getAbsolutePath().equals(indexDirPath)) {
                index = ConversationIndex.load(dir);
                indexDirPath = dir.getAbsolutePath();
            }
            return index;
        }
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
            // 任何一次 switchConversation，等于打不开；含路径分隔符的 id 更要拒绝
            // ——它会让后续保存写到别的目录去。
            if (rawId.length() > 0 && !"null".equalsIgnoreCase(rawId)
                    && StorageManager.isValidConversationId(rawId)) {
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
        // 消息条数在这里统一填一次：列表页可能只有索引、没有 messages，
        // 但「N 条消息」要跟真实文件一致
        conv.messageCount = conv.messages.size();
        conv.messagesLoaded = true;
        LogUtil.v(TAG, "fromJson: id=%s title=%s msgs=%d", conv.id, conv.title, conv.messages.size());
        return conv;
    }
}
