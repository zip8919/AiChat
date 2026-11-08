package xyz.zip8919.app.aichat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 已保存文件（exports 目录）的排序列表。
 *
 * <p>为什么不复用小工具页：那一页的主体是 JS 运行器，文件列表只是输入源，
 * 既没有体积、也不能按体积排。用户从「存储空间 → 已保存文件」进来时，
 * 唯一的问题是「哪个文件在占地方」，需要一个为这个问题排好序的列表。
 *
 * <p>删除与重命名仍然走 {@link StorageManager}：同一份文件不能有两套改名逻辑，
 * 否则「改名失败但界面显示成功」这种事迟早发生。
 */
public class StorageFilesActivity extends ThemedActivity {

    private static final String TAG = "StorageFiles";

    private final List<File> files = new ArrayList<File>();
    private ListView list;
    private TextView summary;
    private TextView sortBtn;
    private TextView empty;
    private FileAdapter adapter;
    /** HTML / SVG 预览器，与聊天页同一套实现。 */
    private ViewerController viewer;

    /** true = 按体积降序，false = 按修改时间降序。默认按体积：进这一页就是为了找大文件。 */
    private boolean sortBySize = true;

    private String highlight;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_storage_files);
        viewer = new ViewerController(this);

        list = (ListView) findViewById(R.id.sf_list);
        summary = (TextView) findViewById(R.id.sf_summary);
        sortBtn = (TextView) findViewById(R.id.sf_sort);
        empty = (TextView) findViewById(R.id.sf_empty);

        if (getIntent() != null) {
            highlight = getIntent().getStringExtra(StorageActivity.EXTRA_HIGHLIGHT_NAME);
            if (highlight != null) LogUtil.i(TAG, "highlight file: %s", highlight);
        }

        adapter = new FileAdapter();
        list.setAdapter(adapter);
        // 单击也弹菜单：单击直接「用第三方应用打开」误触率太高，
        // 而且用户以为会进内容预览。单击与长按走同一入口，行为可预期。
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                if (pos >= 0 && pos < files.size()) showMenu(files.get(pos));
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                if (pos >= 0 && pos < files.size()) showMenu(files.get(pos));
                return true;
            }
        });

        // 按钮文案是「点了会变成什么」，与对话记录页一致
        sortBtn.setText("按时间");
        sortBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                sortBySize = !sortBySize;
                sortBtn.setText(sortBySize ? "按时间" : "按体积");
                LogUtil.d(TAG, "click: sortBySize=%s", sortBySize);
                sort();
                adapter.notifyDataSetChanged();
                scrollToHighlight();
            }
        });

        // 首次加载交给 onResume：从这里返回（重命名/删除）同样需要重新读盘，
        // 两个入口合成一个，不会出现「首次没加载」的空列表
    }

    @Override
    protected void onDestroy() {
        if (viewer != null) {
            viewer.destroy();
            viewer = null;
        }
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从小工具页改过文件再回来，列表必须是新的
        if (adapter != null) reload();
    }

    private File exportsDir() {
        String base = StorageManager.getInstance(getApplicationContext()).getBasePath();
        if (base == null) {
            // 极端路径：存储还没初始化完就进了本页，先指向内部目录保证不 NPE
            return new File(getFilesDir(), "exports");
        }
        return new File(base, "exports");
    }

    private void reload() {
        files.clear();
        File dir = exportsDir();
        File[] all = dir.listFiles();
        long total = 0;
        if (all != null) {
            for (int i = 0; i < all.length; i++) {
                File f = all[i];
                if (!f.isFile() || f.getName().endsWith(".tmp")) continue;
                files.add(f);
                total += f.length();
            }
        }
        sort();
        summary.setText(files.isEmpty()
                ? "暂无文件"
                : files.size() + " 个文件 · 共 " + StorageReport.size(total));
        adapter.notifyDataSetChanged();

        if (files.isEmpty()) {
            empty.setVisibility(View.VISIBLE);
            empty.setText("还没有保存的文件\n对话里导出的图片会出现在这里");
        } else {
            empty.setVisibility(View.GONE);
        }
        scrollToHighlight();
    }

    private void sort() {
        Collections.sort(files, new Comparator<File>() {
            public int compare(File a, File b) {
                if (sortBySize) {
                    if (b.length() > a.length()) return 1;
                    if (b.length() < a.length()) return -1;
                    return 0;
                }
                if (b.lastModified() > a.lastModified()) return 1;
                if (b.lastModified() < a.lastModified()) return -1;
                return 0;
            }
        });
    }

    private void scrollToHighlight() {
        if (highlight == null) return;
        for (int i = 0; i < files.size(); i++) {
            if (highlight.equals(files.get(i).getName())) {
                list.setSelection(i);
                return;
            }
        }
    }

    // ---------------------------------------------------------------- 操作

    /**
     * 菜单必须和小工具页一致。
     *
     * <p>原先这里只有「打开 / 重命名 / 删除」，同一个文件在两个页面能干的事不一样，
     * 用户从存储管理进来就找不到编辑和预览。菜单项顺序也照抄小工具页：
     * 先内容操作，再外部应用，最后是破坏性操作。
     */
    private void showMenu(final File f) {
        String[] items = {
                "查看内容", "预览 / 运行", "编辑",
                "打开方式（第三方应用）", "分享",
                "重命名", "删除"
        };
        AcDialog.builder(this)
                .setTitle(f.getName())
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        switch (which) {
                            case 0: FileActions.viewText(StorageFilesActivity.this, f); break;
                            case 1: FileActions.previewCode(StorageFilesActivity.this, viewer, f); break;
                            case 2: FileActions.editText(StorageFilesActivity.this, f, new Runnable() {
                                public void run() { reload(); }
                            }); break;
                            case 3: FileActions.open(StorageFilesActivity.this, f); break;
                            case 4: FileActions.share(StorageFilesActivity.this, f); break;
                            case 5: rename(f); break;
                            case 6: confirmDelete(f); break;
                            default: break;
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void rename(final File f) {
        final EditText input = new EditText(this);
        input.setText(f.getName());
        input.setSelectAllOnFocus(true);
        AcDialog.builder(this)
                .setTitle("重命名")
                .setView(input)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String name = input.getText().toString().trim();
                        if (TextUtils.isEmpty(name)) return;
                        if (!name.equals(f.getName()) && !name.contains(".")) {
                            // 扩展名决定「用什么打开」，改名时手滑删掉就没法预览了
                            int dot = f.getName().lastIndexOf('.');
                            if (dot > 0) name += f.getName().substring(dot);
                        }
                        String renamed = StorageManager.getInstance(getApplicationContext())
                                .renameExport(f.getName(), name);
                        if (renamed == null) {
                            AcToast.show(StorageFilesActivity.this, "重命名失败（重名或文件被占用）");
                            return;
                        }
                        StorageActivity.invalidateCache();
                        reload();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDelete(final File f) {
        AcDialog.builder(this)
                .setTitle("删除文件")
                .setMessage("删除 " + f.getName() + "？\n\n"
                        + StorageReport.size(f.length()) + "，此操作不可恢复。")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        boolean ok = f.delete();
                        LogUtil.i(TAG, "delete: %s ok=%s", f.getName(), ok);
                        if (!ok) {
                            AcToast.show(StorageFilesActivity.this, "删除失败，文件可能被占用");
                        }
                        StorageActivity.invalidateCache();
                        reload();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------------------------------------------------------- 适配器

    private final class FileAdapter extends BaseAdapter {
        private final SimpleDateFormat fmt =
                new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());

        public int getCount() {
            return files.size();
        }

        public Object getItem(int position) {
            return files.get(position);
        }

        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = LayoutInflater.from(StorageFilesActivity.this)
                        .inflate(R.layout.item_storage_file, parent, false);
            }
            File f = files.get(position);
            ((TextView) row.findViewById(R.id.file_name)).setText(f.getName());
            String ext = "";
            int dot = f.getName().lastIndexOf('.');
            if (dot > 0 && dot < f.getName().length() - 1) {
                ext = f.getName().substring(dot + 1).toUpperCase(Locale.US) + " · ";
            }
            ((TextView) row.findViewById(R.id.file_cat)).setText(
                    ext + fmt.format(new Date(f.lastModified())));
            ((TextView) row.findViewById(R.id.file_size)).setText(StorageReport.size(f.length()));
            ThemeRecolor.applyTo(row);
            row.setBackgroundColor(f.getName().equals(highlight)
                    ? ThemeRecolor.listMarkBg(row.getContext(), 0xFFE3F2FD)
                    : ThemeRecolor.listBg(row.getContext()));
            return row;
        }
    }
}
