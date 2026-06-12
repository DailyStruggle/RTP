package io.github.dailystruggle.rtp.api.world;

/**
 * ADR-062 Phase 3 - per-world classification of how cheaply the platform can
 * answer "what biome is at (x, z)?" for an <em>arbitrary, not-yet-recorded</em>
 * coordinate. The cost/accuracy ladder runs cheapest-first:
 *
 * <ol>
 *   <li>{@link #NOISE_SAMPLABLE} - the world's biome source is a deterministic
 *       noise source the adapter can query without loading or generating a
 *       chunk (vanilla {@code MultiNoiseBiomeSource} and equivalents). The
 *       cheapest tier, no chunk I/O.</li>
 *   <li>{@link #ANVIL_ONLY} - the biome cannot be noise-sampled, but already
 *       generated chunks can be read from disk via the Anvil pre-filter
 *       (ADR-016). The biome of an <em>ungenerated</em> coordinate is unknown
 *       without generation.</li>
 *   <li>{@link #GENERATE_REQUIRED} - the only way to learn the biome of an
 *       ungenerated coordinate is to generate the chunk. This is the worst
 *       case (cost already incurred by the L2/cold cache fill and the periodic
 *       random sampler), and shall never run synchronously on the main thread
 *       (S-005).</li>
 * </ol>
 *
 * <p>The classification is resolved per world (once per {@code /rtp}
 * invocation, like the biome registry and {@code biomeWeights}) and lets
 * {@code rtp-core} branch the gray-space steering decision without importing
 * any platform / {@code net.minecraft.*} types.
 */
public enum BiomeSampleCapability {
  /** Cheapest: deterministic noise-source sample, no chunk I/O. */
  NOISE_SAMPLABLE,
  /** Mid: read already-generated chunks from disk (Anvil), no noise sample. */
  ANVIL_ONLY,
  /** Worst case: must generate the chunk to learn its biome. */
  GENERATE_REQUIRED
}
