package io.github.dailystruggle.rtp.anvil;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Observe-only storage-latency accumulator for cold region-file reads.
 *
 * <p>Purely passive: it performs no I/O of its own and issues no synthetic probes. Every sample
 * comes from a read the prefilter was going to perform anyway, on {@link AnvilIoPool} threads, so
 * instrumenting it adds no syscalls and no main-thread exposure (S-005).</p>
 *
 * <p>Why this exists: a memory-versus-storage cost model cannot be calibrated from a constant. The
 * price of spilling state to disk instead of holding it resident differs by more than an order of
 * magnitude between a spinning disk and an NVMe device, so the resident budget is only defensible
 * if the storage side of the trade is measured on the machine that has to pay it.</p>
 *
 * <p><b>Interpretation caveats, stated because the numbers are otherwise easy to over-read:</b></p>
 *
 * <ul>
 *   <li>Reads served by the OS page cache look like RAM, not like the device. Observed throughput
 *       is therefore an upper bound on device capability, and {@link #classify()} is advisory.
 *   <li>Concurrency inflates per-read latency: {@link AnvilIoPool} runs up to {@code 2 * cpus}
 *       readers, so queueing is included in the sample. That is deliberate - the cost model wants
 *       the latency a caller actually waits, not an idealized device figure.
 *   <li>Percentiles come from power-of-two buckets, so they are accurate to a factor of two. That
 *       is sufficient to separate device classes and insufficient for anything finer.
 * </ul>
 */
public final class StorageLatencyProbe {

    /** EWMA smoothing factor, as a reciprocal: each sample carries 1/16 of the weight. */
    private static final int EWMA_SHIFT = 4;

    /** Samples required before {@link #classify()} stops answering {@link Device#UNKNOWN}. */
    private static final int MIN_CLASSIFY_SAMPLES = 16;

    private static final long NVME_MIN_BYTES_PER_SEC = 1_200L * 1024L * 1024L;
    private static final long SSD_MIN_BYTES_PER_SEC = 250L * 1024L * 1024L;
    private static final long HDD_MIN_BYTES_PER_SEC = 40L * 1024L * 1024L;

    /**
     * Per-operation latency ceilings, in nanoseconds, for {@link #classifyByLatency()}.
     *
     * <p>Throughput is the wrong statistic for a latency-bound decision. A device moving 40 MiB/s
     * in 4 MiB reads and one moving 40 MiB/s in 4 KiB header reads are entirely different cost
     * regimes, and {@link #classify()} calls them identical. Whether to hold state resident or
     * fetch it turns on what one operation costs, not on how many bytes per second the device can
     * sustain, so the cost model wants this classification rather than that one.
     */
    private static final long NVME_MAX_OP_NANOS = 250_000L;

    /** @see #NVME_MAX_OP_NANOS */
    private static final long SSD_MAX_OP_NANOS = 2_000_000L;

    /** @see #NVME_MAX_OP_NANOS */
    private static final long HDD_MAX_OP_NANOS = 20_000_000L;

    /** Power-of-two latency buckets: index {@code i} holds reads of {@code [2^i, 2^(i+1))} ns. */
    private static final int BUCKETS = 64;

    private static final AtomicLong SAMPLES = new AtomicLong();
    private static final AtomicLong TOTAL_NANOS = new AtomicLong();
    private static final AtomicLong TOTAL_BYTES = new AtomicLong();
    private static final AtomicLong EWMA_NANOS = new AtomicLong();
    private static final AtomicLongArray HISTOGRAM = new AtomicLongArray(BUCKETS);

    private StorageLatencyProbe() {}

    /** Coarse device class inferred from observed cold-read throughput. Advisory only. */
    public enum Device {
        /** Not enough samples yet, or no bytes were transferred. */
        UNKNOWN,
        /** Throughput consistent with NVMe, or with reads served from the OS page cache. */
        NVME,
        /** Throughput consistent with a SATA solid-state device. */
        SSD,
        /** Throughput consistent with a spinning disk. */
        HDD,
        /** Below spinning-disk throughput: network storage, or a heavily contended device. */
        SLOW
    }

    /**
     * Records one completed cold read.
     *
     * <p>Non-blocking and allocation-free. A failed read contributes its elapsed time with zero
     * bytes, so a device that is timing out is not flattered by having its failures dropped.</p>
     *
     * @param elapsedNanos wall time of the read, clamped at zero
     * @param bytesRead    bytes transferred; {@code 0} for a failed read
     */
    public static void record(long elapsedNanos, long bytesRead) {
        long nanos = Math.max(0L, elapsedNanos);
        SAMPLES.incrementAndGet();
        TOTAL_NANOS.addAndGet(nanos);
        if (bytesRead > 0L) TOTAL_BYTES.addAndGet(bytesRead);

        int bucket = (nanos == 0L) ? 0 : (63 - Long.numberOfLeadingZeros(nanos));
        HISTOGRAM.incrementAndGet(Math.min(BUCKETS - 1, Math.max(0, bucket)));

        // Lock-free EWMA. A lost update under contention costs one sample's weight, which is
        // immaterial to a smoothed average and cheaper than serializing every reader.
        long prev = EWMA_NANOS.get();
        long next = (prev == 0L) ? nanos : prev + ((nanos - prev) >> EWMA_SHIFT);
        EWMA_NANOS.compareAndSet(prev, next);
    }

    /** @return cold reads recorded since JVM start or the last {@link #reset()} */
    public static long samples() {
        return SAMPLES.get();
    }

    /**
     * Smoothed per-read latency, the {@code t_io} term a cost model should use.
     *
     * @return EWMA of cold-read wall time in nanoseconds; {@code 0} when unsampled
     */
    public static long ewmaNanosPerRead() {
        return EWMA_NANOS.get();
    }

    /** @return mean cold-read wall time in nanoseconds; {@code 0} when unsampled */
    public static long meanNanosPerRead() {
        long n = SAMPLES.get();
        return n == 0L ? 0L : TOTAL_NANOS.get() / n;
    }

    /**
     * Observed cold-read throughput.
     *
     * @return bytes per second, or {@code 0} when nothing has been transferred
     */
    public static long bytesPerSecond() {
        long nanos = TOTAL_NANOS.get();
        long bytes = TOTAL_BYTES.get();
        if (nanos <= 0L || bytes <= 0L) return 0L;
        return (long) ((double) bytes * 1_000_000_000.0d / (double) nanos);
    }

    /**
     * Latency percentile, accurate to a factor of two by construction.
     *
     * @param percentile in {@code (0, 100]}; values outside are clamped
     * @return lower edge of the bucket containing that percentile, in nanoseconds
     */
    public static long percentileNanos(double percentile) {
        long total = SAMPLES.get();
        if (total <= 0L) return 0L;
        double p = Math.min(100.0d, Math.max(0.0001d, percentile));
        long target = (long) Math.ceil(total * p / 100.0d);
        long seen = 0L;
        for (int i = 0; i < BUCKETS; i++) {
            seen += HISTOGRAM.get(i);
            if (seen >= target) return 1L << i;
        }
        return 1L << (BUCKETS - 1);
    }

    /**
     * Device class inferred from {@link #bytesPerSecond()}.
     *
     * @return the inferred class, or {@link Device#UNKNOWN} below {@value #MIN_CLASSIFY_SAMPLES}
     *     samples
     */
    public static Device classify() {
        if (SAMPLES.get() < MIN_CLASSIFY_SAMPLES) return Device.UNKNOWN;
        long rate = bytesPerSecond();
        if (rate <= 0L) return Device.UNKNOWN;
        if (rate >= NVME_MIN_BYTES_PER_SEC) return Device.NVME;
        if (rate >= SSD_MIN_BYTES_PER_SEC) return Device.SSD;
        if (rate >= HDD_MIN_BYTES_PER_SEC) return Device.HDD;
        return Device.SLOW;
    }

    /**
     * Device class inferred from per-operation latency rather than from throughput.
     *
     * <p>This is the classification a resident-versus-paged cost model should consume: the term it
     * needs is what a caller waits for one fetch, and that is a latency, not a rate. Percentile
     * data is deliberately not used here - the median is the term the objective multiplies, and
     * tail latency belongs in a separate risk decision.
     *
     * @return the inferred class, or {@link Device#UNKNOWN} below {@value #MIN_CLASSIFY_SAMPLES}
     *     samples
     */
    public static Device classifyByLatency() {
        if (SAMPLES.get() < MIN_CLASSIFY_SAMPLES) return Device.UNKNOWN;
        long nanos = meanNanosPerRead();
        if (nanos <= 0L) return Device.UNKNOWN;
        if (nanos <= NVME_MAX_OP_NANOS) return Device.NVME;
        if (nanos <= SSD_MAX_OP_NANOS) return Device.SSD;
        if (nanos <= HDD_MAX_OP_NANOS) return Device.HDD;
        return Device.SLOW;
    }

    /** Immutable snapshot of every exposed figure, for reporting in one consistent read. */
    public record Snapshot(long samples, long ewmaNanosPerRead, long meanNanosPerRead,
                           long bytesPerSecond, long p50Nanos, long p99Nanos, Device device,
                           Device latencyDevice) {

        /** @return {@link #ewmaNanosPerRead()} expressed in milliseconds */
        public double ewmaMillis() {
            return ewmaNanosPerRead / 1_000_000.0d;
        }

        /** @return observed throughput in MiB/s */
        public double megabytesPerSecond() {
            return bytesPerSecond / (1024.0d * 1024.0d);
        }
    }

    /** @return a snapshot of the current counters */
    public static Snapshot snapshot() {
        return new Snapshot(
                samples(),
                ewmaNanosPerRead(),
                meanNanosPerRead(),
                bytesPerSecond(),
                percentileNanos(50.0d),
                percentileNanos(99.0d),
                classify(),
                classifyByLatency());
    }

    /** Zeros every counter. Test hook, and the per-window reset a reporter would use. */
    public static void reset() {
        SAMPLES.set(0L);
        TOTAL_NANOS.set(0L);
        TOTAL_BYTES.set(0L);
        EWMA_NANOS.set(0L);
        for (int i = 0; i < BUCKETS; i++) {
            HISTOGRAM.set(i, 0L);
        }
    }
}
