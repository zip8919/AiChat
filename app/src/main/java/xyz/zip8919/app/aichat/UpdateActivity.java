package xyz.zip8919.app.aichat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 版本更新页：GitHub 最新 Release + 加速镜像测速 + 下载安装。
 *
 * <p>258dp 窄屏约束：顶部信息固定、镜像列表滚动、底部操作栏固定；
 * 镜像只有 7~9 项且不会无限增长，用动态 LinearLayout 行而非 ListView——
 * 省掉一层 Adapter，也避免选中/测速刷新时整表重建导致的滚动跳动。
 */
public class UpdateActivity extends Activity implements UpdateManager.Callback {

    private static final String TAG = "UpdateActivity";

    private TextView statusView, assetView, progressText;
    private ProgressBar progress;
    private Button btnRefresh, btnNotes, btnSwitchAsset, btnProbe, btnAdd;
    private Button btnCustomOk, btnAction;
    private LinearLayout mirrorList, customBox;
    private EditText customInput;

    private List<UpdateManager.Mirror> mirrors;
    private UpdateManager.Release release;
    private UpdateManager.Asset asset;
    private int selectedIndex;
    private File apkFile;

    private boolean destroyed;
    private boolean downloading;
    /** 切换安装包后，正在跑的测速结果作废，避免旧包延迟串到新包并自动改选镜像。 */
    private boolean probeStale;

    // 行视图缓存：测速/选中只刷新单行文本，不重建列表
    private final List<TextView> marks = new ArrayList<TextView>();
    private final List<TextView> nameViews = new ArrayList<TextView>();
    private final List<TextView> latencyViews = new ArrayList<TextView>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_update);

        statusView = (TextView) findViewById(R.id.update_status);
        assetView = (TextView) findViewById(R.id.update_asset);
        progressText = (TextView) findViewById(R.id.update_progress_text);
        progress = (ProgressBar) findViewById(R.id.update_progress);
        btnRefresh = (Button) findViewById(R.id.btn_refresh);
        btnNotes = (Button) findViewById(R.id.btn_notes);
        btnSwitchAsset = (Button) findViewById(R.id.btn_switch_asset);
        btnProbe = (Button) findViewById(R.id.btn_probe);
        btnAdd = (Button) findViewById(R.id.btn_add);
        btnCustomOk = (Button) findViewById(R.id.btn_custom_ok);
        btnAction = (Button) findViewById(R.id.btn_action);
        mirrorList = (LinearLayout) findViewById(R.id.mirror_list);
        customBox = (LinearLayout) findViewById(R.id.custom_box);
        customInput = (EditText) findViewById(R.id.custom_input);

        mirrors = UpdateManager.loadMirrors(this);
        selectedIndex = indexOfTemplate(UpdateManager.selectedTemplate(this));
        buildRows();
        bindActions();
        check();
        LogUtil.d(TAG, "onCreate: mirrors=%d selected=%d", mirrors.size(), selectedIndex);
    }

    // ==================== 初始化与行渲染 ====================

    private int indexOfTemplate(String template) {
        for (int i = 0; i < mirrors.size(); i++) {
            if (mirrors.get(i).template.equals(template)) return i;
        }
        return 0; // 默认直连
    }

    private void buildRows() {
        mirrorList.removeAllViews();
        marks.clear();
        nameViews.clear();
        latencyViews.clear();
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);

        for (int i = 0; i < mirrors.size(); i++) {
            final int idx = i;
            final UpdateManager.Mirror m = mirrors.get(i);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setMinimumHeight(dp(48));
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(4), dp(4), dp(4), dp(4));
            if (tv.resourceId != 0) row.setBackgroundResource(tv.resourceId);

            TextView mark = new TextView(this);
            mark.setTextSize(14);
            mark.setWidth(dp(22));
            row.addView(mark);
            marks.add(mark);

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView name = new TextView(this);
            name.setText(m.name);
            name.setTextSize(13);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(name);
            nameViews.add(name);

            if (m.custom) {
                TextView sub = new TextView(this);
                sub.setText(m.template);
                sub.setTextSize(10);
                sub.setTextColor(getResources().getColor(R.color.text_secondary));
                sub.setSingleLine(true);
                sub.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                col.addView(sub);
            }
            row.addView(col);

            TextView latency = new TextView(this);
            latency.setTextSize(11);
            latency.setWidth(dp(64));
            latency.setGravity(Gravity.RIGHT);
            latency.setSingleLine(true);
            row.addView(latency);
            latencyViews.add(latency);

            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    selectMirror(idx);
                }
            });
            if (m.custom) {
                row.setOnLongClickListener(new View.OnLongClickListener() {
                    public boolean onLongClick(View v) {
                        confirmRemove(m);
                        return true;
                    }
                });
            }
            mirrorList.addView(row);
        }
        for (int i = 0; i < mirrors.size(); i++) refreshRow(i);
    }

    private void refreshRow(int i) {
        if (i < 0 || i >= mirrors.size() || i >= marks.size()) return;
        UpdateManager.Mirror m = mirrors.get(i);
        boolean sel = (i == selectedIndex);
        int primary = getResources().getColor(R.color.primary);
        int textPrimary = getResources().getColor(R.color.text_primary);
        int secondary = getResources().getColor(R.color.text_secondary);

        marks.get(i).setText(sel ? "●" : "○");
        marks.get(i).setTextColor(sel ? primary : secondary);
        nameViews.get(i).setTextColor(sel ? primary : textPrimary);
        nameViews.get(i).setTypeface(null, sel ? Typeface.BOLD : Typeface.NORMAL);

        int l = m.latency;
        latencyViews.get(i).setText(latencyText(l));
        latencyViews.get(i).setTextColor(
                l == UpdateManager.FAILED ? getResources().getColor(R.color.danger)
                        : (l >= 0 ? primary : secondary));
    }

    private static String latencyText(int ms) {
        if (ms == UpdateManager.NOT_TESTED) return "—";
        if (ms == UpdateManager.FAILED) return "不可用";
        return ms + " ms";
    }

    private void selectMirror(int i) {
        selectedIndex = i;
        UpdateManager.setSelectedTemplate(this, mirrors.get(i).template);
        for (int j = 0; j < mirrors.size(); j++) refreshRow(j);
        if (!downloading) {
            progressText.setText("已选 " + mirrors.get(i).name + "，点「下载」开始");
        }
    }

    // ==================== 事件绑定 ====================

    private void bindActions() {
        btnRefresh.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { check(); }
        });
        btnNotes.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showNotes(); }
        });
        btnSwitchAsset.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { chooseAsset(); }
        });
        btnProbe.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { runProbe(); }
        });
        btnAdd.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean show = customBox.getVisibility() != View.VISIBLE;
                customBox.setVisibility(show ? View.VISIBLE : View.GONE);
                if (show) customInput.requestFocus();
            }
        });
        btnCustomOk.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { addCustom(); }
        });
        btnAction.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (downloading) {
                    UpdateManager.cancel();
                } else if (apkFile != null && apkFile.isFile()) {
                    install();
                } else {
                    startDownload();
                }
            }
        });
    }

    // ==================== 检查 / 测速 ====================

    private void check() {
        if (downloading) return;
        apkFile = null;
        release = null;
        asset = null;
        btnAction.setText("下载");
        btnAction.setEnabled(false);
        btnNotes.setEnabled(false);
        btnSwitchAsset.setEnabled(false);
        statusView.setText("正在检查更新…");
        assetView.setText("将下载：—");
        progressText.setText("检查中，请稍候");
        UpdateManager.resetCancel();
        UpdateManager.fetchLatest(this);
    }

    @Override
    public void onRelease(UpdateManager.Release r, String err) {
        if (destroyed) return;
        if (err != null || r == null) {
            statusView.setText("检查失败：" + err + "\n点「重新检查」重试");
            progressText.setText("网络异常时仍可自定义镜像");
            btnAction.setEnabled(false);
            return;
        }
        release = r;
        asset = UpdateManager.pickAsset(r);
        String cur = versionName();
        int cmp = UpdateManager.compareVersion(r.tag, cur);
        if (cmp > 0) {
            statusView.setText("发现新版本 " + r.tag + "\n当前 " + cur);
        } else if (cmp == 0) {
            statusView.setText("已是最新版 " + r.tag);
        } else {
            statusView.setText("线上 " + r.tag + " / 本机 " + cur + "（较新）");
        }
        btnNotes.setEnabled(r.body.length() > 0);
        btnSwitchAsset.setEnabled(!r.assets.isEmpty());
        btnAction.setEnabled(asset != null);
        assetView.setText(asset == null ? "无可用安装包"
                : "将下载：" + asset.name + "  " + formatSize(asset.size));
        runProbe();
    }

    private void runProbe() {
        if (asset == null || downloading) {
            Toast.makeText(this, "请先检查更新成功", Toast.LENGTH_SHORT).show();
            return;
        }
        probeStale = false;
        for (UpdateManager.Mirror m : mirrors) m.latency = UpdateManager.NOT_TESTED;
        for (int i = 0; i < mirrors.size(); i++) refreshRow(i);
        btnProbe.setEnabled(false);
        progressText.setText("正在逐项测速…（HTTP 首字节）");
        UpdateManager.probeAll(mirrors, asset.url, this);
    }

    @Override
    public void onProbeDone(int index, int latencyMs) {
        if (destroyed || probeStale) return;
        refreshRow(index);
    }

    @Override
    public void onProbeFinished() {
        if (destroyed || probeStale) return;
        btnProbe.setEnabled(true);
        int best = -1, min = Integer.MAX_VALUE;
        for (int i = 0; i < mirrors.size(); i++) {
            int l = mirrors.get(i).latency;
            if (l >= 0 && l < min) { min = l; best = i; }
        }
        if (best >= 0) {
            selectMirror(best);
            progressText.setText("已选最快：" + mirrors.get(best).name + "  " + min + " ms");
        } else {
            progressText.setText("全部镜像不可用，可试直连或自定义后重测");
        }
    }

    // ==================== 下载 / 安装 ====================

    private void startDownload() {
        if (asset == null || selectedIndex < 0 || selectedIndex >= mirrors.size()) return;
        UpdateManager.Mirror m = mirrors.get(selectedIndex);
        File dir = new File(StorageManager.getInstance(this).getExportsDir());
        int cleared = UpdateManager.clearOldApks(dir);
        String safeTag = release.tag.replaceAll("[^A-Za-z0-9._-]", "_");
        apkFile = new File(dir, "AiChat-" + safeTag + "-" + shortName(asset.name) + ".apk");
        String url = UpdateManager.applyMirror(m, asset.url);

        downloading = true;
        // 测速与下载已分属不同线程，点下载即开始。把在跑的测速作废：
        // 不再占连接，也不会在结束后自动改选镜像、覆盖本次下载正在用的那个。
        probeStale = true;
        UpdateManager.cancelProbe();
        btnAction.setText("取消下载");
        btnRefresh.setEnabled(false);
        btnProbe.setEnabled(false);
        btnAdd.setEnabled(false);
        btnSwitchAsset.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        progress.setProgress(0);
        progressText.setText("下载中…  经 " + m.name + "（已清理 " + cleared + " 个旧包）");
        LogUtil.i(TAG, "startDownload: mirror=%s url=%s", m.name, url);
        UpdateManager.resetCancel();
        UpdateManager.download(url, apkFile, asset.size, this);
    }

    @Override
    public void onProgress(long done, long total) {
        if (destroyed) return;
        if (total > 0) {
            int p = (int) (done * 100 / total);
            progress.setProgress(p);
            progressText.setText("下载中 " + p + "%   "
                    + formatSize(done) + " / " + formatSize(total));
        } else {
            progressText.setText("下载中  " + formatSize(done));
        }
    }

    @Override
    public void onDownloaded(File f, String err) {
        if (destroyed) return;
        downloading = false;
        btnRefresh.setEnabled(true);
        btnProbe.setEnabled(true);
        btnAdd.setEnabled(true);
        btnSwitchAsset.setEnabled(true);
        progress.setVisibility(View.GONE);
        if (err != null || f == null) {
            apkFile = null;
            btnAction.setText("重试下载");
            progressText.setText("下载失败：" + err + "（换个镜像或直连再试）");
            return;
        }
        apkFile = f;
        btnAction.setText("安装");
        progressText.setText("下载完成 " + formatSize(f.length()) + "，点「安装」");
    }

    private void install() {
        if (apkFile == null || !apkFile.isFile()) {
            Toast.makeText(this, "安装包不存在", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            // 4.4 的 PackageInstaller intent-filter 只声明 file scheme，不认 content://，
            // 用 content:// 会直接 ActivityNotFoundException。targetSdk 18 也不会触发
            // FileUriExposedException，所以这里必须走 file://。
            // N+ 反过来禁止跨应用暴露 file://，才用 FilePathProvider 的 content://。
            Uri uri;
            if (Build.VERSION.SDK_INT >= 24) {
                uri = FileActions.uriOf(apkFile);          // APK 在 exports，provider 能解析
            } else {
                uri = Uri.fromFile(worldReadable(apkFile));
            }
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            if (Build.VERSION.SDK_INT >= 24) {
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            LogUtil.i(TAG, "install: %s", apkFile.getName());
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "未找到系统安装器，请用文件管理器打开已下载的 APK",
                    Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            LogUtil.w(TAG, "install failed: " + t.getMessage());
            Toast.makeText(this, "调起安装失败", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 外部存储可用时，APK 落在 Android/data/&lt;包名&gt;/files/exports，安装器凭
     * READ_EXTERNAL_STORAGE 就能读 file://，原样返回。
     * 只有当 StorageManager 回退到内部私有目录（/data/data/...）时才需要复制——
     * 那个目录其他 uid 进不去，安装器拿到 file:// 也读不到字节。
     */
    private File worldReadable(File src) {
        String internal = getFilesDir().getAbsolutePath();
        if (!src.getAbsolutePath().startsWith(internal)) return src;

        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) return src;
        if (!dir.isDirectory() && !dir.mkdirs()) return src;
        File dst = new File(dir, src.getName());
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(dst);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            LogUtil.i(TAG, "worldReadable: copied to %s", dst.getAbsolutePath());
            return dst;
        } catch (IOException e) {
            LogUtil.w(TAG, "worldReadable copy failed: %s", e.getMessage());
            return src;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable t) {}
            try { if (out != null) out.close(); } catch (Throwable t) {}
        }
    }

    // ==================== 自定义镜像 / 弹层 ====================

    private void addCustom() {
        String t = UpdateManager.addCustomMirror(this, customInput.getText().toString());
        if (t == null) {
            Toast.makeText(this, "请填写有效的镜像地址", Toast.LENGTH_SHORT).show();
            return;
        }
        customInput.setText("");
        customBox.setVisibility(View.GONE);
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(customInput.getWindowToken(), 0);
        } catch (Throwable e) {}
        mirrors = UpdateManager.loadMirrors(this);
        selectedIndex = mirrors.size() - 1;
        UpdateManager.setSelectedTemplate(this, t);
        buildRows();
        progressText.setText("已添加：" + t + "\n点「测速」验证可用性");
        LogUtil.i(TAG, "addCustom: selected=%d", selectedIndex);
    }

    private void confirmRemove(final UpdateManager.Mirror m) {
        new AlertDialog.Builder(this)
                .setTitle("删除自定义镜像")
                .setMessage(m.name + "\n" + m.template)
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        UpdateManager.removeCustomMirror(UpdateActivity.this, m.template);
                        mirrors = UpdateManager.loadMirrors(UpdateActivity.this);
                        String sel = UpdateManager.selectedTemplate(UpdateActivity.this);
                        selectedIndex = indexOfTemplate(sel);
                        buildRows();
                        progressText.setText("已删除 " + m.name);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showNotes() {
        if (release == null) return;
        String body = release.body;
        if (body.length() > 4000) body = body.substring(0, 4000) + "\n…（完整说明见 GitHub）";
        new AlertDialog.Builder(this)
                .setTitle(release.name.length() > 0 ? release.name : release.tag)
                .setMessage(body)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void chooseAsset() {
        if (release == null || release.assets.isEmpty()) return;
        final List<UpdateManager.Asset> list = release.assets;
        String[] names = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            names[i] = shortName(list.get(i).name) + "  " + formatSize(list.get(i).size);
        }
        new AlertDialog.Builder(this)
                .setTitle("选择安装包")
                .setItems(names, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        probeStale = true;
                        UpdateManager.cancelProbe();
                        UpdateManager.cancel();
                        btnProbe.setEnabled(true);
                        asset = list.get(w);
                        apkFile = null;
                        assetView.setText("将下载：" + asset.name + "  " + formatSize(asset.size));
                        btnAction.setText("下载");
                        progressText.setText("已切换，建议重新测速");
                        for (UpdateManager.Mirror m : mirrors) {
                            m.latency = UpdateManager.NOT_TESTED;
                        }
                        for (int i = 0; i < mirrors.size(); i++) refreshRow(i);
                    }
                })
                .show();
    }

    // ==================== 工具 ====================

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private static String shortName(String n) {
        String s = n;
        if (s.startsWith("app-")) s = s.substring(4);
        if (s.endsWith("-release.apk")) s = s.substring(0, s.length() - 12);
        return s;
    }

    private static String formatSize(long b) {
        if (b < 1024) return b + " B";
        if (b < 1048576) return String.format(Locale.US, "%.0f KB", b / 1024f);
        return String.format(Locale.US, "%.2f MB", b / 1048576f);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        // 不 resetCancel：排队中的任务应看到 cancelled=true 直接退出，避免页面销毁后继续联网
        UpdateManager.cancel();
        UpdateManager.cancelProbe();
        super.onDestroy();
    }
}
