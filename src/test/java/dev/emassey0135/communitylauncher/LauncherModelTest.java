package dev.emassey0135.communitylauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.ContextWrapper;
import android.content.SharedPreferences;

import dev.emassey0135.communitylauncher.LauncherModel.Node;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class LauncherModelTest {
    private static final String SELF = "dev.emassey0135.communitylauncher";
    private static final String SELF_VOICING = "com.example.selfvoicing";
    private static final String OTHER = "com.example.other";

    private Map<String, String> installed;
    private TestModel model;

    @Before public void setUp() {
        installed = apps("com.orbit.filemanager", "com.orbit.bookreader", "com.orbit.alarmclock", "com.orbit.calculator",
                "com.orbit.settings", "com.orbit.screenreader", "com.orbit.krakal", SELF, SELF_VOICING, OTHER);
        model = new TestModel();
    }

    @Test public void defaultMenuGroupsAppsLikeOrbit() {
        model.restore(null, installed);
        assertSame(model.root, parentOf("com.orbit.filemanager"));
        assertSame(folder("Clock and Time Management"), parentOf("com.orbit.alarmclock"));
        assertSame(folder("Tools"), parentOf("com.orbit.calculator"));
        assertSame(folder("Settings"), parentOf("com.orbit.settings"));
        assertSame(model.root, parentOf(SELF_VOICING));
        assertSame(folder(LauncherModel.ANDROID_APPS), parentOf(OTHER));
    }

    @Test public void servicesAndTheLauncherItselfAreNotShown() {
        model.restore(null, installed);
        assertNull(parentOf("com.orbit.screenreader"));
        assertNull(parentOf("com.orbit.krakal"));
        assertNull(parentOf(SELF));
    }

    @Test public void reconcileDropsRemovedAppsAndPlacesNewOnes() {
        model.restore(null, installed);
        installed.remove(OTHER);
        installed.put("com.example.new", "New");
        assertTrue(model.reconcile(installed));
        assertNull(parentOf(OTHER));
        assertSame(folder(LauncherModel.ANDROID_APPS), parentOf("com.example.new"));
        assertFalse(model.reconcile(installed));
    }

    @Test public void hiddenAppsStayHiddenUntilUnhidden() {
        model.restore(null, installed);
        model.hidden.add(SELF_VOICING);
        model.removeFromTree(find(model.root, SELF_VOICING));
        assertFalse(model.reconcile(installed));
        assertNull(parentOf(SELF_VOICING));
        model.unhide(SELF_VOICING);
        assertSame(model.root, parentOf(SELF_VOICING)); // a self-voicing app goes back to the main menu
    }

    @Test public void savedMenuComesBackTheSame() throws Exception {
        model.restore(null, installed);
        Node mine = Node.folder("Mine");
        model.root.children.add(0, mine);
        Node other = find(model.root, OTHER);
        model.removeFromTree(other);
        mine.children.add(other);
        model.hidden.add("com.orbit.bookreader");
        model.removeFromTree(find(model.root, "com.orbit.bookreader"));
        String saved = model.toJson();

        TestModel restored = new TestModel();
        restored.restore(saved, installed);
        assertEquals(saved, restored.toJson());
        assertSame(restored.root.children.get(0), restored.parentOf(find(restored.root, OTHER)));
    }

    @Test public void missingBuiltInItemsAreAddedToTheirFolders() {
        String saved = "{\"tree\":[{\"t\":\"f\",\"n\":\"Clock and Time Management\",\"c\":[]}],\"hidden\":[]}";
        model.restore(saved, installed);
        Node clock = folder("Clock and Time Management");
        assertTrue(hasAction(clock, "time"));
        assertTrue(hasAction(clock, "date"));
        List<Node> top = model.root.children;
        assertEquals("power", top.get(top.size() - 1).actionId);
    }

    @Test public void unreadableMenuIsRebuilt() {
        model.restore("{not json", installed);
        assertNotNull(folder("Tools"));
        assertSame(folder(LauncherModel.ANDROID_APPS), parentOf(OTHER));
    }

    @Test public void plusAppsAreHiddenWithoutALicenseOnTheSpeak() {
        model.restore(null, installed);
        Node bookReader = find(model.root, "com.orbit.bookreader");
        model.setLicensed(false);
        assertFalse(model.isVisible(bookReader));
        assertTrue(model.isVisible(find(model.root, "com.orbit.filemanager")));
        model.setLicensed(true);
        assertTrue(model.isVisible(bookReader));
    }

    @Test public void thePlayerShowsEveryAppAndNoPlusCheck() {
        model.restore(null, installed);
        model.setLicensed(false);
        model.setPhoneKeypad(true);
        assertTrue(model.isVisible(find(model.root, "com.orbit.bookreader")));
        assertFalse(model.isVisible(findAction(model.root, "plus")));
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private static Map<String, String> apps(String... pkgs) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String p : pkgs) out.put(p, p);
        return out;
    }

    private Node parentOf(String pkg) {
        Node n = find(model.root, pkg);
        return n == null ? null : model.parentOf(n);
    }

    private Node folder(String name) {
        for (Node n : model.root.children) if (n.isFolder() && name.equals(n.folderName)) return n;
        return null;
    }

    private static Node find(Node folder, String pkg) {
        for (Node n : folder.children) {
            if (n.isApp() && n.pkg.equals(pkg)) return n;
            if (n.isFolder()) {
                Node f = find(n, pkg);
                if (f != null) return f;
            }
        }
        return null;
    }

    private static Node findAction(Node folder, String id) {
        for (Node n : folder.children) {
            if (n.isAction() && n.actionId.equals(id)) return n;
            if (n.isFolder()) {
                Node f = findAction(n, id);
                if (f != null) return f;
            }
        }
        return null;
    }

    private static boolean hasAction(Node folder, String id) {
        for (Node n : folder.children) if (n.isAction() && n.actionId.equals(id)) return true;
        return false;
    }

    /** The model with the self-voicing check answered from a fixed set instead of the PackageManager. */
    private static final class TestModel extends LauncherModel {
        private static final Set<String> SELF_VOICING_APPS = new HashSet<>(Arrays.asList(SELF_VOICING));

        TestModel() { super(new TestContext()); }

        @Override boolean isSelfVoicing(String pkg) {
            return pkg.startsWith("com.orbit.") || SELF_VOICING_APPS.contains(pkg);
        }
    }

    private static final class TestContext extends ContextWrapper {
        private final SharedPreferences prefs = new MemoryPreferences();

        TestContext() { super(null); }

        @Override public String getPackageName() { return SELF; }
        @Override public File getFilesDir() { return new File(System.getProperty("java.io.tmpdir")); }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) { return prefs; }
    }

    /** SharedPreferences kept in memory, as far as the model uses them. */
    private static final class MemoryPreferences implements SharedPreferences, SharedPreferences.Editor {
        private final Map<String, Object> values = new HashMap<>();

        @Override public Map<String, ?> getAll() { return values; }
        @Override public String getString(String key, String def) { return (String) values.getOrDefault(key, def); }
        @SuppressWarnings("unchecked")
        @Override public Set<String> getStringSet(String key, Set<String> def) { return (Set<String>) values.getOrDefault(key, def); }
        @Override public int getInt(String key, int def) { return (Integer) values.getOrDefault(key, def); }
        @Override public long getLong(String key, long def) { return (Long) values.getOrDefault(key, def); }
        @Override public float getFloat(String key, float def) { return (Float) values.getOrDefault(key, def); }
        @Override public boolean getBoolean(String key, boolean def) { return (Boolean) values.getOrDefault(key, def); }
        @Override public boolean contains(String key) { return values.containsKey(key); }
        @Override public Editor edit() { return this; }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }

        @Override public Editor putString(String key, String value) { values.put(key, value); return this; }
        @Override public Editor putStringSet(String key, Set<String> value) { values.put(key, value); return this; }
        @Override public Editor putInt(String key, int value) { values.put(key, value); return this; }
        @Override public Editor putLong(String key, long value) { values.put(key, value); return this; }
        @Override public Editor putFloat(String key, float value) { values.put(key, value); return this; }
        @Override public Editor putBoolean(String key, boolean value) { values.put(key, value); return this; }
        @Override public Editor remove(String key) { values.remove(key); return this; }
        @Override public Editor clear() { values.clear(); return this; }
        @Override public boolean commit() { return true; }
        @Override public void apply() { }
    }
}
