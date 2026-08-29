package io.github.dailystruggle.rtp.groupaddon;

/**
 * Configuration keys for an individual group placement profile (.yml file in definitions/groups/).
 *
 * <p>Enum constants are matched against YAML keys by RTP's
 * {@link io.github.dailystruggle.rtp.common.configuration.ConfigParser}.
 *
 * <p><b>Units.</b> The subspace footprint is expressed in <em>chunks</em> because the inherited
 * parent {@code MemoryShape} stores validity at chunk granularity; placement spacing and elevation
 * are expressed in <em>blocks</em> because participants stand on individual columns. See
 * {@code SubspaceShape} for why the two must not be conflated.
 */
public enum GroupKeys {
  /**
   * Geometric distribution pattern (CLUSTER, OPPOSING_POLES, RING, GRID).
   */
  distribution,
  /**
   * Subspace footprint half-width in CHUNKS. The footprint spans {@code (2*n + 1)^2} chunks around
   * the anchor chunk (e.g. {@code 1} = 3x3 chunks = 48x48 blocks). This is the chunk-granularity
   * Stage 1 pre-filter bound, not a block radius.
   */
  subspaceChunkRadius,
  /**
   * Minimum separation in BLOCKS between any two placed participants within the subspace. This is
   * the single spacing knob: the Stage 2 block sampling stride is derived internally from it, so
   * there is no separate {@code blockStep} key to conflict with it.
   */
  minSeparation,
  /**
   * Maximum allowed elevation delta (Y-variance) in BLOCKS between participants in the subspace.
   */
  elevationTolerance,
  /**
   * Maximum members teleported in a single group operation using this profile.
   */
  maxGroupSize
}
