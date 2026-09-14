package io.github.dailystruggle.rtp.folia.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.folia.mock.MockAsyncScheduler;
import io.github.dailystruggle.rtp.folia.mock.MockGlobalRegionScheduler;
import io.github.dailystruggle.rtp.folia.mock.MockRegionScheduler;
import io.github.dailystruggle.rtp.folia.world.FoliaRTPWorld;
import io.papermc.paper.plugin.configuration.PluginMeta;
import java.io.File;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class FoliaSchedulerGovernanceTest {

  private static final Plugin STUB_PLUGIN = new Plugin() {
    @Override public void onDisable() {}
    @Override public void onEnable() {}
    @Override public void onLoad() {}
    @Override public boolean isEnabled() { return true; }
    @Override public PluginLoader getPluginLoader() { return null; }
    @Override public Server getServer() { return null; }
    @Override public String getName() { return "StubPlugin"; }
    @Override public PluginMeta getPluginMeta() { return null; }
    @Override public PluginDescriptionFile getDescription() { return null; }
    @Override public FileConfiguration getConfig() { return null; }
    @Override public InputStream getResource(String filename) { return null; }
    @Override public void saveConfig() {}
    @Override public void saveDefaultConfig() {}
    @Override public void saveResource(String resourcePath, boolean replace) {}
    @Override public void reloadConfig() {}
    @Override public File getDataFolder() { return null; }
    @Override public Logger getLogger() { return Logger.getLogger("StubPlugin"); }
    @Override public boolean isNaggable() { return false; }
    @Override public void setNaggable(boolean canNag) {}
    @Override public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) { return null; }
    @Override public BiomeProvider getDefaultBiomeProvider(String worldName, String id) { return null; }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) { return false; }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) { return List.of(); }
  };

  private MockAsyncScheduler asyncScheduler;
  private MockRegionScheduler regionScheduler;
  private MockGlobalRegionScheduler globalScheduler;
  private FoliaSchedulerImpl scheduler;
  private FoliaRTPWorld mockWorld;
  private World mockBukkitWorld;

  @BeforeEach
  void setUp() {
    try {
      java.lang.reflect.Field serverField = org.bukkit.Bukkit.class.getDeclaredField("server");
      serverField.setAccessible(true);
      org.bukkit.Server mockServer = Mockito.mock(org.bukkit.Server.class);
      serverField.set(null, mockServer);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    asyncScheduler = new MockAsyncScheduler();
    regionScheduler = new MockRegionScheduler();
    globalScheduler = new MockGlobalRegionScheduler();

    // Max in-flight = 2, timeout = 200ms
    scheduler = new FoliaSchedulerImpl(
        STUB_PLUGIN, regionScheduler, asyncScheduler, globalScheduler, 2, 200L);

    mockBukkitWorld = Mockito.mock(World.class);
    mockWorld = new FoliaRTPWorld(mockBukkitWorld);
  }

  @Test
  @DisplayName("Background tasks defer when in-flight regional tasks reach maxRegionalInflight")
  void testBackgroundRegionalTaskThrottling() {
    assertEquals(0, scheduler.getInFlightRegionalTasks());

    // Task 1 dispatches to region
    scheduler.runTask(mockWorld, 0, 0, () -> {});
    assertEquals(1, scheduler.getInFlightRegionalTasks());

    // Task 2 dispatches to region
    scheduler.runTask(mockWorld, 1, 1, () -> {});
    assertEquals(2, scheduler.getInFlightRegionalTasks());

    // Task 3 attempts to dispatch - should be deferred via async scheduler because inflight == 2
    scheduler.runTask(mockWorld, 2, 2, () -> {});
    assertEquals(2, scheduler.getInFlightRegionalTasks());
    assertEquals(1, asyncScheduler.pendingCount());

    // Complete pending region tasks
    regionScheduler.executeAll();
    assertEquals(0, scheduler.getInFlightRegionalTasks());
  }

  @Test
  @DisplayName("Interactive requests queue when saturated and pump through upon completion")
  void testInteractiveQueuePumping() {
    // Saturate permits with 2 background tasks
    scheduler.runTask(mockWorld, 0, 0, () -> {});
    scheduler.runTask(mockWorld, 1, 1, () -> {});
    assertEquals(2, scheduler.getInFlightRegionalTasks());

    // Interactive request submitted - enters queue
    AtomicBoolean interactiveExecuted = new AtomicBoolean(false);
    AtomicBoolean timeoutFired = new AtomicBoolean(false);

    scheduler.runInteractiveTask(
        mockWorld, 10, 10,
        () -> interactiveExecuted.set(true),
        () -> timeoutFired.set(true),
        1000L);

    assertEquals(1, scheduler.getInteractiveQueueSize());
    assertFalse(interactiveExecuted.get());
    assertFalse(timeoutFired.get());

    // Region tasks finish and retire permits -> pumpQueue activates interactive task!
    regionScheduler.executeAll();

    // Now region scheduler has the interactive task queued
    assertEquals(1, regionScheduler.pendingCount());
    regionScheduler.executeAll();

    assertTrue(interactiveExecuted.get());
    assertFalse(timeoutFired.get());
    assertEquals(0, scheduler.getInteractiveQueueSize());
    assertEquals(0, scheduler.getInFlightRegionalTasks());
  }

  @Test
  @DisplayName("Interactive request expires cleanly on deadline timeout when permits remain unavailable")
  void testInteractiveRequestDeadlineTimeout() {
    // Saturate permits
    scheduler.runTask(mockWorld, 0, 0, () -> {});
    scheduler.runTask(mockWorld, 1, 1, () -> {});
    assertEquals(2, scheduler.getInFlightRegionalTasks());

    AtomicBoolean interactiveExecuted = new AtomicBoolean(false);
    AtomicBoolean timeoutFired = new AtomicBoolean(false);

    // Timeout set to 0 (immediate expiration)
    scheduler.runInteractiveTask(
        mockWorld, 5, 5,
        () -> interactiveExecuted.set(true),
        () -> timeoutFired.set(true),
        1L);

    try {
      Thread.sleep(5);
    } catch (InterruptedException ignored) {}

    // Async timeout watchdog executes
    asyncScheduler.executeAll();

    assertTrue(timeoutFired.get(), "Timeout callback must fire cleanly on deadline expiration");
    assertFalse(interactiveExecuted.get(), "Task must not execute after deadline timeout");
  }
}
