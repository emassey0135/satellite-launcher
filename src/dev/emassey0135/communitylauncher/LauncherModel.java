package dev.emassey0135.communitylauncher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The menu tree the launcher shows, plus its persistence, default layout, and Plus-license gating.
 *
 * Nodes are apps, folders, or built-in actions. App labels resolve live from PackageManager, so the store keeps
 * only structure (folder names, action ids, ordering, placement) plus a hidden-package set. On load the tree is
 * reconciled with installed launchables: entries for removed apps drop out, newly installed apps appear.
 *
 * Plus gating covers only Orbit's own Plus apps: without an active Plus license those are hidden from view (not
 * removed), so they reappear if the license becomes active. The Android Apps folder is never gated, since BackTalk,
 * not Orbit, reads third-party apps. Apps are grouped by whether they self-voice (Orbit's own or the
 * orbit.self_voicing flag) into the main menu, versus the Android Apps folder for the rest.
 */
final class LauncherModel {
    static final String TAG = "CommunityLauncher";
    static final String ANDROID_APPS = "Android Apps";

    /**
     * Orbit packages that have a launcher activity but are services or the launcher itself, or that Orbit installs
     * before it lists them in its own launcher (Krakal); never shown as items.
     */
    static final Set<String> EXCLUDED = new HashSet<>(Arrays.asList(
            "com.orbit.homelauncher", "com.orbit.liblouisservice", "com.orbit.accessibilityserviceapp",
            "com.orbit.screenreader", "com.orbit.krakal"));

    /** Apps Orbit's launcher shows only with an active Plus license. */
    static final Set<String> PLUS_APPS = new HashSet<>(Arrays.asList(
            "com.orbit.bookreader", "com.orbit.braillereader", "com.orbit.internetradio",
            "com.orbit.podcast", "com.orbit.voicerecorder", "com.orbit.appcatalog"));

    // Orbit's default grouping (from its launcher's level filters), in display order.
    private static final String[] TOP_APPS = {
            "com.orbit.filemanager", "com.orbit.bookreader", "com.orbit.braillereader", "com.orbit.editor",
            "com.orbit.mediaplayer", "com.orbit.internetradio", "com.orbit.podcast", "com.orbit.voicerecorder"};
    private static final String[] CLOCK_APPS = {
            "com.orbit.alarmclock", "com.orbit.worldclock", "com.orbit.stopwatch", "com.orbit.timer"};
    private static final String[] TOOLS_APPS = {
            "com.orbit.calculator", "com.orbit.calendar", "com.orbit.contacts", "com.orbit.weather",
            "com.orbit.updater", "com.orbit.appcatalog", "com.orbit.remotemode"};
    private static final String[] SETTINGS_APPS = {
            "com.orbit.settings", "com.orbit.voicemanager", "com.orbit.googlevoicemanager"};

    /** A menu entry: an app (pkg), a folder (folderName + children), or a built-in action (actionId + actionLabel). */
    static final class Node {
        String pkg;
        String folderName;
        String actionId;
        String actionLabel;
        final List<Node> children = new ArrayList<>();
        transient String appLabel;

        boolean isFolder() { return folderName != null; }
        boolean isApp() { return pkg != null; }
        boolean isAction() { return actionId != null; }

        static Node app(String pkg) { Node n = new Node(); n.pkg = pkg; return n; }
        static Node folder(String name) { Node n = new Node(); n.folderName = name; return n; }
        static Node action(String id, String label) { Node n = new Node(); n.actionId = id; n.actionLabel = label; return n; }
    }

    final Context context;
    final Node root = Node.folder("Home");
    final Set<String> hidden = new HashSet<>();
    private final File file;
    private final SharedPreferences prefs;
    boolean licensed;
    /** Whether this device has a Plus tier at all: the Orbit Player (phone keypad) is sold in one version with every app. */
    boolean plusTier;

    LauncherModel(Context context) {
        this.context = context;
        this.file = new File(context.getFilesDir(), "menu.json");
        this.prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE);
        this.licensed = prefs.getBoolean("licensed", true); // optimistic until a read says otherwise
        this.plusTier = !prefs.getBoolean("phone_keypad", false);
    }

    String labelOf(Node n) {
        if (n.isFolder()) return n.folderName;
        if (n.isAction()) return n.actionLabel;
        return n.appLabel != null ? n.appLabel : n.pkg;
    }

    /**
     * Whether a node is shown given the current license state. Only Orbit's own Plus apps need a Plus license; the
     * Android Apps folder is always shown, since BackTalk (not Orbit's screen reader) reads third-party apps, so Plus
     * is not effectively buying anything for them.
     */
    boolean isVisible(Node n) {
        if (!plusTier) return !(n.isAction() && "plus".equals(n.actionId)); // no Plus tier: every app, no license check
        if (licensed) return true;
        return !(n.isApp() && PLUS_APPS.contains(n.pkg));
    }

    /** Self-voicing apps (Orbit's own, or any declaring the orbit.self_voicing manifest flag) belong in the main menu. */
    boolean isSelfVoicing(String pkg) {
        if (pkg.startsWith("com.orbit.")) return true;
        try {
            ApplicationInfo ai = context.getPackageManager().getApplicationInfo(pkg, PackageManager.GET_META_DATA);
            return ai.metaData != null && ai.metaData.getBoolean("orbit.self_voicing", false);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Record which keypad the device has (known from its key codes); returns whether that changed what is shown. */
    boolean setPhoneKeypad(boolean phone) {
        if (plusTier == !phone) return false;
        plusTier = !phone;
        prefs.edit().putBoolean("phone_keypad", phone).apply();
        return !licensed;
    }

    void setLicensed(boolean value) {
        if (licensed == value) return;
        licensed = value;
        prefs.edit().putBoolean("licensed", value).apply();
    }

    // ---- load / reconcile / save ------------------------------------------------

    void load() {
        Map<String, String> installed = launchableApps();
        boolean loaded = false;
        try {
            if (file.exists()) {
                JSONObject o = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                JSONArray h = o.optJSONArray("hidden");
                if (h != null) for (int i = 0; i < h.length(); i++) hidden.add(h.getString(i));
                JSONArray t = o.optJSONArray("tree");
                if (t != null) { readInto(root, t); loaded = true; }
            }
        } catch (Exception e) {
            Log.e(TAG, "menu load failed, rebuilding", e);
            root.children.clear();
        }
        if (!loaded) buildDefault(installed);
        reconcile(installed);
        ensureBuiltins();
        resolveLabels(root, installed);
        save();
    }

    /**
     * Pick up apps installed, removed, enabled or disabled since the menu was loaded (e.g. from the Play Store). Folders
     * are kept as the same objects, so open menus stay valid.
     */
    void refreshApps() {
        Map<String, String> installed = launchableApps();
        reconcile(installed);
        resolveLabels(root, installed);
        save();
    }

    private void readInto(Node parent, JSONArray arr) throws Exception {
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String t = o.getString("t");
            if ("f".equals(t)) {
                Node f = Node.folder(o.getString("n"));
                readInto(f, o.optJSONArray("c") == null ? new JSONArray() : o.getJSONArray("c"));
                parent.children.add(f);
            } else if ("x".equals(t)) {
                parent.children.add(Node.action(o.getString("id"), o.getString("n")));
            } else {
                parent.children.add(Node.app(o.getString("p")));
            }
        }
    }

    /** Build Orbit's default menu: top-level apps, then Clock, Tools, Settings, Android Apps and Help folders. */
    private void buildDefault(Map<String, String> installed) {
        root.children.clear();
        Set<String> used = new HashSet<>();

        addApps(root, TOP_APPS, installed, used);

        root.children.add(Node.action("recents", "Recent Apps"));
        root.children.add(Node.action("notifications", "Notifications"));

        Node clock = Node.folder("Clock and Time Management");
        clock.children.add(Node.action("time", "Check Current Time"));
        clock.children.add(Node.action("date", "Check Current Date"));
        addApps(clock, CLOCK_APPS, installed, used);
        root.children.add(clock);

        Node tools = Node.folder("Tools");
        tools.children.add(Node.action("battery", "Check Battery Status"));
        addApps(tools, TOOLS_APPS, installed, used);
        tools.children.add(Node.action("format", "Format SD Card"));
        root.children.add(tools);

        Node settings = Node.folder("Settings");
        if (installed.containsKey("com.orbit.settings")) { settings.children.add(Node.app("com.orbit.settings")); used.add("com.orbit.settings"); }
        settings.children.add(Node.action("sr_settings", "Screen Reader Settings"));
        for (String p : new String[]{"com.orbit.voicemanager", "com.orbit.googlevoicemanager"})
            if (installed.containsKey(p)) { settings.children.add(Node.app(p)); used.add(p); }
        root.children.add(settings);

        Node android = Node.folder(ANDROID_APPS);
        for (Map.Entry<String, String> e : installed.entrySet()) {
            String pkg = e.getKey();
            if (pkg.equals(context.getPackageName()) || used.contains(pkg) || EXCLUDED.contains(pkg)) continue;
            if (isSelfVoicing(pkg)) { root.children.add(Node.app(pkg)); used.add(pkg); }
            else { android.children.add(Node.app(pkg)); used.add(pkg); }
        }
        root.children.add(android);

        Node help = Node.folder("Help");
        help.children.add(Node.action("userguide", "User Guide"));
        help.children.add(Node.action("about", "About Device"));
        help.children.add(Node.action("plus", "Check for Plus License"));
        root.children.add(help);

        root.children.add(Node.action("power", "Power"));
    }

    private void addApps(Node folder, String[] pkgs, Map<String, String> installed, Set<String> used) {
        for (String p : pkgs) if (installed.containsKey(p)) { folder.children.add(Node.app(p)); used.add(p); }
    }

    private void reconcile(Map<String, String> installed) {
        Set<String> placed = new HashSet<>();
        pruneAndCollect(root, installed.keySet(), placed);
        Node androidApps = null;
        for (Node n : root.children) if (n.isFolder() && ANDROID_APPS.equals(n.folderName)) androidApps = n;
        for (String pkg : installed.keySet()) {
            if (pkg.equals(context.getPackageName()) || placed.contains(pkg) || hidden.contains(pkg) || EXCLUDED.contains(pkg)) continue;
            if (isSelfVoicing(pkg)) {
                root.children.add(Node.app(pkg));
            } else {
                if (androidApps == null) { androidApps = Node.folder(ANDROID_APPS); root.children.add(androidApps); }
                androidApps.children.add(Node.app(pkg));
            }
        }
    }

    private void pruneAndCollect(Node folder, Set<String> installed, Set<String> placed) {
        for (int i = folder.children.size() - 1; i >= 0; i--) {
            Node n = folder.children.get(i);
            if (n.isFolder()) {
                pruneAndCollect(n, installed, placed);
            } else if (n.isApp() && (!installed.contains(n.pkg) || hidden.contains(n.pkg) || placed.contains(n.pkg) || EXCLUDED.contains(n.pkg))) {
                folder.children.remove(i);
            } else if (n.isApp()) {
                placed.add(n.pkg);
            }
        }
    }

    /**
     * Make sure the built-in action items exist, so layouts saved before a feature was added pick it up without a
     * reset. Missing actions are added to their home folder (when it still exists); Power is appended last at the
     * root, so it defaults to the bottom of the menu. Actions the user merely moved are left where they are.
     */
    private void ensureBuiltins() {
        Set<String> present = new HashSet<>();
        collectActionIds(root, present);
        // id -> {label, home folder name, or null for the root}, in the order they should be added.
        String[][] builtins = {
                {"time", "Check Current Time", "Clock and Time Management"},
                {"date", "Check Current Date", "Clock and Time Management"},
                {"battery", "Check Battery Status", "Tools"},
                {"format", "Format SD Card", "Tools"},
                {"sr_settings", "Screen Reader Settings", "Settings"},
                {"userguide", "User Guide", "Help"},
                {"about", "About Device", "Help"},
                {"plus", "Check for Plus License", "Help"},
                {"recents", "Recent Apps", null},
                {"notifications", "Notifications", null},
                {"power", "Power", null},
        };
        for (String[] b : builtins) {
            if (present.contains(b[0])) continue;
            Node target = b[2] == null ? root : findFolder(root, b[2]);
            if (target == null) target = root;
            target.children.add(Node.action(b[0], b[1]));
        }
    }

    private void collectActionIds(Node folder, Set<String> out) {
        for (Node n : folder.children) {
            if (n.isAction()) out.add(n.actionId);
            else if (n.isFolder()) collectActionIds(n, out);
        }
    }

    private Node findFolder(Node folder, String name) {
        for (Node n : folder.children) if (n.isFolder()) {
            if (name.equals(n.folderName)) return n;
            Node f = findFolder(n, name);
            if (f != null) return f;
        }
        return null;
    }

    private void resolveLabels(Node folder, Map<String, String> installed) {
        for (Node n : folder.children) {
            if (n.isFolder()) resolveLabels(n, installed);
            else if (n.isApp()) n.appLabel = installed.getOrDefault(n.pkg, n.pkg);
        }
    }

    void save() {
        try {
            JSONObject o = new JSONObject();
            o.put("version", 1);
            o.put("tree", writeNodes(root.children));
            o.put("hidden", new JSONArray(hidden));
            Files.write(file.toPath(), o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.e(TAG, "menu save failed", e);
        }
    }

    private JSONArray writeNodes(List<Node> nodes) throws Exception {
        JSONArray arr = new JSONArray();
        for (Node n : nodes) {
            JSONObject o = new JSONObject();
            if (n.isFolder()) { o.put("t", "f"); o.put("n", n.folderName); o.put("c", writeNodes(n.children)); }
            else if (n.isAction()) { o.put("t", "x"); o.put("id", n.actionId); o.put("n", n.actionLabel); }
            else { o.put("t", "a"); o.put("p", n.pkg); }
            arr.put(o);
        }
        return arr;
    }

    // ---- queries & mutations ----------------------------------------------------

    private Map<String, String> launchableApps() {
        PackageManager pm = context.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> infos = pm.queryIntentActivities(main, 0);
        List<ResolveInfo> sorted = new ArrayList<>(infos);
        sorted.sort((a, b) -> a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString()));
        Map<String, String> out = new LinkedHashMap<>();
        for (ResolveInfo ri : sorted) {
            String pkg = ri.activityInfo.packageName;
            if (!out.containsKey(pkg)) out.put(pkg, ri.loadLabel(pm).toString());
        }
        return out;
    }

    String labelForPackage(String pkg) {
        try {
            PackageManager pm = context.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) { return pkg; }
    }

    /** Folders that {@code node} may move into: all folders except itself, its descendants, and its current parent. */
    Map<String, Node> moveTargets(Node node, Node currentParent) {
        Map<String, Node> out = new LinkedHashMap<>();
        if (currentParent != root) out.put("Home", root);
        collectFolders(root, "Home", node, currentParent, out);
        return out;
    }

    private void collectFolders(Node folder, String path, Node exclude, Node currentParent, Map<String, Node> out) {
        for (Node n : folder.children) {
            if (!n.isFolder() || n == exclude) continue;
            String p = path + " / " + n.folderName;
            if (n != currentParent) out.put(p, n);
            collectFolders(n, p, exclude, currentParent, out);
        }
    }

    Node parentOf(Node target) { return findParent(root, target); }

    private Node findParent(Node folder, Node target) {
        if (folder.children.contains(target)) return folder;
        for (Node n : folder.children) if (n.isFolder()) {
            Node p = findParent(n, target);
            if (p != null) return p;
        }
        return null;
    }

    boolean removeFromTree(Node target) { return removeFrom(root, target); }

    private boolean removeFrom(Node folder, Node target) {
        if (folder.children.remove(target)) return true;
        for (Node n : folder.children) if (n.isFolder() && removeFrom(n, target)) return true;
        return false;
    }

    /** Swap a node with its neighbour in its parent; returns true if it moved. */
    boolean reorder(Node node, int delta) {
        Node parent = parentOf(node);
        if (parent == null) return false;
        int i = parent.children.indexOf(node);
        int j = i + delta;
        if (j < 0 || j >= parent.children.size()) return false;
        parent.children.set(i, parent.children.get(j));
        parent.children.set(j, node);
        save();
        return true;
    }

    /** Hidden packages as pkg -> label, for an un-hide screen. */
    Map<String, String> hiddenApps() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String pkg : hidden) out.put(pkg, labelForPackage(pkg));
        return out;
    }

    void unhide(String pkg) {
        if (!hidden.remove(pkg)) return;
        Node app = Node.app(pkg);
        app.appLabel = labelForPackage(pkg);
        if (pkg.startsWith("com.orbit.")) {
            root.children.add(app);
        } else {
            Node android = null;
            for (Node n : root.children) if (n.isFolder() && ANDROID_APPS.equals(n.folderName)) android = n;
            if (android == null) { android = Node.folder(ANDROID_APPS); root.children.add(android); }
            android.children.add(app);
        }
        save();
    }
}
