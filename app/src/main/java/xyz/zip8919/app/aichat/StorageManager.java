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
            } else {
                this.basePath = Environment.getExternalStorageDirectory().getAbsolutePath()
                        + "/" + DATA_SUBDIR;
                new File(this.basePath).mkdirs();
            }
        }

        this.configPath = this.basePath + File.separator + CONFIG_FILE;
        this.conversationsPath = this.basePath + File.separator + CONVERSATIONS_DIR;
    }

    private String findAvailablePath(String[] paths) {
        for (String path : paths) {
            File dir = new File(path);
            if (dir.exists() || dir.mkdirs()) {
                File testFile = new File(dir, ".write_test");
                try {
                    if (testFile.createNewFile()) {
                        testFile.delete();
                        return path;
                    }
                } catch (IOException ignored) {}
            }
        }
        return null;
    }

    public boolean createDirectories() {
        if (!isExternalStorageAvailable() && !this.useInternalStorage) {
            return false;
        }
        File baseDir = new File(this.basePath);
        if (!baseDir.exists() && !baseDir.mkdirs()) {
            return false;
        }
        File convDir = new File(this.conversationsPath);
        return convDir.exists() || convDir.mkdirs();
    }

    public boolean isExternalStorageAvailable() {
        return "mounted".equals(Environment.getExternalStorageState());
    }

    // ---- config ----

    public boolean saveConfig(String content) {
        return writeFile(this.configPath, content);
    }

    public String loadConfig() {
        return readFile(this.configPath);
    }

    // ---- conversations ----

    public boolean saveConversation(String conversationId, String content) {
        String filePath = this.conversationsPath + File.separator + conversationId + ".json";
        return writeFile(filePath, content);
    }

    public String loadConversation(String conversationId) {
        String filePath = this.conversationsPath + File.separator + conversationId + ".json";
        return readFile(filePath);
    }

    public boolean deleteConversation(String conversationId) {
        String filePath = this.conversationsPath + File.separator + conversationId + ".json";
        File file = new File(filePath);
        return file.exists() && file.delete();
    }

    public String[] getConversationFiles() {
        File dir = new File(this.conversationsPath);
        if (!dir.exists()) {
            return new String[0];
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return new String[0];
        }
        String[] result = new String[files.length];
        for (int i = 0; i < files.length; i++) {
            result[i] = files[i].getName();
        }
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
            return true;
        } catch (IOException e) {
            e.printStackTrace();
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
                return null;
            }
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            String result = sb.toString();
            return result.length() > 0 ? result : null;
        } catch (IOException e) {
            e.printStackTrace();
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
