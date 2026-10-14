package dev.emassey0135.communitylauncher;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How the device was before setup, as a shell script that puts it back: {@code restore.sh}, run as root. It needs no
 * app to be working, so it can undo setup from a computer whatever state the device is in. It leaves the launcher
 * installed (the computer uninstalls it last) and Magisk's own files repaired.
 */
final class SetupBackup {
    /** Secure and system settings: name to value, or null where it wasn't set. */
    final Map<String, String> secure = new LinkedHashMap<>();
    final Map<String, String> system = new LinkedHashMap<>();
    /** The home activity, or null if none was chosen. */
    String home;
    /** Package or component to its enabled state, as PackageManager numbers them. */
    final Map<String, Integer> enabledStates = new LinkedHashMap<>();
    /** Magisk's zygisk setting, or null where it wasn't set. */
    String zygisk;
    boolean lsposedInstalled;
    /** Whether LSPosed's module list was saved next to the script, and with it the log of its latest changes. */
    boolean lsposedConfigSaved;
    boolean lsposedConfigLogSaved;
    boolean hookInstalled;
    boolean backtalkInstalled;
    /** Magisk policies' notification setting by app uid. */
    final Map<Integer, Integer> rootNotifications = new LinkedHashMap<>();
    int launcherUid;

    String restoreScript(String created) {
        StringBuilder s = new StringBuilder();
        s.append("#!/system/bin/sh\n");
        s.append("# Undoes the Community Launcher setup made on ").append(created).append(". Run it as root:\n");
        s.append("#   adb shell su -c sh ").append(SetupSteps.STATE_DIR).append("/restore.sh\n");
        s.append("# then uninstall the launcher (pm uninstall ").append(SetupSteps.LAUNCHER)
                .append(") and restart the device.\n\n");
        for (Map.Entry<String, String> e : secure.entrySet()) setting(s, "secure", e.getKey(), e.getValue());
        for (Map.Entry<String, String> e : system.entrySet()) setting(s, "system", e.getKey(), e.getValue());
        if (home != null) s.append("cmd package set-home-activity ").append(quote(home)).append('\n');
        for (Map.Entry<String, Integer> e : enabledStates.entrySet()) {
            s.append(enabledCommand(e.getValue())).append(' ').append(quote(e.getKey())).append('\n');
        }
        s.append(zygisk == null
                ? "magisk --sqlite \"DELETE FROM settings WHERE key='zygisk'\"\n"
                : "magisk --sqlite \"REPLACE INTO settings (key,value) VALUES('zygisk'," + Integer.parseInt(zygisk) + ")\"\n");
        if (!lsposedInstalled) s.append("touch ").append(SetupSteps.LSPOSED_MODULE).append("/remove\n");
        if (lsposedConfigSaved) {
            String saved = SetupSteps.STATE_DIR + "/modules_config.db";
            String db = SetupSteps.LSPOSED_DB;
            s.append("cp ").append(saved).append(' ').append(db).append(" && rm -f ").append(db).append("-shm && ")
                    .append(lsposedConfigLogSaved ? "cp " + saved + "-wal " + db + "-wal" : "rm -f " + db + "-wal")
                    .append('\n');
        }
        s.append("touch ").append(SetupSteps.GUARD_MODULE).append("/remove\n");
        if (!hookInstalled) s.append("pm uninstall ").append(SetupSteps.HOOK).append('\n');
        if (!backtalkInstalled) s.append("pm uninstall ").append(SetupSteps.BACKTALK).append('\n');
        for (Map.Entry<Integer, Integer> e : rootNotifications.entrySet()) {
            s.append("magisk --sqlite \"UPDATE policies SET notification=").append(e.getValue())
                    .append(" WHERE uid=").append(e.getKey()).append("\"\n");
        }
        s.append("magisk --sqlite \"DELETE FROM policies WHERE uid=").append(launcherUid).append("\"\n");
        s.append("echo 'Restored. Uninstall the launcher, then restart the device.'\n");
        return s.toString();
    }

    private static void setting(StringBuilder s, String table, String name, String value) {
        if (value == null) s.append("settings delete ").append(table).append(' ').append(name).append('\n');
        else s.append("settings put ").append(table).append(' ').append(name).append(' ').append(quote(value)).append('\n');
    }

    /** The pm command that sets an enabled state (PackageManager.COMPONENT_ENABLED_STATE_*). */
    private static String enabledCommand(int state) {
        switch (state) {
            case 1: return "pm enable";
            case 2: return "pm disable";
            case 3: return "pm disable-user --user 0";
            case 4: return "pm disable-until-used";
            default: return "pm default-state";
        }
    }

    /** {@code value} as one shell word. */
    static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
