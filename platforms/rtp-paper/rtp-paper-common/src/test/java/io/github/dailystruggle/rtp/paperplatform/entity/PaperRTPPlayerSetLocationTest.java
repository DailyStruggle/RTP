package io.github.dailystruggle.rtp.paperplatform.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.bukkitplatform.entity.TeleportPathSelector;
import io.github.dailystruggle.rtp.bukkitplatform.world.BukkitRTPWorld;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.RTP;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Tests for {@link PaperRTPPlayer#setLocation(RTPLocation)}.
 *
 * <p>Verifies S-005: Paper teleports use native {@link Player#teleportAsync(Location)} to load the
 * arrival chunk off-tick before transitioning the entity, preventing synchronous chunk loads
 * on arrival tick.
 *
 * <p>Verifies S-004: Teleport failures or exceptions never hang or get silently swallowed,
 * completing false or falling back safely to the scheduler.
 */
class PaperRTPPlayerSetLocationTest {

  private Player player;
  private World bukkitWorld;
  private BukkitRTPWorld rtpWorld;
  private RTPLocation destination;
  private RTPScheduler scheduler;
  private RTPScheduler previousScheduler;

  @BeforeEach
  void setUp() {
    bukkitWorld = mock(World.class);
    rtpWorld = mock(BukkitRTPWorld.class);
    when(rtpWorld.world()).thenReturn(bukkitWorld);
    destination = new RTPLocation(rtpWorld, 100, 70, -50);

    player = mock(Player.class);
    when(player.getLocation()).thenReturn(new Location(bukkitWorld, 0, 64, 0, 90.0f, 45.0f));
    when(player.isOnline()).thenReturn(true);

    scheduler = mock(RTPScheduler.class);
    previousScheduler = RTP.scheduler;
    RTP.scheduler = scheduler;
  }

  @AfterEach
  void tearDown() {
    TeleportPathSelector.resetForTesting();
    RTP.scheduler = previousScheduler;
  }

  /** Main thread, not Folia, destination chunk (6, -4) resident. */
  private void directPreconditions(boolean primary, boolean chunkLoaded) {
    TeleportPathSelector.setForTesting(() -> primary, false);
    when(bukkitWorld.isChunkLoaded(100 >> 4, -50 >> 4)).thenReturn(chunkLoaded);
    when(player.teleport(any(Location.class))).thenReturn(true);
    when(player.teleportAsync(any(Location.class))).thenReturn(CompletableFuture.completedFuture(true));
  }

  @Test
  @DisplayName("ADR-005: main thread and resident destination chunk teleports inline in the same tick")
  void testSetLocation_directWhenResidentOnMain() throws Exception {
    directPreconditions(true, true);

    CompletableFuture<Boolean> future = new PaperRTPPlayer(player).setLocation(destination);

    assertTrue(future.isDone());
    assertTrue(future.get(1, TimeUnit.SECONDS));
    ArgumentCaptor<Location> captor = ArgumentCaptor.forClass(Location.class);
    verify(player).teleport(captor.capture());
    assertEquals(100.5, captor.getValue().getX(), 1e-4);
    assertEquals(-49.5, captor.getValue().getZ(), 1e-4);
    verify(player, never()).teleportAsync(any(Location.class));
    verify(scheduler, never()).runTask(any(Runnable.class));
  }

  @Test
  @DisplayName("S-005: unloaded destination chunk keeps teleportAsync even on the main thread")
  void testSetLocation_asyncWhenChunkUnloaded() {
    directPreconditions(true, false);

    new PaperRTPPlayer(player).setLocation(destination);

    verify(player).teleportAsync(any(Location.class));
    verify(player, never()).teleport(any(Location.class));
  }

  @Test
  @DisplayName("ADR-005: off-main-thread caller keeps teleportAsync")
  void testSetLocation_asyncWhenOffMainThread() {
    directPreconditions(false, true);

    new PaperRTPPlayer(player).setLocation(destination);

    verify(player).teleportAsync(any(Location.class));
    verify(player, never()).teleport(any(Location.class));
  }

  @Test
  @DisplayName("ADR-005: Folia keeps teleportAsync even with a resident chunk on the owning thread")
  void testSetLocation_asyncOnFolia() {
    directPreconditions(true, true);
    TeleportPathSelector.setForTesting(() -> true, true);

    new PaperRTPPlayer(player).setLocation(destination);

    verify(player).teleportAsync(any(Location.class));
    verify(player, never()).teleport(any(Location.class));
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  @DisplayName("ADR-005: teleportPath ASYNC forces teleportAsync even when the inline path is safe")
  void testSetLocation_asyncModeForcesTeleportAsync() {
    directPreconditions(true, true);
    Configs previousConfigs = RTP.configs;
    try {
      Configs configs = mock(Configs.class);
      ConfigParser perf = mock(ConfigParser.class);
      when(configs.getParser(PerformanceKeys.class)).thenReturn(perf);
      when(perf.getConfigValue(org.mockito.ArgumentMatchers.eq(PerformanceKeys.teleportPath), any()))
          .thenReturn("ASYNC");
      RTP.configs = configs;

      new PaperRTPPlayer(player).setLocation(destination);

      verify(player).teleportAsync(any(Location.class));
      verify(player, never()).teleport(any(Location.class));
    } finally {
      RTP.configs = previousConfigs;
    }
  }

  @Test
  @DisplayName("S-004: inline teleport refused by an event completes false without a second attempt")
  void testSetLocation_directReturnsFalse() throws Exception {
    directPreconditions(true, true);
    when(player.teleport(any(Location.class))).thenReturn(false);

    CompletableFuture<Boolean> future = new PaperRTPPlayer(player).setLocation(destination);

    assertTrue(future.isDone());
    assertFalse(future.get(1, TimeUnit.SECONDS));
    verify(player, never()).teleportAsync(any(Location.class));
  }

  @Test
  @DisplayName("S-004: inline teleport throwing falls back to teleportAsync")
  void testSetLocation_directThrowsFallsBackToAsync() throws Exception {
    directPreconditions(true, true);
    when(player.teleport(any(Location.class))).thenThrow(new IllegalStateException("boom"));

    CompletableFuture<Boolean> future = new PaperRTPPlayer(player).setLocation(destination);

    assertTrue(future.get(1, TimeUnit.SECONDS));
    verify(player).teleportAsync(any(Location.class));
  }

  @Test
  @DisplayName("S-005: setLocation routes through player.teleportAsync with centered coordinates and preserved rotation")
  void testSetLocation_success() throws Exception {
    when(player.teleportAsync(any(Location.class))).thenReturn(CompletableFuture.completedFuture(true));

    PaperRTPPlayer paperPlayer = new PaperRTPPlayer(player);
    CompletableFuture<Boolean> future = paperPlayer.setLocation(destination);

    assertTrue(future.isDone());
    assertTrue(future.get(1, TimeUnit.SECONDS));

    ArgumentCaptor<Location> captor = ArgumentCaptor.forClass(Location.class);
    verify(player).teleportAsync(captor.capture());
    Location loc = captor.getValue();
    assertEquals(bukkitWorld, loc.getWorld());
    assertEquals(100.5, loc.getX(), 1e-4);
    assertEquals(70.0, loc.getY(), 1e-4);
    assertEquals(-49.5, loc.getZ(), 1e-4);
    assertEquals(90.0f, loc.getYaw(), 1e-4);
    assertEquals(45.0f, loc.getPitch(), 1e-4);

    verify(player, never()).teleport(any(Location.class));
  }

  @Test
  @DisplayName("S-004: setLocation completes false when teleportAsync returns false")
  void testSetLocation_teleportAsyncReturnsFalse() throws Exception {
    when(player.teleportAsync(any(Location.class))).thenReturn(CompletableFuture.completedFuture(false));

    PaperRTPPlayer paperPlayer = new PaperRTPPlayer(player);
    CompletableFuture<Boolean> future = paperPlayer.setLocation(destination);

    assertTrue(future.isDone());
    assertFalse(future.get(1, TimeUnit.SECONDS));
  }

  @Test
  @DisplayName("S-004: setLocation completes false when teleportAsync fails exceptionally")
  void testSetLocation_teleportAsyncExceptional() throws Exception {
    CompletableFuture<Boolean> failed = new CompletableFuture<>();
    failed.completeExceptionally(new RuntimeException("Paper teleportAsync internal exception"));
    when(player.teleportAsync(any(Location.class))).thenReturn(failed);

    PaperRTPPlayer paperPlayer = new PaperRTPPlayer(player);
    CompletableFuture<Boolean> future = paperPlayer.setLocation(destination);

    assertTrue(future.isDone());
    assertFalse(future.get(1, TimeUnit.SECONDS));
  }

  @Test
  @DisplayName("S-004: setLocation falls back to sync teleport if teleportAsync throws synchronously")
  void testSetLocation_syncFallback() throws Exception {
    when(player.teleportAsync(any(Location.class))).thenThrow(new UnsupportedOperationException("sync error"));
    when(player.teleport(any(Location.class))).thenReturn(true);

    org.mockito.Mockito.doAnswer(inv -> {
      inv.<Runnable>getArgument(0).run();
      return null;
    }).when(scheduler).runTask(any(Runnable.class));

    PaperRTPPlayer paperPlayer = new PaperRTPPlayer(player);
    CompletableFuture<Boolean> future = paperPlayer.setLocation(destination);

    assertTrue(future.isDone());
    assertTrue(future.get(1, TimeUnit.SECONDS));
    verify(player).teleport(any(Location.class));
  }
}
