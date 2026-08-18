package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.ChunkReservation;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ADR-043 - Personal-Queue Permission Semantics: Bucket-Only Opt-In tests.
 */
class ReqRtpAdr043PersonalQueueSeparationTest {

    @TempDir
    File tempDir;

    private Region mockRegion;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir);
        mockRegion = buildMockRegion();
        // RegionQueueManager construction may inherit state from previous
        // tests via the shared RTP singleton - be defensive.
        RTP.getInstance().queuedPlayers.clear();
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    @DisplayName("ADR-043: openPersonalQueue creates a bucket and does NOT enroll on the waitlist")
    void openPersonalQueue_isBucketOnly() {
        RegionQueueManager qm = new RegionQueueManager(mockRegion);
        UUID uuid = UUID.randomUUID();

        qm.openPersonalQueue(uuid);

        assertTrue(qm.perPlayerLocationQueue.containsKey(uuid),
                "openPersonalQueue must create the personal bucket");
        assertFalse(qm.playerQueue.contains(uuid),
                "openPersonalQueue must NOT enroll the uuid on the teleport waitlist (ADR-043)");
        assertFalse(RTP.getInstance().queuedPlayers.contains(uuid),
                "openPersonalQueue must NOT set the global awaiting-teleport flag (ADR-043)");
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    @DisplayName("ADR-043: openPersonalQueue is idempotent (re-open does not duplicate bucket or guard)")
    void openPersonalQueue_isIdempotent() {
        RegionQueueManager qm = new RegionQueueManager(mockRegion);
        UUID uuid = UUID.randomUUID();

        qm.openPersonalQueue(uuid);
        var firstBucket = qm.perPlayerLocationQueue.get(uuid);
        qm.openPersonalQueue(uuid);
        var secondBucket = qm.perPlayerLocationQueue.get(uuid);

        assertSame(firstBucket, secondBucket,
                "openPersonalQueue must reuse an existing bucket, not replace it");
        // No exception, no duplicate listener wiring side effects.
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    @DisplayName("ADR-043: requestTeleport enrolls on the waitlist and does NOT create a bucket")
    void requestTeleport_isWaitlistOnly() {
        RegionQueueManager qm = new RegionQueueManager(mockRegion);
        UUID uuid = UUID.randomUUID();

        qm.requestTeleport(uuid);

        assertTrue(qm.playerQueue.contains(uuid),
                "requestTeleport must enroll the uuid on the teleport waitlist");
        assertTrue(RTP.getInstance().queuedPlayers.contains(uuid),
                "requestTeleport must set the global awaiting-teleport flag");
        assertFalse(qm.perPlayerLocationQueue.containsKey(uuid),
                "requestTeleport must NOT create a personal bucket (ADR-043)");
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    @DisplayName("ADR-043: closePersonalQueue drains banked coordinates to unkeptLocations")
    void closePersonalQueue_drainsBucketToUnkept() {
        RegionQueueManager qm = new RegionQueueManager(mockRegion);
        UUID uuid = UUID.randomUUID();

        // Open the bucket directly (avoid scheduling) and bank a coordinate.
        qm.perPlayerLocationQueue.putIfAbsent(
                uuid, new java.util.concurrent.ConcurrentLinkedQueue<>());
        RTPCoords coords = new RTPCoords("world", 200, 64, 200);
        ChunkReservation reservation = mock(ChunkReservation.class);
        RTPLocation banked = new RTPLocation(coords, 1L, reservation);
        qm.perPlayerLocationQueue.get(uuid).offer(banked);

        long unkeptBefore = qm.unkeptLocations.size();

        qm.closePersonalQueue(uuid);

        assertFalse(qm.perPlayerLocationQueue.containsKey(uuid),
                "closePersonalQueue must remove the bucket entry");
        assertEquals(unkeptBefore + 1, qm.unkeptLocations.size(),
                "closePersonalQueue must return banked coordinates to unkeptLocations");
        assertFalse(qm.perPlayerInFlight.contains(uuid),
                "closePersonalQueue must clear the per-uuid push-on-open guard");
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    @DisplayName("ADR-043: closePersonalQueue does NOT remove the uuid from playerQueue or queuedPlayers")
    void closePersonalQueue_leavesWaitlistUntouched() {
        RegionQueueManager qm = new RegionQueueManager(mockRegion);
        UUID uuid = UUID.randomUUID();

        qm.requestTeleport(uuid);
        qm.perPlayerLocationQueue.putIfAbsent(
                uuid, new java.util.concurrent.ConcurrentLinkedQueue<>());

        qm.closePersonalQueue(uuid);

        assertTrue(qm.playerQueue.contains(uuid),
                "closePersonalQueue must not touch the teleport waitlist — that is RTPTeleportCancel's job");
        assertTrue(RTP.getInstance().queuedPlayers.contains(uuid),
                "closePersonalQueue must not clear the global awaiting-teleport flag");
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    @DisplayName("ADR-043: closePersonalQueue on an unknown uuid is a no-op (idempotent)")
    void closePersonalQueue_idempotentOnUnknownUuid() {
        RegionQueueManager qm = new RegionQueueManager(mockRegion);
        UUID uuid = UUID.randomUUID();

        // No bucket open, no waitlist entry, nothing in flight.
        assertDoesNotThrow(() -> qm.closePersonalQueue(uuid));
        assertFalse(qm.perPlayerLocationQueue.containsKey(uuid));
        assertFalse(qm.playerQueue.contains(uuid));
    }

    /** Mock {@link Region} with the minimum {@link RegionSettings} fields {@code RegionQueueManager} reads. */
    private static Region buildMockRegion() {
        RegionSettings settings = mock(RegionSettings.class);
        when(settings.cacheCap()).thenReturn(1024L);
        when(settings.activeChunkCap()).thenReturn(64);

        Region region = mock(Region.class);
        when(region.getSettings()).thenReturn(settings);
        return region;
    }
}
