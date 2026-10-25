package io.github.dailystruggle.rtp.common.predict;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit verification for {@link WorkAttributionLedger}.
 *
 * @governedBy ADR-087
 * @governedBy ADR-115
 * @governedBy ADR-124
 */
@DisplayName("ADR-124: WorkAttributionLedger Scope Consumers, Dual-EWMA Cost Attribution, and Bounded Eviction")
class WorkAttributionLedgerTest {

    private static final long MS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final long SEC = TimeUnit.SECONDS.toNanos(1);
    private static final long MIN = TimeUnit.MINUTES.toNanos(1);

    private WorkAttributionLedger<UUID> ledger;

    @BeforeEach
    void setUp() {
        ledger = new WorkAttributionLedger<>();
    }

    @Test
    @DisplayName("Constructor validates positive capacities, windows, and half-lives")
    void constructorValidation() {
        assertThrows(IllegalArgumentException.class,
                () -> new WorkAttributionLedger<>(0, 10, SEC, SEC, SEC, SEC, null));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkAttributionLedger<>(10, 0, SEC, SEC, SEC, SEC, null));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkAttributionLedger<>(10, 10, 0L, SEC, SEC, SEC, null));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkAttributionLedger<>(10, 10, SEC, 0L, SEC, SEC, null));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkAttributionLedger<>(10, 10, SEC, SEC, 0L, SEC, null));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkAttributionLedger<>(10, 10, SEC, SEC, SEC, 0L, null));
    }

    @Test
    @DisplayName("distinctConsumers floors at 1, ignores null actors, caps at maxScopeConsumers, and expires after window")
    void distinctConsumersWindowAndCap() {
        WorkAttributionLedger<String> custom = new WorkAttributionLedger<>(
                16, 3, 10 * MIN, 30 * MIN, 30 * SEC, 10 * MIN, String::compareTo);

        assertEquals(1, custom.distinctConsumers("overworld", 0L));
        assertEquals(1, custom.distinctConsumers(null, 0L));

        custom.recordConsumption("overworld", null, 5 * MS, SEC);
        assertEquals(1, custom.distinctConsumers("overworld", SEC));
        assertEquals(0, custom.size());

        custom.recordConsumption("overworld", "p1", 2 * MS, 10 * SEC);
        custom.recordConsumption("overworld", "p2", 2 * MS, 20 * SEC);
        custom.recordConsumption("overworld", "p3", 2 * MS, 30 * SEC);
        assertEquals(3, custom.distinctConsumers("overworld", 30 * SEC));

        // 4th distinct consumer evicts oldest (p1) to stay capped at maxScopeConsumers = 3
        custom.recordConsumption("overworld", "p4", 2 * MS, 40 * SEC);
        assertEquals(3, custom.distinctConsumers("overworld", 40 * SEC));

        // Refresh p2 at 5 min; at 10m + 35s only p2 and p4 remain within the 10-minute window
        custom.recordConsumption("overworld", "p2", 2 * MS, 5 * MIN);
        assertEquals(2, custom.distinctConsumers("overworld", 10 * MIN + 35 * SEC));

        // After 16 minutes all consumers expire -> floored at 1
        assertEquals(1, custom.distinctConsumers("overworld", 16 * MIN));
    }

    @Test
    @DisplayName("Dual-EWMA attributed cost scales linearly and decays at short and long half-lives")
    void dualEwmaHalfLifeDecayAndLinearity() {
        UUID light = UUID.randomUUID();
        UUID heavy = UUID.randomUUID();

        ledger.recordCharge(light, 10 * MS, 0L);
        ledger.recordCharge(heavy, 20 * MS, 0L);

        WorkAttributionLedger.AttributionEntry<UUID> light0 = ledger.entry(light, 0L);
        WorkAttributionLedger.AttributionEntry<UUID> heavy0 = ledger.entry(heavy, 0L);
        assertNotNull(light0);
        assertNotNull(heavy0);

        assertEquals(2.0 * light0.shortEwmaMsPerMin(), heavy0.shortEwmaMsPerMin(), 1e-6);
        assertEquals(2.0 * light0.longEwmaMsPerMin(), heavy0.longEwmaMsPerMin(), 1e-6);
        assertEquals(2.0 * light0.costMsPerMin(), heavy0.costMsPerMin(), 1e-6);
        assertEquals(0.5 * (light0.shortEwmaMsPerMin() + light0.longEwmaMsPerMin()), light0.costMsPerMin(), 1e-9);

        WorkAttributionLedger.AttributionEntry<UUID> light30s = ledger.entry(light, 30 * SEC);
        assertNotNull(light30s);
        assertEquals(0.5 * light0.shortEwmaMsPerMin(), light30s.shortEwmaMsPerMin(), 1e-6);

        WorkAttributionLedger.AttributionEntry<UUID> light10m = ledger.entry(light, 10 * MIN);
        assertNotNull(light10m);
        assertEquals(0.5 * light0.longEwmaMsPerMin(), light10m.longEwmaMsPerMin(), 1e-6);
    }

    @Test
    @DisplayName("LRU eviction enforces maxActorEntries and idle entries expire after idleExpiryNanos")
    void lruEvictionAndIdleExpiry() {
        WorkAttributionLedger<Integer> small = new WorkAttributionLedger<>(
                4, 4, 10 * MIN, 30 * MIN, 30 * SEC, 10 * MIN, Integer::compareTo);

        small.recordCharge(1, 5 * MS, 1_000L);
        small.recordCharge(2, 5 * MS, 2_000L);
        small.recordCharge(3, 5 * MS, 3_000L);
        small.recordCharge(4, 5 * MS, 4_000L);
        assertEquals(4, small.size());

        // Refresh actor 1 so actor 2 becomes the least-recently-active
        small.recordCharge(1, 5 * MS, 5_000L);
        small.recordCharge(5, 5 * MS, 6_000L);
        assertEquals(4, small.size());
        assertNull(small.entry(2, 6_000L));
        assertNotNull(small.entry(1, 6_000L));

        // Idle expiry after 30 minutes
        long expiredAt = 6_000L + 30 * MIN + SEC;
        assertNull(small.entry(1, expiredAt));
        assertEquals(0, small.activeActorCount(expiredAt));
        assertEquals(0.0, small.costP99MsPerMin(expiredAt), 1e-9);
    }

    @Test
    @DisplayName("topActors, costP99MsPerMin, and reset order and clear state accurately")
    void topActorsP99AndReset() {
        UUID u1 = new UUID(0L, 1L);
        UUID u2 = new UUID(0L, 2L);
        UUID u3 = new UUID(0L, 3L);

        ledger.recordCharge(u1, 10 * MS, SEC);
        ledger.recordCharge(u2, 50 * MS, SEC);
        ledger.recordCharge(u3, 25 * MS, SEC);

        List<WorkAttributionLedger.AttributionEntry<UUID>> top2 = ledger.topActors(2, SEC);
        assertEquals(2, top2.size());
        assertEquals(u2, top2.get(0).actorId());
        assertEquals(u3, top2.get(1).actorId());
        assertEquals(top2.get(0).costMsPerMin(), ledger.costP99MsPerMin(SEC), 1e-6);
        assertTrue(ledger.attributedCostMsPerMin(u2, SEC) > ledger.attributedCostMsPerMin(u1, SEC));

        ledger.reset();
        assertEquals(0, ledger.size());
        assertTrue(ledger.topActors(5, SEC).isEmpty());
        assertTrue(ledger.topActors(0, SEC).isEmpty());
        assertTrue(ledger.topActors(5, SEC, null).isEmpty());
        assertNull(ledger.entry(null, SEC));
        assertEquals(0.0, ledger.attributedCostMsPerMin(null, SEC), 1e-9);
        assertEquals(WorkAttributionLedger.DEFAULT_MAX_ACTOR_ENTRIES, ledger.maxActorEntries());
        assertEquals(WorkAttributionLedger.DEFAULT_MAX_SCOPE_CONSUMERS, ledger.maxScopeConsumers());
        assertEquals(WorkAttributionLedger.DEFAULT_SCOPE_WINDOW_NANOS, ledger.scopeWindowNanos());
        assertEquals(WorkAttributionLedger.DEFAULT_IDLE_EXPIRY_NANOS, ledger.idleExpiryNanos());
        assertEquals(WorkAttributionLedger.DEFAULT_SHORT_HALF_LIFE_NANOS, ledger.shortHalfLifeNanos());
        assertEquals(WorkAttributionLedger.DEFAULT_LONG_HALF_LIFE_NANOS, ledger.longHalfLifeNanos());
    }
}
