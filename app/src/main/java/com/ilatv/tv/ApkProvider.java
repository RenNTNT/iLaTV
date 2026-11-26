package com.ilatv.tv;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 把手机推送过来的 APK 以 content:// 形式交给系统安装器（Android 7.0+ 不允许 file://）。
 * 只暴露 cache/push/update.apk 这一个只读文件，不 exported，靠 Intent 的临时授权读取。
 */
public final class ApkProvider extends ContentProvider {

    private static final String FILE = "update.apk";

    public static File apkFile(Context c) {
        return new File(new File(c.getCacheDir(), "push"), FILE);
    }

    public static Uri uriFor(Context c) {
        return Uri.parse("content://" + c.getPackageName() + ".apkprovider/" + FILE);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode != null && mode.indexOf('w') >= 0) throw new FileNotFoundException("read only");
        File f = apkFile(getContext());
        if (!f.isFile()) throw new FileNotFoundException("no pushed apk");
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /**
     * 系统安装器会通过 ContentResolver 查 _display_name / _size 再解析包。
     * 返回 null 会让不少电视 ROM 拿不到文件名与长度，直接报「解析软件包出现问题」。
     */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File f = apkFile(getContext());
        String[] cols = projection == null
                ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE, "_data"}
                : projection;
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            String col = cols[i];
            if (OpenableColumns.DISPLAY_NAME.equals(col)) row[i] = "update.apk";
            else if (OpenableColumns.SIZE.equals(col)) row[i] = f.isFile() ? f.length() : 0L;
            else if ("_data".equals(col)) row[i] = f.getAbsolutePath();
            else row[i] = null;
        }
        c.addRow(row);
        return c;
    }
    @Override public String getType(Uri uri) { return "application/vnd.android.package-archive"; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] selectionArgs) { return 0; }
}
