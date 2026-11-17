package xyz.zip8919.app.aichat;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 公告。数据来自反馈中心后台，启动时拉一次。
 *
 * <p>已读记在本机：读过的那条编号存进 SharedPreferences，下次启动只弹最新的未读一条。
 * 只存编号不清理，公告条数很少，几十个短字符串不影响体积；这样即使中途下线再上线，
 * 用户也不会被同一条重复打扰。
 */
final class Notice {

    private static final String TAG = "Notice";
    private static final String PREF = "aichat_notices";
    private static final String KEY_READ = "read_ids";
    /** 编号列表最多留 200 个，远超公告实际数量，防止无限增长。 */
    private static final int READ_MAX = 200;

    final String id;
    final String title;
    final String body;
    final String link;
    final long createdAt;

    private Notice(String id, String title, String body, String link, long createdAt) {
        this.id = id;
        this.title = title;
        this.body = body;
        this.link = link;
        this.createdAt = createdAt;
    }

    static List<Notice> parseList(JSONObject data) {
        List<Notice> out = new ArrayList<Notice>();
        if (data == null) return out;
        JSONArray arr = data.optJSONArray("items");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id", "");
            String title = o.optString("title", "");
            if (id.length() == 0 || title.length() == 0) continue;
            out.add(new Notice(id, title, o.optString("body", ""),
                    o.optString("link", ""), o.optLong("createdAt", 0)));
        }
        return out;
    }

    /** 用当前版本号拉取展示中的公告；版本号随请求带上，服务端按版本范围过滤。 */
    static void load(Context context, FeedbackApi.Callback callback) {
        String v = SystemInfo.appVersion(context);
        String path = "/api/public/notices";
        if (v != null && v.length() > 0) path += "?appVersion=" + android.net.Uri.encode(v);
        FeedbackApi.getPath(path, callback);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 已读编号，逗号分隔存储。 */
    static boolean isRead(Context ctx, String id) {
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
        prefs(ctx).edit().putString(KEY_READ, next).apply();
    }

    /** 最新的未读一条；都读过了返回 null。 */
    static Notice firstUnread(Context ctx, List<Notice> list) {
        for (int i = 0; i < list.size(); i++) {
            if (!isRead(ctx, list.get(i).id)) return list.get(i);
        }
        return null;
    }
}
