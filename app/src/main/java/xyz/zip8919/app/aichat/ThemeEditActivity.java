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
    /** 上次填的主题需求：「返回改需求」时回填，避免重打。 */
    private String lastDesc = "";
    /** 刚从属性编辑页返回，onResume 需要补一次标题刷新。 */
    private boolean expectTokenReturn;

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
                ? Strings.get(R.string.themeeditactivity_001) : Strings.get(R.string.themeeditactivity_002) + t.name);
    }

    private void updateModeButtons() {
        // 用「● / ○」文字标记当前套别，不靠颜色，深浅主题下都能辨认
        btnLight.setText(darkMode ? Strings.get(R.string.themeeditactivity_003) : Strings.get(R.string.themeeditactivity_004));
        btnDark.setText(darkMode ? Strings.get(R.string.themeeditactivity_005) : Strings.get(R.string.themeeditactivity_006));
    }

    private void collect() {
        t.name = nameEdit.getText().toString();
        t.author = authorEdit.getText().toString();
        t.description = descEdit.getText().toString();
        String css = cssEdit.getText().toString();
        // hasDark / hasLight 必须跟着置位：Theme.toJson() 是按这两个标志决定要不
        // 要输出对应分组的。导入一个只有浅色的主题、在编辑页切到深色并保存，
        // 若这里只赋 darkCss 不置 hasDark，刚写的一整套深色样式会被静默丢掉。
        if (darkMode) {
            t.darkCss = css;
            if (css != null && css.trim().length() > 0) t.hasDark = true;
        } else {
            t.lightCss = css;
            if (css != null && css.trim().length() > 0) t.hasLight = true;
        }
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
        findViewById(R.id.btn_edit_tokens).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openTokenEditor(); }
        });
    }

    /**
     * 进入逐属性编辑。
     *
     * <p>用静态引用传内存对象，不走 Intent：主题含两套 map 与 CSS，序列化既慢又容易
     * 在 64KB 事务上限边缘翻车。编辑结果直接落在这个 {@code t} 上，
     * 返回后 {@link #isDirty()} 会自然发现变化。
     */
    private void openTokenEditor() {
        collect();   // 先把名称/作者/CSS 同步进 t，避免编辑页看到过期的名字
        expectTokenReturn = true;
        ThemeAttrActivity.sTheme = t;
        Intent i = new Intent(this, ThemeAttrActivity.class);
        // 显式标出模式：静态引用是「一次性」的（目标页读完就置空），
        // 连点两次时第二次会拿到 null，有这个标记就能识别并安全退出，
        // 而不是悄悄退化成「外壳覆盖模式」让用户以为在改主题。
        i.putExtra("theme_mode", true);
        startActivity(i);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!expectTokenReturn) return;
        expectTokenReturn = false;
        // 从属性页返回：tokens 变了但界面元素没变。先 collect 保住用户正在输入的 CSS，
        // 再刷新标题（名称可能在别处改过）。
        collect();
        title.setText(t.id.length() == 0 ? Strings.get(R.string.themeeditactivity_007) : Strings.get(R.string.themeeditactivity_008) + t.name);
        // 属性页里的预览是临时的（退出即收回），这里必须讲清楚，否则用户会以为没生效
        AcToast.show(this, Strings.get(R.string.themeeditactivity_009));
    }

    private void switchMode(boolean toDark) {
        if (toDark == darkMode) return;
        collect();                 // 先暂存当前一套，再切到另一套
        darkMode = toDark;
        cssEdit.setText(darkMode ? t.darkCss : t.lightCss);
        updateModeButtons();
    }

    // ---- 保存 / 另存为 ----

    private boolean save() {
        collect();
        String n = t.name == null ? "" : t.name.trim();
        if (n.length() == 0) { toast(Strings.get(R.string.themeeditactivity_010)); return false; }
        t.name = n.length() > 32 ? n.substring(0, 32) : n;
        String id = repo.save(t);
        if (id == null) { toast(Strings.get(R.string.themeeditactivity_011)); return false; }
        t.id = id;
        repo.invalidate();
        snapshot();
        refreshFields();
        toast(Strings.get(R.string.themeeditactivity_012) + t.name + "」");
        return true;
    }

    private void saveAs() {
        promptName(Strings.get(R.string.themeeditactivity_013), t.name + Strings.get(R.string.themeeditactivity_014), new NameCb() {
            public void onName(String n) {
                collect();
                t.name = n;
                t.id = "";
                t.builtin = false;
                t.filePath = null;
                String id = repo.save(t);
                if (id == null) { toast(Strings.get(R.string.themeeditactivity_015)); return; }
                t.id = id;
                repo.invalidate();
                snapshot();
                refreshFields();
                toast(Strings.get(R.string.themeeditactivity_016) + n + "」");
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
            toast(Strings.get(R.string.themeeditactivity_017));
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_IMPORT || res != RESULT_OK || data == null || data.getData() == null) return;
        final String text = readText(data.getData());
        if (text == null) { toast(Strings.get(R.string.themeeditactivity_018)); return; }
        final ThemeValidator.Result vr =
                ThemeValidator.validate(text, FontRepository.getInstance().installedIds());
        if (vr.blocked || vr.theme == null) {
            toast(Strings.get(R.string.themeeditactivity_019) + vr.blockReason);
            return;
        }
        confirmOverwrite(new Runnable() {
            public void run() {
                fillFromTheme(vr.theme);
                warnIfNeeded(vr);
                toast(Strings.get(R.string.themeeditactivity_020));
            }
        });
    }

    private void confirmClear() {
        AcDialog.builder(this)
                .setTitle(Strings.get(R.string.themeeditactivity_021))
                .setMessage(Strings.get(R.string.themeeditactivity_022))
                .setPositiveButton(Strings.get(R.string.themeeditactivity_023), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        collect();
                        t.lightCss = "";
                        t.darkCss = "";
                        t.lightTokens.clear();
                        t.darkTokens.clear();
                        cssEdit.setText("");
                        toast(Strings.get(R.string.themeeditactivity_024));
                    }
                })
                .setNegativeButton(Strings.get(R.string.themeeditactivity_025), null)
                .show();
    }

    private void pasteFromClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || cm.getPrimaryClip() == null
                || cm.getPrimaryClip().getItemCount() == 0) {
            toast(Strings.get(R.string.themeeditactivity_026));
            return;
        }
        CharSequence cs = cm.getPrimaryClip().getItemAt(0).getText();
        if (cs == null) { toast(Strings.get(R.string.themeeditactivity_027)); return; }
        // 保留原始文本：解析失败时「作为 CSS 填入」应回填用户真正粘贴的内容。
        // 围栏/解释文字/注释等的统一清洗交给 ThemeValidator → ThemeText.clean()。
        final String text = cs.toString();
        final ThemeValidator.Result vr =
                ThemeValidator.validate(text, FontRepository.getInstance().installedIds());
        if (vr.blocked || vr.theme == null) {
            // 非法内容先确认再覆盖，不静默吞掉
            AcDialog.builder(this)
                    .setTitle(Strings.get(R.string.themeeditactivity_028))
                    .setMessage(Strings.get(R.string.themeeditactivity_029))
                    .setPositiveButton(Strings.get(R.string.themeeditactivity_030), new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            cssEdit.setText(text);
                            toast(Strings.get(R.string.themeeditactivity_031));
                        }
                    })
                    .setNegativeButton(Strings.get(R.string.themeeditactivity_032), null)
                    .show();
            return;
        }
        confirmOverwrite(new Runnable() {
            public void run() {
                fillFromTheme(vr.theme);
                warnIfNeeded(vr);
                toast(Strings.get(R.string.themeeditactivity_033));
            }
        });
    }

    private void confirmOverwrite(final Runnable action) {
        AcDialog.builder(this)
                .setTitle(Strings.get(R.string.themeeditactivity_034))
                .setMessage(Strings.get(R.string.themeeditactivity_035))
                .setPositiveButton(Strings.get(R.string.themeeditactivity_036), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { action.run(); }
                })
                .setNegativeButton(Strings.get(R.string.themeeditactivity_037), null)
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
        toast(Strings.get(R.string.themeeditactivity_038) + sb.toString());
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
        startPromptFlow(null);
    }

    /** @param initial 非空则回填上次输入：「返回改需求」不该让用户重打一遍 */
    private void startPromptFlow(String initial) {
        final EditText et = new EditText(this);
        et.setHint(Strings.get(R.string.themeeditactivity_039));
        et.setMinLines(2);
        if (initial != null && initial.length() > 0) {
            et.setText(initial);
            et.setSelection(initial.length());
        }
        final AlertDialog d = AcDialog.builder(this)
                .setTitle(Strings.get(R.string.themeeditactivity_040))
                .setView(et)
                .setPositiveButton(Strings.get(R.string.themeeditactivity_041), null)
                .setNegativeButton(Strings.get(R.string.themeeditactivity_042), null)
                .create();
        AcDialog.onShow(d, new DialogInterface.OnShowListener() {
            public void onShow(DialogInterface di) {
                d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        String desc = et.getText().toString().trim();
                        if (desc.length() == 0) {
                            toast(Strings.get(R.string.themeeditactivity_043));
                            return;   // 不 dismiss，空描述不允许继续
                        }
                        d.dismiss();
                        lastDesc = desc;
                        showPromptPreview(desc, ThemePromptStore.render(ThemeEditActivity.this, desc));
                    }
                });
            }
        });
        d.show();
    }

    // 提示词模板已移到设置：ThemePromptStore 负责读取、填 {desc} 与设备类占位符。
    // 内置默认模板与原硬编码版本逐字一致，改过的用户也只会影响自己的输出。

    /**
     * 预览最终提示词，再决定下一步。
     *
     * <p>原来只有一个「已生成」的列表弹框，用户看不到正文，模板一旦被自定义过，
     * 发出去才发现不对，只能整个流程重来。现在给全文 + 「返回改需求」，
     * 描述回填到输入框，改完再生成。
     *
     * <p>动作沿用 {@link MainActivity#newPromptIntent} 的 EXTRA_PROMPT /
     * EXTRA_PROMPT_SEND 协议，插入/发送两条路与之前完全一致。
     */
    private void showPromptPreview(final String desc, final String prompt) {
        View panel = getLayoutInflater().inflate(R.layout.dialog_prompt_preview, null);
        TextView meta = (TextView) panel.findViewById(R.id.preview_meta);
        TextView body = (TextView) panel.findViewById(R.id.preview_text);
        // 300dp 写死在布局里，小屏（480dp 高）会把按钮顶出屏幕，这里按屏高收一收。
        View scroll = panel.findViewById(R.id.preview_scroll);
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.42f);
        if (maxH > 0 && scroll.getLayoutParams().height > maxH) {
            scroll.getLayoutParams().height = maxH;
            scroll.requestLayout();
        }
        body.setText(prompt);
        meta.setText(prompt.length() + Strings.get(R.string.themeeditactivity_044)
                + (ThemePromptStore.isCustomized(this) ? Strings.get(R.string.themeeditactivity_045) : Strings.get(R.string.themeeditactivity_046))
                + Strings.get(R.string.themeeditactivity_047));

        final AlertDialog d = AcDialog.builder(this)
                .setTitle(Strings.get(R.string.themeeditactivity_048))
                .setView(panel)
                .create();

        panel.findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                d.dismiss();
                startPromptFlow(desc);   // 回填描述，改完再生成
            }
        });
        panel.findViewById(R.id.btn_copy).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                copyToClipboard(prompt);
                toast(Strings.get(R.string.themeeditactivity_049));
            }
        });
        panel.findViewById(R.id.btn_insert).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                d.dismiss();
                startActivity(MainActivity.newPromptIntent(ThemeEditActivity.this, prompt, false));
            }
        });
        panel.findViewById(R.id.btn_send).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                d.dismiss();
                startActivity(MainActivity.newPromptIntent(ThemeEditActivity.this, prompt, true));
            }
        });
        d.show();
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
                .setTitle(Strings.get(R.string.themeeditactivity_050))
                .setItems(new String[]{Strings.get(R.string.themeeditactivity_051), Strings.get(R.string.themeeditactivity_052), Strings.get(R.string.themeeditactivity_053)},
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                if (w == 0) finish();
                                else if (w == 1) {
                                    if (save()) finish();   // 保存失败时保留页面，避免编辑内容丢失
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
                .setPositiveButton(Strings.get(R.string.themeeditactivity_054), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String n = et.getText().toString().trim();
                        if (n.length() == 0) { toast(Strings.get(R.string.themeeditactivity_055)); return; }
                        if (n.length() > 32) n = n.substring(0, 32);
                        cb.onName(n);
                    }
                })
                .setNegativeButton(Strings.get(R.string.themeeditactivity_056), null)
                .show();
    }

    private void toast(String s) {
        AcToast.show(this, s);
    }
}
