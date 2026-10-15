package dev.emassey0135.communitylauncher;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayDeque;

/**
 * The power options ({@link PowerMenu}), shown as a dialog over whatever app is in front when the hardware power button
 * is pressed. The power button receiver in {@link CommunityLauncherApp} launches it in place of Orbit's own power
 * dialog. Its keys are the launcher's ({@link MenuKeys}); going back from the first list, or cancelling, closes it. It
 * opens in a task of its own (an empty task affinity in the manifest), so that closing it returns to the app it opened
 * over; in the launcher's task it would bring the home screen forward.
 *
 * <p>It takes the keypad under a name of its own ({@link #KEYPAD_NAME}) so dismissing it does not disturb the launcher's
 * own keypad registration; when it closes, the app underneath reclaims the keypad as usual.
 */
public final class PowerDialogActivity extends Activity implements ScreenReaderClient.Listener, PowerMenu.Host {

    /** Distinct from the launcher's own registration so unregistering here leaves the launcher's intact. */
    static final String KEYPAD_NAME = "dev.emassey0135.communitylauncher.power";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Frame> stack = new ArrayDeque<>();
    private ScreenReaderClient sr;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setFinishOnTouchOutside(true);
        stack.push(PowerMenu.frame(this));
        sr = new ScreenReaderClient(this, this, KEYPAD_NAME);
        sr.bind();
    }

    @Override public void onReady() {
        main.post(() -> {
            sr.register();
            stack.peek().requestTitle();
            stack.peek().announce(sr);
        });
    }

    @Override public void onKey(String keyName, int action, boolean longPress) {
        main.post(() -> {
            Frame frame = stack.peek();
            MenuKeys.Command command = MenuKeys.of(keyName, action, longPress);
            if (frame.navigate(command)) {
                frame.announce(sr);
                return;
            }
            switch (command) {
                case ACTIVATE: frame.activate(); break;
                case BACK: back(); break;
                case HOME: finish(); break;
                default: break;
            }
        });
    }

    private void back() {
        if (stack.size() == 1) { finish(); return; }
        stack.pop();
        stack.peek().requestTitle();
        stack.peek().announce(sr);
    }

    @Override public void push(Frame frame) {
        stack.push(frame);
        frame.announce(sr);
    }

    @Override public void cancel() { finish(); }

    @Override public ScreenReaderClient screenReader() { return sr; }

    @Override protected void onDestroy() {
        if (sr != null) sr.unbind();
        super.onDestroy();
    }
}
