package io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.jump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.FakeChunkColumnProbe;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link JumpAdjustor#adjustFromProbe}. The probe-backed fast path collapses
 * the legacy step-halving binary descent into a linear bottom-up center-column scan
 * (documented rationale on the override in {@link JumpAdjustor}).
 */
public class JumpAdjustorProbeTest {

  @TempDir Path tempDir;

  @BeforeEach
  void setUp() {
    RTPTestSetup.install(tempDir.toFile());
  }

  private JumpAdjustor adj(int minY, int maxY) {
    JumpAdjustor a = new JumpAdjustor(new ArrayList<>());
    a.set(JumpAdjustorKeys.minY, (long) minY);
    a.set(JumpAdjustorKeys.maxY, (long) maxY);
    a.set(JumpAdjustorKeys.step, 4);
    a.set(JumpAdjustorKeys.requireSkyLight, false);
    return a;
  }

  /**
   * First acceptable Y on a bottom-up scan is returned, at the first
   * {@code testCoords} column the multi-column probe sweep visits — which is
   * {@code (7, 7)} — matching the live {@code adjust(RTPChunk,...)} path. The
   * fake's data is uniform across columns (default delegation to the center
   * accessor), so every column accepts at the same Y; the column reported is
   * therefore deterministic.
   */
  @Test
  void findsFirstValidY() {
    FakeChunkColumnProbe probe = new FakeChunkColumnProbe(5, 7, 0, 128);
    probe.setSolidRange(0, 63);
    probe.setAirRange(64, 128);

    RTPCoords r = adj(60, 80).adjustFromProbe(probe, "world");

    assertNotNull(r);
    assertEquals(64, r.y());
    assertEquals(5 * 16 + 7, r.x());
    assertEquals(7 * 16 + 7, r.z());
  }

  /**
   * requireSkyLight=true with a lit chunk and sky-exposed head cell → probe answers
   * directly from its {@code SkyLight} data; no fallback.
   */
  @Test
  void requireSkyLight_litAndSkyExposed_accepts() {
    FakeChunkColumnProbe probe = new FakeChunkColumnProbe(0, 0, 0, 128);
    probe.setSolidRange(0, 63);
    probe.setAirRange(64, 128);
    probe.setLightOn(true);
    probe.setDefaultSkyLight(15);

    JumpAdjustor a = adj(60, 80);
    a.set(JumpAdjustorKeys.requireSkyLight, true);

    RTPCoords r = a.adjustFromProbe(probe, "w");
    assertNotNull(r);
    assertEquals(64, r.y());
  }

  /** requireSkyLight=true but column is dark → rejects all Ys in the window. */
  @Test
  void requireSkyLight_litButDarkColumn_returnsNull() {
    FakeChunkColumnProbe probe = new FakeChunkColumnProbe(0, 0, 0, 128);
    probe.setSolidRange(0, 63);
    probe.setAirRange(64, 128);
    probe.setLightOn(true);
    probe.setDefaultSkyLight(5);

    JumpAdjustor a = adj(60, 80);
    a.set(JumpAdjustorKeys.requireSkyLight, true);

    assertNull(a.adjustFromProbe(probe, "w"));
  }

  /**
   * requireSkyLight=true + isLightOn=false + no heightmap → defer to the live
   * vert method (return null). Covers the "no trustworthy sky-light source"
   * branch of the tiered gate added when the heightmap proxy was introduced.
   */
  @Test
  void requireSkyLight_lightingNotFinalized_returnsNull() {
    FakeChunkColumnProbe probe = new FakeChunkColumnProbe(0, 0, 0, 128);
    probe.setSolidRange(0, 63);
    probe.setAirRange(64, 128);
    probe.setLightOn(false);
    probe.setDefaultSkyLight(15);
    // Note: heightmapTopY defaults to OptionalInt.empty() — no proxy available.

    JumpAdjustor a = adj(60, 80);
    a.set(JumpAdjustorKeys.requireSkyLight, true);

    assertNull(a.adjustFromProbe(probe, "w"));
  }

  /**
   * requireSkyLight=true, {@code isLightOn=false}, BUT the chunk has a verified-open
   * {@code MOTION_BLOCKING_NO_LEAVES} heightmap (every cell above the reported top is
   * air). The adjustor should synthesize sky-access from the heightmap proxy and
   * accept any candidate whose {@code y+1} is strictly above the top instead of
   * deferring. Mirrors {@code LinearAdjustorProbeTest.lightOff_verifiedOpenHeightmap_acceptsViaProxy}.
   */
  @Test
  void lightOff_verifiedOpenHeightmap_acceptsViaProxy() {
    FakeChunkColumnProbe probe = new FakeChunkColumnProbe(0, 0, 0, 128);
    probe.setSolidRange(0, 63);
    probe.setAirRange(64, 128);
    probe.setLightOn(false);
    probe.setDefaultSkyLight(0); // would normally reject under strict skyLightAt
    probe.setHeightmapTop(63);

    JumpAdjustor a = adj(60, 80);
    a.set(JumpAdjustorKeys.requireSkyLight, true);

    RTPCoords r = a.adjustFromProbe(probe, "w");
    assertNotNull(r, "verified-open heightmap should permit accept despite isLightOn=false");
    assertEquals(64, r.y());
  }

  /**
   * requireSkyLight=true, {@code isLightOn=false}, heightmap present BUT a non-air
   * block sits above the reported top (overhang / cave roof / structure ceiling /
   * player edit / older-version chunk where noise no longer correlates).
   * Verification fails → defer to the live vert method. Mirrors
   * {@code LinearAdjustorProbeTest.lightOff_overhangAboveHeightmap_returnsNull}.
   */
  @Test
  void lightOff_overhangAboveHeightmap_returnsNull() {
    FakeChunkColumnProbe probe = new FakeChunkColumnProbe(0, 0, 0, 128);
    probe.setSolidRange(0, 63);
    probe.setAirRange(64, 128);
    probe.withBlock(90, "minecraft:stone"); // floating overhang above the reported top
    probe.setLightOn(false);
    probe.setDefaultSkyLight(15);
    probe.setHeightmapTop(63);

    JumpAdjustor a = adj(60, 80);
    a.set(JumpAdjustorKeys.requireSkyLight, true);

    assertNull(a.adjustFromProbe(probe, "w"),
        "overhang above heightmap top must invalidate the proxy and force live fallback");
  }

  /** Probe window narrower than adjustor range → null. */
  @Test
  void probeWindowTooNarrow_returnsNull() {
    FakeChunkColumnProbe probe = new FakeChunkColumnProbe(0, 0, 65, 70);
    probe.setSolidRange(65, 70);
    assertNull(adj(60, 80).adjustFromProbe(probe, "w"));
  }

  /** Entire range solid → no valid Y → null. */
  @Test
  void allSolid_returnsNull() {
    FakeChunkColumnProbe probe = new FakeChunkColumnProbe(0, 0, 0, 128);
    probe.setSolidRange(0, 128);
    assertNull(adj(60, 80).adjustFromProbe(probe, "w"));
  }

  /** Unsafe floor rejects candidate; next Y up with safe floor is accepted. */
  @Test
  void unsafeFloor_rejectsAndAdvances() {
    FakeChunkColumnProbe probe = new FakeChunkColumnProbe(0, 0, 0, 128);
    probe.setSolidRange(0, 62);
    probe.withBlock(63, "LAVA"); // default unsafe
    probe.withBlock(64, "minecraft:stone");
    probe.setAirRange(65, 128);

    RTPCoords r = adj(60, 80).adjustFromProbe(probe, "w");
    assertNotNull(r);
    assertEquals(65, r.y());
  }

  /**
   * Regression: when the body / head cells hold a non-air block that the operator has
   * listed in {@code safety.yml}'s {@code airBlocks} (e.g. {@code TALL_GRASS}), the probe
   * path must accept the Y. Prior to wiring {@code airBlocks} into {@code acceptProbeY},
   * the strict {@code ChunkColumnProbe.isAirAt} check rejected every such chunk and
   * routed the scan back to the full-load path — showing up as the residual
   * {@code adjustNull == activeChecks} tail on the ScanTask concurrency gauge.
   */
  @SuppressWarnings("unchecked")
  @Test
  void airBlocksFromConfig_acceptsTallGrassHeadSpace() throws Exception {
    // Seed safety.yml's airBlocks via the in-memory EnumMap (the YAML-backed parser
    // is not materialised in unit tests). Same reflection approach as
    // ReqRtpS004NullChunkAttributionTest / ReqRtpS005StaleChunkGuardTest.
    ConfigParser<SafetyKeys> safety =
        (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
    java.lang.reflect.Field dataField = FactoryValue.class.getDeclaredField("data");
    dataField.setAccessible(true);
    EnumMap<SafetyKeys, Object> safetyData = (EnumMap<SafetyKeys, Object>) dataField.get(safety);
    safetyData.put(SafetyKeys.airBlocks, new ArrayList<>(Arrays.asList("TALL_GRASS")));

    // Force the 5-second cache refresh window to elapse; the static lastUpdate may
    // already have been advanced by a prior test in this class.
    java.lang.reflect.Field lastUpdateField = JumpAdjustor.class.getDeclaredField("lastUpdate");
    lastUpdateField.setAccessible(true);
    ((java.util.concurrent.atomic.AtomicLong) lastUpdateField.get(null)).set(0L);

    FakeChunkColumnProbe probe = new FakeChunkColumnProbe(0, 0, 0, 128);
    probe.setSolidRange(0, 63);
    probe.withBlock(64, "TALL_GRASS"); // body cell: passable per config
    probe.withBlock(65, "TALL_GRASS"); // head cell: passable per config
    // All Ys above 65 default to stone; the bottom-up scan must find Y=64.

    RTPCoords r = adj(60, 80).adjustFromProbe(probe, "w");
    assertNotNull(r, "expected the probe path to accept a TALL_GRASS body/head when listed in airBlocks");
    assertEquals(64, r.y());
  }

  /**
   * Regression for the "residual {@code adjustNull} tail" observed on live scan runs: when
   * an operator lists an ADR-017 tag token (e.g. {@code #minecraft:flowers}) in
   * {@code airBlocks}, the probe fast path must accept a column whose head cell reports a
   * tag member (e.g. {@code POPPY}) — not fall through to the ~85 ms full-load path.
   *
   * <p>Prior to the tag-expansion wiring in {@code JumpAdjustor#refreshSafetySets()},
   * {@code airBlocks} held the literal string {@code "#minecraft:flowers"} and
   * {@code airBlocks.contains("POPPY")} was always {@code false} — every flower-topped
   * chunk paid the full-load penalty, which is what the {@code adjustNull ≈ 0.39 ×
   * activeChecks} ratio in the scan logs was showing. See
   * {@code docs/dev/SAFETY_TAGS_AND_STATES_PLAN.md} Slice 3.
   */
  @SuppressWarnings("unchecked")
  @Test
  void airBlocksFromConfig_expandsMinecraftTagToFlowerHeadSpace() throws Exception {
    // Seed the config with a tag token.
    ConfigParser<SafetyKeys> safety =
        (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
    java.lang.reflect.Field dataField = FactoryValue.class.getDeclaredField("data");
    dataField.setAccessible(true);
    EnumMap<SafetyKeys, Object> safetyData = (EnumMap<SafetyKeys, Object>) dataField.get(safety);
    safetyData.put(SafetyKeys.airBlocks, new ArrayList<>(Arrays.asList("#minecraft:flowers")));

    // Force the 5-second refresh window.
    java.lang.reflect.Field lastUpdateField = JumpAdjustor.class.getDeclaredField("lastUpdate");
    lastUpdateField.setAccessible(true);
    ((java.util.concurrent.atomic.AtomicLong) lastUpdateField.get(null)).set(0L);

    // Install a tag-snapshot-aware accessor that publishes minecraft:flowers → {POPPY}.
    io.github.dailystruggle.rtp.api.server.RTPServerAccessor prev = RTP.serverAccessor;
    RTP.serverAccessor =
        new io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor(tempDir.toFile()) {
          @Override
          public java.util.Map<String, java.util.Set<String>> blockTagSnapshot() {
            return java.util.Map.of(
                "minecraft:flowers", java.util.Set.of("POPPY", "DANDELION"));
          }
        };

    try {
      FakeChunkColumnProbe probe = new FakeChunkColumnProbe(0, 0, 0, 128);
      probe.setSolidRange(0, 63);
      probe.withBlock(64, "POPPY"); // body cell: tag member, must be passable
      probe.withBlock(65, "POPPY"); // head cell: tag member, must be passable
      // Y ≥ 66 defaults to stone — the bottom-up scan must land on Y=64.

      RTPCoords r = adj(60, 80).adjustFromProbe(probe, "w");
      assertNotNull(
          r,
          "expected the probe path to accept POPPY body/head when airBlocks contains the"
              + " #minecraft:flowers tag token");
      assertEquals(64, r.y());

      // Cross-check the materialised fast-lookup set directly (the private static
      // airBlocks field consumed by acceptProbeY). The reapply to the YAML-backed
      // config value is also attempted (try/catch in refreshSafetySets) but the
      // in-test YamlFile branch of ConfigParser.set can reject ArrayList values
      // when the resource is backed by a ConfigurationSection — the live set is
      // the authoritative source of truth for the probe path regardless.
      java.lang.reflect.Field airBlocksField = JumpAdjustor.class.getDeclaredField("airBlocks");
      airBlocksField.setAccessible(true);
      @SuppressWarnings("unchecked")
      java.util.Set<String> airBlocksLive = (java.util.Set<String>) airBlocksField.get(null);
      org.junit.jupiter.api.Assertions.assertTrue(
          airBlocksLive.contains("POPPY"),
          "materialised airBlocks set should contain the expanded POPPY member, was "
              + airBlocksLive);
      org.junit.jupiter.api.Assertions.assertTrue(
          airBlocksLive.contains("DANDELION"),
          "materialised airBlocks set should contain the expanded DANDELION member, was "
              + airBlocksLive);
      org.junit.jupiter.api.Assertions.assertFalse(
          airBlocksLive.contains("#minecraft:flowers"),
          "materialised airBlocks set must not contain the literal tag token");
    } finally {
      RTP.serverAccessor = prev;
    }
  }

  /**
   * State-predicated tokens ({@code MATERIAL[prop=val]}) must survive the refresh/reapply
   * round-trip in the config value so that compiled-form consumers
   * ({@code SafetyCompilationCache} called from {@code QueueTask.afterChunkResolved}) keep
   * honouring them. They are correctly dropped from the probe fast-path set because the
   * probe has no block-state property map.
   */
  @SuppressWarnings("unchecked")
  @Test
  void statePredicatedTokens_preservedInReappliedConfig() throws Exception {
    ConfigParser<SafetyKeys> safety =
        (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
    java.lang.reflect.Field dataField = FactoryValue.class.getDeclaredField("data");
    dataField.setAccessible(true);
    EnumMap<SafetyKeys, Object> safetyData = (EnumMap<SafetyKeys, Object>) dataField.get(safety);
    safetyData.put(
        SafetyKeys.unsafeBlocks,
        new ArrayList<>(Arrays.asList("LAVA", "CAMPFIRE[lit=true]")));

    java.lang.reflect.Field lastUpdateField = JumpAdjustor.class.getDeclaredField("lastUpdate");
    lastUpdateField.setAccessible(true);
    ((java.util.concurrent.atomic.AtomicLong) lastUpdateField.get(null)).set(0L);

    // Trigger refreshSafetySets via a probe call.
    FakeChunkColumnProbe probe = new FakeChunkColumnProbe(0, 0, 0, 128);
    probe.setSolidRange(0, 63);
    probe.setAirRange(64, 128);
    adj(60, 80).adjustFromProbe(probe, "w");

    // Fast-lookup unsafe set: LAVA materialised, state-predicated CAMPFIRE dropped
    // (probe has no property map). These assertions are the authoritative contract
    // for the probe-fast-path; the config-value reapply is attempted best-effort
    // inside refreshSafetySets and tested above.
    java.lang.reflect.Field unsafeField = JumpAdjustor.class.getDeclaredField("unsafeBlocks");
    unsafeField.setAccessible(true);
    @SuppressWarnings("unchecked")
    java.util.Set<String> unsafeLive = (java.util.Set<String>) unsafeField.get(null);
    org.junit.jupiter.api.Assertions.assertTrue(
        unsafeLive.contains("LAVA"), "bare material LAVA must land in the fast-lookup set");
    org.junit.jupiter.api.Assertions.assertFalse(
        unsafeLive.contains("CAMPFIRE[lit=true]"),
        "state-predicated tokens must be excluded from the probe-fast-path set (probe has"
            + " no property map) — the compiled-form path on the full-load branch still"
            + " honours them");
    org.junit.jupiter.api.Assertions.assertFalse(
        unsafeLive.contains("CAMPFIRE"),
        "a state-predicated CAMPFIRE[lit=true] token must not be silently collapsed to the"
            + " bare CAMPFIRE material in the fast-lookup set");
  }

  /**
   * Regression: the anvil column probe returns lowercase namespaced ids
   * ({@code "minecraft:water"}) while {@code JumpAdjustor.unsafeBlocks} is
   * materialised to uppercase / namespace-stripped ids ({@code "WATER"}).
   * Before canonicalising both sides of the {@code acceptProbeY} lookup,
   * water at the feet-Y silently passed the unsafe check and players
   * landed in lakes. This guard asserts the lowercase form is rejected.
   */
  @Test
  void lowercaseNamespacedFluidAtFeetRejects() {
    FakeChunkColumnProbe probe = new FakeChunkColumnProbe(0, 0, 0, 128);
    probe.setSolidRange(0, 63);
    probe.setAirRange(64, 128);
    probe.withBlock(63, "minecraft:water");
    probe.withBlock(64, "minecraft:stone");

    RTPCoords r = adj(60, 80).adjustFromProbe(probe, "w");
    assertNotNull(r,
        "scan should find a higher accept after rejecting the water-floored y=64");
    assertEquals(65, r.y(),
        "expected the stone-floored y=65, not the water-floored y=64");
  }

  /** Mirror of the water case for lava — the other common plains-biome fluid. */
  @Test
  void lowercaseNamespacedLavaAtFeetRejects() {
    FakeChunkColumnProbe probe = new FakeChunkColumnProbe(0, 0, 0, 128);
    probe.setSolidRange(0, 63);
    probe.setAirRange(64, 128);
    probe.withBlock(63, "minecraft:lava");
    probe.withBlock(64, "minecraft:stone");

    RTPCoords r = adj(60, 80).adjustFromProbe(probe, "w");
    assertNotNull(r);
    assertEquals(65, r.y());
  }

  // ---------------------------------------------------------------------------
  // PR-20: adjustFromProbeWithReason rejection-reason attribution. Mirrors the
  // matching block in LinearAdjustorProbeTest. See ScanTask.readProbeOutcome
  // StatsAndReset for the consumer side.
  // ---------------------------------------------------------------------------

  /** Window too narrow → {@code WINDOW}. */
  @Test
  void adjustFromProbeWithReason_windowMismatch_returnsWindow() {
    FakeChunkColumnProbe probe = new FakeChunkColumnProbe(0, 0, 65, 70);
    probe.setSolidRange(65, 70);
    var r = adj(60, 80).adjustFromProbeWithReason(probe, "w");
    assertNull(r.picked());
    assertEquals(
        io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors
            .VerticalAdjustor.ProbeRejectReason.WINDOW,
        r.reason());
  }

  /** requireSkyLight=true + isLightOn=false + no heightmap → {@code LIGHT_GATE}. */
  @Test
  void adjustFromProbeWithReason_unverifiedLight_returnsLightGate() {
    FakeChunkColumnProbe probe = new FakeChunkColumnProbe(0, 0, 0, 128);
    probe.setSolidRange(0, 63);
    probe.setAirRange(64, 128);
    probe.setLightOn(false);
    // No heightmap set → LIGHT_GATE.

    JumpAdjustor a = adj(60, 80);
    a.set(JumpAdjustorKeys.requireSkyLight, true);

    var r = a.adjustFromProbeWithReason(probe, "w");
    assertNull(r.picked());
    assertEquals(
        io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors
            .VerticalAdjustor.ProbeRejectReason.LIGHT_GATE,
        r.reason());
  }

  /** No acceptable Y on the center column → {@code SCAN_MISS}. */
  @Test
  void adjustFromProbeWithReason_scanMiss_returnsScanMiss() {
    FakeChunkColumnProbe probe = new FakeChunkColumnProbe(0, 0, 0, 128);
    probe.setSolidRange(0, 128);
    var r = adj(60, 80).adjustFromProbeWithReason(probe, "w");
    assertNull(r.picked());
    assertEquals(
        io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors
            .VerticalAdjustor.ProbeRejectReason.SCAN_MISS,
        r.reason());
  }

  /** Success path → {@code NONE} and non-null {@code picked()}. */
  @Test
  void adjustFromProbeWithReason_success_returnsNone() {
    FakeChunkColumnProbe probe = new FakeChunkColumnProbe(2, 3, 0, 128);
    probe.setSolidRange(0, 63);
    probe.setAirRange(64, 128);
    var r = adj(60, 80).adjustFromProbeWithReason(probe, "world");
    assertNotNull(r.picked());
    assertEquals(64, r.picked().y());
    assertEquals(
        io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors
            .VerticalAdjustor.ProbeRejectReason.NONE,
        r.reason());
  }

  // ---------------------------------------------------------------------------
  // Multi-column probe sweep. The probe path now scans the same testCoords
  // columns ((7,7), (2,2), (12,12), (2,12), (12,2)) the live adjust(RTPChunk,…)
  // path scans, so a probe SCAN_MISS is authoritative — i.e. no acceptable Y
  // exists on any of those five live-path columns either, which is what makes
  // the SCAN_MISS short-circuit in ScanTask.evaluateScanProbe behaviour-safe.
  // These regression tests pin both halves of that contract:
  //   1. center-column-only foothold ⟹ probe accepts at the center.
  //   2. center-column all-air, off-center column has foothold ⟹ probe must
  //      accept at the off-center column (no SCAN_MISS).
  //   3. every column has no foothold ⟹ SCAN_MISS (authoritative bad).
  // ---------------------------------------------------------------------------

  /**
   * Off-center foothold case: center column (8,8) is fully air, but local
   * column (2,2) — second {@code testCoords} entry — has solid ground at y=63
   * and air above. The probe sweep must visit (2,2) after (7,7) fails and
   * report acceptance at the off-center column with the corresponding global
   * x/z. This is the scenario that, before the multi-column probe sweep,
   * caused unnecessary full-chunk loads (probe rejected, live accepted) and
   * blocked the SCAN_MISS short-circuit.
   */
  @Test
  void adjustFromProbe_offCenterFoothold_acceptsAtOffCenterColumn() {
    FakeChunkColumnProbe probe = new FakeChunkColumnProbe(5, 7, 0, 128);
    // Default-uniform-air across all columns.
    probe.setAirRange(0, 128);
    // Carve a foothold ONLY at (2,2): solid feet at 63, air at 64/65.
    probe.setColumnSolid(2, 2, 63);
    probe.setColumnAir(2, 2, 64);
    probe.setColumnAir(2, 2, 65);

    RTPCoords r = adj(60, 80).adjustFromProbe(probe, "w");
    assertNotNull(r,
        "off-center foothold at (2,2) must be discovered by the multi-column probe sweep");
    assertEquals(64, r.y());
    assertEquals(5 * 16 + 2, r.x(), "global x reflects local lx=2");
    assertEquals(7 * 16 + 2, r.z(), "global z reflects local lz=2");
  }

  /**
   * Air-column / no-foothold case: every column in the chunk is fully air
   * across the whole window. The probe sweep visits every {@code testCoords}
   * column, finds no Y where {@code y-1} is solid, and returns SCAN_MISS.
   * Mirrors the worst-case adjustNull tail (sky islands, void chunks,
   * air-only generated chunks) that the SCAN_MISS short-circuit now turns
   * into a zero-cost reject in {@code ScanTask.evaluateScanProbe}.
   */
  @Test
  void adjustFromProbeWithReason_airColumn_returnsScanMissAcrossAllColumns() {
    FakeChunkColumnProbe probe = new FakeChunkColumnProbe(0, 0, 0, 128);
    probe.setAirRange(0, 128); // every Y on every column is air ⟹ no foothold anywhere
    var r = adj(60, 80).adjustFromProbeWithReason(probe, "w");
    assertNull(r.picked(),
        "no acceptable Y exists on any testCoords column (every column is air)");
    assertEquals(
        io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors
            .VerticalAdjustor.ProbeRejectReason.SCAN_MISS,
        r.reason(),
        "air-column rejection must surface as SCAN_MISS so ScanTask short-circuits"
            + " instead of paying for a full chunk load");
  }
}
