package io.github.dailystruggle.rtp.common.commands.maps;

import io.github.dailystruggle.mapsapi.model.RegionWalkPath;
import io.github.dailystruggle.mapsapi.render.RegionWalkPathRenderer;
import io.github.dailystruggle.rtp.api.maps.ChartSpec;
import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Resolver for {@link ChartSpec.Kind#REGION_WALK_PATH} (ADR-089).
 * Builds a {@link RegionWalkPath} capturing the selection path across the region domain,
 * classified by validity (valid vs hazard), bounds (inside vs outside), and progression.
 *
 * <p>Operates 100% off-tick with zero main-thread chunk I/O (S-005).</p>
 */
public final class RegionWalkPathResolver extends AbstractRegionGridResolver {

  private static final int BACKDROP_WIDTH = 512;
  private static final int BACKDROP_HEIGHT = 512;
  private static final Set<ChartSpec.Kind> SUPPORTED_KINDS = Set.of(ChartSpec.Kind.REGION_WALK_PATH);

  @Override
  public Resolution resolve(ChartSpec spec) throws UnresolvableChartSpecException {
    validateSpec(spec, SUPPORTED_KINDS);
    Region region = resolveRegion(spec);
    MemoryShape<?> memoryShape = resolveMemoryShape(region);
    long range = memoryShape.getRange();

    // 1. Discover bounding box matching ComprehensiveRegionImageExporter
    RegionBounds bounds = discoverBounds(memoryShape, 4096, 16, 20);
    int minX = bounds.minX();
    int maxX = bounds.maxX();
    int minZ = bounds.minZ();
    int maxZ = bounds.maxZ();

    // 2. Compute domain containment and backdrop terrain grid
    int bufferW = BACKDROP_WIDTH;
    int bufferH = BACKDROP_HEIGHT;
    GridDomain domain = sampleDomainGrid(memoryShape, bounds, bufferW, bufferH, true);
    boolean[] insideDomain = domain.insideDomain();
    int[] biomeRgb = domain.biomeRgb();
    boolean[] hazardMask = domain.hazardMask();

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
