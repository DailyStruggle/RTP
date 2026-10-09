package io.github.dailystruggle.rtp.bukkitplatform.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.bukkitplatform.world.BukkitRTPWorld;
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
 * Tests for {@link BukkitRTPPlayer#setLocation(RTPLocation)}.
 *
 * <p>Verifies S-005: When running on a server platform providing {@code Entity#teleportAsync(Location)}
 * (Paper, Folia, Purpur), {@code BukkitRTPPlayer} reflectively invokes {@code teleportAsync} to avoid
 * synchronous chunk loading (S-005) or Folia regional thread exceptions.
 *
 * <p>Verifies Spigot fallback: On pure Spigot / CraftBukkit where {@code teleportAsync} is absent,
 * {@code BukkitRTPPlayer} schedules a synchronous {@link Player#teleport(Location)} task on the main thread.
 *
 * <p>Verifies S-004: Failures are never silently swallowed and futures always complete.
 */
class BukkitRTPPlayerSetLocationTest {

  public interface PaperMockPlayer extends Player {
    CompletableFuture<Boolean> teleportAsync(Location location);
  }

  private World bukkitWorld;
  private BukkitRTPWorld rtpWorld;
  private RTPLocation destination;
  private RTPScheduler scheduler;
  private RTPScheduler previousScheduler;

  @BeforeEach
  void setUp() {
    BukkitRTPPlayer.resetTeleportAsyncResolutionForTesting();

    bukkitWorld = mock(World.class);
    rtpWorld = mock(BukkitRTPWorld.class);
    when(rtpWorld.world()).thenReturn(bukkitWorld);
    destination = new RTPLocation(rtpWorld, 100, 70, -50);

    scheduler = mock(RTPScheduler.class);
    previousScheduler = RTP.scheduler;
    RTP.scheduler = scheduler;
  }

  @AfterEach
  void tearDown() {
    BukkitRTPPlayer.resetTeleportAsyncResolutionForTesting();
    TeleportPathSelector.resetForTesting();
    RTP.scheduler = previousScheduler;
  }

  @Test
  @DisplayName("ADR-005: reflective Paper path teleports inline when on main thread and the chunk is resident")
  void testSetLocation_reflectivePath_directWhenResident() throws Exception {
    TeleportPathSelector.setForTesting(() -> true, false);
    when(bukkitWorld.isChunkLoaded(100 >> 4, -50 >> 4)).thenReturn(true);
    PaperMockPlayer paperPlayer = mock(PaperMockPlayer.class);
    when(paperPlayer.getLocation()).thenReturn(new Location(bukkitWorld, 0, 64, 0));
    when(paperPlayer.teleport(any(Location.class))).thenReturn(true);

    CompletableFuture<Boolean> future = new BukkitRTPPlayer(paperPlayer).setLocation(destination);

    assertTrue(future.isDone());
    assertTrue(future.get(1, TimeUnit.SECONDS));
    verify(paperPlayer).teleport(any(Location.class));
    verify(paperPlayer, never()).teleportAsync(any(Location.class));
    verify(scheduler, never()).runTask(any(Runnable.class));
  }

  @Test
  @DisplayName("ADR-005: reflective Paper path keeps teleportAsync when the destination chunk is not resident")
  void testSetLocation_reflectivePath_asyncWhenNotResident() {
    TeleportPathSelector.setForTesting(() -> true, false);
    when(bukkitWorld.isChunkLoaded(100 >> 4, -50 >> 4)).thenReturn(false);
    PaperMockPlayer paperPlayer = mock(PaperMockPlayer.class);
    when(paperPlayer.getLocation()).thenReturn(new Location(bukkitWorld, 0, 64, 0));
    when(paperPlayer.teleportAsync(any(Location.class))).thenReturn(CompletableFuture.completedFuture(true));

    new BukkitRTPPlayer(paperPlayer).setLocation(destination);

    verify(paperPlayer).teleportAsync(any(Location.class));
    verify(paperPlayer, never()).teleport(any(Location.class));
  }

  @Test
  @DisplayName("S-005: reflectively invokes teleportAsync when present on the runtime player class")
  void testSetLocation_reflectiveTeleportAsync_success() throws Exception {
    PaperMockPlayer paperPlayer = mock(PaperMockPlayer.class);
    when(paperPlayer.getLocation()).thenReturn(new Location(bukkitWorld, 0, 64, 0, 180.0f, 0.0f));
    when(paperPlayer.teleportAsync(any(Location.class))).thenReturn(CompletableFuture.completedFuture(true));

    BukkitRTPPlayer rtpPlayer = new BukkitRTPPlayer(paperPlayer);
    CompletableFuture<Boolean> future = rtpPlayer.setLocation(destination);

    assertTrue(future.isDone());
    assertTrue(future.get(1, TimeUnit.SECONDS));

    ArgumentCaptor<Location> captor = ArgumentCaptor.forClass(Location.class);
    verify(paperPlayer).teleportAsync(captor.capture());
    Location loc = captor.getValue();
    assertEquals(bukkitWorld, loc.getWorld());
    assertEquals(100.5, loc.getX(), 1e-4);
    assertEquals(70.0, loc.getY(), 1e-4);
    assertEquals(-49.5, loc.getZ(), 1e-4);
    assertEquals(180.0f, loc.getYaw(), 1e-4);
    assertEquals(0.0f, loc.getPitch(), 1e-4);

    verify(paperPlayer, never()).teleport(any(Location.class));
    verify(scheduler, never()).runTask(any(Runnable.class));
  }

  @Test
  @DisplayName("Spigot fallback: falls back to synchronous player.teleport via scheduler when teleportAsync is absent")
  void testSetLocation_fallbackSyncTeleport() throws Exception {
    Player spigotPlayer = mock(Player.class);
    when(spigotPlayer.getLocation()).thenReturn(new Location(bukkitWorld, 0, 64, 0));
    when(spigotPlayer.teleport(any(Location.class))).thenReturn(true);

    doAnswer(inv -> {
      inv.<Runnable>getArgument(0).run();
      return null;
    }).when(scheduler).runTask(any(Runnable.class));

    BukkitRTPPlayer rtpPlayer = new BukkitRTPPlayer(spigotPlayer);
    CompletableFuture<Boolean> future = rtpPlayer.setLocation(destination);

    assertTrue(future.isDone());
    assertTrue(future.get(1, TimeUnit.SECONDS));
    verify(scheduler).runTask(any(Runnable.class));
    verify(spigotPlayer).teleport(any(Location.class));
  }

  @Test
  @DisplayName("S-004: reflective teleportAsync completing false yields future false")
  void testSetLocation_reflectiveTeleportAsync_returnsFalse() throws Exception {
    PaperMockPlayer paperPlayer = mock(PaperMockPlayer.class);
    when(paperPlayer.getLocation()).thenReturn(new Location(bukkitWorld, 0, 64, 0));
    when(paperPlayer.teleportAsync(any(Location.class))).thenReturn(CompletableFuture.completedFuture(false));

    BukkitRTPPlayer rtpPlayer = new BukkitRTPPlayer(paperPlayer);
    CompletableFuture<Boolean> future = rtpPlayer.setLocation(destination);

    assertTrue(future.isDone());
    assertFalse(future.get(1, TimeUnit.SECONDS));
  }

  @Test
  @DisplayName("S-004: reflective teleportAsync completing exceptionally yields future false")
  void testSetLocation_reflectiveTeleportAsync_exceptional() throws Exception {
    PaperMockPlayer paperPlayer = mock(PaperMockPlayer.class);
    when(paperPlayer.getLocation()).thenReturn(new Location(bukkitWorld, 0, 64, 0));
    CompletableFuture<Boolean> failed = new CompletableFuture<>();
    failed.completeExceptionally(new RuntimeException("async fail"));
    when(paperPlayer.teleportAsync(any(Location.class))).thenReturn(failed);

    BukkitRTPPlayer rtpPlayer = new BukkitRTPPlayer(paperPlayer);
    CompletableFuture<Boolean> future = rtpPlayer.setLocation(destination);

    assertTrue(future.isDone());
    assertFalse(future.get(1, TimeUnit.SECONDS));
  }
}
