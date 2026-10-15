package dev.emassey0135.communitylauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.orbit.olympus_sdk.IOlympusSDKCallback;
import com.orbit.screenreader.IScreenReaderService;
import com.orbit.screenreader.ISettingsCallback;
import com.orbit.screenreader.IUARTKeypadCallback;

/**
 * Wraps Orbit's screen reader service: binds to it, registers for keypad events, and speaks.
 *
 * The screen reader routes the keypad to whichever package last called
 * {@link IScreenReaderService#notifyAppForeground}, so the launcher claims it under its own name. (The launcher's name
 * doesn't start with {@code com.orbit.}, so it relies on the Orbit hook for Orbit to leave its window alone.) Speech
 * goes out through {@code speakText}; navigation interrupts with {@code stopSpeaking}.
 */
final class ScreenReaderClient {
    static final String TAG = LauncherModel.TAG;

    interface Listener {
        void onReady();
        /** A key the launcher cares about, delivered on the main thread. Return value is ignored here. */
        void onKey(String keyName, int action, boolean longPress);
        /** An Orbit setting changed (e.g. the braille input table); refresh anything read from settings. */
        default void onSettingsChanged() { }
    }

    private final Context context;
    private final Listener listener;
    private final String pkg;
    private volatile IScreenReaderService sr;

    ScreenReaderClient(Context context, Listener listener) {
        this(context, listener, context.getPackageName());
    }

    /**
     * @param registrationName the package name to claim the keypad under. Use a distinct name (not the launcher's own) for a transient owner like the power
     *     dialog, so unregistering it does not remove the launcher's registration. Null only speaks, and never takes
     *     the keypad.
     */
    ScreenReaderClient(Context context, Listener listener, String registrationName) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.pkg = registrationName;
    }

    boolean isReady() { return sr != null; }

    private volatile boolean screenReaderReady;

    /**
     * Whether Orbit's screen reader has said it is ready. Until then (for a while after the device starts) it is bound
     * but drops what it is asked to speak.
     */
    boolean isScreenReaderReady() { return sr != null && screenReaderReady; }

    private final IOlympusSDKCallback sdk = new IOlympusSDKCallback.Stub() {
        @Override public void onScreenReaderReady() { screenReaderReady = true; register(); listener.onReady(); }
        @Override public void onUtteranceStart(String id) { }
        @Override public void onUtteranceDone(String id) { }
        @Override public void onUtteranceError(String id) { }
    };

    private final IUARTKeypadCallback keys = new IUARTKeypadCallback.Stub() {
        @Override public boolean onInternalKeyEvent(Bundle b) {
            int code = b.getInt("keyCode", -1);
            if (code >= 1000 && code <= 1022) phoneKeypad = true;     // Orbit Player
            else if (code >= 3000) phoneKeypad = false;             // braille keypad (Orbit Speak)
            String name = b.getString("keyName");
            int action = b.getInt("action", -1);
            boolean lp = b.getBoolean("isLongPress", false);
            if (name != null) listener.onKey(name, action, lp);
            return true;
        }
        @Override public void onAdjustVolume(boolean increase) { }
    };

    private final ISettingsCallback settings = new ISettingsCallback.Stub() {
        @Override public void onSettingsChanged() { listener.onSettingsChanged(); }
    };

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            sr = IScreenReaderService.Stub.asInterface(b);
            try { sr.registerSDKCallback(sdk); } catch (RemoteException e) { Log.e(TAG, "registerSDKCallback", e); }
            try { sr.registerSettingsCallback(settings); } catch (RemoteException e) { Log.e(TAG, "registerSettingsCallback", e); }
            register();
            listener.onReady();
        }
        @Override public void onServiceDisconnected(ComponentName n) { sr = null; screenReaderReady = false; }
        /** The screen reader was updated or force-stopped; Android won't reconnect this binding, so bind again. */
        @Override public void onBindingDied(ComponentName n) {
            sr = null;
            screenReaderReady = false;
            try { context.unbindService(this); } catch (Exception ignored) { }
            bind();
        }
    };

    void bind() {
        Intent i = new Intent("com.orbit.screenreader.IScreenReaderService");
        i.setComponent(new ComponentName("com.orbit.screenreader",
                "com.orbit.screenreader.service.ScreenReaderBoundService"));
        if (!context.bindService(i, conn, Context.BIND_AUTO_CREATE)) {
            Log.e(TAG, "bindService to screen reader returned false");
        }
    }

    void unbind() {
        try { if (sr != null && pkg != null) sr.unregisterKeypadCallback(pkg); } catch (RemoteException ignored) { }
        try { if (sr != null) sr.unregisterSettingsCallback(settings); } catch (RemoteException ignored) { }
        try { context.unbindService(conn); } catch (Exception ignored) { }
    }

    private volatile boolean phoneKeypad;

    /** Whether the keys come from a phone-style keypad (the Orbit Player) rather than braille keys. */
    boolean isPhoneKeypad() { return phoneKeypad; }

    private volatile boolean foreground = true;

    /**
     * Whether this screen should hold the keypad. A screen that can be covered by a system window (an app-not-responding
     * dialog, a SystemUI pop-up) sets this from its window focus, so the keypad is left to the reader driving the cover
     * (Backtalk) until it closes, and taken back when focus returns.
     */
    void setForeground(boolean foreground) {
        this.foreground = foreground;
        if (foreground) register();
    }

    /** Register the keypad callback, and take the keypad while this screen is in the foreground. */
    void register() {
        IScreenReaderService s = sr;
        if (s == null || pkg == null) return;
        try {
            s.registerKeypadCallback(pkg, keys);
            if (foreground) s.notifyAppForeground(pkg);
        } catch (RemoteException e) {
            Log.e(TAG, "register keypad failed", e);
        }
    }

    /** One call to Orbit's screen reader. */
    private interface Call {
        void run(IScreenReaderService s) throws RemoteException;
    }

    /** One question to Orbit's screen reader. */
    private interface Query<T> {
        T run(IScreenReaderService s) throws RemoteException;
    }

    /** Makes {@code call} if the screen reader is connected; a failure is logged as {@code what}. */
    private void call(String what, Call call) {
        IScreenReaderService s = sr;
        if (s == null) return;
        try { call.run(s); } catch (RemoteException e) { Log.e(TAG, what + " failed", e); }
    }

    /** What {@code query} returns, or {@code fallback} if the screen reader isn't connected or can't say. */
    private <T> T query(Query<T> query, T fallback) {
        IScreenReaderService s = sr;
        if (s == null) return fallback;
        try { return query.run(s); } catch (RemoteException e) { return fallback; }
    }

    void speak(String text) {
        if (text == null) return;
        call("speak", s -> {
            s.stopSpeaking();
            s.speakText(text);
        });
    }

    /** Speak without interrupting what is already being said. */
    void speakQueued(String text) {
        if (text != null) call("speakText", s -> s.speakText(text));
    }

    int battery() { return query(IScreenReaderService::getBatteryPercentage, -1); }

    String firmware() { return query(IScreenReaderService::getFirmwareVersion, null); }

    String serial() { return query(IScreenReaderService::getSerialNumber, null); }

    /** Tell the screen reader focus moved to a new control (it speaks it using the user's verbosity settings). */
    void focusChanged(Bundle widget) { call("onFocusChanged", s -> s.onFocusChanged(widget)); }

    /** Tell the screen reader the selection changed within the current list/menu. */
    void itemChanged(Bundle container) { call("onItemChanged", s -> s.onItemChanged(container)); }

    /** Announce an edit field (role 6) so the screen reader describes it and review commands read its text. */
    void focusEdit(String name, String content, int cursor) {
        Bundle b = new Bundle();
        b.putString("name", name);
        b.putInt("roleId", 6);
        b.putString("content", content);
        b.putString("currentLine", content);
        b.putInt("cursorPosition", cursor);
        b.putBoolean("isMultiline", false);
        b.putBoolean("isReadonly", false);
        focusChanged(b);
    }

    /** Echo one typed character the way Orbit's edit fields do (the screen reader handles capital pitch, etc.). */
    void characterTyped(String ch, boolean isCapital) {
        Bundle b = typed(ch, "", isCapital);
        b.putString("resolvedWord", "");
        call("onCharacterTyped", s -> s.onCharacterTyped(b));
    }

    /** Echo a T9 candidate character for the named edit field (the screen reader speaks it per the user's settings). */
    void characterTyped(String ch, String fieldName) {
        Bundle b = typed(ch, fieldName, false);
        call("onCharacterTyped", s -> s.onCharacterTyped(b));
    }

    private static Bundle typed(String ch, String characterName, boolean isCapital) {
        Bundle b = new Bundle();
        b.putString("character", ch);
        b.putString("characterName", characterName);
        b.putBoolean("isCapital", isCapital);
        return b;
    }

    /**
     * Report an edit field's new content and caret. The screen reader keeps the field's previous content by name and
     * speaks what changed according to {@code changeType} (character_inserted, character_deleted, cursor_moved,
     * boundary_start, boundary_end, t9_mode_changed:NAME); a type it doesn't know just updates its copy silently.
     */
    void editBoxChanged(String fieldName, String content, int cursor, String changeType) {
        Bundle b = new Bundle();
        b.putString("widgetName", fieldName);
        b.putString("content", content);
        b.putInt("cursorPosition", cursor);
        b.putString("changeType", changeType);
        call("onEditBoxContentChanged", s -> s.onEditBoxContentChanged(b));
    }

    /** One of the screen reader's sounds (1 = error, 9 = character committed). */
    void playSound(int soundId) { call("playSound", s -> s.playSound(soundId)); }

    void vibrate(long millis) { call("vibrate", s -> s.vibrate(millis)); }

    /** The user's T9 character commit timeout in milliseconds (500 if the screen reader can't say). */
    int characterCommitTimeout() {
        int t = query(IScreenReaderService::getCharacterCommitTimeout, 500);
        return t > 0 ? t : 500;
    }

    /** The user's T9 commit feedback: 1 sound, 2 speak, 3 vibrate, 4 sound and speak, 5 vibrate and speak, else none. */
    int characterCommitFeedback() { return query(IScreenReaderService::getCharacterCommitFeedback, 1); }

    /** Send a raw command to the keypad/MCU over the screen reader's open sendData channel. */
    void sendData(byte command, byte[] payload) { call("sendData", s -> s.sendData(command, payload)); }

    void appOpened(String name) {
        Bundle b = new Bundle();
        b.putString("appName", name);
        b.putString("focusedElement", "");
        call("onAppOpened", s -> s.onAppOpened(b));
    }
}
