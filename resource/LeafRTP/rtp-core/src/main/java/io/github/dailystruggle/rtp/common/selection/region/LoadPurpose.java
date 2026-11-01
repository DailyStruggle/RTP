package io.github.dailystruggle.rtp.common.selection.region;

/**
 * Why a location is being generated; decides whether a native (server) chunk load is allowed
 * while verifying it (ADR-110).
 */
public enum LoadPurpose {
    /** A player is waiting on the result: native loads and generation allowed. */
    IMMEDIATE,
    /**
     * Queue or backlog fill: resident chunks and region-file reads first. A miss loads natively,
     * pinned (reservation held until teleport) when a kept slot is free, else unpinned without
     * the success ring; deferred only under heap pressure.
     */
    SPECULATIVE
}
