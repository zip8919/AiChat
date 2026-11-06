package xyz.zip8919.app.aichat;

import android.content.Context;

/**
 * 429 限流重试策略。做成不可变对象随 {@link StreamJob} 传给请求线程，
 * 避免「重试跑到一半用户去设置页改了参数」这种没法复现的时序问题。
 *
 * <p>只对 429 生效。5xx 不重试：那类错误服务端可能已经处理并计费，
 * 重发会重复扣 token；408/超时同理。
 */
final class RetryPolicy {

    final boolean enabled;
    /** 最多重试几次（不含首次请求）。 */
    final int maxAttempts;
    final long baseMs;
    final boolean backoff;
    final long maxWaitMs;

    RetryPolicy(boolean enabled, int maxAttempts, long baseMs, boolean backoff, long maxWaitMs) {
        this.enabled = enabled;
        this.maxAttempts = Math.max(0, maxAttempts);
        this.baseMs = Math.max(500L, baseMs);
        this.backoff = backoff;
        this.maxWaitMs = Math.max(this.baseMs, maxWaitMs);
    }

    static RetryPolicy from(Context ctx) {
        if (ctx == null) return new RetryPolicy(false, 0, 2000L, true, 30000L);
        return new RetryPolicy(
                SettingsActivity.isRetry429Enabled(ctx),
                SettingsActivity.getRetry429MaxAttempts(ctx),
                SettingsActivity.getRetry429BaseSeconds(ctx) * 1000L,
                SettingsActivity.isRetry429Backoff(ctx),
                SettingsActivity.getRetry429MaxWaitSeconds(ctx) * 1000L);
    }

    /**
     * 本次重试前该等多久。
     *
     * @param attempt            已经重试过几次（0 表示第一次重试）
     * @param retryAfterSeconds 服务端 {@code Retry-After} 声明的秒数，没有则传 -1
     */
    long delayFor(int attempt, int retryAfterSeconds) {
        if (retryAfterSeconds >= 0) {
            // 服务端说了算，但不低于 base（Retry-After: 0 时立刻重打只会再次被拒）
            long d = Math.max(baseMs, retryAfterSeconds * 1000L);
            return Math.min(d, maxWaitMs);
        }
        long d = baseMs;
        if (backoff) {
            for (int i = 0; i < attempt; i++) {
                d *= 2;
                if (d >= maxWaitMs) {
                    d = maxWaitMs;
                    break;
                }
            }
        }
        return Math.max(baseMs, Math.min(d, maxWaitMs));
    }
}
