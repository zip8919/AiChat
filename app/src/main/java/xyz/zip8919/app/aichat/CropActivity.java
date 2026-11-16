package xyz.zip8919.app.aichat;

import android.app.Activity;
import android.app.ProgressDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 简单的图片裁剪页。
 *
 * <p>为什么不用系统的 {@code com.android.camera.action.CROP}：那是非标准 Intent，
 * 各家 ROM 的实现差异极大——有的返回 200×200 的缩略图、有的直接把原图还回来、
 * 有的根本没有这个 Activity。用户点了「确定」却得到一张没裁的图，比没有这功能更糟。
 *
 * <p>这里自己画选框、自己裁像素：行为确定，输出尺寸可预期，也不用申请任何权限。
 */
public class CropActivity extends ThemedActivity {

    static final String EXTRA_SRC_PATH = "src_path";
    static final String EXTRA_OUT_PATH = "out_path";
    static final String EXTRA_NAME = "name";

    /**
     * 低于这个长边就认为「拿到的不是原图」：相机只回预览图、或相册给的是缩略图缓存。
     * 裁剪本身不会把清晰原图变糊，但源图就这点像素时再怎么显示都是马赛克，
     * 必须显式告诉用户，否则会被当成裁剪页的锅。
     */
    private static final int LOW_RES_PX = 1000;

    private CropView cropView;
    private Button doneBtn;
    private Button originalBtn;
    private Button rotateBtn;
    private String srcPath;
    private String displayName;
    /** 按屏幕尺寸采样解码后的位图；裁剪坐标系就建在它上面。 */
    private Bitmap source;
    /** onDestroy 的 recycle 与后台裁剪的 drawBitmap 互斥，防 "recycled bitmap"。 */
    private final Object cropLock = new Object();
    /** 累计旋转角度，只用于按钮文案；真正的坐标系始终是当前的 {@link #source}。 */
    private int rotationDegrees;
    private ProgressDialog progress;
    private final Handler handler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_crop);

        srcPath = getIntent().getStringExtra(EXTRA_SRC_PATH);
        displayName = getIntent().getStringExtra(EXTRA_NAME);
        if (srcPath == null || srcPath.length() == 0) {
            AcToast.show(this, Strings.get(R.string.cropactivity_001));
            finish();
            return;
        }

        cropView = (CropView) findViewById(R.id.crop_view);
        doneBtn = (Button) findViewById(R.id.crop_done);
        originalBtn = (Button) findViewById(R.id.crop_original);
        rotateBtn = (Button) findViewById(R.id.crop_rotate);

        TextView head = (TextView) findViewById(R.id.crop_head);
        if (displayName != null && displayName.length() > 0) head.setText(displayName);
        // 文件名太长会把标题挤到「完成」底下
        head.setSingleLine(true);
        head.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);

        findViewById(R.id.crop_cancel).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                setResult(RESULT_CANCELED);
                finish();
            }
        });

        originalBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // 不想裁就直接回原图：这条路径不重新编码，画质一点不损失
                returnPath(srcPath);
            }
        });

        doneBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                doCrop();
            }
        });

        rotateBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                rotateSource();
            }
        });

        doneBtn.setEnabled(false);
        originalBtn.setEnabled(false);
        rotateBtn.setEnabled(false);
        decodeSource();
    }

    /**
     * 按屏幕尺寸采样解码。
     *
     * <p>不按原图尺寸解：一张 4000×3000 的相册原图是 48 MB 的 ARGB，API 18 设备的堆
     * 常见只有 96~192 MB，解出来就是一次 OOM。裁剪输出的分辨率本来也不需要超过屏幕。
     */
    private void decodeSource() {
        new Thread(new Runnable() {
            public void run() {
                Bitmap bmp = null;
                String err = null;
                int srcW = 0, srcH = 0;
                try {
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(srcPath, bounds);
                    srcW = bounds.outWidth;
                    srcH = bounds.outHeight;
                    if (srcW <= 0 || srcH <= 0) throw new Exception(Strings.get(R.string.cropactivity_002));

                    int screen = Math.max(
                            getResources().getDisplayMetrics().widthPixels,
                            getResources().getDisplayMetrics().heightPixels);
                    int sample = 1;
                    while (Math.max(srcW, srcH) / (sample * 2) >= screen) sample *= 2;

                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inSampleSize = sample;
                    bmp = BitmapFactory.decodeFile(srcPath, o);
                    if (bmp == null) throw new Exception(Strings.get(R.string.cropactivity_003));
                    LogUtil.d("CropActivity", "decoded %dx%d sample=%d -> %dx%d",
                            srcW, srcH, sample, bmp.getWidth(), bmp.getHeight());
                } catch (Exception e) {
                    err = e.getMessage();
                } catch (OutOfMemoryError e) {
                    err = Strings.get(R.string.cropactivity_004);
                }
                final Bitmap decoded = bmp;
                final String error = err;
                final int sourceW = srcW, sourceH = srcH;
                handler.post(new Runnable() {
                    public void run() {
                        if (isFinishing() || isDestroyed()) {
                            if (decoded != null) decoded.recycle();
                            return;
                        }
                        if (decoded == null) {
                            AcToast.showLong(CropActivity.this, Strings.get(R.string.cropactivity_005,
                                    error == null ? Strings.get(R.string.cropactivity_006) : error));
                            finish();
                            return;
                        }
                        source = decoded;
                        cropView.setBitmap(source);
                        doneBtn.setEnabled(true);
                        originalBtn.setEnabled(true);
                        rotateBtn.setEnabled(true);
                        // 源图本身就小：明确告知，别让用户以为是裁剪页把图弄糊了
                        if (Math.max(sourceW, sourceH) < LOW_RES_PX) {
                            TextView head = (TextView) findViewById(R.id.crop_head);
                            if (head != null) {
                                head.setText(head.getText() + Strings.get(R.string.cropactivity_007));
                            }
                            AcToast.showLong(CropActivity.this, Strings.get(R.string.cropactivity_008, sourceW, sourceH));
                        }
                    }
                });
            }
        }, "aichat-crop-decode").start();
    }

    /**
     * 顺时针 90° 旋转当前位图。
     *
     * <p>关键点是<b>先换裁剪视图的位图、再回收旧图</b>：CropView 在 onDraw 里直接画
     * source，若先 recycle 再 setBitmap，中间一帧就会画出已回收的 Bitmap 而崩。
     * 旋转后的新位图成为裁剪坐标系，computeCropRect() 无需任何补偿。
     * 「原图」按钮返回的是磁盘路径，不受这里影响。
     */
    private void rotateSource() {
        if (source == null || source.isRecycled()) return;
        // 与 onDestroy 的 recycle(source) 共用一把锁，旋转期间不允许回收
        synchronized (cropLock) {
            if (source == null || source.isRecycled()) return;
            final Bitmap current = source;
            Matrix matrix = new Matrix();
            matrix.postRotate(90);
            Bitmap rotated = null;
            try {
                rotated = Bitmap.createBitmap(current, 0, 0,
                        current.getWidth(), current.getHeight(), matrix, true);
            } catch (OutOfMemoryError e) {
                AcToast.show(this, Strings.get(R.string.cropactivity_010));
                return;
            } catch (Exception e) {
                AcToast.show(this, Strings.get(R.string.cropactivity_011, e.getMessage()));
                return;
            }
            if (rotated == null) {
                AcToast.show(this, Strings.get(R.string.cropactivity_012));
                return;
            }
            source = rotated;
            rotationDegrees = (rotationDegrees + 90) % 360;
            cropView.setBitmap(source);
            rotateBtn.setText(Strings.get(R.string.cropactivity_013, rotationDegrees));
            // setBitmap 已切到新图，旧图此刻不再被 CropView 引用
            if (!current.isRecycled()) current.recycle();
        }
    }

    private void doCrop() {
        if (source == null) return;
        final Rect rect = cropView.computeCropRect();
        if (rect.width() <= 0 || rect.height() <= 0) {
            AcToast.show(this, Strings.get(R.string.cropactivity_014));
            return;
        }
        setBusy(true);
        new Thread(new Runnable() {
            public void run() {
                String outPath = null;
                String err = null;
                Bitmap cropped = null;
                try {
                    // onDestroy 会 recycle(source)：点「完成」立刻返回时两者会并发。
                    synchronized (cropLock) {
                        if (source == null || source.isRecycled()) {
                            throw new IllegalStateException("source bitmap released");
                        }
                        try {
                            cropped = Bitmap.createBitmap(source, rect.left, rect.top,
                                    rect.width(), rect.height());
                        } catch (IllegalArgumentException iae) {
                            // 某些设备上 createBitmap 对越界极其敏感，退一步走 Canvas 重绘
                            cropped = Bitmap.createBitmap(rect.width(), rect.height(),
                                    Bitmap.Config.ARGB_8888);
                            Canvas c = new Canvas(cropped);
                            c.drawBitmap(source, -rect.left, -rect.top, null);
                        }
                    }
                    File dir = new File(getCacheDir(), "camera");
                    if (!dir.exists() && !dir.mkdirs()) throw new Exception(Strings.get(R.string.cropactivity_015));
                    File out = new File(dir, "crop-" + System.currentTimeMillis() + ".jpg");
                    FileOutputStream fos = new FileOutputStream(out);
                    try {
                        cropped.compress(Bitmap.CompressFormat.JPEG, 92, fos);
                        fos.flush();
                    } finally {
                        try { fos.close(); } catch (Exception ignored) { }
                    }
                    if (out.length() == 0) throw new Exception(Strings.get(R.string.cropactivity_016));
                    outPath = out.getAbsolutePath();
                    LogUtil.i("CropActivity", "cropped %dx%d -> %s (%d bytes)",
                            rect.width(), rect.height(), out.getName(), out.length());
                } catch (Exception e) {
                    err = e.getMessage();
                } catch (OutOfMemoryError e) {
                    err = Strings.get(R.string.cropactivity_017);
                } finally {
                    // 选框拖到四边（等于不裁）时 createBitmap 会直接把 source 原样返回，
                    // 这里若无条件 recycle，就把 CropView 正在画的那张图回收了，
                    // 主线程下一次重绘直接 "trying to use a recycled bitmap" 崩溃。
                    if (cropped != null && cropped != source) cropped.recycle();
                }
                final String path = outPath;
                final String error = err;
                handler.post(new Runnable() {
                    public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        setBusy(false);
                        if (path == null) {
                            AcToast.showLong(CropActivity.this, Strings.get(R.string.cropactivity_018,
                                    error == null ? Strings.get(R.string.cropactivity_006) : error));
                            return;
                        }
                        returnPath(path);
                    }
                });
            }
        }, "aichat-crop-run").start();
    }

    private void returnPath(String path) {
        android.content.Intent data = new android.content.Intent();
        data.putExtra(EXTRA_OUT_PATH, path);
        data.putExtra(EXTRA_NAME, displayName);
        setResult(RESULT_OK, data);
        finish();
    }

    private void setBusy(boolean busy) {
        doneBtn.setEnabled(!busy);
        originalBtn.setEnabled(!busy);
        rotateBtn.setEnabled(!busy && source != null);
        if (busy) {
            progress = ProgressDialog.show(this, null, Strings.get(R.string.cropactivity_020), true, false);
        } else if (progress != null) {
            try { progress.dismiss(); } catch (Exception ignored) { }
            progress = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (progress != null) {
            try { progress.dismiss(); } catch (Exception ignored) { }
            progress = null;
        }
        // source 是裁剪的唯一来源，Activity 结束时必须还回去
        synchronized (cropLock) {
            // CropView 仍持有同一个 Bitmap：已排队的 traversal 若在这之后
            // 再绘一帧，就会抛 "trying to use a recycled bitmap"。
            if (cropView != null) cropView.setBitmap(null);
            if (source != null) {
                source.recycle();
                source = null;
            }
        }
    }
}
