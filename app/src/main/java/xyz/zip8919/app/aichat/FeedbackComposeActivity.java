package xyz.zip8919.app.aichat;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 新建反馈：全部是安卓原生控件，输入框自己画、文件用系统选择器挑，
 * 提交直接打反馈中心的 multipart 接口，不经过浏览器，也不跳 WebView。
 *
 * <p>校验在本地先做一遍：标题、描述、描述字数、单文件 15MB、最多 5 个。
 * 服务端同样有一套校验，这两层重复不是冗余——本地这层让用户在点提交的瞬间就知道
 * 哪里不对，而不必等一趟网络往返才被打回。
 *
 * <p>提交成功后 {@code setResult(RESULT_OK)}，列表页据此刷新第一页；弹窗里可以直接
 * 跳到这条反馈的详情，也可以关掉继续浏览。
 */
public class FeedbackComposeActivity extends ThemedActivity {

    private static final String TAG = "FeedbackComposeActivity";

    private static final int REQ_PICK = 2001;

    /** 与反馈中心 config.js 的 LIMITS 保持一致：单文件 15MB、最多 5 个。 */
    private static final long MAX_FILE_BYTES = 15L * 1024 * 1024;
    private static final int MAX_FILES = 5;
    /** 服务端要求描述至少 5 个字，本地提前拦住。 */
    private static final int MIN_BODY_CHARS = 5;

    private EditText titleView;
    private EditText bodyView;
    private EditText contactView;
    private Button pickButton;
    private Button submitButton;
    private LinearLayout filesBox;
    private TextView footnoteView;
    private TextView noticeView;

    private final List<FeedbackApi.UploadFile> files = new ArrayList<FeedbackApi.UploadFile>();
    private boolean busy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_feedback_compose);

        titleView = (EditText) findViewById(R.id.compose_title);
        bodyView = (EditText) findViewById(R.id.compose_body);
        contactView = (EditText) findViewById(R.id.compose_contact);
        pickButton = (Button) findViewById(R.id.compose_pick);
        submitButton = (Button) findViewById(R.id.compose_submit);
        filesBox = (LinearLayout) findViewById(R.id.compose_files);
        footnoteView = (TextView) findViewById(R.id.compose_footnote);
        noticeView = (TextView) findViewById(R.id.compose_notice);

        findViewById(R.id.compose_back).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (busy) {
                    AcToast.show(FeedbackComposeActivity.this, Strings.get(R.string.feedbackcomposeactivity_027));
                    return;
                }
                finish();
            }
        });

        pickButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!busy) pickFiles();
            }
        });
        submitButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (busy) {
                    AcToast.show(FeedbackComposeActivity.this, Strings.get(R.string.feedbackcomposeactivity_027));
                    return;
                }
                submit();
            }
        });

        refreshFiles();
    }

    // ------------------------------------------------------------ 选文件

    /**
     * 用系统文件选择器，不限类型：反馈里经常要附日志、截图、导出配置，
     * 按 MIME 过滤只会在老 ROM 上把 .log / .json 变成灰的选不中。
     */
    private void pickFiles() {
        if (files.size() >= MAX_FILES) {
            AcToast.show(this, Strings.get(R.string.feedbackcomposeactivity_015));
            return;
        }
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(Intent.createChooser(intent,
                    Strings.get(R.string.feedbackcomposeactivity_007)), REQ_PICK);
        } catch (Exception e) {
            LogUtil.w(TAG, "pickFiles failed: %s", e.getMessage());
            AcToast.show(this, Strings.get(R.string.feedbackcomposeactivity_025));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != Activity.RESULT_OK || data == null) return;

        List<Uri> picked = new ArrayList<Uri>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) picked.add(uri);
            }
        } else if (data.getData() != null) {
            picked.add(data.getData());
        }

        for (int i = 0; i < picked.size(); i++) {
            if (files.size() >= MAX_FILES) {
                AcToast.show(this, Strings.get(R.string.feedbackcomposeactivity_015));
                break;
            }
            Uri uri = picked.get(i);
            String name = displayName(uri);
            long size = displaySize(uri);
            if (size > MAX_FILE_BYTES) {
                AcToast.show(this, Strings.get(R.string.feedbackcomposeactivity_014, name));
                continue;
            }
            String mime = null;
            try {
                mime = getContentResolver().getType(uri);
            } catch (Exception e) {
                LogUtil.w(TAG, "getType failed: %s", e.getMessage());
            }
            files.add(new FeedbackApi.UploadFile(uri, name, size, mime));
        }
        refreshFiles();
    }

    /** 选择器给的展示名；查不到就退回路径末段，绝不能出现空白文件名。 */
    private String displayName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String n = c.getString(idx);
                    if (n != null && n.length() > 0) return n;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Exception ignored) {
                }
            }
        }
        String seg = uri.getLastPathSegment();
        return seg == null || seg.length() == 0 ? "file" : seg;
    }

    /** 拿不到大小返回 -1：本地放行，交给服务端按真实字节数判定。 */
    private long displaySize(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{OpenableColumns.SIZE},
                    null, null, null);
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Exception ignored) {
                }
            }
        }
        return -1;
    }

    /** 重画已选文件列表。文件数量本来就是 5 以内，全量重建比局部增删更不容易错。 */
    private void refreshFiles() {
        filesBox.removeAllViews();
        for (int i = 0; i < files.size(); i++) {
            final int index = i;
            filesBox.addView(buildFileRow(files.get(i), index));
        }
        if (files.isEmpty()) {
            footnoteView.setText(R.string.feedbackcomposeactivity_028);
        } else {
            footnoteView.setText(Strings.get(R.string.feedbackcomposeactivity_016, files.size()));
        }
    }

    private View buildFileRow(final FeedbackApi.UploadFile file, final int index) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView name = new TextView(this);
        name.setTextSize(13);
        name.setTextColor(getResources().getColor(R.color.text_primary));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        String size = file.size >= 0 ? Formatter.formatShortFileSize(this, file.size) : "";
        name.setText(size.length() == 0 ? file.name : file.name + "  " + size);
        row.addView(name, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView remove = new TextView(this);
        remove.setTextSize(13);
        remove.setTextColor(getResources().getColor(R.color.primary));
        remove.setPadding(dp(10), dp(4), 0, dp(4));
        remove.setText(R.string.feedbackcomposeactivity_024);
        remove.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (busy) return;
                if (index >= 0 && index < files.size()) {
                    files.remove(index);
                    refreshFiles();
                }
            }
        });
        row.addView(remove);
        return row;
    }

    // ------------------------------------------------------------ 提交

    private void submit() {
        final String title = titleView.getText().toString().trim();
        final String body = bodyView.getText().toString().trim();
        final String contact = contactView.getText().toString().trim();

        if (title.length() == 0) {
            AcToast.show(this, Strings.get(R.string.feedbackcomposeactivity_011));
            titleView.requestFocus();
            return;
        }
        if (body.length() == 0) {
            AcToast.show(this, Strings.get(R.string.feedbackcomposeactivity_012));
            bodyView.requestFocus();
            return;
        }
        if (body.length() < MIN_BODY_CHARS) {
            AcToast.show(this, Strings.get(R.string.feedbackcomposeactivity_013));
            bodyView.requestFocus();
            return;
        }

        setBusy(true);
        String appVersion = SystemInfo.appVersion(this);
        String device = SystemInfo.deviceModel() + " / Android " + SystemInfo.androidVersion();

        FeedbackApi.submit(this, title, body, contact, appVersion, device,
                new ArrayList<FeedbackApi.UploadFile>(files), new FeedbackApi.Callback() {
                    public void onSuccess(JSONObject data) {
                        setBusy(false);
                        setResult(Activity.RESULT_OK);
                        showResult(data);
                    }

                    public void onError(String message) {
                        setBusy(false);
                        AcToast.show(FeedbackComposeActivity.this,
                                Strings.get(R.string.feedbackcomposeactivity_023, message));
                    }
                });
    }

    private void setBusy(boolean value) {
        busy = value;
        submitButton.setEnabled(!value);
        pickButton.setEnabled(!value);
        submitButton.setText(value
                ? R.string.feedbackcomposeactivity_010
                : R.string.feedbackcomposeactivity_009);
        noticeView.setVisibility(value ? View.VISIBLE : View.GONE);
        if (value) noticeView.setText(R.string.feedbackcomposeactivity_027);
    }

    /**
     * 提交成功后的确认弹窗。把编号、AI 分配的类型与摘要在这一屏交代清楚：
     * 用户下一步要用编号回查，也要知道这条被归到了哪一类。
     */
    private void showResult(final JSONObject data) {
        final String id = data.optString("id");
        StringBuilder sb = new StringBuilder();
        sb.append(Strings.get(R.string.feedbackcomposeactivity_018, id));
        String typeText = data.optString("typeText");
        if (typeText.length() > 0) {
            sb.append('\n').append(Strings.get(R.string.feedbackcomposeactivity_019, typeText));
        }
        String summary = data.optString("summary");
        if (summary.length() > 0) {
            sb.append('\n').append(Strings.get(R.string.feedbackcomposeactivity_020, summary));
        }

        AcDialog.builder(this)
                .setTitle(Strings.get(R.string.feedbackcomposeactivity_026))
                .setMessage(sb.toString())
                .setPositiveButton(Strings.get(R.string.feedbackcomposeactivity_021),
                        new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int w) {
                                d.dismiss();
                                if (id.length() > 0) {
                                    Intent intent = new Intent(FeedbackComposeActivity.this,
                                            FeedbackDetailActivity.class);
                                    intent.putExtra(FeedbackDetailActivity.EXTRA_ID, id);
                                    startActivity(intent);
                                }
                                finish();
                            }
                        })
                .setNegativeButton(Strings.get(R.string.feedbackcomposeactivity_022),
                        new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int w) {
                                d.dismiss();
                                finish();
                            }
                        })
                // 已经提交成功，返回键不该把这一屏留在可编辑状态：再点一次提交就是重复反馈
                .setCancelable(false)
                .show();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
