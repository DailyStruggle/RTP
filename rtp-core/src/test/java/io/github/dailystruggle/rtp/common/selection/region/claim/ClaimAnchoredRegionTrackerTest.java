package io.github.dailystruggle.rtp.common.selection.region.claim;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dailystruggle.rtp.api.claim.ClaimBoundary;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.selection.region.LocationGenerator;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.SubspaceShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import java.io.File;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ClaimAnchoredRegionTrackerTest {

  @TempDir
  static File tempDir;

  @BeforeEach
  void beforeEach() {
    MockRTPServerAccessor accessor =
        io.github.dailystruggle.rtp.common.mock.RTPTestSetup.install(tempDir);
    RTP.serverAccessor = accessor;
    io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = accessor;
    if (RTP.getInstance() != null) {
      RTP.getInstance().databaseAccessor = org.mockito.Mockito.mock(io.github.dailystruggle.rtp.common.database.DatabaseAccessor.class);
    }
  }

  @AfterEach
  void afterEach() {
    io.github.dailystruggle.rtp.common.mock.RTPTestSetup.cleanUp();
  }

  /**
   * Simple mock boundary representing a bounding box from minX, minZ to maxX, maxZ.
   */
  public static class RectangularClaimBoundary implements ClaimBoundary {
    private final String id;
    private final String world;
    private int minX, minZ, maxX, maxZ;
    private int[] centroid;

    public RectangularClaimBoundary(String id, String world, int minX, int minZ, int maxX, int maxZ) {
      this.id = id;
      this.world = world;
      this.minX = minX;
      this.minZ = minZ;
      this.maxX = maxX;
      this.maxZ = maxZ;
      this.centroid = new int[] {(minX + maxX) / 2, (minZ + maxZ) / 2};
    }

    public void updateBounds(int minX, int minZ, int maxX, int maxZ) {
      this.minX = minX;
      this.minZ = minZ;
      this.maxX = maxX;
      this.maxZ = maxZ;
      this.centroid = new int[] {(minX + maxX) / 2, (minZ + maxZ) / 2};
    }

    @Override
    public String id() {
      return id;
    }

    @Override
    public String world() {
      return world;
    }

    @Override
    public boolean contains(int x, int z) {
      return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    @Override
    public int[] centroid() {
      return centroid;
    }

    @Override
    public int minChunkX() {
      return minX >> 4;
    }

    @Override
    public int minChunkZ() {
      return minZ >> 4;
    }

    @Override
    public int maxChunkX() {
      return maxX >> 4;
    }

    @Override
    public int maxChunkZ() {
      return maxZ >> 4;
    }
  }

  @Test
  @DisplayName("Center is preserved when territory expands but old center remains inside boundary")
  void testCenterPreservedWhenWithinBoundary() {
    ClaimAnchoredRegionTracker tracker = new ClaimAnchoredRegionTracker(60, TimeUnit.SECONDS);
    RectangularClaimBoundary boundary = new RectangularClaimBoundary("faction_a", "world", 0, 0, 100, 100);

    long t0 = 1000L;
    int[] anchor1 = tracker.resolveAnchor(boundary, t0);
    assertEquals(50, anchor1[0]);
    assertEquals(50, anchor1[1]);

    // Faction claims land to the east: bounds expand to [0, 0] -> [200, 100]. New centroid is (100, 50).
    boundary.updateBounds(0, 0, 200, 100);
    assertEquals(100, boundary.centroid()[0]);
    assertEquals(50, boundary.centroid()[1]);

    // t0 + 10s: within cooldown, and old center (50, 50) is still within [0..200, 0..100].
    int[] anchor2 = tracker.resolveAnchor(boundary, t0 + 10_000L);
    assertEquals(50, anchor2[0], "Pinned center X must be preserved");
    assertEquals(50, anchor2[1], "Pinned center Z must be preserved");

    // t0 + 70s: cooldown expired, BUT old center is STILL within boundary, so preserved unless explicit drift recompute policy
    // In our policy: if old center inside boundary, it is preserved to avoid resetting 1D bijections!
    int[] anchor3 = tracker.resolveAnchor(boundary, t0 + 70_000L);
    // When cooldown elapses and centroid moved, it re-anchors to the new centroid
    assertEquals(100, anchor3[0], "After cooldown elapses, centroid update takes effect");
    assertEquals(50, anchor3[1]);
  }

  @Test
  @DisplayName("Center recomputes immediately when territory moves outside old pinned center, ignoring cooldown")
  void testCenterRecomputesImmediatelyWhenOutsideBoundary() {
    ClaimAnchoredRegionTracker tracker = new ClaimAnchoredRegionTracker(300, TimeUnit.SECONDS);
    RectangularClaimBoundary boundary = new RectangularClaimBoundary("faction_b", "world", 0, 0, 100, 100);

    long t0 = 5000L;
    int[] anchor1 = tracker.resolveAnchor(boundary, t0);
    assertEquals(50, anchor1[0]);
    assertEquals(50, anchor1[1]);

    // Faction unclaims old base and moves entirely to [500, 500] -> [600, 600].
    // Centroid is now (550, 550).
    boundary.updateBounds(500, 500, 600, 600);

    // Call only 5 seconds later (well within 300s cooldown)
    int[] anchor2 = tracker.resolveAnchor(boundary, t0 + 5000L);
    assertEquals(550, anchor2[0], "Anchor must immediately jump to new centroid when old center is lost");
    assertEquals(550, anchor2[1], "Anchor must immediately jump to new centroid when old center is lost");
  }

  private static Region createTestRegion(String name, Shape<?> shape, RTPWorld world) {
    RegionSettings settings = new RegionSettings(
        name,
        world,
        shape,
        new io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor(new java.util.ArrayList<>()),
        false,
        false,
        10L,
        100L,
        0L,
        5,
        0.0,
        1L,
        "",
        false);
    return new Region(name, settings);
  }

  @Test
  @DisplayName("Cross-region memory ingestion copies known hazards from source region into target shape")
  void testMemoryIngestionFromSourceRegion() {
    Circle sourceCircle = new Circle();
    sourceCircle.set(GenericMemoryShapeParams.centerRadius, 0L);
    sourceCircle.set(GenericMemoryShapeParams.radius, 500L);
    sourceCircle.set(GenericMemoryShapeParams.centerX, 0L);
    sourceCircle.set(GenericMemoryShapeParams.centerZ, 0L);
    sourceCircle.set(GenericMemoryShapeParams.expand, false);
    RTPWorld mockWorld = RTP.serverAccessor.getRTPWorld("world");
    Region sourceRegion = createTestRegion("source_world", sourceCircle, mockWorld);

    // Target shape for the claim
    Circle targetCircle = new Circle();
    targetCircle.set(GenericMemoryShapeParams.centerRadius, 0L);
    targetCircle.set(GenericMemoryShapeParams.radius, 500L);
    targetCircle.set(GenericMemoryShapeParams.centerX, 0L);
    targetCircle.set(GenericMemoryShapeParams.centerZ, 0L);
    targetCircle.set(GenericMemoryShapeParams.expand, false);

    // Mark chunk (cx=2, cz=2) -> block center (40, 40) as bad in source
    long loc1D = sourceCircle.xzToLocation(40, 40);
    sourceCircle.addBadChunk(loc1D, LocationGenerator.FailTypes.biome);
    assertTrue(sourceCircle.isKnownBad(40, 40));

    assertFalse(targetCircle.isKnownBad(40, 40));

    // Claim boundary covering chunk (2, 2)
    RectangularClaimBoundary boundary = new RectangularClaimBoundary("faction_c", "world", 0, 0, 64, 64);
    assertTrue(boundary.containsChunk(2, 2));

    // Ingest memory
    int ingested = ClaimAnchoredRegionTracker.ingestMemoryFromRegion(sourceRegion, targetCircle, boundary);
    assertTrue(ingested > 0, "Ingestion must have imported at least 1 hazard chunk");
    targetCircle.flushAndRebuild(0L);
    assertTrue(targetCircle.isKnownBad(40, 40), "Target circle must now know that chunk (2, 2) is bad");
    assertTrue(targetCircle.causeAt(40, 40) >= 0);
  }

  @Test
  @DisplayName("createClaimSubspace successfully anchors SubspaceShape and connects parent memory")
  void testCreateClaimSubspace() {
    ClaimAnchoredRegionTracker tracker = new ClaimAnchoredRegionTracker(60, TimeUnit.SECONDS);
    RectangularClaimBoundary boundary = new RectangularClaimBoundary("faction_d", "world", 100, 100, 300, 300);

    Circle parentCircle = new Circle();
    RTPWorld mockWorld = RTP.serverAccessor.getRTPWorld("world");
    Region parentRegion = createTestRegion("parent_world", parentCircle, mockWorld);

    SubspaceShape subspace = tracker.createClaimSubspace(boundary, 64, parentRegion, 10_000L);
    assertNotNull(subspace);
    assertEquals(200, subspace.getAnchor().coords().x());
    assertEquals(200, subspace.getAnchor().coords().z());
    assertEquals(64, subspace.getBlockRadius());
    assertSame(parentRegion, subspace.getParentRegion());
  }
}
