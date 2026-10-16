package dev.emassey0135.satellite.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;

import dev.emassey0135.satellite.launcher.SetupEngine.Outcome;
import dev.emassey0135.satellite.launcher.SetupEngine.Step;
import dev.emassey0135.satellite.launcher.SetupEngine.StopException;

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
 * Zygisk, the Satellite hook, Backtalk using the Orbit's keys as a braille display, the step aside script, and this
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
    static final String LAUNCHER = "dev.emassey0135.satellite.launcher";
    static final String PAYLOAD = "/data/local/tmp/satellite";
    /** Where setup keeps its backup and the boot guard its count; only root can read it. */
    static final String STATE_DIR = "/data/adb/satellite";
    static final String HOOK = "dev.emassey0135.satellite.hook";
    static final String BACKTALK = "fyi.quin.backtalk";
    static final String BACKTALK_SERVICE = "fyi.quin.backtalk/com.google.android.marvin.talkback.TalkBackService";
    static final String BRAILLE_DISPLAY_PERMISSION = "fyi.quin.backtalk.permission.BRAILLE_DISPLAY";
    /** Opens Backtalk's question about whether an app may be its braille display. */
    static final String ASK_BRAILLE_DISPLAY = "fyi.quin.backtalk.action.ASK_BRAILLE_DISPLAY";
    private static final String APPROVAL_ACTIVITY =
            "com.google.android.accessibility.braille.brailledisplay.platform.external.ExternalDisplayApprovalActivity";
    /** Where Backtalk keeps the apps the user allowed to be braille displays; read with root. */
    private static final String BACKTALK_DISPLAY_PREFS = "/data/user_de/0/" + BACKTALK + "/shared_prefs/braille_display.xml";
    static final String LSPOSED_MODULE = "/data/adb/modules/zygisk_lsposed";
    static final String LSPOSED_DB = "/data/adb/lspd/config/modules_config.db";
    static final String GUARD_MODULE = "/data/adb/modules/satellite_boot_guard";
    static final String SCRIPT = "orbit-step-aside.js";
    static final String SCRIPT_URI = "content://" + LAUNCHER + ".scripts/" + SCRIPT;
    /** Goes to the home screen, through root, as an app in the background can't. */
    static final String GO_HOME = "am start -a android.intent.action.MAIN -c android.intent.category.HOME";

    /** LSPosed 1.9.2 (7024) for Zygisk, the version tested on both devices. */
    private static final String LSPOSED_ZIP = "lsposed.zip";
    private static final String LSPOSED_URL =
            "https://github.com/LSPosed/LSPosed/releases/download/v1.9.2/LSPosed-v1.9.2-7024-zygisk-release.zip";
    private static final String LSPOSED_SHA256 = "0ebc6bcb465d1c4b44b7220ab5f0252e6b4eb7fe43da74650476d2798bb29622";
    private static final int LSPOSED_VERSION = 7024;
    private static final String HOOK_APK = "satellite-hook.apk";
    private static final String BACKTALK_APK = "backtalk.apk";
    /** Backtalk's development builds, which it updates itself from. */
    private static final String BACKTALK_URL = "https://github.com/trypsynth/backtalk/releases/download/dev/backtalk.apk";
    private static final String BACKTALK_CERT = "8ddba1d5edf9b7470850b19f756e4755b6a4d99ee793d5d9b608918a91ba0eb0";
    /** The apps the hook runs in. */
    private static final String[] HOOK_SCOPE = {"com.orbit.screenreader", "com.orbit.alarmclock", "com.orbit.updater"};
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
    /**
     * Google Play: its services, the framework under them and the Play Store. Orbit turns some of them off on the Orbit
     * Player before shipping it, which leaves no way to install apps and stops many from working.
     */
    private static final String[] GOOGLE_PLAY = {
            "com.google.android.gsf", "com.google.android.gms", "com.android.vending"};
    private static final String[] BOOT_GUARD_FILES = {"module.prop", "post-fs-data.sh", "service.sh"};

    private final Context context;
    private final PackageManager pm;
    private final SetupEngine.Listener listener;
    /** The computer's choices (see {@link SetupService}), and what the first run left to do. */
    private final android.content.SharedPreferences options;
    /** Set when setup makes its backup, which only its first run does, until Do Not Disturb has been seen to. */
    private static final String DND_PENDING = "dnd_pending";

    SetupSteps(Context context, SetupEngine.Listener listener) {
        this.context = context;
        this.pm = context.getPackageManager();
        this.listener = listener;
        this.options = context.getSharedPreferences(SetupService.OPTIONS, Context.MODE_PRIVATE);
    }

    List<Step> steps() {
        return Arrays.asList(
                step("check the device", this::checkDevice),
                step("keep a home app until setup finishes", this::keepHome),
                step("back up what setup changes", this::backUp),
                step("turn off root notices for Orbit's apps", this::quietRootNotices),
                step("repair Magisk", this::repairMagisk),
                step("set the device's language", this::setLocale),
                SetupEngine.RESTART,
                step("install the boot guard", this::installBootGuard),
                step("turn on Zygisk", this::enableZygisk),
                step("install LSPosed", this::installLsposed),
                SetupEngine.RESTART,
                step("check that LSPosed is running", this::checkLsposed),
                step("install the Satellite hook", this::installHook),
                step("turn the Satellite hook on in LSPosed", this::enableHook),
                SetupEngine.RESTART,
                step("check that the Satellite hook is working", this::checkHook),
                // The launcher needs the hook (Orbit leaves its window alone and gives it the keypad), not Backtalk.
                step("make this launcher the home app", this::makeHome),
                step("apply the device settings", this::applyDeviceSettings),
                step("turn on Google Play", this::enableGooglePlay),
                step("turn off Do Not Disturb", this::disableDoNotDisturb),
                step("install Backtalk", this::installBacktalk),
                step("let the Satellite hook be Backtalk's braille display", this::allowDisplay),
                step("turn Backtalk on", this::enableBacktalk),
                step("check that Backtalk uses the Orbit's keys", this::checkDisplay),
                step("install the step aside script", this::installScript));
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
        int battery = Power.batteryPercent(context);
        if (battery >= 0 && battery < 50 && !Power.isCharging(context)) {
            throw new StopException("Setup restarts the device several times. Charge the battery to at least half, "
                    + "or connect the charger, and start setup again.");
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
        backup.locale = root("getprop persist.sys.locale").output.trim();
        for (String name : new String[] {Settings.System.ACCELEROMETER_ROTATION, Settings.System.USER_ROTATION,
                "system_locales"}) {
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
        for (String pkg : concat(new String[] {DeviceSetup.AUTOMATION_PACKAGE}, GOOGLE_PLAY)) {
            Integer state = DeviceSetup.enabledState(pm, pkg);
            if (state != null) backup.enabledStates.put(pkg, state);
        }
        backup.zygisk = zygiskSetting();
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
        backup.zenMode = Settings.Global.getInt(context.getContentResolver(), "zen_mode", -1);
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
        options.edit().putBoolean(DND_PENDING, true).apply();
        listener.say("Saved a backup of the device's settings.");
        return Outcome.DONE;
    }

    /**
     * As soon as the backup has recorded them, so that Magisk doesn't announce Orbit's apps' root commands through setup's
     * restarts. The launcher keeps them off afterwards ({@link DeviceSetup}).
     */
    private Outcome quietRootNotices() {
        root(DeviceSetup.quietRootNotices(context));
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
        if ("1".equals(zygiskSetting())) return Outcome.DONE;
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
        PackageInfo info = archiveInfo(apk, PackageManager.GET_ACTIVITIES);
        if (info == null || !BACKTALK.equals(info.packageName) || (downloaded && !BACKTALK_CERT.equals(Updates.signer(info)))) {
            throw new StopException("The Backtalk that setup got isn't Backtalk's own.");
        }
        if (!hasApprovalActivity(info)) {
            throw new StopException("This version of Backtalk can't use the Orbit's keys yet, so setup can't go on.");
        }
        install(apk, "Backtalk");
        if (!backtalkHasDisplayApi()) {
            throw new StopException("This version of Backtalk can't use the Orbit's keys.");
        }
        listener.say("Installed Backtalk.");
        return Outcome.DONE;
    }

    /**
     * The hook from the computer if it gave one, else its latest release, if the hook isn't installed or the release is
     * newer. A hook that is already in use is loaded into Orbit's apps only when they start again, so updating it
     * needs a restart.
     */
    private Outcome installHook() throws Exception {
        PackageInfo current = installed(HOOK);
        File apk = fetchPayloadIfPresent(HOOK_APK);
        boolean downloaded = apk == null;
        if (downloaded) {
            if (current != null && !newerRelease(Updates.App.HOOK)) return Outcome.DONE;
            apk = download(Updates.App.HOOK.apkUrl(), HOOK_APK, "the Satellite hook");
        }
        PackageInfo info = archiveInfo(apk, 0);
        if (info == null || !HOOK.equals(info.packageName) || (downloaded && !Updates.CERT.equals(Updates.signer(info)))) {
            throw new StopException("The Satellite hook that setup got isn't the Satellite hook.");
        }
        if (current != null && current.getLongVersionCode() >= info.getLongVersionCode()) return Outcome.DONE;
        install(apk, "the Satellite hook");
        if (current == null) {
            listener.say("Installed the Satellite hook.");
            return Outcome.DONE;
        }
        listener.say("Updated the Satellite hook.");
        return Outcome.NEEDS_RESTART;
    }

    /**
     * A newer release of this launcher, downloaded and checked, for setup to install once it is done (installing it
     * stops the launcher, setup with it); null if there is none, or it can't be had.
     */
    File launcherUpdate() {
        if (!newerRelease(Updates.App.LAUNCHER)) return null;
        try {
            File apk = download(Updates.App.LAUNCHER.apkUrl(), Updates.App.LAUNCHER.apk, "the launcher's update");
            PackageInfo info = archiveInfo(apk, 0);
            if (info == null || !LAUNCHER.equals(info.packageName) || !Updates.CERT.equals(Updates.signer(info))) {
                listener.log("the launcher's update isn't the launcher");
                return null;
            }
            return apk;
        } catch (Exception e) {
            listener.log("the launcher's update failed: " + e);
            return null;
        }
    }

    /**
     * Whether {@code app} has a newer release that can replace the copy installed (a copy signed with the release key).
     * Without the network, the installed copy is kept.
     */
    private boolean newerRelease(Updates.App app) {
        if (!Updates.updatable(pm, app)) return false;
        Updates.Release release;
        try {
            release = Updates.latest(app);
        } catch (java.io.IOException e) {
            listener.log("couldn't read the latest release of " + app.name + ": " + e);
            return false;
        }
        if (release == null) return false; // none yet
        boolean newer = Updates.isNewer(pm, release);
        listener.log("latest release of " + app.name + ": " + release.versionName + (newer ? ", newer" : ""));
        return newer;
    }

    /**
     * Backtalk uses the Orbit display only once the user allows it in Backtalk, after a warning, so setup opens that
     * question. Backtalk isn't on yet, so Orbit's screen reader reads it (the hook leaves Orbit speaking while the Orbit
     * display isn't connected), and the user answers with the keypad.
     */
    @android.annotation.SuppressLint("SdCardPath") // Backtalk's own storage, read with root
    private Outcome allowDisplay() throws Exception {
        if (displayAllowed()) return Outcome.DONE;
        // The permission only lets the hook ask, and Android gives it to an app installed before the app that declares
        // it only when that app is installed again.
        if (pm.checkPermission(BRAILLE_DISPLAY_PERMISSION, HOOK) != PackageManager.PERMISSION_GRANTED) {
            install(new File(pm.getApplicationInfo(HOOK, 0).sourceDir), "the Satellite hook");
        }
        boolean allowed = askInBacktalk("am start -a " + ASK_BRAILLE_DISPLAY + " -p " + BACKTALK
                        + " --es fyi.quin.backtalk.extra.PACKAGE " + HOOK,
                "Backtalk is asking whether the Orbit's keys may be its braille display. Choose Allow.",
                this::displayAllowed, () -> inDisplaySet("pref_external_displays_denied"),
                "Setup couldn't ask Backtalk to use the Orbit's keys.");
        if (!allowed) {
            throw new StopException("Backtalk wasn't allowed to use the Orbit's keys. Start setup again to be asked again.");
        }
        listener.say("Backtalk can use the Orbit's keys.");
        return Outcome.DONE;
    }

    /** Whether the user has allowed the Satellite hook in Backtalk's list of braille display apps. */
    private boolean displayAllowed() {
        return inDisplaySet("pref_external_displays_allowed");
    }

    /** Whether the Satellite hook is in one of Backtalk's sets of braille display apps: allowed, or not allowed. */
    private boolean inDisplaySet(String name) {
        String prefs = root("cat " + BACKTALK_DISPLAY_PREFS + " 2>/dev/null").output;
        int set = prefs.indexOf("<set name=\"" + name + "\"");
        if (set < 0) return false;
        int end = prefs.indexOf("</set>", set);
        return prefs.substring(set, end < 0 ? prefs.length() : end).contains("<string>" + HOOK + "</string>");
    }

    /** Something setup checks about the device. */
    private interface Check {
        boolean test();
    }

    /** How long the device must have been running before setup asks the user anything. */
    private static final long SETTLED_MS = 2 * 60_000;

    /**
     * Opens a question in Backtalk with {@code command}, says {@code question}, and waits up to 5 minutes for the user to
     * answer it ({@code answered}); false if they don't, or turn it down ({@code refused}).
     *
     * <p>The device must have been running for 2 minutes first: until then, the apps starting with it can keep the
     * processor so busy that Android reports Backtalk as not responding while it asks. If the question goes away
     * without an answer, such as when the user chooses Close then, it is asked once more.
     */
    private boolean askInBacktalk(String command, String question, Check answered, Check refused, String cantOpen)
            throws StopException {
        long up = SystemClock.elapsedRealtime();
        if (up < SETTLED_MS) {
            listener.say("Waiting a moment for the device to finish starting.");
            SystemClock.sleep(SETTLED_MS - up);
        }
        for (int asked = 0; asked < 2; asked++) {
            RootQuery.Result open = root(command);
            if (!open.ok() || open.output.contains("Error")) throw new StopException(cantOpen);
            listener.say(asked == 0 ? question : "Backtalk's question closed without an answer, so here it is again. "
                    + question);
            long until = SystemClock.uptimeMillis() + 5 * 60_000;
            boolean seen = false;
            while (SystemClock.uptimeMillis() < until) {
                SystemClock.sleep(2000);
                if (answered.test()) return true;
                if (refused.test()) return false;
                boolean showing = backtalkShowing();
                if (showing) {
                    seen = true;
                } else if (seen) {
                    // Gone: answered just now (Backtalk records it a moment later), or closed without an answer.
                    SystemClock.sleep(2000);
                    if (answered.test()) return true;
                    break;
                }
            }
            if (SystemClock.uptimeMillis() >= until) return false;
        }
        return false;
    }

    /**
     * Whether one of Backtalk's screens is in front, such as one asking the user something. Android's not responding
     * warning shows over it without taking its place.
     */
    private boolean backtalkShowing() {
        return root("dumpsys activity activities | grep -q 'ResumedActivity.* " + BACKTALK + "/' && echo yes")
                .output.contains("yes");
    }

    /** LSPosed adds a newly installed module to its list turned off; turn it on, in the Orbit apps it fixes. */
    private Outcome enableHook() throws Exception {
        if (!root("[ -f " + LSPOSED_DB + " ]").ok()) {
            throw new StopException("LSPosed hasn't made its list of modules yet, so setup can't go on.");
        }
        String apkPath = pm.getApplicationInfo(HOOK, 0).sourceDir;
        // LSPosed adds the module itself soon after it is installed; give it the chance, so the two don't race.
        for (int i = 0; i < 5; i++) {
            if (withConfigCopy(db -> LsposedConfig.isEnabled(db, HOOK, HOOK_SCOPE))) return Outcome.DONE;
            if (withConfigCopy(db -> LsposedConfig.isListed(db, HOOK))) break;
            SystemClock.sleep(2000);
        }
        File db = copyConfig();
        try (SQLiteDatabase sql = SQLiteDatabase.openDatabase(db.getPath(), null, SQLiteDatabase.OPEN_READWRITE)) {
            LsposedConfig.enable(sql, HOOK, apkPath, HOOK_SCOPE);
        }
        // The copy is now one file, with LSPosed's log of changes folded in; drop the real one's so it isn't replayed.
        rootOrStop("cat " + db + " > " + LSPOSED_DB + " && rm -f " + LSPOSED_DB + "-wal " + LSPOSED_DB + "-shm",
                "Setup couldn't turn the Satellite hook on in LSPosed.");
        listener.say("Turned the Satellite hook on in LSPosed.");
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
                    if (attempt > 0) listener.say("The Satellite hook is working.");
                    return Outcome.DONE;
                }
                if (status == HookStatus.FAILED) {
                    throw new StopException("The Satellite hook is loaded, but it can't find what it needs in this version of "
                            + "Orbit's screen reader, so Backtalk won't be turned on.");
                }
                if (status == HookStatus.LOADED && SystemClock.uptimeMillis() > until - 45_000) break; // it reports at once
                SystemClock.sleep(3000);
            }
            if (attempt == 0) {
                listener.log("the hook hasn't reported; restarting Orbit's screen reader");
                restartScreenReader();
            }
        }
        throw new StopException("The Satellite hook isn't working in Orbit's screen reader, so Backtalk won't be turned on.");
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
            } else if (m.group(2).equals(pid) && message.startsWith("SatelliteHook: ")) {
                if (message.contains("chord pass-through installed")) status = HookStatus.WORKING;
                else if (message.contains("not found") || message.contains("unavailable")
                        || message.contains("failed")) status = HookStatus.FAILED;
            }
        }
        return status;
    }

    /**
     * Restarts Orbit's screen reader, so that it loads the hook again. Stopping it in the middle of speaking can leave the
     * speech engine failing every request (AccessMind's Vocalizer reports native error 16003), and then nothing speaks,
     * so the engine is restarted too; Android starts it again for whoever uses it next.
     */
    private void restartScreenReader() {
        root("kill $(pidof com.orbit.screenreader)");
        SystemClock.sleep(2000);
        String engine = Settings.Secure.getString(context.getContentResolver(), "tts_default_synth");
        if (engine != null && !engine.isEmpty()) root("kill $(pidof " + engine + ") 2>/dev/null");
        SystemClock.sleep(3000);
    }

    private Outcome enableBacktalk() throws StopException {
        String services = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean on = Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1;
        String list = DeviceSetup.withServices(services, BACKTALK_SERVICE);
        if (on && list.equals(services)) return Outcome.DONE;
        rootOrStop("settings put secure enabled_accessibility_services " + SetupBackup.quote(list)
                + " && settings put secure accessibility_enabled 1", "Setup couldn't turn Backtalk on.");
        listener.say("Turned Backtalk on.");
        return Outcome.DONE;
    }

    /** Backtalk binds the Satellite hook's display service; if it hasn't, the hook asks it to look again. */
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
        Check installed = () -> root("sha256sum " + installedPath).output.startsWith(wanted);
        if (installed.test()) return Outcome.DONE;
        boolean done = askInBacktalk("am start -a android.intent.action.VIEW -t application/javascript -d " + SCRIPT_URI
                        + " -p " + BACKTALK,
                "Backtalk is asking to install the Orbit step aside script, which keeps it quiet in Orbit's apps. "
                        + "Choose Install.",
                installed, () -> false, "Setup couldn't open the step aside script with Backtalk.");
        if (!done) throw new StopException("The step aside script wasn't installed. Start setup again to be asked again.");
        return Outcome.DONE;
    }

    private Outcome makeHome() throws StopException {
        ComponentName ours = new ComponentName(context, LauncherActivity.class);
        if (ours.equals(currentHome())) return Outcome.DONE;
        rootOrStop("cmd package set-home-activity " + ours.flattenToShortString(),
                "Setup couldn't make this launcher the home app.");
        // Orbit's launcher turns Do Not Disturb back on whenever it opens; stop it so it doesn't open again.
        root("am force-stop com.orbit.homelauncher");
        root(GO_HOME);
        listener.say("Made this launcher the home app.");
        return Outcome.DONE;
    }

    /**
     * Sets the device's language, but only if the computer asked for one: the devices come set to English (India), and
     * which English, or other language, is right depends on the user. It takes effect at the next start.
     */
    private Outcome setLocale() throws StopException {
        String locale = options.getString(SetupService.EXTRA_LOCALE, null);
        if (locale == null || locale.isEmpty()) return Outcome.DONE;
        if (!isLanguageTag(locale)) throw new StopException("The language setup was given, " + locale + ", isn't one.");
        if (locale.equals(root("getprop persist.sys.locale").output.trim())) return Outcome.DONE;
        rootOrStop("setprop persist.sys.locale " + locale + " && settings put system system_locales " + locale,
                "Setup couldn't set the device's language.");
        listener.say("Set the device's language.");
        return Outcome.NEEDS_RESTART;
    }

    /** Whether {@code tag} is a language tag such as en or en-GB, and so safe to put in a command. */
    static boolean isLanguageTag(String tag) {
        return tag.matches("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*");
    }

    /**
     * Turns Google Play back on where it was turned off, once, during setup; later the user's choice stands, so the
     * launcher doesn't keep it on the way it keeps the device settings. The computer can leave it as it is.
     */
    private Outcome enableGooglePlay() throws StopException {
        if (!options.getBoolean(SetupService.EXTRA_GOOGLE_PLAY, true)) return Outcome.DONE;
        StringBuilder enable = new StringBuilder();
        for (String pkg : GOOGLE_PLAY) {
            Integer state = DeviceSetup.enabledState(pm, pkg);
            if (state != null && DeviceSetup.isDisabled(state)) {
                if (enable.length() > 0) enable.append(" && ");
                enable.append("pm enable ").append(pkg);
            }
        }
        if (enable.length() == 0) return Outcome.DONE;
        rootOrStop(enable.toString(), "Setup couldn't turn on Google Play.");
        listener.say("Turned on Google Play.");
        return Outcome.DONE;
    }

    /**
     * Orbit turns Do Not Disturb on (to priority only), so the first run of setup turns it off; after that it is the
     * user's to choose, so later runs, such as after an update, leave it alone. The computer can leave it as it is.
     */
    private Outcome disableDoNotDisturb() throws StopException {
        if (!options.getBoolean(DND_PENDING, false)) return Outcome.DONE;
        if (options.getBoolean(SetupService.EXTRA_TURN_OFF_DND, true)
                && Settings.Global.getInt(context.getContentResolver(), "zen_mode", 0) != 0) {
            rootOrStop("cmd notification set_dnd off", "Setup couldn't turn off Do Not Disturb.");
            listener.say("Turned off Do Not Disturb.");
        }
        options.edit().putBoolean(DND_PENDING, false).apply();
        return Outcome.DONE;
    }

    private Outcome applyDeviceSettings() {
        DeviceSetup.apply(context, true);
        return Outcome.DONE;
    }

    // ---- Helpers ---------------------------------------------------------------------------------------------------

    /** Magisk's zygisk setting, or null if it isn't set. */
    private String zygiskSetting() {
        return sqliteValue(root("magisk --sqlite \"SELECT value FROM settings WHERE key='zygisk'\"").output, "value");
    }

    private static String[] concat(String[] a, String[] b) {
        String[] all = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, all, a.length, b.length);
        return all;
    }

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

    /** Whether the installed Backtalk can use another app as a braille display, once the user allows it. */
    private boolean backtalkHasDisplayApi() {
        return pm.resolveActivity(new Intent(ASK_BRAILLE_DISPLAY).setPackage(BACKTALK), 0) != null;
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
                Streams.copy(in, out);
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

    /** Whether a Backtalk APK can use another app as a braille display: it has the screen that asks the user. */
    private static boolean hasApprovalActivity(PackageInfo info) {
        if (info.activities == null) return false;
        for (android.content.pm.ActivityInfo a : info.activities) if (APPROVAL_ACTIVITY.equals(a.name)) return true;
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
            return Streams.readAll(in);
        }
    }

    private static void copyEntry(ZipFile zip, String name, File to) throws Exception {
        ZipEntry e = zip.getEntry(name);
        if (e == null) throw new StopException("The Magisk app doesn't have " + name + " to repair Magisk with.");
        try (InputStream in = zip.getInputStream(e); OutputStream out = new FileOutputStream(to)) {
            Streams.copy(in, out);
        }
    }

    private static void deleteTree(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteTree(c);
        f.delete();
    }

    static String sha256(File f) throws Exception {
        try (InputStream in = new java.io.FileInputStream(f)) {
            return sha256(Streams.readAll(in));
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
