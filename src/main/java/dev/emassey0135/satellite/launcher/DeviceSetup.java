package dev.emassey0135.satellite.launcher;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Puts back the parts of the device setup that an Orbit update (or anything else) can undo, so that nobody has to
 * connect the device to a computer again afterwards. Do Not Disturb is the user's choice and is left alone.
 *
 * <p>Everything is checked without root first; only what needs changing is done, in a single {@code su} call. Nothing is
 * done until setup has made this launcher the home app, so a device that isn't set up yet (or whose setup is still
 * making its backup) is left alone.
 */
final class DeviceSetup {
    private static final String TAG = LauncherModel.TAG;
    static final String ORBIT_PREFIX = "com.orbit.";
    private static final String ORBIT_LAUNCHER = "com.orbit.homelauncher";

    /**
     * Parts of Orbit's launcher that keep running when it isn't the home app: its power dialog (which would open
     * alongside ours), and from 0.0.76 a service that shows it too and records usage to send to Orbit, plus the
     * daily alarm that sends it.
     */
    static final String[] DISABLED_COMPONENTS = {
            "com.orbit.homelauncher/com.orbit.homelauncher.PowerOffService",
            "com.orbit.homelauncher/com.orbit.homelauncher.service.DeviceUsageTrackingService",
            "com.orbit.homelauncher/com.orbit.homelauncher.devicetracking.DailySyncAlarmReceiver",
    };

    /** Orbit's automation service, which accepts security prompts by itself and clicks around in the file picker. */
    static final String AUTOMATION_PACKAGE = "com.orbit.accessibilityserviceapp";

    private static final String SCREEN_READER_SERVICE = "com.orbit.screenreader/.ScreenReaderAccessibilityService";
    /**
     * Orbit's launcher replaces the whole list of accessibility services, dropping any other screen reader, if this
     * entry is missing. Keeping it in the list prevents that; the service itself doesn't run while its package is
     * disabled.
     */
    private static final String AUTOMATION_SERVICE = "com.orbit.accessibilityserviceapp/.MyAccessibilityService";

    private DeviceSetup() { }

    /** Check and repair on a background thread. {@code rootGrants} also silences new Magisk root toasts. */
    static void applyInBackground(Context context, boolean rootGrants) {
        Context app = context.getApplicationContext();
        new Thread(() -> apply(app, rootGrants), "device-setup").start();
    }

    static synchronized void apply(Context context, boolean rootGrants) {
        PackageManager pm = context.getPackageManager();
        if (!new ComponentName(context, LauncherActivity.class).equals(SetupSteps.currentHome(pm))) return;
        ContentResolver resolver = context.getContentResolver();
        List<String> commands = new ArrayList<>();

        for (String name : DISABLED_COMPONENTS) {
            ComponentName component = ComponentName.unflattenFromString(name);
            try {
                if (pm.getComponentEnabledSetting(component) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                    commands.add("pm disable " + name);
                }
            } catch (IllegalArgumentException e) {
                // Not in this version of Orbit's launcher.
            }
        }
        Integer automation = enabledState(pm, AUTOMATION_PACKAGE);
        if (automation != null && !isDisabled(automation)) commands.add("pm disable-user --user 0 " + AUTOMATION_PACKAGE);

        // Turning the device makes screen readers lose their place, and nobody needs the screen to turn.
        if (Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 0) != 0) {
            commands.add("settings put system accelerometer_rotation 0");
        }
        if (Settings.System.getInt(resolver, Settings.System.USER_ROTATION, 0) != 0) {
            commands.add("settings put system user_rotation 0");
        }

        if (isInstalled(pm, "com.orbit.screenreader")) {
            String current = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            String wanted = withServices(current, SCREEN_READER_SERVICE,
                    isInstalled(pm, ORBIT_LAUNCHER) ? AUTOMATION_SERVICE : null);
            if (!wanted.equals(current)) {
                commands.add("settings put secure enabled_accessibility_services " + SetupBackup.quote(wanted));
            }
            if (Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                commands.add("settings put secure accessibility_enabled 1");
            }
        }

        if (rootGrants) {
            StringBuilder uids = new StringBuilder().append(context.getApplicationInfo().uid);
            for (ApplicationInfo info : pm.getInstalledApplications(0)) {
                if (info.packageName.startsWith(ORBIT_PREFIX)) uids.append(',').append(info.uid);
            }
            commands.add("magisk --sqlite \"UPDATE policies SET notification=0 WHERE uid IN (" + uids + ")\"");
        }

        if (commands.isEmpty()) return;
        Log.i(TAG, "device setup: " + commands);
        RootQuery.run(String.join("; ", commands));
    }

    /** The enabled-services list with the given entries added if missing (compared ignoring case, as Android does). */
    static String withServices(String current, String... services) {
        Set<String> entries = new LinkedHashSet<>();
        if (current != null) {
            for (String entry : current.split(":")) {
                if (!entry.isEmpty()) entries.add(entry);
            }
        }
        for (String service : services) {
            if (service == null) continue;
            boolean present = false;
            for (String entry : entries) present |= entry.equalsIgnoreCase(service);
            if (!present) entries.add(service);
        }
        return String.join(":", entries);
    }

    static boolean isInstalled(PackageManager pm, String packageName) {
        try {
            pm.getApplicationInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** An app's enabled state (PackageManager.COMPONENT_ENABLED_STATE_*), or null if it isn't installed. */
    static Integer enabledState(PackageManager pm, String packageName) {
        try {
            return pm.getApplicationEnabledSetting(packageName);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Whether an enabled state is one of the turned-off ones that Orbit, or the user, sets. */
    static boolean isDisabled(int state) {
        return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
    }
}
