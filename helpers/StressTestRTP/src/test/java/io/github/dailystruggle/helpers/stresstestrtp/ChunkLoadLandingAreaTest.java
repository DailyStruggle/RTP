package io.github.dailystruggle.helpers.stresstestrtp;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChunkLoadLandingAreaTest {

    private static final String WORLD = "world";

    private static ChunkLoadCounter counter() {
        Plugin plugin = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (p, m, a) -> "getName".equals(m.getName()) ? "StressTestRTP" : null);
        ChunkLoadCounter c = new ChunkLoadCounter(plugin);
        c.setViewDistance(10);
        c.resetPhase();
        return c;
    }

    private static MetricsRecorder.Attempt dispatch(ChunkLoadCounter c, String player) {
        MetricsRecorder.Attempt a = new MetricsRecorder.Attempt(UUID.randomUUID(), player, WORLD, "rtp",
                System.currentTimeMillis(), 0.0, 0.0, 20.0, 10.0, 0L);
        c.beginAttempt(a);
        return a;
    }

    /** Completes {@code a} at block (x, z); chunk = floor(x / 16). */
    private static void land(ChunkLoadCounter c, MetricsRecorder.Attempt a, double x, double z) {
        a.success = true;
        a.toX = x;
        a.toZ = z;
        c.endAttempt(a);
    }

    @Test
    @DisplayName("Loads around a finished landing are charged to it, not to another account's in-flight attempt")
    void landingAreaBeatsLatestDispatch() {
        ChunkLoadCounter c = counter();
        MetricsRecorder.Attempt a = dispatch(c, "leaf26");
        land(c, a, 1600, 1600); // chunk (100, 100)
        MetricsRecorder.Attempt b = dispatch(c, "leaf_26");

        c.route(WORLD, 105, 95, true, null);   // inside the 11-chunk ring
        c.route(WORLD, 111, 100, true, null);  // edge: Chebyshev 11
        c.route(WORLD, 112, 100, true, null);  // outside: falls to b (latest in flight)

        assertEquals(2, c.phaseLanding());
        assertEquals(1, c.phaseAttributed());
        land(c, b, 8000, 8000);
        assertEquals(1, b.attributedChunkLoads);
    }

    @Test
    @DisplayName("The same account's next dispatch closes its landing window; another account's does not")
    void nextDispatchOfSameAccountCutsOff() {
        ChunkLoadCounter c = counter();
        land(c, dispatch(c, "leaf26"), 1600, 1600);

        dispatch(c, "leaf_27");                 // other account: window stays open
        c.route(WORLD, 100, 100, false, null);
        assertEquals(1, c.phaseLanding());

        MetricsRecorder.Attempt next = dispatch(c, "leaf26"); // same account: window closes
        c.route(WORLD, 100, 100, false, null);  // off-tick, no ticket -> background
        c.route(WORLD, 100, 100, true, null);   // on-tick -> latest in-flight attempt
        assertEquals(1, c.phaseLanding());
        assertEquals(1, c.phaseBackground());
        assertEquals(1, c.phaseAttributed());
        land(c, next, -1600, -1600);
        assertEquals(1, next.attributedChunkLoads);
    }

    @Test
    @DisplayName("Overlapping landing areas charge the most recent landing; failures and phase resets open no window")
    void overlapFailureAndReset() {
        ChunkLoadCounter c = counter();
        land(c, dispatch(c, "leaf26"), 1600, 1600);
        land(c, dispatch(c, "leaf_26"), 1760, 1600); // chunk (110, 100), overlaps
        c.route(WORLD, 105, 100, false, null);
        assertEquals(1, c.phaseLanding());

        MetricsRecorder.Attempt failed = dispatch(c, "leaf_27");
        failed.success = false;
        c.endAttempt(failed);
        c.route(WORLD, -500, -500, false, null);
        assertEquals(1, c.phaseBackground());

        c.route("world_nether", 100, 100, false, null); // other world never matches
        assertEquals(2, c.phaseBackground());

        c.resetPhase();
        c.route(WORLD, 100, 100, false, null);
        assertEquals(0, c.phaseLanding());
        assertEquals(1, c.phaseBackground());
    }

    @Test
    @DisplayName("attributed + landing_area + background reconciles with the phase total")
    void totalsReconcile() {
        ChunkLoadCounter c = counter();
        MetricsRecorder.Attempt a = dispatch(c, "leaf26");
        c.route(WORLD, 0, 0, true, null);
        land(c, a, 160, 160);
        c.route(WORLD, 10, 10, true, null);
        c.route(WORLD, 300, 300, true, null);
        c.route(WORLD, 300, 300, false, null);
        assertEquals(c.phaseTotal(), c.phaseAttributed() + c.phaseLanding() + c.phaseBackground());
        assertEquals(4, c.phaseTotal());
    }
}
