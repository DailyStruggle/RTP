package io.github.dailystruggle.rtp.proxy.common.transport.direct;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Bounded record of request nonces seen by a {@code proxy-direct} listener
 * (REQ-RTP-PROXY-007, rtp-proxy-ADR-010). Consulted only after a request's MAC
 * verifies, so unauthenticated traffic cannot fill it.
 *
 * <p>Each nonce is kept for {@code 2 * MAX_CLOCK_SKEW_MS}: longer than any
 * timestamp the freshness check still accepts, so a repeat inside the window
 * is always seen. When full, expired entries are evicted; if still full the
 * request is refused ({@link Result#FULL}, fail closed) rather than admitted
 * unchecked.</p>
 */
public final class ProxyDirectReplayCache {

    /** Default entry bound. */
    public static final int DEFAULT_MAX_ENTRIES = 65_536;

    /** Default retention per nonce. */
    public static final long DEFAULT_TTL_MS = 2 * ProxyDirectWire.MAX_CLOCK_SKEW_MS;

    /** Outcome of {@link #checkAndRecord}. */
    public enum Result { ACCEPTED, REPLAYED, FULL }

    private final ConcurrentHashMap<String, Long> expiryByNonce = new ConcurrentHashMap<>();
    private final int maxEntries;
    private final long ttlMs;
    private final LongSupplier clock;

    /** Wall-clock cache with default bound and retention. */
    public ProxyDirectReplayCache() {
        this(System::currentTimeMillis);
    }

    /** Default bound and retention on {@code clock}; the listener shares its own clock. */
    public ProxyDirectReplayCache(LongSupplier clock) {
        this(DEFAULT_MAX_ENTRIES, DEFAULT_TTL_MS, clock);
    }

    ProxyDirectReplayCache(int maxEntries, long ttlMs, LongSupplier clock) {
        if (maxEntries <= 0) throw new IllegalArgumentException("maxEntries must be > 0");
        if (ttlMs <= 0) throw new IllegalArgumentException("ttlMs must be > 0");
        this.maxEntries = maxEntries;
        this.ttlMs = ttlMs;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Record {@code nonce} unless it is still live. Size is bounded
     * approximately under concurrency (a check-then-insert race may overshoot
     * by at most the number of concurrent callers).
     */
    public Result checkAndRecord(String nonce) {
        Objects.requireNonNull(nonce, "nonce");
        long now = clock.getAsLong();
        if (!expiryByNonce.containsKey(nonce) && expiryByNonce.size() >= maxEntries) {
            evictExpired(now);
            if (expiryByNonce.size() >= maxEntries) return Result.FULL;
        }
        boolean[] replayed = {false};
        expiryByNonce.compute(nonce, (k, expiry) -> {
            if (expiry != null && expiry > now) {
                replayed[0] = true;
                return expiry;
            }
            return now + ttlMs;
        });
        return replayed[0] ? Result.REPLAYED : Result.ACCEPTED;
    }

    private void evictExpired(long now) {
        expiryByNonce.values().removeIf(expiry -> expiry <= now);
    }

    /** Visible for tests: current entry count (expired entries included until evicted). */
    int size() {
        return expiryByNonce.size();
    }
}
