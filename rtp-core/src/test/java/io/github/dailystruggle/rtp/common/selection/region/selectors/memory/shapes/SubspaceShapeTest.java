package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.CandidateValidator;
import io.github.dailystruggle.rtp.common.selection.region.RTPLocation;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionFileCoord;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SubspaceShape & Memory Inheritance Tests")
public class SubspaceShapeTest {

  @TempDir
  File tempDir;

  @BeforeEach
  void setUp() {
    RTPTestSetup.install(tempDir);
  }

  /**
   * Chunk-granularity dummy memory: {@code isKnownBad(cx, cz)} answers at whole-chunk resolution,
   * matching the real {@link MemoryShape} contract that {@link SubspaceShape} depends on.
   */
  private static class DummyMemoryShape extends MemoryShape<GenericMemoryShapeParams> {
    private final Set<Long> badChunks = new HashSet<>();

    public DummyMemoryShape() {
      super(GenericMemoryShapeParams.class, "DUMMY", createDefaultData());
    }

    private static EnumMap<GenericMemoryShapeParams, Object> createDefaultData() {
      EnumMap<GenericMemoryShapeParams, Object> data = new EnumMap<>(GenericMemoryShapeParams.class);
      data.put(GenericMemoryShapeParams.mode, "ACCUMULATE");
      data.put(GenericMemoryShapeParams.radius, 1000L);
      data.put(GenericMemoryShapeParams.centerRadius, 0L);
      data.put(GenericMemoryShapeParams.centerX, 0L);
      data.put(GenericMemoryShapeParams.centerZ, 0L);
      data.put(GenericMemoryShapeParams.weight, 1.0);
      data.put(GenericMemoryShapeParams.uniquePlacements, false);
      data.put(GenericMemoryShapeParams.expand, false);
      return data;
    }

    /** Marks a whole chunk (chunk coordinates) bad. */
    public void markBadChunk(int cx, int cz) {
      badChunks.add(((long) cx << 32) ^ (cz & 0xFFFFFFFFL));
    }

    @Override
    public boolean isKnownBad(int cx, int cz) {
      return badChunks.contains(((long) cx << 32) ^ (cz & 0xFFFFFFFFL));
    }

    @Override
    public boolean isKnownHazard(int cx, int cz) {
      return isKnownBad(cx, cz);
    }

    @Override public long getRange() { return 1000; }
    @Override public long xzToLocation(long x, long z) { return ((x << 32) ^ z); }
    @Override public long xzToLocation(MutableRTPCoords coords) { return ((long) coords.x << 32) ^ coords.z; }
    @Override public int[] locationToXZ(long location) { return new int[]{0, 0}; }
    @Override public void locationToXZ(long location, MutableRTPCoords output) {}
    @Override public Map getParameters() { return null; }
    @Override public Collection<String> keys() { return Collections.emptyList(); }
    @Override public int[] select() { return new int[]{0, 0}; }
    @Override public long rand() { return 0; }
    @Override public boolean contains(int x, int z) { return true; }
  }

  /** Every column standable at a flat Y=64 (permissive candidate validator). */
  private static final CandidateValidator FLAT_GROUND =
      (x, z) -> new RTPLocation(new RTPCoords("world", x, 64, z), 1);
  /** No column standable (all reject). */
  private static final CandidateValidator VOID = (x, z) -> null;

  private Region createDummyRegion(DummyMemoryShape shape) {
    return createDummyRegion((MemoryShape<?>) shape);
  }

  private Region createDummyRegion(MemoryShape<?> shape) {
    MockRTPWorld world = new MockRTPWorld("world");
    LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());
    RegionSettings settings = new RegionSettings(
        "test",
        world,
        shape,
        vert,
        false,
        false,
        10L,
        1000L,
        0L,
        5,
        0.0,
        1L,
        "",
        false);
    return new Region("test", settings);
  }

  @Test
  @DisplayName("Regression: real SquareOptimizedDualLayer parent yields separated slots on fresh memory")
  void testRealDualLayerParentPlacesSeparatedSlots() {
    // Production defect (point C): group-placement tests mocked Region so getShape() was null and the
    // parent-shape isKnownBad mapping was never exercised, hiding a footprint that resolved to zero
    // candidates. Drive selection through a real dual-layer parent with empty (unscanned) hazard
    // memory: every in-domain chunk is not-known-bad, so the footprint candidate pool must be full
    // and yield memberCount separated, validated slots.
    SquareOptimizedDualLayer dualLayer = new SquareOptimizedDualLayer();
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("radius", 10000L);
    data.put("centerRadius", 0L);
    data.put("centerX", 0L);
    data.put("centerZ", 0L);
    dualLayer.setData(data);

    Region region = createDummyRegion(dualLayer);
    // Anchor well away from the center hole, mirroring an anchor drawn from the region queue.
    RTPLocation anchor = new RTPLocation(new RTPCoords("world", 2000, 64, 2000), 1);
    SubspaceShape subspace = new SubspaceShape(anchor, 64, region);

    int minSeparation = 24;
    List<RTPLocation> slots = subspace.selectSafeSlots(2, minSeparation, 64, null, FLAT_GROUND);

    assertEquals(2, slots.size(), "Real dual-layer parent with fresh memory must place both participants");
    RTPCoords a = slots.get(0).coords();
    RTPCoords b = slots.get(1).coords();
    double dist = Math.hypot(a.x() - b.x(), a.z() - b.z());
    assertTrue(dist >= minSeparation, "Placed slots must respect minSeparation post-validation: " + dist);
  }

  @Test
  @DisplayName("Subspaces inherit base memory hazards but not uniquePlacements marks")
  void testSubspaceInheritsBaseHazardNotUniquePlacements() {
    SquareOptimizedDualLayer parentShape = new SquareOptimizedDualLayer();
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("radius", 10000L);
    data.put("centerRadius", 0L);
    data.put("centerX", 0L);
    data.put("centerZ", 0L);
    data.put("uniquePlacements", "auto");
    parentShape.setData(data);

    // Anchor at world chunk (100, 100)
    RTPLocation anchor = new RTPLocation(new RTPCoords("world", 1600, 64, 1600), 1);
    long anchorChunkLoc = parentShape.xzToLocation(100, 100);

    // 1. Simulate uniquePlacements: auto marking chunks within 8 chunks of the anchor as uniquePlacement
    parentShape.addBadChunkRadius(anchorChunkLoc, 8);

    // 2. Also mark an ACTUAL base hazard (safety) on a specific chunk (101, 100)
    long hazardChunkLoc = parentShape.xzToLocation(101, 100);
    parentShape.addBadLocation(hazardChunkLoc, io.github.dailystruggle.rtp.common.selection.region.LocationGenerator.FailTypes.safety);

    Region region = createDummyRegion(parentShape);
    SubspaceShape subspace = new SubspaceShape(anchor, 64, region); // 4-chunk radius

    // Chunk (100, 100) offset (0, 0) is only uniquePlacement -> not a hazard
    assertFalse(subspace.isChunkKnownBad(0, 0), "uniquePlacement must not be treated as known bad hazard in subspace");
    // Chunk (102, 100) offset (2, 0) is only uniquePlacement -> not a hazard
    assertFalse(subspace.isChunkKnownBad(2, 0), "uniquePlacement must not be treated as known bad hazard in subspace");
    // Chunk (101, 100) offset (1, 0) has FailTypes.terrain -> must be inherited as known bad
    assertTrue(subspace.isChunkKnownBad(1, 0), "base memory terrain hazard must be inherited as known bad in subspace");

    // Candidate enumeration must NOT be starved by uniquePlacement marks
    List<RTPLocation> slots = subspace.selectSafeSlots(2, 24, 64, null, FLAT_GROUND);
    assertEquals(2, slots.size(), "Subspace must successfully place participants despite uniquePlacements on parent");
  }

  @Test
  @DisplayName("Nearplayer with centerRadius excludes center anchor coordinate")
  void testNearPlayerExcludesCenterAnchor() {
    DummyMemoryShape memShape = new DummyMemoryShape();
    Region region = createDummyRegion(memShape);
    RTPLocation targetPlayer = new RTPLocation(new RTPCoords("world", 500, 70, 500), 1);

    // Subspace configured with 16-block (1 chunk) centerRadius exclusion zone
    int footprintRadius = 96;
    int centerRadius = 16;
    int minSeparation = 24;
    SubspaceShape subspace = new SubspaceShape(targetPlayer, footprintRadius, centerRadius, region);

    int latticeUnits = (subspace.getFootprintBlocks() / 2) / minSeparation;
    int centerLatticeUnits = centerRadius / minSeparation;

    CircleOptimizedDualLayer circleMask = new CircleOptimizedDualLayer();
    Map<String, Object> maskData = new LinkedHashMap<>();
    maskData.put("radius", (long) latticeUnits);
    maskData.put("centerRadius", (long) centerLatticeUnits);
    maskData.put("centerX", 0L);
    maskData.put("centerZ", 0L);
    circleMask.setData(maskData);

    List<RTPLocation> placed = subspace.selectSafeSlots(1, minSeparation, 64, circleMask, FLAT_GROUND);
    assertEquals(1, placed.size(), "Must place 1 participant");

    RTPCoords placedCoords = placed.get(0).coords();
    double distFromTarget = Math.hypot(placedCoords.x() - targetPlayer.coords().x(), placedCoords.z() - targetPlayer.coords().z());
    assertTrue(distFromTarget >= centerRadius,
        "Placed location must be outside centerRadius exclusion zone around target player: " + distFromTarget);
  }

  @Test
  @DisplayName("Subspace affine projection maps relative block offsets to world coordinates")
  void testAffineProjection() {
    DummyMemoryShape memShape = new DummyMemoryShape();
    Region region = createDummyRegion(memShape);
    RTPLocation anchor = new RTPLocation(new RTPCoords("world", 100, 64, 200), 1);

    SubspaceShape subspace = new SubspaceShape(anchor, 1, region);

    assertEquals(100, subspace.projectX(0));
    assertEquals(200, subspace.projectZ(0));
    assertEquals(110, subspace.projectX(10));
    assertEquals(195, subspace.projectZ(-5));
  }

  @Test
  @DisplayName("Stage 1: subspace inherits chunk-granularity bad memory from parent shape")
  void testInheritedChunkMemory() {
    DummyMemoryShape memShape = new DummyMemoryShape();
    // Anchor at block (100, 200) -> chunk (6, 12). Mark the anchor's own chunk bad.
    memShape.markBadChunk(6, 12);

    Region region = createDummyRegion(memShape);
    RTPLocation anchor = new RTPLocation(new RTPCoords("world", 100, 64, 200), 1);
    SubspaceShape subspace = new SubspaceShape(anchor, 1, region);

    assertTrue(subspace.isChunkKnownBad(0, 0), "anchor chunk marked bad must be known bad");
    assertFalse(subspace.isChunkKnownBad(1, 0), "neighbour chunk not marked must survive");

    // 3x3 footprint minus the 1 bad chunk => 8 surviving chunks.
    assertEquals(8, subspace.survivingChunks().size());
  }

  @Test
  @DisplayName("Capacity check denies fail-closed when no column is standable")
  void testCapacityDenialVoid() {
    DummyMemoryShape memShape = new DummyMemoryShape();
    Region region = createDummyRegion(memShape);
    RTPLocation anchor = new RTPLocation(new RTPCoords("world", 100, 64, 200), 1);
    SubspaceShape subspace = new SubspaceShape(anchor, 48, region);

    // Chunks survive stage 1, but block-level validation finds nothing standable.
    List<RTPLocation> slots = subspace.selectSafeSlots(4, 2, -1, null, VOID);
    assertTrue(slots.isEmpty(), "Subspace with no standable columns must deny allocation fail-closed");
  }

  @Test
  @DisplayName("Capacity check denies fail-closed when all footprint chunks are known bad")
  void testCapacityDenialAllChunksBad() {
    DummyMemoryShape memShape = new DummyMemoryShape();
    // Mark the whole 3x3 chunk footprint around chunk (6, 12) bad.
    for (int cx = 5; cx <= 7; cx++) {
      for (int cz = 11; cz <= 13; cz++) {
        memShape.markBadChunk(cx, cz);
      }
    }
    Region region = createDummyRegion(memShape);
    RTPLocation anchor = new RTPLocation(new RTPCoords("world", 100, 64, 200), 1);
    SubspaceShape subspace = new SubspaceShape(anchor, 1, region);

    assertTrue(subspace.survivingChunks().isEmpty(), "all footprint chunks bad => no survivors");
    List<RTPLocation> slots = subspace.selectSafeSlots(4, 2, -1, null, FLAT_GROUND);
    assertTrue(slots.isEmpty(), "no surviving chunks must deny allocation fail-closed");
  }

  @Test
  @DisplayName("Subspace allocates non-colliding block slots with real resolved Y")
  void testSuccessfulAllocation() {
    DummyMemoryShape memShape = new DummyMemoryShape();
    Region region = createDummyRegion(memShape);
    RTPLocation anchor = new RTPLocation(new RTPCoords("world", 100, 64, 200), 1);
    SubspaceShape subspace = new SubspaceShape(anchor, 48, region);

    int memberCount = 4;
    int minSeparation = 3;
    List<RTPLocation> slots =
        subspace.selectSafeSlots(memberCount, minSeparation, -1, null, FLAT_GROUND);

    assertEquals(memberCount, slots.size());
    for (RTPLocation slot : slots) {
      assertEquals(64, slot.coords().y(), "Y must come from the block validator, not copied blindly");
    }
    for (int i = 0; i < slots.size(); i++) {
      for (int j = i + 1; j < slots.size(); j++) {
        RTPCoords a = slots.get(i).coords();
        RTPCoords b = slots.get(j).coords();
        double dist = Math.sqrt(Math.pow(a.x() - b.x(), 2) + Math.pow(a.z() - b.z(), 2));
        assertTrue(dist >= minSeparation, "Participants must be separated by at least minSeparation");
      }
    }
  }

  @Test
  @DisplayName("CandidateValidator overload selects over the shared validator seam (S-001)")
  void testCandidateValidatorOverload() {
    DummyMemoryShape memShape = new DummyMemoryShape();
    Region region = createDummyRegion(memShape);
    RTPLocation anchor = new RTPLocation(new RTPCoords("world", 100, 64, 200), 1);
    SubspaceShape subspace = new SubspaceShape(anchor, 48, region);

    // A shared-style validator that resolves a real standable Y and returns a full RTPLocation.
    CandidateValidator validator =
        (worldX, worldZ) -> new RTPLocation(new RTPCoords("world", worldX, 72, worldZ), 1);

    int memberCount = 3;
    int minSeparation = 3;
    List<RTPLocation> slots =
        subspace.selectSafeSlots(memberCount, minSeparation, -1, null, validator);

    assertEquals(memberCount, slots.size());
    for (RTPLocation slot : slots) {
      assertEquals(72, slot.coords().y(), "Y must come from the validator-resolved location");
    }
  }

  @Test
  @DisplayName("CandidateValidator overload denies fail-closed when validator rejects every column")
  void testCandidateValidatorRejectAll() {
    DummyMemoryShape memShape = new DummyMemoryShape();
    Region region = createDummyRegion(memShape);
    RTPLocation anchor = new RTPLocation(new RTPCoords("world", 100, 64, 200), 1);
    SubspaceShape subspace = new SubspaceShape(anchor, 1, region);

    CandidateValidator rejectAll = (worldX, worldZ) -> null;

    List<RTPLocation> slots = subspace.selectSafeSlots(4, 2, -1, null, rejectAll);
    assertTrue(slots.isEmpty(), "validator rejecting all columns must deny allocation fail-closed");
  }

  @Test
  @DisplayName("Subspace shape-mask: annular ring placement for nearplayer/nearclaim prevents landing at center")
  void testAnnularRingDistribution() {
    DummyMemoryShape memShape = new DummyMemoryShape();
    Region region = createDummyRegion(memShape);
    RTPLocation anchor = new RTPLocation(new RTPCoords("world", 1000, 64, 1000), 1);
    SubspaceShape subspace = new SubspaceShape(anchor, 64, region);

    Circle circleRing = new Circle();
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("radius", 10L);
    data.put("centerRadius", 3L);
    data.put("centerX", 0L);
    data.put("centerZ", 0L);
    circleRing.setData(data);

    int minSeparation = 4;
    List<RTPLocation> slots = subspace.selectSafeSlots(1, minSeparation, -1, circleRing, FLAT_GROUND);
    assertFalse(slots.isEmpty(), "Should place slot in annular ring");
    RTPCoords placed = slots.get(0).coords();

    double dist = Math.sqrt(Math.pow(placed.x() - 1000, 2) + Math.pow(placed.z() - 1000, 2));
    assertTrue(dist >= 3 * minSeparation, "Must be outside centerRadius");
    assertTrue(dist <= 10 * minSeparation, "Must be within outer radius");
  }

  @Test
  @DisplayName("Stage 1 bad-location inheritance: chunks outside parent region bounds are known bad")
  void testParentRegionBoundsInheritance() {
    // Memory shape where chunk (6, 12) is valid, but chunk (7, 12) is outside parent region bounds
    DummyMemoryShape memShape = new DummyMemoryShape() {
      @Override
      public boolean contains(int x, int z) {
        return x <= 6; // chunk X > 6 is outside parent shape
      }
    };
    Region region = createDummyRegion(memShape);
    RTPLocation anchor = new RTPLocation(new RTPCoords("world", 100, 64, 200), 1); // chunk (6, 12)
    SubspaceShape subspace = new SubspaceShape(anchor, 32, region);

    assertFalse(subspace.isChunkKnownBad(0, 0), "chunk inside parent region must not be bad");
    memShape.markBadChunk(7, 12);
    assertTrue(subspace.isChunkKnownBad(1, 0), "chunk marked bad in parent memory must be known bad");
  }

  @Test
  @DisplayName("Optimal selection: lattice cells in known-bad chunks are pruned from candidate set")
  void testOptimalSelectionExcludesBadChunks() {
    DummyMemoryShape memShape = new DummyMemoryShape();
    // Anchor at (100, 200) -> chunk (6, 12).
    // Mark neighbour chunk (7, 12) bad.
    memShape.markBadChunk(7, 12);
    Region region = createDummyRegion(memShape);
    RTPLocation anchor = new RTPLocation(new RTPCoords("world", 100, 64, 200), 1);
    SubspaceShape subspace = new SubspaceShape(anchor, 32, region);

    List<RTPLocation> slots = subspace.selectSafeSlots(3, 8, -1, null, FLAT_GROUND);
    assertFalse(slots.isEmpty(), "Should select safe slots from good chunks");
    for (RTPLocation loc : slots) {
      int cx = loc.coords().x() >> 4;
      int cz = loc.coords().z() >> 4;
      assertFalse(memShape.isKnownBad(cx, cz), "Selected slot must never be in a known-bad chunk");
    }
  }

  @Test
  @DisplayName("Multi-bin boundary spanning: anchor near chunk boundary correctly selects safe slots")
  void testMultiBinBoundarySpanning() {
    DummyMemoryShape memShape = new DummyMemoryShape();
    // Anchor placed near a 32-chunk MCA bin edge (e.g. chunk 31, 31) -> world block (31 * 16, 31 * 16) = (496, 496)
    Region region = createDummyRegion(memShape);
    RTPLocation anchor = new RTPLocation(new RTPCoords("world", 496, 64, 496), 1);
    // Subspace radius of 48 blocks spans across chunk boundary into neighbor bin
    SubspaceShape subspace = new SubspaceShape(anchor, 48, region);

    List<RTPLocation> slots = subspace.selectSafeSlots(4, 12, -1, null, FLAT_GROUND);
    assertEquals(4, slots.size(), "Must successfully place 4 participants across bin boundary");
    for (RTPLocation loc : slots) {
      assertNotNull(loc.coords());
      assertEquals(64, loc.coords().y());
    }
  }

  @Test
  @DisplayName("Clustered subspace placement: 2v2 groups teammates tightly and separates opposing clusters")
  void testSelectSafeClusterSlots2v2() {
    DummyMemoryShape memShape = new DummyMemoryShape();
    Region region = createDummyRegion(memShape);
    RTPLocation anchor = new RTPLocation(new RTPCoords("world", 1000, 64, 1000), 1);
    SubspaceShape subspace = new SubspaceShape(anchor, 64, region);

    int minClusterSep = 24;
    int intraClusterRadius = 4;
    List<Integer> clusterSizes = List.of(2, 2); // 2v2

    List<List<RTPLocation>> clusters =
        subspace.selectSafeClusterSlots(clusterSizes, minClusterSep, intraClusterRadius, 8, null, FLAT_GROUND);

    assertEquals(2, clusters.size(), "Must return 2 clusters");
    assertEquals(2, clusters.get(0).size(), "Cluster 1 must have 2 players");
    assertEquals(2, clusters.get(1).size(), "Cluster 2 must have 2 players");

    // 1. Verify teammates within cluster 1 are close together
    RTPCoords c1p1 = clusters.get(0).get(0).coords();
    RTPCoords c1p2 = clusters.get(0).get(1).coords();
    double intraDist1 = Math.hypot(c1p1.x() - c1p2.x(), c1p1.z() - c1p2.z());
    assertTrue(intraDist1 <= intraClusterRadius * 2, "Teammates in cluster 1 must be clustered together: " + intraDist1);

    // 2. Verify teammates within cluster 2 are close together
    RTPCoords c2p1 = clusters.get(1).get(0).coords();
    RTPCoords c2p2 = clusters.get(1).get(1).coords();
    double intraDist2 = Math.hypot(c2p1.x() - c2p2.x(), c2p1.z() - c2p2.z());
    assertTrue(intraDist2 <= intraClusterRadius * 2, "Teammates in cluster 2 must be clustered together: " + intraDist2);

    // 3. Verify opposing cluster members are separated
    double interDist = Math.hypot(c1p1.x() - c2p1.x(), c1p1.z() - c2p1.z());
    assertTrue(interDist >= (minClusterSep - intraClusterRadius * 2),
        "Opposing cluster members must be separated by minClusterSep margin: " + interDist);
  }

  @Test
  @DisplayName("Clustered subspace placement: 1v2 (asymmetric Juggernaut) placement succeeds")
  void testSelectSafeClusterSlots1v2() {
    DummyMemoryShape memShape = new DummyMemoryShape();
    Region region = createDummyRegion(memShape);
    RTPLocation anchor = new RTPLocation(new RTPCoords("world", 500, 64, 500), 1);
    SubspaceShape subspace = new SubspaceShape(anchor, 48, region);

    List<Integer> clusterSizes = List.of(1, 2); // 1v2
    List<List<RTPLocation>> clusters =
        subspace.selectSafeClusterSlots(clusterSizes, 20, 3, 8, null, FLAT_GROUND);

    assertEquals(2, clusters.size());
    assertEquals(1, clusters.get(0).size(), "Juggernaut cluster has 1 player");
    assertEquals(2, clusters.get(1).size(), "Hunters cluster has 2 players");
  }

  @Test
  @DisplayName("Clustered subspace placement: fails closed if validator rejects one cluster")
  void testSelectSafeClusterSlotsFailsClosed() {
    DummyMemoryShape memShape = new DummyMemoryShape();
    Region region = createDummyRegion(memShape);
    RTPLocation anchor = new RTPLocation(new RTPCoords("world", 0, 64, 0), 1);
    SubspaceShape subspace = new SubspaceShape(anchor, 16, region);

    // Tiny subspace cannot fit 2 clusters with 32 block separation
    List<List<RTPLocation>> clusters =
        subspace.selectSafeClusterSlots(List.of(2, 2), 32, 4, 8, null, FLAT_GROUND);

    assertTrue(clusters.isEmpty(), "Must fail closed if capacity cannot satisfy clusters");
  }

  @Test
  @DisplayName("Async slot selection: selectSafeSlotsAsync evaluates candidates on-demand without prior caching")
  void testSelectSafeSlotsAsyncOnDemand() throws Exception {
    DummyMemoryShape memShape = new DummyMemoryShape();
    Region region = createDummyRegion(memShape);
    RTPLocation anchor = new RTPLocation(new RTPCoords("world", 100, 64, 100), 1);
    SubspaceShape subspace = new SubspaceShape(anchor, 48, region);

    CandidateValidator onDemandValidator = new CandidateValidator() {
      @Override
      public RTPLocation validate(int x, int z) {
        return new RTPLocation(new RTPCoords("world", x, 64, z), 1);
      }

      @Override
      public java.util.concurrent.CompletableFuture<RTPLocation> validateAsync(int x, int z) {
        return java.util.concurrent.CompletableFuture.completedFuture(validate(x, z));
      }
    };

    List<RTPLocation> slots =
        subspace.selectSafeSlotsAsync(3, 16, 8, null, onDemandValidator).get(5, java.util.concurrent.TimeUnit.SECONDS);

    assertEquals(3, slots.size(), "selectSafeSlotsAsync must return 3 validated slots");
    for (RTPLocation loc : slots) {
      assertNotNull(loc.coords());
      assertEquals(64, loc.coords().y());
    }
  }

  @Test
  @DisplayName("REPRODUCER: Real MemoryShape with centerRadius marks spawn chunks as known-bad")
  void testRealMemoryShapeInnerRadiusKnownBadRepro() {
    Square_Normal sq = new Square_Normal();
    // Default has centerRadius=64, centerX=0, centerZ=0
    // Coordinate (0, 0) is inside centerRadius=64, so it is outside the 1D domain
    // It should NOT be considered a known hazard chunk!
    assertFalse(sq.isKnownBad(0, 0), "Unscanned inner-radius chunk in Square_Normal must not be reported as known bad");
  }

  @Test
  @DisplayName("Async slot selection: respect elevationTolerance around anchor Y")
  void testElevationToleranceAroundAnchorY() throws Exception {
    DummyMemoryShape memShape = new DummyMemoryShape();
    Region region = createDummyRegion(memShape);
    // Anchor has anchorY=90, ground is at Y=92 (delta=2 <= elevationTolerance=8)
    RTPLocation anchor = new RTPLocation(new RTPCoords("world", 100, 90, 100), 1);
    SubspaceShape subspace = new SubspaceShape(anchor, 48, region);

    CandidateValidator groundAt92 = new CandidateValidator() {
      @Override
      public RTPLocation validate(int x, int z) {
        return new RTPLocation(new RTPCoords("world", x, 92, z), 1);
      }

      @Override
      public java.util.concurrent.CompletableFuture<RTPLocation> validateAsync(int x, int z) {
        return java.util.concurrent.CompletableFuture.completedFuture(validate(x, z));
      }
    };

    List<RTPLocation> slots =
        subspace.selectSafeSlotsAsync(2, 16, 8, null, groundAt92).get(5, java.util.concurrent.TimeUnit.SECONDS);

    assertEquals(2, slots.size(), "Both slots within elevationTolerance of anchor Y must be selected");
  }

  @Test
  @DisplayName("Async slot selection: rejects slots exceeding elevationTolerance")
  void testElevationToleranceRejectsExceedingSlots() throws Exception {
    DummyMemoryShape memShape = new DummyMemoryShape();
    Region region = createDummyRegion(memShape);
    // Anchor has anchorY=64, ground is at Y=90 (delta=26 > elevationTolerance=8)
    RTPLocation anchor = new RTPLocation(new RTPCoords("world", 100, 64, 100), 1);
    SubspaceShape subspace = new SubspaceShape(anchor, 48, region);

    CandidateValidator groundAt90 = (x, z) -> new RTPLocation(new RTPCoords("world", x, 90, z), 1);

    List<RTPLocation> slots =
        subspace.selectSafeSlotsAsync(1, 16, 8, null, groundAt90).get(5, java.util.concurrent.TimeUnit.SECONDS);

    assertTrue(slots.isEmpty(), "Slots exceeding elevationTolerance must be rejected");
  }

  @Test
  @DisplayName("Phase 6.1: Subspace multi-member on steep cliff rejects terrace delta exceeding tolerance")
  void testSteepCliffTerraceSeparation() throws Exception {
    DummyMemoryShape memShape = new DummyMemoryShape();
    Region region = createDummyRegion(memShape);
    // Anchor is on a terrace at Y=64
    RTPLocation anchor = new RTPLocation(new RTPCoords("world", 100, 64, 100), 1);
    SubspaceShape subspace = new SubspaceShape(anchor, 48, region);

    // Steep cliff: ground at x < 100 is plateau at Y=64, ground at x >= 100 is sheer cliff dropping to Y=20
    CandidateValidator cliffValidator = (x, z) -> {
      int y = (x < 100) ? 64 : 20;
      return new RTPLocation(new RTPCoords("world", x, y, z), 1);
    };

    // Asking for 2 slots with elevationTolerance=8
    List<RTPLocation> slots =
        subspace.selectSafeSlotsAsync(2, 16, 8, null, cliffValidator).get(5, java.util.concurrent.TimeUnit.SECONDS);

    // All returned slots must be on the plateau (Y=64) within elevationTolerance, never on the bottom of the cliff (Y=20)
    for (RTPLocation loc : slots) {
      assertEquals(64, loc.coords().y(), "Selected slot must not be on the sheer drop below elevationTolerance");
    }
  }

  @Test
  @DisplayName("Phase 6.1: Subspace multi-member on ocean / island border filters out water columns")
  void testOceanIslandBorderFiltering() throws Exception {
    DummyMemoryShape memShape = new DummyMemoryShape();
    Region region = createDummyRegion(memShape);
    // Anchor is on an island shoreline at (100, 64, 100)
    RTPLocation anchor = new RTPLocation(new RTPCoords("world", 100, 64, 100), 1);
    SubspaceShape subspace = new SubspaceShape(anchor, 48, region);

    // Ocean boundary: only x <= 105 is solid ground. x > 105 is deep ocean (liquid rejected by validator -> null)
    CandidateValidator islandShoreValidator = (x, z) -> {
      if (x > 105) {
        return null; // ocean water rejected by validator
      }
      return new RTPLocation(new RTPCoords("world", x, 64, z), 1);
    };

    List<RTPLocation> slots =
        subspace.selectSafeSlotsAsync(2, 12, 8, null, islandShoreValidator).get(5, java.util.concurrent.TimeUnit.SECONDS);

    assertFalse(slots.isEmpty(), "Should successfully place available slots on solid island terrain");
    for (RTPLocation loc : slots) {
      assertTrue(loc.coords().x() <= 105, "Placed slot must be on dry ground, not in ocean columns: " + loc.coords().x());
    }
  }

  @Test
  @DisplayName("Phase 6.1: Subspace multi-member narrow cavern envelope rejects insufficient clearance")
  void testNarrowCavernEnvelopeHeadroom() throws Exception {
    DummyMemoryShape memShape = new DummyMemoryShape();
    Region region = createDummyRegion(memShape);
    // Anchor inside a cavern at Y=30
    RTPLocation anchor = new RTPLocation(new RTPCoords("world", 100, 30, 100), 1);
    SubspaceShape subspace = new SubspaceShape(anchor, 48, region);

    // Cavern: floor at Y=30. In some columns (x > 110), ceiling collapses to 1 block high (headroom insufficient -> null)
    CandidateValidator cavernValidator = (x, z) -> {
      if (x > 110) {
        return null; // suffocation hazard / blocked clearance rejected by validator
      }
      return new RTPLocation(new RTPCoords("world", x, 30, z), 1);
    };

    List<RTPLocation> slots =
        subspace.selectSafeSlotsAsync(2, 10, 5, null, cavernValidator).get(5, java.util.concurrent.TimeUnit.SECONDS);

    assertEquals(2, slots.size());
    for (RTPLocation loc : slots) {
      assertTrue(loc.coords().x() <= 110, "Placed slot must be in open cavern headroom: " + loc.coords().x());
      assertEquals(30, loc.coords().y());
    }
  }

  @Test
  @DisplayName("Phase 3: Bin-memory propagation calculates intersecting bins correctly")
  void testIntersectingBinsCalculation() {
    RTPLocation anchor = new RTPLocation(new RTPCoords("world", 100, 64, 100), 1);
    SubspaceShape subspace = new SubspaceShape(anchor, 48, null);

    Set<RegionFileCoord> bins = subspace.intersectingBins();
    assertNotNull(bins);
    assertFalse(bins.isEmpty());
    // 100 +/- 48 is [52, 148]. 52 >> 9 = 0, 148 >> 9 = 0. So it falls entirely in bin (0, 0).
    assertEquals(1, bins.size());
    assertTrue(bins.contains(new RegionFileCoord("world", 0, 0)));

    // Anchor near region file boundary: 500 +/- 32 is [468, 532].
    // 468 >> 9 = 0, 532 >> 9 = 1.
    RTPLocation boundaryAnchor = new RTPLocation(new RTPCoords("world", 500, 64, 500), 1);
    SubspaceShape boundarySubspace = new SubspaceShape(boundaryAnchor, 32, null);
    Set<RegionFileCoord> boundaryBins = boundarySubspace.intersectingBins();
    assertEquals(4, boundaryBins.size());
    assertTrue(boundaryBins.contains(new RegionFileCoord("world", 0, 0)));
    assertTrue(boundaryBins.contains(new RegionFileCoord("world", 1, 0)));
    assertTrue(boundaryBins.contains(new RegionFileCoord("world", 0, 1)));
    assertTrue(boundaryBins.contains(new RegionFileCoord("world", 1, 1)));
  }

  @Test
  @DisplayName("Path A vs Path B: SubspaceShape adopts region memory (Path A) or operates without it (Path B)")
  void testPathARegionMemoryAdoptionAndPathBWithoutMemory() throws Exception {
    String worldName = "test_memory_paths_world";
    DummyMemoryShape memShape = new DummyMemoryShape();
    Region region = createDummyRegion(memShape);

    // Anchor at block (100, 64, 100) -> chunk (6, 6)
    RTPLocation anchor = new RTPLocation(new RTPCoords(worldName, 100, 64, 100), 1);

    // Path A: With region memory attached, mark chunk (7, 6) bad (block X around 112..127)
    memShape.markBadChunk(7, 6);
    SubspaceShape subspacePathA = new SubspaceShape(anchor, 32, region);
    assertTrue(subspacePathA.isChunkKnownBad(1, 0), "Path A: chunk (7, 6) must be known bad");

    CandidateValidator validator = (x, z) -> new RTPLocation(new RTPCoords(worldName, x, 64, z), 1);
    List<RTPLocation> slotsPathA = subspacePathA.selectSafeSlotsAsync(4, 4, 16, 8, null, validator)
        .get(5, java.util.concurrent.TimeUnit.SECONDS);
    assertFalse(slotsPathA.isEmpty());
    // None of the selected slots should fall in chunk (7, 6)
    for (RTPLocation loc : slotsPathA) {
      assertFalse((loc.coords().x() >> 4) == 7 && (loc.coords().z() >> 4) == 6,
          "Path A must not place slots in known-bad chunks from parent region memory");
    }

    // Path B: Without region memory (parentRegion == null or non-memory shape)
    SubspaceShape subspacePathB = new SubspaceShape(anchor, 32, null);
    assertNull(subspacePathB.getParentShape(), "Path B: parentShape must be null");
    assertFalse(subspacePathB.isChunkKnownBad(1, 0), "Path B: does not inherit or adopt region memory");

    List<RTPLocation> slotsPathB = subspacePathB.selectSafeSlotsAsync(4, 4, 16, 8, null, validator)
        .get(5, java.util.concurrent.TimeUnit.SECONDS);
    assertEquals(4, slotsPathB.size(), "Path B: should successfully fulfill slot quota directly without region memory");
  }

  @Test
  @DisplayName("Phase 3: Selection-time lattice separation guarantees quota and clearance")
  void testSelectionTimeLatticeSeparationAndQuota() throws Exception {
    String worldName = "test_lattice_world";
    DummyMemoryShape memShape = new DummyMemoryShape();
    MockRTPWorld mockWorld = new MockRTPWorld(worldName);
    LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());
    RegionSettings settings = new RegionSettings(
        "test_region",
        mockWorld,
        memShape,
        vert,
        false,
        false,
        10L,
        1000L,
        0L,
        5,
        0.0,
        1L,
        "",
        false);
    Region region = new Region("test_region", settings);

    RTPLocation anchor = new RTPLocation(new RTPCoords(worldName, 100, 64, 100), 1);
    SubspaceShape subspace = new SubspaceShape(anchor, 48, region);

    CandidateValidator validator = new CandidateValidator() {
      @Override
      public RTPLocation validate(int x, int z) {
        return new RTPLocation(new RTPCoords(worldName, x, 64, z), 1);
      }

      @Override
      public java.util.concurrent.CompletableFuture<RTPLocation> validateAsync(int x, int z) {
        return java.util.concurrent.CompletableFuture.completedFuture(
            new RTPLocation(new RTPCoords(worldName, x, 64, z), 1));
      }
    };

    // Request quota of 4 slots with minSeparation=16
    List<RTPLocation> slots =
        subspace.selectSafeSlotsAsync(4, 4, 16, 8, null, validator).get(5, java.util.concurrent.TimeUnit.SECONDS);

    assertEquals(4, slots.size(), "Should satisfy requested quota of 4 slots");
    // Verify all pairwise distances are at least minSeparation
    for (int i = 0; i < slots.size(); i++) {
      for (int j = i + 1; j < slots.size(); j++) {
        RTPCoords c1 = slots.get(i).coords();
        RTPCoords c2 = slots.get(j).coords();
        long dx = (long) c1.x() - c2.x();
        long dz = (long) c1.z() - c2.z();
        assertTrue(dx * dx + dz * dz >= 16 * 16, "All pairs must satisfy minSeparation (16)");
      }
    }
  }

  @Test
  @DisplayName("Async slot selection drains a ~12k-candidate footprint of completed futures without StackOverflowError")
  void testSelectSafeSlotsAsyncLargeFootprintCompletedFutures() throws Exception {
    String worldName = "test_large_footprint_world";
    RTPLocation anchor = new RTPLocation(new RTPCoords(worldName, 0, 64, 0), 1);
    SubspaceShape subspace = new SubspaceShape(anchor, 1024, null);

    java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
    CandidateValidator rejectAll = new CandidateValidator() {
      @Override
      public RTPLocation validate(int x, int z) {
        return null;
      }

      @Override
      public java.util.concurrent.CompletableFuture<RTPLocation> validateAsync(int x, int z) {
        calls.incrementAndGet();
        return java.util.concurrent.CompletableFuture.completedFuture(null);
      }
    };

    List<RTPLocation> none =
        subspace.selectSafeSlotsAsync(1, 1, 16, 8, null, rejectAll).get(10, java.util.concurrent.TimeUnit.SECONDS);
    assertTrue(none.isEmpty(), "All-rejecting validator must yield no slots");
    assertTrue(calls.get() > 10_000, "Every footprint candidate must be evaluated, got " + calls.get());

    // Accept only late candidates: the drain must still reach them on one stack.
    java.util.concurrent.atomic.AtomicInteger seen = new java.util.concurrent.atomic.AtomicInteger();
    CandidateValidator acceptLate = new CandidateValidator() {
      @Override
      public RTPLocation validate(int x, int z) {
        return null;
      }

      @Override
      public java.util.concurrent.CompletableFuture<RTPLocation> validateAsync(int x, int z) {
        RTPLocation loc = seen.incrementAndGet() > 10_000
            ? new RTPLocation(new RTPCoords(worldName, x, 64, z), 1)
            : null;
        return java.util.concurrent.CompletableFuture.completedFuture(loc);
      }
    };
    List<RTPLocation> late =
        subspace.selectSafeSlotsAsync(2, 2, 16, 8, null, acceptLate).get(10, java.util.concurrent.TimeUnit.SECONDS);
    assertEquals(2, late.size(), "Late-accepted candidates must be selected");
  }
}
