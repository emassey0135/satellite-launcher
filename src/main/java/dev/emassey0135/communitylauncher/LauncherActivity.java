package dev.emassey0135.communitylauncher;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;

import dev.emassey0135.communitylauncher.LauncherModel.Node;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The home screen. A stack of {@link Frame}s drives navigation; the top frame handles keys. Browse frames show a
 * folder's visible contents (Plus gating applied); action frames show a flat list of operations (context menus,
 * move targets, hidden-app lists). Keys follow the Orbit Speak user guide.
 */
public class LauncherActivity extends Activity implements ScreenReaderClient.Listener {

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Frame> stack = new ArrayDeque<>();
    private final LicenseChecker license = new LicenseChecker();
    private LauncherModel model;
    private ScreenReaderClient sr;
    private LiblouisClient braille;
    private TextView view;
    private boolean started;
    private boolean inFront; // between onResume and onStop: HOME pressed now means "go to the top"

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        view = new TextView(this);
        view.setGravity(Gravity.CENTER);
        view.setTextColor(Color.WHITE);
        view.setBackgroundColor(Color.BLACK);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        view.setPadding(32, 32, 32, 32);
        setContentView(view);

        model = new LauncherModel(this);
        stack.push(new BrowseFrame(model.root));
        render(false);
        model.load(() -> render(true));
        rescheduleOrbitAgendas();

        sr = new ScreenReaderClient(this, this);
        sr.setForeground(false); // until the window has focus: see onWindowFocusChanged
        sr.bind();
        braille = new LiblouisClient(this);
        braille.bind();
    }

    @Override protected void onResume() {
        super.onResume();
        inFront = true;
        model.refreshApps(() -> render(false)); // apps may have been installed, removed, enabled or disabled meanwhile
        if (started) refreshLicense(false);    // Plus may have been bought or have run out meanwhile
        if (sr != null) sr.register();
        if (frame() != null) frame().requestTitle(); // returning from an app: re-announce as a focus change
        render(true);
    }

    /**
     * Hold the keypad only while the launcher's window has focus. A system window over it (an app-not-responding or
     * crash dialog, a SystemUI pop-up) takes focus without pausing the launcher, and Backtalk reads and drives that
     * window, so the keypad must stay with Backtalk until it closes.
     */
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (sr != null) sr.setForeground(hasFocus);
    }

    @Override protected void onStop() {
        inFront = false;
        super.onStop();
    }

    /**
     * HOME while the launcher is already showing (the Orbit Player's HOME key, which Orbit turns into Android's home)
     * goes to the top level, like the Z chord. Coming back from an app keeps the place. onResume, which always follows,
     * announces the result.
     */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (inFront && intent.hasCategory(Intent.CATEGORY_HOME)) resetToTop();
    }

    @Override protected void onDestroy() {
        if (sr != null) sr.unbind();
        if (braille != null) braille.unbind();
        super.onDestroy();
    }

    @Override public void onBackPressed() { back(); }

    // ---- ScreenReaderClient.Listener -------------------------------------------

    @Override public void onReady() {
        main.post(() -> {
            if (!started) {
                started = true;
                sr.appOpened("Launcher");
                refreshLicense(false);
            }
            render(true);
        });
    }

    @Override public void onKey(String keyName, int action, boolean longPress) {
        main.post(() -> handleKey(keyName, action, longPress));
    }

    @Override public void onSettingsChanged() {
        if (braille != null) braille.refresh();
    }

    private void handleKey(String keyName, int action, boolean longPress) {
        // A window over the launcher (a system dialog) has the keypad; a key that was on its way when it opened isn't ours.
        if (!hasWindowFocus() || !model.isLoaded()) return;
        // The first key from a phone keypad (the Orbit Player, which has no Plus tier) shows the Plus apps.
        if (model.setPhoneKeypad(sr.isPhoneKeypad())) render(false);
        if (frame() instanceof TextEntryFrame) { ((TextEntryFrame) frame()).key(keyName, action, longPress); return; }
        // Orbit Speak keys: Up/Down move, Left back, Right/Select/Dot8 activate, Dot7 back,
        // Space+M context menu, full-cell / Z chord to Home.
        // Orbit Player: the same arrows and OK, plus BACK = back and MENU = context menu (HOME: see onNewIntent).
        if (action == 0) {
            switch (keyName) {
                case "UP": if (longPress) frame().moveTo(0); else frame().move(-1); render(true); return;
                case "DOWN": if (longPress) frame().moveTo(frame().size() - 1); else frame().move(1); render(true); return;
                case "LEFT": back(); return;
                case "RIGHT": case "OK": case "SELECT": frame().activate(); return;
                case "BACK": if (!longPress) back(); return;
                default: return;
            }
        }
        if (action == 1) {
            if (keyName.equals("DOT7")) back();
            else if (keyName.equals("DOT8")) frame().activate();
            else if (keyName.equals("MENU")) frame().menu();
            return;
        }
        if (action == 3) {
            if (keyName.equals("SPACE_DOT1_DOT3_DOT4")) frame().menu();
            else if (keyName.equals("SPACE_DOT1_DOT2_DOT3_DOT5")) openRecents(); // Space+r: recents, as in Backtalk
            else if (keyName.equals("SPACE_DOT1_DOT3_DOT4_DOT5")) openNotifications(); // Space+n: notification shade
            else if (keyName.equals("SPACE_DOT1_DOT3_DOT5_DOT6")
                    || keyName.equals("SPACE_DOT1_DOT2_DOT3_DOT4_DOT5_DOT6")) goHome();
        }
    }

    // ---- frame stack & rendering -----------------------------------------------

    private Frame frame() { return stack.peek(); }

    private void push(Frame f) { stack.push(f); render(true); }

    private void back() {
        if (stack.size() > 1) { stack.pop(); frame().requestTitle(); render(true); }
        else sr.speak("Home");
    }

    private void goHome() {
        resetToTop();
        render(true);
    }

    private void resetToTop() {
        while (stack.size() > 1) stack.pop();
        frame().moveTo(0);
        frame().requestTitle();
    }

    /** Pop any action/context frames so the top is the browse frame they were opened from. */
    private void popToBrowse() {
        while (stack.size() > 1 && !(frame() instanceof BrowseFrame)) stack.pop();
    }

    private void returnToBrowse(Node select) {
        popToBrowse();
        if (select != null && frame() instanceof BrowseFrame) ((BrowseFrame) frame()).selectNode(select);
        render(true);
    }

    // Screen reader sounds used by the phone keypad text field.
    private static final int SOUND_ERROR = 1, SOUND_COMMIT = 9;

    // Widget roles understood by the screen reader (subset we use).
    private static final int ROLE_BUTTON = 1, ROLE_LIST_BOX = 5, ROLE_EDIT_BOX = 6;

    private void render(boolean speak) {
        if (!model.isLoaded()) { view.setText("Loading"); return; } // announced once loaded
        Frame f = frame();
        String item = f.size() == 0 ? "Empty" : f.labelAt(f.index());
        view.setText(f.title() + "\n\n" + item + (!f.isText() && f.size() > 0 ? "\n\n" + (f.index() + 1) + " of " + f.size() : ""));
        if (!speak || sr == null || !sr.isReady()) return;
        if (f.isText()) {
            if (f.consumeTitleAnnouncement()) {
                TextEntryFrame t = (TextEntryFrame) f;
                sr.focusEdit(t.title(), t.display(), t.display().length());
                // Phone keypad edits are reported as changes against the screen reader's own copy of the field (kept
                // by name), so start it from this field's text. An unknown change type updates it without speaking.
                if (sr.isPhoneKeypad()) sr.editBoxChanged(t.title(), t.display(), t.display().length(), "field_opened");
            }
            return;
        }
        // A list: new frame -> focus event (announces list name + item); a move -> item-changed event.
        // The screen reader renders role, item and "N of M" per the user's own verbosity settings, and this
        // feeds its model so the review commands read our items.
        announceList(f, f.consumeTitleAnnouncement());
    }

    private void announceList(Frame f, boolean asFocus) {
        Bundle b = new Bundle();
        b.putString("name", f.title());
        b.putInt("roleId", ROLE_LIST_BOX);
        b.putInt("itemCount", f.size());
        b.putInt("selectedIndex", f.size() == 0 ? -1 : f.index());
        if (f.size() > 0) b.putString("selectedItem", f.labelAt(f.index()));
        if (asFocus) sr.focusChanged(b); else sr.itemChanged(b);
    }

    private void toast(String msg) { if (sr != null) sr.speak(msg); }

    private void rescheduleOrbitAgendas() {
        sendOrbitBroadcast("com.orbit.ALARM_DATA", "com.orbit.alarmclock");
        sendOrbitBroadcast("com.orbit.CALENDAR_DATA", "com.orbit.calendar");
    }

    private void sendOrbitBroadcast(String action, String pkg) {
        try { sendBroadcast(new Intent(action).setPackage(pkg).putExtra("isDeviceRestarted", true)); }
        catch (Exception ignored) { }
    }

    // ---- launching & actions ----------------------------------------------------

    private void launch(Node app) {
        Intent i = getPackageManager().getLaunchIntentForPackage(app.pkg);
        if (i == null) { toast("Cannot open " + model.labelOf(app)); return; }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        sr.speak("Opening " + model.labelOf(app));
        try { startActivity(i); } catch (Exception e) { toast("Could not open " + model.labelOf(app)); }
    }

    private void launchComponent(String pkg, String cls, String name) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(pkg, cls))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            sr.speak("Opening " + name);
            startActivity(i);
        } catch (Exception e) { toast("Could not open " + name); }
    }

    private void doAction(String id) {
        switch (id) {
            case "time": {
                String fmt = DateFormat.is24HourFormat(this) ? "H:mm" : "h:mm a";
                sr.speak(new SimpleDateFormat(fmt, Locale.getDefault()).format(new Date()));
                break;
            }
            case "date":
                sr.speak(new SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(new Date()));
                break;
            case "battery":
                speakInBackground(() -> {
                    int b = sr.battery();
                    return b < 0 ? "Battery status unavailable" : "Battery " + b + " percent";
                });
                break;
            case "sr_settings":
                launchComponent("com.orbit.screenreader", "com.orbit.screenreader.settings.SettingsActivity",
                        "Screen Reader Settings");
                break;
            case "about":
                speakInBackground(() -> {
                    int b = sr.battery();
                    String fw = sr.firmware();
                    String serial = sr.serial();
                    StringBuilder sb = new StringBuilder("About device. ");
                    if (serial != null) sb.append("Serial ").append(serial).append(". ");
                    if (fw != null) sb.append("Firmware ").append(fw).append(". ");
                    if (b >= 0) sb.append("Battery ").append(b).append(" percent.");
                    return sb.toString();
                });
                break;
            case "plus":
                refreshLicense(true);
                break;
            case "recents":
                openRecents();
                break;
            case "notifications":
                openNotifications();
                break;
            case "power":
                openPowerMenu();
                break;
            case "format":
                openFormatConfirm();
                break;
            case "userguide":
                openUserGuide();
                break;
            default:
                toast("Not available");
        }
    }

    /** Speak what {@code text} returns, getting it off the main thread: the screen reader may ask the keypad for it. */
    private void speakInBackground(Supplier<String> text) {
        new Thread(() -> {
            String s = text.get();
            main.post(() -> sr.speak(s));
        }, "device-info").start();
    }

    /**
     * Open the stock Android app switcher (Overview), where Backtalk reads recent apps and their close action. The
     * launcher is not an accessibility service, so it can't call performGlobalAction(RECENTS); instead it presses the
     * recents key via root, which opens the same Overview.
     */
    private void openRecents() {
        new Thread(() -> RootQuery.run("input keyevent 187"), "recents").start(); // KEYCODE_APP_SWITCH
    }

    /** Open the notification shade, where Backtalk reads notifications and their actions (e.g. dismiss, reply). */
    private void openNotifications() {
        new Thread(() -> RootQuery.run("cmd statusbar expand-notifications"), "notifications").start();
    }

    // ---- user guide -------------------------------------------------------------

    /**
     * Open the Orbit user guide (from Orbit's installed APK) in the Book Reader, as Orbit's launcher does. It is copied
     * out of the APK off the main thread.
     */
    private void openUserGuide() {
        String lang = Locale.getDefault().getLanguage().toLowerCase(Locale.ROOT);
        File out = new File(getCacheDir(), GuideProvider.FILE);
        new Thread(() -> {
            String problem;
            try {
                problem = extractUserGuide(lang, out) ? null : "User guide not found";
            } catch (Exception e) {
                problem = "Could not open the user guide";
            }
            String error = problem;
            main.post(() -> { if (error != null) toast(error); else showUserGuide(out); });
        }, "user-guide").start();
    }

    /** Copy the guide in the device's language, else English, to {@code out}; false if Orbit's launcher has neither. */
    private boolean extractUserGuide(String lang, File out) throws Exception {
        ApplicationInfo ai = getPackageManager().getApplicationInfo("com.orbit.homelauncher", 0);
        try (ZipFile zip = new ZipFile(ai.sourceDir)) {
            ZipEntry e = zip.getEntry("assets/userGuide/user_guide_" + lang + ".docx");
            if (e == null) e = zip.getEntry("assets/userGuide/user_guide_en.docx");
            if (e == null) return false;
            try (InputStream in = zip.getInputStream(e); OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            return true;
        }
    }

    private void showUserGuide(File file) {
        Uri uri = Uri.parse("content://" + getPackageName() + ".guide/" + GuideProvider.FILE);
        Intent i = new Intent(Intent.ACTION_VIEW).setPackage("com.orbit.bookreader")
                .setDataAndType(uri, "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("path", file.getPath())
                .putExtra("skip_db_entry", true)
                .putExtra("from_where", "CommunityLauncher");
        sr.speak("Opening User Guide");
        try { startActivity(i); } catch (Exception e) { toast("Could not open the user guide"); }
    }

    // ---- format SD card ---------------------------------------------------------

    private void openFormatConfirm() {
        List<Action> actions = new ArrayList<>();
        actions.add(new Action("No, cancel", this::popToBrowse));
        actions.add(new Action("Yes, erase and format the SD card", this::formatSdCard));
        push(new ActionFrame("Format SD card? This erases everything on it", actions));
    }

    /** Format the SD card as portable storage, the way Orbit does: sm partition <adoptable disk> public. */
    private void formatSdCard() {
        popToBrowse();
        sr.speak("Please wait, formatting the SD card");
        new Thread(() -> {
            String disks = RootQuery.run("sm list-disks adoptable");
            String disk = null;
            if (disks != null) for (String line : disks.split("\n")) if (line.startsWith("disk:")) { disk = line.trim(); break; }
            final String target = disk;
            if (target == null) { main.post(() -> toast("No SD card found")); return; }
            String result = RootQuery.run("sm partition " + target + " public");
            String lower = result == null ? "" : result.toLowerCase(Locale.ROOT);
            boolean ok = result != null && !lower.contains("error") && !lower.contains("exception");
            main.post(() -> toast(ok ? "SD card formatted" : "Could not format the SD card"));
        }, "format-sd").start();
    }

    // ---- power ------------------------------------------------------------------

    private void openPowerMenu() {
        List<Action> actions = new ArrayList<>();
        actions.add(new Action("Shut Down", () -> confirmPower("Shut down the device", false)));
        actions.add(new Action("Restart", () -> confirmPower("Restart the device", true)));
        actions.add(new Action("Cancel", this::popToBrowse));
        push(new ActionFrame("Power", actions));
    }

    private void confirmPower(String prompt, boolean restart) {
        List<Action> actions = new ArrayList<>();
        actions.add(new Action("No, cancel", this::popToBrowse));
        actions.add(new Action("Yes, " + (restart ? "restart" : "shut down"), () -> power(restart)));
        push(new ActionFrame(prompt + "?", actions));
    }

    /** Mirror Orbit: tell the keypad MCU, announce, then ask the system to shut down or reboot (root). */
    private void power(boolean restart) {
        sr.speak(restart ? "Restarting" : "Shutting down");
        sr.sendData((byte) 8, new byte[]{ (byte) (restart ? 4 : 3) });
        new Thread(() -> RootQuery.run(restart ? "svc power reboot" : "svc power shutdown"), "power").start();
    }

    private void refreshLicense(boolean announce) {
        if (announce) sr.speak("Checking Plus license");
        license.check((known, active) -> {
            boolean changed = known && active != model.licensed;
            if (known) model.setLicensed(active);
            if (changed) goHome();
            if (announce) {
                if (!known) toast("Could not check the Plus license");
                else toast(active ? "Plus license is active" : "Plus license is not active");
            } else if (changed) {
                render(true);
            }
        });
    }

    // ---- context menus & reorganisation ----------------------------------------

    private void openContextMenu(Node node, Node container) {
        List<Action> actions = new ArrayList<>();
        if (node.isFolder()) actions.add(new Action("Open", () -> returnToBrowseEntering(node)));
        else if (node.isApp()) actions.add(new Action("Launch", () -> { popToBrowse(); launch(node); }));
        else actions.add(new Action("Activate", () -> { popToBrowse(); doAction(node.actionId); }));

        actions.add(new Action("Move up", () -> { if (model.reorder(node, -1)) returnToBrowse(node); else { popToBrowse(); toast("Already first"); } }));
        actions.add(new Action("Move down", () -> { if (model.reorder(node, 1)) returnToBrowse(node); else { popToBrowse(); toast("Already last"); } }));
        actions.add(new Action("Move to folder", () -> openMoveTargets(node, container)));

        if (node.isFolder()) {
            actions.add(new Action("Rename folder", () -> promptRename(node)));
            actions.add(new Action("New folder inside", () -> promptNewFolder(node, node)));
            actions.add(new Action("Delete folder", () -> deleteFolder(node)));
        } else if (node.isApp()) {
            actions.add(new Action("Hide from menu", () -> {
                model.hidden.add(node.pkg);
                model.removeFromTree(node);
                model.save();
                popToBrowse();
                render(true);
                toast(model.labelOf(node) + " hidden");
            }));
        }
        actions.add(new Action("New folder here", () -> promptNewFolder(container, null)));
        actions.add(new Action("Show hidden apps", this::openHiddenApps));
        push(new ActionFrame("Options for " + model.labelOf(node), actions));
    }

    private void openContainerMenu(Node container) {
        List<Action> actions = new ArrayList<>();
        actions.add(new Action("New folder here", () -> promptNewFolder(container, null)));
        actions.add(new Action("Show hidden apps", this::openHiddenApps));
        push(new ActionFrame("Folder options", actions));
    }

    private void openMoveTargets(Node node, Node container) {
        Map<String, Node> folders = model.moveTargets(node, container);
        List<Action> actions = new ArrayList<>();
        if (folders.isEmpty()) actions.add(new Action("No other folders", () -> popToBrowse()));
        for (Map.Entry<String, Node> e : folders.entrySet()) {
            Node dest = e.getValue();
            actions.add(new Action(e.getKey(), () -> {
                if (model.removeFromTree(node)) dest.children.add(node);
                model.save();
                returnToBrowse(null);
                toast("Moved to " + (dest == model.root ? "Home" : dest.folderName));
            }));
        }
        push(new ActionFrame("Move " + model.labelOf(node) + " to", actions));
    }

    private void openHiddenApps() {
        Map<String, String> hidden = model.hiddenApps();
        List<Action> actions = new ArrayList<>();
        if (hidden.isEmpty()) {
            actions.add(new Action("No hidden apps", () -> popToBrowse()));
        } else {
            for (Map.Entry<String, String> e : hidden.entrySet()) {
                String pkg = e.getKey();
                actions.add(new Action("Show " + e.getValue(), () -> {
                    model.unhide(pkg);
                    returnToBrowse(null);
                    toast(e.getValue() + " shown");
                }));
            }
        }
        push(new ActionFrame("Hidden apps", actions));
    }

    private void promptNewFolder(Node container, Node enterAfter) {
        promptName("New folder name", name -> {
            Node f = Node.folder(uniqueFolderName(container, name));
            container.children.add(f);
            model.save();
            if (enterAfter != null) returnToBrowseEntering(enterAfter);
            else returnToBrowse(f);
            toast("Created " + f.folderName);
        });
    }

    private void promptRename(Node folder) {
        promptName("New name for " + model.labelOf(folder), name -> {
            Node parent = model.parentOf(folder);
            folder.folderName = uniqueFolderName(parent != null ? parent : model.root, name);
            model.save();
            returnToBrowse(folder);
            toast("Renamed to " + folder.folderName);
        });
    }

    /** A non-empty folder name unique within {@code container}; falls back to "Folder N" when blank. */
    private String uniqueFolderName(Node container, String requested) {
        String base = requested == null ? "" : requested.trim();
        if (base.isEmpty()) {
            int n = 1;
            while (hasFolder(container, "Folder " + n)) n++;
            return "Folder " + n;
        }
        if (!hasFolder(container, base)) return base;
        int n = 2;
        while (hasFolder(container, base + " " + n)) n++;
        return base + " " + n;
    }

    private boolean hasFolder(Node container, String name) {
        for (Node c : container.children) if (c.isFolder() && name.equals(c.folderName)) return true;
        return false;
    }

    private void promptName(String title, Consumer<String> onDone) {
        if (braille == null || !braille.isReady()) toast("Braille input not ready, try again");
        push(new TextEntryFrame(title, onDone));
    }

    private void deleteFolder(Node folder) {
        if (!folder.children.isEmpty()) { popToBrowse(); toast("Folder is not empty"); return; }
        model.removeFromTree(folder);
        model.save();
        popToBrowse();
        render(true);
        toast("Folder deleted");
    }

    private void returnToBrowseEntering(Node folder) {
        popToBrowse();
        if (frame() instanceof BrowseFrame) ((BrowseFrame) frame()).enter(folder);
        else render(true);
    }

    // ---- frames -----------------------------------------------------------------

    private interface Frame {
        String title();
        int size();
        int index();
        void move(int delta);
        void moveTo(int i);
        String labelAt(int i);
        void activate();
        void menu();
        void requestTitle();
        boolean consumeTitleAnnouncement();
        default boolean isText() { return false; }
    }

    private abstract class BaseFrame implements Frame {
        int index;
        boolean announceTitle = true;
        @Override public int index() {
            int n = size();
            return n == 0 ? 0 : Math.min(index, n - 1); // the list can shrink when an app is removed
        }
        @Override public void move(int delta) {
            int n = size();
            if (n == 0) return;
            index = ((index + delta) % n + n) % n;
        }
        @Override public void moveTo(int i) { if (size() > 0) index = Math.max(0, Math.min(size() - 1, i)); }
        @Override public void requestTitle() { announceTitle = true; }
        @Override public boolean consumeTitleAnnouncement() {
            if (announceTitle) { announceTitle = false; return true; }
            return false;
        }
    }

    /** Shows the visible children of one folder (Plus gating applied). */
    private final class BrowseFrame extends BaseFrame {
        final Node folder;
        BrowseFrame(Node folder) { this.folder = folder; }

        private List<Node> visible() {
            List<Node> v = new ArrayList<>();
            for (Node n : folder.children) if (model.isVisible(n)) v.add(n);
            return v;
        }

        @Override public String title() { return folder == model.root ? "Home" : folder.folderName; }
        @Override public int size() { return visible().size(); }
        @Override public String labelAt(int i) {
            Node n = visible().get(i);
            return n.isFolder() ? model.labelOf(n) + ", folder" : model.labelOf(n);
        }
        @Override public void activate() {
            List<Node> v = visible();
            if (v.isEmpty()) { openContainerMenu(folder); return; }
            Node n = v.get(Math.min(index, v.size() - 1));
            if (n.isFolder()) enter(n);
            else if (n.isApp()) launch(n);
            else doAction(n.actionId);
        }
        @Override public void menu() {
            List<Node> v = visible();
            if (v.isEmpty()) openContainerMenu(folder);
            else openContextMenu(v.get(Math.min(index, v.size() - 1)), folder);
        }
        void enter(Node sub) { push(new BrowseFrame(sub)); }
        void selectNode(Node n) {
            List<Node> v = visible();
            int i = v.indexOf(n);
            if (i >= 0) index = i; else index = Math.min(index, Math.max(0, v.size() - 1));
        }
    }

    private static final class Action {
        final String label; final Runnable run;
        Action(String label, Runnable run) { this.label = label; this.run = run; }
    }

    /** A flat list of named operations. */
    private final class ActionFrame extends BaseFrame {
        final String title; final List<Action> actions;
        ActionFrame(String title, List<Action> actions) { this.title = title; this.actions = actions; }
        @Override public String title() { return title; }
        @Override public int size() { return actions.size(); }
        @Override public String labelAt(int i) { return actions.get(i).label; }
        @Override public void activate() { if (!actions.isEmpty()) actions.get(Math.min(index, actions.size() - 1)).run.run(); }
        @Override public void menu() { }
    }

    /**
     * Braille text entry for folder names, following Orbit's edit-field model so it matches the other apps.
     * Within a word each cell echoes the last character of the mode-388 translation of the word so far (a sign with
     * no output reads as its dot numbers). Spacebar commits the word with the contracted mode (132) and adds a
     * space. Dot 7 is backspace: a braille cell from the current word, or an ASCII char once the word is committed.
     * Left/Right (dots 3 and 6) review by character. Select / Dot 8 confirms; the Z chord cancels.
     *
     * <p>On the Orbit Player's phone keypad the field instead types with T9 ({@link T9Input}) and edits like the fields
     * in Orbit's own apps: Left/Right move by character, Up/Down jump to the start/end, BACK deletes (held: cancels),
     * a held digit types that digit, and OK confirms. Changes are reported to the screen reader as edit-field changes,
     * so it speaks them according to the user's typing echo settings.
     */
    private final class TextEntryFrame extends BaseFrame {
        static final int MODE_LIVE = 388, MODE_COMMIT = 132;
        final String title;
        final Consumer<String> onDone;
        final StringBuilder text = new StringBuilder(); // committed text
        final List<Integer> buffer = new ArrayList<>(); // current word's cells, inserted at wordStart
        int cursor;      // caret position within committed text
        int wordStart;   // where the current word is being inserted
        final T9Input t9 = new T9Input(new T9Host());
        boolean t9Held;  // a held keypad key already acted; ignore its release

        TextEntryFrame(String title, Consumer<String> onDone) { this.title = title; this.onDone = onDone; }

        @Override public String title() { return title; }
        @Override public boolean isText() { return true; }
        @Override public int size() { return 1; }
        @Override public String labelAt(int i) { return display().isEmpty() ? "blank" : display(); }
        @Override public void activate() { }
        @Override public void menu() { }

        /** Committed text with a live view of the word being typed shown at its insertion point. */
        String display() {
            String live = buffer.isEmpty() ? "" : orEmpty(braille.translateContracted(buffer, MODE_LIVE));
            return text.substring(0, wordStart) + live + text.substring(wordStart);
        }

        void key(String keyName, int action, boolean longPress) {
            if (sr.isPhoneKeypad()) { phoneKey(keyName, action, longPress); return; }
            if (action == 0 && keyName.equals("LEFT")) { charMove(-1); return; }
            if (action == 0 && keyName.equals("RIGHT")) { charMove(1); return; }
            if (action == 3 && keyName.equals("SPACE_DOT3")) { charMove(-1); return; } // Left delivered as a chord
            if (action == 3 && keyName.equals("SPACE_DOT6")) { charMove(1); return; }  // Right delivered as a chord
            if (action == 3 && keyName.equals("SPACE_DOT2")) { wordMove(-1); return; }   // previous word
            if (action == 3 && keyName.equals("SPACE_DOT5")) { wordMove(1); return; }    // next word
            if (action == 3 && keyName.equals("SPACE_DOT1_DOT3")) { edgeMove(-1); return; }  // start of text
            if (action == 3 && keyName.equals("SPACE_DOT4_DOT6")) { edgeMove(1); return; }   // end of text
            if (action == 0 && (keyName.equals("OK") || keyName.equals("SELECT"))) { confirm(); return; }
            if (action == 1 && keyName.equals("DOT8")) { confirm(); return; }
            if (action == 1 && keyName.equals("DOT7")) { backspace(); return; }
            if (action == 3 && (keyName.equals("SPACE_DOT1_DOT3_DOT5_DOT6")
                    || keyName.equals("SPACE_DOT1_DOT2_DOT3_DOT4_DOT5_DOT6"))) { cancel(); return; }
            if (keyName.equals("SPACE") && (action == 0 || action == 1)) { insertSpace(); return; }
            if (keyName.startsWith("DOT") && (action == 1 || action == 3)) { inputCell(mask(keyName)); return; }
            if (action == 0 || action == 3) android.util.Log.d(LauncherModel.TAG, "edit unhandled key " + keyName + " action=" + action);
        }

        private int mask(String keyName) {
            int m = 0;
            for (String part : keyName.split("_")) {
                if (!part.startsWith("DOT")) continue;
                try { int d = Integer.parseInt(part.substring(3)); if (d >= 1 && d <= 8) m |= 1 << (d - 1); }
                catch (NumberFormatException ignored) { }
            }
            return m;
        }

        /** Add a braille cell to the current word (inserted at the caret) and echo its last produced character. */
        private void inputCell(int m) {
            if (buffer.isEmpty()) wordStart = cursor;
            buffer.add(m);
            String t = orEmpty(braille.translateContracted(buffer, MODE_LIVE));
            if (t.isEmpty()) sr.speak("dot " + dotNumbers(m));
            else {
                char last = t.charAt(t.length() - 1);
                sr.characterTyped(String.valueOf(last), Character.isUpperCase(last));
            }
        }

        /** Translate the current word (contracted) and insert it into the committed text at its start. */
        private void flushWord() {
            if (buffer.isEmpty()) return;
            String w = orEmpty(braille.translateContracted(buffer, MODE_COMMIT));
            text.insert(wordStart, w);
            cursor = wordStart + w.length();
            buffer.clear();
        }

        private void insertSpace() {
            flushWord();
            text.insert(cursor, ' ');
            cursor++;
            sr.speak("space");
        }

        private void backspace() {
            if (!buffer.isEmpty()) {
                int removed = buffer.remove(buffer.size() - 1);
                String t = orEmpty(braille.translateContracted(java.util.Collections.singletonList(removed), MODE_LIVE));
                sr.speak(t.isEmpty() ? "dot " + dotNumbers(removed) + " deleted" : t + " deleted");
            } else if (cursor > 0) {
                char c = text.charAt(cursor - 1);
                text.deleteCharAt(cursor - 1);
                cursor--;
                sr.speak((c == ' ' ? "space" : String.valueOf(c)) + " deleted");
            } else {
                sr.speak("blank");
            }
        }

        private void charMove(int dir) {
            flushWord();
            if (dir < 0) {
                if (cursor <= 0) { sr.speak("start of text"); return; }
                cursor--;
                speakChar(cursor);
            } else {
                if (cursor >= text.length()) { sr.speak("end of text"); return; }
                speakChar(cursor);
                cursor++;
            }
        }

        private void wordMove(int dir) {
            flushWord();
            int n = text.length();
            if (dir < 0) {
                int i = cursor;
                while (i > 0 && text.charAt(i - 1) == ' ') i--;
                while (i > 0 && text.charAt(i - 1) != ' ') i--;
                if (i == cursor) { sr.speak("start of text"); return; }
                cursor = i;
            } else {
                int i = cursor;
                while (i < n && text.charAt(i) != ' ') i++;
                while (i < n && text.charAt(i) == ' ') i++;
                if (i == cursor) { sr.speak("end of text"); return; }
                cursor = i;
            }
            speakWordAt(cursor);
        }

        private void edgeMove(int dir) {
            flushWord();
            cursor = dir < 0 ? 0 : text.length();
            if (cursor < text.length()) speakWordAt(cursor); else sr.speak("end of text");
        }

        private void speakChar(int pos) {
            char c = text.charAt(pos);
            sr.speak(c == ' ' ? "space" : String.valueOf(c));
        }

        private void speakWordAt(int pos) {
            int e = pos;
            while (e < text.length() && text.charAt(e) != ' ') e++;
            String w = text.substring(pos, e);
            sr.speak(w.isEmpty() ? "space" : w);
        }

        private String dotNumbers(int m) {
            StringBuilder sb = new StringBuilder();
            for (int d = 1; d <= 8; d++) if ((m & (1 << (d - 1))) != 0) sb.append(sb.length() == 0 ? "" : " ").append(d);
            return sb.length() == 0 ? "0" : sb.toString();
        }

        private String orEmpty(String s) { return s == null ? "" : s; }

        // ---- Orbit Player phone keypad -------------------------------------------------------------------------

        private void phoneKey(String keyName, int action, boolean longPress) {
            int digit = keyName.length() == 1 && Character.isDigit(keyName.charAt(0)) ? keyName.charAt(0) - '0' : -1;
            if (digit >= 0 || keyName.equals("*") || keyName.equals("#")) {
                if (action == 0 && longPress) {         // held: a digit types itself; star and pound do nothing
                    t9Held = true;
                    if (digit >= 0) { t9.commitNow(); insertAt(cursor, (char) ('0' + digit)); }
                    return;
                }
                if (action != 1) return;                // keypad keys act on release
                if (t9Held) { t9Held = false; return; }
                if (digit >= 0) t9.tap(digit);
                else if (keyName.equals("*")) t9.nextMode();
                else t9.nextLayout();
                return;
            }
            switch (keyName) {
                case "LEFT": case "RIGHT":
                    if (action == 0 || action == 2) { t9.commitNow(); phoneCharMove(keyName.equals("LEFT") ? -1 : 1); }
                    return;
                case "UP": case "DOWN":
                    if (action == 0 || action == 2) phoneEdge(keyName.equals("UP") ? -1 : 1);
                    return;
                case "BACK":
                    if (action != 1) return;
                    if (longPress) { cancel(); return; }
                    t9.commitNow();
                    phoneBackspace();
                    return;
                case "OK":
                    if (action == 0 && !longPress) { t9.commitNow(); confirm(); }
                    return;
                default:
            }
        }

        private void insertAt(int pos, char c) {
            pos = Math.max(0, Math.min(text.length(), pos));
            text.insert(pos, c);
            cursor = pos + 1;
            report("character_inserted", cursor);
        }

        /** Left speaks the character the caret moved onto; Right the one it moved past. An edge sounds the error tone. */
        private void phoneCharMove(int dir) {
            if (dir < 0) {
                if (cursor <= 0) { sr.playSound(SOUND_ERROR); report("boundary_start", cursor); return; }
                cursor--;
                report("cursor_moved", cursor);
            } else {
                if (cursor >= text.length()) { sr.playSound(SOUND_ERROR); report("boundary_end", cursor - 1); return; }
                cursor++;
                report("cursor_moved", cursor - 1);
            }
        }

        /** Up and Down in a one-line field jump to its start or end. */
        private void phoneEdge(int dir) {
            cursor = dir < 0 ? 0 : text.length();
            sr.playSound(SOUND_ERROR);
            report(dir < 0 ? "boundary_start" : "boundary_end", cursor);
        }

        private void phoneBackspace() {
            if (cursor <= 0 || text.length() == 0) { sr.playSound(SOUND_ERROR); return; }
            text.deleteCharAt(cursor - 1);
            cursor--;
            report("character_deleted", cursor);
        }

        private void report(String changeType, int position) {
            sr.editBoxChanged(title, text.toString(), position, changeType);
        }

        /** T9's link to this field. Ignores a late commit timeout once the field has closed. */
        private final class T9Host implements T9Input.Host {
            private final long timeout = sr.characterCommitTimeout();
            @Override public int caret() { return cursor; }
            @Override public long timeoutMs() { return timeout; }
            @Override public void candidate(char c) { sr.characterTyped(String.valueOf(c), title); }
            @Override public void modeChanged(String name) { report("t9_mode_changed:" + name, cursor); }
            @Override public void commit(char c, int position, boolean feedback) {
                if (frame() != TextEntryFrame.this || position > text.length()) return;
                insertAt(position, c);
                if (!feedback) return;
                int mode = sr.characterCommitFeedback();
                if (mode == 1 || mode == 4) sr.playSound(SOUND_COMMIT);
                if (mode == 3 || mode == 5) sr.vibrate(50);
                if (mode == 2 || mode == 4 || mode == 5) sr.speakQueued(String.valueOf(c));
            }
        }

        private void confirm() {
            flushWord();
            String name = text.toString().trim();
            popToBrowse();
            onDone.accept(name);
        }

        private void cancel() {
            t9.cancel();
            popToBrowse();
            toast("Cancelled");
        }
    }
}
