package xyz.zip8919.app.aichat;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * 会话时间注入。
 *
 * <p>需求是「每个会话第一次 AI 请求附带当前时间，后续不再附带」。实现上不是
 * 「每次请求现算」，而是**首请求时算一次、写进会话、之后原样复用**：
 *
 * <ul>
 *   <li>用户在会话中途改系统提示词，再发消息时时间不会跳成「现在」；</li>
 *   <li>同一轮里的多次工具请求用同一个时间，模型不会看到时间倒流；</li>
 *   <li>会话内 system 前缀稳定，不会因为一句时间把 prompt cache 反复打断。</li>
 * </ul>
 *
 * <p>文案刻意写成「本会话首次请求时间」而不是「当前时间」：老会话没有首发记录，
 * 只能用 {@code createdAt} 兜底，如果文案说「现在」，模型会把几周前的会话
 * 当成今天，问「今天几号」直接答错。
 */
final class ConvTime {

    private ConvTime() {
    }

    /**
     * 取本会话的固定时间标语，没有就补一个。
     *
     * @param conv            当前会话，null 时返回空串（不注入）
     * @param hasHistory      会话里是否已有消息（老会话判断用）
     */
    static String ensure(Conversation conv, boolean hasHistory) {
        if (conv == null) return "";
        if (conv.firstRequestStamp != null && conv.firstRequestStamp.length() > 0) {
            return conv.firstRequestStamp;
        }
        // 老会话（升级前就存在、已经有消息）：没有首发记录，用创建时间兜底。
        // 这比「升级后第一次发消息的时间」更接近真相——那句话代表的本来就是会话起点。
        long ts;
        if (hasHistory && conv.createdAt > 0) {
            ts = conv.createdAt;
        } else {
            ts = System.currentTimeMillis();
        }
        conv.firstRequestAt = ts;
        conv.firstRequestStamp = format(ts);
        LogUtil.i("ConvTime", "stamp assigned: conv=%s at=%d -> %s",
                conv.id, ts, conv.firstRequestStamp);
        return conv.firstRequestStamp;
    }

    /** 拼进 system prompt 末尾的那一行；没时间标时返回空串。 */
    static String promptSuffix(String stamp) {
        if (stamp == null || stamp.length() == 0) return "";
        return "\n\n本会话首次请求时间：" + stamp;
    }

    /**
     * 格式化成 {@code 2026-11-07 17:26 星期五（GMT+8）}。
     *
     * <p>手工拼而不是用 {@code SimpleDateFormat("EEEE")}：后者跟着系统语言走，
     * 英文系统上会输出 {@code Friday}，同一份提示词在不同机器上字面不同，
     * 既影响模型理解也不利于排查。
     */
    static String format(long ts) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(ts);
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        String datePart = df.format(new Date(ts));
        String[] week = {"日", "一", "二", "三", "四", "五", "六"};
        int dow = cal.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY;
        if (dow < 0 || dow > 6) dow = 0;
        int offsetMin = cal.getTimeZone().getOffset(ts) / 60000;
        String sign = offsetMin >= 0 ? "+" : "-";
        int abs = Math.abs(offsetMin);
        StringBuilder sb = new StringBuilder(datePart);
        sb.append(" 星期").append(week[dow]).append("（GMT")
          .append(sign);
        if (abs % 60 == 0) {
            sb.append(abs / 60);
        } else {
            sb.append(String.format(Locale.US, "%d:%02d", abs / 60, abs % 60));
        }
        sb.append("）");
        return sb.toString();
    }
}
