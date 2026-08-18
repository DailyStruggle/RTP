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
import java.text.MessageFormat;
import java.util.logging.Level;

/** Utility class for tracking memory leaks in objects. */
public class MemoryTracker {
  private static final ConcurrentHashMap<UUID, TrackedObject> trackedObjects =
          new ConcurrentHashMap<>();

  private static void log(Level level, String pattern, Object... args) {
    RTP.log(level, MessageFormat.format(pattern, args));
  }

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
      // Diagram 03: TrackRes timer reset (e.g., pipeline stage transition).
      log(Level.FINER, "[RTP][Track] Lifespan reset: id={0}, label={1}",
              trackingId, obj.getLabel());
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
    // Diagram 03: TrackRes node - MemoryTracker.track() registers a freshly opened
    // ChunkReservation (or pipeline task) for the active GC safety net.
    log(Level.FINE,
            "[RTP][Track] Registered: id={0}, label={1}, maxLifespanMs={2}, totalTracked={3}",
            uuid, label, maxLifespan, trackedObjects.size());
    return uuid; // Now returns the reference ID
  }

  /** Forcefully deregisters an object from the leak tracker immediately */
  public static void untrack(Object target) {
    if (target == null) return;
    trackedObjects.entrySet().removeIf(entry -> {
      TrackedObject tracked = entry.getValue();
      boolean remove = tracked.isCollected() || tracked.matches(target);
      // Diagram 03: UntrackRes node - normal teardown path after CloseRes/DropTicket.
      if (remove) {
        log(Level.FINER,
                "[RTP][Track] Untracked: id={0}, label={1}, collected={2}",
                entry.getKey(), tracked.getLabel(), tracked.isCollected());
      }
      return remove;
    });
  }

  /**
   * Returns the current number of live entries in the tracker. Intended for
   * diagnostics and the {@code rtp test chunk-ticket} runtime probe
   * &mdash; not for production
   * decision-making, since the count can change concurrently. Visible in the
   * public API so that {@code rtp-plugin}'s test commands can assert lifecycle
   * correctness without reflection (REQ-RTP-S-002 positive-path coverage).
   */
  public static int trackedCount() {
    return trackedObjects.size();
  }

  /**
   * Clears every tracked entry. Intended for test isolation only: the tracker
   * is a process-wide static, so a unit test that asserts an absolute
   * {@code trackedCount()} (e.g. metrics-snapshot parity) must start from a
   * known-empty state rather than inherit entries leaked by earlier tests in
   * the same JVM. Not for production decision-making.
   */
  public static void reset() {
    trackedObjects.clear();
  }

  /**
   * Returns the count of tracked entries whose label equals {@code label}.
   * Used by {@code rtp test chunk-ticket} to isolate its own sentinels from
   * any unrelated live teleport activity on the server.
   */
  public static int trackedCountByLabel(String label) {
    if (label == null) return 0;
    int n = 0;
    for (TrackedObject t : trackedObjects.values()) {
      if (label.equals(t.getLabel())) n++;
    }
    return n;
  }

  public static void runDiagnostics() {
    // Spark-profiler tagged entry: dispatch through a method whose name matches the
    // documented allow-list tag so the async sampler attributes diagram-04 work
    // (the active-GC sweep) to a self-describing frame. See LESSONS_LEARNED.md
    // "Spark-tagged regions in the live pipeline" and RTPRunnable#sparkFrameName().
    rtp_active_gc_sweep();
  }

  // --- Spark-tagged bridge for diagram 04 (active-GC sweep). Renaming this method
  // --- breaks the documented Spark frame contract; update LESSONS_LEARNED.md if you
  // --- must rename it.
  private static void rtp_active_gc_sweep() {
    // Diagram 04: TimerTrigger -> FetchMap. Per-pulse heartbeat at FINE so operators
    // can confirm the active GC sweep is actually firing without enabling INFO spam.
    log(Level.FINE,
            "[RTP][GC] Sweep pulse start: tracked={0}",
            trackedObjects.size());
    trackedObjects
            .entrySet()
            .removeIf(
                    entry -> {
                      TrackedObject tracked = entry.getValue();
                      if (tracked.isCollected()) {
                        // Diagram 04: collected entry == healthy GC by JVM; trace at FINER.
                        log(Level.FINER,
                                "[RTP][GC] Untrack collected entry label={0}",
                                tracked.getLabel());
                        return true;
                      }
                      // Diagram 04: CheckTimeout per-entry trace (FINER). Healthy entries take the
                      // "No (Healthy, Loop Next)" branch silently; only log when verbose.
                      log(Level.FINER,
                              "[RTP][GC] CheckTimeout label={0} leaking={1}",
                              tracked.getLabel(), tracked.isLeaking());
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
                          log(Level.SEVERE,
                                  "[RTP] Memory leak detected for object: {0}. Alive {1}ms past its expected lifespan.",
                                  label, leakDuration);
                        }

                        // Active Cleanup Injection
                        if (actualTask instanceof io.github.dailystruggle.rtp.common.tasks.teleport.TeleportPipelineTask pipelineTask) {
                          // Diagram 04: ForceClose -> DecCounter -> Untrack. FINE because each occurrence
                          // represents a real cleanup action (cancellation + chunk release scheduling).
                          log(Level.FINE,
                                  "[RTP][GC] ForceClose stalled pipeline label={0} ageOverBudgetMs={1}",
                                  label, leakDuration);
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
        // Account for L3 backlog capacity in the global cache budget (ADR-028).
        totalCacheCap += settings.backlogCacheCap();
        totalActiveChunkCap += settings.activeChunkCap();

        totalLocationQueueSize += region.queueManager.keptLocations.size();
        totalLocationQueueSize += region.queueManager.unkeptLocations.size();
        if (region.queueManager.backlogLocations != null) {
          totalLocationQueueSize += region.queueManager.backlogLocations.size();
        }
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

      // Diagram 04: FetchMap -> FetchServer transition (Internal Sweep Complete).
      log(Level.FINE,
              "[RTP][GC] Internal sweep complete; querying native ticket counts (worlds={0})",
              serverForcedFutures.size());

      java.util.concurrent.CompletableFuture.allOf(serverForcedFutures.toArray(new java.util.concurrent.CompletableFuture[0])).thenAccept(v -> {
        long serverForced = 0;
        for (java.util.concurrent.CompletableFuture<Integer> future : serverForcedFutures) {
          serverForced += future.join();
        }

        // Enforce the cap on expected tickets to reveal locAssChunks hoarding
        long expectedTickets = Math.min(finalTrackedTickets, finalTotalActiveChunkCap);
        long discrepancy = finalActiveTickets - expectedTickets;

        if (isSystemLoggingEnabled()) {
          log(Level.INFO,
                  "[RTP] Diagnostic: Locations=[Queue:{0}/{1}, PerPlayer:{2}], Chunks=[Tickets:{3}, Expected:{4}, PluginForced:{5}, ServerForced:{6}, Discrepancy:{7}]",
                  finalTotalLocationQueueSize, finalTotalCacheCap, finalTotalPerPlayerLocationQueueSize,
                  finalActiveTickets, expectedTickets, finalPluginForced, serverForced, discrepancy);
        }

        // Focus leak detection on the positive discrepancy (orphaned tickets + cap overflows)
        if (discrepancy > 0 && rtp.processingPlayers.isEmpty()) {
          double leakRate = (finalTotalLoads > 0) ? ((double) discrepancy / finalTotalLoads) * 100.0 : 0.0;
          if (isSystemLoggingEnabled()) {
            log(Level.SEVERE,
                    "[RTP] Leak Alert: {0} orphaned chunk tickets detected. Leak Rate: {1}%. Executing GC...",
                    discrepancy, String.format("%.4f", leakRate));
          }

          // Build a per-world map of keep-alive chunk keys across all regions
          java.util.Map<RTPWorld<?>, java.util.Set<Long>> keepAliveByWorld = new java.util.HashMap<>();

          for (Region sweepRegion : allRegions) {
            // Gate the sweep to prevent race conditions with asynchronous location generation
            if (sweepRegion.inFlightCalculations.get() > 0) continue;

            RTPWorld<?> sweepWorld = sweepRegion.getWorld();
            if (sweepWorld == null) continue;

            java.util.Set<Long> keepAliveKeys = keepAliveByWorld.computeIfAbsent(sweepWorld, w -> new java.util.HashSet<>());

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
          }

          // 4. For each world, release any active chunk tickets not in the keep-alive set
          for (java.util.Map.Entry<RTPWorld<?>, java.util.Set<Long>> entry : keepAliveByWorld.entrySet()) {
            // Diagram 04: CheckTracked -> DropOrphan. FINER because the per-world action is
            // a fine-grained step inside the native sweep loop; the high-level SEVERE alert
            // above already covers the operator-visible summary.
            log(Level.FINER,
                    "[RTP][GC] DropOrphan sweep world={0} keepAlive={1}",
                    entry.getKey(), entry.getValue().size());
            entry.getKey().releaseOrphanedTickets(entry.getValue());
          }
        }
        // Diagram 04: GC Pulse Complete terminator.
        log(Level.FINE, "[RTP][GC] Sweep pulse complete");
      });
    }
  }

  public static void untrack(UUID trackingId) {
    if (trackingId != null) {
      TrackedObject removed = trackedObjects.remove(trackingId);
      // Diagram 03: UntrackRes node - RAM Freed transition (id-keyed fast path).
      if (removed != null) {
        log(Level.FINER,
                "[RTP][Track] Untracked by id: id={0}, label={1}, remaining={2}",
                trackingId, removed.getLabel(), trackedObjects.size());
      }
    }
  }
}
