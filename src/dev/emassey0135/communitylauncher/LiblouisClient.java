package dev.emassey0135.communitylauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.orbit.liblouisservice.ILiblouisServiceInterface;

import java.util.HashMap;
import java.util.Map;

/**
 * Binds Orbit's braille translator and turns typed braille cells into text, using the user's current input table
 * (read from Orbit settings via root). Each cell is a char {@code 0x8000 | dotBits}, matching Orbit's own usage.
 */
final class LiblouisClient {
    private static final String TAG = LauncherModel.TAG;
    private static final int MODE = 132; // the mode Orbit passes for braille-to-text of typed cells
    private static final String SETTINGS = "content://com.orbit.settings.provider/preferences/";
    private static final String DEFAULT_TABLE = "en-ueb-g2.ctb";

    private static final String DEFAULT_ECHO_TABLE = "en-ueb-g1.ctb";

    private final Context context;
    private volatile ILiblouisServiceInterface svc;
    private volatile String table = DEFAULT_TABLE;      // user's current table, for the committed name
    private volatile String echoTable = DEFAULT_ECHO_TABLE; // uncontracted, for per-letter echo

    LiblouisClient(Context context) { this.context = context.getApplicationContext(); }

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            svc = ILiblouisServiceInterface.Stub.asInterface(b);
        }
        @Override public void onServiceDisconnected(ComponentName n) { svc = null; }
    };

    /** Whether Orbit's braille translation service is installed (the Speak has it; the Player, with no braille keys, doesn't). */
    private boolean available() {
        try { context.getPackageManager().getPackageInfo("com.orbit.liblouisservice", 0); return true; }
        catch (android.content.pm.PackageManager.NameNotFoundException e) { return false; }
    }

    void bind() {
        if (!available()) return;
        Intent i = new Intent("REMOTE_LIB_LOUIS_SERVICE_BIND");
        i.setComponent(new ComponentName("com.orbit.liblouisservice", "com.orbit.liblouisservice.LiblouisService"));
        try { context.bindService(i, conn, Context.BIND_AUTO_CREATE); }
        catch (Exception e) { Log.w(TAG, "liblouis bind failed", e); }
        new Thread(this::resolveTable, "braille-table").start();
    }

    void unbind() { try { context.unbindService(conn); } catch (Exception ignored) { } }

    private static final String[] TABLE_KEYS = {
        "key_selection_mode",
        "key_contracted_braille_input_table",
        "key_uncontracted_braille_input_table",
        "key_computer_braille_input_table",
    };

    /** Re-read the input table (e.g. after the user changes it). Runs off the main thread. */
    void refresh() { if (available()) new Thread(this::resolveTable, "braille-table").start(); }

    /**
     * Read the user's current input table from Orbit settings. All keys are read in a single {@code su}
     * invocation (one superuser grant, not one per key), then the table is chosen from the selection mode.
     * Falls back to the defaults. Refreshed on a settings change, so it is not read repeatedly.
     */
    private void resolveTable() {
        StringBuilder cmd = new StringBuilder();
        for (String k : TABLE_KEYS) {
            cmd.append("echo @@").append(k).append("; content query --uri ").append(SETTINGS).append(k).append("; ");
        }
        Map<String, String> values = parseMarked(RootQuery.run(cmd.toString()));

        String mode = values.get("key_selection_mode");
        String key = "key_contracted_braille_input_table";
        if (mode != null) {
            String m = mode.toLowerCase();
            if (m.contains("uncontract")) key = "key_uncontracted_braille_input_table";
            else if (m.contains("computer")) key = "key_computer_braille_input_table";
        }
        String t = values.get(key);
        if (t != null && !t.isEmpty()) table = t;
        String e = values.get("key_uncontracted_braille_input_table");
        if (e != null && !e.isEmpty()) echoTable = e;
        Log.d(TAG, "braille input table = " + table + ", echo table = " + echoTable);
    }

    /** Parse the output of the marked multi-query: each {@code @@key} line is followed by that key's row. */
    private static Map<String, String> parseMarked(String out) {
        Map<String, String> map = new HashMap<>();
        if (out == null) return map;
        for (String part : out.split("@@")) {
            int nl = part.indexOf('\n');
            if (nl < 0) continue;
            String key = part.substring(0, nl).trim();
            int i = part.indexOf("value=", nl);
            if (i < 0) continue;
            int end = part.indexOf(", type=", i);
            if (end < 0) end = part.indexOf('\n', i);
            if (end < 0) end = part.length();
            map.put(key, part.substring(i + 6, end).trim());
        }
        return map;
    }

    boolean isReady() { return svc != null; }

    /** Translate a sequence of dot-bit masks to text. Returns null if the translator is unavailable. */
    String translate(int[] cells, int count) {
        ILiblouisServiceInterface s = svc;
        if (s == null || count == 0) return count == 0 ? "" : null;
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) sb.append((char) (0x8000 | (cells[i] & 0xFF)));
        try {
            CharSequence out = s.translateBrailleToText(sb.toString(), MODE, table);
            return out == null ? null : out.toString();
        } catch (RemoteException e) {
            Log.w(TAG, "translate failed", e);
            return null;
        }
    }

    /**
     * Text for a single typed cell, for echo. Tries the uncontracted table first so a letter reads as itself
     * (e.g. t, not the word-sign "that"); if that yields nothing, falls back to the contracted table so a
     * group-sign reads as its letters (e.g. the dots-3-4 cell reads "st"). Null if unavailable, "" if a pending sign.
     */
    String echo(int mask) {
        String uncontracted = translateCell(mask, echoTable);
        if (uncontracted == null) return null;
        if (!uncontracted.isEmpty()) return uncontracted;
        String contracted = translateCell(mask, table);
        return contracted == null ? "" : contracted;
    }

    /** Translate a list of cell masks in the user's current table at the given liblouis mode (388 live, 132 commit). */
    String translateContracted(java.util.List<Integer> cells, int mode) {
        ILiblouisServiceInterface s = svc;
        if (s == null || cells.isEmpty()) return cells.isEmpty() ? "" : null;
        StringBuilder sb = new StringBuilder(cells.size());
        for (int c : cells) sb.append((char) (0x8000 | (c & 0xFF)));
        try {
            CharSequence out = s.translateBrailleToText(sb.toString(), mode, table);
            return out == null ? null : out.toString();
        } catch (RemoteException e) {
            Log.w(TAG, "translateContracted failed", e);
            return null;
        }
    }

    /** A single cell in the uncontracted table: a plain letter reads as itself. "" for a contraction-only cell. */
    String echoUncontracted(int mask) {
        String s = translateCell(mask, echoTable);
        return s == null ? null : s;
    }

    private String translateCell(int mask, String tbl) {
        ILiblouisServiceInterface s = svc;
        if (s == null) return null;
        try {
            CharSequence out = s.translateBrailleToText(String.valueOf((char) (0x8000 | (mask & 0xFF))), MODE, tbl);
            return out == null ? "" : out.toString();
        } catch (RemoteException e) {
            return null;
        }
    }
}
