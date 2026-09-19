package io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.parameters.BooleanParameter;
import io.github.dailystruggle.commandsapi.common.parameters.IntegerParameter;
import io.github.dailystruggle.rtp.api.world.ChunkColumnProbe;
import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.BlocksKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.GenericVerticalAdjustorKeys;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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
  /**
   * Safety state snapshot at adjustor entry: canonicalized {@link BlocksKeys#unsafeBlocks}
   * and {@link SafetyKeys#platformDepth}. Passed by reference through scan predicates
   * for consistent evaluation across a single adjust invocation.
   */
  private record SafetySnapshot(Set<String> unsafeBlocks, int platformDepth) {}

  /**
   * Read the current safety configuration directly from {@link RTP#configs}
   * and canonicalise the unsafe-block tokens for fast set-membership lookup
   * on the probe path. Tag expansion is already performed upstream by
   * {@code SafetyTokenExpander} at config load and on {@code /rtp reload},
   * so the parser values are flat material names by the time this runs.
   */
  @SuppressWarnings("unchecked")
  private static SafetySnapshot readSafetySnapshot() {
    ConfigParser<SafetyKeys> safety =
        (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
    Set<String> unsafe = new HashSet<>();
    if (safety != null) {
      Object value = RTP.configs.getConfigValue(BlocksKeys.unsafeBlocks, new ArrayList<>());
      if (value instanceof Collection) {
        for (Object item : (Collection<?>) value) {
          if (item == null) continue;
          String c = canon(item.toString());
          if (c != null) unsafe.add(c);
        }
      }
    }
    int depth = 1;
    if (safety != null) {
      // Ground-sweep depth - reuses SafetyKeys.safetyRadius so it stays
      // distinct from SafetyKeys.platformDepth (which exclusively sizes the
      // platform-creation tool in BukkitRTPWorld.platform / FoliaRTPWorld.platform).
      // Floor of 1 so the [1..depth] sweep below feet always includes y-1.
      depth = Math.max(1, safety.getNumber(SafetyKeys.safetyRadius, 1).intValue());
    }
    return new SafetySnapshot(unsafe, depth);
  }

  /**
   * Canonicalise an identifier returned by {@link ChunkColumnProbe#blockAt(int)}
   * (lowercase namespaced, e.g. {@code minecraft:water}) into the upper-case,
   * namespace-stripped form used by yml-loaded {@code unsafeBlocks} entries
   * (e.g. {@code WATER}). Mirrors {@code JumpAdjustor.canonicaliseMaterialToken}.
   */
  private static String canon(String id) {
    if (id == null) return null;
    String s = id.trim();
    if (s.isEmpty()) return null;
    // Tag tokens (#namespace:tag) are not material ids - leave them alone so
    // refreshUnsafeBlocks doesn't silently corrupt them. They simply won't
    // match a probe-returned id, which mirrors the pre-fix behaviour for
    // tag-sourced unsafe materials on the probe path (a separate gap, not
    // this fix's concern).
    if (s.charAt(0) == '#') return s.toUpperCase(Locale.ROOT);
    int colon = s.indexOf(':');
    String local = (colon >= 0) ? s.substring(colon + 1) : s;
    // Strip ADR-017 state predicate ([waterlogged=true], etc.) - predicate
    // matching requires BlockData, which the column probe doesn't surface.
    int bracket = local.indexOf('[');
    if (bracket >= 0) local = local.substring(0, bracket);
    return local.toUpperCase(Locale.ROOT);
  }

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
    defaults.put(GenericVerticalAdjustorKeys.direction, 2);
    defaults.put(GenericVerticalAdjustorKeys.requireSkyLight, false);

    // Curated tab-completion suggestions for /rtp vert:linear <TAB>.
    // Mirrors V2 sub-parameter UX so users see the format and scale.
    subParameters.put("maxy", new IntegerParameter(
        "rtp.params", "highest possible location", (sender, s) -> true, 64, 92, 127, 256, 320));
    subParameters.put("miny", new IntegerParameter(
        "rtp.params", "lowest possible location", (sender, s) -> true, -64, 0, 64, 128));
    subParameters.put("direction", new IntegerParameter(
        "rtp.params", "which way to search for a valid location", (sender, s) -> true, 0, 1, 2, 3));
    subParameters.put("requireskylight", new BooleanParameter(
        "rtp.params", "require sky light for placement", (sender, s) -> true));
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

  /**
   * Sweeps {@code chunk.isSafe} across {@code [1..platformDepth]} cells below the
   * candidate feet-Y. Mirrors the probe-path ground-column check in
   * {@link #acceptY} so the live full-load fallback rejects fluids (water/lava)
   * sitting under a thin solid crust - the crust alone would otherwise pass the
   * single {@code y-1} check and the player would drop through on landing.
   * Returns {@code true} when every checked cell is safe.
   */
  private static boolean isGroundSafe(
      RTPChunk chunk, int x, int y, int z, Set<String> unsafeBlocks, int platformDepth) {
    int depth = Math.max(1, platformDepth);
    for (int d = 1; d <= depth; d++) {
      if (!chunk.isSafe(x, y - d, z, unsafeBlocks)) return false;
    }
    return true;
  }

  /**
   * Returns highest non-air Y on column {@code (x, z)} derived from block data.
   * Any {@code y+1 > floor} has unobstructed sky access, avoiding stale light nibbles.
   * Returns {@link Integer#MIN_VALUE} if column is entirely air.
   */
  private static int computeColumnSkyFloor(RTPChunk chunk, int x, int z) {
    int top = chunk.getWorld().getMaxHeight() - 1;
    int bottom = chunk.getWorld().getMinHeight();
    for (int y = top; y >= bottom; y--) {
      if (!chunk.isAir(x, y, z)) return y;
    }
    return Integer.MIN_VALUE;
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

    SafetySnapshot snap = readSafetySnapshot();
    Set<String> unsafeBlocks = snap.unsafeBlocks();
    int platformDepth = snap.platformDepth();

    for (int j = 0; j < testCoords.size(); j++) {
      List<Integer> xz = testCoords.get(j);
      int x = xz.get(0);
      int z = xz.get(1);
      int globalX = (chunk.x() << 4) + x;
      int globalZ = (chunk.z() << 4) + z;
      int columnSkyFloor = requireSkyLight ? computeColumnSkyFloor(chunk, x, z) : Integer.MIN_VALUE;
      switch (dir) {
        case 0:
          { // bottom up
            for (int i = minY; i < maxY; i++) {
              int skylight = (!requireSkyLight || (i + 1) > columnSkyFloor) ? 15 : 0;
              if (!chunk.isAir(x, i - 1, z)
                  && chunk.isAir(x, i, z)
                  && chunk.isAir(x, i + 1, z)
                  && skylight > 7
                  && chunk.isSafe(x, i, z, unsafeBlocks)
                  && chunk.isSafe(x, i + 1, z, unsafeBlocks)
                  && isGroundSafe(chunk, x, i, z, unsafeBlocks, platformDepth)) {
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
              int skylight = (!requireSkyLight || (i + 1) > columnSkyFloor) ? 15 : 0;
              if (!chunk.isAir(x, i - 1, z)
                  && chunk.isAir(x, i, z)
                  && chunk.isAir(x, i + 1, z)
                  && skylight > 7
                  && chunk.isSafe(x, i, z, unsafeBlocks)
                  && chunk.isSafe(x, i + 1, z, unsafeBlocks)
                  && isGroundSafe(chunk, x, i, z, unsafeBlocks, platformDepth)) {
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
              int skylight = (!requireSkyLight || (y + 1) > columnSkyFloor) ? 15 : 0;
              if (!chunk.isAir(x, y - 1, z)
                  && chunk.isAir(x, y, z)
                  && chunk.isAir(x, y + 1, z)
                  && skylight > 7
                  && chunk.isSafe(x, y, z, unsafeBlocks)
                  && chunk.isSafe(x, y + 1, z, unsafeBlocks)
                  && isGroundSafe(chunk, x, y, z, unsafeBlocks, platformDepth)) {
                output.setWorldName(chunk.getWorld().name());
                output.setXZ(globalX, globalZ);
                output.setY(y);
                return true;
              }

              // try bottom
              y = middle - i;
              skylight = (!requireSkyLight || (y + 1) > columnSkyFloor) ? 15 : 0;
              if (!chunk.isAir(x, y - 1, z)
                  && chunk.isAir(x, y, z)
                  && chunk.isAir(x, y + 1, z)
                  && skylight > 7
                  && chunk.isSafe(x, y, z, unsafeBlocks)
                  && chunk.isSafe(x, y + 1, z, unsafeBlocks)
                  && isGroundSafe(chunk, x, y, z, unsafeBlocks, platformDepth)) {
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
              int skylight = (!requireSkyLight || (y + 1) > columnSkyFloor) ? 15 : 0;
              if (!chunk.isAir(x, y - 1, z)
                  && chunk.isAir(x, y, z)
                  && chunk.isAir(x, y + 1, z)
                  && skylight > 7
                  && chunk.isSafe(x, y, z, unsafeBlocks)
                  && chunk.isSafe(x, y + 1, z, unsafeBlocks)
                  && isGroundSafe(chunk, x, y, z, unsafeBlocks, platformDepth)) {
                output.setWorldName(chunk.getWorld().name());
                output.setXZ(globalX, globalZ);
                output.setY(y);
                return true;
              }

              // try bottom
              y = middle - i;
              skylight = (!requireSkyLight || (y + 1) > columnSkyFloor) ? 15 : 0;
              if (!chunk.isAir(x, y - 1, z)
                  && chunk.isAir(x, y, z)
                  && chunk.isAir(x, y + 1, z)
                  && skylight > 7
                  && chunk.isSafe(x, y, z, unsafeBlocks)
                  && chunk.isSafe(x, y + 1, z, unsafeBlocks)
                  && isGroundSafe(chunk, x, y, z, unsafeBlocks, platformDepth)) {
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
              int skylight = (!requireSkyLight || (i + 1) > columnSkyFloor) ? 15 : 0;
              if (!chunk.isAir(x, i - 1, z)
                  && chunk.isAir(x, i, z)
                  && chunk.isAir(x, i + 1, z)
                  && skylight > 7
                  && chunk.isSafe(x, i, z, unsafeBlocks)
                  && chunk.isSafe(x, i + 1, z, unsafeBlocks)
                  && isGroundSafe(chunk, x, i, z, unsafeBlocks, platformDepth)) {
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
   * Shared per-cell acceptance predicate: standable feet-Y {@code y} on column
   * {@code (x, z)} with head clearance, sky-light gate, block safety, and ground
   * depth re-check. Identical to the inner test in {@link #adjust(RTPChunk, MutableRTPCoords)}
   * so the two paths share one definition of "safe cell" (S-001, no drift).
   */
  private static boolean acceptColumnY(
      RTPChunk chunk,
      int x,
      int y,
      int z,
      boolean requireSkyLight,
      int columnSkyFloor,
      Set<String> unsafeBlocks,
      int platformDepth) {
    int skylight = (!requireSkyLight || (y + 1) > columnSkyFloor) ? 15 : 0;
    return !chunk.isAir(x, y - 1, z)
        && chunk.isAir(x, y, z)
        && chunk.isAir(x, y + 1, z)
        && skylight > 7
        && chunk.isSafe(x, y, z, unsafeBlocks)
        && chunk.isSafe(x, y + 1, z, unsafeBlocks)
        && isGroundSafe(chunk, x, y, z, unsafeBlocks, platformDepth);
  }

  /**
   * Re-validates a single, caller-specified column {@code (localX, localZ)} using the
   * same direction sweep and safety predicate as {@link #adjust(RTPChunk, MutableRTPCoords)}.
   * Used by the group subspace path (per-slot resolution) where a specific column - not any
   * column in the chunk - must be resolved. Returns {@code null} when the column has no safe
   * standing Y (fail-closed, S-004).
   */
  @Override
  public @Nullable RTPCoords adjustColumn(@NotNull RTPChunk chunk, int localX, int localZ) {
    if (chunk == null) return null;

    int maxY = getNumber(GenericVerticalAdjustorKeys.maxY, 320L).intValue();
    int minY = getNumber(GenericVerticalAdjustorKeys.minY, 0L).intValue();
    int dir = getNumber(GenericVerticalAdjustorKeys.direction, 0).intValue();

    maxY = Math.min(maxY, chunk.getWorld().getMaxHeight());

    boolean requireSkyLight;
    Object o = getData().getOrDefault(GenericVerticalAdjustorKeys.requireSkyLight, false);
    if (o instanceof Boolean) {
      requireSkyLight = (Boolean) o;
    } else requireSkyLight = Boolean.parseBoolean(o.toString());

    SafetySnapshot snap = readSafetySnapshot();
    Set<String> unsafeBlocks = snap.unsafeBlocks();
    int platformDepth = snap.platformDepth();

    int x = localX & 15;
    int z = localZ & 15;
    int globalX = (chunk.x() << 4) + x;
    int globalZ = (chunk.z() << 4) + z;
    int columnSkyFloor = requireSkyLight ? computeColumnSkyFloor(chunk, x, z) : Integer.MIN_VALUE;

    Integer y = scanColumnForY(
        chunk, x, z, minY, maxY, dir, requireSkyLight, columnSkyFloor, unsafeBlocks, platformDepth);
    if (y == null) return null;
    return new MutableRTPCoords(chunk.getWorld().name(), globalX, y, globalZ).toImmutable();
  }

  /**
   * Direction-aware single-column Y sweep mirroring the {@code dir} switch in
   * {@link #adjust(RTPChunk, MutableRTPCoords)}. Returns the first accepted feet-Y, or
   * {@code null} if none on this column.
   */
  private Integer scanColumnForY(
      RTPChunk chunk,
      int x,
      int z,
      int minY,
      int maxY,
      int dir,
      boolean requireSkyLight,
      int columnSkyFloor,
      Set<String> unsafeBlocks,
      int platformDepth) {
    switch (dir) {
      case 0: { // bottom up
        for (int i = minY; i < maxY; i++) {
          if (acceptColumnY(chunk, x, i, z, requireSkyLight, columnSkyFloor, unsafeBlocks, platformDepth)) return i;
        }
        break;
      }
      case 1: { // top down
        for (int i = maxY; i > minY; i--) {
          if (acceptColumnY(chunk, x, i, z, requireSkyLight, columnSkyFloor, unsafeBlocks, platformDepth)) return i;
        }
        break;
      }
      case 2: { // middle out
        int maxDistance = (maxY - minY) / 2;
        int middle = minY + maxDistance;
        for (int i = 0; i <= maxDistance; i++) {
          int yTop = middle + i;
          if (acceptColumnY(chunk, x, yTop, z, requireSkyLight, columnSkyFloor, unsafeBlocks, platformDepth)) return yTop;
          int yBot = middle - i;
          if (acceptColumnY(chunk, x, yBot, z, requireSkyLight, columnSkyFloor, unsafeBlocks, platformDepth)) return yBot;
        }
        break;
      }
      case 3: { // edges in
        int maxDistance = (maxY - minY) / 2;
        int middle = minY + maxDistance;
        for (int i = maxDistance; i >= 0; i--) {
          int yTop = middle + i;
          if (acceptColumnY(chunk, x, yTop, z, requireSkyLight, columnSkyFloor, unsafeBlocks, platformDepth)) return yTop;
          int yBot = middle - i;
          if (acceptColumnY(chunk, x, yBot, z, requireSkyLight, columnSkyFloor, unsafeBlocks, platformDepth)) return yBot;
        }
        break;
      }
      default: { // random order
        List<Integer> trials = new ArrayList<>(Math.max(0, maxY - minY + 1));
        for (int i = minY; i < maxY; i++) trials.add(i);
        Collections.shuffle(trials, rng);
        for (int i : trials) {
          if (acceptColumnY(chunk, x, i, z, requireSkyLight, columnSkyFloor, unsafeBlocks, platformDepth)) return i;
        }
      }
    }
    return null;
  }

  /**
   * Probe-backed fast path mirroring {@link #adjust(RTPChunk, MutableRTPCoords)}.
   * Returns {@code null} (fall back to live {@link #adjust}) when the probe
   * window doesn't cover {@code [minY, maxY]} or no acceptable Y was found.
   */
  @Override
  public @Nullable RTPCoords adjustFromProbe(
      @NotNull ChunkColumnProbe probe, @NotNull String worldName) {
    return adjustFromProbeWithReason(probe, worldName).picked();
  }

  /**
   * Typed probe-path entry point - same decision tree as the nullable-return
   * {@link #adjustFromProbe}, but reports which gate closed when no coords
   * are returned. {@link #adjustFromProbe} delegates here to keep the two
   * paths as a single source of truth.
   */
  @Override
  public @NotNull AdjustResult adjustFromProbeWithReason(
      @NotNull ChunkColumnProbe probe, @NotNull String worldName) {
    int maxY = getNumber(GenericVerticalAdjustorKeys.maxY, 320L).intValue();
    int minY = getNumber(GenericVerticalAdjustorKeys.minY, 0L).intValue();
    int dir = getNumber(GenericVerticalAdjustorKeys.direction, 0).intValue();

    boolean requireSkyLight;
    Object o = getData().getOrDefault(GenericVerticalAdjustorKeys.requireSkyLight, false);
    if (o instanceof Boolean) {
      requireSkyLight = (Boolean) o;
    } else requireSkyLight = Boolean.parseBoolean(o.toString());

    // Probe window must cover the adjustor's [minY, maxY] with one-cell headroom
    // for the y-1 / y+1 safety probes.
    if (probe.minY() > minY - 1 || probe.maxY() < maxY) return AdjustResult.WINDOW_REJECT;

    SafetySnapshot snap = readSafetySnapshot();

    // Multi-column sweep over testCoords aligning with live adjust() columns.
    // Sky-light gating checks per-column block-data floor (computeColumnSkyFloor).
    for (int j = 0; j < testCoords.size(); j++) {
      List<Integer> xz = testCoords.get(j);
      int lx = xz.get(0);
      int lz = xz.get(1);
      int columnSkyFloor = requireSkyLight
          ? computeColumnSkyFloor(probe, lx, lz)
          : Integer.MIN_VALUE;
      int y = scanProbe(probe, lx, lz, minY, maxY, dir, requireSkyLight, columnSkyFloor,
          snap.unsafeBlocks(), snap.platformDepth());
      if (y == Integer.MIN_VALUE) continue;
      int globalX = (probe.chunkX() << 4) + lx;
      int globalZ = (probe.chunkZ() << 4) + lz;
      MutableRTPCoords out = new MutableRTPCoords(worldName, globalX, y, globalZ);
      return AdjustResult.ok(out.toImmutable());
    }
    return AdjustResult.SCAN_MISS_REJECT;
  }

  /**
   * Returns highest non-air Y on column {@code (lx, lz)} within probe window.
   * Any {@code y+1 > floor} has unobstructed sky access, avoiding stale light nibbles.
   * Returns {@link Integer#MIN_VALUE} if column is entirely air.
   */
  private static int computeColumnSkyFloor(ChunkColumnProbe probe, int lx, int lz) {
    int top = probe.maxY();
    int bottom = probe.minY();
    for (int y = top; y >= bottom; y--) {
      if (!probe.isAirAt(lx, lz, y)) return y;
    }
    return Integer.MIN_VALUE;
  }

  /**
   * Scans column {@code (lx, lz)} for an acceptable Y matching the direction mode.
   *
   * @param columnSkyFloor highest non-air Y on column, or {@link Integer#MIN_VALUE}
   * @return accepted Y, or {@link Integer#MIN_VALUE} if none found
   */
  private int scanProbe(ChunkColumnProbe probe, int lx, int lz, int minY, int maxY, int dir,
                        boolean requireSkyLight, int columnSkyFloor,
                        Set<String> unsafeBlocks, int platformDepth) {
    switch (dir) {
      case 0: // bottom up
        for (int i = minY; i < maxY; i++)
          if (acceptY(probe, lx, lz, i, requireSkyLight, columnSkyFloor, unsafeBlocks, platformDepth)) return i;
        return Integer.MIN_VALUE;
      case 1: // top down
        for (int i = maxY; i > minY; i--)
          if (acceptY(probe, lx, lz, i, requireSkyLight, columnSkyFloor, unsafeBlocks, platformDepth)) return i;
        return Integer.MIN_VALUE;
      case 2: { // middle out
        int maxDistance = (maxY - minY) / 2;
        int middle = minY + maxDistance;
        for (int i = 0; i <= maxDistance; i++) {
          if (acceptY(probe, lx, lz, middle + i, requireSkyLight, columnSkyFloor, unsafeBlocks, platformDepth)) return middle + i;
          if (acceptY(probe, lx, lz, middle - i, requireSkyLight, columnSkyFloor, unsafeBlocks, platformDepth)) return middle - i;
        }
        return Integer.MIN_VALUE;
      }
      case 3: { // edges in
        int maxDistance = (maxY - minY) / 2;
        int middle = minY + maxDistance;
        for (int i = maxDistance; i >= 0; i--) {
          if (acceptY(probe, lx, lz, middle + i, requireSkyLight, columnSkyFloor, unsafeBlocks, platformDepth)) return middle + i;
          if (acceptY(probe, lx, lz, middle - i, requireSkyLight, columnSkyFloor, unsafeBlocks, platformDepth)) return middle - i;
        }
        return Integer.MIN_VALUE;
      }
      default: { // random order
        List<Integer> trials = new ArrayList<>(maxY - minY + 1);
        for (int i = minY; i < maxY; i++) trials.add(i);
        Collections.shuffle(trials, rng);
        for (int k = 0; k < trials.size(); k++) {
          int i = trials.get(k);
          if (acceptY(probe, lx, lz, i, requireSkyLight, columnSkyFloor, unsafeBlocks, platformDepth)) return i;
        }
        return Integer.MIN_VALUE;
      }
    }
  }

  /**
   * Multi-column acceptance predicate: same logic as the legacy single-column
   * scan but reads from chunk-local column {@code (lx, lz)} via the probe's
   * off-center accessors. Sky-floor is computed per-column from block data
   * (see {@link #computeColumnSkyFloor}).
   */
  private boolean acceptY(ChunkColumnProbe probe, int lx, int lz, int y,
                          boolean requireSkyLight, int columnSkyFloor,
                          Set<String> unsafeBlocks, int platformDepth) {
    // Feet stand on a non-air block; head region (y, y+1) is air.
    if (probe.isAirAt(lx, lz, y - 1)) return false;
    if (!probe.isAirAt(lx, lz, y)) return false;
    if (!probe.isAirAt(lx, lz, y + 1)) return false;
    // Safety: head/feet must not be unsafe; ground column from y-1 down to
    // y-platformDepth must not contain a fluid (water/lava) or other unsafe
    // material. Compares canonicalised (uppercase, namespace-stripped) ids
    // against the canonicalised unsafeBlocks set.
    String at = canon(probe.blockAt(lx, lz, y));
    String above = canon(probe.blockAt(lx, lz, y + 1));
    if (at == null || above == null) return false;
    if (unsafeBlocks.contains(at)) return false;
    if (unsafeBlocks.contains(above)) return false;
    int depth = Math.max(1, platformDepth);
    for (int d = 1; d <= depth; d++) {
      String b = canon(probe.blockAt(lx, lz, y - d));
      if (b == null) return false;
      if (unsafeBlocks.contains(b)) return false;
    }
    // Sky-light gate (block-data only): accept iff y+1 is strictly above the
    // highest non-air block on this column. Stored sky-light nibbles and
    // heightmap data are ignored - both are unreliable on unticked /
    // freshly-generated chunks. Block data is always present and deterministic.
    if (requireSkyLight) {
      if (y + 1 <= columnSkyFloor) return false;
    }
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
