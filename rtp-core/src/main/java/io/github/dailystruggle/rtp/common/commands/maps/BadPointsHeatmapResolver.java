package io.github.dailystruggle.rtp.common.commands.maps;

import io.github.dailystruggle.mapsapi.model.Heatmap2D;
import io.github.dailystruggle.mapsapi.render.HeatmapRenderer;
import io.github.dailystruggle.rtp.api.maps.ChartSpec;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;

/**
 * Resolves {@link ChartSpec.Kind#BAD_POINTS_HEATMAP} into a {@link Heatmap2D}.
 *
 * <p>Reads {@link MemoryShape#badKeysSnapshot()} without chunk I/O (S-005, REQ-RTP-MAP-006).
 * Bins bad-location coordinates into a {@value #BINS}x{@value #BINS} grid matching the canvas.
 */
public final class BadPointsHeatmapResolver implements ChartSpecResolver {

  /** Constructs a resolver. Stateless; a single instance may be shared. */
  public BadPointsHeatmapResolver() {
  }

  /** Resolution of the binned heatmap. Matches the vanilla 128px canvas exactly. */
  public static final int BINS = 128;

  @Override
  public Resolution resolve(ChartSpec spec) throws UnresolvableChartSpecException {
    if (spec == null) {
      throw new UnresolvableChartSpecException("spec shall not be null");
    }
    if (spec.kind() != ChartSpec.Kind.BAD_POINTS_HEATMAP) {
      throw new UnresolvableChartSpecException(
          "BadPointsHeatmapResolver only handles BAD_POINTS_HEATMAP, got " + spec.kind());
    }

    Region region;
    try {
      region = RTP.selectionAPI.getRegionOrDefault(spec.regionName());
    } catch (RuntimeException e) {
      throw new UnresolvableChartSpecException(
          "no region resolved for '" + spec.regionName() + "'", e);
    }
    if (region == null) {
      throw new UnresolvableChartSpecException(
          "no region resolved for '" + spec.regionName() + "'");
    }
    if (!(region.shape instanceof MemoryShape<?> memoryShape)) {
      throw new UnresolvableChartSpecException(
          "region '" + region.name + "' shape is not a MemoryShape; no badKeys snapshot available");
    }

    long[] keys = memoryShape.badKeysSnapshot();
    if (keys.length == 0) {
      throw new UnresolvableChartSpecException(
          "region '" + region.name + "' has no bad-location data yet");
    }

    // Two-pass bbox + bin. Decode XZ once into local arrays; data-driven
    // extent so the resolver works for any MemoryShape subtype without
    // depending on the shape's range-encoding semantics
    // (Square.getRange() is a 1D index span, not a coordinate radius).
    int n = keys.length;
    int[] xs = new int[n];
    int[] zs = new int[n];
    int decoded = 0;
    int minX = Integer.MAX_VALUE;
    int maxX = Integer.MIN_VALUE;
    int minZ = Integer.MAX_VALUE;
    int maxZ = Integer.MIN_VALUE;
    for (long key : keys) {
      int[] xz = memoryShape.locationToXZ(key);
      if (xz == null || xz.length < 2) continue;
      xs[decoded] = xz[0];
      zs[decoded] = xz[1];
      if (xz[0] < minX) minX = xz[0];
      if (xz[0] > maxX) maxX = xz[0];
      if (xz[1] < minZ) minZ = xz[1];
      if (xz[1] > maxZ) maxZ = xz[1];
      decoded++;
    }
    if (decoded == 0) {
      throw new UnresolvableChartSpecException(
          "region '" + region.name + "' has bad-location keys but none decoded to valid XZ pairs");
    }
    // Widen any degenerate axis so the bbox is strictly positive and every
    // sample falls inside the binned range when divided by cellSize.
    if (maxX == minX) maxX = minX + 1;
    if (maxZ == minZ) maxZ = minZ + 1;
    double cellW = (double) (maxX - minX + 1) / (double) BINS;
    double cellH = (double) (maxZ - minZ + 1) / (double) BINS;

    double[] values = new double[BINS * BINS];
    double max = 0.0;
    for (int i = 0; i < decoded; i++) {
      int bx = (int) Math.floor((xs[i] - minX) / cellW);
      int bz = (int) Math.floor((zs[i] - minZ) / cellH);
      if (bx < 0) bx = 0;
      if (bx >= BINS) bx = BINS - 1;
      if (bz < 0) bz = 0;
      if (bz >= BINS) bz = BINS - 1;
      int idx = bz * BINS + bx;
      double v = values[idx] + 1.0;
      values[idx] = v;
      if (v > max) max = v;
    }

    Heatmap2D model = new Heatmap2D(BINS, BINS, values, 0.0, max);
    return Resolution.of(HeatmapRenderer.INSTANCE, model);
  }
}
