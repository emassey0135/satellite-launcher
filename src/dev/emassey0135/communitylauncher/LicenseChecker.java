package dev.emassey0135.communitylauncher;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Reads Orbit's Plus-license flag. The value lives in Orbit's launcher content provider, which is guarded by a
 * signature permission, so it is read with root (the same way the device's own apps hold root). The query runs on
 * a background thread; the result is delivered on the main thread. If root or the query is unavailable, the flag is
 * reported as unknown and the caller keeps its last known value.
 */
final class LicenseChecker {
    private static final String TAG = LauncherModel.TAG;
    private static final String URI =
            "content://com.orbit.homelauncher.provider/preferences/isLicenseActivate";

    interface Callback {
        /** @param known whether the license could be read; @param active its value when known. */
        void onResult(boolean known, boolean active);
    }

    private final Handler main = new Handler(Looper.getMainLooper());

    void check(Callback cb) {
        new Thread(() -> {
            Boolean active = query();
            main.post(() -> cb.onResult(active != null, active != null && active));
        }, "license-check").start();
    }

    private Boolean query() {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", "content query --uri " + URI)
                    .redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) out.append(line).append('\n');
            }
            p.waitFor();
            String text = out.toString();
            // Expected: "Row: 0 key=isLicenseActivate, value=1, type=int"
            int i = text.indexOf("value=");
            if (i < 0) { Log.w(TAG, "license query gave no value: " + text.trim()); return null; }
            String v = text.substring(i + 6).trim();
            return v.startsWith("1") || v.toLowerCase().startsWith("true");
        } catch (Exception e) {
            Log.w(TAG, "license query failed (root unavailable?): " + e.getMessage());
            return null;
        } finally {
            if (p != null) p.destroy();
        }
    }
}
