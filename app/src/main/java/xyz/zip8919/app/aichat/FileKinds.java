package xyz.zip8919.app.aichat;

import java.util.Locale;

/**
 * 按文件名判断「这是什么文件」——语言、能否预览、能否运行、是不是图片。
 *
 * <p>单独抽出来是为了能在 JVM 单测里跑：{@link AttachmentFiles} 依赖
 * BitmapFactory，一碰就只能在设备上验证；而这里的映射规则恰恰是最容易写错、
 * 也最容易被文件名坑到的部分（{@code .h} 是 C 头文件不是 Haskell）。
 *
 * <p>判断原则：**拿不准就当纯文本**。猜错语言的高亮比不高亮更难读。
 */
final class FileKinds {

    private FileKinds() {
    }

    /** 返回 CodeHighlighter 认识的语言名；空串表示纯文本。 */
    static String languageOf(String name) {
        String ext = extension(name);
        if (ext.length() == 0) return "";
        if (ext.equals("java")) return "java";
        if (ext.equals("kt") || ext.equals("kts")) return "kotlin";
        if (ext.equals("py")) return "python";
        if (ext.equals("js") || ext.equals("mjs")) return "js";
        if (ext.equals("ts")) return "ts";
        if (ext.equals("json")) return "json";
        if (ext.equals("xml")) return "xml";
        if (ext.equals("html") || ext.equals("htm") || ext.equals("xhtml")) return "html";
        if (ext.equals("svg")) return "svg";
        if (ext.equals("sh") || ext.equals("bash") || ext.equals("zsh")) return "bash";
        if (ext.equals("c") || ext.equals("h")) return "c";
        if (ext.equals("cpp") || ext.equals("cc") || ext.equals("cxx")
                || ext.equals("hpp")) return "cpp";
        if (ext.equals("sql")) return "sql";
        if (ext.equals("go")) return "go";
        if (ext.equals("rs")) return "rust";
        if (ext.equals("swift")) return "swift";
        if (ext.equals("cs")) return "cs";
        return "";
    }

    /** 文件查看器是否支持「预览」：只有能自己渲染的两种。 */
    static boolean isPreviewable(String lang) {
        return "html".equals(lang) || "svg".equals(lang);
    }

    /** 文件查看器是否支持「运行」。 */
    static boolean isRunnable(String lang) {
        return "js".equals(lang);
    }

    /** 扩展名判断：部分 ROM 给的 MIME 不可信，两边都看。 */
    static boolean looksLikeImage(String name, String mime) {
        if (mime != null && mime.toLowerCase(Locale.US).startsWith("image/")) return true;
        String ext = extension(name);
        return ext.equals("png") || ext.equals("jpg") || ext.equals("jpeg")
                || ext.equals("gif") || ext.equals("webp") || ext.equals("bmp");
    }

    private static String extension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot >= name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.US);
    }
}
