package io.github.dailystruggle.rtp.common.commands.maps;

import io.github.dailystruggle.mapsapi.BiomeColorSource;
import io.github.dailystruggle.mapsapi.model.RegionWalkPath;
import io.github.dailystruggle.mapsapi.render.RegionWalkPathRenderer;
import io.github.dailystruggle.rtp.api.maps.ChartSpec;
import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolver for {@link ChartSpec.Kind#REGION_WALK_PATH} (ADR-089).
 * Builds a {@link RegionWalkPath} capturing the selection path across the region domain,
 * classified by validity (valid vs hazard), bounds (inside vs outside), and progression.
 *
 * <p>Operates 100% off-tick with zero main-thread chunk I/O (S-005).</p>
 */
public final class RegionWalkPathResolver implements ChartSpecResolver {

  private static final int BACKDROP_WIDTH = 512;
  private static final int BACKDROP_HEIGHT = 512;

  @Override
  public Resolution resolve(ChartSpec spec) throws UnresolvableChartSpecException {
    if (spec == null) {
      throw new UnresolvableChartSpecException("spec shall not be null");
    }
    if (spec.kind() != ChartSpec.Kind.REGION_WALK_PATH) {
      throw new UnresolvableChartSpecException(
          "RegionWalkPathResolver only handles REGION_WALK_PATH, got " + spec.kind());
    }

    Region region;
    try {
      region = RTP.selectionAPI.getRegionOrDefault(spec.regionName());
    } catch (RuntimeException e) {
      throw new UnresolvableChartSpecException(
          "no region resolved for '" + spec.regionName() + "'", e);
    }
    if (!(region.shape instanceof MemoryShape<?> memoryShape)) {
      throw new UnresolvableChartSpecException(
          "region '" + region.name + "' shape is not a MemoryShape");
    }

    long range = memoryShape.getRange();
    if (range <= 0) {
      throw new UnresolvableChartSpecException("region range must be positive");
    }

    // 1. Discover bounding box matching ComprehensiveRegionImageExporter
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
    int bufferW = BACKDROP_WIDTH;
    int bufferH = BACKDROP_HEIGHT;
    int totalElements = bufferW * bufferH;
    boolean[] insideDomain = new boolean[totalElements];
    int[] biomeRgb = new int[totalElements];
    boolean[] hazardMask = new boolean[totalElements];

    for (int py = 0; py < bufferH; py++) {
      int bz = (int) (minZ + (long) py * boundH / (bufferH - 1));
      int row = py * bufferW;
      for (int px = 0; px < bufferW; px++) {
        int bx = (int) (minX + (long) px * boundW / (bufferW - 1));
        int idx = row + px;
        if (memoryShape.contains(bx, bz)) {
          insideDomain[idx] = true;
          String biomeName = memoryShape.biomeAt(bx, bz);
          biomeRgb[idx] = (biomeName != null) ? (BiomeColorSource.resolve(biomeName) & 0xFFFFFF) : 0x2ECC71;
          if (memoryShape.causeAt(bx, bz) >= 0) {
            hazardMask[idx] = true;
          }
        } else {
          insideDomain[idx] = false;
        }
      }
    }

    // 3. Trace walk path steps - capture every point on the path
    List<RegionWalkPath.WalkStep> steps = new ArrayList<>();
    MutableRTPCoords coords = new MutableRTPCoords(0, 0);

    for (long idx = 0L; idx < range; idx++) {
      memoryShape.locationToXZ(idx, coords);
      int bx = coords.x;
      int bz = coords.z;

      RegionWalkPath.StepStatus status;
      if (!memoryShape.contains(bx, bz)) {
        status = RegionWalkPath.StepStatus.OUT_OF_BOUNDS;
      } else if (memoryShape.causeAt(bx, bz) >= 0) {
        status = RegionWalkPath.StepStatus.HAZARD;
      } else {
        status = RegionWalkPath.StepStatus.VALID;
      }

      float progress = (float) ((double) idx / range);
      steps.add(new RegionWalkPath.WalkStep(bx, bz, status, progress));
    }

    RegionWalkPath model = new RegionWalkPath(
        region.name,
        bufferW,
        bufferH,
        minX,
        minZ,
        maxX,
        maxZ,
        insideDomain,
        biomeRgb,
        hazardMask,
        steps
    );

    return Resolution.of(RegionWalkPathRenderer.INSTANCE, model);
  }
}
