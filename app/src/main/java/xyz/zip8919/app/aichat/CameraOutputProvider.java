package xyz.zip8919.app.aichat;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

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

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        // 相机只写：创建 + 截断 + 只写。读模式一律拒绝。
        return ParcelFileDescriptor.open(resolve(uri),
                ParcelFileDescriptor.MODE_CREATE
                        | ParcelFileDescriptor.MODE_WRITE_ONLY
                        | ParcelFileDescriptor.MODE_TRUNCATE);
    }

    @Override
    public String getType(Uri uri) {
        return "image/jpeg";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
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
