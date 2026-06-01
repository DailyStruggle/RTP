package io.github.dailystruggle.mapsapi.model;

import java.util.Objects;

/**
 * Snapshot of an Archimedean-spiral region's coverage state: which spiral
 * indices have been attempted, succeeded, or are still pending. Consumed by
 * the {@code RegionCoverageRenderer} (Stage 2.8) to visualise live spiral
 * progress on a player's map.
 *
 * <p>State bytes use the following encoding:
 * <ul>
 *   <li>{@code 0} — unattempted</li>
 *   <li>{@code 1} — attempted, succeeded</li>
 *   <li>{@code 2} — attempted, failed</li>
 *   <li>{@code 3} — currently pending in the pipeline</li>
 * </ul>
 *
 * <p>The constructor defensively copies {@code states} (REQ-RTP-MAP-002).
 *
 * @param regionName name of the {@code Region} this snapshot was taken from
 * @param centerX    spiral centre block-X (purely informational; not used by
 *                   the renderer's pixel maths)
 * @param centerZ    spiral centre block-Z
 * @param radius     spiral radius in blocks
 * @param states     row-major {@code (2*radius+1) × (2*radius+1)} state grid
 */
public record RegionCoverage(String regionName, int centerX, int centerZ,
                             int radius, byte[] states) implements ChartModel {

    public RegionCoverage {
        Objects.requireNonNull(regionName, "regionName");
        if (radius <= 0) {
            throw new IllegalArgumentException("radius shall be > 0, got " + radius);
        }
        Objects.requireNonNull(states, "states");
        int side = 2 * radius + 1;
        if (states.length != side * side) {
            throw new IllegalArgumentException(
                    "states.length=" + states.length + " shall equal (2*radius+1)^2=" + (side * side));
        }
        states = states.clone();
    }

    /** Defensive accessor — returns a clone of the internal state array. */
    @Override
    public byte[] states() {
        return states.clone();
    }

    /** Side length of the square state grid: {@code 2 * radius + 1}. */
    public int side() {
        return 2 * radius + 1;
    }
}
