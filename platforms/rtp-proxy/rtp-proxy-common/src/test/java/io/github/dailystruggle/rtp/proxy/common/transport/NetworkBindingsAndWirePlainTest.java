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
import io.github.dailystruggle.rtp.proxy.common.transport.direct.ProxyDirectWire.Direction;
import io.github.dailystruggle.rtp.proxy.common.transport.direct.ProxyDirectWire.WireContext;
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
    byte[] key = new byte[32];
    for (int i = 0; i < 32; i++) key[i] = (byte) (i + 7);
    HmacVerifier v = HmacVerifier.forTesting(key, 1, 1);
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(baos);

    WireContext ctx = ProxyDirectWire.writeRequestHeader(out, ProxyDirectWire.OP_HEARTBEAT);
    ProxyDirectWire.writeSignedPayload(out, ctx, Direction.REQUEST, "test-heartbeat", v, 1);
    ProxyDirectWire.writeList(out, ctx, Direction.RESPONSE, List.of("row1", "row2", "row3"), v, 1);

    String tokenId = UUID.randomUUID().toString();
    UUID playerId = UUID.randomUUID();
    ReservationToken token = new ReservationToken(tokenId, "survival", playerId, System.currentTimeMillis() + 60000L, ReservationToken.State.CLAIMED, "world");
    String encodedToken = ProxyDirectWire.encodeToken(token);
    ProxyDirectWire.writeSignedPayload(out, ctx, Direction.RESPONSE, encodedToken, v, 1);

    DataInputStream in = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
    ProxyDirectWire.RequestHeader header = ProxyDirectWire.readRequestHeader(in);
    assertEquals(ProxyDirectWire.OP_HEARTBEAT, header.opcode());
    assertEquals(ctx, header.context());
    assertEquals("test-heartbeat", ProxyDirectWire.readSignedPayload(in, ctx, Direction.REQUEST, v));
    assertEquals(List.of("row1", "row2", "row3"), ProxyDirectWire.readList(in, ctx, Direction.RESPONSE, v));

    String readTokenStr = ProxyDirectWire.readSignedPayload(in, ctx, Direction.RESPONSE, v);
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
  @DisplayName("REQ-RTP-PROXY-007: ProxyDirectWire signs with a verifier and fails closed without one")
  void testSignedPayloads() throws IOException {
    byte[] key = new byte[32];
    for (int i = 0; i < 32; i++) key[i] = (byte) i;
    HmacVerifier verifier = HmacVerifier.forTesting(key, 1, 1);
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(baos);

    WireContext ctx = new WireContext(ProxyDirectWire.OP_CANCEL, 1_000L, "nonce");
    ProxyDirectWire.writeSignedPayload(out, ctx, Direction.REQUEST, "sensitive-data", verifier, 1);
    assertThrows(IllegalStateException.class,
        () -> ProxyDirectWire.writeSignedPayload(out, ctx, Direction.REQUEST, "plain-data", null, 1));
    ProxyDirectWire.writeSignedPayload(out, ctx, Direction.REQUEST, "signed-but-unverified", verifier, 1);
    ProxyDirectWire.writeList(out, ctx, Direction.RESPONSE, List.of("item1", "item2"), verifier, 1);

    DataInputStream in = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
    assertEquals("sensitive-data", ProxyDirectWire.readSignedPayload(in, ctx, Direction.REQUEST, verifier));
    assertNull(ProxyDirectWire.readSignedPayload(in, ctx, Direction.REQUEST, null),
        "null verifier must reject every payload");
    assertEquals(List.of("item1", "item2"), ProxyDirectWire.readList(in, ctx, Direction.RESPONSE, verifier));
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
