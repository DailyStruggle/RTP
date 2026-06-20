package io.github.dailystruggle.rtp.common.playerData;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Always-present {@link TeleportLimitStore} backed by an in-memory
 * {@link UsageCapTracker}, with best-effort write-through persistence of each
 * player's rolling usage-cap window to the configured {@link DatabaseAccessor}
 * (ADR-068, durability axis {@code local}).
 *
 * <p>The cooldown anchor is persisted independently (via
 * {@code cacheValue(TeleportData)}), so only the usage-cap window is persisted
 * here. Persistence closes the BetterRTP {@code LockAfter} restart gap: before
 * this, the usage cap lived purely in memory and was lost on every reload.
 *
 * <p>State is stored in the accessor's generic key/value table
 * {@value #TABLE} keyed by the player UUID, value {@code "count:start"}. The
 * file-backed {@code YamlFileDatabase} (the lite-assembly default) reloads this
 * table on startup, so usage caps survive a restart there with zero driver
 * dependencies. SQL accessors persist the write-through but do not preload the
 * generic table on startup; cross-restart SQL reload is handled by the
 * cross-server durability phases of ADR-068 and is not relied upon here.
 *
 * <p>All persistence is enqueued onto the accessor's asynchronous write path, so
 * no method blocks a tick thread (S-005). A persistence failure is logged, never
 * swallowed silently (S-004), and never prevents the in-memory enforcement.
 */
public final class LocalTeleportLimitStore implements TeleportLimitStore {
  /** Generic accessor table holding per-player usage-cap windows. */
  static final String TABLE = "rtp_usage_caps";

  private final UsageCapTracker tracker;
  private final boolean persist;
  private final Supplier<DatabaseAccessor<?>> accessorSupplier;
  /** Players whose persisted window has already been loaded this session. */
  private final Set<UUID> loaded = ConcurrentHashMap.newKeySet();

  public LocalTeleportLimitStore(UsageCapTracker tracker, boolean persist) {
    this(tracker, persist,
        () -> RTP.getInstance() == null ? null : RTP.getInstance().databaseAccessor);
  }

  /** Test/seam constructor allowing the backing accessor to be injected. */
  public LocalTeleportLimitStore(
      UsageCapTracker tracker, boolean persist, Supplier<DatabaseAccessor<?>> accessorSupplier) {
    this.tracker = tracker;
    this.persist = persist;
    this.accessorSupplier = accessorSupplier;
  }

  @Override
  public boolean isLocked(UUID id, long cap, long resetMillis, long now) {
    if (cap <= 0 || id == null) return false;
    ensureLoaded(id);
    return tracker.isLocked(id, cap, resetMillis, now);
  }

  @Override
  public void recordSuccess(UUID id, long cap, long resetMillis, long now) {
    if (cap <= 0 || id == null) return;
    ensureLoaded(id);
    tracker.recordSuccess(id, cap, resetMillis, now);
    save(id);
  }

  @Override
  public long millisUntilReset(UUID id, long cap, long resetMillis, long now) {
    if (cap <= 0 || resetMillis <= 0 || id == null) return 0L;
    ensureLoaded(id);
    return tracker.millisUntilReset(id, cap, resetMillis, now);
  }

  @Override
  public void reset(UUID id) {
    if (id == null) return;
    tracker.reset(id);
    loaded.add(id);
    save(id);
  }

  /** Lazily hydrate a player's window from the database on first touch. */
  private void ensureLoaded(UUID id) {
    if (!persist || !loaded.add(id)) return;
    DatabaseAccessor<?> db = accessorSupplier.get();
    if (db == null) return;
    try {
      Object raw = readRaw(db, id);
      if (raw == null) return;
      String s = raw.toString();
      int sep = s.indexOf(':');
      if (sep <= 0) return;
      long count = Long.parseLong(s.substring(0, sep).trim());
      long start = Long.parseLong(s.substring(sep + 1).trim());
      tracker.restore(id, count, start);
    } catch (Throwable t) {
      RTP.log(Level.WARNING,
          "[ADR-068] failed to load persisted usage cap for " + id + ": " + t.getMessage(), t);
    }
  }

  /**
   * Read the raw persisted value, tolerating the accessor naming split: the
   * live in-memory generic table is keyed by the bare name, while a reloaded
   * {@code YamlFileDatabase} keys it by the {@code .yml} file name.
   */
  private Object readRaw(DatabaseAccessor<?> db, UUID id) {
    Object v = unwrap(db.getValue(TABLE, id.toString()));
    if (v == null) v = unwrap(db.getValue(TABLE + ".yml", id.toString()));
    return v;
  }

  /** Sentinel distinguishing a completed-but-null future result from "absent". */
  private static final Object ABSENT = new Object();

  private static Object unwrap(Optional<?> opt) {
    if (opt == null || opt.isEmpty()) return null;
    Object inner = opt.get();
    if (inner instanceof java.util.concurrent.CompletableFuture<?> rawFuture) {
      @SuppressWarnings("unchecked")
      java.util.concurrent.CompletableFuture<Object> future =
          (java.util.concurrent.CompletableFuture<Object>) rawFuture;
      if (!future.isDone() || future.isCompletedExceptionally()) return null;
      Object res = future.getNow(ABSENT);
      if (res == ABSENT) return null;
      if (res instanceof Optional<?> o) return o.orElse(null);
      return res;
    }
    return inner;
  }

  /** Write-through the player's current window to the database. */
  private void save(UUID id) {
    if (!persist) return;
    DatabaseAccessor<?> db = accessorSupplier.get();
    if (db == null) return;
    try {
      long[] snap = tracker.snapshot(id);
      String value = (snap == null) ? "0:0" : (snap[0] + ":" + snap[1]);
      db.setValue(TABLE, id.toString(), value);
    } catch (Throwable t) {
      RTP.log(Level.WARNING,
          "[ADR-068] failed to persist usage cap for " + id + ": " + t.getMessage(), t);
    }
  }
}
