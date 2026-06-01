package io.github.dailystruggle.mapsapi;

import java.util.Objects;
import java.util.UUID;

/**
 * Allocation parameters passed to {@link MapBinding#allocate(MapAllocationRequest)}.
 * Immutable; the binding produces a {@link MapHandle} based on these values
 * without storing the request itself.
 *
 * @param chartId stable, caller-chosen identifier for the chart that will
 *                populate this map. Used for deduplication ({@code allocate}
 *                may return an existing handle for a known id) and for
 *                operator-facing identification in {@code /rtp map list}.
 * @param viewer  the player this map is allocated to, or {@code null} for a
 *                world-scoped / global map shared across viewers
 * @param locking write policy for the resulting in-game map item
 */
public record MapAllocationRequest(String chartId, UUID viewer, Locking locking) {

    public MapAllocationRequest {
        Objects.requireNonNull(chartId, "chartId");
        if (chartId.isBlank()) {
            throw new IllegalArgumentException("chartId shall not be blank");
        }
        Objects.requireNonNull(locking, "locking");
        // viewer is nullable by contract.
    }

    /** Write-protection policy for the allocated map. */
    public enum Locking {
        /** The map shall be locked: vanilla clients cannot trigger a redraw. */
        LOCKED,
        /** The map remains editable by vanilla {@code MapRenderer} writes. */
        EDITABLE
    }
}
