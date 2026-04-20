package io.github.dailystruggle.rtp.common.selection.region.selectors.memory;

/**
 * Represents the different modes for handling biome-based location selection
 * in memory-based shapes.
 */
public enum Mode {
  /**
   * Accumulate biome data over time. The selector will remember which areas
   * correspond to which biomes and use this data to speed up future selections.
   */
  ACCUMULATE,

  /**
   * When a selected location is not in the desired biome, find the nearest
   * valid location that is. This can be computationally expensive.
   */
  NEAREST,

  /**
   * If a selected location is not in the desired biome, simply re-roll and
   * try again. This is the simplest and often fastest method.
   */
  REROLL,

  /**
   * Do not perform any special biome handling. The first selected location
   * will be used regardless of its biome.
   */
  NONE
}
