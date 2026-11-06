package xyz.zip8919.app.aichat;

/**
 * 图片输入的 token 估算——DeepSeek 官方口径。
 *
 * <p>数值与算法来自 DeepSeek「Token 用量计算」页的官方图片 Token 计算器
 * （{@code https://api-docs.deepseek.com/zh-cn/quick_start/token_usage}），
 * 缩放规则与「图像理解」的 Token 用量一节一致
 * （{@code https://api-docs.deepseek.com/zh-cn/guides/vision#token-usage}）：
 * <ol>
 *   <li>进模型前自动缩放：总像素小于约 544×544 的等比放大；更大的等比缩小到约 1300×1300；</li>
 *   <li>每张图 token 上限 1024——2000×2000 与 5000×5000 缩放后消耗相同；</li>
 *   <li>计费口径（v41 档）：patch 14px、3 倍下采样后按 patch 网格计数，
 *       {@code tokens = H×(W+1)+2}（H/W 为下采样后的 patch 数）。</li>
 * </ol>
 *
 * <p>为什么值得单独算：用户对「这张图贵不贵」没有直觉。一张图约 184~1024 token 到顶，
 * 不显示出来，用户会以为「贴张图不要钱」，直接账单出来。
 *
 * <p>这里只做整数/浮点运算，不碰 Bitmap，所以能在 JVM 单测里把官方计算器的数值钉死。
 * 官方页自己也注明「此处为估算值，实际以接口返回的 usage 为准」。
 */
final class ImageTokens {

    /** patch 边长（官方计算器 v41 参数）。 */
    static final int PATCH = 14;

    /** 下采样倍数：patch 先按 3×3 合并再计数。 */
    static final int DOWNSAMPLE = 3;

    /** 每张图 token 上限（官方文档：每张图片消耗的 token 数存在上限 1024）。 */
    static final int MAX_TOKENS = 1024;

    /** 最小像素数：544×544 = 295936。低于就等比放大到这个面积再计。 */
    static final int MIN_PIXELS = 544 * 544;

    /**
     * 发图编码的最长边预算（像素预算 = {@code MAX_SIDE²}）：近方形最大 patch 网格
     * 31×31（31×32+2 = 994 ≤ 1024，再大一格就超），31 网格 × 14px patch × 3 倍下采样 = 1302px。
     * 按这个面积编码，端点不再缩，1024 token 的网格正好吃满。
     */
    static final int MAX_SIDE = PATCH * DOWNSAMPLE * 31;

    private ImageTokens() {
    }

    /**
     * 发图编码侧的最长边：把 {@link #MAX_SIDE} 的像素预算等比折算到本图，不放大。
     *
     * <p>发这个尺寸，卡片上的估算值就是端点的实际计费值（差取整几个 token）；
     * 比预算大纯属浪费请求体，比预算小则模型少看细节、估值还和账单对不上。
     */
    static int apiSide(int width, int height) {
        if (width <= 0 || height <= 0) return 0;
        long budget = (long) MAX_SIDE * MAX_SIDE;
        long area = (long) width * height;
        if (area <= budget) return Math.max(width, height);
        double s = Math.sqrt((double) budget / (double) area);
        return Math.max(1, (int) Math.ceil(Math.max(width, height) * s));
    }

    /** 按 detail=original（不传 detail 字段）估算——本应用只用这一档。 */
    static int estimate(int width, int height) {
        if (width <= 0 || height <= 0) return minTokens();
        long w = width;
        long h = height;
        long[] r = resizeInner(w, h);
        // 官方 calc_resize：拿上一轮的 bestWidth/bestHeight 再算一遍，
        // 直到 patch 网格不动点收敛（最多 10 轮）。实测 1-2 轮就收敛。
        for (int i = 1; i < 10; i++) {
            long[] r2 = resizeInner(r[3], r[2]);
            if (r[0] == r2[0] && r[1] == r2[1] && r[2] == r2[2]
                    && r[3] == r2[3] && r[4] == r2[4]) {
                return (int) r[4];
            }
            r = r2;
        }
        return (int) r[4];
    }

    /**
     * 任何真实图片的最小消耗。544×544（最小像素）→ 13×13 patch → 13×14+2 = 184。
     * 未知尺寸按最小值保守估计：真实图片只会比这贵，不会更便宜。
     */
    static int minTokens() {
        return 184;
    }

    /**
     * 官方 calc_resize_inner（v41：无 maxWhRatio、无 pad 调整）。
     * 返回 {@code {nLlmH, nLlmW, bestHeight, bestWidth, tokens}}。
     */
    private static long[] resizeInner(long w, long h) {
        long area = w * h;
        if (area > 0 && area < MIN_PIXELS) {
            // 总像素不足 544×544：等比放大到目标面积
            double s = Math.sqrt((double) MIN_PIXELS / (double) area);
            w = (long) (w * s);   // 正数截断 = JS Math.trunc
            h = (long) (h * s);
        }
        long bw = ceilTo(w, PATCH);   // 四边 pad 到 patch 整数倍
        long bh = ceilTo(h, PATCH);
        return safeResize(h, w, bh, bw);
    }

    /** 官方 safe_resize：先按 pad 后尺寸计数，超预算再 solve_resize_ratio 缩网。 */
    private static long[] safeResize(long oh, long ow, long bh, long bw) {
        long nH = ceilDiv(bh / PATCH, DOWNSAMPLE);
        long nW = ceilDiv(bw / PATCH, DOWNSAMPLE);
        long tok = nH * (nW + 1) + 2;
        if (tok > MAX_TOKENS) {
            return solveResize(oh, ow, MAX_TOKENS);
        }
        return new long[]{nH, nW, bh, bw, tok};
    }

    /**
     * 官方 solve_resize_ratio：在 token 预算内求最大的 patch 网格，再按原图比例落到实际尺寸。
     * e=高、t=宽（放大后、pad 前）。
     */
    private static long[] solveResize(long e, long t, int budget) {
        double n = (double) e / (double) t;
        // 连续解：nLlmW ≤ i，nLlmH ≈ i×n，解 i² + i = (budget-2)/n
        double i = Math.sqrt((budget - 2) / n + 0.25) - 0.5;
        double a = i * n;
        long bw;
        long bh;
        if (i < 1) {
            // 太窄：宽压到 1 个 patch，高吃满预算
            long ew = 1;
            long th = (budget - 2) / (ew + 1);
            bw = ew * PATCH * DOWNSAMPLE;
            bh = th * PATCH * DOWNSAMPLE;
        } else if (a < 1) {
            // 太扁：高压到 1 个 patch，宽吃满预算
            long eh = 1;
            long tw = (budget - 2) / eh - 1;
            bw = tw * PATCH * DOWNSAMPLE;
            bh = eh * PATCH * DOWNSAMPLE;
        } else {
            long si = (long) i;
            long sn = (long) a;
            double l = (double) (si * PATCH * DOWNSAMPLE) / (double) t;
            double c = (double) (sn * PATCH * DOWNSAMPLE) / (double) e;
            double u = Math.min(l, c);
            bw = (long) (t * u / PATCH) * PATCH;
            bh = (long) (e * u / PATCH) * PATCH;
        }
        long nH = ceilDiv(bh / PATCH, DOWNSAMPLE);
        long nW = ceilDiv(bw / PATCH, DOWNSAMPLE);
        return new long[]{nH, nW, bh, bw, nH * (nW + 1) + 2};
    }

    /** v 向上取整到 unit 的整数倍。 */
    private static long ceilTo(long v, int unit) {
        return ((v + unit - 1) / unit) * unit;
    }

    private static long ceilDiv(long v, int d) {
        return (v + d - 1) / d;
    }

    /** 卡片摘要上的一句话说明，例如 {@code 1024×768}。 */
    static String describe(int width, int height) {
        if (width <= 0 || height <= 0) return "";
        return width + "×" + height;
    }
}
