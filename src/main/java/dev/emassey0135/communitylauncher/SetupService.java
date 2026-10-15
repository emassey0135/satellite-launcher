package dev.emassey0135.communitylauncher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Runs the device setup ({@link SetupSteps}) and speaks its progress through Orbit's screen reader. The computer starts
 * it with root, since it isn't exported:
 * {@code adb shell su -c am start-foreground-service -n dev.emassey0135.communitylauncher/.SetupService}
 * and {@link BootReceiver} starts it again after setup restarts the device. It writes what it does to
 * {@code files/setup.log}.
 *
 * <p>The computer's choices, each on unless a start says otherwise and kept for later runs (including after setup's
 * restarts) until another start changes it: {@code --ez google_play false} leaves Google Play as it is rather than
 * turning it on where it was turned off, {@code --ez turn_off_dnd false} leaves Do Not Disturb as it is on the first
 * run rather than turning it off, and {@code --es locale en-GB} sets the device's language, which is otherwise left
 * alone.
 *
 * <p>Setup also brings the Orbit hook and the launcher up to date with their latest releases; the launcher starts it
 * with {@link #EXTRA_UPDATE} when the user chooses to install updates.
 */
public final class SetupService extends Service {
    private static final String TAG = LauncherModel.TAG;
    /** Set when the device has come back up from setup's own restart, rather than someone starting setup. */
    static final String EXTRA_RESUME = "resume";
    /** Set when the user chose to install updates, which setup brings in with everything else. */
    static final String EXTRA_UPDATE = "update";
    /** Where the computer's choices are kept. */
    static final String OPTIONS = "setup";
    /** Whether to turn Google Play on where it was turned off (default true). */
    static final String EXTRA_GOOGLE_PLAY = "google_play";
    /** Whether the first run turns off Do Not Disturb (default true). */
    static final String EXTRA_TURN_OFF_DND = "turn_off_dnd";
    /** The device's language as a language tag, such as en-GB; left alone unless given. */
    static final String EXTRA_LOCALE = "locale";
    private static final String CHANNEL = "setup";

    private ScreenReaderClient sr;
    private final List<String> unspoken = new ArrayList<>();
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    /**
     * Messages wait until this time (uptime). After setup's own restart, Orbit's screen reader says it is ready before
     * its speech engine is, and then its welcome message cuts off whatever it was asked to say.
     */
    private volatile long speakAfter;
    private boolean resuming;
    /** How long after Orbit's screen reader is ready, after a restart, that setup first speaks. */
    private static final long SPEAK_AFTER_RESTART_MS = 6000;
    /** How long setup's last message has before going home makes the launcher speak. */
    private static final long LAST_MESSAGE_MS = 4000;
    private boolean running;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(1, notification());
        if (running) return START_NOT_STICKY;
        running = true;
        boolean started = intent == null || !intent.getBooleanExtra(EXTRA_RESUME, false);
        boolean updating = intent != null && intent.getBooleanExtra(EXTRA_UPDATE, false);
        resuming = !started;
        android.content.SharedPreferences options = getSharedPreferences(OPTIONS, MODE_PRIVATE);
        for (String option : new String[] {EXTRA_GOOGLE_PLAY, EXTRA_TURN_OFF_DND}) {
            if (intent != null && intent.hasExtra(option)) {
                options.edit().putBoolean(option, intent.getBooleanExtra(option, true)).commit();
            }
        }
        if (intent != null && intent.hasExtra(EXTRA_LOCALE)) {
            options.edit().putString(EXTRA_LOCALE, intent.getStringExtra(EXTRA_LOCALE)).commit();
        }
        sr = new ScreenReaderClient(this, new ScreenReaderClient.Listener() {
            @Override public void onReady() {
                if (!sr.isScreenReaderReady()) return;
                if (resuming && speakAfter == 0) {
                    speakAfter = android.os.SystemClock.uptimeMillis() + SPEAK_AFTER_RESTART_MS;
                    main.postDelayed(SetupService.this::speakUnspoken, SPEAK_AFTER_RESTART_MS);
                } else {
                    speakUnspoken();
                }
            }
            @Override public void onKey(String keyName, int action, boolean longPress) { }
        }, null);
        sr.bind();
        new Thread(() -> {
            SetupEngine.Listener listener = new Listener(this);
            SetupSteps steps = new SetupSteps(this, listener);
            SetupEngine engine = new SetupEngine(steps.steps(), new FileStore(this),
                    new SetupEngine.Device() {
                        @Override public String bootId() { return bootIdOf(); }
                        @Override public void restart() throws Exception {
                            if (!Power.go(sr, true)) throw new Exception("svc power reboot failed");
                        }
                    }, listener);
            if (!started) waitUntilStarted();
            listener.say(!started ? "Continuing setup." : updating ? "Installing updates." : "Setting up the device.");
            SetupEngine.Result result = engine.run(started);
            Log.i(TAG, "setup: " + result);
            if (result == SetupEngine.Result.FINISHED) {
                File update = steps.launcherUpdate();
                // Finish at home, wherever setup left the user (such as Backtalk's script settings), once the last
                // message has been said: the launcher's own announcement would cut it off.
                long held = Math.max(0, speakAfter - android.os.SystemClock.uptimeMillis());
                android.os.SystemClock.sleep(held + LAST_MESSAGE_MS);
                // Unless the launcher is in front already, which going home would only announce again.
                if (!RootQuery.exec("dumpsys window | grep mCurrentFocus").output.contains(getPackageName() + "/")) {
                    RootQuery.exec(SetupSteps.GO_HOME);
                }
                if (update != null) {
                    // Last, as installing it stops the launcher, and this with it; Android starts it again as the home app.
                    listener.say("Updating the launcher. It starts again by itself.");
                    android.os.SystemClock.sleep(LAST_MESSAGE_MS);
                    listener.log("updating the launcher");
                    RootQuery.exec("pm install -r " + update);
                }
            }
            if (result != SetupEngine.Result.RESTARTING) stopSelf();
        }, "setup").start();
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        if (sr != null) sr.unbind();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    /**
     * Waits until the device has finished starting (sys.boot_completed). The launcher can start setup well before then,
     * and Android announces it to apps only after a long queue of others.
     */
    private static void waitUntilStarted() {
        for (int i = 0; i < 300; i++) {
            try {
                Process p = new ProcessBuilder("getprop", "sys.boot_completed").start();
                String value;
                try (java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    value = r.readLine();
                }
                p.destroy();
                if ("1".equals(value == null ? null : value.trim())) return;
            } catch (Exception e) {
                return;
            }
            android.os.SystemClock.sleep(1000);
        }
    }

    /**
     * Carries on with setup if it restarted the device: it asked for a restart, and the device has started since. The
     * launcher may also start in the moments between asking and the restart.
     */
    static void resumeIfRestarted(Context context) {
        SetupEngine.State state = new FileStore(context).load();
        if (SetupEngine.resumesAfterBoot(state) && !state.restartRequestedIn.equals(bootIdOf())) {
            context.startForegroundService(new Intent(context, SetupService.class).putExtra(EXTRA_RESUME, true));
        }
    }

    /** Speak now if Orbit's screen reader is ready, or else as soon as it is. */
    private void say(String message) {
        synchronized (unspoken) {
            if (sr == null || !sr.isScreenReaderReady() || android.os.SystemClock.uptimeMillis() < speakAfter) {
                unspoken.add(message);
                return;
            }
        }
        sr.speak(message);
    }

    private void speakUnspoken() {
        if (android.os.SystemClock.uptimeMillis() < speakAfter) return; // a later call says them
        List<String> messages;
        synchronized (unspoken) {
            messages = new ArrayList<>(unspoken);
            unspoken.clear();
        }
        if (!messages.isEmpty()) sr.speak(String.join(" ", messages));
    }

    private Notification notification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Setup", NotificationManager.IMPORTANCE_LOW));
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("Setting up the device")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .build();
    }

    private static String bootIdOf() {
        try {
            return new String(Files.readAllBytes(new File("/proc/sys/kernel/random/boot_id").toPath()),
                    StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** Writes to files/setup.log and Android's log, and speaks. */
    private static final class Listener implements SetupEngine.Listener {
        private final SetupService service;
        private final File log;

        Listener(SetupService service) {
            this.service = service;
            this.log = new File(service.getFilesDir(), "setup.log");
        }

        @Override public synchronized void log(String message) {
            Log.i(TAG, "setup: " + message);
            String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date()) + " " + message + "\n";
            try (FileOutputStream out = new FileOutputStream(log, true)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                Log.e(TAG, "setup log not written", e);
            }
        }

        @Override public void say(String message) {
            log("said: " + message);
            service.say(message);
        }
    }

    /** Setup's progress in files/setup-state.json. */
    private static final class FileStore implements SetupEngine.Store {
        private final File file;

        FileStore(Context context) { file = new File(context.getFilesDir(), "setup-state.json"); }

        @Override public SetupEngine.State load() {
            SetupEngine.State state = new SetupEngine.State();
            try {
                JSONObject o = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                state.inProgress = o.optBoolean("inProgress");
                state.needsRestart = o.optBoolean("needsRestart");
                state.restartRequestedIn = o.optString("restartRequestedIn", null);
                state.restarts = o.optInt("restarts");
            } catch (Exception e) {
                // Never run, or unreadable: start afresh.
            }
            return state;
        }

        @Override public void save(SetupEngine.State state) {
            try {
                JSONObject o = new JSONObject();
                o.put("inProgress", state.inProgress);
                o.put("needsRestart", state.needsRestart);
                if (state.restartRequestedIn != null) o.put("restartRequestedIn", state.restartRequestedIn);
                o.put("restarts", state.restarts);
                File tmp = new File(file.getPath() + ".new");
                Files.write(tmp.toPath(), o.toString().getBytes(StandardCharsets.UTF_8));
                if (!tmp.renameTo(file)) throw new Exception("rename failed");
            } catch (Exception e) {
                Log.e(TAG, "setup state not saved", e);
            }
        }
    }
}
