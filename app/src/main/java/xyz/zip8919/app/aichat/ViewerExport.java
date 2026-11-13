package xyz.zip8919.app.aichat;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Picture;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Base64;
import android.view.View;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 查看器内容导出为 PNG / JPEG，落到 {@code exports/}（小工具管理的目录）。
 *
 * <p>三条路径，按可用性依次降级：
 * <ol>
 *   <li><b>原图直出</b>：图片查看器若持有本地可读源（{@code data:} / {@code file:}），
 *       同格式直接写原始字节——零背景、保原分辨率、无二次压缩；跨格式才解码重压。
 *       {@code http(s)} 源不在这里下载（防盗链/超时/流量都不可控），自动落到下一条。</li>
 *   <li><b>截图 + 边框剥离</b>：截当前可见区域后，只从四条边向内剥掉「连续纯背景」
 *       的行/列。表格、代码、文档、SVG、公式全部走这条，不再带一圈背景。</li>
 *   <li><b>原样截图</b>：剥离判定异常（整图几乎全背景 / 面积变化过小）时保持原图，
 *       绝不输出空图。</li>
 * </ol>
 *
 * <p><b>线程模型</b>：{@code capturePicture()/draw()} 必须在主线程，其余（解码、剥离、
 * 压缩、写盘、预览解码）全部后台。压缩一张 1080×1920 的 PNG 是秒级操作，留在主线程
 * 就是「点完存住不动、连进度都画不出来」。
 */
@SuppressWarnings("deprecation")
final class ViewerExport {

    private static final String TAG = "ViewerExport";
    private static final int MAX_SIDE = 4096;
    private static final long MAX_PIXELS = 16L * 1024 * 1024;
    /** 背景判定容差：同色像素会有 1~3 的抗锯齿抖动，12 足够稳。 */
    private static final int BG_TOL = 12;
    /** 裁完保留的边距，避免内容贴边。 */
    private static final int TRIM_PAD = 12;

    /** 图片查看器提供「当前页可直出的本地源」；不支持返回 null，走截图。 */
    interface DirectSource {
        String get();
    }

    private ViewerExport() {
    }

    /** 弹格式选择后导出。{@code direct} 在点击时求值，保证翻页后拿到当前图。 */
    static void choose(final Activity a, final WebView wv, final int bg,
                       final String tag, final DirectSource direct) {
        if (wv == null) {
            showFailure(a, "预览尚未就绪，请等内容显示后再导出。");
            return;
        }
        AcDialog.builder(a)
                .setTitle("导出为图片 · 选择格式")
                .setItems(new String[]{"PNG（无损，体积大）", "JPEG（有损，体积小）"},
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int which) {
                                String src = null;
                                if (direct != null) {
                                    try { src = direct.get(); } catch (Throwable ignored) { }
                                }
                                doExport(a, wv, bg, tag, src, which == 1);
                            }
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    private static void doExport(final Activity a, final WebView wv, final int bg,
                                 final String tag, final String direct, final boolean jpeg) {
        final ProgressDialog pd = new ProgressDialog(a);
        pd.setMessage("正在准备…");
        pd.setCancelable(false);
        try {
            pd.show();
        } catch (Throwable e) {
            LogUtil.w(TAG, "progress show failed: %s", e.getMessage());
            return;
        }

        // 先把进度画出来，再做占主线程的截图；否则点完就是一段纯卡死。
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            public void run() {
                if (a.isFinishing() || a.isDestroyed()) {
                    dismissQuietly(pd);
                    return;
                }
                String src = direct;
                if (src != null && !isLocalReadable(src)) src = null;

                Bitmap shot = null;
                if (src == null) {
                    pd.setMessage("正在截图…");
                    shot = capture(wv, bg);
                    if (shot == null) {
                        dismissQuietly(pd);
                        showFailure(a, "截图失败：内容尚未渲染完成，或预览已关闭，请重试。");
                        return;
                    }
                }
                pd.setMessage("正在编码并保存…");

                final Bitmap captured = shot;
                final String source = src;
                new Thread(new Runnable() {
                    public void run() {
                        final Result r = exportInBackground(a, captured, source, bg, tag, jpeg);
                        a.runOnUiThread(new Runnable() {
                            public void run() {
                                dismissQuietly(pd);
                                if (a.isFinishing() || a.isDestroyed()) {
                                    if (r.thumb != null && !r.thumb.isRecycled()) r.thumb.recycle();
                                    return;
                                }
                                if (!r.ok) {
                                    showFailure(a, r.error);
                                    return;
                                }
                                showResult(a, r);
                            }
                        });
                    }
                }, "aichat-viewer-export").start();
            }
        }, 60);
    }

    private static Result exportInBackground(Activity a, Bitmap captured, String direct,
                                             int bg, String tag, boolean jpeg) {
        Result r = new Result();
        r.jpeg = jpeg;
        byte[] out = null;
        int w = 0, h = 0;
        boolean trimmed = false;
        try {
            if (direct != null) {
                byte[] raw = readDirect(direct);
                if (raw != null && raw.length > 0) {
                    String fmt = sniff(raw);
                    boolean same = (jpeg && "jpg".equals(fmt)) || (!jpeg && "png".equals(fmt));
                    if (same) {
                        // 同格式：原始字节直出，零损失
                        out = raw;
                        int[] wh = bounds(raw);
                        w = wh[0];
                        h = wh[1];
                    } else {
                        Bitmap bmp = decodeFull(raw);
                        if (bmp != null) {
                            Bitmap flat = jpeg ? flatten(bmp, bg) : bmp;
                            out = compress(flat, jpeg);
                            w = flat.getWidth();
                            h = flat.getHeight();
                            if (flat != bmp && !bmp.isRecycled()) bmp.recycle();
                            if (!flat.isRecycled()) flat.recycle();
                        }
                    }
                    if (out != null) r.direct = true;
                }
            }

            if (out == null) {
                if (captured == null) {
                    r.error = "截图失败：内容尚未渲染完成，请稍后重试。";
                    return r;
                }
                Bitmap bmp = trimBorder(captured, bg);
                trimmed = (bmp != captured);
                Bitmap flat = (jpeg && bmp.hasAlpha()) ? flatten(bmp, bg) : bmp;
                out = compress(flat, jpeg);
                w = flat.getWidth();
                h = flat.getHeight();
                if (flat != bmp && !bmp.isRecycled()) bmp.recycle();
                if (bmp != captured && !bmp.isRecycled()) bmp.recycle();
            }

            if (out == null || out.length == 0) {
                r.error = "图片编码失败，请换个格式重试。";
                return r;
            }

            r.name = "viewer_" + safe(tag) + "_" + stamp() + (jpeg ? ".jpg" : ".png");
            r.path = StorageManager.getInstance(a.getApplicationContext())
                    .saveExportBytes(r.name, out);
            if (r.path == null) {
                r.error = "保存失败：exports 目录不可写。\n请检查存储权限，或到「小工具」确认根目录。";
                return r;
            }
            r.data = out;
            r.width = w;
            r.height = h;
            r.trimmed = trimmed;
            r.thumb = decodePreview(out);
            r.ok = true;
            LogUtil.i(TAG, "export: %s bytes=%d %dx%d direct=%s trimmed=%s path=%s",
                    r.name, out.length, w, h, r.direct, trimmed, r.path);
            return r;
        } catch (OutOfMemoryError e) {
            r.error = "图片过大，内存不足，请缩小预览后重试。";
            return r;
        } catch (Throwable e) {
            LogUtil.w(TAG, "export failed: %s", e.getMessage());
            r.error = "导出失败：" + e.getMessage();
            return r;
        } finally {
            if (captured != null && !captured.isRecycled()) captured.recycle();
        }
    }

    /**
     * 只从四条边向内剥掉「连续全背景」的行/列，遇到含内容的行列立即停。
     *
     * <p>不用「全局最小包围盒」：内容内部若有大片与背景同色的区域，包围盒会把它
     * 当成边界，反而切进内容。逐边剥离只动外围，永远不会切进内容。
     *
     * @return 裁剪后的新图；不需要裁或判定异常时原样返回 {@code src}。
     */
    private static Bitmap trimBorder(Bitmap src, int bg) {
        int w = src.getWidth(), h = src.getHeight();
        if (w <= 2 || h <= 2) return src;

        int[] line = new int[Math.max(w, h)];
        int top = 0;
        while (top < h) {
            src.getPixels(line, 0, w, 0, top, w, 1);
            if (!allBg(line, w, bg)) break;
            top++;
        }
        int bottom = h - 1;
        while (bottom > top) {
            src.getPixels(line, 0, w, 0, bottom, w, 1);
            if (!allBg(line, w, bg)) break;
            bottom--;
        }
        int left = 0;
        while (left < w) {
            src.getPixels(line, 0, 1, left, 0, 1, h);
            if (!allBg(line, h, bg)) break;
            left++;
        }
        int right = w - 1;
        while (right > left) {
            src.getPixels(line, 0, 1, right, 0, 1, h);
            if (!allBg(line, h, bg)) break;
            right--;
        }

        // 整张全是背景（例如新版 WebView 截出空白）或剥过头：保持原图，绝不输出空图
        if (top > bottom || left > right) return src;

        int x = Math.max(0, left - TRIM_PAD);
        int y = Math.max(0, top - TRIM_PAD);
        int rx = Math.min(w, right + 1 + TRIM_PAD);
        int by = Math.min(h, bottom + 1 + TRIM_PAD);
        int nw = rx - x, nh = by - y;
        if (nw <= 0 || nh <= 0) return src;
        // 几乎没裁（<原图 2%）：别为几个像素新建一张图
        if ((long) nw * nh >= (long) w * h * 98 / 100) return src;
        try {
            return Bitmap.createBitmap(src, x, y, nw, nh);
        } catch (Throwable e) {
            LogUtil.v(TAG, "trimBorder create failed: %s", e.getMessage());
            return src;
        }
    }

    /** 该行/列是否全为背景色（RGB 三通道容差）。遇到第一个非背景像素立即返回。 */
    private static boolean allBg(int[] px, int len, int bg) {
        int br = (bg >> 16) & 0xFF, bgG = (bg >> 8) & 0xFF, bgB = bg & 0xFF;
        for (int i = 0; i < len; i++) {
            int p = px[i];
            if (Math.abs(((p >> 16) & 0xFF) - br) > BG_TOL
                    || Math.abs(((p >> 8) & 0xFF) - bgG) > BG_TOL
                    || Math.abs((p & 0xFF) - bgB) > BG_TOL) {
                return false;
            }
        }
        return true;
    }

    /** 本地可读的图片源：{@code data:image/...;base64} 或存在的 {@code file://}。 */
    private static boolean isLocalReadable(String src) {
        if (src == null) return false;
        if (src.startsWith("data:image/")) return true;
        if (src.startsWith("file://")) {
            try {
                File f = new File(Uri.parse(src).getPath());
                return f.exists() && f.length() > 0;
            } catch (Throwable ignored) {
                return false;
            }
        }
        return false;
    }

    /** 读原始字节；http(s) 与超限文件一律返回 null，让调用方回退截图。 */
    private static byte[] readDirect(String src) {
        if (src == null) return null;
        try {
            if (src.startsWith("data:")) {
                int comma = src.indexOf(',');
                if (comma < 0) return null;
                String meta = src.substring(5, comma);
                if (!meta.contains(";base64")) return null;
                return Base64.decode(src.substring(comma + 1), Base64.DEFAULT);
            }
            if (src.startsWith("file://")) {
                File f = new File(Uri.parse(src).getPath());
                long len = f.length();
                if (!f.exists() || len <= 0 || len > 32L * 1024 * 1024) return null;
                byte[] buf = new byte[(int) len];
                FileInputStream in = new FileInputStream(f);
                try {
                    int off = 0, n;
                    while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) {
                        off += n;
                    }
                    return off == buf.length ? buf : null;
                } finally {
                    try { in.close(); } catch (Exception ignored) { }
                }
            }
        } catch (Throwable e) {
            LogUtil.w(TAG, "readDirect failed: %s", e.getMessage());
        }
        return null;
    }

    /** 只看文件头判断格式，识别不出返回 null。门槛取 4 字节，够判 PNG/JPEG/GIF。 */
    private static String sniff(byte[] d) {
        if (d == null || d.length < 4) return null;
        if ((d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') return "png";
        if ((d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8) return "jpg";
        if (d[0] == 'G' && d[1] == 'I' && d[2] == 'F') return "gif";
        if (d.length >= 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
                && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') return "webp";
        return null;
    }

    private static int[] bounds(byte[] data) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        return new int[]{o.outWidth, o.outHeight};
    }

    /** 解码原图并限制到导出上限（超限才缩，正常图不动）。 */
    private static Bitmap decodeFull(byte[] data) {
        BitmapFactory.Options b = new BitmapFactory.Options();
        b.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, b);
        if (b.outWidth <= 0 || b.outHeight <= 0) return null;
        int sample = 1;
        while (Math.max(b.outWidth, b.outHeight) / (sample * 2) > MAX_SIDE) sample *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (bmp == null) return null;
        float s = fit(bmp.getWidth(), bmp.getHeight());
        if (s < 1f) {
            Bitmap scaled = Bitmap.createScaledBitmap(bmp,
                    Math.max(1, (int) (bmp.getWidth() * s)),
                    Math.max(1, (int) (bmp.getHeight() * s)), true);
            if (scaled != bmp) bmp.recycle();
            bmp = scaled;
        }
        return bmp;
    }

    /** JPEG 没有 alpha：先铺背景色，否则透明区会变黑。 */
    private static Bitmap flatten(Bitmap src, int bg) {
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(),
                Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(bg);
        c.drawBitmap(src, 0, 0, null);
        return out;
    }

    private static byte[] compress(Bitmap bmp, boolean jpeg) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        boolean ok = bmp.compress(jpeg ? Bitmap.CompressFormat.JPEG : Bitmap.CompressFormat.PNG,
                jpeg ? 92 : 100, bos);
        return (ok && bos.size() > 0) ? bos.toByteArray() : null;
    }

    private static Bitmap capture(WebView wv, int bg) {
        Bitmap full = captureFull(wv, bg);
        if (full != null) return full;
        return captureVisible(wv, bg);
    }

    /** 整页。新版 WebView 会返回空白 Picture，用 {@link #isBlank} 判掉后回退。 */
    @SuppressWarnings("deprecation")
    private static Bitmap captureFull(WebView wv, int bg) {
        try {
            Picture pic = wv.capturePicture();
            if (pic == null) return null;
            int pw = pic.getWidth(), ph = pic.getHeight();
            if (pw <= 0 || ph <= 0) return null;
            float scale = fit(pw, ph);
            int bw = Math.max(1, (int) (pw * scale));
            int bh = Math.max(1, (int) (ph * scale));
            Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.drawColor(bg);
            c.scale(scale, scale);
            pic.draw(c);
            if (isBlank(b, bg)) {
                b.recycle();
                return null;
            }
            return b;
        } catch (Throwable e) {
            LogUtil.v(TAG, "captureFull unavailable: %s", e.getMessage());
            return null;
        }
    }

    /** 可见区域。硬件加速下 View.draw 可能拿不到 WebView 内容，临时切软件层。 */
    private static Bitmap captureVisible(WebView wv, int bg) {
        int vw = wv.getWidth(), vh = wv.getHeight();
        if (vw <= 0 || vh <= 0) return null;
        int layer = wv.getLayerType();
        if (layer != View.LAYER_TYPE_SOFTWARE) {
            wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }
        try {
            // 与 captureFull 一致：超大可见区先按 fit 限边长/总像素，
            // 否则 createBitmap + trimBorder/PNG 编码在 4.4 上容易 OOM。
            // 常规分辨率 fit=1，输出与原来逐字节一致。
            float s = fit(vw, vh);
            int bw = Math.max(1, (int) (vw * s));
            int bh = Math.max(1, (int) (vh * s));
            Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.drawColor(bg);
            c.scale(s, s);
            wv.draw(c);
            return b;
        } catch (Throwable e) {
            LogUtil.w(TAG, "captureVisible failed: %s", e.getMessage());
            return null;
        } finally {
            if (layer != View.LAYER_TYPE_SOFTWARE) {
                wv.setLayerType(layer, null);
            }
        }
    }

    /** 限制边长与总像素，避免长表格把堆打爆。 */
    private static float fit(int w, int h) {
        float s = 1f;
        int mx = Math.max(w, h);
        if (mx > MAX_SIDE) s = (float) MAX_SIDE / mx;
        long px = (long) (w * s) * (long) (h * s);
        if (px > MAX_PIXELS) {
            s = (float) Math.sqrt((double) MAX_PIXELS / ((double) w * h));
        }
        return Math.max(0.01f, s);
    }

    /** 5×5 抽样：全等于背景色即视为空白截图。 */
    private static boolean isBlank(Bitmap b, int bg) {
        int w = b.getWidth(), h = b.getHeight();
        for (int iy = 0; iy < 5; iy++) {
            for (int ix = 0; ix < 5; ix++) {
                int x = Math.min(w - 1, (w - 1) * ix / 4);
                int y = Math.min(h - 1, (h - 1) * iy / 4);
                if (b.getPixel(x, y) != bg) return false;
            }
        }
        return true;
    }

    /**
     * 导出结果常驻对话框：把刚落盘的那份字节解码成缩略图直接显示，同时给出
     * 文件名、尺寸、体积、来源（原图直出 / 已裁背景 / 可见区域）与绝对路径。
     *
     * <p>「重命名」不走 Toast、也不要求先进小工具：在这里改完即时生效，文案原地刷新。
     */
    private static void showResult(final Activity a, final Result r) {
        if (a.isFinishing() || a.isDestroyed()) {
            if (r.thumb != null && !r.thumb.isRecycled()) r.thumb.recycle();
            return;
        }
        float density = a.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        final TextView info = new TextView(a);
        info.setText(buildInfo(r));
        info.setTextSize(12);
        info.setTextIsSelectable(true);
        box.addView(info, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final Bitmap thumb = r.thumb;
        if (thumb != null) {
            ImageView iv = new ImageView(a);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setBackgroundColor(0xFF202020);
            iv.setImageBitmap(thumb);
            box.addView(iv, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (int) (240 * density)));
        }

        // 动作按钮放内容区，不用 AlertDialog 的 negative/neutral 按钮位：
        // 三按钮栏在部分 ROM/主题上会挤掉第三个，实测「重命名」根本不显示。
        LinearLayout actions = new LinearLayout(a);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        int gap = (int) (8 * density);
        actions.setPadding(0, gap, 0, 0);

        Button rename = new Button(a);
        rename.setText("重命名");
        rename.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                promptRename(a, r, info);
            }
        });
        LinearLayout.LayoutParams lpRename =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lpRename.setMargins(0, 0, gap / 2, 0);
        actions.addView(rename, lpRename);

        Button open = new Button(a);
        open.setText("在小工具里打开");
        open.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                a.startActivity(new Intent(a, ToolsActivity.class));
            }
        });
        LinearLayout.LayoutParams lpOpen =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lpOpen.setMargins(gap / 2, 0, 0, 0);
        actions.addView(open, lpOpen);

        box.addView(actions, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final AlertDialog dlg = AcDialog.builder(a)
                .setTitle("导出成功")
                .setView(box)
                .setPositiveButton("关闭", null)
                .create();
        if (thumb != null) {
            dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
                public void onDismiss(DialogInterface d) {
                    if (!thumb.isRecycled()) thumb.recycle();
                }
            });
        }
        try {
            dlg.show();
        } catch (Throwable e) {
            LogUtil.w(TAG, "showResult failed: %s", e.getMessage());
            if (thumb != null && !thumb.isRecycled()) thumb.recycle();
        }
    }

    /** 结果框正文。重命名后重新调一次即可原地刷新。 */
    private static String buildInfo(Result r) {
        String mode = r.direct ? "原图直出（无背景）"
                : (r.trimmed ? "已裁掉周围背景" : "当前可见区域");
        int kb = r.data == null ? 0 : r.data.length / 1024;
        return "已保存  " + r.name + "\n"
                + "尺寸  " + r.width + "×" + r.height + "   格式  " + (r.jpeg ? "JPEG" : "PNG")
                + "   体积  " + kb + " KB\n"
                + "来源  " + mode + "\n"
                + "位置  " + r.path + "\n\n"
                + (r.direct ? "" : "长内容在新版系统上只导出当前可见区域。");
    }

    /**
     * 就地重命名：预填当前文件名主干并全选，回车即替换，改完立刻刷新结果框。
     *
     * <p>扩展名由导出格式决定，不交给用户输入——避免出现 {@code .png} 里装 JPEG 字节。
     * 校验失败时用 {@code setError} 停在输入框上，不关框、不弹 Toast。
     */
    private static void promptRename(final Activity a, final Result r, final TextView info) {
        final String ext = r.jpeg ? ".jpg" : ".png";
        String base = r.name.endsWith(ext)
                ? r.name.substring(0, r.name.length() - ext.length()) : r.name;

        final EditText et = new EditText(a);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setText(base);
        et.setSelection(0, base.length());

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * a.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(et, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final AlertDialog dlg = AcDialog.builder(a)
                .setTitle("重命名")
                .setMessage("只填文件名即可，扩展名 " + ext + " 会自动保留。")
                .setView(box)
                .setPositiveButton("确定", null)
                .setNegativeButton("取消", null)
                .create();
        AcDialog.onShow(dlg, new DialogInterface.OnShowListener() {
            public void onShow(DialogInterface d) {
                dlg.getButton(AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener(new View.OnClickListener() {
                            public void onClick(View v) {
                                String name = normalizeName(et.getText().toString(), ext);
                                if (name == null) {
                                    et.setError("请填写文件名（不能只填扩展名或 . / ..）");
                                    return;
                                }
                                if (name.equals(r.name)) {
                                    dlg.dismiss();
                                    return;
                                }
                                StorageManager sm = StorageManager
                                        .getInstance(a.getApplicationContext());
                                // 仅大小写变化时 exportExists 会命中自己，不算重名
                                if (!name.equalsIgnoreCase(r.name) && sm.exportExists(name)) {
                                    et.setError("已存在同名文件，请换一个");
                                    return;
                                }
                                String path = sm.renameExport(r.name, name);
                                if (path == null) {
                                    et.setError("重命名失败：文件可能已被移动或目录不可写");
                                    return;
                                }
                                r.name = name;
                                r.path = path;
                                info.setText(buildInfo(r));
                                LogUtil.i(TAG, "renamed -> %s", path);
                                dlg.dismiss();
                            }
                        });
            }
        });
        try {
            dlg.show();
        } catch (Throwable e) {
            LogUtil.w(TAG, "promptRename failed: %s", e.getMessage());
        }
    }

    /**
     * 把用户输入规整成 exports 内的文件名：剥掉自己重复输入的图片扩展名，
     * 非法字符换成下划线，超长截断。返回 null 表示不可用。
     *
     * <p>只认图片扩展名再剥离，这样 {@code 方案 v1.2} 里的点不会被吃掉。
     */
    private static String normalizeName(String input, String ext) {
        if (input == null) return null;
        String s = input.trim();
        if (s.length() == 0) return null;
        String lower = s.toLowerCase(Locale.US);
        for (String e : new String[]{".png", ".jpg", ".jpeg", ".webp", ".gif"}) {
            if (lower.endsWith(e)) {
                s = s.substring(0, s.length() - e.length()).trim();
                break;
            }
        }
        if (s.length() == 0 || s.equals(".") || s.equals("..")) return null;
        s = s.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_");
        if (s.length() > 64) s = s.substring(0, 64);
        return s + ext;
    }

    private static void showFailure(Activity a, String msg) {
        if (a.isFinishing() || a.isDestroyed()) return;
        try {
            AcDialog.builder(a)
                    .setTitle("导出失败")
                    .setMessage(msg == null ? "未知错误" : msg)
                    .setPositiveButton("知道了", null)
                    .show();
        } catch (Throwable e) {
            LogUtil.w(TAG, "showFailure failed: %s", e.getMessage());
        }
    }

    private static void dismissQuietly(ProgressDialog pd) {
        try {
            if (pd != null && pd.isShowing()) pd.dismiss();
        } catch (Throwable ignored) { }
    }

    /** 采样解码预览图：只解到 720px 级别，避免把整张截图再原尺寸解一份进堆。 */
    private static Bitmap decodePreview(byte[] data) {
        if (data == null || data.length == 0) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            int mx = Math.max(bounds.outWidth, bounds.outHeight);
            while (mx / sample > 720) sample *= 2;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (OutOfMemoryError e) {
            return null;
        } catch (Throwable e) {
            LogUtil.v(TAG, "decodePreview failed: %s", e.getMessage());
            return null;
        }
    }

    private static String safe(String s) {
        return s == null ? "img" : s.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    private static final class Result {
        boolean ok;
        String error;
        String name;
        String path;
        byte[] data;
        Bitmap thumb;
        int width, height;
        boolean jpeg;
        boolean direct;
        boolean trimmed;
    }
}
