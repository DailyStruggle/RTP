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
   * Snapshot of the configured safety state read at adjustor entry: the
   * canonicalised {@link SafetyKeys#unsafeBlocks} set and {@link
   * SafetyKeys#platformDepth}. Plumbed by reference through every helper
   * predicate ({@link #acceptY}, {@link #isGroundSafe}, {@link #scanProbe})
   * so a single {@code adjust(...)} / {@code adjustFromProbeWithReason(...)}
   * invocation evaluates the entire scan against one consistent snapshot.
   * No static cache — config is read once per top-level entry, and {@code
   * SafetyTokenExpander} (config load + {@code /rtp reload}) ensures the
   * parser already holds the tag-expanded list.
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
      Object value = safety.getConfigValue(SafetyKeys.unsafeBlocks, new ArrayList<>());
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
      // Ground-sweep depth — reuses SafetyKeys.safetyRadius so it stays
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
    // Tag tokens (#namespace:tag) are not material ids — leave them alone so
    // refreshUnsafeBlocks doesn't silently corrupt them. They simply won't
    // match a probe-returned id, which mirrors the pre-fix behaviour for
    // tag-sourced unsafe materials on the probe path (a separate gap, not
    // this fix's concern).
    if (s.charAt(0) == '#') return s.toUpperCase(Locale.ROOT);
    int colon = s.indexOf(':');
    String local = (colon >= 0) ? s.substring(colon + 1) : s;
    // Strip ADR-017 state predicate ([waterlogged=true], etc.) — predicate
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
    defaults.put(GenericVerticalAdjustorKeys.direction, 0);
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
   * sitting under a thin solid crust — the crust alone would otherwise pass the
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
              int skylight = 15;
              if (requireSkyLight) skylight = chunk.getSkyLight(x, i + 1, z);
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
              int skylight = 15;
              if (requireSkyLight) skylight = chunk.getSkyLight(x, y + 1, z);
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
              skylight = 15;
              if (requireSkyLight) skylight = chunk.getSkyLight(x, y + 1, z);
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
              int skylight = 15;
              if (requireSkyLight) skylight = chunk.getSkyLight(x, y + 1, z);
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
              skylight = 15;
              if (requireSkyLight) skylight = chunk.getSkyLight(x, y + 1, z);
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
              int skylight = 15;
              if (requireSkyLight) skylight = chunk.getSkyLight(x, i + 1, z);
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
   * Probe-backed fast path mirroring {@link #adjust(RTPChunk, MutableRTPCoords)}'s scan modes
   * on the center column of the supplied probe (local {@code x=8, z=8}).
   *
   * <p>Returns {@code null} (fall back to the live {@link #adjust} vert method) when:
   * <ul>
   *   <li>the probe's window does not cover the adjustor's {@code [minY, maxY]},</li>
   *   <li>{@code requireSkyLight} is true and the probe's on-disk sky-light is
   *       stale ({@link ChunkColumnProbe#isLightOn()} false) <em>and</em> the
   *       heightmap-derived sky-access proxy can't be trusted either (no
   *       heightmap, or the column has a non-air block above the reported top —
   *       cave roof, ravine overhang, structure ceiling, player edit, or an
   *       older-version chunk whose noise maps no longer correlate with the
   *       heightmap),</li>
   *   <li>no acceptable Y was found on the center column.</li>
   * </ul>
   *
   * <p>Sky-light decision tree when {@code requireSkyLight} is true:
   * <ol>
   *   <li>{@code isLightOn} true → trust {@link ChunkColumnProbe#skyLightAt(int)}
   *       and apply the same {@code &gt; 7} threshold as {@link #adjust}.</li>
   *   <li>{@code isLightOn} false + heightmap present + verified open from
   *       {@code heightmapTopY+1} through {@code maxY} → accept any
   *       {@code y+1 &gt; heightmapTopY} as sky-access (vanilla guarantees
   *       sky-light = 15 above the {@code MOTION_BLOCKING_NO_LEAVES} top once
   *       the light engine finalises). The verification step exists because
   *       the heightmap alone is not authoritative — caves/ravines/structures
   *       and player edits routinely contradict it; we re-check the column
   *       blocks the probe already carries before trusting the proxy. Light
   *       data is validated separately at the unkept→kept chunk-load handoff
   *       (live vert fallback path).</li>
   *   <li>{@code isLightOn} false + heightmap absent or contradicted →
   *       return {@code null} and let the live vert method handle it.</li>
   * </ol>
   */
  @Override
  public @Nullable RTPCoords adjustFromProbe(
      @NotNull ChunkColumnProbe probe, @NotNull String worldName) {
    return adjustFromProbeWithReason(probe, worldName).picked();
  }

  /**
   * Typed probe-path entry point — same decision tree as the nullable-return
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

    // Decide sky-light source: trusted nibble array (skyLightAt), or a verified
    // heightmap proxy, or defer to the live vert method.
    int heightmapSkyFloor = computeHeightmapSkyFloor(probe, requireSkyLight);
    if (heightmapSkyFloor == Integer.MAX_VALUE) return AdjustResult.LIGHT_GATE_REJECT;

    SafetySnapshot snap = readSafetySnapshot();

    // Multi-column probe sweep over the same testCoords set used by the live
    // adjust(RTPChunk,...) path. Aligning the column sets makes a probe
    // SCAN_MISS authoritative (no acceptable Y exists on any of the 5 live
    // columns), so ScanTask can short-circuit instead of paying a full chunk
    // load. Off-center reads are O(1) palette-index lookups via the probe.
    for (int j = 0; j < testCoords.size(); j++) {
      List<Integer> xz = testCoords.get(j);
      int lx = xz.get(0);
      int lz = xz.get(1);
      int y = scanProbe(probe, lx, lz, minY, maxY, dir, requireSkyLight, heightmapSkyFloor,
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
   * Pick a sky-light source for {@link #acceptY}.
   *
   * <p>Return contract:
   * <ul>
   *   <li>{@link Integer#MIN_VALUE} — sky-light is not required, or {@code
   *       isLightOn} is true; use {@link ChunkColumnProbe#skyLightAt(int)}.</li>
   *   <li>the heightmap top Y — sky-light is required, {@code isLightOn} is
   *       false, the chunk has a {@code MOTION_BLOCKING_NO_LEAVES} heightmap,
   *       <em>and</em> every cell from {@code top+1} through {@link
   *       ChunkColumnProbe#maxY()} on the center column reads as air (no
   *       overhang/cave/structure/player edit contradicts the reported top).
   *       Callers may then treat any {@code y+1 &gt; floor} as fully sky-lit.</li>
   *   <li>{@link Integer#MAX_VALUE} — sky-light is required, {@code isLightOn}
   *       is false, and the heightmap is absent or contradicted. The caller
   *       must return {@code null} and defer to the live vert method (where
   *       light is validated at the unkept→kept chunk-load handoff).</li>
   * </ul>
   *
   * <p>The verification walk is bounded by the probe's full Y window
   * ({@code probe.maxY() - top} cells, called at most once per probed chunk)
   * — not the adjustor's narrower {@code [minY, maxY]} — because skylight
   * propagates from above the adjustor's scan range, so an overhang at
   * {@code y > adjustor.maxY} but {@code y <= probe.maxY} still blocks sky
   * access to candidates below it. Only runs on the cold {@code !isLightOn}
   * branch, so it does not regress the hot path.
   */
  private static int computeHeightmapSkyFloor(
      ChunkColumnProbe probe, boolean requireSkyLight) {
    if (!requireSkyLight) return Integer.MIN_VALUE;
    if (probe.isLightOn()) return Integer.MIN_VALUE;
    OptionalInt h = probe.heightmapTopY();
    if (h.isEmpty()) return Integer.MAX_VALUE;
    int top = h.getAsInt();
    // Verify heightmap honesty across the probe's full Y window: anything
    // between top+1 and probe.maxY() must be air. A non-air block anywhere
    // above the reported top means we cannot trust "y > top → sky-lit" —
    // defer to the live path which will relight.
    int verifyTo = probe.maxY();
    for (int y = top + 1; y <= verifyTo; y++) {
      if (!probe.isAirAt(y)) return Integer.MAX_VALUE;
    }
    return top;
  }


  /**
   * Scan chunk-local column {@code (lx, lz)} for an acceptable Y under the given
   * direction mode. Mirrors the live {@code adjust(RTPChunk, MutableRTPCoords)}
   * testCoords sweep so the probe path can authoritatively report SCAN_MISS
   * across the same five columns.
   *
   * @param heightmapSkyFloor sky-light source selector — see
   *     {@link #computeHeightmapSkyFloor}: {@link Integer#MIN_VALUE} means use
   *     {@link ChunkColumnProbe#skyLightAt(int, int, int)}, otherwise treat
   *     any {@code y+1 > floor} as fully sky-lit.
   * @return the accepted Y, or {@link Integer#MIN_VALUE} if none found.
   */
  private int scanProbe(ChunkColumnProbe probe, int lx, int lz, int minY, int maxY, int dir,
                        boolean requireSkyLight, int heightmapSkyFloor,
                        Set<String> unsafeBlocks, int platformDepth) {
    switch (dir) {
      case 0: // bottom up
        for (int i = minY; i < maxY; i++)
          if (acceptY(probe, lx, lz, i, requireSkyLight, heightmapSkyFloor, unsafeBlocks, platformDepth)) return i;
        return Integer.MIN_VALUE;
      case 1: // top down
        for (int i = maxY; i > minY; i--)
          if (acceptY(probe, lx, lz, i, requireSkyLight, heightmapSkyFloor, unsafeBlocks, platformDepth)) return i;
        return Integer.MIN_VALUE;
      case 2: { // middle out
        int maxDistance = (maxY - minY) / 2;
        int middle = minY + maxDistance;
        for (int i = 0; i <= maxDistance; i++) {
          if (acceptY(probe, lx, lz, middle + i, requireSkyLight, heightmapSkyFloor, unsafeBlocks, platformDepth)) return middle + i;
          if (acceptY(probe, lx, lz, middle - i, requireSkyLight, heightmapSkyFloor, unsafeBlocks, platformDepth)) return middle - i;
        }
        return Integer.MIN_VALUE;
      }
      case 3: { // edges in
        int maxDistance = (maxY - minY) / 2;
        int middle = minY + maxDistance;
        for (int i = maxDistance; i >= 0; i--) {
          if (acceptY(probe, lx, lz, middle + i, requireSkyLight, heightmapSkyFloor, unsafeBlocks, platformDepth)) return middle + i;
          if (acceptY(probe, lx, lz, middle - i, requireSkyLight, heightmapSkyFloor, unsafeBlocks, platformDepth)) return middle - i;
        }
        return Integer.MIN_VALUE;
      }
      default: { // random order
        List<Integer> trials = new ArrayList<>(maxY - minY + 1);
        for (int i = minY; i < maxY; i++) trials.add(i);
        Collections.shuffle(trials, rng);
        for (int k = 0; k < trials.size(); k++) {
          int i = trials.get(k);
          if (acceptY(probe, lx, lz, i, requireSkyLight, heightmapSkyFloor, unsafeBlocks, platformDepth)) return i;
        }
        return Integer.MIN_VALUE;
      }
    }
  }

  /**
   * Multi-column acceptance predicate: same logic as the legacy single-column
   * scan but reads from chunk-local column {@code (lx, lz)} via the probe's
   * off-center accessors. Heightmap-derived sky-floor is chunk-wide because
   * the probe retains a single (center-column) heightmap.
   */
  private boolean acceptY(ChunkColumnProbe probe, int lx, int lz, int y,
                          boolean requireSkyLight, int heightmapSkyFloor,
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
    if (requireSkyLight) {
      if (heightmapSkyFloor != Integer.MIN_VALUE) {
        if (y + 1 <= heightmapSkyFloor) return false;
      } else if (probe.skyLightAt(lx, lz, y + 1) <= 7) {
        return false;
      }
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
