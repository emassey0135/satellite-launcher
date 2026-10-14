package dev.emassey0135.communitylauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * At boot, re-arm Orbit's alarms and calendar reminders. Orbit's own launcher does this and nothing else does,
 * so a replacement home app must, or alarms and reminders are lost after every restart. Also checks the device
 * setup again, in case something changed it while starting up, and carries on with the setup if it restarted the
 * device.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if (SetupService.resumesAfterBoot(context)) {
            context.startForegroundService(new Intent(context, SetupService.class).putExtra(SetupService.EXTRA_RESUME, true));
        }
        send(context, "com.orbit.ALARM_DATA", "com.orbit.alarmclock");
        send(context, "com.orbit.CALENDAR_DATA", "com.orbit.calendar");
        DeviceSetup.applyInBackground(context, false);
    }

    private void send(Context context, String action, String pkg) {
        try {
            context.sendBroadcast(new Intent(action).setPackage(pkg).putExtra("isDeviceRestarted", true));
        } catch (Exception ignored) { }
    }
}
