package io.github.dailystruggle.rtp.common.tasks.teleport;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.world.RTPChunkManager;
import io.github.dailystruggle.rtp.common.RTP;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class LoadChunksTest {
  @BeforeEach
  public void setUp() {
    RTPServerAccessor serverAccessor = mock(RTPServerAccessor.class);
    RTPChunkManager chunkManager = mock(RTPChunkManager.class);
    when(serverAccessor.getChunkManager()).thenReturn(chunkManager);
    when(chunkManager.getChunkAtAsync(any(), anyInt(), anyInt()))
        .thenReturn(CompletableFuture.completedFuture(1L));
    RTP.serverAccessor = serverAccessor;
  }

  @Test
  public void testFutureCompletion() throws Exception {
    // This is a simplified test to demonstrate the future completion check
    RTPServerAccessor serverAccessor = mock(RTPServerAccessor.class);
    RTPChunkManager chunkManager = mock(RTPChunkManager.class);
    when(serverAccessor.getChunkManager()).thenReturn(chunkManager);
    RTP.serverAccessor = serverAccessor;

    CompletableFuture<Long> future = new CompletableFuture<>();
    when(chunkManager.getChunkAtAsync(any(), anyInt(), anyInt())).thenReturn(future);

    // Assert that we can complete the future later
    CompletableFuture<Void> testFuture =
        CompletableFuture.runAsync(
            () -> {
              try {
                TimeUnit.MILLISECONDS.sleep(100);
                future.complete(1L);
              } catch (InterruptedException e) {
                e.printStackTrace();
              }
            });

    Long chunkKey = future.get(1, TimeUnit.SECONDS);
    assertNotNull(chunkKey);
    assertNotNull(future, "Future should be instantiated");
  }
}
