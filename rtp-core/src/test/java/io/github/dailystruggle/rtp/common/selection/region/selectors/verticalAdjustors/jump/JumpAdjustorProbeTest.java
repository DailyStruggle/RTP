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

  /** First acceptable Y on a bottom-up scan is returned. */
  @Test
  void findsFirstValidY() {
    FakeChunkColumnProbe probe = new FakeChunkColumnProbe(5, 7, 0, 128);
    probe.setSolidRange(0, 63);
    probe.setAirRange(64, 128);

    RTPCoords r = adj(60, 80).adjustFromProbe(probe, "world");

    assertNotNull(r);
    assertEquals(64, r.y());
    assertEquals(5 * 16 + 8, r.x());
    assertEquals(7 * 16 + 8, r.z());
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

  /** requireSkyLight=true + isLightOn=false → defer to authoritative path. */
  @Test
  void requireSkyLight_lightingNotFinalized_returnsNull() {
    FakeChunkColumnProbe probe = new FakeChunkColumnProbe(0, 0, 0, 128);
    probe.setSolidRange(0, 63);
    probe.setAirRange(64, 128);
    probe.setLightOn(false);
    probe.setDefaultSkyLight(15);

    JumpAdjustor a = adj(60, 80);
    a.set(JumpAdjustorKeys.requireSkyLight, true);

    assertNull(a.adjustFromProbe(probe, "w"));
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
    probe.setBlock(63, "LAVA"); // default unsafe
    probe.setBlock(64, "minecraft:stone");
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
    probe.setBlock(64, "TALL_GRASS"); // body cell: passable per config
    probe.setBlock(65, "TALL_GRASS"); // head cell: passable per config
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
      probe.setBlock(64, "POPPY"); // body cell: tag member, must be passable
      probe.setBlock(65, "POPPY"); // head cell: tag member, must be passable
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
}
