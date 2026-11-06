package xyz.zip8919.app.aichat;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;

/**
 * 图片附件与「查看文件」用到的文件级工具：落盘、缩略图、查看用的 data URI、语言识别。
 *
 * <p>三个约束决定了这里的写法：
 * <ul>
 *   <li><b>原 URI 活不过重启</b>：{@code ACTION_GET_CONTENT} 给的读权限只跟当前
 *       Activity 绑定，消息要能重启后继续查看，就必须把字节拷进私有目录；</li>
 *   <li><b>不能把整张图读进内存</b>：API 18 设备的堆常见 128 MB，一张手机原图
 *       ARGB_8888 就是 30~50 MB，所以一律先取边界、再按 {@code inSampleSize} 采样；</li>
 *   <li><b>WebView 不能直接读 file://</b>：聊天页用 {@code loadDataWithBaseURL(null,…)}
 *       加载，本地路径会被当成跨源资源拦掉，因此查看器走 Base64 data URI。</li>
 * </ul>
 */
final class AttachmentFiles {

    private static final String TAG = "AttachmentFiles";

    /** 单张图片上限 20 MB：再大就不是「贴张截图」而是相册原图，拷进来只会拖慢一切。 */
    static final int MAX_IMAGE_BYTES = 20 * 1024 * 1024;

    /** 缩略图长边像素。卡片上只占 36dp，160px 在 3x 屏上也够清晰。 */
    private static final int THUMB_PX = 160;

    /** 查看器里的最长边。1600px 在手机屏上已经能看清细节，再多只是烧内存。 */
    private static final int VIEW_PX = 1600;

    /**
     * 发给模型的编码档位哨兵：{@code <= 0} 表示按 {@link ImageTokens#apiSide} 的像素预算
     * （约 1302²，即模型 1024 token 网格的满档）等比取档。比预算再大也会被端点缩回去，
     * 白白让请求体多出几百 KB；比预算小则模型少看细节、卡片估算还和账单对不上。
     */
    static final int API_BUDGET = 0;

    /** 单张网络图片的下载上限：和本地导入一致，防止一个链接把内存打爆。 */
    static final int MAX_REMOTE_BYTES = MAX_IMAGE_BYTES;

    private AttachmentFiles() {
    }

    // ---------------------------------------------------------------- 导入

    /**
     * 把用户选的图片拷进私有目录，返回可直接挂到消息上的附件。
     *
     * <p>文件名用内容 SHA-1：同一张图重复附加只会占一份空间，也不会因为
     * 「IMG_0001 (1).jpg」这种重名互相覆盖。先写临时文件再改名，中途失败
     * 不会留下一个名字正确、内容半截的坏图。
     */
    static AttachmentText.File importImage(Context ctx, Uri uri, String displayName, int maxBytes)
            throws Exception {
        InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) throw new Exception("无法打开图片");
        return importStream(ctx, in, displayName, maxBytes);
    }

    /**
     * 从任意输入流导入图片（URL 下载与本地选择共用）。
     *
     * <p>调用方负责关闭外层连接；这里只保证 {@code in} 被关闭。
     */
    static AttachmentText.File importStream(Context ctx, InputStream in, String displayName,
            int maxBytes) throws Exception {
        File dir = new File(ctx.getFilesDir(), "attachments");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("无法创建附件目录");
        sweepTempFiles(dir);
        File tmp = new File(dir, "tmp-" + System.currentTimeMillis() + ".bin");

        long total = 0;
        String digest;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > maxBytes) {
                        throw new Exception("图片超过 " + AttachmentText.formatBytes(maxBytes));
                    }
                    md.update(buf, 0, n);
                    out.write(buf, 0, n);
                }
                out.flush();
            } finally {
                try { out.close(); } catch (Exception ignored) { }
            }
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format(Locale.US, "%02x", b & 0xff));
            digest = sb.toString();
        } finally {
            try { in.close(); } catch (Exception ignored) { }
        }

        if (total == 0) {
            tmp.delete();
            throw new Exception("图片是空文件");
        }

        File target = new File(dir, digest + extensionOf(displayName));
        if (target.exists()) {
            // 同一张图已经在库里：临时文件没用了
            tmp.delete();
        } else if (!tmp.renameTo(target)) {
            tmp.delete();
            throw new Exception("保存图片失败");
        }

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(target.getAbsolutePath(), bounds);
        int w = bounds.outWidth > 0 ? bounds.outWidth : 0;
        int h = bounds.outHeight > 0 ? bounds.outHeight : 0;
        if (w <= 0 || h <= 0) {
            // 解不出边界说明不是图片（或格式不支持）。删掉刚拷的文件，
            // 不要留一个点开就白屏的附件。
            target.delete();
            throw new Exception("不是可识别的图片格式");
        }

        String thumb = thumbDataUri(target.getAbsolutePath(), w, h);
        LogUtil.i(TAG, "image imported: name=%s bytes=%d %dx%d thumb=%d",
                displayName, total, w, h, thumb.length());
        return AttachmentText.File.image(displayName, target.getAbsolutePath(),
                total, w, h, thumb);
    }

    /**
     * 把内容 Uri 拷到 cacheDir，供裁剪页读取。
     *
     * <p>必须拷一次：{@code ACTION_GET_CONTENT} 在 API 18 上把读权限授给
     * 「拿到结果的那个 Activity」，不是整个进程。裁剪页是另一个 Activity，
     * 直接读原 Uri 会抛 SecurityException，而且这个异常在部分 ROM 上表现为
     * 打开裁剪页就闪退，很难从堆栈看出根因。
     */
    static File stageToCache(Context ctx, Uri uri, int maxBytes) throws Exception {
        InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) throw new Exception("无法打开图片");
        File dir = new File(ctx.getCacheDir(), "camera");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("无法创建缓存目录");
        File out = new File(dir, "pick-" + System.currentTimeMillis() + ".bin");
        try {
            FileOutputStream fos = new FileOutputStream(out);
            try {
                byte[] buf = new byte[8192];
                int n;
                long total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > maxBytes) {
                        throw new Exception("图片超过 "
                                + AttachmentText.formatBytes(maxBytes));
                    }
                    fos.write(buf, 0, n);
                }
                fos.flush();
            } finally {
                try { fos.close(); } catch (Exception ignored) { }
            }
            if (out.length() == 0) throw new Exception("图片是空文件");
            return out;
        } catch (Exception e) {
            // 半截文件留在 cache 里只会占地方，下次也读不出图
            if (out.exists()) out.delete();
            throw e;
        } finally {
            try { in.close(); } catch (Exception ignored) { }
        }
    }

    /**
     * 从图片链接导入。
     *
     * <p>两道校验：私网地址（未开内网访问时拒绝）与 Content-Type。后者不能省——
     * 很多图床对不存在的图片返回 200 + 一张 404 占位图，用户拿到的是张「Not Found」
     * 的图还以为附上了；而 Content-Type 至少能挡住「链接指向网页」这种。
     */
    static AttachmentText.File importUrl(Context ctx, String urlStr, int maxBytes)
            throws Exception {
        URL url = new URL(urlStr == null ? "" : urlStr.trim());
        String protocol = url.getProtocol();
        if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
            throw new Exception("只支持 http/https 链接");
        }
        HttpURLConnection conn = openFollowingRedirects(url);
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            String type = conn.getContentType();
            String name = nameFromUrl(url, type);
            if (!FileKinds.looksLikeImage(name, type)) {
                throw new Exception(type == null
                        ? "这个链接不是图片" : "这个链接不是图片（" + type + "）");
            }
            InputStream in = conn.getInputStream();
            if (in == null) throw new Exception("无法读取图片");
            return importStream(ctx, in, name, maxBytes);
        } finally {
            try { conn.disconnect(); } catch (Exception ignored) { }
        }
    }

    /**
     * 手动跟随重定向，顺便把每一跳都过一遍私网校验。
     *
     * <p>不能开 {@code setInstanceFollowRedirects(true)}：那样跳转目标是黑盒，
     * 一个公网地址 302 到 {@code 10.0.0.1} 就能绕开内网限制，把图片导入变成
     * 内网探测工具。
     */
    private static HttpURLConnection openFollowingRedirects(URL url) throws Exception {
        URL current = url;
        for (int hop = 0; hop < 4; hop++) {
            if (!NetSecurity.allowPrivateHost()) HttpRequestTool.assertPublicHost(current);
            HttpURLConnection conn = (HttpURLConnection) current.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(false);
            // 不带 UA 的请求会被不少图床直接 403
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 4.3) AiChat");
            conn.setRequestProperty("Accept", "image/*,*/*;q=0.8");
            if (conn instanceof HttpsURLConnection) {
                TlsCompat.apply((HttpsURLConnection) conn);
            }
            int code = conn.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = conn.getHeaderField("Location");
                try { conn.disconnect(); } catch (Exception ignored) { }
                if (location == null || location.length() == 0) {
                    throw new Exception("重定向缺少目标地址");
                }
                current = new URL(current, location);
                continue;
            }
            return conn;
        }
        throw new Exception("重定向次数过多");
    }

    /** 从 URL 的路径段取文件名；没有扩展名时按 Content-Type 补一个。 */
    private static String nameFromUrl(URL url, String contentType) {
        String path = url.getPath();
        String name = "";
        if (path != null) {
            int slash = path.lastIndexOf('/');
            name = slash >= 0 ? path.substring(slash + 1) : path;
        }
        try {
            name = java.net.URLDecoder.decode(name, "UTF-8");
        } catch (Exception ignored) {
            // 编码坏了就用原样，文件名只是显示用
        }
        if (name.length() == 0 || name.lastIndexOf('.') < 0) {
            name = (name.length() == 0 ? "image" : name) + extForMime(contentType);
        }
        return name;
    }

    private static String extForMime(String contentType) {
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.US);
        if (ct.startsWith("image/png")) return ".png";
        if (ct.startsWith("image/gif")) return ".gif";
        if (ct.startsWith("image/webp")) return ".webp";
        if (ct.startsWith("image/bmp")) return ".bmp";
        return ".jpg";
    }

    /**
     * 清掉上一次失败留下的临时文件。
     *
     * <p>拷 20 MB 的图时用户切走、进程被杀，{@code tmp-*} 就会留在目录里。
     * 只删一天前的：正在写的那份时间戳是新的，误删会直接把导入搞坏。
     */
    private static void sweepTempFiles(File dir) {
        try {
            File[] files = dir.listFiles();
            if (files == null) return;
            long cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000;
            for (File f : files) {
                if (f.getName().startsWith("tmp-") && f.lastModified() < cutoff) {
                    if (f.delete()) LogUtil.d(TAG, "swept stale temp: %s", f.getName());
                }
            }
        } catch (Exception e) {
            LogUtil.w(TAG, "sweepTempFiles failed: %s", e.getMessage());
        }
    }

    private static String extensionOf(String name) {
        if (name != null) {
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && dot < name.length() - 1) {
                String ext = name.substring(dot + 1).toLowerCase(Locale.US);
                // 只允许短小的字母数字扩展名：显示名来自文件提供方，不可信
                if (ext.length() <= 5 && ext.matches("[a-z0-9]+")) return "." + ext;
            }
        }
        return ".jpg";
    }

    // ---------------------------------------------------------------- 缩略图 / 查看图

    /** 卡片缩略图：正方形背景图用，返回 {@code data:image/jpeg;base64,...}；失败返回空串。 */
    static String thumbDataUri(String path, int srcW, int srcH) {
        Bitmap bmp = null;
        try {
            int sample = sampleSize(srcW, srcH, THUMB_PX);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            bmp = BitmapFactory.decodeFile(path, o);
            if (bmp == null) return "";
            Bitmap scaled = scaleTo(bmp, THUMB_PX);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, 70, bos);
            if (scaled != bmp) scaled.recycle();
            return "data:image/jpeg;base64,"
                    + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
        } catch (Throwable t) {
            // 缩略图只是锦上添花，生成失败就当没有，不要让整条附件导入失败
            LogUtil.w(TAG, "thumb failed: %s", t.getMessage());
            return "";
        } finally {
            if (bmp != null) bmp.recycle();
        }
    }

    /** 查看器用的大图：按 1600px 采样后转 data URI，返回 null 表示文件不可用。 */
    static String viewDataUri(String path) {
        if (path == null || path.length() == 0) return null;
        File f = new File(path);
        if (!f.isFile() || f.length() <= 0) return null;

        Bitmap bmp = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            int w = bounds.outWidth > 0 ? bounds.outWidth : VIEW_PX;
            int h = bounds.outHeight > 0 ? bounds.outHeight : VIEW_PX;

            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sampleSize(w, h, VIEW_PX);
            bmp = BitmapFactory.decodeFile(path, o);
            if (bmp == null) return null;
            Bitmap scaled = scaleTo(bmp, VIEW_PX);
            boolean hasAlpha = scaled.hasAlpha();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            // 带透明通道的（截图、图标）存 PNG：JPEG 会把透明压成黑底
            if (hasAlpha) {
                scaled.compress(Bitmap.CompressFormat.PNG, 100, bos);
            } else {
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, bos);
            }
            if (scaled != bmp) scaled.recycle();
            String mime = hasAlpha ? "png" : "jpeg";
            LogUtil.d(TAG, "viewDataUri: %dx%d -> %d bytes (%s)", w, h, bos.size(), mime);
            return "data:image/" + mime + ";base64,"
                    + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
        } catch (Throwable t) {
            LogUtil.w(TAG, "viewDataUri failed: %s", t.getMessage());
            return null;
        } finally {
            if (bmp != null) bmp.recycle();
        }
    }

    /**
     * 多模态请求体用的 data URI：压到 {@link ImageTokens#apiSide} 的像素预算、JPEG 82。
     *
     * <p>比查看器那份更省：这是发给模型的，模型只用 1302² 以内的像素，
     * 多发的像素纯属浪费流量。
     */
    static String apiDataUri(String path) {
        return encodeToDataUri(path, API_BUDGET, 82);
    }

    /**
     * 远程图片 → data URI（图片搜索卡片查看、视觉注入）。
     *
     * <p>刻意不落盘：搜索结果图是「看一眼就走」的，落盘就得配一套回收逻辑，
     * 而会话删除时还得判断哪张图还有引用。内存里转一次就扔最省事。
     */
    static String remoteToDataUri(String urlStr, int maxSide) throws Exception {
        byte[] data = downloadBytes(urlStr);
        Bitmap bmp = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            int w = bounds.outWidth;
            int h = bounds.outHeight;
            if (w <= 0 || h <= 0) throw new Exception("这个链接不是可识别的图片");

            int target = maxSide > 0 ? maxSide : ImageTokens.apiSide(w, h);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sampleSize(w, h, target);
            bmp = BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (bmp == null) throw new Exception("图片解码失败");
            String uri = compressToDataUri(bmp, target, 82);
            if (uri == null) throw new Exception("图片编码失败");
            return uri;
        } finally {
            if (bmp != null) bmp.recycle();
        }
    }

    /** 本地文件 → data URI；任何一步失败返回 null（调用方退回纯文本）。 */
    static String encodeToDataUri(String path, int maxSide, int quality) {
        if (path == null || path.length() == 0) return null;
        File f = new File(path);
        if (!f.isFile() || f.length() <= 0) return null;
        Bitmap bmp = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            int w = bounds.outWidth;
            int h = bounds.outHeight;
            if (w <= 0 || h <= 0) return null;

            int target = maxSide > 0 ? maxSide : ImageTokens.apiSide(w, h);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sampleSize(w, h, target);
            bmp = BitmapFactory.decodeFile(path, o);
            if (bmp == null) return null;
            return compressToDataUri(bmp, target, quality);
        } catch (Throwable t) {
            LogUtil.w(TAG, "encodeToDataUri failed: %s", t.getMessage());
            return null;
        } finally {
            if (bmp != null) bmp.recycle();
        }
    }

    /** 缩放 → 铺白底 → JPEG → Base64。不回收 {@code src}，由调用方负责。 */
    private static String compressToDataUri(Bitmap src, int maxSide, int quality) {
        Bitmap scaled = null;
        Bitmap flat = null;
        try {
            scaled = scaleTo(src, maxSide);
            // 透明图直接压 JPEG 会把透明区变黑，模型看到的就是一块黑斑
            flat = scaled.hasAlpha() ? flattenOnWhite(scaled) : scaled;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            flat.compress(Bitmap.CompressFormat.JPEG, quality, bos);
            return "data:image/jpeg;base64,"
                    + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
        } catch (Throwable t) {
            LogUtil.w(TAG, "compressToDataUri failed: %s", t.getMessage());
            return null;
        } finally {
            if (flat != null && flat != scaled) flat.recycle();
            if (scaled != null && scaled != src) scaled.recycle();
        }
    }

    private static Bitmap flattenOnWhite(Bitmap src) {
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(Color.WHITE);
        c.drawBitmap(src, 0, 0, null);
        return out;
    }

    /** 下载整张图到内存；大小与 Content-Type 都按导入规则卡死。 */
    private static byte[] downloadBytes(String urlStr) throws Exception {
        URL url = new URL(urlStr == null ? "" : urlStr.trim());
        String protocol = url.getProtocol();
        if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
            throw new Exception("只支持 http/https 链接");
        }
        HttpURLConnection conn = openFollowingRedirects(url);
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            String type = conn.getContentType();
            if (type != null && !type.toLowerCase(Locale.US).startsWith("image/")) {
                throw new Exception("链接不是图片（" + type + "）");
            }
            InputStream in = conn.getInputStream();
            if (in == null) throw new Exception("无法读取图片");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try {
                byte[] buf = new byte[8192];
                int n;
                long total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_REMOTE_BYTES) {
                        throw new Exception("图片超过 "
                                + AttachmentText.formatBytes(MAX_REMOTE_BYTES));
                    }
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

    /** 采样倍数：保证采样后的最长边仍不小于目标，循环里再按需缩到目标。 */
    private static int sampleSize(int w, int h, int target) {
        int max = Math.max(w, h);
        int sample = 1;
        while (max / (sample * 2) >= target) sample *= 2;
        return sample;
    }

    private static Bitmap scaleTo(Bitmap src, int maxSide) {
        int w = src.getWidth();
        int h = src.getHeight();
        int max = Math.max(w, h);
        if (max <= maxSide) return src;
        float ratio = (float) maxSide / max;
        int nw = Math.max(1, Math.round(w * ratio));
        int nh = Math.max(1, Math.round(h * ratio));
        return Bitmap.createScaledBitmap(src, nw, nh, true);
    }
}
