package io.github.dailystruggle.rtp.common.visualization;

import io.github.dailystruggle.mapsapi.BiomeColorSource;
import io.github.dailystruggle.mapsapi.model.CompositeRegionModel;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import java.util.List;

/**
 * Builds a {@link CompositeRegionModel} from a region's in-memory {@link MemoryShape}
 * and optional queue telemetry (ADR-089).
 *
 * <p>Operates 100% off-tick and performs zero chunk I/O (S-005).</p>
 */
public final class CompositeRegionModelBuilder {

  private CompositeRegionModelBuilder() {
    throw new AssertionError("Non-instantiable utility class.");
  }

  /**
   * Builds a {@link CompositeRegionModel} for the specified region and resolution.
   *
   * @param region          target RTP region
   * @param bufferWidth     canvas pixel width (e.g. 128 for map items, 512+ for export)
   * @param bufferHeight    canvas pixel height
   * @param markers         point markers to overlay (candidates, recent landings)
   * @param trajectoryLines lines connecting consecutive arrivals
   * @param l1Gauge         L1 hot queue capacity gauge
   * @param l2Gauge         L2 cold queue capacity gauge
   * @param l3Gauge         L3 backlog queue capacity gauge
   * @return populated composite model
   */
  public static CompositeRegionModel build(Region region,
                                           int bufferWidth,
                                           int bufferHeight,
                                           List<CompositeRegionModel.Marker> markers,
                                           List<CompositeRegionModel.VectorLine> trajectoryLines,
                                           CompositeRegionModel.QueueGauge l1Gauge,
                                           CompositeRegionModel.QueueGauge l2Gauge,
                                           CompositeRegionModel.QueueGauge l3Gauge) {
    if (region == null || !(region.shape instanceof MemoryShape<?> memoryShape)) {
      throw new IllegalArgumentException("Region must have a valid MemoryShape");
    }

    long range = memoryShape.getRange();
    if (range <= 0) {
      throw new IllegalArgumentException("Region range must be positive");
    }

    // Pass 1: leap-sample to discover bounding box of the shape in block coordinates
    long totalPixels = (long) bufferWidth * bufferHeight;
    long step = Math.max(1L, range / totalPixels);
    int minX = Integer.MAX_VALUE;
    int maxX = Integer.MIN_VALUE;
    int minZ = Integer.MAX_VALUE;
    int maxZ = Integer.MIN_VALUE;
    int samples = 0;

    for (long i = 0L; i < range; i += step) {
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
    int pad = Math.max(1, Math.max(extentX, extentZ) / 10);
    minX -= pad; maxX += pad;
    minZ -= pad; maxZ += pad;
    long boundW = Math.max(1L, (long) maxX - minX);
    long boundH = Math.max(1L, (long) maxZ - minZ);

    // Pass 2: Per-pixel sampling against in-memory biome and bad location memory
    int totalElements = bufferWidth * bufferHeight;
    int[] biomeRgb = new int[totalElements];
    boolean[] hazardMask = new boolean[totalElements];
    boolean[] insideDomain = new boolean[totalElements];

    for (int py = 0; py < bufferHeight; py++) {
      int bz = (int) (minZ + py * boundH / (bufferHeight - 1));
      int row = py * bufferWidth;
      for (int px = 0; px < bufferWidth; px++) {
        int bx = (int) (minX + px * boundW / (bufferWidth - 1));
        int idx = row + px;

        if (memoryShape.contains(bx, bz)) {
          insideDomain[idx] = true;

          // Check biome
          String biomeName = memoryShape.biomeAt(bx, bz);
          if (biomeName != null) {
            biomeRgb[idx] = BiomeColorSource.resolve(biomeName) & 0xFFFFFF;
          } else {
            biomeRgb[idx] = 0x2ECC71; // Default soft green if unsampled
          }

          // Check hazard
          int cause = memoryShape.causeAt(bx, bz);
          if (cause >= 0) {
            hazardMask[idx] = true;
          }
        } else {
          insideDomain[idx] = false;
        }
      }
    }

    return new CompositeRegionModel(
        region.name,
        bufferWidth,
        bufferHeight,
        biomeRgb,
        hazardMask,
        insideDomain,
        markers,
        trajectoryLines,
        l1Gauge,
        l2Gauge,
        l3Gauge
    );
  }
}
