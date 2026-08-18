package io.github.dailystruggle.rtp.api.server;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;

/**
 * Coarse classification of the server runtime bound to {@link RTPServerAccessor}.
 * Groups runtimes sharing an addon-facing API surface (Bukkit forks, Fabric, NeoForge).
 */
@PublicApi
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
