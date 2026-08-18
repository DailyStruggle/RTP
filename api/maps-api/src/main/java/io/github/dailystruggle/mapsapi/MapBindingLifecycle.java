package io.github.dailystruggle.mapsapi;

import java.util.UUID;

/**
 * Optional lifecycle SPI implemented by concrete {@link MapBinding}s that
 * cache state per viewer or per chart and want to be notified when those
 * cache entries are no longer reachable. Mirrors the registration shape used
 * by {@code effects-api} and {@code commands-api}: the SPI is defined here in
 * {@code maps-api}, platform adapters implement it on their concrete binding,
 * and a single bridge listener in the platform adapter forwards
 * {@code PlayerQuitEvent} / plugin {@code onDisable} to the registry in
 * {@code rtp-core} ({@code MapDispatch}).
 *
 * <p>Lifecycle triggers (REQ-RTP-MAP-003):
 * <ul>
 *   <li>{@link #onPlayerQuit(UUID)} - called when a viewer disconnects.
 *       Implementations release any cached handles, map views, or other
 *       resources scoped to that viewer.</li>
 *   <li>{@link #onDisable()} - called once when the host plugin disables.
 *       Implementations release every cached resource and refuse subsequent
 *       allocations (a fresh instance is created on re-enable).</li>
 * </ul>
 *
 * <p>Both methods shall be safe to call from any thread, shall not perform
 * chunk I/O (REQ-RTP-S-005), and shall not block. Implementations log
 * exceptions at WARNING via {@code RTP.log} (REQ-RTP-S-004) rather than
 * propagating; the registry in {@code MapDispatch} additionally catches and
 * logs any unexpected throwable so one misbehaving binding never poisons
 * another's notification.
 */
public interface MapBindingLifecycle {

    /**
     * Release any per-viewer state cached for {@code viewer}. A no-op if the
     * binding holds no state for that viewer. Called once per
     * {@code PlayerQuitEvent} from the platform bridge.
     *
     * @param viewer the disconnecting player's UUID; never {@code null}.
     */
    void onPlayerQuit(UUID viewer);

    /**
     * Release every cached resource. Called once from
     * {@code RTPBukkitPlugin#onDisable} (or the Folia / Fabric equivalent)
     * via the {@code MapDispatch} fan-out. After this call the binding
     * shall be treated as unusable until a fresh instance is installed.
     */
    void onDisable();
}
