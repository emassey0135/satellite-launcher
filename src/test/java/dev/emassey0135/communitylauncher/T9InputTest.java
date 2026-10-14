package dev.emassey0135.communitylauncher;

import static org.junit.Assert.assertEquals;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** The commit timeout never fires here (the Handler does nothing in unit tests), so candidates commit only when told. */
public class T9InputTest {
    private Field field;
    private T9Input t9;

    @Before public void setUp() {
        field = new Field();
        t9 = new T9Input(field);
    }

    @Test public void tappingAKeyAgainCyclesItsLetters() {
        t9.tap(2);
        t9.tap(2);
        t9.tap(2);
        t9.tap(2);
        assertEquals("abca", field.candidates());
        t9.commitNow();
        assertEquals("a", field.text.toString());
    }

    @Test public void anotherKeyCommitsTheCandidate() {
        type(4, 4, 3);
        t9.commitNow();
        assertEquals("hd", field.text.toString());
    }

    @Test public void zeroIsSpace() {
        type(4, 0, 4, 4, 4);
        t9.commitNow();
        assertEquals("g i", field.text.toString());
    }

    @Test public void starSwitchesToUppercaseThenDigitsThenSymbols() {
        t9.nextMode();
        type(2);
        t9.nextMode();
        type(5, 5);
        t9.nextMode();
        type(1);
        t9.commitNow();
        assertEquals("A55.", field.text.toString());
        assertEquals("[uppercase, digits, symbols]", field.modes.toString());
    }

    @Test public void theCandidateGoesWhereItsKeyWasFirstTapped() {
        field.text.append("xy");
        field.caret = 1;
        type(4, 4);
        t9.commitNow();
        assertEquals("xhy", field.text.toString());
        assertEquals(2, field.caret);
    }

    @Test public void cancelDropsTheCandidate() {
        type(2);
        t9.cancel();
        t9.commitNow();
        assertEquals("", field.text.toString());
    }

    @Test public void starAndPoundCommitWithoutFeedback() {
        type(2);
        t9.nextLayout();
        assertEquals("a", field.text.toString());
        assertEquals("[false]", field.feedback.toString());
    }

    private void type(int... digits) {
        for (int d : digits) t9.tap(d);
    }

    /** A one-line edit field that takes T9's output. */
    private static final class Field implements T9Input.Host {
        final StringBuilder text = new StringBuilder();
        final StringBuilder offered = new StringBuilder();
        final List<String> modes = new ArrayList<>();
        final List<Boolean> feedback = new ArrayList<>();
        int caret;

        String candidates() { return offered.toString(); }

        @Override public int caret() { return caret; }
        @Override public long timeoutMs() { return 1000; }
        @Override public void candidate(char c) { offered.append(c); }
        @Override public void modeChanged(String name) { modes.add(name); }
        @Override public void commit(char c, int position, boolean withFeedback) {
            text.insert(position, c);
            caret = position + 1;
            feedback.add(withFeedback);
        }
    }
}
