package dev.emassey0135.satellite.launcher;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Finding items by typing letters, and reading which letter the Orbit Speak's keys typed. */
public class ListFinderTest {
    private static final String[] MENU = {
            "File Manager", "Book Reader", "Braille Reader", "Editor", "Media Player", "Clock and Time Management",
            "Tools", "Settings, folder", "Android Apps, folder"};

    private static ListFinder.Found find(int current, String prefix, char c) {
        return ListFinder.find(MENU, current, prefix, c);
    }

    @Test public void aLetterMovesToTheNextItemStartingWithIt() {
        assertEquals(1, find(0, "", 'b').index);
        assertEquals(2, find(1, "", 'b').index);
        assertEquals(1, find(2, "", 'b').index); // round to the top
        assertEquals(1, find(0, "", 'B').index); // capitals too
    }

    @Test public void lettersTypedTogetherFindItemsStartingWithThemAll() {
        ListFinder.Found b = find(0, "", 'b');
        assertEquals(1, b.index);
        ListFinder.Found br = find(b.index, b.prefix, 'r');
        assertEquals(2, br.index);
        assertEquals("br", br.prefix);
    }

    @Test public void theCurrentItemStaysIfItHasTheNextLetter() {
        ListFinder.Found m = find(0, "", 'm');
        ListFinder.Found me = find(m.index, m.prefix, 'e');
        assertEquals(4, me.index);
        assertEquals("me", me.prefix);
    }

    @Test public void aLetterThatContinuesNothingIsLookedForOnItsOwn() {
        ListFinder.Found b = find(0, "", 'b');
        ListFinder.Found bb = find(b.index, b.prefix, 'b'); // b again: the next b, as if typed alone
        assertEquals(2, bb.index);
        assertEquals("b", bb.prefix);
    }

    @Test public void nothingMatchingIsAnError() {
        ListFinder.Found z = find(3, "", 'z');
        assertEquals(-1, z.index);
        assertEquals("", z.prefix);
        assertEquals(-1, find(3, "ed", 'z').index);
    }

    @Test public void theOnlyMatchBeingCurrentIsAnError() {
        ListFinder.Found e = find(3, "", 'e');
        assertEquals(-1, e.index);
        assertEquals("e", e.prefix); // still typed, for the letters after it
        assertEquals(3, find(3, e.prefix, 'd').index);
    }

    @Test public void aLetterTypedAgainWithOnlyOneItemIsAnError() {
        ListFinder.Found c = find(0, "", 'c');
        assertEquals(5, c.index);
        assertEquals(-1, find(c.index, c.prefix, 'c').index);
    }

    @Test public void cellsAreDotsLetGoOrPressedTogether() {
        assertEquals(1, ListFinder.cell("DOT1", 1));
        assertEquals(0b1001, ListFinder.cell("DOT1_DOT4", 3));
        assertEquals(0, ListFinder.cell("DOT1", 0));           // still being pressed
        assertEquals(0, ListFinder.cell("DOT7", 1));           // backspace
        assertEquals(0, ListFinder.cell("DOT8", 1));           // enter
        assertEquals(0, ListFinder.cell("SPACE_DOT1", 3));     // a command
        assertEquals(0, ListFinder.cell("UP", 0));
        assertEquals(0b1000001, ListFinder.cell("DOT1_DOT7", 3)); // a capital A in computer braille
    }
}
