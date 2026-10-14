package dev.emassey0135.communitylauncher;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/**
 * The power options (Shut Down / Restart / Cancel), shown as a dialog over whatever app is in front when the hardware
 * power button is pressed. The power button receiver in {@link CommunityLauncherApp} launches it in place of Orbit's own
 * power dialog.
 *
 * <p>It takes the keypad under a name of its own ({@link #KEYPAD_NAME}) so dismissing it does not disturb the launcher's
 * own keypad registration; when it closes, the app underneath reclaims the keypad as usual.
 */
public final class PowerDialogActivity extends Activity implements ScreenReaderClient.Listener {

    /** Distinct from the launcher's own registration so unregistering here leaves the launcher's intact. */
    static final String KEYPAD_NAME = "dev.emassey0135.communitylauncher.power";

    private static final String[] LABELS = {"Shut Down", "Restart", "Cancel"};

    private final Handler main = new Handler(Looper.getMainLooper());
    private ScreenReaderClient sr;
    private int index;
    private boolean acted;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setFinishOnTouchOutside(true);
        sr = new ScreenReaderClient(this, this, KEYPAD_NAME);
        sr.bind();
    }

    @Override public void onReady() {
        main.post(() -> {
            sr.register();
            index = 0;
            sr.speak("Power options. " + LABELS[index]);
        });
    }

    /** Keys as in the launcher: Up and Down move, Right, OK and dot 8 choose, Left, BACK and dot 7 close. */
    @Override public void onKey(String keyName, int action, boolean longPress) {
        main.post(() -> {
            if (action == 1) {
                if (keyName.equals("DOT7")) finish();
                else if (keyName.equals("DOT8")) activate();
                return;
            }
            if (action != 0) return;
            switch (keyName) {
                case "UP":
                    index = (index + LABELS.length - 1) % LABELS.length;
                    sr.speak(LABELS[index]);
                    break;
                case "DOWN":
                    index = (index + 1) % LABELS.length;
                    sr.speak(LABELS[index]);
                    break;
                case "OK": case "SELECT": case "RIGHT":
                    activate();
                    break;
                case "LEFT": case "BACK":
                    finish();
                    break;
                default:
                    break;
            }
        });
    }

    private void activate() {
        switch (index) {
            case 0: power(false); break;
            case 1: power(true); break;
            default: finish(); break;
        }
    }

    /** Mirror Orbit: announce, tell the keypad MCU, then ask the system to shut down or reboot (root). */
    private void power(boolean restart) {
        if (acted) return;
        acted = true;
        sr.speak(restart ? "Restarting" : "Shutting down");
        sr.sendData((byte) 8, new byte[]{ (byte) (restart ? 4 : 3) });
        new Thread(() -> RootQuery.run(restart ? "svc power reboot" : "svc power shutdown"), "power").start();
    }

    @Override protected void onDestroy() {
        if (sr != null) sr.unbind();
        super.onDestroy();
    }
}
