package io.github.dailystruggle.rtp.common.selection.region.selectors.memory;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.selection.region.GlobalRegionVerifiers;
import io.github.dailystruggle.rtp.common.selection.region.LocationGenerator;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class Adr079CauseTtlAndStagedExpirationTest {

  @BeforeAll
  static void beforeAll() {
    MockRTPServerAccessor accessor =
        new MockRTPServerAccessor(new java.io.File("target/test-data"));
    RTP.serverAccessor = accessor;
    io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = accessor;
  }

  @Test
  @DisplayName("ADR-079: Selective coalescing - static and dynamic runs do not coalesce")
  void testStaticAndDynamicDoNotCoalesce() {
    Circle circle = new Circle();
    // Index 10: static cause (biome, TTL = infinite <= 0)
    circle.addBadLocation(10L, LocationGenerator.FailTypes.biome, 0L);
    // Index 11: dynamic cause (safetyExternal, TTL = 100s > 0)
    circle.addBadLocation(11L, LocationGenerator.FailTypes.safetyExternal, 100L);

    // Trigger off-tick rebuild
    circle.flushAndRebuild(1L);

    long[] keys = circle.badKeysSnapshot();
    byte[] causes = circle.badCausesSnapshot();
    long[] expiries = circle.badExpiriesSnapshot();

    assertEquals(2, keys.length, "Static and dynamic adjacent runs must not coalesce");
    assertEquals(10L, keys[0]);
    assertEquals((byte) LocationGenerator.FailTypes.biome.ordinal(), causes[0]);
    assertEquals(0L, expiries[0]);

    assertEquals(11L, keys[1]);
    assertEquals((byte) LocationGenerator.FailTypes.safetyExternal.ordinal(), causes[1]);
    assertTrue(expiries[1] > 0L);
  }

  @Test
  @DisplayName("ADR-079: Dynamic coalescing - merges with max(TTL) and first-cause-wins")
  void testDynamicRunsCoalesceWithMaxTtl() {
    Circle circle = new Circle();
    // Index 20: dynamic cause A with 50s TTL
    circle.addBadLocation(20L, LocationGenerator.FailTypes.safetyExternal, 50L);
    // Index 21: dynamic cause B with 200s TTL
    circle.addBadLocation(21L, LocationGenerator.FailTypes.uniquePlacement, 200L);

    circle.flushAndRebuild(1L);

    long[] keys = circle.badKeysSnapshot();
    byte[] causes = circle.badCausesSnapshot();
    long[] expiries = circle.badExpiriesSnapshot();

    assertEquals(1, keys.length, "Adjacent dynamic runs must coalesce");
    assertEquals(20L, keys[0]);
    assertEquals((byte) LocationGenerator.FailTypes.safetyExternal.ordinal(), causes[0], "First-cause-wins");
    long now = java.time.Instant.now().getEpochSecond();
    assertTrue(expiries[0] >= now + 190L, "Merged run must inherit max TTL");
  }


  @Test
  @DisplayName("ADR-079: Staged probation - transition from active to probation and restoration")
  void testProbationTransitionAndRestoration() {
    String file = "probationTestBin";
    String world = "probationWorld";
    long now = Instant.now().getEpochSecond();

    // Create a BIN_VERSION 3 file with:
    // Run 1: key 100, length 5, cause safetyExternal, exp = now - 10 (expired, but within 14d probation)
    // Run 2: key 200, length 5, cause safetyExternal, exp = now - (30 * 86400) (past 2x TTL, evicted)
    // Run 3: key 300, length 5, cause safetyExternal, exp = now + 1000 (still active)
    File dir = new File("target/test-data/database/regionData");
    dir.mkdirs();
    File binFile = new File(dir, file + ".bin");

    try (FileOutputStream fos = new FileOutputStream(binFile)) {
      ByteBuffer buf = ByteBuffer.allocate(4096).order(ByteOrder.BIG_ENDIAN);
      buf.putInt(0x52545031); // BIN_MAGIC
      buf.putInt(3);          // BIN_VERSION 3
      byte[] worldBytes = world.getBytes(StandardCharsets.UTF_8);
      buf.putInt(worldBytes.length);
      buf.put(worldBytes);
      buf.putLong(1L);        // scanStride

      buf.putInt(3);          // 3 bad runs
      // Run 1: Phase 1/2 probation
      buf.putLong(100L);
      buf.putLong(5L);
      buf.put((byte) LocationGenerator.FailTypes.safetyExternal.ordinal());
      buf.putLong(now - 10L);

      // Run 2: Phase 3 evicted
      buf.putLong(200L);
      buf.putLong(5L);
      buf.put((byte) LocationGenerator.FailTypes.safetyExternal.ordinal());
      buf.putLong(now - (30L * 86400L));

      // Run 3: Active
      buf.putLong(300L);
      buf.putLong(5L);
      buf.put((byte) LocationGenerator.FailTypes.safetyExternal.ordinal());
      buf.putLong(now + 1000L);

      buf.putInt(0); // 0 biomes

      buf.flip();
      byte[] data = new byte[buf.remaining()];
      buf.get(data);
      fos.write(data);
    } catch (Exception e) {
      fail(e);
    }

    Circle circle = new Circle();
    circle.load(file, world).join();

    // Active tier verification:
    // Only Run 3 (300..304) should be in active bad keys
    long[] activeKeys = circle.badKeysSnapshot();
    assertEquals(1, activeKeys.length, "Only unexpired run should be active");
    assertEquals(300L, activeKeys[0]);
    assertTrue(circle.isKnownBad(300L));
    assertTrue(circle.isKnownBad(304L));

    // Run 1 (100..104) is in probation:
    // It is candidate-eligible (NOT known bad in active cache)
    assertFalse(circle.isKnownBad(102L), "Expired probation coordinates must be candidate-eligible");
    long[] probKeys = circle.probationKeysSnapshot();
    assertEquals(1, probKeys.length, "Run 1 must be partitioned into probation array");
    assertEquals(100L, probKeys[0]);

    // Run 2 (200..204) is evicted:
    // It should not be in active or probation
    assertFalse(circle.isKnownBad(200L));

    // Phase 2: Candidate verification re-hit re-promotes probation interval
    boolean restored = circle.checkAndRestoreFromProbation(102L);
    assertTrue(restored, "Re-hit on 102 must restore probationary interval [100..104]");

    // Trigger rebuild to flush pending restored entries
    circle.flushAndRebuild(1L);

    // Now Run 1 [100..104] must be back in active cache!
    assertTrue(circle.isKnownBad(100L));
    assertTrue(circle.isKnownBad(104L));
    assertEquals(2, circle.badKeysSnapshot().length);
  }

  @Test
  @DisplayName("ADR-079: GlobalRegionVerifiersDetailed records source class attribution")
  void testVerifierSourceClassAttribution() throws Exception {
    GlobalRegionVerifiers.clearGlobalRegionVerifiers();
    try {
      class MockChecker {
        boolean check(RTPCoords coords) {
          return coords.x() > 0;
        }
      }
      MockChecker checker = new MockChecker();
      GlobalRegionVerifiers.addGlobalRegionVerifier(MockChecker.class, checker::check);

      RTPCoords valid = new RTPCoords("world", 10, 64, 10);
      RTPCoords invalid = new RTPCoords("world", -10, 64, 10);

      var passRes = GlobalRegionVerifiers.checkGlobalRegionVerifiersDetailed(valid).get();
      assertTrue(passRes.passed());
      assertNull(passRes.failedVerifierClass());

      var failRes = GlobalRegionVerifiers.checkGlobalRegionVerifiersDetailed(invalid).get();
      assertFalse(failRes.passed());
      assertEquals(MockChecker.class, failRes.failedVerifierClass());
    } finally {
      GlobalRegionVerifiers.clearGlobalRegionVerifiers();
    }
  }

  @Test
  @DisplayName("ADR-079: TtlConfig duration string parsing (all units and combinations)")
  void testTtlConfigParsing() {
    // Basic sentinels
    assertEquals(-1L, TtlConfig.parseDurationSeconds("-1"));
    assertEquals(-1L, TtlConfig.parseDurationSeconds("infinite"));
    assertEquals(-1L, TtlConfig.parseDurationSeconds("permanent"));
    assertEquals(-1L, TtlConfig.parseDurationSeconds(null));
    assertEquals(-1L, TtlConfig.parseDurationSeconds(""));
    assertEquals(-1L, TtlConfig.parseDurationSeconds("invalidString"));

    // Numbers
    assertEquals(3600L, TtlConfig.parseDurationSeconds(3600));
    assertEquals(-1L, TtlConfig.parseDurationSeconds(-100));

    // Single units
    assertEquals(45L, TtlConfig.parseDurationSeconds("45s"));
    assertEquals(30L * 60L, TtlConfig.parseDurationSeconds("30m"));
    assertEquals(2L * 3600L, TtlConfig.parseDurationSeconds("2h"));
    assertEquals(14L * 86400L, TtlConfig.parseDurationSeconds("14d"));
    assertEquals(3L * 7L * 86400L, TtlConfig.parseDurationSeconds("3w"));
    assertEquals(1L, TtlConfig.parseDurationSeconds("20t"));
    assertEquals(2L, TtlConfig.parseDurationSeconds("2000ms"));

    // Combined units claimed in TTL.md
    assertEquals(86400L + 12L * 3600L, TtlConfig.parseDurationSeconds("1d12h"));
    assertEquals(2L * 3600L + 30L * 60L, TtlConfig.parseDurationSeconds("2h30m"));
    assertEquals(86400L + 2L * 3600L + 15L * 60L + 20L, TtlConfig.parseDurationSeconds("1d2h15m20s"));
    assertEquals(11L, TtlConfig.parseDurationSeconds("10s500ms"));
  }

  @Test
  @DisplayName("ADR-079: Default causes table parity in TtlConfig")
  void testTtlConfigDefaultCausesTable() {
    TtlConfig.resetDefaults();

    // Static natural terrain causes (-1 / infinite)
    assertEquals(-1L, TtlConfig.resolveTtlSeconds(LocationGenerator.FailTypes.biome, null));
    assertEquals(-1L, TtlConfig.resolveTtlSeconds(LocationGenerator.FailTypes.worldBorder, null));
    assertEquals(-1L, TtlConfig.resolveTtlSeconds(LocationGenerator.FailTypes.vert, null));
    assertEquals(-1L, TtlConfig.resolveTtlSeconds(LocationGenerator.FailTypes.safety, null));
    assertEquals(-1L, TtlConfig.resolveTtlSeconds(LocationGenerator.FailTypes.prefilterBiome, null));
    assertEquals(-1L, TtlConfig.resolveTtlSeconds(LocationGenerator.FailTypes.prefilterBlock, null));

    // Dynamic player / server state
    assertEquals(30L * 86400L, TtlConfig.resolveTtlSeconds(LocationGenerator.FailTypes.uniquePlacement, null));
    assertEquals(14L * 86400L, TtlConfig.resolveTtlSeconds(LocationGenerator.FailTypes.safetyExternal, null));

    // Per-verifier class override resolution order claimed in TTL.md
    class WorldGuardChecker {}
    class GriefPreventionChecker {}
    class UnlistedChecker {}

    // 1. Unlisted verifier inherits causes.safetyExternal (14d)
    assertEquals(14L * 86400L, TtlConfig.resolveTtlSeconds(LocationGenerator.FailTypes.safetyExternal, UnlistedChecker.class));

    // 2. Custom config with per-verifier overrides
    TtlConfig.load(
        java.util.Map.of("safetyExternal", "14d", "uniquePlacement", "30d"),
        java.util.Map.of("WorldGuardChecker", "-1", "GriefPreventionChecker", "7d"));

    // WorldGuardChecker resolved to -1 (permanent admin area)
    assertEquals(-1L, TtlConfig.resolveTtlSeconds(LocationGenerator.FailTypes.safetyExternal, WorldGuardChecker.class));
    // GriefPreventionChecker resolved to 7d
    assertEquals(7L * 86400L, TtlConfig.resolveTtlSeconds(LocationGenerator.FailTypes.safetyExternal, GriefPreventionChecker.class));
    // Unlisted verifier still falls back to causes.safetyExternal (14d)
    assertEquals(14L * 86400L, TtlConfig.resolveTtlSeconds(LocationGenerator.FailTypes.safetyExternal, UnlistedChecker.class));
  }

  @Test
  @DisplayName("ADR-079: Backward compatibility - loading legacy BIN_VERSION 1 and 2")
  void testLegacyBinVersionCompatibility() {
    String fileV2 = "legacyV2TestBin";
    String world = "legacyWorld";

    File dir = new File("target/test-data/database/regionData");
    dir.mkdirs();
    File binFile = new File(dir, fileV2 + ".bin");

    // Write BIN_VERSION 2 file (17 bytes per run: long key, long delta, byte cause)
    try (FileOutputStream fos = new FileOutputStream(binFile)) {
      ByteBuffer buf = ByteBuffer.allocate(2048).order(ByteOrder.BIG_ENDIAN);
      buf.putInt(0x52545031); // BIN_MAGIC
      buf.putInt(2);          // BIN_VERSION 2
      byte[] worldBytes = world.getBytes(StandardCharsets.UTF_8);
      buf.putInt(worldBytes.length);
      buf.put(worldBytes);
      buf.putLong(1L);        // scanStride

      buf.putInt(2);          // 2 bad runs
      buf.putLong(50L);
      buf.putLong(10L);
      buf.put((byte) LocationGenerator.FailTypes.biome.ordinal());

      buf.putLong(100L);
      buf.putLong(5L);
      buf.put((byte) LocationGenerator.FailTypes.safetyExternal.ordinal());

      buf.putInt(0); // 0 biomes
      buf.flip();
      byte[] data = new byte[buf.remaining()];
      buf.get(data);
      fos.write(data);
    } catch (Exception e) {
      fail(e);
    }

    Circle circle = new Circle();
    circle.load(fileV2, world).join();

    long[] keys = circle.badKeysSnapshot();
    long[] expiries = circle.badExpiriesSnapshot();
    byte[] causes = circle.badCausesSnapshot();

    assertEquals(2, keys.length);
    assertEquals(50L, keys[0]);
    assertEquals(100L, keys[1]);
    assertEquals((byte) LocationGenerator.FailTypes.biome.ordinal(), causes[0]);
    assertEquals((byte) LocationGenerator.FailTypes.safetyExternal.ordinal(), causes[1]);
    // All legacy runs must default to 0L (static / permanent retention)
    assertEquals(0L, expiries[0]);
    assertEquals(0L, expiries[1]);
    assertTrue(circle.isKnownBad(50L));
    assertTrue(circle.isKnownBad(100L));
  }

  @Test
  @DisplayName("ADR-079: BIN_VERSION 3 save and load preserves TTL and causes")
  void testBinVersion3SaveLoad() {
    Circle circle = new Circle();
    circle.addBadLocation(10L, LocationGenerator.FailTypes.biome, 0L);
    circle.addBadLocation(100L, LocationGenerator.FailTypes.safetyExternal, 1000L);
    circle.flushAndRebuild(1L);

    String file = "adr079TestBin";
    String world = "adr079World";
    circle.save(file, world);

    Circle reloaded = new Circle();
    reloaded.load(file, world).join();

    long[] keys = reloaded.badKeysSnapshot();
    byte[] causes = reloaded.badCausesSnapshot();
    long[] expiries = reloaded.badExpiriesSnapshot();

    assertEquals(2, keys.length);
    assertEquals(10L, keys[0]);
    assertEquals(100L, keys[1]);
    assertEquals((byte) LocationGenerator.FailTypes.biome.ordinal(), causes[0]);
    assertEquals((byte) LocationGenerator.FailTypes.safetyExternal.ordinal(), causes[1]);
    assertEquals(0L, expiries[0]);
    assertTrue(expiries[1] > 0L);
  }
}
