package xyz.zip8919.app.aichat;

import android.graphics.Typeface;
import android.util.LruCache;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import org.json.JSONObject;

/**
 * 字体仓库：fonts/ 目录扫描、元数据（显示名 / 启用态）、Typeface LRU、
 * 添加校验、删除时改引用。
 *
 * <p>元数据单独存 fonts.json，不塞进 appearance.json（外观配置与资源索引分离）；
 * Typeface 用「按条数计费」的 LruCache（上限 3 个：UI 字体 + 等宽字体 + 切换中的旧字体）。
 * 不能按文件字节计费——导入上限本身就是 8MB，sizeOf 取文件长度会让大字体一进来就被
 * 逐出（命中率恒为 0），两个中文字体也会互相把对方挤掉，详见 {@code cache()} 注释。
 */
final class FontRepository {

    private static final String TAG = "FontRepository";
    private static final String META_FILE = "fonts.json";
    private static final int MAX_FONT_BYTES = 8 * 1024 * 1024;
    private static FontRepository instance;

    static final int ADD_OK = 0;
    static final int ADD_BAD_EXT = 1;
    static final int ADD_TOO_LARGE = 2;
    static final int ADD_BAD_FILE = 3;
    static final int ADD_WRITE_FAIL = 4;

    static final class FontItem {
        public String id;
        public String name;
        public String fileName;
        public String path;
        public long sizeBytes;
        public boolean enabled;
        public boolean loadFailed;
    }

    private final StorageManager storage;
    private final Map<String, Meta> metas = new LinkedHashMap<String, Meta>();
    /** id → 文件绝对路径。getTypeface 每个 TextView 调一次，绝不能每次都 listFiles。 */
    private final Map<String, String> pathById = new LinkedHashMap<String, String>();
    private LruCache<String, Typeface> typefaceCache;

    private static final class Meta {
        String name = "";
        boolean enabled = false;
        boolean loadFailed = false;
    }

    private FontRepository() {
        this.storage = StorageManager.getInstance();
        loadMetas();
    }

    static synchronized FontRepository getInstance() {
        if (instance == null) instance = new FontRepository();
        return instance;
    }

    /** key 用字体文件绝对路径：id 不带 .ttf/.otf 后缀，直接 new File(dir,id) 会找不到文件。 */
    private LruCache<String, Typeface> cache() {
        if (typefaceCache == null) {
            // 按「条数」计费，不按文件字节计费。
            //
            // 原来 maxSize = 8MB 且 sizeOf 返回文件长度，而导入上限也是 8MB：
            //   · 单个 8MB 以上的字体（CJK 常见 4~16MB）永远超限，put 完立刻被
            //     trimToSize 逐出，等于没有缓存；
            //   · UI 字体 + 等宽字体两个各 5MB，加起来超限，互相把对方挤出去，
            //     每次切换都重新 createFromFile。
            // 而 getTypeface 是 ThemeApplier.applyTextStyle 给**每个 TextView** 调的，
            // 一个设置页 200 个控件 × 30~100ms 的 createFromFile = 6~20 秒，直接 ANR。
            //
            // 条数上限 3：主题最多同时用 UI + 等宽两个字号，留一个给「切换中」的旧字体。
            // Typeface 的字体数据是 mmap 的 native 内存（文件页可回收），
            // 不占 Dalvik 堆，3 个实例是安全的；换成按条计费后 sizeOf 也不用再 stat。
            typefaceCache = new LruCache<String, Typeface>(3) {
                @Override
                protected int sizeOf(String key, Typeface value) {
                    return 1;
                }
            };
        }
        return typefaceCache;
    }

    private String fontsDir() {
        return storage.getFontsDir();
    }

    // ---- 元数据 ----

    private String metaPath() {
        return storage.getBasePath() + File.separator + META_FILE;
    }

    private void loadMetas() {
        metas.clear();
        String text = storage.readFile(metaPath());
        if (text == null || text.length() == 0) return;
        try {
            JSONObject j = new JSONObject(text);
            JSONObject items = j.optJSONObject("fonts");
            if (items == null) return;
            Iterator<String> it = items.keys();
            while (it.hasNext()) {
                String id = it.next();
                JSONObject o = items.optJSONObject(id);
                if (o == null) continue;
                Meta m = new Meta();
                m.name = o.optString("name", id);
                m.enabled = o.optBoolean("enabled", false);
                m.loadFailed = o.optBoolean("load_failed", false);
                metas.put(id, m);
            }
        } catch (Exception e) {
            // 元数据损坏不影响字体文件本身，用空元数据继续
            LogUtil.e(TAG, "loadMetas parse failed: " + e.getMessage(), e);
        }
    }

    private synchronized void saveMetas() {
        try {
            JSONObject j = new JSONObject();
            j.put("schema", 1);
            JSONObject items = new JSONObject();
            for (Map.Entry<String, Meta> e : metas.entrySet()) {
                JSONObject o = new JSONObject();
                o.put("name", e.getValue().name);
                o.put("enabled", e.getValue().enabled);
                o.put("load_failed", e.getValue().loadFailed);
                items.put(e.getKey(), o);
            }
            j.put("fonts", items);
            storage.writeTextAtomic(metaPath(), j.toString());
        } catch (Exception e) {
            LogUtil.e(TAG, "saveMetas failed: " + e.getMessage(), e);
        }
    }

    // ---- 列表 ----

    synchronized List<FontItem> list() {
        List<FontItem> out = new ArrayList<FontItem>();
        pathById.clear();
        File dir = new File(fontsDir());
        File[] files = dir.listFiles();
        if (files != null) {
            for (int i = 0; i < files.length; i++) {
                File f = files[i];
                if (!f.isFile()) continue;
                String lower = f.getName().toLowerCase();
                if (!lower.endsWith(".ttf") && !lower.endsWith(".otf")) continue;
                String id = stripExt(f.getName());
                Meta m = metas.get(id);
                FontItem it = new FontItem();
                it.id = id;
                it.fileName = f.getName();
                it.path = f.getAbsolutePath();
                pathById.put(id, it.path);
                it.sizeBytes = f.length();
                it.name = (m != null && m.name.length() > 0) ? m.name : id;
                it.enabled = m != null && m.enabled;
                it.loadFailed = m != null && m.loadFailed;
                out.add(it);
            }
        }
        return out;
    }

    /** 已启用的字体 id 集合（外观页 Spinner 选项、主题字体引用校验用）。 */
    synchronized Set<String> enabledIds() {
        Set<String> out = new HashSet<String>();
        for (FontItem f : list()) {
            if (f.enabled && !f.loadFailed) out.add(f.id);
        }
        return out;
    }

    synchronized Set<String> installedIds() {
        Set<String> out = new HashSet<String>();
        for (FontItem f : list()) out.add(f.id);
        return out;
    }

    // ---- Typeface ----

    /** 加载失败回退系统默认：返回 null 表示调用方应使用默认字体。 */
    Typeface getTypeface(String id) {
        if (id == null || "system".equals(id) || id.length() == 0) return null;
        // 已判定为坏字体：直接回退，不要再走一遍 createFromFile。
        //
        // 原来每次都重试，失败后又 markLoadFailed() → saveMetas()，即「整份 fonts.json
        // 序列化 + 原子写盘」。而 getTypeface 是每个 TextView 调一次的：
        // 一个字体文件损坏，设置页 100 个控件就是 100 次全量写盘 ≈ 0.5~2 秒，
        // 而且每次 onResume 都重来。坏字体是确定状态，没有理由每个控件重新判定一次。
        synchronized (this) {
            Meta m = metas.get(id);
            if (m != null && m.loadFailed) return null;
        }
        String path = pathOf(id);
        if (path == null) return null;
        Typeface cached = cache().get(path);
        if (cached != null) return cached;
        Typeface tf;
        try {
            tf = Typeface.createFromFile(path);
        } catch (Throwable t) {
            tf = null;
        }
        if (tf == null) {
            // 文件被外部删掉时不能误标 loadFailed：清掉索引，下次按目录重建
            if (!new File(path).isFile()) {
                pathById.remove(id);
                return null;
            }
            markLoadFailed(id);
            return null;
        }
        cache().put(path, tf);
        return tf;
    }

    /** 字体 id → 实际文件绝对路径；找不到返回 null。@font-face 必须用绝对路径。 */
    synchronized String fontFilePath(String id) {
        if (id == null || "system".equals(id)) return null;
        return pathOf(id);
    }

    /** 查 id 对应路径：命中时零磁盘 IO；未命中才重建一次索引。 */
    private synchronized String pathOf(String id) {
        String p = pathById.get(id);
        if (p != null) return p;
        rebuildPathIndex();
        return pathById.get(id);
    }

    private void rebuildPathIndex() {
        pathById.clear();
        File dir = new File(fontsDir());
        File[] files = dir.listFiles();
        if (files == null) return;
        for (int i = 0; i < files.length; i++) {
            File f = files[i];
            if (f.isFile()) pathById.put(stripExt(f.getName()), f.getAbsolutePath());
        }
    }

    private synchronized void markLoadFailed(String id) {
        Meta m = metas.get(id);
        if (m == null) { m = new Meta(); m.name = id; metas.put(id, m); }
        // 状态没变就不要写盘：markLoadFailed 可能在「标记之前就已经被并发调用」后
        // 再次进来（getTypeface 不是 synchronized，两个线程可能同时判定失败）。
        // saveMetas 是整份序列化 + 原子写，一次 5~20ms，能省就省。
        if (m.loadFailed && !m.enabled) return;
        m.loadFailed = true;
        m.enabled = false;
        saveMetas();
        LogUtil.w(TAG, "font load failed: %s", id);
    }

    // ---- 增删改 ----

    /**
     * 添加字体：校验扩展名 → 大小 → 试加载 → 原子写入。默认停用。
     * @param bytes 文件字节（UI 层从 ACTION_GET_CONTENT 读出，URI 权限活不过重启）
     */
    synchronized int add(byte[] bytes, String fileName) {
        if (fileName == null) return ADD_BAD_EXT;
        String lower = fileName.toLowerCase();
        if (!lower.endsWith(".ttf") && !lower.endsWith(".otf")) return ADD_BAD_EXT;
        if (bytes == null || bytes.length > MAX_FONT_BYTES) return ADD_TOO_LARGE;
        if (bytes.length == 0) return ADD_BAD_FILE;

        String safe = ThemeRepository.safeFileName(stripExt(fileName));
        if (safe.length() == 0) safe = "font";
        String ext = lower.endsWith(".otf") ? ".otf" : ".ttf";
        String finalName = safe + ext;
        String path = fontsDir() + File.separator + finalName;

        // 试加载：先写临时文件再 createFromFile，失败不落正式文件
        File tmp = new File(path + ".tmp");
        if (!storage.writeBytesAtomic(tmp.getAbsolutePath(), bytes)) return ADD_WRITE_FAIL;
        Typeface tf = null;
        try {
            tf = Typeface.createFromFile(tmp);
        } catch (Throwable t) {
            tf = null;
        }
        if (tf == null) {
            tmp.delete();
            return ADD_BAD_FILE;
        }
        if (!tmp.renameTo(new File(path))) {
            if (!storage.writeBytesAtomic(path, bytes)) {
                tmp.delete();
                return ADD_WRITE_FAIL;
            }
            tmp.delete();
        }

        String id = stripExt(finalName);
        Meta m = new Meta();
        m.name = stripExt(fileName);
        m.enabled = false;
        metas.put(id, m);
        pathById.put(id, path);
        saveMetas();
        cache().evictAll();
        LogUtil.i(TAG, "add: %s (%d bytes) -> %s", id, bytes.length, path);
        return ADD_OK;
    }

    synchronized boolean setEnabled(String id, boolean enabled) {
        Meta m = metas.get(id);
        if (m == null) return false;
        m.enabled = enabled;
        saveMetas();
        return true;
    }

    /**
     * 批量启用/停用，整批只写一次盘。
     *
     * <p>管理页多选后逐项调 {@link #setEnabled}，每项一次「整份 fonts.json 序列化 +
     * 原子写」：勾 10 个字体点一次「启用」= 10 次写盘，主线程冻结 100~300ms。
     */
    synchronized void setEnabledAll(java.util.Collection<String> ids, boolean enabled) {
        if (ids == null || ids.isEmpty()) return;
        boolean changed = false;
        for (String id : ids) {
            Meta m = metas.get(id);
            if (m != null && m.enabled != enabled) {
                m.enabled = enabled;
                changed = true;
            }
        }
        if (changed) saveMetas();
    }

    synchronized boolean rename(String id, String newName) {
        Meta m = metas.get(id);
        if (m == null) return false;
        m.name = newName;
        saveMetas();
        return true;
    }

    /**
     * 批量删除：目录只列一次，元数据只写一次。
     *
     * <p>逐项调 {@link #delete} 时，每一项内部都走一次 {@code list()}（listFiles +
     * 逐文件 stat）和一次 {@code saveMetas()}。删 10 个字体 = 10 次全目录扫描
     * + 10 次整份 JSON 原子写，主线程几百毫秒到秒级。
     *
     * @return 真正删掉的文件数
     */
    synchronized int deleteAll(java.util.Collection<String> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        int n = 0;
        boolean touched = false;
        List<FontItem> all = list();
        for (FontItem f : all) {
            if (!ids.contains(f.id)) continue;
            if (new File(f.path).delete()) n++;
            metas.remove(f.id);
            pathById.remove(f.id);
            touched = true;
        }
        if (touched) {
            cache().evictAll();   // key 是绝对路径，无法按 id 精确移除，整体清空最稳
            saveMetas();
        }
        return n;
    }

    synchronized boolean delete(String id) {
        List<FontItem> all = list();
        boolean ok = false;
        for (FontItem f : all) {
            if (f.id.equals(id)) {
                ok = new File(f.path).delete();
                break;
            }
        }
        metas.remove(id);
        pathById.remove(id);
        cache().evictAll();   // key 是绝对路径，无法按 id 精确移除，整体清空最稳
        saveMetas();
        return ok;
    }

    /**
     * 删除使用中字体前的引用改写：把当前外观设置的显式字体覆盖、以及所有用户主题
     * 里的字体引用，从 oldId 改指 replaceId（"system" 表示系统默认）。
     * 由管理页在二次确认的「替换为」选择器里调用，确保不会删到「无可用字体」。
     */
    void replaceReferences(AppearanceManager m, String oldId, String replaceId) {
        String to = replaceId == null ? "system" : replaceId;
        String[] keys = {ThemeTokens.FONT_UI, ThemeTokens.FONT_MONO};
        for (int k = 0; k < keys.length; k++) {
            for (int d = 0; d < 2; d++) {
                boolean dark = d == 1;
                if (oldId.equals(m.getExplicit(keys[k], dark))) {
                    if ("system".equals(to)) m.clearExplicit(keys[k], dark);
                    else m.setExplicit(keys[k], to, dark);
                }
            }
        }
        ThemeRepository repo = ThemeRepository.getInstance();
        List<Theme> themes = repo.list();
        for (int i = 0; i < themes.size(); i++) {
            Theme t = themes.get(i);
            if (t.builtin) continue;
            boolean changed = false;
            if (oldId.equals(t.fontUi)) { t.fontUi = to; changed = true; }
            if (oldId.equals(t.fontMono)) { t.fontMono = to; changed = true; }
            if (changed) repo.save(t);
        }
    }

    static String addErrorText(int code) {
        switch (code) {
            case ADD_BAD_EXT: return "格式不支持，仅支持 .ttf / .otf";
            case ADD_TOO_LARGE: return "文件超过 8MB";
            case ADD_BAD_FILE: return "字体文件损坏，无法加载";
            case ADD_WRITE_FAIL: return "存储不可写或空间不足";
            default: return "未知错误";
        }
    }

    private static String stripExt(String name) {
        int p = name.lastIndexOf('.');
        return p > 0 ? name.substring(0, p) : name;
    }
}
