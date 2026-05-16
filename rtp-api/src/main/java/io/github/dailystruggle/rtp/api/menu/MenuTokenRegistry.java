package io.github.dailystruggle.rtp.api.menu;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Mint / consume registry for single-use, player-bound, TTL-expiring menu
 * action tokens (ADR-035 §Click handling).
 *
 * <p>Tokens exist to make the click round-trip safe against replay: a player
 * who copies a token string from a screenshot or log can use it exactly once,
 * only as themselves, and only until it expires. Concrete implementations
 * live in {@code rtp-core} ({@code LocalMenuTokenRegistry}); cross-server
 * sharing is out of scope per the 2026-05-15 amendment to ADR-035.
 *
 * <p><b>Atomicity.</b> {@link #consume} must be a single-shot compare-and-set:
 * concurrent invocations with the same token shall succeed for at most one
 * caller. Failed consumes (unknown / expired / wrong player) return
 * {@link Optional#empty()} and must be logged through {@code RTP.log} by the
 * caller per REQ-RTP-S-004; the configurable rejection message is governed by
 * REQ-RTP-F-013 / REQ-RTP-S-007.
 *
 * <p><b>Bounds.</b> Implementations enforce a per-player cap on outstanding
 * tokens (default {@code 256}, key {@code menu.maxOutstandingTokensPerPlayer})
 * via oldest-evict-first; producers may observe the current count via
 * {@link #outstandingFor} to paginate large menus rather than overrun the cap.
 *
 * <p><b>Lifecycle.</b> All methods must throw {@link IllegalStateException}
 * (never silently no-op) when invoked before {@code rtp-core} has loaded,
 * per REQ-RTP-S-006.
 */
public interface MenuTokenRegistry {

    /**
     * Mint a new opaque single-use token bound to {@code playerId} that will
     * dispatch {@code action} when consumed within {@code ttl}.
     *
     * @return the token string (≥ 96 bits of entropy, URL-safe).
     * @throws NullPointerException     if any argument is {@code null}.
     * @throws IllegalArgumentException if {@code ttl} is zero or negative.
     * @throws IllegalStateException    per REQ-RTP-S-006.
     */
    String mint(UUID playerId, MenuAction action, Duration ttl);

    /**
     * Atomically validate and consume a token.
     *
     * @return the stored {@link MenuAction} on success; empty on any failure
     *         (unknown token, expired, already consumed, wrong player).
     * @throws NullPointerException  if any argument is {@code null}.
     * @throws IllegalStateException per REQ-RTP-S-006.
     */
    Optional<MenuAction> consume(UUID playerId, String token);

    /**
     * @return the count of un-expired, un-consumed tokens currently held for
     *         {@code playerId}. Producers may use this to paginate.
     * @throws IllegalStateException per REQ-RTP-S-006.
     */
    int outstandingFor(UUID playerId);
}
