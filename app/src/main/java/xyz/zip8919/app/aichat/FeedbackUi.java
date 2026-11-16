package xyz.zip8919.app.aichat;

import android.content.Context;
import android.graphics.Color;

/**
 * 反馈界面的小工具：把服务端给的状态色调（tone）换成当前外观下的文字颜色。
 *
 * <p>色值不写成资源：这四档是反馈状态专用的语义色，与主题体系的对话气泡、
 * 链接色无关，放进 colors.xml 反而会让主题编辑器把它们当成可改项列出来。
 */
final class FeedbackUi {

    private FeedbackUi() {
    }

    static int toneColor(Context ctx, String tone) {
        if ("ok".equals(tone)) return Color.parseColor("#12805C");
        if ("warn".equals(tone)) return Color.parseColor("#8A6116");
        if ("accent".equals(tone)) return ctx.getResources().getColor(R.color.primary);
        return ctx.getResources().getColor(R.color.text_secondary);
    }
}
