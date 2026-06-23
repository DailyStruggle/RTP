package io.github.dailystruggle.rtp.common.tasks.teleport;

import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;

import java.util.logging.Level;

/**
 * Teleport view-distance clamp and steady restore (ADR-072).
 *
 * <p>The dominant cost of a teleport is the implicit chunk-tracking ring the engine expands when
 * the player is placed at the destination. That burst grows with the square of the player's view
 * distance, so shrinking the view distance immediately before the teleport caps the burst, and
 * restoring it gradually afterward spreads the residual loads across many ticks instead of one.
 *
 * <p>This task owns the restore ramp. It is created and started via
 * {@link #clampAndSchedule(RTPPlayer, int, long)} immediately before the teleport call: that helper
 * captures the player's pre-teleport view distance, applies the clamp, and (when there is anything
 * to restore) schedules this self-rescheduling task. Each run raises the view distance by one chunk
 * and reschedules itself after a chunk-cost-weighted dwell, so the chunks-delivered-per-tick rate
 * stays roughly flat across the whole window rather than back-loading the heaviest increments.
 *
 * <p>The ramp is session-scoped and self-terminating: it stops (and releases its tracking) when the
 * target view distance is reached, when the player disconnects, or when another tool has already
 * raised the view distance to or beyond the restore target. Per-player view distance is never
 * persisted, so a restart cannot leak a clamp.
 */
public final class ViewDistanceRestoreTask extends RTPRunnable {
  /** Minimum renderable view distance in chunks; clients cannot render below this. */
  public static final int MIN_VD = 2;

  private final RTPPlayer player;
  private final int target;
  private final long interval;
  private final long totalMarginalChunks;

  /** The view distance currently applied by the ramp; starts at the clamp value. */
  private int rampValue;

  private ViewDistanceRestoreTask(
      RTPPlayer player, int clampValue, int target, long interval, long totalMarginalChunks) {
    this.player = player;
    this.target = target;
    this.interval = interval;
    this.totalMarginalChunks = totalMarginalChunks;
    this.rampValue = clampValue;
    setTarget(player);
  }

  /**
   * Captures the player's pre-teleport view distance, clamps it for the arrival, and schedules the
   * steady-restore ramp. Must be called immediately before the teleport call, on the thread that
   * owns the player (the main server thread, or the owning region thread on Folia), so the clamp
   * shrinks the tracked ring the engine expands during placement.
   *
   * <p>No-ops when the feature is disabled ({@code interval <= 0}), when the platform exposes no
   * per-player view-distance API ({@code getViewDistance() < 0}), or when the clamp would not
   * actually shrink the player's view distance.
   *
   * @param player          the player about to be teleported; must not be {@code null}
   * @param preloadRadius   the configured {@code viewDistanceTeleport} preload radius, reused as the
   *                        initial clamp value (floored at {@link #MIN_VD})
   * @param intervalTicks   total ticks over which to restore the view distance ({@code 0} disables)
   */
  public static void clampAndSchedule(RTPPlayer player, int preloadRadius, long intervalTicks) {
    if (player == null || intervalTicks <= 0L) {
      return;
    }
    int captured;
    try {
      captured = player.getViewDistance();
    } catch (Throwable t) {
      RTP.log(Level.FINE, "[RTP] view-distance clamp skipped; getViewDistance failed", t);
      return;
    }
    if (captured < 0) {
      // Platform has no per-player view-distance API; feature silently no-ops.
      return;
    }

    int clampValue = Math.max(preloadRadius, MIN_VD);
    int steps = captured - clampValue;
    if (steps <= 0) {
      // Nothing to gain: the clamp is already >= the player's view distance.
      return;
    }

    // Apply the clamp before the teleport (caller guarantees we run before the teleport call).
    try {
      player.setViewDistance(clampValue);
    } catch (Throwable t) {
      RTP.log(Level.FINE, "[RTP] view-distance clamp could not be applied", t);
      return;
    }

    // Total marginal chunk cost of restoring clampValue -> captured, used to weight the per-step
    // dwell so the cheap early increments fire quickly and the expensive late ones get more time.
    long totalMarginal = ringChunks(captured) - ringChunks(clampValue);
    if (totalMarginal <= 0L) {
      totalMarginal = steps; // defensive: fall back to a uniform cadence
    }

    ViewDistanceRestoreTask task =
        new ViewDistanceRestoreTask(player, clampValue, captured, intervalTicks, totalMarginal);
    task.schedule(task.dwellFor(clampValue));
  }

  /** Number of chunks delivered at view distance {@code r}: {@code (2r+1)^2}. */
  private static long ringChunks(int r) {
    long side = 2L * r + 1L;
    return side * side;
  }

  /**
   * Dwell in ticks granted to the increment {@code r -> r+1}, proportional to that step's marginal
   * chunk cost ({@code (2(r+1)+1)^2 - (2r+1)^2 = 8r+8}). Clamped to at least one tick so a restore
   * interval shorter than the step count collapses to one increment per tick.
   */
  private long dwellFor(int r) {
    long marginal = 8L * r + 8L;
    long dwell = Math.round((double) interval * (double) marginal / (double) totalMarginalChunks);
    return Math.max(1L, dwell);
  }

  @Override
  public void run() {
    if (isCancelled()) {
      return;
    }
    if (player == null || !player.isOnline()) {
      finish();
      return;
    }

    int current;
    try {
      current = player.getViewDistance();
    } catch (Throwable t) {
      RTP.log(Level.FINE, "[RTP] view-distance restore read failed; ending ramp", t);
      finish();
      return;
    }
    if (current < 0) {
      finish();
      return;
    }

    // Another tool may have raised the view distance in the meantime; never shrink it.
    if (current >= target) {
      finish();
      return;
    }

    int next = rampValue + 1;
    int desired = Math.max(next, current);
    if (desired > target) {
      desired = target;
    }
    try {
      player.setViewDistance(desired);
    } catch (Throwable t) {
      RTP.log(Level.FINE, "[RTP] view-distance restore step failed; ending ramp", t);
      finish();
      return;
    }
    rampValue = next;

    if (rampValue >= target) {
      finish();
      return;
    }
    schedule(dwellFor(rampValue));
  }

  private void finish() {
    setCancelled(true);
  }
}
