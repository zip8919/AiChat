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
 * Typeface 用「按文件字节计费」的 LruCache，上限 8MB，思路复用
 * StorageAttachmentsActivity 的图片缓存，避免 66MB 内存的老设备被大字体拖垮。
 */
final class FontRepository {

    private static final String TAG = "FontRepository";
    private static final String META_FILE = "fonts.json";
    private static final int MAX_FONT_BYTES = 8 * 1024 * 1024;
    private static final int CACHE_BYTES = 8 * 1024 * 1024;
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
            typefaceCache = new LruCache<String, Typeface>(CACHE_BYTES) {
                @Override
                protected int sizeOf(String key, Typeface value) {
                    long n = new File(key).length();
                    return n > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) n;
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
        File dir = new File(fontsDir());
        File found = null;
        File[] files = dir.listFiles();
        if (files != null) {
            for (int i = 0; i < files.length; i++) {
                if (stripExt(files[i].getName()).equals(id)) { found = files[i]; break; }
            }
        }
        if (found == null || !found.isFile()) return null;
        String key = found.getAbsolutePath();
        Typeface cached = cache().get(key);
        if (cached != null) return cached;
        Typeface tf;
        try {
            tf = Typeface.createFromFile(found);
        } catch (Throwable t) {
            tf = null;
        }
        if (tf == null) {
            markLoadFailed(id);
            return null;
        }
        cache().put(key, tf);
        return tf;
    }

    /** 字体 id → 实际文件绝对路径；找不到返回 null。@font-face 必须用绝对路径。 */
    synchronized String fontFilePath(String id) {
        if (id == null || "system".equals(id)) return null;
        File dir = new File(fontsDir());
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (int i = 0; i < files.length; i++) {
            if (stripExt(files[i].getName()).equals(id)) return files[i].getAbsolutePath();
        }
        return null;
    }

    private synchronized void markLoadFailed(String id) {
        Meta m = metas.get(id);
        if (m == null) { m = new Meta(); m.name = id; metas.put(id, m); }
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

    synchronized boolean rename(String id, String newName) {
        Meta m = metas.get(id);
        if (m == null) return false;
        m.name = newName;
        saveMetas();
        return true;
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
