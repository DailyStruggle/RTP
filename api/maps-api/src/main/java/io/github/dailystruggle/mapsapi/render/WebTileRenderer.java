package io.github.dailystruggle.mapsapi.render;

import io.github.dailystruggle.mapsapi.image.ImageMapCanvas;
import io.github.dailystruggle.mapsapi.model.ChartModel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import javax.imageio.ImageIO;

/**
 * Platform-neutral web map raster tile renderer (ADR-086).
 *
 * <p>Renders a {@link ChartModel} into Web Mercator raster tiles ($512 \times 512$ or custom dimensions)
 * with strict failure domain isolation and fail-closed bulkhead protection via {@link AsyncRenderLimiter}.
 */
public final class WebTileRenderer {

  /** 1x1 transparent PNG fallback byte array. */
  private static final byte[] TRANSPARENT_1X1_PNG = new byte[] {
      (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
      0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
      0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
      0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, (byte) 0xC4,
      (byte) 0x89, 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41,
      0x54, 0x78, (byte) 0x9C, 0x63, 0x00, 0x01, 0x00, 0x00,
      0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, (byte) 0xB4, 0x00,
      0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, (byte) 0xAE,
      0x42, 0x60, (byte) 0x82
  };

  /** Default permit acquisition timeout in milliseconds (ADR-086). */
  public static final long DEFAULT_TIMEOUT_MS = 500L;

  private final AsyncRenderLimiter limiter;

  public WebTileRenderer() {
    this(new AsyncRenderLimiter());
  }

  public WebTileRenderer(AsyncRenderLimiter limiter) {
    this.limiter = Objects.requireNonNull(limiter, "limiter must not be null");
  }

  /**
   * Returns static 1x1 transparent PNG bytes used for fail-closed fast path.
   *
   * @return byte array of 1x1 transparent PNG
   */
  public static byte[] transparent1x1Png() {
    return TRANSPARENT_1X1_PNG.clone();
  }

  /**
   * Renders the specified chart model onto a raster image asynchronously with bulkhead protection.
   *
   * <p>If permit acquisition exceeds {@code timeoutMs}, fails closed immediately by returning
   * {@link #transparent1x1Png()} without allocating raster buffers.
   *
   * @param renderer  chart renderer implementation
   * @param model     model to render
   * @param width     tile width in pixels
   * @param height    tile height in pixels
   * @param timeoutMs permit wait timeout in milliseconds
   * @param <M>       chart model type
   * @return CompletableFuture completing with encoded PNG byte array
   */
  public <M extends ChartModel> CompletableFuture<byte[]> renderTilePng(
      ChartRenderer<M> renderer, M model, int width, int height, long timeoutMs) {
    return CompletableFuture.supplyAsync(() -> {
      if (!limiter.tryAcquire(timeoutMs)) {
        // Fail closed under concurrency pressure: return 1x1 transparent fallback
        return transparent1x1Png();
      }
      try {
        ImageMapCanvas canvas = new ImageMapCanvas(width, height);
        renderer.render(canvas, model);
        return canvas.toByteArray("png");
      } catch (Throwable t) {
        // Isolation guarantee: return fail-closed image rather than bubbling error to web engine
        return transparent1x1Png();
      } finally {
        limiter.release();
      }
    });
  }

  public AsyncRenderLimiter limiter() {
    return limiter;
  }
}
