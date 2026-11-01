package io.github.dailystruggle.rtp.common.database.options;

import io.github.dailystruggle.rtp.api.metrics.MetricBucketRow;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.proxy.common.transport.redis.resp.RespConnection;
import io.github.dailystruggle.rtp.proxy.common.transport.redis.resp.RespPool;
import io.github.dailystruggle.rtp.proxy.common.transport.redis.resp.RespProtocol;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Redis sorted-set persistence accessor for cross-backend metric time-series buckets
 * (ADR-040, REQ-RTP-METTS-001, REQ-RTP-METTS-005, REQ-RTP-METTS-006, REQ-RTP-METTS-008).
 *
 * <p>Key layout: {@code rtp:metric:<backend_id>:<metric_key>}, scored by
 * {@code window_start_epoch}, member = {@link MetricBucketRow#packRedisMember()},
 * with {@code EXPIRE} set to the configured retention window in seconds.
 */
public final class RedisMetricBucketAccessor {

  /** Prefix for all Redis metric time-series sorted sets. */
  public static final String KEY_PREFIX = "rtp:metric:";

  /** Functional RESP command executor abstraction over {@link RespPool} / {@link RespConnection}. */
  @FunctionalInterface
  public interface CommandExecutor {
    Object execute(String... args) throws IOException;
  }

  private final CommandExecutor executor;
  private final Set<String> knownBackends = ConcurrentHashMap.newKeySet();
  private final Set<String> knownMetricKeys = ConcurrentHashMap.newKeySet();

  /**
   * Constructs a Redis metric bucket accessor backed by a {@link RespPool}.
   *
   * @param pool non-null RESP connection pool
   */
  public RedisMetricBucketAccessor(RespPool pool) {
    Objects.requireNonNull(pool, "pool");
    this.executor =
        args -> {
          try (RespConnection conn = pool.getResource()) {
            return conn.executeCommand(args);
          }
        };
  }

  /**
   * Constructs a Redis metric bucket accessor backed by a custom {@link CommandExecutor}.
   *
   * @param executor non-null command executor
   */
  public RedisMetricBucketAccessor(CommandExecutor executor) {
    this.executor = Objects.requireNonNull(executor, "executor");
  }

  /**
   * Formats the canonical Redis sorted-set key {@code rtp:metric:<backend_id>:<metric_key>}
   * (ADR-040, REQ-RTP-METTS-008).
   */
  public static String keyFor(String backendId, String metricKey) {
    if (backendId == null || backendId.isBlank()) {
      throw new IllegalArgumentException("backendId must not be null or blank");
    }
    if (metricKey == null || metricKey.isBlank()) {
      throw new IllegalArgumentException("metricKey must not be null or blank");
    }
    return KEY_PREFIX + backendId + ":" + metricKey;
  }

  /**
   * Idempotently inserts a batch of {@link MetricBucketRow}s into their respective Redis sorted
   * sets {@code rtp:metric:<backend_id>:<metric_key>} and refreshes {@code EXPIRE} to
   * {@code retentionSeconds} (REQ-RTP-METTS-001, REQ-RTP-METTS-008).
   *
   * @param rows             non-null batch of rows
   * @param retentionSeconds TTL in seconds (&gt; 0)
   * @return number of newly inserted bucket entries
   */
  public int insertMetricBuckets(List<MetricBucketRow> rows, long retentionSeconds) {
    if (rows == null || rows.isEmpty()) return 0;
    for (MetricBucketRow row : rows) {
      if (row == null) {
        throw new IllegalArgumentException("MetricBucketRow element must not be null");
      }
    }
    int inserted = 0;
    for (MetricBucketRow row : rows) {
      String key = keyFor(row.backendId(), row.metricKey());
      String score = String.valueOf(row.windowStartEpoch());
      knownBackends.add(row.backendId());
      knownMetricKeys.add(row.metricKey());
      try {
        Object existing = executor.execute("ZRANGEBYSCORE", key, score, score);
        if ( existing instanceof List<?> list && !list.isEmpty()) {
          // Idempotent no-op: bucket for (backend_id, metric_key, window_start_epoch) already exists
          continue;
        }
        String packed = row.packRedisMember();
        Object zaddRes = executor.execute("ZADD", key, score, packed);
        if (retentionSeconds > 0L) {
          executor.execute("EXPIRE", key, String.valueOf(retentionSeconds));
        }
        if (zaddRes instanceof Number n) {
          inserted += Math.max(0, n.intValue());
        } else {
          inserted++;
        }
      } catch (Exception e) {
        RTPRuntime.log(
            Level.WARNING,
            "[RTP][MetricsTimeSeries] Failed to ZADD metric bucket for key="
                + key
                + ": "
                + e.getMessage(),
            e);
      }
    }
    return inserted;
  }

  /**
   * Removes buckets older than {@code cutoffEpochExclusive} ({@code window_start_epoch < cutoffEpochExclusive})
   * for the specified {@code backendId} and {@code metricKey} (REQ-RTP-METTS-005).
   */
  public int purgeExpiredMetricBuckets(
      String backendId, String metricKey, long cutoffEpochExclusive) {
    if (backendId == null || backendId.isBlank() || metricKey == null || metricKey.isBlank()) {
      return 0;
    }
    String key = keyFor(backendId, metricKey);
    try {
      Object res =
          executor.execute(
              "ZREMRANGEBYSCORE", key, "-inf", "(" + cutoffEpochExclusive);
      if (res instanceof Number n) {
        return n.intValue();
      }
    } catch (Exception e) {
      RTPRuntime.log(
          Level.WARNING,
          "[RTP][MetricsTimeSeries] Failed to ZREMRANGEBYSCORE for key="
              + key
              + ": "
              + e.getMessage(),
          e);
    }
    return 0;
  }

  /**
   * Purges expired buckets older than {@code cutoffEpochExclusive} across all known metric keys
   * for the local {@code backendId} only (REQ-RTP-METTS-005).
   */
  public int purgeExpiredMetricBuckets(String backendId, long cutoffEpochExclusive) {
    if (backendId == null || backendId.isBlank()) return 0;
    int removed = 0;
    Set<String> metrics = new LinkedHashSet<>(knownMetricKeys);
    for (String metricKey : metrics) {
      removed += purgeExpiredMetricBuckets(backendId, metricKey, cutoffEpochExclusive);
    }
    return removed;
  }

  /**
   * Queries metric buckets via {@code ZRANGEBYSCORE} over {@code [fromEpochInclusive, toEpochInclusive]},
   * returning rows ordered by {@code window_start_epoch} ascending (REQ-RTP-METTS-006, REQ-RTP-METTS-008).
   */
  public List<MetricBucketRow> queryMetricBuckets(
      @Nullable String backendId,
      String metricKey,
      long fromEpochInclusive,
      long toEpochInclusive) {
    if (metricKey == null || metricKey.isBlank() || toEpochInclusive < fromEpochInclusive) {
      return Collections.emptyList();
    }
    boolean allBackends =
        backendId == null
            || backendId.isBlank()
            || "all".equalsIgnoreCase(backendId)
            || "*".equals(backendId);
    Set<String> targetBackends =
        allBackends ? new LinkedHashSet<>(knownBackends) : Collections.singleton(backendId);

    List<MetricBucketRow> out = new ArrayList<>();
    String minScore = String.valueOf(fromEpochInclusive);
    String maxScore = String.valueOf(toEpochInclusive);
    for (String bId : targetBackends) {
      String key = keyFor(bId, metricKey);
      try {
        Object rep = executor.execute("ZRANGEBYSCORE", key, minScore, maxScore);
        if (rep instanceof List<?> elements) {
          for (Object el : elements) {
            String member = decodeElement(el);
            if (member != null && !member.isBlank()) {
              out.add(MetricBucketRow.unpackRedisMember(bId, metricKey, member));
            }
          }
        }
      } catch (Exception e) {
        RTPRuntime.log(
            Level.WARNING,
            "[RTP][MetricsTimeSeries] Failed to ZRANGEBYSCORE for key="
                + key
                + ": "
                + e.getMessage(),
            e);
      }
    }
    out.sort(
        Comparator.comparingLong(MetricBucketRow::windowStartEpoch)
            .thenComparing(MetricBucketRow::backendId));
    return Collections.unmodifiableList(out);
  }

  private static String decodeElement(Object el) {
    if (el == null) return null;
    if (el instanceof byte[] bytes) {
      return RespProtocol.toUtf8(bytes);
    }
    return el.toString();
  }
}
