package io.github.dailystruggle.rtp.common.playerData;

import java.util.UUID;

/**
 * Abstraction over the durable, optionally cross-server teleport-limit state
 * that the {@code /rtp} guards consult (ADR-068).
 *
 * <p>Today this fronts the BetterRTP {@code LockAfter} usage cap. The cooldown
 * anchor ({@link TeleportData#time}) is already persisted via the database
 * accessor and reloaded on startup, so it is not yet routed through this seam;
 * the interface is intentionally narrow so the cross-server layers (durable
 * proxy store, push-on-connect snapshot) introduced by later ADR-068 phases can
 * slot in behind the same call sites without further guard churn.
 *
 * <p>Implementations must never block a tick thread (S-005); any persistence is
 * write-through to the asynchronous database accessor queue.
 */
public interface TeleportLimitStore {
  /**
   * Whether the player is currently locked out under the usage cap.
   *
   * @param id          player id
   * @param cap         the configured usage cap ({@code <= 0} disables the feature)
   * @param resetMillis the rolling window length in milliseconds ({@code <= 0}
   *                    means the window never resets)
   * @param now         current epoch milliseconds
   * @return {@code true} if the player should be denied
   */
  boolean isLocked(UUID id, long cap, long resetMillis, long now);

  /**
   * Record one successful teleport for the player, opening or rolling the usage
   * window as needed. No-op when the cap is disabled.
   *
   * @param id          player id
   * @param cap         the configured usage cap ({@code <= 0} disables the feature)
   * @param resetMillis the rolling window length in milliseconds ({@code <= 0}
   *                    means the window never resets)
   * @param now         current epoch milliseconds
   */
  void recordSuccess(UUID id, long cap, long resetMillis, long now);

  /**
   * Milliseconds remaining until the player's current rolling usage-cap window
   * resets, for display in the lockout message.
   *
   * @param id          player id
   * @param cap         the configured usage cap ({@code <= 0} disables the feature)
   * @param resetMillis the rolling window length in milliseconds ({@code <= 0}
   *                    means the window never resets)
   * @param now         current epoch milliseconds
   * @return the milliseconds left on the clock, or {@code 0} when the feature is
   *     disabled, the window never resets, or the player is not currently locked
   */
  long millisUntilReset(UUID id, long cap, long resetMillis, long now);

  /** Clear the recorded usage for a single player (e.g. on an admin reset). */
  void reset(UUID id);
}
