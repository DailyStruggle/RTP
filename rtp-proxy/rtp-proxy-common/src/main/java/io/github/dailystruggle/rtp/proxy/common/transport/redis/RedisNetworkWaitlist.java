package io.github.dailystruggle.rtp.proxy.common.transport.redis;

import io.github.dailystruggle.rtp.proxy.common.spi.NetworkWaitlist;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Redis-backed {@link NetworkWaitlist} for cross-proxy shared parking of
 * cross-server {@code /rtp} enrolments that could not be dispatched
 * immediately. Slice 3b of {@code CHECKLIST-network-waitlist.md}; design in
 * {@code rtp-proxy-ADR-015-shared-network-waitlist-and-dynamic-batched-dispatch.md}.
 *
 * <p>Mirrors {@link RedisNetworkRequestQueue}'s shape: a {@link JedisPool}
 * is opened on construction (or injected from a host that already owns
 * one), all SPI calls hop onto a private single-thread executor, and the
 * impl is {@link AutoCloseable}. Pre-loads six Lua scripts via
 * {@link RedisLuaScripts}; SHA1 mismatch refuses to enable per the project
 * convention.
 *
 * <p>Keyspace:
 * <ul>
 *   <li>{@code rtp:net:waitlist:list} - master FIFO {@code LIST<json>} of
 *       {@link WaitEnvelope} payloads (JSON-encoded by this class; the Lua
 *       scripts treat the payload as opaque except for substring matches
 *       on the {@code "playerId":"…"}, {@code "correlationId":"…"}, and
 *       {@code "enrolledAtMs":…} fields).</li>
 *   <li>{@code rtp:net:waitlist:uuids} - SET of playerId strings for O(1)
 *       duplicate rejection and point-membership lookup.</li>
 *   <li>{@code rtp:net:waitlist:cids} - SET of correlationId strings for
 *       idempotent replay guard.</li>
 * </ul>
 *
 * <p><strong>Drain protocol.</strong> The transport-side Lua does not
 * model region-to-backend routing; that knowledge lives in
 * {@code rtp-proxy-common}'s dispatcher and is fed in via the
 * {@code perBackendCaps} argument of
 * {@link #drainBatch(Map, int)}. This impl therefore performs a
 * two-step drain under the {@code WaitlistLeaderLease} single-drainer
 * guarantee: (1) {@code waitlist_peek} returns up to {@code globalCap}
 * envelopes from the head without mutating state, (2) the caller's
 * per-backend caps are honored client-side preserving FIFO order, (3)
 * {@code waitlist_drain_batch} atomically removes the allocated
 * correlationIds returning the payloads that were actually removed. The
 * leader lease guarantees no concurrent drainer races between (1) and (3);
 * concurrent {@link #remove(UUID, CancelReason)} for a quit is tolerated
 * (the LREM returns 0 and that envelope is silently skipped).
 *
 * <p><strong>S-004 contract.</strong> Every async path either resolves
 * with a typed outcome or completes exceptionally with the underlying
 * Jedis throwable. There are no silent swallows.
 */
public final class RedisNetworkWaitlist implements NetworkWaitlist, AutoCloseable {

    private static final Logger LOG = Logger.getLogger(RedisNetworkWaitlist.class.getName());

    /** Master FIFO key. */
    static final String LIST_KEY = "rtp:net:waitlist:list";
    /** PlayerId membership SET (duplicate guard + point-remove). */
    static final String UUID_KEY = "rtp:net:waitlist:uuids";
    /** CorrelationId membership SET (idempotency guard). */
    static final String CID_KEY = "rtp:net:waitlist:cids";

    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();

    private final JedisPool pool;
    private final boolean ownsPool;
    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final int maxSize;

    private final RedisLuaScripts enrolScript;
    private final RedisLuaScripts peekScript;
    private final RedisLuaScripts drainBatchScript;
    private final RedisLuaScripts positionScript;
    private final RedisLuaScripts removeUuidScript;
    private final RedisLuaScripts reapScript;
    private final RedisLuaScripts refreshTtlScript;

    /**
     * Production constructor. Opens its own {@link JedisPool} and pre-loads
     * all six waitlist scripts.
     *
     * @param host     Redis host
     * @param port     Redis port
     * @param password Redis password ({@code null} / empty disables AUTH)
     * @param maxSize  waitlist hard cap; enrol returns
     *                 {@link EnrolOutcome#REJECTED_FULL} at this depth;
     *                 {@code 0} disables the cap (use with caution)
     */
    public RedisNetworkWaitlist(String host, int port, String password, int maxSize) {
        this(buildPool(host, port, password), true, maxSize);
    }

    /**
     * Pool-injection constructor. Useful when a host already owns a Jedis
     * pool. The caller retains ownership of the pool; this binding's
     * {@link #close()} only shuts down its executor.
     */
    public RedisNetworkWaitlist(JedisPool pool, int maxSize) {
        this(Objects.requireNonNull(pool, "pool"), false, maxSize);
    }

    private RedisNetworkWaitlist(JedisPool pool, boolean ownsPool, int maxSize) {
        if (maxSize < 0) {
            throw new IllegalArgumentException("maxSize must be >= 0, got " + maxSize);
        }
        this.pool = pool;
        this.ownsPool = ownsPool;
        this.maxSize = maxSize;

        // Eagerly validate connectivity.
        try (Jedis j = pool.getResource()) {
            j.ping();
        } catch (Exception e) {
            if (ownsPool) pool.close();
            throw new IllegalStateException(
                    "RedisNetworkWaitlist: cannot reach redis ("
                            + e.getClass().getSimpleName() + ": " + e.getMessage() + ")", e);
        }

        try {
            this.enrolScript      = RedisLuaScripts.load("waitlist_enrol");
            this.peekScript       = RedisLuaScripts.load("waitlist_peek");
            this.drainBatchScript = RedisLuaScripts.load("waitlist_drain_batch");
            this.positionScript   = RedisLuaScripts.load("waitlist_position");
            this.removeUuidScript = RedisLuaScripts.load("waitlist_remove_uuid");
            this.reapScript       = RedisLuaScripts.load("waitlist_reap");
            this.refreshTtlScript = RedisLuaScripts.load("waitlist_refresh_ttl");
            this.enrolScript.scriptLoad(pool);
            this.peekScript.scriptLoad(pool);
            this.drainBatchScript.scriptLoad(pool);
            this.positionScript.scriptLoad(pool);
            this.removeUuidScript.scriptLoad(pool);
            this.reapScript.scriptLoad(pool);
            this.refreshTtlScript.scriptLoad(pool);
        } catch (RuntimeException e) {
            if (ownsPool) pool.close();
            throw e;
        }

        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "rtp-redis-waitlist-" + THREAD_COUNTER.incrementAndGet());
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

    // ---- SPI ----------------------------------------------------------------

    @Override
    public CompletableFuture<EnrolOutcome> enrol(WaitEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        if (closed.get()) {
            return CompletableFuture.completedFuture(EnrolOutcome.REJECTED_CLOSED);
        }
        return runAsync(() -> {
            String json = encode(envelope);
            try (Jedis j = pool.getResource()) {
                Object raw = enrolScript.evalsha(j,
                        Arrays.asList(LIST_KEY, UUID_KEY, CID_KEY),
                        Arrays.asList(
                                envelope.playerId().toString(),
                                envelope.correlationId().toString(),
                                json,
                                Integer.toString(maxSize)));
                String s = raw == null ? "" : raw.toString();
                switch (s) {
                    case "ACCEPTED":
                    case "ACCEPTED_IDEMPOTENT":
                        return EnrolOutcome.ACCEPTED;
                    case "REJECTED_FULL":
                        return EnrolOutcome.REJECTED_FULL;
                    case "REJECTED_DUPLICATE":
                        return EnrolOutcome.REJECTED_DUPLICATE;
                    default:
                        LOG.log(Level.WARNING,
                                "RedisNetworkWaitlist.enrol: unexpected reply '" + s + "'");
                        return EnrolOutcome.REJECTED_FULL;
                }
            }
        });
    }

    @Override
    public CompletableFuture<Map<String, List<WaitEnvelope>>> drainBatch(
            Map<String, Integer> perBackendCaps, int globalCap) {
        Objects.requireNonNull(perBackendCaps, "perBackendCaps");
        if (globalCap <= 0 || perBackendCaps.isEmpty() || closed.get()) {
            return CompletableFuture.completedFuture(Collections.emptyMap());
        }
        return runAsync(() -> {
            // 1. Peek head up to globalCap.
            List<String> peeked;
            try (Jedis j = pool.getResource()) {
                Object raw = peekScript.evalsha(j,
                        Collections.singletonList(LIST_KEY),
                        Collections.singletonList(Integer.toString(globalCap)));
                peeked = coerceStrings(raw);
            }
            if (peeked.isEmpty()) {
                return Collections.<String, List<WaitEnvelope>>emptyMap();
            }

            // 2. Allocate per-backend client-side, preserving FIFO.
            Map<String, Integer> remaining = new HashMap<>();
            for (Map.Entry<String, Integer> e : perBackendCaps.entrySet()) {
                Integer cap = e.getValue();
                if (cap != null && cap > 0) {
                    remaining.put(e.getKey(), cap);
                }
            }
            if (remaining.isEmpty()) {
                return Collections.<String, List<WaitEnvelope>>emptyMap();
            }

            Map<String, List<WaitEnvelope>> tentative = new LinkedHashMap<>();
            // Parallel list preserving the order of envelopes we attempt to drain,
            // so the atomic remove script ARGV is FIFO too.
            List<String> drainArgv = new ArrayList<>();
            int taken = 0;
            for (String json : peeked) {
                if (taken >= globalCap) break;
                WaitEnvelope env = decode(json);
                if (env == null) continue;
                String chosen = pickBackend(remaining);
                if (chosen == null) break;
                tentative.computeIfAbsent(chosen, k -> new ArrayList<>()).add(env);
                drainArgv.add(env.correlationId().toString());
                drainArgv.add(env.playerId().toString());
                drainArgv.add(json);
                int after = remaining.get(chosen) - 1;
                if (after <= 0) remaining.remove(chosen);
                else remaining.put(chosen, after);
                taken++;
            }
            if (drainArgv.isEmpty()) {
                return Collections.<String, List<WaitEnvelope>>emptyMap();
            }

            // 3. Atomically remove the allocated envelopes.
            List<String> removed;
            try (Jedis j = pool.getResource()) {
                Object raw = drainBatchScript.evalsha(j,
                        Arrays.asList(LIST_KEY, UUID_KEY, CID_KEY),
                        drainArgv);
                removed = coerceStrings(raw);
            }
            if (removed.size() == taken) {
                // All envelopes successfully removed; return the tentative allocation.
                return (Map<String, List<WaitEnvelope>>) (Map<?, ?>) tentative;
            }

            // 4. Partial drain (a concurrent remove_uuid ran for some entry, or a
            //    drift). Rebuild the allocation map filtering to actually-removed
            //    JSON payloads. The Lua returns them in the order of ARGV triplets
            //    so map back via JSON identity.
            java.util.Set<String> removedSet = new java.util.HashSet<>(removed);
            Map<String, List<WaitEnvelope>> actual = new LinkedHashMap<>();
            int idx = 0;
            for (Map.Entry<String, List<WaitEnvelope>> e : tentative.entrySet()) {
                List<WaitEnvelope> keep = new ArrayList<>();
                for (WaitEnvelope env : e.getValue()) {
                    // drainArgv layout: cid, pid, json (triplets); json sits at idx*3+2
                    String json = drainArgv.get(idx * 3 + 2);
                    if (removedSet.contains(json)) keep.add(env);
                    idx++;
                }
                if (!keep.isEmpty()) actual.put(e.getKey(), keep);
            }
            return actual;
        });
    }

    private static String pickBackend(Map<String, Integer> remaining) {
        // Deterministic for tests: smallest-keyed serverId with cap > 0.
        String pick = null;
        for (Map.Entry<String, Integer> e : remaining.entrySet()) {
            if (e.getValue() <= 0) continue;
            if (pick == null || e.getKey().compareTo(pick) < 0) {
                pick = e.getKey();
            }
        }
        return pick;
    }

    @Override
    public CompletableFuture<Optional<Integer>> position(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return runAsync(() -> {
            try (Jedis j = pool.getResource()) {
                Object raw = positionScript.evalsha(j,
                        Arrays.asList(LIST_KEY, UUID_KEY),
                        Collections.singletonList(playerId.toString()));
                long rank = coerceLong(raw, 0L);
                return rank <= 0 ? Optional.<Integer>empty() : Optional.of((int) rank);
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> remove(UUID playerId, CancelReason reason) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(reason, "reason");
        return runAsync(() -> {
            try (Jedis j = pool.getResource()) {
                Object raw = removeUuidScript.evalsha(j,
                        Arrays.asList(LIST_KEY, UUID_KEY, CID_KEY),
                        Collections.singletonList(playerId.toString()));
                return coerceLong(raw, 0L) > 0L;
            }
        });
    }

    @Override
    public CompletableFuture<Integer> reap(Duration maxAge) {
        Objects.requireNonNull(maxAge, "maxAge");
        if (maxAge.isNegative() || maxAge.isZero()) {
            return CompletableFuture.completedFuture(0);
        }
        return runAsync(() -> {
            long now = System.currentTimeMillis();
            try (Jedis j = pool.getResource()) {
                Object raw = reapScript.evalsha(j,
                        Arrays.asList(LIST_KEY, UUID_KEY, CID_KEY),
                        Arrays.asList(Long.toString(now), Long.toString(maxAge.toMillis())));
                return (int) coerceLong(raw, 0L);
            }
        });
    }

    @Override
    public CompletableFuture<Integer> size() {
        return runAsync(() -> {
            try (Jedis j = pool.getResource()) {
                Long n = j.llen(LIST_KEY);
                return n == null ? 0 : n.intValue();
            }
        });
    }

    @Override
    public CompletableFuture<Integer> refreshAllTtl() {
        return runAsync(() -> {
            long now = System.currentTimeMillis();
            try (Jedis j = pool.getResource()) {
                Object raw = refreshTtlScript.evalsha(j,
                        Collections.singletonList(LIST_KEY),
                        Collections.singletonList(Long.toString(now)));
                return (int) coerceLong(raw, 0L);
            }
        });
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        executor.shutdownNow();
        if (ownsPool) {
            try { pool.close(); } catch (RuntimeException ignored) { /* idempotent */ }
        }
    }

    // ---- helpers ------------------------------------------------------------

    private <T> CompletableFuture<T> runAsync(Callable<T> body) {
        CompletableFuture<T> f = new CompletableFuture<>();
        executor.submit(() -> {
            try {
                f.complete(body.call());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return f;
    }

    /**
     * Minimal JSON encoder for {@link WaitEnvelope}. Field order is fixed and
     * relied upon by the Lua scripts ({@code "playerId"} appears before
     * {@code "correlationId"} and {@code "enrolledAtMs"}); do not reorder.
     */
    static String encode(WaitEnvelope env) {
        StringBuilder sb = new StringBuilder(192);
        sb.append('{');
        sb.append("\"playerId\":\"").append(env.playerId()).append('"');
        sb.append(",\"correlationId\":\"").append(env.correlationId()).append('"');
        sb.append(",\"regionKey\":");
        appendOptString(sb, env.regionKey());
        sb.append(",\"serverHint\":");
        appendOptString(sb, env.serverHint());
        sb.append(",\"originServerId\":").append(jsonString(env.originServerId()));
        sb.append(",\"enrolledAtMs\":").append(env.enrolledAtMs());
        sb.append('}');
        return sb.toString();
    }

    private static void appendOptString(StringBuilder sb, Optional<String> opt) {
        if (opt == null || opt.isEmpty()) { sb.append("null"); return; }
        sb.append(jsonString(opt.get()));
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /**
     * Minimal JSON decoder for {@link WaitEnvelope}. Tolerates field reorder
     * (defensive) but expects the six fields written by {@link #encode}.
     * Returns {@code null} if the payload is malformed.
     */
    static WaitEnvelope decode(String json) {
        try {
            UUID pid = UUID.fromString(extractString(json, "playerId"));
            UUID cid = UUID.fromString(extractString(json, "correlationId"));
            Optional<String> region = extractOptString(json, "regionKey");
            Optional<String> hint   = extractOptString(json, "serverHint");
            String origin = extractString(json, "originServerId");
            long enrolled = extractLong(json, "enrolledAtMs");
            return new WaitEnvelope(pid, cid, region, hint, origin, enrolled);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "RedisNetworkWaitlist.decode: malformed envelope '"
                    + json + "': " + e.getMessage());
            return null;
        }
    }

    private static String extractString(String json, String field) {
        int i = json.indexOf("\"" + field + "\":");
        if (i < 0) throw new IllegalArgumentException("missing field " + field);
        int valStart = i + field.length() + 3;
        if (valStart >= json.length() || json.charAt(valStart) != '"') {
            throw new IllegalArgumentException("field " + field + " is not a string");
        }
        int end = valStart + 1;
        StringBuilder sb = new StringBuilder();
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c == '\\' && end + 1 < json.length()) {
                char n = json.charAt(end + 1);
                switch (n) {
                    case '"':  sb.append('"');  end += 2; continue;
                    case '\\': sb.append('\\'); end += 2; continue;
                    case 'n':  sb.append('\n'); end += 2; continue;
                    case 'r':  sb.append('\r'); end += 2; continue;
                    case 't':  sb.append('\t'); end += 2; continue;
                    default:   sb.append(n);    end += 2; continue;
                }
            }
            if (c == '"') break;
            sb.append(c);
            end++;
        }
        return sb.toString();
    }

    private static Optional<String> extractOptString(String json, String field) {
        int i = json.indexOf("\"" + field + "\":");
        if (i < 0) return Optional.empty();
        int valStart = i + field.length() + 3;
        if (valStart >= json.length()) return Optional.empty();
        if (json.startsWith("null", valStart)) return Optional.empty();
        return Optional.of(extractString(json, field));
    }

    private static long extractLong(String json, String field) {
        int i = json.indexOf("\"" + field + "\":");
        if (i < 0) throw new IllegalArgumentException("missing field " + field);
        int valStart = i + field.length() + 3;
        int end = valStart;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c == '-' || (c >= '0' && c <= '9')) { end++; continue; }
            break;
        }
        return Long.parseLong(json.substring(valStart, end));
    }

    private static List<String> coerceStrings(Object raw) {
        List<String> out = new ArrayList<>();
        if (!(raw instanceof List)) return out;
        for (Object o : (List<?>) raw) {
            if (o == null) continue;
            if (o instanceof byte[]) out.add(new String((byte[]) o, java.nio.charset.StandardCharsets.UTF_8));
            else out.add(o.toString());
        }
        return out;
    }

    private static long coerceLong(Object raw, long fallback) {
        if (raw == null) return fallback;
        if (raw instanceof Long) return (Long) raw;
        if (raw instanceof Integer) return ((Integer) raw).longValue();
        try { return Long.parseLong(raw.toString()); }
        catch (NumberFormatException nfe) { return fallback; }
    }
}
