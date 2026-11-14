package xyz.zip8919.app.aichat;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

import java.util.Locale;

/**
 * 文件类型图标：按扩展名生成一个「圆角色块 + 扩展名缩写」的自绘 Drawable。
 *
 * <p>为什么自绘而不加一套 PNG：项目 minSdk 18，vector drawable 用不了；
 * 每个类型再切 5 套密度资源只会把 APK 撑大、给主题着色多添一条分支。
 * 这里只有一个类、零资源文件，且图标是固定识别色——不随主题漂，
 * 深色模式下也不会出现「色块和文字一起变黑」。
 *
 * <p>纯展示用：不读文件、不做 IO，可在 getView 里直接调用。
 */
final class FileIcons {

    private FileIcons() {
    }

    /** 图标边长（dp）。三个列表统一，视觉上能对齐。 */
    static final int SIZE_DP = 36;

    private static final int C_IMG = 0xFF7E57C2;     // 图片 紫
    private static final int C_CODE = 0xFF1E88E5;    // 代码/标记 蓝
    private static final int C_DOC = 0xFF43A047;     // 文档 绿
    private static final int C_DATA = 0xFF00897B;    // 表格/数据 青
    private static final int C_ARCHIVE = 0xFFFB8C00; // 压缩包/安装包 橙
    private static final int C_MEDIA = 0xFFE53935;   // 音视频 红
    private static final int C_FONT = 0xFF8D6E63;    // 字体 棕
    private static final int C_OTHER = 0xFF78909C;   // 其它 灰

    /** 按扩展名给一个可放进 ImageView / compound drawable 的图标。 */
    static Drawable of(Context ctx, String name) {
        int size = Math.round(SIZE_DP * ctx.getResources().getDisplayMetrics().density);
        return new Badge(labelOf(name), colorOf(name), size);
    }

    /** 图标上的字：直接用扩展名大写，最多 4 个字符；没有扩展名用「?」。 */
    static String labelOf(String name) {
        String ext = extension(name);
        if (ext.length() == 0) return "?";
        String up = ext.toUpperCase(Locale.US);
        return up.length() > 4 ? up.substring(0, 4) : up;
    }

    private static int colorOf(String name) {
        String e = extension(name);
        if (e.length() == 0) return C_OTHER;
        if (isImage(e)) return C_IMG;
        if (isCode(e)) return C_CODE;
        if (isDoc(e)) return C_DOC;
        if (isData(e)) return C_DATA;
        if (isArchive(e)) return C_ARCHIVE;
        if (isMedia(e)) return C_MEDIA;
        if (isFont(e)) return C_FONT;
        return C_OTHER;
    }

    private static boolean in(String e, String[] set) {
        for (int i = 0; i < set.length; i++) {
            if (set[i].equals(e)) return true;
        }
        return false;
    }

    private static final String[] IMAGE = {
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "ico", "tif", "tiff", "heic"
    };
    private static final String[] CODE = {
            "java", "kt", "kts", "py", "js", "mjs", "cjs", "ts", "tsx", "jsx", "json",
            "xml", "html", "htm", "xhtml", "css", "scss", "less", "c", "h", "cpp", "cc",
            "cxx", "hpp", "go", "rs", "sql", "sh", "bash", "zsh", "swift", "cs", "php",
            "rb", "gradle", "yml", "yaml", "toml", "ini", "conf", "bat", "ps1", "vue"
    };
    private static final String[] DOC = {
            "md", "markdown", "mdown", "mkd", "txt", "log", "pdf", "doc", "docx",
            "rtf", "odt", "tex", "rst", "epub"
    };
    private static final String[] DATA = {
            "csv", "tsv", "xls", "xlsx", "db", "sqlite", "parquet", "numbers"
    };
    private static final String[] ARCHIVE = {
            "zip", "rar", "7z", "gz", "tgz", "tar", "bz2", "xz", "apk", "jar", "war"
    };
    private static final String[] MEDIA = {
            "mp3", "wav", "ogg", "flac", "aac", "m4a", "mp4", "avi", "mkv", "mov",
            "webm", "wmv", "flv", "3gp"
    };
    private static final String[] FONT = {"ttf", "otf", "woff", "woff2"};

    private static boolean isImage(String e) { return in(e, IMAGE); }
    private static boolean isCode(String e) { return in(e, CODE); }
    private static boolean isDoc(String e) { return in(e, DOC); }
    private static boolean isData(String e) { return in(e, DATA); }
    private static boolean isArchive(String e) { return in(e, ARCHIVE); }
    private static boolean isMedia(String e) { return in(e, MEDIA); }
    private static boolean isFont(String e) { return in(e, FONT); }

    private static String extension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot >= name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.US);
    }

    /**
     * 自绘徽章。每次 bind 新建一个实例：Drawable 的 bounds 是实例状态，
     * 多个 ImageView 共用同一个实例会在滚动时互相改尺寸。
     * 对象本身只存 3 个字段 + 2 个 Paint，比 inflate 一个布局便宜得多。
     */
    private static final class Badge extends Drawable {
        private final String label;
        private final int color;
        private final int size;
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

        Badge(String label, int color, int size) {
            this.label = label;
            this.color = color;
            this.size = size;
            text.setColor(Color.WHITE);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(Typeface.DEFAULT_BOLD);
        }

        @Override
        public int getIntrinsicWidth() {
            return size;
        }

        @Override
        public int getIntrinsicHeight() {
            return size;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float w = b.width(), h = b.height();
            if (w <= 0 || h <= 0) return;
            bg.setColor(color);
            float radius = Math.min(w, h) * 0.20f;
            canvas.drawRoundRect(new RectF(b.left, b.top, b.right, b.bottom), radius, radius, bg);
            text.setTextSize(Math.min(w, h) * (label.length() > 3 ? 0.27f : 0.34f));
            Paint.FontMetrics fm = text.getFontMetrics();
            float baseY = b.top + h / 2f - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(label, b.left + w / 2f, baseY, text);
        }

        @Override
        public void setAlpha(int alpha) {
            bg.setAlpha(alpha);
            text.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            // 图标是识别色，不接受外部染色：主题着色遍历到它时也必须保持原色
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
