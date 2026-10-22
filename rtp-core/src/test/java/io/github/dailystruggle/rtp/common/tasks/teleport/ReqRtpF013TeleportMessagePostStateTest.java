package io.github.dailystruggle.rtp.common.tasks.teleport;

import io.github.dailystruggle.rtp.api.selection.GenerationContext;
import io.github.dailystruggle.rtp.api.world.ChunkReservation;
import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.common.database.ActiveDatabase;
import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.mock.TrackedMockWorld;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.playerData.TeleportSessions;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.selection.ActiveSelection;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * REQ-RTP-F-013: the teleport success message shall render the post-teleport state. Placeholders
 * it carries ([processingTime], [lockUses], [remainingLockUses], ...) must already include the
 * completed teleport when the message is formatted.
 */
class ReqRtpF013TeleportMessagePostStateTest {

  @TempDir File tempDir;

  private TrackedMockWorld world;
  private MockRTPServerAccessor accessor;

  /** Player whose teleport completes only when the test completes {@link #pending}. */
  private static final class DeferredPlayer extends MockRTPPlayer {
    final CompletableFuture<Boolean> pending = new CompletableFuture<>();
    volatile TeleportData watched;
    volatile long processingAtMessage = -1L;

    DeferredPlayer(UUID uuid, String name, RTPLocation loc) {
      super(uuid, name, loc);
    }

    @Override
    public CompletableFuture<Boolean> setLocation(RTPLocation to) {
      return pending;
    }

    @Override
    public void sendMessage(String message) {
      if (watched != null) processingAtMessage = watched.processingTime;
      super.sendMessage(message);
    }
  }

  @BeforeEach
  void setUp() {
    RTPTestSetup.install(tempDir);
    world = new TrackedMockWorld("post_state_world");
    accessor = (MockRTPServerAccessor) RTPRuntime.serverAccessor();
    accessor.addWorld(world);
    RTPRunnable.scheduler = accessor.getMockScheduler();
    TeleportPipelineTask.ConfigCache.reload();
    TeleportPipelineTask.teleportPostActions.clear();
    TeleportSessions.current().latestTeleportData.clear();
    TeleportSessions.current().processingPlayers.clear();
    TeleportSessions.current().invulnerablePlayers.clear();

    TeleportPipelineTask.ConfigCache.setRespawnOnTeleport = false;
    TeleportPipelineTask.ConfigCache.postTeleportQueueing = false;
    TeleportPipelineTask.ConfigCache.lockAfterUses = 5;
    TeleportPipelineTask.ConfigCache.lockAfterResetMillis = 60_000L;
    // [lockUses] / [remainingLockUses] read the configured cap and window, not ConfigCache.
    ActiveConfigs.get().getParser(ConfigKeys.class).set(ConfigKeys.lockAfterUses, 5L);
    ActiveConfigs.get().getParser(ConfigKeys.class).set(ConfigKeys.lockAfterResetSeconds, 60L);
    ActiveConfigs.get().getParser(SafetyKeys.class).set(SafetyKeys.invulnerabilityTime, 0L);
    ActiveDatabase.setAccessor(Mockito.mock(DatabaseAccessor.class));
  }

  @AfterEach
  void tearDown() {
    TeleportPipelineTask.teleportPostActions.clear();
    TeleportSessions.current().latestTeleportData.clear();
    TeleportSessions.current().processingPlayers.clear();
    TeleportSessions.current().invulnerablePlayers.clear();
    RTPRunnable.scheduler = accessor.getMockScheduler();
    TeleportPipelineTask.ConfigCache.reload();
  }

  private Region region(String name) {
    Circle circle = new Circle();
    circle.setRng(new Random(42L));
    RegionSettings settings = new RegionSettings(
        name, world, circle, new LinearAdjustor(new ArrayList<>()),
        false, false, 10L, 1000L, 0L, 5, 0.0, 1L, "", false);
    Region reg = new Region(name, settings);
    ActiveSelection.get().permRegionLookup.put(reg.name, reg);
    return reg;
  }

  private TeleportPipelineTask task(MockRTPPlayer player, Region reg, TeleportData data) throws Exception {
    RTPCoords coords = new RTPCoords(world.name(), 50, 70, 50);
    List<CompletableFuture<Long>> chunks = new ArrayList<>();
    chunks.add(CompletableFuture.completedFuture(0L));
    ChunkSet chunkSet = new ChunkSet(world, 3, 3, chunks, CompletableFuture.completedFuture(true));
    TeleportPipelineTask task = new TeleportPipelineTask(
        new GenerationContext(player, player, null), reg, coords, new ChunkReservation(chunkSet, world));
    data.sender = player;
    data.selectedCoords = coords;
    data.targetRegion = reg;
    TeleportSessions.current().latestTeleportData.put(player.uuid(), data);
    Field f = TeleportPipelineTask.class.getDeclaredField("teleportData");
    f.setAccessible(true);
    f.set(task, data);
    return task;
  }

  @Test
  @DisplayName("REQ-RTP-F-013: teleportMessage renders lock uses and processingTime after the teleport lands")
  void successMessageReflectsCompletedTeleport() throws Exception {
    Region reg = region("post_state_reg");
    DeferredPlayer player = new DeferredPlayer(UUID.randomUUID(), "PostState", new RTPLocation(world, 0, 64, 0));
    accessor.addPlayer(player);
    TeleportPipelineTask.ConfigCache.teleportMessage =
        "uses=[lockUses] left=[remainingLockUses] time=[processingTime]";

    TeleportData data = new TeleportData();
    data.time = System.currentTimeMillis();
    TeleportPipelineTask task = task(player, reg, data);
    task.setPhase(TeleportPipelineTask.Phase.TELEPORT);

    task.run();
    accessor.getMockScheduler().tick(1);
    long dispatchedProcessing = data.processingTime;

    Thread.sleep(60L);
    player.sentMessages.clear();
    player.watched = data;
    player.pending.complete(true);
    accessor.getMockScheduler().tick(1);

    assertTrue(player.sentMessages.stream().anyMatch(message -> message.startsWith("uses=1 left=4")),
        "message must count the teleport it announces; got " + player.sentMessages);
    assertTrue(player.sentMessages.stream().anyMatch(message ->
            message.startsWith("uses=1 left=4 time=") && !message.contains("[processingTime]")),
        "message must render processingTime after setLocation completes; got " + player.sentMessages);
    assertTrue(player.processingAtMessage >= dispatchedProcessing + 60L,
        "processingTime must include the setLocation round trip; dispatched="
            + dispatchedProcessing + " atMessage=" + player.processingAtMessage);
  }

  @Test
  @DisplayName("REQ-RTP-F-013: teleportMessage after a DEATH-held teleport renders the recorded lock use")
  void deathCompletionMessageReflectsRecordedUse() throws Exception {
    Region reg = region("post_state_death_reg");
    MockRTPPlayer player = new MockRTPPlayer(UUID.randomUUID(), "PostStateDeath", new RTPLocation(world, 0, 64, 0));
    accessor.addPlayer(player);
    TeleportPipelineTask.ConfigCache.teleportMessage = "uses=[lockUses] left=[remainingLockUses]";

    TeleportData data = new TeleportData();
    data.time = System.currentTimeMillis();
    TeleportPipelineTask task = task(player, reg, data);

    task.completeDeathTeleport(true);

    assertTrue(player.sentMessages.contains("uses=1 left=4"),
        "message must count the teleport it announces; got " + player.sentMessages);
  }
}
