package dev.emassey0135.satellite.launcher;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The releases of the Satellite hook and this launcher on GitHub, which GitHub Actions builds and signs with the release
 * key, and whether they are newer than what is installed. Each release has a version.json beside its APK, so that is
 * known without downloading the APK. Only a copy signed with the release key can be updated by a release: Android
 * won't replace a copy signed with another key, such as a build from someone's own computer.
 */
final class Updates {
    private static final String TAG = LauncherModel.TAG;
    /** The SHA-256 of the release signing certificate. */
    static final String CERT = "ea01d604414c28b2cdbfcec819cc6e444ba1e888f51a1f7506e62c0964b6c019";

    enum App {
        HOOK(SetupSteps.HOOK, "the Satellite hook", "satellite-hook", "satellite-hook.apk"),
        LAUNCHER(SetupSteps.LAUNCHER, "the Satellite Launcher", "satellite-launcher", "satellite-launcher.apk");

        final String pkg;
        /** What it is called when speaking of it. */
        final String name;
        private final String repo;
        final String apk;

        App(String pkg, String name, String repo, String apk) {
            this.pkg = pkg;
            this.name = name;
            this.repo = repo;
            this.apk = apk;
        }

        String apkUrl() { return latest() + apk; }

        private String latest() { return "https://github.com/emassey0135/" + repo + "/releases/latest/download/"; }
    }

    /** A release's version. */
    static final class Release {
        final App app;
        final long versionCode;
        final String versionName;

        Release(App app, long versionCode, String versionName) {
            this.app = app;
            this.versionCode = versionCode;
            this.versionName = versionName;
        }
    }

    private Updates() { }

    /**
     * The latest release of {@code app}, or null if it has none yet. It uses the network, so not on the main thread.
     *
     * @throws IOException if it can't be read
     */
    static Release latest(App app) throws IOException {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(app.latest() + "version.json").openConnection();
            c.setConnectTimeout(20_000);
            c.setReadTimeout(20_000);
            int status = c.getResponseCode();
            if (status == HttpURLConnection.HTTP_NOT_FOUND) return null;
            if (status != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + status);
            try (InputStream in = c.getInputStream()) {
                JSONObject o = new JSONObject(new String(Streams.readAll(in), StandardCharsets.UTF_8));
                return new Release(app, o.getLong("versionCode"), o.getString("versionName"));
            }
        } catch (JSONException e) {
            throw new IOException("version.json unreadable", e);
        }
    }

    /** Whether {@code app} is installed signed with the release key, so that a release can replace it. */
    static boolean updatable(PackageManager pm, App app) {
        try {
            return CERT.equals(signer(pm.getPackageInfo(app.pkg, PackageManager.GET_SIGNATURES)));
        } catch (Exception e) {
            return false;
        }
    }

    /** Whether {@code release} is newer than the copy of its app that is installed. */
    static boolean isNewer(PackageManager pm, Release release) {
        try {
            return release.versionCode > pm.getPackageInfo(release.app.pkg, 0).getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * The releases newer than what is installed, of the apps that releases can update; null if a release couldn't be
     * read. It uses the network, so not on the main thread.
     */
    static List<Release> available(PackageManager pm) {
        List<Release> newer = new ArrayList<>();
        for (App app : App.values()) {
            if (!updatable(pm, app)) continue;
            try {
                Release release = latest(app);
                if (release != null && isNewer(pm, release)) newer.add(release);
            } catch (IOException e) {
                Log.w(TAG, "latest release of " + app.name + " not read: " + e);
                return null;
            }
        }
        return newer;
    }

    /** The SHA-256 of the certificate an APK is signed with, as apksigner prints it; null if it has several or none. */
    static String signer(PackageInfo info) throws Exception {
        if (info.signatures == null || info.signatures.length != 1) return null;
        return SetupSteps.sha256(info.signatures[0].toByteArray());
    }
}
