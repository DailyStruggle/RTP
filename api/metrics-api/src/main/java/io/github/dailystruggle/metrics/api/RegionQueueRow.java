package io.github.dailystruggle.metrics.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable per-region queue and cache status carrier (METRICS_PLAN.md & ADR-078 Phase M2).
 *
 * <p>Captures the per-region player queue depth, hot/cold/login inventory levels,
 * stage occupancy, and zero-I/O reallocation counts. No platform types appear here.
 */
public final class RegionQueueRow {

    public final int playerQueueDepth;
    public final int keptFill;
    public final int keptCap;
    public final int unkeptFill;
    public final int unkeptCap;
    public final Integer loginFill;
    public final Integer loginCap;
    public final RegionQueueStatus status;
    public final Map<String, Integer> stageOccupancy;
    public final int reallocations;

    /**
     * Standard constructor matching METRICS_PLAN.md Phase M2 specification.
     */
    public RegionQueueRow(
            int playerQueueDepth,
            int keptFill,
            int keptCap,
            int unkeptFill,
            int unkeptCap,
            Integer loginFill,
            Integer loginCap,
            RegionQueueStatus status,
            Map<String, Integer> stageOccupancy,
            int reallocations) {
        this.playerQueueDepth = playerQueueDepth;
        this.keptFill = keptFill;
        this.keptCap = keptCap;
        this.unkeptFill = unkeptFill;
        this.unkeptCap = unkeptCap;
        this.loginFill = loginFill;
        this.loginCap = loginCap;
        this.status = (status != null)
                ? status
                : RegionQueueStatus.derive(playerQueueDepth, keptFill, keptCap, unkeptFill);
        this.stageOccupancy = (stageOccupancy == null || stageOccupancy.isEmpty())
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(stageOccupancy));
        this.reallocations = Math.max(0, reallocations);
    }

    /**
     * Minimal convenience constructor deriving {@link RegionQueueStatus} automatically.
     */
    public RegionQueueRow(
            int playerQueueDepth,
            int keptFill,
            int keptCap,
            int unkeptFill,
            int unkeptCap,
            Integer loginFill,
            Integer loginCap) {
        this(playerQueueDepth, keptFill, keptCap, unkeptFill, unkeptCap, loginFill, loginCap,
                null, Collections.emptyMap(), 0);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RegionQueueRow that)) return false;
        return playerQueueDepth == that.playerQueueDepth
                && keptFill == that.keptFill
                && keptCap == that.keptCap
                && unkeptFill == that.unkeptFill
                && unkeptCap == that.unkeptCap
                && reallocations == that.reallocations
                && Objects.equals(loginFill, that.loginFill)
                && Objects.equals(loginCap, that.loginCap)
                && status == that.status
                && Objects.equals(stageOccupancy, that.stageOccupancy);
    }

    @Override
    public int hashCode() {
        return Objects.hash(playerQueueDepth, keptFill, keptCap, unkeptFill, unkeptCap,
                loginFill, loginCap, status, stageOccupancy, reallocations);
    }

    @Override
    public String toString() {
        return "RegionQueueRow{"
                + "playerQueueDepth=" + playerQueueDepth
                + ", keptFill=" + keptFill
                + ", keptCap=" + keptCap
                + ", unkeptFill=" + unkeptFill
                + ", unkeptCap=" + unkeptCap
                + ", loginFill=" + loginFill
                + ", loginCap=" + loginCap
                + ", status=" + status
                + ", stageOccupancy=" + stageOccupancy
                + ", reallocations=" + reallocations
                + '}';
    }
}
