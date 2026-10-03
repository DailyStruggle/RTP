package io.github.dailystruggle.rtp.proxy.common.transport.redis;

import io.github.dailystruggle.rtp.proxy.common.spi.PlayerOwnershipTracker;
import io.github.dailystruggle.rtp.proxy.common.transport.redis.resp.RespConnection;
import io.github.dailystruggle.rtp.proxy.common.transport.redis.resp.RespPool;

import java.util.Arrays;
import java.util.Objects;
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
 * Redis-backed {@link PlayerOwnershipTracker} (rtp-proxy-ADR-016).
 *
 * <p>Keyspace: {@code rtp:net:owner:&lt;uuid&gt;} -&gt; {@code proxyId} string,
 * SET with EXPIRE (TTL = 2&times; heartbeat interval; refreshed each pulse).
 * Compare-and-DEL on release uses an inline Lua to avoid clobbering a
 * fresh tag a peer proxy may have just set.</p>
 */
public final class RedisPlayerOwnershipTracker implements PlayerOwnershipTracker {

    private static final Logger LOG = Logger.getLogger(RedisPlayerOwnershipTracker.class.getName());
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();

    private static final String KEY_PREFIX = "rtp:net:owner:";

    /** Compare-and-DEL: removes the key only if its value equals ARGV[1]. */
    private static final String CAS_DEL_LUA =
            "if redis.call('GET', KEYS[1]) == ARGV[1] then "
            + "return redis.call('DEL', KEYS[1]) else return 0 end";

    private final RespPool pool;
    private final boolean ownsPool;
    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public RedisPlayerOwnershipTracker(String host, int port, String password) {
        this(new RespPool(host, port, 2000, password, 2), true);
    }

    public RedisPlayerOwnershipTracker(RespPool pool) {
        this(Objects.requireNonNull(pool, "pool"), false);
    }

    private RedisPlayerOwnershipTracker(RespPool pool, boolean ownsPool) {
        this.pool = pool;
        this.ownsPool = ownsPool;
        try (RespConnection j = pool.getResource()) {
            j.ping();
        } catch (Exception e) {
            if (ownsPool) pool.close();
            throw new IllegalStateException(
                    "RedisPlayerOwnershipTracker: cannot reach redis ("
                            + e.getClass().getSimpleName() + ": " + e.getMessage() + ")", e);
        }
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "rtp-redis-ownership-" + THREAD_COUNTER.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        this.executor = Executors.newSingleThreadExecutor(tf);
    }

    @Override
    public CompletableFuture<Void> claim(UUID playerId, String thisProxyId, int ttlSeconds) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(thisProxyId, "thisProxyId");
        if (thisProxyId.isEmpty()) {
            CompletableFuture<Void> f = new CompletableFuture<>();
            f.completeExceptionally(new IllegalArgumentException("thisProxyId must be non-empty"));
            return f;
        }
        if (ttlSeconds <= 0) {
            CompletableFuture<Void> f = new CompletableFuture<>();
            f.completeExceptionally(new IllegalArgumentException("ttlSeconds must be > 0"));
            return f;
        }
        return runAsync(() -> {
            try (RespConnection j = pool.getResource()) {
                j.setex(KEY_PREFIX + playerId, ttlSeconds, thisProxyId);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "RedisPlayerOwnershipTracker.claim failed for "
                        + playerId + ": " + e.getMessage());
                if (e instanceof RuntimeException re) throw re;
                throw new RuntimeException(e);
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> release(UUID playerId, String thisProxyId) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(thisProxyId, "thisProxyId");
        return runAsync(() -> {
            try (RespConnection j = pool.getResource()) {
                j.eval(CAS_DEL_LUA,
                        java.util.Collections.singletonList(KEY_PREFIX + playerId),
                        java.util.Collections.singletonList(thisProxyId));
            } catch (Exception e) {
                LOG.log(Level.WARNING, "RedisPlayerOwnershipTracker.release failed for "
                        + playerId + ": " + e.getMessage());
                if (e instanceof RuntimeException re) throw re;
                throw new RuntimeException(e);
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<String> ownerOf(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return runAsync(() -> {
            try (RespConnection j = pool.getResource()) {
                String v = j.get(KEY_PREFIX + playerId);
                return v == null ? "" : v;
            }
        });
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        executor.shutdownNow();
        if (ownsPool) {
            try { pool.close(); } catch (RuntimeException ignored) { /* best-effort */ }
        }
    }

    private <T> CompletableFuture<T> runAsync(java.util.concurrent.Callable<T> body) {
        if (closed.get()) {
            CompletableFuture<T> f = new CompletableFuture<>();
            f.completeExceptionally(new IllegalStateException("RedisPlayerOwnershipTracker closed"));
            return f;
        }
        CompletableFuture<T> out = new CompletableFuture<>();
        executor.submit(() -> {
            try { out.complete(body.call()); }
            catch (Throwable t) { out.completeExceptionally(t); }
        });
        return out;
    }

    // Reserved for future use if we need typed arg lists.
    @SuppressWarnings("unused")
    private static java.util.List<String> args(String... a) { return Arrays.asList(a); }
}
