package io.github.dailystruggle.rtp.common.playerData;

import java.util.UUID;

/**
 * Abstraction over durable teleport-limit state for usage cap enforcement.
 * Asynchronous, non-blocking implementations update the database queue.
 */
public interface TeleportLimitStore {
  /**
   * Checks whether player is locked out under the configured usage cap.
   *
   * @param id player UUID
   * @param cap usage cap ({@code <= 0} disables cap)
   * @param resetMillis rolling window duration in ms ({@code <= 0} means no reset)
   * @param now current epoch milliseconds
   * @return {@code true} if locked out
   */
  boolean isLocked(UUID id, long cap, long resetMillis, long now);

  /**
   * Records one successful teleport, advancing usage window as needed.
   *
   * @param id player UUID
   * @param cap usage cap ({@code <= 0} disables cap)
   * @param resetMillis rolling window duration in ms
   * @param now current epoch milliseconds
   */
  void recordSuccess(UUID id, long cap, long resetMillis, long now);

  /**
   * Milliseconds remaining until rolling usage window resets for display messages.
   *
   * @param id player UUID
   * @param cap usage cap
   * @param resetMillis rolling window duration in ms
   * @param now current epoch milliseconds
   * @return milliseconds remaining, or {@code 0} if unlocked/disabled
   */
  long millisUntilReset(UUID id, long cap, long resetMillis, long now);

  /** Clear the recorded usage for a single player (e.g. on an admin reset). */
  void reset(UUID id);
}
