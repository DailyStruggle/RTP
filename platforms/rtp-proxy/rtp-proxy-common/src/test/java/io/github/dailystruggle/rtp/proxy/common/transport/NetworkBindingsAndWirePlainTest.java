package io.github.dailystruggle.rtp.proxy.common.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.proxy.common.config.NetworkConfig;
import io.github.dailystruggle.rtp.proxy.common.security.HmacVerifier;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkTransport;
import io.github.dailystruggle.rtp.proxy.common.spi.ReservationToken;
import io.github.dailystruggle.rtp.proxy.common.transport.direct.ProxyDirectWire;
import io.github.dailystruggle.rtp.proxy.common.transport.memory.InMemoryNetworkStateBinding;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NetworkBindingsAndWirePlainTest {

  @Test
  @DisplayName("ProxyDirectWire opcodes, payloads, and tokens round-trip without servers")
  void testProxyDirectWireRoundTrip() throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(baos);

    ProxyDirectWire.writeOpcode(out, ProxyDirectWire.OP_HEARTBEAT);
    ProxyDirectWire.writeSignedPayload(out, "test-heartbeat", null, 1);
    ProxyDirectWire.writeList(out, List.of("row1", "row2", "row3"), null, 1);

    String tokenId = UUID.randomUUID().toString();
    UUID playerId = UUID.randomUUID();
    ReservationToken token = new ReservationToken(tokenId, "survival", playerId, System.currentTimeMillis() + 60000L, ReservationToken.State.CLAIMED, "world");
    String encodedToken = ProxyDirectWire.encodeToken(token);
    ProxyDirectWire.writeSignedPayload(out, encodedToken, null, 1);

    DataInputStream in = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
    assertEquals(ProxyDirectWire.OP_HEARTBEAT, ProxyDirectWire.readOpcode(in));
    assertEquals("test-heartbeat", ProxyDirectWire.readSignedPayload(in, null));
    assertEquals(List.of("row1", "row2", "row3"), ProxyDirectWire.readList(in, null));

    String readTokenStr = ProxyDirectWire.readSignedPayload(in, null);
    ReservationToken readToken = ProxyDirectWire.decodeToken(readTokenStr);
    assertNotNull(readToken);
    assertEquals(tokenId, readToken.tokenId());
    assertEquals(playerId, readToken.playerId());
    assertEquals("survival", readToken.serverId());
    assertEquals(Optional.of("world"), readToken.regionKey());

    assertNull(ProxyDirectWire.decodeToken(null));
    assertNull(ProxyDirectWire.decodeToken(""));
    assertNull(ProxyDirectWire.decodeToken("invalid-token-wire-format"));
  }

  @Test
  @DisplayName("ProxyDirectWire signed payload round-trip with and without HmacVerifier")
  void testSignedPayloads() throws IOException {
    byte[] key = new byte[32];
    for (int i = 0; i < 32; i++) key[i] = (byte) i;
    HmacVerifier verifier = HmacVerifier.forTesting(key, 1, 1);
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(baos);

    ProxyDirectWire.writeSignedPayload(out, "sensitive-data", verifier, 1);
    ProxyDirectWire.writeSignedPayload(out, "plain-data", null, 1);
    ProxyDirectWire.writeList(out, List.of("item1", "item2"), verifier, 1);

    DataInputStream in = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
    assertEquals("sensitive-data", ProxyDirectWire.readSignedPayload(in, verifier));
    assertEquals("plain-data", ProxyDirectWire.readSignedPayload(in, null));
    assertEquals(List.of("item1", "item2"), ProxyDirectWire.readList(in, verifier));
  }

  @Test
  @DisplayName("NetworkBindings open handles in-memory and plugin-message configurations")
  void testNetworkBindingsOpen() {
    io.github.dailystruggle.rtp.proxy.common.RTPProxyAccessor accessor = new io.github.dailystruggle.rtp.proxy.common.RTPProxyAccessor() {
      @Override public io.github.dailystruggle.rtp.proxy.common.Role role() { return io.github.dailystruggle.rtp.proxy.common.Role.PROXY_VELOCITY; }
      @Override public String proxyId() { return "p1"; }
      @Override public void sendMessage(UUID p, String m) {}
      @Override public java.util.concurrent.CompletableFuture<Void> transferPlayer(UUID p, String s) {
        return java.util.concurrent.CompletableFuture.completedFuture(null);
      }
    };

    java.util.Map<String, Object> root = new java.util.LinkedHashMap<>();
    java.util.Map<String, Object> net = new java.util.LinkedHashMap<>();
    net.put("enabled", false);
    net.put("proxyId", "p1");
    net.put("transport", java.util.Map.of("type", "in-memory"));
    root.put("network", net);
    NetworkConfig memCfg = NetworkConfig.fromMap(root, accessor);
    NetworkTransport transport = NetworkBindings.open(memCfg, null);
    assertTrue(transport instanceof InMemoryNetworkStateBinding);

    net.put("transport", java.util.Map.of("type", "plugin-message"));
    NetworkConfig pluginMsgCfg = NetworkConfig.fromMap(root, accessor);
    NetworkTransport autoTransport = NetworkBindings.open(pluginMsgCfg, null);
    assertTrue(autoTransport instanceof InMemoryNetworkStateBinding);

    net.put("transport", java.util.Map.of("type", "unknown-type"));
    root.put("transport", java.util.Map.of("type", "unknown-type"));
    NetworkConfig unknownCfg = NetworkConfig.fromMap(root, accessor);
    assertThrows(IllegalArgumentException.class, () -> NetworkBindings.open(unknownCfg, null));
  }
}
