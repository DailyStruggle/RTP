package io.github.dailystruggle.mapsapi.model;

import java.util.List;
import java.util.Objects;

/**
 * Diagnostic model capturing selection frequency, candidate locations, and density heatmap
 * across an RTP region's spatial domain (ADR-089, ADR-039).
 *
 * <p>Captures:
 * <ul>
 *   <li>Exact world coordinate bounds {@code [minX, minZ, maxX, maxZ]} for 1:1 canvas mapping.</li>
 *   <li>Discrete selection sample points (coordinates, frequency/weight, selection type, status).</li>
 *   <li>Domain containment mask and terrain/biome colors for geographic context and shape contour.</li>
 *   <li>Accumulated 2D density grid across the bounding box.</li>
 * </ul>
 *
 * <p>Immutable and defensively copied (REQ-RTP-MAP-002).</p>
 */
public record SelectionHeatmap(
    String regionName,
    int width,
    int height,
    int minX,
    int minZ,
    int maxX,
    int maxZ,
    boolean[] insideDomain,
    int[] biomeRgb,
    double[] densityGrid,
    double maxDensity,
    List<SelectionPoint> points
) implements ChartModel {

  public enum SelectionType {
    CANDIDATE,       // General selection candidate drawn by the selection algorithm
    QUEUE_L1,        // Warmed L1 queue location
    QUEUE_L2,        // Pre-verified L2 queue location
    ARRIVAL,         // Verified landing destination
    HAZARD_DISCARD   // Selection discarded due to safety/biome hazard
  }

  public record SelectionPoint(
      int blockX,
      int blockZ,
      int count,
      SelectionType type
  ) {
    public SelectionPoint {
      Objects.requireNonNull(type, "type shall not be null");
    }
  }

  public SelectionHeatmap {
    Objects.requireNonNull(regionName, "regionName shall not be null");
    if (width <= 0) throw new IllegalArgumentException("width shall be > 0, got " + width);
    if (height <= 0) throw new IllegalArgumentException("height shall be > 0, got " + height);
    Objects.requireNonNull(insideDomain, "insideDomain shall not be null");
    if (insideDomain.length != width * height) {
      throw new IllegalArgumentException(
          "insideDomain length " + insideDomain.length + " != width * height " + (width * height));
    }
    Objects.requireNonNull(densityGrid, "densityGrid shall not be null");
    if (densityGrid.length != width * height) {
      throw new IllegalArgumentException(
          "densityGrid length " + densityGrid.length + " != width * height " + (width * height));
    }
    Objects.requireNonNull(points, "points shall not be null");

    insideDomain = insideDomain.clone();
    biomeRgb = (biomeRgb != null) ? biomeRgb.clone() : new int[0];
    densityGrid = densityGrid.clone();
    points = List.copyOf(points);
  }

  @Override
  public boolean[] insideDomain() {
    return insideDomain.clone();
  }

  public int[] biomeRgb() {
    return biomeRgb.clone();
  }

  @Override
  public double[] densityGrid() {
    return densityGrid.clone();
  }

  public long boundW() {
    return Math.max(1L, (long) maxX - minX);
  }

  public long boundH() {
    return Math.max(1L, (long) maxZ - minZ);
  }
}
