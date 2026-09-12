package io.github.dailystruggle.rtp.api.group;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;
import java.util.Objects;

/**
 * Immutable, platform-neutral bundle of the required group-placement parameters.
 *
 * <p>Supplied on every {@link GroupPlacementRequest}. Carries only primitives, so no implementation
 * types cross this boundary. {@code distribution} is the case-insensitive name of a registered
 * placement shape (e.g. {@code circle}, {@code square}) that governs how participants are laid out
 * within the subspace; an unrecognized name falls back to the implementation's default shape.
 *
 * <p><b>Units.</b> {@code radius}, {@code minSeparation}, and {@code elevationTolerance} are all in
 * blocks. {@code radius} is the subspace footprint half-width.
 */
@PublicApi
public final class GroupProfileSpec {

  private final String distribution;
  private final int radius;
  private final int minSeparation;
  private final int elevationTolerance;
  private final int maxGroupSize;

  private GroupProfileSpec(
      String distribution,
      int radius,
      int minSeparation,
      int elevationTolerance,
      int maxGroupSize) {
    this.distribution = distribution;
    this.radius = radius;
    this.minSeparation = minSeparation;
    this.elevationTolerance = elevationTolerance;
    this.maxGroupSize = maxGroupSize;
  }

  /**
   * Builds an inline profile specification.
   *
   * @param distribution case-insensitive distribution name; must not be {@code null} or blank
   * @param radius footprint half-width in blocks (clamped to {@code >= 0})
   * @param minSeparation minimum block distance between participants (clamped to {@code >= 1})
   * @param elevationTolerance maximum block Y delta between participants (clamped to {@code >= 0})
   * @param maxGroupSize maximum participant count (clamped to {@code >= 1})
   * @return an immutable profile specification
   * @throws IllegalArgumentException if {@code distribution} is {@code null} or blank
   */
  public static GroupProfileSpec of(
      String distribution,
      int radius,
      int minSeparation,
      int elevationTolerance,
      int maxGroupSize) {
    if (distribution == null || distribution.trim().isEmpty()) {
      throw new IllegalArgumentException("distribution must not be null or blank");
    }
    return new GroupProfileSpec(
        distribution,
        Math.max(0, radius),
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
   * @return the subspace footprint half-width in blocks
   */
  public int radius() {
    return radius;
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
    return radius == that.radius
        && minSeparation == that.minSeparation
        && elevationTolerance == that.elevationTolerance
        && maxGroupSize == that.maxGroupSize
        && distribution.equalsIgnoreCase(that.distribution);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        distribution.toLowerCase(),
            radius,
        minSeparation,
        elevationTolerance,
        maxGroupSize);
  }

  @Override
  public String toString() {
    return "GroupProfileSpec[" + distribution
        + ", radius=" + radius
        + ", minSep=" + minSeparation
        + ", elevTol=" + elevationTolerance
        + ", maxGroup=" + maxGroupSize + ']';
  }
}
