package io.github.dailystruggle.rtp.common.network;

import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.server.PlayerLifecycleHook;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection;
import io.github.dailystruggle.rtp.common.database.options.AbstractSQLDatabaseAccessor;
import io.github.dailystruggle.rtp.proxy.common.selector.LoadBalancerConfig;
import io.github.dailystruggle.rtp.proxy.common.selector.LoadBalancerConfigYaml;
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

    /**
     * Most-recently-booted instance, or {@code null} when network mode is
     * disabled / not yet booted / has been shut down. Set as a side effect
     * of a successful {@link #boot(File)} that reaches command-hook wiring,
     * cleared by {@link #shutdown()}. Read by callers outside the bootstrap
     * (e.g. {@code RTPCmdBukkit}'s {@code RegionParameter} validator) that
     * need the live {@link PeerRegionRegistry} but cannot hold a direct
     * field reference because they were constructed before the bootstrap
     * finished. Volatile so the publish happens-before relationship is
     * tight.
     *
     * @since rtp-proxy-ADR-014
     */
    public static volatile NetworkModeBootstrap LIVE;

    private NetworkTransport transport;
    private BackendStatePublisher publisher;
    private ReservationTokenReaper reservationReaper;
    private JoinTriggerSource joinTriggerSource;
    // Backend-side router + dirty-write enrolment buffer + read-side status cache.
    // All three live only while network mode is enabled and boot() has fully succeeded.
    private NetworkRouter router;
    private NetworkEnrolmentBuffer enrolmentBuffer;
    private NetworkStatusCache statusCache;
    // Cross-server request queue SPI. Only the
    // in-memory binding is wired (D4/D5 still pending); for any other
    // transport kind this stays null and the no-op sinks remain.
    private NetworkRequestQueue requestQueue;
    // Peer-region registry view over the
    // cached snapshot + Bukkit-platform NetworkCommandHook installed into
    // RTP.networkCommandHook. Null when network mode is disabled or
    // boot() did not complete past router wiring.
    private PeerRegionRegistry peerRegionRegistry;
    private BukkitNetworkCommandHook commandHook;
    // ADR-015 / REQ-RTP-NET-015: lobby-side waitlist UX.
    // - notifier: periodic player-facing 'queued, position N' message.
    // - waitlistGuard: sender-check predicate registered on RTPCmdBukkit
    //   so /rtp* is locked while the player has a non-terminal entry.
    // - quitListener: PlayerQuitEvent hook that calls
    //   NetworkRequestQueue.cancel(uuid, PLAYER_DISCONNECT) so a quit
    //   does not leave a stale slot on the shared waitlist.
    private NetworkWaitlistNotifier waitlistNotifier;
    private NetworkWaitlistGuard waitlistGuard;
    private NetworkWaitlistQuitListener waitlistQuitListener;
    private boolean waitlistQuitListenerRegistered;
    /**
     * Lobby-side auto-retry queue (fix for the lobby
     * "non-processing state" symptom). Non-null only when network mode
     * is enabled, boot() reached router wiring, AND the local
     * {@code network.routing.lobbyMode} is true. See
     * {@link LobbyDispatchRetryQueue}.
     */
    private LobbyDispatchRetryQueue lobbyRetryQueue;

    // Snapshot refresher (fix 2026-05-21): NetworkTransport.readSnapshot() is
    // async (Redis SCAN, SQL poll, etc.) and the previous suppliers used
    // future.getNow(null), which returns null on the calling thread until
    // the async completes. That meant PeerRegionRegistry.peerEntries() and
    // NetworkRouter's snapshot lookup almost always observed a null snapshot,
    // so tab-completion never surfaced peer 'server:region' entries even
    // though heartbeats were landing in Redis correctly. We now refresh a
    // volatile cached snapshot on a small dedicated scheduler at the same
    // cadence as the heartbeat publisher; both suppliers read the cache
    // synchronously without blocking the calling thread.
    private volatile NetworkSnapshot cachedSnapshot;
    // Platform-scheduler task handle for the periodic snapshot refresh.
    // Stored so shutdown() can cancel via RTP.scheduler.cancelTask.
    private Object snapshotRefreshTask;
    // When transport.type=auto resolves to the plugin-message
    // tier, the auto-detect state machine is stored here so boot() can pump it
    // (tick + carrier-availability re-probe) on RTP.scheduler and shutdown()
    // can cancel the pump. Null for every other transport.type.
    private io.github.dailystruggle.rtp.common.network.pluginmessage.ProxyAutoDetector autoDetector;
    // The bridge shared by the auto-detector and the plugin-message binding;
    // retained so the pump can check carrier availability without building a
    // second bridge (which would re-register the channel/listener).
    private io.github.dailystruggle.rtp.common.network.pluginmessage.NetworkBridge autoDetectorBridge;
    // Platform-scheduler task handle for the periodic auto-detector pump.
    private Object autoDetectorTask;

    /**
     * Run boot logic. Idempotent for the disabled-mode path; calling
     * {@link #boot(File)} twice with {@code enabled:false} is a no-op.
     * Calling {@code boot} twice with {@code enabled:true} is undefined -
     * the host (RTPBukkitPlugin) only invokes once per lifecycle.
     *
     * <p>L2 of {@code CHECKLIST-cross-server-rtp.md}: when the transport
     * opens successfully the host should call {@link #registerJoinTriggerSource()}
     * after platform startup so cross-server arrivals are redeemed at
     * player-join time. The bootstrap stores the resolved serverId for that
     * follow-up call.</p>
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
        // The plugin-message tier ages a cached peer heartbeat out of its
        // snapshot after this window (its "stale" timeout). This reuses the
        // pre-existing heartbeat.staleAfterMs knob that the SQL poll loop
        // already honours - there is no separate transport.pluginMessage
        // staleness key - so one value drives staleness across all tiers.
        long staleAfterMs = heartbeat == null ? 5000L : heartbeat.getLong("staleAfterMs", 5000L);
        if (staleAfterMs <= 0) staleAfterMs = 5000L;

        // Reservation-token TTL reaper cadence (REQ-RTP-NET-011). Mirrors
        // NetworkConfig.reservationReapIntervalMs() default + clamping.
        RtpYamlSection reservation = cfg.getConfigurationSection("reservation");
        long reapMs = reservation == null ? 30_000L : reservation.getLong("reapIntervalMs", 30_000L);
        if (reapMs <= 0) reapMs = 30_000L;

        // HMAC envelope wiring (REQ-RTP-PROXY-007 / ADR-010): load the shared
        // HMAC verifier the same way NetworkBindings.open does on the Velocity
        // proxy. Without this the backend constructs RedisNetworkStateBinding
        // with verifier=null and publishes UNSIGNED heartbeat rows; the proxy
        // (which DOES configure a verifier via NetworkBindings.open) then
        // rejects every row with "decodeBackend: HMAC verification failed".
        // Symmetric verifier wiring is required for cross-server `/rtp` to work.
        String secretEnv = network.getString("secretEnv", "RTP_NET_SECRET");
        int schemaVersion = (int) network.getLong("schemaVersion", 1L);
        io.github.dailystruggle.rtp.proxy.common.security.HmacVerifier verifier = null;
        try {
            verifier = io.github.dailystruggle.rtp.proxy.common.security.HmacVerifier
                    .loadFromEnv(secretEnv, schemaVersion, schemaVersion);
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[NETWORK] HMAC verifier load failed (secretEnv='" + secretEnv
                            + "'): " + t.getMessage()
                            + " - heartbeat rows will be unsigned and the proxy "
                            + "will reject them. Set the env var to a 32+ byte secret.", t);
        }

        NetworkTransport selected;
        try {
            selected = openTransport(transportType, intervalMs, staleAfterMs, transportSec, verifier, schemaVersion);
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

        // Read routing.lobbyMode early so the sampler can be
        // constructed with the right advertisement profile from heartbeat-tick 1.
        // Re-read inside the router-wiring try-block below for the command-hook
        // ctor; the two reads are intentionally separated by the publisher
        // start so even a partial wiring failure still produces correctly-
        // suppressed heartbeats.
        boolean lobbyMode = false;
        try {
            RtpYamlSection routingPre = cfg.getConfigurationSection("routing");
            if (routingPre != null) {
                lobbyMode = routingPre.getBoolean("lobbyMode", false);
            }
        } catch (Throwable ignored) {
            // Defensive: malformed routing section degrades to non-lobby.
        }

        java.util.function.Function<Boolean, BackendStateSampler> samplerFactory =
                RTP.backendStateSamplerFactory;
        if (samplerFactory == null) {
            // ADR-049: each backend platform adapter installs a sampler
            // factory during startup. Without one we cannot publish
            // heartbeats, so abort the network boot for this lifecycle
            // (S-004: log, never silently no-op). Real Bukkit / Paper /
            // Folia / Fabric adapters always install the factory; this
            // branch only fires on a misconfigured embedding.
            RTP.log(Level.WARNING,
                    "[NETWORK] no BackendStateSampler factory installed by the platform"
                            + " adapter; network mode requires one - aborting network boot"
                            + " for this lifecycle (serverId='" + serverId + "').");
            return;
        }
        BackendStateSampler sampler = samplerFactory.apply(lobbyMode);
        BackendStatePublisher pub = new BackendStatePublisher(selected, sampler, serverId, intervalMs);
        pub.start();

        // Fix 2026-05-21: start the cached-snapshot refresher BEFORE wiring
        // PeerRegionRegistry / NetworkRouter so the first tab-complete call
        // already has a non-null snapshot to read (modulo the initial
        // intervalMs delay before the first refresh tick lands). Kicks off
        // an initial async refresh immediately so we are not waiting a full
        // intervalMs for the first cache fill.
        // Use RTP.scheduler (Folia-safe async path) instead of a raw
        // ScheduledExecutorService. Period is converted from ms to server
        // ticks (1 tick == 50 ms), clamped to >=1 tick.
        final NetworkTransport refresherTransport = selected;
        Runnable refresh = () -> {
            try {
                NetworkSnapshot snap = refresherTransport.readSnapshot()
                        .get(2000L, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (snap != null) this.cachedSnapshot = snap;
            } catch (Throwable ignored) {
                // Defensive: a flaky transport must not crash the refresher.
            }
        };
        long refreshTicks = Math.max(1L, intervalMs / 50L);
        // Initial fill so we are not waiting a full intervalMs for the first cache value.
        RTP.scheduler.runTaskAsynchronously(refresh);
        this.snapshotRefreshTask =
                RTP.scheduler.runTaskTimerAsynchronously(refresh, refreshTicks, refreshTicks);

        // Pump the auto-detect state machine for
        // transport.type=auto on the plugin-message tier. The detector is
        // platform-neutral and starts no threads itself (guideline Scheduler
        // Usage); we drive it from RTP.scheduler. Each pulse:
        //   - drives onCarrierAvailable() once a carrier player exists (this
        //     fires the active GetServer/GetServers handshake from ARMED, and
        //     re-probes from DISABLED so a server later attached to a proxy
        //     picks the network up without a restart - decision §9.5), and
        //   - calls tick() to time out an over-due handshake back to standalone.
        // The plugin-message binding gossips only when a carrier is online
        // regardless of detector state, so this pump adds topology discovery
        // and the re-probe lifecycle on top of an already-functional tier.
        final io.github.dailystruggle.rtp.common.network.pluginmessage.ProxyAutoDetector detector =
                this.autoDetector;
        final io.github.dailystruggle.rtp.common.network.pluginmessage.NetworkBridge probeBridge =
                this.autoDetectorBridge;
        if (detector != null) {
            Runnable pump = () -> {
                try {
                    if (detector.state()
                            != io.github.dailystruggle.rtp.common.network.pluginmessage
                                    .ProxyAutoDetector.State.ENABLED) {
                        // Only spend a handshake when a carrier player can
                        // actually carry the plugin message.
                        if (probeBridge == null
                                || probeBridge.anyOnlinePlayer().isPresent()) {
                            detector.onCarrierAvailable();
                        }
                    }
                    detector.tick();
                } catch (Throwable ignored) {
                    // Defensive: a flaky bridge must not crash the pump.
                }
            };
            this.autoDetectorTask =
                    RTP.scheduler.runTaskTimerAsynchronously(pump, refreshTicks, refreshTicks);
        }

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
        // proxy-direct is now a remote view of the proxy store (PROPOSAL-
        // proxy-direct-as-remote-store): the standard JoinTriggerSource reads
        // the reservation via findReservation/redeem RPC, so no dedicated
        // proxy-direct arrival listener is needed.
        this.joinTriggerSource = new JoinTriggerSource(selected, serverId);

        // Wire router + enrolment buffer + status cache.
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

            // Build the cross-server queue alongside the
            // transport. Today only the in-memory kind is wired; for sql /
            // redis the factory throws UnsupportedOperationException and we
            // fall through to the no-op sinks (the wiring catch
            // block below picks it up). Failure of openRequestQueue is NOT
            // fatal - the rest of network mode (heartbeat publish, snapshot
            // read, join redeem) keeps working.
            NetworkRequestQueue rq;
            try {
                rq = openRequestQueue(transportType, transportSec);
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
            // proxy-direct (PROPOSAL-proxy-direct-as-remote-store): the
            // backend's request queue is a thin RPC client of the proxy's
            // in-memory queue, riding the same outbound socket as the
            // transport. openRequestQueue returns null for this tier, so build
            // the remote client here from the already-open
            // ProxyDirectNetworkBinding. The standard qref!=null flush/poll
            // wiring below then lights up unchanged.
            if (rq == null
                    && this.transport instanceof io.github.dailystruggle.rtp.common.network.direct.ProxyDirectNetworkBinding pdb) {
                rq = new io.github.dailystruggle.rtp.common.network.direct.ProxyDirectNetworkRequestQueue(pdb);
                RTP.log(Level.INFO,
                        "[NETWORK] proxy-direct: request queue is a remote view of the proxy store "
                                + "(enrolment + status ride the proxy-direct socket).");
            }
            this.requestQueue = rq;

            // When the queue is non-null we
            // pipe enrolments into queue.flushPending(...) and pull status
            // from queue.pollStatus(...). When null (sql/redis not yet wired)
            // we keep the no-op sinks so the bootstrap stays
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
                    // flushPending is async; we still
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
            // Wire the status cache into the join-time trigger so the
            // post-arrival /rtp dispatch evicts the (still non-terminal)
            // lobby-seeded status row before NetworkWaitlistGuard sees it
            // (REQ-RTP-S-004 / REQ-RTP-NET-015 - without this, a successful
            // cross-server redeem is followed by msgAlreadyQueued and no
            // teleport).
            if (this.joinTriggerSource != null) {
                this.joinTriggerSource.setStatusCache(this.statusCache);
            }
            this.router = new NetworkRouter(
                    serverId,
                    routerMode,
                    () -> this.cachedSnapshot,
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

            // Cross-server terminal-state release of the local
            // processingPlayers lock. The lock is acquired by RTPCmd.onCommand
            // on every /rtp and intentionally RETAINED by the CrossServer
            // branch (anti-spam: prevents the player re-enrolling on every
            // keystroke). It is therefore the job of the lobby to release
            // the lock once the proxy reports COMPLETED / FAILED / CANCELLED
            // or stops reporting the row entirely (eviction). Without this
            // release the player gets a one-shot "queued" message and is
            // then permanently in the alreadyTeleporting state until they
            // disconnect - which is exactly the symptom this hook fixes.
            // Also clears any LobbyDispatchRetryQueue entry for the player
            // (defence in depth; the retry queue normally clears itself on
            // enrolment).
            this.statusCache.setTerminalListener((uuid, terminalState) -> {
                // Stuck-after-enrolment auto-retry: when the proxy never
                // produces a terminal transition the sticky-TTL in
                // NetworkStatusCache fires terminalState=FAILED. If we
                // remembered the player's original /rtp args on the
                // retry queue (recordEnrolment from the hook), re-park
                // them for a fresh retry pulse INSTEAD of releasing the
                // lock. For genuine terminal transitions (COMPLETED,
                // supplier-confirmed FAILED/CANCELLED, eviction), we
                // clear the recorded enrolment and release the lock as
                // before. This is the user-approved retry mechanism for
                // the "queue position 0 forever" symptom.
                boolean reparked = false;
                if (this.lobbyRetryQueue != null
                        && terminalState == NetworkStatusCache.QueueStatus.State.FAILED) {
                    try {
                        reparked = this.lobbyRetryQueue.onTerminalFailure(uuid);
                    } catch (Throwable ignored) {
                        reparked = false;
                    }
                }
                if (reparked) {
                    // Re-seed the sticky row so anti-spam guards still
                    // see the player as non-terminal during the retry
                    // pulse. The next pulse will either re-enrol (new
                    // sticky window starts) or terminate via attempts/
                    // TTL bounds inside the retry queue.
                    try { this.statusCache.seedLocal(uuid); } catch (Throwable ignored) {}
                    return;
                }
                try {
                    RTP.getInstance().processingPlayers.remove(uuid);
                } catch (Throwable ignored) {
                    // RTP.getInstance() should never be null here, but the
                    // listener must never propagate.
                }
                if (this.lobbyRetryQueue != null) {
                    try { this.lobbyRetryQueue.cancel(uuid, null); } catch (Throwable ignored) {}
                }
            });

            // ADR-015 / REQ-RTP-NET-015: waitlist UX.
            // Knobs live under network.waitlist.* with conservative defaults:
            //   notifyIntervalSeconds: 5  (min re-notify gap per UUID)
            // The notifier pulse uses the same pollIntervalMs as the status
            // cache so we never notify ahead of a position change. The
            // guard + quitListener are no-ops when the cache is empty, so
            // they cost nothing on single-server installs that still go
            // through this code path (network.enabled is already true here).
            RtpYamlSection waitlistSec = cfg.getConfigurationSection("network.waitlist");
            long notifyIntervalMs = waitlistSec == null
                    ? 5_000L
                    : Math.max(0L, waitlistSec.getLong("notifyIntervalSeconds", 5L) * 1000L);
            this.waitlistNotifier = new NetworkWaitlistNotifier(this.statusCache, notifyIntervalMs);
            this.waitlistNotifier.start(pollTicks);
            this.waitlistGuard = new NetworkWaitlistGuard(this.statusCache);

            // Lobby auto-retry queue. Instantiated BEFORE
            // the quit listener (so disconnect can clear the parked
            // entry) and BEFORE the command hook (so transient
            // LocalFallbacks park instead of falling through to a dead
            // local pipeline). Lobby-only by design - on a normal
            // backend the local pipeline can genuinely serve /rtp, so
            // the prior graceful-degradation behaviour is preserved.
            if (lobbyMode) {
                this.lobbyRetryQueue = new LobbyDispatchRetryQueue(
                        this.router, this.enrolmentBuffer);
                this.lobbyRetryQueue.start();
            }

            if (rq != null) {
                this.waitlistQuitListener = new NetworkWaitlistQuitListener(rq, this.lobbyRetryQueue);
            }

            // rtp-proxy-ADR-014: install the cross-server
            // command hook into RTP.networkCommandHook so /rtp consults
            // the router on every invocation. The hook also drives
            // RegionParameter's extras supplier (the snapshot-adapter
            // PeerRegionRegistry) so tab-completion lists peer-qualified
            // server:region entries.
            // Parse the operator-tuned loadBalancer scoring table from
            // network.yml so PeerRegionRegistry.pickMostKept() and the
            // proxy-side selector share one curves/weights definition
            // (rtp-proxy-ADR-004 *Region-Pair Scoring*). Malformed config
            // degrades to LoadBalancerConfig.defaults() with a WARNING
            // rather than disabling lobby mode - the bundled defaults
            // already produce the prior "most-kept wins" behaviour.
            LoadBalancerConfig loadBalancerConfig;
            try {
                RtpYamlSection lbSec = cfg.getConfigurationSection("loadBalancer");
                if (lbSec == null) {
                    loadBalancerConfig = LoadBalancerConfig.defaults();
                } else {
                    loadBalancerConfig = LoadBalancerConfigYaml.fromMap(lbSec.getValues(true));
                }
            } catch (Throwable lbFail) {
                RTP.log(Level.WARNING,
                        "[NETWORK] failed to parse network.yml::loadBalancer: "
                                + lbFail.getMessage()
                                + " - falling back to bundled defaults (legacy most-kept behaviour).",
                        lbFail);
                loadBalancerConfig = LoadBalancerConfig.defaults();
            }
            this.peerRegionRegistry = new PeerRegionRegistry(
                    () -> this.cachedSnapshot,
                    serverId,
                    loadBalancerConfig);
            // Option 1 (plugin-message tier): seed cross-server destinations
            // from the proxy's GetServers topology that the `auto` resolver
            // discovers. On the DB-free transport a single player cannot carry
            // heartbeat gossip off a player-empty backend, so the snapshot is
            // often empty even though the proxy knows every backend; feeding
            // the discovered server list lets /rtp suggest and accept
            // `server:region` destinations (availability UNKNOWN -> the
            // destination decides on arrival). Durable Redis/SQL tiers leave
            // the detector null and rely purely on heartbeats.
            this.peerRegionRegistry.setTopologyPeerSupplier(() -> {
                io.github.dailystruggle.rtp.common.network.pluginmessage.ProxyAutoDetector d =
                        this.autoDetector;
                return d == null ? java.util.Set.of() : d.peerServerIds();
            });
            // Pass lobbyMode through so a no-arg /rtp on a
            // lobby backend auto-routes to the "most kept" remote region.
            // Pass the backend state publisher so
            // cross-server dispatches force an immediate heartbeat publish
            // (propagating the local PeerRegionRegistry.recordDispatch
            // decrement to peers before the next 1s tick).
            // Pass the LobbyDispatchRetryQueue so transient
            // LocalFallbacks (NETWORK_DISABLED / NO_LIVE_PEER /
            // QUEUE_FULL / TOKEN_BUCKET_EXHAUSTED) park the player on
            // the local retry pulse instead of silently falling through
            // to a dead local pipeline.
            this.commandHook = new BukkitNetworkCommandHook(
                    this.router, this.enrolmentBuffer,
                    this.peerRegionRegistry, lobbyMode,
                    this.publisher, this.lobbyRetryQueue);
            // Wire the enrolment seeder so real cross-server enrolments
            // pre-seed a sticky QUEUED row in the status cache. This is
            // what guarantees the lobby observes a non-terminal -> terminal
            // (or sticky-TTL expiration) transition for every enrolled
            // player, which in turn releases the processingPlayers lock.
            // Without this seeding, a never-responding proxy leaves the
            // player locked until disconnect.
            this.commandHook.setEnrolmentSeeder(this.statusCache::seedLocal);
            RTP.networkCommandHook = this.commandHook;
            // Publish self so RTPCmdBukkit's RegionParameter validator and
            // extras supplier can pick up the live PeerRegionRegistry at
            // call time. Single-process / single-bootstrap invariant: only
            // one live instance per JVM (the static field is volatile so
            // the publish happens-before any subsequent read).
            LIVE = this;

            // C3 (D6 C-warn): one-shot startup audit. The transport may not
            // have assembled a non-empty snapshot yet; in that case the
            // helper logs nothing now and a future heartbeat cycle will
            // expose any overlap on the next operator invocation of
            // /rtp test network. (A scheduled re-audit is a deferred follow-up.)
            try {
                NetworkSnapshot bootSnap = selected.readSnapshot()
                        .get(2000L, java.util.concurrent.TimeUnit.MILLISECONDS);
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
            if (this.waitlistNotifier != null) { try { this.waitlistNotifier.shutdown(); } catch (Throwable ignored) {} this.waitlistNotifier = null; }
            if (this.lobbyRetryQueue != null) { try { this.lobbyRetryQueue.shutdown(); } catch (Throwable ignored) {} this.lobbyRetryQueue = null; }
            this.waitlistGuard = null;
            this.waitlistQuitListener = null;
            if (this.statusCache != null) { try { this.statusCache.shutdown(); } catch (Throwable ignored) {} this.statusCache = null; }
            // H2: unwind the hook so single-server pipeline is restored.
            if (RTP.networkCommandHook == this.commandHook) {
                RTP.networkCommandHook = io.github.dailystruggle.rtp.api.network.NetworkCommandHook.LOCAL_ONLY;
            }
            this.commandHook = null;
            this.peerRegionRegistry = null;
            if (LIVE == this) LIVE = null;
        }

        // One-shot boot reconcile (per
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
    public void registerJoinTriggerSource() {
        if (joinTriggerSource == null) return;
        if (joinTriggerSourceRegistered) return;
        try {
            PlayerLifecycleHook hook = (RTP.serverAccessor != null)
                    ? RTP.serverAccessor.getPlayerLifecycleHook() : null;
            if (hook == null) return;
            joinTriggerSource.register(hook);
            joinTriggerSourceRegistered = true;
            RTP.log(Level.FINE,
                    "[NETWORK] JoinTriggerSource registered for serverId='"
                            + joinTriggerSource.serverId() + "'");
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[NETWORK] failed to register JoinTriggerSource: " + t.getMessage(), t);
        }
    }

    /**
     * ADR-015 / REQ-RTP-NET-015: register the cross-server
     * waitlist player-quit hook so a quit cancels any in-flight
     * enrolment. No-op when network mode is disabled or when boot() did not
     * reach queue wiring. Idempotent.
     */
    public void registerWaitlistQuitListener() {
        if (waitlistQuitListener == null) return;
        if (waitlistQuitListenerRegistered) return;
        try {
            PlayerLifecycleHook hook = (RTP.serverAccessor != null)
                    ? RTP.serverAccessor.getPlayerLifecycleHook() : null;
            if (hook == null) return;
            waitlistQuitListener.register(hook);
            waitlistQuitListenerRegistered = true;
            RTP.log(Level.FINE,
                    "[NETWORK] NetworkWaitlistQuitListener registered.");
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[NETWORK] failed to register NetworkWaitlistQuitListener: "
                            + t.getMessage(), t);
        }
    }

    /**
     * ADR-015 / REQ-RTP-NET-015: register the cross-server
     * waitlist sender-check predicate on the live {@code RTPCmdBukkit} so
     * any {@code /rtp*} invocation by a player with a non-terminal entry
     * is short-circuited with a configurable message. No-op when network
     * mode is disabled or boot() did not reach status-cache wiring.
     */
    public java.util.function.Predicate<RTPCommandSender> waitlistCommandGuard() {
        return waitlistGuard;
    }

    private boolean joinTriggerSourceRegistered;

    /** Reverse-order teardown. Idempotent. */
    public void shutdown() {
        // Unwire the global command hook FIRST so any in-flight
        // /rtp call from this point on takes the local-only pipeline. This
        // must come before the enrolment buffer / router teardown so a
        // late command thread doesn't NPE on a half-shut router.
        if (RTP.networkCommandHook == this.commandHook) {
            RTP.networkCommandHook = io.github.dailystruggle.rtp.api.network.NetworkCommandHook.LOCAL_ONLY;
        }
        this.commandHook = null;
        this.peerRegionRegistry = null;
        if (LIVE == this) LIVE = null;
        // Tear down the queue after the buffer/cache stop
        // firing into it but before the transport closes (so any in-flight
        // flush/poll futures complete first).
        // Tear down the router scaffolding first so its timers
        // stop firing before we yank the transport out from under them.
        if (waitlistNotifier != null) {
            try { waitlistNotifier.shutdown(); } catch (Throwable ignored) { /* best-effort */ }
            waitlistNotifier = null;
        }
        if (lobbyRetryQueue != null) {
            try { lobbyRetryQueue.shutdown(); } catch (Throwable ignored) { /* best-effort */ }
            lobbyRetryQueue = null;
        }
        waitlistGuard = null;
        // ADR-049: explicitly close the PlayerLifecycleHook subscription
        // (the platform no longer auto-unregisters a Bukkit Listener for us);
        // then clear the field so a subsequent boot()+register cycle is clean.
        if (waitlistQuitListener != null) {
            try { waitlistQuitListener.unregister(); } catch (Throwable ignored) { /* best-effort */ }
        }
        waitlistQuitListener = null;
        waitlistQuitListenerRegistered = false;
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
        // ADR-049: explicitly close the PlayerLifecycleHook subscriptions
        // (the platform no longer auto-unregisters a Bukkit Listener for us);
        // then clear the field so a subsequent boot()+register cycle is clean.
        if (joinTriggerSource != null) {
            try { joinTriggerSource.unregister(); } catch (Throwable ignored) { /* best-effort */ }
        }
        joinTriggerSource = null;
        joinTriggerSourceRegistered = false;
        if (reservationReaper != null) {
            try { reservationReaper.close(); } catch (Throwable ignored) { /* best-effort */ }
            reservationReaper = null;
        }
        if (snapshotRefreshTask != null) {
            try { RTP.scheduler.cancelTask(snapshotRefreshTask); }
            catch (Throwable ignored) { /* best-effort */ }
            snapshotRefreshTask = null;
        }
        // Stop pumping the auto-detect state machine.
        if (autoDetectorTask != null) {
            try { RTP.scheduler.cancelTask(autoDetectorTask); }
            catch (Throwable ignored) { /* best-effort */ }
            autoDetectorTask = null;
        }
        autoDetector = null;
        autoDetectorBridge = null;
        cachedSnapshot = null;
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

    /** Visible for tests and for the {@code /rtp} command path. */
    public NetworkRouter router() { return router; }

    /** Visible for tests. */
    public NetworkEnrolmentBuffer enrolmentBuffer() { return enrolmentBuffer; }

    /** Visible for tests. */
    public NetworkStatusCache statusCache() { return statusCache; }

    /** Visible for tests. Null when {@code transport.type}
     *  is {@code sql} or {@code redis} and the queue is not yet wired. */
    public NetworkRequestQueue requestQueue() { return requestQueue; }

    /** Visible for tests / {@code RTPCmdBukkit} validator wiring. Null
     *  when network mode is disabled or wiring failed. */
    public PeerRegionRegistry peerRegionRegistry() { return peerRegionRegistry; }

    /** Visible for tests. Null when network mode is disabled or
     *  wiring failed; otherwise also installed at {@code RTP.networkCommandHook}. */
    public BukkitNetworkCommandHook commandHook() { return commandHook; }

    /**
     * Open a {@link NetworkRequestQueue} matching the
     * configured transport kind. {@code in-memory}, {@code sql},
     * and {@code redis} are all wired. The static factory
     * {@link io.github.dailystruggle.rtp.proxy.common.transport.NetworkBindings#openRequestQueue}
     * is the canonical entry point for vendor-free callers; this local
     * helper mirrors its dispatch but sources connection settings from
     * the backend's {@code network.yml} {@code transport} section (the
     * same one {@link #openTransport} reads) so a single config drives
     * both the state binding and the request queue.
     */
    private static NetworkRequestQueue openRequestQueue(String transportType, RtpYamlSection transportSec) {
        String t = transportType == null ? "in-memory"
                : transportType.toLowerCase(java.util.Locale.ROOT);
        switch (t) {
            case "in-memory":
            case "memory":
                return new InMemoryNetworkRequestQueue();
            case "sql": {
                // SQL: reuse the host's existing
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
            case "redis": {
                // Redis: source host/port/password from the same
                // {@code transport.redis.*} section that {@link #openTransport}
                // reads for the state binding, so the queue and binding both
                // connect to the same Redis instance from one config block.
                // ttlSeconds = 0 disables EXPIRE - terminal transitions in
                // the D3 atomic-claim scripts already delete per-envelope and
                // per-status HASHes (mirrors {@code NetworkBindings.openRequestQueue}).
                RtpYamlSection redis = transportSec == null
                        ? null : transportSec.getConfigurationSection("redis");
                String host = redis == null ? "localhost" : redis.getString("host", "localhost");
                int port = redis == null ? 6379 : redis.getInt("port", 6379);
                String password = redis == null ? null : redis.getString("password", null);
                return new io.github.dailystruggle.rtp.proxy.common.transport.redis.RedisNetworkRequestQueue(
                        host, port, password, 0);
            }
            case "proxy-direct":
            case "proxydirect":
            case "proxy-cache":
            case "plugin-message":
            case "pluginmessage":
            case "auto":
                // DB-free, player-INDEPENDENT discovery-only tiers
                // (rtp-proxy-ADR-017 proxy-direct, the plugin-message /
                // proxy-cache tiers, and auto). These converge cross-server
                // region NAMES only and have no shared mutable request store,
                // so there is no cross-server enrolment queue to open. Return
                // null (not an error): the router short-circuits to local
                // fallback and /rtp keeps serving locally.
                return null;
            default:
                throw new IllegalArgumentException(
                        "NetworkModeBootstrap.openRequestQueue: unrecognised transport.type '"
                                + transportType + "'.");
        }
    }

    /**
     * One-shot boot reconcile. Lists every active
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
     * {@link RTP#selectionAPI}. Gives the router a real
     * local-hot-queue depth instead of the placeholder
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

    /** Default TCP port for the {@code proxy-direct} transport (rtp-proxy-ADR-017). */
    private static final int PROXY_DIRECT_DEFAULT_PORT = 25599;

    /**
     * Open the transport binding matching {@code type}. Supports
     * {@code in-memory} (dev/test) and {@code sql} (real cross-process).
     * {@code redis} surfaces a clear "not yet" message rather than failing
     * silently.
     */
    private NetworkTransport openTransport(String type, long intervalMs, long staleAfterMs,
                                           RtpYamlSection transportSec,
                                           io.github.dailystruggle.rtp.proxy.common.security.HmacVerifier verifier,
                                           int schemaVersion) {
        String t = type == null ? "in-memory" : type.toLowerCase(java.util.Locale.ROOT);
        switch (t) {
            case "in-memory":
            case "memory":
                return new InMemoryNetworkStateBinding();
            case "plugin-message":
            case "pluginmessage": {
                // Tier-1, DB-free default (rtp-proxy-ADR-016). Requires a
                // platform-installed NetworkBridge; absence is a clear error
                // (the operator explicitly asked for plugin-message).
                io.github.dailystruggle.rtp.common.network.pluginmessage.NetworkBridge bridge =
                        openNetworkBridgeOrNull();
                if (bridge == null) {
                    throw new IllegalStateException(
                            "transport.type=plugin-message requires a platform NetworkBridge; "
                                    + "this platform did not install one (RTP.networkBridgeFactory is null).");
                }
                return new io.github.dailystruggle.rtp.common.network.pluginmessage
                        .PluginMessageNetworkBinding(bridge, staleAfterMs, System::currentTimeMillis);
            }
            case "proxy-cache":
            case "proxycache": {
                // Tier-1, DB-free "proxy is the database" transport
                // (PROPOSAL-proxy-as-availability-store). Backends push their
                // heartbeat to the proxy companion's in-memory cache and pull
                // the cached snapshot back; converges without player presence
                // on the peer side (unlike backend-to-backend gossip).
                io.github.dailystruggle.rtp.common.network.pluginmessage.NetworkBridge bridge =
                        openNetworkBridgeOrNull();
                if (bridge == null) {
                    throw new IllegalStateException(
                            "transport.type=proxy-cache requires a platform NetworkBridge; "
                                    + "this platform did not install one (RTP.networkBridgeFactory is null).");
                }
                return new io.github.dailystruggle.rtp.common.network.pluginmessage
                        .ProxyCacheNetworkBinding(bridge, staleAfterMs, System::currentTimeMillis);
            }
            case "auto": {
                // Proxy auto-detect: build the detector and
                // run its cheap passive evaluation. When the bridge is absent
                // or the passive proxy-forwarding probe is disarmed we resolve
                // silently to disabled (return null -> boot() short-circuits)
                // so a standalone server prints no warning spam
                // (rtp-proxy-ADR-016 open item 3). When the probe is armed we
                // open the plugin-message tier and retain the detector so
                // boot() can pump it (active GetServer/GetServers handshake +
                // re-probe on carrier availability) via RTP.scheduler.
                io.github.dailystruggle.rtp.common.network.pluginmessage.NetworkBridge bridge =
                        openNetworkBridgeOrNull();
                if (bridge == null || !bridge.isAvailable()) {
                    RTP.log(Level.FINE,
                            "[NETWORK] transport.type=auto resolved to disabled "
                                    + "(no proxy bridge installed) - staying standalone.");
                    return null;
                }
                io.github.dailystruggle.rtp.common.network.pluginmessage.ProxyAutoDetector detector =
                        new io.github.dailystruggle.rtp.common.network.pluginmessage
                                .ProxyAutoDetector(bridge);
                detector.evaluatePassive();
                if (detector.state()
                        == io.github.dailystruggle.rtp.common.network.pluginmessage
                                .ProxyAutoDetector.State.DISABLED) {
                    RTP.log(Level.FINE,
                            "[NETWORK] transport.type=auto resolved to disabled "
                                    + "(passive proxy probe disarmed) - staying standalone.");
                    return null;
                }
                this.autoDetector = detector;
                this.autoDetectorBridge = bridge;
                // Prefer the proxy-cache tier under auto: it reuses the proxy
                // companion as the availability store and converges without
                // peer-side player presence (the backend-to-backend gossip tier
                // could not). When no companion answers, snapshots stay empty
                // and the topology seeding (Option 1) still routes default
                // regions, so this is strictly >= the gossip tier
                // (PROPOSAL-proxy-as-availability-store Q2: prefer when armed).
                return new io.github.dailystruggle.rtp.common.network.pluginmessage
                        .ProxyCacheNetworkBinding(bridge, staleAfterMs, System::currentTimeMillis);
            }
            case "proxy-direct":
            case "proxydirect": {
                // Tier-1, DB-free player-INDEPENDENT transport (rtp-proxy-ADR-017).
                // The backend dials the proxy companion's TCP listener directly
                // (configured proxy address list), publishing its real region
                // list at startup with NO player and reading the merged snapshot
                // back. This is the only DB-free channel that converges
                // cross-server region names with zero players AND zero per-server
                // operator config (plugin messaging cannot, because it is gated on
                // a player connection).
                java.util.List<String> proxyAddrs =
                        transportSec == null ? java.util.List.of()
                                : transportSec.getStringList("proxies");
                int defaultPort = transportSec == null ? PROXY_DIRECT_DEFAULT_PORT
                        : transportSec.getInt("port", PROXY_DIRECT_DEFAULT_PORT);
                java.util.List<java.net.InetSocketAddress> parsed =
                        io.github.dailystruggle.rtp.common.network.direct
                                .ProxyDirectNetworkBinding.parseProxies(proxyAddrs, defaultPort);
                if (parsed.isEmpty()) {
                    throw new IllegalStateException(
                            "transport.type=proxy-direct requires a non-empty transport.proxies "
                                    + "list (host:port of each proxy companion).");
                }
                int connectTimeoutMs = transportSec == null ? 1000
                        : transportSec.getInt("connectTimeoutMs", 1000);
                int readTimeoutMs = transportSec == null ? 2000
                        : transportSec.getInt("readTimeoutMs", 2000);
                return new io.github.dailystruggle.rtp.common.network.direct
                        .ProxyDirectNetworkBinding(parsed, verifier, schemaVersion,
                        staleAfterMs, connectTimeoutMs, readTimeoutMs, System::currentTimeMillis);
            }
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
                // Redis: heartbeats + snapshot + pub/sub fan-out.
                // HMAC envelope verifier MUST be passed through so the
                // backend publishes signed rows that the Velocity proxy (which
                // configures its own verifier via NetworkBindings.open) can
                // verify. Passing null here silently disables signing and
                // causes the proxy to reject every snapshot read.
                RtpYamlSection redis = transportSec == null ? null : transportSec.getConfigurationSection("redis");
                String host = redis == null ? "localhost" : redis.getString("host", "localhost");
                int port = redis == null ? 6379 : redis.getInt("port", 6379);
                String password = redis == null ? null : redis.getString("password", null);
                if (verifier == null) {
                    // Fall back to the legacy 4-arg ctor only when verifier
                    // load failed - logged at WARNING above. Both ends will be
                    // unsigned in that case, so verify() short-circuits true.
                    return new RedisNetworkStateBinding(host, port, password, intervalMs);
                }
                return new RedisNetworkStateBinding(host, port, password, intervalMs, verifier, schemaVersion);
            }
            default:
                throw new IllegalArgumentException("Unrecognised transport.type='" + type + "'");
        }
    }

    /**
     * Resolve the platform-installed plugin-message {@link io.github.dailystruggle.rtp.common.network.pluginmessage.NetworkBridge}
     * via {@link RTP#networkBridgeFactory}, or {@code null} when no platform
     * installed one (single-server / unsupported platform). Defensive: a
     * throwing factory degrades to {@code null} rather than aborting boot.
     */
    private static io.github.dailystruggle.rtp.common.network.pluginmessage.NetworkBridge openNetworkBridgeOrNull() {
        try {
            java.util.function.Supplier<
                    io.github.dailystruggle.rtp.common.network.pluginmessage.NetworkBridge> factory =
                    RTP.networkBridgeFactory;
            return factory == null ? null : factory.get();
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[NETWORK] networkBridgeFactory threw while building a NetworkBridge: "
                            + t.getMessage() + " - plugin-message tier unavailable.", t);
            return null;
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
     * Read {@code routing.lobbyMode} from {@code network.yml}
     * without booting anything else. Called by the host plugin (e.g.
     * {@code RTPBukkitPlugin.onEnable}) BEFORE
     * {@code BukkitDatabaseHandler#setupDatabase}
     * (which constructs {@code Region} instances and would otherwise schedule
     * pre-fill / hydrate work). The host writes the result to
     * {@link RTP#lobbyMode} so the gates in {@code Region} are armed before
     * any region is built.
     *
     * <p>Strictly defensive: any failure (missing file, malformed YAML,
     * {@code network.enabled=false}, missing {@code routing} section) returns
     * {@code false}. Single-server deployments and network-disabled backends
     * therefore behave byte-identically to non-lobby builds.
     *
     * @param networkYml the {@code network.yml} file under the plugin's data
     *                   folder; may be {@code null} or absent
     * @return {@code true} iff {@code network.enabled=true} AND
     *         {@code routing.lobbyMode=true}
     */
    public static boolean readLobbyModeEarly(File networkYml) {
        if (networkYml == null || !networkYml.isFile()) return false;
        try {
            RtpYamlConfig cfg = RtpYamlConfig.load(networkYml);
            RtpYamlSection network = cfg.getConfigurationSection("network");
            boolean enabled = network != null && network.getBoolean("enabled", false);
            if (!enabled) return false;
            RtpYamlSection routing = cfg.getConfigurationSection("routing");
            if (routing == null) return false;
            return routing.getBoolean("lobbyMode", false);
        } catch (Throwable t) {
            // Defensive: any failure means "not a lobby" so the backend keeps
            // its existing non-lobby behaviour.
            RTP.log(Level.FINE,
                    "[NETWORK] readLobbyModeEarly degraded to non-lobby: " + t.getMessage());
            return false;
        }
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
