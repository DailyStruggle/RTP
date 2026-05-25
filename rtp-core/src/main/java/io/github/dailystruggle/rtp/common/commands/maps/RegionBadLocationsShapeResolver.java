package io.github.dailystruggle.rtp.common.commands.maps;

import io.github.dailystruggle.mapsapi.model.RegionBadLocations;
import io.github.dailystruggle.mapsapi.render.RegionBadLocationsRenderer;
import io.github.dailystruggle.rtp.api.maps.ChartSpec;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;

/**
 * Resolver for {@link ChartSpec.Kind#REGION_BAD_LOCATIONS_SHAPE}.
 * Composes a {@link RegionBadLocations} snapshot from the region's
 * {@link MemoryShape#badKeysSnapshot()} for the admin
 * "Visualizations -> Region shape" entry; the resulting model is painted
 * by {@link RegionBadLocationsRenderer} as red (bad), green (inside the
 * region disk), and black (outside the disk).
 *
 * <p>Center / radius derivation: regions do not expose a single "centre
 * block coordinate + radius in blocks" surface, and the data-driven path
 * already established by {@code BadPointsHeatmapResolver} (decode every
 * bad key, take the axis-aligned bbox) is shape-agnostic and avoids
 * coupling to per-shape range encodings (e.g. {@code Square.getRange()}
 * is a 1D index span rather than a block radius). We re-use the same
 * derivation here: centre = bbox midpoint, radius = half the longer bbox
 * extent (so the inscribed-disk painter encloses every observed bad key).
 *
 * <p>Failure path: throws {@link ChartSpecResolver.UnresolvableChartSpecException}
 * when the named region is unknown, when its shape is not a
 * {@link MemoryShape} (no badKeys cache to read), or when the snapshot is
 * empty (no admin signal to draw - falling back to the configurable
 * {@code mapUnavailable} message via {@link MapDispatch}). Empty-snapshot
 * is treated as "nothing to show" rather than "draw a blank green disk"
 * because the latter is indistinguishable from "the region has been
 * fully scanned and every position is safe", which we cannot truthfully
 * claim.
 *
 * <p>REQ-RTP-MAP-006 / REQ-RTP-S-005 / REQ-RTP-F-008 (no chunk I/O; the
 * snapshot is a copy of a volatile long[] populated off the main thread).
 */
public final class RegionBadLocationsShapeResolver implements ChartSpecResolver {

  @Override
  public Resolution resolve(ChartSpec spec) throws UnresolvableChartSpecException {
    if (spec == null) {
      throw new UnresolvableChartSpecException("spec shall not be null");
    }
    if (spec.kind() != ChartSpec.Kind.REGION_BAD_LOCATIONS_SHAPE) {
      throw new UnresolvableChartSpecException(
          "RegionBadLocationsShapeResolver only handles REGION_BAD_LOCATIONS_SHAPE, got "
              + spec.kind());
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
          "region '" + region.name
              + "' shape is not a MemoryShape; no badKeys snapshot available");
    }

    long[] keys = memoryShape.badKeysSnapshot();
    if (keys.length == 0) {
      throw new UnresolvableChartSpecException(
          "region '" + region.name + "' has no bad-location data yet");
    }

    // Decode keys + compute bbox. Re-use the same packed-long encoding as
    // MemoryShape (blockX in the high 32 bits, blockZ in the low 32 bits)
    // via the shape's own decoder so any future encoding change stays
    // localised to MemoryShape.locationToXZ.
    int minX = Integer.MAX_VALUE;
    int maxX = Integer.MIN_VALUE;
    int minZ = Integer.MAX_VALUE;
    int maxZ = Integer.MIN_VALUE;
    long[] decoded = new long[keys.length];
    int n = 0;
    for (long key : keys) {
      int[] xz = memoryShape.locationToXZ(key);
      if (xz == null || xz.length < 2) continue;
      int bx = xz[0];
      int bz = xz[1];
      decoded[n++] = ((long) bx << 32) | (bz & 0xFFFFFFFFL);
      if (bx < minX) minX = bx;
      if (bx > maxX) maxX = bx;
      if (bz < minZ) minZ = bz;
      if (bz > maxZ) maxZ = bz;
    }
    if (n == 0) {
      throw new UnresolvableChartSpecException(
          "region '" + region.name
              + "' has bad-location keys but none decoded to valid XZ pairs");
    }

    int centerX = (minX + maxX) / 2;
    int centerZ = (minZ + maxZ) / 2;
    int extentX = maxX - minX;
    int extentZ = maxZ - minZ;
    int radius = Math.max(1, Math.max(extentX, extentZ) / 2 + 1);

    long[] keysTrimmed = new long[n];
    System.arraycopy(decoded, 0, keysTrimmed, 0, n);
    RegionBadLocations model =
        new RegionBadLocations(region.name, centerX, centerZ, radius, keysTrimmed);
    return Resolution.of(RegionBadLocationsRenderer.INSTANCE, model);
  }
}
