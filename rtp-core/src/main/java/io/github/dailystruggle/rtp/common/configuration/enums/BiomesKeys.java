package io.github.dailystruggle.rtp.common.configuration.enums;

/**
 * Biome whitelist/blacklist settings (ADR-071). Split out of {@link SafetyKeys}
 * into their own advanced-tier file {@code advanced/biomes.yml} so the common
 * {@code safety.yml} holds only the high-touch safety knobs (one enum = one file
 * = one parser).
 */
public enum BiomesKeys {
  /**
   * If true, {@link #biomes} is a whitelist (only those biomes are allowed). If
   * false, it is a blacklist (those biomes are excluded).
   */
  biomeWhitelist,
  /** Biomes treated as whitelist or blacklist members per {@link #biomeWhitelist}. */
  biomes,
  version
}
