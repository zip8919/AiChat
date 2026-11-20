package xyz.zip8919.app.aichat;

import java.util.Calendar;
import java.util.Random;

/**
 * 空对话时屏幕中央的欢迎语。
 *
 * <p><b>为什么放在网页里而不是原生 View</b>：它要显示在消息区的正中央，而消息区就是
 * 一个 WebView。用原生 View 盖在上面，就得自己跟着键盘弹起、旋转、字体缩放去算位置；
 * 画进页面则天然居中，用户一开始聊天、页面一重灌，它自然就消失了，不需要额外的
 * 显示/隐藏状态机。
 *
 * <p><b>为什么要「多条随机」</b>：每次打开都看到同一句话会有「这软件是不是卡住了」的
 * 错觉。这里给一批候选，按时间段再挑一批（早上/深夜说的话不一样），
 * 每次进入空对话随机取一条。
 *
 * <p>取随机数用 {@link Random} 而不是 {@code Math.random()}：后者在 4.4 上
 * 首次调用会初始化一个新的随机源，每帧调用的场景下不划算；这里只在建页时取一次，
 * 差别不大，但语义更清楚。
 */
final class WelcomePhrases {

    private static final Random RND = new Random();

    /** 通用问候，任何时候都合适。 */
    private static final String[] ANY = {
            "今天想问点什么？有什么我可以帮你的吗？",
            "想聊点什么，或者需要我帮你查点什么？",
            "有什么可以帮你的？直接说就行。",
            "在的，随时可以开始。",
            "需要我帮你做点什么？",
    };

    /** 白天（8:00–18:00）。 */
    private static final String[] DAY = {
            "下午好，今天想做点什么？",
            "现在是干活的好时候，需要我帮什么？",
            "有什么问题想问？我在这儿。",
    };

    /** 夜间（18:00–次日 6:00）。 */
    private static final String[] NIGHT = {
            "晚上好，有什么想聊的？",
            "这么晚还在忙？需要我帮点什么？",
            "夜深了，有问题随时问我。",
    };

    /**
     * 小提示：告诉用户能做什么，比单纯的问候更有用。
     * 与问候语一起显示，位置在下方、字号更小。
     */
    private static final String[] HINTS = {
            "可以让我解释概念、翻译、改写，或者帮你查资料",
            "可以发图片让我看看，也可以让我写代码",
            "多个问题可以连着问，我会记住上下文",
    };

    private WelcomePhrases() {
    }

    /** 按当前时间段挑一条问候语。 */
    static String greeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String[] pool;
        if (hour >= 8 && hour < 18) {
            // 白天：通用池与白天池混着用，避免每天同一句
            pool = (RND.nextInt(3) == 0) ? DAY : ANY;
        } else {
            pool = (RND.nextInt(3) == 0) ? NIGHT : ANY;
        }
        return pool[RND.nextInt(pool.length)];
    }

    static String hint() {
        return HINTS[RND.nextInt(HINTS.length)];
    }

    /**
     * 生成欢迎语卡片 HTML（不含外壳，直接塞进消息区）。
     *
     * <p>用绝对定位占满整屏的消息区而不是「一段居中文本」：页面里有历史消息时不该
     * 出现这个元素（调用方保证只在空对话时插入），所以它不怕压住内容；
     * 而用 flex 居中在 Chromium 30 上对高度 100% 的父容器处理不一致，
     * 绝对定位 + 四边 0 反而更稳。
     *
     * <p>不用 CSS 变量与 flex（4.4 的内核都不支持），所有样式写死。
     * 文字颜色取中性深灰而不是主题色：页面主题由 {@link ThemeCssBuilder} 覆盖，
     * 这里若用主题色，深色主题下会出现「白底上的浅色字」看不出内容。
     */
    static String buildHtml() {
        String g = esc(greeting());
        String h = esc(hint());
        StringBuilder sb = new StringBuilder(512);
        sb.append("<div class=\"welcome-empty\"><div class=\"welcome-inner\">")
          .append("<div class=\"welcome-greet\">").append(g).append("</div>")
          .append("<div class=\"welcome-hint\">").append(h).append("</div>")
          .append("</div></div>");
        return sb.toString();
    }

    /** 样式：单独一段 CSS，由页面构建时追加。 */
    static String buildCss() {
        // 外层铺满整个可视区，内层用 table-cell 垂直居中。
        // 不用 top:50% + transform 居中：部分 4.4 ROM 的 WebView 会忽略 transform，
        // 卡片会被顶到屏幕上半截以外。
        //
        // 定位父级是 body：绝对定位的包含块取最近的已定位祖先，而 #msgs 没有定位
        // （它的高度由内容撑开，空对话时高度为 0）。这里给 body 补 position:relative
        // 与 100% 高度，卡片才是相对「窗口」居中，而不是相对某个零高度容器。
        return "html,body{height:100%;}"
                + "body{position:relative;}"
                + ".welcome-empty{position:absolute;left:0;right:0;top:0;bottom:0;"
                + "display:table;width:100%;height:100%;"
                + "padding:0 28px;-webkit-box-sizing:border-box;box-sizing:border-box;}"
                + ".welcome-inner{display:table-cell;vertical-align:middle;text-align:center;}"
                + ".welcome-greet{font-size:17px;line-height:1.5;color:#37474f;}"
                + ".welcome-hint{margin-top:10px;font-size:12px;line-height:1.6;color:#90a4ae;}";
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&') sb.append("&amp;");
            else if (c == '<') sb.append("&lt;");
            else if (c == '>') sb.append("&gt;");
            else if (c == '"') sb.append("&quot;");
            else sb.append(c);
        }
        return sb.toString();
    }
}
