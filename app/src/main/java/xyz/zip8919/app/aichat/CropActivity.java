package xyz.zip8919.app.aichat;

import android.app.Activity;
import android.app.ProgressDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
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
public class CropActivity extends Activity {

    static final String EXTRA_SRC_PATH = "src_path";
    static final String EXTRA_OUT_PATH = "out_path";
    static final String EXTRA_NAME = "name";

    private CropView cropView;
    private Button doneBtn;
    private Button originalBtn;
    private String srcPath;
    private String displayName;
    /** 按屏幕尺寸采样解码后的位图；裁剪坐标系就建在它上面。 */
    private Bitmap source;
    private ProgressDialog progress;
    private final Handler handler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_crop);

        srcPath = getIntent().getStringExtra(EXTRA_SRC_PATH);
        displayName = getIntent().getStringExtra(EXTRA_NAME);
        if (srcPath == null || srcPath.length() == 0) {
            Toast.makeText(this, "没有可裁剪的图片", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        cropView = (CropView) findViewById(R.id.crop_view);
        doneBtn = (Button) findViewById(R.id.crop_done);
        originalBtn = (Button) findViewById(R.id.crop_original);

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

        doneBtn.setEnabled(false);
        originalBtn.setEnabled(false);
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
                try {
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(srcPath, bounds);
                    int w = bounds.outWidth;
                    int h = bounds.outHeight;
                    if (w <= 0 || h <= 0) throw new Exception("不是可识别的图片");

                    int screen = Math.max(
                            getResources().getDisplayMetrics().widthPixels,
                            getResources().getDisplayMetrics().heightPixels);
                    int sample = 1;
                    while (Math.max(w, h) / (sample * 2) >= screen) sample *= 2;

                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inSampleSize = sample;
                    bmp = BitmapFactory.decodeFile(srcPath, o);
                    if (bmp == null) throw new Exception("图片解码失败");
                    LogUtil.d("CropActivity", "decoded %dx%d sample=%d -> %dx%d",
                            w, h, sample, bmp.getWidth(), bmp.getHeight());
                } catch (Exception e) {
                    err = e.getMessage();
                } catch (OutOfMemoryError e) {
                    err = "图片太大，内存不足";
                }
                final Bitmap decoded = bmp;
                final String error = err;
                handler.post(new Runnable() {
                    public void run() {
                        if (isFinishing()) return;
                        if (decoded == null) {
                            Toast.makeText(CropActivity.this,
                                    "打开图片失败: " + (error == null ? "未知错误" : error),
                                    Toast.LENGTH_LONG).show();
                            finish();
                            return;
                        }
                        source = decoded;
                        cropView.setBitmap(source);
                        doneBtn.setEnabled(true);
                        originalBtn.setEnabled(true);
                    }
                });
            }
        }, "aichat-crop-decode").start();
    }

    private void doCrop() {
        if (source == null) return;
        final Rect rect = cropView.computeCropRect();
        if (rect.width() <= 0 || rect.height() <= 0) {
            Toast.makeText(this, "请先选择裁剪范围", Toast.LENGTH_SHORT).show();
            return;
        }
        setBusy(true);
        new Thread(new Runnable() {
            public void run() {
                String outPath = null;
                String err = null;
                Bitmap cropped = null;
                try {
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
                    File dir = new File(getCacheDir(), "camera");
                    if (!dir.exists() && !dir.mkdirs()) throw new Exception("无法创建缓存目录");
                    File out = new File(dir, "crop-" + System.currentTimeMillis() + ".jpg");
                    FileOutputStream fos = new FileOutputStream(out);
                    try {
                        cropped.compress(Bitmap.CompressFormat.JPEG, 92, fos);
                        fos.flush();
                    } finally {
                        try { fos.close(); } catch (Exception ignored) { }
                    }
                    if (out.length() == 0) throw new Exception("裁剪结果为空");
                    outPath = out.getAbsolutePath();
                    LogUtil.i("CropActivity", "cropped %dx%d -> %s (%d bytes)",
                            rect.width(), rect.height(), out.getName(), out.length());
                } catch (Exception e) {
                    err = e.getMessage();
                } catch (OutOfMemoryError e) {
                    err = "内存不足，请换一张小一点的图";
                } finally {
                    if (cropped != null) cropped.recycle();
                }
                final String path = outPath;
                final String error = err;
                handler.post(new Runnable() {
                    public void run() {
                        if (isFinishing()) return;
                        setBusy(false);
                        if (path == null) {
                            Toast.makeText(CropActivity.this,
                                    "裁剪失败: " + (error == null ? "未知错误" : error),
                                    Toast.LENGTH_LONG).show();
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
        if (busy) {
            progress = ProgressDialog.show(this, null, "正在裁剪…", true, false);
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
        if (source != null) {
            source.recycle();
            source = null;
        }
    }
}
