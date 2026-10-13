package io.github.dailystruggle.rtp.folia.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.folia.mock.MockAsyncScheduler;
import io.github.dailystruggle.rtp.folia.mock.MockGlobalRegionScheduler;
import io.github.dailystruggle.rtp.folia.mock.MockRegionScheduler;
import io.github.dailystruggle.rtp.folia.scheduling.FoliaSchedulerImpl;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Chunk;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Folia chunk-ticket application (S-005 / S-002): adds wait for an async preload, run on the
 * owning region scheduler, and are cancelled by a release issued before the preload resolves.
 */
class FoliaRTPWorldChunkTicketTest {

  private MockAsyncScheduler asyncScheduler;
  private MockRegionScheduler regionScheduler;
  private MockGlobalRegionScheduler globalScheduler;
  private World bukkitWorld;
  private FoliaRTPWorld rtpWorld;
  private Plugin plugin;
  private CompletableFuture<Chunk> preload;
  private RTPServerAccessor previousAccessor;
  private Server previousServer;

  @BeforeEach
  void setUp() throws Exception {
    java.lang.reflect.Field serverField = org.bukkit.Bukkit.class.getDeclaredField("server");
    serverField.setAccessible(true);
    previousServer = (Server) serverField.get(null);
    Server server = Mockito.mock(Server.class);
    PluginManager pluginManager = Mockito.mock(PluginManager.class);
    plugin = Mockito.mock(Plugin.class);
    when(plugin.isEnabled()).thenReturn(true);
    when(pluginManager.getPlugin("RTP")).thenReturn(plugin);
    when(server.getPluginManager()).thenReturn(pluginManager);
    serverField.set(null, server);

    asyncScheduler = new MockAsyncScheduler();
    regionScheduler = new MockRegionScheduler();
    globalScheduler = new MockGlobalRegionScheduler();
    FoliaSchedulerImpl scheduler = new FoliaSchedulerImpl(
        plugin, regionScheduler, asyncScheduler, globalScheduler, 8, 200L);

    previousAccessor = RTP.serverAccessor;
    RTPServerAccessor accessor = Mockito.mock(RTPServerAccessor.class);
    when(accessor.getScheduler()).thenReturn(scheduler);
    RTP.serverAccessor = accessor;

    bukkitWorld = Mockito.mock(World.class);
    preload = new CompletableFuture<>();
    when(bukkitWorld.getChunkAtAsync(3, 4, true)).thenReturn(preload);
    rtpWorld = new FoliaRTPWorld(bukkitWorld);
  }

  @AfterEach
  void tearDown() throws Exception {
    RTP.serverAccessor = previousAccessor;
    java.lang.reflect.Field serverField = org.bukkit.Bukkit.class.getDeclaredField("server");
    serverField.setAccessible(true);
    serverField.set(null, previousServer);
  }

  private void drainSchedulers() {
    for (int i = 0; i < 16; i++) {
      int ran = regionScheduler.executeAll() + globalScheduler.executeAll() + asyncScheduler.executeAll();
      if (ran == 0) return;
    }
  }

  @Test
  @DisplayName("S-005/S-002: ticket add waits for async preload and is applied on the owning region scheduler")
  void addWaitsForPreloadAndRunsOnRegionScheduler() {
    CompletableFuture<Void> apply = rtpWorld.setForceLoaded(3, 4, true);
    drainSchedulers();

    verify(bukkitWorld).getChunkAtAsync(3, 4, true);
    verify(bukkitWorld, never()).addPluginChunkTicket(anyInt(), anyInt(), any(Plugin.class));
    assertFalse(apply.isDone(), "apply future must stay pending until the preload resolves");

    preload.complete(Mockito.mock(Chunk.class));
    assertEquals(0, globalScheduler.pendingCount(), "ticket add must not use the global region scheduler");
    assertEquals(1, regionScheduler.pendingCount(), "ticket add must be dispatched to the owning region");
    regionScheduler.executeAll();

    verify(bukkitWorld).addPluginChunkTicket(3, 4, plugin);
    assertTrue(apply.isDone());
    assertFalse(apply.isCompletedExceptionally());
  }

  @Test
  @DisplayName("S-005/S-002: release before preload completes cancels the deferred ticket add")
  void releaseBeforePreloadCancelsDeferredAdd() {
    CompletableFuture<Void> apply = rtpWorld.setForceLoaded(3, 4, true);
    rtpWorld.setForceLoaded(3, 4, false);
    drainSchedulers();

    preload.complete(Mockito.mock(Chunk.class));
    drainSchedulers();

    verify(bukkitWorld, never()).addPluginChunkTicket(anyInt(), anyInt(), any(Plugin.class));
    verify(bukkitWorld).removePluginChunkTicket(3, 4, plugin);
    assertTrue(apply.isDone());
    assertFalse(apply.isCompletedExceptionally());
  }

  @Test
  @DisplayName("S-005: failed async preload never adds a ticket and fails the apply future")
  void failedPreloadAddsNoTicket() {
    CompletableFuture<Void> apply = rtpWorld.setForceLoaded(3, 4, true);
    preload.completeExceptionally(new IllegalStateException("load failed"));
    drainSchedulers();

    verify(bukkitWorld, never()).addPluginChunkTicket(anyInt(), anyInt(), any(Plugin.class));
    assertTrue(apply.isCompletedExceptionally());
  }

  @Test
  @DisplayName("S-005: null async preload never adds a ticket and fails the apply future")
  void nullPreloadAddsNoTicket() {
    CompletableFuture<Void> apply = rtpWorld.setForceLoaded(3, 4, true);
    preload.complete(null);
    drainSchedulers();

    verify(bukkitWorld, never()).addPluginChunkTicket(anyInt(), anyInt(), any(Plugin.class));
    assertTrue(apply.isCompletedExceptionally());
  }

  @Test
  @DisplayName("S-005/S-002: disabled plugin completes the apply future without touching tickets")
  void disabledPluginCompletesImmediately() {
    when(plugin.isEnabled()).thenReturn(false);
    CompletableFuture<Void> apply = rtpWorld.setForceLoaded(3, 4, true);

    assertTrue(apply.isDone());
    verify(bukkitWorld, never()).getChunkAtAsync(anyInt(), anyInt(), Mockito.anyBoolean());
    verify(bukkitWorld, never()).addPluginChunkTicket(anyInt(), anyInt(), any(Plugin.class));
  }

  @Test
  @DisplayName("S-005: keepChunkAt never loads synchronously; cache and ticket follow the async load")
  void keepChunkAtUsesAsyncLoadOnly() {
    rtpWorld.keepChunkAt(3, 4);
    drainSchedulers();

    verify(bukkitWorld).getChunkAtAsync(3, 4, true);
    verify(bukkitWorld, never()).getChunkAt(anyInt(), anyInt());
    verify(bukkitWorld, never()).addPluginChunkTicket(anyInt(), anyInt(), any(Plugin.class));
    assertEquals(0, rtpWorld.getCacheSize(), "chunk must not be cached before the async load completes");

    preload.complete(Mockito.mock(Chunk.class));
    drainSchedulers();

    assertEquals(1, rtpWorld.getCacheSize());
    verify(bukkitWorld).addPluginChunkTicket(3, 4, plugin);
    verify(bukkitWorld, never()).getChunkAt(anyInt(), anyInt());
  }

  @Test
  @DisplayName("S-005: keepChunkAt never loads synchronously; failed async load keeps nothing")
  void keepChunkAtFailedLoadKeepsNothing() {
    rtpWorld.keepChunkAt(3, 4);
    preload.completeExceptionally(new IllegalStateException("load failed"));
    drainSchedulers();

    assertEquals(0, rtpWorld.getCacheSize());
    verify(bukkitWorld, never()).getChunkAt(anyInt(), anyInt());
    verify(bukkitWorld, never()).addPluginChunkTicket(anyInt(), anyInt(), any(Plugin.class));
  }
}
