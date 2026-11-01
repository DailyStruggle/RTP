package io.github.dailystruggle.rtp.common.playerData;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PureDataRTPCommandSender / PureDataRTPPlayer - permission rules, identity, and live-handle delegation")
class PureDataLiveDelegationTest {

  private RTPServerAccessor originalAccessor;
  private RTPServerAccessor accessor;
  private UUID uuid;

  @BeforeEach
  void setUp() {
    originalAccessor = RTPRuntime.serverAccessor();
    accessor = mock(RTPServerAccessor.class);
    RTPRuntime.setServerAccessor(null);
    uuid = UUID.randomUUID();
  }

  @AfterEach
  void tearDown() {
    RTPRuntime.setServerAccessor(originalAccessor);
  }

  private PureDataRTPCommandSender sender(Set<String> perms) {
    return new PureDataRTPCommandSender(uuid, "Steve", 1L, 2L, perms);
  }

  @Test
  @DisplayName("Constructor rejects a null UUID and defaults a null name and null permissions")
  void constructorDefaults() {
    assertThrows(NullPointerException.class, () -> new PureDataRTPCommandSender(null, "x", 0L, 0L, Set.of()));

    PureDataRTPCommandSender s = new PureDataRTPCommandSender(uuid, null, 0L, 0L, null);
    assertEquals(uuid.toString(), s.name());
    assertTrue(s.getEffectivePermissions().isEmpty());
    assertFalse(s.hasPermission("rtp.use"));
  }

  @Test
  @DisplayName("Copy constructors snapshot identity, timings, and permissions from a live sender or player")
  void copyConstructors() {
    RTPCommandSender live = mock(RTPCommandSender.class);
    when(live.uuid()).thenReturn(uuid);
    when(live.name()).thenReturn("Live");
    when(live.cooldown()).thenReturn(30L);
    when(live.delay()).thenReturn(5L);
    when(live.getEffectivePermissions()).thenReturn(Set.of("rtp.use"));

    PureDataRTPCommandSender copy = new PureDataRTPCommandSender(live);
    assertEquals(uuid, copy.uuid());
    assertEquals("Live", copy.name());
    assertEquals(30L, copy.cooldown());
    assertEquals(5L, copy.delay());
    assertEquals(Set.of("rtp.use"), copy.getEffectivePermissions());

    RTPPlayer livePlayer = mock(RTPPlayer.class);
    when(livePlayer.uuid()).thenReturn(uuid);
    when(livePlayer.name()).thenReturn("LivePlayer");
    when(livePlayer.getEffectivePermissions()).thenReturn(Set.of());

    PureDataRTPPlayer playerCopy = new PureDataRTPPlayer(livePlayer);
    assertEquals(uuid, playerCopy.uuid());
    assertEquals("LivePlayer", playerCopy.name());
    assertTrue(playerCopy.isSelfContained());
  }

  @Test
  @DisplayName("hasPermission honours null, wildcard, RTP wildcard, and case-insensitive nodes")
  void permissionRules() {
    assertFalse(sender(Set.of("rtp.use")).hasPermission(null));
    assertTrue(sender(Set.of("rtp.*")).hasPermission("rtp.admin"));
    assertTrue(sender(Set.of("*")).hasPermission("anything.at.all"));
    assertTrue(sender(Set.of("rtp.use")).hasPermission("RTP.USE"));
    assertFalse(sender(Set.of("rtp.use")).hasPermission("rtp.other"));
  }

  @Test
  @DisplayName("getLiveSender returns null without an accessor, for unknown senders, and for self-contained senders")
  void getLiveSenderNullPaths() {
    PureDataRTPCommandSender s = sender(Set.of());
    assertNull(s.getLiveSender());

    RTPRuntime.setServerAccessor(accessor);
    when(accessor.getSender(uuid)).thenReturn(null);
    assertNull(s.getLiveSender());

    when(accessor.getSender(uuid)).thenReturn(sender(Set.of()));
    assertNull(s.getLiveSender());
  }

  @Test
  @DisplayName("getLiveSender unwraps the platform handle of a live sender")
  void getLiveSenderDelegates() {
    Object handle = new Object();
    RTPCommandSender live = mock(RTPCommandSender.class);
    when(live.isSelfContained()).thenReturn(false);
    when(live.getLiveSender()).thenReturn(handle);
    RTPRuntime.setServerAccessor(accessor);
    when(accessor.getSender(uuid)).thenReturn(live);

    assertSame(handle, sender(Set.of()).getLiveSender());
  }

  @Test
  @DisplayName("sendMessage is a no-op without an accessor and routes by UUID when one is present")
  void sendMessageRouting() {
    PureDataRTPCommandSender s = sender(Set.of());
    assertDoesNotThrow(() -> s.sendMessage("ignored"));

    RTPRuntime.setServerAccessor(accessor);
    s.sendMessage("hello");
    verify(accessor).sendMessage(uuid, "hello");
  }

  @Test
  @DisplayName("performCommand forwards only to a live non-self-contained sender")
  void performCommandRouting() {
    PureDataRTPCommandSender s = sender(Set.of());
    assertDoesNotThrow(() -> s.performCommand(null, "rtp"));

    RTPRuntime.setServerAccessor(accessor);
    when(accessor.getSender(uuid)).thenReturn(null);
    assertDoesNotThrow(() -> s.performCommand(null, "rtp"));

    RTPCommandSender selfContained = mock(RTPCommandSender.class);
    when(selfContained.isSelfContained()).thenReturn(true);
    when(accessor.getSender(uuid)).thenReturn(selfContained);
    s.performCommand(null, "rtp");
    verify(selfContained, never()).performCommand(any(), anyString());

    RTPCommandSender live = mock(RTPCommandSender.class);
    when(live.isSelfContained()).thenReturn(false);
    when(accessor.getSender(uuid)).thenReturn(live);
    RTPPlayer target = mock(RTPPlayer.class);
    s.performCommand(target, "rtp world");
    verify(live).performCommand(target, "rtp world");
  }

  @Test
  @DisplayName("Sender equality is UUID-based against any RTPCommandSender")
  void senderEquality() {
    PureDataRTPCommandSender s = sender(Set.of("rtp.use"));
    assertTrue(s.equals(s));
    assertFalse(s.equals(null));
    assertFalse(s.equals("not a sender"));
    assertEquals(s, new PureDataRTPCommandSender(uuid, "Other", 9L, 9L, Set.of()));
    assertNotEquals(s, new PureDataRTPCommandSender(UUID.randomUUID(), "Steve", 1L, 2L, Set.of()));
    assertEquals(s.hashCode(), new PureDataRTPCommandSender(uuid, "x", 0L, 0L, null).hashCode());
    assertEquals("PureDataRTPCommandSender[uuid=" + uuid + ", name=Steve]", s.toString());
  }

  @Test
  @DisplayName("setLocation fails closed without an accessor or online player and delegates otherwise")
  void playerSetLocation() {
    PureDataRTPPlayer p = new PureDataRTPPlayer(uuid, "Alex", 0L, 0L, Set.of());
    RTPLocation to = new RTPLocation(null, 1, 64, 1);
    assertFalse(p.setLocation(to).join());

    RTPRuntime.setServerAccessor(accessor);
    when(accessor.getPlayer(uuid)).thenReturn(null);
    assertFalse(p.setLocation(to).join());

    RTPPlayer live = mock(RTPPlayer.class);
    when(live.setLocation(to)).thenReturn(CompletableFuture.completedFuture(true));
    when(accessor.getPlayer(uuid)).thenReturn(live);
    assertTrue(p.setLocation(to).join());
  }

  @Test
  @DisplayName("getLocation returns null when offline and the live location when online")
  void playerGetLocation() {
    PureDataRTPPlayer p = new PureDataRTPPlayer(uuid, "Alex", 0L, 0L, Set.of());
    RTPRuntime.setServerAccessor(accessor);
    when(accessor.getPlayer(uuid)).thenReturn(null);
    assertNull(p.getLocation());

    RTPLocation here = new RTPLocation(null, 5, 70, -5);
    RTPPlayer live = mock(RTPPlayer.class);
    when(live.getLocation()).thenReturn(here);
    when(accessor.getPlayer(uuid)).thenReturn(live);
    assertSame(here, p.getLocation());
  }

  @Test
  @DisplayName("isOnline reflects the live player state when an accessor is present")
  void playerIsOnline() {
    PureDataRTPPlayer p = new PureDataRTPPlayer(uuid, "Alex", 0L, 0L, Set.of());
    RTPRuntime.setServerAccessor(accessor);
    when(accessor.getPlayer(uuid)).thenReturn(null);
    assertFalse(p.isOnline());

    RTPPlayer live = mock(RTPPlayer.class);
    when(accessor.getPlayer(uuid)).thenReturn(live);
    when(live.isOnline()).thenReturn(false);
    assertFalse(p.isOnline());

    when(live.isOnline()).thenReturn(true);
    assertTrue(p.isOnline());
  }

  @Test
  @DisplayName("Player clone, equality, hashCode, and toString are UUID-based")
  void playerIdentity() {
    PureDataRTPPlayer p = new PureDataRTPPlayer(uuid, "Alex", 3L, 4L, Set.of("rtp.use"));
    PureDataRTPPlayer clone = p.clone();
    assertNotSame(p, clone);
    assertEquals(p, clone);
    assertEquals("Alex", clone.name());
    assertEquals(3L, clone.cooldown());

    assertTrue(p.equals(p));
    assertFalse(p.equals(null));
    assertEquals(p, sender(Set.of()));
    assertEquals(sender(Set.of()), p);
    assertNotEquals(p, new PureDataRTPPlayer(UUID.randomUUID(), "Alex", 3L, 4L, Set.of()));
    assertEquals(p.hashCode(), clone.hashCode());
    assertEquals("PureDataRTPPlayer[uuid=" + uuid + ", name=Alex]", p.toString());
  }
}
