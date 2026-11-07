package xyz.zip8919.app.aichat;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话列表索引：把列表页要用的元数据从每个会话文件里抽出来，单独存成一个小文件。
 *
 * <p><b>为什么需要它</b>：{@link ConversationManager#loadConversations()} 原来是
 * 「listFiles → 逐个 loadConversation → 逐个 fromJson」，每个会话文件都要完整读一遍再解析。
 * 会话文件里既有全部消息、又有图片缩略图的 Base64，几十个会话就是几十次大文件读 + JSON 解析，
 * 在老设备（sc8830 + 慢速 eMMC）上列表能转好几秒。列表页其实只用得到标题、时间、条数、体积。
 *
 * <p><b>失效判定</b>：每个条目记下写索引时文件的 {@code lastModified} 与字节数。
 * 下次加载时只要这两个值都对得上就直接用索引，不再碰文件内容；对不上（文件被改过）就
 * 回退解析那<b>一个</b>文件并更新索引。索引里多出来的 id 说明文件已被删，剔除。
 * 所以索引只影响「快不快」，不影响「对不对」——索引丢了、坏了、版本不符，最坏结果就是退回全量解析。
 *
 * <p><b>格式</b>：首行版本号，其后每行一个 JSON 对象（NDJSON）。不用一个大 JSON 数组，
 * 是为了单行损坏时能跳过该行而不是整份报废。
 */
class ConversationIndex {

    private static final String TAG = "ConversationIndex";
    static final String FILE_NAME = "conversations.idx";
    /** 格式版本。字段含义发生变化时 +1，旧索引会被忽略并重建。 */
    private static final int VERSION = 1;

    /** 索引里的一条记录。字段名刻意用短键，几十条加起来也就几十 KB。 */
    static class Entry {
        String id;
        String title;
        String model;
        String firstRequestStamp;
        long createdAt;
        long updatedAt;
        long firstRequestAt;
        /** 写索引时文件的字节数，兼作失效判定条件之一。 */
        long fileBytes;
        /** 写索引时文件的 lastModified。 */
        long mtime;
        int messageCount;
        boolean titleGenerated;
        boolean titleRequested;

        /** 还原成列表项：只带元数据，{@code messages} 留空、{@code messagesLoaded=false}。 */
        Conversation toConversation() {
            Conversation c = new Conversation();
            c.id = id;
            c.title = title == null ? "" : title;
            c.createdAt = createdAt;
            c.updatedAt = updatedAt;
            c.model = model == null ? "" : model;
            c.firstRequestAt = firstRequestAt;
            c.firstRequestStamp = firstRequestStamp == null ? "" : firstRequestStamp;
            c.titleGenerated = titleGenerated;
            c.titleRequested = titleRequested;
            c.messageCount = messageCount;
            c.fileBytes = fileBytes;
            // 正文没读：切到这条会话时由 ConversationManager 按需补读
            c.messagesLoaded = false;
            return c;
        }

        static Entry from(Conversation c, long mtime, long bytes) {
            Entry e = new Entry();
            e.id = c.id;
            e.title = c.title;
            e.model = c.model;
            e.firstRequestStamp = c.firstRequestStamp;
            e.createdAt = c.createdAt;
            e.updatedAt = c.updatedAt;
            e.firstRequestAt = c.firstRequestAt;
            e.messageCount = c.messages == null ? 0 : c.messages.size();
            e.titleGenerated = c.titleGenerated;
            e.titleRequested = c.titleRequested;
            e.fileBytes = bytes;
            e.mtime = mtime;
            return e;
        }

        boolean sameContent(Entry o) {
            if (o == null) return false;
            return messageCount == o.messageCount
                    && mtime == o.mtime
                    && fileBytes == o.fileBytes
                    && createdAt == o.createdAt
                    && updatedAt == o.updatedAt
                    && titleGenerated == o.titleGenerated
                    && titleRequested == o.titleRequested
                    && eq(title, o.title)
                    && eq(model, o.model)
                    && eq(firstRequestStamp, o.firstRequestStamp);
        }

        private static boolean eq(String a, String b) {
            return a == null ? b == null : a.equals(b);
        }
    }

    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<String, Entry>();
    private boolean dirty;

    /** 从磁盘加载。任何异常都退化成空索引（下次加载会全量解析并重建）。 */
    static ConversationIndex load(File dir) {
        ConversationIndex idx = new ConversationIndex();
        File f = new File(dir, FILE_NAME);
        if (!f.exists() || f.length() == 0) {
            idx.dirty = true;
            return idx;
        }
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"), 8192);
            String head = r.readLine();
            if (head == null || head.indexOf("\"v\":" + VERSION) < 0) {
                LogUtil.w(TAG, "index version mismatch, will rebuild");
                idx.dirty = true;
                return idx;
            }
            String line;
            int bad = 0;
            while ((line = r.readLine()) != null) {
                if (line.length() == 0) continue;
                try {
                    Entry e = parse(line);
                    if (e != null && e.id != null && e.id.length() > 0) {
                        idx.entries.put(e.id, e);
                    }
                } catch (Exception ex) {
                    // 单行坏掉不影响其余条目；这种情况也会让 dirty 置位，下次整体重写
                    bad++;
                }
            }
            if (bad > 0) {
                idx.dirty = true;
                LogUtil.w(TAG, "index: skipped %d broken line(s), will rewrite", bad);
            }
            LogUtil.i(TAG, "index loaded: %d entries", idx.entries.size());
        } catch (Exception e) {
            LogUtil.w(TAG, "index load failed, will rebuild: " + e.getMessage());
            idx.entries.clear();
            idx.dirty = true;
        } finally {
            close(r);
        }
        return idx;
    }

    Entry get(String id) {
        return entries.get(id);
    }

    int size() {
        return entries.size();
    }

    boolean isDirty() {
        return dirty;
    }

    /** 写入/更新一条。只有内容真的变了才置 dirty，避免每轮对话都重写索引文件。 */
    void put(Conversation c, long mtime, long bytes) {
        if (c == null || c.id == null) return;
        Entry e = Entry.from(c, mtime, bytes);
        Entry old = entries.get(c.id);
        entries.put(c.id, e);
        if (!e.sameContent(old)) dirty = true;
    }

    void remove(String id) {
        if (id != null && entries.remove(id) != null) dirty = true;
    }

    /** 只保留磁盘上真实存在的 id，返回是否发生了剔除。 */
    boolean retainOnly(Collection<String> aliveIds) {
        List<String> dead = new ArrayList<String>();
        for (Map.Entry<String, Entry> en : entries.entrySet()) {
            if (!aliveIds.contains(en.getKey())) dead.add(en.getKey());
        }
        for (int i = 0; i < dead.size(); i++) entries.remove(dead.get(i));
        if (!dead.isEmpty()) {
            dirty = true;
            LogUtil.i(TAG, "index: dropped %d vanished conversation(s)", dead.size());
        }
        return !dead.isEmpty();
    }

    /**
     * 落盘：先写 .tmp 再 rename。中途被杀进程时，旧索引仍然完整，
     * 最坏是这次更新丢失（下次加载按 mtime 对不上自动重建该条）。
     */
    void save(File dir) {
        if (!dir.exists() && !dir.mkdirs()) {
            LogUtil.w(TAG, "index save skipped: cannot create " + dir);
            return;
        }
        File target = new File(dir, FILE_NAME);
        File tmp = new File(dir, FILE_NAME + ".tmp");
        FileOutputStream fos = null;
        OutputStreamWriter w = null;
        try {
            fos = new FileOutputStream(tmp);
            w = new OutputStreamWriter(fos, "UTF-8");
            w.write("{\"v\":" + VERSION + "}\n");
            for (Entry e : entries.values()) {
                w.write(toLine(e));
                w.write('\n');
            }
            w.flush();
            w.close();
            w = null;
            fos = null;
            if (!tmp.renameTo(target)) {
                // rename 失败（个别文件系统不支持覆盖）时退回直接写
                if (target.exists() && !target.delete()) {
                    LogUtil.w(TAG, "index save: cannot replace old file");
                }
                if (!tmp.renameTo(target)) {
                    LogUtil.w(TAG, "index save: rename failed, keeping old index");
                    return;
                }
            }
            dirty = false;
            LogUtil.d(TAG, "index saved: %d entries, %d bytes", entries.size(), target.length());
        } catch (Exception e) {
            LogUtil.w(TAG, "index save failed: " + e.getMessage());
        } finally {
            if (w != null) {
                try { w.close(); } catch (Exception ignored) { }
            } else if (fos != null) {
                try { fos.close(); } catch (Exception ignored) { }
            }
            if (tmp.exists()) tmp.delete();
        }
    }

    // ---- 序列化 ----

    private static String toLine(Entry e) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", e.id);
        if (e.title != null) o.put("t", e.title);
        if (e.model != null && e.model.length() > 0) o.put("m", e.model);
        if (e.createdAt != 0) o.put("c", e.createdAt);
        if (e.updatedAt != 0) o.put("u", e.updatedAt);
        o.put("n", e.messageCount);
        o.put("b", e.fileBytes);
        o.put("mt", e.mtime);
        if (e.firstRequestAt != 0) o.put("fa", e.firstRequestAt);
        if (e.firstRequestStamp != null && e.firstRequestStamp.length() > 0) {
            o.put("fs", e.firstRequestStamp);
        }
        if (e.titleGenerated) o.put("g", true);
        if (e.titleRequested) o.put("r", true);
        return o.toString();
    }

    private static Entry parse(String line) throws Exception {
        JSONObject o = new JSONObject(line);
        Entry e = new Entry();
        e.id = o.optString("id", null);
        e.title = o.optString("t", "");
        e.model = o.optString("m", "");
        e.createdAt = o.optLong("c", 0);
        e.updatedAt = o.optLong("u", 0);
        e.messageCount = o.optInt("n", 0);
        e.fileBytes = o.optLong("b", 0);
        e.mtime = o.optLong("mt", 0);
        e.firstRequestAt = o.optLong("fa", 0);
        e.firstRequestStamp = o.optString("fs", "");
        e.titleGenerated = o.optBoolean("g", false);
        e.titleRequested = o.optBoolean("r", e.titleGenerated);
        return e;
    }

    private static void close(BufferedReader r) {
        if (r != null) {
            try { r.close(); } catch (Exception ignored) { }
        }
    }
}
