package io.github.dailystruggle.rtp.api.hooks;

/**
 * Enumerated outcomes for the optional PvP / combat-tag pre-flight gate (ROADMAP
 * Tier 2, "Optional PvP / combat-tag check").
 *
 * <p>The gate is consulted at two points: when a player issues {@code /rtp}
 * (pre-dispatch, before queue enrolment) and again immediately before the chosen
 * destination is applied (pre-execution prefilter). The configured action decides
 * what happens when the requesting player is currently considered "in combat" by
 * the active {@link PvPCombatStateRegistry.Provider} (or the native fallback when
 * none is bound).
 *
 * <p>Configured via {@code safety.yml#pvpOnCombat}. The lookup is case-insensitive
 * and unknown values resolve to {@link #DENY} (fail-safe).
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
   * once the combat tag expires (or give up after a bounded wait). Equivalent to
   * EzRTP's {@code cancel-countdown-on-pvp-tag} when paired with a countdown.
   */
  DELAY,

  /**
   * Abort an in-progress teleport (countdown or already-queued request) for a
   * player who entered combat after issuing {@code /rtp}. Equivalent to EzRTP's
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
