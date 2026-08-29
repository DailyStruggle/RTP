package io.github.dailystruggle.rtp.api.group;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;
import java.util.Objects;

/**
 * Immutable, platform-neutral bundle of the required group-placement parameters.
 *
 * <p>Supplied on every {@link GroupPlacementRequest}. Carries only primitives, so no implementation
 * types cross this boundary. {@code distribution} is a case-insensitive name (e.g. {@code CLUSTER},
 * {@code OPPOSING_POLES}, {@code RING}, {@code GRID}); unrecognized values fall back to the
 * implementation's default.
 *
 * <p><b>Units.</b> {@code subspaceChunkRadius} is in chunks (footprint half-width);
 * {@code minSeparation} and {@code elevationTolerance} are in blocks.
 */
@PublicApi
public final class GroupProfileSpec {

  private final String distribution;
  private final int subspaceChunkRadius;
  private final int minSeparation;
  private final int elevationTolerance;
  private final int maxGroupSize;

  private GroupProfileSpec(
      String distribution,
      int subspaceChunkRadius,
      int minSeparation,
      int elevationTolerance,
      int maxGroupSize) {
    this.distribution = distribution;
    this.subspaceChunkRadius = subspaceChunkRadius;
    this.minSeparation = minSeparation;
    this.elevationTolerance = elevationTolerance;
    this.maxGroupSize = maxGroupSize;
  }

  /**
   * Builds an inline profile specification.
   *
   * @param distribution case-insensitive distribution name; must not be {@code null} or blank
   * @param subspaceChunkRadius footprint half-width in chunks (clamped to {@code >= 0})
   * @param minSeparation minimum block distance between participants (clamped to {@code >= 1})
   * @param elevationTolerance maximum block Y delta between participants (clamped to {@code >= 0})
   * @param maxGroupSize maximum participant count (clamped to {@code >= 1})
   * @return an immutable profile specification
   * @throws IllegalArgumentException if {@code distribution} is {@code null} or blank
   */
  public static GroupProfileSpec of(
      String distribution,
      int subspaceChunkRadius,
      int minSeparation,
      int elevationTolerance,
      int maxGroupSize) {
    if (distribution == null || distribution.trim().isEmpty()) {
      throw new IllegalArgumentException("distribution must not be null or blank");
    }
    return new GroupProfileSpec(
        distribution,
        Math.max(0, subspaceChunkRadius),
        Math.max(1, minSeparation),
        Math.max(0, elevationTolerance),
        Math.max(1, maxGroupSize));
  }

  /**
   * @return the case-insensitive distribution name; never {@code null} or blank
   */
  public String distribution() {
    return distribution;
  }

  /**
   * @return the Stage 1 footprint half-width in chunks
   */
  public int subspaceChunkRadius() {
    return subspaceChunkRadius;
  }

  /**
   * @return the minimum block distance between placed participants
   */
  public int minSeparation() {
    return minSeparation;
  }

  /**
   * @return the maximum block Y delta permitted between participants
   */
  public int elevationTolerance() {
    return elevationTolerance;
  }

  /**
   * @return the maximum participant count this profile supports
   */
  public int maxGroupSize() {
    return maxGroupSize;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof GroupProfileSpec)) return false;
    GroupProfileSpec that = (GroupProfileSpec) o;
    return subspaceChunkRadius == that.subspaceChunkRadius
        && minSeparation == that.minSeparation
        && elevationTolerance == that.elevationTolerance
        && maxGroupSize == that.maxGroupSize
        && distribution.equalsIgnoreCase(that.distribution);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        distribution.toLowerCase(),
        subspaceChunkRadius,
        minSeparation,
        elevationTolerance,
        maxGroupSize);
  }

  @Override
  public String toString() {
    return "GroupProfileSpec[" + distribution
        + ", chunkR=" + subspaceChunkRadius
        + ", minSep=" + minSeparation
        + ", elevTol=" + elevationTolerance
        + ", maxGroup=" + maxGroupSize + ']';
  }
}
