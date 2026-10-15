package io.github.dailystruggle.rtp.common.predict;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Context container storing runtime measurements, costs, and state indicators for governor decisions.
 *
 * <p>Immutable and thread-safe (REQ-PRED-NF-001).
 */
public final class CostContext {

    private final Map<String, Double> metrics;

    public CostContext(Map<String, Double> metrics) {
        this.metrics = (metrics == null || metrics.isEmpty())
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new HashMap<>(metrics));
    }

    /**
     * Creates a new builder for constructing a {@link CostContext}.
     *
     * @return builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the metric value for {@code key}, or {@code defaultValue} if absent.
     *
     * @param key          metric identifier
     * @param defaultValue fallback value
     * @return metric value or default
     */
    public double get(String key, double defaultValue) {
        return metrics.getOrDefault(key, defaultValue);
    }

    /**
     * Returns the metric value for {@code key}, or {@code Double.NaN} if absent.
     *
     * @param key metric identifier
     * @return metric value or NaN
     */
    public double get(String key) {
        return metrics.getOrDefault(key, Double.NaN);
    }

    /**
     * Returns {@code true} if the metric is present in this context.
     *
     * @param key metric identifier
     * @return presence
     */
    public boolean has(String key) {
        return metrics.containsKey(key);
    }

    /**
     * Returns an unmodifiable view of all stored metrics.
     *
     * @return map of metrics
     */
    public Map<String, Double> metrics() {
        return metrics;
    }

    /**
     * Builder for fluent {@link CostContext} creation.
     */
    public static final class Builder {
        private final Map<String, Double> map = new HashMap<>();

        public Builder with(String key, double value) {
            map.put(key, value);
            return this;
        }

        public CostContext build() {
            return new CostContext(map);
        }
    }
}
