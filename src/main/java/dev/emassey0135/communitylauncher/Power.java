package dev.emassey0135.communitylauncher;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.SystemClock;

import java.util.concurrent.atomic.AtomicBoolean;

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

    /**
     * {@link #go} on a thread of its own, from the main thread. Once the device is going down, choosing again does
     * nothing.
     */
    static void goInBackground(ScreenReaderClient sr, boolean restart) {
        if (!going.compareAndSet(false, true)) return;
        new Thread(() -> { if (!go(sr, restart)) going.set(false); }, "power").start();
    }

    private static final AtomicBoolean going = new AtomicBoolean();

    /** Android's battery level in percent, or -1 if it doesn't know it. */
    static int batteryPercent(Context context) {
        Intent status = batteryStatus(context);
        if (status == null) return -1;
        int level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        return level >= 0 && scale > 0 ? Math.round(level * 100f / scale) : -1;
    }

    /** Whether the charger is connected. */
    static boolean isCharging(Context context) {
        Intent status = batteryStatus(context);
        return status != null && status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
    }

    private static Intent batteryStatus(Context context) {
        return context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }
}
