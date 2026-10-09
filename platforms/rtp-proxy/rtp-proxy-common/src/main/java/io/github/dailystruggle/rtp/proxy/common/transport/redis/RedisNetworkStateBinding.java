package io.github.dailystruggle.rtp.proxy.common.transport.redis;

import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkSnapshot;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkTransport;
import io.github.dailystruggle.rtp.proxy.common.spi.ProxyHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.RedeemOutcome;
import io.github.dailystruggle.rtp.proxy.common.spi.ReleaseReason;
import io.github.dailystruggle.rtp.proxy.common.security.HmacVerifier;
import io.github.dailystruggle.rtp.proxy.common.transport.CanonicalEnvelopes;
import io.github.dailystruggle.rtp.proxy.common.transport.codec.BackendHeartbeatCodec;
import io.github.dailystruggle.rtp.proxy.common.spi.ReservationToken;
import io.github.dailystruggle.rtp.proxy.common.spi.Subscription;
import io.github.dailystruggle.rtp.proxy.common.transport.redis.resp.RespConnection;
import io.github.dailystruggle.rtp.proxy.common.transport.redis.resp.RespPool;
import io.github.dailystruggle.rtp.proxy.common.transport.redis.resp.RespProtocol;
import io.github.dailystruggle.rtp.proxy.common.transport.redis.resp.RespPubSub;

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
 * Redis-backed {@link NetworkTransport} implementation.
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
    private static final String LAST_TP_PREFIX = "rtp:lastTp:";
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

    private final RespPool pool;
    private final boolean ownsPool;
    private final long heartbeatIntervalMs;
    private final int ttlSeconds;
    private final ConcurrentLinkedQueue<Sub> subscribers = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final AtomicInteger threadCounter = new AtomicInteger();
    private final ExecutorService publisherExec;
    private final Thread subscriberThread;
    private final RespPubSub pubSub;
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
     * overload below per rtp-proxy-ADR-010.
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
        this(buildPool(host, port, password), true, heartbeatIntervalMs, verifier, schemaVersion);
    }

    /**
     * Pool-injection constructor. The caller retains ownership of the pool
     * unless explicitly closed.
     */
    public RedisNetworkStateBinding(RespPool pool, long heartbeatIntervalMs,
                                    HmacVerifier verifier, int schemaVersion) {
        this(Objects.requireNonNull(pool, "pool"), false, heartbeatIntervalMs, verifier, schemaVersion);
    }

    private static RespPool buildPool(String host, int port, String password) {
        return new RespPool(host, port, 2000, password, 8);
    }

    private RedisNetworkStateBinding(RespPool pool, boolean ownsPool, long heartbeatIntervalMs,
                                    HmacVerifier verifier, int schemaVersion) {
        this.pool = pool;
        this.ownsPool = ownsPool;
        this.verifier = verifier;
        this.schemaVersion = schemaVersion;
        if (heartbeatIntervalMs <= 0) {
            throw new IllegalArgumentException("heartbeatIntervalMs must be > 0");
        }
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        // 3x interval keeps a row alive across one missed publish; matches the
        // backend-side "staleAfterMs default 5000" rounding bias.
        this.ttlSeconds = (int) Math.max(2L, (heartbeatIntervalMs * 3L) / 1000L);

        // Eagerly validate connectivity. A bad host should fail at open() time,
        // not silently in publish loops.
        try (RespConnection j = pool.getResource()) {
            j.ping();
        } catch (Exception e) {
            if (ownsPool) pool.close();
            throw new IllegalStateException(
                    "RedisNetworkStateBinding: cannot reach redis (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")", e);
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
            if (ownsPool) pool.close();
            throw e;
        }

        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "rtp-redis-pub-" + threadCounter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        this.publisherExec = Executors.newFixedThreadPool(2, tf);

        this.pubSub = new RespPubSub() {
            @Override
            public void onMessage(String channel, String message) {
                if (!BACKEND_CHANNEL.equals(channel)) return;
                BackendHeartbeat hb = decodeBackend(message);
                if (hb == null) return;
                // Receive-side trace: fan-out is otherwise silent on success, so
                // a FINE line here confirms the proxy actually observed a backend
                // heartbeat on the pub/sub channel (gated by advanced/logging.yml).
                if (LOG.isLoggable(Level.FINE)) {
                    LOG.log(Level.FINE, "redis received backend heartbeat for server="
                            + hb.serverId() + " (players=" + hb.playerCount()
                            + ", kept=" + hb.keptCount() + ", accepting=" + hb.acceptingRequests()
                            + "); fanning out to " + subscribers.size() + " subscriber(s)");
                }
                for (Sub s : subscribers) {
                    if (s.isClosed()) continue;
                    try { s.sink.accept(hb); }
                    catch (Throwable t) { LOG.log(Level.WARNING, "subscriber threw", t); }
                }
            }
        };

        this.subscriberThread = new Thread(() -> {
            while (open.get()) {
                try (RespConnection j = pool.getResource()) {
                    pubSub.proceed(j, BACKEND_CHANNEL);
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
            try (RespConnection j = pool.getResource()) {
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
            try (RespConnection j = pool.getResource()) {
                j.hset(key, hash);
                j.expire(key, ttlSeconds);
                j.publish(BACKEND_CHANNEL, encoded);
                // Write-side trace: the HSET/EXPIRE/PUBLISH triad is silent on
                // success, so a FINE line here confirms the row actually reached
                // Redis (gated by advanced/logging.yml).
                if (LOG.isLoggable(Level.FINE)) {
                    LOG.log(Level.FINE, "redis published backend heartbeat key=" + key
                            + " ttl=" + ttlSeconds + "s channel=" + BACKEND_CHANNEL);
                }
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
        try (RespConnection j = pool.getResource()) {
            String cursor = "0";
            do {
                RespConnection.ScanResult res = j.scan(cursor, BACKEND_KEY_PREFIX + "*", 64);
                for (String key : res.getResult()) {
                    Map<String, String> hash = j.hgetAll(key);
                    if (hash == null || hash.isEmpty()) continue;
                    BackendHeartbeat hb = decodeBackend(flatten(hash));
                    if (hb != null) backends.put(hb.serverId(), hb);
                }
                cursor = res.getCursor();
            } while (!"0".equals(cursor));
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
        return claim(serverId, playerId, ttl, Optional.empty());
    }

    @Override
    public CompletableFuture<ReservationToken> claim(String serverId, UUID playerId,
                                                     Duration ttl, Optional<String> regionKey) {
        Objects.requireNonNull(serverId, "serverId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(ttl, "ttl");
        Objects.requireNonNull(regionKey, "regionKey");
        checkOpen();
        return CompletableFuture.supplyAsync(
                () -> claimSync(serverId, playerId, ttl, regionKey.orElse(null)), publisherExec);
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
        Object raw;
        try (RespConnection j = pool.getResource()) {
            // Signed mode: Lua cannot compute HMAC-SHA-256, so Java reads and
            // verifies the row first, then redeem.lua CASes on the verified
            // hmac (ARGV[6]); a row rewritten between read and EVALSHA fails
            // the CAS. Fail closed: unsigned / tampered rows are never consumed.
            String expectedHmac = "";
            if (verifier != null) {
                Map<String, String> hash = j.hgetAll(TOKEN_KEY_PREFIX + tokenId);
                RedeemOutcome pre = preVerifyRedeem(tokenId, playerId, expectedServerId, now, hash);
                if (pre != null) return pre;
                expectedHmac = hash.getOrDefault("hmac", "");
            }
            List<String> args = List.of(
                    tokenId,
                    playerId.toString(),
                    expectedServerId,
                    Long.toString(now),
                    Long.toString(now),
                    expectedHmac);
            raw = redeemScript.evalsha(j, keys, args);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "RedisNetworkStateBinding.redeem failed: " + e.getMessage());
            return RedeemOutcome.TRANSPORT_ERROR;
        }
        String result = raw == null ? "" : (raw instanceof byte[] b ? RespProtocol.toUtf8(b) : raw.toString());
        try {
            return RedeemOutcome.valueOf(result);
        } catch (IllegalArgumentException ex) {
            LOG.log(Level.WARNING,
                    "RedisNetworkStateBinding.redeem: unrecognised Lua return '" + result
                            + "' for token " + tokenPrefix(tokenId) + " (REQ-RTP-S-004)");
            return RedeemOutcome.BAD_STATE;
        }
    }

    /**
     * Java-side mirror of redeem.lua's classification plus HMAC verify.
     * Returns a terminal outcome, or {@code null} when the row is a verified
     * CLAIMED/PENDING token that may proceed to the Lua CAS.
     */
    private RedeemOutcome preVerifyRedeem(String tokenId, UUID playerId, String expectedServerId,
                                          long now, Map<String, String> hash) {
        if (hash == null || hash.isEmpty()) return RedeemOutcome.NOT_FOUND;
        String rowServer = hash.get("serverId");
        String rowPlayer = hash.get("playerId");
        if (!expectedServerId.equals(rowServer) || !playerId.toString().equals(rowPlayer)) {
            return RedeemOutcome.WRONG_SERVER;
        }
        String state = hash.getOrDefault("state", "");
        if ("CONSUMED".equals(state) || "RELEASED".equals(state)) return RedeemOutcome.ALREADY_CONSUMED;
        long expires;
        try {
            expires = Long.parseLong(hash.getOrDefault("expiresAtMs", "0"));
        } catch (NumberFormatException ex) {
            expires = -1L;
        }
        if (expires > 0 && expires <= now) return RedeemOutcome.EXPIRED;
        if (!"CLAIMED".equals(state) && !"PENDING".equals(state)) return RedeemOutcome.BAD_STATE;
        if (!verifyTokenHash(tokenId, hash, state)) {
            LOG.log(Level.WARNING,
                    "RedisNetworkStateBinding.redeem: HMAC verification failed for token "
                            + tokenPrefix(tokenId) + "; refusing to consume (REQ-RTP-S-004)");
            return RedeemOutcome.HMAC_INVALID;
        }
        return null;
    }

    /** Verify a token HASH against its stored hmac; caller guarantees {@code verifier != null}. */
    private boolean verifyTokenHash(String tokenId, Map<String, String> hash, String state) {
        return CanonicalEnvelopes.verifyToken(verifier, schemaVersion,
                tokenId,
                hash.getOrDefault("serverId", ""),
                hash.getOrDefault("playerId", ""),
                hash.getOrDefault("expiresAtMs", "0"),
                hash.getOrDefault("createdAtMs", "0"),
                state,
                hash.get("regionKey"),
                hash.getOrDefault("hmac", ""));
    }

    /** First 8 chars of a token id; full ids are bearer-like and stay out of logs. */
    private static String tokenPrefix(String tokenId) {
        if (tokenId == null) return "null";
        return tokenId.length() <= 8 ? tokenId : tokenId.substring(0, 8);
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
     * backend's boot-time reconcile so the cost is borne once per
     * backend lifecycle, not every tick. Corrupt rows (missing state, bad
     * timestamps, HMAC mismatch) are filtered with WARNING and never raised
     * to the caller, mirroring {@link #findReservationSync(UUID)}.
     */
    private List<ReservationToken> listActiveForServerSync(String serverId) {
        long nowMs = System.currentTimeMillis();
        List<ReservationToken> out = new ArrayList<>();
        try (RespConnection j = pool.getResource()) {
            String cursor = "0";
            do {
                RespConnection.ScanResult res = j.scan(cursor, TOKEN_KEY_PREFIX + "*", 64);
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
                    if (verifier != null && !verifyTokenHash(tokenId, hash, state.name())) {
                        LOG.log(Level.WARNING,
                                "listActiveForServerSync: HMAC verification failed for token "
                                        + tokenPrefix(tokenId) + "; dropping row (REQ-RTP-S-004)");
                        continue;
                    }
                    out.add(new ReservationToken(tokenId, rowServerId, playerId, expires, state,
                            hash.get("regionKey")));
                }
            } while (!"0".equals(cursor));
        } catch (Exception e) {
            LOG.log(Level.WARNING,
                    "RedisNetworkStateBinding.listActiveForServer failed: " + e.getMessage()
                            + " (REQ-RTP-S-004)");
            return List.of();
        }
        return out;
    }

    private ReservationToken claimSync(String serverId, UUID playerId, Duration ttl, String regionKey) {
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
        // Token envelope: Java pre-computes HMAC over the canonical token
        // payload (incl. regionKey) that claim.lua will HSET into the row.
        // Delimiter-bearing fields throw IllegalArgumentException (fail closed).
        String region = (regionKey == null || regionKey.isEmpty()) ? "" : regionKey;
        String hmacHex = "";
        if (verifier != null) {
            hmacHex = CanonicalEnvelopes.signToken(verifier, schemaVersion,
                    tokenId, serverId, playerId.toString(),
                    Long.toString(expires), Long.toString(now),
                    ReservationToken.State.CLAIMED.name(), region);
        } else if (!CanonicalEnvelopes.isSafeField(serverId) || !CanonicalEnvelopes.isSafeField(region)) {
            throw new IllegalArgumentException("claim: serverId/regionKey contains a reserved delimiter character");
        }
        List<String> args = List.of(
                tokenId,
                serverId,
                playerId.toString(),
                Long.toString(expires),
                Long.toString(now),
                Long.toString(ttlSecs),
                hmacHex,
                region);
        Object result;
        try (RespConnection j = pool.getResource()) {
            result = claimScript.evalsha(j, keys, args);
        } catch (Exception e) {
            throw new RuntimeException("RedisNetworkStateBinding.claim failed: " + e.getMessage(), e);
        }
        long rc = (result instanceof Number) ? ((Number) result).longValue() : 0L;
        if (rc != 1L) {
            throw new IllegalStateException(
                    "claim race lost for player " + playerId + " (Redis claim returned " + rc + ")");
        }
        return new ReservationToken(tokenId, serverId, playerId, expires,
                ReservationToken.State.CLAIMED, region.isEmpty() ? null : region);
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
        try (RespConnection j = pool.getResource()) {
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
        try (RespConnection j = pool.getResource()) {
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
        try (RespConnection j = pool.getResource()) {
            // Lookup trace at FINE: token id prefix + state only; never the
            // full row (hmac / full token id stay out of logs).
            String activeKey = TOKEN_ACTIVE_PREFIX + playerId;
            String tokenId = j.get(activeKey);
            if (LOG.isLoggable(Level.FINE)) {
                LOG.log(Level.FINE, "[NETWORK][trace] findReservationSync: player " + playerId
                        + " -> token " + (tokenId == null ? "null" : tokenPrefix(tokenId)));
            }
            if (tokenId == null || tokenId.isEmpty()) return Optional.empty();
            Map<String, String> hash = j.hgetAll(TOKEN_KEY_PREFIX + tokenId);
            if (LOG.isLoggable(Level.FINE)) {
                LOG.log(Level.FINE, "[NETWORK][trace] findReservationSync: token " + tokenPrefix(tokenId)
                        + " state=" + (hash == null ? "missing" : hash.getOrDefault("state", "missing")));
            }
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
            if (expires > 0 && expires <= System.currentTimeMillis()) {
                return Optional.empty();
            }
            // Token envelope: verify HMAC on non-terminal rows. A mismatch
            // (forged row, tampered regionKey, state rewound to CLAIMED, or a
            // pre-v2 signature) drops the row with a REQ-RTP-S-004 WARNING.
            // playerId is pinned to the lookup key, not the row's own field.
            if (verifier != null) {
                Map<String, String> pinned = new java.util.HashMap<>(hash);
                pinned.put("playerId", playerId.toString());
                if (!verifyTokenHash(tokenId, pinned, state.name())) {
                    LOG.log(Level.WARNING,
                            "findReservationSync: HMAC verification failed for token "
                                    + tokenPrefix(tokenId) + "; dropping row (REQ-RTP-S-004)");
                    return Optional.empty();
                }
            }
            return Optional.of(new ReservationToken(
                    tokenId,
                    hash.getOrDefault("serverId", ""),
                    playerId,
                    expires,
                    state,
                    hash.get("regionKey")));
        } catch (Exception e) {
            throw new RuntimeException("RedisNetworkStateBinding.findReservation failed: " + e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<Void> setLastTeleportTime(UUID playerId, long epochMillis) {
        Objects.requireNonNull(playerId, "playerId");
        checkOpen();
        return CompletableFuture.runAsync(() -> {
            try (RespConnection jedis = pool.getResource()) {
                jedis.set(LAST_TP_PREFIX + playerId, String.valueOf(epochMillis));
            } catch (Exception e) {
                LOG.log(Level.WARNING, "setLastTeleportTime failed: " + e.getMessage());
            }
        }, publisherExec);
    }

    @Override
    public CompletableFuture<Long> getLastTeleportTime(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        checkOpen();
        return CompletableFuture.supplyAsync(() -> {
            try (RespConnection jedis = pool.getResource()) {
                String val = jedis.get(LAST_TP_PREFIX + playerId);
                if (val == null || val.isEmpty()) return 0L;
                try {
                    return Long.parseLong(val);
                } catch (NumberFormatException e) {
                    return 0L;
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, "getLastTeleportTime failed: " + e.getMessage());
                return 0L;
            }
        }, publisherExec);
    }

    @Override
    public void close() {
        if (!open.compareAndSet(true, false)) return;
        try { pubSub.unsubscribe(); } catch (Throwable ignored) { /* best-effort */ }
        try { subscriberThread.interrupt(); } catch (Throwable ignored) { /* best-effort */ }
        publisherExec.shutdown();
        if (ownsPool) {
            try { pool.close(); } catch (Throwable ignored) { /* best-effort */ }
        }
    }

    private void checkOpen() {
        if (!open.get()) throw new IllegalStateException("RedisNetworkStateBinding is closed");
    }


    // ---- encode / decode -------------------------------------------------

    private String encodeBackend(BackendHeartbeat r) {
        // Field map / canonical byte sequence are produced by the shared
        // BackendHeartbeatCodec so Redis, SQL, and the plugin-message tier all
        // carry an identical field set and ordering. Both encode (sign) and
        // decode (verify) MUST reconstruct the canonical byte sequence in
        // exactly this order - HSET-read via j.hgetAll() returns a plain
        // HashMap with arbitrary iteration order, so deriving canonical bytes
        // from the parsed map's iteration order would break HMAC verification
        // on every snapshot read (REQ-RTP-S-004, fix 2026-05-21).
        Map<String, String> m = BackendHeartbeatCodec.toFieldMap(r);
        String canonical = canonicalBackend(m);
        StringBuilder sb = new StringBuilder(256).append(canonical);
        if (verifier != null) {
            // A3: append the HMAC line last so decode can strip the 'hmac' key
            // cleanly. Sign covers schemaVersion || '|' || canonical (the rest
            // of the payload, without the hmac line).
            sb.append('\n').append("hmac=").append(verifier.sign(schemaVersion, canonical));
        }
        return sb.toString();
    }

    /**
     * Reconstructs the canonical byte sequence for a backend heartbeat in the
     * fixed field order {@link #encodeBackend(BackendHeartbeat)} signs over.
     * Used by both encode and decode so HMAC sign/verify operate on identical
     * bytes regardless of how the source map was populated (publisher's
     * deterministic LinkedHashMap vs. Jedis's arbitrary-order HashMap from
     * {@code HGETALL}). Missing fields are emitted as empty values to keep
     * the line count fixed; the surrounding decoder rejects malformed rows.
     */
    static String canonicalBackend(Map<String, String> m) {
        return BackendHeartbeatCodec.canonical(m);
    }

    private BackendHeartbeat decodeBackend(String payload) {
        if (payload == null || payload.isEmpty()) return null;
        Map<String, String> m = parseFlat(payload);
        if (verifier != null) {
            String hmacHex = m.remove("hmac");
            int sv;
            try {
                sv = Integer.parseInt(m.getOrDefault("schemaVersion", "1"));
            } catch (NumberFormatException e) {
                sv = 1;
            }
            // Reconstruct canonical bytes in the SAME fixed field order
            // encodeBackend signs over - never from the parsed map's
            // iteration order (HSET-read returns arbitrary-order HashMap).
            if (!verifier.verify(sv, canonicalBackend(m), hmacHex)) {
                LOG.log(Level.WARNING,
                        "decodeBackend: HMAC verification failed; dropping row (REQ-RTP-S-004)");
                return null;
            }
        }
        BackendHeartbeat hb = BackendHeartbeatCodec.fromFieldMap(m);
        if (hb == null) {
            LOG.log(Level.WARNING, "decodeBackend: malformed payload");
        }
        return hb;
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



    private final class Sub implements Subscription {
        final Consumer<BackendHeartbeat> sink;
        final AtomicBoolean closed = new AtomicBoolean(false);
        Sub(Consumer<BackendHeartbeat> sink) { this.sink = sink; }
        @Override public void close() { if (closed.compareAndSet(false, true)) subscribers.remove(this); }
        @Override public boolean isClosed() { return closed.get(); }
    }
}
