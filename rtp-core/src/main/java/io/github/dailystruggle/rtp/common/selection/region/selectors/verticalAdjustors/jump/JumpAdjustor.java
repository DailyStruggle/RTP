package io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.jump;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.rtp.api.world.ChunkColumnProbe;
import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
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
   * canonicalised {@link SafetyKeys#unsafeBlocks} and {@link SafetyKeys#airBlocks}
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
          safety.getConfigValue(SafetyKeys.unsafeBlocks, new ArrayList<>()), tagSnapshot, unsafe);
      expandTokens(
          safety.getConfigValue(SafetyKeys.airBlocks, new ArrayList<>()), tagSnapshot, air);
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

    // subParameter removed
    // subParameter removed
    // subParameter removed
    // subParameter removed
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

      for (int i = minY; i < maxY; i++) {
        if (chunk.isAir(x, i, z)) continue;
        if (!chunk.isSafe(x, i, z, unsafeBlocks)) {
          minY = i;
          break;
        }
      }

      for (int it_len = step; it_len > 2; it_len = it_len / 2) {
        for (int i = minY; i < maxY; i += it_len) {
          int skylight = 15;
          if (requireSkyLight) skylight = chunk.getSkyLight(x, i + 1, z);
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

      for (int i = minY; i < maxY; i++) {
        int skylight = 15;
        if (requireSkyLight) skylight = chunk.getSkyLight(x, i + 1, z);
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
   * Probe-backed fast path mirroring {@link #adjust(RTPChunk, MutableRTPCoords)} on the
   * center column of the supplied probe (local {@code x=8, z=8}).
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
   *       {@code y+1 &gt; heightmapTopY} as sky-access. The verification step
   *       exists because the heightmap alone is not authoritative —
   *       caves/ravines/structures and player edits routinely contradict it;
   *       we re-check the column blocks the probe already carries before
   *       trusting the proxy. Light data is validated separately at the
   *       unkept→kept chunk-load handoff (live vert fallback path).</li>
   *   <li>{@code isLightOn} false + heightmap absent or contradicted →
   *       return {@code null} and let the live vert method handle it.</li>
   * </ol>
   *
   * <p>The step-halving binary-descent of the legacy path is collapsed to a linear
   * bottom-up scan on the probe path: the legacy optimization exists to reduce
   * live {@code chunk.isAir} calls across a 5-column sweep, and the probe already
   * answers those queries in O(1) from the decoded palette, so amortising with a
   * step-halved traversal no longer pays.</p>
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

    // Decide sky-light source: trusted nibble array (skyLightAt), or a verified
    // heightmap proxy, or defer to the live vert method.
    int heightmapSkyFloor = computeHeightmapSkyFloor(probe, requireSkyLight);
    if (heightmapSkyFloor == Integer.MAX_VALUE) return AdjustResult.LIGHT_GATE_REJECT;

    SafetySnapshot snap = readSafetySnapshot();

    // Multi-column probe sweep. Mirrors the live adjust(RTPChunk,...) path,
    // which iterates testCoords (5 sub-columns within the chunk). Aligning
    // the probe and live column sets makes a probe SCAN_MISS authoritative
    // (no acceptable Y exists at any of the five live-path columns either),
    // which lets ScanTask short-circuit instead of paying a full chunk load.
    for (int j = 0; j < testCoords.size(); j++) {
      List<Integer> xz = testCoords.get(j);
      int lx = xz.get(0);
      int lz = xz.get(1);
      for (int y = minY; y < maxY; y++) {
        if (!acceptProbeY(probe, lx, lz, y, requireSkyLight, heightmapSkyFloor, snap)) continue;
        int globalX = (probe.chunkX() << 4) + lx;
        int globalZ = (probe.chunkZ() << 4) + lz;
        return AdjustResult.ok(new MutableRTPCoords(worldName, globalX, y, globalZ).toImmutable());
      }
    }
    return AdjustResult.SCAN_MISS_REJECT;
  }

  /**
   * Pick a sky-light source for {@link #acceptProbeY}. See
   * {@code LinearAdjustor.computeHeightmapSkyFloor} for the full contract — this
   * method is a literal mirror to keep the two adjustors' probe paths aligned.
   *
   * <p>Return contract:
   * <ul>
   *   <li>{@link Integer#MIN_VALUE} — sky-light not required, or {@code
   *       isLightOn} is true; use {@link ChunkColumnProbe#skyLightAt(int)}.</li>
   *   <li>the heightmap top Y — sky-light required, {@code isLightOn} false,
   *       heightmap present, and verified open from {@code top+1} through
   *       {@code maxY}. Callers may then treat any {@code y+1 &gt; floor} as
   *       fully sky-lit.</li>
   *   <li>{@link Integer#MAX_VALUE} — sky-light required, {@code isLightOn}
   *       false, heightmap absent or contradicted; the caller must return
   *       {@code null} and defer to the live vert method.</li>
   * </ul>
   */
  private static int computeHeightmapSkyFloor(
      ChunkColumnProbe probe, boolean requireSkyLight) {
    if (!requireSkyLight) return Integer.MIN_VALUE;
    if (probe.isLightOn()) return Integer.MIN_VALUE;
    java.util.OptionalInt h = probe.heightmapTopY();
    if (h.isEmpty()) return Integer.MAX_VALUE;
    int top = h.getAsInt();
    // Verify heightmap honesty across the probe's full Y window: anything
    // between top+1 and probe.maxY() must be air. A non-air block anywhere
    // above the reported top means the heightmap is lying about openness
    // (cave roof, structure ceiling, overhang, player edit, or older-version
    // chunk where noise maps no longer correlate). Walk to probe.maxY()
    // rather than the adjustor's narrower maxY because skylight propagates
    // from above the adjustor scan range. Defer to the live vert path on
    // failure, which will relight the chunk on load.
    int verifyTo = probe.maxY();
    for (int y = top + 1; y <= verifyTo; y++) {
      if (!probe.isAirAt(y)) return Integer.MAX_VALUE;
    }
    return top;
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
   * Decide whether {@code y} is an acceptable standing coordinate on the probe at
   * chunk-local column {@code (lx, lz)}.
   *
   * <p>A Y is accepted iff:
   * <ul>
   *   <li>{@code y-1} is solid (not vanilla air AND not a member of the snapshot's
   *       {@code airBlocks}) — i.e. there is something to stand on;</li>
   *   <li>{@code y} and {@code y+1} are passable — vanilla air OR a member of
   *       the snapshot's {@code airBlocks} (tall grass, flowers, snow layer,
   *       torches, leaves, ...);</li>
   *   <li>none of the three cells are in the snapshot's {@code unsafeBlocks} —
   *       {@code unsafeBlocks} wins over {@code airBlocks} on conflicts;</li>
   *   <li>when {@code requireSkyLight} is true, sky-access is satisfied either via
   *       {@code skyLightAt(lx, lz, y+1) > 7} (when the probe's lighting is trusted)
   *       or via {@code y+1 > heightmapSkyFloor} when the caller supplied a
   *       verified heightmap proxy (see {@link #computeHeightmapSkyFloor}).</li>
   * </ul>
   *
   * <p>Multi-column variant: parameterised on chunk-local {@code (lx, lz)} so the
   * probe path mirrors the live {@code adjust(RTPChunk, MutableRTPCoords)} sweep
   * over {@code testCoords}. Off-center reads are O(1) palette-index lookups via
   * {@link ChunkColumnProbe#blockAt(int, int, int)}; the heightmap-derived
   * sky-light floor still applies chunk-wide because the probe retains a single
   * (center-column) heightmap and that is the only sky-light proxy used when
   * {@code isLightOn} is false.</p>
   *
   * <p>Using the snapshot's {@code airBlocks} here mirrors the live-path {@code chunk.isSafe(...)}
   * tolerance, so the anvil probe fast path no longer rejects Y candidates whose body
   * or head space contains walkable non-air blocks (flowers, tall grass, etc.). Prior
   * to this wiring, the strict {@link ChunkColumnProbe#isAirAt(int)} check was rejecting
   * every such chunk and routing it to the full-load path, showing up as the residual
   * {@code adjustNull} tail on the ScanTask concurrency gauge.</p>
   */
  private static boolean acceptProbeY(ChunkColumnProbe probe, int lx, int lz, int y,
                                      boolean requireSkyLight, int heightmapSkyFloor,
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
    // Sky-light gate: when heightmapSkyFloor != MIN_VALUE the probe's nibble
    // array is stale (isLightOn=false) but the heightmap was verified honest in
    // computeHeightmapSkyFloor, so any y+1 strictly above the reported top has
    // sky-light = 15 by definition. Otherwise fall back to skyLightAt(lx,lz,y+1)
    // — trusted when isLightOn=true, otherwise the benign "absent tag means
    // fully lit" default of 15.
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
    return getNumber(JumpAdjustorKeys.minY, 0).intValue();
  }

  @Override
  public int maxY() {
    return getNumber(JumpAdjustorKeys.maxY, 256).intValue();
  }
}
