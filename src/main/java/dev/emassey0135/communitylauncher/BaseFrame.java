package dev.emassey0135.communitylauncher;

/** A list's place, which moves around from end to end, and whether its title is still to be said. */
abstract class BaseFrame implements Frame {
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
