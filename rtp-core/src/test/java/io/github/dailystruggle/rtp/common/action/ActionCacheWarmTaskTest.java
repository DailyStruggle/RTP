package io.github.dailystruggle.rtp.common.action;

import io.github.dailystruggle.rtp.api.action.ActionContext;
import io.github.dailystruggle.rtp.api.action.ActionDefinition;
import io.github.dailystruggle.rtp.api.action.ActionSessionResult;
import io.github.dailystruggle.rtp.api.group.GroupPlacementRequest;
import io.github.dailystruggle.rtp.api.group.GroupPlacementResult;
import io.github.dailystruggle.rtp.api.world.ChunkReservation;
import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.selection.region.GroupPlacementDispatcher;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActionCacheWarmTaskTest {

  private MockRTPServerAccessor serverAccessor;
  private ActionManager actionManager;
  private ActionCacheWarmTask warmTask;
  private io.github.dailystruggle.rtp.api.group.GroupPlacementService originalGroupService;

  @BeforeEach
  void setUp() {
    serverAccessor = new MockRTPServerAccessor(new File("."));
    RTP.serverAccessor = serverAccessor;
    originalGroupService = RTP.groupPlacementService;
    actionManager = new ActionManager();
    warmTask = new ActionCacheWarmTask(actionManager);
  }

  @AfterEach
  void tearDown() {
    RTP.groupPlacementService = originalGroupService;
  }

  private ChunkReservation createTrackingReservation(MockRTPWorld world, AtomicBoolean closedTracker) {
    ChunkSet set = new ChunkSet(world, 0, 0, List.of(), CompletableFuture.completedFuture(null));
    return new ChunkReservation(set, world) {
      @Override
      public void close() {
        closedTracker.set(true);
        super.close();
      }
    };
  }

  @Test
  @DisplayName("Stationary vs dynamic anchor classification (ADR-097 1.1)")
  void testStationaryClassification() {
    ActionDefinition.PlacementSpec scatterSpec =
        new ActionDefinition.PlacementSpec("default", "CIRCLE", 64, 16, 8, Map.of(), 3, 2);
    assertTrue(ActionCacheWarmTask.isStationary(scatterSpec));

    ActionDefinition.PlacementSpec fixedSpec =
        new ActionDefinition.PlacementSpec("default", "CIRCLE", 64, 16, 8, Map.of("anchor", "fixed", "anchorX", 100, "anchorZ", 200), 3, 2);
    assertTrue(ActionCacheWarmTask.isStationary(fixedSpec));

    ActionDefinition.PlacementSpec claimHazardSpec =
        new ActionDefinition.PlacementSpec("default", "CIRCLE", 64, 16, 8, Map.of("anchor", "nearclaim"), 3, 2);
    assertTrue(ActionCacheWarmTask.isStationary(claimHazardSpec));

    ActionDefinition.PlacementSpec playerSpec =
        new ActionDefinition.PlacementSpec("default", "CIRCLE", 64, 16, 8, Map.of("anchor", "nearplayer"), 3, 2);
    assertFalse(ActionCacheWarmTask.isStationary(playerSpec));

    ActionDefinition.PlacementSpec entitySpec =
        new ActionDefinition.PlacementSpec("default", "CIRCLE", 64, 16, 8, Map.of("anchor", "entity"), 3, 2);
    assertFalse(ActionCacheWarmTask.isStationary(entitySpec));
  }

  @Test
  @DisplayName("Slot count resolution from placement parameters")
  void testSlotCountResolution() {
    ActionDefinition.PlacementSpec defaultSpec =
        new ActionDefinition.PlacementSpec("default", "CIRCLE", 64, 16, 8, Map.of(), 3, 2);
    assertEquals(2, ActionCacheWarmTask.resolveSlotCount(defaultSpec));

    ActionDefinition.PlacementSpec slotsSpec =
        new ActionDefinition.PlacementSpec("default", "CIRCLE", 64, 16, 8, Map.of("slots", 4), 3, 2);
    assertEquals(4, ActionCacheWarmTask.resolveSlotCount(slotsSpec));

    ActionDefinition.PlacementSpec participantsSpec =
        new ActionDefinition.PlacementSpec("default", "CIRCLE", 64, 16, 8, Map.of("participants", 3), 3, 2);
    assertEquals(3, ActionCacheWarmTask.resolveSlotCount(participantsSpec));
  }

  @Test
  @DisplayName("ActionCacheWarmTask warms stationary actions off-tick up to cacheSize (ADR-097 1.1)")
  void testWarmingStationaryAction() {
    GroupPlacementDispatcher mockDispatcher = mock(GroupPlacementDispatcher.class);
    RTP.groupPlacementService = mockDispatcher;

    ActionDefinition.PlacementSpec pSpec =
        new ActionDefinition.PlacementSpec("default", "CIRCLE", 64, 16, 8, Map.of(), 2, 2);
    ActionDefinition def = new ActionDefinition(
        "duel_warm", "duel_warm", "rtp.action.warm", "",
        pSpec,
        ActionDefinition.ConfinementSpec.DEFAULT,
        ActionDefinition.LifecycleSpec.EMPTY);
    actionManager.registerAction(def);

    MockRTPWorld world = (MockRTPWorld) serverAccessor.getRTPWorld("world");
    when(mockDispatcher.preparePlacement(any(GroupPlacementRequest.class))).thenAnswer(inv -> {
      GroupPlacementRequest req = inv.getArgument(0);
      Map<UUID, RTPLocation> slots = new LinkedHashMap<>();
      for (UUID u : req.participants()) {
        slots.put(u, new RTPLocation(world, 100, 64, 100));
      }
      GroupPlacementDispatcher.PreparedPlacement prep =
          new GroupPlacementDispatcher.PreparedPlacement(
              GroupPlacementResult.success(slots), 100, 100, world);
      return CompletableFuture.completedFuture(prep);
    });

    // 1. Initial count is 0
    assertEquals(0, actionManager.getCachedPlacementCount("duel_warm"));

    // 2. Run warm task: should prepare one placement (target size = 2)
    warmTask.run();
    assertEquals(1, actionManager.getCachedPlacementCount("duel_warm"));

    // 3. Run again: should prepare second placement (target size = 2)
    warmTask.run();
    assertEquals(2, actionManager.getCachedPlacementCount("duel_warm"));

    // 4. Run again: at capacity, no new placements added
    warmTask.run();
    assertEquals(2, actionManager.getCachedPlacementCount("duel_warm"));
  }

  @Test
  @DisplayName("ActionCacheWarmTask skips dynamic entity actions (nearplayer) (ADR-097 1.1)")
  void testWarmingSkipsDynamicAction() {
    GroupPlacementDispatcher mockDispatcher = mock(GroupPlacementDispatcher.class);
    RTP.groupPlacementService = mockDispatcher;

    ActionDefinition.PlacementSpec pSpec =
        new ActionDefinition.PlacementSpec("default", "CIRCLE", 64, 16, 8, Map.of("anchor", "nearplayer"), 2, 2);
    ActionDefinition def = new ActionDefinition(
        "hunt_action", "hunt_action", "rtp.action.hunt", "",
        pSpec,
        ActionDefinition.ConfinementSpec.DEFAULT,
        ActionDefinition.LifecycleSpec.EMPTY);
    actionManager.registerAction(def);

    warmTask.run();

    assertEquals(0, actionManager.getCachedPlacementCount("hunt_action"));
    Mockito.verifyNoInteractions(mockDispatcher);
  }

  @Test
  @DisplayName("Pre-flight revalidation pops next cached entry on failure before live fallback (ADR-097 1.2)")
  @SuppressWarnings("unchecked")
  void testPreFlightRevalidationPopsNextCached() {
    UUID p1 = UUID.randomUUID();
    ActionDefinition.PlacementSpec pSpec =
        new ActionDefinition.PlacementSpec("default", "CIRCLE", 64, 16, 8, Map.of(), 3, 2);
    ActionDefinition def = new ActionDefinition(
        "reval_action", "reval_action", "rtp.action.reval", "",
        pSpec,
        ActionDefinition.ConfinementSpec.DEFAULT,
        ActionDefinition.LifecycleSpec.EMPTY);
    actionManager.registerAction(def);

    // Mock parent region
    Region mockRegion = mock(Region.class);
    MemoryShape mockShape = mock(MemoryShape.class);
    when(mockRegion.getShape()).thenReturn(mockShape);
    when(mockShape.xzToLocation(any(long.class), any(long.class))).thenReturn(12345L);

    // Revalidation rejects x=100, accepts x=200
    when(mockRegion.candidateValidator()).thenReturn((x, z) -> {
      if (x == 100) return null; // Reject first cached placement!
      return new io.github.dailystruggle.rtp.common.selection.region.RTPLocation(
          new RTPCoords("world", x, 64, z), 1);
    });
    RTP.selectionAPI.permRegionLookup.put("default", mockRegion);

    AtomicBoolean firstReservationClosed = new AtomicBoolean(false);
    AtomicBoolean secondReservationClosed = new AtomicBoolean(false);

    MockRTPWorld world = (MockRTPWorld) serverAccessor.getRTPWorld("world");
    ChunkReservation res1 = createTrackingReservation(world, firstReservationClosed);
    ChunkReservation res2 = createTrackingReservation(world, secondReservationClosed);

    // Offer two cached entries: first invalid (x=100), second valid (x=200)
    RTPLocation badLoc = new RTPLocation(world, 100, 64, 100, res1);
    ActionManager.PrevalidatedActionPlacement firstItem =
        new ActionManager.PrevalidatedActionPlacement(Map.of(UUID.randomUUID(), badLoc), 100, 100);
    actionManager.offerCachedPlacement("reval_action", firstItem);

    RTPLocation goodLoc = new RTPLocation(world, 200, 64, 200, res2);
    ActionManager.PrevalidatedActionPlacement secondItem =
        new ActionManager.PrevalidatedActionPlacement(Map.of(UUID.randomUUID(), goodLoc), 200, 200);
    actionManager.offerCachedPlacement("reval_action", secondItem);

    assertEquals(2, actionManager.getCachedPlacementCount("reval_action"));

    AtomicInteger liveCalls = new AtomicInteger(0);
    RTP.groupPlacementService = req -> {
      liveCalls.incrementAndGet();
      return CompletableFuture.completedFuture(
          GroupPlacementResult.success(Map.of(p1, new RTPLocation(world, 300, 64, 300))));
    };

    // Trigger should pop first, fail revalidation, close its reservation, tag memoryShape, pop second, succeed!
    ActionSessionResult result = actionManager.trigger("reval_action", List.of(p1), ActionContext.EMPTY).join();

    assertTrue(result.success());
    assertEquals(0, liveCalls.get(), "Should not fallback to live placement because second cached item succeeded");
    assertTrue(firstReservationClosed.get(), "First invalid placement chunk reservation must be closed (S-002)");
    Mockito.verify(mockShape).addBadChunk(any(long.class), any());

    actionManager.disarm(result.sessionId());
    RTP.selectionAPI.permRegionLookup.remove("default");
  }

  @Test
  @DisplayName("Excess candidate slots close reservations upon participant assignment (S-002)")
  void testExcessCandidateSlotsReleaseReservations() {
    UUID p1 = UUID.randomUUID();
    ActionDefinition.PlacementSpec pSpec =
        new ActionDefinition.PlacementSpec("default", "CIRCLE", 64, 16, 8, Map.of(), 3, 2);
    ActionDefinition def = new ActionDefinition(
        "excess_slots", "excess_slots", "rtp.action.excess", "",
        pSpec,
        ActionDefinition.ConfinementSpec.DEFAULT,
        ActionDefinition.LifecycleSpec.EMPTY);
    actionManager.registerAction(def);

    AtomicBoolean slot1Closed = new AtomicBoolean(false);
    AtomicBoolean slot2Closed = new AtomicBoolean(false);

    MockRTPWorld world = (MockRTPWorld) serverAccessor.getRTPWorld("world");
    RTPLocation loc1 = new RTPLocation(world, 10, 64, 10, createTrackingReservation(world, slot1Closed));
    RTPLocation loc2 = new RTPLocation(world, 20, 64, 20, createTrackingReservation(world, slot2Closed));

    Map<UUID, RTPLocation> candidateMap = new LinkedHashMap<>();
    candidateMap.put(UUID.randomUUID(), loc1);
    candidateMap.put(UUID.randomUUID(), loc2);

    ActionManager.PrevalidatedActionPlacement cached =
        new ActionManager.PrevalidatedActionPlacement(candidateMap, 15, 15);
    actionManager.offerCachedPlacement("excess_slots", cached);

    // Trigger action with 1 participant: slot 1 is used, slot 2 is excess
    ActionSessionResult result = actionManager.trigger("excess_slots", List.of(p1), ActionContext.EMPTY).join();
    assertTrue(result.success());

    assertTrue(slot2Closed.get(), "Unused excess candidate slot reservation must be closed immediately (S-002)");

    actionManager.disarm(result.sessionId());
  }

  @Test
  @DisplayName("Optional region memory inheritance for coordinate/landmark placements (ADR-097 1.3)")
  void testOptionalRegionMemoryInheritance() {
    // 1. Landmark action with memoryRegion specified
    Region customRegion = mock(Region.class);
    customRegion.name = "custom_region";
    RTP.selectionAPI.permRegionLookup.put("custom_region", customRegion);

    ActionDefinition.PlacementSpec landmarkSpec =
        new ActionDefinition.PlacementSpec(
            null, // no region
            "CIRCLE",
            64,
            16,
            8,
            Map.of("anchor", "fixed", "anchorX", 500, "anchorZ", 600, "memoryRegion", "custom_region"),
            3,
            2);
    ActionDefinition def = new ActionDefinition(
        "landmark_action", "landmark_action", "perm", "",
        landmarkSpec,
        ActionDefinition.ConfinementSpec.DEFAULT,
        ActionDefinition.LifecycleSpec.EMPTY);
    actionManager.registerAction(def);

    AtomicInteger capturedAnchorX = new AtomicInteger();
    AtomicInteger capturedAnchorZ = new AtomicInteger();
    java.util.concurrent.atomic.AtomicReference<String> capturedRegionName =
        new java.util.concurrent.atomic.AtomicReference<>();

    RTP.groupPlacementService = req -> {
      capturedRegionName.set(req.regionName());
      RTPCoords coords = req.anchorSource().resolveAnchor(null).join();
      capturedAnchorX.set(coords.x());
      capturedAnchorZ.set(coords.z());
      return CompletableFuture.completedFuture(
          GroupPlacementResult.success(Map.of(UUID.randomUUID(), new RTPLocation(serverAccessor.getRTPWorld("world"), 500, 64, 600))));
    };

    UUID p1 = UUID.randomUUID();
    ActionSessionResult res = actionManager.trigger("landmark_action", List.of(p1), ActionContext.EMPTY).join();
    assertTrue(res.success());

    assertEquals("custom_region", capturedRegionName.get(), "Placement request must inherit configured memoryRegion");
    assertEquals(500, capturedAnchorX.get());
    assertEquals(600, capturedAnchorZ.get());

    actionManager.disarm(res.sessionId());
    RTP.selectionAPI.permRegionLookup.remove("custom_region");
  }
}
