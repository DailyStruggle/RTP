package io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.rtp.api.world.ChunkColumnProbe;
import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.GenericVerticalAdjustorKeys;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class LinearAdjustor extends VerticalAdjustor<GenericVerticalAdjustorKeys> {
  protected static final Map<String, CommandParameter> subParameters = new ConcurrentHashMap<>();

  /** RNG used for the shuffled (state 4) scan order. Replaceable for deterministic testing. */
  private Random rng = new Random();

  /** Inject a seeded {@link Random} to make the shuffled scan order reproducible in tests. */
  public void setRng(Random rng) {
    this.rng = rng;
  }
  protected static final List<String> keys =
      Arrays.stream(GenericMemoryShapeParams.values()).map(Enum::name).collect(Collectors.toList());
  private static final EnumMap<GenericVerticalAdjustorKeys, Object> defaults =
      new EnumMap<>(GenericVerticalAdjustorKeys.class);
  private static final Set<String> unsafeBlocks = new ConcurrentSkipListSet<>();
  private static final AtomicLong lastUpdate = new AtomicLong();

  private static final List<List<Integer>> testCoords =
      Arrays.asList(
          Arrays.asList(7, 7),
          Arrays.asList(2, 2),
          Arrays.asList(12, 12),
          Arrays.asList(2, 12),
          Arrays.asList(12, 2));

  static {
    defaults.put(GenericVerticalAdjustorKeys.maxY, 127);
    defaults.put(GenericVerticalAdjustorKeys.minY, 32);
    defaults.put(GenericVerticalAdjustorKeys.direction, 0);
    defaults.put(GenericVerticalAdjustorKeys.requireSkyLight, false);

    // subParameter removed
    // subParameter removed
    // subParameter removed
    // subParameter removed
  }

  public LinearAdjustor(List<Predicate<RTPCoords>> verifiers) {
    super(GenericVerticalAdjustorKeys.class, "linear", verifiers, defaults);
  }

  @Override
  public List<String> keys() {
    return Arrays.stream(GenericVerticalAdjustorKeys.values())
        .map(Enum::name)
        .collect(Collectors.toList());
  }

  @Override
  public boolean requiresSkyLight() {
    Object o = getData().getOrDefault(GenericVerticalAdjustorKeys.requireSkyLight, false);
    if (o instanceof Boolean b) return b;
    return Boolean.parseBoolean(o.toString());
  }

  @Override
  public @Nullable RTPCoords adjust(@NotNull RTPChunk chunk) {
    MutableRTPCoords output = new MutableRTPCoords(chunk.getWorld().name(), 0, 0, 0);
    if (adjust(chunk, output)) return output.toImmutable();
    return null;
  }

  @Override
  public boolean adjust(@NotNull RTPChunk chunk, @NotNull MutableRTPCoords output) {
    if (chunk == null) return false;

    int maxY = getNumber(GenericVerticalAdjustorKeys.maxY, 320L).intValue();
    int minY = getNumber(GenericVerticalAdjustorKeys.minY, 0L).intValue();
    int dir = getNumber(GenericVerticalAdjustorKeys.direction, 0).intValue();

    maxY = Math.min(maxY, chunk.getWorld().getMaxHeight());

    boolean requireSkyLight;
    Object o = getData().getOrDefault(GenericVerticalAdjustorKeys.requireSkyLight, false);
    if (o instanceof Boolean) {
      requireSkyLight = (Boolean) o;
    } else requireSkyLight = Boolean.parseBoolean(o.toString());

    long t = System.currentTimeMillis();
    long dt = t - lastUpdate.get();
    if (dt > 5000 || dt < 0) {
      ConfigParser<SafetyKeys> safety =
          (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
      Object value = safety.getConfigValue(SafetyKeys.unsafeBlocks, new ArrayList<>());
      unsafeBlocks.clear();
      if (value instanceof Collection) {
        unsafeBlocks.addAll(
            ((Collection<?>) value)
                .stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .collect(Collectors.toSet()));
      }
      lastUpdate.set(t);
    }

    for (int j = 0; j < testCoords.size(); j++) {
      List<Integer> xz = testCoords.get(j);
      int x = xz.get(0);
      int z = xz.get(1);
      int globalX = (chunk.x() << 4) + x;
      int globalZ = (chunk.z() << 4) + z;
      switch (dir) {
        case 0:
          { // bottom up
            for (int i = minY; i < maxY; i++) {
              int skylight = 15;
              if (requireSkyLight) skylight = chunk.getSkyLight(x, i + 1, z);
              if (!chunk.isAir(x, i - 1, z)
                  && chunk.isAir(x, i, z)
                  && chunk.isAir(x, i + 1, z)
                  && skylight > 7
                  && chunk.isSafe(x, i, z, unsafeBlocks)
                  && chunk.isSafe(x, i + 1, z, unsafeBlocks)
                  && chunk.isSafe(x, i - 1, z, unsafeBlocks)) {
                output.setWorldName(chunk.getWorld().name());
                output.setXZ(globalX, globalZ);
                output.setY(i);
                return true;
              }
            }
            break;
          }
        case 1:
          { // top down
            for (int i = maxY; i > minY; i--) {
              int skylight = 15;
              if (requireSkyLight) skylight = chunk.getSkyLight(x, i + 1, z);
              if (!chunk.isAir(x, i - 1, z)
                  && chunk.isAir(x, i, z)
                  && chunk.isAir(x, i + 1, z)
                  && skylight > 7
                  && chunk.isSafe(x, i, z, unsafeBlocks)
                  && chunk.isSafe(x, i + 1, z, unsafeBlocks)
                  && chunk.isSafe(x, i - 1, z, unsafeBlocks)) {
                output.setWorldName(chunk.getWorld().name());
                output.setXZ(globalX, globalZ);
                output.setY(i);
                return true;
              }
            }
            break;
          }
        case 2:
          { // middle out
            int maxDistance =
                (maxY - minY) / 2; // dividing distance is more overflow-safe than simple average
            int middle = minY + maxDistance;
            for (int i = 0; i <= maxDistance; i++) {
              // try top
              int y = middle + i;
              int skylight = 15;
              if (requireSkyLight) skylight = chunk.getSkyLight(x, y + 1, z);
              if (!chunk.isAir(x, y - 1, z)
                  && chunk.isAir(x, y, z)
                  && chunk.isAir(x, y + 1, z)
                  && skylight > 7
                  && chunk.isSafe(x, y, z, unsafeBlocks)
                  && chunk.isSafe(x, y + 1, z, unsafeBlocks)
                  && chunk.isSafe(x, y - 1, z, unsafeBlocks)) {
                output.setWorldName(chunk.getWorld().name());
                output.setXZ(globalX, globalZ);
                output.setY(y);
                return true;
              }

              // try bottom
              y = middle - i;
              skylight = 15;
              if (requireSkyLight) skylight = chunk.getSkyLight(x, y + 1, z);
              if (!chunk.isAir(x, y - 1, z)
                  && chunk.isAir(x, y, z)
                  && chunk.isAir(x, y + 1, z)
                  && skylight > 7
                  && chunk.isSafe(x, y, z, unsafeBlocks)
                  && chunk.isSafe(x, y + 1, z, unsafeBlocks)
                  && chunk.isSafe(x, y - 1, z, unsafeBlocks)) {
                output.setWorldName(chunk.getWorld().name());
                output.setXZ(globalX, globalZ);
                output.setY(y);
                return true;
              }
            }
            break;
          }
        case 3:
          { // edges in
            int maxDistance =
                (maxY - minY) / 2; // dividing distance is more overflow-safe than simple average
            int middle = minY + maxDistance;
            for (int i = maxDistance; i >= 0; i--) {
              // try top
              int y = middle + i;
              int skylight = 15;
              if (requireSkyLight) skylight = chunk.getSkyLight(x, y + 1, z);
              if (!chunk.isAir(x, y - 1, z)
                  && chunk.isAir(x, y, z)
                  && chunk.isAir(x, y + 1, z)
                  && skylight > 7
                  && chunk.isSafe(x, y, z, unsafeBlocks)
                  && chunk.isSafe(x, y + 1, z, unsafeBlocks)
                  && chunk.isSafe(x, y - 1, z, unsafeBlocks)) {
                output.setWorldName(chunk.getWorld().name());
                output.setXZ(globalX, globalZ);
                output.setY(y);
                return true;
              }

              // try bottom
              y = middle - i;
              skylight = 15;
              if (requireSkyLight) skylight = chunk.getSkyLight(x, y + 1, z);
              if (!chunk.isAir(x, y - 1, z)
                  && chunk.isAir(x, y, z)
                  && chunk.isAir(x, y + 1, z)
                  && skylight > 7
                  && chunk.isSafe(x, y, z, unsafeBlocks)
                  && chunk.isSafe(x, y + 1, z, unsafeBlocks)
                  && chunk.isSafe(x, y - 1, z, unsafeBlocks)) {
                output.setWorldName(chunk.getWorld().name());
                output.setXZ(globalX, globalZ);
                output.setY(y);
                return true;
              }
            }
            break;
          }
        default:
          { // random order
            // load up a list of possible vertical indices
            List<Integer> trials = new ArrayList<>(maxY - minY + 1);
            for (int i = minY; i < maxY; i++) {
              trials.add(i);
            }

            // randomize order
            Collections.shuffle(trials, rng);

            // try each
            for (int k = 0; k < trials.size(); k++) {
              int i = trials.get(k);
              int skylight = 15;
              if (requireSkyLight) skylight = chunk.getSkyLight(x, i + 1, z);
              if (!chunk.isAir(x, i - 1, z)
                  && chunk.isAir(x, i, z)
                  && chunk.isAir(x, i + 1, z)
                  && skylight > 7
                  && chunk.isSafe(x, i, z, unsafeBlocks)
                  && chunk.isSafe(x, i + 1, z, unsafeBlocks)
                  && chunk.isSafe(x, i - 1, z, unsafeBlocks)) {
                output.setWorldName(chunk.getWorld().name());
                output.setXZ(globalX, globalZ);
                output.setY(i);
                return true;
              }
            }
          }
      }
    }
    return false;
  }

  /**
   * Probe-backed fast path mirroring {@link #adjust(RTPChunk, MutableRTPCoords)}'s scan modes
   * on the center column of the supplied probe (local {@code x=8, z=8}).
   *
   * <p>Returns {@code null} (fall back to full parse) when:
   * <ul>
   *   <li>the probe's window does not cover the adjustor's {@code [minY, maxY]},</li>
   *   <li>{@code requireSkyLight} is true <em>and</em> the probe reports
   *       {@link ChunkColumnProbe#isLightOn()} is false — lighting hasn't been
   *       finalized for this chunk, so the on-disk sky-light nibble array is stale,</li>
   *   <li>no acceptable Y was found on the center column.</li>
   * </ul>
   *
   * <p>When {@code requireSkyLight} is true and {@code isLightOn} is true, the scan
   * enforces the same {@code skyLight > 7} threshold as {@link #adjust} using
   * {@link ChunkColumnProbe#skyLightAt(int)}; when false, sky-light is not checked.
   */
  @Override
  public @Nullable RTPCoords adjustFromProbe(
      @NotNull ChunkColumnProbe probe, @NotNull String worldName) {
    int maxY = getNumber(GenericVerticalAdjustorKeys.maxY, 320L).intValue();
    int minY = getNumber(GenericVerticalAdjustorKeys.minY, 0L).intValue();
    int dir = getNumber(GenericVerticalAdjustorKeys.direction, 0).intValue();

    boolean requireSkyLight;
    Object o = getData().getOrDefault(GenericVerticalAdjustorKeys.requireSkyLight, false);
    if (o instanceof Boolean) {
      requireSkyLight = (Boolean) o;
    } else requireSkyLight = Boolean.parseBoolean(o.toString());

    // When the caller needs sky-light and the chunk's lighting isn't finalized,
    // on-disk SkyLight is stale — defer to the authoritative path which forces a
    // lighting pass. When !requireSkyLight, isLightOn is irrelevant.
    if (requireSkyLight && !probe.isLightOn()) return null;

    // Probe window must cover the adjustor's [minY, maxY] with one-cell headroom
    // for the y-1 / y+1 safety probes.
    if (probe.minY() > minY - 1 || probe.maxY() < maxY) return null;

    refreshUnsafeBlocks();

    int globalX = (probe.chunkX() << 4) + 8;
    int globalZ = (probe.chunkZ() << 4) + 8;

    int y = scanProbe(probe, minY, maxY, dir, requireSkyLight);
    if (y == Integer.MIN_VALUE) return null;

    MutableRTPCoords out = new MutableRTPCoords(worldName, globalX, y, globalZ);
    return out.toImmutable();
  }

  /**
   * Refresh the cached unsafe-block set on the same 5-second cadence as
   * {@link #adjust(RTPChunk, MutableRTPCoords)}, so probe-first callers that never invoke
   * the authoritative path still see operator-edited safety config.
   */
  private static void refreshUnsafeBlocks() {
    long t = System.currentTimeMillis();
    long dt = t - lastUpdate.get();
    if (dt > 5000 || dt < 0) {
      ConfigParser<SafetyKeys> safety =
          (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
      Object value = safety.getConfigValue(SafetyKeys.unsafeBlocks, new ArrayList<>());
      unsafeBlocks.clear();
      if (value instanceof Collection) {
        unsafeBlocks.addAll(
            ((Collection<?>) value)
                .stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .collect(Collectors.toSet()));
      }
      lastUpdate.set(t);
    }
  }

  /**
   * Scan the center column for an acceptable Y under the given direction mode.
   *
   * @return the accepted Y, or {@link Integer#MIN_VALUE} if none found.
   */
  private int scanProbe(ChunkColumnProbe probe, int minY, int maxY, int dir, boolean requireSkyLight) {
    switch (dir) {
      case 0: // bottom up
        for (int i = minY; i < maxY; i++) if (acceptY(probe, i, requireSkyLight)) return i;
        return Integer.MIN_VALUE;
      case 1: // top down
        for (int i = maxY; i > minY; i--) if (acceptY(probe, i, requireSkyLight)) return i;
        return Integer.MIN_VALUE;
      case 2: { // middle out
        int maxDistance = (maxY - minY) / 2;
        int middle = minY + maxDistance;
        for (int i = 0; i <= maxDistance; i++) {
          if (acceptY(probe, middle + i, requireSkyLight)) return middle + i;
          if (acceptY(probe, middle - i, requireSkyLight)) return middle - i;
        }
        return Integer.MIN_VALUE;
      }
      case 3: { // edges in
        int maxDistance = (maxY - minY) / 2;
        int middle = minY + maxDistance;
        for (int i = maxDistance; i >= 0; i--) {
          if (acceptY(probe, middle + i, requireSkyLight)) return middle + i;
          if (acceptY(probe, middle - i, requireSkyLight)) return middle - i;
        }
        return Integer.MIN_VALUE;
      }
      default: { // random order
        List<Integer> trials = new ArrayList<>(maxY - minY + 1);
        for (int i = minY; i < maxY; i++) trials.add(i);
        Collections.shuffle(trials, rng);
        for (int k = 0; k < trials.size(); k++) {
          int i = trials.get(k);
          if (acceptY(probe, i, requireSkyLight)) return i;
        }
        return Integer.MIN_VALUE;
      }
    }
  }

  private boolean acceptY(ChunkColumnProbe probe, int y, boolean requireSkyLight) {
    // Feet stand on a non-air block; head region (y, y+1) is air.
    if (probe.isAirAt(y - 1)) return false;
    if (!probe.isAirAt(y)) return false;
    if (!probe.isAirAt(y + 1)) return false;
    // Safety: none of y-1, y, y+1 may be in unsafeBlocks.
    String below = probe.blockAt(y - 1);
    String at = probe.blockAt(y);
    String above = probe.blockAt(y + 1);
    if (below == null || at == null || above == null) return false;
    if (unsafeBlocks.contains(below)) return false;
    if (unsafeBlocks.contains(at)) return false;
    if (unsafeBlocks.contains(above)) return false;
    // Sky-light gate: matches adjust()'s `skylight > 7` check at y+1. The probe returns
    // 15 when sky-light wasn't requested at parse time, which is semantically identical
    // to a vanilla section without a SkyLight tag — i.e. treat as fully lit.
    if (requireSkyLight && probe.skyLightAt(y + 1) <= 7) return false;
    return true;
  }

  @Override
  public boolean testPlacement(@NotNull RTPCoords coords) {
    for (int i = 0; i < verifiers.size(); i++) {
      Predicate<RTPCoords> rtpLocationPredicate = verifiers.get(i);
      if (!rtpLocationPredicate.test(coords)) return false;
    }
    return true;
  }

  @Override
  public Map<String, CommandParameter> getParameters() {
    return subParameters;
  }

  @Override
  public int minY() {
    return getNumber(GenericVerticalAdjustorKeys.minY, 0).intValue();
  }

  @Override
  public int maxY() {
    return getNumber(GenericVerticalAdjustorKeys.maxY, 256).intValue();
  }
}
