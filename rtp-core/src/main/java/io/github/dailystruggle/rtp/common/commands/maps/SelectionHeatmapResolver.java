package io.github.dailystruggle.rtp.common.commands.maps;

import io.github.dailystruggle.mapsapi.BiomeColorSource;
import io.github.dailystruggle.mapsapi.model.SelectionHeatmap;
import io.github.dailystruggle.mapsapi.render.SelectionHeatmapRenderer;
import io.github.dailystruggle.rtp.api.maps.ChartSpec;
import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.selection.region.RTPLocation;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolver for {@link ChartSpec.Kind#SELECTION_HEATMAP} (ADR-089, ADR-039).
 * Builds a {@link SelectionHeatmap} capturing candidate visits, queue states,
 * hazard discards, and continuous density across the region domain.
 *
 * <p>Operates 100% off-tick with zero main-thread chunk I/O (Rule S-005).</p>
 */
public final class SelectionHeatmapResolver implements ChartSpecResolver {

  private static final int GRID_W = 512;
  private static final int GRID_H = 512;

  public SelectionHeatmapResolver() {
    // Explicit public constructor
  }

  @Override
  public Resolution resolve(ChartSpec spec) throws UnresolvableChartSpecException {
    if (spec == null) {
      throw new UnresolvableChartSpecException("spec shall not be null");
    }
    if (spec.kind() != ChartSpec.Kind.SELECTION_HEATMAP && spec.kind() != ChartSpec.Kind.BAD_POINTS_HEATMAP) {
      throw new UnresolvableChartSpecException(
          "SelectionHeatmapResolver handles SELECTION_HEATMAP or BAD_POINTS_HEATMAP, got " + spec.kind());
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
          "region '" + region.name + "' shape is not a MemoryShape");
    }

    long range = memoryShape.getRange();
    if (range <= 0) {
      throw new UnresolvableChartSpecException("region range must be positive");
    }

    // 1. Discover Bounding Box matching ComprehensiveRegionImageExporter
    int sampleCount = 4096;
    long boundingStep = Math.max(1L, range / sampleCount);
    int minX = Integer.MAX_VALUE;
    int maxX = Integer.MIN_VALUE;
    int minZ = Integer.MAX_VALUE;
    int maxZ = Integer.MIN_VALUE;
    int samples = 0;

    for (long i = 0L; i < range; i += boundingStep) {
      int[] xz = memoryShape.locationToXZ(i);
      if (xz == null || xz.length < 2) continue;
      if (xz[0] < minX) minX = xz[0];
      if (xz[0] > maxX) maxX = xz[0];
      if (xz[1] < minZ) minZ = xz[1];
      if (xz[1] > maxZ) maxZ = xz[1];
      samples++;
    }

    if (samples == 0) {
      minX = -100; maxX = 100;
      minZ = -100; maxZ = 100;
    }

    int extentX = maxX - minX;
    int extentZ = maxZ - minZ;
    int pad = Math.max(16, Math.max(extentX, extentZ) / 20);
    minX -= pad; maxX += pad;
    minZ -= pad; maxZ += pad;
    long boundW = Math.max(1L, (long) maxX - minX);
    long boundH = Math.max(1L, (long) maxZ - minZ);

    // 2. Compute domain containment and backdrop terrain grid
    int totalElements = GRID_W * GRID_H;
    boolean[] insideDomain = new boolean[totalElements];
    int[] biomeRgb = new int[totalElements];

    for (int py = 0; py < GRID_H; py++) {
      int bz = (int) (minZ + (long) py * boundH / (GRID_H - 1));
      int row = py * GRID_W;
      for (int px = 0; px < GRID_W; px++) {
        int bx = (int) (minX + (long) px * boundW / (GRID_W - 1));
        int idx = row + px;
        if (memoryShape.contains(bx, bz)) {
          insideDomain[idx] = true;
          String biomeName = memoryShape.biomeAt(bx, bz);
          biomeRgb[idx] = (biomeName != null) ? (BiomeColorSource.resolve(biomeName) & 0xFFFFFF) : 0x2ECC71;
        } else {
          insideDomain[idx] = false;
        }
      }
    }

    // 3. Collect Selection Points & Accumulate Density
    List<SelectionHeatmap.SelectionPoint> points = new ArrayList<>();
    double[] density = new double[totalElements];

    // Splat kernel radius for smooth density accumulation (in grid cells)
    final int kernelRadius = 6;
    final double sigma2 = 2.0 * 2.5 * 2.5;

    // A. L1 Hot Queue Locations
    if (region.queueManager != null && region.queueManager.keptLocations != null) {
      int l1Size = region.queueManager.keptLocations.size();
      for (int i = 0; i < l1Size; i++) {
        RTPLocation loc = region.queueManager.keptLocations.get(i);
        if (loc != null && loc.coords() != null) {
          int bx = loc.coords().x();
          int bz = loc.coords().z();
          points.add(new SelectionHeatmap.SelectionPoint(
              bx, bz, 1, SelectionHeatmap.SelectionType.QUEUE_L1));
          splatDensity(density, bx, bz, 2.5, minX, minZ, boundW, boundH, kernelRadius, sigma2);
        }
      }
    }

    // B. L2 Cold Queue Locations
    if (region.queueManager != null && region.queueManager.unkeptLocations != null) {
      int l2Size = region.queueManager.unkeptLocations.size();
      for (int i = 0; i < l2Size; i++) {
        RTPLocation loc = region.queueManager.unkeptLocations.get(i);
        if (loc != null && loc.coords() != null) {
          int bx = loc.coords().x();
          int bz = loc.coords().z();
          points.add(new SelectionHeatmap.SelectionPoint(
              bx, bz, 1, SelectionHeatmap.SelectionType.QUEUE_L2));
          splatDensity(density, bx, bz, 1.8, minX, minZ, boundW, boundH, kernelRadius, sigma2);
        }
      }
    }

    // C. Discarded Hazard Points
    long[] badKeys = memoryShape.badKeysSnapshot();
    if (badKeys != null && badKeys.length > 0) {
      int limit = Math.min(badKeys.length, 500);
      for (int i = 0; i < limit; i++) {
        int[] xz = memoryShape.locationToXZ(badKeys[i]);
        if (xz != null && xz.length >= 2) {
          int bx = xz[0];
          int bz = xz[1];
          points.add(new SelectionHeatmap.SelectionPoint(
              bx, bz, 1, SelectionHeatmap.SelectionType.HAZARD_DISCARD));
          splatDensity(density, bx, bz, 2.0, minX, minZ, boundW, boundH, kernelRadius, sigma2);
        }
      }
    }

    // D. Candidate Samples across the selection algorithm
    // Sample candidates from the shape to reveal spatial selection distribution
    int candidateSampleSize = Math.min(600, (int) Math.min(range, 2000L));
    long stride = Math.max(1L, range / candidateSampleSize);
    MutableRTPCoords coords = new MutableRTPCoords(0, 0);

    for (long idx = 0L; idx < range && points.size() < 1200; idx += stride) {
      memoryShape.locationToXZ(idx, coords);
      int bx = coords.x;
      int bz = coords.z;
      if (memoryShape.contains(bx, bz)) {
        points.add(new SelectionHeatmap.SelectionPoint(
            bx, bz, 1, SelectionHeatmap.SelectionType.CANDIDATE));
        splatDensity(density, bx, bz, 1.0, minX, minZ, boundW, boundH, kernelRadius, sigma2);
      }
    }

    // Find max density
    double maxDensity = 0.0;
    for (double v : density) {
      if (v > maxDensity) maxDensity = v;
    }
    if (maxDensity <= 0.0) maxDensity = 1.0;

    SelectionHeatmap model = new SelectionHeatmap(
        region.name,
        GRID_W,
        GRID_H,
        minX,
        minZ,
        maxX,
        maxZ,
        insideDomain,
        biomeRgb,
        density,
        maxDensity,
        points
    );

    return Resolution.of(SelectionHeatmapRenderer.INSTANCE, model);
  }

  private static void splatDensity(
      double[] density, int bx, int bz, double weight,
      int minX, int minZ, long boundW, long boundH,
      int kernelRadius, double sigma2
  ) {
    int gx = (int) ((bx - minX) * (GRID_W - 1) / boundW);
    int gz = (int) ((bz - minZ) * (GRID_H - 1) / boundH);

    int startX = Math.max(0, gx - kernelRadius);
    int endX = Math.min(GRID_W - 1, gx + kernelRadius);
    int startZ = Math.max(0, gz - kernelRadius);
    int endZ = Math.min(GRID_H - 1, gz + kernelRadius);

    for (int z = startZ; z <= endZ; z++) {
      int dz = z - gz;
      int row = z * GRID_W;
      for (int x = startX; x <= endX; x++) {
        int dx = x - gx;
        double dist2 = dx * dx + dz * dz;
        double g = Math.exp(-dist2 / sigma2);
        density[row + x] += weight * g;
      }
    }
  }
}
