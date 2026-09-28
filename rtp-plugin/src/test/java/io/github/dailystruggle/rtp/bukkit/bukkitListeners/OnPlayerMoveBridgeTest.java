package io.github.dailystruggle.rtp.bukkit.bukkitListeners;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.event.PlayerMoveEvent;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OnPlayerMoveBridgeTest {

  private MockRTPServerAccessor serverAccessor;

  @BeforeEach
  void setUp() {
    serverAccessor = new MockRTPServerAccessor(new File("."));
    RTP.serverAccessor = serverAccessor;
  }

  @Test
  @DisplayName("OnPlayerMove bridges block movements to RTPAPI.playerMoveEvents when player is watched")
  void testMoveEventBridgeForWatchedPlayer() {
    UUID playerId = UUID.randomUUID();
    AtomicReference<PlayerMoveEvent> received = new AtomicReference<>();

    // Register watcher for playerId
    AutoCloseable handle = RTPAPI.playerMoveEvents.watch(playerId, received::set);

    Player mockPlayer = mock(Player.class);
    when(mockPlayer.getUniqueId()).thenReturn(playerId);

    World mockWorld = mock(World.class);
    when(mockWorld.getName()).thenReturn("arena_world");

    Location from = new Location(mockWorld, 10.2, 64.0, 15.1);
    Location to = new Location(mockWorld, 11.0, 64.0, 15.1);

    org.bukkit.event.player.PlayerMoveEvent bukkitEvent =
        new org.bukkit.event.player.PlayerMoveEvent(mockPlayer, from, to);

    OnPlayerMove listener = new OnPlayerMove();
    listener.onPlayerMove(bukkitEvent);

    assertNotNull(received.get(), "Expected PlayerMoveEvent to be fired for watched player");
    assertEquals(playerId, received.get().playerId());
    assertEquals("arena_world", received.get().worldName());
    assertEquals(10, received.get().fromX());
    assertEquals(11, received.get().toX());

    try {
      handle.close();
    } catch (Exception ignored) {}
  }

  @Test
  @DisplayName("OnPlayerMove does not fire RTPAPI.playerMoveEvents for sub-block yaw/pitch moves")
  void testSubBlockMoveDoesNotFire() {
    UUID playerId = UUID.randomUUID();
    AtomicReference<PlayerMoveEvent> received = new AtomicReference<>();

    AutoCloseable handle = RTPAPI.playerMoveEvents.watch(playerId, received::set);

    Player mockPlayer = mock(Player.class);
    when(mockPlayer.getUniqueId()).thenReturn(playerId);

    World mockWorld = mock(World.class);
    when(mockWorld.getName()).thenReturn("arena_world");

    Location from = new Location(mockWorld, 10.2, 64.0, 15.1);
    Location to = new Location(mockWorld, 10.5, 64.0, 15.3); // Same block (10, 64, 15)

    org.bukkit.event.player.PlayerMoveEvent bukkitEvent =
        new org.bukkit.event.player.PlayerMoveEvent(mockPlayer, from, to);

    OnPlayerMove listener = new OnPlayerMove();
    listener.onPlayerMove(bukkitEvent);

    assertNull(received.get(), "Sub-block movements should not fire block-level move events");

    try {
      handle.close();
    } catch (Exception ignored) {}
  }
}
