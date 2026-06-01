package io.github.dailystruggle.mapsapi.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Categorical distribution: parallel labels + non-negative counts. Used by the
 * pie / bar renderers (Stage 4) for cache occupancy and {@code FailTypes}
 * breakdowns.
 *
 * <p>The constructor defensively copies {@code labels} and {@code counts} so
 * the caller may mutate the supplied collections after construction
 * (REQ-RTP-MAP-002).
 *
 * @param labels human-readable category labels; size must match {@code counts}
 * @param counts non-negative sample counts per category
 */
public record CategoryDistribution(List<String> labels, List<Long> counts) implements ChartModel {

    public CategoryDistribution {
        Objects.requireNonNull(labels, "labels");
        Objects.requireNonNull(counts, "counts");
        if (labels.size() != counts.size()) {
            throw new IllegalArgumentException(
                    "labels.size=" + labels.size() + " shall equal counts.size=" + counts.size());
        }
        for (String l : labels) {
            Objects.requireNonNull(l, "labels[*]");
        }
        for (Long c : counts) {
            Objects.requireNonNull(c, "counts[*]");
            if (c < 0L) {
                throw new IllegalArgumentException("counts[*] shall be >= 0, got " + c);
            }
        }
        labels = List.copyOf(labels);
        counts = List.copyOf(counts);
    }

    /** Convenience factory from a {@code label -> count} map. Iteration order preserved. */
    public static CategoryDistribution of(Map<String, Long> data) {
        Objects.requireNonNull(data, "data");
        return new CategoryDistribution(List.copyOf(data.keySet()), List.copyOf(data.values()));
    }
}
