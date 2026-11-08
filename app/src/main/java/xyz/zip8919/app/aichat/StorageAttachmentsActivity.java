package xyz.zip8919.app.aichat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 图片附件管理：使用中 / 无引用两个分段。
 *
 * <p>为什么必须分段：图片按内容哈希命名（{@code a3f2…9c.jpg}），几百个文件排在一起
 * 用户完全无从判断哪个能删。唯一有意义的分组就是「还有没有对话在用」。
 *
 * <p>为什么「使用中」不允许删：删掉它，历史消息里的图片会变成裂图，
 * 而用户在这个页面上看不到那条消息，不知道自己在破坏什么。
 * 要删就先删会话，图片会自己变成无引用。
 */
public class StorageAttachmentsActivity extends ThemedActivity {

    private static final String TAG = "StorageAtt";

    /** 缩略图缓存。8 MB 上限约可放 80 张 160px 的图，足够滚一屏。 */
    private static final LruCache<String, Bitmap> THUMBS =
            new LruCache<String, Bitmap>(8 * 1024 * 1024) {
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };

    private static final ExecutorService THUMB_POOL = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final SimpleDateFormat TIME_FMT =
            new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());

    /** 列表项。refs 是引用它的会话数，0 就是无引用。 */
    static final class Item {
        final File file;
        final String name;
        final long bytes;
        final long mtime;
        final int refs;

        Item(File file, String name, long bytes, long mtime, int refs) {
            this.file = file;
            this.name = name;
            this.bytes = bytes;
            this.mtime = mtime;
            this.refs = refs;
        }
    }

    private final List<Item> used = new ArrayList<Item>();
    private final List<Item> orphans = new ArrayList<Item>();
    private final Set<String> selected = new HashSet<String>();

    private ListView list;
    private AttachAdapter adapter;
    private TextView tabUsed;
    private TextView tabOrphan;
    private View indUsed;
    private View indOrphan;
    private TextView hint;
    private TextView selectBtn;
    private TextView selectAllBtn;
    private TextView cancelBtn;
    private TextView deleteBtn;
    private TextView empty;

    private boolean showOrphans = true;
    private boolean selectionMode;
    private boolean incomplete;
    /** 索引一条都没解析出来：此时「无引用」不可信，界面上要明说。 */
    private boolean indexEmpty;
    private boolean loading;
    /** 从「最占空间」跳进来时要定位的文件名。 */
    private String highlight;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_storage_attachments);

        list = (ListView) findViewById(R.id.att_list);
        tabUsed = (TextView) findViewById(R.id.att_tab_used);
        tabOrphan = (TextView) findViewById(R.id.att_tab_orphan);
        indUsed = findViewById(R.id.att_ind_used);
        indOrphan = findViewById(R.id.att_ind_orphan);
        hint = (TextView) findViewById(R.id.att_hint);
        selectBtn = (TextView) findViewById(R.id.att_select);
        selectAllBtn = (TextView) findViewById(R.id.att_select_all);
        cancelBtn = (TextView) findViewById(R.id.att_cancel);
        deleteBtn = (TextView) findViewById(R.id.att_delete);
        empty = (TextView) findViewById(R.id.att_empty);

        adapter = new AttachAdapter();
        list.setAdapter(adapter);
        if (getIntent() != null) {
            highlight = getIntent().getStringExtra(StorageActivity.EXTRA_HIGHLIGHT_NAME);
            if (highlight != null) LogUtil.i(TAG, "highlight attachment: %s", highlight);
        }

        tabUsed.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (showOrphans) {
                    showOrphans = false;
                    exitSelection();
                    render();
                }
            }
        });
        tabOrphan.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!showOrphans) {
                    showOrphans = true;
                    exitSelection();
                    render();
                }
            }
        });
        selectBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (incomplete) {
                    Toast.makeText(StorageAttachmentsActivity.this,
                            "部分会话无法读取，暂不能清理图片", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (showOrphans) {
                    selectionMode = true;
                    selected.clear();
                    render();
                } else {
                    Toast.makeText(StorageAttachmentsActivity.this,
                            "使用中的图片不能在这里删除，请先删除对应会话", Toast.LENGTH_LONG).show();
                }
            }
        });
        selectAllBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                toggleSelectAll();
            }
        });
        cancelBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                exitSelection();
                render();
            }
        });
        deleteBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                confirmDelete();
            }
        });
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int pos, long id) {
                Item it = adapter.getItemAt(pos);
                if (it == null) return;
                if (selectionMode) {
                    toggle(it.name);
                } else {
                    showItemMenu(it);
                }
            }
        });

        load();
    }

    @Override
    public void onBackPressed() {
        if (selectionMode) {
            exitSelection();
            render();
            return;
        }
        super.onBackPressed();
    }

    // ---------------------------------------------------------------- 扫描

    private void load() {
        if (loading) return;
        loading = true;
        adapter.setData(new ArrayList<Item>());
        empty.setVisibility(View.VISIBLE);
        empty.setText("正在统计…");

        new Thread(new Runnable() {
            public void run() {
                final StorageManager sm = StorageManager.getInstance(getApplicationContext());
                final StorageRefs refs = buildRefs(sm);
                final List<Item> u = new ArrayList<Item>();
                final List<Item> o = new ArrayList<Item>();
                File dir = sm.getAttachmentsDir();
                if (dir != null) {
                    File[] files = dir.listFiles();
                    if (files != null) {
                        for (int i = 0; i < files.length; i++) {
                            File f = files[i];
                            if (!f.isFile()) continue;
                            String name = f.getName();
                            if (name.startsWith("tmp-")) continue;
                            int n = refs.count(name);
                            Item it = new Item(f, name, f.length(), f.lastModified(), n);
                            // 只按「有没有引用」判断，不再有 1 小时保护期。
                            // 原来那层保护期是为了「导入中的图片消息还没落盘，此刻必然查不到引用」，
                            // 但导入是同步完成的：文件写完紧接着就写消息，中间只有毫秒级窗口。
                            // 代价却是用户刚删完会话、图还赖在「使用中」一小时，看起来像没生效。
                            // 索引不可信时仍然全部归入「使用中」：不给出任何可删入口。
                            if (n > 0 || refs.incomplete) u.add(it);
                            else o.add(it);
                        }
                    }
                }
                sortByTime(u);
                sortByTime(o);
                runOnUiThread(new Runnable() {
                    public void run() {
                        loading = false;
                        used.clear();
                        used.addAll(u);
                        orphans.clear();
                        orphans.addAll(o);
                        incomplete = refs.incomplete;
                        indexEmpty = !refs.incomplete && refs.size() == 0 && !o.isEmpty();
                        // 从「最占空间」点进来：先判断这张图在哪一段，再切页签，
                        // 否则用户会落在空列表上，以为没定位到
                        if (highlight != null) {
                            if (indexOf(o, highlight) >= 0) showOrphans = true;
                            else if (indexOf(u, highlight) >= 0) showOrphans = false;
                        }
                        render();
                    }
                });
            }
        }, "storage-att-scan").start();
    }

    private StorageRefs buildRefs(StorageManager sm) {
        return StorageRefs.scan(new File(sm.getConversationsPath()));
    }

    private void sortByTime(List<Item> items) {
        Collections.sort(items, new Comparator<Item>() {
            public int compare(Item a, Item b) {
                return a.mtime == b.mtime ? 0 : (a.mtime > b.mtime ? -1 : 1);
            }
        });
    }

    private static int indexOf(List<Item> items, String name) {
        if (name == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (name.equals(items.get(i).name)) return i;
        }
        return -1;
    }

    // ---------------------------------------------------------------- 渲染

    private void render() {
        tabUsed.setText("使用中 " + used.size());
        tabOrphan.setText("无引用 " + orphans.size());
        tabUsed.setTextColor(getResources().getColor(
                showOrphans ? R.color.text_secondary : R.color.storage_att));
        tabOrphan.setTextColor(getResources().getColor(
                showOrphans ? R.color.storage_att : R.color.text_secondary));
        indUsed.setBackgroundColor(showOrphans
                ? 0x00000000 : getResources().getColor(R.color.storage_att));
        indOrphan.setBackgroundColor(showOrphans
                ? getResources().getColor(R.color.storage_att) : 0x00000000);

        List<Item> data = showOrphans ? orphans : used;
        adapter.setHighlight(highlight);
        adapter.setData(data);
        int hi = indexOf(data, highlight);
        if (hi >= 0 && !selectionMode) list.setSelection(hi);

        selectBtn.setVisibility(!selectionMode && showOrphans && data.size() > 0 && !incomplete
                ? View.VISIBLE : View.GONE);
        // 全选只在多选态出现：未进多选时它顶替「多选」按钮会让人以为能一键删
        boolean canSelectAll = selectionMode && showOrphans && !orphans.isEmpty();
        selectAllBtn.setVisibility(canSelectAll ? View.VISIBLE : View.GONE);
        if (canSelectAll) {
            selectAllBtn.setText(selected.size() >= orphans.size() ? "取消全选" : "全选");
        }
        cancelBtn.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        deleteBtn.setVisibility(selectionMode ? View.VISIBLE : View.GONE);

        if (incomplete) {
            hint.setText("部分会话无法读取，暂不能清理图片");
        } else if (indexEmpty && showOrphans) {
            // 一条引用都没解析出来却把所有图都归为「无引用」：这更像是索引没对上格式，
            // 而不是用户真的删光了所有会话。必须把话说白，别让人一键清掉历史图片。
            hint.setText("⚠ 未在任何会话中找到图片引用，请先确认历史消息里的图还能打开再删");
        } else if (showOrphans) {
            hint.setText(selectionMode
                    ? "已选 " + selected.size() + " 张"
                    : "这些图片不与任何对话关联，可以安全删除");
        } else {
            hint.setText("仍被对话引用，删除会让消息里的图片无法显示");
        }

        deleteBtn.setText("删除" + (selected.size() > 0 ? "(" + selected.size() + ")" : ""));
        deleteBtn.setEnabled(!selected.isEmpty());

        if (data.isEmpty()) {
            empty.setVisibility(View.VISIBLE);
            empty.setText(showOrphans
                    ? "没有无引用的图片\n删除会话后，它用过的图片会出现在这里"
                    : "还没有图片附件\n对话里发送的图片会保存在这里");
        } else {
            empty.setVisibility(View.GONE);
        }
    }

    private void exitSelection() {
        selectionMode = false;
        selected.clear();
    }

    private void toggle(String name) {
        if (selected.contains(name)) selected.remove(name);
        else selected.add(name);
        render();
    }

    /**
     * 全选 / 取消全选。
     *
     * <p>无引用图片常常几百张，逐张点选等于逼用户放弃清理。只在「无引用」分段
     * 可用——使用中的图片本来就不允许删。
     */
    private void toggleSelectAll() {
        if (!selectionMode || !showOrphans || orphans.isEmpty()) return;
        if (selected.size() >= orphans.size()) {
            selected.clear();
        } else {
            selected.clear();
            for (int i = 0; i < orphans.size(); i++) {
                selected.add(orphans.get(i).name);
            }
        }
        LogUtil.i(TAG, "toggleSelectAll: %d/%d selected", selected.size(), orphans.size());
        render();
    }

    /**
     * 点一张图：先给菜单，而不是直接弹一段只有数字的说明。
     *
     * <p>用户点图片的第一反应是「看看这张是什么」，所以第一项就是预览；
     * 详情降级成其中一项。删除只对无引用图片开放——使用中的图从这里删掉，
     * 历史消息会变成裂图，而用户在列表里看不到那条消息。
     */
    private void showItemMenu(final Item it) {
        final java.util.ArrayList<String> items = new java.util.ArrayList<String>();
        items.add("预览大图");
        items.add("打开方式（第三方应用）");
        items.add("分享");
        items.add("详细信息");
        if (it.refs == 0) items.add("删除");

        AcDialog.builder(this)
                .setTitle(it.refs > 0
                        ? "使用中 · " + it.refs + " 个会话引用"
                        : "无引用图片")
                .setItems(items.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        String label = items.get(which);
                        if ("预览大图".equals(label)) {
                            FileActions.previewImage(StorageAttachmentsActivity.this, it.file);
                        } else if (label.startsWith("打开方式")) {
                            FileActions.open(StorageAttachmentsActivity.this, it.file);
                        } else if ("分享".equals(label)) {
                            FileActions.share(StorageAttachmentsActivity.this, it.file);
                        } else if ("详细信息".equals(label)) {
                            showDetail(it);
                        } else if ("删除".equals(label)) {
                            selected.clear();
                            selected.add(it.name);
                            deleteSelected();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showDetail(final Item it) {
        String sub = it.refs > 0
                ? "用于 " + it.refs + " 个会话"
                : "无会话引用";
        String msg = "文件名：" + it.name + "\n"
                + "大小：" + StorageReport.size(it.bytes) + "\n"
                + "保存时间：" + TIME_FMT.format(new Date(it.mtime)) + "\n"
                + "状态：" + sub + "\n\n"
                + (it.refs > 0
                ? "这张图仍被对话引用，删除后消息里的图片会无法显示。"
                : "这张图不与任何对话关联，可以安全删除。");
        AcDialog.builder(this)
                .setTitle(it.refs > 0 ? "使用中的图片" : "无引用图片")
                .setMessage(msg)
                .setNegativeButton("关闭", null)
                .show();
    }

    // ---------------------------------------------------------------- 删除

    private void confirmDelete() {
        if (selected.isEmpty()) {
            Toast.makeText(this, "请先选择要删除的图片", Toast.LENGTH_SHORT).show();
            return;
        }
        long bytes = 0;
        for (int i = 0; i < orphans.size(); i++) {
            if (selected.contains(orphans.get(i).name)) bytes += orphans.get(i).bytes;
        }
        AcDialog.builder(this)
                .setTitle("删除图片")
                .setMessage("删除 " + selected.size() + " 张图片？\n\n共 "
                        + StorageReport.size(bytes) + "，不与任何对话关联。\n此操作不可恢复。")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        deleteSelected();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void deleteSelected() {
        final List<String> names = new ArrayList<String>(selected);
        if (names.isEmpty()) return;
        deleteBtn.setEnabled(false);
        deleteBtn.setText("删除中…");

        new Thread(new Runnable() {
            public void run() {
                StorageManager sm = StorageManager.getInstance(getApplicationContext());
                File dir = sm.getAttachmentsDir();
                int ok = 0;
                int failed = 0;
                long bytes = 0;
                if (dir != null) {
                    for (int i = 0; i < names.size(); i++) {
                        File f = new File(dir, names.get(i));
                        long len = f.length();
                        if (f.isFile() && f.delete()) {
                            ok++;
                            bytes += len;
                            THUMBS.remove(names.get(i));
                        } else {
                            failed++;
                        }
                    }
                }
                final int okCount = ok;
                final int failCount = failed;
                final long freed = bytes;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (isFinishing()) return;
                        Toast.makeText(StorageAttachmentsActivity.this,
                                "已删除 " + okCount + " 张，释放 " + StorageReport.size(freed)
                                        + (failCount > 0 ? "，" + failCount + " 张被占用" : ""),
                                Toast.LENGTH_SHORT).show();
                        exitSelection();
                        // 总览页的缓存已过期，否则返回后仍显示旧数字
                        StorageActivity.invalidateCache();
                        load();
                    }
                });
            }
        }, "storage-att-del").start();
    }

    // ---------------------------------------------------------------- 适配器

    private final class AttachAdapter extends BaseAdapter {

        private List<Item> data = new ArrayList<Item>();
        /** 需要高亮的文件名（从「最占空间」跳进来时定位用）。 */
        private String highlight;

        void setData(List<Item> data) {
            this.data = data;
            notifyDataSetChanged();
        }

        void setHighlight(String name) {
            this.highlight = name;
            notifyDataSetChanged();
        }

        Item getItemAt(int pos) {
            return pos >= 0 && pos < data.size() ? data.get(pos) : null;
        }

        public int getCount() {
            return data.size();
        }

        public Object getItem(int position) {
            return data.get(position);
        }

        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            Holder h;
            if (convertView == null) {
                convertView = LayoutInflater.from(StorageAttachmentsActivity.this)
                        .inflate(R.layout.item_storage_attach, parent, false);
                h = new Holder();
                h.thumb = (ImageView) convertView.findViewById(R.id.att_thumb);
                h.name = (TextView) convertView.findViewById(R.id.att_name);
                h.sub = (TextView) convertView.findViewById(R.id.att_sub);
                h.size = (TextView) convertView.findViewById(R.id.att_size);
                h.check = (CheckBox) convertView.findViewById(R.id.att_check);
                convertView.setTag(h);
            } else {
                h = (Holder) convertView.getTag();
            }

            final Item it = data.get(position);
            h.name.setText(displayName(it.name));
            h.size.setText(StorageReport.size(it.bytes));
            h.sub.setText((it.refs > 0 ? "用于 " + it.refs + " 个会话" : "无会话引用")
                    + " · " + TIME_FMT.format(new Date(it.mtime)));
            ThemeRecolor.applyTo(convertView);

            if (selectionMode) {
                h.check.setVisibility(View.VISIBLE);
                h.check.setChecked(selected.contains(it.name));
                convertView.setBackgroundColor(selected.contains(it.name)
                        ? ThemeRecolor.listMarkBg(convertView.getContext(), 0xFFFFF3CD)
                        : ThemeRecolor.listBg(convertView.getContext()));
            } else {
                h.check.setVisibility(View.GONE);
                // 高亮优先级低于多选：选中态必须压过定位态，否则用户分不清自己选了哪张
                convertView.setBackgroundColor(it.name.equals(highlight)
                        ? ThemeRecolor.listMarkBg(convertView.getContext(), 0xFFE3F2FD)
                        : ThemeRecolor.listBg(convertView.getContext()));
            }

            h.thumb.setTag(it.name);
            Bitmap cached = THUMBS.get(it.name);
            if (cached != null) {
                h.thumb.setImageBitmap(cached);
            } else {
                h.thumb.setImageBitmap(null);
                loadThumb(h.thumb, it.file, it.name);
            }
            return convertView;
        }
    }

    private static final class Holder {
        ImageView thumb;
        TextView name;
        TextView sub;
        TextView size;
        CheckBox check;
    }

    /** 哈希文件名没有可读性，留前 8 位够用户和文件管理器对上号。 */
    private static String displayName(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot > 0 ? name.substring(dot) : "";
        String base = dot > 0 ? name.substring(0, dot) : name;
        if (base.length() <= 12) return name;
        return base.substring(0, 8) + "…" + ext;
    }

    private static void loadThumb(final ImageView view, final File file, final String key) {
        THUMB_POOL.execute(new Runnable() {
            public void run() {
                Bitmap bmp = null;
                try {
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
                    int sample = 1;
                    int max = Math.max(bounds.outWidth, bounds.outHeight);
                    // 卡片上只有 56dp，3x 屏 168px 就够；按 2 的幂采样，避免整图进内存
                    while (max / sample > 240) sample *= 2;
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inSampleSize = sample;
                    bmp = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
                } catch (Throwable t) {
                    LogUtil.w(TAG, "thumb failed: %s", t.getMessage());
                }
                if (bmp == null) return;
                THUMBS.put(key, bmp);
                final Bitmap result = bmp;
                MAIN.post(new Runnable() {
                    public void run() {
                        if (key.equals(view.getTag())) {
                            view.setImageBitmap(result);
                        }
                    }
                });
            }
        });
    }
}
