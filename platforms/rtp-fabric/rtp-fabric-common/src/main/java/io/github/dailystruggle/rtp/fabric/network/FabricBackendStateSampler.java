package io.github.dailystruggle.rtp.fabric.network;

import io.github.dailystruggle.metrics.api.MetricsSnapshot;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.network.BackendStateSampler;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat.PluginState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fabric implementation of {@link BackendStateSampler} (ADR-049 step 4 /
 * rtp-fabric-ADR-013 backend parity). Structurally mirrors
 * {@code BukkitBackendStateSampler}: it pulls TPS / MSPT / player count from
 * the active {@link io.github.dailystruggle.metrics.api.MetricsBinding} via
 * {@link RTP#metrics} and reads available regions from the active {@link RTP}
 * instance.
 *
 * <p>The only platform-specific difference from the Bukkit sampler is loaded
 * world enumeration: instead of {@code Bukkit.getWorlds()} this reads
 * {@link io.github.dailystruggle.rtp.api.server.RTPServerAccessor#getRTPWorlds()},
 * so the class itself carries no {@code net.minecraft.*} import and can live in
 * {@code rtp-fabric-common} alongside the other carrier-agnostic Fabric glue.</p>
 *
 * <p>Installed onto {@link RTP#backendStateSamplerFactory} by the Fabric mod
 * entrypoint ({@code RTPFabricMod}); {@code NetworkModeBootstrap.boot(...)}
 * invokes the factory with the {@code routing.lobbyMode} flag.</p>
 */
public final class FabricBackendStateSampler implements BackendStateSampler {

    /** Schema version emitted into heartbeats; pinned by REQ-RTP-NET-009. */
    private static final int SCHEMA_VERSION = 1;

    /**
     * Lobby-mode flag. When {@code true}, heartbeats publish an
     * empty {@code regions} set and {@code acceptingRequests = false} so peers
     * never select this backend as a cross-server destination.
     */
    private volatile boolean lobbyMode;

    /** Default ctor. Lobby mode off. */
    public FabricBackendStateSampler() {
        this.lobbyMode = false;
    }

    /** {@code lobbyMode == true} flips the suppression described on {@link #lobbyMode}. */
    public FabricBackendStateSampler(boolean lobbyMode) {
        this.lobbyMode = lobbyMode;
    }

    /**
     * Toggle lobby mode at runtime. Volatile write; the next heartbeat tick
     * observes the new value.
     */
    public void setLobbyMode(boolean lobbyMode) {
        this.lobbyMode = lobbyMode;
    }

    /** @return current lobby-mode flag. Visible for tests. */
    public boolean isLobbyMode() {
        return lobbyMode;
    }

    @Override
    public BackendHeartbeat sample(String serverId) {
        long now = System.currentTimeMillis();

        // Metrics: best-effort. Before MetricsBindingDispatcher installs a real
        // binding, RTP.metrics may report UNSAMPLED; coerce to 0 / -1 so the
        // schema's NOT NULL columns are still satisfied.
        double mspt;
        int playerCount;
        int softCap;
        long heapUsed;
        long heapMax;
        try {
            MetricsSnapshot s = RTP.metrics.snapshot();
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

        // Loaded worlds: cheap. Read through the platform-neutral accessor so
        // this class needs no net.minecraft.* import (parity with the Bukkit
        // sampler's Bukkit.getWorlds() loop).
        List<String> worlds = new ArrayList<>();
        try {
            if (RTP.serverAccessor != null) {
                for (RTPWorld<?> w : RTP.serverAccessor.getRTPWorlds()) {
                    if (w != null && w.name() != null) {
                        worlds.add(w.name());
                    }
                }
            }
        } catch (Throwable ignored) {
            // Defensive: tests may run without a server in scope.
        }

        // Queue depth: best-effort from the RTP region queue manager.
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
        // Lobby-mode backends publish an empty regions list and
        // acceptingRequests=false so peers never select them.
        List<String> regions = new ArrayList<>();
        boolean accepting = true;
        int keptCountTotal = 0;
        int networkReservedTotal = 0;
        Map<String, Integer> regionKeptCounts = new HashMap<>();
        Set<String> regionSet = new HashSet<>();
        if (lobbyMode) {
            accepting = false;
        } else {
            try {
                RTP r = RTP.getInstance();
                if (r != null) {
                    for (Region region : RTP.selectionAPI.permRegionLookup.values()) {
                        if (region == null || region.name == null) continue;
                        regions.add(region.name);
                        regionSet.add(region.name);
                        int per = 0;
                        try {
                            if (region.queueManager != null) {
                                if (region.queueManager.keptLocations != null) {
                                    per += region.queueManager.keptLocations.size();
                                }
                                if (region.queueManager.networkKeptLocations != null) {
                                    per += region.queueManager.networkKeptLocations.size();
                                }
                            }
                        } catch (Throwable ignored) {
                            // Defensive: a region mid-shutdown may NPE on its buffers.
                        }
                        regionKeptCounts.put(region.name, per);
                        keptCountTotal += per;
                    }
                }
            } catch (Throwable ignored) {
                // Defensive.
            }
        }

        return new BackendHeartbeat(
                serverId,
                SCHEMA_VERSION,
                PluginState.READY,
                accepting,
                now,
                mspt,
                queueDepth,
                softCap,
                heapUsed,
                heapMax,
                playerCount,
                regions,
                worlds,
                /* killSwitch */ false,
                keptCountTotal,
                networkReservedTotal,
                regionSet,
                regionKeptCounts);
    }
}
