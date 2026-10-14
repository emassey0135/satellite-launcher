package dev.emassey0135.communitylauncher;

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
 * Minimal read-only content provider that hands the cached user-guide file to the Book Reader, so we can share it
 * without an AndroidX FileProvider dependency. Serves exactly one file from the app's cache dir.
 */
public class GuideProvider extends ContentProvider {
    static final String FILE = "user_guide.docx";

    @Override public boolean onCreate() { return true; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = new File(getContext().getCacheDir(), FILE);
        if (!f.exists()) throw new FileNotFoundException(FILE);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String sel, String[] args, String order) {
        File f = new File(getContext().getCacheDir(), FILE);
        MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        c.addRow(new Object[]{FILE, f.length()});
        return c;
    }

    @Override public String getType(Uri uri) {
        return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String sel, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String sel, String[] args) { return 0; }
}
