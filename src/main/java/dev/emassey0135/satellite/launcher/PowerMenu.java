package dev.emassey0135.satellite.launcher;

import dev.emassey0135.satellite.launcher.ActionFrame.Action;

import java.util.Arrays;

/**
 * The power options, the same in the launcher's menu and in the dialog the power button opens: Shut Down and Restart,
 * each asked about again before it is done, and Cancel.
 */
final class PowerMenu {
    /** Where the power options are shown. */
    interface Host {
        void push(Frame frame);
        /** Leave the power options without doing anything. */
        void cancel();
        ScreenReaderClient screenReader();
    }

    private PowerMenu() { }

    static Frame frame(Host host) {
        return new ActionFrame("Power", Arrays.asList(
                new Action("Shut Down", () -> host.push(confirm(host, false))),
                new Action("Restart", () -> host.push(confirm(host, true))),
                new Action("Cancel", host::cancel)));
    }

    private static Frame confirm(Host host, boolean restart) {
        return new ActionFrame(restart ? "Restart the device?" : "Shut down the device?", Arrays.asList(
                new Action("No, cancel", host::cancel),
                new Action(restart ? "Yes, restart" : "Yes, shut down", () -> {
                    ScreenReaderClient sr = host.screenReader();
                    sr.speak(restart ? "Restarting" : "Shutting down");
                    Power.goInBackground(sr, restart);
                })));
    }
}
