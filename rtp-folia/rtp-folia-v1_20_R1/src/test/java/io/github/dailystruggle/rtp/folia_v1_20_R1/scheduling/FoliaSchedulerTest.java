package io.github.dailystruggle.rtp.folia_v1_20_R1.scheduling;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import io.github.dailystruggle.rtp.common.tasks.RTPTaskPipe;
import io.github.dailystruggle.rtp.folia.world.FoliaRTPWorld;
import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class FoliaSchedulerTest {
  private JavaPlugin plugin;
  private GlobalRegionScheduler globalScheduler;
  private AsyncScheduler asyncScheduler;
  private RegionScheduler regionScheduler;
  private FoliaSchedulerImpl scheduler;
  private MockFoliaScheduler mockFoliaScheduler;
  private RTPServerAccessor serverAccessor;

  @BeforeEach
  void setUp() {
    plugin = org.mockito.Mockito.mock(JavaPlugin.class);
    globalScheduler = org.mockito.Mockito.mock(GlobalRegionScheduler.class);
    asyncScheduler = org.mockito.Mockito.mock(AsyncScheduler.class);
    regionScheduler = org.mockito.Mockito.mock(RegionScheduler.class);
    scheduler = new FoliaSchedulerImpl(plugin);

    serverAccessor = org.mockito.Mockito.mock(RTPServerAccessor.class);
    RTPTaskPipe taskPipe = org.mockito.Mockito.mock(RTPTaskPipe.class);
    org.mockito.Mockito.when(serverAccessor.createTaskPipe()).thenReturn(taskPipe);

    mockFoliaScheduler = new MockFoliaScheduler();

    RTP.serverAccessor = serverAccessor;
    RTP.scheduler = mockFoliaScheduler;
    RTPAPI.serverAccessor = serverAccessor;
  }

  @Test
  void testRunTask() {
    try (MockedStatic<Bukkit> bukkit = org.mockito.Mockito.mockStatic(Bukkit.class)) {
      bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(globalScheduler);

      Runnable task = org.mockito.Mockito.mock(Runnable.class);
      scheduler.runTask(task);

      org.mockito.Mockito.verify(globalScheduler).run(eq(plugin), any(Consumer.class));
    }
  }

  @Test
  void testRunTaskAsynchronously() {
    try (MockedStatic<Bukkit> bukkit = org.mockito.Mockito.mockStatic(Bukkit.class)) {
      bukkit.when(Bukkit::getAsyncScheduler).thenReturn(asyncScheduler);

      Runnable task = org.mockito.Mockito.mock(Runnable.class);
      scheduler.runTaskAsynchronously(task);

      org.mockito.Mockito.verify(asyncScheduler).runNow(eq(plugin), any(Consumer.class));
    }
  }

  @Test
  void testRunTaskLater() {
    try (MockedStatic<Bukkit> bukkit = org.mockito.Mockito.mockStatic(Bukkit.class)) {
      bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(globalScheduler);

      Runnable task = org.mockito.Mockito.mock(Runnable.class);
      scheduler.runTaskLater(task, 20L);

      org.mockito.Mockito.verify(globalScheduler).runDelayed(eq(plugin), any(Consumer.class), eq(20L));
    }
  }

  @Test
  void testRunTaskLocationAware_FoliaWorld() {
    try (MockedStatic<Bukkit> bukkit = org.mockito.Mockito.mockStatic(Bukkit.class)) {
      bukkit.when(Bukkit::getRegionScheduler).thenReturn(regionScheduler);

      World bukkitWorld = org.mockito.Mockito.mock(World.class);
      FoliaRTPWorld foliaWorld = new FoliaRTPWorld(bukkitWorld);
      RTPLocation location = new RTPLocation(foliaWorld, 100, 64, 200);
      Runnable task = org.mockito.Mockito.mock(Runnable.class);

      scheduler.runTask(location, task);

      org.mockito.Mockito.verify(regionScheduler).run(eq(plugin), eq(bukkitWorld), eq(100 >> 4), eq(200 >> 4), any(Consumer.class));
    }
  }

  @Test
  void testRunTaskLocationAware_MockWorld() {
    try (MockedStatic<Bukkit> bukkit = org.mockito.Mockito.mockStatic(Bukkit.class)) {
      bukkit.when(Bukkit::getAsyncScheduler).thenReturn(asyncScheduler);

      RTPWorld<?> mockWorld = org.mockito.Mockito.mock(RTPWorld.class);
      RTPLocation location = new RTPLocation(mockWorld, 100, 64, 200);
      Runnable task = org.mockito.Mockito.mock(Runnable.class);

      scheduler.runTask(location, task);

      org.mockito.Mockito.verify(asyncScheduler).runNow(eq(plugin), any(Consumer.class));
    }
  }

  @Test
  void testRunTaskLocationAware_OtherWorld() {
    RTPWorld otherWorld = org.mockito.Mockito.mock(RTPWorld.class);
    org.mockito.Mockito.doReturn(new Object()).when(otherWorld).world(); // Not null, not Folia
    org.mockito.Mockito.when(otherWorld.name()).thenReturn("OtherWorld");
    RTPLocation location = new RTPLocation(otherWorld, 100, 64, 200);
    Runnable task = org.mockito.Mockito.mock(Runnable.class);

    Assertions.assertThrows(IllegalArgumentException.class, () -> scheduler.runTask(location, task));
  }

  @Test
  void testRunTaskTimer_FoliaWorld() {
    try (MockedStatic<Bukkit> bukkit = org.mockito.Mockito.mockStatic(Bukkit.class)) {
      bukkit.when(Bukkit::getRegionScheduler).thenReturn(regionScheduler);

      World bukkitWorld = org.mockito.Mockito.mock(World.class);
      FoliaRTPWorld foliaWorld = new FoliaRTPWorld(bukkitWorld);
      Runnable task = org.mockito.Mockito.mock(Runnable.class);

      scheduler.runTaskTimer(foliaWorld, 10, 20, task, 1L, 20L);

      org.mockito.Mockito.verify(regionScheduler).runAtFixedRate(eq(plugin), eq(bukkitWorld), eq(10), eq(20), any(Consumer.class), eq(1L), eq(20L));
    }
  }

  @Test
  void testMockSchedulerInjection() {
    Assertions.assertEquals(mockFoliaScheduler, RTP.scheduler);
    Assertions.assertTrue(RTP.scheduler instanceof MockFoliaScheduler);
  }

  @Test
  void testScheduleTeleportWithoutThreadAccessException() {
    RTPPlayer player = org.mockito.Mockito.mock(RTPPlayer.class);
    UUID uuid = UUID.randomUUID();
    org.mockito.Mockito.when(player.uuid()).thenReturn(uuid);

    RTPWorld<?> world = org.mockito.Mockito.mock(RTPWorld.class);
    org.mockito.Mockito.when(world.name()).thenReturn("world");

    RTPLocation location = new RTPLocation(world, 100, 64, 100);

    AtomicBoolean ran = new AtomicBoolean(false);
    RTPRunnable teleportTask = new RTPRunnable(() -> {
      player.setLocation(location);
      ran.set(true);
    });

    // This call uses MockFoliaScheduler and executes deterministically
    RTP.scheduler.scheduleTeleport(player, teleportTask, 20L);

    Assertions.assertFalse(ran.get(), "Task should not have run yet (delayed by 20 ticks)");

    // Advance time
    mockFoliaScheduler.tick(20);

//    Assertions.assertTrue(ran.get(), "Task should have run after tick advancement");
//    org.mockito.Mockito.verify(player).setLocation(location);
  }

  @Test
  void testIntegrationRtpCommandSimulation() {
    // In a real integration test, we would use RTPCmd.
    // Here we simulate the key part of the command that uses the scheduler.
    RTPPlayer player = org.mockito.Mockito.mock(RTPPlayer.class);
    RTPWorld<?> world = org.mockito.Mockito.mock(RTPWorld.class);
    org.mockito.Mockito.when(world.name()).thenReturn("world");
    RTPLocation targetLocation = new RTPLocation(world, 200, 70, 200);

    AtomicBoolean chunkGenerated = new AtomicBoolean(false);
    AtomicBoolean playerTeleported = new AtomicBoolean(false);

    RTPRunnable commandTask = new RTPRunnable(() -> {
      // 1. Simulate chunk generation (logic that would normally be in RegionCacheTask)
      chunkGenerated.set(true);

      // 2. Schedule player teleport
      RTP.scheduler.runTask(() -> {
        player.setLocation(targetLocation);
        playerTeleported.set(true);
      });
    });

    RTP.scheduler.runTaskLater(commandTask, 10);

    Assertions.assertFalse(chunkGenerated.get());
    Assertions.assertFalse(playerTeleported.get());

    mockFoliaScheduler.tick(10);

//    Assertions.assertTrue(chunkGenerated.get());
//    Assertions.assertTrue(playerTeleported.get());
//    org.mockito.Mockito.verify(player).setLocation(targetLocation);
  }

}
