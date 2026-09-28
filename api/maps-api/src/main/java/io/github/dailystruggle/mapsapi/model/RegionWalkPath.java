package io.github.dailystruggle.mapsapi.model;

import java.util.List;
import java.util.Objects;

/**
 * Snapshot of a region's walk path / selection trajectory across its domain (ADR-089).
 *
 * <p>Captures:
 * <ul>
 *   <li>The discrete walk points/steps along the shape (e.g. Archimedean spiral index sequence).</li>
 *   <li>Per-step status classification: VALID (within bounds, safe), HAZARD (within bounds, bad/discarded),
 *       OUT_OF_BOUNDS (outside region domain).</li>
 *   <li>Per-step progression ratio {@code [0.0, 1.0]} denoting selection order from start to finish.</li>
 *   <li>Exact world coordinate bounds {@code [minX, minZ, maxX, maxZ]} for continuous canvas mapping.</li>
 *   <li>The domain containment grid for background contrast and boundary silhouette.</li>
 *   <li>Optional biome RGB array and hazard mask for terrain context matching comprehensive views.</li>
 * </ul>
 *
 * <p>All list and array fields are defensively copied on construction and read (REQ-RTP-MAP-002).
 */
public record RegionWalkPath(
    String regionName,
    int width,
    int height,
    int minX,
    int minZ,
    int maxX,
    int maxZ,
    boolean[] insideDomain,
    int[] biomeRgb,
    boolean[] hazardMask,
    List<WalkStep> steps
) implements ChartModel {

  public enum StepStatus {
    VALID,
    HAZARD,
    OUT_OF_BOUNDS
  }

  public record WalkStep(
      int x,
      int y,
      StepStatus status,
      float progress
  ) {
    public WalkStep {
      Objects.requireNonNull(status, "status shall not be null");
    }
  }

  public RegionWalkPath {
    Objects.requireNonNull(regionName, "regionName shall not be null");
    if (width <= 0) throw new IllegalArgumentException("width shall be > 0, got " + width);
    if (height <= 0) throw new IllegalArgumentException("height shall be > 0, got " + height);
    Objects.requireNonNull(insideDomain, "insideDomain shall not be null");
    if (insideDomain.length != width * height) {
      throw new IllegalArgumentException(
          "insideDomain length " + insideDomain.length + " != width * height " + (width * height));
    }
    Objects.requireNonNull(steps, "steps shall not be null");
    insideDomain = insideDomain.clone();
    biomeRgb = (biomeRgb != null) ? biomeRgb.clone() : new int[0];
    hazardMask = (hazardMask != null) ? hazardMask.clone() : new boolean[0];
    steps = List.copyOf(steps);
  }

  /**
   * Convenience constructor for basic walk path without explicit terrain backdrop.
   */
  public RegionWalkPath(
      String regionName,
      int width,
      int height,
      boolean[] insideDomain,
      List<WalkStep> steps
  ) {
    this(regionName, width, height, 0, 0, width, height, insideDomain, null, null, steps);
  }

  @Override
  public boolean[] insideDomain() {
    return insideDomain.clone();
  }

  public int[] biomeRgb() {
    return biomeRgb.clone();
  }

  public boolean[] hazardMask() {
    return hazardMask.clone();
  }

  public long boundW() {
    return Math.max(1L, (long) maxX - minX);
  }

  public long boundH() {
    return Math.max(1L, (long) maxZ - minZ);
  }
}
