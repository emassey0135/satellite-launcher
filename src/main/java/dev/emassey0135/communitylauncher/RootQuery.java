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
        return out == null ? null : valueField(out, 0);
    }

    /** The {@code value=} field of the first row in {@code out} from {@code from} on, or null if there is none. */
    static String valueField(String out, int from) {
        int i = out.indexOf("value=", from);
        if (i < 0) return null;
        int end = out.indexOf(", type=", i);
        if (end < 0) end = out.indexOf('\n', i);
        if (end < 0) end = out.length();
        return out.substring(i + 6, end).trim();
    }

    /** Run a command as root; returns combined output, or null on failure. Call off the main thread. */
    static String run(String command) {
        Result r = exec(command);
        return r.started ? r.output : null;
    }

    /** What a root command printed (with its errors) and how it exited. */
    static final class Result {
        /** Whether {@code su} could run the command at all. */
        final boolean started;
        final int exitCode;
        final String output;

        Result(boolean started, int exitCode, String output) {
            this.started = started;
            this.exitCode = exitCode;
            this.output = output;
        }

        boolean ok() { return started && exitCode == 0; }
    }

    /** Run a command as root, keeping its exit code. Call off the main thread. */
    static Result exec(String command) {
        return exec(false, command);
    }

    /**
     * Run a command as root in the device's own view of storage ({@code globalView}) rather than this app's, in which
     * Android 11 hides other apps' data.
     */
    static Result exec(boolean globalView, String command) {
        Process p = null;
        try {
            p = (globalView ? new ProcessBuilder("su", "--mount-master", "-c", command)
                    : new ProcessBuilder("su", "-c", command)).redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) out.append(line).append('\n');
            }
            return new Result(true, p.waitFor(), out.toString());
        } catch (Exception e) {
            Log.w(TAG, "root command failed: " + e.getMessage());
            return new Result(false, -1, "");
        } finally {
            if (p != null) p.destroy();
        }
    }
}
