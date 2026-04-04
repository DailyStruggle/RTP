package io.github.dailystruggle.rtp.common.tools;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Utility class for tracking memory leaks in objects. */
public class MemoryTracker {
  private static final Logger LOGGER = Logger.getLogger(MemoryTracker.class.getName());
  private static final ConcurrentHashMap<UUID, TrackedObject> trackedObjects =
      new ConcurrentHashMap<>();

  private MemoryTracker() {
    // Private constructor for utility class
  }

  /**
   * Resets the lifespan timer for a specifically tracked object
   */
  public static void updateTracking(UUID trackingId) {
    if (trackingId == null) return;
    TrackedObject obj = trackedObjects.get(trackingId);
    if (obj != null) {
      obj.reset();
    }
  }

  /**
   * Start tracking an object.
   *
   * @param target the object to track
   * @param label a descriptive name or ID
   * @param maxLifespan the expected maximum lifespan in milliseconds
   */
  public static UUID track(Object target, String label, long maxLifespan) {
    UUID uuid = UUID.randomUUID();
    TrackedObject trackedObject = new TrackedObject(target, label, maxLifespan);
    trackedObjects.put(uuid, trackedObject);
    return uuid; // Now returns the reference ID
  }

  /** Forcefully deregisters an object from the leak tracker immediately */
  public static void untrack(Object target) {
    if (target == null) return;
    trackedObjects.entrySet().removeIf(entry -> {
      TrackedObject tracked = entry.getValue();
      return tracked.isCollected() || tracked.matches(target);
    });
  }

  public static void runDiagnostics() {
    trackedObjects
        .entrySet()
        .removeIf(
            entry -> {
              TrackedObject tracked = entry.getValue();
              if (tracked.isCollected()) {
                return true;
              }
              if (tracked.isLeaking()) {
                long leakDuration = tracked.getLeakDuration();
                LOGGER.log(
                    Level.SEVERE,
                    "[RTP] Memory leak detected for object: {0}. Alive {1}ms past its expected lifespan.",
                    new Object[] {tracked.getLabel(), leakDuration});
              }
              return false;
            });

    RTP rtp = RTP.getInstance();
    if (rtp != null) {
      long totalLocationQueueSize = 0;
      long totalPerPlayerLocationQueueSize = 0;
      long totalExpectedTickets = 0;
      long totalCacheCap = 0;
      long totalActiveChunkCap = 0;

      // Combine permanent and temporary regions for full visibility
      List<Region> allRegions = new ArrayList<>(RTP.selectionAPI.permRegionLookup.values());
      allRegions.addAll(RTP.selectionAPI.tempRegions.values());

      for (Region region : allRegions) {
        RegionSettings settings = region.getSettings();
        totalCacheCap += settings.cacheCap();
        totalActiveChunkCap += settings.activeChunkCap();

        totalLocationQueueSize += region.queueManager.locationQueue.size();
        for (java.util.concurrent.ConcurrentLinkedQueue<io.github.dailystruggle.rtp.common.selection.region.CachedLocation> queue : region.queueManager.perPlayerLocationQueue.values()) {
          totalPerPlayerLocationQueueSize += queue.size();
        }

        // Only count chunks if the ChunkSet is actually keeping them loaded
        for (ChunkSet chunkSet : region.chunkManager.locAssChunks.values()) {
          if (chunkSet.keep()) {
            totalExpectedTickets += chunkSet.chunks.size();
          }
        }
      }

      long activeTickets = ChunkSet.ACTIVE_CHUNK_TICKETS.get();
      long totalLoads = ChunkSet.TOTAL_CHUNK_LOADS.get();
      long discrepancy = activeTickets - totalExpectedTickets;

      LOGGER.log(
              Level.INFO,
              "[RTP] Diagnostic: Locations=[Queue:{0}/{1}, PerPlayer:{2}], Chunks=[Active:{3}, Expected:{4}, Cap:{5}, Discrepancy:{6}]",
              new Object[] {
                      totalLocationQueueSize, totalCacheCap, totalPerPlayerLocationQueueSize,
                      activeTickets, totalExpectedTickets, totalActiveChunkCap, discrepancy
              });

      // Focus leak detection on the positive discrepancy (orphaned tickets)
      if (discrepancy > 0 && rtp.processingPlayers.isEmpty()) {
        double leakRate = (totalLoads > 0) ? ((double) discrepancy / totalLoads) * 100.0 : 0.0;
        LOGGER.log(
                Level.SEVERE,
                "[RTP] Leak Alert: {0} orphaned chunk tickets detected. Leak Rate: {1}%.",
                new Object[] { discrepancy, String.format("%.4f", leakRate) });
      }
    }
  }

  public static void untrack(UUID trackingId) {
    if (trackingId != null) {
      trackedObjects.remove(trackingId);
    }
  }
}
