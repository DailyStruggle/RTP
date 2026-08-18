package io.github.dailystruggle.rtp.common.tasks.teleport;

import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;

import java.util.logging.Level;

/**
 * Teleport view-distance clamp and steady restore ramp (ADR-072).
 * Clamps tracking view distance immediately before teleport to limit chunk load burst,
 * then incrementally restores view distance over a configured tick duration.
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
   * Clamps pre-teleport tracking view distance and schedules incremental restore.
   *
   * @param player        teleporting player; must not be {@code null}
   * @param preloadRadius preload radius clamp value (floored at {@link #MIN_VD})
   * @param intervalTicks total ticks over which to restore view distance ({@code 0} disables)
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

    // Pin the client's send view distance to the captured value FIRST, so shrinking the tracking
    // distance below it never sends the client a view-distance change (the source of the flash).
    // Platforms without a separate send API no-op here and fall back to clamping tracking alone.
    try {
      player.setSendViewDistance(captured);
    } catch (Throwable t) {
      RTP.log(Level.FINE, "[RTP] send-view-distance pin could not be applied", t);
      // Non-fatal: continue with a tracking-only clamp (pre-flash-fix behaviour).
    }

    // Apply the clamp before the teleport (caller guarantees we run before the teleport call).
    try {
      player.setViewDistance(clampValue);
    } catch (Throwable t) {
      RTP.log(Level.FINE, "[RTP] view-distance clamp could not be applied", t);
      releaseSendPin(player);
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

  /**
   * Releases the send-view-distance pin applied at clamp time, resetting it to follow the
   * tracking/world default. Best-effort: never throws into the caller.
   */
  private static void releaseSendPin(RTPPlayer player) {
    try {
      player.setSendViewDistance(-1);
    } catch (Throwable t) {
      RTP.log(Level.FINE, "[RTP] send-view-distance pin could not be released", t);
    }
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
      releaseSendPin(player);
      finish();
      return;
    }
    if (current < 0) {
      releaseSendPin(player);
      finish();
      return;
    }

    // Another tool may have raised the view distance in the meantime; never shrink it.
    if (current >= target) {
      releaseSendPin(player);
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
      releaseSendPin(player);
      finish();
      return;
    }
    schedule(dwellFor(rampValue));
  }

  private void finish() {
    setCancelled(true);
  }
}
