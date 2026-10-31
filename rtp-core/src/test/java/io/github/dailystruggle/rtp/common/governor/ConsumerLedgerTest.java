package io.github.dailystruggle.rtp.common.governor;

import io.github.dailystruggle.rtp.common.selection.SelectionAPI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit and property verification for {@link ConsumerLedger} (ADR-124 Sections 1, 5, 7).
 */
@DisplayName("ADR-124: ConsumerLedger per-region n_r, dual-EWMA player cost a_p, 4096-entry bound, and expiry")
class ConsumerLedgerTest {

    private static final long MS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final long SEC = TimeUnit.SECONDS.toNanos(1);
    private static final long MIN = TimeUnit.MINUTES.toNanos(1);

    private ConsumerLedger ledger;

    @BeforeEach
    void setUp() {
        ledger = new ConsumerLedger();
    }

    @Test
    @DisplayName("distinctConsumers floors at 1, ignores null pregen, and expires after 10 minutes")
    void distinctConsumersWindowAndNullPregen() {
        assertEquals(1, ledger.distinctConsumers("overworld", 0L));
        assertEquals(1, ledger.distinctConsumers(null, 0L));

        // Pregeneration (null playerId) never enters n_r or player ledger
        ledger.recordConsumption("overworld", null, 5 * MS, SEC);
        assertEquals(1, ledger.distinctConsumers("overworld", SEC));
        assertEquals(0, ledger.size());

        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        UUID p3 = UUID.randomUUID();
        ledger.recordConsumption("overworld", p1, 2 * MS, 10 * SEC);
        ledger.recordConsumption("overworld", p2, 2 * MS, 20 * SEC);
        ledger.recordConsumption("overworld", p3, 2 * MS, 30 * SEC);
        assertEquals(3, ledger.distinctConsumers("overworld", 30 * SEC));

        // Repeated consumption by same player refreshes timestamp without inflating n_r
        ledger.recordConsumption("overworld", p1, 2 * MS, 5 * MIN);
        assertEquals(3, ledger.distinctConsumers("overworld", 5 * MIN));

        // At 10m + 25s, p2 (20s) has expired (> 10m), while p3 (30s) and p1 (5m) remain active
        long tCheck = 10 * MIN + 25 * SEC;
        assertEquals(2, ledger.distinctConsumers("overworld", tCheck));

        // After 16 minutes, all have expired -> floored back to 1
        assertEquals(1, ledger.distinctConsumers("overworld", 16 * MIN));
    }

    @Test
    @DisplayName("dual-EWMA attributed cost decays with 30 s and 10 min half-lives and scales linearly")
    void dualEwmaHalfLifeDecayAndLinearity() {
        UUID pLight = UUID.randomUUID();
        UUID pHeavy = UUID.randomUUID();

        // Charge 10 ms to pLight and 20 ms to pHeavy at t = 0
        ledger.recordPlayerCharge(pLight, 10 * MS, 0L);
        ledger.recordPlayerCharge(pHeavy, 20 * MS, 0L);

        ConsumerLedger.ConsumerCostEntry light0 = ledger.playerEntry(pLight, 0L);
        ConsumerLedger.ConsumerCostEntry heavy0 = ledger.playerEntry(pHeavy, 0L);
        assertNotNull(light0);
        assertNotNull(heavy0);

        // Linear scaling: 2x charge produces 2x attributed cost and EWMAs
        assertEquals(2.0 * light0.ewma30sMsPerMin(), heavy0.ewma30sMsPerMin(), 1e-6);
        assertEquals(2.0 * light0.ewma10mMsPerMin(), heavy0.ewma10mMsPerMin(), 1e-6);
        assertEquals(2.0 * light0.costMsPerMin(), heavy0.costMsPerMin(), 1e-6);
        assertEquals(0.5 * (light0.ewma30sMsPerMin() + light0.ewma10mMsPerMin()), light0.costMsPerMin(), 1e-9);

        // At t = 30 s (1 short half-life), ewma30s is exactly halved
        ConsumerLedger.ConsumerCostEntry light30s = ledger.playerEntry(pLight, 30 * SEC);
        assertNotNull(light30s);
        assertEquals(0.5 * light0.ewma30sMsPerMin(), light30s.ewma30sMsPerMin(), 1e-6);

        // At t = 10 min (1 long half-life), ewma10m is exactly halved
        ConsumerLedger.ConsumerCostEntry light10m = ledger.playerEntry(pLight, 10 * MIN);
        assertNotNull(light10m);
        assertEquals(0.5 * light0.ewma10mMsPerMin(), light10m.ewma10mMsPerMin(), 1e-6);
    }

    @Test
    @DisplayName("bounded to 4096 player entries via LRU eviction and expires entries after 30 minutes idle")
    void boundedTo4096WithLruEvictionAndIdleExpiry() {
        UUID firstPlayer = new UUID(0L, 1L);
        UUID refreshedEarlyPlayer = new UUID(0L, 2L);

        ledger.recordPlayerCharge(firstPlayer, 5 * MS, 1_000L);
        ledger.recordPlayerCharge(refreshedEarlyPlayer, 5 * MS, 2_000L);

        for (int i = 3; i <= ConsumerLedger.MAX_PLAYER_ENTRIES; i++) {
            ledger.recordPlayerCharge(new UUID(0L, i), 5 * MS, i * 1_000L);
        }
        assertEquals(ConsumerLedger.MAX_PLAYER_ENTRIES, ledger.size());

        // Touch refreshedEarlyPlayer so firstPlayer (t=1_000L) is strictly the least-recently-active
        long refreshTime = (ConsumerLedger.MAX_PLAYER_ENTRIES + 1L) * 1_000L;
        ledger.recordPlayerCharge(refreshedEarlyPlayer, 5 * MS, refreshTime);

        // Insert 100 more distinct players beyond 4096
        for (int i = 1; i <= 100; i++) {
            UUID extra = new UUID(1L, i);
            ledger.recordPlayerCharge(extra, 5 * MS, refreshTime + i * 1_000L);
            assertTrue(ledger.size() <= ConsumerLedger.MAX_PLAYER_ENTRIES,
                    "ConsumerLedger must never exceed 4096 entries");
        }
        assertEquals(ConsumerLedger.MAX_PLAYER_ENTRIES, ledger.size());

        long latestTime = refreshTime + 100_000L;
        assertNull(ledger.playerEntry(firstPlayer, latestTime),
                "Least-recently-active entry must be evicted when cap is exceeded");
        assertNotNull(ledger.playerEntry(refreshedEarlyPlayer, latestTime),
                "Recently refreshed entry must be retained");

        // After 30 minutes + 1 second of inactivity, all entries expire
        long expiredTime = latestTime + ConsumerLedger.IDLE_EXPIRY_NANOS + SEC;
        assertNull(ledger.playerEntry(refreshedEarlyPlayer, expiredTime));
        assertEquals(0, ledger.activePlayerCount(expiredTime));
        assertEquals(0.0, ledger.costP99MsPerMin(expiredTime), 1e-9);
    }

    @Test
    @DisplayName("topConsumers, costP99MsPerMin, regionRefillShares, and SelectionAPI hooks")
    void topConsumersP99AndSelectionApiHooks() {
        ComputeGovernor gov = ComputeGovernor.active();
        gov.reset();
        try {
            gov.setUnitCostsForTesting(4 * MS, MS, MS);
            UUID u1 = UUID.randomUUID();
            UUID u2 = UUID.randomUUID();
            UUID u3 = UUID.randomUUID();

            ledger.recordPlayerCharge(u1, 10 * MS, SEC);
            ledger.recordPlayerCharge(u2, 50 * MS, SEC);
            ledger.recordPlayerCharge(u3, 25 * MS, SEC);

            List<ConsumerLedger.ConsumerCostEntry> top2 = ledger.topConsumers(2, SEC);
            assertEquals(2, top2.size());
            assertEquals(u2, top2.get(0).playerId());
            assertEquals(u3, top2.get(1).playerId());
            assertEquals(top2.get(0).costMsPerMin(), ledger.costP99MsPerMin(SEC), 1e-6);

            Map<String, Double> shares = ConsumerLedger.computeRegionRefillShares(
                    Map.of("main", 3.0, "nether", 1.0));
            assertEquals(0.75, shares.get("main"), 1e-6);
            assertEquals(0.25, shares.get("nether"), 1e-6);

            // SelectionAPI charge & consumption hooks forward to active ledger
            SelectionAPI.chargePlayer(u1, 8 * MS);
            SelectionAPI.recordConsumption("main", u1);
            assertTrue(ConsumerLedger.active().attributedCostMsPerMin(u1, System.nanoTime()) > 0.0);
            assertEquals(1, ConsumerLedger.active().distinctConsumers("main", System.nanoTime()));
            assertTrue(ConsumerLedger.active().evictPlayer(u1));
            assertEquals(0.0, ConsumerLedger.active().attributedCostMsPerMin(u1, System.nanoTime()), 1e-9);

            ledger.reset();
            assertEquals(0, ledger.size());
            assertTrue(ledger.topConsumers(5, SEC).isEmpty());
        } finally {
            gov.reset();
        }
    }
}
