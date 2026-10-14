package dev.emassey0135.communitylauncher;

import android.os.Handler;
import android.os.Looper;

/**
 * Multi-tap (T9) letter entry for the Orbit Player's phone keypad, behaving like the edit fields in Orbit's own apps.
 *
 * <p>Tapping a digit key offers that key's first character; tapping the same key again moves on to its next character,
 * wrapping around. The offered character is only a candidate: it goes into the text (at the caret position where its key
 * was first tapped) when another key is tapped, when the field is edited or navigated, or when the user's commit
 * timeout passes with no further tap. Star switches mode (lowercase, uppercase, digits, symbols); pound would switch
 * keyboard layout, and only English is available.
 */
final class T9Input {

    /** What the edit field does with T9's output. */
    interface Host {
        /** Current caret position in the field's text. */
        int caret();
        /** Commit timeout in milliseconds. */
        long timeoutMs();
        /** A new candidate character was offered. */
        void candidate(char c);
        /** Insert {@code c} at {@code position}; {@code feedback} is true when the commit timeout committed it. */
        void commit(char c, int position, boolean feedback);
        /** The mode changed; {@code name} is its spoken name. */
        void modeChanged(String name);
    }

    enum Mode {
        LOWERCASE("lowercase"), UPPERCASE("uppercase"), DIGITS("digits"), SYMBOLS("symbols");
        final String spoken;
        Mode(String spoken) { this.spoken = spoken; }
    }

    private static final String[] LOWERCASE =
            {" ", ".,?!-@_;:/", "abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz"};
    private static final String[] UPPERCASE = new String[LOWERCASE.length];
    private static final String[] DIGITS = {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9"};
    private static final String[] SYMBOLS =
            {" ", ".,?!", "-@_;", ":/\\|", "#$%^", "&*'`", "(){}", "{}<>", "\"~°№", "+=©•"};
    static {
        for (int i = 0; i < LOWERCASE.length; i++) UPPERCASE[i] = LOWERCASE[i].toUpperCase();
    }

    private final Host host;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeout = () -> commit(true);
    private Mode mode = Mode.LOWERCASE;
    private int key = -1;          // digit whose characters are being cycled, or -1
    private int taps;              // taps of that key so far
    private Character pending;     // the offered candidate, not yet in the text
    private int pendingPos = -1;   // where the candidate will be inserted

    T9Input(Host host) { this.host = host; }

    boolean hasPending() { return pending != null; }

    /** The candidate and where it goes, for showing the text with the candidate in place; null if none. */
    Character pending() { return pending; }
    int pendingPosition() { return pendingPos; }

    /** A tap (release) of digit key {@code digit}. */
    void tap(int digit) {
        boolean digits = mode == Mode.DIGITS;
        boolean differentKey = key != -1 && key != digit;
        // In digits mode each key has one character, so a repeated key types it again rather than cycling.
        if (!differentKey && digits && pending != null) commit(false);
        String chars = table()[digit];
        handler.removeCallbacks(timeout);
        if (key == digit) {
            pending = chars.charAt(taps % chars.length());
            taps++;
        } else {
            if (pending != null) commit(false);
            key = digit;
            taps = 1;
            pending = chars.charAt(0);
        }
        handler.postDelayed(timeout, host.timeoutMs());
        if (pendingPos < 0 || differentKey || digits) pendingPos = host.caret();
        host.candidate(pending);
    }

    /** Star: next mode. */
    void nextMode() {
        if (pending != null) commit(false);
        Mode[] modes = Mode.values();
        mode = modes[(mode.ordinal() + 1) % modes.length];
        host.modeChanged(mode.spoken);
    }

    /** Pound: next keyboard layout. Only English exists, so this just commits any candidate. */
    void nextLayout() {
        if (pending != null) commit(false);
    }

    /** Put the candidate into the text now (no commit feedback), as any edit or navigation does first. */
    void commitNow() {
        if (pending != null) commit(false);
    }

    /** Drop any candidate without inserting it (the field is closing). */
    void cancel() {
        reset();
        pendingPos = -1;
    }

    private void commit(boolean feedback) {
        Character c = pending;
        int pos = pendingPos;
        reset();
        pendingPos = -1;
        if (c != null && pos >= 0) host.commit(c, pos, feedback);
    }

    private void reset() {
        key = -1;
        taps = 0;
        pending = null;
        handler.removeCallbacks(timeout);
    }

    private String[] table() {
        switch (mode) {
            case UPPERCASE: return UPPERCASE;
            case DIGITS: return DIGITS;
            case SYMBOLS: return SYMBOLS;
            default: return LOWERCASE;
        }
    }
}
