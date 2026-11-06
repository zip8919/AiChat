package xyz.zip8919.app.aichat;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 裁剪选框。
 *
 * <p>只做两件事：整块拖动、四角缩放。没有旋转、没有比例锁定、没有九宫格以外的
 * 辅助线——「简单的裁剪」就该是这么点功能。功能越多，手指在 4 寸屏上越难精确操作。
 *
 * <p>坐标分两层：<b>控件坐标</b>（{@link #cropRect}，用于绘制与命中）与
 * <b>图片坐标</b>（{@link #computeCropRect()} 的返回值，用于真正裁像素）。
 * 混用这两层是这类控件最常见的 bug：在 1080p 屏上裁出来的图会偏到右下角。
 */
public class CropView extends View {

    /** 选框最小边长（dp）。再小手指就捏不住了。 */
    private static final float MIN_SIZE_DP = 48f;

    /** 手指离角多近算「抓住了角」（dp）。太小则移动选框时容易误抓手柄。 */
    private static final float HANDLE_SLOP_DP = 26f;

    private static final int MODE_NONE = 0;
    private static final int MODE_MOVE = 1;
    private static final int MODE_LT = 2;
    private static final int MODE_RT = 3;
    private static final int MODE_LB = 4;
    private static final int MODE_RB = 5;

    private Bitmap bitmap;
    /** 图片在控件里的显示矩形（等比缩放 + 居中）。 */
    private final RectF imageRect = new RectF();
    /** 选框（控件坐标）。 */
    private final RectF cropRect = new RectF();

    private int mode = MODE_NONE;
    private float lastX;
    private float lastY;
    private final float density;

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint maskPaint = new Paint();
    private final Paint borderPaint = new Paint();
    private final Paint gridPaint = new Paint();
    private final Paint handlePaint = new Paint();

    public CropView(Context context) {
        this(context, null);
    }

    public CropView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;

        maskPaint.setColor(0xAA000000);
        maskPaint.setStyle(Paint.Style.FILL);

        borderPaint.setColor(0xFFFFFFFF);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(Math.max(1f, 1.5f * density));

        gridPaint.setColor(0x55FFFFFF);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1f);

        handlePaint.setColor(0xFFFFFFFF);
        handlePaint.setStyle(Paint.Style.STROKE);
        handlePaint.setStrokeWidth(Math.max(2f, 3f * density));
        handlePaint.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setBitmap(Bitmap b) {
        this.bitmap = b;
        layoutImage();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layoutImage();
    }

    /** 等比放进控件并居中；选框默认内缩 8%，让用户一眼看出哪里可以拖。 */
    private void layoutImage() {
        imageRect.setEmpty();
        cropRect.setEmpty();
        if (bitmap == null || getWidth() <= 0 || getHeight() <= 0) return;

        float bw = bitmap.getWidth();
        float bh = bitmap.getHeight();
        float scale = Math.min(getWidth() / bw, getHeight() / bh);
        float w = bw * scale;
        float h = bh * scale;
        float left = (getWidth() - w) / 2f;
        float top = (getHeight() - h) / 2f;
        imageRect.set(left, top, left + w, top + h);

        float inset = Math.min(w, h) * 0.08f;
        cropRect.set(imageRect.left + inset, imageRect.top + inset,
                imageRect.right - inset, imageRect.bottom - inset);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (bitmap == null) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mode = pickMode(e.getX(), e.getY());
                lastX = e.getX();
                lastY = e.getY();
                return mode != MODE_NONE;
            case MotionEvent.ACTION_MOVE:
                if (mode == MODE_NONE) return false;
                apply(e.getX() - lastX, e.getY() - lastY);
                lastX = e.getX();
                lastY = e.getY();
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mode = MODE_NONE;
                return true;
            default:
                return false;
        }
    }

    private int pickMode(float x, float y) {
        float slop = HANDLE_SLOP_DP * density;
        if (near(x, y, cropRect.left, cropRect.top, slop)) return MODE_LT;
        if (near(x, y, cropRect.right, cropRect.top, slop)) return MODE_RT;
        if (near(x, y, cropRect.left, cropRect.bottom, slop)) return MODE_LB;
        if (near(x, y, cropRect.right, cropRect.bottom, slop)) return MODE_RB;
        if (cropRect.contains(x, y)) return MODE_MOVE;
        return MODE_NONE;
    }

    private static boolean near(float x, float y, float tx, float ty, float slop) {
        return Math.abs(x - tx) <= slop && Math.abs(y - ty) <= slop;
    }

    private void apply(float dx, float dy) {
        float min = MIN_SIZE_DP * density;
        switch (mode) {
            case MODE_MOVE:
                cropRect.offset(dx, dy);
                // 拖到边界就顶住，不要让选框跑到图片外面去
                if (cropRect.left < imageRect.left) {
                    cropRect.offset(imageRect.left - cropRect.left, 0);
                }
                if (cropRect.top < imageRect.top) {
                    cropRect.offset(0, imageRect.top - cropRect.top);
                }
                if (cropRect.right > imageRect.right) {
                    cropRect.offset(imageRect.right - cropRect.right, 0);
                }
                if (cropRect.bottom > imageRect.bottom) {
                    cropRect.offset(0, imageRect.bottom - cropRect.bottom);
                }
                break;
            case MODE_LT:
                cropRect.left = clamp(cropRect.left + dx, imageRect.left, cropRect.right - min);
                cropRect.top = clamp(cropRect.top + dy, imageRect.top, cropRect.bottom - min);
                break;
            case MODE_RT:
                cropRect.right = clamp(cropRect.right + dx, cropRect.left + min, imageRect.right);
                cropRect.top = clamp(cropRect.top + dy, imageRect.top, cropRect.bottom - min);
                break;
            case MODE_LB:
                cropRect.left = clamp(cropRect.left + dx, imageRect.left, cropRect.right - min);
                cropRect.bottom = clamp(cropRect.bottom + dy, cropRect.top + min, imageRect.bottom);
                break;
            case MODE_RB:
                cropRect.right = clamp(cropRect.right + dx, cropRect.left + min, imageRect.right);
                cropRect.bottom = clamp(cropRect.bottom + dy, cropRect.top + min, imageRect.bottom);
                break;
            default:
                break;
        }
    }

    private static float clamp(float v, float lo, float hi) {
        if (hi < lo) return lo;
        return v < lo ? lo : (v > hi ? hi : v);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap == null || imageRect.isEmpty()) return;

        canvas.drawBitmap(bitmap, null, imageRect, bitmapPaint);

        // 选框外压暗：四块矩形而不是一层半透明全屏 + 挖洞，
        // 因为 Canvas 的 clip 差集在 API 18 上要 API 26 才有的方法
        canvas.drawRect(imageRect.left, imageRect.top, imageRect.right, cropRect.top, maskPaint);
        canvas.drawRect(imageRect.left, cropRect.bottom, imageRect.right, imageRect.bottom, maskPaint);
        canvas.drawRect(imageRect.left, cropRect.top, cropRect.left, cropRect.bottom, maskPaint);
        canvas.drawRect(cropRect.right, cropRect.top, imageRect.right, cropRect.bottom, maskPaint);

        canvas.drawRect(cropRect, borderPaint);

        float w3 = cropRect.width() / 3f;
        float h3 = cropRect.height() / 3f;
        canvas.drawLine(cropRect.left + w3, cropRect.top,
                cropRect.left + w3, cropRect.bottom, gridPaint);
        canvas.drawLine(cropRect.left + w3 * 2, cropRect.top,
                cropRect.left + w3 * 2, cropRect.bottom, gridPaint);
        canvas.drawLine(cropRect.left, cropRect.top + h3,
                cropRect.right, cropRect.top + h3, gridPaint);
        canvas.drawLine(cropRect.left, cropRect.top + h3 * 2,
                cropRect.right, cropRect.top + h3 * 2, gridPaint);

        float arm = Math.max(12f, 18f * density);
        drawCorner(canvas, cropRect.left, cropRect.top, arm, arm);
        drawCorner(canvas, cropRect.right, cropRect.top, -arm, arm);
        drawCorner(canvas, cropRect.left, cropRect.bottom, arm, -arm);
        drawCorner(canvas, cropRect.right, cropRect.bottom, -arm, -arm);
    }

    private void drawCorner(Canvas c, float x, float y, float dx, float dy) {
        c.drawLine(x, y, x + dx, y, handlePaint);
        c.drawLine(x, y, x, y + dy, handlePaint);
    }

    /** 选框在图片像素坐标系里的位置；没加载好时返回空 Rect。 */
    public Rect computeCropRect() {
        if (bitmap == null || imageRect.width() <= 0) return new Rect();
        float scale = imageRect.width() / (float) bitmap.getWidth();
        if (scale <= 0) return new Rect();

        int x = Math.round((cropRect.left - imageRect.left) / scale);
        int y = Math.round((cropRect.top - imageRect.top) / scale);
        int w = Math.round(cropRect.width() / scale);
        int h = Math.round(cropRect.height() / scale);

        // 浮点误差可能让右下角越过原图边界，Bitmap.createBitmap 会直接抛
        x = Math.max(0, Math.min(x, bitmap.getWidth() - 1));
        y = Math.max(0, Math.min(y, bitmap.getHeight() - 1));
        w = Math.max(1, Math.min(w, bitmap.getWidth() - x));
        h = Math.max(1, Math.min(h, bitmap.getHeight() - y));
        return new Rect(x, y, x + w, y + h);
    }

    public boolean hasBitmap() {
        return bitmap != null;
    }
}
