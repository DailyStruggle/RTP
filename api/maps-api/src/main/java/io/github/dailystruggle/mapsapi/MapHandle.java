package io.github.dailystruggle.mapsapi;

import java.util.Objects;
import java.util.UUID;

/**
 * Opaque handle returned by {@link MapBinding#allocate(MapAllocationRequest)}.
 * Identifies a single allocated map slot on the host runtime.
 *
 * @param chartId stable, caller-chosen identifier for the chart populating this map
 * @param viewer  the player this map is allocated to, or {@code null} for a
 *                world-scoped / global map
 * @param mapId   the host-side map identifier (vanilla {@code MapId} on Bukkit,
 *                {@code MapItemSavedData} id on Fabric)
 */
public record MapHandle(String chartId, UUID viewer, int mapId) {

    public MapHandle {
        Objects.requireNonNull(chartId, "chartId");
        if (chartId.isBlank()) {
            throw new IllegalArgumentException("chartId shall not be blank");
        }
        // viewer is nullable by contract.
    }
}
