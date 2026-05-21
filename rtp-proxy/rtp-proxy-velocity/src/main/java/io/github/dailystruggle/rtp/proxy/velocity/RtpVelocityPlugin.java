package io.github.dailystruggle.rtp.proxy.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection;
import io.github.dailystruggle.rtp.proxy.common.RtpProxy;
import io.github.dailystruggle.rtp.proxy.common.config.NetworkConfig;
import io.github.dailystruggle.rtp.proxy.common.config.NetworkConfigException;
import io.github.dailystruggle.rtp.proxy.common.dispatch.DefaultRtpDispatcher;
import io.github.dailystruggle.rtp.proxy.common.publisher.ProxyStatePublisher;
import io.github.dailystruggle.rtp.proxy.common.selector.LoadBalancerConfig;
import io.github.dailystruggle.rtp.proxy.common.selector.WeightedAverageBackendSelector;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendSelector;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkTransport;
import io.github.dailystruggle.rtp.proxy.common.spi.ReleaseReason;
import io.github.dailystruggle.rtp.proxy.common.spi.ReservationToken;
import io.github.dailystruggle.rtp.proxy.common.spi.RtpDispatcher;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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
            } catch (RuntimeException ex) {
                logger.warn("RTP transport.type='{}' could not be opened on the proxy ({}); "
                        + "falling back to in-memory binding for this session. "
                        + "Phase 2e-SQL-Proxy adds proxy-side JDBC support.",
                        config.transportType(), ex.getMessage());
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
            this.selector = new WeightedAverageBackendSelector(LoadBalancerConfig.defaults());
            this.sender = new VelocityProxySender(proxyServer, logger);
            this.dispatcher = new DefaultRtpDispatcher(selector, transport, sender, scheduler);

            // Open the cross-server request queue alongside the transport. Failure
            // here logs a WARNING and leaves the trigger source unstarted: the proxy
            // stays up for heartbeat/reservation-redeem purposes but stops draining
            // queued teleport requests (S-004: no silent swallow).
            int workerThreads = readQueueWorkerThreads(raw);
            Duration pollTimeout = readQueuePollTimeout(raw);
            try {
                this.requestQueue = NetworkBindings.openRequestQueue(config, /* dataSource */ null);
                this.requestTriggerSource = new TransportRequestTriggerSource(
                        requestQueue, dispatcher, workerThreads, pollTimeout, logger);
                this.requestTriggerSource.start();
            } catch (RuntimeException ex) {
                logger.warn("RTP request queue open/start failed ({}); proxy will not drain "
                        + "cross-server /rtp requests this session.", ex.getMessage());
            }

            logger.info("RTP Velocity adapter active as participant: proxyId='{}', transport='{}', heartbeat={}ms, queueWorkers={}.",
                    config.proxyId(), config.transportType(), config.heartbeatIntervalMs(), workerThreads);
        } catch (RuntimeException ex) {
            logger.warn("RTP Velocity adapter init failed; running disabled.", ex);
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
        // Consume the token. We deliberately do not block on this future; release
        // is idempotent and best-effort. A failure here cannot un-do the routing
        // decision we already made.
        try {
            active.release(token.tokenId(), ReleaseReason.CONSUMED);
        } catch (RuntimeException ex) {
            logger.warn("RTP token {} release(CONSUMED) failed after routing player {} -> '{}': {}",
                    token.tokenId(), playerId, token.serverId(), ex.getMessage());
        }
        logger.info("RTP routed player {} to backend '{}' via reservation {}.",
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
            if (requestTriggerSource != null) {
                try { requestTriggerSource.stop(); }
                catch (RuntimeException ex) {
                    logger.warn("RTP shutdown step {} (stop request trigger source) threw: {}", step, ex.getMessage());
                }
            }
            step = 2;
            if (requestQueue != null) {
                try {
                    if (requestQueue instanceof AutoCloseable c) c.close();
                } catch (Exception ex) {
                    logger.warn("RTP shutdown step {} (close request queue) threw: {}", step, ex.getMessage());
                }
            }
            step = 3;
            if (reservationReaper != null) {
                try { reservationReaper.close(); }
                catch (RuntimeException ex) {
                    logger.warn("RTP shutdown step {} (close reaper) threw: {}", step, ex.getMessage());
                }
            }
            step = 4;
            if (publisher != null) {
                try { publisher.stop(); }
                catch (RuntimeException ex) {
                    logger.warn("RTP shutdown step {} (stop publisher) threw: {}", step, ex.getMessage());
                }
            }
            step = 5;
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
            step = 6;
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
