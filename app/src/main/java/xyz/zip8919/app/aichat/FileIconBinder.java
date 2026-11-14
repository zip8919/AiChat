package xyz.zip8919.app.aichat;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.CheckedTextView;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 文件列表图标的统一入口：图片项显示真实缩略图，其余按扩展名显示 {@link FileIcons} 图标。
 *
 * <p>三条约束（4.4 词典笔上都是踩过的坑）：
 * <ul>
 *   <li><b>不解码原图</b>：先读 bounds 再按 inSampleSize 采样，配置用 RGB_565。
 *       一张 4000×3000 的相机图直接 decode 就是 48MB，两次就能把 66MB 堆打爆。</li>
 *   <li><b>单线程解码 + LruCache</b>：并发解码多张图等于同时持有多个大 Bitmap；
 *       缓存按字节算上限 1MB，滚动列表不会把内存吃光。</li>
 *   <li><b>用 tag 校验复用</b>：ListView 行会被复用，后台回来的 Bitmap 必须
 *       确认这一行仍然绑着同一个文件，否则会出现「A 文件的行显示 B 的图」。</li>
 * </ul>
 *
 * <p>所有 bind 方法都在主线程调用；解码在后台线程，回调 post 回主线程。
 */
final class FileIconBinder {

    private static final String TAG = "FileIconBinder";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** 单线程：同一时刻最多一张图在被解码，杜绝并发大图 OOM。 */
    private static final ExecutorService DECODER = Executors.newSingleThreadExecutor();

    /** 按字节计上限（1MB）。key 里带 mtime 与长度，文件被覆盖后不会命中旧图。 */
    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>(1024 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getRowBytes() * value.getHeight();
                }
            };

    private FileIconBinder() {
    }

    /** 绑到独立的 ImageView（已保存文件 / 占用前 10）。 */
    static void bind(final ImageView iv, final File f) {
        if (iv == null) return;
        if (f == null || !FileKinds.looksLikeImage(f.getName(), null)) {
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setImageDrawable(FileIcons.of(iv.getContext(), f == null ? "" : f.getName()));
            iv.setTag(R.id.file_icon_path_tag, null);
            return;
        }
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        load(f, new Sink() {
            public void tag(String key) { iv.setTag(R.id.file_icon_path_tag, key); }
            public String tag() { Object o = iv.getTag(R.id.file_icon_path_tag); return o instanceof String ? (String) o : null; }
            public void placeholder(Drawable d) { iv.setImageDrawable(d); }
            public void bitmap(Bitmap b) { iv.setImageBitmap(b); }
            public int sizePx() {
                return Math.round(FileIcons.SIZE_DP
                        * iv.getContext().getResources().getDisplayMetrics().density);
            }
        }, iv.getContext());
    }

    /** 绑到 CheckedTextView 的左侧 compound drawable（代码库列表）。 */
    static void bindCompound(final CheckedTextView tv, final File f) {
        if (tv == null) return;
        tv.setCompoundDrawablePadding(dp(tv, 8));
        if (f == null || !FileKinds.looksLikeImage(f.getName(), null)) {
            setCompound(tv, FileIcons.of(tv.getContext(), f == null ? "" : f.getName()), dp(tv, FileIcons.SIZE_DP));
            tv.setTag(R.id.file_icon_path_tag, null);
            return;
        }
        load(f, new Sink() {
            public void tag(String key) { tv.setTag(R.id.file_icon_path_tag, key); }
            public String tag() { Object o = tv.getTag(R.id.file_icon_path_tag); return o instanceof String ? (String) o : null; }
            public void placeholder(Drawable d) { setCompound(tv, d, dp(tv, FileIcons.SIZE_DP)); }
            public void bitmap(Bitmap b) {
                setCompound(tv, new BitmapDrawable(tv.getContext().getResources(), b),
                        dp(tv, FileIcons.SIZE_DP));
            }
            public int sizePx() { return dp(tv, FileIcons.SIZE_DP); }
        }, tv.getContext());
    }

    private static void setCompound(TextView tv, Drawable d, int size) {
        if (d != null) d.setBounds(0, 0, size, size);
        tv.setCompoundDrawables(d, null, null, null);
    }

    private interface Sink {
        void tag(String key);

        String tag();

        void placeholder(Drawable d);

        void bitmap(Bitmap b);

        int sizePx();
    }

    private static void load(final File f, final Sink sink, final android.content.Context ctx) {
        final String key = f.getAbsolutePath() + "|" + f.lastModified() + "|" + f.length();
        sink.tag(key);
        Bitmap cached = CACHE.get(key);
        if (cached != null && !cached.isRecycled()) {
            sink.bitmap(cached);
            return;
        }
        // 先给占位图标：解码期间这一行不能是空白，也不能留着上一行的旧图
        Drawable placeholder = FileIcons.of(ctx, f.getName());
        placeholder.setBounds(0, 0, sink.sizePx(), sink.sizePx());
        sink.placeholder(placeholder);
        final int target = sink.sizePx() * 2;   // 2 倍显示尺寸，缩略图不会糊
        DECODER.execute(new Runnable() {
            public void run() {
                final Bitmap bmp = decode(f.getAbsolutePath(), target);
                if (bmp != null) CACHE.put(key, bmp);
                MAIN.post(new Runnable() {
                    public void run() {
                        if (!key.equals(sink.tag())) return;   // 行已被复用，丢弃
                        if (bmp != null && !bmp.isRecycled()) sink.bitmap(bmp);
                    }
                });
            }
        });
    }

    /** 采样解码；任何失败都返回 null，调用方保留占位图标。 */
    private static Bitmap decode(String path, int target) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            while (bounds.outWidth / sample > target || bounds.outHeight / sample > target) {
                sample *= 2;
                if (sample > 64) break;
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(path, o);
        } catch (OutOfMemoryError e) {
            LogUtil.w(TAG, "decode OOM: %s", path);
            CACHE.evictAll();
            return null;
        } catch (Throwable t) {
            LogUtil.w(TAG, "decode failed: %s : %s", path, t.getMessage());
            return null;
        }
    }

    private static int dp(TextView tv, int v) {
        return Math.round(v * tv.getContext().getResources().getDisplayMetrics().density);
    }
}
