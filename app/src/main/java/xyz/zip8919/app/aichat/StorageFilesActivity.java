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

    /**
     * 重新扫 exports 目录。
     *
     * <p>整个扫描挪到后台：原来在主线程里对每个文件做 `isFile()` + `length()`，
     * sort() 里再来一次 `length()` + `lastModified()`——每个文件 4 次 stat 系统调用。
     * 4.4 老机的 stat 走的是慢路径（很多 ROM 上 fstatat 带 SELinux 检查），
     * 单次 0.3~0.8ms，50 个文件就是 60~160ms；这个页面又是从「存储空间」点进来的
     * 高频入口，onResume 每次都跑，肉眼就是一次白屏。
     * 现在一次扫完、一次排序，再把结果整体换给主线程。
     */
    private void reload() {
        if (reloading) {
            // 扫描在飞时又来一次请求（例如删完文件立刻 onResume）：
            // 直接丢弃会让界面停在删除前的快照上，必须记下来、等这轮结束再扫一次。
            reloadWanted = true;
            return;
        }
        reloading = true;
        final boolean bySize = sortBySize;
        new Thread(new Runnable() {
            public void run() {
                final List<File> scanned = new ArrayList<File>();
                final java.util.HashMap<String, long[]> meta =
                        new java.util.HashMap<String, long[]>();
                long total = 0;
                try {
                    File[] all = exportsDir().listFiles();
                    if (all != null) {
                        for (int i = 0; i < all.length; i++) {
                            File f = all[i];
                            // isDirectory() 与后面的 length()/lastModified() 共用同一次
                            // 目录缓存的 stat 结果，比再调一次 isFile() 省一次系统调用
                            if (f.isDirectory()) continue;
                            String name = f.getName();
                            if (name.endsWith(".tmp")) continue;
                            long len = f.length();
                            long mt = f.lastModified();
                            scanned.add(f);
                            meta.put(name, new long[]{len, mt});
                            total += len;
                        }
                    }
                    sortList(scanned, meta, bySize);
                } catch (Throwable t) {
                    LogUtil.e(TAG, "reload failed: " + t.getMessage());
                }
                final long totalSize = total;
                runOnUiThread(new Runnable() {
                    public void run() {
                        reloading = false;
                        boolean again = reloadWanted;
                        reloadWanted = false;
                        if (isFinishing() || isDestroyed()) return;
                        // 后台按扫描启动时的 bySize 快照排好了；这期间用户可能
                        // 切了排序，按钮文案已翻转，直接覆盖会出现「文案按时间、
                        // 列表按体积」。scanned 已带 meta，纯内存重排代价可忽略。
                        if (bySize != sortBySize) sortList(scanned, meta, sortBySize);
                        files.clear();
                        files.addAll(scanned);
                        fileMeta.clear();
                        fileMeta.putAll(meta);
                        summary.setText(files.isEmpty()
                                ? "暂无文件"
                                : files.size() + " 个文件 · 共 " + StorageReport.size(totalSize));
                        adapter.notifyDataSetChanged();
                        if (files.isEmpty()) {
                            empty.setVisibility(View.VISIBLE);
                            empty.setText("还没有保存的文件\n对话里导出的图片会出现在这里");
                        } else {
                            empty.setVisibility(View.GONE);
                        }
                        scrollToHighlight();
                        if (again) reload();   // 期间又有请求：补扫一次，保证看到的是最新状态
                    }
                });
            }
        }, "aichat-files-scan").start();
    }

    /** 扫描在飞时不要重复起线程（onResume 与操作回调可能连着触发）。 */
    private volatile boolean reloading;
    /** 扫描期间收到的重扫请求：结束后补一次，否则删完文件界面会停在旧快照。 */
    private volatile boolean reloadWanted;

    /**
     * 文件名 → {size, mtime}。getView 每次 bind 都调 length()/lastModified() 就是两次
     * stat 系统调用，滚一屏十来行就是二三十次（4.4 单核上 20~60ms/帧）。
     * reload/sort 时取一次，之后列表滚动零磁盘 IO。
     */
    private final java.util.HashMap<String, long[]> fileMeta =
            new java.util.HashMap<String, long[]>();

    private void sort() {
        // 比较器里每调一次 length()/lastModified() 就是一次 stat 系统调用，
        // 排序 n log n 次比较 × 2 次取值 ≈ 300+ 次 stat（50 个文件，30~100ms）。
        // 先一次性取出 size/mtime，比较时只读内存。
        //
        // 已经有值的条目不再重新 stat：reload() 在后台线程刚取过一轮，
        // 这里重取等于把省下来的系统调用又还回去。
        final java.util.HashMap<String, long[]> meta = fileMeta;
        for (int i = 0; i < files.size(); i++) {
            File f = files.get(i);
            String n = f.getName();
            if (!meta.containsKey(n)) meta.put(n, new long[]{f.length(), f.lastModified()});
        }
        sortList(files, meta, sortBySize);
    }

    /** 按 meta 里的 size/mtime 排序；纯内存操作，可以放在任意线程。 */
    private static void sortList(List<File> list, final java.util.Map<String, long[]> meta,
                                 final boolean bySize) {
        Collections.sort(list, new Comparator<File>() {
            public int compare(File a, File b) {
                long[] ma = meta.get(a.getName());
                long[] mb = meta.get(b.getName());
                long sa = ma == null ? 0L : ma[0], sb = mb == null ? 0L : mb[0];
                long ta = ma == null ? 0L : ma[1], tb = mb == null ? 0L : mb[1];
                if (bySize) {
                    if (sb > sa) return 1;
                    if (sb < sa) return -1;
                    return 0;
                }
                if (tb > ta) return 1;
                if (tb < ta) return -1;
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
                        if (!name.equals(f.getName())) {
                            // 扩展名决定「用什么打开」。旧逻辑只在「新名完全不含点」时补，
                            // 于是把 x.png 改成「截图 v2.0」这种含点但不带扩展名的名字就漏了，
                            // 之后「编辑」会按文本打开、保存时把原图写成乱码，不可恢复。
                            // 判据改为「末段至少 2 个字符」才算用户自己给了扩展名。
                            String oldName = f.getName();
                            int oldDot = oldName.lastIndexOf('.');
                            if (oldDot > 0) {
                                String ext = oldName.substring(oldDot);
                                if (!name.endsWith(ext) && !name.endsWith(ext.toUpperCase())) {
                                    int newDot = name.lastIndexOf('.');
                                    boolean userExt = newDot > 0 && name.length() - newDot - 1 >= 2;
                                    if (!userExt) name += ext;
                                }
                            }
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
            String name = f.getName();
            long[] mm = fileMeta.get(name);
            long size = mm != null ? mm[0] : f.length();
            long mtime = mm != null ? mm[1] : f.lastModified();
            ((TextView) row.findViewById(R.id.file_name)).setText(name);
            String ext = "";
            int dot = name.lastIndexOf('.');
            if (dot > 0 && dot < name.length() - 1) {
                ext = name.substring(dot + 1).toUpperCase(Locale.US) + " · ";
            }
            ((TextView) row.findViewById(R.id.file_cat)).setText(
                    ext + fmt.format(new Date(mtime)));
            ((TextView) row.findViewById(R.id.file_size)).setText(StorageReport.size(size));
            // 放在 ThemeRecolor 之前会被整树着色扫到，图标色块会被换底；
            // 放在其后，图标保持固定的识别色。
            ThemeRecolor.applyTo(row);
            FileIconBinder.bind((android.widget.ImageView) row.findViewById(R.id.file_icon), f);
            row.setBackgroundColor(name.equals(highlight)
                    ? ThemeRecolor.listMarkBg(row.getContext(), 0xFFE3F2FD)
                    : ThemeRecolor.listBg(row.getContext()));
            return row;
        }
    }
}
