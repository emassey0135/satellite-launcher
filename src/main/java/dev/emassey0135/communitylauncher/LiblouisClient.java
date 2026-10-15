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
import java.util.Locale;
import java.util.Map;

/**
 * Binds Orbit's braille translator and turns typed braille cells into text, using the user's current input table
 * (read from Orbit settings via root). Each cell is a char {@code 0x8000 | dotBits}, matching Orbit's own usage.
 */
final class LiblouisClient {
    private static final String TAG = LauncherModel.TAG;
    private static final String SETTINGS = "content://com.orbit.settings.provider/preferences/";
    private static final String DEFAULT_TABLE = "en-ueb-g2.ctb";
    private static final String DEFAULT_LETTER_TABLE = "en-ueb-g1.ctb";

    private final Context context;
    private volatile ILiblouisServiceInterface svc;
    private volatile String table = DEFAULT_TABLE;      // user's current table, for the committed name
    /** The user's table that reads each cell as itself (no contractions), for finding items by letter. */
    private volatile String letterTable = DEFAULT_LETTER_TABLE;

    LiblouisClient(Context context) { this.context = context.getApplicationContext(); }

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            svc = ILiblouisServiceInterface.Stub.asInterface(b);
        }
        @Override public void onServiceDisconnected(ComponentName n) { svc = null; }
    };

    /** Whether Orbit's braille translation service is installed (the Speak has it; the Player, with no braille keys, doesn't). */
    private boolean available() {
        return DeviceSetup.isInstalled(context.getPackageManager(), "com.orbit.liblouisservice");
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
            String m = mode.toLowerCase(Locale.ROOT);
            if (m.contains("uncontract")) key = "key_uncontracted_braille_input_table";
            else if (m.contains("computer")) key = "key_computer_braille_input_table";
        }
        String t = values.get(key);
        if (t != null && !t.isEmpty()) table = t;
        // Letters are read without contractions, so that every letter means itself: computer braille's own table when
        // that is what the user types, else the uncontracted one.
        String letters = values.get(key.equals("key_computer_braille_input_table")
                ? key : "key_uncontracted_braille_input_table");
        if (letters != null && !letters.isEmpty()) letterTable = letters;
        Log.d(TAG, "braille input table = " + table + ", for letters " + letterTable);
    }

    /** Parse the output of the marked multi-query: each {@code @@key} line is followed by that key's row. */
    private static Map<String, String> parseMarked(String out) {
        Map<String, String> map = new HashMap<>();
        if (out == null) return map;
        for (String part : out.split("@@")) {
            int nl = part.indexOf('\n');
            if (nl < 0) continue;
            String value = RootQuery.valueField(part, nl);
            if (value != null) map.put(part.substring(0, nl).trim(), value);
        }
        return map;
    }

    boolean isReady() { return svc != null; }

    /** Translate a list of cell masks in the user's current table at the given liblouis mode (388 live, 132 commit). */
    String translateContracted(java.util.List<Integer> cells, int mode) {
        return translate(cells, mode, table);
    }

    /** The letter one cell stands for on its own, without contractions; null if it isn't known. */
    String translateLetter(int cell) {
        return translate(java.util.Collections.singletonList(cell), 132, letterTable);
    }

    private String translate(java.util.List<Integer> cells, int mode, String table) {
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
}
