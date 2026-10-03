package io.github.dailystruggle.rtp.common.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dailystruggle.rtp.proxy.common.spi.NetworkTransport;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class NetworkStateBindingTest {

  private static class DummyBinding implements NetworkStateBinding {
    private final NetworkTransport transport;
    DummyBinding(NetworkTransport transport) {
      this.transport = transport;
    }
    @Override
    public NetworkTransport transport() {
      return transport;
    }
  }

  @Test
  void testDefaultMethodsWithoutTransport() {
    NetworkStateBinding binding = new DummyBinding(null);
    UUID id = UUID.randomUUID();

    assertEquals(null, binding.transport());
    assertEquals(0L, binding.getLastTeleportTime(id));
    // void method should safely no-op
    binding.setLastTeleportTime(id, 12345L);
  }

  @Test
  void testDefaultMethodsWithTransport() {
    UUID id = UUID.randomUUID();
    long[] savedTime = new long[] {0L};

    NetworkTransport mockTransport = (NetworkTransport) java.lang.reflect.Proxy.newProxyInstance(
        getClass().getClassLoader(),
        new Class<?>[]{NetworkTransport.class},
        (proxy, method, args) -> {
          if (method.getName().equals("getLastTeleportTime")) {
            return CompletableFuture.completedFuture(savedTime[0]);
          }
          if (method.getName().equals("setLastTeleportTime")) {
            savedTime[0] = (long) args[1];
            return null;
          }
          return null;
        });

    NetworkStateBinding binding = new DummyBinding(mockTransport);

    binding.setLastTeleportTime(id, 99999L);
    assertEquals(99999L, binding.getLastTeleportTime(id));
  }

  @Test
  void testExceptionsInGetLastTeleportTime() {
    UUID id = UUID.randomUUID();
    NetworkTransport errTransport = (NetworkTransport) java.lang.reflect.Proxy.newProxyInstance(
        getClass().getClassLoader(),
        new Class<?>[]{NetworkTransport.class},
        (proxy, method, args) -> {
          if (method.getName().equals("getLastTeleportTime")) {
            return CompletableFuture.failedFuture(new RuntimeException("boom"));
          }
          return null;
        });

    NetworkStateBinding binding = new DummyBinding(errTransport);
    assertEquals(0L, binding.getLastTeleportTime(id));
  }

  @Test
  void testRTPNetworkManagerDeprecatedDefaults() {
    UUID id = UUID.randomUUID();
    long[] stored = new long[] {0L};

    RTPNetworkManager manager = new RTPNetworkManager() {
      @Override public void setLastTeleportTime(UUID playerId, long epochMillis) {
        stored[0] = epochMillis;
      }
      @Override public long getLastTeleportTime(UUID playerId) {
        return stored[0];
      }
      @Override public void publish(String channel, String jsonPayload) {}
      @Override public void initializeAsync() {}
      @Override public void shutdown() {}
    };

    assertEquals(0L, manager.getCooldown(id));
    manager.setCooldown(id, 30L);
    assertEquals(stored[0], manager.getLastTeleportTime(id));
    long cd = manager.getCooldown(id);
    // cd should be approximately 30 (>= 28 and <= 31)
    org.junit.jupiter.api.Assertions.assertTrue(cd >= 28L && cd <= 31L);
  }
}
