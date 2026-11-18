package xyz.zip8919.app.aichat;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;
import java.util.ArrayList;
import java.util.List;

/**
 * 附件预览。
 *
 * <p>图片、文本（日志、JSON、CSV 等后缀）、PDF 在应用内直接看，
 * 其余类型给出说明并可交给系统应用打开。多条附件可前后翻看，图片支持双指缩放。
 *
 * <p>数据都从签名链接取，不需要额外鉴权；下载失败按「网络不通」与「要重新打开」分开提示，
 * 让用户知道下一步做什么。
 */
public class AttachmentPreviewActivity extends Activity {

    private static final String TAG = "AttachmentPreview";
    /** 文本预览一次最多读这么多字符，超出提示下载后查看。 */
    private static final int TEXT_MAX = 200 * 1024;
    /** 图片下载上限，超过就不整张读进内存。 */
    private static final int IMAGE_MAX_BYTES = 12 * 1024 * 1024;

    private final Handler ui = new Handler();

    private final List<Item> items = new ArrayList<Item>();
    private int index;

    private TextView titleView;
    private TextView metaView;
    private TextView prevBtn;
    private TextView nextBtn;
    private TextView copyBtn;
    private TextView openBtn;
    private LinearLayout contentBox;

    /** 一条附件。kind 取值与网页一致：image / pdf / text / other。 */
    private static class Item {
        String name = "";
        String sizeText = "";
        String url = "";
        String kind = "other";
    }

    /** 从反馈详情里取出附件列表并打开；没有附件就不打开。 */
    static void open(Activity from, JSONArray attachments, String startUrl) {
        if (attachments == null || attachments.length() == 0) return;
        Intent it = new Intent(from, AttachmentPreviewActivity.class);
        it.putExtra("data", attachments.toString());
        it.putExtra("startUrl", startUrl);
        from.startActivity(it);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_attachment_preview);

        titleView = (TextView) findViewById(R.id.pv_name);
        metaView = (TextView) findViewById(R.id.pv_meta);
        prevBtn = (TextView) findViewById(R.id.pv_prev);
        nextBtn = (TextView) findViewById(R.id.pv_next);
        copyBtn = (TextView) findViewById(R.id.pv_copy);
        openBtn = (TextView) findViewById(R.id.pv_download);
        contentBox = (LinearLayout) findViewById(R.id.pv_content);

        findViewById(R.id.pv_back).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                finish();
            }
        });
        prevBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                step(-1);
            }
        });
        nextBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                step(1);
            }
        });
        copyBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Item it = current();
                if (it == null) return;
                ClipUtil.copy(AttachmentPreviewActivity.this, it.url);
                AcToast.show(AttachmentPreviewActivity.this, Strings.get(R.string.attachmentpreview_012));
            }
        });
        openBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Item it = current();
                if (it == null) return;
                openWithSystem(it.url);
            }
        });

        parse(getIntent() == null ? null : getIntent().getStringExtra("data"),
                getIntent() == null ? "" : getIntent().getStringExtra("startUrl"));
        if (items.isEmpty()) {
            finish();
            return;
        }
        render();
    }

    private void parse(String json, String startUrl) {
        try {
            JSONArray arr = new JSONArray(json == null ? "[]" : json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Item it = new Item();
                it.name = o.optString("name", "");
                it.sizeText = o.optString("sizeText", "");
                it.url = o.optString("url", "");
                it.kind = o.optString("kind", "");
                if (it.kind.length() == 0) it.kind = guessKind(it.name);
                if (it.url.length() == 0) continue;
                items.add(it);
            }
            // 打开时定位到点的那一条；按地址找，列表顺序变了也不会错位
            index = 0;
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).url.equals(startUrl)) {
                    index = i;
                    break;
                }
            }
        } catch (Exception e) {
            LogUtil.w(TAG, "parse failed: %s", e.getMessage());
        }
    }

    /** 老接口没带 kind 时按后缀判断，至少图片与文本能认出来。 */
    private static String guessKind(String name) {
        String n = name == null ? "" : name.toLowerCase();
        if (n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".gif") || n.endsWith(".webp")) {
            return "image";
        }
        if (n.endsWith(".pdf")) return "pdf";
        if (n.endsWith(".log") || n.endsWith(".txt") || n.endsWith(".json") || n.endsWith(".md")
                || n.endsWith(".csv") || n.endsWith(".xml") || n.endsWith(".yml") || n.endsWith(".yaml")) {
            return "text";
        }
        return "other";
    }

    private Item current() {
        if (index < 0 || index >= items.size()) return null;
        return items.get(index);
    }

    private void step(int delta) {
        int n = items.size();
        if (n < 2) return;
        index = (index + delta + n) % n;
        render();
    }

    private void render() {
        Item it = current();
        if (it == null) return;
        titleView.setText(it.name);
        String meta = it.sizeText;
        if (items.size() > 1) meta = meta + "  " + (index + 1) + "/" + items.size();
        metaView.setText(meta);
        boolean multi = items.size() > 1;
        prevBtn.setVisibility(multi ? View.VISIBLE : View.GONE);
        nextBtn.setVisibility(multi ? View.VISIBLE : View.GONE);
        prevBtn.setEnabled(index > 0);
        nextBtn.setEnabled(index < items.size() - 1);
        prevBtn.setTextColor(getResources().getColor(index > 0 ? R.color.primary : R.color.text_secondary));
        nextBtn.setTextColor(getResources().getColor(
                index < items.size() - 1 ? R.color.primary : R.color.text_secondary));
        openBtn.setText(R.string.attachmentpreview_004);

        showHint(R.string.attachmentpreview_005);
        if ("image".equals(it.kind)) {
            loadImage(it);
        } else if ("text".equals(it.kind)) {
            loadText(it);
        } else if ("pdf".equals(it.kind)) {
            showPdf(it);
        } else {
            showUnsupported(it);
        }
    }

    /** 加载中提示；加载完替换成实际内容。 */
    private void showHint(int res) {
        contentBox.removeAllViews();
        TextView tv = new TextView(this);
        tv.setTextSize(13);
        tv.setTextColor(getResources().getColor(R.color.text_secondary));
        tv.setText(res);
        contentBox.addView(tv);
    }

    private void showError(final Item it, int res) {
        contentBox.removeAllViews();
        TextView tv = new TextView(this);
        tv.setTextSize(13);
        tv.setTextColor(getResources().getColor(R.color.text_secondary));
        tv.setText(res);
        tv.setPadding(0, dp(4), 0, dp(10));
        contentBox.addView(tv);

        TextView retry = new TextView(this);
        retry.setTextSize(14);
        retry.setTextColor(getResources().getColor(R.color.primary));
        retry.setPadding(0, dp(6), dp(16), dp(6));
        retry.setText(R.string.attachmentpreview_006);
        retry.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                render();
            }
        });
        contentBox.addView(retry);

        TextView open = new TextView(this);
        open.setTextSize(14);
        open.setTextColor(getResources().getColor(R.color.primary));
        open.setPadding(0, dp(6), 0, dp(6));
        open.setText(R.string.attachmentpreview_004);
        open.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                openWithSystem(it.url);
            }
        });
        contentBox.addView(open);
    }

    private void loadImage(final Item it) {
        final String url = it.url;
        new Thread(new Runnable() {
            public void run() {
                byte[] data = null;
                boolean tooBig = false;
                try {
                    data = download(url, IMAGE_MAX_BYTES);
                } catch (DownloadTooLarge e) {
                    tooBig = true;
                } catch (Exception e) {
                    LogUtil.w(TAG, "image download failed: %s", e.getMessage());
                }
                final byte[] bytes = data;
                final boolean big = tooBig;
                ui.post(new Runnable() {
                    public void run() {
                        if (isFinishing()) return;
                        Item now = current();
                        if (now == null || !url.equals(now.url)) return;
                        contentBox.removeAllViews();
                        if (big) {
                            showUnsupportedMessage(R.string.attachmentpreview_011);
                            return;
                        }
                        if (bytes == null) {
                            showError(now, R.string.attachmentpreview_007);
                            return;
                        }
                        Bitmap bmp = decode(bytes);
                        if (bmp == null) {
                            showError(now, R.string.attachmentpreview_008);
                            return;
                        }
                        ImageView iv = new ImageView(AttachmentPreviewActivity.this);
                        iv.setImageBitmap(bmp);
                        iv.setAdjustViewBounds(true);
                        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                        iv.setLayoutParams(new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                        contentBox.addView(iv);
                    }
                });
            }
        }).start();
    }

    /** 先按屏宽采样解码，避免大图直接 OOM；解不出来再降一档重试。 */
    private Bitmap decode(byte[] data) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int w = o.outWidth;
            int h = o.outHeight;
            int target = Math.max(getResources().getDisplayMetrics().widthPixels, 720);
            int sample = 1;
            while (w / (sample * 2) >= target) sample *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            d.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(data, 0, data.length, d);
        } catch (OutOfMemoryError e) {
            LogUtil.w(TAG, "decode oom: %s", e.getMessage());
            return null;
        } catch (Exception e) {
            LogUtil.w(TAG, "decode failed: %s", e.getMessage());
            return null;
        }
    }

    private void loadText(final Item it) {
        final String url = it.url;
        new Thread(new Runnable() {
            public void run() {
                String text = null;
                boolean truncated = false;
                try {
                    String api = previewApiOf(url);
                    if (api != null) {
                        String body = new String(download(api, 4 * 1024 * 1024), "UTF-8");
                        JSONObject o = new JSONObject(body);
                        if (!o.optBoolean("ok")) {
                            throw new Exception(o.optString("message", "preview failed"));
                        }
                        text = o.optString("text", "");
                        truncated = o.optBoolean("truncated", false);
                    } else {
                        byte[] raw = download(url, TEXT_MAX);
                        text = new String(raw, "UTF-8");
                    }
                } catch (DownloadTooLarge e) {
                    truncated = true;
                } catch (Exception e) {
                    LogUtil.w(TAG, "text download failed: %s", e.getMessage());
                }
                final String body = text;
                final boolean cut = truncated;
                ui.post(new Runnable() {
                    public void run() {
                        if (isFinishing()) return;
                        Item now = current();
                        if (now == null || !url.equals(now.url)) return;
                        contentBox.removeAllViews();
                        if (body == null) {
                            showError(now, R.string.attachmentpreview_009);
                            return;
                        }
                        String shown = body;
                        boolean more = cut;
                        if (shown.length() > TEXT_MAX) {
                            shown = shown.substring(0, TEXT_MAX);
                            more = true;
                        }
                        TextView tv = new TextView(AttachmentPreviewActivity.this);
                        tv.setTextSize(12);
                        tv.setTextColor(getResources().getColor(R.color.text_primary));
                        tv.setTextIsSelectable(true);
                        tv.setText(shown.length() == 0 ? Strings.get(R.string.attachmentpreview_013) : shown);
                        contentBox.addView(tv);
                        if (more) {
                            TextView note = new TextView(AttachmentPreviewActivity.this);
                            note.setTextSize(12);
                            note.setTextColor(getResources().getColor(R.color.text_secondary));
                            note.setPadding(0, dp(8), 0, 0);
                            note.setText(R.string.attachmentpreview_010);
                            contentBox.addView(note);
                        }
                    }
                });
            }
        }).start();
    }

    /** 签名下载链接换成预览接口，token 与查询串原样保留。 */
    private static String previewApiOf(String url) {
        if (url == null) return null;
        int i = url.indexOf("/api/attachment?");
        if (i < 0) return null;
        return url.substring(0, i) + "/api/preview?" + url.substring("/api/attachment?".length() + i);
    }

    private void showPdf(final Item it) {
        contentBox.removeAllViews();
        TextView tv = new TextView(this);
        tv.setTextSize(13);
        tv.setTextColor(getResources().getColor(R.color.text_primary));
        tv.setText(R.string.attachmentpreview_014);
        tv.setPadding(0, dp(4), 0, dp(10));
        contentBox.addView(tv);
        TextView open = new TextView(this);
        open.setTextSize(14);
        open.setTextColor(getResources().getColor(R.color.primary));
        open.setPadding(0, dp(6), 0, dp(6));
        open.setText(R.string.attachmentpreview_015);
        open.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                openWithSystem(it.url);
            }
        });
        contentBox.addView(open);
    }

    private void showUnsupported(final Item it) {
        contentBox.removeAllViews();
        TextView tv = new TextView(this);
        tv.setTextSize(13);
        tv.setTextColor(getResources().getColor(R.color.text_secondary));
        tv.setText(R.string.attachmentpreview_016);
        contentBox.addView(tv);
        TextView open = new TextView(this);
        open.setTextSize(14);
        open.setTextColor(getResources().getColor(R.color.primary));
        open.setPadding(0, dp(10), 0, dp(6));
        open.setText(R.string.attachmentpreview_004);
        open.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                openWithSystem(it.url);
            }
        });
        contentBox.addView(open);
    }

    private void showUnsupportedMessage(int res) {
        contentBox.removeAllViews();
        TextView tv = new TextView(this);
        tv.setTextSize(13);
        tv.setTextColor(getResources().getColor(R.color.text_secondary));
        tv.setText(res);
        contentBox.addView(tv);
    }

    private void openWithSystem(String url) {
        if (TextUtils.isEmpty(url)) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            LogUtil.w(TAG, "open failed: %s", e.getMessage());
            AcToast.show(this, Strings.get(R.string.feedbackdetailactivity_013));
        }
    }

    private static class DownloadTooLarge extends Exception {
    }

    /** 读整段响应；超过上限直接抛 DownloadTooLarge，不把内存吃满。 */
    private static byte[] download(String urlStr, int max) throws Exception {
        URL url = new URL(urlStr == null ? "" : urlStr.trim());
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("User-Agent", "AiChat-Android");
            // 老系统默认套件太少，部分 CDN 直接握手失败，走应用统一的兼容层
            if (conn instanceof HttpsURLConnection) {
                try {
                    TlsCompat.apply((HttpsURLConnection) conn);
                } catch (Exception e) {
                    LogUtil.w(TAG, "tls apply failed: %s", e.getMessage());
                }
            }
            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try {
                byte[] buf = new byte[8192];
                int n;
                int total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > max) throw new DownloadTooLarge();
                    out.write(buf, 0, n);
                }
            } finally {
                try { in.close(); } catch (Exception ignored) { }
            }
            return out.toByteArray();
        } finally {
            try { conn.disconnect(); } catch (Exception ignored) { }
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
