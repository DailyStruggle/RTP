package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.selection.region.LocationGenerator;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.table.HybridHazardTable;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Random;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class MemoryShapeHazardMirrorTest {

  @BeforeAll
  static void beforeAll() {
    MockRTPServerAccessor accessor =
        new MockRTPServerAccessor(new File("target/test-data-hazard-mirror"));
    RTP.serverAccessor = accessor;
    io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = accessor;
  }

  private static class MirrorTestShape extends MemoryShape<GenericMemoryShapeParams> {
    private final long range;

    public MirrorTestShape(long range) {
      super(GenericMemoryShapeParams.class, "MIRROR_TEST", createDefaultData());
      this.range = range;
    }

    private static EnumMap<GenericMemoryShapeParams, Object> createDefaultData() {
      EnumMap<GenericMemoryShapeParams, Object> data =
          new EnumMap<>(GenericMemoryShapeParams.class);
      data.put(GenericMemoryShapeParams.mode, "ACCUMULATE");
      data.put(GenericMemoryShapeParams.radius, 100L);
      data.put(GenericMemoryShapeParams.centerRadius, 0L);
      data.put(GenericMemoryShapeParams.centerX, 0L);
      data.put(GenericMemoryShapeParams.centerZ, 0L);
      data.put(GenericMemoryShapeParams.weight, 1.0);
      data.put(GenericMemoryShapeParams.uniquePlacements, false);
      data.put(GenericMemoryShapeParams.expand, false);
      return data;
    }

    @Override
    public long getRange() {
      return range;
    }

    @Override
    public long xzToLocation(long x, long z) {
      return 0;
    }

    @Override
    public long xzToLocation(MutableRTPCoords coords) {
      return 0;
    }

    @Override
    public int[] locationToXZ(long location) {
      return new int[] {0, 0};
    }

    @Override
    public void locationToXZ(long location, MutableRTPCoords output) {}

    @Override
    public java.util.Map<String, io.github.dailystruggle.commandsapi.common.CommandParameter>
        getParameters() {
      return null;
    }

    @Override
    public java.util.Collection<String> keys() {
      return java.util.Collections.emptyList();
    }

    @Override
    public int[] select() {
      return new int[] {0, 0};
    }

    @Override
    public long rand() {
      return 0;
    }

    @Override
    public boolean contains(int x, int z) {
      return true;
    }

    @Override
    public void flushAndRebuild(long spatialResolution) {
      super.flushAndRebuild(spatialResolution);
    }
  }

  @Test
  @DisplayName("HybridHazardTable mirror parity test across mutation sites and 5000 sampled keys")
  void testHazardMirrorParity() {
    long range = 100000L;
    MirrorTestShape shape = new MirrorTestShape(range);
    shape.setSpatialResolution(4L);

    HybridHazardTable mirror = shape.getHazardMirror();
    assertNotNull(mirror, "Hazard mirror must be initialized when range fits int");

    // 1. Initial clear
    shape.clear();
    mirror = shape.getHazardMirror();
    assertNotNull(mirror);
    assertEquals(0, mirror.countBad());
    assertEquals(0L, shape.getEffectiveBadCount());

    // 2. Mix of addBadLocation with causes & TTLs
    shape.addBadLocation(100L, LocationGenerator.FailTypes.biome, 0L);
    shape.addBadLocation(101L, LocationGenerator.FailTypes.biome, 0L);
    shape.addBadLocation(102L, LocationGenerator.FailTypes.biome, 0L);
    // Gap that will be coalesced/bridged with spatialResolution=2 (gap is (104 - (102+1)) = 1 <= 2)
    shape.addBadLocation(104L, LocationGenerator.FailTypes.biome, 0L);

    // Dynamic runs
    shape.addBadLocation(500L, LocationGenerator.FailTypes.safetyExternal, 1000L);
    shape.addBadLocation(501L, LocationGenerator.FailTypes.safetyExternal, 1000L);
    shape.addBadLocation(503L, LocationGenerator.FailTypes.safetyExternal, 1000L);

    // Isolated runs
    shape.addBadLocation(2000L);
    shape.addBadLocation(3500L);

    // 3. Flush and rebuild with spatialResolution=2
    shape.flushAndRebuild(2L);
    mirror = shape.getHazardMirror();
    assertNotNull(mirror);

    // Check bridging: between 102 and 104, key 103 is bridged by coalescing
    assertTrue(shape.isKnownBad(103L), "103 should be bridged in shape");
    assertTrue(mirror.isBad(103L), "103 should be marked bad in mirror after flushAndRebuild");

    // 4. Test probation restore via load and checkAndRestoreFromProbation
    String file = "mirrorProbationBin";
    String world = "mirrorWorld";
    long now = Instant.now().getEpochSecond();
    File dir = new File("target/test-data-hazard-mirror/database/regionData");
    dir.mkdirs();
    File binFile = new File(dir, file + ".bin");

    try (FileOutputStream fos = new FileOutputStream(binFile)) {
      ByteBuffer buf = ByteBuffer.allocate(4096).order(ByteOrder.BIG_ENDIAN);
      buf.putInt(0x52545031); // BIN_MAGIC
      buf.putInt(3); // BIN_VERSION 3 (modern unified format)
      byte[] curveBytes = "SPIRAL".getBytes(StandardCharsets.UTF_8);
      buf.putInt(curveBytes.length);
      buf.put(curveBytes); // curve name (default SPIRAL)
      buf.putInt(1); // P == 1 (default point edge)
      buf.putInt(8); // keyWidth == 8 (long keys/deltas)
      byte[] worldBytes = world.getBytes(StandardCharsets.UTF_8);
      buf.putInt(worldBytes.length);
      buf.put(worldBytes);
      buf.putLong(1L); // scanStride
      buf.putInt(2); // 2 runs
      // Run 1: key 10000, len 10, expired but within probation window
      buf.putLong(10000L);
      buf.putLong(10L);
      buf.put((byte) LocationGenerator.FailTypes.safetyExternal.ordinal());
      buf.putLong(now - 10L);
      // Run 2: key 20000, len 10, active
      buf.putLong(20000L);
      buf.putLong(10L);
      buf.put((byte) LocationGenerator.FailTypes.safetyExternal.ordinal());
      buf.putLong(now + 1000L);
      buf.putInt(0); // union: 0 biome names
      buf.putInt(0); // union: 0 biome runs
      buf.flip();
      byte[] data = new byte[buf.remaining()];
      buf.get(data);
      fos.write(data);
    } catch (Exception e) {
      fail(e);
    }

    shape.load(file, world).join();
    mirror = shape.getHazardMirror();
    assertNotNull(mirror);

    // Active run [20000..20009] must be bad in both
    for (long k = 20000L; k < 20010L; k++) {
      assertTrue(shape.isKnownBad(k));
      assertTrue(mirror.isBad(k));
    }
    // Probation run [10000..10009] is not in active cache
    for (long k = 10000L; k < 10010L; k++) {
      assertFalse(shape.isKnownBad(k));
      assertFalse(mirror.isBad(k));
    }

    // Now restore from probation
    boolean restored = shape.checkAndRestoreFromProbation(10005L);
    assertTrue(restored, "Probation run must be restored");
    shape.flushAndRebuild(1L);

    // After flushAndRebuild, [10000..10009] is back active and bad in both
    for (long k = 10000L; k < 10010L; k++) {
      assertTrue(shape.isKnownBad(k));
      assertTrue(shape.getHazardMirror().isBad(k));
    }

    // 5. Add more random points and test 5000 sampled keys
    Random rand = new Random(42L);
    for (int i = 0; i < 500; i++) {
      long pt = rand.nextInt((int) range);
      shape.addBadLocation(pt);
    }
    shape.flushAndRebuild(4L);
    mirror = shape.getHazardMirror();
    assertNotNull(mirror);

    // Verify 5000 sampled keys across range, including gaps and known bad
    int sampleCount = 5000;
    for (int i = 0; i < sampleCount; i++) {
      long k;
      if (i < 500) {
        // Sample around known runs
        k = 100L + (i % 20);
      } else if (i < 1000) {
        k = 10000L + (i % 25);
      } else if (i < 1500) {
        k = 20000L + (i % 25);
      } else {
        k = rand.nextInt((int) range);
      }
      boolean expected = shape.isKnownBad(k);
      boolean actual = mirror.isBad(k);
      assertEquals(
          expected,
          actual,
          "Parity mismatch at key " + k + " (expected isKnownBad=" + expected + ", mirror=" + actual + ")");
    }

    // Assert mirror count of bad equals getEffectiveBadCount() within bridging tolerance
    // Note: getEffectiveBadCount() reflects total bad cells accounted by prefix sums.
    // In HybridHazardTable, countBad() returns the count of distinct bad bits set.
    // They are identical when all bad run cells are within [0, range).
    long effectiveBad = shape.getEffectiveBadCount();
    long mirrorCount = mirror.countBad();
    assertEquals(
        effectiveBad,
        mirrorCount,
        "Mirror count (" + mirrorCount + ") must equal effectiveBadCount (" + effectiveBad + ")");

    // Step 2 Accumulate Parity Check:
    // Assert that mirror.resolveAccumulate(target) produces the EXACT same location
    // as the array A -> A+N resolution for all sampled good targets.
    long totalGood = mirror.totalGood();
    long expectedTotalGood = range - effectiveBad;
    assertEquals(expectedTotalGood, totalGood, "Total good count must match range - effectiveBad");

    long[] keys = shape.badKeysSnapshot();
    long[] sums = shape.badPrefixSumsSnapshot();
    for (int i = 0; i < 1000; i++) {
      long target = (i < 500) ? i : (long) (rand.nextDouble() * totalGood);
      long mirrorLoc = mirror.resolveAccumulate(target);

      // Compute array location via A -> A+N fixed point
      long currentBadSum = 0L;
      while (true) {
        int index = java.util.Arrays.binarySearch(keys, target + currentBadSum);
        if (index < 0) {
          index = -index - 1;
        } else {
          index = index + 1;
        }
        if (index > sums.length) index = sums.length;
        long newBadSum = (index > 0) ? sums[index - 1] : 0L;
        if (newBadSum == currentBadSum) break;
        currentBadSum = newBadSum;
      }
      long arrayLoc = target + currentBadSum;

      assertEquals(
          arrayLoc,
          mirrorLoc,
          "Accumulate target " + target + " mismatch: mirror=" + mirrorLoc + " array=" + arrayLoc);
      assertFalse(mirror.isBad(mirrorLoc), "Resolved slot must not be bad: " + mirrorLoc);
    }
  }

  @Test
  @DisplayName("Hazard mirror lazily skips when range exceeds Integer.MAX_VALUE")
  void testLargeRangeSkipsMirror() {
    long largeRange = (long) Integer.MAX_VALUE + 1000L;
    MirrorTestShape largeShape = new MirrorTestShape(largeRange);
    assertNull(largeShape.getHazardMirror(), "Large range (> Integer.MAX_VALUE) must leave mirror null");
  }
}
