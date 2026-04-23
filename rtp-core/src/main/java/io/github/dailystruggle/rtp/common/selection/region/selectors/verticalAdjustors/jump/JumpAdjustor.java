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
            && chunk.isSafe(x, i - 1, z, unsafeBlocks)) {
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
   * <p>Returns {@code null} (fall back to full parse) when:
   * <ul>
   *   <li>the probe's window does not cover the adjustor's {@code [minY, maxY]},</li>
   *   <li>{@code requireSkyLight} is true <em>and</em> the probe reports
   *       {@link ChunkColumnProbe#isLightOn()} is false — the on-disk sky-light
   *       nibble array is stale, defer to the authoritative path,</li>
   *   <li>no acceptable Y was found on the center column.</li>
   * </ul>
   *
   * <p>When {@code requireSkyLight} is true and {@code isLightOn} is true, the
   * scan enforces the same {@code skyLight > 7} threshold as {@link #adjust}
   * using {@link ChunkColumnProbe#skyLightAt(int)}.
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
    int maxY = getNumber(JumpAdjustorKeys.maxY, 256L).intValue();
    int minY = getNumber(JumpAdjustorKeys.minY, 0L).intValue();

    boolean requireSkyLight;
    Object o = getData().getOrDefault(JumpAdjustorKeys.requireSkyLight, false);
    if (o instanceof Boolean) {
      requireSkyLight = (Boolean) o;
    } else requireSkyLight = Boolean.parseBoolean(o.toString());

    // When sky-light is required but the chunk's lighting isn't finalized, on-disk
    // SkyLight is stale — defer to the authoritative path which forces a lighting pass.
    if (requireSkyLight && !probe.isLightOn()) return null;

    // Probe window must cover the adjustor's [minY, maxY] with one-cell headroom
    // for the y-1 / y+1 safety probes.
    if (probe.minY() > minY - 1 || probe.maxY() < maxY) return null;

    refreshSafetySets();

    // Linear bottom-up center-column scan; same acceptance predicate as the legacy
    // final pass in adjust(...).
    for (int y = minY; y < maxY; y++) {
      if (!acceptProbeY(probe, y, requireSkyLight)) continue;
      int globalX = (probe.chunkX() << 4) + 8;
      int globalZ = (probe.chunkZ() << 4) + 8;
      return new MutableRTPCoords(worldName, globalX, y, globalZ).toImmutable();
    }
    return null;
  }

  /**
   * Refresh the cached {@link #unsafeBlocks} and {@link #airBlocks} sets on the same
   * 5-second cadence. Both sets are sourced from {@code safety.yml} via
   * {@link SafetyKeys#unsafeBlocks} / {@link SafetyKeys#airBlocks} and are consumed by
   * both {@link #adjust(RTPChunk, MutableRTPCoords)} (legacy full-load path) and
   * {@link #acceptProbeY(ChunkColumnProbe, int, boolean)} (anvil probe fast path).
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
   * Decide whether {@code y} is an acceptable standing coordinate on the probe's center column.
   *
   * <p>A Y is accepted iff:
   * <ul>
   *   <li>{@code y-1} is solid (not vanilla air AND not a member of {@link #airBlocks}) —
   *       i.e. there is something to stand on;</li>
   *   <li>{@code y} and {@code y+1} are passable — vanilla air OR a member of
   *       {@link #airBlocks} (tall grass, flowers, snow layer, torches, ...);</li>
   *   <li>none of the three cells are in {@link #unsafeBlocks} — {@code unsafeBlocks}
   *       wins over {@code airBlocks} on conflicts;</li>
   *   <li>when {@code requireSkyLight} is true, the sky-light at {@code y+1} exceeds 7.</li>
   * </ul>
   *
   * <p>Using {@link #airBlocks} here mirrors the live-path {@code chunk.isSafe(...)}
   * tolerance, so the anvil probe fast path no longer rejects Y candidates whose body
   * or head space contains walkable non-air blocks (flowers, tall grass, etc.). Prior
   * to this wiring, the strict {@link ChunkColumnProbe#isAirAt(int)} check was rejecting
   * every such chunk and routing it to the full-load path, showing up as the residual
   * {@code adjustNull} tail on the ScanTask concurrency gauge.</p>
   */
  private static boolean acceptProbeY(ChunkColumnProbe probe, int y, boolean requireSkyLight) {
    String below = probe.blockAt(y - 1);
    String at = probe.blockAt(y);
    String above = probe.blockAt(y + 1);
    if (below == null || at == null || above == null) return false;
    // Ground cell must be non-passable.
    if (probe.isAirAt(y - 1) || airBlocks.contains(below)) return false;
    // Body and head cells must be passable (vanilla air OR configured air-block).
    if (!probe.isAirAt(y) && !airBlocks.contains(at)) return false;
    if (!probe.isAirAt(y + 1) && !airBlocks.contains(above)) return false;
    // Unsafe set wins over air set on conflicts.
    if (unsafeBlocks.contains(below)) return false;
    if (unsafeBlocks.contains(at)) return false;
    if (unsafeBlocks.contains(above)) return false;
    // Sky-light gate matches adjust()'s `skylight > 7` at y+1. The probe returns 15
    // when sky-light wasn't requested at parse time (vanilla "absent tag == fully lit").
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
    return getNumber(JumpAdjustorKeys.minY, 0).intValue();
  }

  @Override
  public int maxY() {
    return getNumber(JumpAdjustorKeys.maxY, 256).intValue();
  }
}
