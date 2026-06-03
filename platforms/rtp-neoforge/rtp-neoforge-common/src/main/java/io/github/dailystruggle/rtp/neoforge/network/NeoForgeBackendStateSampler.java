package io.github.dailystruggle.rtp.neoforge.network;

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
 * NeoForge implementation of {@link BackendStateSampler} (ADR-049 step 4
 * backend parity, NeoForge analogue of {@code FabricBackendStateSampler}).
 * Structurally mirrors {@code BukkitBackendStateSampler}: it pulls TPS / MSPT /
 * player count from the active {@link io.github.dailystruggle.metrics.api.MetricsBinding}
 * via {@link RTP#metrics} and reads available regions from the active {@link RTP}
 * instance.
 *
 * <p>The only platform-specific difference from the Bukkit sampler is loaded
 * world enumeration: instead of {@code Bukkit.getWorlds()} this reads
 * {@link io.github.dailystruggle.rtp.api.server.RTPServerAccessor#getRTPWorlds()},
 * so the class itself carries no {@code net.minecraft.*} import and lives in
 * {@code rtp-neoforge-common} alongside the other carrier-agnostic glue.</p>
 *
 * <p>Installed onto {@link RTP#backendStateSamplerFactory} by the NeoForge mod
 * entrypoint ({@code RTPNeoForgeMod}); {@code NetworkModeBootstrap.boot(...)}
 * invokes the factory with the {@code routing.lobbyMode} flag.</p>
 */
public final class NeoForgeBackendStateSampler implements BackendStateSampler {

    /** Schema version emitted into heartbeats; pinned by REQ-RTP-NET-009. */
    private static final int SCHEMA_VERSION = 1;

    /**
     * Lobby-mode flag. When {@code true}, heartbeats publish an empty
     * {@code regions} set and {@code acceptingRequests = false} so peers never
     * select this backend as a cross-server destination.
     */
    private volatile boolean lobbyMode;

    /** Default ctor. Lobby mode off. */
    public NeoForgeBackendStateSampler() {
        this.lobbyMode = false;
    }

    /** {@code lobbyMode == true} flips the suppression above. */
    public NeoForgeBackendStateSampler(boolean lobbyMode) {
        this.lobbyMode = lobbyMode;
    }

    /** Toggle lobby mode at runtime. Volatile write; the next heartbeat observes it. */
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

        // Loaded worlds: read through the platform-neutral accessor so this
        // class needs no net.minecraft.* import.
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

        int queueDepth = 0;
        try {
            RTP r = RTP.getInstance();
            if (r != null) {
                queueDepth = r.processingPlayers.size();
            }
        } catch (Throwable ignored) {
            // Defensive.
        }

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
