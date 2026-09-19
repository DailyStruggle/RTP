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
import io.github.dailystruggle.rtp.common.selection.region.cache.RejectionReason;
import io.github.dailystruggle.rtp.common.selection.region.cache.StageTransition;
import io.github.dailystruggle.rtp.common.selection.region.cache.TransitionOutcome;
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

@DisplayName("GroupSubspaceCache hot/cold/backlog Pipeline Tests")
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
  @DisplayName("GroupSubspaceCache tier operations (backlog, cold, hot)")
  void testGroupSubspaceCacheTierProgression() {
    MockRTPWorld world = new MockRTPWorld("world");
    GroupSubspaceCache cache = new GroupSubspaceCache(2, 5, 10);
    String key = "testRegion:party";

    GroupProfile profile =
        new GroupProfile("party", Map.of("name", "SQUARE", "radius", 24L, "spatialResolution", 2L), 4);
    RTPCoords anchorCoords = new RTPCoords("world", 100, 64, 100);

    // Backlog offer and poll
    GroupBacklogEntry entry = new GroupBacklogEntry(anchorCoords, 24, profile, List.of(anchorCoords));
    assertTrue(cache.offerBacklog(key, entry));
    assertEquals(1, cache.sizeBacklog(key));
    assertSame(entry, cache.pollBacklog(key));
    assertEquals(0, cache.sizeBacklog(key));

    // Cold offer and poll
    RTPLocation anchorLoc = new RTPLocation(anchorCoords, 1);
    GroupSubspace cold = new GroupSubspace(anchorLoc, 24, List.of(anchorLoc), Collections.emptyList());
    assertTrue(cache.offerCold(key, cold));
    assertEquals(1, cache.sizeCold(key));
    assertSame(cold, cache.pollCold(key));
    assertEquals(0, cache.sizeCold(key));

    // Hot offer and poll
    ChunkReservation res = createReservation(world, 6, 6);
    GroupSubspace hot = new GroupSubspace(anchorLoc, 24, List.of(anchorLoc), List.of(res));
    assertTrue(cache.offerHot(key, hot));
    assertEquals(1, cache.sizeHot(key));
    assertSame(hot, cache.pollHot(key));
    assertEquals(0, cache.sizeHot(key));
    assertEquals(1, world.activeChunkTickets.get());
    hot.close();
    assertEquals(0, world.activeChunkTickets.get());
  }

  @Test
  @DisplayName("GroupSubspaceCache hot capacity enforcement closes rejected over-capacity subspace")
  void testGroupSubspaceCacheHotCapacityRejectionClosesTickets() {
    MockRTPWorld world = new MockRTPWorld("world");
    GroupSubspaceCache cache = new GroupSubspaceCache(1, 5, 10);
    String key = "testRegion:party";

    RTPLocation anchor = new RTPLocation(new RTPCoords("world", 0, 64, 0), 1);
    ChunkReservation res1 = createReservation(world, 0, 0);
    GroupSubspace hot1 = new GroupSubspace(anchor, 16, List.of(anchor), List.of(res1));

    ChunkReservation res2 = createReservation(world, 1, 0);
    GroupSubspace hot2 = new GroupSubspace(anchor, 16, List.of(anchor), List.of(res2));

    assertTrue(cache.offerHot(key, hot1));
    assertEquals(1, cache.sizeHot(key));
    assertEquals(2, world.activeChunkTickets.get()); // res1 and res2 both opened

    // Second hot subspace exceeds capacity (cap = 1) -> must be rejected and closed immediately
    assertFalse(cache.offerHot(key, hot2));
    assertEquals(1, world.activeChunkTickets.get()); // res2 closed
    assertEquals(1, cache.sizeHot(key));

    // Clearing cache closes remaining hot subspace
    cache.clear();
    assertEquals(0, world.activeChunkTickets.get()); // res1 closed
    assertEquals(0, cache.sizeHot(key));
  }

  @Test
  @DisplayName("GroupCacheWorker pulse moves backlog to cold")
  void testGroupCacheWorkerPulseBacklogToCold() {
    CandidateValidator flatGround = (x, z) -> new RTPLocation(new RTPCoords("world", x, 70, z), 1);
    GroupSubspaceCache cache = new GroupSubspaceCache(2, 5, 10);
    GroupCacheWorker worker = new GroupCacheWorker(cache);

    DummyMemoryShape memShape = new DummyMemoryShape();
    Region region = createDummyRegion(memShape);
    GroupProfile profile =
        new GroupProfile("party", Map.of("name", "SQUARE", "radius", 24L, "spatialResolution", 2L), 4);
    String key = region.name + ":" + profile.name();

    // Pulse 1: fills the backlog
    worker.pulse(region, profile, flatGround);
    assertTrue(cache.sizeBacklog(key) > 0);

    // Pulse 2: screens the backlog and promotes to cold, which the same pulse may immediately
    // promote onward to hot. That last hop completes on the chunk-reservation future's async
    // continuation, so poll rather than assume the promotion has already landed.
    worker.pulse(region, profile, flatGround);
    assertTrue(
        awaitPromotion(cache, key),
        "backlog must be promoted into the cold or hot tier");
  }

  /** Waits up to ~2s for a screened backlog entry to reach the cold or hot tier. */
  private static boolean awaitPromotion(GroupSubspaceCache cache, String key) {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
    while (System.nanoTime() < deadline) {
      if (cache.sizeCold(key) > 0 || cache.sizeHot(key) > 0) return true;
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    return cache.sizeCold(key) > 0 || cache.sizeHot(key) > 0;
  }

  @Test
  @DisplayName("GroupPlacementEngine allocateWithCache prefers hot, falls back to cold and live")
  void testAllocateWithCacheFallbackHierarchy() {
    GroupSubspaceCache cache = new GroupSubspaceCache(2, 5, 10);
    DummyMemoryShape memShape = new DummyMemoryShape();
    Region region = createDummyRegion(memShape);
    GroupProfile profile =
        new GroupProfile("party", Map.of("name", "SQUARE", "radius", 24L, "spatialResolution", 2L), 2);
    String key = region.name + ":" + profile.name();

    RTPLocation p1 = new RTPLocation(new RTPCoords("world", 10, 70, 10), 1);
    RTPLocation p2 = new RTPLocation(new RTPCoords("world", 12, 70, 10), 1);

    // Case 1: Populated hot
    MockRTPWorld world = new MockRTPWorld("world");
    ChunkReservation res = createReservation(world, 0, 0);
    GroupSubspace hot = new GroupSubspace(p1, 24, List.of(p1, p2), List.of(res));
    cache.offerHot(key, hot);

    SubspaceAllocationResult r1 =
        GroupPlacementEngine.allocateWithCache(cache, p1, region, profile, 2);
    assertTrue(r1.isSuccess());
    assertEquals(2, r1.destinations().size());
    assertEquals(0, cache.sizeHot(key)); // consumed

    // Case 2: Populated cold (hot empty)
    GroupSubspace cold = new GroupSubspace(p1, 24, List.of(p1, p2), Collections.emptyList());
    cache.offerCold(key, cold);

    SubspaceAllocationResult r2 =
        GroupPlacementEngine.allocateWithCache(cache, p1, region, profile, 2);
    assertTrue(r2.isSuccess());
    assertEquals(2, r2.destinations().size());
    assertEquals(0, cache.sizeCold(key)); // consumed

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

  @Test
  @DisplayName("GroupSubspaceHotSink reports correct footprint chunkCostPerEntry and satisfies HotSink contract")
  void testGroupSubspaceHotSinkContractAndChunkCost() {
    MockRTPWorld world = new MockRTPWorld("world");
    GroupSubspaceCache cache = new GroupSubspaceCache(2, 5, 10);
    String key = "testRegion:party";

    // Subspace with blockRadius = 24:
    // chunkRadius = (24 + 15) / 16 = 2
    // side = 2 * 2 + 1 = 5 chunks -> side * side = 25 chunks
    int calculatedCost = GroupSubspaceHotSink.calculateChunkCost(24);
    assertEquals(25, calculatedCost);

    // Subspace with blockRadius = 16:
    // chunkRadius = (16 + 15) / 16 = 1
    // side = 2 * 1 + 1 = 3 chunks -> side * side = 9 chunks
    assertEquals(9, GroupSubspaceHotSink.calculateChunkCost(16));

    // Subspace with blockRadius = 0:
    // chunkRadius = (0 + 15) / 16 = 0
    // side = 2 * 0 + 1 = 1 chunk -> side * side = 1 chunk
    assertEquals(1, GroupSubspaceHotSink.calculateChunkCost(0));

    GroupSubspaceHotSink hotSink = cache.createHotSink(key, calculatedCost);
    assertEquals("group-subspace:" + key, hotSink.name());
    assertEquals(25, hotSink.chunkCostPerEntry());
    assertNotNull(hotSink.stage());
    assertNotNull(hotSink.coldSource());
    assertFalse(hotSink.hasExtrinsicVerifier());
    assertFalse(hotSink.isExternallyLeased());
    assertFalse(hotSink.narrowsBeyondColdSource());

    RTPLocation anchor = new RTPLocation(new RTPCoords("world", 0, 64, 0), 1);
    GroupSubspace coldSubspace = new GroupSubspace(anchor, 24, List.of(anchor), Collections.emptyList());
    assertFalse(hotSink.accepts(coldSubspace), "Cold subspace without reservations must fail closed on accepts()");

    ChunkReservation res = createReservation(world, 0, 0);
    GroupSubspace hotSubspace = new GroupSubspace(anchor, 24, List.of(anchor), List.of(res));
    assertTrue(hotSink.accepts(hotSubspace), "Hot subspace with active reservations must be accepted");

    hotSubspace.close();
    assertFalse(hotSink.accepts(hotSubspace), "Closed subspace must fail closed on accepts()");

    // Demand tracking
    assertEquals(0L, hotSink.demandWeight());
    hotSink.recordDemand(5L);
    assertEquals(5L, hotSink.demandWeight());
    hotSink.setDemandWeight(12L);
    assertEquals(12L, hotSink.demandWeight());
  }

  @Test
  @DisplayName("GroupSubspaceHotSink subsumption and transfer eligibility according to ADR-078")
  void testGroupSubspaceHotSinkSubsumptionTransfer() {
    MockRTPWorld world = new MockRTPWorld("world");
    GroupSubspaceCache cache = new GroupSubspaceCache(2, 5, 10);
    String largeKey = "testRegion:largeParty";
    String smallKey = "testRegion:smallParty";

    // Large sink (e.g. radius 24 -> chunkCost = 25)
    GroupSubspaceHotSink largeSink = cache.createHotSink(largeKey, 25);
    // Small sink (e.g. radius 16 -> chunkCost = 9) sharing same cold stage
    GroupSubspaceHotSink smallSink =
        new GroupSubspaceHotSink(
            "small",
            cache.openHotStage(smallKey),
            cache.openColdStage(largeKey), // shared cold source
            9);

    RTPLocation anchor = new RTPLocation(new RTPCoords("world", 0, 64, 0), 1);
    ChunkReservation res = createReservation(world, 0, 0);
    GroupSubspace largeHotSubspace = new GroupSubspace(anchor, 24, List.of(anchor), List.of(res));

    // Rule 0 & Rule 1: large (cost 25) -> small (cost 9) is eligible (subsumption authorized)
    assertTrue(
        io.github.dailystruggle.rtp.common.selection.region.cache.HotSink.transferEligible(
            largeSink, smallSink, largeHotSubspace));

    // Inverse: small (cost 9) -> large (cost 25) is NOT eligible (rule 0 violation: smaller cannot serve larger)
    assertFalse(
        io.github.dailystruggle.rtp.common.selection.region.cache.HotSink.transferEligible(
            smallSink, largeSink, largeHotSubspace));

    largeHotSubspace.close();
  }

  @Test
  @DisplayName("GroupBacklogToColdTransition promotes valid backlog entry to cold subspace")
  void testGroupBacklogToColdTransitionSuccess() {
    DummyMemoryShape memShape = new DummyMemoryShape();
    Region region = createDummyRegion(memShape);
    GroupProfile profile =
        new GroupProfile("party", Map.of("name", "SQUARE", "radius", 24L, "spatialResolution", 2L), 4);
    CandidateValidator flatGround = (x, z) -> new RTPLocation(new RTPCoords("world", x, 70, z), 1);

    GroupBacklogToColdTransition transition =
        new GroupBacklogToColdTransition(region, profile, flatGround);

    RTPCoords anchorCoords = new RTPCoords("world", 100, 64, 100);
    GroupBacklogEntry entry =
        new GroupBacklogEntry(anchorCoords, 24, profile, List.of(anchorCoords));
    assertEquals(GroupBacklogEntry.Validity.UNVERIFIED, entry.validity());

    CompletableFuture<TransitionOutcome<GroupSubspace>> future = transition.promote(entry);
    assertNotNull(future);
    TransitionOutcome<GroupSubspace> outcome = future.join();
    assertTrue(outcome.isPromoted());
    assertEquals(GroupBacklogEntry.Validity.VALIDATED, entry.validity());

    GroupSubspace cold = outcome.value().orElseThrow();
    assertNotNull(cold);
    assertFalse(cold.isHot());
    assertTrue(cold.reservations().isEmpty());
    assertEquals(anchorCoords, cold.anchor().coords());
    assertEquals(4, cold.slotLocations().size());
  }

  @Test
  @DisplayName("GroupBacklogToColdTransition rejects with UNSAFE_BLOCK when slots cannot be allocated")
  void testGroupBacklogToColdTransitionRejectionUnsafeBlock() {
    DummyMemoryShape memShape = new DummyMemoryShape();
    Region region = createDummyRegion(memShape);
    GroupProfile profile =
        new GroupProfile("party", Map.of("name", "SQUARE", "radius", 24L, "spatialResolution", 2L), 4);
    CandidateValidator unsafeValidator = (x, z) -> null;

    GroupBacklogToColdTransition transition =
        new GroupBacklogToColdTransition(region, profile, unsafeValidator);

    RTPCoords anchorCoords = new RTPCoords("world", 100, 64, 100);
    GroupBacklogEntry entry =
        new GroupBacklogEntry(anchorCoords, 24, profile, List.of(anchorCoords));

    TransitionOutcome<GroupSubspace> outcome = transition.promote(entry).join();
    assertFalse(outcome.isPromoted());
    assertTrue(outcome instanceof TransitionOutcome.Rejected<GroupSubspace>);
    TransitionOutcome.Rejected<GroupSubspace> rejected =
        (TransitionOutcome.Rejected<GroupSubspace>) outcome;
    assertEquals(RejectionReason.UNSAFE_BLOCK, rejected.reason());
    assertFalse(rejected.detail().isEmpty());
    assertEquals(GroupBacklogEntry.Validity.INVALIDATED, entry.validity());
  }

  @Test
  @DisplayName("GroupBacklogToColdTransition rejects with OUT_OF_BOUNDS on missing region or anchor")
  void testGroupBacklogToColdTransitionRejectionOutOfBounds() {
    GroupProfile profile =
        new GroupProfile("party", Map.of("name", "SQUARE", "radius", 24L, "spatialResolution", 2L), 4);
    CandidateValidator flatGround = (x, z) -> new RTPLocation(new RTPCoords("world", x, 70, z), 1);

    GroupBacklogToColdTransition transition =
        new GroupBacklogToColdTransition(null, profile, flatGround);

    RTPCoords anchorCoords = new RTPCoords("world", 100, 64, 100);
    GroupBacklogEntry entry =
        new GroupBacklogEntry(anchorCoords, 24, profile, List.of(anchorCoords));

    TransitionOutcome<GroupSubspace> outcome = transition.promote(entry).join();
    assertTrue(outcome instanceof TransitionOutcome.Rejected<GroupSubspace>);
    TransitionOutcome.Rejected<GroupSubspace> rejected =
        (TransitionOutcome.Rejected<GroupSubspace>) outcome;
    assertEquals(RejectionReason.OUT_OF_BOUNDS, rejected.reason());
    assertEquals(GroupBacklogEntry.Validity.INVALIDATED, entry.validity());
  }

  @Test
  @DisplayName("GroupBacklogToColdTransition maps unexpected validator exceptions to ERROR reason without swallowing")
  void testGroupBacklogToColdTransitionRejectionErrorOnException() {
    DummyMemoryShape memShape = new DummyMemoryShape();
    Region region = createDummyRegion(memShape);
    GroupProfile profile =
        new GroupProfile("party", Map.of("name", "SQUARE", "radius", 24L, "spatialResolution", 2L), 4);
    CandidateValidator throwingValidator = (x, z) -> {
      throw new IllegalStateException("Disk read failure during block inspection");
    };

    GroupBacklogToColdTransition transition =
        new GroupBacklogToColdTransition(region, profile, throwingValidator);

    RTPCoords anchorCoords = new RTPCoords("world", 100, 64, 100);
    GroupBacklogEntry entry =
        new GroupBacklogEntry(anchorCoords, 24, profile, List.of(anchorCoords));

    TransitionOutcome<GroupSubspace> outcome = transition.promote(entry).join();
    assertTrue(outcome instanceof TransitionOutcome.Rejected<GroupSubspace>);
    TransitionOutcome.Rejected<GroupSubspace> rejected =
        (TransitionOutcome.Rejected<GroupSubspace>) outcome;
    assertEquals(RejectionReason.ERROR, rejected.reason());
    assertTrue(rejected.detail().contains("Disk read failure"));
    assertEquals(GroupBacklogEntry.Validity.INVALIDATED, entry.validity());
  }

  @Test
  @DisplayName("GroupColdToHotTransition promotes off-tick and acquires chunk tickets deterministically")
  void testGroupColdToHotTransitionSuccessOffTick() {
    MockRTPWorld world = new MockRTPWorld("world");
    GroupProfile profile =
        new GroupProfile("party", Map.of("name", "SQUARE", "radius", 16L, "spatialResolution", 2L), 4);
    GroupColdToHotTransition transition = new GroupColdToHotTransition(world, profile);

    RTPLocation anchor = new RTPLocation(new RTPCoords("world", 0, 64, 0), 1);
    GroupSubspace cold = new GroupSubspace(anchor, 16, List.of(anchor), Collections.emptyList());
    assertEquals(0, world.activeChunkTickets.get());

    TransitionOutcome<GroupSubspace> outcome = transition.promote(cold).join();
    assertTrue(outcome.isPromoted());

    GroupSubspace hot = outcome.value().orElseThrow();
    assertTrue(hot.isHot());
    // Radius 16 -> (16+15)/16 = 1 chunk radius -> side = 3 chunks -> 3x3 = 9 chunk tickets
    assertEquals(9, hot.reservations().size());
    assertEquals(9, world.activeChunkTickets.get());

    // Deterministic close
    hot.close();
    assertEquals(0, world.activeChunkTickets.get());
  }

  @Test
  @DisplayName("GroupColdToHotTransition rejects with RESERVATION_FAILED when world is unavailable")
  void testGroupColdToHotTransitionRejectionOnNullWorld() {
    GroupProfile profile =
        new GroupProfile("party", Map.of("name", "SQUARE", "radius", 16L, "spatialResolution", 2L), 4);
    GroupColdToHotTransition transition = new GroupColdToHotTransition((RTPWorld<?>) null, profile);

    RTPLocation anchor = new RTPLocation(new RTPCoords("world", 0, 64, 0), 1);
    GroupSubspace cold = new GroupSubspace(anchor, 16, List.of(anchor), Collections.emptyList());

    TransitionOutcome<GroupSubspace> outcome = transition.promote(cold).join();
    assertTrue(outcome instanceof TransitionOutcome.Rejected<GroupSubspace>);
    TransitionOutcome.Rejected<GroupSubspace> rejected =
        (TransitionOutcome.Rejected<GroupSubspace>) outcome;
    assertEquals(RejectionReason.RESERVATION_FAILED, rejected.reason());
  }

  @Test
  @DisplayName("GroupColdToHotTransition rejects and cleans up reservations when chunk load returns null")
  void testGroupColdToHotTransitionRejectionOnChunkLoadFailure() {
    MockRTPWorld failingWorld = new MockRTPWorld("world") {
      @Override
      public CompletableFuture<ChunkSet> getChunkAtAsync(int cx, int cz) {
        return CompletableFuture.completedFuture(null);
      }
    };

    GroupProfile profile =
        new GroupProfile("party", Map.of("name", "SQUARE", "radius", 16L, "spatialResolution", 2L), 4);
    GroupColdToHotTransition transition = new GroupColdToHotTransition(failingWorld, profile);

    RTPLocation anchor = new RTPLocation(new RTPCoords("world", 0, 64, 0), 1);
    GroupSubspace cold = new GroupSubspace(anchor, 16, List.of(anchor), Collections.emptyList());

    TransitionOutcome<GroupSubspace> outcome = transition.promote(cold).join();
    assertTrue(outcome instanceof TransitionOutcome.Rejected<GroupSubspace>);
    TransitionOutcome.Rejected<GroupSubspace> rejected =
        (TransitionOutcome.Rejected<GroupSubspace>) outcome;
    assertEquals(RejectionReason.RESERVATION_FAILED, rejected.reason());
    assertEquals(0, failingWorld.activeChunkTickets.get());
  }

  @Test
  @DisplayName("GroupColdToHotTransition maps async chunk load exception to ERROR and closes any open tickets")
  void testGroupColdToHotTransitionRejectionOnErrorException() {
    MockRTPWorld errorWorld = new MockRTPWorld("world") {
      @Override
      public CompletableFuture<ChunkSet> getChunkAtAsync(int cx, int cz) {
        return CompletableFuture.failedFuture(new RuntimeException("Async chunk loading timeout"));
      }
    };

    GroupProfile profile =
        new GroupProfile("party", Map.of("name", "SQUARE", "radius", 16L, "spatialResolution", 2L), 4);
    GroupColdToHotTransition transition = new GroupColdToHotTransition(errorWorld, profile);

    RTPLocation anchor = new RTPLocation(new RTPCoords("world", 0, 64, 0), 1);
    GroupSubspace cold = new GroupSubspace(anchor, 16, List.of(anchor), Collections.emptyList());

    TransitionOutcome<GroupSubspace> outcome = transition.promote(cold).join();
    assertTrue(outcome instanceof TransitionOutcome.Rejected<GroupSubspace>);
    TransitionOutcome.Rejected<GroupSubspace> rejected =
        (TransitionOutcome.Rejected<GroupSubspace>) outcome;
    assertEquals(RejectionReason.ERROR, rejected.reason());
    assertTrue(rejected.detail().contains("Async chunk loading timeout"));
    assertEquals(0, errorWorld.activeChunkTickets.get());
  }

  @Test
  @DisplayName("GroupCacheWorker restores candidate to cold stage on temporary reservation failure")
  void testGroupCacheWorkerRejectionRestoresColdEntry() {
    GroupSubspaceCache cache = new GroupSubspaceCache(2, 5, 10);
    GroupCacheWorker worker = new GroupCacheWorker(cache);

    DummyMemoryShape memShape = new DummyMemoryShape();
    Region region = createDummyRegion(memShape);
    GroupProfile profile =
        new GroupProfile("party", Map.of("name", "SQUARE", "radius", 16L, "spatialResolution", 2L), 2);
    String key = region.name + ":" + profile.name();

    RTPLocation anchor = new RTPLocation(new RTPCoords("world", 0, 64, 0), 1);
    GroupSubspace cold = new GroupSubspace(anchor, 16, List.of(anchor), Collections.emptyList());
    cache.offerCold(key, cold);
    assertEquals(1, cache.sizeCold(key));

    StageTransition<GroupSubspace, GroupSubspace> rejectingTransition =
        source -> CompletableFuture.completedFuture(
            TransitionOutcome.rejected(RejectionReason.RESERVATION_FAILED, "Temporary server load"));

    worker.pulse(
        region,
        profile,
        (x, z) -> anchor,
        null,
        rejectingTransition);

    // The cold subspace must have been restored back to the cold queue rather than lost
    assertEquals(1, cache.sizeCold(key));
    assertEquals(0, cache.sizeHot(key));
  }
}
