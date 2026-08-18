package io.github.dailystruggle.mapsapi.render;

import io.github.dailystruggle.mapsapi.MapCanvas;
import io.github.dailystruggle.mapsapi.model.ChartModel;

/**
 * Pure pixel-producing function: reads a model {@code M} and writes pixels to
 * a {@link MapCanvas}. Renderers shall be deterministic given the same model
 * and canvas state, and shall not perform any of the following
 * (REQ-RTP-MAP-002, extends REQ-RTP-F-008 / REQ-RTP-S-005):
 * <ul>
 *   <li>chunk I/O ({@code world.getChunkAt}, anvil reads)</li>
 *   <li>blocking on a {@link java.util.concurrent.CompletableFuture}
 *       ({@code .get()}, {@code .join()})</li>
 *   <li>importing {@code org.bukkit.*} or {@code net.minecraft.*}</li>
 * </ul>
 *
 * <p>The {@code ReqRtpMap002NoChunkIoTest} ArchUnit rule enforces these
 * constraints at the bytecode level over every class under
 * {@code io.github.dailystruggle.mapsapi.render.*}.
 *
 * @param <M> the {@link ChartModel} shape this renderer consumes
 */
@FunctionalInterface
public interface ChartRenderer<M extends ChartModel> {

    /**
     * Renders {@code model} onto {@code canvas}. Implementations shall not
     * call {@link MapCanvas#commit()} - the binding owns commit timing so it
     * can hop to the correct platform thread (REQ-RTP-MAP-003).
     */
    void render(MapCanvas canvas, M model);
}
