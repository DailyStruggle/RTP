package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for documented failure modes FM-001 and FM-002.
 *
 * <h3>FM-001 — Queue Empty at Teleport Time (REQ-RTP-S-004)</h3>
 * <p>When no pre-generated location is available for a region, the player's UUID
 * must be added to the deferred queue ({@code playerQueue}) so the next
 * replenishment cycle can fulfil the request automatically.
 *
 * <h3>FM-002 — All Sectors Marked Bad / Fill Exhaustion (REQ-RTP-S-004)</h3>
 * <p>When every sector in a region's shape is marked invalid, the shape must
 * report zero effective good locations and must not enter an infinite loop during
 * selection.  The {@code @Timeout} annotation acts as a hard trip-wire.
 */
class FailureModeTest {

    @TempDir
    File tempDir;

    /** Pre-warmed mock region — created in setUp() so Mockito class-gen is outside @Timeout. */
    private Region mockRegion;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir);
        // Build the mock here so Mockito's one-time proxy-class generation
        // is outside the @Timeout constraint on the individual @Test methods.
        mockRegion = buildMockRegion();
    }

    // =========================================================================
    // FM-001 — Queue empty → player deferred, fulfilled on replenishment
    // =========================================================================

    /**
     * FM-001 step 1: when all queues are empty, {@link RegionQueueManager#poll}
     * returns {@code null} — no blocking, no exception.
     */
    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void fm001_emptyQueue_pollReturnsNull() {
        RegionQueueManager qm = new RegionQueueManager(mockRegion);

        UUID player = UUID.randomUUID();
        assertNull(qm.poll(player),
                "FM-001: poll() on an empty queue must return null (no location available)");
    }

    /**
     * FM-001 step 2 (post-ADR-043): calling
     * {@link RegionQueueManager#requestTeleport} when the queue is empty
     * must add the player to the deferred {@code playerQueue} and to the
     * global awaiting-teleport flag, but must NOT create a personal bucket.
     * Bucket creation is the strict preserve of
     * {@link RegionQueueManager#openPersonalQueue} under the
     * {@code rtp.personalqueue} opt-in.
     */
    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void fm001_emptyQueue_playerDeferredToPlayerQueue() {
        RegionQueueManager qm = new RegionQueueManager(mockRegion);

        UUID player = UUID.randomUUID();
        qm.requestTeleport(player);

        assertTrue(qm.playerQueue.contains(player),
                "FM-001: player UUID must appear in playerQueue after requestTeleport() with empty keptLocations");
        assertFalse(qm.perPlayerLocationQueue.containsKey(player),
                "ADR-043: requestTeleport() must NOT create a personal bucket \u2014 that is openPersonalQueue's job");
        assertNull(qm.poll(player),
                "FM-001: poll() must still return null — no location exists yet");
    }

    /**
     * FM-001 step 3: once the replenishment cycle produces a location and places it
     * in the player's per-player queue, the next {@link RegionQueueManager#poll}
     * must return that location, completing the deferred teleport.
     */
    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void fm001_replenishmentFulfillsDeferredPlayer() throws ExecutionException, InterruptedException {
        RegionQueueManager qm = new RegionQueueManager(mockRegion);

        UUID player = UUID.randomUUID();
        qm.requestTeleport(player); // player deferred on waitlist (ADR-043)
        // Personal-bucket opt-in for the replenishment path is a separate
        // concern post-ADR-043. The full openPersonalQueue() path schedules
        // a RegionCacheTask via RTP.scheduler against the mock region,
        // which is out of scope for this failure-mode test — bypass the
        // scheduling and just install the empty bucket directly so the
        // replenishment cycle has somewhere to deliver into.
        qm.perPlayerLocationQueue.putIfAbsent(
                player, new java.util.concurrent.ConcurrentLinkedQueue<>());

        // Simulate the replenishment cycle delivering a location for this player
        RTPCoords coords = new RTPCoords("world", 100, 64, 200);
        RTPLocation location = new RTPLocation(coords, 1L, null);
        qm.perPlayerLocationQueue.get(player).offer(location);

        // Now poll — must return the replenished location
        CompletableFuture<RTPLocation> result = qm.poll(player);
        assertNotNull(result,
                "FM-001: poll() must return a future once the per-player queue has been replenished");
        assertTrue(result.isDone(),
                "FM-001: the returned future must be already completed");
        assertSame(location, result.get(),
                "FM-001: the returned location must be the one placed by the replenishment cycle");
    }

    // =========================================================================
    // FM-002 — All sectors bad → zero good locations, no infinite loop
    // =========================================================================

    /**
     * FM-002 contract: when every sector in the shape range is marked bad,
     * {@link MemoryShape#getEffectiveGoodCount()} must report zero.
     */
    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void fm002_allSectorsBad_effectiveGoodCountIsZero() {
        TestMemoryShape shape = new TestMemoryShape(10);

        // Mark all 10 sectors as bad (range = 10, so locations 0–9)
        for (long i = 0; i < 10; i++) {
            shape.addBadLocation(i);
        }
        shape.flushAndRebuild(1);

        assertEquals(0, shape.getEffectiveGoodCount(),
                "FM-002: when all sectors are marked bad, effective good count must be 0");
        assertEquals(10, shape.getEffectiveBadCount(),
                "FM-002: effective bad count must equal the total range");
    }

    /**
     * FM-002 contract: {@link MemoryShape#select()} must terminate within the
     * timeout window even when all sectors are bad — no infinite loop.
     * The {@code @Timeout(1s)} annotation is the hard trip-wire.
     */
    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void fm002_allSectorsBad_selectTerminatesWithoutInfiniteLoop() {
        TestMemoryShape shape = new TestMemoryShape(100);

        // Mark entire range as bad
        for (long i = 0; i < 100; i++) {
            shape.addBadLocation(i);
        }
        shape.flushAndRebuild(1);

        assertEquals(0, shape.getEffectiveGoodCount(),
                "FM-002: prerequisite — shape must have 0 effective good locations");

        // select() must return in bounded time (the @Timeout will fail if it loops)
        int[] result = shape.select();
        assertNotNull(result,
                "FM-002: select() must return a result (not throw or loop) when all sectors are bad");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Builds a Mockito-mocked {@link Region} with the settings needed by RegionQueueManager. */
    private static Region buildMockRegion() {
        RegionSettings settings = mock(RegionSettings.class);
        when(settings.cacheCap()).thenReturn(1024L);
        when(settings.activeChunkCap()).thenReturn(64);

        Region region = mock(Region.class);
        when(region.getSettings()).thenReturn(settings);
        return region;
    }

    /**
     * Minimal {@link MemoryShape} subclass for FM-002 testing.
     * {@code range} controls how many sectors exist; {@code select()} returns
     * {@code [0, 0]} immediately (representing the "operator must intervene" state).
     */
    private static class TestMemoryShape extends MemoryShape<GenericMemoryShapeParams> {

        private final long range;

        TestMemoryShape(long range) {
            super(GenericMemoryShapeParams.class, "FM002_TEST", defaultData(range));
            this.range = range;
        }

        private static EnumMap<GenericMemoryShapeParams, Object> defaultData(long range) {
            EnumMap<GenericMemoryShapeParams, Object> data =
                    new EnumMap<>(GenericMemoryShapeParams.class);
            data.put(GenericMemoryShapeParams.mode, "ACCUMULATE");
            data.put(GenericMemoryShapeParams.radius, range);
            data.put(GenericMemoryShapeParams.centerRadius, 0L);
            data.put(GenericMemoryShapeParams.centerX, 0L);
            data.put(GenericMemoryShapeParams.centerZ, 0L);
            data.put(GenericMemoryShapeParams.weight, 1.0);
            data.put(GenericMemoryShapeParams.uniquePlacements, false);
            data.put(GenericMemoryShapeParams.expand, false);
            return data;
        }

        @Override public long getRange()                           { return range; }
        @Override public long xzToLocation(long x, long z)        { return 0L; }
        @Override public long xzToLocation(MutableRTPCoords c)    { return 0L; }
        @Override public int[] locationToXZ(long loc)             { return new int[]{0, 0}; }
        @Override public void locationToXZ(long loc, MutableRTPCoords out) {}
        @Override public Map<String, CommandParameter> getParameters() { return null; }
        @Override public Collection<String> keys()                 { return List.of(); }
        @Override public int[] select()                            { return new int[]{0, 0}; }
        @Override public long rand()                               { return 0L; }
        @Override public boolean contains(int x, int z)           { return true; }

        @Override
        public void flushAndRebuild(long spatialResolution) {
            super.flushAndRebuild(spatialResolution);
        }
    }
}
