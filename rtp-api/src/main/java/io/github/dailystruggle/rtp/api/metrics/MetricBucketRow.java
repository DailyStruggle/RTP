package io.github.dailystruggle.rtp.api.metrics;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable cross-backend metric time-series bucket row (ADR-040, REQ-RTP-METTS-001, REQ-RTP-METTS-003).
 *
 * <p>Natural composite primary key: {@code (backendId, metricKey, windowStartEpoch)}.
 * Every published bucket carries the hybrid payload {@code (sampleCount, mean, min, max)}
 * and optional {@code p95}; single-statistic (mean-only or max-only) payloads are rejected
 * at construction (REQ-RTP-METTS-003).
 *
 * @param backendId        ADR-036 backend server identifier (non-null, non-blank)
 * @param metricKey        dynamically enumerated metric key (non-null, non-blank)
 * @param windowStartEpoch bucket start timestamp in UTC epoch seconds (&gt;= 0)
 * @param windowSeconds    bucket width in seconds (&gt; 0, e.g. 60 for {@code 24h@1m})
 * @param sampleCount      number of samples condensed into this bucket (&gt; 0)
 * @param mean             arithmetic mean across the bucket (finite, within {@code [min, max]})
 * @param min              minimum sample observed in the bucket (finite)
 * @param max              maximum sample observed in the bucket (finite)
 * @param p95              optional 95th percentile sample (nullable; if present, finite and within {@code [min, max]})
 * @param generation       ADR-036 heartbeat generation counter (&gt;= 0)
 */
@PublicApi
public record MetricBucketRow(
    String backendId,
    String metricKey,
    long windowStartEpoch,
    int windowSeconds,
    int sampleCount,
    double mean,
    double min,
    double max,
    @Nullable Double p95,
    long generation) {

  private static final double BOUND_EPSILON = 1e-6;

  /**
   * Validates the hybrid payload invariants (REQ-RTP-METTS-003).
   */
  public MetricBucketRow {
    if (backendId == null || backendId.isBlank()) {
      throw new IllegalArgumentException("backendId must not be null or blank");
    }
    if (metricKey == null || metricKey.isBlank()) {
      throw new IllegalArgumentException("metricKey must not be null or blank");
    }
    if (windowStartEpoch < 0L) {
      throw new IllegalArgumentException(
          "windowStartEpoch must be >= 0, got " + windowStartEpoch);
    }
    if (windowSeconds <= 0) {
      throw new IllegalArgumentException("windowSeconds must be > 0, got " + windowSeconds);
    }
    if (sampleCount <= 0) {
      throw new IllegalArgumentException("sampleCount must be > 0, got " + sampleCount);
    }
    if (!Double.isFinite(mean)) {
      throw new IllegalArgumentException(
          "mean must be finite (max-only / worst-case-only payload rejected per REQ-RTP-METTS-003)");
    }
    if (!Double.isFinite(min) || !Double.isFinite(max)) {
      throw new IllegalArgumentException(
          "min and max must be finite (mean-only payload rejected per REQ-RTP-METTS-003)");
    }
    if (min > max) {
      throw new IllegalArgumentException(
          "min (" + min + ") must not exceed max (" + max + ")");
    }
    double scale = Math.max(1.0, Math.max(Math.abs(min), Math.abs(max)));
    double tol = BOUND_EPSILON * scale;
    if (mean < min - tol || mean > max + tol) {
      throw new IllegalArgumentException(
          "mean (" + mean + ") must lie within [min=" + min + ", max=" + max + "]");
    }
    if (p95 != null) {
      if (!Double.isFinite(p95)) {
        throw new IllegalArgumentException("p95 must be finite when non-null");
      }
      if (p95 < min - tol || p95 > max + tol) {
        throw new IllegalArgumentException(
            "p95 (" + p95 + ") must lie within [min=" + min + ", max=" + max + "]");
      }
    }
    if (generation < 0L) {
      throw new IllegalArgumentException("generation must be >= 0, got " + generation);
    }
  }

  /**
   * Convenience constructor when {@code p95} is not tracked ({@code null}).
   */
  public MetricBucketRow(
      String backendId,
      String metricKey,
      long windowStartEpoch,
      int windowSeconds,
      int sampleCount,
      double mean,
      double min,
      double max,
      long generation) {
    this(
        backendId,
        metricKey,
        windowStartEpoch,
        windowSeconds,
        sampleCount,
        mean,
        min,
        max,
        null,
        generation);
  }

  /** @return backend identifier */
  public String getBackendId() {
    return backendId;
  }

  /** @return metric key */
  public String getMetricKey() {
    return metricKey;
  }

  /** @return window start epoch in seconds */
  public long getWindowStartEpoch() {
    return windowStartEpoch;
  }

  /** @return window width in seconds */
  public int getWindowSeconds() {
    return windowSeconds;
  }

  /** @return sample count */
  public int getSampleCount() {
    return sampleCount;
  }

  /** @return arithmetic mean */
  public double getMean() {
    return mean;
  }

  /** @return minimum value */
  public double getMin() {
    return min;
  }

  /** @return maximum value */
  public double getMax() {
    return max;
  }

  /** @return optional 95th percentile */
  public @Nullable Double getP95() {
    return p95;
  }

  /** @return heartbeat generation */
  public long getGeneration() {
    return generation;
  }

  /**
   * Packs this row into the canonical Redis sorted-set member representation (ADR-040).
   * Format: {@code windowStartEpoch|windowSeconds|sampleCount|mean|min|max|p95|generation}
   *
   * @return packed member string
   */
  public String packRedisMember() {
    return windowStartEpoch
        + "|"
        + windowSeconds
        + "|"
        + sampleCount
        + "|"
        + Double.toString(mean)
        + "|"
        + Double.toString(min)
        + "|"
        + Double.toString(max)
        + "|"
        + (p95 == null ? "" : Double.toString(p95))
        + "|"
        + generation;
  }

  /**
   * Unpacks a Redis sorted-set member into a {@link MetricBucketRow}.
   *
   * @param backendId the backend identifier from the Redis key
   * @param metricKey the metric key from the Redis key
   * @param packed    the packed member string
   * @return unpacked {@link MetricBucketRow}
   * @throws IllegalArgumentException if the packed string is malformed or violates REQ-RTP-METTS-003
   */
  public static MetricBucketRow unpackRedisMember(
      String backendId, String metricKey, String packed) {
    if (packed == null || packed.isBlank()) {
      throw new IllegalArgumentException("packed Redis member must not be null or blank");
    }
    String[] parts = packed.split("\\|", -1);
    if (parts.length != 8) {
      throw new IllegalArgumentException(
          "Invalid packed MetricBucketRow member (expected 8 fields, got "
              + parts.length
              + "): "
              + packed);
    }
    try {
      long windowStartEpoch = Long.parseLong(parts[0]);
      int windowSeconds = Integer.parseInt(parts[1]);
      int sampleCount = Integer.parseInt(parts[2]);
      double mean = Double.parseDouble(parts[3]);
      double min = Double.parseDouble(parts[4]);
      double max = Double.parseDouble(parts[5]);
      Double p95 = parts[6].isEmpty() ? null : Double.valueOf(Double.parseDouble(parts[6]));
      long generation = Long.parseLong(parts[7]);
      return new MetricBucketRow(
          backendId,
          metricKey,
          windowStartEpoch,
          windowSeconds,
          sampleCount,
          mean,
          min,
          max,
          p95,
          generation);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Malformed numeric field in packed member: " + packed, e);
    }
  }

  /**
   * Converts this row to a column map matching the {@code rtp_metric_bucket} schema.
   *
   * @return column map
   */
  public Map<String, Object> toMap() {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("backend_id", backendId);
    map.put("metric_key", metricKey);
    map.put("window_start_epoch", windowStartEpoch);
    map.put("window_seconds", windowSeconds);
    map.put("sample_count", sampleCount);
    map.put("mean", mean);
    map.put("min", min);
    map.put("max", max);
    if (p95 != null) {
      map.put("p95", p95);
    }
    map.put("generation", generation);
    return map;
  }

  /**
   * Constructs a {@link MetricBucketRow} from a raw map, rejecting mean-only or max-only payloads
   * (REQ-RTP-METTS-003).
   *
   * @param map raw field map
   * @return validated {@link MetricBucketRow}
   */
  public static MetricBucketRow fromMap(Map<String, ?> map) {
    Objects.requireNonNull(map, "map");
    Object backendObj = firstNonNull(map, "backend_id", "backendId");
    Object metricObj = firstNonNull(map, "metric_key", "metricKey");
    Object startObj = firstNonNull(map, "window_start_epoch", "windowStartEpoch");
    Object secObj = firstNonNull(map, "window_seconds", "windowSeconds");
    Object countObj = firstNonNull(map, "sample_count", "sampleCount");
    Object meanObj = map.get("mean");
    Object minObj = map.get("min");
    Object maxObj = map.get("max");
    Object p95Obj = map.get("p95");
    Object genObj = map.get("generation");

    double mean = toDoubleOrNaN(meanObj);
    double min = toDoubleOrNaN(minObj);
    double max = toDoubleOrNaN(maxObj);
    Double p95 = (p95Obj == null) ? null : toDoubleOrNaN(p95Obj);

    return new MetricBucketRow(
        backendObj == null ? null : backendObj.toString(),
        metricObj == null ? null : metricObj.toString(),
        toLong(startObj, -1L),
        toInt(secObj, 60),
        toInt(countObj, 0),
        mean,
        min,
        max,
        p95,
        toLong(genObj, 0L));
  }

  /**
   * Creates a builder for {@link MetricBucketRow}. Unset {@code mean}, {@code min}, or {@code max}
   * fields default to {@code Double.NaN} so mean-only or max-only builders fail validation at
   * {@link Builder#build()} per REQ-RTP-METTS-003.
   *
   * @return new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /** Builder for {@link MetricBucketRow}. */
  public static final class Builder {
    private String backendId;
    private String metricKey;
    private long windowStartEpoch;
    private int windowSeconds = 60;
    private int sampleCount;
    private double mean = Double.NaN;
    private double min = Double.NaN;
    private double max = Double.NaN;
    private Double p95;
    private long generation;

    private Builder() {}

    public Builder backendId(String backendId) {
      this.backendId = backendId;
      return this;
    }

    public Builder metricKey(String metricKey) {
      this.metricKey = metricKey;
      return this;
    }

    public Builder windowStartEpoch(long windowStartEpoch) {
      this.windowStartEpoch = windowStartEpoch;
      return this;
    }

    public Builder windowSeconds(int windowSeconds) {
      this.windowSeconds = windowSeconds;
      return this;
    }

    public Builder sampleCount(int sampleCount) {
      this.sampleCount = sampleCount;
      return this;
    }

    public Builder mean(double mean) {
      this.mean = mean;
      return this;
    }

    public Builder min(double min) {
      this.min = min;
      return this;
    }

    public Builder max(double max) {
      this.max = max;
      return this;
    }

    public Builder p95(@Nullable Double p95) {
      this.p95 = p95;
      return this;
    }

    public Builder generation(long generation) {
      this.generation = generation;
      return this;
    }

    public MetricBucketRow build() {
      return new MetricBucketRow(
          backendId,
          metricKey,
          windowStartEpoch,
          windowSeconds,
          sampleCount,
          mean,
          min,
          max,
          p95,
          generation);
    }
  }

  private static Object firstNonNull(Map<String, ?> map, String k1, String k2) {
    Object v = map.get(k1);
    return v != null ? v : map.get(k2);
  }

  private static double toDoubleOrNaN(Object o) {
    if (o == null) return Double.NaN;
    if (o instanceof Number n) return n.doubleValue();
    try {
      return Double.parseDouble(o.toString());
    } catch (NumberFormatException e) {
      return Double.NaN;
    }
  }

  private static long toLong(Object o, long def) {
    if (o == null) return def;
    if (o instanceof Number n) return n.longValue();
    try {
      return Long.parseLong(o.toString());
    } catch (NumberFormatException e) {
      return def;
    }
  }

  private static int toInt(Object o, int def) {
    if (o == null) return def;
    if (o instanceof Number n) return n.intValue();
    try {
      return Integer.parseInt(o.toString());
    } catch (NumberFormatException e) {
      return def;
    }
  }
}
