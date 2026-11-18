package xyz.zip8919.app.aichat;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 反馈详情。数据来自反馈中心的公开详情接口，只有标题、类型、状态、摘要、正文、
 * 版本与附件——联系方式、批注、备注属于管理员信息，客户端拿不到也不需要拿到。
 *
 * <p>附件是签名链接（30 天有效）。点「预览」在本应用内看图片与文本，
 * 点「下载」交给系统浏览器或对应应用打开，不在本机留副本。
 */
public class FeedbackDetailActivity extends ThemedActivity {

    private static final String TAG = "FeedbackDetailActivity";

    /** 反馈编号。列表页与提交成功弹窗都通过它启动本页。 */
    static final String EXTRA_ID = "feedback_id";

    private TextView loadingView;
    private View contentView;
    private TextView titleView;
    private TextView typeView;
    private TextView statusView;
    private TextView metaView;
    private TextView summaryLabel;
    private TextView summaryView;
    private TextView bodyView;
    private TextView remarkLabel;
    private TextView remarkView;
    private LinearLayout attachmentsBox;
    private TextView attachmentsHint;

    private String feedbackId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_feedback_detail);

        loadingView = (TextView) findViewById(R.id.detail_loading);
        contentView = findViewById(R.id.detail_content);
        titleView = (TextView) findViewById(R.id.detail_title);
        typeView = (TextView) findViewById(R.id.detail_type);
        statusView = (TextView) findViewById(R.id.detail_status);
        metaView = (TextView) findViewById(R.id.detail_meta);
        summaryLabel = (TextView) findViewById(R.id.detail_summary_label);
        summaryView = (TextView) findViewById(R.id.detail_summary);
        bodyView = (TextView) findViewById(R.id.detail_body);
        remarkLabel = (TextView) findViewById(R.id.detail_remark_label);
        remarkView = (TextView) findViewById(R.id.detail_remark);
        attachmentsBox = (LinearLayout) findViewById(R.id.detail_attachments);
        attachmentsHint = (TextView) findViewById(R.id.detail_attachments_hint);

        findViewById(R.id.detail_back).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                finish();
            }
        });

        feedbackId = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_ID);
        if (feedbackId == null || feedbackId.length() == 0) {
            showError(Strings.get(R.string.feedbackdetailactivity_012));
            return;
        }
        load();
    }

    private void load() {
        loadingView.setVisibility(View.VISIBLE);
        loadingView.setText(R.string.feedbackdetailactivity_009);
        contentView.setVisibility(View.GONE);

        FeedbackApi.detail(feedbackId, new FeedbackApi.Callback() {
            public void onSuccess(JSONObject data) {
                JSONObject item = data.optJSONObject("item");
                if (item == null) {
                    showError(Strings.get(R.string.feedbackdetailactivity_012));
                    return;
                }
                render(item);
            }

            public void onError(String message) {
                showError(message);
            }
        });
    }

    private void showError(String message) {
        loadingView.setVisibility(View.VISIBLE);
        loadingView.setText(Strings.get(R.string.feedbackdetailactivity_010, message));
        contentView.setVisibility(View.GONE);
    }

    private void render(JSONObject item) {
        loadingView.setVisibility(View.GONE);
        contentView.setVisibility(View.VISIBLE);

        titleView.setText(item.optString("title"));

        String typeText = item.optString("typeText");
        typeView.setText(typeText);
        typeView.setVisibility(typeText.length() == 0 ? View.GONE : View.VISIBLE);

        String statusText = item.optString("reviewStatusText");
        statusView.setText(statusText);
        statusView.setTextColor(FeedbackUi.toneColor(this, item.optString("reviewTone")));
        statusView.setVisibility(statusText.length() == 0 ? View.GONE : View.VISIBLE);

        String id = item.optString("id");
        String time = item.optString("createdAt");
        String version = item.optString("appVersion");
        String nickname = item.optString("nicknameText");
        StringBuilder meta = new StringBuilder();
        meta.append(Strings.get(R.string.feedbackdetailactivity_016)).append(' ')
                .append(nickname.length() == 0
                        ? Strings.get(R.string.feedbackdetailactivity_017) : nickname);
        meta.append('\n').append(Strings.get(R.string.feedbackdetailactivity_003)).append(' ').append(id);
        meta.append('\n').append(Strings.get(R.string.feedbackdetailactivity_004)).append(' ')
                .append(time.length() == 0 ? Strings.get(R.string.feedbackdetailactivity_012) : time);
        if (version.length() > 0) {
            meta.append('\n').append(Strings.get(R.string.feedbackdetailactivity_005))
                    .append(' ').append(version);
        }
        metaView.setText(meta.toString());

        String body = item.optString("body");
        bodyView.setText(body.length() == 0
                ? Strings.get(R.string.feedbackdetailactivity_012) : body);

        // 摘要与正文重复时不再单列一块：AI 生成摘要失败会退回正文首段，重复展示只是噪音
        String summary = item.optString("summary");
        boolean hasSummary = summary.length() > 0 && !summary.equals(body);
        boolean pending = "pending".equals(item.optString("summaryState"));
        summaryLabel.setVisibility(hasSummary || pending ? View.VISIBLE : View.GONE);
        summaryView.setVisibility(hasSummary || pending ? View.VISIBLE : View.GONE);
        if (hasSummary) {
            summaryView.setText(summary);
        } else if (pending) {
            summaryView.setText(R.string.feedbackdetailactivity_019);
        }

        // 备注由管理员填写且对所有人可见，放在正文之后单独一块
        String remark = item.optString("remark");
        remarkLabel.setVisibility(remark.length() == 0 ? View.GONE : View.VISIBLE);
        remarkView.setVisibility(remark.length() == 0 ? View.GONE : View.VISIBLE);
        if (remark.length() > 0) remarkView.setText(remark);

        renderAttachments(item.optJSONArray("attachments"));
    }

    private void renderAttachments(JSONArray attachments) {
        attachmentsBox.removeAllViews();
        int count = attachments == null ? 0 : attachments.length();

        if (count == 0) {
            attachmentsHint.setVisibility(View.VISIBLE);
            attachmentsHint.setText(R.string.feedbackdetailactivity_014);
            return;
        }

        final JSONArray list = attachments;
        for (int i = 0; i < count; i++) {
            JSONObject a = attachments.optJSONObject(i);
            if (a == null) continue;
            final String url = a.optString("url");
            String name = a.optString("name");
            String sizeText = a.optString("sizeText");

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(8), 0, dp(8));
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView label = new TextView(this);
            label.setTextSize(13);
            label.setTextColor(getResources().getColor(R.color.text_primary));
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            label.setText(sizeText.length() == 0 ? name : name + "  " + sizeText);
            row.addView(label, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView preview = new TextView(this);
            preview.setTextSize(13);
            preview.setTextColor(getResources().getColor(R.color.primary));
            preview.setPadding(0, dp(4), dp(8), dp(4));
            preview.setText(R.string.feedbackdetailactivity_020);
            row.addView(preview);

            TextView download = new TextView(this);
            download.setTextSize(13);
            download.setTextColor(getResources().getColor(R.color.text_secondary));
            download.setPadding(dp(10), dp(4), 0, dp(4));
            download.setText(R.string.feedbackdetailactivity_021);
            row.addView(download);

            preview.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    // 预览页内部按地址定位，这里不依赖循环下标
                    AttachmentPreviewActivity.open(FeedbackDetailActivity.this, list, url);
                }
            });
            download.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    openUrl(url);
                }
            });
            attachmentsBox.addView(row);
        }

        attachmentsHint.setVisibility(View.VISIBLE);
        attachmentsHint.setText(R.string.feedbackdetailactivity_011);
    }

    private void openUrl(String url) {
        if (url == null || url.length() == 0) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            LogUtil.w(TAG, "openUrl failed: %s", e.getMessage());
            AcToast.show(this, Strings.get(R.string.feedbackdetailactivity_013));
        } catch (Exception e) {
            LogUtil.w(TAG, "openUrl error: %s", e.getMessage());
            AcToast.show(this, Strings.get(R.string.feedbackdetailactivity_013));
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
