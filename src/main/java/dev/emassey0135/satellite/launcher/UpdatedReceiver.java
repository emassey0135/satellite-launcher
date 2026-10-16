package dev.emassey0135.satellite.launcher;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

/**
 * Goes home when the launcher has just been updated while it is the home app. Installing the update stops the launcher,
 * and Android doesn't bring the home app back by itself: whatever was behind it comes to the front instead, such as
 * Backtalk's settings, left open since setup installed the step aside script.
 */
public final class UpdatedReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
        if (!new ComponentName(context, LauncherActivity.class).equals(SetupSteps.currentHome(context.getPackageManager()))) {
            return;
        }
        PendingResult pending = goAsync();
        // Through root, as an app in the background can't start an activity.
        new Thread(() -> {
            RootQuery.exec(SetupSteps.GO_HOME);
            pending.finish();
        }, "updated").start();
    }
}
