package dev.emassey0135.communitylauncher;

import java.util.List;

/**
 * Runs the device setup's steps in order. Every step checks first and only changes what isn't already as it should be,
 * so the whole list is run from the top each time: after a restart, after a step stopped, or to repair a setup later.
 * Nothing records which step was reached; the device itself is the record.
 *
 * <p>A step that changed something that only takes effect after a restart says so, and the next {@link #RESTART}
 * point in the list restarts the device; setup carries on from the top once it is back up. Restarts are counted, so a
 * change that never takes effect stops setup instead of restarting forever.
 */
final class SetupEngine {
    /** The most restarts one run of setup may make; the full setup needs at most three. */
    static final int MAX_RESTARTS = 5;

    /** What a step found or did. */
    enum Outcome {
        /** Already as it should be, or done now. */
        DONE,
        /** Done, but it takes effect only after a restart. */
        NEEDS_RESTART,
    }

    interface Step {
        /** What the step is for, as written to the log. */
        String name();
        Outcome run() throws Exception;
    }

    /** Setup can't go on: the device isn't suitable, something failed, or it waits for the user. */
    static final class StopException extends Exception {
        /** What to tell the user. */
        final String spoken;

        StopException(String spoken) {
            super(spoken);
            this.spoken = spoken;
        }
    }

    /** In the list of steps, the point where the device restarts if an earlier step needs it. */
    static final Step RESTART = new Step() {
        @Override public String name() { return "restart if needed"; }
        @Override public Outcome run() { return Outcome.DONE; }
    };

    /** Setup's progress kept across restarts. */
    static final class State {
        boolean inProgress;
        boolean needsRestart;
        /** The boot that asked for a restart, so that the next run can tell whether it happened. */
        String restartRequestedIn;
        int restarts;
    }

    interface Store {
        State load();
        void save(State state);
    }

    interface Device {
        /** An id that changes with every boot. */
        String bootId();
        void restart() throws Exception;
    }

    interface Listener {
        void log(String message);
        /** Something the user should hear. */
        void say(String message);
    }

    /** How a run ended. */
    enum Result { FINISHED, RESTARTING, STOPPED }

    private final List<Step> steps;
    private final Store store;
    private final Device device;
    private final Listener listener;

    SetupEngine(List<Step> steps, Store store, Device device, Listener listener) {
        this.steps = steps;
        this.store = store;
        this.device = device;
        this.listener = listener;
    }

    /**
     * Run the steps from the top. {@code started} is true when someone started setup (rather than the device coming
     * back up from setup's own restart), which also allows it as many restarts again.
     */
    Result run(boolean started) {
        State state = store.load();
        String boot = device.bootId();
        if (!state.inProgress) {
            state = new State();
            state.inProgress = true;
        }
        if (started) state.restarts = 0;
        if (state.restartRequestedIn != null && !state.restartRequestedIn.equals(boot)) {
            state.needsRestart = false; // the restart happened
            state.restartRequestedIn = null;
        }
        store.save(state);

        for (Step step : steps) {
            if (step == RESTART) {
                if (!state.needsRestart) continue;
                if (state.restarts >= MAX_RESTARTS) {
                    return stop(state, "Setup stopped, because the device restarted " + state.restarts
                            + " times without finishing.");
                }
                state.restarts++;
                state.restartRequestedIn = boot;
                store.save(state);
                listener.log("restarting (" + state.restarts + ")");
                listener.say("Restarting to continue setup.");
                try {
                    device.restart();
                } catch (Exception e) {
                    listener.log("restart failed: " + e);
                    return stop(state, "Setup stopped, because the device couldn't restart.");
                }
                return Result.RESTARTING;
            }
            listener.log("step: " + step.name());
            try {
                if (step.run() == Outcome.NEEDS_RESTART) {
                    state.needsRestart = true;
                    store.save(state);
                }
            } catch (StopException e) {
                listener.log("stopped at " + step.name() + ": " + e.spoken);
                return stop(state, e.spoken);
            } catch (Exception e) {
                listener.log("failed at " + step.name() + ": " + e);
                return stop(state, "Setup stopped, because a step failed: " + step.name() + ".");
            }
        }
        state.inProgress = false;
        state.needsRestart = false;
        state.restartRequestedIn = null;
        store.save(state);
        listener.log("finished");
        listener.say("Setup is complete.");
        return Result.FINISHED;
    }

    /** Whether setup restarted the device and should carry on now that it is back up. */
    static boolean resumesAfterBoot(State state) {
        return state.inProgress && state.restartRequestedIn != null;
    }

    /** Stop where setup is; starting it again carries on. */
    private Result stop(State state, String spoken) {
        store.save(state);
        listener.say(spoken);
        return Result.STOPPED;
    }
}
