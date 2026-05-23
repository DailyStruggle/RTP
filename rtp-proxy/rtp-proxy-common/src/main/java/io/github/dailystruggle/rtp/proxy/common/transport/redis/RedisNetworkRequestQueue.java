package io.github.dailystruggle.rtp.proxy.common.transport.redis;

import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

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
 * Redis-backed {@link NetworkRequestQueue} for L6 Slice D row D4. Wires the
 * four D3 Lua scripts ({@code enqueue_batch}, {@code pollStatus},
 * {@code dequeueReady}, {@code transition}) onto a {@link JedisPool}-managed
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
 * per the Slice C dirty-write contract.</p>
 *
 * <p>Operational invariants of the scripts (atomicity, terminal-state env
 * cleanup, LPOS positioning) are exercised end-to-end by the opt-in
 * {@code RedisNetworkRequestQueueIT} that ships with Slice D row D9.</p>
 */
public final class RedisNetworkRequestQueue implements NetworkRequestQueue, AutoCloseable {

    private static final Logger LOG = Logger.getLogger(RedisNetworkRequestQueue.class.getName());

    /** Master FIFO key. Settled by D3. */
    static final String READY_KEY = "rtp:net:wq:ready";
    /** Correlation-id idempotency SET. Settled by D3. */
    static final String SEEN_KEY = "rtp:net:wq:seen";

    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();

    private final JedisPool pool;
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
    /** Head-scan window for ownership-aware dequeue (rtp-proxy-ADR-016). */
    private static final int OWNED_DEQUEUE_MAX_SCAN = 16;

    /**
     * Production constructor. Opens its own {@link JedisPool} and pre-loads
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
        this(buildPool(host, port, password), true, ttlSeconds);
    }

    /**
     * Pool-injection constructor. Useful when a host already owns a Jedis
     * pool (e.g., shared with {@link RedisNetworkStateBinding}). The caller
     * retains ownership of the pool and is responsible for closing it; this
     * binding's {@link #close()} only shuts down its executor.
     */
    public RedisNetworkRequestQueue(JedisPool pool, int ttlSeconds) {
        this(Objects.requireNonNull(pool, "pool"), false, ttlSeconds);
    }

    private RedisNetworkRequestQueue(JedisPool pool, boolean ownsPool, int ttlSeconds) {
        if (ttlSeconds < 0) {
            throw new IllegalArgumentException("ttlSeconds must be >= 0");
        }
        this.pool = pool;
        this.ownsPool = ownsPool;
        this.ttlSeconds = ttlSeconds;

        // Eagerly validate connectivity. A bad host should fail at open() time,
        // not silently in flush loops.
        try (Jedis j = pool.getResource()) {
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
            this.enqueueBatchScript.scriptLoad(pool);
            this.pollStatusScript.scriptLoad(pool);
            this.dequeueReadyScript.scriptLoad(pool);
            this.dequeueReadyOwnedScript.scriptLoad(pool);
            this.transitionScript.scriptLoad(pool);
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

    private static JedisPool buildPool(String host, int port, String password) {
        JedisPoolConfig cfg = new JedisPoolConfig();
        cfg.setMaxTotal(4);
        cfg.setMaxIdle(2);
        cfg.setMinIdle(1);
        cfg.setTestOnBorrow(true);
        if (password != null && !password.isEmpty()) {
            return new JedisPool(cfg, host, port, 2000, password);
        }
        return new JedisPool(cfg, host, port, 2000);
    }

    // ---- SPI ------------------------------------------------------------

    @Override
    public CompletableFuture<EnrolOutcome> enrol(EnrolmentEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        return flushPending(java.util.Collections.singletonList(envelope));
    }

    @Override
    public CompletableFuture<EnrolOutcome> flushPending(List<EnrolmentEnvelope> batch) {
        Objects.requireNonNull(batch, "batch");
        if (batch.isEmpty()) {
            return CompletableFuture.completedFuture(EnrolOutcome.ACCEPTED);
        }
        return runAsync(() -> {
            List<String> argv = new ArrayList<>(batch.size() * 7);
            long now = System.currentTimeMillis();
            for (EnrolmentEnvelope env : batch) {
                if (env == null) continue;
                argv.add(env.correlationId().toString());
                argv.add(env.playerId().toString());
                argv.add(env.regionKey().orElse(""));
                argv.add(env.serverHint().orElse(""));
                argv.add(Long.toString(env.createdAtMs()));
                argv.add(Integer.toString(ttlSeconds));
                argv.add(Long.toString(now));
            }
            if (argv.isEmpty()) return EnrolOutcome.ACCEPTED;
            try (Jedis j = pool.getResource()) {
                enqueueBatchScript.evalsha(j, Arrays.asList(READY_KEY, SEEN_KEY), argv);
                return EnrolOutcome.ACCEPTED;
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "RedisNetworkRequestQueue.flushPending failed: "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
                throw e;
            }
        });
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
            try (Jedis j = pool.getResource()) {
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
                try { pid = UUID.fromString(String.valueOf(r.get(0))); }
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
            try (Jedis j = pool.getResource()) {
                while (true) {
                    long now = System.currentTimeMillis();
                    Object raw = dequeueReadyScript.evalsha(j,
                            java.util.Collections.singletonList(READY_KEY),
                            Arrays.asList(Long.toString(now), Integer.toString(ttlSeconds)));
                    if (raw instanceof List && !((List<?>) raw).isEmpty()) {
                        Map<String, String> kv = flattenAlternating((List<?>) raw, 0);
                        UUID pid;
                        UUID cid;
                        try {
                            pid = UUID.fromString(kv.getOrDefault("playerId", ""));
                            cid = UUID.fromString(kv.getOrDefault("correlationId", ""));
                        } catch (IllegalArgumentException iae) {
                            continue; // malformed row; drop and re-poll
                        }
                        String region = kv.getOrDefault("regionKey", "");
                        String hint = kv.getOrDefault("serverHint", "");
                        long createdAt = parseLongSafe(kv.get("createdAtMs"), now);
                        long dequeuedAt = parseLongSafe(kv.get("dequeuedAtMs"), now);
                        return Optional.of(new QueueEnvelope(
                                pid, cid,
                                region.isEmpty() ? Optional.empty() : Optional.of(region),
                                hint.isEmpty() ? Optional.empty() : Optional.of(hint),
                                createdAt,
                                dequeuedAt));
                    }
                    if (System.nanoTime() >= deadline) {
                        return Optional.<QueueEnvelope>empty();
                    }
                    // Short sleep before re-polling. Java-level BLPOP would
                    // tie up a Jedis connection for the full block window; a
                    // 25ms poll trades a small wake-cost for connection
                    // availability. See PROPOSAL §4.4.
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
            try (Jedis j = pool.getResource()) {
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
                        Map<String, String> kv = flattenAlternating((List<?>) raw, 0);
                        UUID pid;
                        UUID cid;
                        try {
                            pid = UUID.fromString(kv.getOrDefault("playerId", ""));
                            cid = UUID.fromString(kv.getOrDefault("correlationId", ""));
                        } catch (IllegalArgumentException iae) {
                            continue;
                        }
                        String region = kv.getOrDefault("regionKey", "");
                        String hint = kv.getOrDefault("serverHint", "");
                        long createdAt = parseLongSafe(kv.get("createdAtMs"), now);
                        long dequeuedAt = parseLongSafe(kv.get("dequeuedAtMs"), now);
                        return Optional.of(new QueueEnvelope(
                                pid, cid,
                                region.isEmpty() ? Optional.empty() : Optional.of(region),
                                hint.isEmpty() ? Optional.empty() : Optional.of(hint),
                                createdAt,
                                dequeuedAt));
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
    public CompletableFuture<Optional<QueueStatus>> transition(
            UUID playerId, QueueState next, Optional<String> reason) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(next, "next");
        Optional<String> reasonOpt = (reason == null) ? Optional.empty() : reason;
        return runAsync(() -> {
            String statusKey = "rtp:net:wq:status:" + playerId;
            long now = System.currentTimeMillis();
            Object raw;
            try (Jedis j = pool.getResource()) {
                raw = transitionScript.evalsha(j,
                        Arrays.asList(statusKey, SEEN_KEY),
                        Arrays.asList(
                                next.name(),
                                reasonOpt.orElse(""),
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
            out.put(String.valueOf(k), v == null ? "" : String.valueOf(v));
        }
        return out;
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
