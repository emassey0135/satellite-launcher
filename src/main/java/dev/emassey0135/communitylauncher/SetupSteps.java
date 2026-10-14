package dev.emassey0135.communitylauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.ResolveInfo;
import android.database.sqlite.SQLiteDatabase;
import android.os.BatteryManager;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;

import dev.emassey0135.communitylauncher.SetupEngine.Outcome;
import dev.emassey0135.communitylauncher.SetupEngine.Step;
import dev.emassey0135.communitylauncher.SetupEngine.StopException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The steps that set up an Orbit Speak or Orbit Player: Magisk repaired if its own files are missing, LSPosed through
 * Zygisk, the Orbit hook, Backtalk using the Orbit's keys as a braille display, the step aside script, and this
 * launcher as the home app. The settings that Orbit updates can undo are left to {@link DeviceSetup}, which this runs
 * last and the launcher runs again whenever Orbit's apps change.
 *
 * <p>The launcher becomes the home app as soon as the hook works, before Backtalk, which it doesn't need; until then
 * Orbit's launcher stays the home app.
 *
 * <p>What it installs is downloaded from where each is published, and checked: LSPosed against its checksum, apps
 * against the key their makers sign them with. A computer can instead put files in {@link #PAYLOAD} (only adb and root
 * can write there), which are used as they are, such as to set up without a network or to test a build. Each is needed
 * only if it isn't installed already.
 */
final class SetupSteps {
    static final String LAUNCHER = "dev.emassey0135.communitylauncher";
    static final String PAYLOAD = "/data/local/tmp/orbit-setup";
    /** Where setup keeps its backup and the boot guard its count; only root can read it. */
    static final String STATE_DIR = "/data/adb/orbit-setup";
    static final String HOOK = "dev.emassey0135.orbithook";
    static final String BACKTALK = "fyi.quin.backtalk";
    static final String BACKTALK_SERVICE = "fyi.quin.backtalk/com.google.android.marvin.talkback.TalkBackService";
    static final String BRAILLE_DISPLAY_PERMISSION = "fyi.quin.backtalk.permission.BRAILLE_DISPLAY";
    static final String LSPOSED_MODULE = "/data/adb/modules/zygisk_lsposed";
    static final String LSPOSED_DB = "/data/adb/lspd/config/modules_config.db";
    static final String GUARD_MODULE = "/data/adb/modules/orbit_boot_guard";
    static final String SCRIPT = "orbit-step-aside.js";
    static final String SCRIPT_URI = "content://" + LAUNCHER + ".scripts/" + SCRIPT;

    /** LSPosed 1.9.2 (7024) for Zygisk, the version tested on both devices. */
    private static final String LSPOSED_ZIP = "lsposed.zip";
    private static final String LSPOSED_URL =
            "https://github.com/LSPosed/LSPosed/releases/download/v1.9.2/LSPosed-v1.9.2-7024-zygisk-release.zip";
    private static final String LSPOSED_SHA256 = "0ebc6bcb465d1c4b44b7220ab5f0252e6b4eb7fe43da74650476d2798bb29622";
    private static final int LSPOSED_VERSION = 7024;
    private static final String HOOK_APK = "orbit-hook.apk";
    private static final String HOOK_URL =
            "https://github.com/emassey0135/orbit-hook/releases/latest/download/orbit-hook.apk";
    /** The SHA-256 of the hook's release signing certificate; until there is one, the hook isn't downloaded. */
    private static final String HOOK_CERT = null;
    private static final String BACKTALK_APK = "backtalk.apk";
    /** Backtalk's development builds, which it updates itself from. */
    private static final String BACKTALK_URL = "https://github.com/trypsynth/backtalk/releases/download/dev/backtalk.apk";
    private static final String BACKTALK_CERT = "8ddba1d5edf9b7470850b19f756e4755b6a4d99ee793d5d9b608918a91ba0eb0";
    /** The apps the hook runs in. */
    private static final String[] HOOK_SCOPE = {"com.orbit.screenreader", "com.orbit.alarmclock"};
    private static final String MAGISK_APP = "com.topjohnwu.magisk";
    /** Orbit's launcher, the home app the devices come with. */
    private static final String ORBIT_HOME = "com.orbit.homelauncher/.HomeLauncherActivity";
    /** Magisk's own files: the name in /data/adb/magisk, and where the Magisk app keeps it. */
    private static final String[][] MAGISK_FILES = {
            {"busybox", "lib/arm64-v8a/libbusybox.so"},
            {"magisk64", "lib/arm64-v8a/libmagisk64.so"},
            {"magisk32", "lib/armeabi-v7a/libmagisk32.so"},
            {"magiskboot", "lib/arm64-v8a/libmagiskboot.so"},
            {"magiskinit", "lib/arm64-v8a/libmagiskinit.so"},
            {"magiskpolicy", "lib/arm64-v8a/libmagiskpolicy.so"},
            {"util_functions.sh", "assets/util_functions.sh"},
            {"boot_patch.sh", "assets/boot_patch.sh"},
            {"addon.d.sh", "assets/addon.d.sh"},
    };
    private static final String[] BOOT_GUARD_FILES = {"module.prop", "post-fs-data.sh", "service.sh"};

    private final Context context;
    private final PackageManager pm;
    private final SetupEngine.Listener listener;

    SetupSteps(Context context, SetupEngine.Listener listener) {
        this.context = context;
        this.pm = context.getPackageManager();
        this.listener = listener;
    }

    List<Step> steps() {
        return Arrays.asList(
                step("check the device", this::checkDevice),
                step("keep a home app until setup finishes", this::keepHome),
                step("back up what setup changes", this::backUp),
                step("repair Magisk", this::repairMagisk),
                SetupEngine.RESTART,
                step("install the boot guard", this::installBootGuard),
                step("turn on Zygisk", this::enableZygisk),
                step("install LSPosed", this::installLsposed),
                SetupEngine.RESTART,
                step("check that LSPosed is running", this::checkLsposed),
                step("install the Orbit hook", this::installHook),
                step("turn the Orbit hook on in LSPosed", this::enableHook),
                SetupEngine.RESTART,
                step("check that the Orbit hook is working", this::checkHook),
                // The launcher needs the hook (Orbit leaves its window alone and gives it the keypad), not Backtalk.
                step("make this launcher the home app", this::makeHome),
                step("apply the device settings", this::applyDeviceSettings),
                step("install Backtalk", this::installBacktalk),
                step("let the Orbit hook be Backtalk's braille display", this::allowDisplay),
                step("turn Backtalk on", this::enableBacktalk),
                step("check that Backtalk uses the Orbit's keys", this::checkDisplay),
                step("install the step aside script", this::installScript),
                step("go to the home screen", this::goHome));
    }

    private interface Action {
        Outcome run() throws Exception;
    }

    private static Step step(String name, Action action) {
        return new Step() {
            @Override public String name() { return name; }
            @Override public Outcome run() throws Exception { return action.run(); }
        };
    }

    // ---- The steps -------------------------------------------------------------------------------------------------

    private Outcome checkDevice() throws StopException {
        String notOrbit = "This setup is only for the Orbit Speak and Orbit Player.";
        if (Build.VERSION.SDK_INT != Build.VERSION_CODES.R || installed("com.orbit.screenreader") == null
                || !Arrays.asList(Build.SUPPORTED_64_BIT_ABIS).contains("arm64-v8a")) {
            throw new StopException(notOrbit);
        }
        if (!root("id").output.contains("uid=0")) {
            throw new StopException("Setup needs root access, which this device doesn't give it.");
        }
        Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null) {
            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
            int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            boolean charging = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            if (!charging && level * 100 < scale * 50) {
                throw new StopException("Setup restarts the device several times. Charge the battery to at least half, "
                        + "or connect the charger, and start setup again.");
            }
        }
        return Outcome.DONE;
    }

    /**
     * Installing this launcher gives Android a second home app, so it forgets which is the default and asks the user to
     * choose the next time they go home. Until the hook works and setup makes this launcher the home app, Orbit's stays
     * the default.
     */
    private Outcome keepHome() throws StopException {
        if (currentHome() != null || installed("com.orbit.homelauncher") == null) return Outcome.DONE;
        rootOrStop("cmd package set-home-activity " + ORBIT_HOME, "Setup couldn't keep Orbit's launcher as the home app.");
        return Outcome.DONE;
    }

    /** Once, before anything is changed: how the device was, as a script that puts it back. */
    private Outcome backUp() throws Exception {
        if (root("[ -f " + STATE_DIR + "/restore.sh ]").ok()) return Outcome.DONE;
        SetupBackup backup = new SetupBackup();
        for (String name : new String[] {Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                Settings.Secure.ACCESSIBILITY_ENABLED}) {
            backup.secure.put(name, Settings.Secure.getString(context.getContentResolver(), name));
        }
        for (String name : new String[] {Settings.System.ACCELEROMETER_ROTATION, Settings.System.USER_ROTATION}) {
            backup.system.put(name, Settings.System.getString(context.getContentResolver(), name));
        }
        ComponentName home = currentHome();
        backup.home = home != null ? home.flattenToShortString()
                : installed("com.orbit.homelauncher") != null ? ORBIT_HOME : null;
        for (String name : DeviceSetup.DISABLED_COMPONENTS) {
            try {
                backup.enabledStates.put(name, pm.getComponentEnabledSetting(ComponentName.unflattenFromString(name)));
            } catch (IllegalArgumentException e) {
                // Not in this version of Orbit's launcher.
            }
        }
        try {
            backup.enabledStates.put(DeviceSetup.AUTOMATION_PACKAGE,
                    pm.getApplicationEnabledSetting(DeviceSetup.AUTOMATION_PACKAGE));
        } catch (IllegalArgumentException e) {
            // Not installed.
        }
        backup.zygisk = sqliteValue(root("magisk --sqlite \"SELECT value FROM settings WHERE key='zygisk'\"").output,
                "value");
        backup.lsposedInstalled = root("[ -d " + LSPOSED_MODULE + " ]").ok();
        backup.hookInstalled = installed(HOOK) != null;
        backup.backtalkInstalled = installed(BACKTALK) != null;
        for (String line : root("magisk --sqlite \"SELECT uid,notification FROM policies\"").output.split("\n")) {
            String uid = sqliteValue(line, "uid");
            String notification = sqliteValue(line, "notification");
            if (uid != null && notification != null) {
                backup.rootNotifications.put(Integer.parseInt(uid), Integer.parseInt(notification));
            }
        }
        backup.launcherUid = context.getApplicationInfo().uid;
        rootOrStop("mkdir -p " + STATE_DIR + " && chmod 700 " + STATE_DIR, "Setup couldn't save its backup.");
        if (root("[ -f " + LSPOSED_DB + " ]").ok()) {
            rootOrStop("cp " + LSPOSED_DB + " " + STATE_DIR + "/modules_config.db", "Setup couldn't save its backup.");
            backup.lsposedConfigSaved = true;
            if (root("[ -f " + LSPOSED_DB + "-wal ]").ok()) {
                rootOrStop("cp " + LSPOSED_DB + "-wal " + STATE_DIR + "/modules_config.db-wal",
                        "Setup couldn't save its backup.");
                backup.lsposedConfigLogSaved = true;
            }
        }
        String created = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date());
        File script = writeCache("restore.sh", backup.restoreScript(created).getBytes(StandardCharsets.UTF_8));
        rootOrStop("cat " + script + " > " + STATE_DIR + "/restore.sh.new && mv " + STATE_DIR + "/restore.sh.new "
                + STATE_DIR + "/restore.sh && chmod 700 " + STATE_DIR + "/restore.sh", "Setup couldn't save its backup.");
        listener.say("Saved a backup of the device's settings.");
        return Outcome.DONE;
    }

    /**
     * Some devices come with Magisk running but its own files missing from /data/adb/magisk, which stops it installing
     * modules. They are the same files that the Magisk app carries, so they are copied from it.
     */
    private Outcome repairMagisk() throws Exception {
        StringBuilder check = new StringBuilder();
        for (String[] f : MAGISK_FILES) {
            check.append("[ -f /data/adb/magisk/").append(f[0]).append(" ] || echo ").append(f[0]).append("; ");
        }
        String missing = root(check.toString()).output.trim();
        if (missing.isEmpty()) return Outcome.DONE;
        listener.log("Magisk files missing: " + missing.replace('\n', ' ') + "; Magisk " + root("magisk -V").output.trim());
        String apk = magiskAppApk();
        if (apk == null) throw new StopException("Magisk is missing its own files, and the Magisk app to repair it from "
                + "isn't installed.");
        File dir = new File(cacheDir(), "magisk");
        deleteTree(dir);
        dir.mkdirs();
        try (ZipFile zip = new ZipFile(apk)) {
            for (String[] f : MAGISK_FILES) copyEntry(zip, f[1], new File(dir, f[0]));
            File chromeos = new File(dir, "chromeos");
            chromeos.mkdirs();
            for (ZipEntry e : java.util.Collections.list(zip.entries())) {
                if (!e.isDirectory() && e.getName().startsWith("assets/chromeos/")) {
                    copyEntry(zip, e.getName(), new File(chromeos, e.getName().substring("assets/chromeos/".length())));
                }
            }
        }
        rootOrStop("mkdir -p /data/adb/magisk && cp -r " + dir + "/. /data/adb/magisk/ && chown -R 0:0 /data/adb/magisk"
                + " && chmod -R 755 /data/adb/magisk && chcon -R u:object_r:magisk_file:s0 /data/adb/magisk",
                "Setup couldn't repair Magisk.");
        deleteTree(dir);
        listener.say("Repaired Magisk.");
        return Outcome.NEEDS_RESTART;
    }

    /**
     * A small Magisk module that turns LSPosed off if the device can't finish starting, so a problem with it can't
     * leave the device unusable. Installed before LSPosed is.
     */
    private Outcome installBootGuard() throws Exception {
        String wanted = new String(readAsset("boot-guard/module.prop"), StandardCharsets.UTF_8);
        String current = root("cat " + GUARD_MODULE + "/module.prop 2>/dev/null").output;
        if (current.equals(wanted) && !root("[ -e " + GUARD_MODULE + "/remove ]").ok()) return Outcome.DONE;
        StringBuilder copy = new StringBuilder("mkdir -p " + STATE_DIR + " " + GUARD_MODULE + " && rm -f " + GUARD_MODULE
                + "/remove " + GUARD_MODULE + "/disable");
        for (String name : BOOT_GUARD_FILES) {
            File f = writeCache(name, readAsset("boot-guard/" + name));
            copy.append(" && cat ").append(f).append(" > ").append(GUARD_MODULE).append('/').append(name);
        }
        copy.append(" && chmod 644 ").append(GUARD_MODULE).append("/module.prop && chmod 755 ").append(GUARD_MODULE)
                .append("/*.sh && chcon -R u:object_r:system_file:s0 ").append(GUARD_MODULE);
        rootOrStop(copy.toString(), "Setup couldn't install the boot guard.");
        listener.say("Installed the boot guard.");
        return Outcome.DONE; // it only needs to be there at the next start
    }

    private Outcome enableZygisk() throws Exception {
        String value = sqliteValue(root("magisk --sqlite \"SELECT value FROM settings WHERE key='zygisk'\"").output,
                "value");
        if ("1".equals(value)) return Outcome.DONE;
        rootOrStop("magisk --sqlite \"REPLACE INTO settings (key,value) VALUES('zygisk',1)\"",
                "Setup couldn't turn on Zygisk in Magisk.");
        listener.say("Turned on Zygisk.");
        return Outcome.NEEDS_RESTART;
    }

    private Outcome installLsposed() throws Exception {
        if (root("[ -s " + STATE_DIR + "/boot-guard.log ]").ok()) {
            throw new StopException("LSPosed was turned off because the device couldn't finish starting with it. Setup "
                    + "won't turn it on again; the log on the device says more.");
        }
        String version = propValue(root("cat " + LSPOSED_MODULE + "/module.prop 2>/dev/null").output, "versionCode");
        if (version != null && Integer.parseInt(version.trim()) >= LSPOSED_VERSION) {
            if (root("[ -e " + LSPOSED_MODULE + "/disable ] || [ -e " + LSPOSED_MODULE + "/remove ]").ok()) {
                throw new StopException("LSPosed is turned off or being removed in Magisk. Turn it on in Magisk, then "
                        + "start setup again.");
            }
            return Outcome.DONE;
        }
        File zip = fetchPayloadIfPresent(LSPOSED_ZIP);
        if (zip == null) zip = download(LSPOSED_URL, LSPOSED_ZIP, "LSPosed");
        if (!LSPOSED_SHA256.equals(sha256(zip))) {
            throw new StopException("The LSPosed that setup got isn't the version setup was tested with.");
        }
        RootQuery.Result r = root("magisk --install-module " + zip);
        if (!r.ok()) {
            throw new StopException("Magisk couldn't install LSPosed. " + lastLine(r.output));
        }
        listener.say("Installed LSPosed.");
        return Outcome.NEEDS_RESTART;
    }

    private Outcome checkLsposed() throws StopException {
        if (!root("pidof lspd").output.trim().isEmpty()) return Outcome.DONE;
        throw new StopException("LSPosed isn't running after the restart, so setup can't go on.");
    }

    /**
     * Backtalk as it is, if it can use another app as a braille display; its updates come from Backtalk itself.
     * Otherwise Backtalk's own release, if that can.
     */
    private Outcome installBacktalk() throws Exception {
        if (installed(BACKTALK) != null && backtalkHasDisplayApi()) return Outcome.DONE;
        File apk = fetchPayloadIfPresent(BACKTALK_APK);
        boolean downloaded = apk == null;
        if (downloaded) apk = download(BACKTALK_URL, BACKTALK_APK, "Backtalk");
        PackageInfo info = archiveInfo(apk, PackageManager.GET_PERMISSIONS);
        if (info == null || !BACKTALK.equals(info.packageName) || (downloaded && !BACKTALK_CERT.equals(signer(info)))) {
            throw new StopException("The Backtalk that setup got isn't Backtalk's own.");
        }
        if (!definesDisplayPermission(info)) {
            throw new StopException("This version of Backtalk can't use the Orbit's keys yet, so setup can't go on.");
        }
        install(apk, "Backtalk");
        if (!backtalkHasDisplayApi()) {
            throw new StopException("This version of Backtalk can't use the Orbit's keys.");
        }
        listener.say("Installed Backtalk.");
        return Outcome.DONE;
    }

    private Outcome installHook() throws Exception {
        PackageInfo current = installed(HOOK);
        File apk = fetchPayloadIfPresent(HOOK_APK);
        boolean downloaded = apk == null;
        if (downloaded) {
            if (current != null) return Outcome.DONE; // only a hook from the computer replaces an installed one
            if (HOOK_CERT == null) throw new StopException("The setup files don't include the Orbit hook.");
            apk = download(HOOK_URL, HOOK_APK, "the Orbit hook");
        }
        PackageInfo info = archiveInfo(apk, 0);
        if (info == null || !HOOK.equals(info.packageName) || (downloaded && !HOOK_CERT.equals(signer(info)))) {
            throw new StopException("The Orbit hook that setup got isn't the Orbit hook.");
        }
        if (current != null && current.getLongVersionCode() >= info.getLongVersionCode()) return Outcome.DONE;
        install(apk, "the Orbit hook");
        listener.say("Installed the Orbit hook.");
        return Outcome.DONE;
    }

    private Outcome allowDisplay() throws StopException {
        if (pm.checkPermission(BRAILLE_DISPLAY_PERMISSION, HOOK) == PackageManager.PERMISSION_GRANTED) {
            return Outcome.DONE;
        }
        rootOrStop("pm grant " + HOOK + " " + BRAILLE_DISPLAY_PERMISSION,
                "Setup couldn't let the Orbit hook be Backtalk's braille display.");
        return Outcome.DONE;
    }

    /** LSPosed adds a newly installed module to its list turned off; turn it on, in Orbit's screen reader and alarm clock. */
    private Outcome enableHook() throws Exception {
        if (!root("[ -f " + LSPOSED_DB + " ]").ok()) {
            throw new StopException("LSPosed hasn't made its list of modules yet, so setup can't go on.");
        }
        String apkPath = pm.getApplicationInfo(HOOK, 0).sourceDir;
        // LSPosed adds the module itself soon after it is installed; give it the chance, so the two don't race.
        for (int i = 0; i < 5; i++) {
            if (withConfigCopy(db -> LsposedConfig.isEnabled(db, HOOK, HOOK_SCOPE))) return Outcome.DONE;
            if (withConfigCopy(db -> moduleListed(db))) break;
            SystemClock.sleep(2000);
        }
        File db = copyConfig();
        try (SQLiteDatabase sql = SQLiteDatabase.openDatabase(db.getPath(), null, SQLiteDatabase.OPEN_READWRITE)) {
            LsposedConfig.enable(sql, HOOK, apkPath, HOOK_SCOPE);
        }
        // The copy is now one file, with LSPosed's log of changes folded in; drop the real one's so it isn't replayed.
        rootOrStop("cat " + db + " > " + LSPOSED_DB + " && rm -f " + LSPOSED_DB + "-wal " + LSPOSED_DB + "-shm",
                "Setup couldn't turn the Orbit hook on in LSPosed.");
        listener.say("Turned the Orbit hook on in LSPosed.");
        return Outcome.NEEDS_RESTART;
    }

    /**
     * The hook reports in LSPosed's log whether it found what it needs in Orbit's screen reader. If the screen reader
     * hasn't loaded it (or loaded an older one that doesn't report), the screen reader is restarted so it loads again.
     */
    private Outcome checkHook() throws Exception {
        int uid = pm.getApplicationInfo("com.orbit.screenreader", 0).uid;
        for (int attempt = 0; attempt < 2; attempt++) {
            long until = SystemClock.uptimeMillis() + 60_000;
            while (SystemClock.uptimeMillis() < until) {
                String log = root("cat \"$(ls -t /data/adb/lspd/log/modules_* | head -1)\"").output;
                HookStatus status = hookStatus(log, uid);
                if (status == HookStatus.WORKING) {
                    if (attempt > 0) listener.say("The Orbit hook is working.");
                    return Outcome.DONE;
                }
                if (status == HookStatus.FAILED) {
                    throw new StopException("The Orbit hook is loaded, but it can't find what it needs in this version of "
                            + "Orbit's screen reader, so Backtalk won't be turned on.");
                }
                if (status == HookStatus.LOADED && SystemClock.uptimeMillis() > until - 45_000) break; // it reports at once
                SystemClock.sleep(3000);
            }
            if (attempt == 0) {
                listener.log("the hook hasn't reported; restarting Orbit's screen reader");
                root("kill $(pidof com.orbit.screenreader)");
                SystemClock.sleep(5000);
            }
        }
        throw new StopException("The Orbit hook isn't working in Orbit's screen reader, so Backtalk won't be turned on.");
    }

    enum HookStatus { NOT_LOADED, LOADED, WORKING, FAILED }

    private static final java.util.regex.Pattern LSPOSED_LOG_LINE =
            java.util.regex.Pattern.compile("^\\[\\s*\\S+\\s+(\\d+):\\s*(\\d+):[^\\]]*\\]\\s?(.*)$");

    /**
     * What LSPosed's log says about the hook in the process with this uid, from the last time that process loaded it.
     * Lines read {@code [ time uid: pid: tid I/tag ] message}.
     */
    static HookStatus hookStatus(String log, int uid) {
        HookStatus status = HookStatus.NOT_LOADED;
        String pid = null;
        for (String line : log.split("\n")) {
            java.util.regex.Matcher m = LSPOSED_LOG_LINE.matcher(line);
            if (!m.matches() || Integer.parseInt(m.group(1)) != uid) continue;
            String message = m.group(3);
            if (message.startsWith("Loading legacy module " + HOOK + " ")) {
                pid = m.group(2);
                status = HookStatus.LOADED;
            } else if (m.group(2).equals(pid) && message.startsWith("OrbitHook: ")) {
                if (message.contains("chord pass-through installed")) status = HookStatus.WORKING;
                else if (message.contains("not found") || message.contains("unavailable")
                        || message.contains("failed")) status = HookStatus.FAILED;
            }
        }
        return status;
    }

    private Outcome enableBacktalk() throws StopException {
        String services = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean on = Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1;
        if (services != null && on) {
            for (String s : services.split(":")) if (s.equalsIgnoreCase(BACKTALK_SERVICE)) return Outcome.DONE;
        }
        String list = services == null || services.isEmpty() ? BACKTALK_SERVICE : services + ":" + BACKTALK_SERVICE;
        rootOrStop("settings put secure enabled_accessibility_services " + SetupBackup.quote(list)
                + " && settings put secure accessibility_enabled 1", "Setup couldn't turn Backtalk on.");
        listener.say("Turned Backtalk on.");
        return Outcome.DONE;
    }

    /** Backtalk binds the Orbit hook's display service; if it hasn't, the hook asks it to look again. */
    private Outcome checkDisplay() throws StopException {
        for (int attempt = 0; attempt < 2; attempt++) {
            long until = SystemClock.uptimeMillis() + 30_000;
            while (SystemClock.uptimeMillis() < until) {
                String services = root("dumpsys activity services " + HOOK + "/.OrbitDisplayService").output;
                if (services.contains("ConnectionRecord") && services.contains(BACKTALK)) return Outcome.DONE;
                SystemClock.sleep(2000);
            }
            if (attempt == 0) root("am broadcast -f 0x20 -n " + HOOK + "/.ConnectReceiver");
        }
        throw new StopException("Backtalk isn't using the Orbit's keys. Check that Backtalk is on, then start setup again.");
    }

    /**
     * Opens the step aside script with Backtalk, which asks the user before installing it, the way Backtalk installs
     * any script. A newer copy with the same id updates it, again after asking.
     */
    @android.annotation.SuppressLint("SdCardPath") // Backtalk's own storage, read with root
    private Outcome installScript() throws Exception {
        String wanted = sha256(readAsset(SCRIPT));
        String installedPath = "/data/data/" + BACKTALK + "/files/scripts/orbit-step-aside/main.js";
        if (root("sha256sum " + installedPath).output.startsWith(wanted)) return Outcome.DONE;
        RootQuery.Result open = root("am start -a android.intent.action.VIEW -t application/javascript -d " + SCRIPT_URI
                + " -p " + BACKTALK);
        if (!open.ok() || open.output.contains("Error")) {
            throw new StopException("Setup couldn't open the step aside script with Backtalk.");
        }
        listener.say("Backtalk is asking to install the Orbit step aside script, which keeps it quiet in Orbit's apps. "
                + "Choose Install.");
        long until = SystemClock.uptimeMillis() + 5 * 60_000;
        while (SystemClock.uptimeMillis() < until) {
            SystemClock.sleep(2000);
            if (root("sha256sum " + installedPath).output.startsWith(wanted)) return Outcome.DONE;
        }
        throw new StopException("The step aside script wasn't installed. Start setup again to be asked again.");
    }

    private Outcome makeHome() throws StopException {
        ComponentName ours = new ComponentName(context, LauncherActivity.class);
        if (ours.equals(currentHome())) return Outcome.DONE;
        rootOrStop("cmd package set-home-activity " + ours.flattenToShortString(),
                "Setup couldn't make this launcher the home app.");
        root("am start -a android.intent.action.MAIN -c android.intent.category.HOME");
        listener.say("Made this launcher the home app.");
        return Outcome.DONE;
    }

    /** Finish at home, wherever setup left the user (such as Backtalk's script settings after it asked). */
    private Outcome goHome() {
        root("am start -a android.intent.action.MAIN -c android.intent.category.HOME");
        return Outcome.DONE;
    }

    private Outcome applyDeviceSettings() {
        DeviceSetup.apply(context, true);
        return Outcome.DONE;
    }

    // ---- Helpers ---------------------------------------------------------------------------------------------------

    private RootQuery.Result root(String command) {
        RootQuery.Result r = RootQuery.exec(true, command);
        listener.log("$ " + command + " -> " + r.exitCode + (r.output.isEmpty() ? "" : ": " + abbreviate(r.output)));
        return r;
    }

    private RootQuery.Result rootOrStop(String command, String spoken) throws StopException {
        RootQuery.Result r = root(command);
        if (!r.ok()) throw new StopException(spoken);
        return r;
    }

    private PackageInfo installed(String pkg) {
        try {
            return pm.getPackageInfo(pkg, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private boolean backtalkHasDisplayApi() {
        try {
            PermissionInfo info = pm.getPermissionInfo(BRAILLE_DISPLAY_PERMISSION, 0);
            return BACKTALK.equals(info.packageName);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void install(File apk, String name) throws StopException {
        RootQuery.Result r = root("pm install -r " + apk);
        if (r.output.contains("Success")) return;
        if (r.output.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE")) {
            throw new StopException("Setup couldn't install " + name + ", because a copy signed by someone else is "
                    + "installed.");
        }
        throw new StopException("Setup couldn't install " + name + ". " + lastLine(r.output));
    }

    private ComponentName currentHome() {
        return currentHome(pm);
    }

    /** The default home activity, or null if there is none and Android would ask. */
    static ComponentName currentHome(PackageManager pm) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo info = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        if (info == null || info.activityInfo == null || "android".equals(info.activityInfo.packageName)) return null;
        return new ComponentName(info.activityInfo.packageName, info.activityInfo.name);
    }

    /** The Magisk app's APK, which may have been given another package name. */
    private String magiskAppApk() {
        try {
            return pm.getApplicationInfo(MAGISK_APP, 0).sourceDir;
        } catch (PackageManager.NameNotFoundException e) {
            // Look for a renamed one.
        }
        for (ApplicationInfo app : pm.getInstalledApplications(0)) {
            try (ZipFile zip = new ZipFile(app.sourceDir)) {
                if (zip.getEntry("lib/arm64-v8a/libmagiskinit.so") != null) return app.sourceDir;
            } catch (Exception ignored) {
                // Not readable; not Magisk.
            }
        }
        return null;
    }

    private interface ConfigCheck {
        boolean test(SQLiteDatabase db);
    }

    private boolean withConfigCopy(ConfigCheck check) throws Exception {
        File db = copyConfig();
        try (SQLiteDatabase sql = SQLiteDatabase.openDatabase(db.getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
            return check.test(sql);
        }
    }

    private static boolean moduleListed(SQLiteDatabase db) {
        try (android.database.Cursor c = db.rawQuery("SELECT 1 FROM modules WHERE module_pkg_name=?",
                new String[] {HOOK})) {
            return c.moveToFirst();
        }
    }

    /** A copy of LSPosed's list in this app's storage, with its log of recent changes. */
    private File copyConfig() throws Exception {
        File db = new File(cacheDir(), "modules_config.db");
        File wal = new File(cacheDir(), "modules_config.db-wal");
        new File(cacheDir(), "modules_config.db-shm").delete();
        // Created here first, so that they are this app's files; root only writes into them.
        writeFile(db, new byte[0]);
        writeFile(wal, new byte[0]);
        rootOrStop("cat " + LSPOSED_DB + " > " + db + " && { [ ! -f " + LSPOSED_DB + "-wal ] || cat " + LSPOSED_DB
                + "-wal > " + wal + "; }", "Setup couldn't read LSPosed's list of modules.");
        if (wal.length() == 0) wal.delete();
        return db;
    }

    /** Download {@code url} into this app's storage. */
    private File download(String url, String name, String what) throws StopException {
        listener.say("Downloading " + what + ".");
        listener.log("downloading " + url);
        File f = new File(cacheDir(), name);
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(30_000);
            c.setReadTimeout(60_000);
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            long length = c.getContentLengthLong();
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(f)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            if (length >= 0 && f.length() != length) throw new Exception("got " + f.length() + " of " + length + " bytes");
            return f;
        } catch (Exception e) {
            listener.log("download failed: " + e);
            throw new StopException("Setup couldn't download " + what + ". Check that the device is connected to Wi-Fi, "
                    + "then start setup again.");
        }
    }

    private PackageInfo archiveInfo(File apk, int flags) {
        return pm.getPackageArchiveInfo(apk.getPath(), flags | PackageManager.GET_SIGNATURES);
    }

    /** The SHA-256 of the certificate an APK is signed with, as apksigner prints it. */
    private static String signer(PackageInfo info) throws Exception {
        if (info.signatures == null || info.signatures.length != 1) return null;
        return sha256(info.signatures[0].toByteArray());
    }

    private static boolean definesDisplayPermission(PackageInfo info) {
        if (info.permissions == null) return false;
        for (PermissionInfo p : info.permissions) if (BRAILLE_DISPLAY_PERMISSION.equals(p.name)) return true;
        return false;
    }

    /** A copy of a file the computer provided, in this app's storage; null if it didn't provide it. */
    private File fetchPayloadIfPresent(String name) throws StopException {
        if (!root("[ -f " + PAYLOAD + "/" + name + " ]").ok()) return null;
        File f = new File(cacheDir(), name);
        writeFile(f, new byte[0]);
        rootOrStop("cat " + PAYLOAD + "/" + name + " > " + f, "Setup couldn't read its files.");
        return f;
    }

    private File cacheDir() {
        File dir = new File(context.getCacheDir(), "setup");
        dir.mkdirs();
        return dir;
    }

    private File writeCache(String name, byte[] bytes) throws StopException {
        File f = new File(cacheDir(), name);
        writeFile(f, bytes);
        return f;
    }

    private static void writeFile(File f, byte[] bytes) throws StopException {
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(bytes);
        } catch (Exception e) {
            throw new StopException("Setup couldn't write its files.");
        }
    }

    private byte[] readAsset(String name) throws Exception {
        try (InputStream in = context.getAssets().open(name)) {
            return readAll(in);
        }
    }

    /** All of {@code in} (InputStream.readAllBytes is Android 13 and later). */
    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static void copyEntry(ZipFile zip, String name, File to) throws Exception {
        ZipEntry e = zip.getEntry(name);
        if (e == null) throw new StopException("The Magisk app doesn't have " + name + " to repair Magisk with.");
        try (InputStream in = zip.getInputStream(e); OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private static void deleteTree(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteTree(c);
        f.delete();
    }

    static String sha256(File f) throws Exception {
        try (InputStream in = new java.io.FileInputStream(f)) {
            return sha256(readAll(in));
        }
    }

    static String sha256(byte[] bytes) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) hex.append(String.format(Locale.ROOT, "%02x", b));
        return hex.toString();
    }

    /** A value from one row of {@code magisk --sqlite} output, which reads {@code key=value|key=value}. */
    static String sqliteValue(String row, String key) {
        for (String line : row.split("\n")) {
            for (String field : line.split("\\|")) {
                int eq = field.indexOf('=');
                if (eq > 0 && field.substring(0, eq).trim().equals(key)) return field.substring(eq + 1).trim();
            }
        }
        return null;
    }

    /** A value from a {@code key=value} properties file, such as a Magisk module's module.prop. */
    static String propValue(String props, String key) {
        for (String line : props.split("\n")) {
            if (line.startsWith(key + "=")) return line.substring(key.length() + 1).trim();
        }
        return null;
    }

    private static String lastLine(String output) {
        String[] lines = output.trim().split("\n");
        return lines[lines.length - 1].trim();
    }

    private static String abbreviate(String output) {
        String flat = output.trim().replace('\n', ' ');
        return flat.length() > 300 ? flat.substring(0, 300) + "..." : flat;
    }
}
