package io.github.dailystruggle.rtp.common.selection.region.cache;

import java.util.Optional;

/**
 * The explicit result of a {@link StageTransition} (ADR-078).
 *
 * <p>Deliberately not an {@code Optional}: a promotion that produced nothing must say
 * why, so a failure cannot be silently discarded (REQ-RTP-S-004).
 *
 * @param <To> destination stage entry type
 */
public sealed interface TransitionOutcome<To> {
    /**
     * Whether the transition produced a destination entry.
     *
     * @return {@code true} for {@link Promoted}, {@code false} for {@link Rejected}.
     */
    default boolean isPromoted() {
        return this instanceof Promoted<To>;
    }

    /**
     * The promoted entry, if any.
     *
     * @return the entry for {@link Promoted}, empty for {@link Rejected}.
     */
    default Optional<To> value() {
        return this instanceof Promoted<To> promoted ? Optional.of(promoted.entry()) : Optional.empty();
    }

    /**
     * A successful promotion carrying the destination entry.
     *
     * @param entry the promoted entry; never null.
     * @param <To>  destination stage entry type
     */
    record Promoted<To>(To entry) implements TransitionOutcome<To> {
        /**
         * @param entry the promoted entry; never null.
         */
        public Promoted {
            if (entry == null) throw new IllegalArgumentException("promoted entry must not be null");
        }
    }

    /**
     * A declined promotion, always carrying a stated cause.
     *
     * @param reason machine-readable cause; never null.
     * @param detail human-readable context for logs; never null, may be empty.
     * @param <To>   destination stage entry type
     */
    record Rejected<To>(RejectionReason reason, String detail) implements TransitionOutcome<To> {
        /**
         * @param reason machine-readable cause; never null.
         * @param detail human-readable context for logs; null is normalized to empty.
         */
        public Rejected {
            if (reason == null) throw new IllegalArgumentException("rejection reason must not be null");
            if (detail == null) detail = "";
        }
    }

    /**
     * Convenience factory for a successful promotion.
     *
     * @param entry the promoted entry.
     * @param <To>  destination stage entry type
     * @return a {@link Promoted} outcome.
     */
    static <To> TransitionOutcome<To> promoted(To entry) {
        return new Promoted<>(entry);
    }

    /**
     * Convenience factory for a declined promotion.
     *
     * @param reason machine-readable cause.
     * @param detail human-readable context for logs.
     * @param <To>   destination stage entry type
     * @return a {@link Rejected} outcome.
     */
    static <To> TransitionOutcome<To> rejected(RejectionReason reason, String detail) {
        return new Rejected<>(reason, detail);
    }
}
