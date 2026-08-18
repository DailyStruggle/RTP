package io.github.dailystruggle.rtp.folia.maps;

import io.github.dailystruggle.mapsapi.bukkit.BukkitMapBinding;

/**
 * Folia-specific {@code MapBinding} override. Thin subclass of
 * {@link BukkitMapBinding} that exists per REQ-RTP-MAP-001..003 to provide a dedicated home for Folia
 * region-affinity scheduling.
 *
 * <p>Scope (this class):
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

    // deliverTo is inherited from BukkitMapBinding: the FILLED_MAP drop is
    // dispatched onto the viewer's region thread by the caller (MapDispatch)
    // via RTP.scheduler.runTask(location, ...), so no per-binding
    // EntityScheduler hop is needed here.

    // bindLive is inherited from BukkitMapBinding: CraftMapView.render is
    // dispatched per-viewer by the platform's own scheduling, on Folia just
    // as on Paper. The LiveChartRenderer body is supplier-driven and reads
    // only RTP.metrics (no chunk I/O, no region-affine state), so no
    // EntityScheduler hop is needed inside the per-tick render path.
}
