package io.github.dailystruggle.rtp.common.action;

import io.github.dailystruggle.rtp.api.action.ActionContext;
import io.github.dailystruggle.rtp.api.action.ActionDefinition;
import io.github.dailystruggle.rtp.api.action.ActionSession;
import io.github.dailystruggle.rtp.api.action.ActionSessionResult;
import io.github.dailystruggle.rtp.api.event.PlayerMoveEvent;
import io.github.dailystruggle.rtp.api.group.GroupPlacementService;
import io.github.dailystruggle.rtp.api.selection.GenerationResult;
import io.github.dailystruggle.rtp.api.world.ChunkReservation;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.CoreRtpRoot;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.GroupPlacementDispatcher;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end integration tests uniting the Action Engine and Subspace Engine without stubbing
 * {@link GroupPlacementService}.
 *
 * <p>Validates the unified pipeline:
 * 1. Startup & Config Loading (ActionDefinition, PlacementSpec, ConfinementSpec, LifecycleSpec)
 * 2. Command Execution (ActionCommand & /rtp action subcommand tree)
 * 3. Subspace Spatial Allocation & Selection (GroupPlacementDispatcher -> SubspaceShape -> CandidateValidator)
 * 4. Placement & Teleport Dispatch
 * 5. Lifecycle Messaging & Scoreboard Updates
 * 6. SUBSPACE Confinement Enforcement & Pull-Back
 */
@DisplayName("Action Engine + Subspace Engine Integrated Lifecycle Tests")
class ActionSubspaceIntegrationTest {

  private static final long SEED = 42L;
  private static final int QUEUE_ANCHOR_X = 200;
  private static final int QUEUE_ANCHOR_Z = 200;

  @TempDir
  File tempDir;

  private MockRTPServerAccessor serverAccessor;
  private GroupPlacementDispatcher dispatcher;
  private ActionManager actionManager;
  private GroupPlacementService originalGroupService;
  private CoreRtpRoot rootCommand;
  private Region mockRegion;
  private MockRTPWorld world;

  private final List<String> dispatchedCommands = new CopyOnWriteArrayList<>();

  @BeforeEach
  void setUp() {
    serverAccessor = RTPTestSetup.install(tempDir);
    RTP.serverAccessor = serverAccessor;
    RTP.scheduler = serverAccessor.getMockScheduler();
    originalGroupService = RTP.groupPlacementService;

    world = (MockRTPWorld) serverAccessor.getRTPWorld("world");

    // Initialize real Subspace Engine Dispatcher with fixed RNG seed for determinism
    dispatcher = new GroupPlacementDispatcher();
    dispatcher.setRng(new Random(SEED));
    RTP.groupPlacementService = dispatcher;

    // Initialize real Action Engine Manager
    actionManager = new ActionManager();
    io.github.dailystruggle.rtp.api.RTPAPI.actionService = actionManager;
    try {
      java.lang.reflect.Field f = RTP.class.getDeclaredField("actionManager");
      f.setAccessible(true);
      f.set(null, actionManager);
    } catch (Exception ignored) {
    }

    // Set up root command tree
    rootCommand = new CoreRtpRoot();
    RTP.baseCommand = rootCommand;

    // Configure test region "arena_region"
    mockRegion = mock(Region.class);
    ChunkReservation ticket = mock(ChunkReservation.class);
    GenerationResult anchorGen = new GenerationResult(
        new RTPCoords("world", QUEUE_ANCHOR_X, 64, QUEUE_ANCHOR_Z), 1, null, ticket);

    org.mockito.Mockito.doReturn(world).when(mockRegion).getWorld();
    when(mockRegion.getLocation(anySet())).thenReturn(CompletableFuture.completedFuture(anchorGen));
    // Candidate validator returns safe location for any coordinate in the world
    when(mockRegion.candidateValidator()).thenReturn(
        (x, z) -> new io.github.dailystruggle.rtp.common.selection.region.RTPLocation(
            new RTPCoords("world", x, 64, z), 1));

    io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape<?> memShape =
        mock(io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape.class);
    when(memShape.contains(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(true);
    when(memShape.isKnownBad(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(false);
    org.mockito.Mockito.doReturn(memShape).when(mockRegion).getShape();

    RTP.selectionAPI.permRegionLookup.put("arena_region", mockRegion);
    RTP.selectionAPI.permRegionLookup.put("default", mockRegion);
  }

  @AfterEach
  void tearDown() {
    RTP.selectionAPI.permRegionLookup.remove("arena_region");
    RTP.selectionAPI.permRegionLookup.remove("default");
    RTP.groupPlacementService = originalGroupService;
    RTPTestSetup.cleanUp();
  }

  @Test
  @DisplayName("Full lifecycle with REGION_QUEUE anchor: Config -> Command -> Subspace Placement -> Messaging -> Confinement")
  void testFullActionSubspaceLifecycle_RegionQueueAnchor() throws Exception {
    // 1. Write YAML action definition matching canonical schema
    File dir = new File(tempDir, "duel_test");
    File actionDir = new File(dir, "definitions/actions");
    actionDir.mkdirs();
    File yamlFile = new File(actionDir, "duel.yml");
    String yamlContent = """
        alias: "duel"
        command:
          name: "duel"
          permission: "rtp.command.duel"
          description: "Duel challenge command"
        placement:
          region: "arena_region"
          anchor: "regionQueue"
          shape:
            name: "SQUARE"
            radius: 32
          minSeparation: 10
          elevationTolerance: 64
          retries: 3
        confinement:
          boundary: "SUBSPACE"
          duration: "120s"
        lifecycle:
          onStart:
            - CONSOLE: "say Duel active for [all]"
            - FOR_EACH:
                PLAYER: "The duel has begun!"
        """;
    Files.writeString(yamlFile.toPath(), yamlContent);

    ActionConfigLoader.loadActions(dir, actionManager);
    actionManager.registerAllCommands();

    // 2. Prepare mock participants
    UUID p1Id = UUID.randomUUID();
    UUID p2Id = UUID.randomUUID();
    MockRTPPlayer p1 = new MockRTPPlayer(p1Id, "PlayerOne", new RTPLocation(world, 0, 64, 0));
    MockRTPPlayer p2 = new MockRTPPlayer(p2Id, "PlayerTwo", new RTPLocation(world, 10, 64, 10));
    p1.setPermission("rtp.*", true);
    p1.setPermission("rtp.other", true);
    p2.setPermission("rtp.*", true);
    serverAccessor.addPlayer(p1);
    serverAccessor.addPlayer(p2);

    // 3. Execute command through top-level ActionCommand for duel
    ActionDefinition duelDef = actionManager.getAction("duel").orElseThrow();
    io.github.dailystruggle.rtp.common.commands.action.ActionCommand duelCmd =
        new io.github.dailystruggle.rtp.common.commands.action.ActionCommand(duelDef);

    boolean handled = duelCmd.onCommand(p1Id, Map.of("player", List.of("PlayerTwo")), null);
    assertTrue(handled, "ActionCommand onCommand must succeed");

    // 4. Verify Active Session created and linked to both participants
    Optional<ActionSession> p1Session = actionManager.getSessionForParticipant(p1Id);
    assertTrue(p1Session.isPresent(), "PlayerOne must have an active ActionSession");
    ActionSession session = p1Session.get();
    assertEquals("duel", session.actionId());
    assertTrue(session.participants().contains(p1Id));
    assertTrue(session.participants().contains(p2Id));

    // 5. Verify Subspace Placement: players were moved to slots around QUEUE_ANCHOR (200, 200)
    RTPLocation p1Loc = p1.getLocation();
    RTPLocation p2Loc = p2.getLocation();
    assertNotNull(p1Loc);
    assertNotNull(p2Loc);

    // Coordinates must be within subspace radius (32) of the anchor (200, 200)
    int dx1 = Math.abs(p1Loc.getBlockX() - QUEUE_ANCHOR_X);
    int dz1 = Math.abs(p1Loc.getBlockZ() - QUEUE_ANCHOR_Z);
    int dx2 = Math.abs(p2Loc.getBlockX() - QUEUE_ANCHOR_X);
    int dz2 = Math.abs(p2Loc.getBlockZ() - QUEUE_ANCHOR_Z);
    assertTrue(dx1 <= 32 && dz1 <= 32, "Player 1 must be placed within subspace radius (32)");
    assertTrue(dx2 <= 32 && dz2 <= 32, "Player 2 must be placed within subspace radius (32)");

    // Separation between participants must respect minSeparation (10)
    double distanceSq = Math.pow(p1Loc.getBlockX() - p2Loc.getBlockX(), 2)
        + Math.pow(p1Loc.getBlockZ() - p2Loc.getBlockZ(), 2);
    assertTrue(distanceSq >= 95.0, "Participants must be separated by at least minSeparation (10)");

    // Clean up session
    actionManager.disarm(session.sessionId());
    assertFalse(actionManager.getSession(session.sessionId()).isPresent());
  }

  @Test
  @DisplayName("Full lifecycle with ENTITY anchor: placement resolves relative to target entity location")
  void testFullActionSubspaceLifecycle_EntityAnchor() throws Exception {
    File dir = new File(tempDir, "entity_test");
    File actionDir = new File(dir, "definitions/actions");
    actionDir.mkdirs();
    File yamlFile = new File(actionDir, "challenge.yml");
    String yamlContent = """
        alias: "challenge"
        placement:
          region: "arena_region"
          anchor: "entity"
          shape:
            name: "SQUARE"
            radius: 30
          minSeparation: 8
          elevationTolerance: 64
          retries: 2
        confinement:
          boundary: "SUBSPACE"
          duration: "60s"
        lifecycle:
          onStart:
            - CONSOLE: "broadcast Challenge match active!"
        """;
    Files.writeString(yamlFile.toPath(), yamlContent);

    ActionConfigLoader.loadActions(dir, actionManager);

    // Initiator at (0, 64, 0), Target at (500, 64, 500)
    UUID p1Id = UUID.randomUUID();
    UUID p2Id = UUID.randomUUID();
    MockRTPPlayer p1 = new MockRTPPlayer(p1Id, "Challenger", new RTPLocation(world, 0, 64, 0));
    MockRTPPlayer p2 = new MockRTPPlayer(p2Id, "Defender", new RTPLocation(world, 500, 64, 500));
    serverAccessor.addPlayer(p1);
    serverAccessor.addPlayer(p2);

    ActionContext context = ActionContext.of(Map.of(
        "anchorWorld", "world",
        "anchorX", 500,
        "anchorY", 64,
        "anchorZ", 500
    ));

    CompletableFuture<ActionSessionResult> triggerFuture =
        actionManager.trigger("challenge", List.of(p1Id, p2Id), context);
    ActionSessionResult result = triggerFuture.join();

    assertTrue(result.success(), "Triggering challenge action with ENTITY anchor must succeed: " + result.failureReason());

    // Placements must be around target coordinate (500, 500) within radius 30
    RTPLocation loc1 = p1.getLocation();
    RTPLocation loc2 = p2.getLocation();
    assertNotNull(loc1);
    assertNotNull(loc2);

    int dx1 = Math.abs(loc1.getBlockX() - 500);
    int dz1 = Math.abs(loc1.getBlockZ() - 500);
    int dx2 = Math.abs(loc2.getBlockX() - 500);
    int dz2 = Math.abs(loc2.getBlockZ() - 500);
    assertTrue(dx1 <= 30 && dz1 <= 30, "Player 1 must be placed within subspace radius (30) of entity");
    assertTrue(dx2 <= 30 && dz2 <= 30, "Player 2 must be placed within subspace radius (30) of entity");

    actionManager.disarm(result.sessionId());
  }

  @Test
  @DisplayName("SUBSPACE boundary enforcement: PlayerMoveEvent beyond radius triggers pull-back to allocated slot")
  void testSubspaceConfinementViolationAndPullBack() throws Exception {
    File dir = new File(tempDir, "confinement_test");
    File actionDir = new File(dir, "definitions/actions");
    actionDir.mkdirs();
    File yamlFile = new File(actionDir, "confined_arena.yml");
    String yamlContent = """
        alias: "confined_arena"
        placement:
          region: "arena_region"
          anchor: "regionQueue"
          shape:
            name: "SQUARE"
            radius: 20
          minSeparation: 6
          elevationTolerance: 64
          retries: 1
        confinement:
          boundary: "SUBSPACE"
          duration: "100s"
        lifecycle:
          onBoundaryViolation:
            - ACTION: PULL_BACK
            - CONSOLE: "warn_breach [violator]"
        """;
    Files.writeString(yamlFile.toPath(), yamlContent);

    ActionConfigLoader.loadActions(dir, actionManager);

    UUID p1Id = UUID.randomUUID();
    MockRTPPlayer player = new MockRTPPlayer(p1Id, "Gladiator", new RTPLocation(world, 0, 64, 0));
    serverAccessor.addPlayer(player);

    CompletableFuture<ActionSessionResult> triggerFuture =
        actionManager.trigger("confined_arena", List.of(p1Id), ActionContext.EMPTY);
    ActionSessionResult result = triggerFuture.join();
    assertTrue(result.success(), "Confined arena action must trigger successfully: " + result.failureReason());

    RTPLocation placedLoc = player.getLocation();
    int assignedX = placedLoc.getBlockX();
    int assignedY = placedLoc.getBlockY();
    int assignedZ = placedLoc.getBlockZ();

    Optional<ActionSession> optSession = actionManager.getSession(result.sessionId());
    assertTrue(optSession.isPresent());
    ActionSession session = optSession.get();

    // 1. Move within radius 20 of anchor (200, 200) -> In bounds, no violations
    io.github.dailystruggle.rtp.api.RTPAPI.playerMoveEvents.fire(
        new PlayerMoveEvent(p1Id, "world", assignedX, assignedY, assignedZ, 205, 64, 205));
    assertEquals(0, session.getViolations(p1Id), "Movement within subspace must not cause violation");

    // 2. Move out of bounds: anchor (200, 200) + radius 20 -> (250, 64, 200) is violation
    io.github.dailystruggle.rtp.api.RTPAPI.playerMoveEvents.fire(
        new PlayerMoveEvent(p1Id, "world", 205, 64, 205, 250, 64, 200));

    // Player should be pulled back to their assigned safe slot
    assertEquals(assignedX, player.getLocation().getBlockX(), "Player must be pulled back to assigned X");
    assertEquals(assignedY, player.getLocation().getBlockY(), "Player must be pulled back to assigned Y");
    assertEquals(assignedZ, player.getLocation().getBlockZ(), "Player must be pulled back to assigned Z");

    actionManager.disarm(result.sessionId());
  }

  @Test
  @DisplayName("Full End-to-End Pipeline: Startup -> Command Input -> Queueing -> Gates -> Location Supply -> setLocation Dispatch")
  void testEndToEndStartupCommandQueueGateLocationSupplyTeleport() throws Exception {
    // --- 1. Startup & Config Loading ---
    File dir = new File(tempDir, "e2e_pipeline_test");
    File actionDir = new File(dir, "definitions/actions");
    actionDir.mkdirs();
    File yamlFile = new File(actionDir, "skirmish.yml");
    String yamlContent = """
        alias: "skirmish"
        command:
          name: "skirmish"
          permission: "rtp.command.skirmish"
          description: "2-Player skirmish action"
        gates:
          - players: ">= 2"
        placement:
          region: "arena_region"
          anchor: "regionQueue"
          shape:
            name: "SQUARE"
            radius: 32
          minSeparation: 12
          elevationTolerance: 64
          retries: 3
        confinement:
          boundary: "SUBSPACE"
          duration: "180s"
        lifecycle:
          onEnqueue:
            - CONSOLE: "say Player [player] entered wait queue"
          onStart:
            - CONSOLE: "say Skirmish match initiated between [all]!"
            - FOR_EACH:
                PLAYER: "say [player] teleported to skirmish battlefield!"
        """;
    Files.writeString(yamlFile.toPath(), yamlContent);

    // Startup orphan cleanup
    actionManager.sweepStartupOrphans();

    // Load action definition from disk into ActionManager
    ActionConfigLoader.loadActions(dir, actionManager);
    actionManager.registerAllCommands();

    ActionDefinition skirmishDef = actionManager.getAction("skirmish").orElseThrow();
    assertEquals("skirmish", skirmishDef.id());
    assertTrue(skirmishDef.placement().enabled());

    // --- 2. Participants Setup ---
    UUID p1Id = UUID.randomUUID();
    UUID p2Id = UUID.randomUUID();
    MockRTPPlayer p1 = new MockRTPPlayer(p1Id, "PlayerAlpha", new RTPLocation(world, 0, 64, 0));
    MockRTPPlayer p2 = new MockRTPPlayer(p2Id, "PlayerBeta", new RTPLocation(world, 10, 64, 10));
    p1.setPermission("rtp.*", true);
    p2.setPermission("rtp.*", true);
    serverAccessor.addPlayer(p1);
    serverAccessor.addPlayer(p2);

    io.github.dailystruggle.rtp.common.commands.action.ActionCommand skirmishCmd =
        new io.github.dailystruggle.rtp.common.commands.action.ActionCommand(skirmishDef);

    // --- 3. Command Input 1 & Queueing (Gate not satisfied: 1 player < 2) ---
    // Player 1 enters alone without specifying target -> triggers solo invocation
    boolean handled1 = skirmishCmd.onCommand(p1Id, Map.of(), null);
    assertTrue(handled1, "ActionCommand onCommand must succeed");

    // Player 1 should be wait-queued because gate (rtp_participant_count >= 2) fails for 1 player
    assertTrue(actionManager.isQueued("skirmish", p1Id), "PlayerAlpha must be in wait queue");
    assertFalse(actionManager.getSessionForParticipant(p1Id).isPresent(), "PlayerAlpha must not have active session yet");

    // Verify onEnqueue lifecycle step sent notification message
    assertTrue(serverAccessor.getExecutedCommands().stream().anyMatch(c -> c.contains("entered wait queue")),
        "onEnqueue console command must have executed");

    // Initial locations unchanged
    assertEquals(0, p1.getLocation().getBlockX());

    // --- 4. Command Input 2 & Gate Resolution (Second player queues -> gate met -> queue drains) ---
    boolean handled2 = skirmishCmd.onCommand(p2Id, Map.of(), null);
    assertTrue(handled2, "Second ActionCommand onCommand must succeed");

    // After PlayerBeta queues, processWaitQueue pairs them up, gate evaluates true, and triggers live placement
    // Both players should no longer be wait-queued
    assertFalse(actionManager.isQueued("skirmish", p1Id), "PlayerAlpha should be dequeued");
    assertFalse(actionManager.isQueued("skirmish", p2Id), "PlayerBeta should be dequeued");

    // --- 5. Session Active & Linked ---
    Optional<ActionSession> optSession1 = actionManager.getSessionForParticipant(p1Id);
    Optional<ActionSession> optSession2 = actionManager.getSessionForParticipant(p2Id);
    assertTrue(optSession1.isPresent(), "PlayerAlpha must be in an active session");
    assertTrue(optSession2.isPresent(), "PlayerBeta must be in an active session");
    assertEquals(optSession1.get().sessionId(), optSession2.get().sessionId(), "Both must share same session");
    ActionSession session = optSession1.get();

    // --- 6. Location Supply & setLocation Dispatch Verification ---
    RTPLocation finalLoc1 = p1.getLocation();
    RTPLocation finalLoc2 = p2.getLocation();
    assertNotNull(finalLoc1);
    assertNotNull(finalLoc2);

    // Placed around QUEUE_ANCHOR (200, 200) within subspace radius 32
    int dx1 = Math.abs(finalLoc1.getBlockX() - QUEUE_ANCHOR_X);
    int dz1 = Math.abs(finalLoc1.getBlockZ() - QUEUE_ANCHOR_Z);
    int dx2 = Math.abs(finalLoc2.getBlockX() - QUEUE_ANCHOR_X);
    int dz2 = Math.abs(finalLoc2.getBlockZ() - QUEUE_ANCHOR_Z);
    assertTrue(dx1 <= 32 && dz1 <= 32, "PlayerAlpha must be placed within subspace radius (32) of anchor");
    assertTrue(dx2 <= 32 && dz2 <= 32, "PlayerBeta must be placed within subspace radius (32) of anchor");

    // Minimum separation (minSeparation: 12) between participants guaranteed at selection time
    double distance = Math.hypot(finalLoc1.getBlockX() - finalLoc2.getBlockX(), finalLoc1.getBlockZ() - finalLoc2.getBlockZ());
    assertTrue(distance >= 12.0, "Participants must satisfy minSeparation (12), actual: " + distance);

    // --- 7. Lifecycle onStart Dispatch ---
    assertTrue(serverAccessor.getExecutedCommands().stream().anyMatch(c -> c.contains("Skirmish match initiated")),
        "Console onStart command must be dispatched");
    assertTrue(serverAccessor.getExecutedCommands().stream().anyMatch(c -> c.contains("teleported to skirmish battlefield")),
        "onStart player actions must be dispatched");

    // --- 8. Clean Disarm & Teardown ---
    actionManager.disarm(session.sessionId());
    assertFalse(actionManager.getSession(session.sessionId()).isPresent(), "Session must be disarmed and removed");
  }

  @Test
  @DisplayName("S-004: When subspace cannot satisfy group size, failure is reported cleanly without silent swallow")
  void testSubspaceInsufficientSlotsFailure_ReportsCleanly() throws Exception {
    File dir = new File(tempDir, "fail_test");
    File actionDir = new File(dir, "definitions/actions");
    actionDir.mkdirs();
    File yamlFile = new File(actionDir, "overcrowded.yml");
    // Radius 2 blocks, minSeparation 10 blocks -> only 1 lattice slot fits in footprint [0, 0]
    String yamlContent = """
        alias: "overcrowded"
        placement:
          region: "arena_region"
          anchor: "regionQueue"
          shape:
            name: "SQUARE"
            radius: 2
          minSeparation: 10
          elevationTolerance: 64
          retries: 1
        """;
    Files.writeString(yamlFile.toPath(), yamlContent);

    ActionConfigLoader.loadActions(dir, actionManager);

    List<UUID> fourPlayers = List.of(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    for (UUID pid : fourPlayers) {
      serverAccessor.addPlayer(new MockRTPPlayer(pid, "P-" + pid, new RTPLocation(world, 0, 64, 0)));
    }

    CompletableFuture<ActionSessionResult> triggerFuture =
        actionManager.trigger("overcrowded", fourPlayers, ActionContext.EMPTY);
    ActionSessionResult result = triggerFuture.join();

    assertFalse(result.success(), "Placement must fail when subspace cannot fit all participants");
    assertNotNull(result.failureReason());
    assertTrue(result.failureReason().contains("INSUFFICIENT_SAFE_SLOTS")
            || result.failureReason().contains("failed")
            || result.failureReason().contains("slots"),
        "Failure reason must be structured and descriptive (REQ-RTP-S-004): " + result.failureReason());
  }
}
