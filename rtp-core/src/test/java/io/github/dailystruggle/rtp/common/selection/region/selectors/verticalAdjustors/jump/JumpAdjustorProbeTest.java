package io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.jump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.BlocksKeys;
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
   * {@code testCoords} column the multi-column probe sweep visits - which is
   * {@code (7, 7)} - matching the live {@code adjust(RTPChunk,...)} path. The
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

    JumpAdjustor a = adj(60, 80);
    a.set(JumpAdjustorKeys.requireSkyLight, true);

    RTPCoords r = a.adjustFromProbe(probe, "w");
    assertNotNull(r);
    assertEquals(64, r.y());
  }

  /**
   * requireSkyLight=true with an open column above the foothold - block-data
   * sky-floor accepts regardless of stored sky-light nibble values. The new
   * gate walks the palette top-down and treats any Y above the highest non-air
   * block as fully sky-lit; stored sky-light is ignored because it is
   * unreliable on unticked / freshly-generated chunks.
   */
  @Test
  void requireSkyLight_openColumnAcceptsRegardlessOfStoredSkyLight() {
    FakeChunkColumnProbe probe = new FakeChunkColumnProbe(0, 0, 0, 128);
    probe.setSolidRange(0, 63);
    probe.setAirRange(64, 128);

    JumpAdjustor a = adj(60, 80);
    a.set(JumpAdjustorKeys.requireSkyLight, true);

    RTPCoords r = a.adjustFromProbe(probe, "w");
    assertNotNull(r);
    assertEquals(64, r.y());
  }

  /**
   * requireSkyLight=true + isLightOn=false + no heightmap - the block-data
   * sky-floor still accepts because it derives openness from the palette,
   * not from stored sky-light or heightmap data. This is the headline change:
   * unticked / freshly-generated chunks no longer defer to the live vert path.
   */
  @Test
  void requireSkyLight_lightingNotFinalized_acceptsViaBlockScan() {
    FakeChunkColumnProbe probe = new FakeChunkColumnProbe(0, 0, 0, 128);
    probe.setSolidRange(0, 63);
    probe.setAirRange(64, 128);
    // No heightmap, isLightOn=false - used to be LIGHT_GATE; now block scan wins.

    JumpAdjustor a = adj(60, 80);
    a.set(JumpAdjustorKeys.requireSkyLight, true);

    RTPCoords r = a.adjustFromProbe(probe, "w");
    assertNotNull(r,
        "block-data sky-floor must accept on unticked chunks where stored light is unreliable");
    assertEquals(64, r.y());
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
    probe.setHeightmapTop(63);

    JumpAdjustor a = adj(60, 80);
    a.set(JumpAdjustorKeys.requireSkyLight, true);

    RTPCoords r = a.adjustFromProbe(probe, "w");
    assertNotNull(r, "verified-open heightmap should permit accept despite isLightOn=false");
    assertEquals(64, r.y());
  }

  /**
   * requireSkyLight=true with a non-air block above the foothold (overhang /
   * cave roof / structure ceiling). The block-data sky-floor binds at the
   * overhang Y, so every adjustor candidate at {@code y+1 <= overhang} is
   * rejected by the sky-light gate. With the overhang at y=90 and the
   * adjustor range [60,80), no Y in the window passes - the result is null
   * (SCAN_MISS rather than LIGHT_GATE under the new gate).
   */
  @Test
  void lightOff_overhangAboveColumn_rejectsCandidatesUnderOverhang() {
    FakeChunkColumnProbe probe = new FakeChunkColumnProbe(0, 0, 0, 128);
    probe.setSolidRange(0, 63);
    probe.setAirRange(64, 128);
    probe.withBlock(90, "minecraft:stone"); // overhang above the foothold
    probe.setHeightmapTop(63);

    JumpAdjustor a = adj(60, 80);
    a.set(JumpAdjustorKeys.requireSkyLight, true);

    assertNull(a.adjustFromProbe(probe, "w"),
        "y+1 must be strictly above the column's highest non-air block (overhang at y=90)");
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
   * routed the scan back to the full-load path - showing up as the residual
   * {@code adjustNull == activeChecks} tail on the ScanTask concurrency gauge.
   */
  @SuppressWarnings("unchecked")
  @Test
  void airBlocksFromConfig_acceptsTallGrassHeadSpace() throws Exception {
    // Seed safety.yml's airBlocks via the in-memory EnumMap (the YAML-backed parser
    // is not materialised in unit tests). Same reflection approach as
    // ReqRtpS004NullChunkAttributionTest / ReqRtpS005StaleChunkGuardTest.
    ConfigParser<BlocksKeys> blocks =
        (ConfigParser<BlocksKeys>) RTP.configs.getParser(BlocksKeys.class);
    java.lang.reflect.Field dataField = FactoryValue.class.getDeclaredField("data");
    dataField.setAccessible(true);
    EnumMap<BlocksKeys, Object> blocksData = (EnumMap<BlocksKeys, Object>) dataField.get(blocks);
    blocksData.put(BlocksKeys.airBlocks, new ArrayList<>(Arrays.asList("TALL_GRASS")));

    // No static cache to reset - readSafetySnapshot() reads the parser per call.

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
   * Regression: tag tokens (e.g. {@code #minecraft:flowers}) in {@code airBlocks}
   * must be expanded by the probe fast path to accept tag members without full chunk loads.
   */
  @SuppressWarnings("unchecked")
  @Test
  void airBlocksFromConfig_expandsMinecraftTagToFlowerHeadSpace() throws Exception {
    // Seed the config with a tag token.
    ConfigParser<BlocksKeys> blocks =
        (ConfigParser<BlocksKeys>) RTP.configs.getParser(BlocksKeys.class);
    java.lang.reflect.Field dataField = FactoryValue.class.getDeclaredField("data");
    dataField.setAccessible(true);
    EnumMap<BlocksKeys, Object> blocksData = (EnumMap<BlocksKeys, Object>) dataField.get(blocks);
    blocksData.put(BlocksKeys.airBlocks, new ArrayList<>(Arrays.asList("#minecraft:flowers")));

    // No static cache to reset - readSafetySnapshot() reads the parser per call.

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
      // Y ≥ 66 defaults to stone - the bottom-up scan must land on Y=64.

      RTPCoords r = adj(60, 80).adjustFromProbe(probe, "w");
      assertNotNull(
          r,
          "expected the probe path to accept POPPY body/head when airBlocks contains the"
              + " #minecraft:flowers tag token");
      assertEquals(64, r.y());

      // Behavioural cross-check: the snapshot drops bare DANDELION too (other tag
      // member). A separate column with DANDELION in body/head must also be
      // accepted, proving the tag was expanded and the literal "#minecraft:flowers"
      // token is no longer a fast-lookup entry.
      FakeChunkColumnProbe probe2 = new FakeChunkColumnProbe(0, 0, 0, 128);
      probe2.setSolidRange(0, 63);
      probe2.withBlock(64, "DANDELION");
      probe2.withBlock(65, "DANDELION");
      RTPCoords r2 = adj(60, 80).adjustFromProbe(probe2, "w");
      assertNotNull(
          r2,
          "expected the probe path to also accept DANDELION (sibling tag member) when"
              + " airBlocks contains the #minecraft:flowers tag token");
      assertEquals(64, r2.y());
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
    ConfigParser<BlocksKeys> blocks =
        (ConfigParser<BlocksKeys>) RTP.configs.getParser(BlocksKeys.class);
    java.lang.reflect.Field dataField = FactoryValue.class.getDeclaredField("data");
    dataField.setAccessible(true);
    EnumMap<BlocksKeys, Object> blocksData = (EnumMap<BlocksKeys, Object>) dataField.get(blocks);
    blocksData.put(
        BlocksKeys.unsafeBlocks,
        new ArrayList<>(Arrays.asList("LAVA", "CAMPFIRE[lit=true]")));

    // Behavioural assertion: the probe path drops state-predicated tokens (it has
    // no property map), so a bare CAMPFIRE at the feet must still be accepted by
    // the probe path - only the compiled-form full-load consumer evaluates the
    // state predicate. Conversely a bare LAVA at the feet must be rejected.
    FakeChunkColumnProbe campfireProbe = new FakeChunkColumnProbe(0, 0, 0, 128);
    campfireProbe.setSolidRange(0, 62);
    campfireProbe.withBlock(63, "CAMPFIRE"); // bare; not a state-predicated match
    campfireProbe.setAirRange(64, 128);
    RTPCoords accepted = adj(60, 80).adjustFromProbe(campfireProbe, "w");
    assertNotNull(
        accepted,
        "probe path must accept CAMPFIRE feet when only CAMPFIRE[lit=true] is unsafe"
            + " — state-predicated tokens are dropped from the probe fast-path set");
    assertEquals(64, accepted.y());

    FakeChunkColumnProbe lavaProbe = new FakeChunkColumnProbe(0, 0, 0, 128);
    lavaProbe.setSolidRange(0, 62);
    lavaProbe.withBlock(63, "LAVA"); // bare unsafe - must be rejected
    lavaProbe.withBlock(64, "STONE");
    lavaProbe.setAirRange(65, 128);
    RTPCoords lavaResult = adj(60, 80).adjustFromProbe(lavaProbe, "w");
    assertNotNull(lavaResult, "scan should land at the higher stone-floored y=65");
    assertEquals(
        65,
        lavaResult.y(),
        "bare LAVA token must reject the y=64 candidate; scan continues to y=65");
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

  /** Mirror of the water case for lava - the other common plains-biome fluid. */
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
  // Rejection-reason attribution for adjustFromProbeWithReason. Mirrors the
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

  /**
   * requireSkyLight=true + isLightOn=false + no heightmap. Under the old
   * tiered gate this was a {@code LIGHT_GATE} fallback signal; under the
   * block-data sky-floor it now succeeds (NONE) because openness is derived
   * from the palette directly. The {@code LIGHT_GATE} ProbeRejectReason
   * remains in the enum but is no longer reachable from the adjustor.
   */
  @Test
  void adjustFromProbeWithReason_unverifiedLight_blockScanAccepts() {
    FakeChunkColumnProbe probe = new FakeChunkColumnProbe(0, 0, 0, 128);
    probe.setSolidRange(0, 63);
    probe.setAirRange(64, 128);
    // No heightmap; would have been LIGHT_GATE under the heightmap-proxy gate.

    JumpAdjustor a = adj(60, 80);
    a.set(JumpAdjustorKeys.requireSkyLight, true);

    var r = a.adjustFromProbeWithReason(probe, "w");
    assertNotNull(r.picked());
    assertEquals(64, r.picked().y());
    assertEquals(
        io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors
            .VerticalAdjustor.ProbeRejectReason.NONE,
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

  // Multi-column probe sweep tests: verifies parity with live adjust testCoords columns.

  /**
   * Off-center foothold: (8,8) center is air, local column (2,2) has foothold at y=63.
   * Probe sweep must visit (2,2) and report accepted coordinates.
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
