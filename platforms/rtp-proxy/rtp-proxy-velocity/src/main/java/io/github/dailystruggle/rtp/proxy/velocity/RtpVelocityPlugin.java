package io.github.dailystruggle.rtp.proxy.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection;
import io.github.dailystruggle.rtp.proxy.common.RtpProxy;
import io.github.dailystruggle.rtp.proxy.common.config.NetworkConfig;
import io.github.dailystruggle.rtp.proxy.common.config.NetworkConfigException;
import io.github.dailystruggle.rtp.proxy.common.dispatch.DefaultRtpDispatcher;
import io.github.dailystruggle.rtp.proxy.common.dispatch.NetworkWaitlistDrainer;
import io.github.dailystruggle.rtp.proxy.common.dispatch.RequestQueueStatusSink;
import io.github.dailystruggle.rtp.proxy.common.dispatch.StatusSink;
import io.github.dailystruggle.rtp.proxy.common.publisher.ProxyStatePublisher;
import io.github.dailystruggle.rtp.proxy.common.selector.LoadBalancerConfig;
import io.github.dailystruggle.rtp.proxy.common.selector.LoadBalancerConfigYaml;
import io.github.dailystruggle.rtp.proxy.common.selector.RegionAwareSelector;
import io.github.dailystruggle.rtp.proxy.common.selector.WeightedAverageBackendSelector;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendSelector;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkTransport;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkWaitlist;
import io.github.dailystruggle.rtp.proxy.common.spi.PlayerOwnershipTracker;
import io.github.dailystruggle.rtp.proxy.common.spi.ReleaseReason;
import io.github.dailystruggle.rtp.proxy.common.spi.ReservationToken;
import io.github.dailystruggle.rtp.proxy.common.spi.RtpDispatcher;
import io.github.dailystruggle.rtp.proxy.common.spi.WaitlistLeaderLease;
import io.github.dailystruggle.rtp.proxy.common.transport.NetworkBindings;
import io.github.dailystruggle.rtp.proxy.common.transport.ReservationTokenReaper;
import io.github.dailystruggle.rtp.proxy.common.transport.memory.InMemoryNetworkStateBinding;
import io.github.dailystruggle.rtp.proxy.common.trigger.TransportRequestTriggerSource;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Velocity proxy adapter entry-point - Phase 2b participant skeleton.
 *
 * <p>Bootstrap order (rtp-proxy-ADR-006, rtp-proxy-ADR-013):
 * <ol>
 *   <li>Register {@link VelocityProxyAccessor} into {@link RtpProxy} BEFORE
 *       config load. Adapter contributes its hard-coded {@code Role}.</li>
 *   <li>Read {@code network.yml} (absent file = disabled defaults).</li>
 *   <li>If {@code enabled:false}: log banner, register nothing further.
 *       REQ-RTP-NET-002 byte-identical no-op.</li>
 *   <li>If {@code enabled:true}: open the configured transport (Phase 2b
 *       ships {@code in-memory} only; Redis/SQL are Phase 2e), start the
 *       heartbeat publisher.</li>
 * </ol>
 *
 * <p>Phase 2b deliberately omits Brigadier {@code /rtp} (Phase 2d) and
 * {@code ServerPreConnectEvent} interception (Phase 2c). The participant
 * skeleton wires the publisher so peers see this proxy on the heartbeat
 * fan-out; the dispatcher half lands in Phase 2e.</p>
 */
@Plugin(
        id = "rtp",
        name = "RTP",
        version = "3.0.0-beta.2",
        description = "Random Teleport - Velocity proxy adapter (Phase 2b participant skeleton).",
        authors = {"dailystruggle"}
)
public final class RtpVelocityPlugin {

    private final ProxyServer proxyServer;
    private final Logger logger;
    private final Path dataDirectory;

    private VelocityProxyAccessor accessor;
    private NetworkConfig config;
    private NetworkTransport transport;
    private NetworkRequestQueue requestQueue;
    private ProxyStatePublisher publisher;
    private ReservationTokenReaper reservationReaper;
    private ScheduledExecutorService scheduler;
    private TransportRequestTriggerSource requestTriggerSource;
    private BackendSelector selector;
    private VelocityProxySender sender;
    private RtpDispatcher dispatcher;
    /** ADR-015 shared cross-proxy waitlist; null when {@code network.waitlist.enabled=false}. */
    private NetworkWaitlist waitlist;
    /** ADR-015 leader lease; null when waitlist disabled. */
    private WaitlistLeaderLease leaderLease;
    /** ADR-015 drainer pulse logic; null when waitlist disabled. */
    private NetworkWaitlistDrainer waitlistDrainer;
    /** ADR-015 scheduled drain pulse; cancelled on shutdown. */
    private ScheduledFuture<?> waitlistDrainerTask;
    /** ADR-015 scheduled reap pulse; cancelled on shutdown. */
    private ScheduledFuture<?> waitlistReaperTask;
    /** ADR-015 disconnect listener; null when waitlist disabled. */
    private VelocityWaitlistQuitListener waitlistQuitListener;
    /** ADR-016 player-ownership tag tracker; {@link PlayerOwnershipTracker#NO_OP} on single-JVM transports. */
    private PlayerOwnershipTracker ownershipTracker;
    /** ADR-016 Velocity login/disconnect listener; null when ownership tracker is NO_OP. */
    private VelocityPlayerOwnershipListener ownershipListener;
    /** ADR-016 periodic tag-refresh pulse; cancelled on shutdown. */
    private ScheduledFuture<?> ownershipRefreshTask;
    /**
     * Proxy-cache companion (PROPOSAL-proxy-as-availability-store): caches
     * backend heartbeats + serves the operator-configured server-&gt;regions
     * snapshot to lobbies over the {@code rtp:net} channel. Null when network
     * is disabled.
     */
    private VelocityProxyCacheListener proxyCacheListener;
    /**
     * Proxy-direct TCP listener (rtp-proxy-ADR-017): backends dial this socket
     * directly (no player connection) to publish their real region list and
     * read the merged snapshot. Null when not enabled. Shares the same
     * {@link VelocityProxyAvailabilityCache} as {@link #proxyCacheListener}.
     */
    private ProxyDirectListener proxyDirectListener;
    /** Idempotence guard for {@link #onProxyShutdown} (checklist row 7f). */
    private volatile boolean shutdownStarted;

    @Inject
    public RtpVelocityPlugin(ProxyServer proxyServer,
                             Logger logger,
                             @DataDirectory Path dataDirectory) {
        this.proxyServer = proxyServer;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        try {
            // Seed plugins/rtp/network.yml from the bundled template on first run so
            // operators have an editable, fully-commented config to flip. Idempotent:
            // skips if the file already exists. Failure here is non-fatal (the
            // adapter will fall through to disabled defaults), but is logged so the
            // operator can see why the file wasn't created.
            saveDefaultNetworkYaml();

            // Phase 2b step 1: register accessor BEFORE any rtp-proxy-common entry point.
            // proxyId is read from network.yml, but the accessor needs a value at
            // construction. Read just the proxyId first (or fall back to a placeholder
            // when the file is absent; disabled config does not require a real id).
            String preliminaryProxyId = readProxyIdHint();
            this.accessor = new VelocityProxyAccessor(proxyServer, preliminaryProxyId);
            RtpProxy.setProxyAccessor(accessor);

            // Phase 2b step 2: parse network.yml (defaults when absent).
            Map<String, Object> raw = readNetworkYaml();
            try {
                this.config = NetworkConfig.fromMap(raw, accessor);
            } catch (NetworkConfigException ex) {
                logger.warn("RTP network mode disabled: {} (running in single-server mode).", ex.getMessage());
                return;
            }

            if (!config.enabled()) {
                logger.info("RTP Velocity adapter loaded; network.enabled=false (no-op, REQ-RTP-NET-002 parity).");
                return;
            }

            // Phase 2b step 3: open transport + start publisher (participant default).
            // Phase 2e-SQL update: route through NetworkBindings factory. The proxy
            // currently has no JDBC DataSource (proxies don't own a SQL DB by
            // default); transport.type=sql therefore fails fast on the proxy until
            // Phase 2e-SQL-Proxy adds a proxy-side JDBC config block. Operators
            // who want cross-process state today should pick transport.type=sql
            // on the BACKENDS and run the proxy with transport.type=in-memory or
            // leave it disabled; backends fan out via the shared DB.
            try {
                this.transport = NetworkBindings.open(config, /* dataSource */ null);
            } catch (RuntimeException | LinkageError ex) {
                // LinkageError (incl. NoClassDefFoundError) is caught alongside
                // RuntimeException because the lite assembly (ADR-024) ships the
                // Velocity entrypoint but EXCLUDES the durable redis/sql transport
                // bindings. A lite proxy whose network.yml still selects
                // transport.type=redis/sql therefore hits a NoClassDefFoundError
                // when NetworkBindings.open instantiates the absent binding; that
                // must degrade to the in-memory binding (cross-server moves still
                // work via Velocity's built-in plugin-messaging vocabulary), not
                // crash network init.
                logger.warn("RTP transport.type='{}' could not be opened on the proxy ({}); "
                        + "falling back to in-memory binding for this session. "
                        + "Durable redis/sql transports are not bundled in the lite "
                        + "edition; use the full edition for proxy-side durable state.",
                        config.transportType(), ex.toString());
                this.transport = new InMemoryNetworkStateBinding();
            }
            this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "rtp-velocity-publisher");
                t.setDaemon(true);
                return t;
            });
            this.publisher = new ProxyStatePublisher(transport, config, scheduler);
            this.publisher.start();

            // Reservation-token TTL reaper (REQ-RTP-NET-011). Periodically bulk-
            // transitions expired PENDING/CLAIMED tokens to RELEASED via the
            // transport's atomic reapExpired path and dispatches release(TTL_EXPIRED)
            // per winner so the originating backend can return the earmarked
            // coordinate to its buffer. Owns its own daemon scheduler.
            this.reservationReaper = new ReservationTokenReaper(
                    transport, Duration.ofMillis(config.reservationReapIntervalMs()));
            this.reservationReaper.start();

            // Slice E (CHECKLIST-cross-server-rtp-L6.md row E2): selector + dispatcher
            // + transport-driven request trigger source. The proxy no longer hosts a
            // Brigadier /rtp command (deleted in Slice E1); instead, backends enrol
            // locally and ship batches into the shared NetworkRequestQueue, and the
            // TransportRequestTriggerSource pops envelopes off the queue and feeds
            // them to the dispatcher. The existing ServerPreConnectEvent listener
            // still redeems the resulting ReservationToken at the connect boundary.
            // Parse loadBalancer: from network.yml so the proxy-side
            // BackendSelector AND the new region-aware fallback share one
            // operator-tuned scoring table (rtp-proxy-ADR-004 *Region-Pair
            // Scoring*). Malformed config degrades to bundled defaults with
            // a WARNING; we never abort network mode over a curve typo.
            LoadBalancerConfig loadBalancerConfig;
            try {
                Object lbNode = raw.get("loadBalancer");
                if (lbNode instanceof Map<?, ?> lbMap) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> typed = (Map<String, Object>) lbMap;
                    loadBalancerConfig = LoadBalancerConfigYaml.fromMap(typed);
                } else {
                    loadBalancerConfig = LoadBalancerConfig.defaults();
                }
            } catch (RuntimeException lbEx) {
                logger.warn("Failed to parse network.yml::loadBalancer ({}); falling back to bundled defaults.",
                        lbEx.getMessage());
                loadBalancerConfig = LoadBalancerConfig.defaults();
            }
            this.selector = new WeightedAverageBackendSelector(loadBalancerConfig);
            // Region-aware fallback: picks (serverId, regionKey) for bare
            // /rtp envelopes that arrived without a regionKey hint. Shares
            // the same LoadBalancerConfig so curves/weights tuned once
            // affect both code paths.
            RegionAwareSelector regionAwareSelector = new RegionAwareSelector(loadBalancerConfig);
            this.sender = new VelocityProxySender(proxyServer, logger);
            // Dispatcher is constructed below, after the waitlist + status sink are
            // resolved so it can park no-backend envelopes onto the shared waitlist
            // (ADR-015 Slice 2). Was: `new DefaultRtpDispatcher(selector, transport,
            // sender, scheduler)` (pre-Slice-2 4-arg ctor with null waitlist).

            // Open the cross-server request queue alongside the transport. Failure
            // here logs a WARNING and leaves the trigger source unstarted: the proxy
            // stays up for heartbeat/reservation-redeem purposes but stops draining
            // queued teleport requests (S-004: no silent swallow).
            int workerThreads = readQueueWorkerThreads(raw);
            Duration pollTimeout = readQueuePollTimeout(raw);
            try {
                this.requestQueue = NetworkBindings.openRequestQueue(config, /* dataSource */ null);
            } catch (RuntimeException | LinkageError ex) {
                // LinkageError: lite assembly excludes the durable redis/sql queue.
                logger.warn("RTP request queue open failed ({}); proxy will not drain "
                        + "cross-server /rtp requests this session.", ex.toString());
            }

            // ADR-015 / REQ-RTP-NET-015: open the shared cross-proxy waitlist + leader
            // lease. Both are null when network.waitlist.enabled=false; the dispatcher
            // then resolves no-backend as terminal FAILED (legacy pre-Slice-2 path).
            // Both open paths degrade-to-in-memory on transport failure per the
            // NetworkBindings factories; we never propagate the exception so the
            // proxy stays up for heartbeats / reservation redeem on a partial outage.
            try {
                this.waitlist = NetworkBindings.openWaitlist(config, /* dataSource */ null);
                this.leaderLease = NetworkBindings.openLeaderLease(config, /* dataSource */ null);
            } catch (RuntimeException | LinkageError ex) {
                // LinkageError: lite assembly excludes the durable redis/sql waitlist/lease.
                logger.warn("RTP waitlist/lease open failed ({}); no-backend dispatch will fall "
                        + "back to terminal FAILED this session.", ex.toString());
                this.waitlist = null;
                this.leaderLease = null;
            }

            // Status sink: forwards every dispatcher state transition into the
            // shared NetworkRequestQueue.transition() so the backend-side
            // NetworkStatusCache picks up WAITLISTED / ROUTING / RESERVED / etc.
            // Only meaningful when both the waitlist AND the request queue opened
            // successfully; otherwise we keep StatusSink.NO_OP so nothing tries to
            // write into a half-initialised transport.
            StatusSink statusSink = (waitlist != null && requestQueue != null)
                    ? new RequestQueueStatusSink(requestQueue)
                    : StatusSink.NO_OP;

            // Slice E (CHECKLIST-cross-server-rtp-L6.md row E2) + ADR-015 Slice 2:
            // construct the dispatcher with the full 8-arg ctor so the no-backend
            // branch can park onto the shared waitlist. When waitlist is null the
            // ctor still accepts the null and the dispatcher behaves pre-Slice-2.
            this.dispatcher = new DefaultRtpDispatcher(
                    selector, transport, sender, scheduler,
                    Duration.ofSeconds(30),
                    statusSink,
                    waitlist,
                    config.proxyId() == null ? "proxy" : config.proxyId(),
                    regionAwareSelector);

            // ADR-016 / REQ-RTP-NET-016: open the cross-proxy player-ownership
            // tracker BEFORE the trigger source so the worker loop can consult it
            // on every dequeue. Returns NO_OP for single-JVM transports (the
            // trigger source then falls back to legacy dequeueReady). Failure
            // here degrades to NO_OP with a WARNING; the proxy stays up.
            String thisProxyId = config.proxyId() == null ? "" : config.proxyId();
            try {
                this.ownershipTracker = NetworkBindings.openOwnershipTracker(config);
            } catch (RuntimeException | LinkageError ex) {
                // LinkageError: lite assembly excludes the durable redis/sql ownership tracker.
                logger.warn("RTP ownership tracker open failed ({}); cross-proxy ownership "
                        + "filtering disabled this session.", ex.toString());
                this.ownershipTracker = PlayerOwnershipTracker.NO_OP;
            }

            // Register the Velocity login/disconnect listener + schedule a
            // heartbeat-cadence refresh so the per-uuid tag stays alive while
            // the player is connected. Only when we have a real (non-NO_OP)
            // tracker AND a non-empty proxyId; otherwise the trigger source
            // would not gate on ownership anyway.
            boolean ownershipActive = ownershipTracker != PlayerOwnershipTracker.NO_OP
                    && !thisProxyId.isEmpty();
            if (ownershipActive) {
                long hbMs = config.heartbeatIntervalMs();
                int ttlSec = (int) Math.max(2L, ((2L * hbMs) / 1000L) + 1L);
                this.ownershipListener = new VelocityPlayerOwnershipListener(
                        ownershipTracker, thisProxyId, ttlSec, logger);
                proxyServer.getEventManager().register(this, ownershipListener);
                // Seed for any player already connected (plugin reload, hot
                // attach) and refresh on every pulse so the TTL never expires.
                final VelocityPlayerOwnershipListener listenerRef = ownershipListener;
                this.ownershipRefreshTask = scheduler.scheduleWithFixedDelay(
                        () -> {
                            try {
                                java.util.List<UUID> ids = new java.util.ArrayList<>();
                                proxyServer.getAllPlayers().forEach(p -> ids.add(p.getUniqueId()));
                                listenerRef.refreshAll(ids);
                            } catch (Throwable t) {
                                logger.warn("RTP ownership refresh pulse failed: {}",
                                        t.getMessage(), t);
                            }
                        },
                        hbMs, hbMs, TimeUnit.MILLISECONDS);
            }

            // Start the trigger source AFTER the dispatcher exists; otherwise an
            // envelope popped from the queue would crash on a null dispatcher
            // reference. The 6-arg ctor routes through the ownership-aware
            // dequeueReady overload when a non-empty proxyId is supplied;
            // single-JVM transports pass an empty string to keep the legacy
            // dequeueReady codepath.
            if (requestQueue != null) {
                try {
                    String triggerProxyId = ownershipActive ? thisProxyId : null;
                    this.requestTriggerSource = new TransportRequestTriggerSource(
                            requestQueue, dispatcher, workerThreads, pollTimeout, logger,
                            triggerProxyId);
                    this.requestTriggerSource.start();
                } catch (RuntimeException ex) {
                    logger.warn("RTP request trigger source start failed ({}); proxy will not drain "
                            + "cross-server /rtp requests this session.", ex.getMessage());
                }
            }

            // ADR-015 Slice 2 drainer + reaper: pulse cadence tracks heartbeat
            // interval (the BackendHeartbeat.regionKeptCounts feed is what bounds
            // the per-backend cap map). scheduleWithFixedDelay (not fixedRate) so a
            // slow pulse never queues up successors behind itself during a transport
            // partition. Both null-checked: a deployment with waitlist disabled
            // (or with the open path having failed above) simply skips this block.
            if (waitlist != null && leaderLease != null && transport != null) {
                this.waitlistDrainer = new NetworkWaitlistDrainer(
                        waitlist, transport, dispatcher, leaderLease,
                        Duration.ofMillis(config.waitlistLeaderLeaseHoldMs()),
                        Duration.ofMillis(config.waitlistDrainPauseMs()));
                long drainMs = config.waitlistDrainIntervalMs();
                this.waitlistDrainerTask = scheduler.scheduleWithFixedDelay(
                        () -> {
                            try { waitlistDrainer.runPulse(); }
                            catch (Throwable t) {
                                logger.warn("RTP waitlist drain pulse failed: {}", t.getMessage(), t);
                            }
                        },
                        drainMs, drainMs, TimeUnit.MILLISECONDS);

                long reapEveryMs = config.waitlistReapIntervalMs();
                Duration reapMaxAge = Duration.ofMillis(config.waitlistReapMaxAgeMs());
                this.waitlistReaperTask = scheduler.scheduleWithFixedDelay(
                        () -> {
                            try { waitlist.reap(reapMaxAge); }
                            catch (Throwable t) {
                                logger.warn("RTP waitlist reap failed: {}", t.getMessage(), t);
                            }
                        },
                        reapEveryMs, reapEveryMs, TimeUnit.MILLISECONDS);

                // Multi-proxy correctness: register a per-proxy DisconnectEvent
                // listener that point-removes the leaving player's UUID. Only the
                // proxy the player is connected to fires this event, so no inter-
                // proxy coordination is needed.
                this.waitlistQuitListener = new VelocityWaitlistQuitListener(waitlist, logger);
                proxyServer.getEventManager().register(this, waitlistQuitListener);
            }

            // Proxy availability store: the proxy is the always-present process,
            // so it holds the merged cross-server region snapshot a lobby reads
            // to populate `/rtp region=` tab-completion. Row sources, ascending
            // precedence: operator-configured `servers.<id>.regions` (optional)
            // < live backend pushes. NO assumed-`default` topology seeding: a
            // server that has not actually reported is NOT fabricated with a
            // guessed region (a backend that is offline is not a valid RTP
            // target). Real region names arrive player-independently via the
            // proxy-direct listener below (rtp-proxy-ADR-017) - a backend dials
            // us at startup and publishes its real regions with no player.
            //
            // The SAME cache instance is shared by two front-ends:
            //   - VelocityProxyCacheListener: the rtp:net plugin-message channel
            //     (player-gated; serves lobbies that have a player online).
            //   - ProxyDirectListener: the player-independent TCP socket
            //     (rtp-proxy-ADR-017) that both ingests backend pushes and
            //     answers snapshot reads.
            // Failure here is non-fatal: the proxy stays up without the cache.
            try {
                Map<String, java.util.List<String>> serverRegions = parseServerRegions(raw);
                VelocityProxyAvailabilityCache cache = new VelocityProxyAvailabilityCache(
                        serverRegions, config.heartbeatStaleAfterMs(), System::currentTimeMillis);
                this.proxyCacheListener = new VelocityProxyCacheListener(proxyServer, cache, logger);
                this.proxyCacheListener.register();
                proxyServer.getEventManager().register(this, proxyCacheListener);
                logger.info("RTP proxy-cache companion active: {} operator-configured server(s) {} on the rtp:net channel.",
                        serverRegions.size(), serverRegions);

                // Player-independent proxy-direct TCP listener, sharing the cache.
                startProxyDirectListener(raw, cache);
            } catch (RuntimeException ex) {
                logger.warn("RTP proxy-cache companion init failed ({}); cross-server region "
                        + "tab-completion will rely on live heartbeats only.", ex.toString());
                this.proxyCacheListener = null;
            }

            logger.info("RTP Velocity adapter active as participant: proxyId='{}', transport='{}', heartbeat={}ms, queueWorkers={}, waitlist={}.",
                    config.proxyId(), config.transportType(), config.heartbeatIntervalMs(),
                    workerThreads, waitlist != null ? "enabled" : "disabled");
        } catch (RuntimeException ex) {
            logger.warn("RTP Velocity adapter init failed; running disabled.", ex);
        }
    }

    /**
     * Parse the optional {@code servers:} block of {@code network.yml} into an
     * operator-declared {@code serverId -> regions} map (direction B). Supported
     * shapes (per entry):
     * <pre>
     * servers:
     *   backend-a:
     *     regions: [default, nether]   # list
     *   backend-b:
     *     regions: "default,arena"     # comma string
     *   backend-c: [default]           # bare list shorthand
     * </pre>
     * Never throws on a malformed entry: a bad row is skipped with a WARNING so
     * a config typo cannot break proxy boot. Returns an empty (mutable) map when
     * the block is absent.
     */
    @SuppressWarnings("unchecked")
    private Map<String, java.util.List<String>> parseServerRegions(Map<String, Object> raw) {
        Map<String, java.util.List<String>> out = new java.util.LinkedHashMap<>();
        if (raw == null) return out;
        Object node = raw.get("servers");
        if (!(node instanceof Map<?, ?> servers)) return out;
        for (Map.Entry<?, ?> e : servers.entrySet()) {
            String serverId = String.valueOf(e.getKey());
            if (serverId.isEmpty()) continue;
            try {
                Object value = e.getValue();
                Object regionsNode = value;
                if (value instanceof Map<?, ?> entryMap) {
                    regionsNode = entryMap.get("regions");
                }
                java.util.List<String> regions = new java.util.ArrayList<>();
                if (regionsNode instanceof java.util.List<?> list) {
                    for (Object r : list) {
                        if (r != null && !String.valueOf(r).isEmpty()) regions.add(String.valueOf(r));
                    }
                } else if (regionsNode instanceof String s) {
                    for (String tok : s.split(",")) {
                        String trimmed = tok.trim();
                        if (!trimmed.isEmpty()) regions.add(trimmed);
                    }
                }
                out.put(serverId, regions);
            } catch (RuntimeException ex) {
                logger.warn("RTP proxy-cache: skipping malformed servers.{} entry ({}).",
                        serverId, ex.getMessage());
            }
        }
        return out;
    }

    /**
     * Start the player-independent {@code proxy-direct} TCP listener
     * (rtp-proxy-ADR-017) when enabled. Enabled iff {@code transport.type} is
     * {@code proxy-direct} OR {@code transport.direct.enabled: true}. Reads
     * {@code transport.direct.port} (default 25599) and
     * {@code transport.direct.bindHost} (default {@code 0.0.0.0}). Builds an
     * {@link io.github.dailystruggle.rtp.proxy.common.security.HmacVerifier}
     * from {@code network.secretEnv} when a secret is present; otherwise runs
     * unsigned (matching the backend binding's fallback). Never throws: a bind
     * failure logs a WARNING and leaves the listener null.
     */
    @SuppressWarnings("unchecked")
    private void startProxyDirectListener(Map<String, Object> raw, VelocityProxyAvailabilityCache cache) {
        Map<String, Object> transport = raw.get("transport") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : java.util.Map.of();
        String type = String.valueOf(transport.getOrDefault("type", ""))
                .toLowerCase(java.util.Locale.ROOT);
        Map<String, Object> direct = transport.get("direct") instanceof Map<?, ?> d
                ? (Map<String, Object>) d : java.util.Map.of();
        boolean enabled = type.equals("proxy-direct") || type.equals("proxydirect")
                || Boolean.parseBoolean(String.valueOf(direct.getOrDefault("enabled", "false")));
        if (!enabled) {
            return;
        }
        int port = parseIntOrDefault(direct.get("port"), 25599);
        String bindHost = String.valueOf(direct.getOrDefault("bindHost", "0.0.0.0"));
        int schema = 1;
        if (raw.get("network") instanceof Map<?, ?> net) {
            schema = parseIntOrDefault(((Map<String, Object>) net).get("schemaVersion"), 1);
        }
        final int schemaVersion = Math.max(1, schema);
        io.github.dailystruggle.rtp.proxy.common.security.HmacVerifier verifier = null;
        try {
            verifier = io.github.dailystruggle.rtp.proxy.common.security.HmacVerifier
                    .loadFromEnv(config.secretEnv(), 1, schemaVersion);
        } catch (RuntimeException ex) {
            logger.warn("RTP proxy-direct: HMAC secret unavailable ({}); running UNSIGNED this session.",
                    ex.getMessage());
        }
        try {
            ProxyDirectListener listener = new ProxyDirectListener(
                    bindHost, port, verifier, schemaVersion,
                    payload -> cache.onPush(
                            io.github.dailystruggle.rtp.proxy.common.transport.codec
                                    .BackendHeartbeatCodec.decode(payload)),
                    () -> {
                        java.util.List<String> rows = new java.util.ArrayList<>();
                        for (io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat hb
                                : cache.snapshot()) {
                            rows.add(io.github.dailystruggle.rtp.proxy.common.transport.codec
                                    .BackendHeartbeatCodec.encode(hb));
                        }
                        return rows;
                    },
                    // proxy-direct = remote view of the proxy store: dispatch
                    // NetworkTransport + NetworkRequestQueue RPCs onto the
                    // proxy's own instances (PROPOSAL-proxy-direct-as-remote-store).
                    // NB: the local `transport` here is the YAML section, so we
                    // pass the plugin fields explicitly.
                    this.transport,
                    this.requestQueue,
                    logger);
            listener.start();
            this.proxyDirectListener = listener;
        } catch (Exception ex) {
            logger.warn("RTP proxy-direct listener failed to bind {}:{} ({}); player-independent "
                    + "backend publishing disabled this session.", bindHost, port, ex.toString());
            this.proxyDirectListener = null;
        }
    }


    /** Lenient int parse for raw YAML values; returns {@code def} on null / non-numeric. */
    private static int parseIntOrDefault(Object value, int def) {
        if (value == null) return def;
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /**
     * Phase 2c: redeem a pre-allocated {@link ReservationToken} at the
     * connect boundary. When a player connects (initial join or
     * cross-backend hop) and the active {@link NetworkTransport} reports an
     * active reservation for them, rewrite the connect target to the
     * token's {@code serverId} and transition the token to
     * {@link ReservationToken.State#CONSUMED}. Otherwise: do nothing and let
     * Velocity's default routing proceed (REQ-RTP-PROXY-VELOCITY-002, -003).
     *
     * <p>Failure handling (REQ-RTP-S-004): every failure mode - no transport
     * (network disabled), no reservation, expired reservation, unknown
     * server id, lookup exception, lookup timeout - logs a single WARNING
     * (or DEBUG for the no-reservation hot path) and falls through. None
     * silently swallow.</p>
     *
     * <p>Velocity fires {@link ServerPreConnectEvent} as an
     * {@code @AwaitingEvent}, so a short blocking get on the lookup future
     * is acceptable here; the timeout (250ms) is deliberately tight to
     * avoid hanging the connect path on a slow shared store.</p>
     */
    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        NetworkTransport active = this.transport;
        if (active == null) {
            // Network mode disabled: REQ-RTP-NET-002 byte-identical no-op.
            return;
        }
        UUID playerId = event.getPlayer().getUniqueId();
        Optional<ReservationToken> maybe;
        try {
            maybe = active.findReservation(playerId)
                    .get(RESERVATION_LOOKUP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            logger.warn("RTP findReservation timed out for {} after {}ms; falling through to default routing.",
                    playerId, RESERVATION_LOOKUP_TIMEOUT_MS);
            return;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            logger.warn("RTP findReservation interrupted for {}; falling through to default routing.", playerId);
            return;
        } catch (java.util.concurrent.ExecutionException ee) {
            logger.warn("RTP findReservation failed for {}: {}; falling through to default routing.",
                    playerId, ee.getCause() != null ? ee.getCause().getMessage() : ee.getMessage());
            return;
        }
        if (maybe.isEmpty()) {
            // No reservation -> default routing. Hot path, no log.
            return;
        }
        ReservationToken token = maybe.get();
        Optional<RegisteredServer> target = proxyServer.getServer(token.serverId());
        if (target.isEmpty()) {
            logger.warn("RTP reservation {} targets unknown server '{}'; falling through to default routing.",
                    token.tokenId(), token.serverId());
            // Release the token so the next attempt doesn't hit the same dead route.
            try {
                active.release(token.tokenId(), ReleaseReason.BACKEND_REJECTED);
            } catch (RuntimeException ignored) { }
            return;
        }
        // Rewrite the connect target. ServerPreConnectEvent.ServerResult is final;
        // we set a new allowed result pointing at the token's backend.
        event.setResult(ServerPreConnectEvent.ServerResult.allowed(target.get()));
        // DO NOT consume the token here (2026-05-23 fix). The reservation must
        // remain CLAIMED so the destination backend's JoinTriggerSource can
        // findReservation(playerId) -> redeem(...) on join, which is what
        // pins the coord, evicts the local NetworkWaitlistGuard cache row,
        // and dispatches /rtp to actually teleport the player. Releasing
        // here transitions the row to CONSUMED and DELs the active-index
        // key (release.lua), so by the time the player joins the backend,
        // findReservation returns empty and no teleport runs (the exact
        // symptom of "player arrives but no /rtp executes"). The
        // ReservationTokenReaper + 30s TTL handle the edge case where the
        // player disconnects mid-transfer and never reaches the backend.
        logger.info("RTP routed player {} to backend '{}' via reservation {} (token remains CLAIMED for backend redeem).",
                playerId, token.serverId(), token.tokenId());
    }

    /**
     * Short timeout for the connect-time reservation lookup. Velocity awaits
     * {@code @AwaitingEvent} handlers, so this directly affects player
     * connect latency on the network-disabled or slow-store fallback path.
     */
    private static final long RESERVATION_LOOKUP_TIMEOUT_MS = 250L;

    /**
     * Reverse-order teardown per rtp-proxy-ADR-006 §Lifecycle.
     * Checklist rows 7b-7f:
     * <ul>
     *   <li>7b: unregister command, stop trigger source, stop publisher,
     *       reaper closes its scheduler; reservation tokens owned by this
     *       proxy are best-effort released via the transport's close path
     *       (in-memory binding clears its state, Redis binding lets the TTL
     *       reaper handle stragglers).</li>
     *   <li>7c: {@link NetworkTransport#close()} bounded by 2000ms via a
     *       worker thread + join; on timeout log WARNING and proceed
     *       (ADR-006 deadline).</li>
     *   <li>7d: HMAC verifier (when present) zeroized via
     *       {@link io.github.dailystruggle.rtp.proxy.common.security.HmacVerifier#close()}.
     *       The transport owns the verifier today, so closing the transport
     *       drives zeroize transitively.</li>
     *   <li>7e: banner distinguishes clean vs timed-out shutdown.</li>
     *   <li>7f: idempotent - second call no-ops via {@link #shutdownStarted}.</li>
     * </ul>
     */
    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        shutdown();
    }

    /**
     * Package-private entry point used by tests to drive the shutdown
     * sequence without booting a real Velocity host. Idempotent.
     */
    synchronized void shutdown() {
        if (shutdownStarted) {
            return;
        }
        shutdownStarted = true;

        int step = 0;
        boolean cleanShutdown = true;
        try {
            step = 1;
            // ADR-015 Slice 2 teardown: cancel drainer + reaper BEFORE stopping
            // the request trigger source so a final in-flight pulse cannot try to
            // dispatch into a half-torn-down pipeline. Future.cancel(false) lets
            // an in-flight pulse run to completion; the scheduler shutdown below
            // forces termination after the 2s grace window.
            if (waitlistDrainerTask != null) {
                try { waitlistDrainerTask.cancel(false); }
                catch (RuntimeException ex) {
                    logger.warn("RTP shutdown step {} (cancel waitlist drainer) threw: {}", step, ex.getMessage());
                }
            }
            if (waitlistReaperTask != null) {
                try { waitlistReaperTask.cancel(false); }
                catch (RuntimeException ex) {
                    logger.warn("RTP shutdown step {} (cancel waitlist reaper) threw: {}", step, ex.getMessage());
                }
            }
            // Release the lease so a peer proxy can pick up drain duty promptly
            // instead of waiting on the TTL handover.
            if (leaderLease != null) {
                try { leaderLease.release(); }
                catch (RuntimeException ex) {
                    logger.warn("RTP shutdown step {} (release leader lease) threw: {}", step, ex.getMessage());
                }
            }
            // Close the waitlist when the impl owns external resources (Redis pool).
            if (waitlist instanceof AutoCloseable wc) {
                try { wc.close(); }
                catch (Exception ex) {
                    logger.warn("RTP shutdown step {} (close waitlist) threw: {}", step, ex.getMessage());
                }
            }
            // Best-effort release on the lease for Redis impls that own a pool.
            if (leaderLease instanceof AutoCloseable lc) {
                try { lc.close(); }
                catch (Exception ex) {
                    logger.warn("RTP shutdown step {} (close leader lease) threw: {}", step, ex.getMessage());
                }
            }

            step = 2;
            if (requestTriggerSource != null) {
                try { requestTriggerSource.stop(); }
                catch (RuntimeException ex) {
                    logger.warn("RTP shutdown step {} (stop request trigger source) threw: {}", step, ex.getMessage());
                }
            }
            // ADR-016 teardown: cancel the ownership refresh pulse and close the
            // tracker. We deliberately do NOT release per-uuid tags here because
            // they carry their own TTL; releasing on shutdown would falsely
            // disown players who are about to be re-routed to the surviving
            // proxy on Velocity restart.
            if (ownershipRefreshTask != null) {
                try { ownershipRefreshTask.cancel(false); }
                catch (RuntimeException ex) {
                    logger.warn("RTP shutdown step {} (cancel ownership refresh) threw: {}", step, ex.getMessage());
                }
            }
            if (ownershipTracker != null) {
                try { ownershipTracker.close(); }
                catch (RuntimeException ex) {
                    logger.warn("RTP shutdown step {} (close ownership tracker) threw: {}", step, ex.getMessage());
                }
            }
            // Proxy-cache companion: deregister the rtp:net channel so a
            // subsequent reload re-registers cleanly.
            if (proxyCacheListener != null) {
                try { proxyCacheListener.unregister(); }
                catch (RuntimeException ex) {
                    logger.warn("RTP shutdown step {} (unregister proxy-cache channel) threw: {}", step, ex.getMessage());
                }
            }
            // Proxy-direct listener: stop accepting + close the TCP socket and
            // its worker pool so a reload re-binds cleanly (rtp-proxy-ADR-017).
            if (proxyDirectListener != null) {
                try { proxyDirectListener.stop(); }
                catch (RuntimeException ex) {
                    logger.warn("RTP shutdown step {} (stop proxy-direct listener) threw: {}", step, ex.getMessage());
                }
            }
            step = 3;
            if (requestQueue != null) {
                try {
                    if (requestQueue instanceof AutoCloseable c) c.close();
                } catch (Exception ex) {
                    logger.warn("RTP shutdown step {} (close request queue) threw: {}", step, ex.getMessage());
                }
            }
            step = 4;
            if (reservationReaper != null) {
                try { reservationReaper.close(); }
                catch (RuntimeException ex) {
                    logger.warn("RTP shutdown step {} (close reaper) threw: {}", step, ex.getMessage());
                }
            }
            step = 5;
            if (publisher != null) {
                try { publisher.stop(); }
                catch (RuntimeException ex) {
                    logger.warn("RTP shutdown step {} (stop publisher) threw: {}", step, ex.getMessage());
                }
            }
            step = 6;
            if (scheduler != null) {
                scheduler.shutdown();
                try {
                    if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                        scheduler.shutdownNow();
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            step = 7;
            if (transport != null) {
                if (!closeTransportBounded(transport, SHUTDOWN_TRANSPORT_CLOSE_TIMEOUT_MS)) {
                    cleanShutdown = false;
                }
            }
        } finally {
            try { RtpProxy.clearProxyAccessor(); } catch (RuntimeException ignored) { }
            if (cleanShutdown) {
                logger.info("RTP network mode disabled (shutdown clean).");
            } else {
                logger.warn("RTP network mode disabled (shutdown timed out at step {}).", step);
            }
        }
    }

    /** Bounded transport close per ADR-006 §Lifecycle. Returns {@code true} on clean close. */
    private boolean closeTransportBounded(NetworkTransport t, long timeoutMs) {
        Thread closer = new Thread(() -> {
            try { t.close(); }
            catch (RuntimeException ex) {
                logger.warn("RTP transport.close() threw: {}", ex.getMessage());
            }
        }, "rtp-velocity-transport-close");
        closer.setDaemon(true);
        closer.start();
        try {
            closer.join(timeoutMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (closer.isAlive()) {
            logger.warn("RTP transport.close() exceeded {}ms; abandoning thread.", timeoutMs);
            return false;
        }
        return true;
    }

    /** ADR-006 deadline for the transport close step. */
    private static final long SHUTDOWN_TRANSPORT_CLOSE_TIMEOUT_MS = 2_000L;

    /**
     * Best-effort read of {@code network.proxyId} before full schema parse, so
     * the accessor has a non-null value at registration time. Returns a
     * placeholder when absent or unreadable - the accessor stores a value
     * verbatim, validation happens in {@link NetworkConfig#fromMap}.
     */
    private String readProxyIdHint() {
        try {
            Path p = dataDirectory.resolve("network.yml");
            if (!Files.exists(p)) return "velocity-default";
            RtpYamlConfig yaml = RtpYamlConfig.load(p.toFile());
            RtpYamlSection network = yaml.getConfigurationSection("network");
            if (network != null) {
                String id = network.getString("proxyId");
                if (id != null && !id.isEmpty()) return id;
            }
        } catch (java.io.IOException | RuntimeException ignored) {
            // Fall through to placeholder.
        }
        return "velocity-default";
    }

    /**
     * Copy the bundled {@code network.yml} template into the plugin data
     * directory on first run. Idempotent: if the file already exists, the
     * existing contents are preserved verbatim (operators may have edited
     * comments, reordered keys, or added their own annotations). On any I/O
     * failure logs a WARNING and returns - the rest of init will treat the
     * missing file as "disabled defaults" via {@link #readNetworkYaml()}.
     */
    private void saveDefaultNetworkYaml() {
        try {
            Path target = dataDirectory.resolve("network.yml");
            if (Files.exists(target)) return;
            Files.createDirectories(dataDirectory);
            // Read the proxy-specific template (`network-proxy.yml`) rather than the
            // shared `network.yml` resource - the latter ships the backend's defaults
            // (`role: backend`, `serverId`, `transport.type: sql`) which are wrong for
            // a Velocity proxy. The single uber-jar (RTP-Pro-*.jar) carries both
            // resources side by side; the backend reads `network.yml`, the proxy reads
            // `network-proxy.yml`. Both get written out to `plugins/rtp/network.yml`
            // on their respective platforms.
            try (java.io.InputStream in = getClass().getClassLoader().getResourceAsStream("network-proxy.yml")) {
                if (in == null) {
                    logger.warn("Bundled network-proxy.yml resource missing from jar; "
                            + "operator must create {} by hand to enable network mode.", target);
                    return;
                }
                Files.copy(in, target);
                logger.info("Created default RTP network.yml at {} (network.enabled=false; edit to enable).", target);
            }
        } catch (java.io.IOException ex) {
            logger.warn("Failed to write default network.yml: {} (running with disabled defaults).", ex.getMessage());
        }
    }

    private Map<String, Object> readNetworkYaml() {
        try {
            Path p = dataDirectory.resolve("network.yml");
            if (!Files.exists(p)) return Map.of();
            RtpYamlConfig yaml = RtpYamlConfig.load(p.toFile());
            return toNestedMap(yaml);
        } catch (java.io.IOException | RuntimeException ex) {
            logger.warn("Failed to read network.yml; treating as disabled defaults: {}", ex.getMessage());
        }
        return Map.of();
    }

    /**
     * Walk an {@link RtpYamlSection} into a nested {@code Map<String,Object>}
     * matching SnakeYAML's load shape (sub-sections become inner maps rather
     * than dotted keys). {@link NetworkConfig#fromMap} expects this nested
     * form.
     */
    private static Map<String, Object> toNestedMap(RtpYamlSection section) {
        java.util.LinkedHashMap<String, Object> out = new java.util.LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            RtpYamlSection sub = section.isConfigurationSection(key) ? section.getConfigurationSection(key) : null;
            if (sub != null) {
                out.put(key, toNestedMap(sub));
            } else {
                out.put(key, section.get(key));
            }
        }
        return out;
    }

    // ---- Test affordances ----

    /** @return registered accessor or {@code null} if init has not run. */
    VelocityProxyAccessor accessor() { return accessor; }

    /** @return loaded config or {@code null} if init has not run or failed. */
    NetworkConfig config() { return config; }

    /** @return active publisher when enabled, else {@code null}. */
    ProxyStatePublisher publisher() { return publisher; }

    /** @return bound transport when enabled, else {@code null}. */
    NetworkTransport transport() { return transport; }

    /** Exposed for tests; do not call from production code. */
    ProxyServer proxyServer() {
        return proxyServer;
    }

    /** @return active transport-driven request trigger source when enabled, else {@code null}. */
    TransportRequestTriggerSource requestTriggerSource() { return requestTriggerSource; }

    /** @return active cross-server request queue when enabled, else {@code null}. */
    NetworkRequestQueue requestQueue() { return requestQueue; }

    /** @return active shared waitlist when enabled, else {@code null}. ADR-015. */
    NetworkWaitlist waitlist() { return waitlist; }

    /** @return active leader lease when waitlist enabled, else {@code null}. ADR-015. */
    WaitlistLeaderLease leaderLease() { return leaderLease; }

    /** @return active drainer when waitlist enabled, else {@code null}. ADR-015. */
    NetworkWaitlistDrainer waitlistDrainer() { return waitlistDrainer; }

    /** @return scheduled drainer task handle when waitlist enabled, else {@code null}. */
    ScheduledFuture<?> waitlistDrainerTask() { return waitlistDrainerTask; }

    /** @return scheduled reaper task handle when waitlist enabled, else {@code null}. */
    ScheduledFuture<?> waitlistReaperTask() { return waitlistReaperTask; }

    /** @return registered disconnect listener when waitlist enabled, else {@code null}. */
    VelocityWaitlistQuitListener waitlistQuitListener() { return waitlistQuitListener; }

    /** @return active dispatcher when network enabled, else {@code null}. */
    RtpDispatcher dispatcher() { return dispatcher; }

    // ---- Slice E (CHECKLIST-cross-server-rtp-L6.md): network.queue.* config readers ----

    /**
     * Read {@code network.queue.workerThreads} from the parsed yaml map.
     * Defaults to {@code 1} when absent, non-numeric, or non-positive.
     * {@link TransportRequestTriggerSource} additionally clamps to
     * {@code >= 1} so this is purely best-effort.
     */
    @SuppressWarnings("unchecked")
    private int readQueueWorkerThreads(Map<String, Object> raw) {
        try {
            Object networkObj = raw.get("network");
            if (!(networkObj instanceof Map<?, ?>)) return 1;
            Object queueObj = ((Map<String, Object>) networkObj).get("queue");
            if (!(queueObj instanceof Map<?, ?>)) return 1;
            Object v = ((Map<String, Object>) queueObj).get("workerThreads");
            if (v instanceof Number n) return Math.max(1, n.intValue());
            if (v instanceof String s) return Math.max(1, Integer.parseInt(s.trim()));
        } catch (RuntimeException ignored) { /* fall through */ }
        return 1;
    }

    /**
     * Read {@code network.queue.pollIntervalMs} (re-used as the
     * {@link TransportRequestTriggerSource} block-wait deadline) from the
     * parsed yaml map. Defaults to {@code 2000ms} when absent.
     */
    @SuppressWarnings("unchecked")
    private Duration readQueuePollTimeout(Map<String, Object> raw) {
        try {
            Object networkObj = raw.get("network");
            if (!(networkObj instanceof Map<?, ?>)) return Duration.ofSeconds(2);
            Object queueObj = ((Map<String, Object>) networkObj).get("queue");
            if (!(queueObj instanceof Map<?, ?>)) return Duration.ofSeconds(2);
            Object v = ((Map<String, Object>) queueObj).get("pollIntervalMs");
            long ms;
            if (v instanceof Number n) ms = n.longValue();
            else if (v instanceof String s) ms = Long.parseLong(s.trim());
            else return Duration.ofSeconds(2);
            if (ms < 100L) ms = 100L;
            return Duration.ofMillis(ms);
        } catch (RuntimeException ignored) {
            return Duration.ofSeconds(2);
        }
    }
}
