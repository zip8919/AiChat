package xyz.zip8919.app.aichat;

/**
 * 图片输入的 token 估算。
 *
 * <p>公式来自 OpenAI 视觉模型的公开计费规则（DeepSeek 等兼容端点的
 * {@code image_url} 也是同一套切块口径）：
 * <ol>
 *   <li>{@code detail=low}：整张图固定 85 tokens，模型只看到一张缩略图；</li>
 *   <li>{@code detail=high}（默认）：先把长边缩到 2048 以内，再把短边缩到 768，
 *       然后按 512×512 切块，每块 170 tokens，另加 85 的基础开销。</li>
 * </ol>
 *
 * <p>为什么值得单独算：用户对「这张图贵不贵」没有直觉。一张 4000×3000 的手机原图
 * 按公式是 1105 tokens，跟一篇一千多字的中文文章差不多；不显示出来，
 * 用户会以为「贴张图不要钱」，直到账单出来。
 *
 * <p>这里只做整数/浮点运算，不碰 Bitmap，所以能在 JVM 单测里把官方示例的数值钉死。
 */
final class ImageTokens {

    /** 每张图的基础开销（low detail 也付这一份）。 */
    static final int BASE = 85;

    /** 每个 512×512 瓦片的开销。 */
    static final int PER_TILE = 170;

    static final int TILE = 512;

    /** 长边上限：超过就整体等比缩下来。 */
    static final int MAX_SIDE = 2048;

    /** 短边上限：长边缩完若短边仍超过它，再等比缩一次。 */
    static final int SHORT_SIDE = 768;

    private ImageTokens() {
    }

    /** 默认按 high detail 估算——多数端点不声明 detail 时就是这个口径。 */
    static int estimate(int width, int height) {
        return estimate(width, height, true);
    }

    /**
     * @param detailHigh {@code false} 表示 {@code detail:"low"}，固定 85
     */
    static int estimate(int width, int height, boolean detailHigh) {
        if (width <= 0 || height <= 0) return BASE;
        if (!detailHigh) return BASE;

        // 第一步：长边超过 2048 就缩
        double scale = 1.0d;
        int maxSide = Math.max(width, height);
        if (maxSide > MAX_SIDE) {
            scale = (double) MAX_SIDE / (double) maxSide;
        }
        double w = width * scale;
        double h = height * scale;

        // 第二步：短边仍超过 768 就再缩。注意必须先缩长边再判短边：
        // 反过来对 4096×1024 会算出 3072 宽的中间态，瓦片数直接翻倍。
        double shortSide = Math.min(w, h);
        if (shortSide > SHORT_SIDE) {
            double s2 = SHORT_SIDE / shortSide;
            w *= s2;
            h *= s2;
        }

        long tilesX = Math.max(1L, (long) Math.ceil(w / (double) TILE));
        long tilesY = Math.max(1L, (long) Math.ceil(h / (double) TILE));
        long tiles = Math.min(64L, tilesX * tilesY);   // 兜底，防止畸形尺寸算出天文数字
        return (int) (BASE + PER_TILE * tiles);
    }

    /** 卡片摘要上的一句话说明，例如 {@code 按 1024×768 估算}。 */
    static String describe(int width, int height) {
        if (width <= 0 || height <= 0) return "";
        return width + "×" + height;
    }
}
