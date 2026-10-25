package io.github.dailystruggle.rtp.api.metrics;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.jetbrains.annotations.Nullable;

/**
 * Batch-mode, tier-promotion-driven publisher and range-scan reader for cross-backend metric
 * time-series buckets (ADR-040, REQ-RTP-METTS-001..008).
 *
 * <p>All static entry points and the {@link #UNINITIALIZED} sentinel enforce the REQ-RTP-S-006
 * pre-init guard by throwing {@link IllegalStateException} when invoked before {@code rtp-core}
 * loads and registers the active publisher.
 */
@PublicApi
public interface MetricTimeSeriesPublisher {

  /** Documented message prefix thrown when accessed before core initialization (REQ-RTP-S-006). */
  String NOT_LOADED_MESSAGE =
      "[RTP API] Cannot access MetricTimeSeriesPublisher: Core implementation is not loaded.";

  /**
   * Uninitialized sentinel throwing {@link IllegalStateException} on every operation (REQ-RTP-S-006).
   */
  MetricTimeSeriesPublisher UNINITIALIZED =
      new MetricTimeSeriesPublisher() {
        @Override
        public void enqueue(MetricBucketRow row) {
          throw new IllegalStateException(NOT_LOADED_MESSAGE + " (enqueue)");
        }

        @Override
        public int flush() {
          throw new IllegalStateException(NOT_LOADED_MESSAGE + " (flush)");
        }

        @Override
        public List<MetricBucketRow> rangeScan(
            @Nullable String backendId,
            String metricKey,
            long fromEpochInclusive,
            long toEpochInclusive) {
          throw new IllegalStateException(NOT_LOADED_MESSAGE + " (rangeScan)");
        }

        @Override
        public boolean isActive() {
          throw new IllegalStateException(NOT_LOADED_MESSAGE + " (isActive)");
        }

        @Override
        public int pendingCount() {
          throw new IllegalStateException(NOT_LOADED_MESSAGE + " (pendingCount)");
        }
      };

  /**
   * Enqueues an immutable promoted bucket row for the next periodic batch flush
   * (REQ-RTP-METTS-002, REQ-RTP-METTS-003).
   *
   * @param row promoted hybrid metric bucket row; never {@code null}
   * @throws IllegalArgumentException if {@code row} is {@code null} or invalid
   * @throws IllegalStateException    if called before core is loaded (REQ-RTP-S-006)
   */
  void enqueue(MetricBucketRow row);

  /**
   * Enqueues multiple promoted bucket rows in order.
   *
   * @param rows non-null collection of bucket rows
   */
  default void enqueueAll(Collection<MetricBucketRow> rows) {
    if (rows == null) {
      throw new IllegalArgumentException("rows must not be null");
    }
    for (MetricBucketRow row : rows) {
      enqueue(row);
    }
  }

  /**
   * Executes one periodic batch flush pass under the configured {@code perPassWriteCap}
   * and runs the local-backend retention sweep (REQ-RTP-METTS-004, REQ-RTP-METTS-005).
   *
   * @return number of bucket rows persisted during this pass
   * @throws IllegalStateException if called before core is loaded (REQ-RTP-S-006)
   */
  int flush();

  /**
   * Performs an off-thread range scan for {@code metricKey} over
   * {@code [fromEpochInclusive, toEpochInclusive]}, ordered by {@code window_start_epoch} ascending
   * (REQ-RTP-METTS-006, REQ-RTP-S-005).
   *
   * @param backendId          specific backend id, or {@code null}/{@code "all"} for all backends
   * @param metricKey          metric key to query
   * @param fromEpochInclusive lower bound (UTC epoch seconds, inclusive)
   * @param toEpochInclusive   upper bound (UTC epoch seconds, inclusive)
   * @return immutable list of matching bucket rows ordered by {@code windowStartEpoch} ascending
   * @throws IllegalStateException if called before core is loaded (REQ-RTP-S-006) or on the main thread (REQ-RTP-S-005)
   */
  List<MetricBucketRow> rangeScan(
      @Nullable String backendId,
      String metricKey,
      long fromEpochInclusive,
      long toEpochInclusive);

  /**
   * Convenience overload scanning all backends for {@code metricKey}.
   */
  default List<MetricBucketRow> rangeScan(
      String metricKey, long fromEpochInclusive, long toEpochInclusive) {
    return rangeScan(null, metricKey, fromEpochInclusive, toEpochInclusive);
  }

  /**
   * Returns {@code true} when {@code metrics.networkTimeSeries.publish=true} and ADR-036 network
   * mode is enabled (REQ-RTP-METTS-007).
   */
  boolean isActive();

  /**
   * Returns the number of promoted bucket rows currently waiting in the write queue.
   */
  int pendingCount();

  // -------------------------------------------------------------------------
  // Static S-006 registry & entry points
  // -------------------------------------------------------------------------

  /**
   * Returns the active {@link MetricTimeSeriesPublisher} registered by {@code rtp-core}.
   *
   * @return non-null publisher instance
   * @throws IllegalStateException if called before core is loaded (REQ-RTP-S-006)
   */
  static MetricTimeSeriesPublisher require() {
    MetricTimeSeriesPublisher pub = Holder.INSTANCE.get();
    if (pub == null) {
      throw new IllegalStateException(NOT_LOADED_MESSAGE);
    }
    return pub;
  }

  /**
   * Alias for {@link #require()} enforcing the REQ-RTP-S-006 pre-init guard.
   *
   * @return non-null publisher instance
   * @throws IllegalStateException if called before core is loaded (REQ-RTP-S-006)
   */
  static MetricTimeSeriesPublisher instance() {
    return require();
  }

  /**
   * Enqueues a bucket row through the registered publisher (REQ-RTP-S-006).
   */
  static void publishBucket(MetricBucketRow row) {
    require().enqueue(row);
  }

  /**
   * Queries a bucket range through the registered publisher (REQ-RTP-S-006).
   */
  static List<MetricBucketRow> queryRange(
      @Nullable String backendId,
      String metricKey,
      long fromEpochInclusive,
      long toEpochInclusive) {
    return require().rangeScan(backendId, metricKey, fromEpochInclusive, toEpochInclusive);
  }

  /**
   * Registers the core publisher implementation during core startup.
   *
   * @param publisher publisher instance, or {@code null} to clear
   */
  static void register(@Nullable MetricTimeSeriesPublisher publisher) {
    Holder.INSTANCE.set(publisher);
  }

  /**
   * Clears the registered publisher (shutdown / pre-init test isolation).
   */
  static void clear() {
    Holder.INSTANCE.set(null);
  }

  /**
   * Returns {@code true} if a core publisher is currently registered.
   */
  static boolean isRegistered() {
    return Holder.INSTANCE.get() != null;
  }

  /** Internal holder for the process-wide publisher reference. */
  final class Holder {
    private Holder() {}

    static final AtomicReference<MetricTimeSeriesPublisher> INSTANCE = new AtomicReference<>(null);
  }
}
