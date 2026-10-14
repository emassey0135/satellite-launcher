package dev.emassey0135.communitylauncher;

import android.os.Handler;
import android.os.Looper;

/**
 * Reads Orbit's Plus-license flag. The value lives in Orbit's launcher content provider, which is guarded by a
 * signature permission, so it is read with root (the same way the device's own apps hold root). The query runs on
 * a background thread; the result is delivered on the main thread. If root or the query is unavailable, the flag is
 * reported as unknown and the caller keeps its last known value.
 */
final class LicenseChecker {
    private static final String URI =
            "content://com.orbit.homelauncher.provider/preferences/isLicenseActivate";

    interface Callback {
        /** @param known whether the license could be read; @param active its value when known. */
        void onResult(boolean known, boolean active);
    }

    private final Handler main = new Handler(Looper.getMainLooper());

    void check(Callback cb) {
        new Thread(() -> {
            // The row reads "Row: 0 key=isLicenseActivate, value=1, type=int".
            String value = RootQuery.contentValue(URI);
            boolean active = "1".equals(value) || "true".equalsIgnoreCase(value);
            main.post(() -> cb.onResult(value != null, active));
        }, "license-check").start();
    }
}
