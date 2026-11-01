package io.github.dailystruggle.rtp.folia.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockPlayerRegistry;
import io.github.dailystruggle.rtp.common.mock.RuntimeMockRTPPlayer;
import java.lang.reflect.Field;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link AbstractFoliaServerAccessor} resolves registered BIT mocks before native lookup (ADR-121). */
class FoliaServerAccessorMockLookupTest {

  private static final String MOCK_NAME = "rtp-bit-0a1b2c3d";

  private Server previousServer;
  private Server server;
  private AbstractFoliaServerAccessor accessor;

  @BeforeEach
  void setUp() throws Exception {
    MockPlayerRegistry.clear();
    Field serverField = Bukkit.class.getDeclaredField("server");
    serverField.setAccessible(true);
    previousServer = (Server) serverField.get(null);
    server = mock(Server.class);
    serverField.set(null, server);
    accessor = new AbstractFoliaServerAccessor() {};
  }

  @AfterEach
  void tearDown() throws Exception {
    MockPlayerRegistry.clear();
    Field serverField = Bukkit.class.getDeclaredField("server");
    serverField.setAccessible(true);
    serverField.set(null, previousServer);
  }

  @Test
  @DisplayName("ADR-121: registered BIT mock resolves before native lookup and is absent from online lists")
  void registeredMockResolvesBeforeNativeLookup() {
    UUID mockId = UUID.randomUUID();
    RuntimeMockRTPPlayer mock = new RuntimeMockRTPPlayer(mockId, MOCK_NAME, null);
    MockPlayerRegistry.register(mock);

    assertSame(mock, accessor.getPlayer(mockId));
    assertSame(mock, accessor.getPlayer("RTP-BIT-0A1B2C3D"));
    assertSame(mock, accessor.getSender(mockId));
    assertFalse(accessor.getOnlinePlayerNames().contains(MOCK_NAME));
    assertTrue(accessor.getOnlinePlayers().stream().noneMatch(p -> mockId.equals(p.uuid())));
  }

  @Test
  @DisplayName("ADR-121: native players still resolve natively while a mock is registered")
  void nativePlayerUnaffectedByRegisteredMock() {
    Player alice = mock(Player.class);
    UUID aliceId = UUID.randomUUID();
    when(alice.getUniqueId()).thenReturn(aliceId);
    when(alice.getName()).thenReturn("Alice");
    when(server.getPlayer(aliceId)).thenReturn(alice);
    when(server.getPlayer("Alice")).thenReturn(alice);
    RuntimeMockRTPPlayer mock = new RuntimeMockRTPPlayer(UUID.randomUUID(), MOCK_NAME, null);
    MockPlayerRegistry.register(mock);

    RTPPlayer byId = accessor.getPlayer(aliceId);
    assertNotNull(byId);
    assertNotSame(mock, byId);
    assertEquals(aliceId, byId.uuid());
    RTPPlayer byName = accessor.getPlayer("Alice");
    assertNotNull(byName);
    assertEquals(aliceId, byName.uuid());
  }

  @Test
  @DisplayName("ADR-121: after unregister, lookups fall back to native behaviour")
  void unregisteredMockFallsBackToNative() {
    UUID mockId = UUID.randomUUID();
    RuntimeMockRTPPlayer mock = new RuntimeMockRTPPlayer(mockId, MOCK_NAME, null);
    MockPlayerRegistry.register(mock);
    MockPlayerRegistry.unregister(mockId);

    assertNull(accessor.getPlayer(mockId));
    assertNull(accessor.getPlayer(MOCK_NAME));
    RTPCommandSender sender = accessor.getSender(mockId);
    assertNotSame(mock, sender);
    assertFalse(sender instanceof RuntimeMockRTPPlayer);
  }

  @Test
  @DisplayName("REQ-RTP-S-006 - Folia 26.x version strings (26.1 through 26.4 snapshots) resolve to intVersion 26")
  void folia26xServerIntVersionResolution() {
    for (String ver : java.util.List.of("26_1", "26_2", "26_3", "26_4", "26.1.2-6-main (MC: 26.1.2)", "26.3-6-main (MC: 26.3)", "26.4-snapshot-1")) {
      AbstractFoliaServerAccessor custom = new AbstractFoliaServerAccessor() {
        @Override
        public String getServerVersion() {
          return ver;
        }
      };
      assertEquals(26, custom.getServerIntVersion(), "Expected intVersion 26 for " + ver);
    }
  }
}
