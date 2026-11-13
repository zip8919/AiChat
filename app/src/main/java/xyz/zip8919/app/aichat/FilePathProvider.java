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
 * 把「已保存文件」和「图片附件」以 {@code content://} 暴露给外部应用，用于打开与分享。
 *
 * <p><b>为什么不能用 file://</b>：这两类文件都在 {@code Android/data/<包名>/files} 下，
 * 属于应用私有目录，其他 uid 的进程无权进入。哪怕本应用 targetSdk 只有 18、
 * 不会触发 {@code FileUriExposedException}，接收方也只会拿到一个打不开的路径——
 * 表现就是「点了打开，应用闪一下就回来」。
 *
 * <p><b>为什么不照搬 FileProvider</b>：项目里没有 androidx，自带 FileProvider 需要
 * 额外依赖与 xml 路径配置。这里只暴露两个固定目录，用两段式 Uri
 * （{@code content://<pkg>.files/exports/<name>}）就够，且天然杜绝
 * 「随便传一个 ../ 就能读私密文件」的路径穿越。
 */
public class FilePathProvider extends ContentProvider {

    private static final String AUTHORITY_SUFFIX = ".files";

    static final String SEG_EXPORTS = "exports";
    static final String SEG_ATTACHMENTS = "attachments";

    static Uri uriFor(File file) {
        String parent = file.getParentFile() == null ? "" : file.getParentFile().getName();
        String seg = SEG_EXPORTS;
        // 附件目录本身也叫 attachments；exports 目录名就是 exports。
        // 两个目录名恰好等于类别名，不需要额外的映射表。
        if (SEG_ATTACHMENTS.equals(parent)) seg = SEG_ATTACHMENTS;
        return Uri.parse("content://" + BuildConfig.APPLICATION_ID + AUTHORITY_SUFFIX
                + "/" + seg + "/" + Uri.encode(file.getName()));
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        if (getContext() == null) throw new FileNotFoundException("no context");
        if (uri.getPathSegments() == null || uri.getPathSegments().size() != 2) {
            throw new FileNotFoundException("bad uri");
        }
        String seg = uri.getPathSegments().get(0);
        String name = uri.getPathSegments().get(1);
        // 单段文件名，禁止路径穿越
        if (name == null || name.length() == 0 || name.contains("/") || name.contains("..")) {
            throw new FileNotFoundException("bad name");
        }
        StorageManager sm = StorageManager.getInstance(getContext());
        File dir;
        if (SEG_ATTACHMENTS.equals(seg)) {
            dir = sm.getAttachmentsDir();
        } else if (SEG_EXPORTS.equals(seg)) {
            dir = new File(sm.getExportsDir());
        } else {
            throw new FileNotFoundException("bad segment");
        }
        if (dir == null) throw new FileNotFoundException("no dir");
        File f = new File(dir, name);
        if (!f.isFile()) throw new FileNotFoundException(f.getAbsolutePath());
        return f;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        // 只读：外部应用没有理由写我们的文件
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        try {
            return FileActions.mimeOf(resolve(uri).getName());
        } catch (FileNotFoundException e) {
            return "application/octet-stream";
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        // ContentProvider 契约：query 必须返回非 null Cursor（结果可为空）或抛异常。
        // 本 Provider 只经 openFile 暴露文件、不提供行数据；返回空游标，避免
        // ContentResolver 调用方对 null 做 getCount()/moveToFirst() 时 NPE。
        String[] cols = projection != null ? projection : new String[]{
                OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        return new MatrixCursor(cols);
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
