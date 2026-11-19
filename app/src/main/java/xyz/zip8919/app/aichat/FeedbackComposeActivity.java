package xyz.zip8919.app.aichat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;

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
    /** 摘要由服务端在提交之后生成，这里按 1.2 秒一轮、最多 20 轮去取。 */
    private static final long SUMMARY_POLL_MS = 1200;
    private static final int SUMMARY_POLL_TRIES = 20;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private EditText titleView;
    private EditText bodyView;
    private EditText nicknameView;
    private EditText contactView;
    private Button pickButton;
    private Button submitButton;
    private LinearLayout filesBox;
    private TextView footnoteView;
    private TextView noticeView;

    private final List<FeedbackApi.UploadFile> files = new ArrayList<FeedbackApi.UploadFile>();
    private boolean busy = false;
    /** 摘要轮询的代次，弹窗关掉后加一，让还在排队的下一轮直接作废。 */
    private int pollSeq = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_feedback_compose);

        titleView = (EditText) findViewById(R.id.compose_title);
        bodyView = (EditText) findViewById(R.id.compose_body);
        nicknameView = (EditText) findViewById(R.id.compose_nickname);
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
        final String nickname = nicknameView.getText().toString().trim();
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

        setBusy(true);
        String appVersion = SystemInfo.appVersion(this);
        String device = SystemInfo.deviceModel() + " / Android " + SystemInfo.androidVersion();

        FeedbackApi.submit(this, title, body, nickname, contact, appVersion, device,
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
     * 提交成功后的确认弹窗。先给出编号，再看摘要：
     * 摘要由服务端在响应之后生成，所以这里先显示「生成中」，取到结果或超时再改。
     *
     * <p>邮件会晚半分钟才发出，这段时间里可以撤销。撤销后内容与附件都填回编辑页，
     * 用户可以改完重新提交，而不是重写一遍。
     */
    private void showResult(final JSONObject data) {
        final String id = data.optString("id");
        final String undoToken = data.optString("undoToken");
        final boolean canUndo = data.optBoolean("canUndo", false) && undoToken.length() > 0;
        final String base = Strings.get(R.string.feedbackcomposeactivity_018, id);

        final String summary = data.optString("summary");
        final boolean hasSummary = summary.length() > 0;
        final String message = hasSummary
                ? base + "\n" + Strings.get(R.string.feedbackcomposeactivity_020, summary)
                : base + "\n" + Strings.get(R.string.feedbackcomposeactivity_029);
        final String withUndo = canUndo
                ? message + "\n\n" + Strings.get(R.string.feedbackcomposeactivity_036)
                : message;

        final int seq = ++pollSeq;
        final AlertDialog.Builder builder = AcDialog.builder(this)
                .setTitle(Strings.get(R.string.feedbackcomposeactivity_026))
                .setMessage(withUndo)
                .setPositiveButton(Strings.get(R.string.feedbackcomposeactivity_021),
                        new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int w) {
                                d.dismiss();
                                pollSeq++;
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
                                pollSeq++;
                                finish();
                            }
                        })
                // 已经提交成功，返回键不该把这一屏留在可编辑状态：再点一次提交就是重复反馈
                .setCancelable(false);
        if (canUndo) {
            builder.setNeutralButton(Strings.get(R.string.feedbackcomposeactivity_034),
                    new android.content.DialogInterface.OnClickListener() {
                        public void onClick(android.content.DialogInterface d, int w) {
                            d.dismiss();
                            pollSeq++;
                            undo(id, undoToken, withUndo);
                        }
                    });
        }
        final AlertDialog dialog = builder.create();
        // 弹窗一旦没了就作废所有在途轮询，不只是靠两个按钮的点击
        dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            public void onDismiss(android.content.DialogInterface d) {
                pollSeq++;
            }
        });
        dialog.show();

        if (!hasSummary && id.length() > 0) pollSummary(id, seq, base, dialog, 0);
    }

    /**
     * 撤销提交。成功后把返回的内容与附件填回编辑页，用户可以接着改。
     *
     * <p>失败按原因分开说：时间过了就是「已经发出」，别让用户以为是网络问题反复重试。
     */
    private void undo(final String id, final String token, final String restoreMessage) {
        AcToast.show(this, Strings.get(R.string.feedbackcomposeactivity_035));
        FeedbackApi.undo(id, token, new FeedbackApi.Callback() {
            public void onSuccess(JSONObject data) {
                JSONObject draft = data.optJSONObject("draft");
                if (draft == null) {
                    AcToast.show(FeedbackComposeActivity.this,
                            Strings.get(R.string.feedbackcomposeactivity_033));
                    return;
                }
                fillDraft(draft);
                AcToast.showLong(FeedbackComposeActivity.this,
                        Strings.get(R.string.feedbackcomposeactivity_037));
            }

            public void onError(String message) {
                AcToast.showLong(FeedbackComposeActivity.this,
                        message == null || message.length() == 0
                                ? Strings.get(R.string.feedbackcomposeactivity_033) : message);
            }
        });
    }

    /** 把撤销返回的内容填回输入框，附件取回来重新加入待提交列表。 */
    private void fillDraft(JSONObject draft) {
        titleView.setText(draft.optString("title"));
        bodyView.setText(draft.optString("body"));
        nicknameView.setText(draft.optString("nickname"));
        contactView.setText(draft.optString("contact"));

        files.clear();
        refreshFiles();

        JSONArray atts = draft.optJSONArray("attachments");
        if (atts == null || atts.length() == 0) return;

        final List<String> urls = new ArrayList<String>();
        final List<String> names = new ArrayList<String>();
        for (int i = 0; i < atts.length() && urls.size() < MAX_FILES; i++) {
            JSONObject a = atts.optJSONObject(i);
            if (a == null) continue;
            String url = a.optString("url");
            if (url.length() == 0) continue;
            urls.add(url);
            names.add(a.optString("name", "附件"));
        }
        if (urls.isEmpty()) return;

        // 附件要从网络取回来：本机没有原文件，服务端暂存了一份
        new Thread(new Runnable() {
            public void run() {
                final List<FeedbackApi.UploadFile> got = new ArrayList<FeedbackApi.UploadFile>();
                for (int i = 0; i < urls.size(); i++) {
                    File f = null;
                    try {
                        f = restoreFile(urls.get(i), names.get(i));
                    } catch (Exception e) {
                        LogUtil.w(TAG, "restore attachment failed: %s", e.getMessage());
                    }
                    if (f == null) continue;
                    got.add(new FeedbackApi.UploadFile(Uri.fromFile(f), names.get(i),
                            f.length(), null));
                }
                runOnUiThread(new Runnable() {
                    public void run() {
                        files.clear();
                        files.addAll(got);
                        refreshFiles();
                        if (got.size() < urls.size()) {
                            AcToast.show(FeedbackComposeActivity.this,
                                    Strings.get(R.string.feedbackcomposeactivity_039));
                        }
                    }
                });
            }

            private File restoreFile(String url, String name) throws Exception {
                // 附件只在这里短暂落地，提交后由系统缓存目录的自然回收处理
                File dir = new File(getCacheDir(), "feedback-undo");
                if (!dir.isDirectory() && !dir.mkdirs()) return null;
                File out = new File(dir, safeName(name));
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                try {
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(30000);
                    conn.setRequestProperty("Accept-Encoding", "identity");
                    conn.setRequestProperty("User-Agent", "AiChat-Android");
                    if (conn instanceof HttpsURLConnection) TlsCompat.apply((HttpsURLConnection) conn);
                    if (conn.getResponseCode() != 200) return null;
                    InputStream in = conn.getInputStream();
                    OutputStream os = new FileOutputStream(out);
                    try {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                    } finally {
                        try { in.close(); } catch (Exception ignored) { }
                        try { os.close(); } catch (Exception ignored) { }
                    }
                    return out.length() > 0 ? out : null;
                } finally {
                    conn.disconnect();
                }
            }
        }, "aichat-feedback-undo").start();
    }

    /** 附件名来自服务端，落盘前只保留安全字符，避免路径穿越。 */
    private static String safeName(String name) {
        String base = name == null ? "file" : name.replaceAll("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5]", "_");
        if (base.length() == 0 || base.startsWith(".")) base = "file" + base;
        return base.length() > 80 ? base.substring(base.length() - 80) : base;
    }

    /**
     * 轮询摘要直到有结果。20 轮之后停下，用户留在这一屏时不会一直占着网络。
     * dialog 已关或代次变了就直接退出，不必再改界面。
     */
    private void pollSummary(final String id, final int seq, final String base,
                             final AlertDialog dialog, final int done) {
        if (seq != pollSeq) return;
        FeedbackApi.detail(id, new FeedbackApi.Callback() {
            public void onSuccess(JSONObject data) {
                if (seq != pollSeq) return;
                JSONObject item = data.optJSONObject("item");
                String text = item == null ? "" : item.optString("summary");
                String state = item == null ? "" : item.optString("summaryState");
                if (text.length() > 0) {
                    dialog.setMessage(base + "\n"
                            + Strings.get(R.string.feedbackcomposeactivity_020, text));
                    return;
                }
                // 服务端明确说生成失败，或轮次用完，都要给一句收尾，不能让「生成中」一直挂着
                if (!"pending".equals(state)) {
                    dialog.setMessage(base + "\n"
                            + Strings.get(R.string.feedbackcomposeactivity_030));
                    return;
                }
                retryPoll(id, seq, base, dialog, done);
            }

            public void onError(String message) {
                retryPoll(id, seq, base, dialog, done);
            }
        });
    }

    private void retryPoll(final String id, final int seq, final String base,
                           final AlertDialog dialog, final int done) {
        if (seq != pollSeq) return;
        if (done >= SUMMARY_POLL_TRIES) {
            dialog.setMessage(base + "\n" + Strings.get(R.string.feedbackcomposeactivity_031));
            return;
        }
        MAIN_HANDLER.postDelayed(new Runnable() {
            public void run() {
                if (seq != pollSeq) return;
                pollSummary(id, seq, base, dialog, done + 1);
            }
        }, SUMMARY_POLL_MS);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
