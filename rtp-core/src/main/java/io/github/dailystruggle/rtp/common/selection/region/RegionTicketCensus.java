package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.ChunkReservation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.playerData.TeleportSessions;
import io.github.dailystruggle.rtp.common.selection.ActiveSelection;
import io.github.dailystruggle.rtp.common.tools.TicketCensusRegion;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;

/**
 * Live {@link TicketCensusRegion} view of a {@link Region} for the {@code MemoryTracker}
 * diagnostics sweep. Holds no state of its own; every call reads the region's queues.
 */
public final class RegionTicketCensus implements TicketCensusRegion {

    private final Region region;

    public RegionTicketCensus(Region region) {
        this.region = Objects.requireNonNull(region, "region");
    }

    /**
     * Views of every permanent and temporary region; empty before {@code ActiveSelection.get()} exists.
     */
    public static List<TicketCensusRegion> allRegions() {
        List<TicketCensusRegion> out = new ArrayList<>();
        if (ActiveSelection.get() == null) {
            return out;
        }
        for (Region r : ActiveSelection.get().permRegionLookup.values()) {
            out.add(new RegionTicketCensus(r));
        }
        for (Region r : ActiveSelection.get().tempRegions.values()) {
            out.add(new RegionTicketCensus(r));
        }
        return out;
    }

    @Override
    public long cacheBudget() {
        RegionSettings settings = region.getSettings();
        return settings.cacheCap() + settings.activeChunkCap() + settings.backlogCacheCap();
    }

    @Override
    public long activeChunkCap() {
        return region.getSettings().activeChunkCap();
    }

    @Override
    public long queuedLocations() {
        RegionQueueManager queues = region.queueManager;
        long total = queues.keptLocations.size() + (long) queues.unkeptLocations.size();
        if (queues.backlogLocations != null) {
            total += queues.backlogLocations.size();
        }
        return total;
    }

    @Override
    public long perPlayerLocations() {
        long total = 0;
        for (Queue<RTPLocation> queue : region.queueManager.getPerPlayerQueues()) {
            total += queue.size();
        }
        return total;
    }

    @Override
    public long reservedChunkTickets() {
        long tickets = 0;
        LockFreeLocationBuffer kept = region.queueManager.keptLocations;
        int keptSize = kept.size();
        for (int i = 0; i < keptSize; i++) {
            tickets += reservedChunks(kept.get(i));
        }
        for (Queue<RTPLocation> queue : region.queueManager.getPerPlayerQueues()) {
            for (RTPLocation loc : queue) {
                tickets += reservedChunks(loc);
            }
        }
        return tickets;
    }

    // Read-only census: the reservation is owned by the queued location and released by its
    // consumer, so it must not be closed here.
    @SuppressWarnings("resource")
    private static long reservedChunks(RTPLocation loc) {
        if (loc == null) {
            return 0;
        }
        ChunkReservation reservation = loc.reservation();
        if (reservation == null || reservation.getChunkSet() == null) {
            return 0;
        }
        return reservation.getChunkSet().chunks().size();
    }

    @Override
    public boolean generationInFlight() {
        return region.inFlightCalculations.get() > 0 || region.pendingKeptPromotions.get() > 0;
    }

    @Override
    public RTPWorld<?> world() {
        return region.getWorld();
    }

    @Override
    public void collectKeepAliveChunkKeys(Set<Long> out) {
        LockFreeLocationBuffer kept = region.queueManager.keptLocations;
        int keptSize = kept.size();
        for (int i = 0; i < keptSize; i++) {
            RTPLocation loc = kept.get(i);
            if (loc != null && loc.coords() != null) out.add(loc.coords().getChunkKey());
        }
        for (Queue<RTPLocation> queue : region.queueManager.getPerPlayerQueues()) {
            for (RTPLocation loc : queue) {
                if (loc != null && loc.coords() != null) out.add(loc.coords().getChunkKey());
            }
        }
        TeleportSessions sessions = TeleportSessions.current();
        if (sessions == null) {
            return;
        }
        Collection<TeleportData> pipelines = TeleportSessions.current().latestTeleportData.values();
        for (TeleportData data : pipelines) {
            if (data != null && data.selectedCoords != null && !data.completed && data.targetRegion == region) {
                out.add(data.selectedCoords.getChunkKey());
            }
        }
    }
}
