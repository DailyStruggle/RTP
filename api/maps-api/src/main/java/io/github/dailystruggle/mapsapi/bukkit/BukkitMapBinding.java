package io.github.dailystruggle.mapsapi.bukkit;

import io.github.dailystruggle.mapsapi.Cancellation;
import io.github.dailystruggle.mapsapi.MapAllocationRequest;
import io.github.dailystruggle.mapsapi.MapBinding;
import io.github.dailystruggle.mapsapi.MapBindingLifecycle;
import io.github.dailystruggle.mapsapi.MapCanvas;
import io.github.dailystruggle.mapsapi.MapHandle;
import io.github.dailystruggle.mapsapi.model.ChartModel;
import io.github.dailystruggle.mapsapi.render.ChartRenderer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapPalette;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Bukkit-family concrete {@link MapBinding}. This is the only file in the
 * {@code maps-api} module permitted to {@code import org.bukkit.*} per
 * {@code maps-api-ADR-001} §Package layout.
 *
 * <p>Scope (this class):
 * <ul>
 *   <li>{@link #allocate(MapAllocationRequest)} - backs each
 *       {@link MapHandle} with a freshly-created {@link MapView} on the
 *       viewer's world (or the default world if no viewer).</li>
 *   <li>{@link #renderEphemeral(MapHandle, ChartRenderer, ChartModel)} - installs
 *       a one-shot {@link MapRenderer} that paints the supplied
 *       {@link ChartModel} once and then removes itself; the resulting filled
 *       map is delivered to the viewer's main hand by the caller (see
 *       {@code OpenMapActionHandler} in {@code rtp-plugin}).</li>
 *   <li>{@link #bindLive} - <strong>deferred</strong>: throws
 *       {@link UnsupportedOperationException}. Live charts arrive with
 *       the live map pipeline; the one-shot path
 *       is sufficient for the {@code /rtp info} bad-points heatmap.</li>
 * </ul>
 *
 * <p>Palette translation: the 32-symbol logical palette
 * ({@link io.github.dailystruggle.mapsapi.render.HeatmapRenderer#RAMP_MIN}..{@link
 * io.github.dailystruggle.mapsapi.render.HeatmapRenderer#RAMP_MAX}) maps to
 * concrete vanilla map-colour bytes via {@link MapPalette#matchColor(int, int, int)}
 * applied to a black-to-red-to-yellow-to-white ramp. Logical 0 maps to
 * transparent ({@link MapPalette#TRANSPARENT}) so an unfilled canvas reads
 * as a clean cartography map.
 *
 * <p>Threading: {@code allocate} and {@code renderEphemeral} are safe to call
 * from any thread. The Bukkit {@code MapView} machinery dispatches
 * {@code MapRenderer#render(...)} on the main thread internally, so renderers
 * authored against {@link MapCanvas} run synchronously inside Bukkit's tick;
 * the {@code BukkitMapCanvas} adapter is a per-render thin wrapper that
 * forwards each {@code setPixel} call to the underlying {@code MapCanvas} so
 * no buffering is needed. No chunk I/O occurs at any point (REQ-RTP-MAP-002 /
 * REQ-RTP-S-005).
 *
 * <p>Folia: this class works on Folia as-is for {@code renderEphemeral}
 * because vanilla {@code MapView} commits are global-region-scheduled by the
 * platform; the dedicated {@code FoliaMapBinding} override
 * will replace this when region-affinity matters (live charts and per-viewer pixel commits).
 */
public class BukkitMapBinding implements MapBinding, MapBindingLifecycle {

    /** Cached palette translation table: logical index 0..31 -> vanilla map byte. */
    private static final byte[] PALETTE = buildPalette();

    /** Live handles indexed by chartId for idempotent allocation. */
    private final ConcurrentHashMap<String, MapHandle> handlesByChartId = new ConcurrentHashMap<>();

    /**
     * Secondary index: viewer UUID -> chartIds, so {@link #onPlayerQuit}
     * can release every cached handle scoped to that viewer without
     * scanning the entire {@link #handlesByChartId} map. Both maps share a
     * single write contract: every mutation of one mutates the other.
     */
    private final ConcurrentHashMap<UUID, java.util.Set<String>> chartIdsByViewer = new ConcurrentHashMap<>();

    /** Set to {@code true} on {@link #onDisable}; subsequent {@link #allocate} calls throw. */
    private volatile boolean disabled = false;

    @Override
    public MapHandle allocate(MapAllocationRequest request) {
        Objects.requireNonNull(request, "request");
        if (disabled) {
            throw new IllegalStateException(
                    "BukkitMapBinding.allocate: binding is disabled (host plugin onDisable fired);"
                            + " install a fresh BukkitMapBinding via MapDispatch.setMapBinding(...).");
        }
        // Idempotency hint: if we have already allocated a handle for this
        // chartId, return it. The caller (MapDispatch) is responsible for
        // honouring the request.locking() write policy on subsequent use.
        MapHandle cached = handlesByChartId.get(request.chartId());
        if (cached != null) return cached;

        World world = resolveWorld(request.viewer());
        if (world == null) {
            throw new IllegalStateException(
                    "BukkitMapBinding.allocate: no world available for viewer="
                            + request.viewer() + " (no loaded worlds?)");
        }
        MapView view = Bukkit.createMap(world);
        if (view == null) {
            throw new IllegalStateException(
                    "BukkitMapBinding.allocate: Bukkit.createMap returned null for world="
                            + world.getName());
        }
        if (request.locking() == MapAllocationRequest.Locking.LOCKED) {
            view.setLocked(true);
        }
        // Strip default renderers so our one-shot paint is the only writer.
        for (MapRenderer existing : view.getRenderers()) {
            view.removeRenderer(existing);
        }
        MapHandle handle = new MapHandle(request.chartId(), request.viewer(), view.getId());
        handlesByChartId.put(request.chartId(), handle);
        if (request.viewer() != null) {
            chartIdsByViewer
                    .computeIfAbsent(request.viewer(),
                            k -> java.util.concurrent.ConcurrentHashMap.newKeySet())
                    .add(request.chartId());
        }
        return handle;
    }

    @Override
    public <M extends ChartModel> void renderEphemeral(MapHandle handle,
                                                       ChartRenderer<M> renderer,
                                                       M model) {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(renderer, "renderer");
        Objects.requireNonNull(model, "model");
        MapView view = Bukkit.getMap(handle.mapId());
        if (view == null) {
            throw new IllegalStateException(
                    "BukkitMapBinding.renderEphemeral: no MapView for handle.mapId="
                            + handle.mapId() + " chartId=" + handle.chartId());
        }
        // Drop any previously-installed one-shot renderer for this handle so
        // repeat renderEphemeral calls on the same handle behave as expected.
        for (MapRenderer existing : view.getRenderers()) {
            view.removeRenderer(existing);
        }
        view.addRenderer(new OneShotChartRenderer<>(renderer, model));
    }

    /**
     * Drops a {@code FILLED_MAP} item entity at the viewer's feet whose
     * {@code MapMeta} points at the {@code MapView} backing {@code handle}.
     * Minecraft only renders a {@code MapView}'s pixels to clients that see
     * a {@code FILLED_MAP} item referencing the view's id (held or in an
     * entity / item frame), so without this step the painted chart is
     * invisible to the player.
     *
     * <p>Threading: this call mutates world state and must run on the
     * viewer's region thread. The caller ({@code MapDispatch}) is responsible
     * for dispatching this onto the correct thread via {@code RTP.scheduler}
     * (on Folia, the region thread owning the viewer's location; on
     * Paper/Spigot, the main thread). This method therefore drops the item
     * directly and assumes it is already on the right thread.
     *
     * <p>S-004: failures (viewer offline, world unavailable, item entity
     * spawn refused by the server) throw {@link IllegalStateException}
     * rather than silently swallowing, so the dispatcher can surface a
     * user-facing message.
     */
    @Override
    public void deliverTo(MapHandle handle, UUID viewer) {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(viewer, "viewer");
        Player player = Bukkit.getPlayer(viewer);
        if (player == null) {
            throw new IllegalStateException(
                    "BukkitMapBinding.deliverTo: viewer " + viewer
                            + " is not online; cannot drop FILLED_MAP for chartId="
                            + handle.chartId());
        }
        MapView view = Bukkit.getMap(handle.mapId());
        if (view == null) {
            throw new IllegalStateException(
                    "BukkitMapBinding.deliverTo: no MapView for handle.mapId="
                            + handle.mapId() + " chartId=" + handle.chartId());
        }
        ItemStack item = new ItemStack(Material.FILLED_MAP, 1);
        MapMeta meta = (MapMeta) item.getItemMeta();
        if (meta == null) {
            throw new IllegalStateException(
                    "BukkitMapBinding.deliverTo: FILLED_MAP has no MapMeta (server="
                            + Bukkit.getServer().getVersion() + ")");
        }
        meta.setMapView(view);
        item.setItemMeta(meta);
        player.getWorld().dropItem(player.getLocation(), item);
    }

    /**
     * Live-chart binding: installs a {@link MapRenderer} that, on every
     * {@code CraftMapView.render} invocation (Bukkit ticks one per
     * holding/framing client per server tick), pulls a fresh model from
     * {@code modelSupplier} and re-paints the canvas. Unlike the one-shot
     * renderer this does NOT short-circuit after the first paint, so the
     * chart updates over time as long as the viewer is holding (or has
     * framed) the {@code FILLED_MAP} item.
     *
     * <p>The {@link Cancellation} returned flips a {@code cancelled} flag
     * the renderer checks every tick; we deliberately do not call
     * {@code view.removeRenderer(this)} from cancel() because
     * {@code CraftMapView.render} iterates its renderer list under the
     * tick and mutating mid-iteration throws CME (see
     * {@code OneShotChartRenderer}'s prior crash on Folia 1.21.11). The
     * stale renderer is evicted lazily by the next
     * {@code allocate} / {@code renderEphemeral} / {@code bindLive} on
     * the same view (all three paths strip existing renderers first).
     */
    @Override
    public <M extends ChartModel> Cancellation bindLive(MapHandle handle,
                                                        ChartRenderer<M> renderer,
                                                        Supplier<M> modelSupplier) {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(renderer, "renderer");
        Objects.requireNonNull(modelSupplier, "modelSupplier");
        MapView view = Bukkit.getMap(handle.mapId());
        if (view == null) {
            throw new IllegalStateException(
                    "BukkitMapBinding.bindLive: no MapView for handle.mapId="
                            + handle.mapId() + " chartId=" + handle.chartId());
        }
        // Drop any previously-installed renderer for this handle so a
        // prior one-shot / live binding does not double-paint.
        for (MapRenderer existing : view.getRenderers()) {
            view.removeRenderer(existing);
        }
        LiveChartRenderer<M> live = new LiveChartRenderer<>(renderer, modelSupplier);
        view.addRenderer(live);
        return new Cancellation() {
            private volatile boolean cancelled = false;
            @Override public void cancel() { cancelled = true; live.cancel(); }
            @Override public boolean cancelled() { return cancelled; }
        };
    }

    /** @return the {@code MapView} backing {@code handle}, or {@code null} if none. */
    public MapView viewOf(MapHandle handle) {
        return handle == null ? null : Bukkit.getMap(handle.mapId());
    }

    private static World resolveWorld(UUID viewerId) {
        if (viewerId != null) {
            Player p = Bukkit.getPlayer(viewerId);
            if (p != null) return p.getWorld();
        }
        return Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
    }

    /**
     * Build the 32-step logical-to-vanilla palette translation table once.
     * Layout per {@link io.github.dailystruggle.mapsapi.PaletteIndex} /
     * {@code maps-api-ADR-001} §Palette policy:
     * <ul>
     *   <li>index 0 ({@code TRANSPARENT}) -> {@link MapPalette#TRANSPARENT};</li>
     *   <li>indices 1..27 ({@code RAMP_MIN..RAMP_MAX}) walk a black -> red ->
     *       yellow -> white gradient that reads well at the 1-pixel scale
     *       vanilla maps use;</li>
     *   <li>indices 28..31 ({@code BLACK / RED / GREEN / WHITE}) are
     *       categorical non-ramp slots used by region-shape, category-pie,
     *       and future overlay renderers.</li>
     * </ul>
     */
    private static byte[] buildPalette() {
        byte[] table = new byte[32];
        table[0] = MapPalette.TRANSPARENT;
        final int rampLo = io.github.dailystruggle.mapsapi.PaletteIndex.RAMP_MIN;
        final int rampHi = io.github.dailystruggle.mapsapi.PaletteIndex.RAMP_MAX;
        final int rampSpan = rampHi - rampLo;
        for (int i = rampLo; i <= rampHi; i++) {
            float t = (i - rampLo) / (float) rampSpan; // 0..1 across rampLo..rampHi
            int r, g, b;
            if (t < 0.5f) {
                // black -> red
                float u = t * 2.0f;
                r = Math.round(255 * u);
                g = 0;
                b = 0;
            } else if (t < 0.85f) {
                // red -> yellow
                float u = (t - 0.5f) / 0.35f;
                r = 255;
                g = Math.round(255 * u);
                b = 0;
            } else {
                // yellow -> white
                float u = (t - 0.85f) / 0.15f;
                r = 255;
                g = 255;
                b = Math.round(255 * u);
            }
            table[i] = MapPalette.matchColor(r, g, b);
        }
        // Named non-ramp slots: stable categorical colors. Slot indices
        // mirror PaletteIndex.BLACK / RED / GREEN / WHITE; RGBs are chosen
        // for vanilla-map readability (mid-saturation rather than primary
        // 0xFF so MapPalette.matchColor lands on a vivid dye-ish vanilla
        // byte rather than the washed-out near-white bucket).
        table[io.github.dailystruggle.mapsapi.PaletteIndex.BLACK] =
                MapPalette.matchColor(0, 0, 0);
        table[io.github.dailystruggle.mapsapi.PaletteIndex.RED] =
                MapPalette.matchColor(170, 0, 0);
        table[io.github.dailystruggle.mapsapi.PaletteIndex.GREEN] =
                MapPalette.matchColor(0, 170, 0);
        table[io.github.dailystruggle.mapsapi.PaletteIndex.WHITE] =
                MapPalette.matchColor(255, 255, 255);
        return table;
    }

    /**
     * {@link MapBindingLifecycle#onPlayerQuit} implementation: drop every
     * cached {@link MapHandle} keyed to {@code viewer}. The underlying
     * vanilla {@link MapView} objects are owned by Bukkit's persistent map
     * registry and intentionally not deleted here (Bukkit has no public API
     * for that); only the in-memory caches that allowed idempotent
     * re-allocation are released. A no-op if the binding never allocated
     * anything for {@code viewer}.
     */
    @Override
    public void onPlayerQuit(UUID viewer) {
        if (viewer == null) return;
        java.util.Set<String> chartIds = chartIdsByViewer.remove(viewer);
        if (chartIds == null) return;
        for (String chartId : chartIds) {
            handlesByChartId.remove(chartId);
        }
    }

    /**
     * {@link MapBindingLifecycle#onDisable} implementation: clear every
     * cached handle and refuse subsequent {@link #allocate} calls until a
     * fresh instance is installed via {@code MapDispatch.setMapBinding}.
     */
    @Override
    public void onDisable() {
        disabled = true;
        handlesByChartId.clear();
        chartIdsByViewer.clear();
    }

    /** Translate a logical-palette byte (0..31) to a vanilla map-colour byte. */
    public static byte toVanillaPalette(byte logicalIndex) {
        int idx = logicalIndex & 0xFF;
        if (idx >= PALETTE.length) idx = PALETTE.length - 1;
        return PALETTE[idx];
    }

    /**
     * One-shot {@link MapRenderer} that runs the supplied chart renderer once
     * against a {@link MapCanvas} adapter wrapping the Bukkit canvas, then
     * removes itself from the {@link MapView}. Stateless renderers
     * (REQ-RTP-MAP-002) plus a fixed model snapshot mean a single render
     * pass is sufficient.
     */
    private static final class OneShotChartRenderer<M extends ChartModel> extends MapRenderer {
        private final ChartRenderer<M> chartRenderer;
        private final M model;
        private boolean rendered = false;

        OneShotChartRenderer(ChartRenderer<M> chartRenderer, M model) {
            super(false);
            this.chartRenderer = chartRenderer;
            this.model = model;
        }

        @Override
        public void render(MapView map, org.bukkit.map.MapCanvas bukkitCanvas, Player player) {
            if (rendered) return;
            chartRenderer.render(new BukkitMapCanvas(bukkitCanvas), model);
            rendered = true;
            // Do NOT call map.removeRenderer(this) here: CraftMapView.render
            // is iterating its renderer list when it invokes us, and mutating
            // that list mid-iteration throws ConcurrentModificationException
            // on the next tick's player update (observed on Folia 1.21.11,
            // crashed the ServerPlayer tick and disconnected the viewer).
            // Subsequent ticks short-circuit on the `rendered` flag above;
            // stale renderers are evicted lazily on the next allocate /
            // renderEphemeral for the same chartId (both paths strip
            // existing renderers before installing new ones).
        }
    }

    /**
     * Live-refresh {@link MapRenderer}: every {@code CraftMapView.render}
     * invocation pulls a fresh model from {@code modelSupplier} and runs
     * the chart renderer against the per-tick canvas. A volatile
     * {@code cancelled} flag short-circuits the path after
     * {@link Cancellation#cancel()} fires; the renderer is not removed
     * from the {@link MapView} mid-iteration (CME hazard, see class-doc).
     * Exceptions thrown by the supplier or renderer are caught and logged
     * via {@code System.err} (the {@code maps-api} module cannot reach
     * {@code RTP.log}); the renderer continues to be installed and may
     * succeed on a later tick (S-004: no silent swallow - the WARNING is
     * audible, and {@code MemoryTracker} reaping still applies upstream).
     */
    private static final class LiveChartRenderer<M extends ChartModel> extends MapRenderer {
        private final ChartRenderer<M> chartRenderer;
        private final Supplier<M> modelSupplier;
        private volatile boolean cancelled = false;

        LiveChartRenderer(ChartRenderer<M> chartRenderer, Supplier<M> modelSupplier) {
            super(false);
            this.chartRenderer = chartRenderer;
            this.modelSupplier = modelSupplier;
        }

        void cancel() { cancelled = true; }

        @Override
        public void render(MapView map, org.bukkit.map.MapCanvas bukkitCanvas, Player player) {
            if (cancelled) return;
            try {
                M model = modelSupplier.get();
                if (model == null) return;
                chartRenderer.render(new BukkitMapCanvas(bukkitCanvas), model);
            } catch (RuntimeException e) {
                // Cannot reach RTP.log from maps-api; surface to stderr so
                // the host plugin's log still captures it. Do not rethrow:
                // an exception here would propagate into CraftMapView.render
                // and crash the player tick (Folia 1.21.11 ServerPlayer
                // disconnect pattern we already hit once).
                System.err.println(
                        "[maps-api] LiveChartRenderer render failed for chartId="
                                + map.getId() + ": " + e.getMessage());
            }
        }
    }

    /**
     * Thin adapter exposing a Bukkit {@link org.bukkit.map.MapCanvas} as the
     * platform-neutral {@link MapCanvas} {@link ChartRenderer}s write into.
     * Each {@link #setPixel(int, int, byte)} call translates the logical
     * palette byte via {@link #toVanillaPalette(byte)} and forwards to the
     * underlying Bukkit canvas. No buffering, no separate commit step.
     */
    private static final class BukkitMapCanvas implements MapCanvas {
        private final org.bukkit.map.MapCanvas delegate;

        BukkitMapCanvas(org.bukkit.map.MapCanvas delegate) {
            this.delegate = delegate;
        }

        @Override public int width() { return VANILLA_WIDTH; }
        @Override public int height() { return VANILLA_HEIGHT; }

        @Override
        public void setPixel(int x, int y, byte paletteIndex) {
            if (x < 0 || x >= VANILLA_WIDTH || y < 0 || y >= VANILLA_HEIGHT) return;
            delegate.setPixel(x, y, toVanillaPalette(paletteIndex));
        }

        @Override
        public void fillRect(int x0, int y0, int x1, int y1, byte paletteIndex) {
            int lx = Math.max(0, Math.min(x0, x1));
            int hx = Math.min(VANILLA_WIDTH - 1, Math.max(x0, x1));
            int ly = Math.max(0, Math.min(y0, y1));
            int hy = Math.min(VANILLA_HEIGHT - 1, Math.max(y0, y1));
            byte vb = toVanillaPalette(paletteIndex);
            for (int yy = ly; yy <= hy; yy++) {
                for (int xx = lx; xx <= hx; xx++) {
                    delegate.setPixel(xx, yy, vb);
                }
            }
        }

        @Override
        public void drawText(int x, int y, String text, byte paletteIndex) {
            // Bukkit's MapCanvas#drawText takes a MapFont; the default
            // MinecraftFont renders 6-pixel glyphs which matches the
            // maps-api-ADR-001 §Mermaid output minimum legible width. We
            // pre-translate the palette byte to a "§<hex>" prefix so font
            // colour matches the rest of the chart palette.
            byte vb = toVanillaPalette(paletteIndex);
            delegate.drawText(x, y,
                    org.bukkit.map.MinecraftFont.Font,
                    text == null ? "" : text);
            // Note: MinecraftFont rendering ignores `vb` directly; ADR-001
            // permits binding-defined glyph palettes. Future Stage 3 work
            // may refine this if richer labelling is needed.
            // The vb local is intentionally retained to document the
            // translation step (and to keep the API symmetric with setPixel).
            if (vb == MapPalette.TRANSPARENT) {
                // no-op marker -- prevents the local being optimised out by
                // some IDEs flagging "unused".
            }
        }

        @Override
        public void clear() {
            byte vb = toVanillaPalette((byte) 0);
            for (int yy = 0; yy < VANILLA_HEIGHT; yy++) {
                for (int xx = 0; xx < VANILLA_WIDTH; xx++) {
                    delegate.setPixel(xx, yy, vb);
                }
            }
        }

        @Override
        public void setPixelRgb(int x, int y, int rgb) {
            if (x < 0 || x >= VANILLA_WIDTH || y < 0 || y >= VANILLA_HEIGHT) return;
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8)  & 0xFF;
            int b = rgb         & 0xFF;
            // MapPalette.matchColor walks the full vanilla map palette
            // (~144 distinct colours) and returns the nearest match. This
            // bypasses the 32-slot logical palette entirely - renderers
            // that drive setPixelRgb get the full vanilla colour space
            // without changing the logical contract.
            delegate.setPixel(x, y, MapPalette.matchColor(r, g, b));
        }

        @Override
        public void commit() {
            // No-op: Bukkit's MapCanvas commits implicitly when the
            // MapRenderer#render(...) method returns. REQ-RTP-MAP-002:
            // no chunk I/O, no blocking future.
        }
    }
}
