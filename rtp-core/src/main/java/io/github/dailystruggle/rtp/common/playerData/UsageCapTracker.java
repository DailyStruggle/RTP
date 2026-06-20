package io.github.dailystruggle.rtp.common.playerData;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player rolling usage-cap tracker backing the BetterRTP {@code LockAfter}
 * parity feature (the {@code lockAfterUses} / {@code lockAfterResetSeconds}
 * config knobs).
 *
 * <p>Each player accrues a count of successful {@code /rtp} uses within a rolling
 * window. Once the count reaches the configured cap the player is "locked" until
 * the window elapses (or forever, when the reset window is {@code 0}). The whole
 * feature is inert when the cap is {@code <= 0}, so a server that leaves the
 * default {@code lockAfterUses: 0} pays nothing.
 *
 * <p><b>Thread safety:</b> all public methods are {@code synchronized}; the
 * feature is exercised at most once per {@code /rtp}, so contention is a
 * non-issue.
 */
public final class UsageCapTracker {
  private static final class Window {
    long count;
    long start;
  }

  private final ConcurrentHashMap<UUID, Window> windows = new ConcurrentHashMap<>();

  /**
   * Whether the player is currently locked out: the cap is enabled and the
   * player's count within the (non-expired) window has reached it.
   *
   * @param id          player id
   * @param cap         the configured usage cap ({@code <= 0} disables the feature)
   * @param resetMillis the rolling window length in milliseconds ({@code <= 0}
   *                    means the window never resets)
   * @param now         current epoch milliseconds
   * @return {@code true} if the player should be denied
   */
  public synchronized boolean isLocked(UUID id, long cap, long resetMillis, long now) {
    if (cap <= 0 || id == null) return false;
    Window w = windows.get(id);
    if (w == null) return false;
    if (resetMillis > 0 && now - w.start >= resetMillis) {
      windows.remove(id);
      return false;
    }
    return w.count >= cap;
  }

  /**
   * Record one successful teleport for the player, opening or rolling the window
   * as needed. No-op when the cap is disabled.
   *
   * @param id          player id
   * @param cap         the configured usage cap ({@code <= 0} disables the feature)
   * @param resetMillis the rolling window length in milliseconds ({@code <= 0}
   *                    means the window never resets)
   * @param now         current epoch milliseconds
   */
  public synchronized void recordSuccess(UUID id, long cap, long resetMillis, long now) {
    if (cap <= 0 || id == null) return;
    Window w = windows.computeIfAbsent(id, k -> {
      Window x = new Window();
      x.start = now;
      return x;
    });
    if (resetMillis > 0 && now - w.start >= resetMillis) {
      w.count = 0;
      w.start = now;
    }
    w.count++;
  }

  /**
   * Milliseconds remaining until the player's current rolling window resets.
   *
   * @param id          player id
   * @param cap         the configured usage cap ({@code <= 0} disables the feature)
   * @param resetMillis the rolling window length in milliseconds ({@code <= 0}
   *                    means the window never resets)
   * @param now         current epoch milliseconds
   * @return the milliseconds left on the clock, or {@code 0} when the feature is
   *     disabled, the window never resets, the player has no open window, or the
   *     window has already elapsed
   */
  public synchronized long millisUntilReset(UUID id, long cap, long resetMillis, long now) {
    if (cap <= 0 || resetMillis <= 0 || id == null) return 0L;
    Window w = windows.get(id);
    if (w == null) return 0L;
    long remaining = (w.start + resetMillis) - now;
    return remaining > 0 ? remaining : 0L;
  }

  /** Clear the recorded usage for a single player (e.g. on an admin reset). */
  public synchronized void reset(UUID id) {
    if (id != null) windows.remove(id);
  }

  /**
   * Snapshot the player's current rolling window for persistence.
   *
   * @param id player id
   * @return a {@code {count, start}} pair, or {@code null} when the player has no
   *     open window
   */
  public synchronized long[] snapshot(UUID id) {
    if (id == null) return null;
    Window w = windows.get(id);
    if (w == null) return null;
    return new long[] {w.count, w.start};
  }

  /**
   * Restore a previously-persisted rolling window for the player. A
   * non-positive {@code count} is treated as "no window" and clears any existing
   * entry, so a persisted cleared marker round-trips cleanly.
   *
   * @param id    player id
   * @param count the persisted use count within the window
   * @param start the persisted window-start epoch milliseconds
   */
  public synchronized void restore(UUID id, long count, long start) {
    if (id == null) return;
    if (count <= 0) {
      windows.remove(id);
      return;
    }
    Window w = windows.computeIfAbsent(id, k -> new Window());
    w.count = count;
    w.start = start;
  }

  /** Clear all recorded usage (e.g. on plugin reload/shutdown). */
  public synchronized void clear() {
    windows.clear();
  }
}
