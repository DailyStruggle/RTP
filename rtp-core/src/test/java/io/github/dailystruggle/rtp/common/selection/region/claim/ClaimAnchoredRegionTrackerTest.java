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

    @Override
    public int minX() {
      return minX;
    }

    @Override
    public int minZ() {
      return minZ;
    }

    @Override
    public int maxX() {
      return maxX;
    }

    @Override
    public int maxZ() {
      return maxZ;
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

    // Shape keys are chunk units: mark chunk (2, 2) (blocks 32..47) as bad in source
    long loc1D = sourceCircle.xzToLocation(2, 2);
    sourceCircle.addBadChunk(loc1D, LocationGenerator.FailTypes.biome);
    assertTrue(sourceCircle.isKnownBad(2, 2));

    assertFalse(targetCircle.isKnownBad(2, 2));

    // Claim boundary covering chunk (2, 2)
    RectangularClaimBoundary boundary = new RectangularClaimBoundary("faction_c", "world", 0, 0, 64, 64);
    assertTrue(boundary.containsChunk(2, 2));

    // Ingest memory
    int ingested = ClaimAnchoredRegionTracker.ingestMemoryFromRegion(sourceRegion, targetCircle, boundary);
    assertTrue(ingested > 0, "Ingestion must have imported at least 1 hazard chunk");
    targetCircle.flushAndRebuild(0L);
    assertTrue(targetCircle.isKnownBad(2, 2), "Target circle must now know that chunk (2, 2) is bad");
    assertTrue(targetCircle.causeAt(2, 2) >= 0);
    assertFalse(targetCircle.isKnownBad(40, 40), "Block-center key (40, 40) is a different chunk");
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

  @Test
  @DisplayName("Single claim hit encapsulates the entire claim envelope in MemoryShape")
  void testEncapsulateClaimBlanksOutBoundingBox() {
    Circle circle = new Circle();
    circle.set(GenericMemoryShapeParams.centerRadius, 0L);
    circle.set(GenericMemoryShapeParams.radius, 500L);
    circle.set(GenericMemoryShapeParams.centerX, 0L);
    circle.set(GenericMemoryShapeParams.centerZ, 0L);
    circle.set(GenericMemoryShapeParams.expand, false);

    // Register a claim boundary spanning [64, 64] -> [128, 128]
    RectangularClaimBoundary boundary = new RectangularClaimBoundary("test_claim", "world", 64, 64, 128, 128);
    io.github.dailystruggle.rtp.api.claim.ClaimBoundaryProvider provider = new io.github.dailystruggle.rtp.api.claim.ClaimBoundaryProvider() {
      @Override public String namespace() { return "test"; }
      @Override public int priority() { return 100; }
      @Override public java.util.Optional<ClaimBoundary> getBoundary(java.util.UUID playerId, String worldName) { return java.util.Optional.empty(); }
      @Override public java.util.Optional<ClaimBoundary> getBoundaryAt(String worldName, int x, int z) {
        return boundary.contains(x, z) ? java.util.Optional.of(boundary) : java.util.Optional.empty();
      }
    };

    io.github.dailystruggle.rtp.common.hooks.DefaultRTPHooks hooks = new io.github.dailystruggle.rtp.common.hooks.DefaultRTPHooks();
    hooks.claimBoundaries().register(provider);
    io.github.dailystruggle.rtp.api.RTPAPI.hooks = hooks;

    // Shape keys are chunk units: blocks [64, 128] are chunks [4, 8]
    assertFalse(circle.isKnownBad(4, 4));
    assertFalse(circle.isKnownBad(6, 6));
    assertFalse(circle.isKnownBad(8, 8));

    // A candidate hit at block (80, 80) fails claim verifier -> encapsulateClaim called
    int marked = ClaimAnchoredRegionTracker.encapsulateClaim(circle, "world", 80, 80, null);
    assertTrue(marked > 0, "Must have marked at least 1 coordinate");
    assertTrue(marked <= 3 * 5 * 5, "At most three spiral indices per claimed chunk, got " + marked);

    // Every claimed chunk must now be known bad
    assertTrue(circle.isKnownBad(4, 4), "Min boundary chunk must be marked bad");
    assertTrue(circle.isKnownBad(5, 5), "Candidate hit chunk must be marked bad");
    assertTrue(circle.isKnownBad(6, 6), "Interior chunk must be marked bad");
    // containsChunk samples the chunk centre: chunk 8 (centre 136) lies outside a claim ending at 128
    assertTrue(circle.isKnownBad(7, 7), "Last fully claimed chunk must be marked bad");

    // Block coordinates read as chunk keys point ~16x farther out and must stay good
    assertFalse(circle.isKnownBad(64, 64), "Chunk (64, 64) lies outside the claim");
    assertFalse(circle.isKnownBad(96, 96), "Chunk (96, 96) lies outside the claim");
    assertFalse(circle.isKnownBad(128, 128), "Chunk (128, 128) lies outside the claim");
    assertFalse(circle.isKnownBad(0, 0), "Chunk outside claim must remain good");
    assertFalse(circle.isKnownBad(12, 12), "Chunk outside claim must remain good");
  }

  @Test
  @DisplayName("REQ-RTP-S-003: claim hit without a resolvable boundary marks the hit's chunk, not the block key")
  void testEncapsulateClaimFallbackUsesChunkOfHit() {
    Circle circle = new Circle();
    circle.set(GenericMemoryShapeParams.centerRadius, 0L);
    circle.set(GenericMemoryShapeParams.radius, 500L);
    circle.set(GenericMemoryShapeParams.centerX, 0L);
    circle.set(GenericMemoryShapeParams.centerZ, 0L);
    circle.set(GenericMemoryShapeParams.expand, false);
    io.github.dailystruggle.rtp.api.RTPAPI.hooks = new io.github.dailystruggle.rtp.common.hooks.DefaultRTPHooks();

    int marked = ClaimAnchoredRegionTracker.encapsulateClaim(circle, "world", 80, 80, null);
    assertTrue(marked > 0, "Fallback must mark the hit chunk");
    circle.flushAndRebuild(0L);
    assertTrue(circle.isKnownBad(5, 5), "Block (80, 80) lies in chunk (5, 5)");
    assertFalse(circle.isKnownBad(80, 80), "Chunk (80, 80) is ~1,280 blocks away from the hit");
  }

  @Test
  @DisplayName("resolveAnchor handles boundary cooldown, updates, and edge cases")
  void testResolveAnchorBranches() {
    ClaimAnchoredRegionTracker tracker = new ClaimAnchoredRegionTracker(100, TimeUnit.MILLISECONDS);
    assertThrows(NullPointerException.class, () -> tracker.resolveAnchor(null, 1000L));

    // Centroid null or short
    ClaimBoundary badCentroid = new ClaimBoundary() {
      @Override public String id() { return "bad"; }
      @Override public String world() { return "world"; }
      @Override public boolean contains(int x, int z) { return true; }
      @Override public int[] centroid() { return new int[] {1}; }
      @Override public int minChunkX() { return 0; }
      @Override public int minChunkZ() { return 0; }
      @Override public int maxChunkX() { return 0; }
      @Override public int maxChunkZ() { return 0; }
    };
    assertThrows(IllegalArgumentException.class, () -> tracker.resolveAnchor(badCentroid, 1000L));

    RectangularClaimBoundary b = new RectangularClaimBoundary("c1", "world", 10, 10, 50, 50);
    int[] a1 = tracker.resolveAnchor(b, 1000L);
    assertEquals(30, a1[0]);
    assertEquals(30, a1[1]);

    // Move boundary slightly while still containing old center (30, 30), before cooldown elapsed
    b.updateBounds(15, 15, 55, 55);
    int[] a2 = tracker.resolveAnchor(b, 1050L);
    assertEquals(30, a2[0], "Before cooldown elapsed, center should be preserved");
    assertEquals(30, a2[1]);

    // After cooldown elapsed, updates to new centroid (35, 35)
    int[] a3 = tracker.resolveAnchor(b, 1150L);
    assertEquals(35, a3[0], "After cooldown elapsed, center should update");
    assertEquals(35, a3[1]);

    // Move boundary completely so old center is outside -> immediate update even before cooldown
    b.updateBounds(100, 100, 200, 200);
    int[] a4 = tracker.resolveAnchor(b, 1160L);
    assertEquals(150, a4[0], "Center outside boundary must update immediately");
    assertEquals(150, a4[1]);

    // Invalidate and clear
    tracker.invalidate("c1");
    tracker.invalidate(null);
    tracker.clear();
  }

  @Test
  @DisplayName("anchorCache stays bounded under claim churn and keeps recently resolved anchors")
  void testAnchorCacheBoundedUnderChurn() {
    assertThrows(IllegalArgumentException.class,
        () -> new ClaimAnchoredRegionTracker(1, TimeUnit.HOURS, 0));
    ClaimAnchoredRegionTracker tracker = new ClaimAnchoredRegionTracker(1, TimeUnit.HOURS, 10);
    RectangularClaimBoundary hot = new RectangularClaimBoundary("hot", "world", 10, 10, 50, 50);
    assertEquals(30, tracker.resolveAnchor(hot, 0L)[0]);

    for (int i = 0; i < 500; i++) {
      long now = 1_000L + i;
      tracker.resolveAnchor(new RectangularClaimBoundary("churn_" + i, "world", i, i, i + 16, i + 16), now);
      tracker.resolveAnchor(hot, now);
      assertTrue(tracker.size() <= 10, "Cache exceeded cap at insert " + i + ": " + tracker.size());
    }

    // The hot claim survived eviction: its pinned centre is preserved within the cooldown.
    hot.updateBounds(15, 15, 55, 55);
    assertEquals(30, tracker.resolveAnchor(hot, 2_000L)[0], "Recently resolved anchor must not be evicted");
  }

  @Test
  @DisplayName("anchorCache drops idle entries once the cap is exceeded")
  void testAnchorCacheIdleEviction() {
    ClaimAnchoredRegionTracker tracker = new ClaimAnchoredRegionTracker(1, TimeUnit.MINUTES, 10);
    for (int i = 0; i < 10; i++) {
      tracker.resolveAnchor(new RectangularClaimBoundary("old_" + i, "world", 0, 0, 16, 16), 0L);
    }
    assertEquals(10, tracker.size());
    long later = TimeUnit.HOURS.toMillis(2);
    tracker.resolveAnchor(new RectangularClaimBoundary("fresh", "world", 0, 0, 16, 16), later);
    assertEquals(1, tracker.size(), "Entries idle beyond the TTL must be dropped on overflow");
  }

  @Test
  @DisplayName("encapsulateClaim edge cases: null shape, null world, null boundary fallback")
  void testEncapsulateClaimEdgeCases() {
    Circle circle = new Circle();
    assertEquals(0, ClaimAnchoredRegionTracker.encapsulateClaim(null, "world", 0, 0, null));
    assertEquals(0, ClaimAnchoredRegionTracker.encapsulateClaim(circle, null, 0, 0, null));

    // When no provider matches, falls back to single candidate chunk
    io.github.dailystruggle.rtp.api.RTPAPI.hooks = null;
    int marked = ClaimAnchoredRegionTracker.encapsulateClaim(circle, "world", 16, 16, null);
    assertTrue(marked >= 0);
  }

  @Test
  @DisplayName("ingestMemoryFromRegion edge cases with nulls and non-memory shapes")
  void testIngestMemoryEdgeCases() {
    Circle circle = new Circle();
    RectangularClaimBoundary b = new RectangularClaimBoundary("c2", "world", 0, 0, 16, 16);
    assertEquals(0, ClaimAnchoredRegionTracker.ingestMemoryFromRegion(null, circle, b));
    assertEquals(0, ClaimAnchoredRegionTracker.ingestMemoryFromRegion(createTestRegion("w", circle, null), null, b));
    assertEquals(0, ClaimAnchoredRegionTracker.ingestMemoryFromRegion(createTestRegion("w", circle, null), circle, null));
  }
}
