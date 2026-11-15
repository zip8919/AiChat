package xyz.zip8919.app.aichat;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * F3 管理自定义主题页（交互方案 6、实现方案 8.3）。
 *
 * <p>分组标题 + 单列表（不用 Tab，258dp 下 Tab 会挤掉标题）；内置项不参与批量勾选。
 * 单击走单列 AlertDialog.setItems，避免窄屏上横排 6 个按钮重叠。
 */
public class ThemeManagerActivity extends ThemedActivity {

    private static final int REQ_IMPORT = 1002;

    private ThemeRepository repo;
    private AppearanceManager am;
    private ManagerRowAdapter adapter;
    private final List<Theme> themes = new ArrayList<Theme>();
    private boolean selectionMode;
    private boolean firstResume = true;

    private TextView title, hint, empty, selectionCount;
    private ListView list;
    private View selectionBar;
    private Button btnPrimary, btnSecondary, btnMulti, btnAct1, btnAct2, btnAct3;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_manager);
        repo = ThemeRepository.getInstance();
        am = AppearanceManager.getInstance(this);
        bindViews();
        title.setText(Strings.get(R.string.thememanageractivity_001));
        hint.setText(Strings.get(R.string.thememanageractivity_002));
        btnPrimary.setText(Strings.get(R.string.thememanageractivity_003));
        btnSecondary.setText(Strings.get(R.string.thememanageractivity_004));
        btnMulti.setText(Strings.get(R.string.thememanageractivity_005));
        btnAct1.setText(Strings.get(R.string.thememanageractivity_006));
        btnAct2.setVisibility(View.GONE);   // 隐藏后两个可见按钮各占一半，不留空档
        btnAct3.setText(Strings.get(R.string.thememanageractivity_007));
        adapter = new ManagerRowAdapter(this);
        list.setAdapter(adapter);
        wire();
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (firstResume) {
            firstResume = false;
            return;
        }
        // 从编辑页 / 预览页返回时：save/delete/rename/copy 已经清过 cache，
        // list() 的目录签名也能发现外部改动，不必再强制重扫一遍磁盘。
        load();
    }

    private void bindViews() {
        title = (TextView) findViewById(R.id.manager_title);
        hint = (TextView) findViewById(R.id.manager_hint);
        empty = (TextView) findViewById(R.id.manager_empty);
        selectionCount = (TextView) findViewById(R.id.manager_selection_count);
        list = (ListView) findViewById(R.id.manager_list);
        selectionBar = findViewById(R.id.manager_selection_bar);
        btnPrimary = (Button) findViewById(R.id.btn_primary);
        btnSecondary = (Button) findViewById(R.id.btn_secondary);
        btnMulti = (Button) findViewById(R.id.btn_multi);
        btnAct1 = (Button) findViewById(R.id.btn_act1);
        btnAct2 = (Button) findViewById(R.id.btn_act2);
        btnAct3 = (Button) findViewById(R.id.btn_act3);
    }

    private void wire() {
        btnPrimary.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickTheme(); }
        });
        btnSecondary.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(ThemeManagerActivity.this, ThemeEditActivity.class));
            }
        });
        btnMulti.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                selectionMode = !selectionMode;
                adapter.setSelectionMode(selectionMode);
                btnMulti.setText(selectionMode ? Strings.get(R.string.thememanageractivity_008) : Strings.get(R.string.thememanageractivity_009));
                refreshSelectionUi();
            }
        });
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View v, int pos, long id) {
                ManagerRowAdapter.Row r = adapter.rowAt(pos);
                if (r == null || r.header) return;
                if (r.disabled) {
                    if (r.key == null) return;
                    toast(Strings.get(R.string.thememanageractivity_010));
                    return;
                }
                if (selectionMode) {
                    Theme t = findTheme(r.key);
                    if (t != null && t.builtin) {
                        toast(Strings.get(R.string.thememanageractivity_011));
                        return;
                    }
                    adapter.toggle(r.key);
                    refreshSelectionUi();
                    return;
                }
                Theme t = findTheme(r.key);
                if (t != null) showSingleActions(t);
            }
        });
        btnAct1.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { batchExport(); }
        });
        btnAct3.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { confirmBatchDelete(); }
        });
    }

    private void load() {
        themes.clear();
        themes.addAll(repo.list());
        List<ManagerRowAdapter.Row> rows = new ArrayList<ManagerRowAdapter.Row>();
        rows.add(ManagerRowAdapter.Row.header(Strings.get(R.string.thememanageractivity_012)));
        for (int i = 0; i < themes.size(); i++) {
            Theme t = themes.get(i);
            if (t.builtin) rows.add(themeRow(t));
        }
        rows.add(ManagerRowAdapter.Row.header(Strings.get(R.string.thememanageractivity_013)));
        int user = 0;
        for (int i = 0; i < themes.size(); i++) {
            Theme t = themes.get(i);
            if (!t.builtin) {
                rows.add(themeRow(t));
                user++;
            }
        }
        if (user == 0) {
            rows.add(ManagerRowAdapter.Row.item(null, Strings.get(R.string.thememanageractivity_014),
                    Strings.get(R.string.thememanageractivity_015), "", true));
        }
        adapter.setRows(rows);
        list.setVisibility(View.VISIBLE);
        empty.setVisibility(View.GONE);
        refreshSelectionUi();
    }

    private ManagerRowAdapter.Row themeRow(Theme t) {
        String badge;
        if (t.fileMissing) badge = Strings.get(R.string.thememanageractivity_016);
        else if (t.versionIncompatible) badge = Strings.get(R.string.thememanageractivity_017);
        else if (t.id.equals(am.getThemeId())) badge = Strings.get(R.string.thememanageractivity_073);
        else badge = t.builtin ? Strings.get(R.string.thememanageractivity_018) : Strings.get(R.string.thememanageractivity_019);
        String sub = (t.author == null ? "" : t.author);
        if (t.description != null && t.description.length() > 0) {
            sub = sub.length() > 0 ? sub + " · " + t.description : t.description;
        }
        return ManagerRowAdapter.Row.item(t.id, t.name, sub, badge,
                t.fileMissing || t.versionIncompatible);
    }

    private void refreshSelectionUi() {
        selectionBar.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        selectionCount.setText(Strings.get(R.string.thememanageractivity_020) + adapter.checkedKeys().size() + Strings.get(R.string.thememanageractivity_021));
    }

    private Theme findTheme(String id) {
        if (id == null) return null;
        for (int i = 0; i < themes.size(); i++) {
            if (id.equals(themes.get(i).id)) return themes.get(i);
        }
        return null;
    }

    // ---- 单项操作（单列列表） ----

    private void showSingleActions(final Theme t) {
        final List<String> labels = new ArrayList<String>();
        final List<Integer> codes = new ArrayList<Integer>();
        if (!t.builtin) {
            labels.add(Strings.get(R.string.thememanageractivity_022)); codes.add(Integer.valueOf(0));
            labels.add(Strings.get(R.string.thememanageractivity_023)); codes.add(Integer.valueOf(1));
        }
        labels.add(Strings.get(R.string.thememanageractivity_024)); codes.add(Integer.valueOf(2));
        labels.add(Strings.get(R.string.thememanageractivity_025)); codes.add(Integer.valueOf(3));
        labels.add(Strings.get(R.string.thememanageractivity_026)); codes.add(Integer.valueOf(4));
        if (!t.builtin) {
            labels.add(Strings.get(R.string.thememanageractivity_027)); codes.add(Integer.valueOf(5));
        }
        AcDialog.builder(this)
                .setTitle(t.name)
                .setItems(labels.toArray(new String[labels.size()]),
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                switch (codes.get(w).intValue()) {
                                    case 0: promptRename(t); break;
                                    case 1: openEdit(t); break;
                                    case 2: promptCopy(t); break;
                                    case 3: doExport(t); break;
                                    case 4: openPreview(t); break;
                                    case 5: confirmDeleteOne(t); break;
                                }
                            }
                        })
                .setNegativeButton(Strings.get(R.string.thememanageractivity_028), null)
                .show();
    }

    private void promptRename(final Theme t) {
        promptName(Strings.get(R.string.thememanageractivity_029), t.name, new NameCb() {
            public void onName(String n) {
                if (repo.rename(t.id, n)) {
                    repo.invalidate();
                    load();
                } else {
                    toast(Strings.get(R.string.thememanageractivity_030));
                }
            }
        });
    }

    private void promptCopy(final Theme t) {
        promptName(Strings.get(R.string.thememanageractivity_031), t.name + Strings.get(R.string.thememanageractivity_032), new NameCb() {
            public void onName(String n) {
                String id = repo.copy(t.id, n);
                if (id == null) { toast(Strings.get(R.string.thememanageractivity_033)); return; }
                repo.invalidate();
                load();
                toast(Strings.get(R.string.thememanageractivity_034) + n + "」");
            }
        });
    }

    private void doExport(Theme t) {
        String path = repo.export(t.id);
        toast(path == null ? Strings.get(R.string.thememanageractivity_035) : Strings.get(R.string.thememanageractivity_036) + path);
    }

    private void openEdit(Theme t) {
        Intent i = new Intent(this, ThemeEditActivity.class);
        i.putExtra("theme_id", t.id);
        startActivity(i);
    }

    private void openPreview(Theme t) {
        Intent i = new Intent(this, ThemePreviewActivity.class);
        i.putExtra("theme_id", t.id);
        startActivity(i);
    }

    private void confirmDeleteOne(final Theme t) {
        String msg = Strings.get(R.string.thememanageractivity_037) + t.name + Strings.get(R.string.thememanageractivity_038);
        if (t.id.equals(am.getThemeId())) {
            msg += Strings.get(R.string.thememanageractivity_039);
        }
        AcDialog.builder(this)
                .setTitle(Strings.get(R.string.thememanageractivity_040))
                .setMessage(msg)
                .setPositiveButton(Strings.get(R.string.thememanageractivity_041), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if (t.id.equals(am.getThemeId())) {
                            // 只解绑 themeId：applyPermanent 会把「临时生效」的改动一起写盘
                            am.detachTheme(t.id);
                        }
                        boolean ok = repo.delete(t.id);
                        load();
                        toast(ok ? Strings.get(R.string.thememanageractivity_042) : Strings.get(R.string.thememanageractivity_043));
                    }
                })
                .setNegativeButton(Strings.get(R.string.thememanageractivity_044), null)
                .show();
    }

    // ---- 批量 ----

    private void batchExport() {
        Set<String> keys = new HashSet<String>(adapter.checkedKeys());
        if (keys.isEmpty()) { toast(Strings.get(R.string.thememanageractivity_045)); return; }
        int ok = 0;
        for (String id : keys) {
            if (repo.export(id) != null) ok++;
        }
        toast(Strings.get(R.string.thememanageractivity_046) + ok + " / " + keys.size() + Strings.get(R.string.thememanageractivity_047));
    }

    private void confirmBatchDelete() {
        final Set<String> keys = new HashSet<String>(adapter.checkedKeys());
        if (keys.isEmpty()) { toast(Strings.get(R.string.thememanageractivity_048)); return; }
        final List<String> deletable = new ArrayList<String>();
        boolean currentHit = false;
        for (String id : keys) {
            Theme t = findTheme(id);
            if (t == null || t.builtin) continue;
            deletable.add(id);
            if (id.equals(am.getThemeId())) currentHit = true;
        }
        if (deletable.isEmpty()) { toast(Strings.get(R.string.thememanageractivity_049)); return; }
        final boolean hit = currentHit;
        String msg = Strings.get(R.string.thememanageractivity_050) + deletable.size() + Strings.get(R.string.thememanageractivity_051);
        if (hit) msg += Strings.get(R.string.thememanageractivity_052);
        AcDialog.builder(this)
                .setTitle(Strings.get(R.string.thememanageractivity_053))
                .setMessage(msg)
                .setPositiveButton(Strings.get(R.string.thememanageractivity_054), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if (hit) {
                            for (String id : deletable) am.detachTheme(id);
                        }
                        // 整批只读一次表：逐项 delete 会「删一个 → 签名失效 → 把剩下的全部重解析」
                        repo.deleteAll(deletable);
                        selectionMode = false;
                        adapter.setSelectionMode(false);
                        btnMulti.setText(Strings.get(R.string.thememanageractivity_055));
                        load();
                        toast(Strings.get(R.string.thememanageractivity_056) + deletable.size() + Strings.get(R.string.thememanageractivity_057));
                    }
                })
                .setNegativeButton(Strings.get(R.string.thememanageractivity_058), null)
                .show();
    }

    // ---- 导入 ----

    private void pickTheme() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(i, REQ_IMPORT);
        } catch (Throwable t) {
            toast(Strings.get(R.string.thememanageractivity_059));
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_IMPORT || res != RESULT_OK || data == null || data.getData() == null) return;
        final String text = readText(data.getData());
        if (text == null) {
            toast(Strings.get(R.string.thememanageractivity_060));
            return;
        }
        final ThemeValidator.Result vr =
                ThemeValidator.validate(text, FontRepository.getInstance().installedIds());
        if (vr.blocked || vr.theme == null) {
            toast(Strings.get(R.string.thememanageractivity_061) + vr.blockReason);
            return;
        }
        final Theme t = vr.theme;
        final StringBuilder warn = new StringBuilder();
        for (int i = 0; i < vr.warnings.size(); i++) {
            if (i > 0) warn.append("\n");
            warn.append("· ").append(vr.warnings.get(i));
        }
        promptName(Strings.get(R.string.thememanageractivity_062), t.name, new NameCb() {
            public void onName(String n) {
                if (hasUserThemeNamed(n)) {
                    confirmImportConflict(t, n, warn.toString());
                } else {
                    finishImport(t, n, warn.toString());
                }
            }
        });
    }

    private void confirmImportConflict(final Theme t, final String name, final String warn) {
        AcDialog.builder(this)
                .setTitle(Strings.get(R.string.thememanageractivity_063))
                .setItems(new String[]{Strings.get(R.string.thememanageractivity_064), Strings.get(R.string.thememanageractivity_065), Strings.get(R.string.thememanageractivity_066)},
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                if (w == 0) finishImport(t, name, warn);
                                else if (w == 1) finishImport(t, uniqueName(name), warn);
                            }
                        })
                .show();
    }

    private void finishImport(Theme t, String name, String warn) {
        t.name = name;
        t.id = "";             // 让 Repository 生成不重复的新 id
        t.builtin = false;
        String id = repo.save(t);
        if (id == null) { toast(Strings.get(R.string.thememanageractivity_067)); return; }
        repo.invalidate();
        load();
        toast(warn.length() > 0 ? Strings.get(R.string.thememanageractivity_068) + warn : Strings.get(R.string.thememanageractivity_069));
    }

    private boolean hasUserThemeNamed(String name) {
        List<Theme> all = repo.list();
        for (int i = 0; i < all.size(); i++) {
            Theme t = all.get(i);
            if (!t.builtin && name.equals(t.name)) return true;
        }
        return false;
    }

    private String uniqueName(String name) {
        for (int i = 2; i < 100; i++) {
            String cand = name + " " + i;
            if (!hasUserThemeNamed(cand)) return cand;
        }
        return name + " " + System.currentTimeMillis();
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
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) {
                try { in.close(); } catch (Throwable ignored) {}
            }
        }
    }

    // ---- 通用输入弹层 ----

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
                .setPositiveButton(Strings.get(R.string.thememanageractivity_070), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String n = et.getText().toString().trim();
                        if (n.length() == 0) { toast(Strings.get(R.string.thememanageractivity_071)); return; }
                        if (n.length() > 32) n = n.substring(0, 32);
                        cb.onName(n);
                    }
                })
                .setNegativeButton(Strings.get(R.string.thememanageractivity_072), null)
                .show();
    }

    private void toast(String s) {
        AcToast.show(this, s);
    }
}
