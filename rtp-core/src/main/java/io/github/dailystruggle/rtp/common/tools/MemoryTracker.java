package io.github.dailystruggle.rtp.common.tools;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.text.MessageFormat;
import java.util.logging.Level;

/** Utility class for tracking memory leaks in objects. */
public class MemoryTracker {
  private static final ConcurrentHashMap<UUID, TrackedObject> trackedObjects =
          new ConcurrentHashMap<>();

  /** Configurable memory ceiling / buffer quota in bytes. -1L indicates unlimited. */
  private static volatile long memoryCeilingBytes = -1L;

  /** Regions the diagnostics sweep counts; installed by the composition root (ADR-115). */
  private static volatile java.util.function.Supplier<? extends Collection<? extends TicketCensusRegion>>
      regionSource = Collections::emptyList;

  /**
   * Installs the supplier of regions for the chunk-ticket census and orphan sweep. Called once
   * from {@code RTP} static init; the supplier must not return {@code null}.
   */
  public static void setRegionSource(
      java.util.function.Supplier<? extends Collection<? extends TicketCensusRegion>> source) {
    regionSource = Objects.requireNonNull(source, "source");
  }

  private static void log(Level level, String pattern, Object... args) {
    RTP.log(level, MessageFormat.format(pattern, args));
  }

  static {
    // Inject the core untrack logic into the API
    RTPRunnable.untrackHook = MemoryTracker::untrack;
    io.github.dailystruggle.rtp.common.metrics.CoreMetrics.setMemoryTrackerSuppliers(
        MemoryTracker::trackedCount,
        MemoryTracker::trackedCountByLabel
    );
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
    if (target instanceof UUID uuid) {
      untrack(uuid);
      return;
    }
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

  /**
   * Returns the count of currently active chunk tickets held across all loaded worlds.
   */
  public static long activeTickets() {
    long activeTickets = 0;
    if (RTP.serverAccessor != null) {
      List<RTPWorld<?>> worlds = RTP.serverAccessor.getRTPWorlds();
      if (worlds != null) {
        for (RTPWorld<?> world : worlds) {
          if (world != null && world.activeChunkTickets != null) {
            activeTickets += world.activeChunkTickets.get();
          }
        }
      }
    }
    return activeTickets;
  }

  /**
   * Returns the count of currently active/in-flight tasks tracked in MemoryTracker.
   */
  public static int activeTasks() {
    int tasks = 0;
    for (TrackedObject obj : trackedObjects.values()) {
      if (obj.isCollected()) continue;
      String label = obj.getLabel();
      Object target = obj.getTarget();
      if ("TeleportPipelineTask".equals(label)
          || target instanceof io.github.dailystruggle.rtp.api.scheduling.TrackedRTPTask
          || target instanceof io.github.dailystruggle.rtp.common.tasks.TrackableTask) {
        tasks++;
      }
    }
    return tasks;
  }

  /**
   * Sets the configured memory ceiling / buffer quota in bytes.
   *
   * @param bytes ceiling in bytes, or -1L for unlimited
   */
  public static void setMemoryCeiling(long bytes) {
    memoryCeilingBytes = bytes;
  }

  /**
   * Sets the configured memory ceiling / buffer quota using a data size string (e.g. "256MB", "1GiB").
   *
   * @param dataSizeString data size string
   */
  public static void setMemoryCeiling(String dataSizeString) {
    memoryCeilingBytes = io.github.dailystruggle.rtp.common.selection.region.util.DataSizeParser.parseBytes(dataSizeString, -1L);
  }

  /**
   * Returns the configured memory ceiling / buffer quota in bytes (-1L if unlimited).
   */
  public static long getMemoryCeiling() {
    return memoryCeilingBytes;
  }

  /**
   * Checks if an allocated byte amount exceeds the configured memory ceiling.
   * Always returns false when memory ceiling is unlimited (&lt;= 0).
   *
   * @param allocatedBytes bytes to check
   * @return true if exceeding memory ceiling
   */
  public static boolean isOverMemoryCeiling(long allocatedBytes) {
    long ceiling = memoryCeilingBytes;
    return ceiling > 0 && allocatedBytes > ceiling;
  }

  /**
   * Players owning a live, non-leaking, non-cancelled tracked pipeline task, and players with a
   * live tracked TeleportData record. Weakly consistent; safe from any thread.
   */
  static void collectLiveOwners(java.util.Set<UUID> pipelines, java.util.Set<UUID> data) {
    for (TrackedObject tracked : trackedObjects.values()) {
      Object target = tracked.getTarget();
      if (target instanceof io.github.dailystruggle.rtp.api.scheduling.TrackedRTPTask wrapper
          && wrapper.getTask() != null) {
        target = wrapper.getTask();
      }
      if (target instanceof io.github.dailystruggle.rtp.common.tasks.TrackableTask task) {
        if (tracked.isLeaking() || task.isCancelled()) {
          continue;
        }
        io.github.dailystruggle.rtp.api.entity.RTPPlayer player = task.player();
        if (player != null) {
          pipelines.add(player.uuid());
        }
      } else if (target instanceof io.github.dailystruggle.rtp.common.playerData.TeleportData) {
        UUID id = teleportDataPlayerId(tracked.getLabel());
        if (id != null) {
          data.add(id);
        }
      }
    }
  }

  /** Player id from a {@code TeleportData-<uuid>} tracker label, or {@code null}. */
  static UUID teleportDataPlayerId(String label) {
    if (label == null || !label.startsWith("TeleportData-")) {
      return null;
    }
    try {
      return UUID.fromString(label.substring("TeleportData-".length()));
    } catch (IllegalArgumentException e) {
      return null;
    }
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
    // Owners captured before the purge, so a TeleportData whose pipeline is alive is spared.
    java.util.Set<UUID> livePipelinesBefore = new java.util.HashSet<>();
    collectLiveOwners(livePipelinesBefore, new java.util.HashSet<>());
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
                        if (actualTask instanceof io.github.dailystruggle.rtp.common.tasks.TrackableTask trackableTask) {
                          // Diagram 04: ForceClose -> DecCounter -> Untrack. FINE because each occurrence
                          // represents a real cleanup action (cancellation + chunk release scheduling).
                          log(Level.FINE,
                                  "[RTP][GC] ForceClose stalled pipeline label={0} ageOverBudgetMs={1}",
                                  label, leakDuration);
                          // Force the abandoned pipeline into its CLEANUP phase
                          trackableTask.setCancelled(true);

                          // Schedule it on the primary thread to safely release chunks
                          if (RTP.scheduler != null) {
                            RTP.scheduler.runTask(trackableTask);
                          }

                          // Return true to instantly purge it from the tracker
                          // to prevent infinite log looping
                          return true;
                        }

                        if (actualTask instanceof io.github.dailystruggle.rtp.common.playerData.TeleportData teleportData) {
                          // The label names the teleported player; the sender may be someone else.
                          UUID targetPlayerId = teleportDataPlayerId(label);
                          if (targetPlayerId == null
                              && teleportData.sender instanceof io.github.dailystruggle.rtp.api.entity.RTPPlayer rtpPlayer) {
                            targetPlayerId = rtpPlayer.uuid();
                          }
                          // The 120 s budget excludes the warm-up delay and legitimate waits (queue,
                          // death hold, live pipeline, network): keep the record; re-checked next sweep.
                          if (!teleportData.completed && targetPlayerId != null
                              && (leakDuration <= Math.max(0L, teleportData.delay)
                                  || ProcessingLockSweeper.isHeld(targetPlayerId, livePipelinesBefore))) {
                            log(Level.FINER,
                                    "[RTP][GC] Keeping TeleportData label={0}: delay or wait still in progress",
                                    label);
                            return false;
                          }
                          log(Level.FINE,
                                  "[RTP][GC] Purging stalled TeleportData label={0} ageOverBudgetMs={1}",
                                  label, leakDuration);
                          teleportData.completed = true;
                          if (targetPlayerId != null && RTP.getInstance() != null) {
                            RTP.getInstance().processingPlayers.remove(targetPlayerId);
                            RTP.getInstance().latestTeleportData.remove(targetPlayerId, teleportData);
                          }
                          return true;
                        }

                        if (actualTask instanceof io.github.dailystruggle.rtp.api.action.ActionSession session) {
                          log(Level.WARNING,
                              "[RTP][GC] Force-disarming stalled ActionSession label={0} ageOverBudgetMs={1}",
                              label, leakDuration);
                          session.disarm();
                          return true;
                        }
                      }
                      return false;
                    });

    // S-004 safety net: release processingPlayers entries nothing owns any more.
    try {
      java.util.Set<UUID> livePipelines = new java.util.HashSet<>();
      java.util.Set<UUID> liveData = new java.util.HashSet<>();
      collectLiveOwners(livePipelines, liveData);
      ProcessingLockSweeper.sweep(livePipelines, liveData);
    } catch (RuntimeException e) {
      RTP.log(Level.WARNING, "[RTP][GC] stale teleport lock sweep failed", e);
    }

    RTP rtp = RTP.getInstance();
    if (rtp != null) {
      long totalLocationQueueSize = 0;
      long totalPerPlayerLocationQueueSize = 0;
      long trackedTickets = 0;
      long totalCacheCap = 0;
      long totalActiveChunkCap = 0;

      // Combine permanent and temporary regions for full visibility
      List<TicketCensusRegion> allRegions = new ArrayList<>(regionSource.get());

      for (TicketCensusRegion region : allRegions) {
        totalCacheCap += region.cacheBudget();
        totalActiveChunkCap += region.activeChunkCap();
        totalLocationQueueSize += region.queuedLocations();
        totalPerPlayerLocationQueueSize += region.perPlayerLocations();
        trackedTickets += region.reservedChunkTickets();
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
      // Post-arrival holds (REQ-RTP-S-005) are owned, time-bounded tickets, not orphans.
      long arrivalHolds = io.github.dailystruggle.rtp.common.tasks.ChunkTicketHolder
          .heldChunkTicketCount();
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
        long expectedTickets = Math.min(finalTrackedTickets, finalTotalActiveChunkCap) + arrivalHolds;
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

          for (TicketCensusRegion sweepRegion : allRegions) {
            // Gate the sweep to prevent race conditions with asynchronous location generation
            // Pending kept promotions hold tickets not yet in keptLocations; sweeping would drop them.
            if (sweepRegion.generationInFlight()) continue;

            RTPWorld<?> sweepWorld = sweepRegion.world();
            if (sweepWorld == null) continue;

            java.util.Set<Long> keepAliveKeys = keepAliveByWorld.computeIfAbsent(sweepWorld, w -> new java.util.HashSet<>());
            // Queued locations and pipeline teleport destinations
            sweepRegion.collectKeepAliveChunkKeys(keepAliveKeys);
          }

          // 3b. Keep chunks pinned by pending post-arrival holds (worlds already being swept)
          for (java.util.Map.Entry<RTPWorld<?>, java.util.Set<Long>> hold
              : io.github.dailystruggle.rtp.common.tasks.ChunkTicketHolder
                  .arrivalHoldChunkKeys().entrySet()) {
            java.util.Set<Long> keepAliveKeys = keepAliveByWorld.get(hold.getKey());
            if (keepAliveKeys != null) keepAliveKeys.addAll(hold.getValue());
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
