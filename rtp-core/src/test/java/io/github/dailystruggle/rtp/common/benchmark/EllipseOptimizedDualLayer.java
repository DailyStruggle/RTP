package io.github.dailystruggle.rtp.common.benchmark;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.parameters.BooleanParameter;
import io.github.dailystruggle.commandsapi.common.parameters.EnumParameter;
import io.github.dailystruggle.commandsapi.common.parameters.FloatParameter;
import io.github.dailystruggle.commandsapi.common.parameters.IntegerParameter;
import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.parameters.DistanceParameter;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.Mode;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.CircleOptimizedDualLayer;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.EllipseMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.table.SegmentedKeyRunTable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * Ellipse memory shape inscribed in {@link CircleOptimizedDualLayer} with segmented
 * secondary tables (ADR-085).
 *
 * <p>Inscribing the ellipse in the circular dual-layer Hilbert space allows smooth radial
 * arc preservation while gaining 2D Hilbert clustering and segmented cache acceleration.
 *
 * <p><b>Test scope only.</b> ADR-080 opt-in tier; excluded from {@code build}. D-005 gate closed.
 */
public class EllipseOptimizedDualLayer extends MemoryShape<EllipseMemoryShapeParams> {

  protected static final EnumMap<EllipseMemoryShapeParams, Object> defaults =
      new EnumMap<>(EllipseMemoryShapeParams.class);
  protected static final Map<String, CommandParameter> subParameters = new ConcurrentHashMap<>();
  protected static final List<String> keys =
      Arrays.stream(EllipseMemoryShapeParams.values()).map(Enum::name).collect(Collectors.toList());

  private final int pointEdgeChunks;
  private final CircleOptimizedDualLayer boundingCircle;

  private volatile SegmentedKeyRunTable segmentedTable;

  static {
    try {
      defaults.put(EllipseMemoryShapeParams.mode, Mode.ACCUMULATE);
      defaults.put(EllipseMemoryShapeParams.radius, 256);
      defaults.put(EllipseMemoryShapeParams.radius2, 256);
      defaults.put(EllipseMemoryShapeParams.centerRadius, 64);
      defaults.put(EllipseMemoryShapeParams.centerRadius2, 64);
      defaults.put(EllipseMemoryShapeParams.rotation, 0);
      defaults.put(EllipseMemoryShapeParams.centerX, 0);
      defaults.put(EllipseMemoryShapeParams.centerZ, 0);
      defaults.put(EllipseMemoryShapeParams.weight, 1.0);
      defaults.put(EllipseMemoryShapeParams.uniquePlacements, 0);
      defaults.put(EllipseMemoryShapeParams.expand, false);

      subParameters.put("mode", new EnumParameter<>(
          "rtp.params", "x-z position adjustment method", (sender, s) -> true, Mode.class));
      subParameters.put("radius", new DistanceParameter(
          "rtp.params", "first axis radius of region", (sender, s) -> true, 64, 128, 256, 512, 1024));
      subParameters.put("radius2", new DistanceParameter(
          "rtp.params", "second axis radius of region", (sender, s) -> true, 64, 128, 256, 512, 1024));
      subParameters.put("centerradius", new DistanceParameter(
          "rtp.params", "inner radius of region", (sender, s) -> true, 16, 32, 64, 128, 256));
      subParameters.put("centerradius2", new DistanceParameter(
          "rtp.params", "second axis of inner exclusion region", (sender, s) -> true, 16, 32, 64, 128, 256));
      subParameters.put("rotation", new IntegerParameter(
          "rtp.params", "rotation in degrees", (sender, s) -> true, 0, 30, 45, 60, 90));
      subParameters.put("centerx", new DistanceParameter(
          "rtp.params", "center point x", (sender, s) -> true, "~", "-~", "0"));
      subParameters.put("centerz", new DistanceParameter(
          "rtp.params", "center point z", (sender, s) -> true, "~", "-~", "0"));
      subParameters.put("weight", new FloatParameter(
          "rtp.params", "weigh towards or away from center", (sender, s) -> true, 0.1, 1.0, 10.0));
      subParameters.put("expand", new BooleanParameter(
          "rtp.params", "expand region to keep a constant amount of usable land", (sender, s) -> true));
      subParameters.put("uniqueplacements", new IntegerParameter(
          "rtp.params", "chunk radius cleared around each selection", (sender, s) -> true, 0, 1, 2, 4, 8));
    } catch (Exception e) {
      RTP.log(Level.WARNING, e.getMessage(), e);
    }
  }

  public EllipseOptimizedDualLayer() {
    this("ELLIPSE_OPTIMIZED_DUAL_LAYER", 32);
  }

  public EllipseOptimizedDualLayer(String newName) {
    this(newName, 32);
  }

  public EllipseOptimizedDualLayer(String newName, int pointEdgeChunks) {
    super(EllipseMemoryShapeParams.class, newName, defaults);
    this.pointEdgeChunks = pointEdgeChunks;
    this.boundingCircle = new CircleOptimizedDualLayer("BOUNDING_" + newName, pointEdgeChunks);
  }

  private long effectiveRadius() {
    long r1 = getNumber(EllipseMemoryShapeParams.radius, 256L).longValue();
    long r2 = getNumber(EllipseMemoryShapeParams.radius2, 256L).longValue();
    return Math.max(r1, r2);
  }

  private long effectiveCenterRadius() {
    long c1 = getNumber(EllipseMemoryShapeParams.centerRadius, 64L).longValue();
    long c2 = getNumber(EllipseMemoryShapeParams.centerRadius2, 64L).longValue();
    return Math.max(c1, c2);
  }

  private void configureBoundingCircle() {
    EnumMap<GenericMemoryShapeParams, Object> map = new EnumMap<>(GenericMemoryShapeParams.class);
    map.put(GenericMemoryShapeParams.radius, (Object) effectiveRadius());
    map.put(GenericMemoryShapeParams.centerRadius, (Object) effectiveCenterRadius());
    map.put(GenericMemoryShapeParams.centerX, (Object) getNumber(EllipseMemoryShapeParams.centerX, 0L).longValue());
    map.put(GenericMemoryShapeParams.centerZ, (Object) getNumber(EllipseMemoryShapeParams.centerZ, 0L).longValue());
    map.put(GenericMemoryShapeParams.expand, (Object) Boolean.FALSE);
    boundingCircle.setData(map);
  }

  @Override
  public long getRange() {
    configureBoundingCircle();
    return boundingCircle.getRange();
  }

  @Override
  public long xzToLocation(long x, long z) {
    configureBoundingCircle();
    return boundingCircle.xzToLocation(x, z);
  }

  @Override
  public long xzToLocation(MutableRTPCoords coords) {
    return xzToLocation(coords.x, coords.z);
  }

  @Override
  public int[] locationToXZ(long location) {
    MutableRTPCoords output = new MutableRTPCoords(0, 0);
    locationToXZ(location, output);
    return new int[] {output.x, output.z};
  }

  @Override
  public void locationToXZ(long location, MutableRTPCoords output) {
    configureBoundingCircle();
    boundingCircle.locationToXZ(location, output);
  }

  @Override
  public boolean contains(int x, int z) {
    long cx = getNumber(EllipseMemoryShapeParams.centerX, 0L).longValue();
    long cz = getNumber(EllipseMemoryShapeParams.centerZ, 0L).longValue();
    long r1 = getNumber(EllipseMemoryShapeParams.radius, 256L).longValue();
    long r2 = getNumber(EllipseMemoryShapeParams.radius2, 256L).longValue();
    long c1 = getNumber(EllipseMemoryShapeParams.centerRadius, 64L).longValue();
    long c2 = getNumber(EllipseMemoryShapeParams.centerRadius2, 64L).longValue();
    long degrees = getNumber(EllipseMemoryShapeParams.rotation, 0L).longValue();

    double dx = (double) x - cx;
    double dz = (double) z - cz;

    if (degrees != 0L) {
      double rad = Math.toRadians(-degrees);
      double cos = Math.cos(rad);
      double sin = Math.sin(rad);
      double rx = dx * cos - dz * sin;
      double rz = dx * sin + dz * cos;
      dx = rx;
      dz = rz;
    }

    if (r1 <= 0L || r2 <= 0L) return false;
    double outerDist = (dx * dx) / (double) (r1 * r1) + (dz * dz) / (double) (r2 * r2);
    if (outerDist >= 1.0) return false;

    if (c1 > 0L && c2 > 0L) {
      double innerDist = (dx * dx) / (double) (c1 * c1) + (dz * dz) / (double) (c2 * c2);
      if (innerDist < 1.0) return false;
    }
    return true;
  }

  @Override
  protected boolean supportsExpand() {
    return false;
  }

  @Override
  protected long postProcess(long location) {
    if (location < 0) return location;
    MutableRTPCoords coords = new MutableRTPCoords(0, 0);
    try {
      locationToXZ(location, coords);
    } catch (Throwable t) {
      return location;
    }
    if (!contains(coords.x, coords.z)) {
      addBadLocation(location);
      return -1L;
    }
    return location;
  }

  @Override
  public long rand() {
    Mode mode = (Mode) data.getOrDefault(EllipseMemoryShapeParams.mode, Mode.ACCUMULATE);
    long range = getRange();

    if (mode == Mode.ACCUMULATE) {
      SegmentedKeyRunTable table = getOrBuildSegmentedTable(range);
      long totalGood = range - table.totalCovered();
      if (totalGood <= 0) return -1L;

      long target = ThreadLocalRandom.current().nextLong(totalGood);
      long loc = table.resolveAccumulate(target);
      return (loc >= 0 && loc < range) ? loc : -1L;
    }

    return super.rand();
  }

  public int getBadRunsCount() {
    return badKeysCache.length;
  }

  public void forceFlushAndRebuild() {
    flushAndRebuild(1L);
  }

  public void forceFlushAndRebuild(long res) {
    flushAndRebuild(res);
  }

  private synchronized SegmentedKeyRunTable getOrBuildSegmentedTable(long range) {
    long[] keys = badKeysCache;
    long[] sums = badPrefixSumsCache;
    int count = Math.min(keys.length, sums.length);

    if (segmentedTable != null && segmentedTable.totalRange() == range) {
      return segmentedTable;
    }

    long[] widths = new long[count];
    long prev = 0L;
    for (int i = 0; i < count; i++) {
      widths[i] = sums[i] - prev;
      prev = sums[i];
    }

    long binSize = SegmentedKeyRunTable.deriveOptimalBinSize(range);
    segmentedTable = SegmentedKeyRunTable.fromRuns(keys, widths, count, range, binSize, 3L);
    return segmentedTable;
  }

  @Override
  public Map<String, CommandParameter> getParameters() {
    return subParameters;
  }

  @Override
  public Collection<String> keys() {
    return keys;
  }
}
