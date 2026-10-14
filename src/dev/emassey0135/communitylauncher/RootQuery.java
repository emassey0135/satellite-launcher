package dev.emassey0135.communitylauncher;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Small root helpers: read a value from a content provider, and run a shell command, both via {@code su}. */
final class RootQuery {
    private static final String TAG = LauncherModel.TAG;

    private RootQuery() { }

    /** The {@code value=} field of a single-row content query, or null if root or the row is unavailable. */
    static String contentValue(String uri) {
        String out = run("content query --uri " + uri);
        if (out == null) return null;
        int i = out.indexOf("value=");
        if (i < 0) return null;
        int end = out.indexOf(", type=", i);
        if (end < 0) end = out.indexOf('\n', i);
        if (end < 0) end = out.length();
        return out.substring(i + 6, end).trim();
    }

    /** Run a command as root; returns combined output, or null on failure. Call off the main thread. */
    static String run(String command) {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) out.append(line).append('\n');
            }
            p.waitFor();
            return out.toString();
        } catch (Exception e) {
            Log.w(TAG, "root command failed: " + e.getMessage());
            return null;
        } finally {
            if (p != null) p.destroy();
        }
    }
}
