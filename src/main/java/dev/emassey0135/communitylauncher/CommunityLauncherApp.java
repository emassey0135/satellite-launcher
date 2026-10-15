package dev.emassey0135.communitylauncher;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Keeps a live receiver for the power button in place of Orbit's own launcher service, and runs {@link DeviceSetup}
 * at start-up and whenever an Orbit app is installed, updated or has a part re-enabled.
 *
 * <p>Orbit's screen reader broadcasts {@link #ACTION_POWER_OFF_REQUESTED} when the power button is pressed. That is an
 * implicit broadcast, which Android only delivers to a dynamically-registered receiver in a running process, so it is
 * registered here from the Application (the home process stays resident) rather than in the manifest. On the button
 * press it opens {@link PowerDialogActivity} over whatever app is in front — launched through root, because an app in
 * the background can't start an activity on its own.
 */
public final class CommunityLauncherApp extends Application {

    private static final String TAG = LauncherModel.TAG;
    static final String ACTION_POWER_OFF_REQUESTED = "com.orbit.screenreader.ACTION_POWER_OFF_REQUESTED";

    private final BroadcastReceiver powerButton = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            new Thread(() -> RootQuery.run(
                    "am start -n dev.emassey0135.communitylauncher/.PowerDialogActivity"), "power-dialog").start();
        }
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean orbitAppsUpdated;
    // An update installs several Orbit apps in a row, so check once they have all arrived.
    private final Runnable checkAfterPackageChanges = () -> {
        DeviceSetup.applyInBackground(this, orbitAppsUpdated);
        orbitAppsUpdated = false;
    };

    private final BroadcastReceiver orbitPackages = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            Uri data = intent.getData();
            String pkg = data == null ? null : data.getSchemeSpecificPart();
            if (pkg == null || !pkg.startsWith(DeviceSetup.ORBIT_PREFIX)) return;
            // A changed app (a part enabled or disabled) can't have new root grants; an installed or updated one can.
            if (!Intent.ACTION_PACKAGE_CHANGED.equals(intent.getAction())) orbitAppsUpdated = true;
            handler.removeCallbacks(checkAfterPackageChanges);
            handler.postDelayed(checkAfterPackageChanges, 3000);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        try {
            registerReceiver(powerButton, new IntentFilter(ACTION_POWER_OFF_REQUESTED));
            Log.i(TAG, "power button receiver registered");
        } catch (Exception e) {
            Log.e(TAG, "could not register power button receiver", e);
        }
        IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REPLACED);
        packages.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packages.addDataScheme("package");
        registerReceiver(orbitPackages, packages);
        DeviceSetup.applyInBackground(this, true);
        // As the home app, the launcher starts well before Android announces that the device has started, so carry on
        // with setup from here if it restarted the device; BootReceiver does too, in case it isn't the home app yet.
        SetupService.resumeIfRestarted(this);
    }
}
