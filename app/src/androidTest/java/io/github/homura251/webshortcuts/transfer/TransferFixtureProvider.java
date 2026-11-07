package io.github.homura251.webshortcuts.transfer;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Test APK only. Uses platform APIs so its independent process needs no target APK libraries. */
public final class TransferFixtureProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    private File directory() {
        File directory = new File(getContext().getCacheDir(), "transfer-test-fixtures");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("Cannot prepare transfer fixtures");
        }
        return directory;
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        // Instrumentation runs under the target app UID; prepare test APK files in this process.
        if ("reset".equals(method)) {
            File[] files = directory().listFiles();
            if (files != null) for (File file : files) {
                if (!file.delete()) throw new IllegalStateException("Cannot reset transfer fixtures");
            }
        } else if ("seed".equals(method)) {
            String html = "<!DOCTYPE NETSCAPE-Bookmark-file-1><DL><DT><A HREF='https://example.test/?a=1&amp;b=2'>导入 &lt;标题&gt;</A></DL>";
            try (FileOutputStream output = new FileOutputStream(new File(directory(), "input.html"))) {
                output.write(html.getBytes(StandardCharsets.UTF_8));
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot write transfer fixture", failure);
            }
        } else {
            throw new UnsupportedOperationException(method);
        }
        return Bundle.EMPTY;
    }

    private File file(Uri uri) {
        String name = uri.getLastPathSegment();
        if (!"input.html".equals(name) && !"export.zip".equals(name) && !"export.html".equals(name)) {
            throw new IllegalArgumentException("Unsupported fixture URI");
        }
        return new File(directory(), name);
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if ("input.html".equals(uri.getLastPathSegment()) && !"r".equals(mode)) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.parseMode(mode));
    }

    @Override public String getType(Uri uri) {
        return "export.zip".equals(uri.getLastPathSegment()) ? "application/zip" : "text/html";
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File file = file(uri);
        String[] columns = projection != null ? projection : new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = file.getName();
            else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = file.length();
        }
        MatrixCursor cursor = new MatrixCursor(columns);
        cursor.addRow(row);
        return cursor;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        return !"input.html".equals(uri.getLastPathSegment()) && file(uri).delete() ? 1 : 0;
    }
}
