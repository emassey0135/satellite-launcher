package dev.emassey0135.communitylauncher;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.Collections;
import java.util.Locale;

/**
 * Finding an item in a list by typing, as in Orbit's own lists.
 *
 * <p>On the Orbit Speak, a braille letter moves to the next item starting with it. Letters typed quickly one after
 * another find the next item starting with all of them, staying put if the current item already does; if nothing
 * starts with them all, the last letter is looked for on its own, and if nothing starts with that, the error sound
 * plays.
 *
 * <p>On the Orbit Player, a digit opens that item: 2 the second, 1 and 2 typed quickly the twelfth, and 0 on its own the
 * tenth, as in Orbit's launcher. Holding star turns letter navigation on or off; while it is on, letters typed with T9
 * find items the same way as on the Speak, star changes the T9 mode and a held digit types that digit.
 */
final class ListFinder {
    /** The list that typing finds in. */
    interface Host {
        /** The list in front. */
        Frame frame();
        /** The current item moved: say it. */
        void moved();
        ScreenReaderClient screenReader();
        /** Orbit's braille translator, or null where there isn't one. */
        LiblouisClient braille();
    }

    /** How soon the next letter or digit must follow to add to what was typed. */
    static final long TYPING_MS = 1000;

    /** Whether the Player's digits type letters (T9) rather than open items; one setting for all the launcher's lists. */
    private static boolean letters;

    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final T9Input t9;
    private String prefix = "";
    private long lastLetterAt;
    private int number;
    private final Runnable openNumber = this::openNumber;
    private boolean held; // a held key already acted; its release does nothing

    ListFinder(Host host) {
        this.host = host;
        this.t9 = new T9Input(new T9Input.Host() {
            @Override public int caret() { return 0; }
            @Override public long timeoutMs() { return host.screenReader().characterCommitTimeout(); }
            @Override public void candidate(char c) { host.screenReader().speak(String.valueOf(c)); }
            @Override public void commit(char c, int position, boolean feedback) {
                letter(c, SystemClock.uptimeMillis(), TYPING_MS + timeoutMs());
            }
            @Override public void modeChanged(String name) { host.screenReader().speak(name); }
        });
    }

    /** Handles a key that types; false for any other key, which the caller handles and then calls {@link #reset}. */
    boolean key(String keyName, int action, boolean longPress) {
        Frame frame = host.frame();
        if (frame == null || frame.isText()) return false;
        if (isPhoneKey(keyName)) {
            phoneKey(keyName, action, longPress);
            return true;
        }
        int cell = cell(keyName, action);
        if (cell == 0) return isLetterPress(keyName, action);
        char c = translate(cell);
        if (c == 0) {
            error();
        } else {
            long now = SystemClock.uptimeMillis();
            letter(c, now, TYPING_MS);
        }
        return true;
    }

    /** Forgets what was typed, as any other key does. */
    void reset() {
        prefix = "";
        number = 0;
        main.removeCallbacks(openNumber);
        t9.cancel();
    }

    // ---- The Orbit Speak's braille keys --------------------------------------------------------------------------

    /** The dots of a braille letter typed: one of dots 1 to 6 let go, or several pressed together; else 0. */
    static int cell(String keyName, int action) {
        if (action != 1 && action != 3) return 0;
        int dots = 0;
        for (String token : keyName.split("_")) {
            if (token.length() != 4 || !token.startsWith("DOT")) return 0;
            int dot = token.charAt(3) - '0';
            if (dot < 1 || dot > 8) return 0;
            dots |= 1 << (dot - 1);
        }
        // Dot 7 and dot 8 on their own are backspace and enter.
        return action == 1 && (dots & 0x3f) == 0 ? 0 : dots;
    }

    /** A dot pressed on the way to a letter, which does nothing until it is let go. */
    private static boolean isLetterPress(String keyName, int action) {
        return action == 0 && keyName.length() == 4 && keyName.startsWith("DOT") && keyName.charAt(3) <= '6';
    }

    /** The first letter of a cell in the user's braille table, as Orbit's lists read it; 0 if it has none. */
    private char translate(int cell) {
        LiblouisClient braille = host.braille();
        String text = braille == null ? null : braille.translateContracted(Collections.singletonList(cell), 132);
        return text == null || text.isEmpty() ? 0 : text.charAt(0);
    }

    // ---- The Orbit Player's phone keypad -------------------------------------------------------------------------

    private static boolean isPhoneKey(String keyName) {
        return keyName.length() == 1 && (Character.isDigit(keyName.charAt(0)) || keyName.equals("*") || keyName.equals("#"));
    }

    private void phoneKey(String keyName, int action, boolean longPress) {
        boolean star = keyName.equals("*");
        if (action == 0 && longPress) {
            held = true;
            if (star) {
                reset();
                letters = !letters;
                host.screenReader().speak(letters ? "Letter navigation mode enabled" : "Letter navigation mode disabled");
            } else if (letters && !keyName.equals("#")) {
                t9.commitNow();
                letter(keyName.charAt(0), SystemClock.uptimeMillis(), TYPING_MS);
            }
            return;
        }
        if (action != 1) return; // keypad keys act when let go
        if (held) { held = false; return; }
        if (star) {
            if (letters) t9.nextMode();
        } else if (keyName.equals("#")) {
            if (letters) t9.nextLayout();
        } else if (letters) {
            t9.tap(keyName.charAt(0) - '0');
        } else {
            digit(keyName.charAt(0) - '0');
        }
    }

    /** A digit towards an item's number, which opens it once no more digits could follow. */
    private void digit(int digit) {
        main.removeCallbacks(openNumber);
        int size = host.frame().size();
        number = number == 0 && digit == 0 ? 10 : number * 10 + digit;
        if (number < 1 || number > size) {
            number = 0;
            error();
        } else if (digit == 0 && number == 10 || number * 10 > size) {
            openNumber();
        } else {
            main.postDelayed(openNumber, TYPING_MS);
        }
    }

    private void openNumber() {
        int n = number;
        number = 0;
        main.removeCallbacks(openNumber);
        Frame frame = host.frame();
        if (n < 1 || n > frame.size()) return;
        frame.moveTo(n - 1);
        frame.activate();
    }

    // ---- Letters, from either keypad ------------------------------------------------------------------------------

    /** Moves for a letter typed at {@code now}; it adds to the letters before it if they were typed within {@code window}. */
    private void letter(char c, long now, long window) {
        Frame frame = host.frame();
        if (frame == null || frame.isText()) return;
        String[] labels = new String[frame.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = frame.labelAt(i);
        boolean continuing = now - lastLetterAt < window;
        lastLetterAt = now;
        Found found = find(labels, frame.index(), continuing ? prefix : "", c);
        prefix = found.prefix;
        if (found.index < 0) {
            error();
        } else if (found.index != frame.index()) {
            frame.moveTo(found.index);
            host.moved();
        }
    }

    /** Where typing moves to, and what has been typed so far. */
    static final class Found {
        /** The item, which may be the current one, or -1 if none matches. */
        final int index;
        final String prefix;

        Found(int index, String prefix) {
            this.index = index;
            this.prefix = prefix;
        }
    }

    /**
     * Where typing {@code c} after {@code prefix} moves from {@code current}: staying put if the current item starts
     * with them all, else the next item that does; else the next item starting with {@code c} alone.
     */
    static Found find(String[] labels, int current, String prefix, char c) {
        String letter = String.valueOf(c).toLowerCase(Locale.ROOT);
        if (!prefix.isEmpty()) {
            String longer = prefix + letter;
            if (current < labels.length && startsWith(labels[current], longer)) return new Found(current, longer);
            int i = next(labels, current, longer);
            if (i >= 0) return new Found(i, longer);
        }
        int i = next(labels, current, letter);
        return new Found(i, i < 0 ? "" : letter);
    }

    /** The first item after {@code current}, going round to the top, that starts with {@code prefix}; -1 if none. */
    private static int next(String[] labels, int current, String prefix) {
        for (int step = 1; step <= labels.length; step++) {
            int i = (current + step) % labels.length;
            if (startsWith(labels[i], prefix)) return i;
        }
        return -1;
    }

    private static boolean startsWith(String label, String prefix) {
        return label.toLowerCase(Locale.ROOT).startsWith(prefix);
    }

    private void error() {
        host.screenReader().playSound(ScreenReaderClient.SOUND_ERROR);
    }
}
