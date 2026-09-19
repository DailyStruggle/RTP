package io.github.dailystruggle.rtp.common.platform;

import io.github.dailystruggle.rtp.api.platform.PendingPlatformRestore;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.database.options.AbstractSQLDatabaseAccessor;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Manages enrolled emergency-platform restore jobs (ADR-060) and periodic countdown reaper.
 * Skips unloaded chunks without force loading (S-005) and dispatches block restores to owning threads.
 */
public final class PlatformRestoreManager {
  /** Process-wide manager handle, lazily created and started during core init. */
  public static volatile PlatformRestoreManager instance;

  private final Map<UUID, PendingPlatformRestore> entries = new ConcurrentHashMap<>();
  private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
  private final PlatformRestoreSqlStore store;

  private volatile Object reaperTask;

  public PlatformRestoreManager(PlatformRestoreSqlStore store) {
    this.store = store;
  }

  /**
   * Constructs (if needed) and starts the process-wide manager, loading any persisted jobs and
   * scheduling the 1 Hz reaper through {@code RTP.scheduler}.
   */
  public static synchronized void startGlobal() {
    if (instance != null) return;
    PlatformRestoreSqlStore store = null;
    if (RTP.getInstance() != null
        && RTP.getInstance().databaseAccessor instanceof AbstractSQLDatabaseAccessor sql) {
      store = new PlatformRestoreSqlStore(sql);
    }
    PlatformRestoreManager m = new PlatformRestoreManager(store);
    m.loadPersisted();
    m.start();
    instance = m;
  }

  /** Stops and clears the process-wide manager (idempotent). */
  public static synchronized void stopGlobal() {
    if (instance == null) return;
    instance.stop();
    instance = null;
  }

  /** Loads persisted restore jobs into memory (resume across restarts). */
  public void loadPersisted() {
    if (store == null) return;
    for (PendingPlatformRestore r : store.loadAll()) {
      entries.put(r.id(), r);
    }
  }

  /** Schedules the 1 Hz (20-tick) reaper pulse. */
  public void start() {
    if (RTP.scheduler == null) return;
    reaperTask = RTP.scheduler.runTaskTimerAsynchronously(this::pulse, 20L, 20L);
  }

  /** Cancels the reaper pulse. */
  public void stop() {
    if (reaperTask != null && RTP.scheduler != null) {
      RTP.scheduler.cancelTask(reaperTask);
    }
    reaperTask = null;
  }

  /**
   * Enrolls a new restore job: held in memory and persisted (when SQL-backed). A {@code null}
   * or empty job is ignored.
   *
   * @param restore the job to enroll
   */
  public void enroll(PendingPlatformRestore restore) {
    if (restore == null || restore.blocks().isEmpty()) return;
    entries.put(restore.id(), restore);
    if (store != null) store.insert(restore);
  }

  /** @return the number of currently enrolled (not-yet-restored) jobs. */
  public int size() {
    return entries.size();
  }

  /**
   * One reaper pulse: decrement every entry whose footprint chunk is loaded, and schedule the
   * restore for those that hit zero. Visible for testing so a {@code MockRTPScheduler}-driven
   * test can advance the countdown deterministically.
   */
  public void pulse() {
    for (PendingPlatformRestore entry : entries.values()) {
      if (inFlight.contains(entry.id())) continue;
      RTPWorld<?> world = RTP.serverAccessor.getRTPWorld(entry.worldName());
      if (world == null) {
        // World not loaded: nothing to tidy, freeze the countdown (no force-load, S-005).
        continue;
      }
      boolean loaded;
      try {
        loaded = world.isChunkLoaded(entry.cx(), entry.cz());
      } catch (Throwable t) {
        loaded = false;
      }
      if (!loaded) {
        // Chunk unloaded: countdown frozen this pulse.
        continue;
      }
      int remaining = entry.decrement();
      if (remaining > 0) {
        if (store != null) store.updateRemaining(entry.id(), remaining);
        continue;
      }
      scheduleRestore(world, entry);
    }
  }

  private void scheduleRestore(RTPWorld<?> world, PendingPlatformRestore entry) {
    if (!inFlight.add(entry.id())) return;
    List<io.github.dailystruggle.rtp.api.platform.BlockDelta> blocks = entry.blocks();
    int x = blocks.get(0).x();
    int y = blocks.get(0).y();
    int z = blocks.get(0).z();
    RTPLocation at = new RTPLocation(world, x, y, z);
    Runnable restore =
        () -> {
          try {
            boolean ok = world.restoreBlocks(blocks);
            if (ok) {
              RTP.log(
                  Level.INFO,
                  "[RTP] restored emergency platform footprint ("
                      + blocks.size()
                      + " blocks) in "
                      + entry.worldName());
            } else {
              RTP.log(
                  Level.WARNING,
                  "[RTP] platform restore for "
                      + entry.worldName()
                      + " was not applied (unsupported platform or failure); retiring entry");
            }
          } catch (Throwable t) {
            RTP.log(
                Level.WARNING,
                "[RTP] platform restore failed for " + entry.worldName() + "; retiring entry",
                t);
          } finally {
            retire(entry.id());
          }
        };
    try {
      RTP.scheduler.runTask(at, restore);
    } catch (Throwable t) {
      RTP.log(Level.WARNING, "[RTP] failed to schedule platform restore; retiring entry", t);
      retire(entry.id());
    }
  }

  private void retire(UUID id) {
    entries.remove(id);
    inFlight.remove(id);
    if (store != null) store.delete(id);
  }
}
