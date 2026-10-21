package io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors;

import io.github.dailystruggle.rtp.api.world.ChunkColumnProbe;
import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Common base for vertical adjustors performing 3D block search, safety probing, and sky-light gating.
 */
public abstract class AbstractVerticalAdjustor<T extends Enum<T>> extends VerticalAdjustor<T> {

  public static final List<List<Integer>> TEST_COORDS =
      Collections.unmodifiableList(
          Arrays.asList(
              Collections.unmodifiableList(Arrays.asList(7, 7)),
              Collections.unmodifiableList(Arrays.asList(2, 2)),
              Collections.unmodifiableList(Arrays.asList(12, 12)),
              Collections.unmodifiableList(Arrays.asList(2, 12)),
              Collections.unmodifiableList(Arrays.asList(12, 2))));

  /** Default and bounds of {@code slopeRadius} (ADR-119). */
  public static final int DEFAULT_SLOPE_RADIUS = 2;
  public static final int MIN_SLOPE_RADIUS = 1;
  public static final int MAX_SLOPE_RADIUS = 8;

  /** Enum constants named {@code maxSlope} / {@code slopeRadius}, or null if the key enum lacks them. */
  private final @Nullable T maxSlopeKey;
  private final @Nullable T slopeRadiusKey;

  protected AbstractVerticalAdjustor(
      Class<T> clazz, String name, List<Predicate<RTPCoords>> verifiers, EnumMap<T, Object> data) {
    super(clazz, name, verifiers, data);
    this.maxSlopeKey = constantNamed(clazz, "maxSlope");
    this.slopeRadiusKey = constantNamed(clazz, "slopeRadius");
  }

  private static <T extends Enum<T>> @Nullable T constantNamed(Class<T> clazz, String name) {
    for (T c : clazz.getEnumConstants()) {
      if (c.name().equals(name)) return c;
    }
    return null;
  }

  /**
   * Configured {@code maxSlope}, or -1 (check disabled) when absent or negative. The key has
   * no defaults entry, so an unconfigured vert keeps its data and cache key (ADR-119).
   */
  public int maxSlope() {
    if (maxSlopeKey == null) return -1;
    int v = getNumber(maxSlopeKey, -1).intValue();
    return v < 0 ? -1 : v;
  }

  /** Configured {@code slopeRadius} clamped to [1, 8]; {@value #DEFAULT_SLOPE_RADIUS} when absent. */
  public int slopeRadius() {
    if (slopeRadiusKey == null) return DEFAULT_SLOPE_RADIUS;
    int v = getNumber(slopeRadiusKey, DEFAULT_SLOPE_RADIUS).intValue();
    return Math.max(MIN_SLOPE_RADIUS, Math.min(MAX_SLOPE_RADIUS, v));
  }

  /** Floor test for {@link #slopeOk}: true when feet-Y {@code y} stands on a floor in column (lx, lz). */
  @FunctionalInterface
  public interface FloorReader {
    boolean isFloor(int lx, int y, int lz);
  }

  /** Live adapter: non-air at y-1, air at y. Same reads as the probe adapter (ADR-016 parity). */
  @SuppressWarnings("unchecked")
  public static FloorReader floorReader(RTPChunk chunk) {
    return (lx, y, lz) -> !chunk.isAir(lx, y - 1, lz) && chunk.isAir(lx, y, lz);
  }

  /** Probe adapter: non-air at y-1, air at y. */
  public static FloorReader floorReader(ChunkColumnProbe probe) {
    return (lx, y, lz) -> !probe.isAirAt(lx, lz, y - 1) && probe.isAirAt(lx, lz, y);
  }

  /**
   * Landing-spot slope check (ADR-119, REQ-RTP-F-015). Samples the 4 cardinal columns at
   * {@code radius}, clamped to chunk-local [0, 15]; a side clamped to distance 0 is skipped.
   * Each sample needs a floor within {@code y +- maxSlope}, searched nearest-first from
   * {@code y}, so it follows the candidate's layer and never reads from the sky.
   *
   * <p>Pre: {@code 0 <= lx, lz <= 15}; {@code maxSlope >= 0}; minY..maxY bound feet-Y with y-1
   * readable.
   * Pure; reads only through {@code reader}; at most 4 * (2 * maxSlope + 1) floor tests.
   */
  public static boolean slopeOk(
      FloorReader reader, int lx, int y, int lz, int radius, int maxSlope, int minY, int maxY) {
    int r = Math.max(MIN_SLOPE_RADIUS, radius);
    int east = Math.min(15, lx + r);
    int west = Math.max(0, lx - r);
    int south = Math.min(15, lz + r);
    int north = Math.max(0, lz - r);
    if (east != lx && !hasFloorNear(reader, east, y, lz, maxSlope, minY, maxY)) return false;
    if (west != lx && !hasFloorNear(reader, west, y, lz, maxSlope, minY, maxY)) return false;
    if (south != lz && !hasFloorNear(reader, lx, y, south, maxSlope, minY, maxY)) return false;
    return north == lz || hasFloorNear(reader, lx, y, north, maxSlope, minY, maxY);
  }

  /**
   * Bound slope settings for one adjust call. Null from the factories means the check is off,
   * so callers pay one null compare per candidate when unconfigured.
   */
  public record SlopeCheck(FloorReader reader, int maxSlope, int radius, int minY, int maxY) {
    public boolean test(int lx, int y, int lz) {
      return slopeOk(reader, lx, y, lz, radius, maxSlope, minY, maxY);
    }

    /**
     * Live-chunk check; feet-Y bounded to [worldMin + 1, worldMax - 1]. Null when
     * {@code maxSlope < 0}.
     */
    public static @Nullable SlopeCheck live(RTPChunk chunk, int maxSlope, int radius) {
      if (maxSlope < 0 || chunk == null || chunk.getWorld() == null) return null;
      return new SlopeCheck(floorReader(chunk), maxSlope, radius,
          chunk.getWorld().getMinHeight() + 1, chunk.getWorld().getMaxHeight() - 1);
    }

    /** Probe check; feet-Y bounded to the probe window. Null when {@code maxSlope < 0}. */
    public static @Nullable SlopeCheck probe(ChunkColumnProbe probe, int maxSlope, int radius) {
      if (maxSlope < 0) return null;
      return new SlopeCheck(floorReader(probe), maxSlope, radius, probe.minY() + 1, probe.maxY());
    }
  }

  /** {@link SlopeCheck#live} with this vert's settings. */
  protected @Nullable SlopeCheck slopeCheck(RTPChunk chunk) {
    int ms = maxSlope();
    return ms < 0 ? null : SlopeCheck.live(chunk, ms, slopeRadius());
  }

  /** {@link SlopeCheck#probe} with this vert's settings. */
  protected @Nullable SlopeCheck slopeCheck(ChunkColumnProbe probe) {
    int ms = maxSlope();
    return ms < 0 ? null : SlopeCheck.probe(probe, ms, slopeRadius());
  }

  private static boolean hasFloorNear(
      FloorReader reader, int lx, int y, int lz, int maxSlope, int minY, int maxY) {
    for (int d = 0; d <= maxSlope; d++) {
      int up = y + d;
      if (up >= minY && up <= maxY && reader.isFloor(lx, up, lz)) return true;
      int down = y - d;
      if (d > 0 && down >= minY && down <= maxY && reader.isFloor(lx, down, lz)) return true;
    }
    return false;
  }

  @Override
  public @Nullable RTPCoords adjust(@NotNull RTPChunk chunk) {
    MutableRTPCoords output = new MutableRTPCoords(chunk.getWorld().name(), 0, 0, 0);
    if (adjust(chunk, output)) return output.toImmutable();
    return null;
  }

  @Override
  public boolean testPlacement(@NotNull RTPCoords coords) {
    for (int i = 0; i < verifiers.size(); i++) {
      Predicate<RTPCoords> rtpLocationPredicate = verifiers.get(i);
      if (!rtpLocationPredicate.test(coords)) return false;
    }
    return true;
  }

  /**
   * Sweeps {@code chunk.isSafe} across {@code [1..platformDepth]} cells below the
   * candidate feet-Y.
   */
  @SuppressWarnings("unchecked")
  protected static boolean isGroundSafe(
      RTPChunk chunk, int x, int y, int z, Set<String> unsafeBlocks, int platformDepth) {
    if (chunk == null) return false;
    io.github.dailystruggle.rtp.api.world.RTPWorld<?> world = chunk.getWorld();
    if (world == null) return false;
    int worldMin = world.getMinHeight();
    int depth = Math.max(1, platformDepth);
    for (int d = 1; d <= depth; d++) {
      int checkY = y - d;
      if (checkY < worldMin) return false;
      if (!chunk.isSafe(x, checkY, z, unsafeBlocks)) return false;
    }
    return true;
  }

  /**
   * Returns highest non-air Y on column {@code (x, z)} derived from block data.
   * Any {@code y+1 > floor} has unobstructed sky access, avoiding stale light nibbles.
   * Returns {@link Integer#MIN_VALUE} if column is entirely air.
   */
  protected static int computeColumnSkyFloor(RTPChunk chunk, int x, int z) {
    if (chunk == null) return Integer.MIN_VALUE;
    io.github.dailystruggle.rtp.api.world.RTPWorld<?> world = chunk.getWorld();
    if (world == null) return Integer.MIN_VALUE;
    int top = world.getMaxHeight() - 1;
    int bottom = world.getMinHeight();
    for (int y = top; y >= bottom; y--) {
      if (!chunk.isAir(x, y, z)) return y;
    }
    return Integer.MIN_VALUE;
  }

  /**
   * Returns highest non-air Y on column {@code (lx, lz)} within probe window.
   * Any {@code y+1 > floor} has unobstructed sky access, avoiding stale light nibbles.
   * Returns {@link Integer#MIN_VALUE} if column is entirely air.
   */
  protected static int computeColumnSkyFloor(ChunkColumnProbe probe, int lx, int lz) {
    int top = probe.maxY();
    int bottom = probe.minY();
    for (int y = top; y >= bottom; y--) {
      if (!probe.isAirAt(lx, lz, y)) return y;
    }
    return Integer.MIN_VALUE;
  }

  /**
   * Canonicalise an identifier or material token into uppercase, namespace-stripped form.
   */
  public static String canon(String id) {
    if (id == null) return null;
    String s = id.trim();
    if (s.isEmpty()) return null;
    if (s.charAt(0) == '#') return s.toUpperCase(java.util.Locale.ROOT);
    int colon = s.indexOf(':');
    String local = (colon >= 0) ? s.substring(colon + 1) : s;
    int bracket = local.indexOf('[');
    if (bracket >= 0) local = local.substring(0, bracket);
    return local.toUpperCase(java.util.Locale.ROOT);
  }
}
