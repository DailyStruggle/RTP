package io.github.dailystruggle.rtp.folia.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.folia.mock.MockAsyncScheduler;
import io.github.dailystruggle.rtp.folia.mock.MockGlobalRegionScheduler;
import io.github.dailystruggle.rtp.folia.mock.MockRegionScheduler;
import io.github.dailystruggle.rtp.folia.scheduling.FoliaSchedulerImpl;
import java.util.ArrayList;
import java.util.Collection;
import java.util.UUID;
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

/** S-002 / S-005: {@link FoliaRTPChunk#keep(boolean)} applies tickets on the owning region, paired. */
class FoliaRTPChunkKeepTest {

  private Server previousServer;
  private RTPServerAccessor previousAccessor;
  private Server server;
  private Plugin plugin;
  private MockRegionScheduler regionScheduler;
  private MockGlobalRegionScheduler globalScheduler;
  private MockAsyncScheduler asyncScheduler;
  private World bukkitWorld;
  private Chunk bukkitChunk;
  private Collection<Plugin> tickets;
  private FoliaRTPChunk chunk;

  @BeforeEach
  void setUp() throws Exception {
    java.lang.reflect.Field serverField = org.bukkit.Bukkit.class.getDeclaredField("server");
    serverField.setAccessible(true);
    previousServer = (Server) serverField.get(null);
    server = Mockito.mock(Server.class);
    PluginManager pluginManager = Mockito.mock(PluginManager.class);
    plugin = Mockito.mock(Plugin.class);
    when(plugin.isEnabled()).thenReturn(true);
    when(pluginManager.getPlugin("RTP")).thenReturn(plugin);
    when(server.getPluginManager()).thenReturn(pluginManager);
    serverField.set(null, server);

    regionScheduler = new MockRegionScheduler();
    globalScheduler = new MockGlobalRegionScheduler();
    asyncScheduler = new MockAsyncScheduler();
    FoliaSchedulerImpl scheduler = new FoliaSchedulerImpl(
        plugin, regionScheduler, asyncScheduler, globalScheduler, 4, 200L);

    bukkitWorld = Mockito.mock(World.class);
    UUID worldId = UUID.randomUUID();
    when(bukkitWorld.getUID()).thenReturn(worldId);
    FoliaRTPWorld rtpWorld = new FoliaRTPWorld(bukkitWorld);

    previousAccessor = RTPRuntime.serverAccessor();
    RTPServerAccessor accessor = Mockito.mock(RTPServerAccessor.class);
    when(accessor.getScheduler()).thenReturn(scheduler);
    Mockito.doReturn(rtpWorld).when(accessor).getRTPWorld(worldId);
    // Mirror platform bootstrap: RTP's static init runs before any accessor is installed.
    RTP.ensureInitialized();
    RTPRuntime.setServerAccessor(accessor);

    tickets = new ArrayList<>();
    bukkitChunk = Mockito.mock(Chunk.class);
    when(bukkitChunk.getWorld()).thenReturn(bukkitWorld);
    when(bukkitChunk.getX()).thenReturn(3);
    when(bukkitChunk.getZ()).thenReturn(4);
    when(bukkitChunk.isLoaded()).thenReturn(true);
    when(bukkitChunk.getPluginChunkTickets()).thenReturn(tickets);
    Mockito.doAnswer(inv -> tickets.add(plugin)).when(bukkitChunk).addPluginChunkTicket(plugin);
    Mockito.doAnswer(inv -> tickets.remove(plugin)).when(bukkitChunk).removePluginChunkTicket(plugin);
    chunk = new FoliaRTPChunk(bukkitChunk);
  }

  @AfterEach
  void tearDown() throws Exception {
    RTPRuntime.setServerAccessor(previousAccessor);
    java.lang.reflect.Field serverField = org.bukkit.Bukkit.class.getDeclaredField("server");
    serverField.setAccessible(true);
    serverField.set(null, previousServer);
  }

  private void ownRegion(boolean owned) {
    when(server.isOwnedByCurrentRegion(any(World.class), anyInt(), anyInt())).thenReturn(owned);
  }

  @Test
  @DisplayName("S-002: keep() off-region routes ticket add/remove to the owning region, never the global region")
  void offRegionKeepRoutesToOwningRegion() {
    chunk.keep(true);
    assertEquals(0, globalScheduler.pendingCount(), "ticket calls must not use the global region");
    assertEquals(1, regionScheduler.pendingCount());
    verify(bukkitChunk, never()).addPluginChunkTicket(any(Plugin.class));
    regionScheduler.executeAll();
    verify(bukkitChunk, times(1)).addPluginChunkTicket(plugin);

    chunk.keep(false);
    assertEquals(0, globalScheduler.pendingCount());
    regionScheduler.executeAll();
    verify(bukkitChunk, times(1)).removePluginChunkTicket(plugin);
    assertEquals(0, tickets.size());
  }

  @Test
  @DisplayName("S-002: keep() on the owning region applies inline")
  void owningRegionKeepRunsInline() {
    ownRegion(true);
    chunk.keep(true);
    verify(bukkitChunk, times(1)).addPluginChunkTicket(plugin);
    assertEquals(0, regionScheduler.pendingCount());
    chunk.keep(false);
    verify(bukkitChunk, times(1)).removePluginChunkTicket(plugin);
  }

  @Test
  @DisplayName("S-002: a release that runs before its queued add leaves no orphaned ticket")
  void releaseOvertakingAddLeavesNoTicket() {
    chunk.keep(true); // queued for the owning region
    ownRegion(true);
    chunk.keep(false); // inline, runs before the queued add
    regionScheduler.executeAll();

    verify(bukkitChunk, never()).addPluginChunkTicket(any(Plugin.class));
    verify(bukkitChunk, never()).removePluginChunkTicket(any(Plugin.class));
    assertEquals(0, tickets.size());
  }

  @Test
  @DisplayName("S-002: repeated release never double-releases the ticket")
  void repeatedReleaseIsSingle() {
    ownRegion(true);
    chunk.keep(true);
    chunk.keep(false);
    chunk.keep(false);
    verify(bukkitChunk, times(1)).removePluginChunkTicket(plugin);
  }

  @Test
  @DisplayName("S-005: keep(true) on a chunk that unloaded adds no ticket (would sync-load)")
  void unloadedChunkAddsNoTicket() {
    ownRegion(true);
    when(bukkitChunk.isLoaded()).thenReturn(false);
    chunk.keep(true);
    verify(bukkitChunk, never()).addPluginChunkTicket(any(Plugin.class));
    chunk.keep(false);
    verify(bukkitChunk, never()).removePluginChunkTicket(any(Plugin.class));
  }
}
