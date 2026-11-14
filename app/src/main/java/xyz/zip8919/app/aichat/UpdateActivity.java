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
import android.view.ViewGroup;
import android.view.LayoutInflater;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
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
public class UpdateActivity extends ThemedActivity implements UpdateManager.Callback {

    private static final String TAG = "UpdateActivity";

    private TextView statusView, assetView, progressText;
    private ProgressBar progress;
    private Button btnRefresh, btnNotes, btnSwitchAsset, btnProbe, btnAdd;
    private Button btnCustomOk, btnAction;
    private ListView mirrorList;
    private LinearLayout customBox;
    private MirrorAdapter mirrorAdapter;
    private EditText customInput;

    private List<UpdateManager.Mirror> mirrors;
    private UpdateManager.Release release;
    private UpdateManager.Asset asset;
    private int selectedIndex;
    private File apkFile;
    /** 4.4 安装准备（拷 APK 到公共目录）是否在进行；切换包/重新检查时置 false，防止弹出切换前旧包的安装器。 */
    private volatile boolean installPending;

    private boolean destroyed;
    private boolean downloading;
    /** 切换安装包后，正在跑的测速结果作废，避免旧包延迟串到新包并自动改选镜像。 */
    private boolean probeStale;
    /** 用户本轮测速期间是否手动点选过镜像；点过就不再被「自动选最快」覆盖。 */
    private boolean manualPicked;
    /** 本轮只测某一个源：结束时不做「自动选最快」。 */
    private boolean probeSingleMode;
    private int probeSingleIndex = -1;
    /** 本轮已完成测速的源数，用于显示 x/N 进度。 */
    private int probeDone;


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
        mirrorList = (ListView) findViewById(R.id.mirror_list);
        customBox = (LinearLayout) findViewById(R.id.custom_box);
        customInput = (EditText) findViewById(R.id.custom_input);

        mirrors = UpdateManager.loadMirrors(this);
        selectedIndex = indexOfTemplate(UpdateManager.selectedTemplate(this));
        setupMirrorList();
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

    private void setupMirrorList() {
        mirrorAdapter = new MirrorAdapter();
        mirrorList.setAdapter(mirrorAdapter);
        mirrorList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                manualPicked = true;
                selectMirror(pos);
            }
        });
        mirrorList.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                showMirrorMenu(pos);
                return true;
            }
        });
    }

    /** 只刷新可见行：测速每完成一项调一次，84 项全表 notify 会让列表一直重排。 */
    private void refreshRow(int i) {
        if (mirrorList == null || i < 0 || i >= mirrors.size()) return;
        int first = mirrorList.getFirstVisiblePosition();
        View row = mirrorList.getChildAt(i - first);
        if (row != null) bindRow(row, i);
    }

    private void refreshAllRows() {
        if (mirrorList == null) return;
        int first = mirrorList.getFirstVisiblePosition();
        int last = mirrorList.getLastVisiblePosition();
        for (int i = first; i <= last; i++) {
            if (i >= 0 && i < mirrors.size()) refreshRow(i);
        }
    }

    private void bindRow(View row, int i) {
        if (row == null || i < 0 || i >= mirrors.size()) return;
        UpdateManager.Mirror m = mirrors.get(i);
        boolean sel = (i == selectedIndex);
        AppearanceManager am = AppearanceManager.getInstance(this);
        boolean cDef = am.isColorDefault();
        boolean cDark = am.isDark();
        int primary = cDef ? getResources().getColor(R.color.primary)
                : am.getEffectiveColor(ThemeTokens.AC_PRIMARY, cDark);
        int textPrimary = cDef ? getResources().getColor(R.color.text_primary)
                : am.getEffectiveColor(ThemeTokens.AC_TEXT, cDark);
        int secondary = cDef ? getResources().getColor(R.color.text_secondary)
                : am.getEffectiveColor(ThemeTokens.AC_TEXT_SECONDARY, cDark);
        int danger = cDef ? getResources().getColor(R.color.danger)
                : am.getEffectiveColor(ThemeTokens.AC_DANGER, cDark);

        TextView mark = (TextView) row.findViewById(R.id.mirror_mark);
        TextView name = (TextView) row.findViewById(R.id.mirror_name);
        TextView sub = (TextView) row.findViewById(R.id.mirror_sub);
        TextView latency = (TextView) row.findViewById(R.id.mirror_latency);
        mark.setText(sel ? "●" : "○");
        mark.setTextColor(sel ? primary : secondary);
        name.setText(m.name);
        name.setTextColor(sel ? primary : textPrimary);
        name.setTypeface(null, sel ? Typeface.BOLD : Typeface.NORMAL);
        if (m.template.length() > 0) {
            sub.setText(m.template);
            sub.setTextColor(secondary);
            sub.setVisibility(View.VISIBLE);
        } else {
            sub.setVisibility(View.GONE);
        }
        int l = m.latency;
        latency.setText(latencyText(l));
        latency.setTextColor(l == UpdateManager.FAILED ? danger
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
        refreshAllRows();
        if (!downloading) {
            progressText.setText("已选 " + mirrors.get(i).name + "，点「下载」开始");
        }
    }

    /** 列表整体变更后重建数据，并按模板重新定位选中项。 */
    private void reloadMirrors(String selectTemplate) {
        invalidateProbe();
        mirrors = UpdateManager.loadMirrors(this);
        String sel = selectTemplate != null ? selectTemplate : UpdateManager.selectedTemplate(this);
        selectedIndex = indexOfTemplate(sel);
        if (mirrorAdapter != null) mirrorAdapter.notifyDataSetChanged();
    }

    private String testUrl() {
        return release != null ? release.speedTestUrl : "";
    }

    private final class MirrorAdapter extends BaseAdapter {
        public int getCount() { return mirrors.size(); }
        public Object getItem(int position) { return mirrors.get(position); }
        public long getItemId(int position) { return position; }
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = LayoutInflater.from(UpdateActivity.this)
                        .inflate(R.layout.item_update_mirror, parent, false);
            }
            bindRow(row, position);
            return row;
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
                if (show) {
                    // 这个输入区是内联的，不是弹框；里面 input 的背景写死 @color/white，
                    // 深色下会突兀地白一块，这里补一次着色。
                    ThemeRecolor.applyTo(customBox);
                    customInput.requestFocus();
                }
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
        // 作废在跑的测速：否则旧代 onProbeFinished 会把进度文案改成「已选最快」
        // 并自动改选镜像、覆盖用户此前选择，与本次检查（可能失败）的状态自相矛盾。
        invalidateProbe();
        installPending = false;
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
        // 内置源已扩到 80+ 项：不再进页面就自动跑一次全量测速。
        // 那是分钟级长任务，还会把用户上次选中的源悄悄换成某个陌生域名。
        // 改成明确提示，测速由用户点按钮或长按单个源触发。
        progressText.setText(asset == null ? "无可用安装包"
                : "点「测速」验证 " + mirrors.size() + " 个加速源，或长按单独测");
    }

    /**
     * 作废正在进行的测速：镜像列表被整体替换前必须调用。
     * 只置 stale + 取消不再需要的连接，不动 UI 之外的状态。
     */
    private void invalidateProbe() {
        probeStale = true;
        UpdateManager.cancelProbe();
        btnProbe.setEnabled(true);
    }

    private void runProbe() {
        if (asset == null || downloading) {
            AcToast.show(this, "请先检查更新成功");
            return;
        }
        probeStale = false;
        probeSingleMode = false;
        probeDone = 0;
        manualPicked = false;
        for (UpdateManager.Mirror m : mirrors) m.latency = UpdateManager.NOT_TESTED;
        refreshAllRows();
        btnProbe.setEnabled(false);
        progressText.setText("正在测速 0/" + mirrors.size()
                + "…（下载并校验测速文件）");
        UpdateManager.probeAll(mirrors, asset.url, testUrl(), this);
    }

    /** 长按菜单里的「测速此源」：只跑一个，结束时不动选中项。 */
    private void probeSingle(final int i) {
        if (asset == null || release == null) {
            AcToast.show(this, "请先检查更新成功，再测速");
            return;
        }
        invalidateProbe();          // 作废可能正在跑的全量测速
        probeStale = false;          // 本轮自身是有效的
        probeSingleMode = true;
        probeSingleIndex = i;
        probeDone = 0;
        for (UpdateManager.Mirror m : mirrors) m.latency = UpdateManager.NOT_TESTED;
        refreshAllRows();
        btnProbe.setEnabled(false);
        progressText.setText("正在测速 " + mirrors.get(i).name + "…");
        UpdateManager.probeOne(mirrors.get(i), i, asset.url, testUrl(), this);
    }

    @Override
    public void onProbeDone(int index, int latencyMs) {
        if (destroyed || probeStale) return;
        refreshRow(index);
        if (!probeSingleMode) {
            probeDone++;
            progressText.setText("正在测速 " + probeDone + "/" + mirrors.size()
                    + "…（下载并校验测速文件）");
        }
    }

    @Override
    public void onProbeFinished() {
        if (destroyed || probeStale) return;
        btnProbe.setEnabled(true);
        if (probeSingleMode) {
            probeSingleMode = false;
            int i = probeSingleIndex;
            if (i >= 0 && i < mirrors.size()) {
                int l = mirrors.get(i).latency;
                progressText.setText(mirrors.get(i).name + " 测速："
                        + (l == UpdateManager.FAILED ? "不可用（未通过文件校验）" : l + " ms"));
            }
            probeSingleIndex = -1;
            return;
        }
        int best = -1, min = Integer.MAX_VALUE;
        for (int i = 0; i < mirrors.size(); i++) {
            int l = mirrors.get(i).latency;
            if (l >= 0 && l < min) { min = l; best = i; }
        }
        if (best >= 0) {
            if (manualPicked && selectedIndex >= 0 && selectedIndex < mirrors.size()) {
                // 用户测速期间已手动点选，尊重用户选择，只用文字告知最快项
                progressText.setText("测速完成，最快：" + mirrors.get(best).name + " " + min
                        + " ms（保留你手动选择的 " + mirrors.get(selectedIndex).name + "）");
            } else {
                selectMirror(best);
                progressText.setText("已选最快：" + mirrors.get(best).name + "  " + min + " ms");
            }
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
        installPending = false;
        apkFile = f;
        btnAction.setText("安装");
        progressText.setText("下载完成 " + formatSize(f.length()) + "，点「安装」");
    }

    private void install() {
        if (apkFile == null || !apkFile.isFile()) {
            AcToast.show(this, "安装包不存在");
            return;
        }
        final File src = apkFile;
        // N+ 用 FileProvider 的 content://，本身就能授权读内部私有目录，不需要任何拷贝。
        // 只有 4.4（<24）只认 file://、且安装器以别的 uid 读不到 /data/data 时，
        // 才需要把 5MB APK 拷到公共 Download —— 那是 4.4 老机 + 慢卡上 0.5~1.5s 的同步 IO，
        // 原来在点击回调里直接跑，用户点「安装」界面先僵住，现在放后台。
        if (Build.VERSION.SDK_INT >= 24) {
            launchInstaller(src);
            return;
        }
        installPending = true;
        btnAction.setEnabled(false);
        new Thread(new Runnable() {
            public void run() {
                File ready = null;
                try {
                    ready = worldReadable(src);
                } catch (Throwable t) {
                    // 后台线程未捕获异常会被 CrashCatcher 交给系统默认 handler killProcess，
                    // 整个 App 闪退；这里兜住，转成界面上的明确提示。
                    LogUtil.w(TAG, "apk prepare failed: " + t.getMessage());
                }
                final File result = ready;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        btnAction.setEnabled(true);
                        if (!installPending) return;   // 期间切换了安装包/重新检查：作废，别弹旧包
                        installPending = false;
                        if (result == null) {
                            AcToast.showLong(UpdateActivity.this,
                                    "无法准备安装包，请到「已保存文件」手动打开，或检查存储后重试");
                            return;
                        }
                        launchInstaller(result);
                    }
                });
            }
        }, "apk-install").start();
    }

    /** 在 UI 线程调起系统安装器。 */
    private void launchInstaller(File apkFile) {
        try {
            // 4.4 的 PackageInstaller intent-filter 只声明 file scheme，不认 content://，
            // 用 content:// 会直接 ActivityNotFoundException。targetSdk 18 也不会触发
            // FileUriExposedException，所以这里必须走 file://。
            // N+ 反过来禁止跨应用暴露 file://，才用 FilePathProvider 的 content://。
            Uri uri;
            if (Build.VERSION.SDK_INT >= 24) {
                uri = FileActions.uriOf(apkFile);          // APK 在 exports，provider 能解析
            } else {
                // 传进来的已经是 worldReadable() 的产物，这里不能再拷一次
                uri = Uri.fromFile(apkFile);
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
            AcToast.showLong(this, "未找到系统安装器，请用文件管理器打开已下载的 APK");
        } catch (Throwable t) {
            LogUtil.w(TAG, "install failed: " + t.getMessage());
            AcToast.show(this, "调起安装失败");
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
        File dst = null;
        InputStream in = null;
        OutputStream out = null;
        try {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) return null;
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            dst = new File(dir, src.getName());
            in = new FileInputStream(src);
            out = new FileOutputStream(dst);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            LogUtil.i(TAG, "worldReadable: copied to %s", dst.getAbsolutePath());
            return dst;
        } catch (Throwable t) {
            // 不只 IOException：SELinux 拒绝写公共目录、SecurityException、OOM 都会走到这里。
            // 任何失败都不能返回内部私有目录的 src —— 4.4 安装器以别的 uid 读不到
            // /data/data，会静默「解析包错误」而界面毫无提示。
            LogUtil.w(TAG, "worldReadable copy failed: %s", t.getMessage());
            if (dst != null) { try { if (dst.exists()) dst.delete(); } catch (Throwable ignored) {} }
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable t) {}
            try { if (out != null) out.close(); } catch (Throwable t) {}
        }
    }

    // ==================== 自定义镜像 / 弹层 ====================

    private void addCustom() {
        String t = UpdateManager.addCustomMirror(this, customInput.getText().toString());
        if (t == null) {
            AcToast.show(this, "请填写有效的镜像地址");
            return;
        }
        customInput.setText("");
        customBox.setVisibility(View.GONE);
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(customInput.getWindowToken(), 0);
        } catch (Throwable e) {}
        // 列表整体替换，reloadMirrors 内部会先作废旧测速
        UpdateManager.setSelectedTemplate(this, t);
        reloadMirrors(t);
        progressText.setText("已添加：" + t + "\n点「测速」验证可用性");
        LogUtil.i(TAG, "addCustom: selected=%d", selectedIndex);
    }

    // ---- 每个加速源的操作入口（长按）----
    //
    // 原来的列表只有「单击选中」，且仅自定义源能长按删除；内置源常年不可用
    // 却删不掉、改不了，也没法单独测一下，等于摆设。这里把操作补齐到所有源：
    // 内置源不允许直接改常量，编辑结果会另存为自定义源并隐藏原项。

    private void showMirrorMenu(final int i) {
        if (i < 0 || i >= mirrors.size()) return;
        final UpdateManager.Mirror m = mirrors.get(i);
        final boolean direct = m.template.length() == 0;   // 直连是兜底，不可移除
        final java.util.List<String> items = new ArrayList<String>();
        items.add("测速此源");
        items.add("编辑地址");
        items.add("复制地址");
        if (!direct) items.add(m.custom ? "删除此源" : "移除此源");
        items.add("恢复全部内置源");
        AcDialog.builder(this)
                .setTitle(m.name)
                .setItems(items.toArray(new String[items.size()]),
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                String action = items.get(w);
                                if ("测速此源".equals(action)) probeSingle(i);
                                else if ("编辑地址".equals(action)) editMirror(i);
                                else if ("复制地址".equals(action)) copyMirror(m);
                                else if ("删除此源".equals(action) || "移除此源".equals(action))
                                    confirmRemoveAt(i);
                                else restoreDefaults();
                            }
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    private void editMirror(final int i) {
        final UpdateManager.Mirror m = mirrors.get(i);
        final EditText input = new EditText(this);
        input.setText(m.template);
        input.setHint("https://域名/{url}");
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        AcDialog.builder(this)
                .setTitle("编辑加速源")
                .setView(input)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String raw = input.getText().toString();
                        if (raw.trim().length() == 0) {
                            AcToast.show(UpdateActivity.this, "地址不能为空");
                            return;
                        }
                        String t;
                        if (m.custom) {
                            t = UpdateManager.updateCustomMirror(
                                    UpdateActivity.this, m.template, raw);
                        } else {
                            t = UpdateManager.addCustomMirror(UpdateActivity.this, raw);
                            if (t != null) {
                                UpdateManager.hideBuiltin(UpdateActivity.this, m.template);
                            }
                        }
                        if (t == null) {
                            AcToast.show(UpdateActivity.this, "地址无效");
                            return;
                        }
                        UpdateManager.setSelectedTemplate(UpdateActivity.this, t);
                        reloadMirrors(t);
                        progressText.setText("已保存 " + t + "\n建议点「测速」验证");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void copyMirror(UpdateManager.Mirror m) {
        String text = m.template.length() > 0 ? m.template : "直连 github.com";
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("mirror", text));
            AcToast.show(this, "已复制");
        } catch (Throwable t) {
            AcToast.show(this, "复制失败");
        }
    }

    private void confirmRemoveAt(final int i) {
        final UpdateManager.Mirror m = mirrors.get(i);
        AcDialog.builder(this)
                .setTitle("移除加速源")
                .setMessage(m.name + "\n" + m.template
                        + "\n\n内置源可通过「恢复全部内置源」找回。")
                .setPositiveButton("移除", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if (m.custom) {
                            UpdateManager.removeCustomMirror(UpdateActivity.this, m.template);
                        } else {
                            UpdateManager.hideBuiltin(UpdateActivity.this, m.template);
                        }
                        reloadMirrors(null);
                        progressText.setText("已移除 " + m.name);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void restoreDefaults() {
        UpdateManager.restoreBuiltins(this);
        reloadMirrors(null);
        AcToast.show(this, "已恢复全部内置加速源");
    }

    private void showNotes() {
        if (release == null) return;
        String body = release.body;
        if (body.length() > 4000) body = TextKit.cut(body, 4000) + "\n…（完整说明见 GitHub）";
        AcDialog.builder(this)
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
        AcDialog.builder(this)
                .setTitle("选择安装包")
                .setItems(names, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        probeStale = true;
                        UpdateManager.cancelProbe();
                        UpdateManager.cancel();
                        btnProbe.setEnabled(true);
                        asset = list.get(w);
                        apkFile = null;
                        // 切换安装包时作废正在进行的旧包安装准备，并恢复按钮：
                        // 否则 install 的后台线程回来会弹出切换前那个包的安装器。
                        installPending = false;
                        btnAction.setEnabled(true);
                        assetView.setText("将下载：" + asset.name + "  " + formatSize(asset.size));
                        btnAction.setText("下载");
                        progressText.setText("已切换，建议重新测速");
                        for (UpdateManager.Mirror m : mirrors) {
                            m.latency = UpdateManager.NOT_TESTED;
                        }
                        refreshAllRows();
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
