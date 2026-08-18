package io.github.dailystruggle.rtp.bukkitplatform.network;

import io.github.dailystruggle.metrics.api.MetricsSnapshot;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.network.BackendStateSampler;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat.PluginState;
import org.bukkit.Bukkit;
import org.bukkit.World;

import io.github.dailystruggle.rtp.common.selection.region.Region;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bukkit/Paper/Folia implementation of {@link BackendStateSampler}.
 * Pulls TPS / MSPT / player count from the active
 * {@link io.github.dailystruggle.metrics.api.MetricsBinding} via
 * {@link RTP#metrics} and reads loaded worlds plus available regions from
 * the active {@link RTP} instance.
 *
 * <p>Pinned by rtp-proxy-ADR-011 section Backend Wiring. Lives in
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

    /**
     * Lobby-mode flag. When {@code true}, heartbeats publish an
     * empty {@code regions} set and {@code acceptingRequests = false} so
     * peers never select this backend as a cross-server destination. The
     * local {@code RTP.selectionAPI.permRegionLookup} contents are ignored
     * for advertisement purposes; the backend still serves local
     * {@code /rtp} calls when an operator-defined region exists, but the
     * network treats it as "dispatch-only".
     */
    private volatile boolean lobbyMode;

    /** Default ctor. Lobby mode off. */
    public BukkitBackendStateSampler() {
        this.lobbyMode = false;
    }

    /**
     * {@code lobbyMode == true} flips the suppression described on {@link #lobbyMode}.
     */
    public BukkitBackendStateSampler(boolean lobbyMode) {
        this.lobbyMode = lobbyMode;
    }

    /**
     * Toggle lobby mode at runtime. Used by {@code NetworkModeBootstrap} to
     * re-arm the sampler when {@code routing.lobbyMode} is flipped on
     * reload. Volatile write; next heartbeat tick observes the new value.
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

        // Queue depth: best-effort from the RTP region queue manager.
        // Default 0 keeps the SQL column happy without lying about the actual depth.
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
        // This is intentionally a coarse snapshot - the proxy selector only uses
        // it for filtering "can this backend satisfy a request for region X?",
        // not for fine-grained matching.
        //
        // Lobby-mode backends publish an empty regions list and
        // acceptingRequests=false so peers never select them. The local
        // permRegionLookup is intentionally NOT consulted - even if the
        // operator left a default region defined locally, the network
        // should treat the lobby as dispatch-only.
        List<String> regions = new ArrayList<>();
        boolean accepting = true;
        // Per-region kept-cache size + total. Populated only outside lobby
        // mode; lobby backends ship empty maps so peer pickers exclude them.
        // NOTE on field semantics: BackendHeartbeat.regionKeptCounts is
        // documented as "per-region count of locations in networkKeptLocations".
        // We deliberately also publish local keptLocations.size() into the same
        // map because the lobby's
        // pickMostKept() needs *any* fresh signal of cached coordinate depth
        // and networkKeptLocations is empty on backends not yet running the
        // cross-server promotion loop. Sum is keptLocations + networkKeptLocations
        // so a network-aware backend's data is not under-counted.
        int keptCountTotal = 0;
        int networkReservedTotal = 0;
        Map<String, Integer> regionKeptCounts = new HashMap<>();
        Set<String> regionSet = new HashSet<>();
        // Per-region descriptive attributes (icon / display hints) advertised
        // to peers and addons. Keyed "<region>.<attribute>"; purely
        // informational. We publish the destination world's environment as a
        // raw string (NORMAL / NETHER / THE_END, or a custom-dimension name)
        // so a consuming server can translate env -> block locally, plus a
        // representative block derived here on the provider side for the
        // common vanilla environments.
        Map<String, String> regionMetadata = new HashMap<>();
        if (lobbyMode) {
            accepting = false;
        } else {
            try {
                RTP r = RTP.getInstance();
                if (r != null) {
                    for (Region region : io.github.dailystruggle.rtp.common.RTP.selectionAPI.permRegionLookup.values()) {
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
                            io.github.dailystruggle.rtp.api.world.RTPWorld<?> rw =
                                    region.getWorld();
                            String worldName = (rw == null) ? null : rw.name();
                            World bw = (worldName == null) ? null : Bukkit.getWorld(worldName);
                            if (bw != null) {
                                String env = bw.getEnvironment().name();
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
                            // label (region displayName) so a lobby can show the
                            // backend's chosen words for this region. Only published
                            // when it actually differs from the region name, to keep
                            // the heartbeat compact. Sent raw (un-rendered); the
                            // consumer applies color/gradient formatting.
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
     * Provider-side region -> block hint for the common vanilla environments,
     * advertised so a consuming menu can show a recognisable surface block for
     * a cross-server destination. Returns {@code null} for an unrecognised
     * (custom-dimension) environment, leaving the consumer to translate the
     * advertised {@code env} string locally.
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
