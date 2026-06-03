package io.github.dailystruggle.rtp.neoforge.maps;

import io.github.dailystruggle.mapsapi.Cancellation;
import io.github.dailystruggle.mapsapi.MapAllocationRequest;
import io.github.dailystruggle.mapsapi.MapBinding;
import io.github.dailystruggle.mapsapi.MapBindingLifecycle;
import io.github.dailystruggle.mapsapi.MapCanvas;
import io.github.dailystruggle.mapsapi.MapHandle;
import io.github.dailystruggle.mapsapi.model.ChartModel;
import io.github.dailystruggle.mapsapi.render.ChartRenderer;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.neoforge.player.NeoForgeMapSink;
import io.github.dailystruggle.rtp.neoforge.version.NeoForgeVersionAdapter;
import io.github.dailystruggle.rtp.neoforge.version.NeoForgeVersionAdapterRegistry;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * NeoForge concrete {@link MapBinding} (NeoForge analogue of
 * {@code FabricMapBinding}). Renders an RTP {@code ChartModel} into a 128x128
 * ARGB pixel buffer via {@link NeoForgeMapCanvas}, then hands the buffer to the
 * active {@link NeoForgeVersionAdapter}'s NM-free
 * {@link NeoForgeVersionAdapter#renderMapChart} seam, which performs the
 * version-specific {@code MapItemSavedData} allocation, ARGB to vanilla
 * {@code MapColor} translation, map-data packet dispatch, and {@code FILLED_MAP}
 * item delivery.
 *
 * <p>This class contains <b>no</b> {@code net.minecraft.*} reference (the viewer
 * is resolved through {@code RTP.serverAccessor} as a {@code NeoForgeRTPPlayer}
 * and every NM touch is delegated to that player and the carrier). Carriers that
 * do not implement {@link NeoForgeVersionAdapter#supportsMapCharts()} are
 * filtered out at install time (see {@code RTPNeoForgeMod}), so a binding
 * installed here is always backed by a working carrier.
 *
 * <p>Threading: {@code allocate} / {@code renderEphemeral} run on the caller's
 * (async) thread and only mutate in-memory buffers. {@code deliverTo} and the
 * live-refresh loop hand off to the {@code NeoForgeRTPPlayer} map sink, which
 * hops to the server tick thread via {@code RTP.scheduler} before the carrier
 * touches map / inventory / connection state (REQ-RTP-MAP-002 / S-005).
 *
 * @see NeoForgeMapCanvas
 * @see NeoForgeVersionAdapter#renderMapChart
 */
public final class NeoForgeMapBinding implements MapBinding, MapBindingLifecycle {

    private static final int PIXELS = MapCanvas.VANILLA_WIDTH * MapCanvas.VANILLA_HEIGHT;

    /** Live-refresh cadence in server ticks (~1 Hz, matching the Bukkit MapView tick rate). */
    private static final long LIVE_PERIOD_TICKS = 20L;

    private final ConcurrentHashMap<String, int[]> buffers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> lockedByChart = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Set<String>> chartIdsByViewer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> liveTasks = new ConcurrentHashMap<>();
    private final AtomicInteger synthId = new AtomicInteger(1);

    private volatile boolean disabled = false;

    @Override
    public MapHandle allocate(MapAllocationRequest request) {
        Objects.requireNonNull(request, "request");
        if (disabled) {
            throw new IllegalStateException(
                    "NeoForgeMapBinding.allocate: binding is disabled (server stopping);"
                            + " install a fresh NeoForgeMapBinding via MapDispatch.setMapBinding(...).");
        }
        String chartId = request.chartId();
        buffers.computeIfAbsent(chartId, k -> new int[PIXELS]);
        lockedByChart.put(chartId, request.locking() == MapAllocationRequest.Locking.LOCKED);
        if (request.viewer() != null) {
            chartIdsByViewer
                    .computeIfAbsent(request.viewer(), k -> ConcurrentHashMap.newKeySet())
                    .add(chartId);
        }
        return new MapHandle(chartId, request.viewer(), synthId.getAndIncrement());
    }

    @Override
    public <M extends ChartModel> void renderEphemeral(MapHandle handle,
                                                        ChartRenderer<M> renderer,
                                                        M model) {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(renderer, "renderer");
        Objects.requireNonNull(model, "model");
        int[] buf = buffers.get(handle.chartId());
        if (buf == null) {
            throw new IllegalStateException(
                    "NeoForgeMapBinding.renderEphemeral: no buffer for chartId=" + handle.chartId()
                            + " (allocate not called?)");
        }
        synchronized (buf) {
            renderer.render(new NeoForgeMapCanvas(buf), model);
        }
    }

    @Override
    public <M extends ChartModel> Cancellation bindLive(MapHandle handle,
                                                         ChartRenderer<M> renderer,
                                                         Supplier<M> modelSupplier) {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(renderer, "renderer");
        Objects.requireNonNull(modelSupplier, "modelSupplier");
        final int[] buf = buffers.get(handle.chartId());
        if (buf == null) {
            throw new IllegalStateException(
                    "NeoForgeMapBinding.bindLive: no buffer for chartId=" + handle.chartId()
                            + " (allocate not called?)");
        }
        final String chartId = handle.chartId();
        final UUID viewer = handle.viewer();
        final boolean locked = lockedByChart.getOrDefault(chartId, Boolean.TRUE);

        // Paint the first frame synchronously so deliverTo has pixels to ship.
        renderFrame(buf, renderer, modelSupplier);

        // Cancel a stale loop on the same chart before installing a new one.
        cancelLiveTask(chartId);

        Object task = RTP.scheduler.runTaskTimerAsynchronously(() -> {
            try {
                if (disabled) return;
                renderFrame(buf, renderer, modelSupplier);
                dispatch(chartId, viewer, buf, locked, /*deliverItem=*/false);
            } catch (RuntimeException e) {
                RTP.log(Level.WARNING,
                        "[RTP][NeoForge maps] live refresh failed for chartId=" + chartId
                                + ": " + e.getMessage());
            }
        }, LIVE_PERIOD_TICKS, LIVE_PERIOD_TICKS);
        liveTasks.put(chartId, task);

        return new Cancellation() {
            private volatile boolean cancelled = false;
            @Override public void cancel() {
                cancelled = true;
                cancelLiveTask(chartId);
            }
            @Override public boolean cancelled() { return cancelled; }
        };
    }

    @Override
    public void deliverTo(MapHandle handle, UUID viewer) {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(viewer, "viewer");
        int[] buf = buffers.get(handle.chartId());
        if (buf == null) {
            throw new IllegalStateException(
                    "NeoForgeMapBinding.deliverTo: no buffer for chartId=" + handle.chartId());
        }
        boolean locked = lockedByChart.getOrDefault(handle.chartId(), Boolean.TRUE);
        boolean ok = dispatch(handle.chartId(), viewer, buf, locked, /*deliverItem=*/true);
        if (!ok) {
            // S-004: surface a hard failure so MapDispatch reports mapUnavailable.
            throw new IllegalStateException(
                    "NeoForgeMapBinding.deliverTo: active version adapter does not support map charts"
                            + " (chartId=" + handle.chartId() + ")");
        }
    }

    // --- internals ----------------------------------------------------------

    private static <M extends ChartModel> void renderFrame(int[] buf,
                                                           ChartRenderer<M> renderer,
                                                           Supplier<M> supplier) {
        M model = supplier.get();
        if (model == null) return; // skip this frame
        synchronized (buf) {
            renderer.render(new NeoForgeMapCanvas(buf), model);
        }
    }

    /**
     * Hand a snapshot of {@code buf} to the active carrier. Returns whether the
     * carrier accepted the request ({@code supportsMapCharts}); the carrier
     * itself performs the server-thread hop and logs any NM-side failure.
     */
    private boolean dispatch(String chartId, UUID viewer, int[] buf,
                             boolean locked, boolean deliverItem) {
        NeoForgeMapSink player = resolveNeoForgePlayer(viewer);
        if (player == null) return false;
        int[] snapshot;
        synchronized (buf) {
            snapshot = buf.clone();
        }
        return player.renderMapChart(chartId, snapshot, locked, deliverItem);
    }

    /**
     * Resolve the viewer to a {@link NeoForgeMapSink} through
     * {@code RTP.serverAccessor}. Returns {@code null} if the accessor is
     * unbound, the viewer is offline, or the active carrier does not support
     * map charts.
     */
    private static NeoForgeMapSink resolveNeoForgePlayer(UUID viewer) {
        NeoForgeVersionAdapter adapter = NeoForgeVersionAdapterRegistry.peek();
        if (adapter == null || !adapter.supportsMapCharts()) return null;
        RTPPlayer rtpPlayer = RTP.serverAccessor == null ? null
                : RTP.serverAccessor.getPlayer(viewer);
        return (rtpPlayer instanceof NeoForgeMapSink sink) ? sink : null;
    }

    private void cancelLiveTask(String chartId) {
        Object task = liveTasks.remove(chartId);
        if (task != null) {
            try {
                RTP.scheduler.cancelTask(task);
            } catch (Throwable ignored) {
                // best-effort: a scheduler that already dropped the task is fine
            }
        }
    }

    @Override
    public void onPlayerQuit(UUID viewer) {
        if (viewer == null) return;
        Set<String> chartIds = chartIdsByViewer.remove(viewer);
        if (chartIds == null) return;
        NeoForgeVersionAdapter adapter = NeoForgeVersionAdapterRegistry.peek();
        for (String chartId : chartIds) {
            cancelLiveTask(chartId);
            buffers.remove(chartId);
            lockedByChart.remove(chartId);
            if (adapter != null) {
                try {
                    adapter.releaseMapChart(chartId);
                } catch (Throwable ignored) {
                    // release is best-effort cache cleanup; never throw from quit
                }
            }
        }
    }

    @Override
    public void onDisable() {
        disabled = true;
        for (String chartId : liveTasks.keySet()) {
            cancelLiveTask(chartId);
        }
        NeoForgeVersionAdapter adapter = NeoForgeVersionAdapterRegistry.peek();
        if (adapter != null) {
            for (String chartId : buffers.keySet()) {
                try {
                    adapter.releaseMapChart(chartId);
                } catch (Throwable ignored) {
                    // best-effort
                }
            }
        }
        buffers.clear();
        lockedByChart.clear();
        chartIdsByViewer.clear();
        liveTasks.clear();
    }
}
