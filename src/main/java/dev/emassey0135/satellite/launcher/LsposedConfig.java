package dev.emassey0135.satellite.launcher;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

/**
 * LSPosed 1.9's list of modules and the apps each is used in (its {@code modules_config.db}), which only its manager
 * app otherwise changes. Setup edits a copy, since only root can reach the real one, and LSPosed reads it at the next
 * start.
 */
final class LsposedConfig {
    private LsposedConfig() { }

    /** Whether {@code pkg} is in the list, turned on, and used in every app in {@code scope}. */
    static boolean isEnabled(SQLiteDatabase db, String pkg, String[] scope) {
        Long mid = moduleId(db, pkg);
        if (mid == null) return false;
        try (Cursor c = db.rawQuery("SELECT enabled FROM modules WHERE mid=?", new String[] {mid.toString()})) {
            if (!c.moveToFirst() || c.getInt(0) != 1) return false;
        }
        for (String app : scope) {
            try (Cursor c = db.rawQuery("SELECT 1 FROM scope WHERE mid=? AND app_pkg_name=? AND user_id=0",
                    new String[] {mid.toString(), app})) {
                if (!c.moveToFirst()) return false;
            }
        }
        return true;
    }

    /** Whether {@code pkg} is in the list, turned on or not. */
    static boolean isListed(SQLiteDatabase db, String pkg) {
        return moduleId(db, pkg) != null;
    }

    /** Add {@code pkg} if LSPosed hasn't yet, turn it on, and use it in every app in {@code scope}. */
    static void enable(SQLiteDatabase db, String pkg, String apkPath, String[] scope) {
        db.beginTransaction();
        try {
            Long mid = moduleId(db, pkg);
            ContentValues module = new ContentValues();
            module.put("apk_path", apkPath);
            module.put("enabled", 1);
            if (mid == null) {
                module.put("module_pkg_name", pkg);
                mid = db.insertOrThrow("modules", null, module);
            } else {
                db.update("modules", module, "mid=?", new String[] {mid.toString()});
            }
            for (String app : scope) {
                ContentValues row = new ContentValues();
                row.put("mid", mid);
                row.put("app_pkg_name", app);
                row.put("user_id", 0);
                db.insertWithOnConflict("scope", null, row, SQLiteDatabase.CONFLICT_IGNORE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static Long moduleId(SQLiteDatabase db, String pkg) {
        try (Cursor c = db.rawQuery("SELECT mid FROM modules WHERE module_pkg_name=?", new String[] {pkg})) {
            return c.moveToFirst() ? c.getLong(0) : null;
        }
    }
}
