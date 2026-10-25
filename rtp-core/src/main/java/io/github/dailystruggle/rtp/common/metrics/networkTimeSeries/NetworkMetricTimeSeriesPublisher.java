package io.github.dailystruggle.rtp.common.metrics.networkTimeSeries;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.metrics.MetricBucketRow;
import io.github.dailystruggle.rtp.api.metrics.MetricTimeSeriesPublisher;
import io.github.dailystruggle.rtp.common.database.ActiveDatabase;
import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;
import io.github.dailystruggle.rtp.common.database.options.AbstractSQLDatabaseAccessor;
import io.github.dailystruggle.rtp.common.network.NetworkModeBootstrap;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Batch-mode, tier-promotion-driven publisher of metric time-series buckets to the ADR-036 shared
 * store (ADR-040, REQ-RTP-METTS-001..008).
 */
public final class NetworkMetricTimeSeriesPublisher implements MetricTimeSeriesPublisher {

  private final Supplier<NetworkTimeSeriesConfig> configSupplier;
  private final BooleanSupplier networkModeActiveSupplier;
  private final Supplier<String> backendIdSupplier;
  private final LongSupplier clockEpochSeconds;
  private final BooleanSupplier mainThreadDetector;
  private final MetricBucketStore store;
  private final Consumer<String> warningSink;

  private final ConcurrentLinkedQueue<MetricBucketRow> writeQueue = new ConcurrentLinkedQueue<>();
  private final AtomicBoolean schemaBootstrapped = new AtomicBoolean(false);
  private final AtomicBoolean startupMismatchWarned = new AtomicBoolean(false);

  private final AtomicLong overflowWarningCount = new AtomicLong(0L);
  private final AtomicLong consecutiveOverflowPasses = new AtomicLong(0L);
  private final AtomicLong mismatchWarningCount = new AtomicLong(0L);
  private final AtomicLong storeFailureWarningCount = new AtomicLong(0L);
  private final AtomicLong totalFlushedRows = new AtomicLong(0L);
  private final AtomicLong totalExpiredDeletedRows = new AtomicLong(0L);

  /**
   * Production constructor resolving config, network-mode status, backend id, and SQL store from
   * active runtime singletons.
   */
  public NetworkMetricTimeSeriesPublisher() {
    this(
        NetworkTimeSeriesConfig::resolve,
        NetworkMetricTimeSeriesPublisher::defaultNetworkModeActive,
        NetworkMetricTimeSeriesPublisher::defaultBackendId,
        () -> System.currentTimeMillis() / 1000L,
        NetworkMetricTimeSeriesPublisher::defaultIsMainOrRegionThread,
        new SqlMetricBucketStore(),
        msg -> RTPRuntime.log(Level.WARNING, msg));
  }

  /**
   * Customizable constructor for dependency injection and deterministic testing.
   */
  public NetworkMetricTimeSeriesPublisher(
      Supplier<NetworkTimeSeriesConfig> configSupplier,
      BooleanSupplier networkModeActiveSupplier,
      Supplier<String> backendIdSupplier,
      LongSupplier clockEpochSeconds,
      BooleanSupplier mainThreadDetector,
      MetricBucketStore store,
      @Nullable Consumer<String> warningSink) {
    this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
    this.networkModeActiveSupplier =
        Objects.requireNonNull(networkModeActiveSupplier, "networkModeActiveSupplier");
    this.backendIdSupplier = Objects.requireNonNull(backendIdSupplier, "backendIdSupplier");
    this.clockEpochSeconds = Objects.requireNonNull(clockEpochSeconds, "clockEpochSeconds");
    this.mainThreadDetector = Objects.requireNonNull(mainThreadDetector, "mainThreadDetector");
    this.store = Objects.requireNonNull(store, "store");
    this.warningSink =
        (warningSink != null) ? warningSink : (msg -> RTPRuntime.log(Level.WARNING, msg));
    evaluateStartupGate();
  }

  /**
   * Evaluates the startup / runtime writer gate (REQ-RTP-METTS-007, S-004).
   *
   * <p>When {@code metrics.networkTimeSeries.publish=true} and ADR-036 network mode is disabled,
   * logs exactly one structured {@code Level.WARNING} message and stays dormant.
   *
   * @return {@code true} if the writer is active and permitted to enqueue/flush
   */
  public boolean evaluateStartupGate() {
    NetworkTimeSeriesConfig cfg = resolveConfig();
    if (!cfg.publish()) {
      return false;
    }
    boolean networkActive = networkModeActiveSupplier.getAsBoolean();
    if (!networkActive) {
      if (startupMismatchWarned.compareAndSet(false, true)) {
        mismatchWarningCount.incrementAndGet();
        emitWarning(
            "[RTP][MetricsTimeSeries] metrics.networkTimeSeries.publish=true while ADR-036 "
                + "network mode is disabled; time-series publisher staying dormant "
                + "(REQ-RTP-METTS-007, S-004).");
      }
      return false;
    }
    ensureSchemaBootstrapped();
    return true;
  }

  private void ensureSchemaBootstrapped() {
    if (schemaBootstrapped.compareAndSet(false, true)) {
      try {
        store.bootstrapSchema();
      } catch (RuntimeException e) {
        schemaBootstrapped.set(false);
        storeFailureWarningCount.incrementAndGet();
        emitWarning(
            "[RTP][MetricsTimeSeries] Failed to bootstrap rtp_metric_bucket schema: "
                + e.getMessage()
                + " (REQ-RTP-METTS-008, S-004).");
      }
    }
  }

  @Override
  public boolean isActive() {
    return evaluateStartupGate();
  }

  @Override
  public void enqueue(MetricBucketRow row) {
    if (row == null) {
      throw new IllegalArgumentException(
          "MetricBucketRow must not be null (REQ-RTP-METTS-003)");
    }
    if (!evaluateStartupGate()) {
      // Dormant per REQ-RTP-METTS-007: zero writes, queue stays empty.
      return;
    }
    writeQueue.add(row);
  }

  /**
   * Validates a raw map payload at the publisher boundary (rejecting mean-only or max-only
   * maps per REQ-RTP-METTS-003) and enqueues the resulting {@link MetricBucketRow}.
   */
  public void enqueueFromMap(Map<String, ?> rawPayload) {
    MetricBucketRow validated = MetricBucketRow.fromMap(rawPayload);
    enqueue(validated);
  }

  @Override
  public int flush() {
    return flushPass(clockEpochSeconds.getAsLong());
  }

  /**
   * Executes one periodic batch flush pass at {@code nowEpochSeconds} under the configured
   * {@code perPassWriteCap}, deferring overflow to the next pass with a structured S-004 warning
   * and running the local-backend retention sweep (REQ-RTP-METTS-004, REQ-RTP-METTS-005, S-005).
   *
   * @param nowEpochSeconds current UTC epoch seconds
   * @return number of rows flushed from the queue in this pass
   */
  public int flushPass(long nowEpochSeconds) {
    checkOffMainThread("flush");
    if (!evaluateStartupGate()) {
      return 0;
    }
    NetworkTimeSeriesConfig cfg = resolveConfig();
    int cap = cfg.perPassWriteCap();

    List<MetricBucketRow> batch = new ArrayList<>(Math.min(cap, writeQueue.size()));
    for (int i = 0; i < cap; i++) {
      MetricBucketRow row = writeQueue.poll();
      if (row == null) break;
      batch.add(row);
    }

    int flushed = 0;
    if (!batch.isEmpty()) {
      try {
        store.insertBatchIdempotent(batch);
        flushed = batch.size();
        totalFlushedRows.addAndGet(flushed);
      } catch (RuntimeException e) {
        storeFailureWarningCount.incrementAndGet();
        emitWarning(
            "[RTP][MetricsTimeSeries] Shared store unreachable during batch flush (batchSize="
                + batch.size()
                + "): "
                + e.getMessage()
                + " (S-004).");
        // Buffer back up to perPassWriteCap; drop excess with structured warning per ADR-040.
        int requeued = 0;
        for (MetricBucketRow r : batch) {
          if (writeQueue.size() < cap) {
            writeQueue.add(r);
            requeued++;
          }
        }
        int dropped = batch.size() - requeued;
        if (dropped > 0) {
          overflowWarningCount.incrementAndGet();
          emitWarning(
              "[RTP][MetricsTimeSeries] Dropped "
                  + dropped
                  + " metric bucket rows after store failure exceeded buffer cap="
                  + cap
                  + " (S-004).");
        }
      }
    }

    int deferredOverflow = writeQueue.size();
    if (deferredOverflow > 0) {
      long consecutive = consecutiveOverflowPasses.incrementAndGet();
      long totalWarns = overflowWarningCount.incrementAndGet();
      emitWarning(
          "[RTP][MetricsTimeSeries] Per-pass write cap reached: flushed="
              + flushed
              + ", perPassWriteCap="
              + cap
              + ", deferredOverflow="
              + deferredOverflow
              + ", consecutiveOverflowPasses="
              + consecutive
              + ", overflowWarnings="
              + totalWarns
              + " (REQ-RTP-METTS-004, S-004).");
    } else {
      consecutiveOverflowPasses.set(0L);
    }

    // Periodic local-backend retention sweep (REQ-RTP-METTS-005).
    String localBackendId = backendIdSupplier.get();
    if (localBackendId != null && !localBackendId.isBlank()) {
      long cutoffEpoch = nowEpochSeconds - cfg.retentionSeconds();
      try {
        int deleted = store.deleteExpired(localBackendId, cutoffEpoch);
        if (deleted > 0) {
          totalExpiredDeletedRows.addAndGet(deleted);
        }
      } catch (RuntimeException e) {
        storeFailureWarningCount.incrementAndGet();
        emitWarning(
            "[RTP][MetricsTimeSeries] Retention sweep failed for backend="
                + localBackendId
                + ": "
                + e.getMessage()
                + " (REQ-RTP-METTS-005, S-004).");
      }
    }

    return flushed;
  }

  @Override
  public List<MetricBucketRow> rangeScan(
      @Nullable String backendId,
      String metricKey,
      long fromEpochInclusive,
      long toEpochInclusive) {
    checkOffMainThread("rangeScan");
    return store.rangeScan(backendId, metricKey, fromEpochInclusive, toEpochInclusive);
  }

  @Override
  public int pendingCount() {
    return writeQueue.size();
  }

  public long overflowWarningCount() {
    return overflowWarningCount.get();
  }

  public long consecutiveOverflowPasses() {
    return consecutiveOverflowPasses.get();
  }

  public long mismatchWarningCount() {
    return mismatchWarningCount.get();
  }

  public long storeFailureWarningCount() {
    return storeFailureWarningCount.get();
  }

  public long totalFlushedRows() {
    return totalFlushedRows.get();
  }

  public long totalExpiredDeletedRows() {
    return totalExpiredDeletedRows.get();
  }

  public MetricBucketStore store() {
    return store;
  }

  private NetworkTimeSeriesConfig resolveConfig() {
    NetworkTimeSeriesConfig cfg = configSupplier.get();
    return cfg != null ? cfg : NetworkTimeSeriesConfig.DEFAULT;
  }

  private void emitWarning(String message) {
    try {
      warningSink.accept(message);
    } catch (Throwable ignored) {
      // Logging must never break the periodic pass.
    }
  }

  private void checkOffMainThread(String operation) {
    if (mainThreadDetector.getAsBoolean()) {
      throw new IllegalStateException(
          "REQ-RTP-S-005: MetricTimeSeriesPublisher."
              + operation
              + "() must not be invoked on the main or region thread");
    }
  }

  public static boolean defaultNetworkModeActive() {
    if (NetworkModeBootstrap.LIVE != null) {
      return true;
    }
    DatabaseAccessor<?> acc = ActiveDatabase.accessor();
    return acc instanceof AbstractSQLDatabaseAccessor sql && sql.getNetworkStateBinding() != null;
  }

  public static String defaultBackendId() {
    NetworkModeBootstrap live = NetworkModeBootstrap.LIVE;
    if (live != null && live.serverId() != null && !live.serverId().isBlank()) {
      return live.serverId();
    }
    return RTPAPI.serverId != null ? RTPAPI.serverId.toString() : "backend-local";
  }

  public static boolean defaultIsMainOrRegionThread() {
    String name = Thread.currentThread().getName();
    if (name == null) return false;
    return name.equals("Server thread")
        || name.equals("Render thread")
        || name.startsWith("Region Scheduler Thread");
  }
}
