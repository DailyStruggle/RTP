package io.github.dailystruggle.rtp.common.pvp;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Platform-agnostic native fallback for PvP combat detection.
 *
 * <p>Records the last instant (epoch millis) at which each player was involved in
 * PvP, and answers {@link #isInCombat(UUID, long, long)} by comparing against a
 * configurable combat-tag window. Platform adapters feed it from their damage
 * event listeners ({@code EntityDamageByEntityEvent} on Bukkit/Paper/Folia, the
 * Fabric equivalent), stamping both the victim and the aggressor as configured.
 *
 * <p>This is the authority consulted by {@code PvPGate} when no external
 * {@code PvPCombatStateRegistry.Provider} is bound (or when the configured source
 * is {@code NATIVE}). It holds no platform types and performs no I/O, so it is
 * safe to call from any thread and to unit-test directly with an injectable clock.
 *
 * <p>The map is self-pruning on read of an expired entry; a coarse opportunistic
 * sweep also runs on {@link #stamp} to bound memory on busy servers.
 */
public final class NativePvPCombatTracker {

  /** uuid -> last PvP involvement, epoch millis. */
  private final Map<UUID, Long> lastCombatMs = new ConcurrentHashMap<>();

  /**
   * Record that {@code player} was just involved in PvP.
   *
   * @param player the player (non-null)
   * @param nowMs  current time, epoch millis
   */
  public void stamp(UUID player, long nowMs) {
    if (player == null) return;
    lastCombatMs.put(player, nowMs);
  }

  /**
   * @param player          the player (may be {@code null} -> never in combat)
   * @param nowMs           current time, epoch millis
   * @param combatTagMillis the combat-tag window in millis ({@code <= 0} -> never in combat)
   * @return {@code true} if the player was involved in PvP within the window
   */
  public boolean isInCombat(UUID player, long nowMs, long combatTagMillis) {
    if (player == null || combatTagMillis <= 0L) return false;
    Long last = lastCombatMs.get(player);
    if (last == null) return false;
    if (nowMs - last < combatTagMillis) {
      return true;
    }
    // Expired - prune so a long-lived map does not accumulate stale entries.
    lastCombatMs.remove(player, last);
    return false;
  }

  /** Forget a player's combat state (e.g. on disconnect). */
  public void clear(UUID player) {
    if (player != null) lastCombatMs.remove(player);
  }

  /** Remaining millis on a player's combat tag, or {@code 0} if not in combat. */
  public long remainingMillis(UUID player, long nowMs, long combatTagMillis) {
    if (player == null || combatTagMillis <= 0L) return 0L;
    Long last = lastCombatMs.get(player);
    if (last == null) return 0L;
    long remaining = combatTagMillis - (nowMs - last);
    return Math.max(0L, remaining);
  }

  /** Drop every tracked entry (test/reload aid). */
  public void clearAll() {
    lastCombatMs.clear();
  }

  /** @return number of currently tracked players (diagnostics/tests). */
  public int trackedCount() {
    return lastCombatMs.size();
  }
}
