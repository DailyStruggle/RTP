package io.github.dailystruggle.rtp.common.tasks.teleport;

import io.github.dailystruggle.rtp.api.selection.GenerationContext;
import io.github.dailystruggle.rtp.api.world.ChunkReservation;
import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPScheduler;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.mock.TrackedMockWorld;
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

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * REQ-RTP-S-005 / REQ-RTP-S-002: a successful arrival keeps its destination chunk ticket for
 * {@link TeleportPipelineTask#ARRIVAL_HOLD_TICKS} so the arrived player's first tick never
 * sync-loads an already-unloaded landing chunk; the ticket is still released exactly once.
 */
class ReqRtpS005ArrivalChunkHoldTest {

  @TempDir
  File tempDir;

  private MockRTPServerAccessor accessor;
  private TrackedMockWorld world;
  private Region region;
  private MockRTPPlayer player;

  @BeforeEach
  void setUp() {
    MemoryTracker.reset();
    TeleportPipelineTask.flushArrivalReservations();
    accessor = RTPTestSetup.install(tempDir);
    world = new TrackedMockWorld("arrival_hold_world");
    accessor.addWorld(world);

    Circle circle = new Circle();
    circle.setRng(new Random(42L));
    RegionSettings settings = new RegionSettings(
        "arrival_hold_region", world, circle, new LinearAdjustor(new ArrayList<>()),
        false, false, 10L, 1000L, 0L, 5, 0.0, 1L, "", false);
    region = new Region("arrival_hold_region", settings);
    RTP.selectionAPI.permRegionLookup.put(region.name, region);

    player = new MockRTPPlayer(UUID.randomUUID(), "ArrivalHoldPlayer", new RTPLocation(world, 0, 64, 0)) {
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
    TeleportPipelineTask.flushArrivalReservations();
    MemoryTracker.reset();
    RTP.getInstance().latestTeleportData.clear();
    RTP.getInstance().processingPlayers.clear();
    RTP.scheduler = accessor.getMockScheduler();
    world.resetTicketCount();
  }

  private ChunkReservation reserve(int cx, int cz) {
    return reserve(world, cx, cz);
  }

  private static ChunkReservation reserve(TrackedMockWorld target, int cx, int cz) {
    List<CompletableFuture<Long>> chunks = new ArrayList<>();
    chunks.add(CompletableFuture.completedFuture(0L));
    ChunkSet set = new ChunkSet(target, cx, cz, chunks, CompletableFuture.completedFuture(true));
    return new ChunkReservation(set, target);
  }

  private void runPipeline(int x, int z) {
    ChunkReservation res = reserve(x >> 4, z >> 4);
    GenerationContext ctx = new GenerationContext(player, player, null);
    TeleportPipelineTask task =
        new TeleportPipelineTask(ctx, region, new RTPCoords(world.name(), x, 70, z), res);
    // Mock setLocation completes inline, so the teleport (and any hold) happens in run().
    task.run();
  }

  @Test
  @Timeout(value = 5, unit = TimeUnit.SECONDS)
  @DisplayName("REQ-RTP-S-005: successful arrival holds the landing ticket for exactly the hold window")
  void successfulArrivalHoldsTicketUntilHoldElapses() {
    runPipeline(100, 200);
    MockRTPScheduler scheduler = accessor.getMockScheduler();

    assertEquals(1, world.getActiveTicketCount(), "ticket must survive the teleport tick");
    assertEquals(1, TeleportPipelineTask.pendingArrivalReleaseCount());

    scheduler.tick(TeleportPipelineTask.ARRIVAL_HOLD_TICKS - 1);
    assertEquals(1, world.getActiveTicketCount(), "ticket must be held for the whole window");

    scheduler.tick(1);
    assertEquals(0, world.getActiveTicketCount(), "ticket must be released after the window (S-002)");
    assertEquals(0, TeleportPipelineTask.pendingArrivalReleaseCount());
  }

  @Test
  @Timeout(value = 5, unit = TimeUnit.SECONDS)
  @DisplayName("REQ-RTP-S-002: failed setLocation releases the landing ticket immediately")
  void failedArrivalReleasesImmediately() {
    player.setFailSetLocation(true);
    runPipeline(100, 200);

    assertEquals(0, world.getActiveTicketCount(), "failed teleport must not hold the ticket");
    assertEquals(0, TeleportPipelineTask.pendingArrivalReleaseCount());
  }

  @Test
  @Timeout(value = 5, unit = TimeUnit.SECONDS)
  @DisplayName("REQ-RTP-S-002: shutdown flush releases pending holds once; the delayed task is then a no-op")
  void flushReleasesPendingHoldsExactlyOnce() {
    runPipeline(100, 200);
    assertEquals(1, world.getActiveTicketCount());

    assertEquals(1, TeleportPipelineTask.flushArrivalReservations());
    assertEquals(0, world.getActiveTicketCount(), "flush must release the held ticket");

    accessor.getMockScheduler().tick(TeleportPipelineTask.ARRIVAL_HOLD_TICKS);
    assertEquals(0, world.getActiveTicketCount(), "delayed release must not double-release");
  }

  @Test
  @Timeout(value = 5, unit = TimeUnit.SECONDS)
  @DisplayName("REQ-RTP-S-002: an expiring hold leaves a newer reservation on the same chunk intact")
  void overlappingReservationSurvivesExpiringHold() {
    // Region-free world: the fixture region's cache tasks must not touch these counters.
    TrackedMockWorld isolated = new TrackedMockWorld("arrival_hold_isolated");
    ChunkReservation held = reserve(isolated, 6, 12);
    TeleportPipelineTask.holdArrivalReservation(held, isolated, 6, 12);
    accessor.getMockScheduler().tick(5);
    ChunkReservation newer = reserve(isolated, 6, 12);
    // One native ticket per chunk; activeChunkTickets counts holders (RTPWorld ref-count).
    assertEquals(2L, isolated.activeChunkTickets.get());
    assertEquals(1, isolated.getActiveTicketCount());

    accessor.getMockScheduler().tick(TeleportPipelineTask.ARRIVAL_HOLD_TICKS);
    assertEquals(1L, isolated.activeChunkTickets.get(), "only the expired hold may be released");
    assertEquals(1, isolated.getActiveTicketCount(), "native ticket must survive for the newer holder");

    newer.close();
    assertEquals(0L, isolated.activeChunkTickets.get());
    assertEquals(0, isolated.getActiveTicketCount());
  }

  @Test
  @Timeout(value = 5, unit = TimeUnit.SECONDS)
  @DisplayName("REQ-RTP-S-002: hold falls back to an immediate release without a scheduler")
  void holdWithoutSchedulerReleasesImmediately() {
    ChunkReservation held = reserve(1, 1);
    RTP.scheduler = null;
    TeleportPipelineTask.holdArrivalReservation(held, world, 1, 1);

    assertEquals(0, world.getActiveTicketCount());
    assertEquals(0, TeleportPipelineTask.pendingArrivalReleaseCount());
  }
}
