package xyz.zip8919.app.aichat;

import android.content.Context;
import android.os.Environment;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;

public class StorageManager {
    private static final String TAG = "StorageManager";
    private static final String CONFIG_FILE = "config.json";
    private static final String CONVERSATIONS_DIR = "conversations";
    private static final String EXPORTS_DIR = "exports";
    // 外观设置：主题文件、字体文件、外观配置快照
    private static final String THEMES_DIR = "themes";
    private static final String FONTS_DIR = "fonts";
    private static final String APPEARANCE_FILE = "appearance.json";
    private static final String DATA_SUBDIR = "Android/data/xyz.zip8919.app.aichat/files";
    private static final String ROOT_PREF_FILE = ".storage_root";

    private static StorageManager instance;

    private static Context sAppContext;

    private Context appContext;
    private String basePath;
    private String configPath;
    private String conversationsPath;
    private boolean useInternalStorage = false;

    private StorageManager(Context context) {
        // context 可能为 null：无参 getInstance() 在子页面被系统重建、MainActivity 尚未执行时的兜底路径
        this.appContext = (context != null) ? context.getApplicationContext() : sAppContext;
        initPaths();
    }

    public static synchronized StorageManager getInstance(Context context) {
        if (context != null) {
            sAppContext = context.getApplicationContext();
        }
        if (instance == null) {
            instance = new StorageManager(context);
        }
        return instance;
    }

    public static synchronized StorageManager getInstance() {
        if (instance == null) {
            // 进程重建后静态字段全部丢失，此处不能再依赖 MainActivity 先初始化；用缓存 Context 或外部存储兜底，避免 IllegalStateException 崩溃
            LogUtil.w(TAG, "getInstance(): lazy init, appContext=%s", sAppContext != null ? "cached" : "null");
            instance = new StorageManager(sAppContext);
        }
        return instance;
    }

    private void initPaths() {
        String[] preferredPaths = {
            "/storage/internalsd/" + DATA_SUBDIR,
            "/storage/emulated/0/" + DATA_SUBDIR
        };

        // 上次因外部存储不可用而回退内部存储的选择要保留，否则下次外部存储可用时根目录漂走、历史页读不到那批会话
        String persistedInternal = readRootPref();
        if (persistedInternal != null && this.appContext != null
                && persistedInternal.equals(this.appContext.getFilesDir().getAbsolutePath())
                && findAvailablePath(new String[] { persistedInternal }) != null) {
            this.basePath = persistedInternal;
            this.useInternalStorage = true;
            LogUtil.i(TAG, "initPaths: keep persisted internal path %s", this.basePath);
        } else {
            this.basePath = findAvailablePath(preferredPaths);
        }

        if (this.basePath == null) {
            if (this.appContext != null) {
                this.basePath = this.appContext.getFilesDir().getAbsolutePath();
                this.useInternalStorage = true;
                writeRootPref(this.basePath);
                LogUtil.i(TAG, "initPaths: no writable external path, fallback to internal %s", this.basePath);
            } else {
                this.basePath = Environment.getExternalStorageDirectory().getAbsolutePath()
                        + "/" + DATA_SUBDIR;
                new File(this.basePath).mkdirs();
                LogUtil.w(TAG, "initPaths: no context, fallback to external root %s", this.basePath);
            }
        } else if (!this.useInternalStorage) {
            LogUtil.i(TAG, "initPaths: using external path %s", this.basePath);
        }

        this.configPath = this.basePath + File.separator + CONFIG_FILE;
        this.conversationsPath = this.basePath + File.separator + CONVERSATIONS_DIR;
        LogUtil.d(TAG, "initPaths: config=%s conversations=%s", this.configPath, this.conversationsPath);
    }

    private String readRootPref() {
        if (this.appContext == null) {
            return null;
        }
        File pref = new File(this.appContext.getFilesDir(), ROOT_PREF_FILE);
        if (!pref.exists()) {
            return null;
        }
        String content = readFile(pref.getAbsolutePath());
        if (content == null) {
            return null;
        }
        content = content.trim();
        return content.length() == 0 ? null : content;
    }

    private void writeRootPref(String path) {
        if (this.appContext == null || path == null) {
            return;
        }
        File pref = new File(this.appContext.getFilesDir(), ROOT_PREF_FILE);
        writeFile(pref.getAbsolutePath(), path);
    }

    private String findAvailablePath(String[] paths) {
        for (String path : paths) {
            File dir = new File(path);
            if (dir.exists() || dir.mkdirs()) {
                File testFile = new File(dir, ".write_test");
                try {
                    // 残留的 .write_test 会让 createNewFile() 返回 false；只要目录可建/文件可删即视为可写
                    boolean writable = testFile.exists() ? testFile.delete() : testFile.createNewFile();
                    if (writable) {
                        testFile.delete();
                        LogUtil.d(TAG, "findAvailablePath: '%s' is writable", path);
                        return path;
                    }
                    LogUtil.w(TAG, "findAvailablePath: '%s' exists but not writable", path);
                } catch (IOException e) {
                    LogUtil.w(TAG, "findAvailablePath: '%s' write test failed: %s", path, e.getMessage());
                }
            } else {
                LogUtil.d(TAG, "findAvailablePath: '%s' not available", path);
            }
        }
        return null;
    }

    public boolean createDirectories() {
        if (!isExternalStorageAvailable() && !this.useInternalStorage) {
            LogUtil.w(TAG, "createDirectories failed: external storage not mounted");
            return false;
        }
        File baseDir = new File(this.basePath);
        if (!baseDir.exists() && !baseDir.mkdirs()) {
            LogUtil.e(TAG, "createDirectories failed: cannot mkdir base %s", this.basePath);
            return false;
        }
        File convDir = new File(this.conversationsPath);
        boolean ok = convDir.exists() || convDir.mkdirs();
        if (!ok) LogUtil.e(TAG, "createDirectories failed: cannot mkdir conversations %s", this.conversationsPath);
        // 附件目录提前建好：导入图片时再建，失败原因会被当成「图片保存失败」，
        // 这里建失败至少能在启动日志里留下真实原因
        File attDir = new File(this.basePath, "attachments");
        if (!attDir.exists() && !attDir.mkdirs()) {
            LogUtil.w(TAG, "createDirectories: cannot mkdir attachments %s", attDir.getAbsolutePath());
        }
        // 外观设置的资源目录，同样提前建好，失败只记日志不阻断启动
        File themesDir = new File(this.basePath, THEMES_DIR);
        if (!themesDir.exists() && !themesDir.mkdirs()) {
            LogUtil.w(TAG, "createDirectories: cannot mkdir themes %s", themesDir.getAbsolutePath());
        }
        File fontsDir = new File(this.basePath, FONTS_DIR);
        if (!fontsDir.exists() && !fontsDir.mkdirs()) {
            LogUtil.w(TAG, "createDirectories: cannot mkdir fonts %s", fontsDir.getAbsolutePath());
        }
        return ok;
    }

    public boolean isStorageReady() {
        if (this.basePath == null) {
            return false;
        }
        File dir = new File(this.basePath);
        return dir.isDirectory() && dir.canWrite();
    }

    public boolean isExternalStorageAvailable() {
        boolean mounted = "mounted".equals(Environment.getExternalStorageState());
        LogUtil.v(TAG, "isExternalStorageAvailable: state=%s mounted=%s",
                Environment.getExternalStorageState(), mounted);
        return mounted;
    }

    // ---- config ----

    public boolean saveConfig(String content) {
        boolean ok = writeFile(this.configPath, content);
        LogUtil.d(TAG, "saveConfig: %d chars -> %s ok=%s", content == null ? 0 : content.length(), this.configPath, ok);
        return ok;
    }

    public String loadConfig() {
        String content = readFile(this.configPath);
        LogUtil.d(TAG, "loadConfig: %s, len=%d", this.configPath, content == null ? 0 : content.length());
        return content;
    }

    // ---- usage stats ----

    /**
     * 使用统计文件。与 config、conversations 同目录，方便用户整目录备份/迁移。
     *
     * <p>单独一个文件而不是塞进 config：统计是高频写入的可再生数据，
     * 混进 config 会让一次写统计失败连带用户 API Key 一起丢。
     */
    public String getStatsPath() {
        return this.basePath + File.separator + "stats.json";
    }

    public String loadStats() {
        String content = readFile(getStatsPath());
        LogUtil.d(TAG, "loadStats: len=%d", content == null ? 0 : content.length());
        return content;
    }

    public boolean saveStats(String content) {
        boolean ok = writeFile(getStatsPath(), content);
        LogUtil.d(TAG, "saveStats: %d chars ok=%s",
                content == null ? 0 : content.length(), ok);
        return ok;
    }

    /** 流式写 stats 的回调：实现方直接往 out 写 JSON，不要自己再攒一个大字符串。 */
    public interface StatsWriter {
        void write(java.io.Writer out) throws Exception;
    }

    /**
     * 流式写 stats。
     *
     * <p>原来是把 2 万条事件建成一棵 JSONArray 树（约 20 万个小对象、峰值堆 8~15MB）
     * 再 toString 复制一份整串。改成调用方直接写流，内存里只有 8KB 缓冲。
     * 原子性仍走 {@link #renameAtomic}，与其它写路径一致。
     */
    public boolean writeStatsStream(StatsWriter w) {
        File file = new File(getStatsPath());
        File dir = file.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            LogUtil.e(TAG, "writeStatsStream: cannot mkdir %s", dir.getAbsolutePath());
            return false;
        }
        File tmp = new File(file.getAbsolutePath() + ".tmp");
        java.io.Writer out = null;
        boolean written = false;
        try {
            out = new java.io.BufferedWriter(new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(tmp), "UTF-8"), 8192);
            w.write(out);
            out.flush();
            out.close();
            out = null;
            written = true;
            boolean ok = renameAtomic(tmp, file);
            LogUtil.d(TAG, "writeStatsStream: %d bytes ok=%s", tmp.length(), ok);
            return ok;
        } catch (Throwable t) {
            LogUtil.w(TAG, "writeStatsStream failed: %s", t.getMessage());
            return false;
        } finally {
            if (out != null) {
                try { out.close(); } catch (Throwable ignored) {}
            }
            // 写成功时 tmp 已被 rename 走；失败或中断则清掉，避免留垃圾
            if (!written && tmp.exists()) tmp.delete();
        }
    }

    // ---- exports ----

    /**
     * Saves a code block export under basePath/exports.
     *
     * @return the absolute path written, or null on failure.
     */
    public String saveExport(String fileName, String content) {
        File dir = new File(this.basePath + File.separator + EXPORTS_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            LogUtil.e(TAG, "saveExport failed: cannot mkdir %s", dir.getAbsolutePath());
            return null;
        }
        String filePath = dir.getAbsolutePath() + File.separator + fileName;
        boolean ok = writeFile(filePath, content);
        LogUtil.i(TAG, "saveExport: %d chars -> %s ok=%s",
                content == null ? 0 : content.length(), filePath, ok);
        return ok ? filePath : null;
    }

    /**
     * True when basePath/exports already holds a file with this name.
     */
    public boolean exportExists(String fileName) {
        File dir = new File(this.basePath + File.separator + EXPORTS_DIR);
        return new File(dir, fileName).exists();
    }

    /**
     * 二进制导出（查看器截图的 PNG / JPEG）。与 {@link #saveExport} 共用 exports 目录，
     * 但走字节流而不是 FileWriter：Writer 会把图片字节按 UTF-8 编码写坏。
     *
     * <p>同样先写 {@code .tmp} 再改名，避免小工具在写到一半时把半截图片列出来。
     *
     * @return 绝对路径，失败返回 null。
     */
    public String saveExportBytes(String fileName, byte[] bytes) {
        if (fileName == null || bytes == null) return null;
        File dir = new File(this.basePath + File.separator + EXPORTS_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            LogUtil.e(TAG, "saveExportBytes failed: cannot mkdir %s", dir.getAbsolutePath());
            return null;
        }
        File file = new File(dir, fileName);
        File tmp = new File(dir, fileName + ".tmp");
        FileOutputStream out = null;
        boolean ok = false;
        try {
            out = new FileOutputStream(tmp);
            out.write(bytes);
            out.flush();
            out.close();
            out = null;
            if (renameAtomic(tmp, file)) {
                ok = true;
            } else {
                LogUtil.e(TAG, "saveExportBytes rename failed: %s", file.getAbsolutePath());
            }
        } catch (IOException e) {
            LogUtil.e(TAG, "saveExportBytes failed: %s", e.getMessage());
        } finally {
            if (out != null) {
                try { out.close(); } catch (IOException ignored) {}
            }
            if (!ok && tmp.exists()) tmp.delete();
        }
        LogUtil.i(TAG, "saveExportBytes: %d bytes -> %s ok=%s", bytes.length, file.getAbsolutePath(), ok);
        return ok ? file.getAbsolutePath() : null;
    }

    /** exports 目录的绝对路径；目录不存在时也返回路径本身，由调用方判断可写性。 */
    public String getExportsDir() {
        return this.basePath + File.separator + EXPORTS_DIR;
    }

    /**
     * 把 exports 目录下的一个文件改名（同目录 {@code renameTo}，原子操作，不复制字节）。
     *
     * <p>不静默覆盖：目标已存在且不是同一个文件时直接失败，交由调用方提示用户。
     * 仅大小写不同的改名（{@code a.png → A.png}）在大小写不敏感的文件系统上
     * 会让 {@code exists()} 命中自己，这里用 canonical path 区分，允许这类改名。
     *
     * @return 改名后的绝对路径；文件不存在、目标重名、rename 失败都返回 null。
     */
    public String renameExport(String oldName, String newName) {
        if (oldName == null || newName == null) return null;
        String from = oldName.trim(), to = newName.trim();
        if (from.length() == 0 || to.length() == 0) return null;
        // 只允许在 exports 目录内改名，挡住 ../ 之类的路径穿越
        if (to.contains("/") || to.contains("\\") || from.contains("/") || from.contains("\\")) {
            LogUtil.w(TAG, "renameExport rejected path: %s -> %s", from, to);
            return null;
        }
        if (from.equals(to)) return null;

        File dir = new File(getExportsDir());
        File src = new File(dir, from);
        File dst = new File(dir, to);
        if (!src.isFile()) {
            LogUtil.w(TAG, "renameExport: source missing %s", src.getAbsolutePath());
            return null;
        }
        try {
            if (dst.exists() && !dst.getCanonicalPath().equals(src.getCanonicalPath())) {
                LogUtil.w(TAG, "renameExport: target exists %s", dst.getAbsolutePath());
                return null;
            }
        } catch (IOException e) {
            LogUtil.w(TAG, "renameExport: canonical failed %s", e.getMessage());
            return null;
        }
        boolean ok;
        try {
            ok = src.renameTo(dst);
        } catch (Throwable e) {
            LogUtil.w(TAG, "renameExport threw: %s", e.getMessage());
            ok = false;
        }
        LogUtil.i(TAG, "renameExport: %s -> %s ok=%s", from, to, ok);
        return ok ? dst.getAbsolutePath() : null;
    }

    // ---- conversations ----

    /**
     * 会话 id 是否合法。
     *
     * <p>id 会被直接拼进文件路径（{@code conversations/<id>.json}），而它的来源
     * 并不总是我们自己生成的：MainActivity 是 exported 的，{@code onNewIntent}
     * 会把外部 Intent 里的 {@code conversation_id} 原样传给 switchConversation；
     * 会话 JSON 里的 {@code id} 字段也可能被手工改过。含 {@code ../} 的 id
     * 可以让保存写到 conversations 目录之外（覆盖 config.json），也能让删除
     * 删掉别人的文件。存量 id 都是纯数字时间戳，收紧规则不会拒绝老数据。
     */
    static boolean isValidConversationId(String id) {
        if (id == null) return false;
        int n = id.length();
        if (n == 0 || n > 64) return false;
        for (int i = 0; i < n; i++) {
            char c = id.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z') || c == '_' || c == '-';
            if (!ok) return false;
        }
        return true;
    }

    public boolean saveConversation(String conversationId, String content) {
        if (!isValidConversationId(conversationId)) {
            LogUtil.w(TAG, "saveConversation rejected: illegal id '%s'", conversationId);
            return false;
        }
        String filePath = this.conversationsPath + File.separator + conversationId + ".json";
        boolean ok = writeFile(filePath, content);
        LogUtil.v(TAG, "saveConversation: id=%s %d chars ok=%s", conversationId,
                content == null ? 0 : content.length(), ok);
        return ok;
    }

    public String loadConversation(String conversationId) {
        if (!isValidConversationId(conversationId)) return null;
        String filePath = this.conversationsPath + File.separator + conversationId + ".json";
        String content = readFile(filePath);
        // 0 字节会话文件维持「视为不存在」语义：switchConversation 按请求 id 建空会话，而不是随机 id
        if (content != null && content.length() == 0) {
            content = null;
        }
        LogUtil.v(TAG, "loadConversation: id=%s len=%d", conversationId, content == null ? 0 : content.length());
        return content;
    }

    public boolean deleteConversation(String conversationId) {
        if (!isValidConversationId(conversationId)) {
            LogUtil.w(TAG, "deleteConversation rejected: illegal id '%s'", conversationId);
            return false;
        }
        String filePath = this.conversationsPath + File.separator + conversationId + ".json";
        File file = new File(filePath);
        boolean ok = file.exists() && file.delete();
        // 同时清掉可能残留的备份：只删主文件的话，「清空历史」之后
        // 每段对话的正文仍以 .bak 形式留在磁盘上（隐私 + 占用）。
        File bak = new File(filePath + ".bak");
        if (bak.exists()) bak.delete();
        LogUtil.d(TAG, "deleteConversation: id=%s exists=%s deleted=%s", conversationId, file.exists(), ok);
        return ok;
    }

    public String[] getConversationFiles() {
        File dir = new File(this.conversationsPath);
        if (!dir.exists()) {
            LogUtil.w(TAG, "getConversationFiles: dir missing %s", this.conversationsPath);
            return new String[0];
        }
        File[] files = dir.listFiles();
        if (files == null) {
            LogUtil.w(TAG, "getConversationFiles: listFiles() returned null for %s", this.conversationsPath);
            return new String[0];
        }
        String[] result = new String[files.length];
        int count = 0;
        for (int i = 0; i < files.length; i++) {
            String n = files[i].getName();
            // 异常退出可能留下 .bak/.tmp，不能被当成一条幽灵会话
            if (files[i].isFile() && !n.endsWith(".bak") && !n.endsWith(".tmp")) {
                result[count++] = n;
            }
        }
        if (count != result.length) {
            String[] trimmed = new String[count];
            System.arraycopy(result, 0, trimmed, 0, count);
            result = trimmed;
        }
        LogUtil.d(TAG, "getConversationFiles: found %d files", result.length);
        return result;
    }

    // ---- I/O helpers ----

    private boolean writeFile(String filePath, String content) {
        FileWriter writer = null;
        File tmpFile = null;
        try {
            File file = new File(filePath);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                LogUtil.e(TAG, "writeFile mkdirs failed: " + parent.getAbsolutePath());
                return false;
            }
            // 先写临时文件再改名，避免 FileWriter 打开即截断导致写入失败时原内容一并丢失
            tmpFile = new File(filePath + ".tmp");
            writer = new FileWriter(tmpFile);
            // content 为 null 时 FileWriter.write 抛 NPE（不是 IOException，不会被下面的
            // catch 兜住），会直接冒到调用线程。落盘语义上 null 等价于空文件。
            writer.write(content == null ? "" : content);
            writer.flush();
            writer.close();
            writer = null;
            if (!renameAtomic(tmpFile, file)) {
                LogUtil.e(TAG, "writeFile rename failed: " + filePath);
                tmpFile.delete();
                tmpFile = null;
                return false;
            }
            LogUtil.v(TAG, "writeFile: wrote %d chars -> %s", content == null ? 0 : content.length(), filePath);
            return true;
        } catch (IOException e) {
            LogUtil.e(TAG, "writeFile failed: " + filePath + " : " + e.getMessage(), e);
            return false;
        } finally {
            if (writer != null) {
                try { writer.close(); } catch (IOException ignored) {}
            }
            if (tmpFile != null) {
                try { tmpFile.delete(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * tmp → file 的原子替换。目标已存在时先挪到 .bak，任何一步失败都把
     * .bak 还原；绝不直接 delete 旧文件。上次异常退出留下的孤儿 .bak
     * 会先自愈，避免主文件消失后数据只留在备份里。
     */
    private static void recoverStaleBak(String path) {
        if (path == null) return;
        File f = new File(path);
        File bak = new File(path + ".bak");
        if (!f.exists() && bak.exists() && !bak.renameTo(f)) {
            LogUtil.w(TAG, "stale .bak restore failed: %s", path);
        }
    }

    private static boolean renameAtomic(File tmp, File file) {
        File bak = new File(file.getAbsolutePath() + ".bak");
        if (!file.exists() && bak.exists() && !bak.renameTo(file)) {
            LogUtil.w(TAG, "stale .bak restore failed: %s", file.getAbsolutePath());
        }
        // 主流文件系统的 renameTo 会原子覆盖已存在的目标，直接成功；但绝不能
        // 在这里 early return —— 下面「成功后删 .bak」还要执行。轮 13 加的清理
        // 正因为这个早退从未生效：旧 .bak（旧正文明文副本）永远留着，既不进
        // 会话列表也不在任何清理范围内，只有删整条会话才会被带走。
        boolean ok = tmp.renameTo(file);
        if (!ok) {
            boolean saved = !file.exists() || file.renameTo(bak);
            ok = saved && tmp.renameTo(file);
            if (!ok && saved && bak.exists()) bak.renameTo(file);
        }
        // 成功替换后必须删掉备份：在 renameTo 不覆盖的目标文件系统上
        // （部分 ROM / FUSE 就是这样），每次覆盖保存都会留下一个完整的旧副本。
        // 它既不会被 getConversationFiles 列出、也不在 .tmp 清理范围内，
        // 于是「删掉会话」之后正文仍以明文躺在磁盘上，容量还接近翻倍。
        if (ok && bak.exists() && !bak.delete()) {
            LogUtil.w(TAG, "stale .bak delete failed: %s", bak.getAbsolutePath());
        }
        return ok;
    }

    public String readFile(String filePath) {
        recoverStaleBak(filePath);
        BufferedReader reader = null;
        try {
            File file = new File(filePath);
            if (!file.exists()) {
                LogUtil.v(TAG, "readFile: not found %s", filePath);
                return null;
            }
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            // 按文件字节数预置容量：UTF-8 的字节数就是字符数的上界。
            // 不预置时从 16 开始倍增，读一个 5MB 会话累计复制约 10M 字符（20MB 垃圾），
            // 而 loadConversations 会逐文件走这里。超大文件不预置，避免一次申请几十 MB。
            long est = file.length();
            StringBuilder sb = new StringBuilder(est > 0 && est < (8L << 20) ? (int) est : 8192);
            // 直接按 char 块读，不再用 readLine()：
            // readLine 每一行都要 new 一个 String，读完再 append 一次；5MB 的会话若每行
            // 40 字符就是 13 万个临时 String，加上 append 的二次复制，等于比实际内容多
            // 复制三份。这里只有「文件 → char[] → sb → toString」两趟。
            char[] buf = new char[8192];
            int n;
            boolean sawCr = false;
            while ((n = reader.read(buf)) > 0) {
                sb.append(buf, 0, n);
                if (!sawCr) {
                    for (int i = 0; i < n; i++) {
                        if (buf[i] == '\r') { sawCr = true; break; }
                    }
                }
            }
            String result;
            if (sawCr) {
                // 保持与 readLine 版一致的行尾语义：\r\n 与单独的 \r 都归一成 \n。
                // 只有外部工具改过的文件才会带 \r，自己写的一直是 \n，所以这是少数路径。
                int len = sb.length();
                StringBuilder clean = new StringBuilder(len);
                for (int i = 0; i < len; i++) {
                    char c = sb.charAt(i);
                    if (c == '\r') {
                        // \r\n 只出一个 \n；孤立的 \r 也要补一个 \n。
                        // 原来直接丢弃 \r 会把 `a\rb` 粘成 `ab`，与注释宣称的
                        // 「\r\n 和单独的 \r 都归一成 \n」及 readLine 语义都不符，
                        // 且展示后写回会把被破坏的内容永久覆盖原文件。
                        clean.append('\n');
                        if (i + 1 < len && sb.charAt(i + 1) == '\n') i++;
                    } else {
                        clean.append(c);
                    }
                }
                result = clean.toString();
            } else {
                result = sb.toString();
            }
            LogUtil.v(TAG, "readFile: read %d chars <- %s", result.length(), filePath);
            return result;
        } catch (IOException e) {
            LogUtil.e(TAG, "readFile failed: " + filePath + " : " + e.getMessage(), e);
            return null;
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (IOException ignored) {}
            }
        }
    }

    // ---- info ----

    /**
     * 图片附件目录：{@code basePath/attachments}，与会话、导出放在一起。
     *
     * <p>早先它在内部存储的 filesDir 下，理由是「外部存储可能不可用，图片必须随时可读」。
     * 代价是用户把数据目录整包拷到新手机时会话过去了、图片全丢，而且不开 root
     * 根本打不开那个目录。图片和会话是同一条消息的两半，拆开放没有任何好处，
     * 所以本版搬回数据目录，旧文件由 {@link AttachmentFiles#migrateLegacy} 迁移。
     *
     * <p>外部存储真的不可用时仍然退回内部目录：宁可路径分叉，也不能让用户
     * 发不出图、看不到历史图。
     *
     * @return null 表示 Context 尚未初始化（进程重建早期的极端路径）。
     */
    public File getAttachmentsDir() {
        if (this.basePath != null) {
            File dir = new File(this.basePath, "attachments");
            if (dir.isDirectory() || dir.mkdirs()) return dir;
            LogUtil.w(TAG, "getAttachmentsDir: cannot use %s, falling back to internal",
                    dir.getAbsolutePath());
        }
        return getLegacyAttachmentsDir();
    }

    /**
     * 旧版附件目录（内部私有）。只用于迁移与兜底读取，新代码不要再往这里写。
     */
    public File getLegacyAttachmentsDir() {
        if (this.appContext == null) {
            LogUtil.w(TAG, "getLegacyAttachmentsDir: appContext null");
            return null;
        }
        return new File(this.appContext.getFilesDir(), "attachments");
    }

    /** 缓存目录（系统可能随时清理）。 */
    public File getCacheDir() {
        if (this.appContext == null) return null;
        return this.appContext.getCacheDir();
    }

    /** 内部私有数据目录，用于在界面上说明「附件在哪」。 */
    public String getInternalFilesPath() {
        return this.appContext == null ? null : this.appContext.getFilesDir().getAbsolutePath();
    }

    public String getBasePath() {
        return this.basePath;
    }

    public String getConfigPath() {
        return this.configPath;
    }

    public String getConversationsPath() {
        return this.conversationsPath;
    }

    // ---- 外观设置：路径与原子写 ----

    /** 外观配置快照的绝对路径。 */
    public String getAppearancePath() {
        return this.basePath + File.separator + APPEARANCE_FILE;
    }

    public String getThemesDir() {
        return this.basePath + File.separator + THEMES_DIR;
    }

    public String getFontsDir() {
        return this.basePath + File.separator + FONTS_DIR;
    }

    public String loadAppearance() {
        String content = readFile(getAppearancePath());
        LogUtil.d(TAG, "loadAppearance: %s, len=%d", getAppearancePath(),
                content == null ? 0 : content.length());
        return content;
    }

    public boolean saveAppearance(String content) {
        return writeTextAtomic(getAppearancePath(), content);
    }

    /**
     * 通用文本原子写：复用 writeFile 的 .tmp → rename；目标已存在导致部分 ROM
     * rename 失败时，删掉旧文件再试一次，避免「覆盖保存」静默失败。
     */
    public boolean writeTextAtomic(String path, String content) {
        // writeFile 内部已有 .bak 回滚，不能再删目标重试：删除成功而二次
        // rename 失败就会把原文件弄丢。
        return writeFile(path, content);
    }

    /**
     * 通用字节原子写（字体 .ttf/.otf 导入用）。与 saveExportBytes 同款 .tmp → rename，
     * 但接受任意绝对路径而非固定写入 exports。
     */
    public boolean writeBytesAtomic(String path, byte[] bytes) {
        if (path == null || bytes == null) return false;
        File file = new File(path);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            LogUtil.e(TAG, "writeBytesAtomic mkdirs failed: %s", parent.getAbsolutePath());
            return false;
        }
        File tmp = new File(path + ".tmp");
        FileOutputStream out = null;
        boolean ok = false;
        try {
            out = new FileOutputStream(tmp);
            out.write(bytes);
            out.flush();
            out.close();
            out = null;
            if (renameAtomic(tmp, file)) {
                ok = true;
            } else {
                LogUtil.e(TAG, "writeBytesAtomic rename failed: %s", path);
            }
        } catch (IOException e) {
            LogUtil.e(TAG, "writeBytesAtomic failed: %s : %s", path, e.getMessage());
        } finally {
            if (out != null) {
                try { out.close(); } catch (IOException ignored) {}
            }
            if (!ok && tmp.exists()) tmp.delete();
        }
        LogUtil.i(TAG, "writeBytesAtomic: %d bytes -> %s ok=%s", bytes.length, path, ok);
        return ok;
    }

    public String getStorageInfo() {
        return this.useInternalStorage ? "内部存储: " + this.basePath : "外部存储: " + this.basePath;
    }

    public boolean isUsingInternalStorage() {
        return this.useInternalStorage;
    }
}
