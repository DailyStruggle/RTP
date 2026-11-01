package io.github.dailystruggle.rtp.api.world;

/**
 * Per-world classification of biome sampling cost (ADR-062).
 *
 * <p>Enables core gray-space steering without importing platform types.
 */
public enum BiomeSampleCapability {
  /** Cheapest: deterministic noise-source sample, no chunk I/O. */
  NOISE_SAMPLABLE,
  /** Mid: read already-generated chunks from disk (Anvil), no noise sample. */
  ANVIL_ONLY,
  /** Worst case: must generate the chunk to learn its biome. */
  GENERATE_REQUIRED
}
