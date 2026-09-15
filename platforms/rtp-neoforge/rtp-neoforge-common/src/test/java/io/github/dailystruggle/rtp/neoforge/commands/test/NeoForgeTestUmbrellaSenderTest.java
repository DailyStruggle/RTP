package io.github.dailystruggle.rtp.neoforge.commands.test;

import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.test.TestUmbrellaSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NeoForgeTestUmbrellaSenderTest {

  private RTPServerAccessor previousAccessor;

  @BeforeEach
  void setUp() {
    previousAccessor = RTP.serverAccessor;
  }

  @AfterEach
  void tearDown() {
    RTP.serverAccessor = previousAccessor;
  }

  @Test
  @DisplayName("NeoForgeTestUmbrellaSender implements TestUmbrellaSender")
  void implementsInterface() {
    NeoForgeTestUmbrellaSender sender = new NeoForgeTestUmbrellaSender();
    assertTrue(sender instanceof TestUmbrellaSender);
  }

  @Test
  @DisplayName("resolveCallerName with null returns server ID string")
  void resolveCallerNameNull() {
    NeoForgeTestUmbrellaSender sender = new NeoForgeTestUmbrellaSender();
    assertEquals(RTP.serverId.toString(), sender.resolveCallerName(null));
  }

  @Test
  @DisplayName("resolveCallerName resolves player name via serverAccessor")
  void resolveCallerNamePlayer() {
    NeoForgeTestUmbrellaSender sender = new NeoForgeTestUmbrellaSender();
    UUID uuid = UUID.randomUUID();
    RTPServerAccessor accessor = mock(RTPServerAccessor.class);
    RTPPlayer player = mock(RTPPlayer.class);
    when(player.name()).thenReturn("TestPlayer");
    when(accessor.getPlayer(uuid)).thenReturn(player);
    RTP.serverAccessor = accessor;

    assertEquals("TestPlayer", sender.resolveCallerName(uuid));
  }

  @Test
  @DisplayName("resolveCallerName falls back to UUID string when player not found")
  void resolveCallerNameFallback() {
    NeoForgeTestUmbrellaSender sender = new NeoForgeTestUmbrellaSender();
    UUID uuid = UUID.randomUUID();
    RTPServerAccessor accessor = mock(RTPServerAccessor.class);
    when(accessor.getPlayer(uuid)).thenReturn(null);
    RTP.serverAccessor = accessor;

    assertEquals(uuid.toString(), sender.resolveCallerName(uuid));
  }

  @Test
  @DisplayName("resolveCallerName falls back to UUID string when accessor throws")
  void resolveCallerNameExceptionFallback() {
    NeoForgeTestUmbrellaSender sender = new NeoForgeTestUmbrellaSender();
    UUID uuid = UUID.randomUUID();
    RTPServerAccessor accessor = mock(RTPServerAccessor.class);
    when(accessor.getPlayer(uuid)).thenThrow(new RuntimeException("boom"));
    RTP.serverAccessor = accessor;

    assertEquals(uuid.toString(), sender.resolveCallerName(uuid));
  }

  @Test
  @DisplayName("addAuditInterceptor and removeAuditInterceptor observe logged lines")
  void auditInterceptorDelivery() {
    NeoForgeTestUmbrellaSender sender = new NeoForgeTestUmbrellaSender();
    List<String> captured = new ArrayList<>();
    Consumer<String> interceptor = captured::add;

    sender.addAuditInterceptor(interceptor);
    sender.log(Level.INFO, "hello world");
    assertEquals(1, captured.size());
    assertEquals("hello world", captured.get(0));

    sender.removeAuditInterceptor(interceptor);
    sender.log(Level.INFO, "second line");
    assertEquals(1, captured.size());
  }

  @Test
  @DisplayName("log with null message is a no-op")
  void logNullMessage() {
    NeoForgeTestUmbrellaSender sender = new NeoForgeTestUmbrellaSender();
    List<String> captured = new ArrayList<>();
    sender.addAuditInterceptor(captured::add);
    sender.log(Level.INFO, null);
    assertTrue(captured.isEmpty());
  }
}
