package io.github.dailystruggle.rtp.common.tools;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.selection.region.RTPLocation;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;

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

  static {
    // Inject the core untrack logic into the API
    RTPRunnable.untrackHook = MemoryTracker::untrack;
  }

  private MemoryTracker() {
    // Private constructor for utility class
  }

  private static boolean isSystemLoggingEnabled() {
    if (RTP.configs != null) {
      io.github.dailystruggle.rtp.common.configuration.ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.LoggingKeys> logging =
          (io.github.dailystruggle.rtp.common.configuration.ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.LoggingKeys>)
              RTP.configs.getParser(io.github.dailystruggle.rtp.common.configuration.enums.LoggingKeys.class);
      if (logging != null) {
        Object o = logging.getConfigValue(io.github.dailystruggle.rtp.common.configuration.enums.LoggingKeys.system_memory_tracker, true);
        if (o instanceof Boolean) {
          return (Boolean) o;
        } else if (o != null) {
          return Boolean.parseBoolean(o.toString());
        }
      }
    }
    return false; // default
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
                        Object actualTask = target; // Store reference to the unwrapped task

                        if (target instanceof io.github.dailystruggle.rtp.api.scheduling.TrackedRTPTask) {
                          io.github.dailystruggle.rtp.api.scheduling.TrackedRTPTask tTask =
                                  (io.github.dailystruggle.rtp.api.scheduling.TrackedRTPTask) target;
                          if (tTask.getTask() != null) {
                            actualTask = tTask.getTask();
                            String innerName = actualTask.getClass().getSimpleName();
                            // Fallback for anonymous classes or lambdas
                            if (innerName == null || innerName.isEmpty()) {
                              String fullName = actualTask.getClass().getName();
                              innerName = fullName.substring(fullName.lastIndexOf('.') + 1);
                            }
                            label = "TrackedRTPTask[" + innerName + "]";
                          }
                        }

                        if (isSystemLoggingEnabled()) {
                          LOGGER.log(
                                  Level.SEVERE,
                                  "[RTP] Memory leak detected for object: {0}. Alive {1}ms past its expected lifespan.",
                                  new Object[] {label, leakDuration});
                        }

                        // Active Cleanup Injection
                        if (actualTask instanceof io.github.dailystruggle.rtp.common.tasks.teleport.TeleportPipelineTask pipelineTask) {
                          // Force the abandoned pipeline into its CLEANUP phase
                          pipelineTask.setCancelled(true);

                          // Schedule it on the primary thread to safely release chunks
                          RTP.scheduler.runTask(pipelineTask);

                          // Return true to instantly purge it from the tracker
                          // to prevent infinite log looping
                          return true;
                        }
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
        totalCacheCap += settings.activeChunkCap();
        totalActiveChunkCap += settings.activeChunkCap();

        totalLocationQueueSize += region.queueManager.keptLocations.size();
        totalLocationQueueSize += region.queueManager.unkeptLocations.size();
        for (java.util.concurrent.ConcurrentLinkedQueue<RTPLocation> queue : region.queueManager.getPerPlayerQueues()) {
          totalPerPlayerLocationQueueSize += queue.size();
        }

        // 1. Count temporary in-flight generation chunks
        // (Tracking removed)

        // 2. Count chunks locked in the public hot queue
        int keptSize = region.queueManager.keptLocations.size();
        for (int i = 0; i < keptSize; i++) {
          RTPLocation loc = region.queueManager.keptLocations.get(i);
          if (loc != null && loc.reservation() != null && loc.reservation().getChunkSet() != null) {
            trackedTickets += loc.reservation().getChunkSet().chunks().size();
          }
        }

        // 3. Count chunks locked in private queues
        for (java.util.concurrent.ConcurrentLinkedQueue<RTPLocation> queue : region.queueManager.getPerPlayerQueues()) {
          for (RTPLocation loc : queue) {
            if (loc != null && loc.reservation() != null && loc.reservation().getChunkSet() != null) {
              trackedTickets += loc.reservation().getChunkSet().chunks().size();
            }
          }
        }
      }

      long activeTickets = 0;
      long totalLoads = 0;
      long pluginForced = 0;
      List<java.util.concurrent.CompletableFuture<Integer>> serverForcedFutures = new ArrayList<>();
      for (RTPWorld<?> world : RTP.serverAccessor.getRTPWorlds()) {
        activeTickets += world.activeChunkTickets.get();
        totalLoads += world.totalChunkLoads.get();
        serverForcedFutures.add(world.getServerForceLoadedCount());
        pluginForced += world.numForceLoaded();
      }

      long finalActiveTickets = activeTickets;
      long finalTotalLoads = totalLoads;
      long finalPluginForced = pluginForced;
      long finalTrackedTickets = trackedTickets;
      long finalTotalActiveChunkCap = totalActiveChunkCap;
      long finalTotalLocationQueueSize = totalLocationQueueSize;
      long finalTotalCacheCap = totalCacheCap;
      long finalTotalPerPlayerLocationQueueSize = totalPerPlayerLocationQueueSize;

      java.util.concurrent.CompletableFuture.allOf(serverForcedFutures.toArray(new java.util.concurrent.CompletableFuture[0])).thenAccept(v -> {
        long serverForced = 0;
        for (java.util.concurrent.CompletableFuture<Integer> future : serverForcedFutures) {
          serverForced += future.join();
        }

        // Enforce the cap on expected tickets to reveal locAssChunks hoarding
        long expectedTickets = Math.min(finalTrackedTickets, finalTotalActiveChunkCap);
        long discrepancy = finalActiveTickets - expectedTickets;

        if (isSystemLoggingEnabled()) {
          LOGGER.log(
                  Level.INFO,
                  "[RTP] Diagnostic: Locations=[Queue:{0}/{1}, PerPlayer:{2}], Chunks=[Tickets:{3}, Expected:{4}, PluginForced:{5}, ServerForced:{6}, Discrepancy:{7}]",
                  new Object[] {
                          finalTotalLocationQueueSize, finalTotalCacheCap, finalTotalPerPlayerLocationQueueSize,
                          finalActiveTickets, expectedTickets, finalPluginForced, serverForced, discrepancy
                  });
        }

        // Focus leak detection on the positive discrepancy (orphaned tickets + cap overflows)
        if (discrepancy > 0 && rtp.processingPlayers.isEmpty()) {
          double leakRate = (finalTotalLoads > 0) ? ((double) discrepancy / finalTotalLoads) * 100.0 : 0.0;
          if (isSystemLoggingEnabled()) {
            LOGGER.log(
                    Level.SEVERE,
                    "[RTP] Leak Alert: {0} orphaned chunk tickets detected. Leak Rate: {1}%. Executing GC...",
                    new Object[] { discrepancy, String.format("%.4f", leakRate) });
          }

          for (Region sweepRegion : allRegions) {
            // Gate the sweep to prevent race conditions with asynchronous location generation
            if (sweepRegion.inFlightCalculations.get() > 0) continue;

            // Store Long chunk keys instead of RTPCoords objects
            java.util.Set<Long> keepAliveKeys = new java.util.HashSet<>();

            // 1. Map public fast queue coordinates
            int keptSize = sweepRegion.queueManager.keptLocations.size();
            for (int i = 0; i < keptSize; i++) {
              RTPLocation loc = sweepRegion.queueManager.keptLocations.get(i);
              if (loc != null && loc.coords() != null) keepAliveKeys.add(loc.coords().getChunkKey());
            }

            // 2. Map private fast queue coordinates
            for (java.util.concurrent.ConcurrentLinkedQueue<RTPLocation> queue : sweepRegion.queueManager.getPerPlayerQueues()) {
              for (RTPLocation loc : queue) {
                if (loc != null && loc.coords() != null) keepAliveKeys.add(loc.coords().getChunkKey());
              }
            }

            // 3. Map pipeline teleport destinations
            for (io.github.dailystruggle.rtp.common.playerData.TeleportData data : RTP.getInstance().latestTeleportData.values()) {
              if (data != null && data.selectedCoords != null && !data.completed && data.targetRegion == sweepRegion) {
                keepAliveKeys.add(data.selectedCoords.getChunkKey());
              }
            }

            // 4. Audit locAssChunks and forcefully close unmapped reservations
            // (Tracking removed)
          }
        }
      });
    }
  }

  public static void untrack(UUID trackingId) {
    if (trackingId != null) {
      trackedObjects.remove(trackingId);
    }
  }
}
