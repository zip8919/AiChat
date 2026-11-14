package xyz.zip8919.app.aichat;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 本地使用统计。
 *
 * <p><b>为什么是事件流而不是按天聚合</b>：上一版把「请求数」和「用量」分别累加到
 * 两个地方（{@code Day.reqs} 与 {@code Day.models[name].reqs}），任何一条路径漏加，
 * 页面上就会出现「总览有 30 次请求，按模型全是 0 次」——数字自相矛盾，
 * 用户立刻不信这个页面。现在只有一种写法：<b>发生一件事，追加一条事件</b>，
 * 总览和明细都由同一条事件聚合出来，结构上不可能不一致。
 *
 * <p>附带好处是任意区间：按天聚合只能查今日/本周/本月，查「10 月 3 日到 10 月 9 日」
 * 就得预存无数种组合。事件流过滤一遍即可。
 *
 * <p>只存本机、本版之后的数据：历史会话没有 prompt cache 字段，回填出来的命中率
 * 一定是错的，宁可从零开始也不给一个看起来精确的假数。旧版 stats.json（v1）
 * 直接丢弃不清空文件——里面按模型的数字本身就是错的，迁移错误比没有更糟。
 */
final class UsageStats {

    private static final String TAG = "UsageStats";
    private static final Object LOCK = new Object();
    private static UsageStats instance;
    private static Context appContext;

    /** 事件上限。一条回复约 2~10 条事件，两万条够用几个月；超了从头部丢最旧的。 */
    private static final int MAX_EVENTS = 20000;

    private static final String EV_REQ = "req";
    private static final String EV_USAGE = "usage";
    private static final String EV_CONV = "conv";
    private static final String EV_ROUND = "round";
    private static final String EV_TOOL = "tool";
    private static final String EV_RETRY = "retry";

    private final List<Ev> events = new ArrayList<Ev>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Runnable flushTask = new Runnable() {
        public void run() {
            flush();
        }
    };
    private volatile boolean dirty;
    /** 只在 LOCK 内访问：load 任务是否已投递（幂等）。 */
    private static boolean loaded;
    /** 磁盘事件已并入 events；加载完成前禁止用半截快照覆盖 stats.json。 */
    private volatile boolean ready;
    /** 加载期间用户点过「清空」：磁盘内容不再回填。 */
    private volatile boolean clearedWhileLoading;
    private final List<Runnable> loadListeners = new ArrayList<Runnable>();

    static void init(Context ctx) {
        if (ctx == null) return;
        UsageStats s;
        boolean needLoad = false;
        synchronized (LOCK) {
            appContext = ctx.getApplicationContext();
            if (instance == null) instance = new UsageStats();
            s = instance;
            if (!loaded) { loaded = true; needLoad = true; }
        }
        if (needLoad) ensureLoaded(s);
    }

    /** 未初始化时返回一个空壳，调用方不用到处判 null（统计丢了不该影响主流程）。 */
    static UsageStats get() {
        UsageStats s;
        boolean needLoad = false;
        synchronized (LOCK) {
            if (instance == null) instance = new UsageStats();
            s = instance;
            // 即使没有走 init() 的入口（后台组件先记一条事件），也必须加载，
            // 否则 ready 永远 false，flush 会一直被门闩挡住、新事件永远不落盘。
            if (!loaded) { loaded = true; needLoad = true; }
        }
        if (needLoad) ensureLoaded(s);
        return s;
    }

    /** 读盘 + 大 JSON 解析全部放到已有的 io 单线程，主线程零阻塞。 */
    private static void ensureLoaded(final UsageStats s) {
        s.io.execute(new Runnable() {
            public void run() {
                // load() 只 catch(Exception)：大 JSON 解析抛 OOM 这类 Error 时
                // 也必须放行 ready 门闩，否则统计永不落盘、页面永不补刷。
                try { s.load(); } finally { s.finishLoad(); }
            }
        });
    }

    private void finishLoad() {
        ready = true;
        final List<Runnable> ls;
        synchronized (loadListeners) {
            ls = new ArrayList<Runnable>(loadListeners);
            loadListeners.clear();
        }
        ui.post(new Runnable() {
            public void run() {
                for (int i = 0; i < ls.size(); i++) {
                    try { ls.get(i).run(); } catch (Throwable ignored) { }
                }
            }
        });
        // 加载期间攒下的 append / clear，加载完补一次完整落盘
        if (dirty) schedule();
    }

    /** 加载完成后补刷一次页面；已就绪则立刻 post。 */
    void whenLoaded(Runnable r) {
        if (r == null) return;
        boolean now;
        synchronized (loadListeners) {
            now = ready;
            if (!now) loadListeners.add(r);
        }
        if (now) ui.post(r);
    }

    // ---------------------------------------------------------------- 数据模型

    /** 一条事件。字段全平铺，避免嵌套对象在 JSON 里翻倍膨胀。 */
    static final class Ev {
        long ts;
        String kind;
        /** 模型名；只有 req / usage 事件有值。 */
        String model;
        /** 服务商名；只有 req / usage 事件有值。 */
        String provider;
        /** 工具名；只有 tool 事件有值。 */
        String name;
        int pt;
        int ct;
        int cached;
        long genMs;
        /** 用量是本地估算（端点没给 usage，例如用户中途打断）。 */
        boolean est;
        /** 工具调用是否成功；其他事件恒为 true。 */
        boolean ok = true;
    }

    static final class Bucket {
        int reqs;
        long pt;
        long ct;
        long cached;
        long genMs;
        /** 其中属于本地估算的部分，页面单独标注，不混进「账单口径」。 */
        long estPt;
        long estCt;

        void addRequest() {
            reqs++;
        }

        void addUsage(int p, int c, int hit, long ms, boolean est) {
            pt += Math.max(0, p);
            ct += Math.max(0, c);
            cached += Math.max(0, hit);
            genMs += Math.max(0, ms);
            if (est) {
                estPt += Math.max(0, p);
                estCt += Math.max(0, c);
            }
        }

        void addTo(Bucket out) {
            out.reqs += reqs;
            out.pt += pt;
            out.ct += ct;
            out.cached += cached;
            out.genMs += genMs;
            out.estPt += estPt;
            out.estCt += estCt;
        }

        int cacheRate() {
            return pt <= 0 ? -1 : (int) Math.round(cached * 100.0 / pt);
        }
    }

    static final class ToolCount {
        int calls;
        int fail;

        void addTo(ToolCount out) {
            out.calls += calls;
            out.fail += fail;
        }
    }

    // ---------------------------------------------------------------- 写入

    void onConversationCreated() {
        append(EV_CONV, null, null, null, 0, 0, 0, 0, false, true);
    }

    void onRound() {
        append(EV_ROUND, null, null, null, 0, 0, 0, 0, false, true);
    }

    /** 一次 HTTP 请求（含工具轮、含 429 重试）。重试另有计数，便于区分请求多的原因。 */
    void onRequest(String model, String provider) {
        append(EV_REQ, model, provider, null, 0, 0, 0, 0, false, true);
    }

    void onRetry() {
        append(EV_RETRY, null, null, null, 0, 0, 0, 0, false, true);
    }

    void onTool(String name, boolean ok) {
        if (name == null || name.length() == 0) return;
        append(EV_TOOL, null, null, name, 0, 0, 0, 0, false, ok);
    }

    /**
     * 一轮请求的用量。
     *
     * <p>只记端点真报过的部分，外加明确标记为估算的部分（{@code est}）：
     * 估算值进不进统计本身是次要的，<b>必须标出来</b>才是关键——
     * 混在一起不标，用户拿统计页对账单，差额永远找不到出处。
     */
    void onUsage(String model, String provider, int prompt, int completion,
                 int cached, long genMs, boolean est) {
        if (prompt <= 0 && completion <= 0) return;
        append(EV_USAGE, model, provider, null, prompt, completion, cached, genMs, est, true);
    }

    private void append(String kind, String model, String provider, String name,
                        int pt, int ct, int cached, long genMs, boolean est, boolean ok) {
        Ev e = new Ev();
        e.ts = System.currentTimeMillis();
        e.kind = kind;
        e.model = model == null ? "" : model;
        e.provider = provider == null ? "" : provider;
        e.name = name == null ? "" : name;
        e.pt = Math.max(0, pt);
        e.ct = Math.max(0, ct);
        e.cached = Math.max(0, cached);
        e.genMs = Math.max(0, genMs);
        e.est = est;
        e.ok = ok;
        synchronized (this) {
            events.add(e);
            // 超上限时丢最旧的一批（一次丢 10%，避免每条都做一次 System.arraycopy）
            int over = events.size() - MAX_EVENTS;
            if (over > 0) {
                events.subList(0, Math.max(over, MAX_EVENTS / 10)).clear();
            }
            dirty = true;
        }
        schedule();
    }

    // ---------------------------------------------------------------- 聚合

    static final int PERIOD_DAY = 0;
    static final int PERIOD_WEEK = 1;
    static final int PERIOD_MONTH = 2;

    static final class Summary {
        long fromTs;
        long toTs;
        int convs;
        int rounds;
        int reqs;
        int retries;
        long pt;
        long ct;
        long cached;
        long genMs;
        long estPt;
        long estCt;
        int toolCalls;
        int toolFail;
        final Map<String, ToolCount> tools = new LinkedHashMap<String, ToolCount>();
        final Map<String, Bucket> models = new LinkedHashMap<String, Bucket>();
        final Map<String, Bucket> providers = new LinkedHashMap<String, Bucket>();

        int cacheRate() {
            return pt <= 0 ? -1 : (int) Math.round(cached * 100.0 / pt);
        }

        double speed() {
            return genMs <= 0 ? 0 : ct * 1000.0 / genMs;
        }

        boolean hasEstimated() {
            return estPt > 0 || estCt > 0;
        }
    }

    /** 今日 / 本周 / 本月（自然周，周一为第一天）。 */
    Summary summarize(int period) {
        long now = System.currentTimeMillis();
        long from;
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(now);
        if (period == PERIOD_DAY) {
            c.set(java.util.Calendar.HOUR_OF_DAY, 0);
            c.set(java.util.Calendar.MINUTE, 0);
            c.set(java.util.Calendar.SECOND, 0);
            c.set(java.util.Calendar.MILLISECOND, 0);
            from = c.getTimeInMillis();
        } else if (period == PERIOD_WEEK) {
            // 自然周从周一算：周日属于上一周，不是下一周的开头
            int dow = c.get(java.util.Calendar.DAY_OF_WEEK);
            int back = (dow == java.util.Calendar.SUNDAY) ? 6 : dow - java.util.Calendar.MONDAY;
            c.add(java.util.Calendar.DAY_OF_MONTH, -back);
            c.set(java.util.Calendar.HOUR_OF_DAY, 0);
            c.set(java.util.Calendar.MINUTE, 0);
            c.set(java.util.Calendar.SECOND, 0);
            c.set(java.util.Calendar.MILLISECOND, 0);
            from = c.getTimeInMillis();
        } else {
            c.set(java.util.Calendar.DAY_OF_MONTH, 1);
            c.set(java.util.Calendar.HOUR_OF_DAY, 0);
            c.set(java.util.Calendar.MINUTE, 0);
            c.set(java.util.Calendar.SECOND, 0);
            c.set(java.util.Calendar.MILLISECOND, 0);
            from = c.getTimeInMillis();
        }
        return summarize(from, now);
    }

    /**
     * 任意区间聚合，闭区间 {@code [fromTs, toTs]}。
     *
     * <p>按事件时间过滤，不按「天」对齐：用户选 3 日 10:00 到 3 日 12:00，
     * 就该只统计这两小时，不能把 3 日全天算进去。
     */
    Summary summarize(long fromTs, long toTs) {
        Summary s = new Summary();
        s.fromTs = fromTs;
        s.toTs = toTs;
        synchronized (this) {
            for (int i = 0; i < events.size(); i++) {
                Ev e = events.get(i);
                if (e.ts < fromTs || e.ts > toTs) continue;
                if (EV_REQ.equals(e.kind)) {
                    s.reqs++;
                    bucket(s.models, e.model).addRequest();
                    bucket(s.providers, e.provider).addRequest();
                } else if (EV_USAGE.equals(e.kind)) {
                    s.pt += e.pt;
                    s.ct += e.ct;
                    s.cached += e.cached;
                    s.genMs += e.genMs;
                    if (e.est) {
                        s.estPt += e.pt;
                        s.estCt += e.ct;
                    }
                    bucket(s.models, e.model).addUsage(e.pt, e.ct, e.cached, e.genMs, e.est);
                    bucket(s.providers, e.provider).addUsage(e.pt, e.ct, e.cached, e.genMs, e.est);
                } else if (EV_CONV.equals(e.kind)) {
                    s.convs++;
                } else if (EV_ROUND.equals(e.kind)) {
                    s.rounds++;
                } else if (EV_RETRY.equals(e.kind)) {
                    s.retries++;
                } else if (EV_TOOL.equals(e.kind)) {
                    s.toolCalls++;
                    if (!e.ok) s.toolFail++;
                    ToolCount t = s.tools.get(e.name);
                    if (t == null) {
                        t = new ToolCount();
                        s.tools.put(e.name, t);
                    }
                    t.calls++;
                    if (!e.ok) t.fail++;
                }
            }
        }
        return s;
    }

    private static Bucket bucket(Map<String, Bucket> map, String key) {
        String k = (key == null || key.length() == 0) ? "未知" : key;
        Bucket b = map.get(k);
        if (b == null) {
            b = new Bucket();
            map.put(k, b);
        }
        return b;
    }

    /** 按请求数从多到少，页面上一眼看到主力模型。 */
    static List<Map.Entry<String, Bucket>> sorted(Map<String, Bucket> map) {
        List<Map.Entry<String, Bucket>> list =
                new ArrayList<Map.Entry<String, Bucket>>(map.entrySet());
        Collections.sort(list, new Comparator<Map.Entry<String, Bucket>>() {
            public int compare(Map.Entry<String, Bucket> a, Map.Entry<String, Bucket> b) {
                return b.getValue().reqs - a.getValue().reqs;
            }
        });
        return list;
    }

    /** 数据的最早时间；没有数据返回 -1（页面用它做「自定义区间」的默认起点）。 */
    long earliestTs() {
        synchronized (this) {
            return events.isEmpty() ? -1 : events.get(0).ts;
        }
    }

    int eventCount() {
        synchronized (this) {
            return events.size();
        }
    }

    void clear() {
        synchronized (this) {
            events.clear();
            dirty = true;
            if (!ready) clearedWhileLoading = true;
        }
        flush();
    }

    // ---------------------------------------------------------------- 落盘

    private void schedule() {
        synchronized (this) {
            dirty = true;
        }
        ui.removeCallbacks(flushTask);
        ui.postDelayed(flushTask, 800);
    }

    private void flush() {
        synchronized (this) {
            // 加载未完成时 events 只有本次启动的新事件，落盘会把磁盘历史覆盖掉。
            // dirty 保持 true，finishLoad() 会补一次完整快照。
            if (!ready) return;
            if (!dirty) return;
            dirty = false;
        }
        io.execute(new Runnable() {
            public void run() {
                // 快照在 io 线程里做：2 万条事件在主线程浅拷贝虽然只要 ~1ms，
                // 但 flush 是挂在 800ms 节流上的常驻动作，能挪就挪。
                // Ev 入列后不再被修改，浅拷贝足够；拷贝期间的新 append 会重新置
                // dirty，800ms 后用更新的快照再写一次，最终不丢事件。
                final List<Ev> snapshot;
                synchronized (UsageStats.this) {
                    snapshot = new ArrayList<Ev>(events);
                }
                try {
                    StorageManager sm = StorageManager.getInstance();
                    if (sm == null) { schedule(); return; }
                    // 流式写：不再在内存里建 20k×11 的 JSONArray 树（峰值 8~15MB）
                    // 再 toString 复制一份整串，直接边遍历边写文件。
                    boolean ok = sm.writeStatsStream(new StorageManager.StatsWriter() {
                        public void write(java.io.Writer out) throws Exception {
                            writeEvents(out, snapshot);
                        }
                    });
                    if (!ok) schedule();
                } catch (Exception e) {
                    LogUtil.w(TAG, "flush failed: %s", e.getMessage());
                    // dirty 已在投递前清掉：写盘失败要重新排队，否则这批事件
                    // 会一直等到下一次 append 才顺带补写，期间退出就永久丢。
                    schedule();
                }
            }
        });
    }

    /**
     * 事件用紧凑数组存：一条 11 个字段，写成对象会多出 11 个键名，
     * 20000 条差出好几 MB，而这份文件是每次回复都要重写的。
     * 下标顺序见 {@link #KIND_CODES} 与 {@link #readEvent}。
     */
    private static final String[] KIND_CODES =
            {EV_REQ, EV_USAGE, EV_CONV, EV_ROUND, EV_TOOL, EV_RETRY};

    private void load() {
        try {
            String raw = StorageManager.getInstance().loadStats();
            if (raw == null || raw.length() == 0) return;
            JSONObject root = new JSONObject(raw);
            if (root.optInt("v", 0) != 2) {
                // v1 是按天聚合的旧结构，且按模型/按服务商的数字本身就是错的
                LogUtil.i(TAG, "load: drop v%d stats", root.optInt("v", 0));
                return;
            }
            JSONArray arr = root.optJSONArray("evs");
            if (arr == null) return;
            synchronized (this) {
                List<Ev> disk = new ArrayList<Ev>();
                for (int i = 0; i < arr.length(); i++) {
                    Ev e = readEvent(arr.optJSONArray(i));
                    if (e != null) disk.add(e);
                }
                // 加载可能晚于本次启动的新事件：磁盘数据 ts 更早，必须插头部，
                // 否则 earliestTs() 会算错、统计区间起点偏晚。
                if (!clearedWhileLoading) {
                    events.addAll(0, disk);
                    int over = events.size() - MAX_EVENTS;
                    if (over > 0) events.subList(0, over).clear();
                }
            }
            LogUtil.i(TAG, "load: %d event(s)", events.size());
        } catch (Exception e) {
            // 统计文件损坏不是致命问题：丢弃磁盘内容、保留本次启动的新事件，
            // 由 finishLoad() 的补写用干净数据覆盖 stats.json。
            LogUtil.w(TAG, "load failed, reset: %s", e.getMessage());
        }
    }

    private static Ev readEvent(JSONArray a) {
        if (a == null || a.length() < 10) return null;
        Ev e = new Ev();
        e.ts = a.optLong(0);
        int code = a.optInt(1, -1);
        if (code < 0 || code >= KIND_CODES.length) return null;
        e.kind = KIND_CODES[code];
        e.model = a.optString(2, "");
        e.provider = a.optString(3, "");
        e.name = a.optString(4, "");
        e.pt = a.optInt(5, 0);
        e.ct = a.optInt(6, 0);
        e.cached = a.optInt(7, 0);
        e.genMs = a.optLong(8, 0);
        e.est = a.optInt(9, 0) == 1;
        e.ok = a.optInt(10, 1) == 1;
        return e;
    }

    /**
     * 流式序列化：与旧 {@code toJson()} 输出格式完全一致（v2 + evs 紧凑数组），
     * 只是不再经过 JSONArray 树，堆上只占一个 8KB 输出缓冲。
     */
    private static void writeEvents(java.io.Writer out, List<Ev> list) throws Exception {
        android.util.JsonWriter w = new android.util.JsonWriter(out);
        w.beginObject();
        w.name("v").value(2);
        w.name("evs").beginArray();
        for (int i = 0; i < list.size(); i++) {
            Ev e = list.get(i);
            w.beginArray();
            w.value(e.ts);
            w.value(kindCode(e.kind));
            w.value(e.model);
            w.value(e.provider);
            w.value(e.name);
            w.value(e.pt);
            w.value(e.ct);
            w.value(e.cached);
            w.value(e.genMs);
            w.value(e.est ? 1 : 0);
            w.value(e.ok ? 1 : 0);
            w.endArray();
        }
        w.endArray();
        w.endObject();
        w.flush();
    }

    private static int kindCode(String kind) {
        for (int i = 0; i < KIND_CODES.length; i++) {
            if (KIND_CODES[i].equals(kind)) return i;
        }
        return 0;
    }

    // ---------------------------------------------------------------- 日期键

    /** 本地时区的 yyyy-MM-dd。不能用 UTC：东八区凌晨 0-8 点会被算到前一天。 */
    static String dayKey(long ts) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(new java.util.Date(ts));
    }

    /** 某天 00:00:00.000 的时间戳，自定义区间取边界用。 */
    static long dayStart(long ts) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(ts);
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** 某天 23:59:59.999 的时间戳。 */
    static long dayEnd(long ts) {
        return dayStart(ts) + 24L * 60 * 60 * 1000 - 1;
    }
}
