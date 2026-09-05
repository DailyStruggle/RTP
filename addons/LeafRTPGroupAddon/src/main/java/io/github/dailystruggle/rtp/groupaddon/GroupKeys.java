package io.github.dailystruggle.rtp.groupaddon;

/**
 * Configuration keys for an individual group placement profile (.yml file in definitions/groups/).
 *
 * <p>Enum constants are matched against YAML keys by RTP's
 * {@link io.github.dailystruggle.rtp.common.configuration.ConfigParser}.
 *
 * <p>The profile is intentionally thin: everything spatial is delegated to the {@code shape} block,
 * reusing the same parameters regions use ({@code radius}/{@code centerRadius} for the footprint,
 * {@code spatialResolution} for the selection stride, {@code uniquePlacements} for non-repeating
 * selection). Only {@code maxGroupSize} is group-specific.
 */
public enum GroupKeys {
  /**
   * Placement shape as a nested block, mirroring how regions declare their shape: a {@code name}
   * plus that shape's parameters. Reused parameters:
   * <ul>
   *   <li>{@code radius} / {@code centerRadius} - subspace footprint half-width in CHUNKS (Stage 1
   *       chunk-granularity pre-filter bound).</li>
   *   <li>{@code spatialResolution} - the selection stride. NOTE: in group placement this field is
   *       reused as the placement stride (participant spacing); this second meaning is local to the
   *       group code and does not change its region/scan meaning elsewhere.</li>
   *   <li>{@code uniquePlacements} - non-repeating selection so no two participants share a slot.</li>
   * </ul>
   * Any registered {@code Shape} may be named; resolution lives in {@code GroupShapes}.
   */
  shape,
  /**
   * Maximum members teleported in a single group operation using this profile.
   */
  maxGroupSize
}
