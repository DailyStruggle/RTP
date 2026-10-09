package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.ChunkReservation;
import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Kept-queue entries must be resident when offered: consumers take them at once, so an entry
 * offered on ticket request makes the teleport wait on the platform load (S-002, S-005).
 */
@DisplayName("REQ-RTP-S-005: cold->hot promotion offers kept entries only once the chunk is resident")
class RegionKeptPromotionReadinessTest {

    @TempDir
    Path tempDir;

    private PendingTicketWorld world;
    private Region region;

    /** Ticket application completes only when the test says so. */
    static final class PendingTicketWorld extends MockRTPWorld {
        volatile CompletableFuture<Void> nextApply = new CompletableFuture<>();
        volatile boolean loaded = true;

        PendingTicketWorld(String name) {
            super(name);
            isChunkLoadedPredicate = k -> loaded;
        }

        @Override
        protected CompletableFuture<Void> setForceLoadedImpl(int cx, int cz, boolean forceLoad) {
            return forceLoad ? nextApply : CompletableFuture.completedFuture(null);
        }
    }

    @BeforeEach
    void setUp() {
        MockRTPServerAccessor accessor = RTPTestSetup.install(tempDir.toFile());
        accessor.setLocationGenerator(new LocationGenerator());
        world = new PendingTicketWorld("kept_ready_world");
        accessor.addWorld(world);
        RegionSettings settings = new RegionSettings(
                "kept_ready", world, new Circle(), new LinearAdjustor(new ArrayList<>()),
                false, false, 10L, 0L, 0L, 5, 0.0, 1L, "", false);
        region = new Region("kept_ready", settings);
    }

    private ChunkReservation reserve(int cx, int cz) throws Exception {
        ChunkSet set = world.getChunkAtAsync(cx, cz).get();
        return new ChunkReservation(set, world);
    }

    private RTPLocation cold(int cx, int cz) {
        return new RTPLocation(new RTPCoords(world.name(), cx * 16 + 8, 64, cz * 16 + 8), 1);
    }

    @Test
    void entryWaitsForTicketApplyBeforeKeptOffer() throws Exception {
        RTPLocation coldLoc = cold(3, 4);
        ChunkReservation res = reserve(3, 4);

        region.offerKeptWhenResident(coldLoc, coldLoc.coords(), res, 3, 4);

        assertEquals(0, region.queueManager.keptLocations.size(), "not offered while the ticket is pending");
        assertEquals(1, region.pendingKeptPromotions.get(), "capacity slot held while waiting");
        assertEquals(0, region.inFlightCalculations.get(),
                "the wait must not hold the in-flight slot that gates RegionCacheTask fill");

        world.nextApply.complete(null);

        assertEquals(1, region.queueManager.keptLocations.size());
        assertSame(res, region.queueManager.keptLocations.peek().reservation());
        assertEquals(0, region.pendingKeptPromotions.get());
        assertEquals(1, world.numForceLoaded(), "ticket kept for the queued entry");
    }

    @Test
    void nonResidentAfterApplyReleasesTicketAndReturnsToUnkept() throws Exception {
        world.loaded = false;
        world.nextApply.complete(null);
        RTPLocation coldLoc = cold(5, 6);

        region.offerKeptWhenResident(coldLoc, coldLoc.coords(), reserve(5, 6), 5, 6);

        assertEquals(0, region.queueManager.keptLocations.size());
        assertEquals(1, region.queueManager.unkeptLocations.size(), "location kept for a later promotion");
        assertEquals(0, world.numForceLoaded(), "S-002: ticket released");
        assertEquals(0, region.pendingKeptPromotions.get());
    }

    @Test
    void failedApplyReleasesTicketAndReturnsToUnkept() throws Exception {
        RTPLocation coldLoc = cold(7, 8);
        ChunkReservation res = reserve(7, 8);

        region.offerKeptWhenResident(coldLoc, coldLoc.coords(), res, 7, 8);
        world.nextApply.completeExceptionally(new IllegalStateException("apply failed"));

        assertEquals(0, region.queueManager.keptLocations.size());
        assertEquals(1, region.queueManager.unkeptLocations.size());
        assertEquals(0, world.numForceLoaded(), "S-002: ticket released");
        assertEquals(0, region.pendingKeptPromotions.get());
    }

    @Test
    void waitDoesNotCompleteSharedApplyFuture() throws Exception {
        RTPLocation coldLoc = cold(9, 9);
        ChunkReservation res = reserve(9, 9);
        CompletableFuture<Void> shared = res.readyFuture();

        region.offerKeptWhenResident(coldLoc, coldLoc.coords(), res, 9, 9);

        assertFalse(shared.isDone(), "the per-chunk apply future is shared; the wait must not complete it");
        world.nextApply.complete(null);
        assertEquals(1, region.queueManager.keptLocations.size());
    }
}
