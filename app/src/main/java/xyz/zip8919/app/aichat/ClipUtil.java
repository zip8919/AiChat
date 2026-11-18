package xyz.zip8919.app.aichat;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

/**
 * 复制纯文本到剪贴板。
 *
 * <p>公告链接在打不开浏览器时才需要复制，写成一个地方，
 * 弹窗页与关于页都复用，避免两处各写一份出现行为不一致。
 */
final class ClipUtil {

    private ClipUtil() {
    }

    static boolean copy(Context ctx, String text) {
        if (ctx == null || text == null || text.length() == 0) return false;
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return false;
            cm.setPrimaryClip(ClipData.newPlainText("aichat", text));
            return true;
        } catch (Throwable t) {
            LogUtil.w("ClipUtil", "copy failed: %s", t.getMessage());
            return false;
        }
    }
}
