package xyz.zip8919.app.aichat;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 反馈列表。数据来自反馈中心的公开接口，只含标题、类型、状态、摘要与附件数，
 * 不展示联系方式、批注与备注，也没有管理操作。
 *
 * <p>点一条进详情，点「新建」进原生编辑页；提交完成回到这里刷新第一页。
 */
public class FeedbackActivity extends ThemedActivity {

    private static final String TAG = "FeedbackActivity";
    private static final int REQ_NEW = 1001;

    private ListView listView;
    private ProgressBar loading;
    private TextView emptyView;
    private TextView pageText;
    private TextView foldView;
    private Button prevButton;
    private Button nextButton;

    private FeedbackAdapter adapter;
    private int page = 1;
    private int pages = 1;
    private boolean loadingNow = false;
    /** true 表示无关内容被折叠，默认如此；点一下折叠条展开。 */
    private boolean fold = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_feedback);

        listView = (ListView) findViewById(R.id.feedback_list);
        loading = (ProgressBar) findViewById(R.id.feedback_loading);
        emptyView = (TextView) findViewById(R.id.feedback_empty);
        pageText = (TextView) findViewById(R.id.feedback_page);
        foldView = (TextView) findViewById(R.id.feedback_fold);
        prevButton = (Button) findViewById(R.id.feedback_prev);
        nextButton = (Button) findViewById(R.id.feedback_next);

        adapter = new FeedbackAdapter();
        listView.setAdapter(adapter);

        findViewById(R.id.feedback_back).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.feedback_refresh).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                load(1);
            }
        });
        findViewById(R.id.feedback_new).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivityForResult(new Intent(FeedbackActivity.this, FeedbackComposeActivity.class), REQ_NEW);
            }
        });

        foldView.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (loadingNow) return;
                fold = !fold;
                load(page);
            }
        });

        prevButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (page > 1 && !loadingNow) load(page - 1);
            }
        });
        nextButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (page < pages && !loadingNow) load(page + 1);
            }
        });

        load(1);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_NEW && resultCode == RESULT_OK) {
            load(1);
        }
    }

    private void load(final int target) {
        if (loadingNow) return;
        loadingNow = true;
        loading.setVisibility(View.VISIBLE);
        emptyView.setVisibility(View.GONE);
        pageText.setText(R.string.feedbackactivity_007);

        FeedbackApi.list(target, fold, new FeedbackApi.Callback() {
            public void onSuccess(JSONObject data) {
                loadingNow = false;
                loading.setVisibility(View.GONE);
                page = data.optInt("page", target);
                pages = Math.max(data.optInt("pages", 1), 1);
                JSONArray items = data.optJSONArray("items");
                adapter.setItems(items == null ? new JSONArray() : items);
                adapter.notifyDataSetChanged();

                boolean isEmpty = adapter.getCount() == 0;
                if (isEmpty) emptyView.setText(R.string.feedbackactivity_006);
                emptyView.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
                if (!isEmpty) listView.setSelection(0);
                renderFold(data.optInt("folded", 0));
                renderPager();
                LogUtil.d(TAG, "list loaded page=%d pages=%d count=%d", page, pages, adapter.getCount());
            }

            public void onError(String message) {
                loadingNow = false;
                loading.setVisibility(View.GONE);
                AcToast.show(FeedbackActivity.this, message);
                // 一条都没加载出来时，空白列表比一句「加载失败」更容易让人以为「没有反馈」
                if (adapter.getCount() == 0) {
                    emptyView.setText(Strings.get(R.string.feedbackactivity_008, message));
                    emptyView.setVisibility(View.VISIBLE);
                }
                renderPager();
            }
        });
    }

    /** 折叠条：折叠时提示有多少条被收起并给「展开」，展开后给「收起」。 */
    private void renderFold(int folded) {
        int textId;
        if (fold && folded > 0) textId = R.string.feedbackactivity_016;
        else if (!fold) textId = R.string.feedbackactivity_017;
        else textId = 0;
        if (textId == 0) {
            foldView.setVisibility(View.GONE);
            return;
        }
        foldView.setText(folded > 0 && fold
                ? Strings.get(textId, folded) : Strings.get(textId));
        foldView.setVisibility(View.VISIBLE);
    }

    private void renderPager() {
        pageText.setText(Strings.get(R.string.feedbackactivity_011, page, pages));
        prevButton.setEnabled(page > 1);
        nextButton.setEnabled(page < pages);
    }

    private final class FeedbackAdapter extends BaseAdapter {

        private JSONArray items = new JSONArray();

        void setItems(JSONArray value) {
            items = value;
        }

        public int getCount() {
            return items.length();
        }

        public Object getItem(int position) {
            return items.optJSONObject(position);
        }

        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = getLayoutInflater().inflate(R.layout.item_feedback, parent, false);
            }
            JSONObject item = items.optJSONObject(position);
            if (item == null) return view;

            TextView title = (TextView) view.findViewById(R.id.item_feedback_title);
            TextView type = (TextView) view.findViewById(R.id.item_feedback_type);
            TextView status = (TextView) view.findViewById(R.id.item_feedback_status);
            TextView summary = (TextView) view.findViewById(R.id.item_feedback_summary);
            TextView meta = (TextView) view.findViewById(R.id.item_feedback_meta);

            title.setText(item.optString("title"));
            type.setText(item.optString("typeText"));
            status.setText(item.optString("reviewStatusText"));
            status.setTextColor(FeedbackUi.toneColor(FeedbackActivity.this, item.optString("reviewTone")));

            String sum = item.optString("summary");
            if (sum.length() > 0 && !sum.equals(item.optString("body"))) {
                summary.setText(sum);
                summary.setVisibility(View.VISIBLE);
            } else if ("pending".equals(item.optString("summaryState"))) {
                // 摘要还在生成，占位比空着好：空着会让人以为这条没有摘要
                summary.setText(R.string.feedbackactivity_015);
                summary.setVisibility(View.VISIBLE);
            } else {
                summary.setVisibility(View.GONE);
            }

            String id = item.optString("id");
            String time = item.optString("createdAt");
            String nickname = item.optString("nicknameText");
            int attachments = item.optInt("attachments", 0);
            StringBuilder sb = new StringBuilder();
            if (nickname.length() > 0) sb.append(nickname).append("  ");
            sb.append(Strings.get(R.string.feedbackactivity_013, id));
            if (time.length() > 0) sb.append("  ").append(time);
            if (attachments > 0) {
                sb.append("  ").append(Strings.get(R.string.feedbackactivity_014, attachments));
            }
            meta.setText(sb.toString());

            final String itemId = id;
            view.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Intent intent = new Intent(FeedbackActivity.this, FeedbackDetailActivity.class);
                    intent.putExtra(FeedbackDetailActivity.EXTRA_ID, itemId);
                    startActivity(intent);
                }
            });
            return view;
        }
    }
}
