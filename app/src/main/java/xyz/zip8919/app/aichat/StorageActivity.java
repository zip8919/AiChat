package xyz.zip8919.app.aichat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/**
 * 存储管理总览。
 *
 * <p>五个分类的顺序是写死的（对话、图片、导出、临时、其他），不按体积动态排序：
 * 每次进来位置乱跳会让人找不到上次点的那个。
 *
 * <p>扫描一律在后台线程、结果缓存 60 秒。从本页进分类页再返回时，
 * 除非超过缓存时间，否则不重扫——用户点两下就等两次「统计中…」是不能接受的。
 */
public class StorageActivity extends Activity {

    /** 从存储管理跳到其它管理页时带上，用于把返回路径指回本页。 */
    public static final String EXTRA_FROM_STORAGE = "from_storage";

    /** 点「最占空间」里的文件时，让目标页定位并高亮到这一条。 */
    public static final String EXTRA_HIGHLIGHT_ID = "highlight_id";
    public static final String EXTRA_HIGHLIGHT_NAME = "highlight_name";

    private static final long CACHE_MS = 60L * 1000L;

    /** 扫描结果缓存。静态持有是因为页面常被销毁重建，而扫描本身要读几十 MB。 */
    private static StorageReport sCached;
    private static long sCachedAt;

    /**
     * 别的页面删了数据（会话、附件）之后调用，让下次进入本页重新扫描。
     *
     * <p>不直接清空界面上的数字：当前页可能不在前台，改 UI 没有意义；
     * 置空缓存足够让下一次 onCreate/onResume 重扫。
     */
    static void invalidateCache() {
        sCached = null;
        sCachedAt = 0;
    }

    /** 供设置页显示「已用 X」；没有缓存时返回 null，由调用方决定是否后台扫。 */
    static StorageReport cachedReport() {
        if (sCached == null) return null;
        if (System.currentTimeMillis() - sCachedAt > CACHE_MS) return null;
        return sCached;
    }

    private final int[] catColors = {
            R.color.storage_conv, R.color.storage_att, R.color.storage_export,
            R.color.storage_temp, R.color.storage_other
    };
    private final String[] catTitles = {"对话记录", "图片附件", "已保存文件", "临时文件", "其他"};
    private final String[] catShort = {"对话", "图片", "导出", "临时", "其他"};
    private final String[] catUnits = {"个", "张", "个", "个", "个"};

    private StorageReport report;
    private boolean scanning;
    private LinearLayout categories;
    private LinearLayout topList;
    private LinearLayout cleanCard;
    private TextView cleanTitle;
    private TextView cleanSub;
    private android.widget.Button cleanBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_storage);

        categories = (LinearLayout) findViewById(R.id.storage_categories);
        topList = (LinearLayout) findViewById(R.id.storage_top);
        cleanCard = (LinearLayout) findViewById(R.id.storage_clean_card);
        cleanTitle = (TextView) findViewById(R.id.storage_clean_title);
        cleanSub = (TextView) findViewById(R.id.storage_clean_sub);
        cleanBtn = (android.widget.Button) findViewById(R.id.storage_clean_btn);

        findViewById(R.id.storage_refresh).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                LogUtil.d("StorageActivity", "click: refresh");
                refresh(true);
            }
        });
        findViewById(R.id.storage_path).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showPaths();
            }
        });
        cleanBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                confirmClean();
            }
        });

        buildLegend();
        refresh(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从分类页回来：数据可能变了（删了会话/文件），但 60 秒内不重扫，
        // 避免「进去看一眼再返回」也触发一次全盘扫描
        if (report != null && System.currentTimeMillis() - sCachedAt > CACHE_MS) {
            refresh(true);
        }
    }

    // ---------------------------------------------------------------- 扫描

    private void refresh(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && sCached != null && now - sCachedAt < CACHE_MS) {
            apply(sCached);
            return;
        }
        if (scanning) return;
        scanning = true;

        TextView used = (TextView) findViewById(R.id.storage_used);
        TextView free = (TextView) findViewById(R.id.storage_free);
        used.setText("统计中…");
        free.setText("正在统计各类数据的占用");
        if (categories.getChildCount() == 0) {
            renderPlaceholders();
        }

        new Thread(new Runnable() {
            public void run() {
                final StorageReport r = StorageScanner.scan(StorageActivity.this);
                runOnUiThread(new Runnable() {
                    public void run() {
                        scanning = false;
                        if (isFinishing()) return;
                        sCached = r;
                        sCachedAt = System.currentTimeMillis();
                        apply(r);
                    }
                });
            }
        }, "storage-scan").start();
    }

    private void apply(StorageReport r) {
        this.report = r;

        TextView used = (TextView) findViewById(R.id.storage_used);
        TextView free = (TextView) findViewById(R.id.storage_free);
        if (r.externalMissing && r.usedBytes() == 0) {
            used.setText("无法读取存储");
            free.setText("外部存储不可用，请检查后重试");
        } else {
            used.setText("已用 " + StorageReport.size(r.usedBytes()));
            long total = r.usedBytes() + r.freeBytes;
            free.setText("可用 " + StorageReport.size(r.freeBytes)
                    + (total > 0 ? " · 共 " + StorageReport.size(total) : ""));
        }

        ((StorageBarView) findViewById(R.id.storage_bar)).setData(r.size, r.freeBytes);

        TextView warn = (TextView) findViewById(R.id.storage_warn);
        if (r.externalMissing) {
            warn.setVisibility(View.VISIBLE);
            warn.setText("⚠ 外部存储不可用，以上只统计了内部数据");
        } else if (r.indexIncomplete) {
            warn.setVisibility(View.VISIBLE);
            warn.setText("⚠ " + r.unreadableConversations
                    + " 个会话无法读取，为避免误删，本次不清理图片");
        } else if (r.indexEmpty) {
            warn.setVisibility(View.VISIBLE);
            warn.setText("⚠ 未能在会话中读到图片引用，图片归属可能不准，已暂停一键清理图片");
        } else {
            warn.setVisibility(View.GONE);
        }

        StorageManager sm = StorageManager.getInstance(getApplicationContext());
        TextView path = (TextView) findViewById(R.id.storage_path);
        path.setText("存储位置：" + (sm.isUsingInternalStorage() ? "内部存储" : "外部存储") + "  ›");

        renderCategories(r);
        renderClean(r);
        renderTop(r);
    }

    // ---------------------------------------------------------------- 分类卡

    private void renderPlaceholders() {
        categories.removeAllViews();
        for (int i = 0; i < catTitles.length; i++) {
            View row = createCategoryRow(i);
            ((TextView) row.findViewById(R.id.cat_sub)).setText("统计中…");
            categories.addView(row);
        }
    }

    private void renderCategories(StorageReport r) {
        categories.removeAllViews();
        long used = r.usedBytes();
        for (int i = 0; i < catTitles.length; i++) {
            View row = createCategoryRow(i);
            TextView sub = (TextView) row.findViewById(R.id.cat_sub);
            TextView count = (TextView) row.findViewById(R.id.cat_count);

            StringBuilder sb = new StringBuilder();
            sb.append(StorageReport.size(r.size[i]));
            if (used > 0) {
                sb.append(" · 占 ").append(Math.round(r.size[i] * 100f / used)).append('%');
            }
            if (i == StorageReport.CAT_ATTACHMENT && !r.indexIncomplete && r.orphanCount > 0) {
                sb.append(" · ").append(r.orphanCount).append(" 张无引用");
            }
            if (i == StorageReport.CAT_TEMP && r.cleanableTempSize > 0) {
                sb.append(" · 可清 ").append(StorageReport.size(r.cleanableTempSize));
            }
            sub.setText(sb.toString());
            count.setText(r.count[i] + " " + catUnits[i]);
            categories.addView(row);
        }
    }

    private View createCategoryRow(final int index) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_storage_category, categories, false);
        ((View) row.findViewById(R.id.cat_stripe)).setBackgroundColor(
                getResources().getColor(catColors[index]));
        ((TextView) row.findViewById(R.id.cat_title)).setText(catTitles[index]);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                openCategory(index);
            }
        });
        return row;
    }

    private void openCategory(int index) {
        switch (index) {
            case StorageReport.CAT_CONVERSATION: {
                Intent it = new Intent(this, ConversationManagerActivity.class);
                it.putExtra(EXTRA_FROM_STORAGE, true);
                startActivity(it);
                break;
            }
            case StorageReport.CAT_ATTACHMENT: {
                startActivity(new Intent(this, StorageAttachmentsActivity.class));
                break;
            }
            case StorageReport.CAT_EXPORT: {
                Intent it = new Intent(this, StorageFilesActivity.class);
                it.putExtra(EXTRA_FROM_STORAGE, true);
                startActivity(it);
                break;
            }
            case StorageReport.CAT_TEMP:
                showTempDetail();
                break;
            default:
                showOtherDetail();
                break;
        }
    }

    // ---------------------------------------------------------------- 临时 / 其他

    /**
     * 临时文件不单独做页：缓存目录里可能有上千个文件，列出来没有意义。
     * 给「总量 / 可清理量」和直接清理的入口就够。
     */
    private void showTempDetail() {
        if (report == null) return;
        String msg = "缓存与中间文件共 " + StorageReport.size(report.size[StorageReport.CAT_TEMP])
                + "，其中 " + StorageReport.size(report.cleanableTempSize)
                + " 已过保护期，可以清理。\n\n"
                + "包括：拍照与裁剪的中间图（7 天）、网页缓存与临时文件（1 天）、"
                + "导入残留与写入中转（24 小时 / 1 小时）。\n\n"
                + "正在使用的文件不会被动到。";
        new AlertDialog.Builder(this)
                .setTitle("临时文件")
                .setMessage(msg)
                .setPositiveButton("清理", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        // 从这里进来只清临时文件：用户点的是「临时文件」这张卡，
                        // 顺手把图片也删了属于超出预期的操作
                        doClean(true);
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showOtherDetail() {
        if (report == null) return;
        StringBuilder sb = new StringBuilder();
        if (report.roots.isEmpty()) {
            sb.append("（无）");
        } else {
            for (int i = 0; i < report.roots.size(); i++) {
                StorageReport.Entry e = report.roots.get(i);
                if (i > 0) sb.append('\n');
                sb.append(e.file.getName()).append("  ").append(StorageReport.size(e.bytes));
                if (e.file.getName().endsWith(".json")) {
                    sb.append("  （配置与统计，不可清理）");
                }
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("其他文件")
                .setMessage(sb.toString())
                .setNegativeButton("关闭", null)
                .show();
    }

    // ---------------------------------------------------------------- 清理

    private void renderClean(StorageReport r) {
        long can = r.cleanableBytes();
        if (can <= 0) {
            cleanCard.setVisibility(View.GONE);
            return;
        }
        cleanCard.setVisibility(View.VISIBLE);
        cleanTitle.setText("可安全释放 " + StorageReport.size(can));
        String sub;
        if (r.indexIncomplete) {
            sub = "临时文件 " + StorageReport.size(r.cleanableTempSize)
                    + "（部分会话无法读取，本次不清理图片）";
        } else if (r.indexEmpty) {
            sub = "临时文件 " + StorageReport.size(r.cleanableTempSize)
                    + "（未读到图片引用，本次不清理图片）";
        } else {
            sub = "临时文件 " + StorageReport.size(r.cleanableTempSize)
                    + " + 无引用图片 " + StorageReport.size(r.orphanSize);
        }
        cleanSub.setText(sub);
        cleanBtn.setEnabled(true);
        cleanBtn.setText("立即清理");
    }

    private void confirmClean() {
        if (report == null) return;
        final long can = report.cleanableBytes();
        if (can <= 0) return;

        StringBuilder msg = new StringBuilder();
        msg.append("将清理临时文件");
        if (!report.indexIncomplete && !report.indexEmpty && report.orphanCount > 0) {
            msg.append("和 ").append(report.orphanCount).append(" 张无引用图片");
        }
        msg.append("，释放约 ").append(StorageReport.size(can)).append("。\n\n");
        if (report.indexIncomplete) {
            msg.append("部分会话无法读取，本次只清理临时文件，不删除任何图片。\n\n");
        }
        msg.append("这些文件不属于任何对话，删除后无法恢复。");

        new AlertDialog.Builder(this)
                .setTitle("安全清理")
                .setMessage(msg.toString())
                .setPositiveButton("清理", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        doClean(false);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * @param tempOnly true 时只清临时文件，不碰图片。
     */
    private void doClean(final boolean tempOnly) {
        if (scanning) return;
        scanning = true;
        cleanBtn.setEnabled(false);
        cleanBtn.setText("清理中…");

        final boolean skipImages = tempOnly || (report != null
                && (report.indexIncomplete || report.indexEmpty));
        new Thread(new Runnable() {
            public void run() {
                StorageManager sm = StorageManager.getInstance(getApplicationContext());
                StorageCleaner.Result temp = StorageCleaner.cleanTemp(sm);
                StorageCleaner.Result img = skipImages
                        ? new StorageCleaner.Result() : StorageCleaner.cleanOrphans(sm);
                final long bytes = temp.bytes + img.bytes;
                final int count = temp.deleted + img.deleted;
                final int failed = temp.failed + img.failed;
                runOnUiThread(new Runnable() {
                    public void run() {
                        scanning = false;
                        if (isFinishing()) return;
                        String text;
                        if (count == 0) {
                            text = failed > 0 ? "没有可清理的文件（" + failed + " 个被占用）" : "没有可清理的文件";
                        } else {
                            text = "已释放 " + StorageReport.size(bytes) + "，共 " + count + " 个文件";
                            if (failed > 0) text += "，" + failed + " 个被占用未删除";
                        }
                        Toast.makeText(StorageActivity.this, text, Toast.LENGTH_SHORT).show();
                        refresh(true);
                    }
                });
            }
        }, "storage-clean").start();
    }

    // ---------------------------------------------------------------- Top 榜

    private void renderTop(StorageReport r) {
        topList.removeAllViews();
        TextView empty = (TextView) findViewById(R.id.storage_top_empty);
        if (r.top.isEmpty()) {
            empty.setVisibility(View.VISIBLE);
            topList.setVisibility(View.GONE);
            return;
        }
        empty.setVisibility(View.GONE);
        topList.setVisibility(View.VISIBLE);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < r.top.size(); i++) {
            final StorageReport.Entry e = r.top.get(i);
            View row = inflater.inflate(R.layout.item_storage_file, topList, false);
            ((TextView) row.findViewById(R.id.file_name)).setText(e.file.getName());
            ((TextView) row.findViewById(R.id.file_cat)).setText(
                    StorageReport.categoryName(e.category)
                            + (e.category == StorageReport.CAT_CONVERSATION ? " · 单个会话" : ""));
            ((TextView) row.findViewById(R.id.file_size)).setText(
                    StorageReport.size(e.bytes) + "  ›");
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    openTopFile(e);
                }
            });
            topList.addView(row);
            if (i < r.top.size() - 1) {
                topList.addView(divider());
            }
        }
    }

    /**
     * 「最占空间」里的文件：能定位的定位，定位不了的给明细。
     *
     * <p>只跳分类页是不够的——用户点的是某一个 12 MB 的会话，落在一个按时间排的
     * 列表顶部，等于没找到。目标页会按 id / 文件名滚动并高亮那一行。
     */
    private void openTopFile(StorageReport.Entry e) {
        String name = e.file.getName();
        switch (e.category) {
            case StorageReport.CAT_CONVERSATION: {
                String id = name.endsWith(".json")
                        ? name.substring(0, name.length() - 5) : name;
                Intent it = new Intent(this, ConversationManagerActivity.class);
                it.putExtra(EXTRA_FROM_STORAGE, true);
                it.putExtra(EXTRA_HIGHLIGHT_ID, id);
                startActivity(it);
                break;
            }
            case StorageReport.CAT_ATTACHMENT: {
                Intent it = new Intent(this, StorageAttachmentsActivity.class);
                it.putExtra(EXTRA_HIGHLIGHT_NAME, name);
                startActivity(it);
                break;
            }
            case StorageReport.CAT_EXPORT: {
                Intent it = new Intent(this, StorageFilesActivity.class);
                it.putExtra(EXTRA_FROM_STORAGE, true);
                it.putExtra(EXTRA_HIGHLIGHT_NAME, name);
                startActivity(it);
                break;
            }
            case StorageReport.CAT_TEMP:
                // 临时文件不做列表页，只能给总量与清理入口
                showTempDetail();
                break;
            default:
                showOtherDetail();
                break;
        }
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(0xFFE0E0E0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.leftMargin = dp(14);
        lp.rightMargin = dp(14);
        v.setLayoutParams(lp);
        return v;
    }

    // ---------------------------------------------------------------- 图例 / 路径

    private void buildLegend() {
        LinearLayout legend = (LinearLayout) findViewById(R.id.storage_legend);
        legend.removeAllViews();
        for (int i = 0; i < catTitles.length; i++) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(android.view.Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            item.setLayoutParams(lp);

            View dot = new View(this);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(6), dp(6));
            dotLp.rightMargin = dp(3);
            dot.setLayoutParams(dotLp);
            dot.setBackgroundColor(getResources().getColor(catColors[i]));
            item.addView(dot);

            TextView name = new TextView(this);
            name.setTextSize(11);
            name.setTextColor(getResources().getColor(R.color.text_secondary));
            name.setText(catShort[i]);
            item.addView(name);

            legend.addView(item);
        }
    }

    /**
     * 存储位置说明 + 复制。
     *
     * <p>早先用 {@code setItems} 弹一个纯文字列表，界面上没有任何「复制」的痕迹，
     * 用户点一下也不知道发生了什么。改成每行一个明确的「复制」按钮，
     * 并把「为什么要看这个」写清楚：数据能整目录拷走，前提是图也在里面。
     */
    private void showPaths() {
        StorageManager sm = StorageManager.getInstance(getApplicationContext());
        File attDir = sm.getAttachmentsDir();
        File cacheDir = sm.getCacheDir();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        box.setPadding(pad, dp(8), pad, 0);

        TextView intro = new TextView(this);
        intro.setTextSize(13);
        intro.setTextColor(getResources().getColor(R.color.text_secondary));
        intro.setText("会话、图片、导出都在下面这个数据目录里。"
                + "把整个目录拷到新手机，历史记录和图片会一起过去。");
        box.addView(intro, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addPathRow(box, "数据目录", "会话 / 图片 / 导出",
                sm.getBasePath());
        addPathRow(box, "附件目录", "图片原件",
                attDir == null ? null : attDir.getAbsolutePath());
        addPathRow(box, "缓存目录", "系统可随时清理",
                cacheDir == null ? null : cacheDir.getAbsolutePath());

        TextView foot = new TextView(this);
        foot.setTextSize(12);
        foot.setTextColor(getResources().getColor(R.color.text_secondary));
        foot.setPadding(0, dp(12), 0, 0);
        foot.setText("点右侧「复制」把路径放进剪贴板，粘贴到文件管理器的地址栏就能打开。\n"
                + "旧版本的图片存在应用私有目录，打开应用后会自动迁移到这里，无需 root。");
        box.addView(foot);

        new AlertDialog.Builder(this)
                .setTitle("存储位置")
                .setView(box)
                .setNegativeButton("关闭", null)
                .show();
    }

    private void addPathRow(LinearLayout parent, String title, String subtitle, final String path) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(12);
        row.setLayoutParams(rlp);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView t = new TextView(this);
        t.setTextSize(13);
        t.setTextColor(getResources().getColor(R.color.text_primary));
        t.setText(title + "  " + subtitle);
        text.addView(t);

        TextView p = new TextView(this);
        p.setTextSize(11);
        p.setTextColor(getResources().getColor(R.color.text_secondary));
        // 路径没有空格，默认不会换行；强制按字符换行，否则长路径会被截掉尾巴，
        // 而尾巴正是「附件在哪个子目录」这个关键信息
        p.setSingleLine(false);
        p.setText(path == null ? "（不可用）" : path);
        text.addView(p);
        row.addView(text);

        TextView btn = new TextView(this);
        btn.setText("复制");
        btn.setTextSize(13);
        btn.setTextColor(getResources().getColor(R.color.storage_att));
        btn.setPadding(dp(12), dp(6), dp(4), dp(6));
        btn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (path == null) {
                    Toast.makeText(StorageActivity.this, "路径不可用", Toast.LENGTH_SHORT).show();
                    return;
                }
                copy(path);
            }
        });
        row.addView(btn);

        parent.addView(row);
    }

    private void copy(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("path", text));
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "复制失败", Toast.LENGTH_SHORT).show();
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
