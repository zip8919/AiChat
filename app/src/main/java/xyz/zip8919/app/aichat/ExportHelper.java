package xyz.zip8919.app.aichat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.widget.EditText;
import android.widget.Toast;

/**
 * 代码块落盘：语言 → 扩展名映射、文件名清洗，以及"另存为"对话框流程。
 *
 * <p>从 MainActivity 抽出。这里只有文件命名与保存策略，不碰任何视图状态，
 * 因此 JsBridge 的 saveCode 回调、JsRunnerActivity 的导出入口可以共用同一份规则，
 * 不会出现两处扩展名映射不一致的问题。
 */
final class ExportHelper {

    private static final String TAG = "ExportHelper";

    private ExportHelper() {
    }

    /** 弹出"保存代码"对话框；文件名可编辑，重名走覆盖确认。 */
    static void saveCode(final Activity activity, final String lang, final String code) {
        if (code == null || code.trim().isEmpty()) {
            AcToast.show(activity, "代码内容为空，无法保存");
            return;
        }
        final String ext = extForLang(lang);
        final String type = typeForLang(lang);
        final EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setText("code_" + type + "_" + System.currentTimeMillis());
        input.setSelection(input.getText().length());
        AcDialog.builder(activity)
                .setTitle("保存代码")
                .setMessage("文件名（自动追加 ." + ext + "）")
                .setView(input)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String name = sanitizeName(input.getText().toString());
                        if (name.isEmpty()) {
                            name = "code_" + type + "_" + System.currentTimeMillis();
                        }
                        final String fileName = name + "." + ext;
                        if (StorageManager.getInstance().exportExists(fileName)) {
                            confirmOverwrite(activity, fileName, lang, code);
                            return;
                        }
                        write(activity, fileName, lang, code);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static void confirmOverwrite(final Activity activity, final String fileName,
            final String lang, final String code) {
        AcDialog.builder(activity)
                .setTitle("文件已存在")
                .setMessage(fileName + " 已存在，是否覆盖？")
                .setPositiveButton("覆盖", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        write(activity, fileName, lang, code);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static void write(Activity activity, String fileName, String lang, String code) {
        String path = StorageManager.getInstance().saveExport(fileName, code);
        LogUtil.i(TAG, "saveCode: lang=%s file=%s path=%s", lang, fileName, path);
        if (path == null) {
            AcToast.show(activity, "保存失败");
        } else {
            AcToast.showLong(activity, "已保存到 " + path);
        }
    }

    static String extForLang(String lang) {
        String key = normalizeLangToken(lang);
        if (key.isEmpty()) {
            return "txt";
        }
        if ("html".equals(key) || "htm".equals(key) || "xhtml".equals(key)) {
            return "html";
        }
        if ("svg".equals(key)) {
            return "svg";
        }
        if ("js".equals(key) || "javascript".equals(key) || "node".equals(key) || "jsx".equals(key)) {
            return "js";
        }
        if ("ts".equals(key) || "typescript".equals(key)) {
            return "ts";
        }
        if ("py".equals(key) || "python".equals(key) || "python3".equals(key)) {
            return "py";
        }
        if ("kt".equals(key) || "kotlin".equals(key)) {
            return "kt";
        }
        if ("sh".equals(key) || "shell".equals(key) || "bash".equals(key)
                || "zsh".equals(key) || "console".equals(key)) {
            return "sh";
        }
        if ("c++".equals(key) || "cpp".equals(key) || "cxx".equals(key)) {
            return "cpp";
        }
        if ("c#".equals(key) || "cs".equals(key) || "csharp".equals(key)) {
            return "cs";
        }
        if ("yml".equals(key) || "yaml".equals(key)) {
            return "yaml";
        }
        if ("md".equals(key) || "markdown".equals(key)) {
            return "md";
        }
        if ("rs".equals(key) || "rust".equals(key)) {
            return "rs";
        }
        if ("rb".equals(key) || "ruby".equals(key)) {
            return "rb";
        }
        if ("pl".equals(key) || "perl".equals(key)) {
            return "pl";
        }
        if ("ps1".equals(key) || "powershell".equals(key)) {
            return "ps1";
        }
        if ("objective-c".equals(key) || "objc".equals(key)) {
            return "m";
        }
        if ("plaintext".equals(key) || "text".equals(key) || "txt".equals(key)) {
            return "txt";
        }
        // Already a usable extension token (java, json, xml, go, sql, css, php, swift,
        // dart, lua, scala, groovy, ini, properties, r, diff, patch, ...).
        if (key.matches("[a-z0-9_+-]{1,10}")) {
            return key;
        }
        return "txt";
    }

    /**
     * 默认文件名里的类型标记，直接取代码块声明的语言，让 ```python 存成
     * code_python_&lt;ts&gt;.py，而不是所有未知语言都塌缩成 txt。
     */
    static String typeForLang(String lang) {
        String key = normalizeLangToken(lang);
        return key.isEmpty() ? "txt" : key;
    }

    private static String normalizeLangToken(String lang) {
        if (lang == null) {
            return "";
        }
        return lang.trim().toLowerCase().replaceAll("[^a-z0-9_+.#-]", "");
    }

    /** 去掉路径分隔符和 Android 文件系统不接受的字符。 */
    static String sanitizeName(String name) {
        if (name == null) {
            return "";
        }
        return name.trim().replaceAll("[/\\\\:*?\"<>|]", "_");
    }
}
