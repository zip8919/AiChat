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
import android.widget.Spinner;
import android.widget.TextView;

import java.text.SimpleDateFormat;
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

        statsText = (TextView) findViewById(R.id.stats_text);
        periodSpinner = (Spinner) findViewById(R.id.stats_period);
        customRow = findViewById(R.id.stats_custom_row);
        fromButton = (Button) findViewById(R.id.stats_from);
        toButton = (Button) findViewById(R.id.stats_to);

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
                .setMessage(Strings.get(R.string.statsactivity_006)
                        + Strings.get(R.string.statsactivity_007))
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
        UsageStats.Summary s = period == PERIOD_CUSTOM
                ? UsageStats.get().summarize(customFrom, customTo)
                : UsageStats.get().summarize(period);

        StringBuilder sb = new StringBuilder();
        sb.append(periodLabel()).append('\n');
        sb.append(dayFmt.format(new Date(s.fromTs))).append(" ~ ")
          .append(dayFmt.format(new Date(s.toTs))).append("\n\n");

        sb.append(Strings.get(R.string.statsactivity_010)).append(s.convs)
          .append(Strings.get(R.string.statsactivity_011)).append(s.rounds).append("\n");
        sb.append(Strings.get(R.string.statsactivity_012)).append(s.reqs);
        if (s.retries > 0) sb.append(Strings.get(R.string.statsactivity_013)).append(s.retries).append(Strings.get(R.string.statsactivity_014));
        sb.append("\n");
        sb.append(Strings.get(R.string.statsactivity_015)).append(s.toolCalls).append(Strings.get(R.string.statsactivity_016));
        if (s.toolFail > 0) sb.append(Strings.get(R.string.statsactivity_017)).append(s.toolFail).append(Strings.get(R.string.statsactivity_018));
        sb.append("\n");

        long total = s.pt + s.ct;
        sb.append("Token  ").append(tok(total))
          .append(Strings.get(R.string.statsactivity_019)).append(tok(s.pt))
          .append(Strings.get(R.string.statsactivity_020)).append(tok(s.ct))
          .append("）\n");
        if (s.pt > 0) {
            sb.append(Strings.get(R.string.statsactivity_021)).append(tok(s.cached))
              .append(Strings.get(R.string.statsactivity_022)).append(s.cacheRate()).append("%）\n");
        } else {
            sb.append(Strings.get(R.string.statsactivity_023));
        }
        if (s.genMs > 0 && s.ct > 0) {
            sb.append(Strings.get(R.string.statsactivity_024)).append(String.format(Locale.US, "%.1f tok/s", s.speed())).append("\n");
        }
        if (s.hasEstimated()) {
            // 中断的回复、端点不返回 usage 的回复都只能估算。单列一行而不是混进总数：
            // 混进去用户对账单时永远差那么一点，且找不到差在哪。
            sb.append(Strings.get(R.string.statsactivity_025)).append(tok(s.estPt + s.estCt))
              .append(Strings.get(R.string.statsactivity_026));
        }

        if (!s.tools.isEmpty()) {
            sb.append(Strings.get(R.string.statsactivity_027));
            for (Map.Entry<String, UsageStats.ToolCount> e : s.tools.entrySet()) {
                sb.append("  ").append(e.getKey()).append("  ")
                  .append(e.getValue().calls).append(Strings.get(R.string.statsactivity_028));
                if (e.getValue().fail > 0) {
                    sb.append(Strings.get(R.string.statsactivity_029)).append(e.getValue().fail).append("）");
                }
                sb.append("\n");
            }
        }

        sb.append(Strings.get(R.string.statsactivity_030));
        appendBuckets(sb, s.models);
        sb.append(Strings.get(R.string.statsactivity_031));
        appendBuckets(sb, s.providers);

        sb.append(Strings.get(R.string.statsactivity_043));
        statsText.setText(sb.toString());
        LogUtil.d(TAG, "render: period=%d, reqs=%d, models=%d",
                period, s.reqs, s.models.size());
    }

    private static String tok(long n) {
        return MessageHtmlRenderer.formatTokens((int) Math.min(n, Integer.MAX_VALUE));
    }

    /**
     * 一行一个模型 / 服务商。
     *
     * <p>命中率只在有输入侧用量时显示：刚发出去还没回来的请求只有 reqs，
     * 显示「命中 0%」会被误读成「缓存完全没生效」。
     */
    private void appendBuckets(StringBuilder sb, Map<String, UsageStats.Bucket> map) {
        List<Map.Entry<String, UsageStats.Bucket>> list = UsageStats.sorted(map);
        if (list.isEmpty()) {
            sb.append(Strings.get(R.string.statsactivity_034));
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            Map.Entry<String, UsageStats.Bucket> e = list.get(i);
            UsageStats.Bucket b = e.getValue();
            sb.append("  ").append(e.getKey()).append("\n")
              .append("    ").append(b.reqs).append(Strings.get(R.string.statsactivity_035))
              .append(tok(b.pt + b.ct)).append(" tokens");
            if (b.pt > 0) {
                sb.append(Strings.get(R.string.statsactivity_036)).append(tok(b.pt))
                  .append(Strings.get(R.string.statsactivity_037)).append(b.cacheRate()).append("%");
            }
            if (b.estPt > 0 || b.estCt > 0) {
                sb.append(Strings.get(R.string.statsactivity_038));
            }
            if (b.genMs > 0 && b.ct > 0) {
                sb.append(" · ").append(String.format(Locale.US, "%.1f tok/s",
                        b.ct * 1000.0 / b.genMs));
            }
            sb.append("\n");
        }
    }

    private String periodLabel() {
        if (period == PERIOD_CUSTOM) return Strings.get(R.string.statsactivity_039);
        if (period == UsageStats.PERIOD_WEEK) return Strings.get(R.string.statsactivity_040);
        if (period == UsageStats.PERIOD_MONTH) return Strings.get(R.string.statsactivity_041);
        return Strings.get(R.string.statsactivity_042);
    }
}
