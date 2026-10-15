package dev.emassey0135.communitylauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** The parsing and list building that the launcher's setup and device checks share. */
public class DeviceSetupTest {
    private static final String ORBIT = "com.orbit.screenreader/.ScreenReaderAccessibilityService";
    private static final String BACKTALK = "fyi.quin.backtalk/com.google.android.marvin.talkback.TalkBackService";

    @Test public void servicesAreAddedOnlyIfMissing() {
        assertEquals(ORBIT + ":" + BACKTALK, DeviceSetup.withServices(ORBIT, BACKTALK));
        assertEquals(ORBIT + ":" + BACKTALK, DeviceSetup.withServices(ORBIT + ":" + BACKTALK, BACKTALK));
    }

    @Test public void servicesAreComparedIgnoringCase() {
        String lower = BACKTALK.toLowerCase(java.util.Locale.ROOT);
        assertEquals(lower, DeviceSetup.withServices(lower, BACKTALK));
    }

    @Test public void anEmptyListGetsJustTheService() {
        assertEquals(BACKTALK, DeviceSetup.withServices(null, BACKTALK));
        assertEquals(BACKTALK, DeviceSetup.withServices("", BACKTALK));
        assertEquals(ORBIT, DeviceSetup.withServices(ORBIT, (String) null));
    }

    @Test public void valueIsReadFromAContentRow() {
        assertEquals("1", RootQuery.valueField("Row: 0 key=isLicenseActivate, value=1, type=int\n", 0));
        assertEquals("en-ueb-g2.ctb", RootQuery.valueField("Row: 0 key=t, value=en-ueb-g2.ctb\n", 0));
        assertNull(RootQuery.valueField("No result found.\n", 0));
    }

    @Test public void valueIsReadFromWhereTheRowStarts() {
        String out = "Row: 0 key=a, value=first, type=string\nRow: 0 key=b, value=second, type=string\n";
        assertEquals("second", RootQuery.valueField(out, out.indexOf("key=b")));
    }
}
