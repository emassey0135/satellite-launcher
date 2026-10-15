package dev.emassey0135.communitylauncher;

import java.util.List;

/** A list of things to do, such as an options menu or a question. */
final class ActionFrame extends BaseFrame {
    /** One thing to do, and what it is called. */
    static final class Action {
        final String label;
        final Runnable run;

        Action(String label, Runnable run) {
            this.label = label;
            this.run = run;
        }
    }

    private final String title;
    private final List<Action> actions;

    ActionFrame(String title, List<Action> actions) {
        this.title = title;
        this.actions = actions;
    }

    @Override public String title() { return title; }
    @Override public int size() { return actions.size(); }
    @Override public String labelAt(int i) { return actions.get(i).label; }
    @Override public void activate() { if (!actions.isEmpty()) actions.get(index()).run.run(); }
    @Override public void menu() { }
}
