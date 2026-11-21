package xyz.zip8919.app.aichat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 余额查询结果的展示层。
 *
 * <p>三条硬要求直接决定了它的形态：
 * <ol>
 *   <li><b>成功和失败都能看原始 JSON</b>：余额接口没有统一协议，字段名对不上是常态。
 *       用户能自己点开原始返回，就知道是「解析规则没覆盖」还是「接口压根返回了错误」，
 *       不用来反馈里猜。</li>
 *   <li><b>失败时能看错误详情</b>：HTTP 状态码、请求地址、网络异常说明一起给出。
 *       只报「查询失败」等于什么都没说。</li>
 *   <li><b>结果可复制</b>：排查问题经常要把返回内容发出来，选中复制在 4.4 的长按
 *       菜单里很难操作，直接给一个复制按钮。</li>
 * </ol>
 *
 * <p>为什么不用 Activity + WebView：余额内容就几行字，为它开一个页面要处理主题、
 * 旋转、返回栈；对话框在设置页里用完即走，心智负担最小。
 */
final class BalanceDialog {

    private BalanceDialog() {
    }

    /**
     * @param title  对话框标题（提供商名）
     * @param res    解析结果（可含 error）
     * @param resp   原始响应（含 HTTP 状态码、请求地址、网络异常）
     */
    static void show(final Activity act, String title, final BalancePresets.Result res,
            final ApiClient.BalanceResponse resp) {
        if (act == null || act.isFinishing()) return;

        final StringBuilder plain = new StringBuilder();
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(act, 16);
        box.setPadding(pad, dp(act, 8), pad, 0);

        // ---- 主结果 ----
        if (res != null && res.ok) {
            for (String[] f : res.fields) {
                TextView row = new TextView(act);
                row.setTextSize(14);
                row.setTextColor(ThemeTokens.getSafe(act, ThemeTokens.AC_TEXT));
                row.setText(f[0] + "：" + f[1]);
                row.setPadding(0, dp(act, 5), 0, dp(act, 5));
                box.addView(row);
                plain.append(f[0]).append("：").append(f[1]).append('\n');
            }
        } else {
            TextView err = new TextView(act);
            err.setTextSize(14);
            err.setTextColor(ThemeTokens.getSafe(act, ThemeTokens.AC_DANGER));
            err.setPadding(0, dp(act, 6), 0, dp(act, 6));
            err.setText("查询失败\n" + (res != null && res.error != null ? res.error : "未知原因"));
            box.addView(err);
            plain.append("查询失败\n")
                 .append(res != null && res.error != null ? res.error : "未知原因").append('\n');
        }

        // ---- 说明（平台没有余额接口时的去处指引） ----
        if (res != null && res.note != null && res.note.length() > 0) {
            TextView note = new TextView(act);
            note.setTextSize(12);
            note.setTextColor(ThemeTokens.getSafe(act, ThemeTokens.AC_TEXT_SECONDARY));
            note.setPadding(0, dp(act, 8), 0, dp(act, 2));
            note.setText(res.note);
            box.addView(note);
            plain.append(res.note).append('\n');
        }

        // ---- 请求信息（失败时最有价值） ----
        if (resp != null) {
            TextView meta = new TextView(act);
            meta.setTextSize(11);
            meta.setTextColor(ThemeTokens.getSafe(act, ThemeTokens.AC_TEXT_SECONDARY));
            meta.setPadding(0, dp(act, 8), 0, 0);
            StringBuilder m = new StringBuilder();
            if (resp.url != null) m.append("请求地址：").append(resp.url);
            if (resp.code > 0) {
                if (m.length() > 0) m.append('\n');
                m.append("HTTP 状态：").append(resp.code);
            }
            if (resp.error != null) {
                if (m.length() > 0) m.append('\n');
                m.append("网络异常：").append(resp.error);
            }
            if (resp.code == 404) {
                // 404 不是「解析失败」，是「这个地址上没有余额接口」。
                // 不点明的话用户会一直以为自己填错了 key。
                if (m.length() > 0) m.append('\n');
                m.append("这个地址没有余额接口：确认 Base URL 是否填到 /v1，"
                        + "或该平台是否允许用 API Key 查余额");
            }
            meta.setText(m.toString());
            box.addView(meta);
            plain.append(m).append('\n');
        }

        final String rawText = rawOf(res, resp);

        ScrollView sv = new ScrollView(act);
        sv.addView(box);
        AlertDialog dlg = AcDialog.builder(act)
                .setTitle(title)
                .setView(sv)
                .setPositiveButton("关闭", null)
                .setNeutralButton("原始返回", null)
                .setNegativeButton("复制", null)
                .create();
        dlg.show();
        // 监听必须在 show 之后挂：写进 builder 的按钮回调会让对话框自动关闭，
        // 「原始返回」点了就没了，而它本该是展开、不是关掉
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showRaw(act, rawText);
            }
        });
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                ClipUtil.copy(act, plain.toString());
                AcToast.show(act, "已复制查询结果");
            }
        });
    }

    /** 原始返回：格式化后展示，只读、可选中、可滚动。 */
    private static void showRaw(Activity act, String rawText) {
        String formatted = pretty(rawText);
        final String body = formatted.length() == 0 ? "（没有返回内容）" : formatted;
        // 用 TextView 而不是 EditText：EditText 在这套主题下是单行输入框，
        // 换行会被 SingleLineTransformationMethod 折成空格 —— 排版好的 JSON
        // 于是在屏幕上又变回「一行，字段之间只有空白」。TextView 不折行，
        // 只读也不需要输入法，正好。
        TextView tv = new TextView(act);
        tv.setTextSize(11);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextColor(ThemeTokens.getSafe(act, ThemeTokens.AC_TEXT));
        tv.setTextIsSelectable(true);
        tv.setHorizontallyScrolling(false);
        tv.setText(body);

        ScrollView sv = new ScrollView(act);
        int pad = dp(act, 12);
        sv.setPadding(pad, pad, pad, 0);
        sv.addView(tv);

        AcDialog.builder(act)
                .setTitle("原始返回")
                .setView(sv)
                .setPositiveButton("关闭", null)
                .setNegativeButton("复制", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        ClipUtil.copy(act, body);
                        AcToast.show(act, "已复制原始返回");
                    }
                })
                .show();
    }

    /**
     * 把 JSON 排成两空格缩进的多行。
     *
     * <p>余额接口的返回经常是「一长串 800 字符、没有一个换行」，塞进对话框就是
     * 一行横向滚到天荒地老，字段名和值对不上号。缩进后每行一个字段，扫一眼就能
     * 找到 total_balance 在哪。
     *
     * <p>两级格式化：<b>先交给 org.json</b>（合法 JSON 的缩进最标准）；
     * <b>解析不了再自己按括号走一遍</b>。后者是为了半截响应、带前缀的响应
     * （{@code data: {...}}、{@code HTTP 500: {...}}）也能排出多行 —— 以前只在
     * org.json 成功时才排版，一次解析失败就退回原文，屏幕上就剩一整行。
     *
     * <p>完全不像 JSON 的内容原样返回：网关错误页、纯文本提示都要能看见，
     * 不能因为「格式化失败」就显示成空白或被拆得乱七八糟。
     */
    private static String pretty(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        if (t.length() == 0) return "";

        int start = jsonStart(t);
        if (start < 0) return t;
        String head = t.substring(0, start).trim();
        // 前缀像 HTML 错误页时不动它：页面里的 JS 花括号被当成 JSON 排版只会更乱
        if (head.length() > 70 || head.indexOf('<') >= 0) return t;
        String body = formatJson(t.substring(start));
        if (body.length() == 0) return t;
        return head.length() == 0 ? body : (head + "\n" + body);
    }

    /** 第一个 {@code {} / {@code [} 的位置；两者都找不到返回 -1。 */
    private static int jsonStart(String t) {
        int a = t.indexOf('{');
        int b = t.indexOf('[');
        if (a < 0) return b;
        if (b < 0) return a;
        return Math.min(a, b);
    }

    /** 尝试标准解析，失败则走容错排版；不像 JSON 时返回空串。 */
    private static String formatJson(String json) {
        try {
            if (json.charAt(0) == '{') return new org.json.JSONObject(json).toString(2);
            if (json.charAt(0) == '[') return new org.json.JSONArray(json).toString(2);
        } catch (Throwable ignored) {
            // 半截 / 前后缀多余 / 非法转义：往下走容错排版
        }
        if (!looksJson(json)) return "";
        return indent(json);
    }

    /** 粗略判断是不是 JSON 片段：看着像才排版，避免把普通文本拆散。 */
    private static boolean looksJson(String s) {
        if (s.length() < 2) return false;
        char c = s.charAt(0);
        if (c != '{' && c != '[') return false;
        return s.indexOf(':') >= 0 && s.indexOf('"') >= 0;
    }

    /**
     * 按括号与逗号换行的容错排版。
     *
     * <p>不要求 JSON 合法：括号没闭合、末尾被截断也能排出来，这对「响应太大被截断」
     * 和「网关回了一段不完整的 JSON」都有效。字符串内部的括号、逗号不参与缩进。
     */
    private static String indent(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 96);
        int depth = 0;
        boolean inStr = false;
        boolean justOpened = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                sb.append(c);
                if (c == '\\' && i + 1 < s.length()) {
                    sb.append(s.charAt(++i));
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') continue;

            if (justOpened) {
                justOpened = false;
                if (c == '}' || c == ']') {
                    // 空对象 / 空数组：{} 连在一起写，别拆成两行
                    depth = depth > 0 ? depth - 1 : 0;
                    sb.append(c);
                    continue;
                }
                newline(sb, depth);
            }

            if (c == '"') {
                inStr = true;
                sb.append(c);
            } else if (c == '{' || c == '[') {
                sb.append(c);
                depth++;
                justOpened = true;
            } else if (c == '}' || c == ']') {
                depth = depth > 0 ? depth - 1 : 0;
                newline(sb, depth);
                sb.append(c);
            } else if (c == ',') {
                sb.append(c);
                newline(sb, depth);
            } else if (c == ':') {
                sb.append(": ");
            } else {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    private static void newline(StringBuilder sb, int depth) {
        int n = sb.length();
        while (n > 0 && (sb.charAt(n - 1) == ' ' || sb.charAt(n - 1) == '\t')) {
            sb.setLength(--n);
        }
        sb.append('\n');
        for (int i = 0; i < depth; i++) sb.append("  ");
    }

    private static String rawOf(BalancePresets.Result res, ApiClient.BalanceResponse resp) {
        if (resp != null && resp.body != null && resp.body.length() > 0) return resp.body;
        if (res != null && res.raw != null) return res.raw;
        return "";
    }

    private static int dp(Activity act, int v) {
        return (int) (v * act.getResources().getDisplayMetrics().density + 0.5f);
    }
}
