package io.github.dailystruggle.rtp.api.hooks;

/**
 * Configured outcomes for PvP/combat-tag pre-flight checks.
 *
 * <p>Consulted at dispatch and execution time when a player is in combat.
 * Configured via {@code safety.yml#pvpOnCombat}. Defaults to {@link #DENY}.
 */
public enum PvPCombatAction {

  /**
   * Permit the teleport, but emit a single audit log line (REQ-RTP-S-004). Useful
   * for operators who want visibility without changing behaviour.
   */
  ALLOW,

  /**
   * Refuse the {@code /rtp} request outright with the configurable busy message and
   * do not enrol the player in any queue.
   */
  DENY,

  /**
   * Postpone the request: keep re-checking combat state and proceed automatically
   * once the combat tag expires (or give up after a bounded wait).
   */
  DELAY,

  /**
   * Abort an in-progress teleport (countdown or already-queued request) for a
   * player who entered combat after issuing {@code /rtp}.
   * {@code cancel-queued-on-pvp-tag}.
   */
  CANCEL;

  /**
   * Resolve a configured string to an action, fail-safe to {@link #DENY}.
   *
   * @param raw the raw config value (may be {@code null})
   * @return a non-null action
   */
  public static PvPCombatAction fromConfig(String raw) {
    if (raw == null) return DENY;
    String s = raw.trim().toUpperCase(java.util.Locale.ROOT);
    if (s.isEmpty()) return DENY;
    try {
      return valueOf(s);
    } catch (IllegalArgumentException ignored) {
      return DENY;
    }
  }
}
