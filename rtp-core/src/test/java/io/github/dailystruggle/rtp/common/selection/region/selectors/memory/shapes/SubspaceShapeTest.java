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

  /** Every column standable at a flat Y=64 (permissive block validator). */
  private static final SubspaceShape.BlockValidator FLAT_GROUND = (x, z) -> 64;
  /** No column standable (all invalid). */
  private static final SubspaceShape.BlockValidator VOID = (x, z) -> SubspaceShape.BlockValidator.INVALID;

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
    SubspaceShape subspace = new SubspaceShape(anchor, 1, region);

    // Chunks survive stage 1, but block-level validation finds nothing standable.
    List<RTPLocation> slots = subspace.selectSafeSlots(4, 2, VOID);
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
    List<RTPLocation> slots = subspace.selectSafeSlots(4, 2, FLAT_GROUND);
    assertTrue(slots.isEmpty(), "no surviving chunks must deny allocation fail-closed");
  }

  @Test
  @DisplayName("Subspace allocates non-colliding block slots with real resolved Y")
  void testSuccessfulAllocation() {
    DummyMemoryShape memShape = new DummyMemoryShape();
    Region region = createDummyRegion(memShape);
    RTPLocation anchor = new RTPLocation(new RTPCoords("world", 100, 64, 200), 1);
    SubspaceShape subspace = new SubspaceShape(anchor, 1, region);

    int memberCount = 4;
    int minSeparation = 3;
    List<RTPLocation> slots = subspace.selectSafeSlots(memberCount, minSeparation, FLAT_GROUND);

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
    SubspaceShape subspace = new SubspaceShape(anchor, 1, region);

    // A shared-style validator that resolves a real standable Y and returns a full RTPLocation.
    CandidateValidator validator =
        (worldX, worldZ) -> new RTPLocation(new RTPCoords("world", worldX, 72, worldZ), 1);

    int memberCount = 3;
    int minSeparation = 3;
    List<RTPLocation> slots = subspace.selectSafeSlots(memberCount, minSeparation, validator);

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

    List<RTPLocation> slots = subspace.selectSafeSlots(4, 2, rejectAll);
    assertTrue(slots.isEmpty(), "validator rejecting all columns must deny allocation fail-closed");
  }
}
