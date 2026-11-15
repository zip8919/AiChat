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
import android.widget.CheckBox;
import android.widget.CompoundButton;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 版本更新页：GitHub 最新 Release + 加速镜像测速 + 下载安装。
 *
 * <p>258dp 窄屏约束：顶部信息固定、镜像列表滚动、底部操作栏固定。
 * 内置源已扩到 80+ 项，用 ListView + Adapter 复用行，不能再用动态 LinearLayout。
 *
 * <p>全量测速是「边测边排」：每项结果回来就按延迟重排一次，所以回调传 Mirror
 * 对象而不是列表下标；对话框打开期间用 openDialogs 计数暂停重排，避免菜单里
 * 捕获的行下标指错源。
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
    private CheckBox probeParallelCheck;
    private Button btnConcurrency;
    /** 本轮测速的模式文案，进度里复用，避免每个回调都去读一次偏好。 */
    private String probeModeLabel = "";

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
    /** 单源测速的目标对象。持有引用而不是下标：列表一旦重排，下标就指到别的源上。 */
    private UpdateManager.Mirror probeSingleMirror;
    /** 本轮已完成测速的源数，用于显示 x/N 进度。 */
    private int probeDone;
    /** 本轮是否正在跑全量测速（单源测速不算）；作废时同步置 false。 */
    private boolean probing;
    /** 打开的对话框计数：>0 时暂停实时重排，菜单/编辑框捕获的行下标才不会失效。 */
    private int openDialogs;
    /** 暂停期间有新结果到达：对话框全部关闭后补排一次。 */
    private boolean resortDirty;


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
        probeParallelCheck = (CheckBox) findViewById(R.id.probe_parallel_check);
        btnConcurrency = (Button) findViewById(R.id.btn_probe_concurrency);

        mirrors = UpdateManager.loadMirrors(this);
        selectedIndex = indexOfTemplate(UpdateManager.selectedTemplate(this));
        setupMirrorList();
        setupProbeOptions();
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

    // ==================== 并行测速设置 ====================

    private void setupProbeOptions() {
        final boolean parallel = UpdateManager.isParallelProbe(this);
        // 先置空监听再 setChecked：避免初始化时误触发一次 onCheckedChanged 弹 Toast
        probeParallelCheck.setOnCheckedChangeListener(null);
        probeParallelCheck.setChecked(parallel);
        btnConcurrency.setEnabled(parallel);
        refreshProbeOptionText();
        probeParallelCheck.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean on) {
                UpdateManager.setParallelProbe(UpdateActivity.this, on);
                btnConcurrency.setEnabled(on);
                refreshProbeOptionText();
                AcToast.show(UpdateActivity.this, on
                        ? Strings.get(R.string.updateactivity_001)
                        : Strings.get(R.string.updateactivity_002));
            }
        });
        btnConcurrency.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { editProbeConcurrency(); }
        });
    }

    private void refreshProbeOptionText() {
        boolean parallel = UpdateManager.isParallelProbe(this);
        btnConcurrency.setText(parallel
                ? Strings.get(R.string.updateactivity_003) + UpdateManager.probeConcurrency(this) : Strings.get(R.string.updateactivity_004));
    }

    /** 并发数 1~64，越界直接拒绝；老设备内存有限，文案里把风险说清楚。 */
    private void editProbeConcurrency() {
        final EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        input.setText(String.valueOf(UpdateManager.probeConcurrency(this)));
        input.setSelection(input.getText().length());
        final String range = UpdateManager.MIN_CONCURRENCY + "～" + UpdateManager.MAX_CONCURRENCY;
        showPaused(AcDialog.builder(this)
                .setTitle(Strings.get(R.string.updateactivity_005))
                .setMessage(Strings.get(R.string.updateactivity_105, range, UpdateManager.DEFAULT_CONCURRENCY))
                .setView(input)
                .setPositiveButton(Strings.get(R.string.updateactivity_010), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        int n;
                        try {
                            n = Integer.parseInt(input.getText().toString().trim());
                        } catch (Throwable t) {
                            AcToast.show(UpdateActivity.this, Strings.get(R.string.updateactivity_106, range));
                            return;
                        }
                        if (n < UpdateManager.MIN_CONCURRENCY || n > UpdateManager.MAX_CONCURRENCY) {
                            AcToast.show(UpdateActivity.this, Strings.get(R.string.updateactivity_106, range));
                            return;
                        }
                        UpdateManager.setProbeConcurrency(UpdateActivity.this, n);
                        refreshProbeOptionText();
                        AcToast.show(UpdateActivity.this,
                                Strings.get(R.string.updateactivity_015) + n + Strings.get(R.string.updateactivity_016));
                    }
                })
                .setNegativeButton(Strings.get(R.string.updateactivity_017), null)
                );
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
        if (ms == UpdateManager.FAILED) return Strings.get(R.string.updateactivity_018);
        return ms + " ms";
    }

    /**
     * 排序键：可用按延迟升序，未测次之，不可用垫底。
     *
     * <p>每收到一项测速结果就用它重排一次（边测边排）：一个源从「未测」变成
     * 可用会往前提，判成 FAILED 会沉到底。不能直接比 latency ——
     * NOT_TESTED(-1) 与 FAILED(-2) 都是负数，直接升序会把「不可用」顶到最前面。
     */
    private static int latencyRank(int ms) {
        if (ms >= 0) return ms;                            // 0 ~ 9999，越小越快
        if (ms == UpdateManager.NOT_TESTED) return 1000000;
        return 2000000;                                    // FAILED 及其它异常负值
    }

    private static final Comparator<UpdateManager.Mirror> LATENCY_ORDER =
            new Comparator<UpdateManager.Mirror>() {
                public int compare(UpdateManager.Mirror a, UpdateManager.Mirror b) {
                    int ra = latencyRank(a.latency), rb = latencyRank(b.latency);
                    return ra < rb ? -1 : (ra == rb ? 0 : 1);
                }
            };

    // ==================== 边测边排 ====================

    /**
     * 弹对话框期间暂停实时重排。
     *
     * <p>长按菜单、编辑框、移除确认都把行下标捕获进点击回调；列表一重排，
     * 「编辑地址」「移除此源」就会落到另一个源上。用计数器而不是布尔值：
     * 菜单里再点「编辑地址」会嵌套弹第二个 dialog，外层 dismiss 不能提前恢复。
     */
    private AlertDialog showPaused(AlertDialog.Builder b) {
        final AlertDialog d = b.show();
        if (d != null) {
            openDialogs++;
            d.setOnDismissListener(new DialogInterface.OnDismissListener() {
                public void onDismiss(DialogInterface dlg) {
                    if (openDialogs > 0) openDialogs--;
                    if (openDialogs == 0) flushResort();
                }
            });
        }
        return d;
    }

    /** 对话框全部关闭后，把暂停期间累积的结果一次性排进去。 */
    private void flushResort() {
        if (!resortDirty) return;
        resortDirty = false;
        // 不检查 probing：测速刚结束、对话框还开着时也要补这最后一次（幂等）
        if (destroyed || probeStale || probeSingleMode) return;
        resortMirrors(true);
    }

    /**
     * 按当前 latency 重排整个列表，并按对象引用重新定位选中项。
     *
     * <p>测速回调已由 UpdateManager post 回主线程，这里只跑在主线程；
     * 后台任务持有的是 Mirror 对象引用，不再读 mirrors 列表，排序不会与它们打架。
     * 80+ 项排序加上只重绘可见行，老设备开销可忽略，所以不做延迟合并——
     * 合并成下一帧反而可能与 onProbeFinished 抢顺序。
     */
    private void resortMirrors(boolean autoPick) {
        UpdateManager.Mirror keep = (selectedIndex >= 0 && selectedIndex < mirrors.size())
                ? mirrors.get(selectedIndex) : null;
        if (autoPick && !manualPicked) {
            UpdateManager.Mirror fastest = fastestMirror();
            if (fastest != null) {
                keep = fastest;
                // 只有真正换源才写偏好：80+ 次回调逐次落盘没必要
                if (!fastest.template.equals(UpdateManager.selectedTemplate(this))) {
                    UpdateManager.setSelectedTemplate(this, fastest.template);
                }
            }
        }
        Collections.sort(mirrors, LATENCY_ORDER);
        selectedIndex = keep == null ? 0 : mirrors.indexOf(keep);
        if (selectedIndex < 0) selectedIndex = 0;
        if (mirrorAdapter != null) mirrorAdapter.notifyDataSetChanged();
    }

    /** 当前已出结果里延迟最小的源；没有可用源时返回 null。 */
    private UpdateManager.Mirror fastestMirror() {
        int min = Integer.MAX_VALUE;
        UpdateManager.Mirror best = null;
        for (UpdateManager.Mirror m : mirrors) {
            if (m.latency >= 0 && m.latency < min) { min = m.latency; best = m; }
        }
        return best;
    }

    private void selectMirror(int i) {
        selectedIndex = i;
        UpdateManager.setSelectedTemplate(this, mirrors.get(i).template);
        refreshAllRows();
        if (!downloading) {
            progressText.setText(Strings.get(R.string.updateactivity_019) + mirrors.get(i).name + Strings.get(R.string.updateactivity_020));
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
        btnAction.setText(Strings.get(R.string.updateactivity_021));
        btnAction.setEnabled(false);
        btnNotes.setEnabled(false);
        btnSwitchAsset.setEnabled(false);
        statusView.setText(Strings.get(R.string.updateactivity_022));
        assetView.setText(Strings.get(R.string.updateactivity_023));
        progressText.setText(Strings.get(R.string.updateactivity_024));
        UpdateManager.resetCancel();
        UpdateManager.fetchLatest(this);
    }

    @Override
    public void onRelease(UpdateManager.Release r, String err) {
        if (destroyed) return;
        if (err != null || r == null) {
            statusView.setText(Strings.get(R.string.updateactivity_025) + err + Strings.get(R.string.updateactivity_026));
            progressText.setText(Strings.get(R.string.updateactivity_027));
            btnAction.setEnabled(false);
            return;
        }
        release = r;
        asset = UpdateManager.pickAsset(r);
        String cur = versionName();
        int cmp = UpdateManager.compareVersion(r.tag, cur);
        if (cmp > 0) {
            statusView.setText(Strings.get(R.string.updateactivity_028) + r.tag + Strings.get(R.string.updateactivity_029) + cur);
        } else if (cmp == 0) {
            statusView.setText(Strings.get(R.string.updateactivity_030) + r.tag);
        } else {
            statusView.setText(Strings.get(R.string.updateactivity_031) + r.tag + Strings.get(R.string.updateactivity_032) + cur + Strings.get(R.string.updateactivity_033));
        }
        btnNotes.setEnabled(r.body.length() > 0);
        btnSwitchAsset.setEnabled(!r.assets.isEmpty());
        btnAction.setEnabled(asset != null);
        assetView.setText(asset == null ? Strings.get(R.string.updateactivity_034)
                : Strings.get(R.string.updateactivity_035) + asset.name + "  " + formatSize(asset.size));
        // 内置源已扩到 80+ 项：不再进页面就自动跑一次全量测速。
        // 那是分钟级长任务，还会把用户上次选中的源悄悄换成某个陌生域名。
        // 改成明确提示，测速由用户点按钮或长按单个源触发。
        progressText.setText(asset == null ? Strings.get(R.string.updateactivity_036)
                : Strings.get(R.string.updateactivity_037) + mirrors.size() + Strings.get(R.string.updateactivity_038));
    }

    /**
     * 作废正在进行的测速：镜像列表被整体替换前必须调用。
     * 只置 stale + 取消不再需要的连接，不动 UI 之外的状态。
     */
    private void invalidateProbe() {
        probeStale = true;
        // 作废后不能再补排：否则对话框关闭时 flushResort 会把旧代结果排进新列表
        probing = false;
        resortDirty = false;
        UpdateManager.cancelProbe();
        btnProbe.setEnabled(true);
    }

    private void runProbe() {
        if (asset == null || downloading) {
            AcToast.show(this, Strings.get(R.string.updateactivity_039));
            return;
        }
        // 先断开上一轮可能还在跑的连接：否则改了并发数立刻重测时，旧任务仍占着
        // 连接，新池的并发上限形同虚设。cancelProbe 会让旧代回调全部作废。
        UpdateManager.cancelProbe();
        final boolean parallel = UpdateManager.isParallelProbe(this);
        final int concurrency = UpdateManager.probeConcurrency(this);
        probeModeLabel = parallel ? Strings.get(R.string.updateactivity_040) + concurrency + Strings.get(R.string.updateactivity_041) : Strings.get(R.string.updateactivity_042);
        probeStale = false;
        probing = true;
        probeSingleMode = false;
        probeSingleMirror = null;
        probeDone = 0;
        manualPicked = false;
        resortDirty = false;
        for (UpdateManager.Mirror m : mirrors) m.latency = UpdateManager.NOT_TESTED;
        refreshAllRows();
        btnProbe.setEnabled(false);
        // 窄屏只放得下模式 + 排序状态；「下载并校验测速文件」写在常驻提示里
        progressText.setText(Strings.get(R.string.updateactivity_043) + mirrors.size()
                + "…（" + probeModeLabel + Strings.get(R.string.updateactivity_044));
        UpdateManager.probeAll(mirrors, asset.url, testUrl(),
                parallel, concurrency, this);
    }

    /** 长按菜单里的「测速此源」：只跑一个，结束时不动选中项。 */
    private void probeSingle(final int i) {
        if (asset == null || release == null) {
            AcToast.show(this, Strings.get(R.string.updateactivity_045));
            return;
        }
        final UpdateManager.Mirror target = mirrors.get(i);
        invalidateProbe();          // 作废可能正在跑的全量测速（只断连接，不动已测出的结果）
        probeStale = false;          // 本轮自身是有效的
        probing = false;             // 单源测速不参与全量排序
        probeModeLabel = Strings.get(R.string.updateactivity_046);
        probeSingleMode = true;
        probeSingleMirror = target;
        probeDone = 0;
        // 只清目标源自己那一条延迟。原来这里是「for (Mirror m : mirrors) m.latency =
        // NOT_TESTED」——整表清零，用户测一个源，其它几十个源已测出的 ms 全变成「—」。
        // latency 只活在内存对象里、不落盘，抹掉就再也回不来（重进页面同样是「—」），
        // 看起来就是「测速记录消失了」。全量测速才会整表重测，单源只该动一行。
        target.latency = UpdateManager.NOT_TESTED;
        int row = mirrors.indexOf(target);
        if (row >= 0) refreshRow(row);
        btnProbe.setEnabled(false);
        progressText.setText(Strings.get(R.string.updateactivity_047) + target.name + "…");
        UpdateManager.probeOne(target, asset.url, testUrl(), this);
    }

    @Override
    public void onProbeDone(UpdateManager.Mirror m, int latencyMs) {
        if (destroyed || probeStale) return;
        // 单源测速不重排：只刷新这一行的数值，其它源保留各自上次的结果，
        // 列表顺序也保持不动（重排只会让用户找不到刚才那一行）。
        if (probeSingleMode) {
            int i = mirrors.indexOf(m);
            if (i >= 0) refreshRow(i);
            return;
        }
        if (!probing) return;      // 全量测速已结束的迟到回调，不再动列表
        probeDone++;
        progressText.setText(Strings.get(R.string.updateactivity_048) + probeDone + "/" + mirrors.size()
                + "…（" + probeModeLabel + Strings.get(R.string.updateactivity_049));
        if (openDialogs > 0) {
            // 菜单/编辑框开着时列表必须停住，否则里面捕获的行下标会指错源。
            // 只刷这一行的数值，重排推迟到对话框全部关闭后 flushResort 补一次。
            resortDirty = true;
            int i = mirrors.indexOf(m);
            if (i >= 0) refreshRow(i);
            return;
        }
        resortMirrors(true);
    }

    @Override
    public void onProbeFinished() {
        if (destroyed || probeStale) return;
        probing = false;
        btnProbe.setEnabled(true);
        if (probeSingleMode) {
            probeSingleMode = false;
            UpdateManager.Mirror m = probeSingleMirror;
            probeSingleMirror = null;
            if (m != null) {
                int l = m.latency;
                progressText.setText(m.name + Strings.get(R.string.updateactivity_050)
                        + (l == UpdateManager.FAILED ? Strings.get(R.string.updateactivity_051) : l + " ms"));
            }
            return;
        }
        // 过程中已逐项排好；这里只在没有对话框压着时补一次兜底，
        // 若对话框还开着，交给它关闭时的 flushResort 处理。
        if (openDialogs == 0) {
            resortDirty = false;
            resortMirrors(true);
        } else {
            resortDirty = true;
        }

        UpdateManager.Mirror fastest = fastestMirror();
        String modeSuffix = probeModeLabel.length() > 0 ? "（" + probeModeLabel + "）" : "";
        if (fastest != null) {
            if (manualPicked) {
                // 用户测速期间手动点过源：自动跟随让位于手动选择，只文字告知最快项
                UpdateManager.Mirror keep = (selectedIndex >= 0 && selectedIndex < mirrors.size())
                        ? mirrors.get(selectedIndex) : null;
                progressText.setText(Strings.get(R.string.updateactivity_052) + fastest.name + " "
                        + fastest.latency + " ms" + modeSuffix + Strings.get(R.string.updateactivity_053)
                        + (keep == null ? "—" : keep.name) + "）");
            } else {
                progressText.setText(Strings.get(R.string.updateactivity_054) + fastest.name + "  "
                        + fastest.latency + " ms" + modeSuffix);
            }
        } else {
            progressText.setText(Strings.get(R.string.updateactivity_055));
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
        probing = false;
        resortDirty = false;
        UpdateManager.cancelProbe();
        btnAction.setText(Strings.get(R.string.updateactivity_056));
        btnRefresh.setEnabled(false);
        btnProbe.setEnabled(false);
        btnAdd.setEnabled(false);
        btnSwitchAsset.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        progress.setProgress(0);
        progressText.setText(Strings.get(R.string.updateactivity_057) + m.name + Strings.get(R.string.updateactivity_058) + cleared + Strings.get(R.string.updateactivity_059));
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
            progressText.setText(Strings.get(R.string.updateactivity_060) + p + "%   "
                    + formatSize(done) + " / " + formatSize(total));
        } else {
            progressText.setText(Strings.get(R.string.updateactivity_061) + formatSize(done));
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
            btnAction.setText(Strings.get(R.string.updateactivity_062));
            progressText.setText(Strings.get(R.string.updateactivity_063) + err + Strings.get(R.string.updateactivity_064));
            return;
        }
        installPending = false;
        apkFile = f;
        btnAction.setText(Strings.get(R.string.updateactivity_065));
        progressText.setText(Strings.get(R.string.updateactivity_066) + formatSize(f.length()) + Strings.get(R.string.updateactivity_067));
    }

    private void install() {
        if (apkFile == null || !apkFile.isFile()) {
            AcToast.show(this, Strings.get(R.string.updateactivity_068));
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
                                    Strings.get(R.string.updateactivity_069));
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
            AcToast.showLong(this, Strings.get(R.string.updateactivity_070));
        } catch (Throwable t) {
            LogUtil.w(TAG, "install failed: " + t.getMessage());
            AcToast.show(this, Strings.get(R.string.updateactivity_071));
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
            AcToast.show(this, Strings.get(R.string.updateactivity_072));
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
        progressText.setText(Strings.get(R.string.updateactivity_073) + t + Strings.get(R.string.updateactivity_074));
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
        items.add(Strings.get(R.string.updateactivity_075));
        items.add(Strings.get(R.string.updateactivity_076));
        items.add(Strings.get(R.string.updateactivity_077));
        if (!direct) items.add(m.custom ? Strings.get(R.string.updateactivity_078) : Strings.get(R.string.updateactivity_079));
        items.add(Strings.get(R.string.updateactivity_080));
        showPaused(AcDialog.builder(this)
                .setTitle(m.name)
                .setItems(items.toArray(new String[items.size()]),
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                if (w == 0) probeSingle(i);
                                else if (w == 1) editMirror(i);
                                else if (w == 2) copyMirror(m);
                                else if (w == 3 && !direct) confirmRemoveAt(i);
                                else restoreDefaults();
                            }
                        })
                .setNegativeButton(Strings.get(R.string.updateactivity_081), null)
                );
    }

    private void editMirror(final int i) {
        final UpdateManager.Mirror m = mirrors.get(i);
        final EditText input = new EditText(this);
        input.setText(m.template);
        input.setHint(Strings.get(R.string.updateactivity_082));
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        showPaused(AcDialog.builder(this)
                .setTitle(Strings.get(R.string.updateactivity_083))
                .setView(input)
                .setPositiveButton(Strings.get(R.string.updateactivity_084), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String raw = input.getText().toString();
                        if (raw.trim().length() == 0) {
                            AcToast.show(UpdateActivity.this, Strings.get(R.string.updateactivity_085));
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
                            AcToast.show(UpdateActivity.this, Strings.get(R.string.updateactivity_086));
                            return;
                        }
                        UpdateManager.setSelectedTemplate(UpdateActivity.this, t);
                        reloadMirrors(t);
                        progressText.setText(Strings.get(R.string.updateactivity_087) + t + Strings.get(R.string.updateactivity_088));
                    }
                })
                .setNegativeButton(Strings.get(R.string.updateactivity_089), null)
                );
    }

    private void copyMirror(UpdateManager.Mirror m) {
        String text = m.template.length() > 0 ? m.template : Strings.get(R.string.updateactivity_090);
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("mirror", text));
            AcToast.show(this, Strings.get(R.string.updateactivity_091));
        } catch (Throwable t) {
            AcToast.show(this, Strings.get(R.string.updateactivity_092));
        }
    }

    private void confirmRemoveAt(final int i) {
        final UpdateManager.Mirror m = mirrors.get(i);
        showPaused(AcDialog.builder(this)
                .setTitle(Strings.get(R.string.updateactivity_093))
                .setMessage(m.name + "\n" + m.template
                        + Strings.get(R.string.updateactivity_094))
                .setPositiveButton(Strings.get(R.string.updateactivity_095), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if (m.custom) {
                            UpdateManager.removeCustomMirror(UpdateActivity.this, m.template);
                        } else {
                            UpdateManager.hideBuiltin(UpdateActivity.this, m.template);
                        }
                        reloadMirrors(null);
                        progressText.setText(Strings.get(R.string.updateactivity_096) + m.name);
                    }
                })
                .setNegativeButton(Strings.get(R.string.updateactivity_097), null)
                );
    }

    private void restoreDefaults() {
        UpdateManager.restoreBuiltins(this);
        reloadMirrors(null);
        AcToast.show(this, Strings.get(R.string.updateactivity_098));
    }

    private void showNotes() {
        if (release == null) return;
        String body = release.body;
        if (body.length() > 4000) body = TextKit.cut(body, 4000) + Strings.get(R.string.updateactivity_099);
        showPaused(AcDialog.builder(this)
                .setTitle(release.name.length() > 0 ? release.name : release.tag)
                .setMessage(body)
                .setPositiveButton(Strings.get(R.string.updateactivity_100), null)
                );
    }

    private void chooseAsset() {
        if (release == null || release.assets.isEmpty()) return;
        final List<UpdateManager.Asset> list = release.assets;
        String[] names = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            names[i] = shortName(list.get(i).name) + "  " + formatSize(list.get(i).size);
        }
        showPaused(AcDialog.builder(this)
                .setTitle(Strings.get(R.string.updateactivity_101))
                .setItems(names, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        probeStale = true;
                        probing = false;
                        resortDirty = false;
                        UpdateManager.cancelProbe();
                        UpdateManager.cancel();
                        btnProbe.setEnabled(true);
                        asset = list.get(w);
                        apkFile = null;
                        // 切换安装包时作废正在进行的旧包安装准备，并恢复按钮：
                        // 否则 install 的后台线程回来会弹出切换前那个包的安装器。
                        installPending = false;
                        btnAction.setEnabled(true);
                        assetView.setText(Strings.get(R.string.updateactivity_102) + asset.name + "  " + formatSize(asset.size));
                        btnAction.setText(Strings.get(R.string.updateactivity_103));
                        progressText.setText(Strings.get(R.string.updateactivity_104));
                        for (UpdateManager.Mirror m : mirrors) {
                            m.latency = UpdateManager.NOT_TESTED;
                        }
                        refreshAllRows();
                    }
                })
                );
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
