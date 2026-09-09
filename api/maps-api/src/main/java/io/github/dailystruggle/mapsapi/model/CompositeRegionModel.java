package io.github.dailystruggle.mapsapi.model;

import java.util.List;
import java.util.Objects;

/**
 * Composite model representing a region's spatial visualization (ADR-089):
 * <ul>
 *   <li>Desaturated biome background.
 *   <li>Red hazard overlay mask.
 *   <li>L1 / L2 / L3 location points and recent teleports.
 *   <li>Trajectory vector hops between consecutive teleports.
 *   <li>L1 / L2 / L3 queue capacity gauge fractions.
 * </ul>
 */
public record CompositeRegionModel(
    String regionName,
    int width,
    int height,
    int[] biomeRgb,
    boolean[] hazardMask,
    boolean[] insideDomain,
    List<CompositeRegionModel.Marker> markers,
    List<CompositeRegionModel.VectorLine> trajectoryLines,
    CompositeRegionModel.QueueGauge l1Gauge,
    CompositeRegionModel.QueueGauge l2Gauge,
    CompositeRegionModel.QueueGauge l3Gauge
) implements ChartModel {

  /**
   * Represents an individual point marker on the map.
   */
  public record Marker(int x, int y, int fillRgb, int borderRgb, int radius) {
    public Marker {
      if (radius < 0) radius = 0;
    }
  }

  /**
   * Represents a vector line on the map.
   */
  public record VectorLine(int x0, int y0, int x1, int y1, int rgb) {}

  /**
   * Capacity and current occupancy for a queue layer.
   */
  public record QueueGauge(String name, int current, int maxCapacity, int fillRgb) {
    public double fraction() {
      return maxCapacity > 0 ? (double) current / maxCapacity : 0.0;
    }
  }

  public CompositeRegionModel {
    Objects.requireNonNull(regionName, "regionName");
    biomeRgb = biomeRgb != null ? biomeRgb.clone() : new int[width * height];
    hazardMask = hazardMask != null ? hazardMask.clone() : new boolean[width * height];
    insideDomain = insideDomain != null ? insideDomain.clone() : new boolean[width * height];
    markers = markers != null ? List.copyOf(markers) : List.of();
    trajectoryLines = trajectoryLines != null ? List.copyOf(trajectoryLines) : List.of();
    l1Gauge = l1Gauge != null ? l1Gauge : new QueueGauge("L1", 0, 1, 0x2ECC71);
    l2Gauge = l2Gauge != null ? l2Gauge : new QueueGauge("L2", 0, 1, 0x3498DB);
    l3Gauge = l3Gauge != null ? l3Gauge : new QueueGauge("L3", 0, 1, 0x9B59B6);
  }

  @Override
  public int[] biomeRgb() {
    return biomeRgb.clone();
  }

  @Override
  public boolean[] hazardMask() {
    return hazardMask.clone();
  }

  @Override
  public boolean[] insideDomain() {
    return insideDomain.clone();
  }
}
