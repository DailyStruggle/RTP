package io.github.dailystruggle.rtp.common.configuration.enums;

public enum SafetyKeys {
  invulnerabilityTime,
  safetyRadius,
  /**
   * Bounded retry budget for the stale-chunk guard (ADR-015 / REQ-RTP-S-005).
   * After {@code getChunkAtAsync} resolves, the follow-up block-evaluation task is enqueued
   * onto a Count-Bound task pipe (Folia Region Thread scheduler). If the pipe is backlogged,
   * the native chunk GC may unload the chunk before the task runs, which would otherwise
   * trigger a synchronous chunk load on the Region Thread and trip the Folia Watchdog.
   * When the guard detects that case, it re-queues an async chunk load up to this many
   * times per location candidate; on exhaustion, the candidate is rejected via the normal
   * "unsafe" path (a WARN log is emitted; no silent discard — REQ-RTP-S-004).
   * Default: 2. Raise only on forks with hyper-aggressive chunk GC.
   */
  staleChunkRetryLimit,
  platformRadius,
  platformAirHeight,
  platformDepth,
  platformMaterial,
  /**
   * Optional timeout, in seconds, after which the emergency landing platform's footprint is
   * restored to the blocks that were there before it was built (ADR-060 / REQ-RTP-S-005).
   * {@code -1} (default) disables restoration entirely - the platform is permanent, exactly as
   * before. {@code 0} restores on the first reaper pulse at which the footprint chunk is loaded;
   * {@code > 0} restores after that many seconds of <i>chunk-loaded</i> time have elapsed. The
   * countdown is frozen while the footprint chunk is unloaded (the reaper never force-loads).
   * Pending restores are persisted to the database and resumed across restarts.
   */
  platformRestoreSeconds,
  airBlocks,
  unsafeBlocks,
  /**
   * Toggle for the vanilla-Spigot Anvil read-only pre-filter (ADR-016 / REQ-RTP-S-005).
   * When enabled, {@code BukkitRTPWorld.getChunkAt} will probe the persisted
   * {@code r.X.Z.mca} region file on a background thread and reject clearly-unsafe
   * candidates (lava/magma/fire at the heightmap surface) before requesting the
   * authoritative live chunk load. Default: {@code true}. The flag has no effect on
   * Paper or Folia — those platforms {@code @Override} {@code getChunkAt} with their
   * native async APIs and never enter the pre-filter path.
   */
  anvilPrefilterEnabled,
  /**
   * Master toggle for the optional PvP / combat-tag pre-flight gate (ROADMAP Tier 2).
   * When {@code true}, {@code /rtp} consults the active combat-state authority (a bound
   * {@code PvPCombatStateRegistry.Provider}, or RTP's native damage tracker when none is
   * bound) before enrolling the player and again before applying the destination, and
   * applies {@link #pvpOnCombat}. Default: {@code false}.
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
  biomeWhitelist,
  biomes,
  version
}
