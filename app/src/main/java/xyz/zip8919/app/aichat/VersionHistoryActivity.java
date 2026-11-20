package xyz.zip8919.app.aichat;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * 历史版本页：列出全部已发布版本、发布时间与更新日志，可挑任意一版下载。
 *
 * <p><b>为什么选完版本要回到更新页而不是在这里下载</b>：下载要处理镜像选择、
 * 测速、断点续传、4.4 上的安装包拷贝与 Installer 兼容，那一整套逻辑已经在
 * {@code UpdateActivity} 里跑了很多个版本、被各种机型验证过。这页若自己再写一遍，
 * 等于把最容易出错的路径复制一份。所以这里只做「选」，
 * 选定后带 tag 回更新页，由它加载该版本并沿用原有下载/安装链路。
 *
 * <p><b>更新日志用可展开的纯文本</b>：release body 是 Markdown，长日志整段铺开会把
 * 页面拉到几十屏。默认折叠成几行摘要，点「展开」看全文——纯文本不做 Markdown 渲染，
 * 因为这里的诉求是「看清改了什么」，不是排版好看，少一层渲染在老设备上也更快。
 */
public class VersionHistoryActivity extends ThemedActivity {

    private static final String TAG = "VersionHistory";

    private TextView status;
    private LinearLayout list;
    private Button refresh;
    private boolean loading;
    private boolean destroyed;

    /** 当前安装的版本号，列表里标注「当前」并据此提示降级。 */
    private String currentVersion = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_version_history);

        status = (TextView) findViewById(R.id.ver_status);
        list = (LinearLayout) findViewById(R.id.ver_list);
        refresh = (Button) findViewById(R.id.ver_refresh);
        currentVersion = versionName();

        findViewById(R.id.ver_back).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                finish();
            }
        });
        refresh.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                load();
            }
        });
        load();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        super.onDestroy();
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "";
        }
    }

    private void load() {
        if (loading) return;
        loading = true;
        refresh.setEnabled(false);
        status.setText("正在加载历史版本…");
        list.removeAllViews();
        UpdateManager.fetchReleases(new UpdateManager.ReleasesCallback() {
            public void onReleases(final List<UpdateManager.Release> data, final String err) {
                if (destroyed || isFinishing()) return;
                loading = false;
                refresh.setEnabled(true);
                if (err != null || data == null || data.isEmpty()) {
                    status.setText("加载失败：" + (err == null ? "没有可用的历史版本" : err));
                    return;
                }
                status.setText("共 " + data.size() + " 个版本 · 当前安装 " + currentVersion
                        + "\n时间为 UTC，点击版本可下载该版本");
                for (int i = 0; i < data.size(); i++) {
                    View card = buildCard(data.get(i), i == 0);
                    list.addView(card);
                    // 卡片是异步回调里 new 出来的，不在 Activity 的 inflater 上：
                    // 主题钩子够不到它，系统按钮底（Holo 浅灰）会一直是亮的。
                    ThemeRecolor.applyTo(card);
                }
                LogUtil.i(TAG, "loaded %d releases, current=%s", data.size(), currentVersion);
            }
        });
    }

    /** 一张版本卡片：版本号 + 时间 + 标记 + 日志（可展开）+ 下载按钮。 */
    private View buildCard(final UpdateManager.Release r, boolean newest) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(ThemeTokens.getSafe(this, ThemeTokens.AC_SURFACE));
        int p = dp(12);
        card.setPadding(p, dp(10), p, dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        card.setLayoutParams(lp);

        // ---- 标题行：版本号 + 标记 ----
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView tag = new TextView(this);
        tag.setText(r.tag);
        tag.setTextSize(15);
        tag.setTypeface(Typeface.DEFAULT_BOLD);
        tag.setTextColor(ThemeTokens.getSafe(this, ThemeTokens.AC_PRIMARY));
        head.addView(tag);

        int cmp = UpdateManager.compareVersion(r.tag, currentVersion);
        if (cmp == 0) {
            head.addView(badge("当前版本", 0xFF2E7D32));
        } else if (newest && cmp > 0) {
            head.addView(badge("最新", 0xFFC62828));
        } else if (cmp < 0) {
            // 比本机低：醒目提示这是降级
            head.addView(badge("低于当前", 0xFFE65100));
        }
        if (r.prerelease) head.addView(badge("测试版", 0xFF6A1B9A));
        card.addView(head);

        // ---- 时间 ----
        String when = UpdateManager.formatDate(r.publishedAt);
        if (when.length() > 0 || (r.name != null && r.name.length() > 0)) {
            TextView sub = new TextView(this);
            StringBuilder sb = new StringBuilder();
            if (r.name != null && r.name.length() > 0 && !r.name.equals(r.tag)) sb.append(r.name);
            if (when.length() > 0) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(when);
            }
            sub.setText(sb.toString());
            sub.setTextSize(11);
            sub.setTextColor(ThemeTokens.getSafe(this, ThemeTokens.AC_TEXT_SECONDARY));
            sub.setPadding(0, dp(3), 0, 0);
            card.addView(sub);
        }

        // ---- 更新日志 ----
        final String body = r.body == null ? "" : r.body.trim();
        if (body.length() > 0) {
            final TextView notes = new TextView(this);
            notes.setTextSize(12);
            notes.setTextColor(ThemeTokens.getSafe(this, ThemeTokens.AC_TEXT));
            notes.setLineSpacing(dp(2), 1f);
            notes.setPadding(0, dp(6), 0, 0);
            // 折叠态只显示前 6 行：长日志默认全展开会把列表拉成几十屏，
            // 用户根本翻不到更早的版本
            final String preview = previewOf(body, 6);
            final boolean needFold = preview.length() < body.length();
            notes.setText(preview);
            card.addView(notes);
            if (needFold) {
                final TextView toggle = new TextView(this);
                toggle.setText("展开全文");
                toggle.setTextSize(12);
                toggle.setTextColor(ThemeTokens.getSafe(this, ThemeTokens.AC_PRIMARY));
                toggle.setPadding(0, dp(4), 0, 0);
                toggle.setOnClickListener(new View.OnClickListener() {
                    private boolean open;
                    public void onClick(View v) {
                        open = !open;
                        notes.setText(open ? body : preview);
                        toggle.setText(open ? "收起" : "展开全文");
                    }
                });
                card.addView(toggle);
            }
        }

        // ---- 下载按钮 ----
        Button dl = new Button(this);
        final UpdateManager.Asset asset = UpdateManager.pickAsset(r);
        dl.setText(asset == null ? "无可用安装包" : "下载此版本");
        dl.setTextSize(12);
        dl.setEnabled(asset != null);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(36));
        blp.topMargin = dp(8);
        dl.setLayoutParams(blp);
        dl.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                LogUtil.d(TAG, "pick version %s", r.tag);
                Intent it = new Intent(VersionHistoryActivity.this, UpdateActivity.class);
                it.putExtra(UpdateActivity.EXTRA_TAG, r.tag);
                startActivity(it);
                finish();
            }
        });
        card.addView(dl);
        return card;
    }

    private TextView badge(String text, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(10);
        t.setTextColor(0xFFFFFFFF);
        t.setBackgroundColor(color);
        t.setPadding(dp(5), dp(1), dp(5), dp(1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(6);
        t.setLayoutParams(lp);
        return t;
    }

    /** 取前 n 行作为折叠态摘要。 */
    private static String previewOf(String body, int lines) {
        String[] arr = body.split("\n", -1);
        if (arr.length <= lines) return body;
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (int i = 0; i < arr.length && used < lines; i++) {
            // 连续空行折叠成一个，避免摘要里一半是空行
            if (arr[i].trim().length() == 0 && sb.length() > 0
                    && sb.charAt(sb.length() - 1) == '\n') {
                continue;
            }
            sb.append(arr[i]).append('\n');
            used++;
        }
        sb.append("…");
        return sb.toString();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
