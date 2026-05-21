package io.github.dailystruggle.rtp.common.commands.maps;

import io.github.dailystruggle.rtp.api.maps.ChartSpec;
import io.github.dailystruggle.rtp.common.RTP;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Short-lived, single-use registry of {@link ChartSpec} instances keyed by a
 * UUID token.
 *
 * <p>Implements {@code CHECKLIST-metrics-to-maps.md} Stage 2 items 2.4 / 2.5
 * and satisfies REQ-RTP-MAP-006. Mirrors the security boundary established by
 * {@code LocalMenuTokenRegistry} (ADR-035 §3): the menu surface never embeds a
 * raw command or spec, so a malicious renderer cannot smuggle a fabricated
 * chart request through a click event. The token is minted server-side when
 * the {@code /rtp info} page is composed, attached to a {@code MenuAction.OpenMap}
 * fragment, and consumed exactly once when the player clicks.
 *
 * <p>Differences from {@code LocalMenuTokenRegistry}:
 * <ul>
 *   <li>No per-player cap or FIFO eviction. Charts are produced server-side on
 *       a known composition cadence (per {@code /rtp info} render), not by
 *       arbitrary user input, so the registry's upper bound is governed by
 *       TTL alone.</li>
 *   <li>Token is a raw {@link UUID}, not a base32 string. Tokens are passed
 *       around as the click payload of {@link io.github.dailystruggle.rtp.api.menu.MenuAction}
 *       (Stage 2.1) which can carry typed fields directly.</li>
 *   <li>The {@code playerId} is stored alongside the spec so the consume path
 *       can reject mismatched-player click attempts (a token minted for player
 *       A is invalid for player B even within the TTL window).</li>
 * </ul>
 *
 * <p>All failure paths return {@link Optional#empty()} per the registry's
 * consume contract; callers (notably {@code OpenMapActionHandler} on the
 * Bukkit side, Stage 2.10) are responsible for logging the rejection through
 * {@link RTP#log(Level, String)} and routing the user-facing message through
 * {@code messages.yml} per REQ-RTP-S-004 / S-007 / F-013 (the existing
 * {@code mapBindingMissing} / {@code mapResolverMissing} / {@code mapUnavailable}
 * / {@code mapBusy} keys cover the downstream {@code MapDispatch} surfaces;
 * an expired or unknown token surfaces as a silent no-op from the click,
 * consistent with how stale menu-redeem tokens behave).
 *
 * <p>Cross-server sharing is intentionally out of scope (mirrors the
 * 2026-05-15 amendment to ADR-035 that struck shared menu token storage); a
 * future proxy-aware implementation will live alongside this class without
 * changing the public surface.
 */
public final class ChartSpecTokens {

    /** Default TTL when {@link #mint(UUID, ChartSpec)} is called. */
    public static final Duration DEFAULT_TTL = Duration.ofSeconds(60);

    /** Default sweep period when {@link #scheduleSweeps()} is invoked. */
    public static final Duration DEFAULT_SWEEP_PERIOD = Duration.ofSeconds(30);

    private final ConcurrentHashMap<UUID, Entry> entries = new ConcurrentHashMap<>();

    public ChartSpecTokens() {
    }

    /**
     * Mint a token bound to {@code playerId} for {@code spec}, using
     * {@link #DEFAULT_TTL}.
     */
    public UUID mint(UUID playerId, ChartSpec spec) {
        return mint(playerId, spec, DEFAULT_TTL);
    }

    /**
     * Mint a token bound to {@code playerId} for {@code spec}.
     *
     * @param playerId clicking player; the same uuid must be passed to
     *                 {@link #consume(UUID, UUID)} or the token is rejected.
     * @param spec     the resolved chart request to honour on click.
     * @param ttl      lifetime of the token; expired tokens are swept by
     *                 {@link #sweepExpired(long)} and rejected on consume.
     * @return a freshly-generated UUID token. The token is not embedded in any
     *         persistent message channel: it is intended to be sent only as
     *         part of the in-process {@code MenuAction} payload.
     */
    public UUID mint(UUID playerId, ChartSpec spec, Duration ttl) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive, got " + ttl);
        }
        long expires = nowMillis() + ttl.toMillis();
        UUID token = UUID.randomUUID();
        // UUID.randomUUID is 122 bits of entropy; a collision against a live
        // entry is astronomically unlikely, but if it ever happens we retry
        // rather than silently overwrite (which would let player A's mint
        // invalidate player B's outstanding token).
        while (entries.putIfAbsent(token, new Entry(playerId, spec, expires)) != null) {
            token = UUID.randomUUID();
        }
        return token;
    }

    /**
     * Consume the token for {@code playerId}. Returns the bound {@link ChartSpec}
     * exactly once on success; subsequent calls (or calls with a mismatched
     * player or expired token) return {@link Optional#empty()}.
     */
    public Optional<ChartSpec> consume(UUID playerId, UUID token) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(token, "token");
        Entry entry = entries.get(token);
        if (entry == null) {
            return Optional.empty();
        }
        if (!entry.playerId.equals(playerId)) {
            // Mismatched player: do NOT consume the token. The legitimate
            // owner may still redeem it before TTL.
            return Optional.empty();
        }
        if (entry.expiresAtMillis < nowMillis()) {
            // Expired: best-effort cleanup, then reject.
            entries.remove(token, entry);
            return Optional.empty();
        }
        // CAS: only one caller observes a successful remove for this (key, value) pair.
        if (!entries.remove(token, entry)) {
            return Optional.empty();
        }
        return Optional.of(entry.spec);
    }

    /**
     * Visible for tests: drop every entry whose {@code expiresAtMillis} is
     * &lt; {@code now}.
     *
     * @return number of entries removed.
     */
    public int sweepExpired(long now) {
        int removed = 0;
        Iterator<Map.Entry<UUID, Entry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Entry> e = it.next();
            if (e.getValue().expiresAtMillis < now) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    /** Number of live (not necessarily unexpired) entries; visible for tests. */
    public int size() {
        return entries.size();
    }

    /**
     * Schedule the periodic TTL sweep against {@code RTP.scheduler}. Called once
     * during plugin startup; idempotent in the sense that re-invocation simply
     * schedules an additional timer (callers shouldn't do that).
     *
     * @return scheduler handle (opaque to callers; useful only for cancellation),
     *         or {@code null} if {@code RTP.scheduler} is not yet installed.
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
                        RTP.log(Level.WARNING, "ChartSpecTokens sweep failed", e);
                    }
                },
                periodTicks,
                periodTicks);
    }

    /** Test seam: overridable wall-clock source. */
    long nowMillis() {
        return System.currentTimeMillis();
    }

    private static final class Entry {
        final UUID playerId;
        final ChartSpec spec;
        final long expiresAtMillis;

        Entry(UUID playerId, ChartSpec spec, long expiresAtMillis) {
            this.playerId = playerId;
            this.spec = spec;
            this.expiresAtMillis = expiresAtMillis;
        }
    }
}
