package dev.emassey0135.satellite.launcher;

import android.os.Bundle;

/** One list in the launcher (a folder, a menu, a question) or a text field, of which the top one of a stack has the keys. */
interface Frame {
    /** The screen reader's role for a list. */
    int ROLE_LIST_BOX = 5;

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

    /** Moves for the commands that only move within the list; false for any other command. */
    default boolean navigate(MenuKeys.Command command) {
        switch (command) {
            case UP: move(-1); return true;
            case DOWN: move(1); return true;
            case TOP: moveTo(0); return true;
            case BOTTOM: moveTo(size() - 1); return true;
            default: return false;
        }
    }

    /**
     * Tells the screen reader about the list: as newly focused when its title is due, which says the list's name and
     * the item, or else as a move within it. The screen reader says role, item and "N of M" by the user's own
     * verbosity settings, and its review commands read the items.
     */
    default void announce(ScreenReaderClient sr) {
        Bundle b = new Bundle();
        b.putString("name", title());
        b.putInt("roleId", ROLE_LIST_BOX);
        b.putInt("itemCount", size());
        b.putInt("selectedIndex", size() == 0 ? -1 : index());
        if (size() > 0) b.putString("selectedItem", labelAt(index()));
        if (consumeTitleAnnouncement()) sr.focusChanged(b); else sr.itemChanged(b);
    }
}
