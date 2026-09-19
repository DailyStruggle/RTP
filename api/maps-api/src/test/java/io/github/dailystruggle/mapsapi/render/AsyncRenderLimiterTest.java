package io.github.dailystruggle.mapsapi.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.mapsapi.model.Heatmap2D;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class AsyncRenderLimiterTest {

  @Test
  @DisplayName("AsyncRenderLimiter enforces bounded concurrency permits")
  public void testLimiterPermits() {
    AsyncRenderLimiter limiter = new AsyncRenderLimiter(2);
    assertEquals(2, limiter.availablePermits());
    assertEquals(2, limiter.maxPermits());

    assertTrue(limiter.tryAcquire(50));
    assertEquals(1, limiter.availablePermits());

    assertTrue(limiter.tryAcquire(50));
    assertEquals(0, limiter.availablePermits());

    // Third acquire should fail when timeout expires
    long start = System.currentTimeMillis();
    boolean acquired = limiter.tryAcquire(50);
    long elapsed = System.currentTimeMillis() - start;

    assertTrue(!acquired);
    assertTrue(elapsed >= 40);

    limiter.release();
    assertEquals(1, limiter.availablePermits());

    limiter.release();
    assertEquals(2, limiter.availablePermits());
  }

  @Test
  @DisplayName("WebTileRenderer returns 1x1 transparent image when concurrency limit is saturated (fail-closed)")
  public void testWebTileRendererFailClosed() throws Exception {
    AsyncRenderLimiter limiter = new AsyncRenderLimiter(1);
    WebTileRenderer webTileRenderer = new WebTileRenderer(limiter);

    // Acquire the only permit
    assertTrue(limiter.tryAcquire(10));

    HeatmapRenderer renderer = new HeatmapRenderer();
    Heatmap2D model = new Heatmap2D(128, 128, new double[128 * 128], 0.0, 1.0);

    // Call renderTilePng with small timeout (50ms) - should fail closed and return 1x1 transparent PNG
    CompletableFuture<byte[]> future = webTileRenderer.renderTilePng(renderer, model, 128, 128, 50);
    byte[] result = future.get(2, TimeUnit.SECONDS);

    assertNotNull(result);
    assertArrayEquals(WebTileRenderer.transparent1x1Png(), result);

    // Release permit
    limiter.release();

    // Now render should succeed and return real image bytes
    CompletableFuture<byte[]> successFuture = webTileRenderer.renderTilePng(renderer, model, 128, 128, 500);
    byte[] realImage = successFuture.get(2, TimeUnit.SECONDS);
    assertNotNull(realImage);
    assertTrue(realImage.length > WebTileRenderer.transparent1x1Png().length);
  }
}
