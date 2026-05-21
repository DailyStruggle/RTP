package io.github.dailystruggle.rtp.bukkitplatform.network;

import io.github.dailystruggle.metrics.api.MetricsSnapshot;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.network.BackendStateSampler;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat.PluginState;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Bukkit/Paper/Folia implementation of {@link BackendStateSampler}.
 * Pulls TPS / MSPT / player count from the active
 * {@link io.github.dailystruggle.metrics.api.MetricsBinding} via
 * {@link RTP#metrics} and reads loaded worlds plus available regions from
 * the active {@link RTP} instance.
 *
 * <p>Pinned by rtp-proxy-ADR-011 §Backend Wiring. Lives in
 * {@code rtp-bukkit-common} (not {@code rtp-core}) because it imports
 * {@code org.bukkit.*}; the platform-agnostic surface stays in
 * {@link BackendStateSampler}.</p>
 *
 * <p>Sampling is cheap: snapshot read from the metrics binding is lock-free;
 * region / world enumeration touches {@code Bukkit.getWorlds()} (small
 * collection) and the in-memory region map.</p>
 */
public final class BukkitBackendStateSampler implements BackendStateSampler {

    /** Schema version emitted into heartbeats; pinned by REQ-RTP-NET-009. */
    private static final int SCHEMA_VERSION = 1;

    @Override
    public BackendHeartbeat sample(String serverId) {
        long now = System.currentTimeMillis();

        // Metrics: best-effort. Before MetricsBindingDispatcher installs a real
        // binding, RTP.metrics may report UNSAMPLED; we coerce to 0 / -1 so the
        // schema's NOT NULL columns are still satisfied.
        double mspt;
        int playerCount;
        int softCap;
        long heapUsed;
        long heapMax;
        try {
            MetricsSnapshot s = io.github.dailystruggle.rtp.common.RTP.metrics.snapshot();
            // MetricsSnapshot exposes public-final fields; UNSAMPLED == Double.NaN.
            mspt = s == null || Double.isNaN(s.mspt) ? 0.0 : s.mspt;
            playerCount = s == null ? 0 : s.playerCount;
            softCap = s == null ? 0 : s.softCap;
            heapUsed = s == null ? 0L : s.heapUsedBytes;
            heapMax = s == null ? 0L : s.heapMaxBytes;
        } catch (Throwable ignored) {
            mspt = 0.0;
            playerCount = 0;
            softCap = 0;
            heapUsed = 0L;
            heapMax = 0L;
        }

        // Loaded worlds: cheap; the cap defends against pathological setups.
        List<String> worlds = new ArrayList<>();
        try {
            for (World w : Bukkit.getWorlds()) {
                worlds.add(w.getName());
            }
        } catch (Throwable ignored) {
            // Defensive: tests may run without a Bukkit server in scope.
        }

        // Queue depth: best-effort from the RTP region queue manager. Not
        // wired to the network wait queue yet (Phase 2c). Default 0 keeps
        // the SQL column happy without lying about the actual depth.
        int queueDepth = 0;
        try {
            RTP r = RTP.getInstance();
            if (r != null) {
                queueDepth = r.processingPlayers.size();
            }
        } catch (Throwable ignored) {
            // Defensive.
        }

        // Regions available: lift the region keys from the loaded RTP config.
        // For Phase 2e this is intentionally a coarse snapshot - the proxy
        // selector only uses it for filtering "can this backend satisfy a
        // request for region X?", not for fine-grained matching.
        List<String> regions = new ArrayList<>();
        try {
            RTP r = RTP.getInstance();
            if (r != null) {
                regions.addAll(io.github.dailystruggle.rtp.common.RTP.selectionAPI.permRegionLookup.keySet());
            }
        } catch (Throwable ignored) {
            // Defensive.
        }

        return new BackendHeartbeat(
                serverId,
                SCHEMA_VERSION,
                PluginState.READY,
                /* acceptingRequests */ true,
                now,
                mspt,
                queueDepth,
                softCap,
                heapUsed,
                heapMax,
                playerCount,
                regions,
                worlds,
                /* killSwitch */ false);
    }
}
