package io.github.dailystruggle.rtp.common.tools;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.common.selection.region.Region;
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
   * Start tracking an object.
   *
   * @param target the object to track
   * @param label a descriptive name or ID
   * @param maxLifespan the expected maximum lifespan in milliseconds
   */
  public static void track(Object target, String label, long maxLifespan) {
    UUID uuid = UUID.randomUUID();
    TrackedObject trackedObject = new TrackedObject(target, label, maxLifespan);
    trackedObjects.put(uuid, trackedObject);
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
      int processingPlayersSize = rtp.processingPlayers.size();
      int latestTeleportDataSize = rtp.latestTeleportData.size();

      long totalLocationQueueSize = 0;
      long totalPerPlayerLocationQueueSize = 0;
      long totalLocAssChunksSize = 0;

      for (Region region : RTP.selectionAPI.permRegionLookup.values()) {
        totalLocationQueueSize += region.queueManager.locationQueue.size();
        for (java.util.concurrent.ConcurrentLinkedQueue<io.github.dailystruggle.rtp.common.selection.region.CachedLocation> queue : region.queueManager.perPlayerLocationQueue.values()) {
          totalPerPlayerLocationQueueSize += queue.size();
        }
        totalLocAssChunksSize += region.chunkManager.locAssChunks.size();
      }

      LOGGER.log(
          Level.INFO,
          "[RTP] Diagnostic check: processingPlayers={0}, latestTeleportData={1}, locationQueue={2}, perPlayerLocationQueue={3}, locAssChunks={4}",
          new Object[] {
            processingPlayersSize,
            latestTeleportDataSize,
            totalLocationQueueSize,
            totalPerPlayerLocationQueueSize,
            totalLocAssChunksSize
          });

      long activeTickets = ChunkSet.ACTIVE_CHUNK_TICKETS.get();
      long totalLoads = ChunkSet.TOTAL_CHUNK_LOADS.get();

      if (activeTickets > 0 && rtp.processingPlayers.isEmpty()) {
        double leakRate = (totalLoads > 0) ? ((double) activeTickets / totalLoads) * 100.0 : 0.0;
        LOGGER.log(
            Level.SEVERE,
            "[RTP] Chunk Ticket Leak Detected! Active: "
                + activeTickets
                + " | Lifetime Loads: "
                + totalLoads
                + " | Leak Rate: "
                + String.format("%.4f%%", leakRate)
                + ". A high rate indicates an internal plugin failure; a low rate indicates external event interference. "
                + "Diagnostic Note: If the server's total orphaned chunk count exceeds this plugin's lifetime loads (["
                + totalLoads
                + "]), this plugin is not the sole source of the memory leak.");
      }
    }
  }
}
