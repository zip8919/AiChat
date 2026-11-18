package xyz.zip8919.app.aichat;

import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 历史公告。从「关于 → 历史公告」进入，列出全部展示中的公告，点开看全文。
 *
 * <p>打开这个页面不等于读过某条：已读只在启动弹窗点「我知道了」时记录，
 * 保证不该打扰的内容不打扰，主动来看的内容也不会被当成通知推给用户。
 */
public class NoticeActivity extends ThemedActivity {

    private static final String TAG = "NoticeActivity";

    private final List<Notice> items = new ArrayList<Notice>();
    private NoticeAdapter adapter;
    private TextView emptyView;
    private View footer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_notice);

        setTitle(Strings.get(R.string.noticeactivity_001));

        ListView list = (ListView) findViewById(R.id.notice_list);
        emptyView = (TextView) findViewById(R.id.notice_empty);
        footer = findViewById(R.id.notice_footer);
        adapter = new NoticeAdapter();
        list.setAdapter(adapter);
        list.setEmptyView(emptyView);

        Button retry = (Button) findViewById(R.id.notice_retry);
        retry.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                load();
            }
        });

        load();
    }

    private void load() {
        footer.setVisibility(View.VISIBLE);
        Notice.load(this, new FeedbackApi.Callback() {
            public void onSuccess(JSONObject data) {
                items.clear();
                items.addAll(Notice.parseList(data));
                adapter.notifyDataSetChanged();
                footer.setVisibility(View.GONE);
                emptyView.setText(items.isEmpty()
                        ? Strings.get(R.string.noticeactivity_004)
                        : Strings.get(R.string.noticeactivity_005));
                LogUtil.d(TAG, "loaded %d notices", items.size());
            }

            public void onError(String message) {
                footer.setVisibility(View.GONE);
                emptyView.setText(Strings.get(R.string.noticeactivity_005));
                AcToast.show(NoticeActivity.this, message);
            }
        });
    }

    private void showDetail(Notice n) {
        final View content = LayoutInflater.from(this).inflate(R.layout.view_notice_detail, null);
        TextView title = (TextView) content.findViewById(R.id.nd_title);
        TextView body = (TextView) content.findViewById(R.id.nd_body);
        TextView link = (TextView) content.findViewById(R.id.nd_link);
        title.setText(n.title);
        body.setText(n.body);
        if (n.link != null && n.link.length() > 0) {
            link.setVisibility(View.VISIBLE);
            link.setText(Strings.get(R.string.noticeactivity_006));
            final String url = n.link;
            link.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    openUrl(url);
                }
            });
        }

        AcDialog.builder(this)
                .setTitle(Strings.get(R.string.noticeactivity_002))
                .setView(content)
                .setPositiveButton(Strings.get(R.string.noticeactivity_003), null)
                .show();
    }

    /** 打不开浏览器时把链接显示出来，允许复制，不让入口变成死路。 */
    private void openUrl(final String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            LogUtil.w(TAG, "openUrl failed: %s", e.getMessage());
            AcDialog.builder(this)
                    .setTitle(Strings.get(R.string.noticeactivity_007))
                    .setMessage(url)
                    .setPositiveButton(Strings.get(R.string.noticeactivity_008), new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            if (ClipUtil.copy(NoticeActivity.this, url)) {
                                AcToast.show(NoticeActivity.this, Strings.get(R.string.noticeactivity_010));
                            }
                        }
                    })
                    .show();
        }
    }

    private final class NoticeAdapter extends BaseAdapter {

        public int getCount() {
            return items.size();
        }

        public Object getItem(int position) {
            return items.get(position);
        }

        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = LayoutInflater.from(NoticeActivity.this)
                        .inflate(R.layout.item_notice, parent, false);
            }
            final Notice n = items.get(position);
            TextView title = (TextView) row.findViewById(R.id.item_notice_title);
            TextView preview = (TextView) row.findViewById(R.id.item_notice_preview);
            TextView time = (TextView) row.findViewById(R.id.item_notice_time);
            title.setText(n.title);
            String body = n.body == null ? "" : n.body.replace('\n', ' ');
            preview.setText(body.length() > 60 ? body.substring(0, 60) + "…" : body);
            time.setText(n.createdAt > 0
                    ? ConvTime.format(n.createdAt)
                    : Strings.get(R.string.noticeactivity_009));
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    showDetail(n);
                }
            });
            return row;
        }
    }
}
