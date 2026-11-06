package xyz.zip8919.app.aichat;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;

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

        File dir = new File(ctx.getFilesDir(), "attachments");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("无法创建附件目录");
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
