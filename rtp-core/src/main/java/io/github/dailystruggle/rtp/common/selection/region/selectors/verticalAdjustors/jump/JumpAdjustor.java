package io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.jump;

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
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class JumpAdjustor extends VerticalAdjustor<JumpAdjustorKeys> {
  protected static final Map<String, CommandParameter> subParameters = new ConcurrentHashMap<>();
  protected static final List<String> keys =
      Arrays.stream(GenericMemoryShapeParams.values()).map(Enum::name).collect(Collectors.toList());
  private static final EnumMap<JumpAdjustorKeys, Object> defaults =
      new EnumMap<>(JumpAdjustorKeys.class);

  /**
   * Snapshot of the configured safety state read at adjustor entry: the
   * canonicalised {@link BlocksKeys#unsafeBlocks} and {@link BlocksKeys#airBlocks}
   * sets (with ADR-017 tag tokens flattened through {@code RTP.serverAccessor
   * .blockTagSnapshot()}) and {@link SafetyKeys#platformDepth}. Plumbed by
   * reference through every helper predicate so a single {@code adjust(...)} /
   * {@code adjustFromProbeWithReason(...)} invocation evaluates the entire scan
   * against one consistent snapshot. No static cache — the parser already holds
   * the tag-expanded list (written back by {@code SafetyTokenExpander} at config
   * load and on {@code /rtp reload}), and the per-call {@code blockTagSnapshot()}
   * lookup is a hash read against the cached registry map.
   */
  private record SafetySnapshot(
      Set<String> unsafeBlocks, Set<String> airBlocks, int platformDepth) {}

  /**
   * Read the current safety configuration directly from {@link RTP#configs}
   * and the {@link io.github.dailystruggle.rtp.api.server.RTPServerAccessor#blockTagSnapshot()
   * block-tag snapshot}. Tokens are normalised on the fly: bare materials are
   * canonicalised, {@code #namespace:tag} tokens are expanded into their
   * registered material members, and {@code MATERIAL[prop=val]} state-predicated
   * tokens are dropped (the probe path has no property map; the compiled-form
   * consumer still honours them via {@code SafetyCompilationCache}).
   */
  @SuppressWarnings("unchecked")
  private static SafetySnapshot readSafetySnapshot() {
    ConfigParser<SafetyKeys> safety =
        (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);

    Map<String, Set<String>> tagSnapshot = Collections.emptyMap();
    if (RTP.serverAccessor != null) {
      try {
        Map<String, Set<String>> snap = RTP.serverAccessor.blockTagSnapshot();
        if (snap != null) tagSnapshot = snap;
      } catch (Throwable ignored) {
        // Best-effort — fall through with the empty snapshot.
      }
    }

    Set<String> unsafe = new HashSet<>();
    Set<String> air = new HashSet<>();
    int depth = 1;
    if (safety != null) {
      expandTokens(
          RTP.configs.getConfigValue(BlocksKeys.unsafeBlocks, new ArrayList<>()), tagSnapshot, unsafe);
      expandTokens(
          RTP.configs.getConfigValue(BlocksKeys.airBlocks, new ArrayList<>()), tagSnapshot, air);
      // Ground-sweep depth — reuses SafetyKeys.safetyRadius so it stays
      // distinct from SafetyKeys.platformDepth (which exclusively sizes the
      // platform-creation tool in BukkitRTPWorld.platform / FoliaRTPWorld.platform).
      // Floor of 1 so the [1..depth] sweep below feet always includes y-1.
      depth = Math.max(1, safety.getNumber(SafetyKeys.safetyRadius, 1).intValue());
    }
    return new SafetySnapshot(unsafe, air, depth);
  }

  /**
   * Expand an ADR-017 safety-token list into {@code sink} as canonical material
   * names. Bare materials are canonicalised; {@code #namespace:tag} tokens are
   * resolved through {@code tagSnapshot} (bare tag ids default to
   * {@code minecraft:}); {@code MATERIAL[prop=val]} state-predicated tokens are
   * dropped because the probe path has no property map.
   */
  private static void expandTokens(
      Object raw, Map<String, Set<String>> tagSnapshot, Set<String> sink) {
    if (!(raw instanceof Collection)) return;
    for (Object item : (Collection<?>) raw) {
      if (item == null) continue;
      String token = item.toString().trim();
      if (token.isEmpty()) continue;
      if (token.indexOf('[') >= 0) continue; // state-predicated — drop on probe path
      if (token.charAt(0) == '#') {
        String tagId = token.substring(1);
        if (tagId.indexOf(':') < 0) tagId = "minecraft:" + tagId;
        tagId = tagId.toLowerCase(Locale.ROOT);
        Set<String> members = tagSnapshot.get(tagId);
        if (members == null) continue;
        for (String m : members) {
          if (m == null) continue;
          sink.add(canonicaliseMaterialToken(m));
        }
        continue;
      }
      sink.add(canonicaliseMaterialToken(token));
    }
  }

  private static final List<List<Integer>> testCoords =
      Arrays.asList(
          Arrays.asList(7, 7),
          Arrays.asList(2, 2),
          Arrays.asList(12, 12),
          Arrays.asList(2, 12),
          Arrays.asList(12, 2));

  static {
    defaults.put(JumpAdjustorKeys.maxY, 127);
    defaults.put(JumpAdjustorKeys.minY, 32);
    defaults.put(JumpAdjustorKeys.step, 0);
    defaults.put(JumpAdjustorKeys.requireSkyLight, false);

    // Curated tab-completion suggestions for /rtp vert:jump <TAB>.
    // Mirrors V2 sub-parameter UX so users see the format and scale.
    subParameters.put("maxy", new IntegerParameter(
        "rtp.params", "highest possible location", (sender, s) -> true, 64, 92, 127, 256, 320));
    subParameters.put("miny", new IntegerParameter(
        "rtp.params", "lowest possible location", (sender, s) -> true, -64, 0, 64, 128));
    subParameters.put("step", new IntegerParameter(
        "rtp.params", "initial amount to jump", (sender, s) -> true, 1, 16, 32));
    subParameters.put("requireskylight", new BooleanParameter(
        "rtp.params", "require sky light for placement", (sender, s) -> true));
  }

  public JumpAdjustor(List<Predicate<RTPCoords>> verifiers) {
    super(JumpAdjustorKeys.class, "jump", verifiers, defaults);
  }

  @Override
  public Collection<String> keys() {
    return Arrays.stream(JumpAdjustorKeys.values()).map(Enum::name).collect(Collectors.toList());
  }

  @Override
  public boolean requiresSkyLight() {
    Object o = getData().getOrDefault(JumpAdjustorKeys.requireSkyLight, false);
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
   * {@link #acceptProbeY} so the live full-load fallback rejects fluids hidden
   * under a thin solid crust (sand-over-water, magma-under-cobblestone).
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
   * Live-chunk analogue of {@link #computeColumnSkyFloor(ChunkColumnProbe, int, int)}:
   * the highest non-air Y on chunk-local column {@code (x, z)} derived purely
   * from block data. Any {@code y+1} strictly above this floor has unobstructed
   * sky access by construction. Stored sky-light nibbles ({@code getSkyLight})
   * are unreliable on freshly-generated / unticked chunks (the server may report
   * a stale full 15 before relighting), which let cave candidates pass the legacy
   * {@code skyLight > 7} gate; walking the palette is deterministic and always
   * available. Returns {@link Integer#MIN_VALUE} for a fully-air column.
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
    if (chunk == null) throw new NullPointerException("Chunk cannot be null");

    int maxY = getNumber(JumpAdjustorKeys.maxY, 256L).intValue();
    int minY = getNumber(JumpAdjustorKeys.minY, 0L).intValue();
    int step = getNumber(JumpAdjustorKeys.step, 0).intValue();

    maxY = Math.min(maxY, chunk.getWorld().getMaxHeight());

    boolean requireSkyLight;
    Object o = getData().getOrDefault(JumpAdjustorKeys.requireSkyLight, false);
    if (o instanceof Boolean) {
      requireSkyLight = (Boolean) o;
    } else requireSkyLight = Boolean.parseBoolean(o.toString());

    int oldY = minY;

    // enforce valid inputs
    step = Math.max(step, 1);
    step = Math.min(step, (maxY - minY) / 8);

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

      for (int i = minY; i < maxY; i++) {
        if (chunk.isAir(x, i, z)) continue;
        if (!chunk.isSafe(x, i, z, unsafeBlocks)) {
          minY = i;
          break;
        }
      }

      for (int it_len = step; it_len > 2; it_len = it_len / 2) {
        for (int i = minY; i < maxY; i += it_len) {
          int skylight = (!requireSkyLight || (i + 1) > columnSkyFloor) ? 15 : 0;
          if (chunk.isAir(x, i, z)
              && chunk.isAir(x, i + 1, z)
              && skylight > 7
              && chunk.isSafe(x, i + 1, z, unsafeBlocks)) {
            minY = oldY;
            maxY = i;
            break;
          }
          if (i > maxY - it_len) return false;
          oldY = i;
        }
      }

      // Phase 3 scans inclusively up to maxY. Phase 2 narrows maxY DOWN onto
      // the lowest air-pair it samples, which on simple terrain is exactly the
      // valid standing Y (feet just above the ground). An exclusive `i < maxY`
      // bound never re-tested that converged candidate, so a live, generated
      // land chunk whose only landing aligned with the Phase-2 step grid was
      // silently dropped at cold->hot promotion ([PROMOTE_DIAG] vert.adjust=null).
      // Cap the head read (i + 1) at the world top so the inclusive bound never
      // reads past getMaxHeight().
      int scanTop = Math.min(maxY, chunk.getWorld().getMaxHeight() - 2);
      for (int i = minY; i <= scanTop; i++) {
        int skylight = (!requireSkyLight || (i + 1) > columnSkyFloor) ? 15 : 0;
        if (!chunk.isAir(x, i - 1, z)
            && chunk.isAir(x, i, z)
            && chunk.isAir(x, i + 1, z)
            && skylight > 7
            && chunk.isSafe(x, i + 1, z, unsafeBlocks)
            && chunk.isSafe(x, i, z, unsafeBlocks)
            && isGroundSafe(chunk, x, i, z, unsafeBlocks, platformDepth)) {
          output.setWorldName(chunk.getWorld().name());
          output.setXZ(globalX, globalZ);
          output.setY(i);
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Re-validates a single, caller-specified column using the same ground +
   * headroom + safety predicate as Phase 3 of {@link #adjust(RTPChunk,
   * MutableRTPCoords)}. The cold-&gt;hot promotion path uses this to re-verify
   * the exact column where a candidate was originally selected, rather than
   * re-sampling the fixed {@code testCoords} sub-columns (which silently
   * dropped good land locations whose only safe column was not one of the
   * sampled few - the {@code [PROMOTE_DIAG] vert.adjust=null} drop).
   */
  @Override
  public @Nullable RTPCoords adjustColumn(@NotNull RTPChunk chunk, int localX, int localZ) {
    if (chunk == null) throw new NullPointerException("Chunk cannot be null");

    int maxY = getNumber(JumpAdjustorKeys.maxY, 256L).intValue();
    int minY = getNumber(JumpAdjustorKeys.minY, 0L).intValue();
    maxY = Math.min(maxY, chunk.getWorld().getMaxHeight());

    boolean requireSkyLight;
    Object o = getData().getOrDefault(JumpAdjustorKeys.requireSkyLight, false);
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

    // Inclusive scan to maxY with a one-cell headroom cap (mirrors Phase 3).
    int scanTop = Math.min(maxY, chunk.getWorld().getMaxHeight() - 2);
    for (int i = minY; i <= scanTop; i++) {
      int skylight = (!requireSkyLight || (i + 1) > columnSkyFloor) ? 15 : 0;
      if (!chunk.isAir(x, i - 1, z)
          && chunk.isAir(x, i, z)
          && chunk.isAir(x, i + 1, z)
          && skylight > 7
          && chunk.isSafe(x, i + 1, z, unsafeBlocks)
          && chunk.isSafe(x, i, z, unsafeBlocks)
          && isGroundSafe(chunk, x, i, z, unsafeBlocks, platformDepth)) {
        return new MutableRTPCoords(chunk.getWorld().name(), globalX, i, globalZ).toImmutable();
      }
    }
    return null;
  }

  /**
   * Probe-backed fast path mirroring {@link #adjust(RTPChunk, MutableRTPCoords)}.
   * Returns {@code null} (fall back to live {@link #adjust}) when the probe window
   * doesn't cover {@code [minY, maxY]} or no acceptable Y was found.
   */
  @Override
  public @Nullable RTPCoords adjustFromProbe(
      @NotNull ChunkColumnProbe probe, @NotNull String worldName) {
    return adjustFromProbeWithReason(probe, worldName).picked();
  }

  /**
   * Typed probe-path entry point mirroring {@code LinearAdjustor}'s —
   * {@link #adjustFromProbe} delegates here so the two paths share a single
   * source of truth.
   */
  @Override
  public @NotNull AdjustResult adjustFromProbeWithReason(
      @NotNull ChunkColumnProbe probe, @NotNull String worldName) {
    int maxY = getNumber(JumpAdjustorKeys.maxY, 256L).intValue();
    int minY = getNumber(JumpAdjustorKeys.minY, 0L).intValue();

    boolean requireSkyLight;
    Object o = getData().getOrDefault(JumpAdjustorKeys.requireSkyLight, false);
    if (o instanceof Boolean) {
      requireSkyLight = (Boolean) o;
    } else requireSkyLight = Boolean.parseBoolean(o.toString());

    // Probe window must cover the adjustor's [minY, maxY] with one-cell headroom
    // for the y-1 / y+1 safety probes.
    if (probe.minY() > minY - 1 || probe.maxY() < maxY) return AdjustResult.WINDOW_REJECT;

    SafetySnapshot snap = readSafetySnapshot();

    // Multi-column probe sweep. Mirrors the live adjust(RTPChunk,...) path,
    // which iterates testCoords (5 sub-columns within the chunk). Aligning
    // the probe and live column sets makes a probe SCAN_MISS authoritative
    // (no acceptable Y exists at any of the five live-path columns either),
    // which lets ScanTask short-circuit instead of paying a full chunk load.
    //
    // Sky-light gating uses a per-column block-data scan (computeColumnSkyFloor):
    // the highest non-air Y on the column is the sky floor; any y+1 strictly
    // above it has open sky by construction. This replaces the heightmap +
    // isLightOn fallbacks (stored sky-light data is unreliable on unticked /
    // freshly generated chunks) and makes the probe verdict authoritative
    // enough that ScanTask can skip the Pass-2 vert.adjust re-scan.
    for (int j = 0; j < testCoords.size(); j++) {
      List<Integer> xz = testCoords.get(j);
      int lx = xz.get(0);
      int lz = xz.get(1);
      int columnSkyFloor = requireSkyLight
          ? computeColumnSkyFloor(probe, lx, lz)
          : Integer.MIN_VALUE;
      for (int y = minY; y < maxY; y++) {
        if (!acceptProbeY(probe, lx, lz, y, requireSkyLight, columnSkyFloor, snap)) continue;
        int globalX = (probe.chunkX() << 4) + lx;
        int globalZ = (probe.chunkZ() << 4) + lz;
        return AdjustResult.ok(new MutableRTPCoords(worldName, globalX, y, globalZ).toImmutable());
      }
    }
    return AdjustResult.SCAN_MISS_REJECT;
  }

  /**
   * Per-column sky-floor derived purely from block data (palette identifiers).
   * The sky floor is the highest non-air Y on column {@code (lx, lz)} within
   * the probe window; any {@code y+1} strictly above the floor has unobstructed
   * sky access by construction (no overhang, no cave ceiling, no structure
   * roof — every cell above the floor is air).
   *
   * <p>This replaces the previous {@code isLightOn} / stored-sky-light /
   * heightmap-proxy chain. Stored sky-light nibbles and heightmaps are
   * unreliable on unticked or freshly-generated chunks (the server may not
   * have relit them yet, the {@code MOTION_BLOCKING_NO_LEAVES} heightmap may
   * be absent on older formats, and player edits invalidate both). Walking
   * the palette directly gives a deterministic, always-available answer.
   *
   * <p>Returns {@link Integer#MIN_VALUE} when the column is fully air across
   * the probe window — caller treats every Y as sky-lit (no foothold means
   * the {@code y-1} solid check will reject anyway).
   *
   * <p>Walks {@code probe.maxY()} down to {@code probe.minY()} rather than
   * the adjustor's narrower {@code [minY, maxY]} because anything above the
   * adjustor's range still blocks sky access to candidates below it.
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
   * Canonicalise a material token to the upper-case, namespace-stripped form
   * used by {@code Material.name()} and by the reconciled {@code AnvilColumnProbeAdapter}
   * output. Matches {@code PaletteIdentifierNormalizer.normalize(...)}.
   */
  private static String canonicaliseMaterialToken(String raw) {
    String trimmed = raw.trim();
    int colon = trimmed.indexOf(':');
    String local = (colon >= 0) ? trimmed.substring(colon + 1) : trimmed;
    return local.toUpperCase(Locale.ROOT);
  }

  /**
   * Accept {@code y} on column {@code (lx, lz)} iff: y-1 is solid (not vanilla
   * air and not in {@code airBlocks}); y and y+1 are passable (vanilla air or
   * in {@code airBlocks}); none of the three cells are in {@code unsafeBlocks}
   * (unsafe wins on conflict); and when {@code requireSkyLight}, {@code y+1
   * > columnSkyFloor} (block-data derived; stored sky-light/heightmaps ignored
   * as unreliable on unticked chunks). Mirrors live {@code chunk.isSafe(...)}
   * tolerance for walkable non-air (flowers, tall grass, snow, ...).
   */
  private static boolean acceptProbeY(ChunkColumnProbe probe, int lx, int lz, int y,
                                      boolean requireSkyLight, int columnSkyFloor,
                                      SafetySnapshot snap) {
    Set<String> unsafeBlocks = snap.unsafeBlocks();
    Set<String> airBlocks = snap.airBlocks();
    String below = probe.blockAt(lx, lz, y - 1);
    String at = probe.blockAt(lx, lz, y);
    String above = probe.blockAt(lx, lz, y + 1);
    if (below == null || at == null || above == null) return false;
    // Ground cell must be non-passable.
    if (probe.isAirAt(lx, lz, y - 1)
        || airBlocks.contains(canonicaliseMaterialToken(below))) return false;
    // Body and head cells must be passable (vanilla air OR configured air-block).
    if (!probe.isAirAt(lx, lz, y)
        && !airBlocks.contains(canonicaliseMaterialToken(at))) return false;
    if (!probe.isAirAt(lx, lz, y + 1)
        && !airBlocks.contains(canonicaliseMaterialToken(above))) return false;
    // Unsafe set wins over air set on conflicts.
    String atCanon = canonicaliseMaterialToken(at);
    String aboveCanon = canonicaliseMaterialToken(above);
    if (unsafeBlocks.contains(atCanon)) return false;
    if (unsafeBlocks.contains(aboveCanon)) return false;
    // Sweep down [1..platformDepth] for hidden fluids/unsafe materials under
    // a thin solid crust (sand-over-water, magma-under-cobblestone, ...).
    int depth = Math.max(1, snap.platformDepth());
    for (int d = 1; d <= depth; d++) {
      String b = probe.blockAt(lx, lz, y - d);
      if (b == null) return false;
      if (unsafeBlocks.contains(canonicaliseMaterialToken(b))) return false;
    }
    // Sky-light gate (block-data only): accept iff y+1 is strictly above the
    // highest non-air block on this column (computeColumnSkyFloor). This
    // ignores stored sky-light nibbles and heightmap data — both are unreliable
    // on unticked / freshly-generated chunks. Block data is always present and
    // deterministic.
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
    return getNumber(JumpAdjustorKeys.minY, 0).intValue();
  }

  @Override
  public int maxY() {
    return getNumber(JumpAdjustorKeys.maxY, 256).intValue();
  }
}
