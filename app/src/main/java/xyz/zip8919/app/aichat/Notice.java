package xyz.zip8919.app.aichat;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 公告。数据来自反馈中心后台，启动时拉一次。
 *
 * <p>已读记录由 NoticeReadStore 管理，本类只管公告内容本身。
 */
final class Notice {

    private static final String TAG = "Notice";

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
}
