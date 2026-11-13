package xyz.zip8919.app.aichat;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 只暴露「相机输出文件」一个 Uri 的极小 ContentProvider。
 *
 * <p><b>为什么必须有它</b>：{@code ACTION_IMAGE_CAPTURE + EXTRA_OUTPUT} 若用
 * {@code file://} 指向 {@code getCacheDir()}，相机是另一个 uid，根本没有权限写 app
 * 私有目录。实际结果是文件不存在或 0 字节，于是每次都落到 {@code data} 里的
 * 160×120 预览 Bitmap，裁剪页放大后全是马赛克。换 {@code content://} + Intent
 * 临时写授权，相机才写得进原图。
 *
 * <p>只做一件事：把 {@code content://<pkg>.camera/<文件名>} 映射到
 * {@code cacheDir/camera/<文件名>}，并且<b>只允许写</b>——不给读，避免变成任意文件读取入口。
 */
public class CameraOutputProvider extends ContentProvider {

    private static final String AUTHORITY_SUFFIX = ".camera";
    private static final String DIR = "camera";

    /** 给相机的输出 Uri。文件名只允许单段，杜绝路径穿越。 */
    static Uri uriFor(String fileName) {
        return Uri.parse("content://" + BuildConfig.APPLICATION_ID
                + AUTHORITY_SUFFIX + "/" + fileName);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        if (getContext() == null) throw new FileNotFoundException("no context");
        String name = uri.getLastPathSegment();
        if (name == null || name.length() == 0
                || name.contains("/") || name.contains("..")) {
            throw new FileNotFoundException("bad name");
        }
        File dir = new File(getContext().getCacheDir(), DIR);
        if (!dir.exists() && !dir.mkdirs()) throw new FileNotFoundException("no dir");
        return new File(dir, name);
    }

    /**
     * 把 openFile 的 mode 字符串翻成 POSIX flag。
     *
     * <p><b>为什么不能无脑 TRUNCATE</b>：部分机型（三星/HTC 常见）的相机在写完
     * 照片后会以 {@code "rw"} 再打开一次补写 EXIF。旧实现不区分 mode、恒定带
     * {@code MODE_TRUNCATE}，于是刚写完的原图被截成 0 字节，界面只能回落到
     * {@code data} 里 160×120 的缩略图——正是这个 Provider 想解决的问题。
     *
     * <p>不用 {@code ParcelFileDescriptor.parseMode()}：它要求 API 19，而本项目
     * minSdk 18。
     */
    private static int parseMode(String mode) {
        if (mode == null) mode = "r";
        boolean read = mode.indexOf('r') >= 0;
        boolean write = mode.indexOf('w') >= 0;
        boolean append = mode.indexOf('a') >= 0;
        int flags;
        if (write || append) {
            flags = read ? ParcelFileDescriptor.MODE_READ_WRITE
                    : ParcelFileDescriptor.MODE_WRITE_ONLY;
            flags |= ParcelFileDescriptor.MODE_CREATE;
            if (append) {
                flags |= ParcelFileDescriptor.MODE_APPEND;
            } else if (mode.indexOf('t') >= 0 || !read) {
                // "w"/"wt" 按约定截断；"rw" 绝不截断，除非显式要求（"rwt"）
                flags |= ParcelFileDescriptor.MODE_TRUNCATE;
            }
        } else {
            flags = ParcelFileDescriptor.MODE_READ_ONLY;
        }
        return flags;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(resolve(uri), parseMode(mode));
    }

    @Override
    public String getType(Uri uri) {
        return "image/jpeg";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        // ContentProvider 契约要求返回非 null Cursor：部分相机在写之前会先 query
        // 取 DISPLAY_NAME/SIZE，拿到 null 会 NPE 或直接放弃保存（照片丢失）。
        String[] cols = (projection != null && projection.length > 0)
                ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        File f = null;
        try {
            f = resolve(uri);
        } catch (FileNotFoundException ignored) {
            // 文件还没建：DISPLAY_NAME 仍可给出，SIZE 报 0
        }
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) {
                row[i] = uri.getLastPathSegment();
            } else if (OpenableColumns.SIZE.equals(cols[i])) {
                row[i] = Long.valueOf(f != null && f.isFile() ? f.length() : 0L);
            } else {
                row[i] = null;
            }
        }
        c.addRow(row);
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
