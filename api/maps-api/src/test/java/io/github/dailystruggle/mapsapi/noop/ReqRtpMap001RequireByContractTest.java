package io.github.dailystruggle.mapsapi.noop;

import io.github.dailystruggle.mapsapi.Cancellation;
import io.github.dailystruggle.mapsapi.MapAllocationRequest;
import io.github.dailystruggle.mapsapi.MapHandle;
import io.github.dailystruggle.mapsapi.model.ChartModel;
import io.github.dailystruggle.mapsapi.model.Heatmap2D;
import io.github.dailystruggle.mapsapi.render.ChartRenderer;
import io.github.dailystruggle.mapsapi.render.HeatmapRenderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-RTP-MAP-001 (extends REQ-RTP-S-006): every {@link NoopMapBinding}
 * entry-point shall throw {@link IllegalStateException} with the documented
 * prefix — never return {@code null} and never silently no-op.
 */
@DisplayName("REQ-RTP-MAP-001 — NoopMapBinding require-by-contract")
class ReqRtpMap001RequireByContractTest {

    private final NoopMapBinding noop = new NoopMapBinding();

    @Test
    @DisplayName("allocate() throws IllegalStateException with documented prefix")
    void allocateThrows() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> noop.allocate(new MapAllocationRequest(
                        "chart-x", UUID.randomUUID(),
                        MapAllocationRequest.Locking.LOCKED)));
        assertTrue(ex.getMessage().startsWith(NoopMapBinding.NOT_LOADED_MESSAGE_PREFIX),
                "message should start with documented prefix, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("renderEphemeral() throws IllegalStateException with documented prefix")
    void renderEphemeralThrows() {
        MapHandle handle = new MapHandle("chart-x", null, 1);
        ChartRenderer<Heatmap2D> renderer = new HeatmapRenderer();
        Heatmap2D model = new Heatmap2D(2, 2, new double[]{0.0, 1.0, 2.0, 3.0}, 0.0, 3.0);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> noop.renderEphemeral(handle, renderer, model));
        assertTrue(ex.getMessage().startsWith(NoopMapBinding.NOT_LOADED_MESSAGE_PREFIX),
                "message should start with documented prefix, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("bindLive() throws IllegalStateException with documented prefix")
    void bindLiveThrows() {
        MapHandle handle = new MapHandle("chart-x", null, 1);
        ChartRenderer<Heatmap2D> renderer = new HeatmapRenderer();
        Supplier<Heatmap2D> supplier = () -> new Heatmap2D(2, 2, new double[]{0, 1, 2, 3}, 0, 3);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> {
                    Cancellation c = noop.bindLive(handle, renderer, supplier);
                    // Unreachable; satisfy unused-var.
                    if (c != null) c.cancel();
                });
        assertTrue(ex.getMessage().startsWith(NoopMapBinding.NOT_LOADED_MESSAGE_PREFIX),
                "message should start with documented prefix, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("every entry-point names itself in the thrown message")
    void messageNamesEntryPoint() {
        IllegalStateException a = assertThrows(IllegalStateException.class,
                () -> noop.allocate(new MapAllocationRequest("c", null,
                        MapAllocationRequest.Locking.EDITABLE)));
        assertTrue(a.getMessage().contains("allocate"));

        MapHandle h = new MapHandle("c", null, 1);
        ChartRenderer<ChartModel> noopRenderer = (canvas, model) -> { /* unused */ };
        IllegalStateException b = assertThrows(IllegalStateException.class,
                () -> noop.renderEphemeral(h, noopRenderer, (ChartModel) null));
        assertTrue(b.getMessage().contains("renderEphemeral"));

        IllegalStateException c = assertThrows(IllegalStateException.class,
                () -> noop.bindLive(h, noopRenderer, () -> null));
        assertTrue(c.getMessage().contains("bindLive"));
    }
}
