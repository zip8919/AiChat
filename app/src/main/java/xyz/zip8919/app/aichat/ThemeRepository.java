package xyz.zip8919.app.aichat;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 主题仓库：内置 4 套 + 用户主题目录扫描、增删复制导出、文件缺失自愈。
 *
 * <p>不建单独索引文件（工程裁决 E5）：数量级小，按需扫 themes/ 目录 +
 * 内存缓存即可；索引文件会引入与 conversations.idx 同类的校准与损坏问题。
 * 每次落盘都走 StorageManager 的原子写，不产生半文件。
 */
final class ThemeRepository {

    private static final String TAG = "ThemeRepository";
    private static final String SUFFIX = ".actheme";
    private static ThemeRepository instance;

    private final StorageManager storage;
    private List<Theme> cache;
    /** 目录签名：文件名 + lastModified + length；写路径置 forceCheck 强制重算。 */
    private String dirSig;
    private boolean forceCheck;
    /** 上次 directorySignature() 的计算时刻，用于热路径节流。 */
    private long dirSigAt;
    /** 目录签名复用时间窗：colorSignature/整树重刷会连续调十几次 list()。 */
    private static final long DIR_SIG_TTL_MS = 500;

    private ThemeRepository() {
        this.storage = StorageManager.getInstance();
    }

    static synchronized ThemeRepository getInstance() {
        if (instance == null) instance = new ThemeRepository();
        return instance;
    }

    synchronized void invalidate() {
        // 应用内写路径必须立刻看到新内容，直接清 cache 最稳妥；
        // 目录签名缓存只用于「子页返回但磁盘未变」的读路径。
        cache = null;
        forceCheck = true;
    }

    /** 内置在前、用户主题在后；文件损坏的条目仍出现在列表里，带标记置灰。 */
    synchronized List<Theme> list() {
        // 热路径节流：colorSignature()/整树重刷会在几十 ms 内连续调十几次 list()，
        // 每次 listFiles + 逐文件 lastModified()/length() 在单核 A7 上都是 syscall。
        // 窗口内直接复用缓存；应用内写路径走 invalidate()（置 forceCheck）不受影响，
        // 外部 App 改文件最多延迟 DIR_SIG_TTL_MS 才可见。
        long now = System.currentTimeMillis();
        if (cache != null && !forceCheck && now - dirSigAt < DIR_SIG_TTL_MS) return cache;

        File dir = new File(storage.getThemesDir());
        File[] files = dir.listFiles();
        // listFiles 返回 null 是「读取失败」而非「目录为空」：保留旧缓存，
        // 否则用户主题会整批消失且长期不恢复。
        if (files == null && cache != null) return cache;
        String sig = directorySignature(files);
        dirSigAt = now;
        if (cache != null && !forceCheck && sig.equals(dirSig)) return cache;
        forceCheck = false;
        List<Theme> out = new ArrayList<Theme>();
        out.addAll(BuiltinThemes.list());

        if (files != null) {
            for (int i = 0; i < files.length; i++) {
                File f = files[i];
                if (!f.isFile() || !f.getName().toLowerCase().endsWith(SUFFIX)) continue;
                Theme t = loadOne(f);
                if (t != null) out.add(t);
            }
        }
        cache = out;
        dirSig = sig;
        return out;
    }

    /** listFiles 顺序不保证，必须按 key 排序后再拼签名。 */
    private static String directorySignature(File[] files) {
        if (files == null) return "";
        String[] keys = new String[files.length];
        for (int i = 0; i < files.length; i++) {
            File f = files[i];
            keys[i] = f.getName() + ":" + f.lastModified() + ":" + f.length();
        }
        java.util.Arrays.sort(keys);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keys.length; i++) sb.append(keys[i]).append('|');
        return sb.toString();
    }

    private Theme loadOne(File f) {
        String content = storage.readFile(f.getAbsolutePath());
        String id = stripSuffix(f.getName());
        if (content == null || content.length() == 0) {
            Theme t = new Theme();
            t.id = id;
            t.name = id;
            t.filePath = f.getAbsolutePath();
            t.fileMissing = true;
            return t;
        }
        ThemeValidator.Result vr = ThemeValidator.validate(content);
        Theme t;
        if (vr.blocked || vr.theme == null) {
            t = new Theme();
            t.id = id;
            t.name = id;
            t.versionIncompatible = true;
            LogUtil.w(TAG, "theme unusable: %s (%s)", f.getName(), vr.blockReason);
        } else {
            t = vr.theme;
            t.id = id;
        }
        t.filePath = f.getAbsolutePath();
        return t;
    }

    synchronized Theme find(String id) {
        if (id == null || id.length() == 0) return null;
        List<Theme> all = list();
        for (int i = 0; i < all.size(); i++) {
            Theme t = all.get(i);
            if (id.equals(t.id)) return t;
        }
        return null;
    }

    /** 当前选中的主题；未选择或已丢失返回 null，由 ThemeResolver 回退内置。 */
    Theme active(String themeId) {
        Theme t = find(themeId);
        if (t == null) return null;
        if (t.fileMissing || t.versionIncompatible) return null;
        return t;
    }

    /** 该主题在当前明暗下对 key 的定义值；无定义返回 null。 */
    String themeValue(String themeId, String key, boolean dark) {
        Theme t = active(themeId);
        if (t == null || !t.hasMode(dark)) return null;
        return t.valueFor(key, dark);
    }

    String activeCss(String themeId, boolean dark) {
        Theme t = active(themeId);
        if (t == null || !t.hasMode(dark)) return "";
        return t.cssFor(dark);
    }

    // ---- 写入 ----

    /**
     * 把已通过校验的 Theme 落盘。id 为空时生成新 id；name 会做显示名长度裁剪。
     * @return 落盘后的 id，失败返回 null
     */
    synchronized String save(Theme t) {
        if (t == null) return null;
        if (t.id == null || t.id.length() == 0) {
            t.id = "t" + UUID.randomUUID().toString().substring(0, 8);
        }
        if (t.name == null || t.name.trim().length() == 0) t.name = "未命名主题";
        try {
            String json = t.toJson();
            String path = themePath(t.id);
            boolean ok = storage.writeTextAtomic(path, json);
            if (!ok) {
                LogUtil.e(TAG, "save failed: %s", path);
                return null;
            }
            t.filePath = path;
            t.builtin = false;
            cache = null;
            LogUtil.i(TAG, "save: %s -> %s (%d chars)", t.id, path, json.length());
            return t.id;
        } catch (Exception e) {
            LogUtil.e(TAG, "save serialize failed: " + e.getMessage(), e);
            return null;
        }
    }

    /** 导入：先校验（阻断则拒绝），冲突名由调用方处理后再 save。 */
    Theme fromValidated(String text) {
        ThemeValidator.Result vr = ThemeValidator.validate(text);
        if (vr.blocked || vr.theme == null) return null;
        return vr.theme;
    }

    synchronized boolean delete(String id) {
        Theme t = find(id);
        if (t == null || t.builtin || t.filePath == null) return false;
        boolean ok = new File(t.filePath).delete();
        cache = null;
        LogUtil.i(TAG, "delete: %s ok=%s", id, ok);
        return ok;
    }

    /** 复制：深拷贝 JSON + 新 id + 新名称。 */
    synchronized String copy(String id, String newName) {
        Theme src = find(id);
        if (src == null) return null;
        Theme t = src.copy();
        t.id = "";
        t.builtin = false;
        t.filePath = null;
        t.fileMissing = false;
        t.versionIncompatible = false;
        t.name = newName;
        return save(t);
    }

    /** 重命名只改 meta.name，不改文件名 / id。 */
    synchronized boolean rename(String id, String newName) {
        Theme t = find(id);
        if (t == null || t.builtin) return false;
        String oldName = t.name;
        t.name = newName;
        if (save(t) == null) {
            t.name = oldName;
            cache = null;   // 内存与磁盘可能已不一致，强制下次重读
            return false;
        }
        return true;
    }

    /** 导出到 exports/&lt;安全名&gt;.actheme，返回绝对路径，失败返回 null。 */
    String export(String id) {
        Theme t = find(id);
        if (t == null) return null;
        try {
            String name = safeFileName(t.name);
            if (name.length() == 0) name = t.id;
            String text = t.toJson();
            String path = storage.saveExport(name + SUFFIX, text);
            LogUtil.i(TAG, "export: %s -> %s", id, path);
            return path;
        } catch (Exception e) {
            LogUtil.e(TAG, "export failed: " + e.getMessage(), e);
            return null;
        }
    }

    private String themePath(String id) {
        return storage.getThemesDir() + File.separator + safeFileName(id) + SUFFIX;
    }

    static String safeFileName(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?'
                    || c == '"' || c == '<' || c == '>' || c == '|' || c == '\n' || c == '\r') {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    private static String stripSuffix(String name) {
        int p = name.toLowerCase().lastIndexOf(SUFFIX);
        return p > 0 ? name.substring(0, p) : name;
    }
}
