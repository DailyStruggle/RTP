package io.github.dailystruggle.rtp.common.configuration.enums;

/** Configuration keys for RTP */
public enum ConfigKeys {
  /** Delay before teleportation */
  teleportDelay,
  /** Cooldown after teleportation */
  teleportCooldown,
  /**
   * Maximum number of successful {@code /rtp} uses a player may make within the
   * {@link #lockAfterResetSeconds} window before being locked out (BetterRTP
   * {@code LockAfter} parity). {@code 0} disables the cap (default).
   */
  lockAfterUses,
  /**
   * Length (seconds) of the rolling usage-cap window for {@link #lockAfterUses}.
   * The counter resets this many seconds after the window's first use. {@code 0}
   * means the cap never resets (a hard lifetime cap) (default).
   */
  lockAfterResetSeconds,
  /**
   * When {@code true}, a successful {@code /rtp} sets the landed location as the
   * player's persistent spawn anchor (BetterRTP {@code SetAsRespawn} parity).
   * {@code false} disables it (default).
   */
  setRespawnOnTeleport,
  /** Distance to cancel teleportation */
  cancelDistance,
  /** Commands to run as console */
  consoleCommands,
  /** Commands to run as player */
  playerCommands,
  /** Database configuration */
  database,
  /** Network configuration */
  network,
  /** Generalized menu framework configuration (ADR-035 / ADR-044). */
  menu,
  /**
   * Global default templates and scalars that region/world settings may inherit via an
   * {@code @config} reference token (ADR-073). Holds the {@code shape}/{@code vert}
   * named blocks plus the config-owned scalars ({@code cacheCap}, {@code backlogCacheCap},
   * {@code activeChunkCap}, {@code spatialResolution}, {@code requirePermission}).
   */
  defaults,
  /** Configuration version */
  version
}
