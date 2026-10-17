package io.github.dailystruggle.rtp.common.selection.region;

/**
 * Immutable per-region view of the cost-governed on-demand chunk reader and native admission gate (ADR-114).
 *
 * @param region               region name
 * @param mode                 current decision mode: {@code "BYPASS"} (direct native load) or {@code "PREFILTER"} (off-thread Anvil pre-filter)
 * @param pReject              empirical candidate rejection rate
 * @param ewmaFileReadMs       EWMA off-thread region-file read latency (C_file, async)
 * @param ewmaNativeLoadMs     EWMA native chunk load worker latency (C_native, async)
 * @param ewmaNativeSyncMs     EWMA native chunk load sync admission latency (C_native, sync)
 * @param dedupThreshold       current deduplication threshold: C_file / (C_native + W_tick * C_sync)
 * @param bypassedAnvilReads   candidates that bypassed Anvil pre-filter directly to native load
 * @param prefilteredAnvilReads candidates that ran off-thread Anvil pre-filter
 * @param activeGenerations    concurrent native generations currently in flight in this region
 * @param throttledGenerations total generation attempts delayed or rejected due to semaphore bounds
 * @param permitsAvailable     remaining regional generation permits (0 or 1)
 */
public record ImmediateGovernorRow(
        String region,
        String mode,
        double pReject,
        double ewmaFileReadMs,
        double ewmaNativeLoadMs,
        double ewmaNativeSyncMs,
        double dedupThreshold,
        long bypassedAnvilReads,
        long prefilteredAnvilReads,
        int activeGenerations,
        long throttledGenerations,
        int permitsAvailable) {
}
