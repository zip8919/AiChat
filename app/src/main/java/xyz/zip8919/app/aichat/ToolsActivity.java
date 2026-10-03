package xyz.zip8919.app.aichat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
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

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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

        fileList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                showFileMenu(files.get(pos));
            }
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
        return new File(StorageManager.getInstance().getBasePath(), "exports");
    }

    private void refreshFiles() {
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
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        LogUtil.i(TAG, "refreshFiles: dir=%s count=%d", dir.getAbsolutePath(), files.size());

        List<String> labels = new ArrayList<String>();
        for (File f : files) {
            labels.add(f.getName() + "\n" + f.length() + " B");
        }
        if (labels.isEmpty()) {
            labels.add("（暂无已保存代码）");
        }
        fileList.setAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, labels));
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
        return StorageManager.getInstance().readFile(f.getAbsolutePath());
    }

    private void viewFile(final File f) {
        final String content = readFile(f);
        if (content == null) {
            Toast.makeText(this, "读取失败", Toast.LENGTH_SHORT).show();
            return;
        }
        TextView tv = new TextView(this);
        tv.setText(content);
        tv.setTextSize(12);
        tv.setTextIsSelectable(true);
        tv.setPadding(12, 12, 12, 12);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle(f.getName())
                .setView(sv)
                .setPositiveButton("复制全部", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        copyToClipboard(content);
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
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
                        String name = MainActivity.sanitizeExportName(input.getText().toString());
                        if (name.isEmpty()) {
                            Toast.makeText(ToolsActivity.this, "文件名不能为空", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        File target = new File(exportsDir(), name);
                        boolean ok = f.renameTo(target);
                        LogUtil.i(TAG, "renameFile: %s -> %s ok=%s", f.getName(), name, ok);
                        if (!ok) {
                            Toast.makeText(ToolsActivity.this, "重命名失败", Toast.LENGTH_SHORT).show();
                        }
                        refreshFiles();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
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
                        String ok = StorageManager.getInstance()
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
        String lang = MainActivity.exportExtForLang(extOf(f.getName()));
        LogUtil.i(TAG, "previewFile: %s ext=%s", f.getName(), lang);
        startActivity(MainActivity.newPreviewIntent(this, lang, content));
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
                            currentJsFileName = files.get(which).getName();
                            jsInput.setText(content);
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
        startActivity(MainActivity.newJsRunnerIntent(this, code, MainActivity.exportExtForLang(extOf(currentJsFileName))));
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
