package io.github.dailystruggle.rtp.bukkit.network;

import io.github.dailystruggle.rtp.bukkitplatform.network.BukkitBackendStateSampler;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection;
import io.github.dailystruggle.rtp.common.database.options.AbstractSQLDatabaseAccessor;
import io.github.dailystruggle.rtp.common.network.BackendStatePublisher;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkSnapshot;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkTransport;
import io.github.dailystruggle.rtp.proxy.common.transport.ReservationTokenReaper;
import io.github.dailystruggle.rtp.proxy.common.transport.memory.InMemoryNetworkRequestQueue;
import io.github.dailystruggle.rtp.proxy.common.transport.memory.InMemoryNetworkStateBinding;
import io.github.dailystruggle.rtp.proxy.common.transport.redis.RedisNetworkStateBinding;
import io.github.dailystruggle.rtp.proxy.common.transport.sql.SqlNetworkStateBinding;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.Duration;
import java.util.logging.Level;

/**
 * Self-contained backend-side network-mode bootstrap. Called once from
 * {@code RTPBukkitPlugin.onEnable} after the database is set up; mirror call
 * in {@code onDisable} via {@link #shutdown()}. Keeps the network-mode
 * lifecycle out of {@code RTPBukkitPlugin}'s already-large {@code onEnable}.
 *
 * <p>Strict REQ-RTP-NET-002 (Behavioural Parity When Disabled): when
 * {@code network.enabled: false} (the default), {@link #boot(File)}
 * does nothing observable except read-and-discard the file. No transport
 * opens, no scheduler starts, no DDL runs.</p>
 *
 * <p>Pinned by rtp-proxy-ADR-011 §Backend Wiring. Holds the live
 * {@link NetworkTransport} + {@link BackendStatePublisher} for the lifetime
 * of the plugin; both are released in reverse order on {@link #shutdown()}.</p>
 */
public final class NetworkModeBootstrap {

    private NetworkTransport transport;
    private BackendStatePublisher publisher;
    private ReservationTokenReaper reservationReaper;
    private JoinTriggerSource joinTriggerSource;
    // L6 Slice C: backend-side router + dirty-write enrolment buffer + read-side status cache.
    // All three live only while network mode is enabled and boot() has fully succeeded.
    private NetworkRouter router;
    private NetworkEnrolmentBuffer enrolmentBuffer;
    private NetworkStatusCache statusCache;
    // L6 Slice D row D6: cross-server request queue SPI. Today only the
    // in-memory binding is wired (D4/D5 still pending); for any other
    // transport kind this stays null and the Slice C no-op sinks remain.
    private NetworkRequestQueue requestQueue;

    /**
     * Run boot logic. Idempotent for the disabled-mode path; calling
     * {@link #boot(File)} twice with {@code enabled:false} is a no-op.
     * Calling {@code boot} twice with {@code enabled:true} is undefined -
     * the host (RTPBukkitPlugin) only invokes once per lifecycle.
     *
     * <p>L2 of {@code CHECKLIST-cross-server-rtp.md}: when the transport
     * opens successfully the host (RTPBukkitPlugin) should pass a non-null
     * plugin reference to {@link #registerJoinTriggerSource(org.bukkit.plugin.Plugin)}
     * after listener registration so cross-server arrivals are redeemed at
     * {@code PlayerJoinEvent} time. The bootstrap stores the resolved
     * serverId for that follow-up call.</p>
     *
     * @param networkYml the {@code network.yml} file under the plugin's
     *                   data folder; may be absent (treated as
     *                   {@code enabled:false})
     */
    public void boot(File networkYml) {
        // REQ-RTP-NET-002 fast-exit: file absent => disabled, byte-identical no-op.
        if (networkYml == null || !networkYml.isFile()) {
            return;
        }

        RtpYamlConfig cfg;
        try {
            cfg = RtpYamlConfig.load(networkYml);
        } catch (IOException e) {
            RTP.log(Level.WARNING,
                    "[NETWORK] network.yml present but failed to parse: " + e.getMessage()
                            + " - network mode stays disabled.", e);
            return;
        }

        RtpYamlSection network = cfg.getConfigurationSection("network");
        boolean enabled = network != null && network.getBoolean("enabled", false);
        if (!enabled) {
            return; // REQ-RTP-NET-002: parity-by-construction.
        }

        String serverId = network.getString("serverId", "");
        if (serverId == null || serverId.isEmpty()) {
            RTP.log(Level.WARNING,
                    "[NETWORK] network.enabled=true but network.serverId is empty; "
                            + "refusing to enable network mode (REQ-RTP-NET-002).");
            return;
        }

        RtpYamlSection transportSec = cfg.getConfigurationSection("transport");
        String transportType = transportSec == null ? "in-memory"
                : transportSec.getString("type", "in-memory");
        RtpYamlSection heartbeat = cfg.getConfigurationSection("heartbeat");
        long intervalMs = heartbeat == null ? 1000L : heartbeat.getLong("intervalMs", 1000L);
        if (intervalMs <= 0) intervalMs = 1000L;

        // Reservation-token TTL reaper cadence (REQ-RTP-NET-011). Mirrors
        // NetworkConfig.reservationReapIntervalMs() default + clamping.
        RtpYamlSection reservation = cfg.getConfigurationSection("reservation");
        long reapMs = reservation == null ? 30_000L : reservation.getLong("reapIntervalMs", 30_000L);
        if (reapMs <= 0) reapMs = 30_000L;

        NetworkTransport selected;
        try {
            selected = openTransport(transportType, intervalMs, transportSec);
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[NETWORK] Failed to open transport.type=" + transportType
                            + ": " + t.getMessage()
                            + " - network mode stays disabled.", t);
            return;
        }
        if (selected == null) return;

        // Install on the SQL accessor (D3 slot) so any future consumer of
        // RTP.serverAccessor.getDatabaseAccessor()...getNetworkStateBinding()
        // sees the active binding without needing to hop through this helper.
        try {
            if (RTP.getInstance() != null
                    && RTP.getInstance().databaseAccessor instanceof AbstractSQLDatabaseAccessor sql) {
                sql.setNetworkStateBinding(new BindingAdapter(selected));
            }
        } catch (Throwable ignored) {
            // The D3 slot is a convenience; absence does not block heartbeat publishing.
        }

        BukkitBackendStateSampler sampler = new BukkitBackendStateSampler();
        BackendStatePublisher pub = new BackendStatePublisher(selected, sampler, serverId, intervalMs);
        pub.start();

        // Reservation-token TTL reaper (REQ-RTP-NET-011). On backends in proxy-
        // aware mode the reaper sweeps tokens this backend (or any peer) issued,
        // dispatching release(TTL_EXPIRED) so the originating backend's buffer
        // gets its earmarked coordinate back.
        ReservationTokenReaper reaper = new ReservationTokenReaper(selected, Duration.ofMillis(reapMs));
        reaper.start();

        this.transport = selected;
        this.publisher = pub;
        this.reservationReaper = reaper;
        // L2: prepare the join-time redeem listener. Actual Bukkit listener
        // registration is deferred to registerJoinTriggerSource(Plugin) so
        // RTPBukkitPlugin can register us alongside its other listeners.
        this.joinTriggerSource = new JoinTriggerSource(selected, serverId);

        // L6 Slice C: wire router + enrolment buffer + status cache.
        // The actual Redis EVAL sink + status supplier are attached by Slice D.
        // Until then the buffer's flushSink is a no-op and the cache's
        // supplier returns an empty Collection, so the timers spin but do
        // not move any cross-server traffic - matching D2 (routing.mode=local).
        try {
            RtpYamlSection routing = cfg.getConfigurationSection("routing");
            String modeRaw = routing == null ? "local" : routing.getString("mode", "local");
            NetworkRouter.Mode routerMode = NetworkRouter.Mode.parse(modeRaw);

            RtpYamlSection queue = cfg.getConfigurationSection("queue");
            int queueMaxDepth = queue == null ? 50 : (int) queue.getLong("maxDepth", 50L);
            long flushIntervalMs = queue == null ? 250L : queue.getLong("flushIntervalMs", 250L);
            long pollIntervalMs = queue == null ? 1000L : queue.getLong("pollIntervalMs", 1000L);
            int rps = queue == null ? 5 : (int) queue.getLong("crossServerRequestsPerSecond", 5L);
            int burst = queue == null ? 10 : (int) queue.getLong("crossServerRequestsBurst", 10L);

            // Slice D row D6: build the cross-server queue alongside the
            // transport. Today only the in-memory kind is wired; for sql /
            // redis the factory throws UnsupportedOperationException and we
            // fall through to the Slice C no-op sinks (the C7 wiring catch
            // block below picks it up). Failure of openRequestQueue is NOT
            // fatal - the rest of network mode (heartbeat publish, snapshot
            // read, join redeem) keeps working.
            NetworkRequestQueue rq;
            try {
                rq = openRequestQueue(transportType);
            } catch (UnsupportedOperationException notReady) {
                RTP.log(Level.INFO,
                        "[NETWORK] NetworkRequestQueue for transport=" + transportType
                                + " is not yet wired; cross-server enrolment stays disabled "
                                + "(router will short-circuit fallback): " + notReady.getMessage());
                rq = null;
            } catch (Throwable t) {
                RTP.log(Level.WARNING,
                        "[NETWORK] NetworkRequestQueue open failed for transport=" + transportType
                                + ": " + t.getMessage() + " - cross-server enrolment stays disabled.", t);
                rq = null;
            }
            this.requestQueue = rq;

            // Slice C -> Slice D adapters. When the queue is non-null we
            // pipe enrolments into queue.flushPending(...) and pull status
            // from queue.pollStatus(...). When null (sql/redis not yet wired)
            // we keep the Slice C no-op sinks so the bootstrap stays
            // resilient and /rtp continues to serve locally.
            final NetworkRequestQueue qref = rq;
            java.util.function.Consumer<java.util.List<NetworkEnrolmentBuffer.EnrolmentRecord>> flushSink;
            java.util.function.Supplier<java.util.Collection<NetworkStatusCache.QueueStatus>> statusSupplier;
            if (qref != null) {
                flushSink = batch -> {
                    java.util.List<NetworkRequestQueue.EnrolmentEnvelope> envelopes =
                            new java.util.ArrayList<>(batch.size());
                    for (NetworkEnrolmentBuffer.EnrolmentRecord r : batch) {
                        envelopes.add(new NetworkRequestQueue.EnrolmentEnvelope(
                                r.playerId(), r.correlationId(), r.regionKey(),
                                r.serverHint(), r.createdAtMs()));
                    }
                    // Slice D contract: flushPending is async; we still
                    // block here (with a bounded wait) because the buffer's
                    // S-004 head-re-enqueue is driven by whether the sink
                    // throws. Any failure - timeout, transport error, queue
                    // rejection - propagates as a CompletionException and
                    // the buffer puts the batch back.
                    try {
                        NetworkRequestQueue.EnrolOutcome out = qref.flushPending(envelopes)
                                .get(2L, java.util.concurrent.TimeUnit.SECONDS);
                        if (out != NetworkRequestQueue.EnrolOutcome.ACCEPTED) {
                            throw new java.util.concurrent.CompletionException(
                                    new RuntimeException("flushPending returned " + out));
                        }
                    } catch (java.util.concurrent.CompletionException ce) {
                        throw ce;
                    } catch (Throwable err) {
                        throw new java.util.concurrent.CompletionException(err);
                    }
                };
                // Track locally-enrolled UUIDs in a thread-safe set so the
                // status poll can ask the queue specifically about "my"
                // players. The buffer doesn't expose a UUID listing
                // directly; reading from the existing statusCache snapshot
                // is the cheapest available stand-in until E2 lands a real
                // local-enrolment ledger.
                java.util.function.Supplier<java.util.List<java.util.UUID>> knownIds = () -> {
                    if (this.statusCache == null) return java.util.Collections.emptyList();
                    return new java.util.ArrayList<>(this.statusCache.snapshot().keySet());
                };
                statusSupplier = () -> {
                    java.util.List<java.util.UUID> ids = knownIds.get();
                    if (ids.isEmpty()) return java.util.Collections.emptyList();
                    java.util.List<NetworkRequestQueue.QueueStatus> rows;
                    try {
                        rows = qref.pollStatus(ids).get(2L, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (Throwable err) {
                        // S-004: propagate so the cache's pollOnce keeps
                        // the previous snapshot rather than wiping it.
                        throw new RuntimeException("pollStatus failed", err);
                    }
                    java.util.List<NetworkStatusCache.QueueStatus> out = new java.util.ArrayList<>(rows.size());
                    for (NetworkRequestQueue.QueueStatus s : rows) {
                        out.add(new NetworkStatusCache.QueueStatus(
                                s.playerId(),
                                NetworkStatusCache.QueueStatus.State.valueOf(s.state().name()),
                                s.positionInQueue(),
                                s.serverId(),
                                s.regionKey(),
                                s.updatedAtMs()));
                    }
                    return out;
                };
            } else {
                flushSink = batch -> { /* slice-D not wired for this transport */ };
                statusSupplier = java.util.Collections::emptyList;
            }

            this.enrolmentBuffer = new NetworkEnrolmentBuffer(flushSink, 0);
            this.statusCache = new NetworkStatusCache(statusSupplier);
            this.router = new NetworkRouter(
                    serverId,
                    routerMode,
                    () -> {
                        try { return selected.readSnapshot().getNow(null); }
                        catch (Throwable ignored) { return null; }
                    },
                    NetworkModeBootstrap::sumLocalKeptCount,
                    this.enrolmentBuffer::pendingDepth,
                    queueMaxDepth,
                    rps,
                    burst,
                    System::currentTimeMillis);

            long flushTicks = Math.max(1L, flushIntervalMs / 50L);
            long pollTicks  = Math.max(1L, pollIntervalMs  / 50L);
            this.enrolmentBuffer.start(flushTicks);
            this.statusCache.start(pollTicks);

            // C3 (D6 C-warn): one-shot startup audit. The transport may not
            // have assembled a non-empty snapshot yet; in that case the
            // helper logs nothing now and a future heartbeat cycle will
            // expose any overlap on the next operator invocation of
            // /rtp test network. (A scheduled re-audit is a deferred follow-up.)
            try {
                NetworkSnapshot bootSnap = selected.readSnapshot().getNow(null);
                NetworkRegionCollisionWarner.auditAndWarn(
                        bootSnap, serverId, NetworkRegionCollisionWarner.Policy.WARN);
            } catch (Throwable warnFail) {
                RTP.log(Level.FINE,
                        "[NETWORK] region-collision boot audit skipped: " + warnFail.getMessage());
            }
        } catch (Throwable t) {
            // C7: wiring is best-effort; if it fails we still leave the
            // transport / publisher / reaper / joinTriggerSource installed
            // so cross-server arrivals still redeem. Router-less means
            // /rtp stays purely local, which is the D2 default anyway.
            RTP.log(Level.WARNING,
                    "[NETWORK] router / enrolment buffer / status cache wiring failed: "
                            + t.getMessage() + " - /rtp stays local for this lifecycle.", t);
            this.router = null;
            if (this.enrolmentBuffer != null) { try { this.enrolmentBuffer.shutdown(); } catch (Throwable ignored) {} this.enrolmentBuffer = null; }
            if (this.statusCache != null) { try { this.statusCache.shutdown(); } catch (Throwable ignored) {} this.statusCache = null; }
        }

        // L6 Slice F row F1 (one-shot boot reconcile, per user-confirmed
        // sub-scope "skip steady-state pulse, do startup reconcile only"):
        // ask the transport for every active reservation (PENDING/CLAIMED,
        // expiresAtMs > now) owned by this backend's serverId and try to
        // repopulate networkReservedLocations so a cross-server arrival
        // mid-restart still finds its earmarked coord on join. On a region
        // miss we release the token with BACKEND_REJECTED so the proxy
        // stops counting the slot under networkReservedCount and the
        // reaper can recycle it. Failures here are non-fatal: the proxy
        // still owns the canonical reservation table and a missed
        // reconcile only degrades to "local pipeline serves the joining
        // player a fresh coord", which is the L2 baseline behaviour.
        try {
            reconcileNetworkReservations(selected, serverId);
        } catch (Throwable reconcileFail) {
            RTP.log(Level.WARNING,
                    "[NETWORK] boot-time reservation reconcile failed: "
                            + reconcileFail.getMessage()
                            + " - cross-server arrivals during this lifecycle "
                            + "will fall through to local /rtp.", reconcileFail);
        }

        RTP.log(Level.INFO,
                "[NETWORK] Backend network mode enabled: serverId='" + serverId
                        + "' transport=" + transportType + " intervalMs=" + intervalMs
                        + " reapIntervalMs=" + reapMs
                        + " router=" + (router == null ? "disabled" : router.mode()));
    }

    /**
     * Register the L2 join-time redeem listener with Bukkit. No-op when
     * network mode is disabled or {@link #boot(File)} did not complete
     * successfully. Idempotent within a single lifecycle: re-registering
     * the same listener via Bukkit's PluginManager would duplicate the
     * event delivery, so we guard with a sentinel field.
     */
    public void registerJoinTriggerSource(org.bukkit.plugin.Plugin plugin) {
        if (joinTriggerSource == null || plugin == null) return;
        if (joinTriggerSourceRegistered) return;
        try {
            org.bukkit.Bukkit.getPluginManager().registerEvents(joinTriggerSource, plugin);
            joinTriggerSourceRegistered = true;
            RTP.log(Level.FINE,
                    "[NETWORK] JoinTriggerSource registered for serverId='"
                            + joinTriggerSource.serverId() + "'");
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[NETWORK] failed to register JoinTriggerSource: " + t.getMessage(), t);
        }
    }

    private boolean joinTriggerSourceRegistered;

    /** Reverse-order teardown. Idempotent. */
    public void shutdown() {
        // L6 Slice D: tear down the queue after the buffer/cache stop
        // firing into it but before the transport closes (so any in-flight
        // flush/poll futures complete first).
        // L6 Slice C: tear down the router scaffolding first so its timers
        // stop firing before we yank the transport out from under them.
        if (statusCache != null) {
            try { statusCache.shutdown(); } catch (Throwable ignored) { /* best-effort */ }
            statusCache = null;
        }
        if (enrolmentBuffer != null) {
            try { enrolmentBuffer.shutdown(); } catch (Throwable ignored) { /* best-effort */ }
            enrolmentBuffer = null;
        }
        router = null;
        if (requestQueue != null) {
            try {
                if (requestQueue instanceof InMemoryNetworkRequestQueue mem) {
                    mem.shutdown();
                }
            } catch (Throwable ignored) { /* best-effort */ }
            requestQueue = null;
        }
        // Bukkit unregisters listeners automatically on plugin disable; we
        // clear the field so a subsequent boot()+register cycle starts clean.
        joinTriggerSource = null;
        joinTriggerSourceRegistered = false;
        if (reservationReaper != null) {
            try { reservationReaper.close(); } catch (Throwable ignored) { /* best-effort */ }
            reservationReaper = null;
        }
        if (publisher != null) {
            try { publisher.stop(); } catch (Throwable ignored) { /* best-effort */ }
            publisher = null;
        }
        if (transport != null) {
            try { transport.close(); } catch (Throwable ignored) { /* best-effort */ }
            transport = null;
        }
    }

    /** Visible for tests. */
    NetworkTransport transport() { return transport; }

    /** Visible for tests. */
    BackendStatePublisher publisher() { return publisher; }

    /** Visible for tests. */
    JoinTriggerSource joinTriggerSource() { return joinTriggerSource; }

    /** Visible for tests / and for the {@code /rtp} command path in Slice D. */
    public NetworkRouter router() { return router; }

    /** Visible for tests / Slice D wiring. */
    public NetworkEnrolmentBuffer enrolmentBuffer() { return enrolmentBuffer; }

    /** Visible for tests / Slice D wiring. */
    public NetworkStatusCache statusCache() { return statusCache; }

    /** Visible for tests / Slice D wiring. Null when {@code transport.type}
     *  is {@code sql} or {@code redis} (D4/D5 not yet wired). */
    public NetworkRequestQueue requestQueue() { return requestQueue; }

    /**
     * Slice D row D6 hook: open a {@link NetworkRequestQueue} matching the
     * configured transport kind. {@code in-memory} (D2) and {@code sql}
     * (D5) are wired; {@code redis} (D4) still throws
     * {@link UnsupportedOperationException} from this local helper and the
     * caller falls back to the Slice C no-op sinks. The static factory
     * {@link io.github.dailystruggle.rtp.proxy.common.transport.NetworkBindings#openRequestQueue}
     * supports all three kinds.
     */
    private static NetworkRequestQueue openRequestQueue(String transportType) {
        String t = transportType == null ? "in-memory"
                : transportType.toLowerCase(java.util.Locale.ROOT);
        switch (t) {
            case "in-memory":
            case "memory":
                return new InMemoryNetworkRequestQueue();
            case "sql": {
                // Slice D row D5: reuse the host's existing
                // AbstractSQLDatabaseAccessor pool (rtp-proxy-ADR-011
                // §HikariCP Pool Sharing). Mirrors {@link #openTransport}.
                AbstractSQLDatabaseAccessor accessor = sqlAccessorOrNull();
                if (accessor == null) {
                    throw new IllegalStateException(
                            "transport.type=sql requires an AbstractSQLDatabaseAccessor; "
                                    + "current database.yml is not SQL-backed.");
                }
                return new io.github.dailystruggle.rtp.proxy.common.transport.sql.SqlNetworkRequestQueue(
                        accessor.asDataSource());
            }
            case "redis":
                throw new UnsupportedOperationException(
                        "RedisNetworkRequestQueue is Slice D row D4; not yet wired.");
            default:
                throw new IllegalArgumentException(
                        "NetworkModeBootstrap.openRequestQueue: unrecognised transport.type '"
                                + transportType + "'.");
        }
    }

    /**
     * L6 Slice F row F1 one-shot boot reconcile. Lists every active
     * reservation token owned by this backend's {@code serverId} from the
     * shared store, then for each token tries every known region (perm +
     * temp) until {@link io.github.dailystruggle.rtp.common.selection.region.RegionQueueManager#reserveFromNetworkKept}
     * returns a non-null coordinate. On a region miss the token is released
     * with {@link io.github.dailystruggle.rtp.proxy.common.spi.ReleaseReason#BACKEND_REJECTED}
     * so the proxy stops counting the slot under {@code networkReservedCount}.
     *
     * <p>Per user-confirmed F1 sub-scope (2026-05-21): no steady-state
     * polling timer. This single pass at boot covers the only case the
     * polling pulse would have covered - a backend restarted while a
     * reservation was outstanding on the shared store but missing from
     * its in-memory {@code networkReservedLocations} map. The hot path
     * (proxy claims -> player joins -> {@code JoinTriggerSource.onRedeemed}
     * drives F2 redeem) covers steady-state correctness without a pulse.
     *
     * <p>Async, non-blocking: the future completes on the transport's
     * executor and is logged at WARNING on failure (S-004). Boot does NOT
     * wait for it; a slow shared store cannot stall the plugin start-up.
     */
    static void reconcileNetworkReservations(NetworkTransport transport, String serverId) {
        if (transport == null || serverId == null || serverId.isEmpty()) return;
        transport.listActiveForServer(serverId).whenComplete((tokens, err) -> {
            if (err != null) {
                RTP.log(Level.WARNING,
                        "[NETWORK] listActiveForServer('" + serverId + "') failed during "
                                + "boot reconcile: " + err.getMessage()
                                + " - skipping (REQ-RTP-S-004).", err);
                return;
            }
            if (tokens == null || tokens.isEmpty()) {
                RTP.log(Level.FINE,
                        "[NETWORK] boot reconcile: 0 active reservations for serverId='"
                                + serverId + "'.");
                return;
            }
            int reserved = 0;
            int released = 0;
            for (io.github.dailystruggle.rtp.proxy.common.spi.ReservationToken token : tokens) {
                java.util.UUID networkTokenId;
                try {
                    networkTokenId = java.util.UUID.fromString(token.tokenId());
                } catch (IllegalArgumentException ex) {
                    // S-004: tokenId not a UUID (older / corrupt row). Skip;
                    // the reaper will TTL it out.
                    continue;
                }
                io.github.dailystruggle.rtp.common.selection.region.RTPLocation coord = null;
                if (RTP.getInstance() != null && RTP.selectionAPI != null) {
                    for (io.github.dailystruggle.rtp.common.selection.region.Region r
                            : RTP.selectionAPI.permRegionLookup.values()) {
                        if (r == null || r.queueManager == null) continue;
                        coord = r.queueManager.reserveFromNetworkKept(networkTokenId, r.name);
                        if (coord != null) break;
                    }
                    if (coord == null) {
                        for (io.github.dailystruggle.rtp.common.selection.region.Region r
                                : RTP.selectionAPI.tempRegions.values()) {
                            if (r == null || r.queueManager == null) continue;
                            coord = r.queueManager.reserveFromNetworkKept(networkTokenId, r.name);
                            if (coord != null) break;
                        }
                    }
                }
                if (coord != null) {
                    reserved++;
                } else {
                    released++;
                    try {
                        transport.release(token.tokenId(),
                                io.github.dailystruggle.rtp.proxy.common.spi.ReleaseReason.BACKEND_REJECTED);
                    } catch (Throwable releaseErr) {
                        // S-004: a release failure must not abort the rest of
                        // the reconcile pass; subsequent tokens still process.
                        RTP.log(Level.WARNING,
                                "[NETWORK] boot reconcile: release(BACKEND_REJECTED) "
                                        + "failed for token=" + token.tokenId() + ": "
                                        + releaseErr.getMessage());
                    }
                }
            }
            RTP.log(Level.INFO,
                    "[NETWORK] boot reconcile complete: serverId='" + serverId
                            + "' totalActive=" + tokens.size()
                            + " reserved=" + reserved
                            + " released=" + released + ".");
        });
    }

    /**
     * Sum {@link io.github.dailystruggle.rtp.common.selection.region.RegionQueueManager#keptCount()}
     * across every permanent and temporary region known to
     * {@link RTP#selectionAPI}. Slice D row D6: gives the router a real
     * local-hot-queue depth instead of the Slice C placeholder
     * {@code () -> 0} so the {@code routing.mode = auto} 7-gate matrix
     * can correctly prefer local-serve when there are warm coordinates.
     */
    static int sumLocalKeptCount() {
        try {
            if (RTP.getInstance() == null || RTP.selectionAPI == null) return 0;
            long total = 0L;
            for (io.github.dailystruggle.rtp.common.selection.region.Region r
                    : RTP.selectionAPI.permRegionLookup.values()) {
                if (r == null || r.queueManager == null) continue;
                total += r.queueManager.keptCount();
            }
            for (io.github.dailystruggle.rtp.common.selection.region.Region r
                    : RTP.selectionAPI.tempRegions.values()) {
                if (r == null || r.queueManager == null) continue;
                total += r.queueManager.keptCount();
            }
            if (total > Integer.MAX_VALUE) return Integer.MAX_VALUE;
            return (int) total;
        } catch (Throwable ignored) {
            // S-004: the supplier is called inside the router's auto-mode
            // hot path; a defensive zero keeps the router falling back to
            // the network path on lookup failure rather than throwing.
            return 0;
        }
    }

    /**
     * Open the transport binding matching {@code type}. Phase 2e supports
     * {@code in-memory} (dev/test) and {@code sql} (real cross-process).
     * {@code redis} surfaces a clear "not yet" message rather than failing
     * silently.
     */
    private NetworkTransport openTransport(String type, long intervalMs, RtpYamlSection transportSec) {
        String t = type == null ? "in-memory" : type.toLowerCase(java.util.Locale.ROOT);
        switch (t) {
            case "in-memory":
            case "memory":
                return new InMemoryNetworkStateBinding();
            case "sql": {
                // Reuse the existing AbstractSQLDatabaseAccessor pool via asDataSource()
                // - REQ-RTP-PROXY-COMMON-010 / ADR-011 §HikariCP Pool Sharing (Q2).
                AbstractSQLDatabaseAccessor accessor = sqlAccessorOrNull();
                if (accessor == null) {
                    throw new IllegalStateException(
                            "transport.type=sql requires an AbstractSQLDatabaseAccessor; "
                                    + "current database.yml is not SQL-backed.");
                }
                return new SqlNetworkStateBinding(accessor.asDataSource(), intervalMs);
            }
            case "redis": {
                // Phase 2e-Redis A1: heartbeats + snapshot + pub/sub fan-out.
                // Atomic claim / HMAC / reconnect-hardening land in A2-A4.
                RtpYamlSection redis = transportSec == null ? null : transportSec.getConfigurationSection("redis");
                String host = redis == null ? "localhost" : redis.getString("host", "localhost");
                int port = redis == null ? 6379 : redis.getInt("port", 6379);
                String password = redis == null ? null : redis.getString("password", null);
                return new RedisNetworkStateBinding(host, port, password, intervalMs);
            }
            default:
                throw new IllegalArgumentException("Unrecognised transport.type='" + type + "'");
        }
    }

    private static AbstractSQLDatabaseAccessor sqlAccessorOrNull() {
        try {
            RTP r = RTP.getInstance();
            if (r != null && r.databaseAccessor instanceof AbstractSQLDatabaseAccessor sql) {
                return sql;
            }
        } catch (Throwable ignored) {
            // Defensive.
        }
        return null;
    }

    /**
     * Copy the bundled {@code network.yml} resource into the plugin data
     * folder if it does not already exist. Mirrors the convention used by
     * other RTP config files; idempotent.
     */
    public static File ensureNetworkYml(File pluginDataFolder, Class<?> resourceOwner) {
        File target = new File(pluginDataFolder, "network.yml");
        if (target.isFile()) return target;
        try (InputStream in = resourceOwner.getClassLoader().getResourceAsStream("network.yml")) {
            if (in == null) return target;
            //noinspection ResultOfMethodCallIgnored
            pluginDataFolder.mkdirs();
            Files.copy(in, target.toPath());
        } catch (IOException e) {
            RTP.log(Level.WARNING,
                    "[NETWORK] failed to write default network.yml: " + e.getMessage(), e);
        }
        return target;
    }

    /**
     * Trivial adapter: lets the existing
     * {@link io.github.dailystruggle.rtp.common.network.NetworkStateBinding}
     * slot on {@code AbstractSQLDatabaseAccessor} hold a reference to the
     * live transport. The interface's {@code transport()} default returns
     * {@code null}; this override exposes the delegate to consumers such
     * as {@code NetworkSimulationTestJob} ({@code rtp test network}).
     */
    private static final class BindingAdapter
            implements io.github.dailystruggle.rtp.common.network.NetworkStateBinding {
        private final NetworkTransport delegate;
        BindingAdapter(NetworkTransport delegate) { this.delegate = delegate; }
        @Override
        public NetworkTransport transport() { return delegate; }
    }
}
