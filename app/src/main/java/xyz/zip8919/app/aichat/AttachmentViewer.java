package xyz.zip8919.app.aichat;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * 反馈附件预览：取数据 → 交给已有的 {@link ViewerController}。
 *
 * <p>刻意不另做预览页。App 里的查看器（图片 / 表格 / 代码 / 文件）本来就带缩放、
 * 旋转、背景色、导出和上下条，再写一套只是多一份要维护、还缺这些功能的分支。
 * 这里只负责「把网络附件变成查看器认得的东西」：
 * <ul>
 *   <li>图片 → 下载 → 采样解码 → data URI → {@link ViewerController#showImage}；
 *       一次把所有图片都转好，查看器里的上一张/下一张直接用</li>
 *   <li>文本（日志、JSON、CSV、代码等）→ {@link ViewerController#showDocument}，
 *       该高亮的按后缀高亮，.md 还能切渲染预览</li>
 *   <li>PDF 与其它二进制 → 查看器打不开，交还调用方用系统应用打开</li>
 * </ul>
 */
final class AttachmentViewer {

    private static final String TAG = "AttachmentViewer";

    /** 单张图片的下载上限，超过就不整张读进内存。 */
    private static final int IMAGE_MAX_BYTES = 20 * 1024 * 1024;
    /** 查看器里的大图长边，与本地附件一致（再大 base64 也进不了 WebView）。 */
    private static final int VIEW_PX = 1600;
    /** 单张压缩后的字节上限，超了降一档质量重压。 */
    private static final int VIEW_URI_MAX_BYTES = 900 * 1024;

    private AttachmentViewer() {
    }

    /** 附件是什么，决定走哪个查看器。取值与后端 {@code kind}、网页端保持一致。 */
    static final class Kind {
        static final String IMAGE = "image";
        static final String TEXT = "text";
        static final String PDF = "pdf";
        static final String OTHER = "other";
    }

    /** 能不能在应用内的查看器里看。 */
    static boolean canPreview(String kind) {
        return Kind.IMAGE.equals(kind) || Kind.TEXT.equals(kind);
    }

    /**
     * 打开一条附件。
     *
     * @param items   同一条反馈下的全部附件，图片会一起交给查看器以支持上下条
     * @param start   从哪一条开始（按地址定位，列表顺序变了也不错位）
     * @param onFallback 应用内看不了的，回调交给调用方用系统打开
     */
    static void open(final Activity activity, final ViewerController viewer,
                     final List<Item> items, final Item start,
                     final Fallback onFallback) {
        if (activity == null || viewer == null || items == null || start == null) return;

        if (Kind.IMAGE.equals(start.kind)) {
            openImages(activity, viewer, items, start, onFallback);
            return;
        }
        if (Kind.TEXT.equals(start.kind)) {
            openText(activity, viewer, start, onFallback);
            return;
        }
        // PDF 需要系统阅读器渲染，其它二进制本来也没有可读内容
        if (onFallback != null) onFallback.openWithSystem(start.url);
    }

    /** 应用内看不到时的出口，通常是 {@code Intent.ACTION_VIEW}。 */
    interface Fallback {
        void openWithSystem(String url);
    }

    /** 一条附件。kind 取自接口，老接口没带就按后缀猜。 */
    static final class Item {
        final String name;
        final String sizeText;
        final String url;
        final String kind;

        Item(String name, String sizeText, String url, String kind) {
            this.name = name == null ? "" : name;
            this.sizeText = sizeText == null ? "" : sizeText;
            this.url = url == null ? "" : url;
            String k = kind == null ? "" : kind;
            this.kind = k.length() == 0 ? guessKind(this.name) : k;
        }
    }

    /** 接口里的附件数组 → 列表；没有地址的条目直接丢掉。 */
    static List<Item> parse(JSONArray arr) {
        List<Item> out = new ArrayList<Item>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Item it = new Item(o.optString("name"), o.optString("sizeText"),
                    o.optString("url"), o.optString("kind"));
            if (it.url.length() == 0) continue;
            out.add(it);
        }
        return out;
    }

    /** 老接口没带 kind 时按后缀判断，至少图片与文本能认出来。 */
    static String guessKind(String name) {
        String n = name == null ? "" : name.toLowerCase();
        if (n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
                || n.endsWith(".gif") || n.endsWith(".webp") || n.endsWith(".bmp")) {
            return Kind.IMAGE;
        }
        if (n.endsWith(".pdf")) return Kind.PDF;
        if (n.endsWith(".log") || n.endsWith(".txt") || n.endsWith(".json") || n.endsWith(".md")
                || n.endsWith(".csv") || n.endsWith(".xml") || n.endsWith(".yml")
                || n.endsWith(".yaml") || n.endsWith(".ini") || n.endsWith(".cfg")
                || n.endsWith(".conf") || n.endsWith(".java") || n.endsWith(".js")
                || n.endsWith(".html") || n.endsWith(".htm") || n.endsWith(".svg")) {
            return Kind.TEXT;
        }
        return Kind.OTHER;
    }

    // ---------------------------------------------------------------- 图片

    /**
     * 所有图片一起转成 data URI 再开查看器：查看器按数组翻页，
     * 只给一张就变成「点了下一张是空白」。
     */
    private static void openImages(final Activity activity, final ViewerController viewer,
                                   final List<Item> items, final Item start,
                                   final Fallback onFallback) {
        final List<Item> images = new ArrayList<Item>();
        int startIndex = 0;
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if (!Kind.IMAGE.equals(it.kind)) continue;
            if (it == start) startIndex = images.size();
            images.add(it);
        }
        if (images.isEmpty()) {
            if (onFallback != null) onFallback.openWithSystem(start.url);
            return;
        }
        final int index = startIndex;
        final Handler ui = new Handler(Looper.getMainLooper());

        new Thread(new Runnable() {
            public void run() {
                final JSONArray arr = new JSONArray();
                String failure = null;
                for (int i = 0; i < images.size(); i++) {
                    Item it = images.get(i);
                    String src = null;
                    try {
                        src = remoteDataUri(it.url);
                    } catch (Exception e) {
                        LogUtil.w(TAG, "image %s failed: %s", it.name, e.getMessage());
                    }
                    if (src == null) {
                        // 当前这张都拿不到就直接报错；其它图失败只是少一张，不打断
                        if (i == index) {
                            failure = it.name;
                            break;
                        }
                        continue;
                    }
                    try {
                        JSONObject o = new JSONObject();
                        o.put("src", src);
                        o.put("alt", it.name);
                        o.put("type", "raster");
                        arr.put(o);
                    } catch (Exception e) {
                        LogUtil.w(TAG, "image json failed: %s", e.getMessage());
                    }
                }

                final String bad = failure;
                final String json = arr.toString();
                ui.post(new Runnable() {
                    public void run() {
                        if (activity.isFinishing()) return;
                        if (bad != null || arr.length() == 0) {
                            AcToast.show(activity, Strings.get(R.string.feedbackdetailactivity_022));
                            return;
                        }
                        viewer.showImage(Math.min(index, arr.length() - 1), json);
                    }
                });
            }
        }, "aichat-attachment-image").start();
    }

    /**
     * 下载并按长边采样解码，压成 data URI。
     *
     * <p>不落盘：附件是「看一眼就走」的，落盘就得配一套回收逻辑，
     * 而会话删除时还得判断哪张图还有引用。
     */
    private static String remoteDataUri(String urlStr) throws Exception {
        byte[] data = download(urlStr, IMAGE_MAX_BYTES);
        Bitmap bmp = null;
        Bitmap scaled = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, VIEW_PX);
            bmp = BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (bmp == null) return null;

            int w = bmp.getWidth();
            int h = bmp.getHeight();
            int max = Math.max(w, h);
            if (max > VIEW_PX) {
                float ratio = (float) VIEW_PX / max;
                scaled = Bitmap.createScaledBitmap(bmp,
                        Math.max(1, Math.round(w * ratio)),
                        Math.max(1, Math.round(h * ratio)), true);
            }
            Bitmap src = scaled != null ? scaled : bmp;
            boolean alpha = src.hasAlpha();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            // 带透明的（截图、图标）用 PNG，JPEG 会把透明压成黑底
            if (alpha) {
                src.compress(Bitmap.CompressFormat.PNG, 100, bos);
            } else {
                src.compress(Bitmap.CompressFormat.JPEG, 85, bos);
            }
            String mime = alpha ? "png" : "jpeg";
            if (bos.size() > VIEW_URI_MAX_BYTES) {
                ByteArrayOutputStream jpg = new ByteArrayOutputStream();
                src.compress(Bitmap.CompressFormat.JPEG, 70, jpg);
                if (jpg.size() < bos.size()) {
                    bos = jpg;
                    mime = "jpeg";
                }
            }
            LogUtil.d(TAG, "image %dx%d -> %d bytes (%s)", w, h, bos.size(), mime);
            return "data:image/" + mime + ";base64,"
                    + android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP);
        } finally {
            if (scaled != null) scaled.recycle();
            if (bmp != null) bmp.recycle();
        }
    }

    private static int sampleSize(int w, int h, int target) {
        int max = Math.max(w, h);
        int sample = 1;
        while (max / (sample * 2) >= target) sample *= 2;
        return sample;
    }

    // ---------------------------------------------------------------- 文本

    /** 文本走文件查看器：按后缀高亮，.md 可切渲染预览。 */
    private static void openText(final Activity activity, final ViewerController viewer,
                                 final Item it, final Fallback onFallback) {
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            public void run() {
                String text = null;
                boolean truncated = false;
                try {
                    String api = previewApiOf(it.url);
                    if (api != null) {
                        byte[] raw = download(api, 8 * 1024 * 1024);
                        JSONObject o = new JSONObject(new String(raw, "UTF-8"));
                        if (!o.optBoolean("ok")) {
                            throw new Exception(o.optString("message", "preview failed"));
                        }
                        text = o.optString("text", "");
                        truncated = o.optBoolean("truncated", false);
                    } else {
                        // 没有签名链接格式时退回直接读，服务端会按类型给内容
                        text = new String(download(it.url, 1024 * 1024), "UTF-8");
                    }
                } catch (Exception e) {
                    LogUtil.w(TAG, "text %s failed: %s", it.name, e.getMessage());
                }

                final String body = text;
                final boolean cut = truncated;
                ui.post(new Runnable() {
                    public void run() {
                        if (activity.isFinishing()) return;
                        if (body == null) {
                            AcToast.show(activity, Strings.get(R.string.feedbackdetailactivity_023));
                            return;
                        }
                        if (body.trim().length() == 0) {
                            AcToast.show(activity, Strings.get(R.string.feedbackdetailactivity_024));
                            return;
                        }
                        if (cut) {
                            AcToast.show(activity, Strings.get(R.string.feedbackdetailactivity_025));
                        }
                        viewer.showDocument(it.name, FileKinds.languageOf(it.name), body);
                    }
                });
            }
        }, "aichat-attachment-text").start();
    }

    /** 签名下载链接换成预览接口，token 与查询串原样保留。 */
    private static String previewApiOf(String url) {
        if (url == null) return null;
        int i = url.indexOf("/api/attachment?");
        if (i < 0) return null;
        return url.substring(0, i) + "/api/preview?" + url.substring("/api/attachment?".length() + i);
    }

    // ---------------------------------------------------------------- 网络

    /** 读整段响应；超过上限直接中断，不把内存吃满。 */
    private static byte[] download(String urlStr, int max) throws Exception {
        URL url = new URL(urlStr == null ? "" : urlStr.trim());
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("User-Agent", "AiChat-Android");
            // 老系统默认套件太少，部分 CDN 直接握手失败，走应用统一的兼容层
            if (conn instanceof javax.net.ssl.HttpsURLConnection) {
                try {
                    TlsCompat.apply((javax.net.ssl.HttpsURLConnection) conn);
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
                    if (total > max) throw new Exception("body too large");
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
}
