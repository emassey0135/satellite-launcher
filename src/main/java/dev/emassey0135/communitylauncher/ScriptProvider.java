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
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Serves the Orbit step aside script, which setup opens with Backtalk so that Backtalk asks the user to install it. It is
 * open to every app, because setup opens it through root rather than as this app; it serves only that one public
 * script.
 */
public final class ScriptProvider extends ContentProvider {
    private static final String TYPE = "application/javascript";

    @Override public boolean onCreate() { return true; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!SetupSteps.SCRIPT.equals(uri.getLastPathSegment())) throw new FileNotFoundException(uri.toString());
        File f = new File(getContext().getCacheDir(), SetupSteps.SCRIPT);
        try (InputStream in = getContext().getAssets().open(SetupSteps.SCRIPT); OutputStream out = new FileOutputStream(f)) {
            Streams.copy(in, out);
        } catch (Exception e) {
            throw new FileNotFoundException(SetupSteps.SCRIPT);
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String sel, String[] args, String order) {
        MatrixCursor c = new MatrixCursor(new String[] {OpenableColumns.DISPLAY_NAME});
        c.addRow(new Object[] {SetupSteps.SCRIPT});
        return c;
    }

    @Override public String getType(Uri uri) { return TYPE; }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String sel, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String sel, String[] args) { return 0; }
}
