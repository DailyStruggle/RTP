package io.github.dailystruggle.rtp.groupaddon;

import io.github.dailystruggle.rtp.api.world.ChunkReservation;
import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.CandidateValidator;
import io.github.dailystruggle.rtp.common.selection.region.RTPLocation;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("GroupSubspaceCache L1/L2/L3 Pipeline Tests")
public class GroupSubspaceCacheTest {

  @TempDir
  File tempDir;

  @BeforeEach
  void setUp() {
    RTPTestSetup.install(tempDir);
  }

  private static ChunkReservation createReservation(RTPWorld<?> world, int cx, int cz) {
    ChunkSet chunkSet =
        new ChunkSet(
            world, cx, cz, Collections.emptyList(), CompletableFuture.completedFuture(true));
    return new ChunkReservation(chunkSet, world);
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
    @Override public long xzToLocation(long x, long z) { return ((x << 32) ^ z); }
    @Override public long xzToLocation(io.github.dailystruggle.rtp.api.world.MutableRTPCoords coords) { return ((long) coords.x << 32) ^ coords.z; }
    @Override public int[] locationToXZ(long location) { return new int[]{0, 0}; }
    @Override public void locationToXZ(long location, io.github.dailystruggle.rtp.api.world.MutableRTPCoords output) {}
    @Override public Map getParameters() { return null; }
    @Override public Collection<String> keys() { return Collections.emptyList(); }
    @Override public int[] select() { return new int[]{100, 100}; }
    @Override public long rand() { return 0; }
    @Override public boolean contains(int x, int z) { return true; }
  }

  private Region createDummyRegion(DummyMemoryShape shape) {
    MockRTPWorld world = new MockRTPWorld("world");
    LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());
    RegionSettings settings = new RegionSettings(
        "testRegion",
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
    return new Region("testRegion", settings);
  }

  @Test
  @DisplayName("GroupSubspace deterministic close releases all underlying chunk reservations")
  void testGroupSubspaceCloseReleasesReservations() {
    MockRTPWorld world = new MockRTPWorld("world");
    RTPLocation anchor = new RTPLocation(new RTPCoords("world", 0, 64, 0), 1);
    ChunkReservation res1 = createReservation(world, 0, 0);
    ChunkReservation res2 = createReservation(world, 1, 0);

    GroupSubspace subspace =
        new GroupSubspace(anchor, 16, Collections.emptyList(), List.of(res1, res2));
    assertTrue(subspace.isHot());
    assertEquals(2, world.activeChunkTickets.get());

    subspace.close();
    assertFalse(subspace.isHot());
    assertEquals(0, world.activeChunkTickets.get());
  }

  @Test
  @DisplayName("GroupSubspace transferReservations moves reservations without double-closing")
  void testGroupSubspaceTransferReservations() {
    MockRTPWorld world = new MockRTPWorld("world");
    RTPLocation anchor = new RTPLocation(new RTPCoords("world", 0, 64, 0), 1);
    ChunkReservation res1 = createReservation(world, 0, 0);

    GroupSubspace subspace =
        new GroupSubspace(anchor, 16, Collections.emptyList(), List.of(res1));
    List<ChunkReservation> transferred = subspace.transferReservations();

    assertEquals(1, transferred.size());
    assertSame(res1, transferred.get(0));
    assertEquals(1, world.activeChunkTickets.get());

    // Calling close on subspace now does not close transferred reservations
    subspace.close();
    assertEquals(1, world.activeChunkTickets.get());

    // Clean up
    transferred.get(0).close();
    assertEquals(0, world.activeChunkTickets.get());
  }

  @Test
  @DisplayName("GroupSubspaceCache tier operations (L3 backlog, L2 cold, L1 hot)")
  void testGroupSubspaceCacheTierProgression() {
    MockRTPWorld world = new MockRTPWorld("world");
    GroupSubspaceCache cache = new GroupSubspaceCache(2, 5, 10);
    String key = "testRegion:party";

    GroupProfile profile =
        new GroupProfile("party", Map.of("name", "SQUARE", "radius", 24L, "spatialResolution", 2L), 4);
    RTPCoords anchorCoords = new RTPCoords("world", 100, 64, 100);

    // L3 offer and poll
    GroupBacklogEntry entry = new GroupBacklogEntry(anchorCoords, 24, profile, List.of(anchorCoords));
    assertTrue(cache.offerBacklog(key, entry));
    assertEquals(1, cache.sizeL3(key));
    assertSame(entry, cache.pollBacklog(key));
    assertEquals(0, cache.sizeL3(key));

    // L2 offer and poll
    RTPLocation anchorLoc = new RTPLocation(anchorCoords, 1);
    GroupSubspace cold = new GroupSubspace(anchorLoc, 24, List.of(anchorLoc), Collections.emptyList());
    assertTrue(cache.offerCold(key, cold));
    assertEquals(1, cache.sizeL2(key));
    assertSame(cold, cache.pollCold(key));
    assertEquals(0, cache.sizeL2(key));

    // L1 offer and poll
    ChunkReservation res = createReservation(world, 6, 6);
    GroupSubspace hot = new GroupSubspace(anchorLoc, 24, List.of(anchorLoc), List.of(res));
    assertTrue(cache.offerHot(key, hot));
    assertEquals(1, cache.sizeL1(key));
    assertSame(hot, cache.pollHot(key));
    assertEquals(0, cache.sizeL1(key));
    assertEquals(1, world.activeChunkTickets.get());
    hot.close();
    assertEquals(0, world.activeChunkTickets.get());
  }

  @Test
  @DisplayName("GroupSubspaceCache L1 capacity enforcement closes rejected over-capacity subspace")
  void testGroupSubspaceCacheL1CapacityRejectionClosesTickets() {
    MockRTPWorld world = new MockRTPWorld("world");
    GroupSubspaceCache cache = new GroupSubspaceCache(1, 5, 10);
    String key = "testRegion:party";

    RTPLocation anchor = new RTPLocation(new RTPCoords("world", 0, 64, 0), 1);
    ChunkReservation res1 = createReservation(world, 0, 0);
    GroupSubspace hot1 = new GroupSubspace(anchor, 16, List.of(anchor), List.of(res1));

    ChunkReservation res2 = createReservation(world, 1, 0);
    GroupSubspace hot2 = new GroupSubspace(anchor, 16, List.of(anchor), List.of(res2));

    assertTrue(cache.offerHot(key, hot1));
    assertEquals(1, cache.sizeL1(key));
    assertEquals(2, world.activeChunkTickets.get()); // res1 and res2 both opened

    // Second hot subspace exceeds capacity (cap = 1) -> must be rejected and closed immediately
    assertFalse(cache.offerHot(key, hot2));
    assertEquals(1, world.activeChunkTickets.get()); // res2 closed
    assertEquals(1, cache.sizeL1(key));

    // Clearing cache closes remaining hot subspace
    cache.clear();
    assertEquals(0, world.activeChunkTickets.get()); // res1 closed
    assertEquals(0, cache.sizeL1(key));
  }

  @Test
  @DisplayName("GroupCacheWorker pulse moves L3 backlog to L2 cold")
  void testGroupCacheWorkerPulseL3ToL2() {
    CandidateValidator flatGround = (x, z) -> new RTPLocation(new RTPCoords("world", x, 70, z), 1);
    GroupSubspaceCache cache = new GroupSubspaceCache(2, 5, 10);
    GroupCacheWorker worker = new GroupCacheWorker(cache);

    DummyMemoryShape memShape = new DummyMemoryShape();
    Region region = createDummyRegion(memShape);
    GroupProfile profile =
        new GroupProfile("party", Map.of("name", "SQUARE", "radius", 24L, "spatialResolution", 2L), 4);
    String key = region.name + ":" + profile.name();

    // Pulse 1: fills backlog (L3)
    worker.pulse(region, profile, flatGround);
    assertTrue(cache.sizeL3(key) > 0);

    // Pulse 2: screens L3 and promotes to L2 cold
    worker.pulse(region, profile, flatGround);
    assertTrue(cache.sizeL2(key) > 0 || cache.sizeL1(key) > 0);
  }

  @Test
  @DisplayName("GroupPlacementEngine allocateWithCache prefers L1 hot, falls back to L2 and live")
  void testAllocateWithCacheFallbackHierarchy() {
    GroupSubspaceCache cache = new GroupSubspaceCache(2, 5, 10);
    DummyMemoryShape memShape = new DummyMemoryShape();
    Region region = createDummyRegion(memShape);
    GroupProfile profile =
        new GroupProfile("party", Map.of("name", "SQUARE", "radius", 24L, "spatialResolution", 2L), 2);
    String key = region.name + ":" + profile.name();

    RTPLocation p1 = new RTPLocation(new RTPCoords("world", 10, 70, 10), 1);
    RTPLocation p2 = new RTPLocation(new RTPCoords("world", 12, 70, 10), 1);

    // Case 1: Populated L1
    MockRTPWorld world = new MockRTPWorld("world");
    ChunkReservation res = createReservation(world, 0, 0);
    GroupSubspace hot = new GroupSubspace(p1, 24, List.of(p1, p2), List.of(res));
    cache.offerHot(key, hot);

    SubspaceAllocationResult r1 =
        GroupPlacementEngine.allocateWithCache(cache, p1, region, profile, 2);
    assertTrue(r1.isSuccess());
    assertEquals(2, r1.destinations().size());
    assertEquals(0, cache.sizeL1(key)); // consumed

    // Case 2: Populated L2 (L1 empty)
    GroupSubspace cold = new GroupSubspace(p1, 24, List.of(p1, p2), Collections.emptyList());
    cache.offerCold(key, cold);

    SubspaceAllocationResult r2 =
        GroupPlacementEngine.allocateWithCache(cache, p1, region, profile, 2);
    assertTrue(r2.isSuccess());
    assertEquals(2, r2.destinations().size());
    assertEquals(0, cache.sizeL2(key)); // consumed

    // Case 3: Cache empty -> falls back to live allocation
    SubspaceAllocationResult r3 =
        GroupPlacementEngine.allocateWithCache(cache, p1, region, profile, 2);
    assertNotNull(r3);
  }

  @Test
  @DisplayName("RTPGroupAddon lifecycle initializes and cleans up cache")
  void testAddonLifecycle() {
    RTPGroupAddon addon = new RTPGroupAddon();
    addon.onLoad();
    assertNotNull(RTPGroupAddon.getCache());
    assertNotNull(RTPGroupAddon.getWorker());

    addon.onUnload();
    assertNull(RTPGroupAddon.getCache());
    assertNull(RTPGroupAddon.getWorker());
  }
}
