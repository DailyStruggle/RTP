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
        // Per-region descriptive attributes (icon / display hints) advertised
        // to peers and addons. Keyed "<region>.<attribute>"; purely
        // informational. Mirrors the Bukkit/Fabric sampler so a lobby/GUI can
        // show a recognisable cross-server icon and the operator-configured
        // display label (region displayName) for a NeoForge backend's regions.
        Map<String, String> regionMetadata = new HashMap<>();
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
                        try {
                            RTPWorld<?> rw = region.getWorld();
                            String env = (rw == null) ? null : rw.environment();
                            if (env != null && !env.isEmpty()) {
                                regionMetadata.put(region.name + ".env", env);
                                String block = representativeBlock(env);
                                if (block != null) {
                                    regionMetadata.put(region.name + ".block", block);
                                }
                            }
                        } catch (Throwable ignored) {
                            // Defensive: env enrichment is best-effort and must
                            // never break the heartbeat sample.
                        }
                        try {
                            // Advertise the operator-configured cosmetic display
                            // label (region displayName) so a lobby shows the
                            // backend's chosen words for this region. Only published
                            // when it differs from the region name, to keep the
                            // heartbeat compact. Sent raw; the consumer applies
                            // color/gradient formatting.
                            String label = region.displayName();
                            if (label != null && !label.isEmpty()
                                    && !label.equals(region.name)) {
                                regionMetadata.put(region.name + ".label", label);
                            }
                        } catch (Throwable ignored) {
                            // Defensive: label enrichment is best-effort.
                        }
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
                regionKeptCounts,
                regionMetadata);
    }

    /**
     * Provider-side environment -> block hint for the common vanilla
     * environments, advertised so a consuming menu can show a recognisable
     * surface block for a cross-server destination. Returns {@code null} for an
     * unrecognised (custom-dimension) environment, leaving the consumer to
     * translate the advertised {@code env} string locally.
     */
    private static String representativeBlock(String env) {
        if (env == null) return null;
        switch (env) {
            case "NORMAL":
                return "GRASS_BLOCK";
            case "NETHER":
                return "NETHERRACK";
            case "THE_END":
                return "END_STONE";
            default:
                return null;
        }
    }
}
