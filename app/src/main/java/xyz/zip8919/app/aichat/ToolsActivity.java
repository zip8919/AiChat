package xyz.zip8919.app.aichat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.SparseBooleanArray;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.webkit.WebView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/**
 * Long-press on 历史 opens this page: saved-code manager plus a JS runner.
 */
public class ToolsActivity extends Activity {

    private static final String TAG = "ToolsActivity";

    private ListView fileList;
    private EditText jsInput;
    private final List<File> files = new ArrayList<File>();
    private String currentJsFileName = "";
    private boolean loadingFile = false;

    private boolean selectionMode = false;
    private final HashSet<String> checkedNames = new HashSet<String>();
    private LinearLayout selectionActions;
    private TextView selectionCount;
    private Button selectButton;
    private Button selectionCancelButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tools);

        findViewById(R.id.tools_back_button).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });

        fileList = (ListView) findViewById(R.id.tools_file_list);
        jsInput = (EditText) findViewById(R.id.tools_js_input);
        jsInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        // 用户手动改写输入框后旧文件名不再代表内容语言，否则 JS 会被当 HTML 渲染而静默无输出
        jsInput.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { }
            public void afterTextChanged(Editable s) {
                if (!loadingFile) {
                    currentJsFileName = "";
                }
            }
        });

        fileList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                if (selectionMode) {
                    updateSelectionBar();
                    return;
                }
                if (pos < 0 || pos >= files.size()) {
                    return;
                }
                showFileMenu(files.get(pos));
            }
        });

        selectionActions = (LinearLayout) findViewById(R.id.tools_selection_actions);
        selectionCount = (TextView) findViewById(R.id.tools_selection_count);
        selectButton = (Button) findViewById(R.id.tools_select_button);
        selectionCancelButton = (Button) findViewById(R.id.tools_selection_cancel_button);
        selectButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { enterSelectionMode(); }
        });
        selectionCancelButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { exitSelectionMode(); }
        });
        findViewById(R.id.tools_selection_all_button).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { selectAllFiles(); }
        });
        findViewById(R.id.tools_selection_invert_button).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { invertFileSelection(); }
        });
        findViewById(R.id.tools_selection_delete_button).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { confirmDeleteSelectedFiles(); }
        });

        findViewById(R.id.tools_js_run_button).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { runJs(jsInput.getText().toString()); }
        });
        findViewById(R.id.tools_js_from_file_button).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickFileIntoInput(); }
        });
        findViewById(R.id.tools_js_clear_button).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { jsInput.setText(""); currentJsFileName = ""; }
        });
        findViewById(R.id.tools_js_paste_button).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pasteIntoInput(); }
        });
        findViewById(R.id.tools_env_copy_button).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { copyEnvPrompt(); }
        });
        findViewById(R.id.tools_env_insert_button).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { sendEnvPrompt(false); }
        });
        findViewById(R.id.tools_env_send_button).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { sendEnvPrompt(true); }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshFiles();
    }

    private File exportsDir() {
        // 传入 Context：本页可能是进程重建后的栈顶，惰性初始化必须带上 appContext，
        // 否则 StorageManager 会以 null context 固化基础路径，且后续无法再被 MainActivity 纠正
        return new File(StorageManager.getInstance(getApplicationContext()).getBasePath(), "exports");
    }

    private void refreshFiles() {
        // 重建 adapter 前先按文件名记录勾选，否则 onResume 会丢掉用户已选中的项
        if (selectionMode) {
            checkedNames.clear();
            for (int i = 0; i < files.size(); i++) {
                if (fileList.isItemChecked(i)) {
                    checkedNames.add(files.get(i).getName());
                }
            }
        }
        files.clear();
        File dir = exportsDir();
        File[] list = dir.listFiles();
        if (list != null) {
            for (File f : list) {
                if (f.isFile()) {
                    files.add(f);
                }
            }
        }
        Collections.sort(files, new Comparator<File>() {
            public int compare(File a, File b) {
                long x = b.lastModified();
                long y = a.lastModified();
                return (x < y) ? -1 : ((x == y) ? 0 : 1);
            }
        });
        LogUtil.i(TAG, "refreshFiles: dir=%s count=%d", dir.getAbsolutePath(), files.size());

        List<String> labels = new ArrayList<String>();
        for (File f : files) {
            labels.add(f.getName() + "\n" + f.length() + " B");
        }
        if (labels.isEmpty() && !selectionMode) {
            labels.add("（暂无已保存代码）");
        }
        fileList.setChoiceMode(selectionMode
                ? ListView.CHOICE_MODE_MULTIPLE : ListView.CHOICE_MODE_NONE);
        int itemLayout = selectionMode
                ? R.layout.item_tools_file_sel : android.R.layout.simple_list_item_1;
        fileList.setAdapter(new ArrayAdapter<String>(this, itemLayout, labels));
        fileList.clearChoices();
        if (selectionMode) {
            for (int i = 0; i < files.size(); i++) {
                if (checkedNames.contains(files.get(i).getName())) {
                    fileList.setItemChecked(i, true);
                }
            }
        }
        updateSelectionBar();
    }

    // ---------- multi-select ----------

    @Override
    public void onBackPressed() {
        if (selectionMode) {
            exitSelectionMode();
            return;
        }
        super.onBackPressed();
    }

    private void enterSelectionMode() {
        if (files.isEmpty()) {
            Toast.makeText(this, "没有可用的已保存代码", Toast.LENGTH_SHORT).show();
            return;
        }
        selectionMode = true;
        refreshFiles();
        LogUtil.i(TAG, "enterSelectionMode: count=%d", files.size());
    }

    private void exitSelectionMode() {
        selectionMode = false;
        fileList.clearChoices();
        refreshFiles();
        LogUtil.i(TAG, "exitSelectionMode");
    }

    private int selectedFileCount() {
        SparseBooleanArray checked = fileList.getCheckedItemPositions();
        int n = 0;
        if (checked != null) {
            for (int i = 0; i < checked.size(); i++) {
                if (checked.valueAt(i)) {
                    n++;
                }
            }
        }
        return n;
    }

    private void updateSelectionBar() {
        if (selectionActions == null) {
            return;
        }
        selectionActions.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        selectButton.setVisibility(selectionMode ? View.GONE : View.VISIBLE);
        selectionCancelButton.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        if (selectionMode) {
            selectionCount.setText("已选 " + selectedFileCount() + " / " + files.size());
        } else {
            selectionCount.setText("点击多选可批量删除");
        }
    }

    private void selectAllFiles() {
        for (int i = 0; i < files.size(); i++) {
            fileList.setItemChecked(i, true);
        }
        updateSelectionBar();
    }

    private void invertFileSelection() {
        for (int i = 0; i < files.size(); i++) {
            fileList.setItemChecked(i, !fileList.isItemChecked(i));
        }
        updateSelectionBar();
    }

    private List<File> getSelectedFiles() {
        List<File> selected = new ArrayList<File>();
        for (int i = 0; i < files.size(); i++) {
            if (fileList.isItemChecked(i)) {
                selected.add(files.get(i));
            }
        }
        return selected;
    }

    private void confirmDeleteSelectedFiles() {
        final List<File> selected = getSelectedFiles();
        if (selected.isEmpty()) {
            Toast.makeText(this, "请先选择要删除的代码", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("删除代码")
                .setMessage("确定要删除选中的 " + selected.size() + " 个文件吗？\n\n此操作不可恢复！")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        deleteSelectedFiles(selected);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void deleteSelectedFiles(List<File> selected) {
        int ok = 0;
        for (File f : selected) {
            if (f.delete()) {
                ok++;
            }
        }
        LogUtil.i(TAG, "deleteSelectedFiles: %d/%d ok", ok, selected.size());
        Toast.makeText(this, "已删除 " + ok + " 个文件", Toast.LENGTH_SHORT).show();
        selectionMode = false;
        refreshFiles();
    }

    // ---------- saved code ----------

    private void showFileMenu(final File f) {
        final String[] items = {
                "查看 / 复制内容", "重命名", "编辑", "预览 / 运行", "删除"
        };
        new AlertDialog.Builder(this)
                .setTitle(f.getName())
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        switch (which) {
                            case 0: viewFile(f); break;
                            case 1: renameFile(f); break;
                            case 2: editFile(f); break;
                            case 3: previewFile(f); break;
                            case 4: deleteFile(f); break;
                            default: break;
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String readFile(File f) {
        return StorageManager.getInstance(getApplicationContext()).readFile(f.getAbsolutePath());
    }

    private void viewFile(final File f) {
        // 读文件与整篇正则高亮都放到后台线程，避免点击「查看」时阻塞主线程
        new Thread(new Runnable() {
            public void run() {
                final String content = readFile(f);
                if (content == null) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            Toast.makeText(ToolsActivity.this, "读取失败", Toast.LENGTH_SHORT).show();
                        }
                    });
                    return;
                }
                final String lang = MainActivity.detectLang(content);
                final String html = buildHighlightHtml(content, lang);
                LogUtil.i(TAG, "viewFile: %s lang=%s len=%d", f.getName(), lang, content.length());
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        showContentDialog(f, content, lang, html);
                    }
                });
            }
        }).start();
    }

    private void showContentDialog(File f, final String content, String lang, String html) {
        final WebView wv = new WebView(this);
        wv.getSettings().setJavaScriptEnabled(false);
        wv.setBackgroundColor(Color.WHITE);
        int viewH = (int) (getResources().getDisplayMetrics().density * 380);
        wv.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, viewH));
        wv.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(f.getName() + "  [" + lang + "]")
                .setView(wv)
                .setPositiveButton("复制全部", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        copyToClipboard(content);
                    }
                })
                .setNegativeButton("关闭", null)
                .create();
        // 弹窗关闭即销毁 WebView，否则每次查看都泄漏一个 WebView 及渲染资源
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            public void onDismiss(DialogInterface d) {
                wv.destroy();
            }
        });
        dialog.show();
    }

    private String buildHighlightHtml(String content, String lang) {
        return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<style>"
                + "html,body{margin:0;padding:0;background:#fff;}"
                + "pre{margin:8px;padding:8px;background:#F5F5F5;font-family:monospace;font-size:12px;"
                + "white-space:pre-wrap;word-wrap:break-word;}"
                + ".tk-kw{color:#d73a49;font-weight:bold;}"
                + ".tk-str{color:#032f62;}"
                + ".tk-cmt{color:#6a737d;font-style:italic;}"
                + ".tk-num{color:#005cc5;}"
                + ".tk-type{color:#6f42c1;}"
                + ".tk-fn{color:#6f42c1;}"
                + ".tk-op{color:#d73a49;}"
                + "</style></head><body><pre>"
                + CodeHighlighter.highlight(content, lang)
                + "</pre></body></html>";
    }

    private void renameFile(final File f) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(f.getName());
        input.setSelection(input.getText().length());
        new AlertDialog.Builder(this)
                .setTitle("重命名")
                .setView(input)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        final String name = MainActivity.sanitizeExportName(input.getText().toString());
                        if (name.isEmpty()) {
                            Toast.makeText(ToolsActivity.this, "文件名不能为空", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        final File target = new File(exportsDir(), name);
                        if (!target.equals(f) && target.exists()) {
                            // rename(2) 会静默覆盖已存在的目标文件，先确认避免数据丢失
                            new AlertDialog.Builder(ToolsActivity.this)
                                    .setTitle("文件已存在")
                                    .setMessage(name + " 已存在，是否覆盖？")
                                    .setPositiveButton("覆盖", new DialogInterface.OnClickListener() {
                                        public void onClick(DialogInterface d2, int w2) {
                                            performRename(f, target, name);
                                        }
                                    })
                                    .setNegativeButton("取消", null)
                                    .show();
                            return;
                        }
                        performRename(f, target, name);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void performRename(File f, File target, String name) {
        boolean ok = f.renameTo(target);
        LogUtil.i(TAG, "renameFile: %s -> %s ok=%s", f.getName(), name, ok);
        if (!ok) {
            Toast.makeText(ToolsActivity.this, "重命名失败", Toast.LENGTH_SHORT).show();
        }
        refreshFiles();
    }

    private void editFile(final File f) {
        final String content = readFile(f);
        if (content == null) {
            Toast.makeText(this, "读取失败", Toast.LENGTH_SHORT).show();
            return;
        }
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setText(content);
        input.setTextSize(12);
        new AlertDialog.Builder(this)
                .setTitle("编辑 " + f.getName())
                .setView(input)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String ok = StorageManager.getInstance(getApplicationContext())
                                .saveExport(f.getName(), input.getText().toString());
                        LogUtil.i(TAG, "editFile: save %s ok=%s", f.getName(), (ok != null));
                        if (ok == null) {
                            Toast.makeText(ToolsActivity.this, "保存失败", Toast.LENGTH_SHORT).show();
                        }
                        refreshFiles();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void previewFile(File f) {
        String content = readFile(f);
        if (content == null) {
            Toast.makeText(this, "读取失败", Toast.LENGTH_SHORT).show();
            return;
        }
        String lang = MainActivity.detectLang(content);
        LogUtil.i(TAG, "previewFile: %s lang=%s", f.getName(), lang);
        if ("html".equals(lang) || "svg".equals(lang)) {
            startActivity(MainActivity.newCodePreviewIntent(this, lang, content));
        } else {
            startActivity(MainActivity.newJsRunnerIntent(this, content, lang));
        }
    }

    private void deleteFile(final File f) {
        new AlertDialog.Builder(this)
                .setTitle("删除 " + f.getName() + " ?")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        boolean ok = f.delete();
                        LogUtil.i(TAG, "deleteFile: %s ok=%s", f.getName(), ok);
                        if (!ok) {
                            Toast.makeText(ToolsActivity.this, "删除失败", Toast.LENGTH_SHORT).show();
                        }
                        refreshFiles();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static String extOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    private static String sniffLang(String code) {
        if (code == null) {
            return "js";
        }
        String lower = code.trim().toLowerCase(java.util.Locale.US);
        if (lower.startsWith("<!doctype html") || lower.startsWith("<html")) {
            return "html";
        }
        if (lower.startsWith("<svg")) {
            return "svg";
        }
        if (lower.startsWith("<?xml")) {
            int xmlEnd = lower.indexOf("?>");
            if (xmlEnd >= 0 && lower.substring(xmlEnd + 2).trim().startsWith("<svg")) {
                return "svg";
            }
        }
        return "js";
    }

    // ---------- JS runner ----------

    private void pickFileIntoInput() {
        if (files.isEmpty()) {
            Toast.makeText(this, "没有可用的已保存代码", Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] names = new String[files.size()];
        for (int i = 0; i < files.size(); i++) {
            names[i] = files.get(i).getName();
        }
        new AlertDialog.Builder(this)
                .setTitle("选择代码文件")
                .setItems(names, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        String content = readFile(files.get(which));
                        if (content != null) {
                            loadingFile = true;
                            jsInput.setText(content);
                            loadingFile = false;
                            currentJsFileName = files.get(which).getName();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void pasteIntoInput() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null) {
            Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipData clip = cm.getPrimaryClip();
        if (clip.getItemCount() == 0) {
            Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence text = clip.getItemAt(0).coerceToText(this);
        jsInput.setText(text == null ? "" : text.toString());
        currentJsFileName = "";
    }

    private void runJs(String code) {
        if (code == null || code.trim().isEmpty()) {
            Toast.makeText(this, "请输入要运行的 JS 代码", Toast.LENGTH_SHORT).show();
            return;
        }
        LogUtil.i(TAG, "runJs: len=%d", code.length());
        String lang = currentJsFileName == null || currentJsFileName.isEmpty()
                ? sniffLang(code) : extOf(currentJsFileName);
        startActivity(MainActivity.newJsRunnerIntent(this, code, MainActivity.exportExtForLang(lang)));
    }

    // ---------- env prompt ----------

    private static final String ENV_PROMPT =
            "我在一个 Android 4.4 (API 19) 的 WebView 里运行 JavaScript，"
            + "运行环境是 Chromium 30 内核，不支持 ES6 及以上语法"
            + "（不能用 let/const、箭头函数、模板字符串、class、Promise、"
            + "扩展运算符、async/await、for...of）。"
            + "为避免中文编码问题，请把所有中文文本写成 \\uXXXX 转义形式。"
            + "只输出一个 ```js 代码块，代码用 var 声明变量，可直接运行并输出结果。";

    private void copyEnvPrompt() {
        copyToClipboard(ENV_PROMPT);
    }

    private void sendEnvPrompt(boolean send) {
        LogUtil.i(TAG, "sendEnvPrompt: send=%s", send);
        Toast.makeText(this, send ? "已发送环境提示词" : "已插入环境提示词",
                Toast.LENGTH_SHORT).show();
        startActivity(MainActivity.newPromptIntent(this, ENV_PROMPT, send));
        finish();
    }

    private void copyToClipboard(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("aichat", text));
            Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
        }
    }
}
