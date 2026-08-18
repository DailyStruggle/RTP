package io.github.dailystruggle.rtp.proxy.velocity;

import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat.PluginState;
import io.github.dailystruggle.rtp.proxy.common.transport.codec.BackendHeartbeatCodec;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Proxy-side availability store for the {@code proxy-cache} transport tier. The Velocity
 * companion is the one process that is always present (never player-empty),
 * so it is the natural authority for the cross-server region snapshot a lobby
 * reads to populate {@code /rtp region=} tab-completion.
 *
 * <p>Three row sources are merged, keyed by {@code serverId}, in ascending
 * precedence (later overrides earlier):</p>
 * <ul>
 *   <li><b>Topology (zero-config, zero-player):</b> the proxy's own
 *       registered-server list ({@code velocity.toml} {@code [servers]}, via
 *       {@code proxyServer.getAllServers()}). The Velocity companion is the
 *       one always-present process and natively knows every backend's id
 *       without any backend ever connecting, so it can synthesise a
 *       {@code READY} row per server (region assumed {@code default}). This is
 *       the base source that lets a lobby see {@code server:default} entries
 *       with <em>no players anywhere</em> and <em>no operator config</em> -
 *       a player-empty backend has no proxy connection and therefore cannot
 *       push, so the proxy must originate the row itself.</li>
 *   <li><b>Configured (direction B):</b> an operator-declared
 *       {@code servers.<id>.regions} map from {@code network.yml}. Overrides
 *       the assumed {@code default} region with the operator's real region
 *       list for that server.</li>
 *   <li><b>Live:</b> a {@code BackendHeartbeat} a backend actively pushed
 *       (over the {@code rtp:net} companion channel) while it had a player
 *       online. A non-stale live row wins over the topology/configured
 *       synthetic row for the same server, so real per-region availability
 *       supersedes the assumption whenever it is fresh.</li>
 * </ul>
 *
 * <p>This class is pure logic (no Velocity types) so it is unit-testable; the
 * {@link VelocityProxyCacheListener} owns the plugin-message glue.</p>
 */
public final class VelocityProxyAvailabilityCache {

    private final Map<String, List<String>> configuredRegions;
    private final Supplier<? extends Collection<String>> topologyServerIds;
    private final List<String> assumedRegions;
    private final long staleAfterMs;
    private final LongSupplier clock;
    private final Map<String, Live> live = new ConcurrentHashMap<>();

    private record Live(BackendHeartbeat hb, long seenMs) {
    }

    /**
     * @param configuredRegions operator-declared {@code serverId -> regions};
     *                           may be {@code null}/empty (then only live
     *                           pushes populate the snapshot)
     * @param staleAfterMs       a live row older than this is treated as stale
     *                           and falls back to its configured synthetic row
     * @param clock              wall-clock supplier (epoch ms); injectable for tests
     */
    public VelocityProxyAvailabilityCache(Map<String, List<String>> configuredRegions,
                                          long staleAfterMs,
                                          LongSupplier clock) {
        this(configuredRegions, null, null, staleAfterMs, clock);
    }

    /**
     * @param configuredRegions   operator-declared {@code serverId -> regions};
     *                            may be {@code null}/empty
     * @param topologyServerIds   supplier of the proxy's registered backend ids
     *                            ({@code proxyServer.getAllServers()}); evaluated
     *                            on every {@link #snapshot()} so servers that
     *                            register after boot are picked up. May be
     *                            {@code null} (no topology seeding).
     * @param assumedRegions      region id(s) assumed for a topology-seeded server
     *                            with no configured/live row; {@code null}/empty
     *                            defaults to {@code [default]}
     * @param staleAfterMs        a live row older than this is treated as stale
     * @param clock               wall-clock supplier (epoch ms); injectable for tests
     */
    public VelocityProxyAvailabilityCache(Map<String, List<String>> configuredRegions,
                                          Supplier<? extends Collection<String>> topologyServerIds,
                                          List<String> assumedRegions,
                                          long staleAfterMs,
                                          LongSupplier clock) {
        this.configuredRegions = configuredRegions == null
                ? Map.of() : new LinkedHashMap<>(configuredRegions);
        this.topologyServerIds = topologyServerIds;
        this.assumedRegions = (assumedRegions == null || assumedRegions.isEmpty())
                ? List.of("default") : List.copyOf(assumedRegions);
        this.staleAfterMs = staleAfterMs;
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    /** Cache a heartbeat a backend pushed. No-op for null / id-less rows. */
    public void onPush(BackendHeartbeat hb) {
        if (hb == null || hb.serverId() == null || hb.serverId().isEmpty()) return;
        live.put(hb.serverId(), new Live(hb, clock.getAsLong()));
    }

    /** Decode and cache a raw codec-encoded payload pushed by a backend. */
    public void onPushPayload(byte[] payload) {
        if (payload == null || payload.length == 0) return;
        onPush(BackendHeartbeatCodec.decode(new String(payload, StandardCharsets.UTF_8)));
    }

    /**
     * The merged snapshot: one row per known server. Live (non-stale) rows win
     * over configured synthetic rows; configured servers with no fresh live row
     * fall back to a synthetic {@code READY} row carrying the operator-declared
     * regions so a lobby sees availability even for a player-empty backend.
     */
    public List<BackendHeartbeat> snapshot() {
        long now = clock.getAsLong();
        Map<String, BackendHeartbeat> out = new LinkedHashMap<>();
        // Base source: the proxy's own registered-server list. Zero players,
        // zero operator config; the proxy originates a default-region row per
        // backend it knows about.
        if (topologyServerIds != null) {
            Collection<String> ids;
            try {
                ids = topologyServerIds.get();
            } catch (RuntimeException ex) {
                ids = null;
            }
            if (ids != null) {
                for (String id : ids) {
                    if (id == null || id.isEmpty()) continue;
                    out.put(id, synthetic(id, assumedRegions, now));
                }
            }
        }
        for (Map.Entry<String, List<String>> e : configuredRegions.entrySet()) {
            out.put(e.getKey(), synthetic(e.getKey(), e.getValue(), now));
        }
        for (Map.Entry<String, Live> e : live.entrySet()) {
            Live l = e.getValue();
            if (now - l.seenMs() <= staleAfterMs) {
                out.put(e.getKey(), l.hb());
            }
        }
        return new ArrayList<>(out.values());
    }

    /** Encode each snapshot row to its canonical codec payload (one per server). */
    public List<byte[]> snapshotPayloads() {
        List<byte[]> out = new ArrayList<>();
        for (BackendHeartbeat hb : snapshot()) {
            out.add(BackendHeartbeatCodec.encode(hb).getBytes(StandardCharsets.UTF_8));
        }
        return out;
    }

    private static BackendHeartbeat synthetic(String serverId, List<String> regions, long now) {
        List<String> r = regions == null ? List.of() : regions;
        return new BackendHeartbeat(
                serverId, 1, PluginState.READY, true, now,
                0.0, 0, 0, 0L, 0L, 0, r, List.of(), false);
    }
}
