package io.github.dailystruggle.rtp.fabric.v1_21_R11;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S-005: on 1.21.11 three {@code ServerChunkCache} methods share {@code (int, int, ChunkStatus,
 * boolean) -> CompletableFuture}. Only the private {@code getChunkFutureMainThread} is
 * non-blocking on the tick thread; the public {@code getChunkFuture} managedBlocks there.
 */
class V1_21_R11ChunkFutureMethodSelectionTest {

  /** The adapter's static TicketType fields need the vanilla registries. */
  @BeforeAll
  static void bootstrapRegistries() {
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
  }

  /** Same shape as 1.21.11 {@code ServerChunkCache} (public wrapper, private body, synthetic lambda). */
  @SuppressWarnings("unused")
  static final class ShapedLikeServerChunkCache {
    public CompletableFuture<Object> getChunkFuture(int x, int z, ChunkStatus status, boolean load) {
      Supplier<CompletableFuture<Object>> body = () -> getChunkFutureMainThread(x, z, status, load);
      return body.get();
    }

    private CompletableFuture<Object> getChunkFutureMainThread(int x, int z, ChunkStatus status, boolean load) {
      return CompletableFuture.completedFuture(null);
    }
  }

  @Test
  @DisplayName("S-005: chunk-future scan binds only the private non-blocking getChunkFutureMainThread")
  void bindsOnlyPrivateMainThreadMethod() {
    List<String> matches = new ArrayList<>();
    int sameSignature = 0;
    for (Method m : ShapedLikeServerChunkCache.class.getDeclaredMethods()) {
      Class<?>[] p = m.getParameterTypes();
      if (p.length == 4 && p[2] == ChunkStatus.class) sameSignature++;
      if (V1_21_R11FabricVersionAdapter.isNonBlockingChunkFutureMethod(m)) matches.add(m.getName());
    }
    assertEquals(3, sameSignature, "fixture must mirror the public, private and synthetic-lambda trio");
    assertEquals(List.of("getChunkFutureMainThread"), matches);
  }

  @Test
  @DisplayName("S-004: chunk-future ChunkResult yields the loaded chunk, null for a failed load")
  void unwrapsChunkResult() {
    Object chunk = new Object();
    assertEquals(chunk, V1_21_R11FabricVersionAdapter.chunkResultValue(ChunkResult.of(chunk)));
    assertNull(V1_21_R11FabricVersionAdapter.chunkResultValue(ChunkResult.error("unloaded")));
    assertNull(V1_21_R11FabricVersionAdapter.chunkResultValue(null));
  }
}
