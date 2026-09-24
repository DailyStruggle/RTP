package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.CandidateValidator;
import io.github.dailystruggle.rtp.common.selection.region.RTPLocation;
import io.github.dailystruggle.rtp.common.selection.region.Region;
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
    assertTrue(subspace.isChunkKnownBad(1, 0), "chunk outside parent bounds must be known bad");
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
}
