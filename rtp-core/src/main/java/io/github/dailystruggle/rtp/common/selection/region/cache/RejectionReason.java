package io.github.dailystruggle.rtp.common.selection.region.cache;

/**
 * Why a stage transition declined to promote an entry (ADR-078).
 *
 * <p>A rejection always carries one of these, so no promotion failure can be discarded
 * without a stated cause (REQ-RTP-S-004).
 */
public enum RejectionReason {
    /** Destination block was not a safe teleport target (REQ-RTP-S-001). */
    UNSAFE_BLOCK,
    /** Candidate fell in a biome the region excludes. */
    BIOME_EXCLUDED,
    /** Candidate fell outside the region shape, distance, or vertical bounds. */
    OUT_OF_BOUNDS,
    /** Candidate fell in claim-protected land (REQ-RTP-S-003). */
    CLAIM_PROTECTED,
    /** The async chunk reservation could not be acquired. */
    RESERVATION_FAILED,
    /** The per-pulse promotion budget was already spent. */
    BUDGET_EXHAUSTED,
    /** The host is shutting down and no new residency may be established. */
    SHUTTING_DOWN,
    /** The transition completed exceptionally; the throwable is logged at WARNING. */
    ERROR
}
