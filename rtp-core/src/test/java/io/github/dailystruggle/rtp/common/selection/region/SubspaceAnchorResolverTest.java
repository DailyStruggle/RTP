package io.github.dailystruggle.rtp.common.selection.region;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dailystruggle.rtp.api.group.AnchorSource;
import io.github.dailystruggle.rtp.api.selection.GenerationResult;
import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import java.io.File;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SubspaceAnchorResolverTest {

  @TempDir
  File tempDir;

  @BeforeEach
  void setUp() {
    io.github.dailystruggle.rtp.common.mock.RTPTestSetup.install(tempDir);
  }

  private static class DummyMemoryShape extends MemoryShape<GenericMemoryShapeParams> {
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

    @Override public long getRange() { return 1000; }
    @Override public long xzToLocation(long x, long z) { return ((x << 32) ^ (z & 0xFFFFFFFFL)); }
    @Override public long xzToLocation(MutableRTPCoords coords) { return ((long) coords.x << 32) ^ (coords.z & 0xFFFFFFFFL); }
    @Override public int[] locationToXZ(long location) {
      int x = (int) (location >> 32);
      int z = (int) location;
      return new int[]{x, z};
    }
    @Override public void locationToXZ(long location, MutableRTPCoords output) {}
    @Override public Map getParameters() { return null; }
    @Override public Collection<String> keys() { return Collections.emptyList(); }
    @Override public int[] select() { return new int[]{0, 0}; }
    @Override public long rand() { return 0; }
    @Override public boolean contains(int x, int z) { return true; }
  }

  private Region createDummyRegion(DummyMemoryShape shape) {
    MockRTPWorld world = new MockRTPWorld("test_world");
    LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());
    RegionSettings settings = new RegionSettings(
        "test_region",
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
    return new Region("test_region", settings);
  }

  @Test
  @DisplayName("Fixed AnchorSource resolves immediately to exact coordinates")
  void testFixedAnchorSource() {
    DummyMemoryShape memShape = new DummyMemoryShape();
    Region region = createDummyRegion(memShape);
    RTPCoords target = new RTPCoords("test_world", 123, 65, 456);

    AnchorSource fixed = AnchorSource.fixed(target);
    GenerationResult res = SubspaceAnchorResolver.resolveAnchor(region, fixed).join();

    assertNotNull(res);
    assertEquals(target, res.coords());
  }

  @Test
  @DisplayName("Entity AnchorSource dynamically polls supplier (nearplayer)")
  void testEntityAnchorSource() {
    DummyMemoryShape memShape = new DummyMemoryShape();
    Region region = createDummyRegion(memShape);

    int[] livePos = new int[]{500, 70, 600};
    AnchorSource entity = AnchorSource.entity(() -> new RTPCoords("test_world", livePos[0], livePos[1], livePos[2]));

    GenerationResult res = SubspaceAnchorResolver.resolveAnchor(region, entity).join();
    assertNotNull(res);
    assertEquals(500, res.coords().x());
    assertEquals(600, res.coords().z());

    // Update live entity position
    livePos[0] = 750;
    livePos[2] = 850;
    GenerationResult res2 = SubspaceAnchorResolver.resolveAnchor(region, entity).join();
    assertNotNull(res2);
    assertEquals(750, res2.coords().x());
    assertEquals(850, res2.coords().z());
  }

  @Test
  @DisplayName("ClaimHazard AnchorSource recalls safetyExternal coordinates from spatial memory (nearclaim)")
  void testClaimHazardAnchorSource() {
    DummyMemoryShape memShape = new DummyMemoryShape();
    // Simulate external claim rejection recorded in MemoryShape under ADR-079 (chunk coords 15, 20)
    long claimLoc = memShape.xzToLocation(15, 20);
    memShape.addBadLocation(claimLoc, LocationGenerator.FailTypes.safetyExternal, 1000L);
    memShape.flushAndRebuild(1L);

    Region region = createDummyRegion(memShape);
    AnchorSource claimHazard = AnchorSource.claimHazard();

    GenerationResult res = SubspaceAnchorResolver.resolveAnchor(region, claimHazard).join();
    assertNotNull(res, "Should resolve claim perimeter from safetyExternal hazard run");
    // Chunk coords (15, 20) converted to block coordinates ((15 << 4) + 8 = 248, (20 << 4) + 8 = 328)
    assertEquals(248, res.coords().x());
    assertEquals(328, res.coords().z());
    assertEquals("test_world", res.coords().worldName());
  }
}
