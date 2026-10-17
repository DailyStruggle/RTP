package io.github.dailystruggle.rtp.common.commands.maps;

import io.github.dailystruggle.mapsapi.model.SelectionHeatmap;
import io.github.dailystruggle.mapsapi.render.SelectionHeatmapRenderer;
import io.github.dailystruggle.rtp.api.maps.ChartSpec;
import io.github.dailystruggle.rtp.common.metrics.LandingHeatmap;
import io.github.dailystruggle.rtp.common.selection.region.LockFreeLocationBuffer;
import io.github.dailystruggle.rtp.common.selection.region.RTPLocation;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Resolver for {@link ChartSpec.Kind#SELECTION_HEATMAP} (ADR-089, ADR-039).
 * Builds a {@link SelectionHeatmap} of selections: recorded landings ({@link LandingHeatmap})
 * and queued selections, not bad locations (those are {@code REGION_BAD_LOCATIONS_SHAPE}).
 *
 * <p>Grid and bounds are in chunk units (shape space); queue locations are blocks and are
 * shifted to chunks. Landings are lost on restart, so a fresh server shows queues only.</p>
 *
 * <p>Operates 100% off-tick with zero main-thread chunk I/O (Rule S-005).</p>
 */
public final class SelectionHeatmapResolver extends AbstractRegionGridResolver {

  private static final int GRID_W = 512;
  private static final int GRID_H = 512;
  private static final int MAX_MARKERS = 2000;
  private static final Set<ChartSpec.Kind> SUPPORTED_KINDS =
      Set.of(ChartSpec.Kind.SELECTION_HEATMAP);

  public SelectionHeatmapResolver() {
    // Explicit public constructor
  }

  @Override
  public Resolution resolve(ChartSpec spec) throws UnresolvableChartSpecException {
    validateSpec(spec, SUPPORTED_KINDS);
    Region region = resolveRegion(spec);
    MemoryShape<?> memoryShape = resolveMemoryShape(region);

    // 1. Discover Bounding Box matching ComprehensiveRegionImageExporter
    RegionBounds bounds = discoverBounds(memoryShape, 4096, 16, 20);
    int minX = bounds.minX();
    int maxX = bounds.maxX();
    int minZ = bounds.minZ();
    int maxZ = bounds.maxZ();
    long boundW = bounds.boundW();
    long boundH = bounds.boundH();

    // 2. Compute domain containment and backdrop terrain grid
    GridDomain domain = sampleDomainGrid(memoryShape, bounds, GRID_W, GRID_H, false);
    boolean[] insideDomain = domain.insideDomain();
    int[] biomeRgb = domain.biomeRgb();
    int totalElements = GRID_W * GRID_H;

    // 3. Collect Selection Points & Accumulate Density
    List<SelectionHeatmap.SelectionPoint> points = new ArrayList<>();
    double[] density = new double[totalElements];

    // Splat kernel radius for smooth density accumulation (in grid cells)
    final int kernelRadius = 6;
    final double sigma2 = 2.0 * 2.5 * 2.5;

    // A. Landings: one row per chunk, weighted by landing count. Points carry chunk coords.
    for (int[] row : LandingHeatmap.snapshot(region.name)) {
      int cx = row[0];
      int cz = row[1];
      int n = row[2];
      if (points.size() < MAX_MARKERS) {
        points.add(new SelectionHeatmap.SelectionPoint(
            cx, cz, n, SelectionHeatmap.SelectionType.ARRIVAL));
      }
      splatDensity(density, cx, cz, n, minX, minZ, boundW, boundH, kernelRadius, sigma2);
    }

    // B. Queued selections (block coords -> chunks)
    if (region.queueManager != null) {
      addQueue(region.queueManager.keptLocations, SelectionHeatmap.SelectionType.QUEUE_L1,
          points, density, minX, minZ, boundW, boundH, kernelRadius, sigma2);
      addQueue(region.queueManager.unkeptLocations, SelectionHeatmap.SelectionType.QUEUE_L2,
          points, density, minX, minZ, boundW, boundH, kernelRadius, sigma2);
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

  private static void addQueue(
      LockFreeLocationBuffer queue, SelectionHeatmap.SelectionType type,
      List<SelectionHeatmap.SelectionPoint> points, double[] density,
      int minX, int minZ, long boundW, long boundH, int kernelRadius, double sigma2
  ) {
    if (queue == null) return;
    int size = queue.size();
    for (int i = 0; i < size; i++) {
      RTPLocation loc = queue.get(i);
      if (loc == null || loc.coords() == null) continue;
      int cx = loc.coords().x() >> 4;
      int cz = loc.coords().z() >> 4;
      if (points.size() < MAX_MARKERS) {
        points.add(new SelectionHeatmap.SelectionPoint(cx, cz, 1, type));
      }
      splatDensity(density, cx, cz, 1.0, minX, minZ, boundW, boundH, kernelRadius, sigma2);
    }
  }

  private static void splatDensity(
      double[] density, int bx, int bz, double weight,
      int minX, int minZ, long boundW, long boundH,
      int kernelRadius, double sigma2
  ) {
    int gx = (int) ((bx - minX) * (GRID_W - 1) / boundW);
    int gz = (int) ((bz - minZ) * (GRID_H - 1) / boundH);
    if (gx < -kernelRadius || gx > GRID_W - 1 + kernelRadius
        || gz < -kernelRadius || gz > GRID_H - 1 + kernelRadius) {
      return;
    }

    int startX = Math.max(0, gx - kernelRadius);
    int endX = Math.min(GRID_W - 1, gx + kernelRadius);
    int startZ = Math.max(0, gz - kernelRadius);
    int endZ = Math.min(GRID_H - 1, gz + kernelRadius);

    for (int z = startZ; z <= endZ; z++) {
      int dz = z - gz;
      int row = z * GRID_W;
      for (int x = startX; x <= endX; x++) {
        int dx = x - gx;
        double dist2 = (double) dx * (double) dx + (double) dz * (double) dz;
        double g = Math.exp(-dist2 / sigma2);
        density[row + x] += weight * g;
      }
    }
  }
}
