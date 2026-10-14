package dev.emassey0135.communitylauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.emassey0135.communitylauncher.SetupEngine.Outcome;
import dev.emassey0135.communitylauncher.SetupEngine.Result;
import dev.emassey0135.communitylauncher.SetupEngine.Step;
import dev.emassey0135.communitylauncher.SetupEngine.StopException;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SetupEngineTest {
    private final FakeDevice device = new FakeDevice();
    private final MemoryStore store = new MemoryStore();
    private final List<String> said = new ArrayList<>();
    private final List<String> ran = new ArrayList<>();

    @Test public void runsEveryStepAndFinishes() {
        SetupEngine engine = engine(step("a", Outcome.DONE), SetupEngine.RESTART, step("b", Outcome.DONE));
        assertEquals(Result.FINISHED, engine.run(true));
        assertEquals(Arrays.asList("a", "b"), ran);
        assertEquals(0, device.restarts);
        assertFalse(store.state.inProgress);
        assertEquals("Setup is complete.", said.get(said.size() - 1));
    }

    @Test public void restartsAtTheNextPointAndCarriesOnAfterwards() {
        Toggle installed = new Toggle();
        Step install = new Step() {
            @Override public String name() { return "install"; }
            @Override public Outcome run() {
                ran.add("install");
                if (installed.on) return Outcome.DONE;
                installed.on = true;
                return Outcome.NEEDS_RESTART;
            }
        };
        SetupEngine engine = engine(install, step("before restart", Outcome.DONE), SetupEngine.RESTART,
                step("after restart", Outcome.DONE));
        assertEquals(Result.RESTARTING, engine.run(true));
        assertEquals(Arrays.asList("install", "before restart"), ran);
        assertEquals(1, device.restarts);
        assertTrue(SetupEngine.resumesAfterBoot(store.state));

        device.boot++;
        ran.clear();
        assertEquals(Result.FINISHED, engine.run(false));
        assertEquals(Arrays.asList("install", "before restart", "after restart"), ran);
        assertEquals(1, device.restarts);
        assertFalse(SetupEngine.resumesAfterBoot(store.state));
    }

    @Test public void aRestartThatDidntHappenIsAskedForAgain() {
        SetupEngine engine = engine(step("a", Outcome.NEEDS_RESTART), SetupEngine.RESTART);
        engine.run(true);
        engine.run(false); // same boot: the restart never happened
        assertEquals(2, device.restarts);
    }

    @Test public void stopsAtAStepThatCantGoOnAndSaysWhy() {
        Step stop = new Step() {
            @Override public String name() { return "stop"; }
            @Override public Outcome run() throws StopException { throw new StopException("Charge the battery."); }
        };
        SetupEngine engine = engine(step("a", Outcome.DONE), stop, step("never", Outcome.DONE));
        assertEquals(Result.STOPPED, engine.run(true));
        assertEquals(Arrays.asList("a"), ran);
        assertEquals("Charge the battery.", said.get(said.size() - 1));
        assertTrue(store.state.inProgress);
        assertFalse(SetupEngine.resumesAfterBoot(store.state)); // only setup's own restarts resume it
    }

    @Test public void aFailingStepStopsSetup() {
        Step fail = new Step() {
            @Override public String name() { return "broken"; }
            @Override public Outcome run() { throw new IllegalStateException("boom"); }
        };
        assertEquals(Result.STOPPED, engine(fail).run(true));
        assertTrue(said.get(said.size() - 1).contains("broken"));
    }

    @Test public void givesUpAfterTooManyRestarts() {
        SetupEngine engine = engine(step("never takes effect", Outcome.NEEDS_RESTART), SetupEngine.RESTART);
        Result result = engine.run(true);
        for (int i = 0; i < SetupEngine.MAX_RESTARTS && result == Result.RESTARTING; i++) {
            device.boot++;
            result = engine.run(false);
        }
        assertEquals(Result.STOPPED, result);
        assertEquals(SetupEngine.MAX_RESTARTS, device.restarts);
        device.boot++;
        assertEquals(Result.RESTARTING, engine.run(true)); // starting it again allows restarts again
    }

    private SetupEngine engine(Step... steps) {
        return new SetupEngine(Arrays.asList(steps), store, device, new SetupEngine.Listener() {
            @Override public void log(String message) { }
            @Override public void say(String message) { said.add(message); }
        });
    }

    private Step step(String name, Outcome outcome) {
        return new Step() {
            @Override public String name() { return name; }
            @Override public Outcome run() {
                ran.add(name);
                return outcome;
            }
        };
    }

    private static final class Toggle {
        boolean on;
    }

    private static final class FakeDevice implements SetupEngine.Device {
        int boot;
        int restarts;

        @Override public String bootId() { return "boot-" + boot; }
        @Override public void restart() { restarts++; }
    }

    /** Keeps a copy, as a file would, so that later changes to the engine's object aren't saved by accident. */
    private static final class MemoryStore implements SetupEngine.Store {
        SetupEngine.State state = new SetupEngine.State();

        @Override public SetupEngine.State load() { return copy(state); }
        @Override public void save(SetupEngine.State s) { state = copy(s); }

        private static SetupEngine.State copy(SetupEngine.State s) {
            SetupEngine.State c = new SetupEngine.State();
            c.inProgress = s.inProgress;
            c.needsRestart = s.needsRestart;
            c.restartRequestedIn = s.restartRequestedIn;
            c.restarts = s.restarts;
            return c;
        }
    }
}
