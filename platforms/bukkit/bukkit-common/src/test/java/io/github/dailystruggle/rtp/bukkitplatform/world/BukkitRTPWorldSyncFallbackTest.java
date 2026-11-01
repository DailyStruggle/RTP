package io.github.dailystruggle.rtp.bukkitplatform.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;

/**
 * S-005: live chunk loads go through the async chunk API only. Every async failure edge rejects
 * the candidate (future completes {@code null}) instead of calling {@link World#getChunkAt(int,
 * int)}. Runs on the MockBukkit primary thread, where a synchronous fallback would run inline.
 */
class BukkitRTPWorldSyncFallbackTest {

  private ServerMock server;
  /** Value returned by the World mock for {@code getChunkAtAsync(int,int)}; null = return null. */
  private final AtomicReference<CompletableFuture<Chunk>> asyncResult = new AtomicReference<>();

  @BeforeEach
  void setUp() {
    server = MockBukkit.mock();
  }

  @AfterEach
  void tearDown() {
    MockBukkit.unmock();
  }

  private World mockWorld() {
    Answer<Object> answer = invocation -> {
      java.lang.reflect.Method m = invocation.getMethod();
      if (m.getName().equals("getChunkAtAsync") && m.getParameterCount() == 2
          && CompletableFuture.class.isAssignableFrom(m.getReturnType())) {
        return asyncResult.get();
      }
      if (m.getName().equals("getName")) return "sync-fallback-test";
      return Mockito.RETURNS_DEFAULTS.answer(invocation);
    };
    return Mockito.mock(World.class, answer);
  }

  private static boolean asyncApiPresent() {
    try {
      return CompletableFuture.class.isAssignableFrom(
          World.class.getMethod("getChunkAtAsync", int.class, int.class).getReturnType());
    } catch (NoSuchMethodException e) {
      return false;
    }
  }

  @Test
  @DisplayName("S-005: async API returning null rejects the candidate without synchronous getChunkAt")
  void asyncApiReturningNullRejects() {
    World world = mockWorld();
    asyncResult.set(null);
    assertTrue(server.isPrimaryThread());

    CompletableFuture<Long> future = new BukkitRTPWorld(world).loadLiveChunk(2, 3);

    assertTrue(future.isDone());
    assertNull(future.join());
    verify(world, never()).getChunkAt(anyInt(), anyInt());
  }

  @Test
  @DisplayName("S-005: async load failure rejects the candidate without synchronous getChunkAt")
  void asyncLoadFailureRejects() {
    World world = mockWorld();
    asyncResult.set(CompletableFuture.failedFuture(new IllegalStateException("io failure")));

    CompletableFuture<Long> future = new BukkitRTPWorld(world).loadLiveChunk(2, 3);

    assertTrue(future.isDone());
    assertNull(future.join());
    verify(world, never()).getChunkAt(anyInt(), anyInt());
  }

  @Test
  @DisplayName("S-005: async load resolving to a null chunk rejects without synchronous getChunkAt")
  void asyncLoadNullChunkRejects() {
    World world = mockWorld();
    asyncResult.set(CompletableFuture.completedFuture(null));

    CompletableFuture<Long> future = new BukkitRTPWorld(world).loadLiveChunk(2, 3);

    assertTrue(future.isDone());
    assertNull(future.join());
    verify(world, never()).getChunkAt(anyInt(), anyInt());
  }

  @Test
  @DisplayName("S-005: successful async load returns the packed chunk key")
  void asyncLoadSuccessReturnsKey() {
    Assumptions.assumeTrue(asyncApiPresent(), "CompletableFuture getChunkAtAsync absent on test API");
    World world = mockWorld();
    asyncResult.set(CompletableFuture.completedFuture(Mockito.mock(Chunk.class)));

    CompletableFuture<Long> future = new BukkitRTPWorld(world).loadLiveChunk(2, 3);

    long expected = (2L & 0xffffffffL) | (3L << 32);
    assertEquals(expected, future.join());
    verify(world, never()).getChunkAt(anyInt(), anyInt());
  }

  @Test
  @DisplayName("S-005: keepChunkAt never loads synchronously; cache and ticket follow the async load")
  void keepChunkAtUsesAsyncLoadOnly() {
    Assumptions.assumeTrue(asyncApiPresent(), "CompletableFuture getChunkAtAsync absent on test API");
    MockBukkit.createMockPlugin("RTP");
    World world = mockWorld();
    Chunk chunk = Mockito.mock(Chunk.class);
    when(chunk.isLoaded()).thenReturn(true);
    CompletableFuture<Chunk> pending = new CompletableFuture<>();
    asyncResult.set(pending);
    BukkitRTPWorld rtpWorld = new BukkitRTPWorld(world);
    long key = (2L & 0xffffffffL) | (3L << 32);

    rtpWorld.keepChunkAt(2, 3);

    assertNull(rtpWorld.getCachedChunk(key));
    assertEquals(0L, rtpWorld.lifetimeTicketsIssued.get());
    verify(world, never()).addPluginChunkTicket(anyInt(), anyInt(), any());

    pending.complete(chunk);

    assertNotNull(rtpWorld.getCachedChunk(key));
    assertEquals(1L, rtpWorld.lifetimeTicketsIssued.get());
    verify(world, times(1)).addPluginChunkTicket(eq(2), eq(3), any());
    verify(world, never()).getChunkAt(anyInt(), anyInt());
  }

  @Test
  @DisplayName("S-005: keepChunkAt never loads synchronously; failed or null async load keeps nothing")
  void keepChunkAtFailedLoadKeepsNothing() {
    MockBukkit.createMockPlugin("RTP");
    World world = mockWorld();
    BukkitRTPWorld rtpWorld = new BukkitRTPWorld(world);
    long key = (2L & 0xffffffffL) | (3L << 32);

    asyncResult.set(CompletableFuture.failedFuture(new IllegalStateException("io failure")));
    rtpWorld.keepChunkAt(2, 3);
    asyncResult.set(CompletableFuture.completedFuture(null));
    rtpWorld.keepChunkAt(2, 3);
    asyncResult.set(null);
    rtpWorld.keepChunkAt(2, 3);

    assertNull(rtpWorld.getCachedChunk(key));
    assertEquals(0L, rtpWorld.lifetimeTicketsIssued.get());
    verify(world, never()).addPluginChunkTicket(anyInt(), anyInt(), any());
    verify(world, never()).getChunkAt(anyInt(), anyInt());
  }

  @Test
  @DisplayName("S-005: getBiome on primary thread with unloaded chunk returns null without calling World#getBiome")
  void getBiomeOnPrimaryThreadUnloadedChunkSkipsLiveCall() {
    World world = mockWorld();
    when(world.isChunkLoaded(2, 3)).thenReturn(false);
    assertTrue(server.isPrimaryThread());

    BukkitRTPWorld rtpWorld = new BukkitRTPWorld(world);
    String biome = rtpWorld.getBiome(36, 64, 52);

    assertNull(biome);
    verify(world, never()).getBiome(anyInt(), anyInt(), anyInt());
  }
}
