package dev.emassey0135.communitylauncher;

import android.os.SystemClock;

/**
 * Shuts down or restarts the device the way Orbit's screen reader does: tell the keypad's microcontroller first (UART
 * command 8: 3 to shut down, 4 to restart), then ask Android through root. Without the message, the microcontroller
 * doesn't answer for a while after the device starts again, and Orbit's screen reader announces a communication error.
 */
final class Power {
    private static final byte COMMAND = 8;
    private static final byte SHUT_DOWN = 3;
    private static final byte RESTART = 4;
    /** Time for the microcontroller to take the message before Android goes down. */
    private static final long SETTLE_MS = 1000;

    private Power() { }

    /** Restarts the device, or shuts it down; returns false if root wouldn't do it. Call off the main thread. */
    static boolean go(ScreenReaderClient sr, boolean restart) {
        if (sr != null && sr.isReady()) {
            sr.sendData(COMMAND, new byte[] {restart ? RESTART : SHUT_DOWN});
            SystemClock.sleep(SETTLE_MS);
        }
        return RootQuery.exec(restart ? "svc power reboot" : "svc power shutdown").ok();
    }

    /** {@link #go} on a thread of its own, from the main thread. */
    static void goInBackground(ScreenReaderClient sr, boolean restart) {
        new Thread(() -> go(sr, restart), "power").start();
    }
}
