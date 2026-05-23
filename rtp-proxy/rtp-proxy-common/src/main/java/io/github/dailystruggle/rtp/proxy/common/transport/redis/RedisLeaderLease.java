package io.github.dailystruggle.rtp.proxy.common.transport.redis;

import io.github.dailystruggle.rtp.proxy.common.spi.WaitlistLeaderLease;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.params.SetParams;

import java.time.Duration;
import java.util.Collections;
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
 * Redis-backed {@link WaitlistLeaderLease} implementing cross-proxy single-
 * leader election for the shared {@link io.github.dailystruggle.rtp.proxy.common.spi.NetworkWaitlist}
 * drainer. Slice 3a of `CHECKLIST-network-waitlist.md`; design in
 * `rtp-proxy-ADR-015-shared-network-waitlist-and-dynamic-batched-dispatch.md`.
 *
 * <p>Election protocol:
 * <ul>
 *   <li>A {@code holderId} (random UUID generated at construction time)
 *       identifies this instance.</li>
 *   <li>{@code tryAcquire(holdFor)} issues {@code SET key holderId NX PX <ms>}.
 *       If it succeeds, this proxy is the leader for {@code holdFor}.</li>
 *   <li>If {@code SET NX} fails but the existing key value already equals
 *       this proxy's {@code holderId}, the lease is re-extended via
 *       {@code SET key holderId XX PX <ms>} (idempotent re-acquire by the
 *       same holder, per SPI contract).</li>
 *   <li>Otherwise another proxy holds it; the call resolves {@code false}.</li>
 * </ul>
 *
 * <p>{@link #release()} uses a compare-and-delete script so a proxy never
 * stomps a successor's lease that took over after its TTL expired.</p>
 *
 * <p>Mirrors the {@link JedisPool} + single-thread async executor pattern of
 * {@link RedisNetworkRequestQueue} and {@link RedisNetworkStateBinding}. The
 * Lua atomicity of the rest of the {@code RedisNetworkWaitlist} impl
 * (drain-batch, remove-by-uuid, position, reap, refreshAllTtl) is Slice 3b.</p>
 *
 * <p><strong>S-004 contract:</strong> every async path either resolves to a
 * typed boolean / void or completes exceptionally carrying the underlying
 * Jedis throwable; no silent swallow.</p>
 */
public final class RedisLeaderLease implements WaitlistLeaderLease, AutoCloseable {

    private static final Logger LOG = Logger.getLogger(RedisLeaderLease.class.getName());

    /** Default election key. */
    public static final String DEFAULT_KEY = "rtp:net:waitlist:leader";

    /**
     * Compare-and-delete Lua. Returns {@code 1} when the holder matched and
     * the key was deleted, {@code 0} otherwise. Avoids the classic "TTL
     * expires, successor acquires, original holder DELs the successor's
     * lease" stomp window.
     */
    static final String COMPARE_AND_DELETE_LUA =
            "if redis.call('GET', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('DEL', KEYS[1]) "
                    + "else return 0 end";

    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();

    private final JedisPool pool;
    private final boolean ownsPool;
    private final ExecutorService executor;
    private final String key;
    private final String holderId;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Production constructor. Opens its own {@link JedisPool} against
     * {@code host}/{@code port}, uses the default key, and generates a fresh
     * random {@code holderId}.
     *
     * @param host     Redis host.
     * @param port     Redis port.
     * @param password optional Redis password ({@code null} or empty disables AUTH).
     */
    public RedisLeaderLease(String host, int port, String password) {
        this(buildPool(host, port, password), true, DEFAULT_KEY, UUID.randomUUID().toString());
    }

    /**
     * Test-friendly / DI-friendly constructor. Caller owns {@code pool} and
     * is responsible for closing it; supply a custom {@code key} when
     * multiple lease scopes coexist on one Redis (e.g., per-cluster).
     */
    public RedisLeaderLease(JedisPool pool, String key, String holderId) {
        this(Objects.requireNonNull(pool, "pool"), false, key, holderId);
    }

    private RedisLeaderLease(JedisPool pool, boolean ownsPool, String key, String holderId) {
        this.pool = pool;
        this.ownsPool = ownsPool;
        this.key = Objects.requireNonNull(key, "key");
        this.holderId = Objects.requireNonNull(holderId, "holderId");
        this.executor = Executors.newSingleThreadExecutor(threadFactory());
    }

    private static JedisPool buildPool(String host, int port, String password) {
        JedisPoolConfig cfg = new JedisPoolConfig();
        cfg.setMaxTotal(2);
        cfg.setMaxIdle(1);
        cfg.setMinIdle(0);
        return (password == null || password.isEmpty())
                ? new JedisPool(cfg, host, port, 2000)
                : new JedisPool(cfg, host, port, 2000, password);
    }

    private ThreadFactory threadFactory() {
        return r -> {
            Thread t = new Thread(r, "rtp-redis-leader-lease-" + THREAD_COUNTER.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    /** Visible for tests. */
    String holderId() {
        return holderId;
    }

    /** Visible for tests. */
    String key() {
        return key;
    }

    @Override
    public CompletableFuture<Boolean> tryAcquire(Duration holdFor) {
        Objects.requireNonNull(holdFor, "holdFor");
        if (holdFor.isNegative() || holdFor.isZero()) {
            CompletableFuture<Boolean> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalArgumentException(
                    "holdFor must be > 0; got " + holdFor));
            return failed;
        }
        if (closed.get()) {
            return CompletableFuture.completedFuture(Boolean.FALSE);
        }
        long pxMillis = Math.max(1L, holdFor.toMillis());
        return CompletableFuture.supplyAsync(() -> {
            try (Jedis j = pool.getResource()) {
                // First try SET NX PX — the common case when no leader holds it.
                SetParams nx = SetParams.setParams().nx().px(pxMillis);
                String r = j.set(key, holderId, nx);
                if ("OK".equals(r)) {
                    return Boolean.TRUE;
                }
                // SET NX failed: someone holds the key. If it's us, extend
                // the TTL idempotently with SET XX PX (re-acquire by same
                // holder per SPI contract).
                String current = j.get(key);
                if (holderId.equals(current)) {
                    SetParams xx = SetParams.setParams().xx().px(pxMillis);
                    String r2 = j.set(key, holderId, xx);
                    return Boolean.valueOf("OK".equals(r2));
                }
                return Boolean.FALSE;
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "RedisLeaderLease.tryAcquire failed for key " + key, e);
                throw e;
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> release() {
        if (closed.get()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> {
            try (Jedis j = pool.getResource()) {
                j.eval(COMPARE_AND_DELETE_LUA,
                        Collections.singletonList(key),
                        Collections.singletonList(holderId));
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "RedisLeaderLease.release failed for key " + key, e);
                throw e;
            }
        }, executor);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        executor.shutdown();
        if (ownsPool) {
            try {
                pool.close();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "RedisLeaderLease.close: pool close failed", e);
            }
        }
    }
}
