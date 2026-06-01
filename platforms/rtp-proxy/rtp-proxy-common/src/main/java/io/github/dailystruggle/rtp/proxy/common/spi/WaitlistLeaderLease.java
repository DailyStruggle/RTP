package io.github.dailystruggle.rtp.proxy.common.spi;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Cross-proxy single-leader lease guarding the shared
 * {@link NetworkWaitlist} drainer. Slice 2 of `CHECKLIST-network-waitlist.md`;
 * design in `rtp-proxy-ADR-015-shared-network-waitlist-and-dynamic-batched-dispatch.md`.
 *
 * <p>When two or more proxies share one Redis (or SQL) waitlist, only one
 * proxy at a time may pop envelopes off it - otherwise the same envelope
 * is dispatched twice. The lease lets a proxy probe each tick:
 * <ul>
 *   <li>{@code tryAcquire} returns {@code true} for the current leader and
 *       {@code false} for the other proxies.</li>
 *   <li>The leader runs the drain pass.</li>
 *   <li>Losers idle this tick and re-probe next tick.</li>
 *   <li>If the leader dies, its lease TTL expires and another proxy wins
 *       the next probe.</li>
 * </ul>
 *
 * <p>In-memory and single-proxy deployments use
 * {@link io.github.dailystruggle.rtp.proxy.common.transport.memory.AlwaysLeaderLease}
 * which always returns {@code true}. The Redis-backed lease using
 * {@code SET NX PX} is deferred to Slice 3.
 *
 * <p><strong>S-004 contract.</strong> Every failure path resolves with a
 * meaningful future. Transport errors complete exceptionally; "lost the
 * race" resolves with {@code false}, never a silent swallow.
 */
public interface WaitlistLeaderLease {

    /**
     * Try to acquire (or extend) the drainer leadership lease for at least
     * {@code holdFor}. Returns {@code true} when this proxy holds the
     * lease at the moment the call resolves, {@code false} when another
     * proxy holds it.
     *
     * <p>Implementations must be idempotent on re-acquisition by the same
     * holder: a leader calling {@code tryAcquire} repeatedly extends its
     * lease rather than failing it.
     */
    CompletableFuture<Boolean> tryAcquire(Duration holdFor);

    /**
     * Release the lease early so another proxy can win the next probe
     * without waiting for {@code holdFor} to elapse. Idempotent; safe to
     * call when the caller does not hold the lease.
     */
    CompletableFuture<Void> release();
}
