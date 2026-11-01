package io.github.dailystruggle.rtp.common.playerData;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player window in which an effect moves the player itself during the teleport delay, so the
 * {@code cancelDistance} check counts only horizontal movement and ignores teleports. Granted by
 * the Rift addon while its carved hole is open: the fall into it must not cancel the pending
 * {@code /rtp}, but walking away still does (RTP-71).
 *
 * <p>Thread-safe. Every grant carries an expiry, so a missed {@link #clear} cannot switch the
 * check off for good.
 */
public final class MoveCancelGrace {

  private static final Map<UUID, Long> UNTIL_MILLIS = new ConcurrentHashMap<>();

  private MoveCancelGrace() {}

  /**
   * Opens (or extends) the window for {@code playerId}.
   *
   * @param playerId player whose own effect-driven movement is exempt
   * @param durationMillis window length; values {@code <= 0} grant nothing
   */
  public static void grant(UUID playerId, long durationMillis) {
    if (playerId == null || durationMillis <= 0) return;
    UNTIL_MILLIS.put(playerId, System.currentTimeMillis() + durationMillis);
  }

  /**
   * Closes the window for {@code playerId}.
   *
   * @param playerId player to clear; {@code null} is ignored
   */
  public static void clear(UUID playerId) {
    if (playerId != null) UNTIL_MILLIS.remove(playerId);
  }

  /**
   * Whether the window is open for {@code playerId}. Expired windows are dropped.
   *
   * @param playerId player to check
   * @return {@code true} while the grant has not expired or been cleared
   */
  public static boolean isActive(UUID playerId) {
    if (playerId == null) return false;
    Long until = UNTIL_MILLIS.get(playerId);
    if (until == null) return false;
    if (System.currentTimeMillis() < until) return true;
    UNTIL_MILLIS.remove(playerId, until);
    return false;
  }
}
