package xyz.zip8919.app.aichat;

/**
 * 使用说明与免责声明的唯一文案来源。
 *
 * <p>首启弹窗和「关于 → 使用说明」必须是同一份文字：两处各写一份的话，
 * 改了功能忘了同步，用户看到的第一屏和后来查到的说明就对不上。
 */
final class HelpText {

    private HelpText() {
    }

    /** 功能说明。改完 UI 记得回来同步，这里是用户唯一会读的地方。 */
    static String usage() {
        return Strings.get(R.string.helptext_025);
    }

    /** 免责声明。 */
    static String disclaimer() {
        return Strings.get(R.string.helptext_026);
    }

    /** 首启弹窗用：说明 + 声明。 */
    static String full() {
        return usage() + "\n\n" + disclaimer();
    }
}
