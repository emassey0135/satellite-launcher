package dev.emassey0135.communitylauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import dev.emassey0135.communitylauncher.SetupSteps.HookStatus;

import org.junit.Test;

/** The parts of setup that read what the device says, and the restore script it writes. */
public class SetupStepsTest {
    private static final int SCREEN_READER = 10193;
    private static final int ALARM_CLOCK = 10177;

    private static String line(int uid, int pid, String message) {
        return "[ 2026-10-14T05:31:11.111    " + uid + ":  " + pid + ":  " + pid + " I/LSPosed-Bridge  ] " + message;
    }

    private static String load(int uid, int pid) {
        return line(uid, pid, "Loading legacy module dev.emassey0135.orbithook from /data/app/x/base.apk");
    }

    @Test public void hookWorksWhenTheScreenReaderReportsTheChordPassThrough() {
        String log = String.join("\n",
                load(SCREEN_READER, 2495),
                line(SCREEN_READER, 2495, "  Loading class dev.emassey0135.orbithook.Hook"),
                line(SCREEN_READER, 2495, "OrbitHook: chord pass-through installed on c0.n.a"));
        assertEquals(HookStatus.WORKING, SetupSteps.hookStatus(log, SCREEN_READER));
    }

    @Test public void onlyTheLatestLoadCounts() {
        String log = String.join("\n",
                load(SCREEN_READER, 2495),
                line(SCREEN_READER, 2495, "OrbitHook: chord pass-through installed on c0.n.a"),
                load(SCREEN_READER, 7491)); // restarted, and hasn't reported yet
        assertEquals(HookStatus.LOADED, SetupSteps.hookStatus(log, SCREEN_READER));
    }

    @Test public void aMissingRouterIsAFailure() {
        String log = String.join("\n",
                load(SCREEN_READER, 2495),
                line(SCREEN_READER, 2495, "OrbitHook: keypad router not found; chord pass-through disabled"));
        assertEquals(HookStatus.FAILED, SetupSteps.hookStatus(log, SCREEN_READER));
    }

    @Test public void otherProcessesDontCount() {
        String log = String.join("\n",
                load(ALARM_CLOCK, 6368),
                line(ALARM_CLOCK, 6368, "OrbitHook: chord pass-through installed on c0.n.a"));
        assertEquals(HookStatus.NOT_LOADED, SetupSteps.hookStatus(log, SCREEN_READER));
        assertEquals(HookStatus.NOT_LOADED, SetupSteps.hookStatus("", SCREEN_READER));
    }

    @Test public void readsMagiskSqliteRows() {
        assertEquals("1", SetupSteps.sqliteValue("value=1\n", "value"));
        String row = "logging=1|notification=0|policy=2|uid=10170|until=0";
        assertEquals("10170", SetupSteps.sqliteValue(row, "uid"));
        assertEquals("0", SetupSteps.sqliteValue(row, "notification"));
        assertNull(SetupSteps.sqliteValue("", "value"));
    }

    @Test public void readsModuleProps() {
        String props = "id=zygisk_lsposed\nversion=v1.9.2 (7024)\nversionCode=7024\n";
        assertEquals("7024", SetupSteps.propValue(props, "versionCode"));
        assertNull(SetupSteps.propValue(props, "author"));
    }

    @Test public void restoreScriptPutsBackWhatWasThere() {
        SetupBackup b = new SetupBackup();
        b.secure.put("enabled_accessibility_services", "com.orbit.screenreader/.ScreenReaderAccessibilityService");
        b.secure.put("accessibility_enabled", "1");
        b.system.put("accelerometer_rotation", null);
        b.home = "com.orbit.homelauncher/.HomeLauncherActivity";
        b.enabledStates.put("com.orbit.homelauncher/com.orbit.homelauncher.PowerOffService", 0);
        b.enabledStates.put("com.orbit.accessibilityserviceapp", 1);
        b.zygisk = "0";
        b.lsposedInstalled = false;
        b.hookInstalled = false;
        b.backtalkInstalled = true;
        b.rootNotifications.put(10170, 1);
        b.launcherUid = 10207;
        b.zenMode = 1;
        String script = b.restoreScript("2026-10-14 06:00");

        assertTrue(script.contains("settings put secure enabled_accessibility_services "
                + "'com.orbit.screenreader/.ScreenReaderAccessibilityService'\n"));
        assertTrue(script.contains("settings delete system accelerometer_rotation\n"));
        assertTrue(script.contains("cmd package set-home-activity 'com.orbit.homelauncher/.HomeLauncherActivity'\n"));
        assertTrue(script.contains("pm default-state 'com.orbit.homelauncher/com.orbit.homelauncher.PowerOffService'\n"));
        assertTrue(script.contains("pm enable 'com.orbit.accessibilityserviceapp'\n"));
        assertTrue(script.contains("VALUES('zygisk',0)"));
        assertTrue(script.contains("touch /data/adb/modules/zygisk_lsposed/remove\n"));
        assertTrue(script.contains("touch /data/adb/modules/orbit_boot_guard/remove\n"));
        assertTrue(script.contains("pm uninstall dev.emassey0135.orbithook\n"));
        assertFalse(script.contains("pm uninstall fyi.quin.backtalk")); // it was there before
        assertTrue(script.contains("UPDATE policies SET notification=1 WHERE uid=10170"));
        assertTrue(script.contains("DELETE FROM policies WHERE uid=10207"));
        assertTrue(script.contains("cmd notification set_dnd priority\n"));
    }

    @Test public void restoreScriptKeepsAnLsposedThatWasThere() {
        SetupBackup b = new SetupBackup();
        b.lsposedInstalled = true;
        b.lsposedConfigSaved = true;
        b.lsposedConfigLogSaved = true;
        String script = b.restoreScript("now");
        assertFalse(script.contains("zygisk_lsposed/remove"));
        assertTrue(script.contains("cp /data/adb/orbit-setup/modules_config.db /data/adb/lspd/config/modules_config.db"
                + " && rm -f /data/adb/lspd/config/modules_config.db-shm && cp /data/adb/orbit-setup/modules_config.db-wal"
                + " /data/adb/lspd/config/modules_config.db-wal\n"));
        assertTrue(script.contains("DELETE FROM settings WHERE key='zygisk'")); // it wasn't set
    }

    @Test public void quotesForTheShell() {
        assertEquals("'it'\\''s'", SetupBackup.quote("it's"));
    }
}
