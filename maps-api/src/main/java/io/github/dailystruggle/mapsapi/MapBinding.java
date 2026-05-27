package io.github.dailystruggle.mapsapi;

import io.github.dailystruggle.mapsapi.model.ChartModel;
import io.github.dailystruggle.mapsapi.render.ChartRenderer;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Platform-side runtime hook through which {@code maps-api} reaches Bukkit /
 * Paper / Folia / Fabric map subsystems. Concrete implementations live in:
 * <ul>
 *   <li>{@code mapsapi.bukkit.BukkitMapBinding} (Stage 2)</li>
 *   <li>{@code rtp-folia} {@code FoliaMapBinding} (Stage 2)</li>
 *   <li>{@code mapsapi.fabric.FabricMapBinding} (Stage 3)</li>
 *   <li>{@link io.github.dailystruggle.mapsapi.noop.NoopMapBinding} (Lite assembly + tests)</li>
 * </ul>
 *
 * <h2>Contracts</h2>
 * <ul>
 *   <li><strong>REQ-RTP-MAP-001 (extends REQ-RTP-S-006):</strong> when no
 *       binding is installed via {@code RTPHooks}, every entry-point shall
 *       throw {@link IllegalStateException} — never return {@code null} and
 *       never silently no-op.</li>
 *   <li><strong>REQ-RTP-MAP-002 (extends REQ-RTP-F-008 / REQ-RTP-S-005):</strong>
 *       no method on this interface shall block on chunk I/O or call
 *       {@code CompletableFuture#get / join}. Refresh dispatch happens on the
 *       platform's appropriate async / region scheduler.</li>
 *   <li><strong>REQ-RTP-MAP-003:</strong> implementations shall register every
 *       live binding with {@code MemoryTracker} and release on
 *       {@link Cancellation#cancel()}, on player disconnect, and on plugin
 *       disable.</li>
 *   <li><strong>Folia commit-thread:</strong> {@link MapCanvas#commit()} for a
 *       Bukkit-family binding shall be performed via the Global Region
 *       Scheduler ({@code MapView} has no region affinity); per-viewer pixel
 *       commits dispatch via the Entity Scheduler.</li>
 * </ul>
 */
public interface MapBinding {

    /**
     * Reserves a map slot on the host runtime and returns its handle. May
     * return an existing handle for a previously-seen
     * {@code request.chartId()} (idempotency hint, not a strict guarantee).
     *
     * @throws IllegalStateException if the SPI is not yet wired (REQ-RTP-MAP-001)
     */
    MapHandle allocate(MapAllocationRequest request);

    /**
     * Renders {@code model} once via {@code renderer} onto the canvas backing
     * {@code handle}, then commits a single frame. No subscription, no
     * refresh loop.
     *
     * @throws IllegalStateException if the SPI is not yet wired (REQ-RTP-MAP-001)
     */
    <M extends ChartModel> void renderEphemeral(MapHandle handle,
                                                ChartRenderer<M> renderer,
                                                M model);

    /**
     * Subscribes a live chart to {@code handle}: on every refresh tick the
     * binding pulls {@code modelSupplier.get()} and dispatches {@code renderer}
     * + commit through the appropriate platform scheduler.
     *
     * @return cancellation handle for the lifecycle (REQ-RTP-MAP-003)
     * @throws IllegalStateException if the SPI is not yet wired (REQ-RTP-MAP-001)
     */
    <M extends ChartModel> Cancellation bindLive(MapHandle handle,
                                                 ChartRenderer<M> renderer,
                                                 Supplier<M> modelSupplier);

    /**
     * Delivers the rendered map referenced by {@code handle} to {@code viewer}
     * so the client actually displays it. Minecraft only renders a
     * {@code MapView}'s pixels to a client that is holding a {@code FILLED_MAP}
     * item referencing the view's id (or sees one in an item frame); a freshly
     * allocated and painted {@code MapView} with no item pointing at it is
     * invisible by design. Implementations shall arrange for that item to
     * reach the viewer (Bukkit-family: place a {@code FILLED_MAP} into the
     * player's inventory).
     *
     * <p>Default implementation is a no-op so the {@code NoopMapBinding} and
     * test doubles need no override.
     *
     * <p>Threading: implementations are responsible for hopping to the
     * platform-appropriate thread (Folia: viewer's {@code EntityScheduler};
     * Paper: main thread) before mutating player inventory.
     *
     * <p>S-004: implementations shall not silently swallow delivery failures
     * (offline viewer, full inventory, etc.); throw {@link RuntimeException}
     * so {@code MapDispatch.paint} surfaces a user-facing message.
     */
    default void deliverTo(MapHandle handle, UUID viewer) {
        // no-op: NoopMapBinding and test doubles inherit this.
    }
}
