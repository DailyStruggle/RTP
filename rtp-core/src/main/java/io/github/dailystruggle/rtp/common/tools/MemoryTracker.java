package io.github.dailystruggle.rtp.common.tools;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPChunkManager;
import io.github.dailystruggle.rtp.common.selection.region.RTPLocation;
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
                        String label = tracked.getLabel();

                        // Unpack TrackedRTPTask to display the specific Runnable class leaking
                        Object target = tracked.getTarget();
                        if (target instanceof io.github.dailystruggle.rtp.api.scheduling.TrackedRTPTask) {
                          io.github.dailystruggle.rtp.api.scheduling.TrackedRTPTask tTask =
                                  (io.github.dailystruggle.rtp.api.scheduling.TrackedRTPTask) target;
                          if (tTask.getTask() != null) {
                            String innerName = tTask.getTask().getClass().getSimpleName();
                            // Fallback for anonymous classes or lambdas (e.g., FillTask$1)
                            if (innerName == null || innerName.isEmpty()) {
                              String fullName = tTask.getTask().getClass().getName();
                              innerName = fullName.substring(fullName.lastIndexOf('.') + 1);
                            }
                            label = "TrackedRTPTask[" + innerName + "]";
                          }
                        }

                        LOGGER.log(
                                Level.SEVERE,
                                "[RTP] Memory leak detected for object: {0}. Alive {1}ms past its expected lifespan.",
                                new Object[] {label, leakDuration});
                      }
                      return false;
                    });

    RTP rtp = RTP.getInstance();
    if (rtp != null) {
      long totalLocationQueueSize = 0;
      long totalPerPlayerLocationQueueSize = 0;
      long trackedTickets = 0;
      long totalCacheCap = 0;
      long totalActiveChunkCap = 0;

      // Combine permanent and temporary regions for full visibility
      List<Region> allRegions = new ArrayList<>(RTP.selectionAPI.permRegionLookup.values());
      allRegions.addAll(RTP.selectionAPI.tempRegions.values());

      for (Region region : allRegions) {
        RegionSettings settings = region.getSettings();
        totalCacheCap += settings.cacheCap();
        totalActiveChunkCap += settings.activeChunkCap();

        totalLocationQueueSize += region.queueManager.keptLocations.size();
        for (java.util.concurrent.ConcurrentLinkedQueue<RTPLocation> queue : region.queueManager.perPlayerLocationQueue.values()) {
          totalPerPlayerLocationQueueSize += queue.size();
        }

        // Only count chunks if the ChunkSet is actually keeping them loaded
        for (io.github.dailystruggle.rtp.api.world.ChunkReservation reservation : region.chunkManager.locAssChunks.values()) {
          trackedTickets += reservation.getChunkSet().chunks().size();
        }
      }

      long activeTickets = RTPChunkManager.ACTIVE_CHUNK_TICKETS.get();
      long totalLoads = RTPChunkManager.TOTAL_CHUNK_LOADS.get();

      // Enforce the cap on expected tickets to reveal locAssChunks hoarding
      long expectedTickets = Math.min(trackedTickets, totalActiveChunkCap);
      long discrepancy = activeTickets - expectedTickets;

      LOGGER.log(
              Level.INFO,
              "[RTP] Diagnostic: Locations=[Queue:{0}/{1}, PerPlayer:{2}], Chunks=[Active:{3}, Expected:{4}, Cap:{5}, Discrepancy:{6}]",
              new Object[] {
                      totalLocationQueueSize, totalCacheCap, totalPerPlayerLocationQueueSize,
                      activeTickets, expectedTickets, totalActiveChunkCap, discrepancy
              });

      // Focus leak detection on the positive discrepancy (orphaned tickets + cap overflows)
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
