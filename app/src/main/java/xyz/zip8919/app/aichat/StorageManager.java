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
    private static final String DATA_SUBDIR = "Android/data/xyz.zip8919.app.aichat/files";

    private static StorageManager instance;

    private Context appContext;
    private String basePath;
    private String configPath;
    private String conversationsPath;
    private boolean useInternalStorage = false;

    private StorageManager(Context context) {
        this.appContext = context.getApplicationContext();
        initPaths();
    }

    public static synchronized StorageManager getInstance(Context context) {
        if (instance == null) {
            instance = new StorageManager(context);
        }
        return instance;
    }

    public static synchronized StorageManager getInstance() {
        if (instance == null) {
            LogUtil.e(TAG, "getInstance() failed: StorageManager not initialized");
            throw new IllegalStateException("StorageManager not initialized");
        }
        return instance;
    }

    private void initPaths() {
        String[] preferredPaths = {
            "/storage/internalsd/" + DATA_SUBDIR,
            "/storage/emulated/0/" + DATA_SUBDIR
        };

        this.basePath = findAvailablePath(preferredPaths);
        if (this.basePath == null) {
            if (this.appContext != null) {
                this.basePath = this.appContext.getFilesDir().getAbsolutePath();
                this.useInternalStorage = true;
                LogUtil.i(TAG, "initPaths: no writable external path, fallback to internal %s", this.basePath);
            } else {
                this.basePath = Environment.getExternalStorageDirectory().getAbsolutePath()
                        + "/" + DATA_SUBDIR;
                new File(this.basePath).mkdirs();
                LogUtil.w(TAG, "initPaths: no context, fallback to external root %s", this.basePath);
            }
        } else {
            LogUtil.i(TAG, "initPaths: using external path %s", this.basePath);
        }

        this.configPath = this.basePath + File.separator + CONFIG_FILE;
        this.conversationsPath = this.basePath + File.separator + CONVERSATIONS_DIR;
        LogUtil.d(TAG, "initPaths: config=%s conversations=%s", this.configPath, this.conversationsPath);
    }

    private String findAvailablePath(String[] paths) {
        for (String path : paths) {
            File dir = new File(path);
            if (dir.exists() || dir.mkdirs()) {
                File testFile = new File(dir, ".write_test");
                try {
                    if (testFile.createNewFile()) {
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
        for (int i = 0; i < files.length; i++) {
            result[i] = files[i].getName();
        }
        LogUtil.d(TAG, "getConversationFiles: found %d files", result.length);
        return result;
    }

    // ---- I/O helpers ----

    private boolean writeFile(String filePath, String content) {
        FileWriter writer = null;
        try {
            File file = new File(filePath);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            writer = new FileWriter(file);
            writer.write(content);
            writer.flush();
            LogUtil.v(TAG, "writeFile: wrote %d chars -> %s", content == null ? 0 : content.length(), filePath);
            return true;
        } catch (IOException e) {
            LogUtil.e(TAG, "writeFile failed: " + filePath + " : " + e.getMessage(), e);
            return false;
        } finally {
            if (writer != null) {
                try { writer.close(); } catch (IOException ignored) {}
            }
        }
    }

    private String readFile(String filePath) {
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
            while ((line = reader.readLine()) != null) {
                sb.append(line);
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
