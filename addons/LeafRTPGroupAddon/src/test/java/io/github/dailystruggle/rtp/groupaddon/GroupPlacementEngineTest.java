package io.github.dailystruggle.rtp.groupaddon;

import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.CandidateValidator;
import io.github.dailystruggle.rtp.common.selection.region.RTPLocation;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.SubspaceShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("GroupPlacementEngine Allocation Tests")
public class GroupPlacementEngineTest {

  @TempDir
  File tempDir;

  @BeforeEach
  void setUp() {
    RTPTestSetup.install(tempDir);
  }

  private static class DummyMemoryShape extends MemoryShape<GenericMemoryShapeParams> {
    private final Set<Long> badCoords = new HashSet<>();

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
      badCoords.add(((long) cx << 32) ^ (cz & 0xFFFFFFFFL));
    }

    @Override
    public boolean isKnownBad(int cx, int cz) {
      return badCoords.contains(((long) cx << 32) ^ (cz & 0xFFFFFFFFL));
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

  /** Every column standable at a flat Y=70 (permissive candidate validator). */
  private static final CandidateValidator FLAT_GROUND =
      (x, z) -> new RTPLocation(new RTPCoords("world", x, 70, z), 1);
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
  @DisplayName("Allocate party profile successfully resolves destinations")
  void testPartyAllocationSuccess() {
    DummyMemoryShape memShape = new DummyMemoryShape();
    Region region = createDummyRegion(memShape);
    RTPLocation anchor = new RTPLocation(new RTPCoords("world", 500, 70, 500), 1);

    GroupProfile profile =
        new GroupProfile("party", Map.of("name", "SQUARE", "radius", 24L, "spatialResolution", 2L), 8);
    SubspaceAllocationResult result =
        GroupPlacementEngine.allocate(anchor, region, profile, 4, FLAT_GROUND);

    assertTrue(result.isSuccess());
    assertEquals(SubspaceAllocationResult.Status.SUCCESS, result.status());
    assertEquals(4, result.destinations().size());
  }

  @Test
  @DisplayName("Allocate fails with EXCEEDED_MAX_GROUP_SIZE when participant count exceeds limit")
  void testExceededMaxGroupSize() {
    DummyMemoryShape memShape = new DummyMemoryShape();
    Region region = createDummyRegion(memShape);
    RTPLocation anchor = new RTPLocation(new RTPCoords("world", 500, 70, 500), 1);

    GroupProfile profile =
        new GroupProfile(
            "duel",
            Map.of("name", "CIRCLE", "radius", 100L, "centerRadius", 6L, "spatialResolution", 50L),
            2);
    SubspaceAllocationResult result =
        GroupPlacementEngine.allocate(anchor, region, profile, 5, FLAT_GROUND);

    assertFalse(result.isSuccess());
    assertEquals(SubspaceAllocationResult.Status.EXCEEDED_MAX_GROUP_SIZE, result.status());
  }

  @Test
  @DisplayName("Allocate fails fail-closed with INSUFFICIENT_SAFE_SLOTS when subspace is blocked")
  void testInsufficientSafeSlots() {
    DummyMemoryShape memShape = new DummyMemoryShape();

    Region region = createDummyRegion(memShape);
    RTPLocation anchor = new RTPLocation(new RTPCoords("world", 500, 70, 500), 1);

    GroupProfile profile =
        new GroupProfile("party", Map.of("name", "SQUARE", "radius", 24L, "spatialResolution", 2L), 8);
    // Chunks survive Stage 1, but block-level validation finds nothing standable => fail-closed.
    SubspaceAllocationResult result =
        GroupPlacementEngine.allocate(anchor, region, profile, 3, VOID);

    assertFalse(result.isSuccess());
    assertEquals(SubspaceAllocationResult.Status.INSUFFICIENT_SAFE_SLOTS, result.status());
  }
}
