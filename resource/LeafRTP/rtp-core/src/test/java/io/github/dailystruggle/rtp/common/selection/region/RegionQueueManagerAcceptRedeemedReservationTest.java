package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies
 * {@link RegionQueueManager#acceptRedeemedReservation(UUID, RTPLocation)}
 * delegates to the per-player queue mechanism so a backend-side
 * {@code JoinTriggerSource} can pin a redeemed cross-server coord for the
 * immediately-following local {@code /rtp} dispatch.
 */
class RegionQueueManagerAcceptRedeemedReservationTest {

    @TempDir
    Path tempDir;

    private RegionQueueManager qm;
    private MockRTPWorld world;

    private static RTPLocation loc(MockRTPWorld w, int x, int z) {
        return new RTPLocation(new RTPCoords(w.name(), x, 64, z), 1L, null);
    }

    @BeforeEach
    void setUp() {
        MockRTPServerAccessor accessor = RTPTestSetup.install(tempDir.toFile());
        world = new MockRTPWorld("net_world");
        accessor.addWorld(world);
        RegionSettings settings = new RegionSettings(
                "net_region", world,
                new Circle(), new LinearAdjustor(new ArrayList<>()),
                false, false,
                16L, 1000L, 8L, 8,
                0.0, 1L, "", false);
        Region region = new Region("net_region", settings);
        qm = region.queueManager;
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void accept_nullPlayerId_returnsFalse() {
        RTPLocation redeemed = loc(world, 5, 5);
        assertFalse(qm.acceptRedeemedReservation(null, redeemed));
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void accept_nullLocation_returnsFalse() {
        assertFalse(qm.acceptRedeemedReservation(UUID.randomUUID(), null));
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void accept_populatesPerPlayerQueueWithExactCoord() {
        UUID playerId = UUID.randomUUID();
        RTPLocation redeemed = loc(world, 123, 456);

        assertTrue(qm.acceptRedeemedReservation(playerId, redeemed));

        ConcurrentLinkedQueue<RTPLocation> q = qm.getPerPlayerQueue(playerId);
        assertNotNull(q, "per-player queue must be allocated after accept");
        RTPLocation polled = q.poll();
        assertNotNull(polled);
        assertEquals(redeemed.coords(), polled.coords(),
                "polled coord must match the accepted redeemed coord");
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void accept_secondCallReplacesPriorEntry_singleSlotInvariant() {
        // enqueuePlayerLocation enforces max 1 slot per player; verify
        // a second accept evicts the prior entry (the proxy hop only
        // earmarks one coord per token, so two outstanding redeems for
        // the same player is a logic error we want to drop the older of).
        UUID playerId = UUID.randomUUID();
        RTPLocation first = loc(world, 1, 1);
        RTPLocation second = loc(world, 2, 2);

        assertTrue(qm.acceptRedeemedReservation(playerId, first));
        assertTrue(qm.acceptRedeemedReservation(playerId, second));

        ConcurrentLinkedQueue<RTPLocation> q = qm.getPerPlayerQueue(playerId);
        assertNotNull(q);
        RTPLocation polled = q.poll();
        assertNotNull(polled);
        assertEquals(second.coords(), polled.coords(),
                "second accept must evict the first per single-slot invariant");
        assertNull(q.poll(), "queue must contain at most one entry per player");
    }
}
