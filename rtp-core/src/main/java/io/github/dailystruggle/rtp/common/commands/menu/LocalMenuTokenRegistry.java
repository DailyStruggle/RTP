package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuTokenRegistry;
import io.github.dailystruggle.rtp.common.RTP;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * In-process {@link MenuTokenRegistry} backed by per-player concurrent hash maps.
 *
 * <p>Implements ADR-035 (amended 2026-05-15) and {@code CHECKLIST-generalized-menu.md}
 * Stage 2 item 2.1:
 *
 * <ul>
 *   <li>Per-player cap (default {@code 256}; key {@code menu.maxOutstandingTokensPerPlayer}),
 *       oldest-evict-first using a per-player FIFO of token strings.</li>
 *   <li>≥ 96 bits of entropy per token (12 random bytes, base32 encoded, URL-safe).</li>
 *   <li>Atomic compare-and-set on consume via {@code ConcurrentHashMap#remove(key, value)} +
 *       {@code Map#remove(key)} re-check (the value is unique per mint, so a successful
 *       removal proves we are the consuming caller).</li>
 *   <li>TTL sweep scheduled on the RTP async scheduler ({@link io.github.dailystruggle.rtp.api.scheduling.RTPScheduler#runTaskTimerAsynchronously
 *       runTaskTimerAsynchronously}); production callers invoke {@link #scheduleSweeps()}
 *       once after {@code RTP.scheduler} is wired. Tests drive the sweep directly via
 *       {@link #sweepExpired(long)}.</li>
 * </ul>
 *
 * <p>Cross-server sharing is intentionally out of scope (struck from ADR-035
 * §{@code SharedMenuTokenRegistry} in the 2026-05-15 amendment); a future
 * proxy-aware implementation will live alongside this class without changing the
 * public {@link MenuTokenRegistry} contract.
 *
 * <p>All failure paths return {@link Optional#empty()} per the
 * {@link MenuTokenRegistry#consume} contract; callers (notably
 * {@code MenuRedeemSubcommand}) are responsible for logging the rejection through
 * {@link RTP#log(Level, String)} and routing the user-facing message through
 * {@code messages.yml} per REQ-RTP-S-004 / S-007 / F-013.
 */
public final class LocalMenuTokenRegistry implements MenuTokenRegistry {

    /** Default value of {@code menu.maxOutstandingTokensPerPlayer}. */
    public static final int DEFAULT_MAX_OUTSTANDING_PER_PLAYER = 256;

    /** Default sweep period when {@link #scheduleSweeps()} is invoked. */
    public static final Duration DEFAULT_SWEEP_PERIOD = Duration.ofSeconds(30);

    /** Base32 alphabet (RFC 4648, upper-case, no padding) for URL-safe token text. */
    private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

    /** 12 random bytes = 96 bits of entropy. */
    private static final int TOKEN_ENTROPY_BYTES = 12;

    private final SecureRandom random;
    private final int maxOutstandingPerPlayer;

    /**
     * Live store: {@code playerId -> token -> entry}. Per-player inner map is
     * concurrent so {@link #consume} can run lock-free against producers.
     */
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<String, Entry>> entries =
            new ConcurrentHashMap<>();

    /**
     * Mint-order FIFO of tokens per player, used to evict the oldest entry when the
     * per-player cap is reached. Mutations are guarded by synchronising on the deque
     * itself; producers add to the tail, eviction polls the head. The deque holds
     * <em>only</em> token strings (not entries) so it stays cheap.
     */
    private final ConcurrentHashMap<UUID, Deque<String>> mintOrder = new ConcurrentHashMap<>();

    public LocalMenuTokenRegistry() {
        this(new SecureRandom(), DEFAULT_MAX_OUTSTANDING_PER_PLAYER);
    }

    public LocalMenuTokenRegistry(int maxOutstandingPerPlayer) {
        this(new SecureRandom(), maxOutstandingPerPlayer);
    }

    /** Test seam — callers may inject a deterministic {@link SecureRandom} for fixtures. */
    public LocalMenuTokenRegistry(SecureRandom random, int maxOutstandingPerPlayer) {
        if (maxOutstandingPerPlayer < 1) {
            throw new IllegalArgumentException(
                    "maxOutstandingPerPlayer must be >= 1, got " + maxOutstandingPerPlayer);
        }
        this.random = Objects.requireNonNull(random, "random");
        this.maxOutstandingPerPlayer = maxOutstandingPerPlayer;
    }

    @Override
    public String mint(UUID playerId, MenuAction action, Duration ttl) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive, got " + ttl);
        }

        String token = newToken();
        long now = nowMillis();
        Entry entry = new Entry(action, now + ttl.toMillis());

        ConcurrentHashMap<String, Entry> playerEntries =
                entries.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());
        Deque<String> order = mintOrder.computeIfAbsent(playerId, k -> new ArrayDeque<>());

        // Race-tolerant in the common case: tokens are unique per call so a duplicate
        // key is astronomically unlikely, but we re-roll just in case.
        while (playerEntries.putIfAbsent(token, entry) != null) {
            token = newToken();
            entry = new Entry(action, now + ttl.toMillis());
        }

        synchronized (order) {
            order.addLast(token);
            // Oldest-evict-first to enforce the per-player cap.
            while (order.size() > maxOutstandingPerPlayer) {
                String evicted = order.pollFirst();
                if (evicted != null) {
                    playerEntries.remove(evicted);
                }
            }
        }

        return token;
    }

    @Override
    public Optional<MenuAction> consume(UUID playerId, String token) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(token, "token");

        ConcurrentHashMap<String, Entry> playerEntries = entries.get(playerId);
        if (playerEntries == null) {
            return Optional.empty();
        }
        Entry entry = playerEntries.get(token);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expiresAtMillis < nowMillis()) {
            // Expired: try to clean it up (best-effort; concurrent sweep may have removed it).
            playerEntries.remove(token, entry);
            return Optional.empty();
        }
        // CAS: only one caller observes a successful remove for this (key, value) pair.
        if (!playerEntries.remove(token, entry)) {
            return Optional.empty();
        }
        Deque<String> order = mintOrder.get(playerId);
        if (order != null) {
            synchronized (order) {
                order.remove(token);
            }
        }
        return Optional.of(entry.action);
    }

    @Override
    public int outstandingFor(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        ConcurrentHashMap<String, Entry> playerEntries = entries.get(playerId);
        if (playerEntries == null) {
            return 0;
        }
        // Cheap O(n) prune of obviously-expired entries so this count is meaningful for
        // paginating producers; full sweep still happens on the timer.
        long now = nowMillis();
        int outstanding = 0;
        for (Map.Entry<String, Entry> e : playerEntries.entrySet()) {
            if (e.getValue().expiresAtMillis < now) {
                playerEntries.remove(e.getKey(), e.getValue());
            } else {
                outstanding++;
            }
        }
        return outstanding;
    }

    /**
     * Visible for tests: drop every entry whose {@code expiresAtMillis} is &lt; {@code now}.
     *
     * @return number of entries removed.
     */
    public int sweepExpired(long now) {
        int removed = 0;
        for (Map.Entry<UUID, ConcurrentHashMap<String, Entry>> playerEntry : entries.entrySet()) {
            ConcurrentHashMap<String, Entry> playerEntries = playerEntry.getValue();
            Iterator<Map.Entry<String, Entry>> it = playerEntries.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Entry> e = it.next();
                if (e.getValue().expiresAtMillis < now) {
                    it.remove();
                    Deque<String> order = mintOrder.get(playerEntry.getKey());
                    if (order != null) {
                        synchronized (order) {
                            order.remove(e.getKey());
                        }
                    }
                    removed++;
                }
            }
        }
        return removed;
    }

    /**
     * Schedule the periodic TTL sweep against {@code RTP.scheduler}. Called once during
     * plugin startup; idempotent in the sense that re-invocation simply schedules an
     * additional timer (callers shouldn't do that).
     *
     * @return scheduler handle (opaque to callers; useful only for cancellation), or
     *         {@code null} if {@code RTP.scheduler} is not yet installed.
     */
    public Object scheduleSweeps() {
        return scheduleSweeps(DEFAULT_SWEEP_PERIOD);
    }

    public Object scheduleSweeps(Duration period) {
        Objects.requireNonNull(period, "period");
        if (period.isZero() || period.isNegative()) {
            throw new IllegalArgumentException("period must be positive, got " + period);
        }
        if (RTP.scheduler == null) {
            return null;
        }
        long periodTicks = Math.max(1L, period.toMillis() / 50L);
        return RTP.scheduler.runTaskTimerAsynchronously(
                () -> {
                    try {
                        sweepExpired(nowMillis());
                    } catch (RuntimeException e) {
                        RTP.log(Level.WARNING, "menu token sweep failed: " + e.getMessage(), e);
                    }
                },
                periodTicks,
                periodTicks);
    }

    private String newToken() {
        byte[] raw = new byte[TOKEN_ENTROPY_BYTES];
        random.nextBytes(raw);
        // Each base32 character encodes 5 bits; 12 bytes = 96 bits = 20 chars (no padding).
        char[] out = new char[(TOKEN_ENTROPY_BYTES * 8 + 4) / 5];
        long buffer = 0;
        int bits = 0;
        int outIdx = 0;
        for (byte b : raw) {
            buffer = (buffer << 8) | (b & 0xFFL);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                out[outIdx++] = BASE32[(int) ((buffer >> bits) & 0x1FL)];
            }
        }
        if (bits > 0) {
            out[outIdx++] = BASE32[(int) ((buffer << (5 - bits)) & 0x1FL)];
        }
        return new String(out, 0, outIdx);
    }

    private static long nowMillis() {
        return System.currentTimeMillis();
    }

    private static final class Entry {
        final MenuAction action;
        final long expiresAtMillis;

        Entry(MenuAction action, long expiresAtMillis) {
            this.action = action;
            this.expiresAtMillis = expiresAtMillis;
        }
    }
}
