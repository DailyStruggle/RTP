package io.github.dailystruggle.rtp.folia.metrics;

import io.github.dailystruggle.rtp.common.bit.PlatformHookBitComponent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit verification for {@link EntitySchedulerSampler} (ADR-123 Sections 5, 11, 12).
 */
@DisplayName("ADR-123: EntitySchedulerSampler 64-player rotation, 10-tick lifecycle, and overrun/breach detection")
class EntitySchedulerSamplerTest {

    private static final long MS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final long SEC = TimeUnit.SECONDS.toNanos(1);

    private static final class ScheduledProbe {
        final UUID playerId;
        final Runnable runCallback;
        final Runnable retiredCallback;
        final AtomicBoolean cancelled = new AtomicBoolean(false);

        ScheduledProbe(UUID playerId, Runnable runCallback, Runnable retiredCallback) {
            this.playerId = playerId;
            this.runCallback = runCallback;
            this.retiredCallback = retiredCallback;
        }
    }

    @Test
    @DisplayName("Inactive when autoTune/observe is disabled; samples at most 64 players per round and rotates through all online players")
    void inactiveWhenDisabledAndRotates64PlayersPerRound() {
        AtomicLong clock = new AtomicLong(SEC);
        AtomicBoolean enabled = new AtomicBoolean(false);
        UUID worldId = UUID.randomUUID();
        List<UUID> onlinePlayers = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            onlinePlayers.add(new UUID(0L, i + 1));
        }

        List<EntitySchedulerSampler.CellCoord> cells = List.of(
                new EntitySchedulerSampler.CellCoord(worldId, null, 0, 0),
                new EntitySchedulerSampler.CellCoord(worldId, null, 64, 64)
        );
        Map<UUID, ScheduledProbe> scheduled = new HashMap<>();

        EntitySchedulerSampler sampler = new EntitySchedulerSampler(
                clock::get,
                enabled::get,
                () -> onlinePlayers,
                () -> cells,
                (wId, w, cx, cz) -> cx == 0 && cz == 0,
                (playerId, runCb, retiredCb) -> {
                    ScheduledProbe p = new ScheduledProbe(playerId, runCb, retiredCb);
                    scheduled.put(playerId, p);
                    return () -> p.cancelled.set(true);
                }
        );

        // 1. Disabled -> starts 0 tasks
        assertEquals(0, sampler.runRound());
        assertEquals(0, sampler.activeProbeCount());
        assertEquals(-1, sampler.sampledPopulation(worldId, 0, 0));

        // 2. Enable and run 3 rounds for 150 players: 64 + 64 + 22 = 150
        enabled.set(true);
        assertEquals(64, sampler.runRound());
        assertEquals(64, sampler.activeProbeCount());
        driveAllTenTicks(scheduled, clock, 50 * MS);
        assertEquals(0, sampler.activeProbeCount(), "All 64 tasks must cancel after 10 runs");

        scheduled.clear();
        assertEquals(64, sampler.runRound());
        driveAllTenTicks(scheduled, clock, 50 * MS);

        scheduled.clear();
        assertEquals(22, sampler.runRound());
        driveAllTenTicks(scheduled, clock, 50 * MS);

        // Full rotation of 150 players completed: cell (0,0) matched all 150, cell (64,64) matched 0
        assertEquals(150, sampler.sampledPopulation(worldId, 0, 0));
        assertEquals(0, sampler.sampledPopulation(worldId, 64, 64));
        assertFalse(sampler.isCellBreached(worldId, 0, 0));
        assertEquals(0L, sampler.overrunsTotal());
    }

    @Test
    @DisplayName("Ignores arbitrary start phase at 50 ms; flags >= 2 overruns (> 60 ms) or single > 100 ms gap as breach; handles retirement and staleness")
    void startPhaseOverrunsBreachesRetirementAndStaleness() {
        AtomicLong clock = new AtomicLong(12_345_678L); // arbitrary start phase
        UUID worldId = UUID.randomUUID();
        UUID pHealthy = UUID.randomUUID();
        UUID pTwoOverruns = UUID.randomUUID();
        UUID pSingleSevere = UUID.randomUUID();
        UUID pRetiring = UUID.randomUUID();

        List<UUID> currentOnline = new ArrayList<>(List.of(pHealthy));
        List<EntitySchedulerSampler.CellCoord> cells = List.of(
                new EntitySchedulerSampler.CellCoord(worldId, null, 8, 8)
        );
        Map<UUID, ScheduledProbe> scheduled = new HashMap<>();
        AtomicInteger cancelCount = new AtomicInteger(0);

        EntitySchedulerSampler sampler = new EntitySchedulerSampler(
                clock::get,
                () -> true,
                () -> currentOnline,
                () -> cells,
                (wId, w, cx, cz) -> true,
                (playerId, runCb, retiredCb) -> {
                    ScheduledProbe p = new ScheduledProbe(playerId, runCb, retiredCb);
                    scheduled.put(playerId, p);
                    return () -> {
                        if (p.cancelled.compareAndSet(false, true)) {
                            cancelCount.incrementAndGet();
                        }
                    };
                }
        );

        // 1. Healthy 50 ms ticks from arbitrary start phase + single 62 ms spike (< 2 overruns) -> NO breach
        assertEquals(1, sampler.runRound());
        ScheduledProbe healthyProbe = scheduled.get(pHealthy);
        assertNotNull(healthyProbe);
        healthyProbe.runCallback.run(); // run 1
        clock.addAndGet(62 * MS);       // 1 overrun (> 60 ms, <= 100 ms)
        healthyProbe.runCallback.run(); // run 2
        for (int r = 3; r <= 10; r++) {
            clock.addAndGet(50 * MS);
            healthyProbe.runCallback.run();
        }
        assertTrue(healthyProbe.cancelled.get());
        assertEquals(1L, sampler.overrunsTotal());
        assertFalse(sampler.isCellBreached(worldId, 8, 8), "A single 62 ms gap must not trigger a breach");

        // 2. Two 65 ms gaps in 10 runs -> triggers breach
        scheduled.clear();
        currentOnline.clear();
        currentOnline.add(pTwoOverruns);
        sampler.runRound();
        ScheduledProbe overrunProbe = scheduled.get(pTwoOverruns);
        overrunProbe.runCallback.run(); // run 1
        clock.addAndGet(65 * MS);
        overrunProbe.runCallback.run(); // run 2 (overrun 1)
        clock.addAndGet(65 * MS);
        overrunProbe.runCallback.run(); // run 3 (overrun 2 -> breach!)
        for (int r = 4; r <= 10; r++) {
            clock.addAndGet(50 * MS);
            overrunProbe.runCallback.run();
        }
        assertTrue(sampler.isCellBreached(worldId, 8, 8), "Two > 60 ms gaps in 10 ticks must flag a breach");

        // 3. Single 110 ms gap (> 100 ms) -> triggers breach immediately
        scheduled.clear();
        currentOnline.clear();
        currentOnline.add(pSingleSevere);
        sampler.runRound();
        ScheduledProbe severeProbe = scheduled.get(pSingleSevere);
        severeProbe.runCallback.run(); // run 1
        clock.addAndGet(110 * MS);
        severeProbe.runCallback.run(); // run 2 (> 100 ms)
        for (int r = 3; r <= 10; r++) {
            clock.addAndGet(50 * MS);
            severeProbe.runCallback.run();
        }
        assertTrue(sampler.isCellBreached(worldId, 8, 8), "A single > 100 ms gap must flag a breach");

        // 4. Player retirement mid-window cleans up active probe immediately
        scheduled.clear();
        currentOnline.clear();
        currentOnline.add(pRetiring);
        sampler.runRound();
        assertEquals(1, sampler.activeProbeCount());
        ScheduledProbe retiringProbe = scheduled.get(pRetiring);
        retiringProbe.runCallback.run(); // run 1
        retiringProbe.retiredCallback.run();
        assertEquals(0, sampler.activeProbeCount(), "Retired player probe must be removed immediately");
        assertTrue(retiringProbe.cancelled.get());

        // 5. Missed sample keeps reading for one round (2 s), then becomes STALE after > 2 rounds (> 4 s)
        clock.addAndGet(EntitySchedulerSampler.ROUND_INTERVAL_NANOS);
        assertEquals(1, sampler.sampledPopulation(worldId, 8, 8), "Reading stays valid for one missed round");
        clock.addAndGet(EntitySchedulerSampler.ROUND_INTERVAL_NANOS * 2);
        assertEquals(-1, sampler.sampledPopulation(worldId, 8, 8), "Reading becomes STALE (-1) after > 1 missed round");
    }

    @Test
    @DisplayName("Routes cancelAndClear and Bukkit.getOnlinePlayers exceptions through PlatformHookBitComponent.recordStateSamplerDegraded")
    void routesCancelAndOnlinePlayerExceptionsToPlatformHookBitComponent() {
        PlatformHookBitComponent.reset();
        try {
            AtomicLong clock = new AtomicLong(SEC);
            UUID pThrowingCancel = UUID.randomUUID();
            EntitySchedulerSampler cancelSampler = new EntitySchedulerSampler(
                    clock::get,
                    () -> true,
                    () -> List.of(pThrowingCancel),
                    Collections::emptyList,
                    (wId, w, cx, cz) -> false,
                    (playerId, runCb, retiredCb) -> () -> {
                        throw new IllegalStateException("cancel failed");
                    }
            );

            assertEquals(1, cancelSampler.runRound());
            cancelSampler.shutdown();
            assertEquals(1L, PlatformHookBitComponent.samplerFallbackCount());
            assertTrue(PlatformHookBitComponent.lastSamplerDetail()
                    .contains("EntitySchedulerSampler#cancelAndClear (IllegalStateException: cancel failed)"));

            // Production constructor when Bukkit.server is null (unit test context) trips getOnlinePlayers catch
            System.setProperty("rtp.governor.observe", "true");
            try {
                EntitySchedulerSampler prodSampler = new EntitySchedulerSampler(
                        null, clock::get, Collections::emptyList);
                assertEquals(0, prodSampler.runRound());
                assertEquals(2L, PlatformHookBitComponent.samplerFallbackCount());
                assertTrue(PlatformHookBitComponent.lastSamplerDetail()
                        .contains("EntitySchedulerSampler#getOnlinePlayers"));
            } finally {
                System.clearProperty("rtp.governor.observe");
            }
        } finally {
            PlatformHookBitComponent.reset();
        }
    }

    private static void driveAllTenTicks(Map<UUID, ScheduledProbe> probes, AtomicLong clock, long stepNanos) {
        for (int run = 1; run <= EntitySchedulerSampler.RUNS_PER_PLAYER; run++) {
            if (run > 1) {
                clock.addAndGet(stepNanos);
            }
            for (ScheduledProbe p : probes.values()) {
                p.runCallback.run();
            }
        }
    }
}
