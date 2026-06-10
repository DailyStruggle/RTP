package io.github.dailystruggle.mapsapi.noop;

import io.github.dailystruggle.mapsapi.Cancellation;
import io.github.dailystruggle.mapsapi.MapAllocationRequest;
import io.github.dailystruggle.mapsapi.MapBinding;
import io.github.dailystruggle.mapsapi.MapHandle;
import io.github.dailystruggle.mapsapi.model.ChartModel;
import io.github.dailystruggle.mapsapi.render.ChartRenderer;

import java.util.function.Supplier;

/**
 * Default {@link MapBinding} installed before {@code RTPHooks} wires a real
 * platform binding. Every entry-point throws {@link IllegalStateException}
 * with the same documented prefix per REQ-RTP-MAP-001 (extends REQ-RTP-S-006).
 *
 * <p>This is the fallback binding only. Per REQ-RTP-MAP-004 (amended
 * 2026-06-04) the Lite assembly ships the same platform-backed
 * {@code MapBinding} as the full assembly: the Lite jar carries the full
 * {@code mapsapi/**} tree, and the Lite bootstraps install the real concrete
 * bindings ({@code BukkitMapBinding} on Bukkit/Paper/Folia, {@code FabricMapBinding}
 * on Fabric, {@code NeoForgeMapBinding} on NeoForge). {@code NoopMapBinding}
 * is registered only when no platform binding is available on the active
 * runtime, in which case {@code /rtp} map commands surface the configurable
 * {@code mapBindingMissing} message (REQ-RTP-F-013 / S-004) rather than
 * silently no-opping.
 */
public final class NoopMapBinding implements MapBinding {

    /** Prefix shared by every thrown message; asserted by {@code ReqRtpMap001RequireByContractTest}. */
    public static final String NOT_LOADED_MESSAGE_PREFIX =
            "RTP core not loaded — register a MapBinding via RTPHooks before use.";

    @Override
    public MapHandle allocate(MapAllocationRequest request) {
        throw new IllegalStateException(NOT_LOADED_MESSAGE_PREFIX + " (allocate)");
    }

    @Override
    public <M extends ChartModel> void renderEphemeral(MapHandle handle,
                                                       ChartRenderer<M> renderer,
                                                       M model) {
        throw new IllegalStateException(NOT_LOADED_MESSAGE_PREFIX + " (renderEphemeral)");
    }

    @Override
    public <M extends ChartModel> Cancellation bindLive(MapHandle handle,
                                                        ChartRenderer<M> renderer,
                                                        Supplier<M> modelSupplier) {
        throw new IllegalStateException(NOT_LOADED_MESSAGE_PREFIX + " (bindLive)");
    }
}
