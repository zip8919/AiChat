package xyz.zip8919.app.aichat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 附件：给模型看的正文、给人看的摘要。
 *
 * <p>同一份附件要呈现两次，而且必须不一致：
 * <ul>
 *   <li>{@link #toApi} 给模型：正文全量，用 {@code <附件 name="...">} 包裹；</li>
 *   <li>给人看的是气泡下方独立的附件卡片，只显示文件名、大小与 token 估算。</li>
 * </ul>
 *
 * <p>为什么用标签而不是 markdown 代码围栏：文件内容里本来就常见 ```，
 * 围栏会被提前闭合，模型拿到的是半截代码加一堆散文本。
 *
 * <p>为什么附件是 {@link Message#attachments} 而不是正文里的一行字：正文里混着
 * 文件元信息，编辑一次就丢一次，复制/搜索还得先剥一遍。见 {@link #migrateLegacy}。
 */
final class AttachmentText {

    private AttachmentText() {
    }

    /** 文本附件。 */
    static final String KIND_TEXT = "text";
    /**
     * 图片附件。
     *
     * <p>图片不进请求体：{@code chat/completions} 的 content 是纯文本，塞 Base64 会被
     * 端点当普通文字计费、或者直接 400。它在这里是「本地留档 + 查看」，
     * 给模型的那份只写一行说明（见 {@link #toApi}），不假装发送过像素。
     */
    static final String KIND_IMAGE = "image";

    /** 一个待发送的附件。正文只存在内存与 {@link Message} 里。 */
    static final class File {
        final String name;
        /** 文本附件正文；图片为空串。 */
        final String content;
        /** 原始文件字节数；0 表示来源未知（程序内部构造的），显示时退回字数。 */
        final long bytes;
        /** {@link #KIND_TEXT} 或 {@link #KIND_IMAGE}。 */
        final String kind;
        /** 图片在应用私有目录里的绝对路径（已拷贝，原 URI 的读权限活不过重启）。 */
        final String path;
        final int width;
        final int height;
        /** 图片缩略图（base64 data URI，约 3~8 KB），卡片上直接当背景图用；文本为空串。 */
        final String thumb;

        /**
         * token 估算缓存。-1 表示还没算过。
         *
         * <p>10 MB 的附件要遍历一千万个字符，而整页重渲染每轮都会问一次
         * 「这个附件多少 token」。不缓存的话，滚动加载历史时主线程会被反复烧穿。
         * 对象不可变，缓存永久有效。
         */
        private int tokensCache = -1;

        File(String name, String content) {
            this(name, content, 0L);
        }

        File(String name, String content, long bytes) {
            this(name, content, bytes, KIND_TEXT, "", 0, 0, "");
        }

        File(String name, String content, long bytes, String kind, String path,
                int width, int height, String thumb) {
            this.name = name == null || name.length() == 0 ? "未命名.txt" : name;
            this.content = content == null ? "" : content;
            this.bytes = Math.max(0L, bytes);
            this.kind = KIND_IMAGE.equals(kind) ? KIND_IMAGE : KIND_TEXT;
            this.path = path == null ? "" : path;
            this.width = Math.max(0, width);
            this.height = Math.max(0, height);
            this.thumb = thumb == null ? "" : thumb;
        }

        static File image(String name, String path, long bytes, int width, int height, String thumb) {
            return new File(name, "", bytes, KIND_IMAGE, path, width, height, thumb);
        }

        boolean isImage() {
            return KIND_IMAGE.equals(kind);
        }

        int chars() {
            return content.length();
        }

        /** 估算 token：与消息元信息、思考块标题共用同一套换算，避免三处数字打架。 */
        int tokens() {
            if (tokensCache < 0) tokensCache = MessageHtmlRenderer.estimateTokens(content);
            return tokensCache;
        }

        /** 图片的「大小 · 尺寸」摘要；文本附件返回空串。 */
        String imageSize() {
            StringBuilder sb = new StringBuilder();
            if (bytes > 0) sb.append(formatBytes(bytes));
            if (width > 0 && height > 0) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(width).append('×').append(height);
            }
            return sb.toString();
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
            if (f.isImage()) {
                // 只写「有这么一张图」，不写像素：content 是纯文本协议，
                // Base64 塞进去会被当正文计费，多数端点还会直接 400。
                // 与其让模型对着乱码猜，不如明确告诉它这一轮看不到图。
                sb.append("\n\n[图片附件] ").append(f.name);
                String meta = f.imageSize();
                if (meta.length() > 0) sb.append("（").append(meta).append("）");
                sb.append("\n（本次为纯文本请求，未包含图像内容）");
                continue;
            }
            sb.append("\n\n<附件 name=\"").append(f.name).append("\">\n")
                    .append(f.content)
                    .append("\n</附件>");
        }
        return sb.toString();
    }

    /**
     * 这条消息该以什么文本发给模型。
     *
     * <p>用户消息的正文由 {@link #content} + {@link Message#attachments} 现拼；
     * 老会话（1.8.2 及以前）没有 attachments 字段，正文整段存在 {@code apiText} 里，
     * 继续按老路取，读到就迁移（见 {@link #migrateLegacy}）。
     */
    static String apiTextOf(Message m) {
        if (m == null) return "";
        if (m.attachments != null && !m.attachments.isEmpty()) {
            return toApi(m.content, m.attachments);
        }
        if (m.apiText != null && m.apiText.trim().length() > 0) return m.apiText;
        return m.content == null ? "" : m.content;
    }

    /**
     * 一条消息的估算用量，只在端点没给 usage 时兜底。
     *
     * <p>分块累加而不是把附件拼成一个大字符串再数：10 MB 的附件复制一份就是 20 MB，
     * 而 {@link File#tokens()} 有缓存，整页重渲染不会重复遍历正文。
     */
    static int tokensOf(Message m) {
        if (m == null) return 0;
        int t = MessageHtmlRenderer.estimateTokens(m.content);
        if (m.attachments != null) {
            for (int i = 0; i < m.attachments.size(); i++) {
                File f = m.attachments.get(i);
                // 图片只发一行说明，按 24 个 token 估；文本按标签 16 个 token 补
                t += f.isImage() ? 24 : f.tokens() + 16;
            }
        }
        return t;
    }

    /** 附件条第二行：{@code 12.3 KB · ~3.2k tokens}；图片是 {@code 1.2 MB · 1200×800 · 仅本机}。 */
    static String sizeAndTokens(File f) {
        if (f.isImage()) {
            String s = f.imageSize();
            return s.length() == 0 ? "图片 · 仅本机" : s + " · 仅本机";
        }
        StringBuilder sb = new StringBuilder();
        if (f.bytes > 0) {
            sb.append(formatBytes(f.bytes)).append(" · ");
        } else {
            sb.append(f.chars()).append(" 字 · ");
        }
        sb.append('~').append(MessageHtmlRenderer.formatTokens(f.tokens())).append(" tokens");
        return sb.toString();
    }

    /** 复制/导出时贴在原话后面的附件清单，与气泡里的卡片文案一致。 */
    static String copySuffix(List<File> files) {
        if (files == null || files.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < files.size(); i++) {
            sb.append("\n[附件] ").append(files.get(i).name)
              .append("（").append(sizeAndTokens(files.get(i))).append("）");
        }
        return sb.toString();
    }

    static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /** 长文件名会把整条附件栏撑爆，超出从中间省略。 */
    static String shortName(String name) {
        if (name == null || name.length() == 0) return "未命名";
        if (name.length() <= 18) return name;
        return name.substring(0, 9) + "…" + name.substring(name.length() - 6);
    }

    // ---------------------------------------------------------------- 老会话迁移

    private static final String TAG_OPEN = "<附件 name=\"";
    private static final String TAG_NAME_END = "\">\n";
    private static final String TAG_CLOSE = "\n</附件>";
    /** 1.8.2 及以前 content 里那行摘要的前缀。 */
    private static final String LEGACY_SUMMARY_HEAD = "[附件] ";
    private static final String LEGACY_SUMMARY = "\n\n" + LEGACY_SUMMARY_HEAD;

    /**
     * 把 1.8.2 及以前的老会话迁移成结构化附件。
     *
     * <p>老数据有两个已知形态：
     * <ul>
     *   <li>正常：content = 「原话 + [附件] 摘要行」，apiText = 「原话 + &lt;附件&gt; 正文」；</li>
     *   <li>1.8.1 的 bug 数据：apiText 里根本没有正文，只有摘要行——这种数据没有迁移价值，
     *       原样保留，不要凭空造出空附件让人以为文件还在。</li>
     * </ul>
     *
     * <p>只有能从 apiText 里完整解析出至少一个附件块时才迁移；解析一半就放弃，
     * 宁可显示难看的老格式，也不要留下半真半假的附件。
     */
    static void migrateLegacy(Message m) {
        if (m == null || !m.isUser()) return;
        if (m.attachments != null && !m.attachments.isEmpty()) return;
        String content = m.content == null ? "" : m.content;
        String api = m.apiText == null ? "" : m.apiText;
        // 只发附件不写字的老消息，摘要在正文最前面（没有前导空行）；
        // 有原话时摘要前面是 "\n\n"
        boolean hasSummary = content.indexOf(LEGACY_SUMMARY) >= 0
                || content.startsWith(LEGACY_SUMMARY_HEAD);
        if (!hasSummary || api.indexOf(TAG_OPEN) < 0) return;

        List<File> parsed = parseApiText(api);
        if (parsed.isEmpty()) return;

        // 摘要行的条数必须与解析出的附件数一致，否则说明这行文案被用户改过，
        // 以哪边为准都是猜，直接放弃迁移。
        int declared = countLegacySummary(content);
        if (declared != parsed.size()) {
            LogUtil.w("AttachmentText", "legacy migrate skipped: summary=%d parsed=%d", declared, parsed.size());
            return;
        }

        // 摘要从正文里整段剥掉：前面有原话时从 "\n\n" 处切，纯附件消息直接从 0 切
        int cut = content.indexOf(LEGACY_SUMMARY);
        if (cut < 0) cut = 0;
        String original = content.substring(0, cut).trim();
        m.content = original;
        m.attachments.addAll(parsed);
        // 正文已经搬到 attachments，apiText 留着就是第二份副本；清掉，
        // 下次落盘时文件不会因为同一个附件存两遍而膨胀。
        m.apiText = "";
        LogUtil.i("AttachmentText", "legacy migrated: %d attachment(s), textLen=%d",
                parsed.size(), original.length());
    }

    private static int countLegacySummary(String content) {
        int n = 0;
        if (content.startsWith(LEGACY_SUMMARY_HEAD)) n++;
        int i = content.indexOf(LEGACY_SUMMARY);
        while (i >= 0) {
            n++;
            i = content.indexOf(LEGACY_SUMMARY, i + LEGACY_SUMMARY.length());
        }
        return n;
    }

    /** 从「原话 + &lt;附件 name="x"&gt;正文&lt;/附件&gt;」里拆出附件；格式不符返回空表。 */
    static List<File> parseApiText(String api) {
        List<File> out = new ArrayList<File>();
        if (api == null) return out;
        int i = 0;
        while (true) {
            int s = api.indexOf(TAG_OPEN, i);
            if (s < 0) break;
            int nameEnd = api.indexOf(TAG_NAME_END, s);
            if (nameEnd < 0) return new ArrayList<File>();
            String name = api.substring(s + TAG_OPEN.length(), nameEnd);
            int bodyStart = nameEnd + TAG_NAME_END.length();
            int bodyEnd = api.indexOf(TAG_CLOSE, bodyStart);
            if (bodyEnd < 0) return new ArrayList<File>();
            out.add(new File(name, api.substring(bodyStart, bodyEnd), 0L));
            i = bodyEnd + TAG_CLOSE.length();
        }
        return out;
    }
}
