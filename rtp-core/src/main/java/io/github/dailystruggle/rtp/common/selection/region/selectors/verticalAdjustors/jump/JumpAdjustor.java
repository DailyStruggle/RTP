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
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicLong;
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
  private static final Set<String> unsafeBlocks = new ConcurrentSkipListSet<>();
  /**
   * Cached {@link SafetyKeys#platformDepth} value, refreshed alongside
   * {@link #unsafeBlocks}. Controls how many blocks below the candidate
   * feet-Y the probe-side {@link #acceptProbeY} sweep checks against
   * {@link #unsafeBlocks} — catches fluids hidden under a thin crust of
   * solid blocks (e.g. sand-over-water) that would otherwise pass the
   * single-cell {@code y-1} ground check and place the player on water.
   */
  private static volatile int platformDepth = 1;
  /**
   * Reconciled, materialised set of {@link SafetyKeys#airBlocks} — materials the
   * operator considers passable / non-blocking (tall grass, flowers, torches, snow
   * layer, etc.) in addition to vanilla {@code AIR}/{@code CAVE_AIR}/{@code VOID_AIR}.
   * Refreshed on the same 5-second cadence as {@link #unsafeBlocks}. Treated as an
   * "OR air" predicate in {@link #acceptProbeY} and the center-column scan of
   * {@link #adjust(RTPChunk, MutableRTPCoords)} so that the probe-fast-path verdict
   * matches the legacy {@code chunk.isSafe(...)} tolerance for walkable non-air
   * blocks. ADR-017 {@code #namespace:tag} tokens are expanded through
   * {@code RTP.serverAccessor.blockTagSnapshot()} inside {@link #refreshSafetySets()};
   * {@code MATERIAL[prop=val]} state-predicated tokens are dropped from this set
   * because the anvil probe exposes only palette identifiers without block-state
   * properties (the legacy full-load path still honours them via
   * {@code SafetyCompilationCache}). Executes {@code docs/dev/SAFETY_TAGS_AND_STATES_PLAN.md}
   * Slice 3 for the two {@code JumpAdjustor}-owned safety sets.
   */
  private static final Set<String> airBlocks = new ConcurrentSkipListSet<>();
  private static final AtomicLong lastUpdate = new AtomicLong();

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
  private static boolean isGroundSafe(RTPChunk chunk, int x, int y, int z) {
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

    refreshSafetySets();

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
            && isGroundSafe(chunk, x, i, z)) {
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

    refreshSafetySets();

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
        if (!acceptProbeY(probe, lx, lz, y, requireSkyLight, heightmapSkyFloor)) continue;
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
   * Refresh the cached {@link #unsafeBlocks} and {@link #airBlocks} sets on the same
   * 5-second cadence. Both sets are sourced from {@code safety.yml} via
   * {@link SafetyKeys#unsafeBlocks} / {@link SafetyKeys#airBlocks} and are consumed by
   * both {@link #adjust(RTPChunk, MutableRTPCoords)} (legacy full-load path) and
   * {@link #acceptProbeY(ChunkColumnProbe, int, int, int, boolean, int)} (anvil probe fast path).
   *
   * <p>ADR-017 token grammar is expanded in-place at refresh time:
   * <ul>
   *   <li>{@code MATERIAL} — kept verbatim (upper-cased, namespace-stripped).</li>
   *   <li>{@code #namespace:tag} — expanded to the set of material names
   *       published by {@code RTP.serverAccessor.blockTagSnapshot()} (ADR-017 §4
   *       tag-member bucket). Bare (namespace-less) tag ids default to
   *       {@code minecraft:}.</li>
   *   <li>{@code MATERIAL[prop=val,...]} / {@code #tag[prop=val]} / {@code *[prop=val]}
   *       — state-predicated tokens are <b>dropped</b> from the probe-fast-path sets
   *       because the anvil probe exposes only palette identifiers without the
   *       block-state property map. Legacy {@code chunk.isSafe(...)} on the
   *       full-load path still honours these tokens via the compiled-form path.</li>
   * </ul>
   *
   * <p>The expanded materialised list is reapplied to the in-memory config value
   * via {@code setConfigValue(...)} so downstream consumers reading
   * {@code safety.getConfigValue(SafetyKeys.airBlocks, ...)} / {@code unsafeBlocks}
   * see the pre-expanded set without repeating this work. The reapply is
   * in-memory only — disk persistence is gated on an explicit {@code save()}
   * which this method does not call, so operator-authored tag tokens in
   * {@code safety.yml} are preserved on disk.
   */
  private static void refreshSafetySets() {
    long t = System.currentTimeMillis();
    long dt = t - lastUpdate.get();
    if (dt > 5000 || dt < 0) {
      ConfigParser<SafetyKeys> safety =
          (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);

      Map<String, Set<String>> tagSnapshot = Collections.emptyMap();
      if (RTP.serverAccessor != null) {
        try {
          tagSnapshot = RTP.serverAccessor.blockTagSnapshot();
          if (tagSnapshot == null) tagSnapshot = Collections.emptyMap();
        } catch (Throwable ex) {
          // Tag snapshot is a best-effort hint — do not fail the refresh.
          tagSnapshot = Collections.emptyMap();
        }
      }

      Object unsafeValue = safety.getConfigValue(SafetyKeys.unsafeBlocks, new ArrayList<>());
      List<String> reappliedUnsafe = expandSafetyTokens(unsafeValue, tagSnapshot, unsafeBlocks);

      Object airValue = safety.getConfigValue(SafetyKeys.airBlocks, new ArrayList<>());
      List<String> reappliedAir = expandSafetyTokens(airValue, tagSnapshot, airBlocks);

      platformDepth = Math.max(1,
          safety.getNumber(SafetyKeys.platformDepth, 1).intValue());

      // Reapply the expanded lists to the in-memory config value so downstream
      // consumers (QueueTask, LocationGenerator, SafetyCompilationCache) read the
      // pre-expanded form on their next refresh. State-predicated tokens
      // ({@code MATERIAL[prop=val]}) are preserved verbatim in the reapplied list
      // so the compiled-form path on the full-load branch keeps honouring them;
      // tag tokens ({@code #namespace:tag}) are replaced by their materialised
      // members. The reapply is in-memory only — {@code safety.yml} on disk is
      // not touched unless a later {@code save()} is invoked elsewhere.
      try {
        safety.setConfigValue(SafetyKeys.unsafeBlocks.name(), reappliedUnsafe);
        safety.setConfigValue(SafetyKeys.airBlocks.name(), reappliedAir);
      } catch (Throwable ex) {
        // Non-fatal: the live sets are already updated above. Log and continue.
        RTP.log(
            java.util.logging.Level.FINE,
            "[JumpAdjustor] failed to reapply expanded safety lists to config value: "
                + ex.getClass().getSimpleName() + ": " + ex.getMessage());
      }

      lastUpdate.set(t);
    }
  }

  /**
   * Expand an ADR-017 safety-token list into two sinks:
   *
   * <ul>
   *   <li>{@code fastSink} — the materialised set of bare material names used by
   *       the probe-fast-path {@link #acceptProbeY} and the legacy set-lookup
   *       branch of {@link #adjust(RTPChunk, MutableRTPCoords)}. Populated with
   *       upper-cased, namespace-stripped material names only. State-predicated
   *       tokens are dropped (the probe has no property map); tag tokens are
   *       expanded via {@code tagSnapshot}.</li>
   *   <li>Return value — the reapply-to-config list. Preserves
   *       {@code MATERIAL[prop=val]} state-predicated tokens verbatim so the
   *       compiled-form consumer ({@code SafetyCompilationCache} called from
   *       {@code QueueTask.afterChunkResolved}) keeps honouring them. Tag tokens
   *       are replaced by their materialised members; if a tag has no snapshot
   *       entry (tag registry unavailable / custom operator tag), the original
   *       token is preserved so {@code SafetyCompilationCache} can retry later
   *       with its own expansion path.</li>
   * </ul>
   *
   * <p>The two outputs intentionally share bare-material entries so
   * {@code fastSink} can be read without a second pass.
   */
  private static List<String> expandSafetyTokens(
      Object raw, Map<String, Set<String>> tagSnapshot, Set<String> fastSink) {
    fastSink.clear();
    List<String> reapply = new ArrayList<>();
    if (!(raw instanceof Collection)) return reapply;
    for (Object item : (Collection<?>) raw) {
      if (item == null) continue;
      String token = item.toString().trim();
      if (token.isEmpty()) continue;

      // State-predicated tokens: preserve in reapply list (for compiled-form
      // consumers), drop from fast-lookup set (probe has no property map).
      if (token.indexOf('[') >= 0) {
        reapply.add(token);
        continue;
      }

      if (token.charAt(0) == '#') {
        String tagId = token.substring(1);
        if (tagId.indexOf(':') < 0) tagId = "minecraft:" + tagId;
        tagId = tagId.toLowerCase(Locale.ROOT);
        Set<String> members = tagSnapshot.get(tagId);
        if (members != null && !members.isEmpty()) {
          for (String m : members) {
            if (m == null) continue;
            String canonical = canonicaliseMaterialToken(m);
            if (fastSink.add(canonical)) reapply.add(canonical);
          }
        } else {
          // Tag registry unavailable or tag has no members — preserve the
          // token so SafetyCompilationCache can retry with its own pipeline.
          reapply.add(token);
        }
        continue;
      }

      // Bare MATERIAL token.
      String canonical = canonicaliseMaterialToken(token);
      if (fastSink.add(canonical)) reapply.add(canonical);
    }
    return reapply;
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
   *   <li>{@code y-1} is solid (not vanilla air AND not a member of {@link #airBlocks}) —
   *       i.e. there is something to stand on;</li>
   *   <li>{@code y} and {@code y+1} are passable — vanilla air OR a member of
   *       {@link #airBlocks} (tall grass, flowers, snow layer, torches, leaves, ...);</li>
   *   <li>none of the three cells are in {@link #unsafeBlocks} — {@code unsafeBlocks}
   *       wins over {@code airBlocks} on conflicts;</li>
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
   * <p>Using {@link #airBlocks} here mirrors the live-path {@code chunk.isSafe(...)}
   * tolerance, so the anvil probe fast path no longer rejects Y candidates whose body
   * or head space contains walkable non-air blocks (flowers, tall grass, etc.). Prior
   * to this wiring, the strict {@link ChunkColumnProbe#isAirAt(int)} check was rejecting
   * every such chunk and routing it to the full-load path, showing up as the residual
   * {@code adjustNull} tail on the ScanTask concurrency gauge.</p>
   */
  private static boolean acceptProbeY(ChunkColumnProbe probe, int lx, int lz, int y,
                                      boolean requireSkyLight, int heightmapSkyFloor) {
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
    int depth = Math.max(1, platformDepth);
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
