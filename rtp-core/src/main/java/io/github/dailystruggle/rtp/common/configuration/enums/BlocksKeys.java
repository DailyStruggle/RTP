package io.github.dailystruggle.rtp.common.configuration.enums;

/**
 * Landing-safety block lists (ADR-071). Split out of {@link SafetyKeys} into their
 * own advanced-tier file {@code advanced/blocks.yml} so the common {@code safety.yml}
 * holds only the high-touch safety knobs (one enum = one file = one parser).
 */
public enum BlocksKeys {
  /**
   * Blocks treated as passable air for landing-safety checks. Accepts Bukkit
   * Material names and {@code "#minecraft:<tag>"} tag references.
   */
  airBlocks,
  /**
   * Blocks that disqualify a landing spot (lava, hazards, etc.). Accepts Bukkit
   * Material names, {@code "#minecraft:<tag>"} tag references, state predicates such
   * as {@code "*[waterlogged=true]"}, and numeric range predicates on integer
   * block-state properties using {@code >=, <=, >, <}.
   */
  unsafeBlocks,
  version
}
