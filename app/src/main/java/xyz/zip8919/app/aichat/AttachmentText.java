package xyz.zip8919.app.aichat;

import java.util.List;

/**
 * 附件的两套文本：给模型看的、给人看的。
 *
 * <p>同一份附件要呈现两次，而且必须不一致：
 * <ul>
 *   <li>{@link #toApi} 给模型：正文全量，用 {@code <附件 name="...">} 包裹；</li>
 *   <li>{@link #toDisplay} 给人：只有文件名和字数，气泡里不重复铺一遍正文。</li>
 * </ul>
 *
 * <p>为什么用标签而不是 markdown 代码围栏：文件内容里本来就常见 ```，
 * 围栏会被提前闭合，模型拿到的是半截代码加一堆散文本。
 */
final class AttachmentText {

    private AttachmentText() {
    }

    /** 一个待发送的文本附件。正文只在内存里，不落盘。 */
    static final class File {
        final String name;
        final String content;

        File(String name, String content) {
            this.name = name == null || name.length() == 0 ? "未命名.txt" : name;
            this.content = content == null ? "" : content;
        }

        int chars() {
            return content.length();
        }
    }

    /**
     * 实际发给模型的文本：用户原话 + 每个附件一段带标签的正文。
     * 没写字只丢了文件时补一句「请阅读以下附件」，否则请求体里会出现空的 user 内容。
     */
    static String toApi(String userText, List<File> files) {
        String base = userText == null ? "" : userText;
        if (files == null || files.isEmpty()) return base;
        StringBuilder sb = new StringBuilder(base);
        if (sb.length() == 0) sb.append("请阅读以下附件。");
        for (int i = 0; i < files.size(); i++) {
            File f = files.get(i);
            sb.append("\n\n<附件 name=\"").append(f.name).append("\">\n")
                    .append(f.content)
                    .append("\n</附件>");
        }
        return sb.toString();
    }

    /** 气泡里显示的文本：原话 + 一行附件摘要。 */
    static String toDisplay(String userText, List<File> files) {
        String base = userText == null ? "" : userText;
        if (files == null || files.isEmpty()) return base;
        StringBuilder sb = new StringBuilder(base);
        for (int i = 0; i < files.size(); i++) {
            File f = files.get(i);
            if (sb.length() > 0) sb.append("\n\n");
            sb.append("[附件] ").append(f.name).append("（").append(f.chars()).append(" 字）");
        }
        return sb.toString();
    }

    /** 长文件名会把整条附件栏撑爆，超出从中间省略。 */
    static String shortName(String name) {
        if (name == null || name.length() == 0) return "未命名";
        if (name.length() <= 18) return name;
        return name.substring(0, 9) + "…" + name.substring(name.length() - 6);
    }
}
