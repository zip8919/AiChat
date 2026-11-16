package xyz.zip8919.app.aichat;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
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
 * F2 管理字体页（交互方案 5、实现方案 8.2）。
 *
 * <p>复用 activity_manager.xml 的多选骨架。API 18 没有 SAF，文件选择只能
 * ACTION_GET_CONTENT，且返回的 URI 读权限活不过重启，所以在 onActivityResult
 * 里立刻把字节读进内存交给 FontRepository 拷贝到自有目录。
 */
public class FontManagerActivity extends ThemedActivity {

    private static final int REQ_ADD = 1001;
    private static final int MAX_BYTES = 8 * 1024 * 1024;

    private FontRepository repo;
    private AppearanceManager am;
    private ManagerRowAdapter adapter;
    private final List<FontRepository.FontItem> fonts =
            new ArrayList<FontRepository.FontItem>();
    private boolean selectionMode;

    private TextView title, hint, empty, selectionCount;
    private ListView list;
    private View selectionBar;
    private Button btnPrimary, btnSecondary, btnMulti, btnAct1, btnAct2, btnAct3;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_manager);
        repo = FontRepository.getInstance();
        am = AppearanceManager.getInstance(this);
        bindViews();
        title.setText(Strings.get(R.string.fontmanageractivity_001));
        hint.setText(Strings.get(R.string.fontmanageractivity_002));
        btnPrimary.setText(Strings.get(R.string.fontmanageractivity_003));
        btnSecondary.setVisibility(View.GONE);
        btnMulti.setText(Strings.get(R.string.fontmanageractivity_004));
        btnAct1.setText(Strings.get(R.string.fontmanageractivity_005));
        btnAct2.setText(Strings.get(R.string.fontmanageractivity_006));
        btnAct3.setText(Strings.get(R.string.fontmanageractivity_007));
        adapter = new ManagerRowAdapter(this);
        list.setAdapter(adapter);
        wire();
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
            public void onClick(View v) { pickFont(); }
        });
        btnMulti.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                selectionMode = !selectionMode;
                adapter.setSelectionMode(selectionMode);
                btnMulti.setText(selectionMode ? Strings.get(R.string.fontmanageractivity_008) : Strings.get(R.string.fontmanageractivity_009));
                refreshSelectionUi();
            }
        });
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View v, int pos, long id) {
                ManagerRowAdapter.Row r = adapter.rowAt(pos);
                if (r == null || r.header || r.disabled) return;
                if (selectionMode) {
                    adapter.toggle(r.key);
                    refreshSelectionUi();
                    return;
                }
                FontRepository.FontItem f = findFont(r.key);
                if (f != null) showSingleActions(f);
            }
        });
        btnAct1.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { batchSetEnabled(true); }
        });
        btnAct2.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { batchSetEnabled(false); }
        });
        btnAct3.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { confirmBatchDelete(); }
        });
    }

    private void load() {
        fonts.clear();
        fonts.addAll(repo.list());
        List<ManagerRowAdapter.Row> rows = new ArrayList<ManagerRowAdapter.Row>();
        for (int i = 0; i < fonts.size(); i++) {
            FontRepository.FontItem f = fonts.get(i);
            String state = f.loadFailed ? Strings.get(R.string.fontmanageractivity_010) : (f.enabled ? Strings.get(R.string.fontmanageractivity_011) : Strings.get(R.string.fontmanageractivity_012));
            String sub = f.fileName + " · " + (f.sizeBytes / 1024) + "KB · " + state;
            rows.add(ManagerRowAdapter.Row.item(f.id, f.name, sub, state, f.loadFailed));
        }
        adapter.setRows(rows);
        boolean blank = rows.isEmpty();
        list.setVisibility(blank ? View.GONE : View.VISIBLE);
        empty.setVisibility(blank ? View.VISIBLE : View.GONE);
        empty.setText(Strings.get(R.string.fontmanageractivity_013));
        refreshSelectionUi();
    }

    private void refreshSelectionUi() {
        selectionBar.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        selectionCount.setText(Strings.get(R.string.fontmanageractivity_014, adapter.checkedKeys().size(), fonts.size()));
    }

    private FontRepository.FontItem findFont(String id) {
        if (id == null) return null;
        for (int i = 0; i < fonts.size(); i++) {
            if (id.equals(fonts.get(i).id)) return fonts.get(i);
        }
        return null;
    }

    // ---- 单项操作 ----

    private void showSingleActions(final FontRepository.FontItem f) {
        String toggle = f.enabled ? Strings.get(R.string.fontmanageractivity_015) : Strings.get(R.string.fontmanageractivity_016);
        AcDialog.builder(this)
                .setTitle(f.name)
                .setItems(new String[]{Strings.get(R.string.fontmanageractivity_017), Strings.get(R.string.fontmanageractivity_018), toggle, Strings.get(R.string.fontmanageractivity_019)},
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                if (w == 0) promptRename(f);
                                else if (w == 1) previewFont(f);
                                else if (w == 2) {
                                    repo.setEnabled(f.id, !f.enabled);
                                    load();
                                } else confirmDeleteOne(f);
                            }
                        })
                .setNegativeButton(Strings.get(R.string.fontmanageractivity_020), null)
                .show();
    }

    private void promptRename(final FontRepository.FontItem f) {
        final EditText et = new EditText(this);
        et.setText(f.name);
        et.setSingleLine(true);
        et.setSelection(et.getText().length());
        AcDialog.builder(this)
                .setTitle(Strings.get(R.string.fontmanageractivity_021))
                .setView(et)
                .setPositiveButton(Strings.get(R.string.fontmanageractivity_022), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String n = et.getText().toString().trim();
                        if (n.length() == 0) { toast(Strings.get(R.string.fontmanageractivity_023)); return; }
                        if (n.length() > 32) n = n.substring(0, 32);
                        repo.rename(f.id, n);
                        load();
                    }
                })
                .setNegativeButton(Strings.get(R.string.fontmanageractivity_024), null)
                .show();
    }

    private void previewFont(FontRepository.FontItem f) {
        Typeface tf = repo.getTypeface(f.id);
        if (tf == null) toast(Strings.get(R.string.fontmanageractivity_025));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        box.setPadding(pad, pad, pad, pad);
        float[] sizes = {12f, 14f, 20f};
        for (int i = 0; i < sizes.length; i++) {
            TextView tv = new TextView(this);
            tv.setText(Strings.get(R.string.fontmanageractivity_026, (int) sizes[i]));
            tv.setTextSize(sizes[i]);
            if (tf != null) tv.setTypeface(tf);
            box.addView(tv);
        }
        AcDialog.builder(this)
                .setTitle(f.name)
                .setView(box)
                .setPositiveButton(Strings.get(R.string.fontmanageractivity_027), null)
                .show();
    }

    private void confirmDeleteOne(final FontRepository.FontItem f) {
        if (isReferenced(f.id)) {
            promptReplaceThenDelete(f);
            return;
        }
        AcDialog.builder(this)
                .setTitle(Strings.get(R.string.fontmanageractivity_028))
                .setMessage(Strings.get(R.string.fontmanageractivity_029, f.name))
                .setPositiveButton(Strings.get(R.string.fontmanageractivity_031), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        repo.delete(f.id);
                        load();
                        toast(Strings.get(R.string.fontmanageractivity_032));
                    }
                })
                .setNegativeButton(Strings.get(R.string.fontmanageractivity_033), null)
                .show();
    }

    /** 删除使用中字体：先把当前外观与所有用户主题里的引用改指 replaceId，再删文件。 */
    private void promptReplaceThenDelete(final FontRepository.FontItem f) {
        final List<String> names = new ArrayList<String>();
        final List<String> ids = new ArrayList<String>();
        names.add(Strings.get(R.string.fontmanageractivity_034));
        ids.add("system");
        for (int i = 0; i < fonts.size(); i++) {
            FontRepository.FontItem o = fonts.get(i);
            if (!o.id.equals(f.id) && !o.loadFailed) {
                names.add(o.name);
                ids.add(o.id);
            }
        }
        AcDialog.builder(this)
                .setTitle(Strings.get(R.string.fontmanageractivity_035))
                .setMessage(Strings.get(R.string.fontmanageractivity_061, f.name))
                .setItems(names.toArray(new String[names.size()]),
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                repo.replaceReferences(am, f.id, ids.get(w));
                                // 只提交字体引用改写，不要把「临时生效」的其它改动一并永久化
                                am.commitFontReferences();
                                repo.delete(f.id);
                                load();
                                toast(Strings.get(R.string.fontmanageractivity_038));
                            }
                        })
                .setNegativeButton(Strings.get(R.string.fontmanageractivity_039), null)
                .show();
    }

    /** 当前外观的显式覆盖 / 主题生效值 / 任意主题的字体引用，命中任一就算使用中。 */
    private boolean isReferenced(String id) {
        String[] keys = {ThemeTokens.FONT_UI, ThemeTokens.FONT_MONO};
        for (int k = 0; k < keys.length; k++) {
            for (int d = 0; d < 2; d++) {
                boolean dark = d == 1;
                if (id.equals(am.getExplicit(keys[k], dark))) return true;
                if (id.equals(am.getEffective(keys[k], dark))) return true;
            }
        }
        List<Theme> themes = ThemeRepository.getInstance().list();
        for (int i = 0; i < themes.size(); i++) {
            Theme t = themes.get(i);
            if (id.equals(t.fontUi) || id.equals(t.fontMono)) return true;
        }
        return false;
    }

    // ---- 批量 ----

    private void batchSetEnabled(boolean on) {
        Set<String> keys = adapter.checkedKeys();
        if (keys.isEmpty()) { toast(Strings.get(R.string.fontmanageractivity_040)); return; }
        // 一次写盘：逐项 setEnabled 会为每个字体整份重写 fonts.json
        repo.setEnabledAll(keys, on);
        load();
        toast(on ? Strings.get(R.string.fontmanageractivity_041) : Strings.get(R.string.fontmanageractivity_042));
    }

    private void confirmBatchDelete() {
        final Set<String> keys = new HashSet<String>(adapter.checkedKeys());
        if (keys.isEmpty()) { toast(Strings.get(R.string.fontmanageractivity_043)); return; }
        final List<String> used = new ArrayList<String>();
        for (String id : keys) {
            if (isReferenced(id)) used.add(id);
        }
        String msg = used.isEmpty()
                ? Strings.get(R.string.fontmanageractivity_044, keys.size())
                : Strings.get(R.string.fontmanageractivity_062, keys.size(), used.size());
        AcDialog.builder(this)
                .setTitle(Strings.get(R.string.fontmanageractivity_048))
                .setMessage(msg)
                .setPositiveButton(Strings.get(R.string.fontmanageractivity_049), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        boolean changed = false;
                        for (String id : used) {
                            repo.replaceReferences(am, id, "system");
                            changed = true;
                        }
                        if (changed) am.commitFontReferences();
                        // 目录只列一次、元数据只写一次，不再逐项 delete（每项一次全表重扫）
                        repo.deleteAll(keys);
                        selectionMode = false;
                        adapter.setSelectionMode(false);
                        btnMulti.setText(Strings.get(R.string.fontmanageractivity_050));
                        load();
                        toast(Strings.get(R.string.fontmanageractivity_051, keys.size()));
                    }
                })
                .setNegativeButton(Strings.get(R.string.fontmanageractivity_053), null)
                .show();
    }

    // ---- 添加字体 ----

    private void pickFont() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(i, REQ_ADD);
        } catch (Throwable t) {
            toast(Strings.get(R.string.fontmanageractivity_054));
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_ADD || res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        final String name = displayName(uri);
        String lower = name.toLowerCase();
        if (!lower.endsWith(".ttf") && !lower.endsWith(".otf")) {
            toast(FontRepository.addErrorText(FontRepository.ADD_BAD_EXT));
            return;
        }
        // 读取（最多 8MB）+ 写盘 + Typeface 试加载原来全在 onActivityResult 主线程同步跑，
        // 导入大字体时冻结 1~5s，低端机直接 ANR。整段搬后台，回调再回主线程。
        new Thread(new Runnable() {
            public void run() {
                final byte[] bytes = readAll(uri);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        if (bytes == null) {
                            toast(Strings.get(R.string.fontmanageractivity_055));
                            return;
                        }
                        if (repo.installedIds().contains(stripExt(name))) {
                            showNameConflict(name, bytes);
                        } else {
                            doAdd(name, bytes);
                        }
                    }
                });
            }
        }, "aichat-font-read").start();
    }

    private void showNameConflict(final String name, final byte[] bytes) {
        AcDialog.builder(this)
                .setTitle(Strings.get(R.string.fontmanageractivity_056))
                .setItems(new String[]{Strings.get(R.string.fontmanageractivity_057), Strings.get(R.string.fontmanageractivity_058), Strings.get(R.string.fontmanageractivity_059)},
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                if (w == 0) doAdd(name, bytes);
                                else if (w == 1) doAdd(uniqueName(name), bytes);
                            }
                        })
                .show();
    }

    private void doAdd(final String name, final byte[] bytes) {
        // repo.add 内部要原子写盘 + Typeface.createFromFile 试加载，同属重 IO，一样放后台。
        new Thread(new Runnable() {
            public void run() {
                final int code = repo.add(bytes, name);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        if (code == FontRepository.ADD_OK) {
                            load();
                            toast(Strings.get(R.string.fontmanageractivity_060));
                        } else {
                            toast(FontRepository.addErrorText(code));
                        }
                    }
                });
            }
        }, "aichat-font-add").start();
    }

    private String uniqueName(String name) {
        int p = name.lastIndexOf('.');
        String base = p > 0 ? name.substring(0, p) : name;
        String ext = p > 0 ? name.substring(p) : ".ttf";
        for (int i = 2; i < 100; i++) {
            String cand = base + "_" + i + ext;
            if (!repo.installedIds().contains(stripExt(cand))) return cand;
        }
        return base + "_" + System.currentTimeMillis() + ext;
    }

    private String displayName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String n = c.getString(idx);
                    if (n != null && n.length() > 0) return n;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) {
                try { c.close(); } catch (Throwable ignored) {}
            }
        }
        String seg = uri.getLastPathSegment();
        return seg == null ? "font.ttf" : seg;
    }

    /** 尽量按文件大小预分配：8MB 级数组反复扩容时每次都要整段拷贝，白烧 CPU。 */
    private int sizeHintOf(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{OpenableColumns.SIZE},
                    null, null, null);
            if (c != null && c.moveToFirst() && !c.isNull(0)) {
                long n = c.getLong(0);
                if (n > 0 && n <= MAX_BYTES) return (int) n + 1;
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) {
                try { c.close(); } catch (Throwable ignored) {}
            }
        }
        return 8192;
    }

    private byte[] readAll(Uri uri) {
        InputStream in = null;
        ByteArrayOutputStream out = new ByteArrayOutputStream(sizeHintOf(uri));
        try {
            in = getContentResolver().openInputStream(uri);
            if (in == null) return null;
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                // 提前拒掉超大文件，不在 66MB 内存的老设备上先撑出一个大数组
                if (out.size() > MAX_BYTES + 1) return null;
            }
            return out.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) {
                try { in.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private static String stripExt(String name) {
        int p = name.lastIndexOf('.');
        return p > 0 ? name.substring(0, p) : name;
    }

    private void toast(String s) {
        AcToast.show(this, s);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
