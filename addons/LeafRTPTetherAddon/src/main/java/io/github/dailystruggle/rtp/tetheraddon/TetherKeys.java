package io.github.dailystruggle.rtp.tetheraddon;

/**
 * Configuration keys for the tether addon.
 *
 * <p>Enum constants are matched against YAML keys in {@code tether.yml} by RTP's
 * {@link io.github.dailystruggle.rtp.common.configuration.ConfigParser}. Add a new constant here to
 * expose a new option; no reflection glue is needed.
 */
public enum TetherKeys {
  /** When true, the tether addon is active; when false it registers nothing (hard off switch). */
  enabled,
  /**
   * What happens when a tethered player crosses the boundary of the region they are tethered to.
   *
   * <p>{@code PULL_BACK} teleports the player back to a fresh safe destination inside the region
   * (drawn from the core supply pipeline, so it satisfies the same safety guarantees as any RTP).
   * This is the platform-neutral enforcement; unlike vetoing the movement it works identically on
   * Bukkit/Paper/Folia and Fabric/NeoForge. Read as a string; unknown values fall back to
   * {@code PULL_BACK}.
   */
  onExit,
  /**
   * When true, active tethers are persisted through the core database interface so a player stays
   * tethered across a server restart or relog; when false a tether lasts only for the current
   * session.
   */
  persistState,
}
