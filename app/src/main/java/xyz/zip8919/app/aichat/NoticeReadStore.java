package xyz.zip8919.app.aichat;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * 公告已读记录。存在主偏好文件 aichat_prefs 里，和「首启已完成」等开关同一份。
 *
 * <p>只存公告编号，逗号分隔。退出应用不会清，卸载或清除数据才会丢。
 * 判断「有没有新公告」用「上次看到的最新编号」而不是逐条比对：
 * 后台一条公告改过标题或置顶后会排在前面，逐条比对会再弹一次，编号比对不会。
 */
final class NoticeReadStore {

    private static final String KEY_READ = "notice_read_ids";
    private static final String KEY_LAST = "notice_last_seen";
    /** 编号列表最多留 200 个，远超公告实际数量，防止无限增长。 */
    private static final int READ_MAX = 200;

    private NoticeReadStore() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences("aichat_prefs", Context.MODE_PRIVATE);
    }

    static boolean isRead(Context ctx, String id) {
        if (id == null || id.length() == 0) return false;
        String all = prefs(ctx).getString(KEY_READ, "");
        if (all.length() == 0) return false;
        String[] parts = all.split(",");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equals(id)) return true;
        }
        return false;
    }

    static void markRead(Context ctx, String id) {
        if (id == null || id.length() == 0) return;
        if (isRead(ctx, id)) return;
        String all = prefs(ctx).getString(KEY_READ, "");
        String next = all.length() == 0 ? id : all + "," + id;
        String[] parts = next.split(",");
        if (parts.length > READ_MAX) {
            StringBuilder sb = new StringBuilder();
            for (int i = parts.length - READ_MAX; i < parts.length; i++) {
                if (sb.length() > 0) sb.append(',');
                sb.append(parts[i]);
            }
            next = sb.toString();
        }
        prefs(ctx).edit().putString(KEY_READ, next).commit();
    }

    static String lastSeen(Context ctx) {
        return prefs(ctx).getString(KEY_LAST, "");
    }

    /** 记住这次看到的最新编号，下次拿它和最新一条比对，相同就不再打扰。 */
    static void rememberLast(Context ctx, String id) {
        if (id == null) id = "";
        prefs(ctx).edit().putString(KEY_LAST, id).commit();
    }

    /** 还没读过的公告，保持服务端顺序。 */
    static List<Notice> unread(Context ctx, List<Notice> list) {
        List<Notice> out = new ArrayList<Notice>();
        for (int i = 0; i < list.size(); i++) {
            if (!isRead(ctx, list.get(i).id)) out.add(list.get(i));
        }
        return out;
    }
}
