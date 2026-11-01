package io.github.dailystruggle.rtp.common.configuration.enums;

public enum SafetyKeys {
  invulnerabilityTime,
  safetyRadius,
  /**
   * Bounded retry budget for the stale-chunk guard (ADR-015 / REQ-RTP-S-005).
   * Re-queues async chunk load when native chunk GC unloads a chunk before evaluation.
   */
  staleChunkRetryLimit,
  platformRadius,
  platformAirHeight,
  platformDepth,
  platformMaterial,
  /**
   * Footprint restoration timeout in seconds for emergency landing platforms (ADR-060).
   * Value of -1 disables restoration; >= 0 counts loaded chunk time before restoring original blocks.
   */
  platformRestoreSeconds,
  /**
   * Enables background Anvil (.mca) pre-filtering on Spigot (ADR-016 / REQ-RTP-S-005).
   */
  anvilPrefilterEnabled,
  /**
   * Master toggle for PvP / combat-tag pre-flight gate.
   */
  pvpCheckEnabled,
  /**
   * Window, in seconds, during which a player who has dealt or taken PvP damage is
   * considered "in combat" by the native fallback tracker. Ignored when an external
   * combat-tag provider is bound (that plugin owns its own timing). Default: 15.
   */
  pvpCombatTagSeconds,
  /**
   * What happens when a combat-tagged player issues (or is mid-)/rtp. One of
   * {@code ALLOW}, {@code DENY}, {@code DELAY}, {@code CANCEL} (case-insensitive;
   * unknown values fail safe to {@code DENY}). See {@code PvPCombatAction}. Default: DENY.
   */
  pvpOnCombat,
  /**
   * Combat-state source preference: {@code AUTO} (external provider if bound, else native),
   * {@code NATIVE} (always RTP's damage tracker), or {@code EXTERNAL} (only a bound provider;
   * if none is bound the gate treats everyone as not-in-combat). Default: AUTO.
   */
  pvpSource,
  /** Native tracker: stamp a player as in-combat when they <i>take</i> PvP damage. Default: true. */
  pvpTagVictim,
  /** Native tracker: stamp a player as in-combat when they <i>deal</i> PvP damage. Default: true. */
  pvpTagAggressor,
  version
}
