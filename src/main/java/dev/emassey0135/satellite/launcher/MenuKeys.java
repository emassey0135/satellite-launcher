package dev.emassey0135.satellite.launcher;

/**
 * What the keys do in the launcher's lists: its menus, and the power dialog. The Orbit Speak's arrows, OK, dots 7 and 8
 * and chords, and the Orbit Player's arrows, OK, BACK and MENU (HOME reaches the launcher as Android's home).
 */
final class MenuKeys {
    enum Command { NONE, UP, DOWN, TOP, BOTTOM, BACK, ACTIVATE, MENU, HOME, RECENTS, NOTIFICATIONS }

    private MenuKeys() { }

    /** The command for a key: {@code action} 0 is a press, 1 a release and 3 a chord. */
    static Command of(String keyName, int action, boolean longPress) {
        switch (action) {
            case 0:
                switch (keyName) {
                    case "UP": return longPress ? Command.TOP : Command.UP;
                    case "DOWN": return longPress ? Command.BOTTOM : Command.DOWN;
                    case "LEFT": return Command.BACK;
                    case "RIGHT": case "OK": case "SELECT": return Command.ACTIVATE;
                    case "BACK": return longPress ? Command.NONE : Command.BACK;
                    default: return Command.NONE;
                }
            case 1:
                switch (keyName) {
                    case "DOT7": return Command.BACK;
                    case "DOT8": return Command.ACTIVATE;
                    case "MENU": return Command.MENU;
                    default: return Command.NONE;
                }
            case 3:
                switch (keyName) {
                    case "SPACE_DOT1": return Command.UP;
                    case "SPACE_DOT4": return Command.DOWN;
                    case "SPACE_DOT1_DOT2_DOT3": return Command.TOP;                     // Space+L
                    case "SPACE_DOT4_DOT5_DOT6": return Command.BOTTOM;
                    case "SPACE_DOT1_DOT5": return Command.BACK;                         // Space+E
                    case "SPACE_DOT1_DOT3_DOT4": return Command.MENU;                    // Space+M
                    case "SPACE_DOT1_DOT2_DOT3_DOT5": return Command.RECENTS;            // Space+R, as in Backtalk
                    case "SPACE_DOT1_DOT3_DOT4_DOT5": return Command.NOTIFICATIONS;      // Space+N
                    case "SPACE_DOT1_DOT3_DOT5_DOT6":                                    // Space+Z
                    case "SPACE_DOT1_DOT2_DOT3_DOT4_DOT5_DOT6": return Command.HOME;      // Space with a full cell
                    default: return Command.NONE;
                }
            default:
                return Command.NONE;
        }
    }
}
