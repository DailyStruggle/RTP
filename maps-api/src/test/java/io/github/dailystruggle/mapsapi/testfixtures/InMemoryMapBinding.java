package io.github.dailystruggle.mapsapi.testfixtures;

import io.github.dailystruggle.mapsapi.Cancellation;
import io.github.dailystruggle.mapsapi.MapAllocationRequest;
import io.github.dailystruggle.mapsapi.MapBinding;
import io.github.dailystruggle.mapsapi.MapCanvas;
import io.github.dailystruggle.mapsapi.MapHandle;
import io.github.dailystruggle.mapsapi.model.ChartModel;
import io.github.dailystruggle.mapsapi.render.ChartRenderer;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Test-only {@link MapBinding} backed by an in-memory {@code byte[128*128]}
 * pixel buffer per allocated handle. Used by Stage-1 renderer tests to assert
 * on rendered pixels without instantiating a Bukkit / Fabric runtime.
 *
 * <p>No {@code org.bukkit.*} or {@code net.minecraft.*} imports. Refresh
 * dispatch is synchronous on the calling thread: {@link #bindLive} runs the
 * supplied renderer once and registers the supplier for manual {@link #tick()}
 * invocation so tests stay deterministic.
 */
public final class InMemoryMapBinding implements MapBinding {

    private final AtomicInteger nextMapId = new AtomicInteger(1);
    private final Map<Integer, InMemoryCanvas> canvases = new HashMap<>();
    private final Map<Integer, LiveSubscription<?>> live = new HashMap<>();

    @Override
    public MapHandle allocate(MapAllocationRequest request) {
        Objects.requireNonNull(request, "request");
        int id = nextMapId.getAndIncrement();
        canvases.put(id, new InMemoryCanvas());
        return new MapHandle(request.chartId(), request.viewer(), id);
    }

    @Override
    public <M extends ChartModel> void renderEphemeral(MapHandle handle,
                                                       ChartRenderer<M> renderer,
                                                       M model) {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(renderer, "renderer");
        Objects.requireNonNull(model, "model");
        InMemoryCanvas canvas = requireCanvas(handle);
        renderer.render(canvas, model);
        canvas.commit();
    }

    @Override
    public <M extends ChartModel> Cancellation bindLive(MapHandle handle,
                                                        ChartRenderer<M> renderer,
                                                        Supplier<M> modelSupplier) {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(renderer, "renderer");
        Objects.requireNonNull(modelSupplier, "modelSupplier");
        InMemoryCanvas canvas = requireCanvas(handle);
        LiveSubscription<M> sub = new LiveSubscription<>(canvas, renderer, modelSupplier);
        live.put(handle.mapId(), sub);
        // Initial frame:
        sub.tick();
        return sub;
    }

    /** Manually advances every live subscription by one frame. Deterministic test driver. */
    public void tick() {
        for (LiveSubscription<?> sub : live.values()) {
            if (!sub.cancelled()) {
                sub.tick();
            }
        }
    }

    /** Snapshot of the pixel buffer for {@code handle}; row-major {@code [128][128]}. */
    public byte[][] snapshot(MapHandle handle) {
        Objects.requireNonNull(handle, "handle");
        return requireCanvas(handle).snapshot();
    }

    /** Number of times {@link MapCanvas#commit()} has been called for {@code handle}. */
    public int commitCount(MapHandle handle) {
        Objects.requireNonNull(handle, "handle");
        return requireCanvas(handle).commitCount();
    }

    private InMemoryCanvas requireCanvas(MapHandle handle) {
        InMemoryCanvas c = canvases.get(handle.mapId());
        if (c == null) {
            throw new IllegalStateException("Unknown handle: " + handle);
        }
        return c;
    }

    /** In-memory backing canvas. */
    private static final class InMemoryCanvas implements MapCanvas {
        private final byte[] pixels = new byte[VANILLA_WIDTH * VANILLA_HEIGHT];
        private int commits = 0;

        @Override public int width() { return VANILLA_WIDTH; }
        @Override public int height() { return VANILLA_HEIGHT; }

        @Override
        public void setPixel(int x, int y, byte paletteIndex) {
            if (x < 0 || x >= VANILLA_WIDTH || y < 0 || y >= VANILLA_HEIGHT) return;
            pixels[y * VANILLA_WIDTH + x] = paletteIndex;
        }

        @Override
        public void fillRect(int x0, int y0, int x1, int y1, byte paletteIndex) {
            int lx = Math.max(0, Math.min(x0, x1));
            int hx = Math.min(VANILLA_WIDTH - 1, Math.max(x0, x1));
            int ly = Math.max(0, Math.min(y0, y1));
            int hy = Math.min(VANILLA_HEIGHT - 1, Math.max(y0, y1));
            for (int y = ly; y <= hy; y++) {
                for (int x = lx; x <= hx; x++) {
                    pixels[y * VANILLA_WIDTH + x] = paletteIndex;
                }
            }
        }

        @Override
        public void drawText(int x, int y, String text, byte paletteIndex) {
            // Minimal stub: writes one pixel per character along x for tests
            // that assert on label presence without binding to glyph shape.
            if (text == null) return;
            for (int i = 0; i < text.length(); i++) {
                setPixel(x + i, y, paletteIndex);
            }
        }

        @Override
        public void clear() {
            java.util.Arrays.fill(pixels, (byte) 0);
        }

        @Override
        public void commit() {
            commits++;
        }

        byte[][] snapshot() {
            byte[][] out = new byte[VANILLA_HEIGHT][VANILLA_WIDTH];
            for (int y = 0; y < VANILLA_HEIGHT; y++) {
                System.arraycopy(pixels, y * VANILLA_WIDTH, out[y], 0, VANILLA_WIDTH);
            }
            return out;
        }

        int commitCount() { return commits; }
    }

    /** Live-subscription handle for {@link InMemoryMapBinding#bindLive}. */
    private static final class LiveSubscription<M extends ChartModel> implements Cancellation {
        private final InMemoryCanvas canvas;
        private final ChartRenderer<M> renderer;
        private final Supplier<M> supplier;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        LiveSubscription(InMemoryCanvas canvas, ChartRenderer<M> renderer, Supplier<M> supplier) {
            this.canvas = canvas;
            this.renderer = renderer;
            this.supplier = supplier;
        }

        void tick() {
            if (cancelled.get()) return;
            M model = supplier.get();
            if (model == null) return;
            renderer.render(canvas, model);
            canvas.commit();
        }

        @Override public void cancel() { cancelled.set(true); }
        @Override public boolean cancelled() { return cancelled.get(); }
    }
}
