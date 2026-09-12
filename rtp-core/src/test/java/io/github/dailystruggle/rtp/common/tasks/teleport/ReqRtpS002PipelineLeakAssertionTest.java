package io.github.dailystruggle.rtp.common.tasks.teleport;

import io.github.dailystruggle.rtp.api.selection.GenerationContext;
import io.github.dailystruggle.rtp.api.selection.GenerationResult;
import io.github.dailystruggle.rtp.api.world.ChunkReservation;
import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockLocationGenerator;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.mock.TrackedMockWorld;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import io.github.dailystruggle.rtp.common.tools.MemoryTracker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MemoryTracker leak assertion test suite (REQ-RTP-S-002, ENTERPRISE_READINESS item 24).
 * <p>Asserts that after a full pipeline run across normal completion, cancellation,
 * failure paths, and active GC sweeps, all chunk tickets and tracked tasks/data return to zero.
 */
class ReqRtpS002PipelineLeakAssertionTest {

  @TempDir
  File tempDir;

  private MockRTPServerAccessor accessor;
  private TrackedMockWorld world;
  private Region region;
  private MockRTPPlayer player;

  @BeforeEach
  void setUp() {
    MemoryTracker.reset();
    accessor = RTPTestSetup.install(tempDir);
    world = new TrackedMockWorld("leak_test_world");
    accessor.addWorld(world);

    Circle circle = new Circle();
    circle.setRng(new Random(42L));
    LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());

    RegionSettings settings = new RegionSettings(
        "leak_test_region",
        world,
        circle,
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

    region = new Region("leak_test_region", settings);
    RTP.selectionAPI.permRegionLookup.put(region.name, region);

    player = new MockRTPPlayer(UUID.randomUUID(), "LeakTestPlayer", new RTPLocation(world, 0, 64, 0)) {
      @Override
      public boolean hasPermission(String permission) {
        if ("rtp.noCancel".equalsIgnoreCase(permission)) {
          return false;
        }
        return super.hasPermission(permission);
      }
    };
    accessor.addPlayer(player);
  }

  @AfterEach
  void tearDown() {
    MemoryTracker.reset();
    RTP.getInstance().latestTeleportData.clear();
    RTP.getInstance().processingPlayers.clear();
    if (world != null) {
      world.resetTicketCount();
    }
  }

  private ChunkReservation createMockReservation(TrackedMockWorld targetWorld, int cx, int cz) {
    List<CompletableFuture<Long>> chunks = new ArrayList<>();
    chunks.add(CompletableFuture.completedFuture(0L));
    ChunkSet set = new ChunkSet(targetWorld, cx, cz, chunks, CompletableFuture.completedFuture(true));
    return new ChunkReservation(set, targetWorld);
  }

  @Test
  @Timeout(value = 5, unit = TimeUnit.SECONDS)
  @DisplayName("REQ-RTP-S-002: normal completion path frees all tickets and untracks pipeline tasks")
  void normalCompletionLeavesZeroTicketsAndTasks() {
    assertEquals(0, world.getActiveTicketCount(), "Precondition: 0 active tickets");
    assertEquals(0, MemoryTracker.trackedCount(), "Precondition: 0 tracked objects");

    RTPCoords targetCoords = new RTPCoords(world.name(), 100, 70, 200);
    ChunkReservation res = createMockReservation(world, 100 >> 4, 200 >> 4);

    assertEquals(1, world.getActiveTicketCount(), "Reservation holds active ticket");

    GenerationContext ctx = new GenerationContext(player, player, null);
    TeleportPipelineTask task = new TeleportPipelineTask(ctx, region, targetCoords, res);

    assertTrue(MemoryTracker.trackedCount() > 0, "Pipeline task must be registered in MemoryTracker");
    assertEquals(1, MemoryTracker.trackedCountByLabel("TeleportPipelineTask"));

    // Drive the pipeline to completion: LOAD -> TELEPORT -> CLEANUP
    task.run();
    accessor.getMockScheduler().tick(1);

    assertEquals(0, world.getActiveTicketCount(),
        "Active chunk tickets must return to 0 after normal pipeline completion (S-002)");
    assertEquals(0, MemoryTracker.trackedCount(),
        "MemoryTracker entries (tasks and TeleportData) must return to 0 after completion");
    TeleportData data = RTP.getInstance().latestTeleportData.get(player.uuid());
    assertNotNull(data, "latestTeleportData must be recorded on successful completion");
    assertTrue(data.completed, "TeleportData must be marked completed");
  }

  @Test
  @Timeout(value = 5, unit = TimeUnit.SECONDS)
  @DisplayName("REQ-RTP-S-002: cancellation during SETUP frees all tickets and untracks tasks")
  void cancellationDuringSetupLeavesZeroTicketsAndTasks() {
    assertEquals(0, world.getActiveTicketCount());
    assertEquals(0, MemoryTracker.trackedCount());

    GenerationContext ctx = new GenerationContext(player, player, null);
    TeleportPipelineTask task = new TeleportPipelineTask(ctx, region);

    assertTrue(MemoryTracker.trackedCount() > 0);
    task.setCancelled(true);

    task.run();

    assertEquals(0, world.getActiveTicketCount(),
        "Chunk tickets must be 0 after cancellation in SETUP");
    assertEquals(0, MemoryTracker.trackedCount(),
        "MemoryTracker must return to 0: TeleportPipelineTask=" + MemoryTracker.trackedCountByLabel("TeleportPipelineTask")
            + ", TeleportData=" + MemoryTracker.trackedCountByLabel("TeleportData-" + player.uuid()));
  }

  @Test
  @Timeout(value = 5, unit = TimeUnit.SECONDS)
  @DisplayName("REQ-RTP-S-002: cancellation during LOAD with active reservation closes reservation and untracks")
  void cancellationDuringLoadLeavesZeroTicketsAndTasks() {
    assertEquals(0, world.getActiveTicketCount());
    assertEquals(0, MemoryTracker.trackedCount());

    RTPCoords targetCoords = new RTPCoords(world.name(), 64, 64, 64);
    ChunkReservation res = createMockReservation(world, 4, 4);
    assertEquals(1, world.getActiveTicketCount());

    GenerationContext ctx = new GenerationContext(player, player, null);
    TeleportPipelineTask task = new TeleportPipelineTask(ctx, region, targetCoords, res);

    task.setCancelled(true);
    task.run();

    assertEquals(0, world.getActiveTicketCount(),
        "Chunk tickets must return to 0 after cancelling task holding reservation (S-002)");
    assertEquals(0, MemoryTracker.trackedCount(),
        "MemoryTracker must return to 0 after cancellation during LOAD");
  }

  @Test
  @Timeout(value = 5, unit = TimeUnit.SECONDS)
  @DisplayName("REQ-RTP-S-002: cancellation via RTPTeleportCancel clears tickets and tasks")
  void cancellationViaRTPTeleportCancelLeavesZeroTicketsAndTasks() {
    assertEquals(0, world.getActiveTicketCount());
    assertEquals(0, MemoryTracker.trackedCount());

    RTPCoords targetCoords = new RTPCoords(world.name(), 32, 64, 32);
    ChunkReservation res = createMockReservation(world, 2, 2);

    GenerationContext ctx = new GenerationContext(player, player, null);
    TeleportPipelineTask task = new TeleportPipelineTask(ctx, region, targetCoords, res);

    TeleportData data = new TeleportData();
    data.sender = player;
    data.completed = false;
    data.nextTask = task;
    RTP.getInstance().latestTeleportData.put(player.uuid(), data);
    RTP.getInstance().processingPlayers.add(player.uuid());

    new RTPTeleportCancel(player.uuid()).run();
    assertTrue(task.isCancelled(), "Task must be marked cancelled by RTPTeleportCancel");

    // Execute task cleanup
    task.run();

    assertEquals(0, world.getActiveTicketCount(),
        "Chunk tickets must return to 0 after cancellation via RTPTeleportCancel");
    assertEquals(0, MemoryTracker.trackedCount(),
        "MemoryTracker must return to 0 after cancellation via RTPTeleportCancel");
  }

  @Test
  @Timeout(value = 5, unit = TimeUnit.SECONDS)
  @DisplayName("REQ-RTP-S-002: failure when no safe location is found frees all tickets and untracks tasks")
  void failureNoSafeLocationLeavesZeroTicketsAndTasks() {
    assertEquals(0, world.getActiveTicketCount());
    assertEquals(0, MemoryTracker.trackedCount());

    // Mock LocationGenerator returning null coords
    accessor.setLocationGenerator(new MockLocationGenerator(world) {
      @Override
      public CompletableFuture<GenerationResult> getLocation(Object r, GenerationContext c) {
        return CompletableFuture.completedFuture(new GenerationResult(null, 10, null));
      }
    });

    GenerationContext ctx = new GenerationContext(player, player, null);
    TeleportPipelineTask task = new TeleportPipelineTask(ctx, region);

    task.run();

    assertEquals(0, world.getActiveTicketCount(),
        "Tickets must be 0 after failure to find safe location");
    assertEquals(0, MemoryTracker.trackedCount(),
        "MemoryTracker must return to 0 after failure to find safe location");
  }

  @Test
  @Timeout(value = 5, unit = TimeUnit.SECONDS)
  @DisplayName("REQ-RTP-S-002: failure when generator throws exception frees all tickets and untracks tasks")
  void failureGeneratorExceptionLeavesZeroTicketsAndTasks() {
    assertEquals(0, world.getActiveTicketCount());
    assertEquals(0, MemoryTracker.trackedCount());

    accessor.setLocationGenerator(new MockLocationGenerator(world) {
      @Override
      public CompletableFuture<GenerationResult> getLocation(Object r, GenerationContext c) {
        throw new RuntimeException("Simulated generation explosion");
      }
    });

    GenerationContext ctx = new GenerationContext(player, player, null);
    TeleportPipelineTask task = new TeleportPipelineTask(ctx, region);

    assertDoesNotThrow(() -> task.run());

    assertEquals(0, world.getActiveTicketCount(),
        "Tickets must be 0 when generator throws exception");
    assertEquals(0, MemoryTracker.trackedCount(),
        "MemoryTracker must return to 0 when generator throws exception");
  }

  @Test
  @Timeout(value = 5, unit = TimeUnit.SECONDS)
  @DisplayName("REQ-RTP-S-002: active GC sweep force-closes stalled pipeline task and releases tickets")
  void activeGcSweepReclaimsStalledPipeline() {
    assertEquals(0, world.getActiveTicketCount());
    assertEquals(0, MemoryTracker.trackedCount());

    RTPCoords targetCoords = new RTPCoords(world.name(), 48, 64, 48);
    ChunkReservation res = createMockReservation(world, 3, 3);
    assertEquals(1, world.getActiveTicketCount());

    GenerationContext ctx = new GenerationContext(player, player, null);
    TeleportPipelineTask task = new TeleportPipelineTask(ctx, region, targetCoords, res);

    // Track it with 0ms lifespan so it is immediately considered leaking
    MemoryTracker.reset();
    MemoryTracker.track(task, "StalledTeleportPipelineTask", 0L);
    try {
      Thread.sleep(10);
    } catch (InterruptedException ignored) {}
    assertEquals(1, MemoryTracker.trackedCount());

    // Trigger active GC sweep
    MemoryTracker.runDiagnostics();

    // The sweep force-closes the pipeline and dispatches it onto the scheduler
    assertTrue(task.isCancelled(), "Active GC sweep must mark the stalled pipeline cancelled");
    accessor.getMockScheduler().tick(1);

    assertEquals(0, world.getActiveTicketCount(),
        "Chunk tickets must return to 0 after GC sweep force-closes stalled pipeline (S-002)");
    assertEquals(0, MemoryTracker.trackedCount(),
        "MemoryTracker must return to 0 after GC sweep cleans up stalled pipeline");
  }
}
