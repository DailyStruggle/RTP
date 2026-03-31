package io.github.dailystruggle.rtp.folia_v1_20_R1.scheduling;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.folia.world.FoliaRTPWorld;
import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
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

  @BeforeEach
  void setUp() {
    plugin = mock(JavaPlugin.class);
    globalScheduler = mock(GlobalRegionScheduler.class);
    asyncScheduler = mock(AsyncScheduler.class);
    regionScheduler = mock(RegionScheduler.class);
    scheduler = new FoliaSchedulerImpl(plugin);
  }

  @Test
  void testRunTask() {
    try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
      bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(globalScheduler);

      Runnable task = mock(Runnable.class);
      scheduler.runTask(task);

      verify(globalScheduler).run(eq(plugin), any(Consumer.class));
    }
  }

  @Test
  void testRunTaskAsynchronously() {
    try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
      bukkit.when(Bukkit::getAsyncScheduler).thenReturn(asyncScheduler);

      Runnable task = mock(Runnable.class);
      scheduler.runTaskAsynchronously(task);

      verify(asyncScheduler).runNow(eq(plugin), any(Consumer.class));
    }
  }

  @Test
  void testRunTaskLater() {
    try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
      bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(globalScheduler);

      Runnable task = mock(Runnable.class);
      scheduler.runTaskLater(task, 20L);

      verify(globalScheduler).runDelayed(eq(plugin), any(Consumer.class), eq(20L));
    }
  }

  @Test
  void testRunTaskLocationAware_FoliaWorld() {
    try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
      bukkit.when(Bukkit::getRegionScheduler).thenReturn(regionScheduler);

      World bukkitWorld = mock(World.class);
      FoliaRTPWorld foliaWorld = new FoliaRTPWorld(bukkitWorld);
      RTPLocation location = new RTPLocation(foliaWorld, 100, 64, 200);
      Runnable task = mock(Runnable.class);

      scheduler.runTask(location, task);

      verify(regionScheduler).run(eq(plugin), eq(bukkitWorld), eq(100 >> 4), eq(200 >> 4), any(Consumer.class));
    }
  }

  @Test
  void testRunTaskLocationAware_MockWorld() {
    try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
      bukkit.when(Bukkit::getAsyncScheduler).thenReturn(asyncScheduler);

      RTPWorld<?> mockWorld = mock(RTPWorld.class);
      RTPLocation location = new RTPLocation(mockWorld, 100, 64, 200);
      Runnable task = mock(Runnable.class);

      scheduler.runTask(location, task);

      verify(asyncScheduler).runNow(eq(plugin), any(Consumer.class));
    }
  }

  @Test
  void testRunTaskLocationAware_OtherWorld() {
    RTPWorld otherWorld = mock(RTPWorld.class);
    doReturn(new Object()).when(otherWorld).world(); // Not null, not Folia
    when(otherWorld.name()).thenReturn("OtherWorld");
    RTPLocation location = new RTPLocation(otherWorld, 100, 64, 200);
    Runnable task = mock(Runnable.class);

    Assertions.assertThrows(IllegalArgumentException.class, () -> scheduler.runTask(location, task));
  }

  @Test
  void testRunTaskTimer_FoliaWorld() {
    try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
      bukkit.when(Bukkit::getRegionScheduler).thenReturn(regionScheduler);

      World bukkitWorld = mock(World.class);
      FoliaRTPWorld foliaWorld = new FoliaRTPWorld(bukkitWorld);
      Runnable task = mock(Runnable.class);

      scheduler.runTaskTimer(foliaWorld, 10, 20, task, 1L, 20L);

      verify(regionScheduler).runAtFixedRate(eq(plugin), eq(bukkitWorld), eq(10), eq(20), any(Consumer.class), eq(1L), eq(20L));
    }
  }
}
