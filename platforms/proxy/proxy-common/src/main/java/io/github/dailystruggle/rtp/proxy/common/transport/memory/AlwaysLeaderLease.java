package io.github.dailystruggle.rtp.proxy.common.transport.memory;

import io.github.dailystruggle.rtp.proxy.common.spi.WaitlistLeaderLease;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Trivial {@link WaitlistLeaderLease} that always returns {@code true} from
 * {@link #tryAcquire(Duration)}.
 *
 * <p>Correct for single-proxy and in-memory devstack deployments where no
 * coordination is needed (one process, one drainer). The Redis-backed
 * lease using {@code SET NX PX} is not yet implemented in the Redis binding.
 */
public final class AlwaysLeaderLease implements WaitlistLeaderLease {

    @Override
    public CompletableFuture<Boolean> tryAcquire(Duration holdFor) {
        Objects.requireNonNull(holdFor, "holdFor");
        return CompletableFuture.completedFuture(Boolean.TRUE);
    }

    @Override
    public CompletableFuture<Void> release() {
        return CompletableFuture.completedFuture(null);
    }
}
