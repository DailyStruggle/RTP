package io.github.dailystruggle.metrics.api;

/**
 * Derived health status for a region's cache and teleport queue (METRICS_PLAN.md).
 */
public enum RegionQueueStatus {
    /** Queue and hot cache operate within healthy parameters. */
    OK,
    /** Hot cache has dropped below 25% of configured capacity (approaching exhaustion). */
    LOW,
    /** Both hot (kept) and cold (unkept) caches are completely empty; teleports incur full pipeline generation cost. */
    EMPTY,
    /** Waiting players are queued while hot cache is completely exhausted. */
    SATURATED;

    /**
     * Derives the status from queue depth and cache inventory levels per METRICS_PLAN.md.
     *
     * @param playerQueueDepth count of players waiting in queue
     * @param keptFill         current hot cache count
     * @param keptCap          configured hot cache capacity
     * @param unkeptFill       current cold cache count
     * @return derived {@link RegionQueueStatus}
     */
    public static RegionQueueStatus derive(int playerQueueDepth, int keptFill, int keptCap, int unkeptFill) {
        if (playerQueueDepth > 0 && keptFill == 0) {
            return SATURATED;
        }
        if (keptFill == 0 && unkeptFill == 0) {
            return EMPTY;
        }
        if (keptCap > 0 && keptFill < (keptCap / 4)) {
            return LOW;
        }
        return OK;
    }
}
