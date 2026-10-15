package io.github.dailystruggle.rtp.common.selection.region;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ADR-114 ImmediateLoadGovernor cost deduplication and admission semaphores")
class ImmediateLoadGovernorTest {

    @BeforeEach
    @AfterEach
    void cleanup() {
        ImmediateLoadGovernor.resetAll();
    }

    @Test
    @DisplayName("safe terrain under healthy ticks bypasses Anvil pre-filter directly to native load")
    void dedupThresholdBypassesInSafeTerrainHealthyTicks() {
        ImmediateLoadGovernor gov = ImmediateLoadGovernor.of("world_plains");
        // Defaults: C_file=2.5, C_native=15.0, C_sync=1.0, W_tick(20ms)=5.0
        // Denom = 15.0 + 5.0 * 1.0 = 20.0 -> Threshold = 2.5 / 20.0 = 0.125
        // P_reject default = 0.05 <= 0.125 -> Bypass!
        assertFalse(gov.shouldPrefilter(false, 20.0), "0.05 <= 0.125 threshold should bypass Anvil");
        assertEquals(1, gov.bypassedAnvilReads.sum());
        assertEquals(0, gov.prefilteredAnvilReads.sum());
    }

    @Test
    @DisplayName("hazardous terrain elevates rejection rate and engages off-thread Anvil pre-filter")
    void dedupThresholdEngagesInHazardousTerrain() {
        ImmediateLoadGovernor gov = ImmediateLoadGovernor.of("nether_wastes");
        // Elevate rejection rate past threshold (~0.125)
        for (int i = 0; i < 50; i++) {
            gov.recordOutcome(true);
        }
        assertTrue(gov.pReject() > 0.125, "rejection rate should rise above threshold: " + gov.pReject());
        assertTrue(gov.shouldPrefilter(false, 20.0), "elevated rejection should engage Anvil pre-filter");
        assertEquals(1, gov.prefilteredAnvilReads.sum());
        assertEquals(0, gov.bypassedAnvilReads.sum());
    }

    @Test
    @DisplayName("tick distress elevates tick weight and engages off-thread Anvil pre-filter even in safe terrain")
    void dedupThresholdEngagesUnderTickDistress() {
        ImmediateLoadGovernor gov = ImmediateLoadGovernor.of("world_busy");
        // MSPT > 35 engages pre-filtering to protect communal tick loop
        assertTrue(gov.shouldPrefilter(false, 45.0), "MSPT > 35ms should engage pre-filter");
        assertTrue(gov.shouldPrefilter(false, 36.0), "MSPT > 35ms should engage pre-filter");
    }

    @Test
    @DisplayName("predicate-constrained queries (/rtp biome:...) always engage off-thread Anvil pre-filter")
    void predicateConstrainedAlwaysPrefilters() {
        ImmediateLoadGovernor gov = ImmediateLoadGovernor.of("world_biome_query");
        assertTrue(gov.shouldPrefilter(true, 15.0), "predicate-constrained search must always pre-filter");
        assertEquals(1, gov.prefilteredAnvilReads.sum());
    }

    @Test
    @DisplayName("regional permit bounds in-flight generation to 1 per region")
    void regionalPermitLimitsToOnePerRegion() {
        ImmediateLoadGovernor gov = ImmediateLoadGovernor.of("regA");
        ImmediateLoadGovernor.GenerationPermit p1 = gov.tryAcquirePermit(20.0);
        assertNotNull(p1, "first permit in region must be granted");
        assertEquals(1, gov.regionalInFlight());
        assertEquals(1, ImmediateLoadGovernor.globalInFlight());

        ImmediateLoadGovernor.GenerationPermit p2 = gov.tryAcquirePermit(20.0);
        assertNull(p2, "second permit in same region must be refused (max 1 regional)");
        assertEquals(1, gov.regionalInFlight());
        assertEquals(1, gov.throttledGenerations.sum());

        p1.close();
        assertEquals(0, gov.regionalInFlight());
        assertEquals(0, ImmediateLoadGovernor.globalInFlight());

        ImmediateLoadGovernor.GenerationPermit p3 = gov.tryAcquirePermit(20.0);
        assertNotNull(p3, "permit can be acquired after previous is closed");
        p3.close();
    }

    @Test
    @DisplayName("global permit bounds server-wide in-flight generation to 2")
    void globalPermitLimitsToTwoServerWide() {
        ImmediateLoadGovernor gA = ImmediateLoadGovernor.of("regA");
        ImmediateLoadGovernor.GenerationPermit pA = gA.tryAcquirePermit(20.0);
        assertNotNull(pA);

        ImmediateLoadGovernor gB = ImmediateLoadGovernor.of("regB");
        ImmediateLoadGovernor.GenerationPermit pB = gB.tryAcquirePermit(20.0);
        assertNotNull(pB);

        ImmediateLoadGovernor.of("regC");
        ImmediateLoadGovernor.GenerationPermit pC = ImmediateLoadGovernor.of("regC").tryAcquirePermit(20.0);
        assertNull(pC, "third server-wide permit must be refused (max 2 global)");

        pA.close();
        ImmediateLoadGovernor.GenerationPermit pC2 = ImmediateLoadGovernor.of("regC").tryAcquirePermit(20.0);
        assertNotNull(pC2, "permit acquired after one global slot freed");
        pB.close();
        pC2.close();
    }

    @Test
    @DisplayName("tick distress damps global permit ceiling to 1 server-wide")
    void tickDistressDampsGlobalPermitToOne() {
        ImmediateLoadGovernor gA = ImmediateLoadGovernor.of("regA");
        ImmediateLoadGovernor.GenerationPermit pA = gA.tryAcquirePermit(40.0);
        assertNotNull(pA);

        ImmediateLoadGovernor gB = ImmediateLoadGovernor.of("regB");
        ImmediateLoadGovernor.GenerationPermit pB = gB.tryAcquirePermit(40.0);
        assertNull(pB, "under tick distress (MSPT > 35), global permit ceiling is damped to 1");

        pA.close();
    }

    @Test
    @DisplayName("async permit acquisition queues and wakes on permit release")
    void asyncPermitQueueFIFOAndRelease() throws ExecutionException, InterruptedException, TimeoutException {
        ImmediateLoadGovernor gov = ImmediateLoadGovernor.of("regAsync");
        ImmediateLoadGovernor.GenerationPermit p1 = gov.tryAcquirePermit(20.0);
        assertNotNull(p1);

        CompletableFuture<ImmediateLoadGovernor.GenerationPermit> future = gov.acquirePermit(() -> 20.0, 500);
        assertFalse(future.isDone(), "queued permit should be pending");

        p1.close(); // should trigger drainWaiters and satisfy future
        ImmediateLoadGovernor.GenerationPermit p2 = future.get(100, TimeUnit.MILLISECONDS);
        assertNotNull(p2, "queued permit should be granted upon release");
        p2.close();
    }

    @Test
    @DisplayName("async permit acquisition times out cleanly when permit is held past deadline")
    void asyncPermitQueueTimeout() {
        ImmediateLoadGovernor gov = ImmediateLoadGovernor.of("regTimeout");
        ImmediateLoadGovernor.GenerationPermit p1 = gov.tryAcquirePermit(20.0);
        assertNotNull(p1);

        CompletableFuture<ImmediateLoadGovernor.GenerationPermit> future = gov.acquirePermit(() -> 20.0, 50);
        assertThrows(ExecutionException.class, () -> future.get(200, TimeUnit.MILLISECONDS));
        assertTrue(gov.throttledGenerations.sum() >= 1);
        p1.close();
    }

    @Test
    @DisplayName("snapshotAll captures immutable state sorted by region")
    void snapshotAllReflectsState() {
        ImmediateLoadGovernor g1 = ImmediateLoadGovernor.of("beta");
        ImmediateLoadGovernor g2 = ImmediateLoadGovernor.of("alpha");
        g1.shouldPrefilter(false, 20.0);
        g2.shouldPrefilter(true, 20.0);

        var all = ImmediateLoadGovernor.snapshotAll();
        assertEquals(2, all.size());
        assertTrue(all.containsKey("alpha"));
        assertTrue(all.containsKey("beta"));
        assertEquals("PREFILTER", all.get("alpha").mode());
        assertEquals("BYPASS", all.get("beta").mode());
    }
}
