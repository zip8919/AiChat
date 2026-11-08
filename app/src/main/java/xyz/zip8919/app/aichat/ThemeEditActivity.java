package xyz.zip8919.app.aichat;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * F4 主题新建 / 编辑页（交互方案 7、实现方案 8.4）。
 *
 * <p>明暗两套 CSS 分别编辑，切换时自动 collect 暂存当前一套。生成提示词与
 * 一键导入刻意解耦：只把提示词复制/带到聊天页，不自动发送、不新建会话、
 * 不期待任何页面回传（实现方案 §9）。
 */
public class ThemeEditActivity extends ThemedActivity {

    private static final int REQ_IMPORT = 1003;

    private ThemeRepository repo;
    private Theme t;
    private boolean darkMode;
    private String originalJson;

    private TextView title;
    private EditText nameEdit, authorEdit, descEdit, cssEdit;
    private Button btnLight, btnDark;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_theme_edit);
        repo = ThemeRepository.getInstance();
        bindViews();
        loadTheme(getIntent().getStringExtra("theme_id"));
        refreshFields();
        updateModeButtons();
        snapshot();
        wire();
    }

    private void bindViews() {
        title = (TextView) findViewById(R.id.edit_title);
        nameEdit = (EditText) findViewById(R.id.edit_name);
        authorEdit = (EditText) findViewById(R.id.edit_author);
        descEdit = (EditText) findViewById(R.id.edit_desc);
        cssEdit = (EditText) findViewById(R.id.edit_css);
        btnLight = (Button) findViewById(R.id.btn_mode_light);
        btnDark = (Button) findViewById(R.id.btn_mode_dark);
    }

    private void loadTheme(String id) {
        Theme found = id == null ? null : repo.find(id);
        if (found == null || found.builtin || found.fileMissing || found.versionIncompatible) {
            t = Theme.blank();
        } else {
            t = found.copy();
        }
        darkMode = false;
    }

    private void refreshFields() {
        nameEdit.setText(t.name);
        authorEdit.setText(t.author);
        descEdit.setText(t.description);
        cssEdit.setText(darkMode ? t.darkCss : t.lightCss);
        title.setText(t.id.length() == 0
                ? "新建主题" : "编辑主题 · " + t.name);
    }

    private void updateModeButtons() {
        // 用「● / ○」文字标记当前套别，不靠颜色，深浅主题下都能辨认
        btnLight.setText(darkMode ? "○ 浅色" : "● 浅色");
        btnDark.setText(darkMode ? "● 深色" : "○ 深色");
    }

    private void collect() {
        t.name = nameEdit.getText().toString();
        t.author = authorEdit.getText().toString();
        t.description = descEdit.getText().toString();
        String css = cssEdit.getText().toString();
        if (darkMode) t.darkCss = css;
        else t.lightCss = css;
    }

    private void snapshot() {
        try {
            originalJson = t.toJson();
        } catch (Exception e) {
            originalJson = null;
        }
    }

    private boolean isDirty() {
        collect();
        try {
            return originalJson == null || !t.toJson().equals(originalJson);
        } catch (Exception e) {
            return true;
        }
    }

    private void wire() {
        btnLight.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { switchMode(false); }
        });
        btnDark.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { switchMode(true); }
        });
        findViewById(R.id.btn_import).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickImport(); }
        });
        findViewById(R.id.btn_clear).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { confirmClear(); }
        });
        findViewById(R.id.btn_paste).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pasteFromClipboard(); }
        });
        findViewById(R.id.btn_save_as).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { saveAs(); }
        });
        findViewById(R.id.btn_save).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { save(); }
        });
        findViewById(R.id.btn_prompt).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startPromptFlow(); }
        });
    }

    private void switchMode(boolean toDark) {
        if (toDark == darkMode) return;
        collect();                 // 先暂存当前一套，再切到另一套
        darkMode = toDark;
        cssEdit.setText(darkMode ? t.darkCss : t.lightCss);
        updateModeButtons();
    }

    // ---- 保存 / 另存为 ----

    private void save() {
        collect();
        String n = t.name == null ? "" : t.name.trim();
        if (n.length() == 0) { toast("名称不能为空"); return; }
        t.name = n.length() > 32 ? n.substring(0, 32) : n;
        String id = repo.save(t);
        if (id == null) { toast("保存失败：存储不可写"); return; }
        t.id = id;
        repo.invalidate();
        snapshot();
        refreshFields();
        toast("已保存主题「" + t.name + "」");
    }

    private void saveAs() {
        promptName("另存为", t.name + " 副本", new NameCb() {
            public void onName(String n) {
                collect();
                t.name = n;
                t.id = "";
                t.builtin = false;
                t.filePath = null;
                String id = repo.save(t);
                if (id == null) { toast("另存失败：存储不可写"); return; }
                t.id = id;
                repo.invalidate();
                snapshot();
                refreshFields();
                toast("已另存为「" + n + "」");
            }
        });
    }

    // ---- 导入 / 清空 / 粘贴 ----

    private void pickImport() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(i, REQ_IMPORT);
        } catch (Throwable e) {
            toast("无法打开文件选择器");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_IMPORT || res != RESULT_OK || data == null || data.getData() == null) return;
        final String text = readText(data.getData());
        if (text == null) { toast("读取失败或文件超过 64KB"); return; }
        final ThemeValidator.Result vr =
                ThemeValidator.validate(text, FontRepository.getInstance().installedIds());
        if (vr.blocked || vr.theme == null) {
            toast("导入失败：" + vr.blockReason);
            return;
        }
        confirmOverwrite(new Runnable() {
            public void run() {
                fillFromTheme(vr.theme);
                warnIfNeeded(vr);
                toast("已填入当前编辑区，尚未保存");
            }
        });
    }

    private void confirmClear() {
        AcDialog.builder(this)
                .setTitle("清空")
                .setMessage("清空两套 CSS 与颜色 token？名称、作者会保留。此操作需二次确认。")
                .setPositiveButton("清空", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        collect();
                        t.lightCss = "";
                        t.darkCss = "";
                        t.lightTokens.clear();
                        t.darkTokens.clear();
                        cssEdit.setText("");
                        toast("已清空 CSS 与 token");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void pasteFromClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || cm.getPrimaryClip() == null
                || cm.getPrimaryClip().getItemCount() == 0) {
            toast("剪贴板为空");
            return;
        }
        CharSequence cs = cm.getPrimaryClip().getItemAt(0).getText();
        if (cs == null) { toast("剪贴板里没有文本"); return; }
        // 保留原始文本：解析失败时「作为 CSS 填入」应回填用户真正粘贴的内容。
        // 围栏/解释文字/注释等的统一清洗交给 ThemeValidator → ThemeText.clean()。
        final String text = cs.toString();
        final ThemeValidator.Result vr =
                ThemeValidator.validate(text, FontRepository.getInstance().installedIds());
        if (vr.blocked || vr.theme == null) {
            // 非法内容先确认再覆盖，不静默吞掉
            AcDialog.builder(this)
                    .setTitle("不是合法的 .actheme")
                    .setMessage("剪贴板内容无法解析为主题。仍要把它当作当前明暗套的 CSS 文本填入吗？")
                    .setPositiveButton("作为 CSS 填入", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            cssEdit.setText(text);
                            toast("已填入 CSS 文本（原始粘贴内容）");
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        confirmOverwrite(new Runnable() {
            public void run() {
                fillFromTheme(vr.theme);
                warnIfNeeded(vr);
                toast("已粘贴并填入当前编辑区");
            }
        });
    }

    private void confirmOverwrite(final Runnable action) {
        AcDialog.builder(this)
                .setTitle("覆盖当前编辑内容")
                .setMessage("导入/粘贴会覆盖当前的名称、作者、描述与两套 CSS，且不会自动落盘。确定继续？")
                .setPositiveButton("覆盖", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { action.run(); }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 只拷内容，不拷运行时元数据（id / builtin / filePath / 缺失标记）。 */
    private void fillFromTheme(Theme src) {
        t.name = src.name;
        t.author = src.author;
        t.description = src.description;
        t.fontUi = src.fontUi;
        t.fontMono = src.fontMono;
        t.hasLight = src.hasLight;
        t.hasDark = src.hasDark;
        t.lightTokens.clear();
        t.lightTokens.putAll(src.lightTokens);
        t.darkTokens.clear();
        t.darkTokens.putAll(src.darkTokens);
        t.lightCss = src.lightCss;
        t.darkCss = src.darkCss;
        refreshFields();
    }

    private void warnIfNeeded(ThemeValidator.Result vr) {
        if (vr.warnings.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < vr.warnings.size(); i++) {
            if (i > 0) sb.append("\n");
            sb.append("· ").append(vr.warnings.get(i));
        }
        toast("校验提示\n" + sb.toString());
    }

    private String readText(Uri uri) {
        InputStream in = null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            in = getContentResolver().openInputStream(uri);
            if (in == null) return null;
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > Theme.MAX_FILE_BYTES + 1) return null;
            }
            return new String(out.toByteArray(), "UTF-8");
        } catch (Throwable e) {
            return null;
        } finally {
            if (in != null) {
                try { in.close(); } catch (Throwable ignored) {}
            }
        }
    }

    // ---- 生成提示词（六步，解耦） ----

    private void startPromptFlow() {
        final EditText et = new EditText(this);
        et.setHint("例如：暖白纸墨风、暗色低饱和、适合夜间阅读");
        et.setMinLines(2);
        final AlertDialog d = AcDialog.builder(this)
                .setTitle("你想要什么样的主题？")
                .setView(et)
                .setPositiveButton("生成", null)
                .setNegativeButton("取消", null)
                .create();
        d.setOnShowListener(new DialogInterface.OnShowListener() {
            public void onShow(DialogInterface di) {
                d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        String desc = et.getText().toString().trim();
                        if (desc.length() == 0) {
                            toast("请先描述想要的主题");
                            return;   // 不 dismiss，空描述不允许继续
                        }
                        d.dismiss();
                        showPrompt(buildPrompt(desc));
                    }
                });
            }
        });
        d.show();
    }

    /**
     * 默认提示词：先把「这个软件是什么、界面由什么构成、能改什么」讲清楚，
     * 再给真实选择器清单与可导入的完整示例，让 AI 像读过源码一样设计 CSS。
     *
     * <p>早期版本只说「我要一个 .actheme」，模板里 css 还是空串并明令「只能写颜色、
     * 不要改布局」，结果 AI 一律只吐颜色、CSS 留空，而且输出 ```json 导致导入失败。
     * 现在：明确支持形状/样式（方形搜索卡片、代码卡片特定样式）、css 必须非空、
     * 输出约束写死，配合 ThemeValidator 的容错清洗做到「生成即可导入」。
     */
    private String buildPrompt(String desc) {
        StringBuilder p = new StringBuilder();
        p.append("你在为一个 AI 聊天 Android 应用编写主题文件 .actheme。下面这段是该应用的源码摘要，")
         .append("请当作你已经读过它的代码，据此设计主题。\n\n");

        p.append("【一、这是什么软件、界面由什么构成】\n")
         .append("一个运行在 Android 4.4（API 18/19）上的 AI 对话客户端，屏幕很窄（258dp 宽）。\n")
         .append("· 原生界面：顶部有模型选择、思考等级、扫描按钮；下面是消息区；底部是输入框。")
         .append("按钮、卡片、顶部栏、输入框的颜色由 16 个 token 控制，CSS 不影响它们。\n")
         .append("· 消息区：不是原生控件，而是一个 WebView，把 AI 回复的 Markdown 渲染成 HTML。")
         .append("内核是 Chromium 30，不支持 CSS 变量 var()、flex、gap、object-fit；")
         .append("请用传统写法（float / inline-block / border / border-radius / background）。\n")
         .append("· 主题分 light（浅色）与 dark（深色）两套，用户可随时切换。\n\n");

        p.append("【二、主题能改什么（不只是颜色）】\n")
         .append("1) tokens：16 个颜色值，控制原生界面与消息区基底色。\n")
         .append("2) css：作用于消息 WebView 的任意 CSS。除了颜色，还可以改【形状与样式】：")
         .append("border-radius（写 0 就是方形）、border（粗细/实线虚线/颜色）、background（纯色或渐变）、")
         .append("padding、margin、box-shadow、font-size、font-family、font-weight、letter-spacing、")
         .append("line-height、text-shadow、opacity 等。\n")
         .append("   例：「搜索卡片变成棕色方形」→ .tool-card{border-radius:0;background:#6D4C41;}\n")
         .append("   例：「代码卡片用虚线边框的特定样式」→ pre{border:1px dashed #8D6E63;border-radius:2px;}\n")
         .append("3) fonts：ui / mono 字体引用，只能填 system 或已安装字体 id，不要内嵌字体文件。\n\n");

        p.append("【三、消息 WebView 的真实 CSS 选择器（照这些名字写，别自己编）】\n")
         .append("· 页面/消息：body, .msg, .msg.user, .msg.user .bubble, .msg.user.failed .bubble, ")
         .append(".msg.ai, .msg.ai .content, .msg-meta, .msg-meta-sub, .msg-menu-btn, .mdot, #older-hint\n")
         .append("· Markdown：h1~h6, p, li, ul, ol, blockquote, a, strong, em, hr, mark, del, s, ")
         .append("sup, sub, img, svg, table, th, td, .table-scroll, .svg-scroll\n")
         .append("· 代码：pre, pre code, code, pre .lang-tag, pre .copy-btn, pre .save-btn, ")
         .append("pre .import-btn, pre .preview-btn, pre .run-btn, ")
         .append(".tk-kw, .tk-str, .tk-cmt, .tk-num, .tk-type, .tk-fn, .tk-op\n")
         .append("· 搜索/工具卡片：.tool-card, .tool-card-head, .tool-item, .tool-title, .tool-meta, ")
         .append(".tool-summary, .tool-arrow, .tool-hint, .tool-status, .tool-trim, .tool-fold, ")
         .append(".tool-imgs, .tool-img-cell, .tool-fetch-content, .tool-error, .tool-empty, .tool-body\n")
         .append("· 思考块/流式：.thinking-block, .thinking-toggle, .stream-tail, .stream-tail.plain\n")
         .append("· 来源/脚注：.sources-block, .sources-head, .sources-body, .src-item, .src-arrow, ")
         .append(".fn-ref, .fn-def\n")
         .append("· 附件：.attach-list, .attach-item, .attach-ico, .attach-thumb, .attach-body, ")
         .append(".attach-name, .attach-sub, .attach-cut, .attach-view\n")
         .append("· 错误/重试：.error-card, .err-title, .err-summary, .err-actions, .err-btn, ")
         .append(".err-btn.retry, .retry-btn, .retry-reason\n")
         .append("· 数学：.math-block, .math-inline\n\n");

        p.append("【四、16 个 token（值只能是 #RRGGBB）】\n")
         .append("ac_primary 顶部栏/主按钮；ac_primary_dark 按压态；ac_accent 强调/引用边；")
         .append("ac_bg 页面底；ac_surface 卡片/输入框表面；ac_text 主文字；")
         .append("ac_text_secondary 次要文字；ac_divider 分隔线；ac_bubble_user 用户气泡；")
         .append("ac_bubble_ai AI 消息；ac_danger 错误；ac_track 轨道/高亮；")
         .append("ac_code_bg 代码底；ac_code_text 代码字；ac_link 链接；ac_border 边框。\n\n");

        p.append("【五、我的主题需求】\n").append(desc).append("\n\n");

        p.append("【六、输出格式（必须严格遵守，否则无法导入）】\n")
         .append("只输出一个 ```actheme 代码块，代码块内是纯 JSON，不要任何解释文字。\n")
         .append("结构：\n")
         .append("{\n")
         .append("  \"format\": \"actheme\",\n")
         .append("  \"version\": 1,\n")
         .append("  \"meta\": {\"name\": \"主题名\", \"author\": \"\", \"description\": \"\"},\n")
         .append("  \"fonts\": {\"ui\": \"system\", \"mono\": \"system\"},\n")
         .append("  \"light\": {\"tokens\": {...}, \"css\": \"...\"},\n")
         .append("  \"dark\":  {\"tokens\": {...}, \"css\": \"...\"}\n")
         .append("}\n")
         .append("硬性要求：\n")
         .append("1. format 必须是 actheme，version 必须是数字 1，meta.name 必填且不超过 32 字。\n")
         .append("2. light 与 dark 两套都要写完整；每套的 css 必须非空，且至少包含一条改形状/样式的规则")
         .append("（如 border-radius 或 border），不能只给颜色。\n")
         .append("3. css 是单行字符串：CSS 内部一律不要用双引号（需要引号时用单引号），")
         .append("不要写真实换行，多条规则直接连写。JSON 里的双引号只用于字段名和字符串值。\n")
         .append("4. JSON 里不要写注释（// 或 /* */），不要尾逗号，不要用单引号包字段名。\n")
         .append("5. 不要用 position:fixed、@import、url(http...)、javascript:；")
         .append("不要给 body/pre/table/img 设固定像素 width、overflow-x、word-wrap、")
         .append("white-space:nowrap（会撑破 258dp 窄屏）。\n")
         .append("6. 只使用第三节列出的选择器。请输出完整闭合的 JSON，不要截断。\n\n");

        p.append("【七、完整示例（示范「棕色方形搜索卡片 + 代码卡片特定样式」，仅供格式参考，不要照抄配色）】\n")
         .append("```actheme\n")
         .append("{\"format\":\"actheme\",\"version\":1,")
         .append("\"meta\":{\"name\":\"棕纸示例\",\"author\":\"\",\"description\":\"搜索卡片方形、代码卡片虚线框\"},")
         .append("\"fonts\":{\"ui\":\"system\",\"mono\":\"system\"},")
         .append("\"light\":{\"tokens\":{\"ac_primary\":\"#6D4C41\",\"ac_primary_dark\":\"#4E342E\",")
         .append("\"ac_accent\":\"#A1887F\",\"ac_bg\":\"#FAF6EF\",\"ac_surface\":\"#FFFDF7\",")
         .append("\"ac_text\":\"#3E2723\",\"ac_text_secondary\":\"#795548\",\"ac_divider\":\"#E0D7C8\",")
         .append("\"ac_bubble_user\":\"#EFE6D8\",\"ac_bubble_ai\":\"#FFFDF7\",\"ac_link\":\"#8D6E63\",")
         .append("\"ac_border\":\"#D7C4B5\",\"ac_code_bg\":\"#F0E7DA\",\"ac_code_text\":\"#3E2723\"},")
         .append("\"css\":\".tool-card{border-radius:0;background:#6D4C41;border:1px solid #4E342E;}")
         .append(".tool-card-head{background:#5D4037;color:#FFF3E0;}")
         .append(".tool-title,.tool-status{color:#FFE0B2;}")
         .append("pre{border-radius:2px;border:1px dashed #8D6E63;background:#F0E7DA;}\"},")
         .append("\"dark\":{\"tokens\":{\"ac_primary\":\"#A1887F\",\"ac_primary_dark\":\"#5D4037\",")
         .append("\"ac_accent\":\"#BCAAA4\",\"ac_bg\":\"#241C18\",\"ac_surface\":\"#2E241F\",")
         .append("\"ac_text\":\"#EFEBE9\",\"ac_text_secondary\":\"#BCAAA4\",\"ac_divider\":\"#4E342E\",")
         .append("\"ac_bubble_user\":\"#3E2F28\",\"ac_bubble_ai\":\"#2E241F\",\"ac_link\":\"#BCAAA4\",")
         .append("\"ac_border\":\"#4E342E\",\"ac_code_bg\":\"#1E1714\",\"ac_code_text\":\"#EFEBE9\"},")
         .append("\"css\":\".tool-card{border-radius:0;background:#4E342E;border:1px solid #8D6E63;}")
         .append(".tool-card-head{background:#3E2F28;color:#EFEBE9;}")
         .append(".tool-title,.tool-status{color:#D7CCC8;}")
         .append("pre{border-radius:2px;border:1px dashed #8D6E63;background:#1E1714;}\"}}\n")
         .append("```\n");
        return p.toString();
    }

    /**
     * 生成后交互对齐扫描页 / 环境提示词：插入到输入框、复制、直接发送三选一，
     * 复用 MainActivity.newPromptIntent 的 EXTRA_PROMPT / EXTRA_PROMPT_SEND 协议。
     */
    private void showPrompt(final String prompt) {
        final String[] items = {
                "插入到输入框 — 先改再发",
                "复制提示词 — 贴到别处",
                "直接发送给 AI — 回聊天页发出"
        };
        AcDialog.builder(this)
                .setTitle("提示词已生成")
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            startActivity(MainActivity.newPromptIntent(
                                    ThemeEditActivity.this, prompt, false));
                        } else if (which == 1) {
                            copyToClipboard(prompt);
                            toast("提示词已复制");
                        } else {
                            startActivity(MainActivity.newPromptIntent(
                                    ThemeEditActivity.this, prompt, true));
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void copyToClipboard(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("actheme-prompt", text));
        } catch (Throwable ignored) {
        }
    }

    // ---- 离开保护 ----

    @Override
    public void onBackPressed() {
        if (!isDirty()) {
            super.onBackPressed();
            return;
        }
        AcDialog.builder(this)
                .setTitle("有未保存的编辑")
                .setItems(new String[]{"放弃编辑", "保存并返回", "继续编辑"},
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                if (w == 0) finish();
                                else if (w == 1) {
                                    save();
                                    finish();
                                }
                            }
                        })
                .show();
    }

    // ---- 通用 ----

    private interface NameCb {
        void onName(String name);
    }

    private void promptName(String titleText, String initial, final NameCb cb) {
        final EditText et = new EditText(this);
        et.setText(initial == null ? "" : initial);
        et.setSingleLine(true);
        et.setSelection(et.getText().length());
        AcDialog.builder(this)
                .setTitle(titleText)
                .setView(et)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String n = et.getText().toString().trim();
                        if (n.length() == 0) { toast("名称不能为空"); return; }
                        if (n.length() > 32) n = n.substring(0, 32);
                        cb.onName(n);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
