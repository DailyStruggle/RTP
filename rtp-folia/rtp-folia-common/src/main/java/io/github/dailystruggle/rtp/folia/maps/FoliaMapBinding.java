package io.github.dailystruggle.rtp.folia.maps;

import io.github.dailystruggle.mapsapi.MapHandle;
import io.github.dailystruggle.mapsapi.bukkit.BukkitMapBinding;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.UUID;

/**
 * Folia-specific {@code MapBinding} override. Thin subclass of
 * {@link BukkitMapBinding} that exists per {@code CHECKLIST-maps-api.md}
 * Stage 2.3 / REQ-RTP-MAP-001..003 to provide a dedicated home for Folia
 * region-affinity scheduling.
 *
 * <p>Stage 2.3 scope (this class):
 * <ul>
 *   <li>{@code allocate} -- inherits the Bukkit allocation path.
 *       {@code Bukkit.createMap} is safe to call from any region thread on
 *       Folia (the underlying registry is synchronised internally).</li>
 *   <li>{@code renderEphemeral} -- inherits the Bukkit ephemeral renderer.
 *       Vanilla {@code MapView} commits are global-region-scheduled by Folia
 *       itself, so the one-shot {@code MapRenderer} installed by the
 *       superclass is sufficient and S-005 compliant (no chunk I/O on any
 *       region thread).</li>
 *   <li>{@code bindLive} -- inherited from the superclass. The
 *       supplier-driven live renderer reads only {@code RTP.metrics}
 *       (no chunk I/O, no region-affine state), so the per-tick
 *       {@code CraftMapView.render} dispatch on Folia is sufficient
 *       and no {@code EntityScheduler} hop is required.</li>
 * </ul>
 *
 * <p>Why this class exists at all if it currently delegates: the
 * {@code MapBinding} installed in {@code RTPBukkitPlugin#onEnable} is keyed
 * off {@code isFolia()}. Centralising the Folia variant here (rather than
 * branching inside {@code BukkitMapBinding}) follows the existing
 * {@code rtp-folia-common} convention of giving every Bukkit surface that
 * may need region-affinity hooks a dedicated Folia subclass, even when the
 * current override is empty. It also gives Stage 3 (live charts) a stable
 * type to refine without further touching {@code maps-api} or
 * {@code BukkitMapBinding}.
 *
 * @see BukkitMapBinding
 * @see io.github.dailystruggle.mapsapi.MapBinding
 */
public class FoliaMapBinding extends BukkitMapBinding {

    /**
     * Best-effort hop to a viewer's {@code EntityScheduler} for any
     * follow-up work a caller wants to attach to the viewer's region.
     * Currently unused by the ephemeral path (Stage 3 hook); exposed for
     * the future {@code bindLive} implementation.
     *
     * @param viewer player UUID; must not be {@code null}
     * @param work   runnable to dispatch on the viewer's region
     * @return {@code true} if the viewer was online and the task was
     *         scheduled, {@code false} otherwise
     */
    public boolean dispatchToViewerRegion(UUID viewer, Runnable work) {
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(work, "work");
        Player player;
        try {
            player = Bukkit.getPlayer(viewer);
        } catch (Throwable t) {
            // No server is running (e.g. unit-test context); treat as offline.
            return false;
        }
        if (player == null) return false;
        try {
            Plugin[] plugins = Bukkit.getPluginManager().getPlugins();
            if (plugins.length == 0) return false;
            player.getScheduler().run(plugins[0], scheduledTask -> work.run(), null);
            return true;
        } catch (Throwable t) {
            // Folia not present, or scheduler refused; caller treats this as
            // "no region hop available" and falls back to the global path.
            return false;
        }
    }

    /**
     * Folia override: hop to the viewer's {@code EntityScheduler} before
     * dropping the {@code FILLED_MAP} item entity, because mutating world
     * state at a player's location from a foreign region thread throws
     * {@code ThreadAccessException} on Folia. Delegates to the superclass
     * implementation once on the correct thread. If the viewer cannot be
     * reached (offline, no Folia scheduler present), falls back to the
     * superclass path (which itself throws an {@code IllegalStateException}
     * the dispatcher then surfaces to the player via {@code mapUnavailable}).
     */
    @Override
    public void deliverTo(MapHandle handle, UUID viewer) {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(viewer, "viewer");
        boolean hopped = dispatchToViewerRegion(viewer, () -> super.deliverTo(handle, viewer));
        if (!hopped) {
            // Viewer offline / Folia scheduler unavailable; superclass will
            // throw IllegalStateException, which is the S-004 contract.
            super.deliverTo(handle, viewer);
        }
    }

    // bindLive is inherited from BukkitMapBinding: CraftMapView.render is
    // dispatched per-viewer by the platform's own scheduling, on Folia just
    // as on Paper. The LiveChartRenderer body is supplier-driven and reads
    // only RTP.metrics (no chunk I/O, no region-affine state), so no
    // EntityScheduler hop is needed inside the per-tick render path.
}
