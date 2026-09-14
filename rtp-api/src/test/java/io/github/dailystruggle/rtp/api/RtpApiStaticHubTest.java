package io.github.dailystruggle.rtp.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.metrics.api.FoliaRegionSample;
import io.github.dailystruggle.metrics.api.MetricsSnapshot;
import io.github.dailystruggle.rtp.api.event.PlayerMoveEvent;
import io.github.dailystruggle.rtp.api.event.PrefabAppliedEvent;
import io.github.dailystruggle.rtp.api.hooks.RTPHooks;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RtpApiStaticHubTest {

  private RTPServerAccessor originalServerAccessor;
  private UUID originalServerId;

  @BeforeEach
  void setUp() {
    originalServerAccessor = RTPAPI.serverAccessor;
    originalServerId = RTPAPI.serverId;
    resetDelegates();
  }

  @AfterEach
  void tearDown() {
    RTPAPI.serverAccessor = originalServerAccessor;
    RTPAPI.serverId = originalServerId;
    resetDelegates();
  }

  private void resetDelegates() {
    RTPAPI.biomeProvider = null;
    RTPAPI.hooks = null;
    RTPAPI.teleportDelegate = null;
    RTPAPI.cancelDelegate = null;
    RTPAPI.queueDepthDelegate = null;
    RTPAPI.warmupDelegate = null;
    RTPAPI.allowedTargetsDelegate = null;
    RTPAPI.targetStatusDelegate = null;
    RTPAPI.metricsSnapshotDelegate = null;
  }

  @Test
  @DisplayName("Default constructor can be invoked for 100% coverage")
  void testConstructor() {
    RTPAPI api = new RTPAPI();
    assertNotNull(api);
  }

  @Test
  @DisplayName("setServerAccessor validates null, sets instance, and prevents reassignment")
  void testSetServerAccessor() throws Exception {
    RTPAPI.serverAccessor = null;
    assertThrows(IllegalArgumentException.class, () -> RTPAPI.setServerAccessor(null));

    RTPServerAccessor mock1 = (RTPServerAccessor) java.lang.reflect.Proxy.newProxyInstance(
        getClass().getClassLoader(),
        new Class<?>[]{RTPServerAccessor.class},
        (proxy, method, args) -> null);

    RTPServerAccessor mock2 = (RTPServerAccessor) java.lang.reflect.Proxy.newProxyInstance(
        getClass().getClassLoader(),
        new Class<?>[]{RTPServerAccessor.class},
        (proxy, method, args) -> null);

    RTPAPI.setServerAccessor(mock1);
    assertSame(mock1, RTPAPI.serverAccessor);

    // Setting the same accessor is idempotent
    RTPAPI.setServerAccessor(mock1);

    // Setting a different accessor throws IllegalStateException
    assertThrows(IllegalStateException.class, () -> RTPAPI.setServerAccessor(mock2));
  }

  @Test
  @DisplayName("getBiomes delegates when provider is set or returns null when unset")
  void testGetBiomes() {
    assertNull(RTPAPI.getBiomes(null));

    RTPAPI.biomeProvider = world -> Set.of("plains", "forest");
    Set<String> biomes = RTPAPI.getBiomes(null);
    assertNotNull(biomes);
    assertEquals(2, biomes.size());
    assertTrue(biomes.contains("plains"));
  }

  @Test
  @DisplayName("hooks() throws IllegalStateException when uninitialized or returns instance")
  void testHooks() {
    RTPAPI.hooks = null;
    assertThrows(IllegalStateException.class, RTPAPI::hooks);

    RTPHooks mockHooks = (RTPHooks) java.lang.reflect.Proxy.newProxyInstance(
        getClass().getClassLoader(),
        new Class<?>[]{RTPHooks.class},
        (proxy, method, args) -> null);

    RTPAPI.hooks = mockHooks;
    assertSame(mockHooks, RTPAPI.hooks());
  }

  @Test
  @DisplayName("onPrefabApplied and watchPlayerMove delegate to dispatchers")
  void testEventSubscriptions() throws Exception {
    AtomicBoolean prefabNotified = new AtomicBoolean(false);
    try (AutoCloseable sub = RTPAPI.onPrefabApplied(event -> prefabNotified.set(true))) {
      assertNotNull(sub);
      RTPAPI.prefabEvents.fire(new PrefabAppliedEvent("test-prefab", UUID.randomUUID(), List.of("config.yml"), Collections.emptyMap(), true));
      assertTrue(prefabNotified.get());
    }

    UUID playerId = UUID.randomUUID();
    AtomicBoolean moveNotified = new AtomicBoolean(false);
    try (AutoCloseable sub = RTPAPI.watchPlayerMove(playerId, event -> moveNotified.set(true))) {
      assertNotNull(sub);
      RTPAPI.playerMoveEvents.fire(new PlayerMoveEvent(playerId, "world", 0, 64, 0, 1, 64, 0));
      assertTrue(moveNotified.get());
    }
  }

  @Test
  @DisplayName("teleport() validates arguments and delegate presence")
  void testTeleport() {
    UUID playerId = UUID.randomUUID();
    RtpTarget target = RtpTarget.defaultRegion();

    assertThrows(IllegalArgumentException.class, () -> RTPAPI.teleport(null, target));
    assertThrows(IllegalArgumentException.class, () -> RTPAPI.teleport(playerId, null));

    RTPAPI.teleportDelegate = null;
    assertThrows(IllegalStateException.class, () -> RTPAPI.teleport(playerId, target));

    RTPResult expectedResult = RTPResult.queued("ok");
    RTPAPI.teleportDelegate = (uuid, t) -> CompletableFuture.completedFuture(expectedResult);

    CompletableFuture<RTPResult> fut = RTPAPI.teleport(playerId, target);
    assertEquals(expectedResult, fut.join());
  }

  @Test
  @DisplayName("cancel() validates arguments and delegate presence")
  void testCancel() {
    UUID playerId = UUID.randomUUID();

    assertThrows(IllegalArgumentException.class, () -> RTPAPI.cancel(null));

    RTPAPI.cancelDelegate = null;
    assertThrows(IllegalStateException.class, () -> RTPAPI.cancel(playerId));

    RTPAPI.cancelDelegate = uuid -> true;
    assertTrue(RTPAPI.cancel(playerId));
  }

  @Test
  @DisplayName("queueDepth() validates arguments and delegate presence")
  void testQueueDepth() {
    assertThrows(IllegalArgumentException.class, () -> RTPAPI.queueDepth(null));

    RTPWorld<String> dummyWorld = new StubWorld();

    RTPAPI.queueDepthDelegate = null;
    assertThrows(IllegalStateException.class, () -> RTPAPI.queueDepth(dummyWorld));

    RTPAPI.queueDepthDelegate = w -> 42;
    assertEquals(42, RTPAPI.queueDepth(dummyWorld));
  }

  private static final class StubWorld extends RTPWorld<String> {
    StubWorld() { super("stub"); }
    @Override protected java.util.concurrent.CompletableFuture<Void> setForceLoadedImpl(int cx, int cz, boolean f) {
      return java.util.concurrent.CompletableFuture.completedFuture(null);
    }
    @Override public String name() { return "stub"; }
    @Override public UUID id() { return new UUID(0L, 0L); }
    @Override public java.util.concurrent.CompletableFuture<Long> getChunkAt(int cx, int cz) { return java.util.concurrent.CompletableFuture.completedFuture(0L); }
    @Override public java.util.concurrent.CompletableFuture<io.github.dailystruggle.rtp.api.world.ChunkSet> getChunkAtAsync(int cx, int cz) { return java.util.concurrent.CompletableFuture.completedFuture(null); }
    @Override public java.util.concurrent.CompletableFuture<Integer> getServerForceLoadedCount() { return java.util.concurrent.CompletableFuture.completedFuture(0); }
    @Override public io.github.dailystruggle.rtp.api.world.RTPChunk<?> getCachedChunk(long key) { return null; }
    @Override public void keepChunkAt(int cx, int cz) { }
    @Override public void forgetChunkAt(int cx, int cz) { }
    @Override public void forgetChunks() { }
    @Override public String getBiome(int x, int y, int z) { return ""; }
    @Override public void platform(RTPLocation location) { }
    @Override public boolean isInactive() { return false; }
    @Override public void save() { }
    @Override public int getMaxHeight() { return 320; }
    @Override public int getMinHeight() { return -64; }
    @Override public int getCacheSize() { return 0; }
    @Override public long getSeed() { return 0L; }
  }

  @Test
  @DisplayName("isWarmingUp() validates arguments and delegate presence")
  void testIsWarmingUp() {
    UUID playerId = UUID.randomUUID();

    assertThrows(IllegalArgumentException.class, () -> RTPAPI.isWarmingUp(null));

    RTPAPI.warmupDelegate = null;
    assertThrows(IllegalStateException.class, () -> RTPAPI.isWarmingUp(playerId));

    RTPAPI.warmupDelegate = uuid -> true;
    assertTrue(RTPAPI.isWarmingUp(playerId));
  }

  @Test
  @DisplayName("getAllowedTargets() validates arguments, delegate presence, and null fallback")
  void testGetAllowedTargets() {
    UUID playerId = UUID.randomUUID();

    assertThrows(IllegalArgumentException.class, () -> RTPAPI.getAllowedTargets(null));

    RTPAPI.allowedTargetsDelegate = null;
    assertThrows(IllegalStateException.class, () -> RTPAPI.getAllowedTargets(playerId));

    RTPAPI.allowedTargetsDelegate = uuid -> null;
    assertEquals(Collections.emptyList(), RTPAPI.getAllowedTargets(playerId));

    RtpTarget t = RtpTarget.defaultRegion();
    RTPAPI.allowedTargetsDelegate = uuid -> List.of(t);
    assertEquals(List.of(t), RTPAPI.getAllowedTargets(playerId));
  }

  @Test
  @DisplayName("getTargetStatus() validates arguments, delegate presence, and null fallback")
  void testGetTargetStatus() {
    UUID playerId = UUID.randomUUID();
    RtpTarget target = RtpTarget.defaultRegion();

    assertThrows(IllegalArgumentException.class, () -> RTPAPI.getTargetStatus(null, target));
    assertThrows(IllegalArgumentException.class, () -> RTPAPI.getTargetStatus(playerId, null));

    RTPAPI.targetStatusDelegate = null;
    assertThrows(IllegalStateException.class, () -> RTPAPI.getTargetStatus(playerId, target));

    RTPAPI.targetStatusDelegate = (uuid, t) -> null;
    RtpTargetStatus fallback = RTPAPI.getTargetStatus(playerId, target);
    assertEquals(RtpTargetStatus.Availability.UNKNOWN, fallback.availability());
    assertEquals(0L, fallback.remainingCooldownMillis());
    assertEquals(0.0, fallback.cost());

    RtpTargetStatus active = new RtpTargetStatus(RtpTargetStatus.Availability.READY, 10L, 5.0);
    RTPAPI.targetStatusDelegate = (uuid, t) -> active;
    assertEquals(active, RTPAPI.getTargetStatus(playerId, target));
  }

  @Test
  @DisplayName("getMetricsSnapshot() and getRegionSamples() branches")
  void testMetricsSnapshotAndRegionSamples() {
    RTPAPI.metricsSnapshotDelegate = null;
    assertThrows(IllegalStateException.class, RTPAPI::getMetricsSnapshot);
    assertThrows(IllegalStateException.class, RTPAPI::getRegionSamples);

    RTPAPI.metricsSnapshotDelegate = () -> null;
    assertNull(RTPAPI.getMetricsSnapshot());
    assertEquals(Collections.emptyList(), RTPAPI.getRegionSamples());

    MetricsSnapshot emptyRegions = new MetricsSnapshot(
        20.0, 20.0, 20.0, 5.0, 10, 100, 100L, 200L, System.currentTimeMillis(), Collections.emptyList());
    RTPAPI.metricsSnapshotDelegate = () -> emptyRegions;
    assertEquals(Collections.emptyList(), RTPAPI.getRegionSamples());

    FoliaRegionSample sample = new FoliaRegionSample("world", 20.0, 5.0, 4, 1);
    MetricsSnapshot withRegions = new MetricsSnapshot(
        20.0, 20.0, 20.0, 5.0, 10, 100, 100L, 200L, System.currentTimeMillis(), List.of(sample));
    RTPAPI.metricsSnapshotDelegate = () -> withRegions;
    assertEquals(List.of(sample), RTPAPI.getRegionSamples());
  }
}
