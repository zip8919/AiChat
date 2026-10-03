package xyz.zip8919.app.aichat;

import android.content.Context;
import android.os.Environment;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;

public class StorageManager {
    private static final String TAG = "StorageManager";
    private static final String CONFIG_FILE = "config.json";
    private static final String CONVERSATIONS_DIR = "conversations";
    private static final String EXPORTS_DIR = "exports";
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
        return ok;
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

    // ---- conversations ----

    public boolean saveConversation(String conversationId, String content) {
        String filePath = this.conversationsPath + File.separator + conversationId + ".json";
        boolean ok = writeFile(filePath, content);
        LogUtil.v(TAG, "saveConversation: id=%s %d chars ok=%s", conversationId,
                content == null ? 0 : content.length(), ok);
        return ok;
    }

    public String loadConversation(String conversationId) {
        String filePath = this.conversationsPath + File.separator + conversationId + ".json";
        String content = readFile(filePath);
        LogUtil.v(TAG, "loadConversation: id=%s len=%d", conversationId, content == null ? 0 : content.length());
        return content;
    }

    public boolean deleteConversation(String conversationId) {
        String filePath = this.conversationsPath + File.separator + conversationId + ".json";
        File file = new File(filePath);
        boolean ok = file.exists() && file.delete();
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
            if (files[i].isFile()) {
                result[count++] = files[i].getName();
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
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            // 先写临时文件再改名，避免 FileWriter 打开即截断导致写入失败时原内容一并丢失
            tmpFile = new File(filePath + ".tmp");
            writer = new FileWriter(tmpFile);
            writer.write(content);
            writer.flush();
            writer.close();
            writer = null;
            if (!tmpFile.renameTo(file)) {
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

    public String readFile(String filePath) {
        BufferedReader reader = null;
        try {
            File file = new File(filePath);
            if (!file.exists()) {
                LogUtil.v(TAG, "readFile: not found %s", filePath);
                return null;
            }
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            boolean firstLine = true;
            while ((line = reader.readLine()) != null) {
                // readLine() 会剥掉行尾换行符，需在行间补回，否则多行文本被压成一行
                if (!firstLine) {
                    sb.append('\n');
                }
                sb.append(line);
                firstLine = false;
            }
            String result = sb.toString();
            if (result.length() == 0) {
                LogUtil.v(TAG, "readFile: empty file %s", filePath);
                return null;
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

    public String getBasePath() {
        return this.basePath;
    }

    public String getConfigPath() {
        return this.configPath;
    }

    public String getConversationsPath() {
        return this.conversationsPath;
    }

    public String getStorageInfo() {
        return this.useInternalStorage ? "内部存储: " + this.basePath : "外部存储: " + this.basePath;
    }

    public boolean isUsingInternalStorage() {
        return this.useInternalStorage;
    }
}
