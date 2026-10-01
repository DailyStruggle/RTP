package io.github.dailystruggle.rtp.common.commands.maps;

import io.github.dailystruggle.mapsapi.BiomeColorSource;
import io.github.dailystruggle.rtp.api.maps.ChartSpec;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;

import java.util.Objects;
import java.util.Set;

/**
 * Base abstract resolver encapsulating common region discovery, bounding box leap-sampling,
 * and grid domain resolution routines across maps components (ADR-089, REQ-RTP-MAP-006).
 */
public abstract class AbstractRegionGridResolver implements ChartSpecResolver {

  /**
   * Encapsulates 2D bounding extents discovered from a {@link MemoryShape}.
   */
  public record RegionBounds(int minX, int maxX, int minZ, int maxZ, long boundW, long boundH) {}

  /**
   * Container for domain containment, biome colors, and optional hazard masks across a resolution grid.
   */
  public record GridDomain(int width, int height, boolean[] insideDomain, int[] biomeRgb, boolean[] hazardMask) {}

  /**
   * Validates common specification invariants.
   *
   * @param spec           input spec
   * @param supportedKinds supported chart spec kinds
   * @throws UnresolvableChartSpecException if invalid or unsupported
   */
  protected void validateSpec(ChartSpec spec, Set<ChartSpec.Kind> supportedKinds) throws UnresolvableChartSpecException {
    if (spec == null) {
      throw new UnresolvableChartSpecException("spec shall not be null");
    }
    if (!supportedKinds.contains(spec.kind())) {
      throw new UnresolvableChartSpecException(
          getClass().getSimpleName() + " handles " + supportedKinds + ", got " + spec.kind());
    }
  }

  /**
   * Resolves the {@link Region} from the specification.
   */
  protected Region resolveRegion(ChartSpec spec) throws UnresolvableChartSpecException {
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
    return region;
  }

  /**
   * Resolves and validates the region's {@link MemoryShape} and range.
   */
  protected MemoryShape<?> resolveMemoryShape(Region region) throws UnresolvableChartSpecException {
    Objects.requireNonNull(region, "region shall not be null");
    if (!(region.shape instanceof MemoryShape<?> memoryShape)) {
      throw new UnresolvableChartSpecException(
          "region '" + region.name + "' shape is not a MemoryShape");
    }
    long range = memoryShape.getRange();
    if (range <= 0) {
      throw new UnresolvableChartSpecException("region range must be positive");
    }
    return memoryShape;
  }

  /**
   * Discovers the 2D bounding box of the given shape by leap-sampling positions.
   * Matches the standard 4096-sample algorithm with padding.
   */
  protected RegionBounds discoverBounds(MemoryShape<?> memoryShape, int sampleCount, int minPadding, int paddingDivisor) {
    long range = memoryShape.getRange();
    long step = Math.max(1L, range / sampleCount);
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
      minX = -100;
      maxX = 100;
      minZ = -100;
      maxZ = 100;
    }

    int extentX = maxX - minX;
    int extentZ = maxZ - minZ;
    int pad = Math.max(minPadding, Math.max(extentX, extentZ) / paddingDivisor);
    minX -= pad;
    maxX += pad;
    minZ -= pad;
    maxZ += pad;
    long boundW = Math.max(1L, (long) maxX - minX);
    long boundH = Math.max(1L, (long) maxZ - minZ);

    return new RegionBounds(minX, maxX, minZ, maxZ, boundW, boundH);
  }

  /**
   * Resolves the domain containment grid, biome colors, and optional hazard mask for a given grid dimension.
   */
  protected GridDomain sampleDomainGrid(
      MemoryShape<?> memoryShape,
      RegionBounds bounds,
      int gridW,
      int gridH,
      boolean sampleHazards
  ) {
    int totalElements = gridW * gridH;
    boolean[] insideDomain = new boolean[totalElements];
    int[] biomeRgb = new int[totalElements];
    boolean[] hazardMask = sampleHazards ? new boolean[totalElements] : null;

    int minX = bounds.minX();
    int minZ = bounds.minZ();
    long boundW = bounds.boundW();
    long boundH = bounds.boundH();

    for (int py = 0; py < gridH; py++) {
      int bz = (int) (minZ + (long) py * boundH / (gridH - 1));
      int row = py * gridW;
      for (int px = 0; px < gridW; px++) {
        int bx = (int) (minX + (long) px * boundW / (gridW - 1));
        int idx = row + px;
        if (memoryShape.contains(bx, bz)) {
          insideDomain[idx] = true;
          String biomeName = memoryShape.biomeAt(bx, bz);
          biomeRgb[idx] = (biomeName != null) ? (BiomeColorSource.resolve(biomeName) & 0xFFFFFF) : 0x2ECC71;
          if (sampleHazards && memoryShape.causeAt(bx, bz) >= 0) {
            hazardMask[idx] = true;
          }
        } else {
          insideDomain[idx] = false;
        }
      }
    }

    return new GridDomain(gridW, gridH, insideDomain, biomeRgb, hazardMask);
  }
}
