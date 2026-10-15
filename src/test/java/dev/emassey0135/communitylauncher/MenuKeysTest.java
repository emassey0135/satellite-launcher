package dev.emassey0135.communitylauncher;

import static org.junit.Assert.assertEquals;

import dev.emassey0135.communitylauncher.MenuKeys.Command;

import org.junit.Test;

/** The keys in the launcher's lists and the power dialog. */
public class MenuKeysTest {
    private static final int PRESS = 0, RELEASE = 1, CHORD = 3;

    @Test public void arrowsMoveAndHeldArrowsGoToTheEnds() {
        assertEquals(Command.UP, MenuKeys.of("UP", PRESS, false));
        assertEquals(Command.DOWN, MenuKeys.of("DOWN", PRESS, false));
        assertEquals(Command.TOP, MenuKeys.of("UP", PRESS, true));
        assertEquals(Command.BOTTOM, MenuKeys.of("DOWN", PRESS, true));
    }

    @Test public void chordsDoWhatTheArrowsDo() {
        assertEquals(Command.UP, MenuKeys.of("SPACE_DOT1", CHORD, false));
        assertEquals(Command.DOWN, MenuKeys.of("SPACE_DOT4", CHORD, false));
        assertEquals(Command.TOP, MenuKeys.of("SPACE_DOT1_DOT2_DOT3", CHORD, false));
        assertEquals(Command.BOTTOM, MenuKeys.of("SPACE_DOT4_DOT5_DOT6", CHORD, false));
    }

    @Test public void backFromLeftDot7SpaceEAndThePlayersBack() {
        assertEquals(Command.BACK, MenuKeys.of("LEFT", PRESS, false));
        assertEquals(Command.BACK, MenuKeys.of("DOT7", RELEASE, false));
        assertEquals(Command.BACK, MenuKeys.of("SPACE_DOT1_DOT5", CHORD, false));
        assertEquals(Command.BACK, MenuKeys.of("BACK", PRESS, false));
        assertEquals(Command.NONE, MenuKeys.of("BACK", PRESS, true));
    }

    @Test public void activateFromRightOkSelectAndDot8() {
        for (String key : new String[] {"RIGHT", "OK", "SELECT"}) assertEquals(Command.ACTIVATE, MenuKeys.of(key, PRESS, false));
        assertEquals(Command.ACTIVATE, MenuKeys.of("DOT8", RELEASE, false));
    }

    @Test public void otherChords() {
        assertEquals(Command.MENU, MenuKeys.of("SPACE_DOT1_DOT3_DOT4", CHORD, false));
        assertEquals(Command.MENU, MenuKeys.of("MENU", RELEASE, false));
        assertEquals(Command.RECENTS, MenuKeys.of("SPACE_DOT1_DOT2_DOT3_DOT5", CHORD, false));
        assertEquals(Command.NOTIFICATIONS, MenuKeys.of("SPACE_DOT1_DOT3_DOT4_DOT5", CHORD, false));
        assertEquals(Command.HOME, MenuKeys.of("SPACE_DOT1_DOT3_DOT5_DOT6", CHORD, false));
        assertEquals(Command.HOME, MenuKeys.of("SPACE_DOT1_DOT2_DOT3_DOT4_DOT5_DOT6", CHORD, false));
    }

    @Test public void dotsOnTheirOwnAndUnknownKeysDoNothing() {
        assertEquals(Command.NONE, MenuKeys.of("DOT1", RELEASE, false));
        assertEquals(Command.NONE, MenuKeys.of("SPACE_DOT2", CHORD, false));
        assertEquals(Command.NONE, MenuKeys.of("UP", RELEASE, false));
    }
}
