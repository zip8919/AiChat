package xyz.zip8919.app.aichat;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 一条五段式占用条：段长 = 各类数据占比，剩余灰段 = 可用空间。
 *
 * <p>为什么自己画而不是叠五个 View：段宽要按字节精确分配，用 weight 只能按比例近似，
 * 而且相邻段之间要留 1dp 白缝、整体要圆角裁剪，五个 View 拼不出这个效果。
 *
 * <p>{@code clipPath} 在 API 18 的硬件加速层上行为不可靠（只有 intersect 生效），
 * 所以这里关掉硬件加速。这个 View 每屏只重绘一次，软件层的代价可以忽略。
 */
public class StorageBarView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF rect = new RectF();

    /** 五类体积，顺序与 {@link StorageReport} 的 CAT_* 一致。 */
    private long[] sizes;
    private long freeBytes;

    private int colorConv;
    private int colorAtt;
    private int colorExport;
    private int colorTemp;
    private int colorOther;
    private int colorTrack;
    private int colorTrackLow;

    public StorageBarView(Context context) {
        super(context);
        init(context);
    }

    public StorageBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        paint.setStyle(Paint.Style.FILL);
        colorConv = context.getResources().getColor(R.color.storage_conv);
        colorAtt = context.getResources().getColor(R.color.storage_att);
        colorExport = context.getResources().getColor(R.color.storage_export);
        colorTemp = context.getResources().getColor(R.color.storage_temp);
        colorOther = context.getResources().getColor(R.color.storage_other);
        colorTrack = context.getResources().getColor(R.color.storage_track);
        colorTrackLow = context.getResources().getColor(R.color.storage_track_low);
    }

    void setData(long[] sizes, long freeBytes) {
        this.sizes = sizes;
        this.freeBytes = freeBytes;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        long used = 0;
        if (sizes != null) {
            for (int i = 0; i < sizes.length; i++) used += sizes[i];
        }
        long total = used + Math.max(0, freeBytes);

        float radius = h / 2f;
        rect.set(0, 0, w, h);
        clip.reset();
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);

        // 余量不足 15% 时灰段转浅红：只改灰段，不动彩色分段，
        // 否则整条变色后反而看不出构成
        boolean low = total > 0 && freeBytes * 100L / total < 15L;
        paint.setColor(low ? colorTrackLow : colorTrack);
        canvas.drawRect(0, 0, w, h, paint);

        if (total <= 0 || sizes == null) {
            canvas.restore();
            return;
        }

        int[] colors = {colorConv, colorAtt, colorExport, colorTemp, colorOther};
        float x = 0;
        for (int i = 0; i < sizes.length && i < colors.length; i++) {
            if (sizes[i] <= 0) continue;
            float seg = w * sizes[i] / (float) total;
            paint.setColor(colors[i]);
            canvas.drawRect(x, 0, x + seg, h, paint);
            x += seg;
        }
        canvas.restore();
    }
}
