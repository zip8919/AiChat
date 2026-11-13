package xyz.zip8919.app.aichat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Base64;
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
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * Long-press on 历史 opens this page: saved-code manager plus a JS runner.
 */
public class ToolsActivity extends ThemedActivity {

    private static final String TAG = "ToolsActivity";

    private ListView fileList;
    private EditText jsInput;
    private final List<File> files = new ArrayList<File>();
    private String currentJsFileName = "";
    private boolean loadingFile = false;

    /**
     * 代码预览器。
     *
     * <p>此前预览是 {@code newCodePreviewIntent()}：目标写成 MainActivity +
     * {@code CLEAR_TOP|SINGLE_TOP}，等于把本页从栈里清掉再回主界面弹预览。
     * 用户看到的现象是「预览完按返回直接掉回聊天页」，正在清理的文件列表也没了。
     * 改成在本页叠一个 dialog，关掉仍在原页面。
     */
    private ViewerController viewer;

    private boolean selectionMode = false;
    private final HashSet<String> checkedNames = new HashSet<String>();
    private LinearLayout selectionActions;
    private TextView selectionCount;
    private Button selectButton;
    private Button selectionCancelButton;
    /** 分段容器：代码库 / 运行器，同一时刻只显示一个。 */
    private LinearLayout libraryPanel;
    private LinearLayout runnerPanel;
    private LinearLayout selectionFooter;
    private LinearLayout libraryHead;
    private Button tabLibrary;
    private Button tabRunner;
    private View emptyView;
    private boolean runnerTab;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tools);
        viewer = new ViewerController(this);

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
        selectionFooter = (LinearLayout) findViewById(R.id.tools_selection_footer);
        libraryHead = (LinearLayout) findViewById(R.id.tools_library_head);
        emptyView = findViewById(R.id.tools_empty);
        libraryPanel = (LinearLayout) findViewById(R.id.tools_panel_library);
        runnerPanel = (LinearLayout) findViewById(R.id.tools_panel_runner);
        tabLibrary = (Button) findViewById(R.id.tools_tab_library);
        tabRunner = (Button) findViewById(R.id.tools_tab_runner);
        // 这两个分段按钮的颜色由 showTab() 按「选中态」动态决定，同一 View 会在
        // primary / surface 之间来回切，按捕获色反查会固定成一种颜色把选中态抹掉。
        // 整块豁免重着色，改由 showTab() 自己读当前生效色（顺便避开 250% 档撑破 40dp）。
        tabLibrary.setTag(R.id.ac_no_recolor, Boolean.TRUE);
        tabRunner.setTag(R.id.ac_no_recolor, Boolean.TRUE);
        tabLibrary.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showTab(false); }
        });
        tabRunner.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showTab(true); }
        });
        findViewById(R.id.tools_select_entry).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { enterSelectionMode(); }
        });
        findViewById(R.id.tools_tpl_hello).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                loadTemplate("console.log('Hello from AiChat');");
            }
        });
        findViewById(R.id.tools_tpl_dom).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                loadTemplate("var d = document.createElement('div');\n"
                        + "d.textContent = 'DOM 就绪：' + document.title;\n"
                        + "document.body.appendChild(d);\n"
                        + "console.log('已插入一个 div');");
            }
        });
        findViewById(R.id.tools_tpl_timer).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                loadTemplate("var n = 0;\n"
                        + "var t = setInterval(function () {\n"
                        + "  console.log('tick ' + (++n));\n"
                        + "  if (n >= 5) clearInterval(t);\n"
                        + "}, 300);");
            }
        });
        findViewById(R.id.tools_env_entry).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showEnvSheet(); }
        });
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
        // 环境提示词的三个动作在 tools_env_entry 的弹层里，不再是一排并列按钮
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshFiles();
    }

    /** 分段切换：代码库 ↔ 运行器。段控件固定，不随内容滚走。 */
    private void showTab(boolean runner) {
        runnerTab = runner;
        libraryPanel.setVisibility(runner ? View.GONE : View.VISIBLE);
        runnerPanel.setVisibility(runner ? View.VISIBLE : View.GONE);
        int primary = ThemeTokens.get(this, ThemeTokens.AC_PRIMARY);
        int surface = AppearanceManager.getInstance(this).getEffectiveColor(ThemeTokens.AC_SURFACE);
        int text = AppearanceManager.getInstance(this).getEffectiveColor(ThemeTokens.AC_TEXT);
        tabLibrary.setBackgroundColor(runner ? surface : primary);
        tabLibrary.setTextColor(runner ? text : 0xFFFFFFFF);
        tabRunner.setBackgroundColor(runner ? primary : surface);
        tabRunner.setTextColor(runner ? 0xFFFFFFFF : text);
        if (runner) {
            selectionFooter.setVisibility(View.GONE);
        } else {
            updateSelectionBar();
        }
    }

    /** 空态模板：点一下切到运行器并把代码填好，省掉「先建文件再打开」的两步。 */
    private void loadTemplate(String code) {
        loadingFile = true;
        jsInput.setText(code);
        loadingFile = false;
        currentJsFileName = "";
        showTab(true);
        jsInput.setSelection(code.length());
        LogUtil.i(TAG, "loadTemplate: len=%d", code.length());
    }

    private void showEnvSheet() {
        final String[] items = {
                "复制提示词 — 贴到别的 AI 里",
                "插入到输入框 — 先改再用",
                "直接发送给 AI — 回聊天页发出"};
        AcDialog.builder(this)
                .setTitle("给 AI 的环境说明")
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            copyEnvPrompt();
                        } else if (which == 1) {
                            sendEnvPrompt(false);
                        } else {
                            sendEnvPrompt(true);
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        // 预览 dialog 持有 WebView 与 Activity，不释放会随每次进出累积
        if (viewer != null) {
            viewer.destroy();
            viewer = null;
        }
        super.onDestroy();
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
            labels.add(f.getName() + "\n" + f.length() + " B"
                    + (isImageFile(f) ? "  [图片]" : ""));
        }
        // 空态不再用一行「（暂无已保存文件）」占位，改为说明 + 3 个模板（见 layout 的 tools_empty）
        boolean empty = labels.isEmpty();
        fileList.setVisibility(empty && !selectionMode ? View.GONE : View.VISIBLE);
        emptyView.setVisibility(empty && !selectionMode ? View.VISIBLE : View.GONE);
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
        // 分段里的返回先回「代码库」，再按一次才退出页面 —— 与用户对分段的心智一致
        if (runnerTab) {
            showTab(false);
            return;
        }
        super.onBackPressed();
    }

    private void enterSelectionMode() {
        if (files.isEmpty()) {
            AcToast.show(this, "没有可用的已保存代码");
            return;
        }
        selectionMode = true;
        refreshFiles();
        LogUtil.i(TAG, "enterSelectionMode: count=%d", files.size());
    }

    private void exitSelectionMode() {
        selectionMode = false;
        // 必须清空：refreshFiles 只在 selectionMode 为 true 时重建这个集合，
        // 退出时不清就会留到下一次多选，把上一轮选过的文件重新勾上，
        // 用户点删除时连带删掉本轮没选的文件（不可恢复）。
        checkedNames.clear();
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
        // 多选态：顶部条换状态栏、列表头（含「多选」入口）让位、底部出现固定删除栏
        findViewById(R.id.tools_selection_bar).setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        libraryHead.setVisibility(selectionMode ? View.GONE : View.VISIBLE);
        selectionFooter.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        selectionActions.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        selectButton.setVisibility(View.GONE);
        selectionCancelButton.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        if (selectionMode) {
            selectionCount.setText("已选 " + selectedFileCount() + " / " + files.size());
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
            AcToast.show(this, "请先选择要删除的代码");
            return;
        }
        AcDialog.builder(this)
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
        AcToast.show(this, "已删除 " + ok + " 个文件");
        StorageActivity.invalidateCache();
        selectionMode = false;
        checkedNames.clear();   // 同上：不清会被下一轮多选继承，导致误删
        refreshFiles();
    }

    // ---------- saved code ----------

    private void showFileMenu(final File f) {
        // 图片文件不能走文本菜单：readFile 会把 PNG/JPEG 字节按 UTF-8 解成乱码，
        // 编辑再保存更是直接把图片写坏。给图片单独一条「预览 / 重命名 / 删除」。
        if (isImageFile(f)) {
            final String[] imgItems = {"预览图片", "打开方式", "分享", "重命名", "删除"};
            AcDialog.builder(this)
                    .setTitle(f.getName())
                    .setItems(imgItems, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int which) {
                            switch (which) {
                                case 0: FileActions.previewImage(ToolsActivity.this, f); break;
                                case 1: FileActions.open(ToolsActivity.this, f); break;
                                case 2: FileActions.share(ToolsActivity.this, f); break;
                                case 3: renameFile(f); break;
                                case 4: deleteFile(f); break;
                                default: break;
                            }
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        final String[] items = {
                "查看 / 复制内容", "重命名", "编辑", "预览 / 运行",
                "打开方式（第三方应用）", "分享", "删除"
        };
        AcDialog.builder(this)
                .setTitle(f.getName())
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        switch (which) {
                            case 0: viewFile(f); break;
                            case 1: renameFile(f); break;
                            case 2: editFile(f); break;
                            case 3: previewFile(f); break;
                            case 4: FileActions.open(ToolsActivity.this, f); break;
                            case 5: FileActions.share(ToolsActivity.this, f); break;
                            case 6: deleteFile(f); break;
                            default: break;
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 查看器导出的 PNG/JPEG，以及用户自己放进 exports 的位图。 */
    static boolean isImageFile(File f) {
        return FileActions.isImage(f.getName());
    }

    /**
     * 用 base64 data URI 显示图片，不用 file:// —— 项目里 MathRenderer 也是这个做法，
     * 避免 WebView 的 file 访问限制与路径转义问题。读文件放后台，避免大图卡主线程。
     *
     * <p>实现已抽到 {@link FileActions}：存储管理的两个列表也要同一套预览、
     * 同一套「打开方式 / 分享」，复制三份必然漂移成三种行为。
     */
    private void previewImage(final File f) {
        FileActions.previewImage(this, f);
    }

    private String readFile(File f) {
        return StorageManager.getInstance(getApplicationContext()).readFile(f.getAbsolutePath());
    }

    /** 三个文本入口的兜底：万一图片文件绕过菜单（如外部调用），不把字节当文本读。 */
    private boolean guardImage(File f) {
        if (!isImageFile(f)) return false;
        AcToast.show(this, "图片请用「预览图片」查看");
        previewImage(f);
        return true;
    }

    private void viewFile(final File f) {
        if (guardImage(f)) return;
        // 读文件、整篇正则高亮都在 FileActions 的后台线程里做，不占主线程
        FileActions.viewText(this, f);
    }



    private void renameFile(final File f) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(f.getName());
        input.setSelection(input.getText().length());
        AcDialog.builder(this)
                .setTitle("重命名")
                .setView(input)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        final String name = ExportHelper.sanitizeName(input.getText().toString());
                        if (name.isEmpty()) {
                            AcToast.show(ToolsActivity.this, "文件名不能为空");
                            return;
                        }
                        final File target = new File(exportsDir(), name);
                        if (!target.equals(f) && target.exists()) {
                            // rename(2) 会静默覆盖已存在的目标文件，先确认避免数据丢失
                            AcDialog.builder(ToolsActivity.this)
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
            AcToast.show(ToolsActivity.this, "重命名失败");
        }
        refreshFiles();
    }

    private void editFile(final File f) {
        if (guardImage(f)) return;
        // 读写全部交给 FileActions：与本页原先的实现等价，只是不再有两份
        FileActions.editText(this, f, new Runnable() {
            public void run() { refreshFiles(); }
        });
    }

    private void previewFile(final File f) {
        if (guardImage(f)) return;
        // html / svg 在本页叠预览（不再 CLEAR_TOP 回主界面），其余进 JS 运行器
        FileActions.previewCode(this, viewer, f);
    }

    private void deleteFile(final File f) {
        AcDialog.builder(this)
                .setTitle("删除 " + f.getName() + " ?")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        boolean ok = f.delete();
                        LogUtil.i(TAG, "deleteFile: %s ok=%s", f.getName(), ok);
                        if (!ok) {
                            AcToast.show(ToolsActivity.this, "删除失败");
                        }
                        // 导出目录变了，存储管理页的缓存作废，否则返回后仍显示旧体积
                        StorageActivity.invalidateCache();
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
            AcToast.show(this, "没有可用的已保存代码");
            return;
        }
        final String[] names = new String[files.size()];
        for (int i = 0; i < files.size(); i++) {
            names[i] = files.get(i).getName();
        }
        AcDialog.builder(this)
                .setTitle("选择代码文件")
                .setItems(names, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        final File picked = files.get(which);
                        // 读文件放到后台线程，避免选择文件时阻塞主线程
                        new Thread(new Runnable() {
                            public void run() {
                                final String content = readFile(picked);
                                runOnUiThread(new Runnable() {
                                    public void run() {
                                        if (isFinishing() || isDestroyed()) return;
                                        if (content != null) {
                                            loadingFile = true;
                                            jsInput.setText(content);
                                            loadingFile = false;
                                            currentJsFileName = picked.getName();
                                        }
                                    }
                                });
                            }
                        }, "aichat-pickFile").start();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void pasteIntoInput() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null) {
            AcToast.show(this, "剪贴板为空");
            return;
        }
        ClipData clip = cm.getPrimaryClip();
        if (clip.getItemCount() == 0) {
            AcToast.show(this, "剪贴板为空");
            return;
        }
        CharSequence text = clip.getItemAt(0).coerceToText(this);
        jsInput.setText(text == null ? "" : text.toString());
        currentJsFileName = "";
    }

    private void runJs(String code) {
        if (code == null || code.trim().isEmpty()) {
            AcToast.show(this, "请输入要运行的 JS 代码");
            return;
        }
        LogUtil.i(TAG, "runJs: len=%d", code.length());
        String lang = currentJsFileName == null || currentJsFileName.isEmpty()
                ? sniffLang(code) : extOf(currentJsFileName);
        startActivity(MainActivity.newJsRunnerIntent(this, code, ExportHelper.extForLang(lang)));
    }

    // ---------- env prompt ----------

    /**
     * 环境提示词已改由设置页维护，这里只负责取「已填充占位符」的最终文本。
     * 原来写死成「Android 4.4 / Chromium 30」，换一台设备告诉模型的环境就是错的，
     * 生成的代码可能用上旧内核不支持的语法。
     */
    private String envPrompt() {
        return SettingsActivity.getJsEnvPrompt(this);
    }

    private void copyEnvPrompt() {
        copyToClipboard(envPrompt());
    }

    private void sendEnvPrompt(boolean send) {
        LogUtil.i(TAG, "sendEnvPrompt: send=%s", send);
        AcToast.show(this, send ? "已发送环境提示词" : "已插入环境提示词");
        startActivity(MainActivity.newPromptIntent(this, envPrompt(), send));
        finish();
    }

    private void copyToClipboard(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("aichat", text));
            AcToast.show(this, "已复制到剪贴板");
        }
    }
}
