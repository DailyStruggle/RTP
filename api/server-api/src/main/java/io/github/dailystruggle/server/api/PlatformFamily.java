package io.github.dailystruggle.server.api;

/**
 * Coarse classification of a server runtime by its addon-facing platform surface.
 */
public enum PlatformFamily {
  /** Bukkit/Spigot and every Bukkit-derived fork (Paper, Folia, Purpur, ...). */
  BUKKIT,
  /** Fabric loader. */
  FABRIC,
  /** NeoForge loader. */
  NEOFORGE,
  /** Unrecognised runtime; treated as no platform-specific behaviour. */
  UNKNOWN
}
