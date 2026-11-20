package xyz.zip8919.app.aichat;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.DatePicker;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 使用统计页：今日 / 本周 / 本月 / 自定义区间 + 按模型 / 按服务商拆分。
 *
 * <p>数据来自 {@link UsageStats} 的本地事件流，不联网、不上报。
 * 会话数与轮次无法按模型拆分——一个会话可能中途切模型，归给谁都是错的，
 * 所以拆分区只列请求级指标。
 *
 * <p>自定义区间用「日期」而不是「最近 N 天」：用户问的通常是「上周三到周五
 * 那几次搜索花了多少」，那是个具体的日期范围，换算成天数反而要先自己数一遍。
 */
public class StatsActivity extends ThemedActivity {

    private static final String TAG = "StatsActivity";

    /** Spinner 里的「自定义」位置，与 period 取值区分开。 */
    private static final int PERIOD_CUSTOM = 3;

    private TextView statsText;
    private Spinner periodSpinner;
    private View customRow;
    private Button fromButton, toButton;

    /** 表格容器：概览 / 服务商 / 模型 / 工具 逐块塞进去。 */
    private LinearLayout content;
    private Spinner filterProvider, filterModel;
    /** 筛选值：{@code null} / 空串表示「全部」。 */
    private String selProvider = "";
    private String selModel = "";
    /** 上次渲染时出现过的服务商与模型，用于重建下拉且保住用户的选择。 */
    private final List<String> providerOptions = new ArrayList<String>();
    private final List<String> modelOptions = new ArrayList<String>();
    private boolean buildingFilters;

    private int period = UsageStats.PERIOD_DAY;
    /** 自定义区间，闭区间；只在 period == PERIOD_CUSTOM 时有效。 */
    private long customFrom, customTo;
    /** 上一次的合法选择，用户在日期选择框里点取消时回退到这里。 */
    private int lastPeriod = UsageStats.PERIOD_DAY;

    private final SimpleDateFormat dayFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stats);
        UsageStats.init(getApplicationContext());

        statsText = null;
        content = (LinearLayout) findViewById(R.id.stats_content);
        filterProvider = (Spinner) findViewById(R.id.stats_filter_provider);
        filterModel = (Spinner) findViewById(R.id.stats_filter_model);
        periodSpinner = (Spinner) findViewById(R.id.stats_period);
        customRow = findViewById(R.id.stats_custom_row);
        fromButton = (Button) findViewById(R.id.stats_from);
        toButton = (Button) findViewById(R.id.stats_to);
        setupFilterSpinners();

        long now = System.currentTimeMillis();
        customTo = now;
        long earliest = UsageStats.get().earliestTs();
        customFrom = UsageStats.dayStart(earliest > 0 ? earliest : now);

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item,
                new String[]{Strings.get(R.string.statsactivity_001), Strings.get(R.string.statsactivity_002), Strings.get(R.string.statsactivity_003), Strings.get(R.string.statsactivity_004)});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        periodSpinner.setAdapter(adapter);
        periodSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (pos == PERIOD_CUSTOM) {
                    // 选自定义不立刻切视图：先让用户定下两个端点，
                    // 否则中间会闪一帧「今天到现在」的旧数据。
                    // post 一下再弹：在 onItemSelected 里同步弹 Dialog，
                    // 下拉列表的收起动画会被打断、甚至留下一层残影。
                    periodSpinner.post(new Runnable() {
                        public void run() {
                            pickCustom();
                        }
                    });
                } else {
                    period = pos;
                    lastPeriod = pos;
                    customRow.setVisibility(View.GONE);
                    render();
                }
            }

            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        fromButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                pickDate(true, customFrom, false);
            }
        });
        toButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                pickDate(false, customTo, false);
            }
        });

        Button clear = (Button) findViewById(R.id.stats_clear);
        clear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                confirmClear();
            }
        });
        ((Button) findViewById(R.id.stats_close)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                finish();
            }
        });
        updateCustomButtons();
        render();
        // 冷启动统计已改为后台加载：加载完成后补一次，历史数据才能显示。
        UsageStats.get().whenLoaded(new Runnable() {
            public void run() {
                if (isFinishing() || isDestroyed()) return;
                long e = UsageStats.get().earliestTs();
                if (period != PERIOD_CUSTOM && e > 0) {
                    customFrom = UsageStats.dayStart(e);
                    updateCustomButtons();
                }
                render();
            }
        });
    }

    /** 进入自定义模式的入口：先选起始日，再接着选结束日。 */
    private void pickCustom() {
        pickDate(true, customFrom, true);
    }

    /**
     * 弹出日期选择。
     *
     * <p>{@code chainNext} 只在「刚从下拉框切到自定义」时为 true：那条路径上用户
     * 还没定过区间，连着问两个日期是省事；而点上面两个按钮改端点时再自动弹下一个，
     * 就变成了「我只想改起始日，它非要我再选一次结束日」。
     *
     * <p>取消时回到上一次的合法选择——Spinner 已经停在「自定义」上了，
     * 不显式 setSelection 回去的话，页面会卡在一个既不是今日也不是自定义的中间态。
     */
    private void pickDate(final boolean isFrom, long initial, final boolean chainNext) {
        // 这个方法会被 periodSpinner.post(...) 延迟调用：用户在选完「自定义」的
        // 同一帧按返回，就会在已结束的 Activity 上 show 对话框 → BadTokenException。
        if (isFinishing() || isDestroyed()) return;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(initial);
        // DatePickerDialog 是独立 Window，windowBackground 与内部文字都由构造它的
        // context 主题决定，Activity 里刷了多少 View 都影响不到它。深色下必须换
        // Holo 对话框主题，否则会在深色页面上弹出一块刺眼的白板。
        android.content.Context dlgCtx = AppearanceManager.getInstance(this).isDark()
                ? new android.view.ContextThemeWrapper(this, android.R.style.Theme_Holo_Dialog)
                : this;
        DatePickerDialog dlg = new DatePickerDialog(dlgCtx,
                new DatePickerDialog.OnDateSetListener() {
                    public void onDateSet(DatePicker view, int year, int month, int day) {
                        Calendar picked = Calendar.getInstance();
                        picked.set(year, month, day, 0, 0, 0);
                        picked.set(Calendar.MILLISECOND, 0);
                        long ts = picked.getTimeInMillis();
                        if (isFrom) {
                            customFrom = ts;
                            // 起点晚于终点 = 用户想反过来选，直接把终点顶到同一天，
                            // 而不是弹一个「区间无效」让人重选
                            if (customTo < customFrom) customTo = UsageStats.dayEnd(customFrom);
                        } else {
                            customTo = UsageStats.dayEnd(ts);
                            if (customFrom > customTo) customFrom = UsageStats.dayStart(ts);
                        }
                        if (isFrom && customTo == 0) customTo = UsageStats.dayEnd(customFrom);
                        enterCustom();
                        // 起始日刚定完，接着问结束日，少一次点击
                        if (isFrom && chainNext) pickDate(false, customTo, false);
                    }
                },
                c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
        dlg.setOnCancelListener(new DialogInterface.OnCancelListener() {
            public void onCancel(DialogInterface d) {
                if (period != PERIOD_CUSTOM) {
                    // 兜底：lastPeriod 理论上不会是「自定义」（切走时已被覆盖），
                    // 但真要落到这里，setSelection(自定义) 会把弹窗再弹一次，无限循环
                    int back = lastPeriod == PERIOD_CUSTOM ? UsageStats.PERIOD_DAY : lastPeriod;
                    periodSpinner.setSelection(back);
                }
            }
        });
        dlg.show();
    }

    private void enterCustom() {
        period = PERIOD_CUSTOM;
        lastPeriod = PERIOD_CUSTOM;
        customRow.setVisibility(View.VISIBLE);
        updateCustomButtons();
        render();
    }

    private void updateCustomButtons() {
        fromButton.setText(dayFmt.format(new Date(customFrom)));
        toButton.setText(dayFmt.format(new Date(customTo)));
    }

    private void confirmClear() {
        AcDialog.builder(this)
                .setTitle(Strings.get(R.string.statsactivity_005))
                .setMessage(Strings.get(R.string.statsactivity_044))
                .setPositiveButton(Strings.get(R.string.statsactivity_008), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        UsageStats.get().clear();
                        render();
                    }
                })
                .setNegativeButton(Strings.get(R.string.statsactivity_009), null)
                .show();
    }

    private void render() {
        UsageStats.Summary s = currentSummary();
        rebuildFilters(s);
        drawTables(s);
        LogUtil.d(TAG, "render: period=%d, reqs=%d, models=%d",
                period, s.reqs, s.models.size());
    }

    private UsageStats.Summary currentSummary() {
        return period == PERIOD_CUSTOM
                ? UsageStats.get().summarize(customFrom, customTo)
                : UsageStats.get().summarize(period);
    }

    /** 画区间说明与四张表。筛选变化时只走这条路径，不重建下拉。 */
    private void drawTables(UsageStats.Summary s) {
        content.removeAllViews();

        TextView head = new TextView(this);
        head.setTextSize(12);
        head.setTextColor(0xFF78909C);
        head.setPadding(0, 0, 0, dp(8));
        head.setText(periodLabel() + "  " + dayFmt.format(new Date(s.fromTs))
                + " ~ " + dayFmt.format(new Date(s.toTs)));
        content.addView(head);

        appendOverview(s);
        appendBucketsTable("服务商", filterBy(s.providers, selProvider), "这个区间内没有服务商数据");
        appendBucketsTable("模型", filterBy(s.models, selModel), "这个区间内没有模型数据");
        appendToolsTable(s);
    }

    /** 小标题：每张表前面一行，说明这张表是什么。 */
    private TextView sectionTitle(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setTextColor(0xFF1565C0);
        t.setPadding(0, dp(12), 0, dp(4));
        return t;
    }

    /** 概览表：把原先那一大段文字拆成「指标 / 数值」两列。 */
    private void appendOverview(UsageStats.Summary s) {
        content.addView(sectionTitle("总计"));
        StatsTableView t = new StatsTableView(this);
        List<StatsTableView.Col> cols = new ArrayList<StatsTableView.Col>();
        cols.add(new StatsTableView.Col("指标", 120, false));
        cols.add(new StatsTableView.Col("数值", 150, true));
        t.setColumns(cols);

        List<StatsTableView.Row> rows = new ArrayList<StatsTableView.Row>();
        rows.add(row2("会话数", String.valueOf(s.convs)));
        rows.add(row2("轮次", String.valueOf(s.rounds)));
        rows.add(row2("请求数", s.retries > 0
                ? s.reqs + "（含重试 " + s.retries + "）" : String.valueOf(s.reqs)));
        rows.add(row2("Token 合计", tok(s.pt + s.ct)));
        rows.add(row2("输入 tokens", tok(s.pt)));
        rows.add(row2("输出 tokens", tok(s.ct)));
        if (s.pt > 0) {
            rows.add(row2("缓存命中", tok(s.cached) + "（" + s.cacheRate() + "%）"));
        }
        if (s.genMs > 0 && s.ct > 0) {
            rows.add(row2("生成速度", String.format(Locale.US, "%.1f tok/s", s.speed())));
        }
        rows.add(row2("工具调用", s.toolFail > 0
                ? s.toolCalls + " 次（失败 " + s.toolFail + "）"
                : s.toolCalls + " 次"));
        if (s.hasEstimated()) {
            // 估算部分单列：端点没给 usage（例如中途打断）时只能本地估算，
            // 混进总数会让用户对账永远差一点且找不到差在哪
            rows.add(row2("其中本地估算", tok(s.estPt + s.estCt) + "（非官方用量）"));
        }
        t.setRows(rows);
        content.addView(t);
    }

    private StatsTableView.Row row2(String k, String v) {
        return new StatsTableView.Row(k, new String[] { k, v }, new double[] { 0, 0 });
    }

    /**
     * 「服务商」与「模型」两张表结构一致：一行一个对象，列是各项指标。
     *
     * <p>列宽是定值，整表可左右滑——词典笔屏幕只有几厘米，塞不下七八列，
     * 滑动比压缩列宽（最后每列只剩一两个字）可读得多。
     */
    private void appendBucketsTable(String title, Map<String, UsageStats.Bucket> map,
            String noDataText) {
        content.addView(sectionTitle(title));
        StatsTableView t = new StatsTableView(this);
        List<StatsTableView.Col> cols = new ArrayList<StatsTableView.Col>();
        cols.add(new StatsTableView.Col("名称", 130, false));
        cols.add(new StatsTableView.Col("请求", 52, true));
        cols.add(new StatsTableView.Col("输入", 62, true));
        cols.add(new StatsTableView.Col("输出", 62, true));
        cols.add(new StatsTableView.Col("合计", 66, true));
        cols.add(new StatsTableView.Col("缓存率", 56, true));
        cols.add(new StatsTableView.Col("速度", 66, true));
        t.setColumns(cols);

        List<StatsTableView.Row> rows = new ArrayList<StatsTableView.Row>();
        List<Map.Entry<String, UsageStats.Bucket>> list = UsageStats.sorted(map);
        for (int i = 0; i < list.size(); i++) {
            Map.Entry<String, UsageStats.Bucket> e = list.get(i);
            UsageStats.Bucket b = e.getValue();
            boolean hasIn = b.pt > 0;
            boolean hasSpeed = b.genMs > 0 && b.ct > 0;
            double speed = hasSpeed ? b.ct * 1000.0 / b.genMs : 0;
            // 没有输入侧用量时命中率显示「—」而不是 0%：
            // 0% 会被读成「缓存完全没生效」，实际只是还没收到 usage
            String cache = hasIn ? (b.cacheRate() + "%") : "—";
            String sp = hasSpeed ? String.format(Locale.US, "%.0f", speed) : "—";
            String totalStr = tok(b.pt + b.ct) + (b.estPt > 0 || b.estCt > 0 ? "*" : "");
            rows.add(new StatsTableView.Row(e.getKey(),
                    new String[] { e.getKey(), String.valueOf(b.reqs), tok(b.pt), tok(b.ct),
                            totalStr, cache, sp },
                    new double[] { 0, b.reqs, b.pt, b.ct, b.pt + b.ct,
                            hasIn ? b.cacheRate() : -1, speed }));
        }
        t.setRows(rows);
        t.setEmptyText(noDataText);
        content.addView(t);

        // 有带 * 的行才解释 * 是什么意思，避免每张表都挂一行没用的说明
        boolean anyEst = false;
        for (Map.Entry<String, UsageStats.Bucket> e : list) {
            if (e.getValue().estPt > 0 || e.getValue().estCt > 0) { anyEst = true; break; }
        }
        if (anyEst) content.addView(note("* 该行含本地估算的用量（端点未返回 usage）"));
    }

    /** 工具表：调用次数与失败次数。 */
    private void appendToolsTable(UsageStats.Summary s) {
        content.addView(sectionTitle("工具调用"));
        StatsTableView t = new StatsTableView(this);
        List<StatsTableView.Col> cols = new ArrayList<StatsTableView.Col>();
        cols.add(new StatsTableView.Col("工具", 150, false));
        cols.add(new StatsTableView.Col("调用", 60, true));
        cols.add(new StatsTableView.Col("失败", 60, true));
        cols.add(new StatsTableView.Col("成功率", 70, true));
        t.setColumns(cols);

        List<StatsTableView.Row> rows = new ArrayList<StatsTableView.Row>();
        for (Map.Entry<String, UsageStats.ToolCount> e : s.tools.entrySet()) {
            UsageStats.ToolCount c = e.getValue();
            int okCount = c.calls - c.fail;
            double rate = c.calls <= 0 ? 0 : okCount * 100.0 / c.calls;
            rows.add(new StatsTableView.Row(e.getKey(),
                    new String[] { e.getKey(), String.valueOf(c.calls), String.valueOf(c.fail),
                            c.calls <= 0 ? "—" : String.format(Locale.US, "%.0f%%", rate) },
                    new double[] { 0, c.calls, c.fail, rate }));
        }
        t.setRows(rows);
        t.setEmptyText("这个区间内没有工具调用");
        content.addView(t);
    }

    private TextView note(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(10);
        t.setTextColor(0xFF90A4AE);
        t.setPadding(dp(2), dp(4), 0, 0);
        return t;
    }

    // ==================== 筛选 ====================

    /**
     * 筛选下拉的初始化。
     *
     * <p>两个下拉联动：选了服务商之后，模型下拉只列该服务商下出现过的模型。
     * 但这里的数据来自「当前区间的事件」，所以还有一个附加规则——
     * <b>筛选项永远保留「全部」</b>，且当前选中的值即使不在列表里也保留，
     * 否则切换区间后筛选项消失、用户看到空白表格却不知道是被筛掉了。
     */
    private void setupFilterSpinners() {
        filterProvider.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (buildingFilters) return;
                Object o = parent.getItemAtPosition(pos);
                String v = o == null ? "" : o.toString();
                String next = v.startsWith("全部") ? "" : v;
                if (next.equals(selProvider)) return;
                selProvider = next;
                // 换了服务商，模型的选择可能不再存在，重置回「全部」
                selModel = "";
                render();
            }

            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        filterModel.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (buildingFilters) return;
                Object o = parent.getItemAtPosition(pos);
                String v = o == null ? "" : o.toString();
                String next = v.startsWith("全部") ? "" : v;
                if (next.equals(selModel)) return;
                selModel = next;
                // 只重画表格，不动下拉本身（否则会把用户刚点的项再重置一次）
                redrawTablesOnly();
            }

            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    /**
     * 按当前区间的数据重建两个下拉的选项。
     *
     * <p>{@code buildingFilters} 抑制 setAdapter / setSelection 触发的 onItemSelected：
     * 那两个回调会把 selProvider/selModel 重置掉，并立刻再触发一次 render，
     * 表现为「切区间后筛选自动跳回全部」。
     */
    private void rebuildFilters(UsageStats.Summary s) {
        buildingFilters = true;
        providerOptions.clear();
        providerOptions.addAll(keysOf(s.providers));

        List<String> pLabels = new ArrayList<String>();
        pLabels.add("全部");
        for (String p : providerOptions) pLabels.add(p);
        setSpinner(filterProvider, pLabels, selProvider);
        // 之前选的服务商在新区间里不存在了：下拉已回退到「全部」，状态也跟着回退，
        // 否则表格会被一个下拉上根本看不到的值筛空
        if (selProvider.length() > 0 && filterProvider.getSelectedItemPosition() == 0) {
            selProvider = "";
            selModel = "";
        }

        // 模型列表给全集：Summary 按模型聚合，反查「某模型属于哪个服务商」需要
        // 逐事件遍历，代价不划算。选了服务商时模型下拉仍列全集，但用户选中的模型
        // 若不属于该服务商，表格会显示为空——这比藏起选项更不容易让人困惑。
        modelOptions.clear();
        modelOptions.addAll(keysOf(s.models));
        List<String> mLabels = new ArrayList<String>();
        mLabels.add("全部");
        for (String m : modelOptions) mLabels.add(m);
        setSpinner(filterModel, mLabels, selModel);
        if (selModel.length() > 0 && filterModel.getSelectedItemPosition() == 0) {
            selModel = "";
        }
        buildingFilters = false;
    }

    private List<String> keysOf(Map<String, UsageStats.Bucket> map) {
        List<String> out = new ArrayList<String>();
        for (Map.Entry<String, UsageStats.Bucket> e : UsageStats.sorted(map)) out.add(e.getKey());
        return out;
    }

    /** 塞选项并保住当前选择；选项里找不到时回退到第一项。 */
    private void setSpinner(Spinner sp, List<String> labels, String selected) {
        ArrayAdapter<String> ad = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, labels);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        int idx = 0;
        if (selected != null && selected.length() > 0) {
            int at = labels.indexOf(selected);
            if (at >= 0) idx = at;
        }
        sp.setSelection(idx);
    }

    /** 只重画表格区（保留区间说明与下拉），供模型筛选使用。 */
    private void redrawTablesOnly() {
        drawTables(currentSummary());
    }

    /** 按名字过滤一张表；filter 为空时原样返回。 */
    private Map<String, UsageStats.Bucket> filterBy(Map<String, UsageStats.Bucket> map,
            String filter) {
        if (filter == null || filter.length() == 0) return map;
        Map<String, UsageStats.Bucket> out = new java.util.LinkedHashMap<String, UsageStats.Bucket>();
        UsageStats.Bucket b = map.get(filter);
        if (b != null) out.put(filter, b);
        return out;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static String tok(long n) {
        return MessageHtmlRenderer.formatTokens((int) Math.min(n, Integer.MAX_VALUE));
    }

    private String periodLabel() {
        if (period == PERIOD_CUSTOM) return Strings.get(R.string.statsactivity_039);
        if (period == UsageStats.PERIOD_WEEK) return Strings.get(R.string.statsactivity_040);
        if (period == UsageStats.PERIOD_MONTH) return Strings.get(R.string.statsactivity_041);
        return Strings.get(R.string.statsactivity_042);
    }
}
