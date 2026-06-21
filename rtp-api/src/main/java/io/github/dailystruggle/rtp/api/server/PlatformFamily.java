package io.github.dailystruggle.rtp.api.server;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;

/**
 * Coarse classification of the server runtime an {@link RTPServerAccessor} is bound to.
 *
 * <p>Unlike {@link RTPServerAccessor#getPlatform()} (which returns a free-form brand-ish
 * string such as {@code "Paper"} or {@code "Folia"}), a family groups every runtime that
 * shares an addon-facing API surface. It is derived from authoritative runtime detection
 * rather than the server's self-reported brand, so renamed forks (Purpur, Pufferfish,
 * Leaf, Gale, Folia forks, ...) resolve to their base family and an addon never has to
 * maintain a string allowlist or reach into a platform-specific dependency to discover
 * where it is running.
 */
@PublicApi
public enum PlatformFamily {
  /** Bukkit/Spigot and every Bukkit-derived fork (Paper, Folia, Purpur, ...). */
  BUKKIT,
  /** Fabric loader. */
  FABRIC,
  /** NeoForge loader. */
  NEOFORGE,
  /**
   * The runtime could not be classified into a known family. This is a reportable
   * condition: code that gates behaviour on a family should treat {@code UNKNOWN} as
   * "no platform-specific behaviour" and the API logs it so the unrecognised platform
   * surfaces in bug reports.
   */
  UNKNOWN
}
