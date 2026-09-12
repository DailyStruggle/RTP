package io.github.dailystruggle.rtp.common.selection.region.cache;

import java.util.concurrent.CompletableFuture;

/**
 * An asynchronous promotion edge between two pipeline stages (ADR-078).
 *
 * <p>Transitions run off the main thread (REQ-RTP-S-005): screening transitions read
 * region files through the Anvil I/O pool, promotion transitions acquire chunk
 * reservations asynchronously.
 *
 * <p>A transition never returns an empty result without a cause and never swallows an
 * exception (REQ-RTP-S-004); exceptional completion maps to
 * {@link RejectionReason#ERROR}, is logged at WARNING, and disposes the source entry.
 * Promotion is idempotent per entry: the source entry is removed from its stage before
 * the transition starts, so a failed promotion returns a bare coordinate to the cold
 * stage rather than duplicating it.
 *
 * @param <From> source stage entry type
 * @param <To>   destination stage entry type
 */
@FunctionalInterface
public interface StageTransition<From, To> {
    /**
     * Attempts to promote one entry to the destination stage's residency class.
     *
     * @param source the entry already removed from the source stage.
     * @return a future outcome; a decline carries a {@link RejectionReason}.
     */
    CompletableFuture<TransitionOutcome<To>> promote(From source);
}
