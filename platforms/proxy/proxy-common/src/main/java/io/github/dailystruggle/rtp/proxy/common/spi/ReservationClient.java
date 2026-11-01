package io.github.dailystruggle.rtp.proxy.common.spi;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Convenience helper that composes a {@link BackendSelector} with
 * {@link NetworkTransport#claim} under the capped-retry chain
 * (REQ-RTP-PROXY-COMMON-004). Reference implementation lives under
 * {@code …proxy.common.reservation}; adapters may swap it out.
 *
 * <p>rtp-proxy-ADR-001 section 5; rtp-proxy-ADR-004 section Fallback Chain.</p>
 */
public interface ReservationClient {

    CompletableFuture<ClaimResult> obtain(RtpRequest request);

    /**
     * Result of {@link #obtain}: either a {@link ReservationToken} held in
     * the CLAIMED state or a {@link DispatchOutcome.Failed} reason explaining
     * why the capped-retry chain exhausted.
     */
    record ClaimResult(
            Optional<ReservationToken> token,
            Optional<DispatchOutcome.Failed> failure
    ) {
        public ClaimResult {
            Objects.requireNonNull(token, "token");
            Objects.requireNonNull(failure, "failure");
            if (token.isPresent() == failure.isPresent()) {
                throw new IllegalArgumentException(
                        "Exactly one of token / failure must be present");
            }
        }

        public static ClaimResult success(ReservationToken token) {
            return new ClaimResult(Optional.of(token), Optional.empty());
        }

        public static ClaimResult failure(DispatchOutcome.Failed failure) {
            return new ClaimResult(Optional.empty(), Optional.of(failure));
        }

        public boolean isSuccess() {
            return token.isPresent();
        }
    }
}
