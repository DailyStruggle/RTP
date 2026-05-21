package io.github.dailystruggle.rtp.proxy.common.transport.redis;

import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat.PluginState;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkSnapshot;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkTransport;
import io.github.dailystruggle.rtp.proxy.common.spi.ProxyHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.RedeemOutcome;
import io.github.dailystruggle.rtp.proxy.common.spi.ReleaseReason;
import io.github.dailystruggle.rtp.proxy.common.security.HmacVerifier;
import io.github.dailystruggle.rtp.proxy.common.spi.ReservationToken;
import io.github.dailystruggle.rtp.proxy.common.spi.Subscription;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Redis-backed {@link NetworkTransport} implementation - Phase 2e-Redis A1 slice
 * per {@code MULTI_SERVER_PLAN.md}.
 *
 * <p><strong>A1 + A2 scope (this class):</strong> heartbeats (publish + read snapshot
 * via {@code SCAN}), subscriber fan-out via Redis pub/sub on the
 * {@value #BACKEND_CHANNEL} channel, and atomic reservation-token claim /
 * release via SHA1-verified Lua scripts under {@code /redis/}. <em>No</em>
 * HMAC envelope, <em>no</em> kill-switch propagation hardening, <em>no</em>
 * reconnect logic beyond Jedis's pool retry: those land in A3 / A4 turns under
 * rtp-proxy-ADR-005 / ADR-010.</p>
 *
 * <p><strong>Key layout (A1+A2, subset of ADR-005):</strong></p>
 * <pre>
 *   rtp:net:backend:{serverId}      HSET fields encoding the BackendHeartbeat
 *                                    (TTL = 3 * heartbeatIntervalMs)
 *   rtp:net:proxy:{proxyId}         HSET fields encoding the ProxyHeartbeat (TTL same)
 *   PUBSUB rtp:net:backend          receives the same encoded payload on each publish
 *   rtp:net:tok:{tokenId}           HSET reservation-token row (state, serverId, ...)
 *   rtp:net:tokactive:{playerId}    STRING active-token index (holds tokenId)
 * </pre>
 *
 * <p><strong>Encoding.</strong> A handwritten line-oriented format (one
 * {@code key=value} per line, no escaping of LF) to avoid pulling a JSON
 * dependency into the proxy-common module; collection columns are
 * comma-separated. Round-trip is symmetric inside this class so the wire
 * format is private. If schema evolution becomes painful, A2 swaps in a real
 * codec; for now this is the minimum viable thing.</p>
 *
 * <p><strong>Atomic claim (A2).</strong> {@link #claim} is a single-call
 * create-and-lock that mirrors the {@code SqlNetworkStateBinding} contract:
 * the winner sees the token HASH materialised in {@code CLAIMED} state under
 * {@code rtp:net:tok:<tokenId>} with an active-player index at
 * {@code rtp:net:tokactive:<playerId>}; the loser sees {@code 0} from the
 * Lua script and the future completes exceptionally with
 * {@link IllegalStateException}. {@link #release} is idempotent (re-releasing
 * a missing or already-terminal token is a no-op). {@link #findReservation}
 * resolves the active-player index and filters out terminal / expired rows
 * (parity with {@code SqlNetworkStateBinding.findReservationSync}). Scripts
 * live under {@code /redis/claim.lua} and {@code /redis/release.lua}, each
 * paired with a {@code .sha1} sidecar that is verified at construction time
 * per rtp-proxy-ADR-005 Amendment 2026-05-18.</p>
 *
 * <p><strong>Threading.</strong> Pub/sub subscriber runs on its own daemon
 * thread (Jedis {@code subscribe} blocks); publish-side methods complete on
 * a small bounded executor. Subscriber callbacks dispatch on the subscriber
 * thread; consumers must hop to their host scheduler before touching shared
 * state (S-005, REQ-RTP-PROXY-COMMON-001).</p>
 *
 * <p><strong>Connection failure handling.</strong> Heartbeat publish exceptions
 * are swallowed with a WARNING log; the next interval retries. The subscriber
 * thread reconnects via Jedis pool's built-in retry. This is intentionally
 * lossy at A1 - production hardening lives in A4.</p>
 */
public final class RedisNetworkStateBinding implements NetworkTransport {

    private static final Logger LOG = Logger.getLogger(RedisNetworkStateBinding.class.getName());

    private static final String BACKEND_KEY_PREFIX = "rtp:net:backend:";
    private static final String PROXY_KEY_PREFIX = "rtp:net:proxy:";
    private static final String TOKEN_KEY_PREFIX = "rtp:net:tok:";
    private static final String TOKEN_ACTIVE_PREFIX = "rtp:net:tokactive:";
    private static final String BACKEND_CHANNEL = "rtp:net:backend";

    /**
     * Grace window (seconds) added to a reservation token's Redis-level
     * {@code EXPIRE} on top of its logical TTL. The logical TTL ({@code
     * expiresAtMs}) governs reapability via {@link #reapExpired}; the Redis
     * key must outlive that by enough wall-clock time for the reaper to
     * observe the row in a {@code PENDING}/{@code CLAIMED} state and
     * transition it to {@code RELEASED}. Without this grace the doomed
     * row is evicted server-side before the reaper's SCAN sees it, and
     * REQ-RTP-NET-011 ("reapExpired returns a non-empty list when at least
     * one token is past TTL") fails for any short-TTL test path - see
     * {@code NetworkSimulationTestJob#runTokenProbe} reap step.
     */
    private static final long REAP_GRACE_SECONDS = 300L;

    private final JedisPool pool;
    private final long heartbeatIntervalMs;
    private final int ttlSeconds;
    private final ConcurrentLinkedQueue<Sub> subscribers = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final AtomicInteger threadCounter = new AtomicInteger();
    private final ExecutorService publisherExec;
    private final Thread subscriberThread;
    private final JedisPubSub pubSub;
    private final RedisLuaScripts claimScript;
    private final RedisLuaScripts releaseScript;
    private final RedisLuaScripts reapScript;
    private final RedisLuaScripts redeemScript;
    private final HmacVerifier verifier;
    private final int schemaVersion;

    /**
     * Legacy constructor; HMAC envelope disabled. Retained for tests and in-memory
     * fallback paths. Production wiring through {@code NetworkBindings.open} uses
     * the {@link #RedisNetworkStateBinding(String,int,String,long,HmacVerifier,int)}
     * overload below per rtp-proxy-ADR-010 (Phase 2e-Redis A3 slice).
     */
    public RedisNetworkStateBinding(String host, int port, String password, long heartbeatIntervalMs) {
        this(host, port, password, heartbeatIntervalMs, null, 1);
    }

    /**
     * Construct with an HMAC envelope verifier per rtp-proxy-ADR-010. When
     * {@code verifier} is non-null, every published row carries an
     * {@code hmac=<hex>} field signed over the rest of the canonical payload
     * (the existing line-oriented {@code key=value\n...} encoding minus the
     * {@code hmac=} line itself), and every read row is verified before
     * deserialisation; rows that fail verification are dropped with a
     * {@code WARNING} log (REQ-RTP-S-004 auditing).
     *
     * @param host                 Redis host
     * @param port                 Redis port
     * @param password             Redis password (may be empty / null for no-auth)
     * @param heartbeatIntervalMs  heartbeat publish interval (governs key TTL)
     * @param verifier             HMAC envelope verifier; {@code null} disables HMAC
     * @param schemaVersion        wire schema version this binding publishes with
     */
    public RedisNetworkStateBinding(String host, int port, String password, long heartbeatIntervalMs,
                                    HmacVerifier verifier, int schemaVersion) {
        this.verifier = verifier;
        this.schemaVersion = schemaVersion;
        if (heartbeatIntervalMs <= 0) {
            throw new IllegalArgumentException("heartbeatIntervalMs must be > 0");
        }
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        // 3x interval keeps a row alive across one missed publish; matches the
        // backend-side "staleAfterMs default 5000" rounding bias.
        this.ttlSeconds = (int) Math.max(2L, (heartbeatIntervalMs * 3L) / 1000L);

        JedisPoolConfig cfg = new JedisPoolConfig();
        cfg.setMaxTotal(8);
        cfg.setMaxIdle(4);
        cfg.setMinIdle(1);
        cfg.setTestOnBorrow(true);
        if (password != null && !password.isEmpty()) {
            this.pool = new JedisPool(cfg, host, port, 2000, password);
        } else {
            this.pool = new JedisPool(cfg, host, port, 2000);
        }

        // Eagerly validate connectivity. A bad host should fail at open() time,
        // not silently in publish loops.
        try (Jedis j = pool.getResource()) {
            j.ping();
        } catch (Exception e) {
            pool.close();
            throw new IllegalStateException(
                    "RedisNetworkStateBinding: cannot reach redis at " + host + ":" + port
                            + " (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")", e);
        }

        // A2: load + SHA1-verify the atomic-claim/release Lua scripts, then pre-load
        // them into Redis's script cache so the first claim path is EVALSHA, not EVAL.
        // SHA1 mismatch is a build-time defect (rtp-proxy-ADR-005 Amendment 2026-05-18):
        // refuse to enable so a stale sidecar cannot silently disable atomic claim.
        try {
            this.claimScript = RedisLuaScripts.load("claim");
            this.releaseScript = RedisLuaScripts.load("release");
            this.reapScript = RedisLuaScripts.load("reap");
            this.redeemScript = RedisLuaScripts.load("redeem");
            this.claimScript.scriptLoad(pool);
            this.releaseScript.scriptLoad(pool);
            this.redeemScript.scriptLoad(pool);
            this.reapScript.scriptLoad(pool);
        } catch (RuntimeException e) {
            pool.close();
            throw e;
        }

        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "rtp-redis-pub-" + threadCounter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        this.publisherExec = Executors.newFixedThreadPool(2, tf);

        this.pubSub = new JedisPubSub() {
            @Override
            public void onMessage(String channel, String message) {
                if (!BACKEND_CHANNEL.equals(channel)) return;
                BackendHeartbeat hb = decodeBackend(message);
                if (hb == null) return;
                for (Sub s : subscribers) {
                    if (s.isClosed()) continue;
                    try { s.sink.accept(hb); }
                    catch (Throwable t) { LOG.log(Level.WARNING, "subscriber threw", t); }
                }
            }
        };

        this.subscriberThread = new Thread(() -> {
            while (open.get()) {
                try (Jedis j = pool.getResource()) {
                    j.subscribe(pubSub, BACKEND_CHANNEL);
                } catch (Throwable t) {
                    if (!open.get()) return;
                    LOG.log(Level.WARNING,
                            "redis subscriber disconnected; reconnecting in 1s: " + t.getMessage());
                    try { Thread.sleep(1000L); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }, "rtp-redis-sub-" + threadCounter.incrementAndGet());
        this.subscriberThread.setDaemon(true);
        this.subscriberThread.start();
    }

    @Override
    public CompletableFuture<Void> publishProxyHeartbeat(ProxyHeartbeat row) {
        Objects.requireNonNull(row, "row");
        checkOpen();
        return CompletableFuture.runAsync(() -> {
            String key = PROXY_KEY_PREFIX + row.proxyId();
            Map<String, String> hash = encodeProxy(row);
            try (Jedis j = pool.getResource()) {
                j.hset(key, hash);
                j.expire(key, ttlSeconds);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "publishProxyHeartbeat failed: " + e.getMessage());
            }
        }, publisherExec);
    }

    @Override
    public CompletableFuture<Void> publishBackendHeartbeat(BackendHeartbeat row) {
        Objects.requireNonNull(row, "row");
        checkOpen();
        return CompletableFuture.runAsync(() -> {
            String key = BACKEND_KEY_PREFIX + row.serverId();
            String encoded = encodeBackend(row);
            Map<String, String> hash = parseFlat(encoded);
            try (Jedis j = pool.getResource()) {
                j.hset(key, hash);
                j.expire(key, ttlSeconds);
                j.publish(BACKEND_CHANNEL, encoded);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "publishBackendHeartbeat failed: " + e.getMessage());
            }
        }, publisherExec);
    }

    @Override
    public CompletableFuture<NetworkSnapshot> readSnapshot() {
        checkOpen();
        return CompletableFuture.supplyAsync(this::readSnapshotSync, publisherExec);
    }

    private NetworkSnapshot readSnapshotSync() {
        Map<String, BackendHeartbeat> backends = new LinkedHashMap<>();
        try (Jedis j = pool.getResource()) {
            String cursor = ScanParams.SCAN_POINTER_START;
            ScanParams params = new ScanParams().match(BACKEND_KEY_PREFIX + "*").count(64);
            do {
                ScanResult<String> res = j.scan(cursor, params);
                for (String key : res.getResult()) {
                    Map<String, String> hash = j.hgetAll(key);
                    if (hash == null || hash.isEmpty()) continue;
                    BackendHeartbeat hb = decodeBackend(flatten(hash));
                    if (hb != null) backends.put(hb.serverId(), hb);
                }
                cursor = res.getCursor();
            } while (!ScanParams.SCAN_POINTER_START.equals(cursor));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "readSnapshot failed: " + e.getMessage());
        }
        return new NetworkSnapshot(System.currentTimeMillis(), backends);
    }

    @Override
    public Subscription subscribeBackendHeartbeats(Consumer<BackendHeartbeat> sink) {
        Objects.requireNonNull(sink, "sink");
        checkOpen();
        Sub s = new Sub(sink);
        subscribers.add(s);
        return s;
    }

    @Override
    public CompletableFuture<ReservationToken> claim(String serverId, UUID playerId, Duration ttl) {
        Objects.requireNonNull(serverId, "serverId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(ttl, "ttl");
        checkOpen();
        return CompletableFuture.supplyAsync(() -> claimSync(serverId, playerId, ttl), publisherExec);
    }

    @Override
    public CompletableFuture<Void> release(String tokenId, ReleaseReason reason) {
        Objects.requireNonNull(tokenId, "tokenId");
        Objects.requireNonNull(reason, "reason");
        checkOpen();
        return CompletableFuture.runAsync(() -> releaseSync(tokenId, reason), publisherExec);
    }

    @Override
    public CompletableFuture<RedeemOutcome> redeem(String tokenId, UUID playerId, String expectedServerId) {
        Objects.requireNonNull(tokenId, "tokenId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(expectedServerId, "expectedServerId");
        checkOpen();
        return CompletableFuture.supplyAsync(
                () -> redeemSync(tokenId, playerId, expectedServerId), publisherExec);
    }

    private RedeemOutcome redeemSync(String tokenId, UUID playerId, String expectedServerId) {
        List<String> keys = List.of(TOKEN_KEY_PREFIX + tokenId, TOKEN_ACTIVE_PREFIX + playerId);
        long now = System.currentTimeMillis();
        List<String> args = List.of(
                tokenId,
                playerId.toString(),
                expectedServerId,
                Long.toString(now),
                Long.toString(now));
        Object raw;
        try (Jedis j = pool.getResource()) {
            raw = redeemScript.evalsha(j, keys, args);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "RedisNetworkStateBinding.redeem failed: " + e.getMessage());
            return RedeemOutcome.TRANSPORT_ERROR;
        }
        String result = raw == null ? "" : raw.toString();
        try {
            return RedeemOutcome.valueOf(result);
        } catch (IllegalArgumentException ex) {
            LOG.log(Level.WARNING,
                    "RedisNetworkStateBinding.redeem: unrecognised Lua return '" + result
                            + "' for token " + tokenId + " (REQ-RTP-S-004)");
            return RedeemOutcome.BAD_STATE;
        }
    }

    @Override
    public CompletableFuture<Optional<ReservationToken>> findReservation(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        checkOpen();
        return CompletableFuture.supplyAsync(() -> findReservationSync(playerId), publisherExec);
    }

    @Override
    public CompletableFuture<List<String>> reapExpired(Instant now) {
        Objects.requireNonNull(now, "now");
        checkOpen();
        return CompletableFuture.supplyAsync(() -> reapExpiredSync(now), publisherExec);
    }

    @Override
    public CompletableFuture<List<ReservationToken>> listActiveForServer(String serverId) {
        Objects.requireNonNull(serverId, "serverId");
        checkOpen();
        return CompletableFuture.supplyAsync(() -> listActiveForServerSync(serverId), publisherExec);
    }

    /**
     * One-shot SCAN+HGETALL filter over {@code rtp:net:tok:*}. Used by a
     * backend's boot-time reconcile (L6/F1) so the cost is borne once per
     * backend lifecycle, not every tick. Corrupt rows (missing state, bad
     * timestamps, HMAC mismatch) are filtered with WARNING and never raised
     * to the caller, mirroring {@link #findReservationSync(UUID)}.
     */
    private List<ReservationToken> listActiveForServerSync(String serverId) {
        long nowMs = System.currentTimeMillis();
        List<ReservationToken> out = new ArrayList<>();
        try (Jedis j = pool.getResource()) {
            String cursor = ScanParams.SCAN_POINTER_START;
            ScanParams params = new ScanParams().match(TOKEN_KEY_PREFIX + "*").count(64);
            do {
                ScanResult<String> res = j.scan(cursor, params);
                cursor = res.getCursor();
                for (String key : res.getResult()) {
                    if (!key.startsWith(TOKEN_KEY_PREFIX)) continue;
                    String tokenId = key.substring(TOKEN_KEY_PREFIX.length());
                    if (tokenId.isEmpty()) continue;
                    Map<String, String> hash = j.hgetAll(key);
                    if (hash == null || hash.isEmpty()) continue;
                    String rowServerId = hash.get("serverId");
                    if (!serverId.equals(rowServerId)) continue;
                    String stateStr = hash.get("state");
                    if (stateStr == null) continue;
                    ReservationToken.State state;
                    try {
                        state = ReservationToken.State.valueOf(stateStr);
                    } catch (IllegalArgumentException ex) {
                        continue;
                    }
                    if (state != ReservationToken.State.PENDING && state != ReservationToken.State.CLAIMED) continue;
                    long expires;
                    try {
                        expires = Long.parseLong(hash.getOrDefault("expiresAtMs", "0"));
                    } catch (NumberFormatException ex) {
                        continue;
                    }
                    if (expires <= nowMs) continue;
                    String playerIdStr = hash.get("playerId");
                    if (playerIdStr == null) continue;
                    UUID playerId;
                    try {
                        playerId = UUID.fromString(playerIdStr);
                    } catch (IllegalArgumentException ex) {
                        continue;
                    }
                    if (verifier != null) {
                        String storedHmac = hash.getOrDefault("hmac", "");
                        String canonical = canonicalToken(
                                tokenId, rowServerId, playerIdStr,
                                hash.getOrDefault("expiresAtMs", "0"),
                                hash.getOrDefault("createdAtMs", "0"),
                                state.name());
                        if (!verifier.verify(schemaVersion, canonical, storedHmac)) {
                            LOG.log(Level.WARNING,
                                    "listActiveForServerSync: HMAC verification failed for token "
                                            + tokenId + "; dropping row (REQ-RTP-S-004)");
                            continue;
                        }
                    }
                    out.add(new ReservationToken(tokenId, rowServerId, playerId, expires, state));
                }
            } while (!ScanParams.SCAN_POINTER_START.equals(cursor));
        } catch (Exception e) {
            LOG.log(Level.WARNING,
                    "RedisNetworkStateBinding.listActiveForServer failed: " + e.getMessage()
                            + " (REQ-RTP-S-004)");
            return List.of();
        }
        return out;
    }

    private ReservationToken claimSync(String serverId, UUID playerId, Duration ttl) {
        // A2 atomic claim via Lua. The script is single-call create-and-lock:
        // race losers see 0 and we mirror SqlNetworkStateBinding.claimSync by
        // throwing IllegalStateException so the dispatcher uniformly retries.
        String tokenId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        long expires = now + ttl.toMillis();
        // Logical TTL governs reapability (claim.lua compares expiresAtMs in
        // ms); the Redis key-level EXPIRE merely bounds storage. We extend it
        // by REAP_GRACE_SECONDS so that short logical TTLs (e.g. 1ms test
        // tokens) survive long enough for reapExpired's SCAN to observe and
        // transition them; without this grace, Redis evicts the row before
        // the reaper sees it and REQ-RTP-NET-011 cannot be verified.
        long logicalSecs = Math.max(1L, ttl.toSeconds());
        long ttlSecs = logicalSecs + REAP_GRACE_SECONDS;
        List<String> keys = List.of(TOKEN_KEY_PREFIX + tokenId, TOKEN_ACTIVE_PREFIX + playerId);
        // A3 token envelope: Java pre-computes HMAC over the canonical token
        // payload that claim.lua will HSET into the row. Sign the same byte
        // sequence that findReservationSync rebuilds on read (terminal-state
        // rows skip verify since they are filtered to Optional.empty()).
        String hmacHex = "";
        if (verifier != null) {
            hmacHex = verifier.sign(schemaVersion,
                    canonicalToken(tokenId, serverId, playerId.toString(),
                            Long.toString(expires), Long.toString(now),
                            ReservationToken.State.CLAIMED.name()));
        }
        List<String> args = List.of(
                tokenId,
                serverId,
                playerId.toString(),
                Long.toString(expires),
                Long.toString(now),
                Long.toString(ttlSecs),
                hmacHex);
        Object result;
        try (Jedis j = pool.getResource()) {
            result = claimScript.evalsha(j, keys, args);
        } catch (Exception e) {
            throw new RuntimeException("RedisNetworkStateBinding.claim failed: " + e.getMessage(), e);
        }
        long rc = (result instanceof Number) ? ((Number) result).longValue() : 0L;
        if (rc != 1L) {
            throw new IllegalStateException(
                    "claim race lost for player " + playerId + " (Redis claim returned " + rc + ")");
        }
        return new ReservationToken(tokenId, serverId, playerId, expires, ReservationToken.State.CLAIMED);
    }

    private void releaseSync(String tokenId, ReleaseReason reason) {
        String terminal = reason == ReleaseReason.CONSUMED
                ? ReservationToken.State.CONSUMED.name()
                : ReservationToken.State.RELEASED.name();
        List<String> keys = List.of(TOKEN_KEY_PREFIX + tokenId);
        List<String> args = List.of(
                tokenId,
                terminal,
                Long.toString(System.currentTimeMillis()),
                TOKEN_ACTIVE_PREFIX);
        try (Jedis j = pool.getResource()) {
            releaseScript.evalsha(j, keys, args);
            // Idempotent: 0 return is a no-op (missing or already terminal).
        } catch (Exception e) {
            throw new RuntimeException("RedisNetworkStateBinding.release failed: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> reapExpiredSync(Instant now) {
        // REQ-RTP-NET-011 reap pass via Lua. The script SCANs the token
        // keyspace, transitions every PENDING/CLAIMED row whose expiresAtMs
        // has passed, drops its active-player index, and returns the freshly-
        // reaped tokenIds. Atomic per Redis semantics (single Lua block).
        List<String> args = List.of(
                Long.toString(now.toEpochMilli()),
                TOKEN_KEY_PREFIX,
                TOKEN_ACTIVE_PREFIX,
                "100");
        Object result;
        try (Jedis j = pool.getResource()) {
            result = reapScript.evalsha(j, List.of(), args);
        } catch (Exception e) {
            throw new RuntimeException("RedisNetworkStateBinding.reapExpired failed: " + e.getMessage(), e);
        }
        if (!(result instanceof List<?>)) {
            return List.of();
        }
        List<?> raw = (List<?>) result;
        List<String> reaped = new ArrayList<>(raw.size());
        for (Object o : raw) {
            if (o == null) continue;
            if (o instanceof byte[] bytes) {
                reaped.add(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            } else {
                reaped.add(o.toString());
            }
        }
        return reaped;
    }

    private Optional<ReservationToken> findReservationSync(UUID playerId) {
        try (Jedis j = pool.getResource()) {
            String tokenId = j.get(TOKEN_ACTIVE_PREFIX + playerId);
            if (tokenId == null || tokenId.isEmpty()) return Optional.empty();
            Map<String, String> hash = j.hgetAll(TOKEN_KEY_PREFIX + tokenId);
            if (hash == null || hash.isEmpty()) return Optional.empty();
            String stateStr = hash.get("state");
            if (stateStr == null) return Optional.empty();
            ReservationToken.State state;
            try {
                state = ReservationToken.State.valueOf(stateStr);
            } catch (IllegalArgumentException ex) {
                // Corrupt state row: treat as no reservation (REQ-RTP-S-004
                // parity with SqlNetworkStateBinding.findReservationSync).
                return Optional.empty();
            }
            if (state == ReservationToken.State.CONSUMED || state == ReservationToken.State.RELEASED) {
                return Optional.empty();
            }
            long expires;
            try {
                expires = Long.parseLong(hash.getOrDefault("expiresAtMs", "0"));
            } catch (NumberFormatException ex) {
                return Optional.empty();
            }
            if (expires > 0 && expires <= System.currentTimeMillis()) return Optional.empty();
            // A3 token envelope: verify HMAC on non-terminal rows. Sign covers
            // the same canonical payload claimSync built pre-EVALSHA. A
            // mismatch (forged row, replayed claim, or state tampered from
            // RELEASED back to CLAIMED) drops the row with a REQ-RTP-S-004
            // WARNING and returns Optional.empty().
            if (verifier != null) {
                String storedHmac = hash.getOrDefault("hmac", "");
                String canonical = canonicalToken(
                        tokenId,
                        hash.getOrDefault("serverId", ""),
                        playerId.toString(),
                        hash.getOrDefault("expiresAtMs", "0"),
                        hash.getOrDefault("createdAtMs", "0"),
                        state.name());
                if (!verifier.verify(schemaVersion, canonical, storedHmac)) {
                    LOG.log(Level.WARNING,
                            "findReservationSync: HMAC verification failed for token "
                                    + tokenId + "; dropping row (REQ-RTP-S-004)");
                    return Optional.empty();
                }
            }
            return Optional.of(new ReservationToken(
                    tokenId,
                    hash.getOrDefault("serverId", ""),
                    playerId,
                    expires,
                    state));
        } catch (Exception e) {
            throw new RuntimeException("RedisNetworkStateBinding.findReservation failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        if (!open.compareAndSet(true, false)) return;
        try { pubSub.unsubscribe(); } catch (Throwable ignored) { /* best-effort */ }
        try { subscriberThread.interrupt(); } catch (Throwable ignored) { /* best-effort */ }
        publisherExec.shutdown();
        try { pool.close(); } catch (Throwable ignored) { /* best-effort */ }
    }

    private void checkOpen() {
        if (!open.get()) throw new IllegalStateException("RedisNetworkStateBinding is closed");
    }


    // ---- encode / decode -------------------------------------------------

    private String encodeBackend(BackendHeartbeat r) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("serverId=").append(r.serverId()).append('\n');
        sb.append("schemaVersion=").append(r.schemaVersion()).append('\n');
        sb.append("pluginState=").append(r.pluginState().name()).append('\n');
        sb.append("acceptingRequests=").append(r.acceptingRequests()).append('\n');
        sb.append("lastSeenEpochMs=").append(r.lastSeenEpochMs()).append('\n');
        sb.append("mspt=").append(r.mspt()).append('\n');
        sb.append("queueDepth=").append(r.queueDepth()).append('\n');
        sb.append("softCap=").append(r.softCap()).append('\n');
        sb.append("heapUsedBytes=").append(r.heapUsedBytes()).append('\n');
        sb.append("heapMaxBytes=").append(r.heapMaxBytes()).append('\n');
        sb.append("playerCount=").append(r.playerCount()).append('\n');
        sb.append("regionsAvailable=").append(joinList(r.regionsAvailable())).append('\n');
        sb.append("worldsLoaded=").append(joinList(r.worldsLoaded())).append('\n');
        sb.append("killSwitch=").append(r.killSwitch());
        String canonical = sb.toString();
        if (verifier != null) {
            // A3: append the HMAC line last so decode can strip the 'hmac' key
            // cleanly. Sign covers schemaVersion || '|' || canonical (the rest
            // of the payload, without the hmac line).
            sb.append('\n').append("hmac=").append(verifier.sign(schemaVersion, canonical));
        }
        return sb.toString();
    }

    private BackendHeartbeat decodeBackend(String payload) {
        if (payload == null || payload.isEmpty()) return null;
        Map<String, String> m = parseFlat(payload);
        if (verifier != null) {
            String hmacHex = m.remove("hmac");
            // Rebuild the canonical payload from the surviving keys in their
            // original LinkedHashMap insertion order, matching encodeBackend's
            // deterministic key sequence.
            int sv;
            try {
                sv = Integer.parseInt(m.getOrDefault("schemaVersion", "1"));
            } catch (NumberFormatException e) {
                sv = 1;
            }
            if (!verifier.verify(sv, flatten(m), hmacHex)) {
                LOG.log(Level.WARNING,
                        "decodeBackend: HMAC verification failed; dropping row (REQ-RTP-S-004)");
                return null;
            }
        }
        try {
            return new BackendHeartbeat(
                    requireKey(m, "serverId"),
                    Integer.parseInt(m.getOrDefault("schemaVersion", "1")),
                    PluginState.valueOf(m.getOrDefault("pluginState", "READY")),
                    Boolean.parseBoolean(m.getOrDefault("acceptingRequests", "true")),
                    Long.parseLong(m.getOrDefault("lastSeenEpochMs", "0")),
                    Double.parseDouble(m.getOrDefault("mspt", "0")),
                    Integer.parseInt(m.getOrDefault("queueDepth", "0")),
                    Integer.parseInt(m.getOrDefault("softCap", "0")),
                    Long.parseLong(m.getOrDefault("heapUsedBytes", "0")),
                    Long.parseLong(m.getOrDefault("heapMaxBytes", "0")),
                    Integer.parseInt(m.getOrDefault("playerCount", "0")),
                    splitList(m.getOrDefault("regionsAvailable", "")),
                    splitList(m.getOrDefault("worldsLoaded", "")),
                    Boolean.parseBoolean(m.getOrDefault("killSwitch", "false"))
            );
        } catch (Exception e) {
            LOG.log(Level.WARNING, "decodeBackend: malformed payload (" + e.getMessage() + ")");
            return null;
        }
    }

    private Map<String, String> encodeProxy(ProxyHeartbeat r) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("proxyId", r.proxyId());
        m.put("schemaVersion", Integer.toString(r.schemaVersion()));
        m.put("lastSeenEpochMs", Long.toString(r.lastSeenEpochMs()));
        m.put("playerCount", Integer.toString(r.playerCount()));
        m.put("inFlightRequests", Integer.toString(r.inFlightRequests()));
        m.put("killSwitch", Boolean.toString(r.killSwitch()));
        if (verifier != null) {
            // Sign over the flattened canonical form before adding the hmac key.
            m.put("hmac", verifier.sign(schemaVersion, flatten(m)));
        }
        return m;
    }

    private static Map<String, String> parseFlat(String s) {
        Map<String, String> m = new LinkedHashMap<>();
        if (s == null || s.isEmpty()) return m;
        for (String line : s.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            m.put(line.substring(0, eq), line.substring(eq + 1));
        }
        return m;
    }

    /**
     * Canonical token payload used by the A3 HMAC envelope. The byte sequence
     * is the same on claim-side (Java pre-computes the HMAC and passes it as
     * ARGV[7] to {@code claim.lua}) and on read-side ({@code
     * findReservationSync} rebuilds it from the HSET fields and verifies).
     *
     * <p>Field order is fixed and matches {@code claim.lua}'s {@code HSET}
     * sequence: tokenId, serverId, playerId, expiresAtMs, createdAtMs, state.
     * Adding a new signed field is a wire-format breaking change (requires a
     * {@code FLUSHDB} on upgrade, same posture as the heartbeat envelope).</p>
     */
    private static String canonicalToken(String tokenId, String serverId, String playerId,
                                         String expiresAtMs, String createdAtMs, String state) {
        return "tokenId=" + tokenId
                + "\nserverId=" + serverId
                + "\nplayerId=" + playerId
                + "\nexpiresAtMs=" + expiresAtMs
                + "\ncreatedAtMs=" + createdAtMs
                + "\nstate=" + state;
    }

    private static String flatten(Map<String, String> hash) {
        StringBuilder sb = new StringBuilder(256);
        boolean first = true;
        for (Map.Entry<String, String> e : hash.entrySet()) {
            if (!first) sb.append('\n');
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return sb.toString();
    }

    private static String requireKey(Map<String, String> m, String key) {
        String v = m.get(key);
        if (v == null) throw new IllegalArgumentException("missing required key: " + key);
        return v;
    }

    private static String joinList(List<String> xs) {
        if (xs == null || xs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < xs.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(xs.get(i).replace(',', '_').replace('\n', '_'));
        }
        return sb.toString();
    }

    private static List<String> splitList(String s) {
        if (s == null || s.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (String tok : s.split(",")) {
            if (!tok.isEmpty()) out.add(tok);
        }
        return out;
    }

    private final class Sub implements Subscription {
        final Consumer<BackendHeartbeat> sink;
        final AtomicBoolean closed = new AtomicBoolean(false);
        Sub(Consumer<BackendHeartbeat> sink) { this.sink = sink; }
        @Override public void close() { if (closed.compareAndSet(false, true)) subscribers.remove(this); }
        @Override public boolean isClosed() { return closed.get(); }
    }
}
