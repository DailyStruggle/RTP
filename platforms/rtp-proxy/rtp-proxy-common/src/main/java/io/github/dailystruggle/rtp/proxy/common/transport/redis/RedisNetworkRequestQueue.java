package io.github.dailystruggle.rtp.proxy.common.transport.redis;

import io.github.dailystruggle.rtp.proxy.common.security.HmacVerifier;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue;
import io.github.dailystruggle.rtp.proxy.common.transport.CanonicalEnvelopes;
import io.github.dailystruggle.rtp.proxy.common.transport.redis.resp.RespConnection;
import io.github.dailystruggle.rtp.proxy.common.transport.redis.resp.RespPool;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Redis-backed {@link NetworkRequestQueue}. Wires the
 * four D3 Lua scripts ({@code enqueue_batch}, {@code pollStatus},
 * {@code dequeueReady}, {@code transition}) onto a {@link RespPool}-managed
 * connection. Mirrors the pool + single-thread async executor pattern of
 * {@link RedisNetworkStateBinding}.
 *
 * <p>Keyspace (settled by D3):
 * <ul>
 *   <li>{@code rtp:net:wq:ready} - master FIFO LIST of correlationIds</li>
 *   <li>{@code rtp:net:wq:seen} - correlation-id idempotency SET</li>
 *   <li>{@code rtp:net:wq:env:<cid>} - per-envelope HASH</li>
 *   <li>{@code rtp:net:wq:status:<pid>} - per-player status HASH</li>
 * </ul>
 *
 * <p><strong>S-004 contract:</strong> every async path either returns a
 * completed future with a typed outcome, or a failed future carrying the
 * underlying Jedis throwable. The caller (typically
 * {@code NetworkModeBootstrap}'s sink/supplier adapters) treats a failed
 * future as a transient transport fault and re-enqueues the affected work
 * per the dirty-write contract.</p>
 *
 * <p>Operational invariants of the scripts (atomicity, terminal-state env
 * cleanup, LPOS positioning) are exercised end-to-end by the opt-in
 * {@code RedisNetworkRequestQueueIT}.</p>
 *
 * <p><strong>HMAC envelope (rtp-proxy-ADR-010).</strong> With a verifier,
 * every envelope is signed at flush over
 * {@link CanonicalEnvelopes#canonicalQueueEnvelope} and verified on dequeue;
 * unsigned / tampered / delimiter-bearing envelopes are dropped with a
 * REQ-RTP-S-004 WARNING and never reach the dispatcher. A second envelope
 * for a player with an undequeued entry is skipped (one pending request per
 * player).</p>
 */
public final class RedisNetworkRequestQueue implements NetworkRequestQueue, AutoCloseable {

    private static final Logger LOG = Logger.getLogger(RedisNetworkRequestQueue.class.getName());

    /** Master FIFO key. Settled by D3. */
    static final String READY_KEY = "rtp:net:wq:ready";
    /** Correlation-id idempotency SET. Settled by D3. */
    static final String SEEN_KEY = "rtp:net:wq:seen";

    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();

    private final RespPool pool;
    private final boolean ownsPool;
    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    /** TTL (seconds) applied to per-envelope and per-status HASHes; 0 disables EXPIRE. */
    private final int ttlSeconds;

    private final RedisLuaScripts enqueueBatchScript;
    private final RedisLuaScripts pollStatusScript;
    private final RedisLuaScripts dequeueReadyScript;
    private final RedisLuaScripts dequeueReadyOwnedScript;
    private final RedisLuaScripts transitionScript;
    private final RedisLuaScripts requeueScript;
    /** Head-scan window for ownership-aware dequeue (rtp-proxy-ADR-016). */
    private static final int OWNED_DEQUEUE_MAX_SCAN = 16;
    /** HMAC envelope verifier; {@code null} disables signing and verification. */
    private final HmacVerifier verifier;
    private final int schemaVersion;

    /**
     * Production constructor. Opens its own {@link RespPool} and pre-loads
     * the four D3 scripts; SHA1 mismatch refuses to enable (see
     * {@link RedisLuaScripts#load(String)} - build-time defect).
     *
     * @param host       Redis host
     * @param port       Redis port
     * @param password   Redis password ({@code null} / empty disables AUTH)
     * @param ttlSeconds TTL applied to per-envelope and per-status HASHes;
     *                   pass {@code 0} to disable EXPIRE (entries live until
     *                   their terminal transition deletes them).
     */
    public RedisNetworkRequestQueue(String host, int port, String password, int ttlSeconds) {
        this(buildPool(host, port, password), true, ttlSeconds, null, 1);
    }

    /**
     * Signed production constructor (rtp-proxy-ADR-010). Every participant
     * on the queue (enrolling backends and dequeuing proxies) must share the
     * same secret and {@code schemaVersion}.
     *
     * @param verifier      HMAC verifier; {@code null} disables signing
     * @param schemaVersion schema version passed to {@link HmacVerifier}
     */
    public RedisNetworkRequestQueue(String host, int port, String password, int ttlSeconds,
                                    HmacVerifier verifier, int schemaVersion) {
        this(buildPool(host, port, password), true, ttlSeconds, verifier, schemaVersion);
    }

    /**
     * Pool-injection constructor. Useful when a host already owns a Jedis
     * pool (e.g., shared with {@link RedisNetworkStateBinding}). The caller
     * retains ownership of the pool and is responsible for closing it; this
     * binding's {@link #close()} only shuts down its executor.
     */
    public RedisNetworkRequestQueue(RespPool pool, int ttlSeconds) {
        this(Objects.requireNonNull(pool, "pool"), false, ttlSeconds, null, 1);
    }

    /** Pool-injection constructor with HMAC envelope; see the signed host/port constructor. */
    public RedisNetworkRequestQueue(RespPool pool, int ttlSeconds,
                                    HmacVerifier verifier, int schemaVersion) {
        this(Objects.requireNonNull(pool, "pool"), false, ttlSeconds, verifier, schemaVersion);
    }

    private RedisNetworkRequestQueue(RespPool pool, boolean ownsPool, int ttlSeconds,
                                     HmacVerifier verifier, int schemaVersion) {
        if (ttlSeconds < 0) {
            throw new IllegalArgumentException("ttlSeconds must be >= 0");
        }
        this.pool = pool;
        this.ownsPool = ownsPool;
        this.ttlSeconds = ttlSeconds;
        this.verifier = verifier;
        this.schemaVersion = schemaVersion;

        // Eagerly validate connectivity. A bad host should fail at open() time,
        // not silently in flush loops.
        try (RespConnection j = pool.getResource()) {
            j.ping();
        } catch (Exception e) {
            if (ownsPool) pool.close();
            throw new IllegalStateException(
                    "RedisNetworkRequestQueue: cannot reach redis ("
                            + e.getClass().getSimpleName() + ": " + e.getMessage() + ")", e);
        }

        try {
            this.enqueueBatchScript = RedisLuaScripts.load("enqueue_batch");
            this.pollStatusScript = RedisLuaScripts.load("pollStatus");
            this.dequeueReadyScript = RedisLuaScripts.load("dequeueReady");
            this.dequeueReadyOwnedScript = RedisLuaScripts.load("dequeueReadyOwned");
            this.transitionScript = RedisLuaScripts.load("transition");
            this.requeueScript = RedisLuaScripts.load("requeue");
            this.enqueueBatchScript.scriptLoad(pool);
            this.pollStatusScript.scriptLoad(pool);
            this.dequeueReadyScript.scriptLoad(pool);
            this.dequeueReadyOwnedScript.scriptLoad(pool);
            this.transitionScript.scriptLoad(pool);
            this.requeueScript.scriptLoad(pool);
        } catch (RuntimeException e) {
            if (ownsPool) pool.close();
            throw e;
        }

        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "rtp-redis-wq-" + THREAD_COUNTER.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        this.executor = Executors.newSingleThreadExecutor(tf);
    }

    private static RespPool buildPool(String host, int port, String password) {
        return new RespPool(host, port, 2000, password, 4);
    }

    // ---- SPI ------------------------------------------------------------

    /**
     * Single enrolment. Unlike {@link #flushPending}, a skipped envelope
     * (duplicate player, delimiter-bearing field) resolves
     * {@link EnrolOutcome#REJECTED}.
     */
    @Override
    public CompletableFuture<EnrolOutcome> enrol(EnrolmentEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        return runAsync(() -> {
            int[] counts = enqueue(java.util.Collections.singletonList(envelope));
            return counts[1] > 0 ? EnrolOutcome.REJECTED : EnrolOutcome.ACCEPTED;
        });
    }

    @Override
    public CompletableFuture<EnrolOutcome> flushPending(List<EnrolmentEnvelope> batch) {
        Objects.requireNonNull(batch, "batch");
        if (batch.isEmpty()) {
            return CompletableFuture.completedFuture(EnrolOutcome.ACCEPTED);
        }
        // Skipped envelopes (duplicate player / unsafe field) are logged and
        // the batch still resolves ACCEPTED: the backend buffer re-enqueues a
        // non-ACCEPTED batch, so rejecting would loop forever on a bad row.
        return runAsync(() -> {
            enqueue(batch);
            return EnrolOutcome.ACCEPTED;
        });
    }

    /**
     * Sign + ship envelopes via enqueue_batch.lua.
     *
     * @return {@code {accepted, skipped}} where skipped counts duplicate-player
     *         and delimiter-bearing envelopes (each logged at WARNING)
     */
    private int[] enqueue(List<EnrolmentEnvelope> batch) {
        List<String> argv = new ArrayList<>(batch.size() * 8);
        long now = System.currentTimeMillis();
        int skipped = 0;
        for (EnrolmentEnvelope env : batch) {
            if (env == null) continue;
            String cid = env.correlationId().toString();
            String pid = env.playerId().toString();
            String region = env.regionKey().orElse("");
            String hint = env.serverHint().orElse("");
            String created = Long.toString(env.createdAtMs());
            String hmac;
            try {
                hmac = verifier == null ? "" : CanonicalEnvelopes.signQueueEnvelope(
                        verifier, schemaVersion, cid, pid, region, hint, created);
                if (verifier == null && (!CanonicalEnvelopes.isSafeField(region)
                        || !CanonicalEnvelopes.isSafeField(hint))) {
                    throw new IllegalArgumentException("regionKey/serverHint contains a reserved delimiter character");
                }
            } catch (IllegalArgumentException iae) {
                skipped++;
                LOG.log(Level.WARNING, "RedisNetworkRequestQueue: dropping envelope for player " + pid
                        + " (correlationId=" + cid + "): " + iae.getMessage() + " (REQ-RTP-S-004)");
                continue;
            }
            argv.add(cid);
            argv.add(pid);
            argv.add(region);
            argv.add(hint);
            argv.add(created);
            argv.add(Integer.toString(ttlSeconds));
            argv.add(Long.toString(now));
            argv.add(hmac);
        }
        if (argv.isEmpty()) return new int[]{0, skipped};
        Object raw;
        try (RespConnection j = pool.getResource()) {
            raw = enqueueBatchScript.evalsha(j, Arrays.asList(READY_KEY, SEEN_KEY), argv);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "RedisNetworkRequestQueue.flushPending failed: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            throw e;
        }
        int accepted = 0;
        int duplicates = 0;
        if (raw instanceof List<?> l && l.size() >= 2) {
            accepted = parseIntSafe(asString(l.get(0)), 0);
            duplicates = parseIntSafe(asString(l.get(1)), 0);
        } else if (raw instanceof Number n) {
            accepted = n.intValue();
        }
        if (duplicates > 0) {
            LOG.log(Level.WARNING, "RedisNetworkRequestQueue: skipped " + duplicates
                    + " envelope(s) for players that already have a pending request (REQ-RTP-S-004)");
        }
        return new int[]{accepted, skipped + duplicates};
    }

    @Override
    public CompletableFuture<List<QueueStatus>> pollStatus(List<UUID> playerIds) {
        Objects.requireNonNull(playerIds, "playerIds");
        if (playerIds.isEmpty()) {
            return CompletableFuture.completedFuture(java.util.Collections.emptyList());
        }
        return runAsync(() -> {
            List<String> argv = new ArrayList<>(playerIds.size());
            for (UUID id : playerIds) {
                if (id != null) argv.add(id.toString());
            }
            if (argv.isEmpty()) return java.util.Collections.<QueueStatus>emptyList();
            Object raw;
            try (RespConnection j = pool.getResource()) {
                raw = pollStatusScript.evalsha(j, java.util.Collections.emptyList(), argv);
            }
            List<QueueStatus> out = new ArrayList<>();
            if (!(raw instanceof List)) return out;
            for (Object row : (List<?>) raw) {
                if (!(row instanceof List)) continue;
                List<?> r = (List<?>) row;
                if (r.isEmpty()) continue;
                // Element 0 is the playerId. If that's the only element, the
                // pollStatus script returned the "no live entry" sentinel
                // ({ pid }); skip per SPI contract ("absent from returned list").
                if (r.size() == 1) continue;
                Map<String, String> kv = flattenAlternating(r, 1);
                UUID pid;
                try { pid = UUID.fromString(asString(r.get(0))); }
                catch (IllegalArgumentException iae) { continue; }
                QueueState state = parseState(kv.getOrDefault("state", "UNKNOWN"));
                int pos = parseIntSafe(kv.get("positionInQueue"), 0);
                String serverId = kv.getOrDefault("serverId", "");
                String regionKey = kv.getOrDefault("regionKey", "");
                long updatedAtMs = parseLongSafe(kv.get("updatedAtMs"), 0L);
                out.add(new QueueStatus(
                        pid,
                        state,
                        pos,
                        serverId.isEmpty() ? Optional.empty() : Optional.of(serverId),
                        regionKey.isEmpty() ? Optional.empty() : Optional.of(regionKey),
                        updatedAtMs));
            }
            return out;
        });
    }

    @Override
    public CompletableFuture<Optional<QueueEnvelope>> dequeueReady(Duration blockFor) {
        Objects.requireNonNull(blockFor, "blockFor");
        return runAsync(() -> {
            long deadline = System.nanoTime() + Math.max(0L, blockFor.toNanos());
            try (RespConnection j = pool.getResource()) {
                while (true) {
                    long now = System.currentTimeMillis();
                    Object raw = dequeueReadyScript.evalsha(j,
                            java.util.Collections.singletonList(READY_KEY),
                            Arrays.asList(Long.toString(now), Integer.toString(ttlSeconds)));
                    if (raw instanceof List && !((List<?>) raw).isEmpty()) {
                        QueueEnvelope env = toEnvelope(flattenAlternating((List<?>) raw, 0), now);
                        // null = malformed / unverified row, already dropped; fall through to re-poll.
                        if (env != null) return Optional.of(env);
                    }
                    if (System.nanoTime() >= deadline) {
                        return Optional.<QueueEnvelope>empty();
                    }
                    // Short sleep before re-polling. Java-level BLPOP would
                    // tie up a Jedis connection for the full block window; a
                    // 25ms poll trades a small wake-cost for connection
                    // availability.
                    try { Thread.sleep(25L); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return Optional.<QueueEnvelope>empty();
                    }
                }
            }
        });
    }

    @Override
    public CompletableFuture<Optional<QueueEnvelope>> dequeueReady(
            Duration blockFor, String thisProxyId) {
        Objects.requireNonNull(blockFor, "blockFor");
        Objects.requireNonNull(thisProxyId, "thisProxyId");
        if (thisProxyId.isEmpty()) {
            // Empty proxyId would match the empty 'owner == ""' branch in the
            // Lua script, treating every entry as owner-less. Fail loud rather
            // than silently mis-dispatch.
            CompletableFuture<Optional<QueueEnvelope>> f = new CompletableFuture<>();
            f.completeExceptionally(new IllegalArgumentException("thisProxyId must be non-empty"));
            return f;
        }
        return runAsync(() -> {
            long deadline = System.nanoTime() + Math.max(0L, blockFor.toNanos());
            try (RespConnection j = pool.getResource()) {
                while (true) {
                    long now = System.currentTimeMillis();
                    Object raw = dequeueReadyOwnedScript.evalsha(j,
                            java.util.Collections.singletonList(READY_KEY),
                            Arrays.asList(
                                    thisProxyId,
                                    Long.toString(now),
                                    Integer.toString(ttlSeconds),
                                    Integer.toString(OWNED_DEQUEUE_MAX_SCAN)));
                    if (raw instanceof List && !((List<?>) raw).isEmpty()) {
                        QueueEnvelope env = toEnvelope(flattenAlternating((List<?>) raw, 0), now);
                        if (env != null) return Optional.of(env);
                    }
                    if (System.nanoTime() >= deadline) {
                        return Optional.<QueueEnvelope>empty();
                    }
                    // Same 25ms poll as dequeueReady; no foreign-cid busy-spin
                    // because the script returns {} when no head is eligible.
                    try { Thread.sleep(25L); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return Optional.<QueueEnvelope>empty();
                    }
                }
            }
        });
    }

    @Override
    public boolean supportsRequeue() {
        return true;
    }

    /** Conditional hand-back via requeue.lua; see {@link NetworkRequestQueue#requeue}. */
    @Override
    public CompletableFuture<Boolean> requeue(QueueEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        return runAsync(() -> {
            Object raw;
            try (RespConnection j = pool.getResource()) {
                raw = requeueScript.evalsha(j,
                        java.util.Collections.singletonList(READY_KEY),
                        Arrays.asList(
                                envelope.correlationId().toString(),
                                envelope.playerId().toString(),
                                Long.toString(System.currentTimeMillis()),
                                Integer.toString(ttlSeconds)));
            }
            return raw instanceof Number n ? n.longValue() == 1L : "1".equals(asString(raw));
        });
    }

    @Override
    public CompletableFuture<Optional<QueueStatus>> transition(
            UUID playerId, QueueState next, Optional<String> reason) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(next, "next");
        Objects.requireNonNull(reason, "reason");
        return runAsync(() -> {
            String statusKey = "rtp:net:wq:status:" + playerId;
            long now = System.currentTimeMillis();
            Object raw;
            try (RespConnection j = pool.getResource()) {
                raw = transitionScript.evalsha(j,
                        Arrays.asList(statusKey, SEEN_KEY),
                        Arrays.asList(
                                next.name(),
                                reason.orElse(""),
                                "", // serverId - not surfaced through SPI today
                                Long.toString(now),
                                Integer.toString(ttlSeconds)));
            }
            if (!(raw instanceof List) || ((List<?>) raw).isEmpty()) {
                return Optional.<QueueStatus>empty();
            }
            Map<String, String> kv = flattenAlternating((List<?>) raw, 0);
            QueueState state = parseState(kv.getOrDefault("state", next.name()));
            String serverId = kv.getOrDefault("serverId", "");
            String regionKey = kv.getOrDefault("regionKey", "");
            long updatedAtMs = parseLongSafe(kv.get("updatedAtMs"), now);
            int pos = parseIntSafe(kv.get("positionInQueue"), 0);
            return Optional.of(new QueueStatus(
                    playerId,
                    state,
                    pos,
                    serverId.isEmpty() ? Optional.empty() : Optional.of(serverId),
                    regionKey.isEmpty() ? Optional.empty() : Optional.of(regionKey),
                    updatedAtMs));
        });
    }

    @Override
    public CompletableFuture<Void> cancel(UUID playerId, CancelReason reason) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(reason, "reason");
        return transition(playerId, QueueState.CANCELLED, Optional.of(reason.name()))
                .thenApply(ignored -> null);
    }

    // ---- lifecycle ------------------------------------------------------

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        executor.shutdownNow();
        if (ownsPool) {
            try { pool.close(); } catch (RuntimeException ignored) { /* best-effort */ }
        }
    }

    // ---- helpers --------------------------------------------------------

    private <T> CompletableFuture<T> runAsync(java.util.concurrent.Callable<T> body) {
        if (closed.get()) {
            CompletableFuture<T> f = new CompletableFuture<>();
            f.completeExceptionally(new IllegalStateException("RedisNetworkRequestQueue closed"));
            return f;
        }
        CompletableFuture<T> f = new CompletableFuture<>();
        executor.execute(() -> {
            try { f.complete(body.call()); }
            catch (Throwable t) { f.completeExceptionally(t); }
        });
        return f;
    }

    /**
     * Decode + verify a dequeued envelope. Returns {@code null} (row dropped,
     * WARNING logged on HMAC failure) for malformed ids or a missing /
     * mismatched / delimiter-bearing signature under signed mode. The
     * signed {@code createdAtMs} string is verified verbatim.
     */
    private QueueEnvelope toEnvelope(Map<String, String> kv, long now) {
        String pidStr = kv.getOrDefault("playerId", "");
        String cidStr = kv.getOrDefault("correlationId", "");
        UUID pid;
        UUID cid;
        try {
            pid = UUID.fromString(pidStr);
            cid = UUID.fromString(cidStr);
        } catch (IllegalArgumentException iae) {
            LOG.log(Level.WARNING, "RedisNetworkRequestQueue: dropping malformed envelope (bad ids)");
            return null;
        }
        String region = kv.getOrDefault("regionKey", "");
        String hint = kv.getOrDefault("serverHint", "");
        String createdStr = kv.getOrDefault("createdAtMs", "");
        if (verifier != null && !CanonicalEnvelopes.verifyQueueEnvelope(verifier, schemaVersion,
                cidStr, pidStr, region, hint, createdStr, kv.getOrDefault("hmac", ""))) {
            LOG.log(Level.WARNING, "RedisNetworkRequestQueue: HMAC verification failed for envelope "
                    + cidStr + "; dropping (REQ-RTP-S-004)");
            return null;
        }
        if (verifier == null && (!CanonicalEnvelopes.isSafeField(region) || !CanonicalEnvelopes.isSafeField(hint))) {
            LOG.log(Level.WARNING, "RedisNetworkRequestQueue: dropping envelope " + cidStr
                    + " with delimiter-bearing regionKey/serverHint (REQ-RTP-S-004)");
            return null;
        }
        return new QueueEnvelope(
                pid, cid,
                region.isEmpty() ? Optional.empty() : Optional.of(region),
                hint.isEmpty() ? Optional.empty() : Optional.of(hint),
                parseLongSafe(createdStr, now),
                parseLongSafe(kv.get("dequeuedAtMs"), now));
    }

    /**
     * Folds a flat Lua alternating field/value array (starting at
     * {@code fromIndex}) into a {@code Map<String,String>}. Trailing odd
     * element is dropped.
     */
    private static Map<String, String> flattenAlternating(List<?> flat, int fromIndex) {
        Map<String, String> out = new HashMap<>();
        for (int i = fromIndex; i + 1 < flat.size(); i += 2) {
            Object k = flat.get(i);
            Object v = flat.get(i + 1);
            if (k == null) continue;
            out.put(asString(k), asString(v));
        }
        return out;
    }

    private static String asString(Object o) {
        if (o == null) return "";
        if (o instanceof byte[]) return new String((byte[]) o, java.nio.charset.StandardCharsets.UTF_8);
        return String.valueOf(o);
    }

    private static QueueState parseState(String s) {
        if (s == null || s.isEmpty()) return QueueState.UNKNOWN;
        try { return QueueState.valueOf(s); }
        catch (IllegalArgumentException iae) { return QueueState.UNKNOWN; }
    }

    private static int parseIntSafe(String s, int fallback) {
        if (s == null || s.isEmpty()) return fallback;
        try { return Integer.parseInt(s); }
        catch (NumberFormatException nfe) { return fallback; }
    }

    private static long parseLongSafe(String s, long fallback) {
        if (s == null || s.isEmpty()) return fallback;
        try { return Long.parseLong(s); }
        catch (NumberFormatException nfe) { return fallback; }
    }
}
