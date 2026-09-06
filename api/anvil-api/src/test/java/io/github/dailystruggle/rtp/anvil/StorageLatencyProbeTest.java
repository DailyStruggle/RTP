package io.github.dailystruggle.rtp.anvil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Deterministic behaviour of the observe-only storage-latency accumulator.
 *
 * <p>Every sample here is injected, so nothing in this class depends on the machine's real disk.
 * The device thresholds are asserted at their nominal boundaries rather than against measured
 * hardware, which is the only way this can be a regression test rather than a benchmark.
 */
public class StorageLatencyProbeTest {

    private static final long MIB = 1024L * 1024L;

    @BeforeEach
    public void reset() {
        StorageLatencyProbe.reset();
    }

    @Test
    @DisplayName("unsampled probe reports zeros and refuses to classify")
    public void unsampledIsInert() {
        assertEquals(0L, StorageLatencyProbe.samples());
        assertEquals(0L, StorageLatencyProbe.ewmaNanosPerRead());
        assertEquals(0L, StorageLatencyProbe.meanNanosPerRead());
        assertEquals(0L, StorageLatencyProbe.bytesPerSecond());
        assertEquals(0L, StorageLatencyProbe.percentileNanos(50.0d));
        assertEquals(StorageLatencyProbe.Device.UNKNOWN, StorageLatencyProbe.classify());
    }

    @Test
    @DisplayName("mean and EWMA converge on a constant sample stream")
    public void convergesOnConstantStream() {
        long nanos = 4_000_000L;
        for (int i = 0; i < 256; i++) {
            StorageLatencyProbe.record(nanos, 4L * MIB);
        }
        assertEquals(256L, StorageLatencyProbe.samples());
        assertEquals(nanos, StorageLatencyProbe.meanNanosPerRead());
        // EWMA seeds on the first sample, so a constant stream pins it exactly.
        assertEquals(nanos, StorageLatencyProbe.ewmaNanosPerRead());
    }

    @Test
    @DisplayName("EWMA tracks a step change without jumping to it")
    public void ewmaLagsAStepChange() {
        for (int i = 0; i < 64; i++) {
            StorageLatencyProbe.record(1_000_000L, MIB);
        }
        long before = StorageLatencyProbe.ewmaNanosPerRead();
        StorageLatencyProbe.record(100_000_000L, MIB);
        long after = StorageLatencyProbe.ewmaNanosPerRead();

        assertTrue(after > before, "a slower read must raise the EWMA: " + before + " -> " + after);
        assertTrue(
                after < 100_000_000L,
                "one sample must not carry the EWMA all the way to the new value: " + after);
    }

    @Test
    @DisplayName("failed reads contribute latency but no throughput")
    public void failedReadsCountAgainstTheDevice() {
        StorageLatencyProbe.record(50_000_000L, 0L);
        assertEquals(1L, StorageLatencyProbe.samples());
        assertEquals(50_000_000L, StorageLatencyProbe.meanNanosPerRead());
        assertEquals(0L, StorageLatencyProbe.bytesPerSecond());
        assertEquals(StorageLatencyProbe.Device.UNKNOWN, StorageLatencyProbe.classify());
    }

    @Test
    @DisplayName("percentiles bracket the sample distribution to within a factor of two")
    public void percentilesBracketTheDistribution() {
        // 99 fast reads at ~1 ms and one slow read at ~64 ms.
        for (int i = 0; i < 99; i++) {
            StorageLatencyProbe.record(1_048_576L, MIB);
        }
        StorageLatencyProbe.record(67_108_864L, MIB);

        long p50 = StorageLatencyProbe.percentileNanos(50.0d);
        long p99 = StorageLatencyProbe.percentileNanos(99.9d);
        assertEquals(1_048_576L, p50, "median must land in the fast bucket");
        assertEquals(67_108_864L, p99, "the tail must reach the slow bucket");
        assertTrue(p99 > p50, "tail must exceed median");
    }

    @Test
    @DisplayName("device class follows observed throughput across every threshold")
    public void classifiesByThroughput() {
        assertEquals(StorageLatencyProbe.Device.NVME, classifyAt(2_000L));
        assertEquals(StorageLatencyProbe.Device.SSD, classifyAt(500L));
        assertEquals(StorageLatencyProbe.Device.HDD, classifyAt(120L));
        assertEquals(StorageLatencyProbe.Device.SLOW, classifyAt(10L));
    }

    /** Feeds enough samples at {@code mibPerSecond} to pass the classification floor. */
    private static StorageLatencyProbe.Device classifyAt(long mibPerSecond) {
        StorageLatencyProbe.reset();
        long bytes = mibPerSecond * MIB;
        for (int i = 0; i < 32; i++) {
            // One second of transfer per sample, so throughput is exactly the requested rate.
            StorageLatencyProbe.record(1_000_000_000L, bytes);
        }
        return StorageLatencyProbe.classify();
    }

    @Test
    @DisplayName("device class follows per-operation latency across every threshold")
    public void classifiesByLatency() {
        assertEquals(StorageLatencyProbe.Device.NVME, classifyByLatencyAt(120_000L));
        assertEquals(StorageLatencyProbe.Device.SSD, classifyByLatencyAt(500_000L));
        assertEquals(StorageLatencyProbe.Device.HDD, classifyByLatencyAt(9_000_000L));
        assertEquals(StorageLatencyProbe.Device.SLOW, classifyByLatencyAt(50_000_000L));
    }

    @Test
    @DisplayName("latency and throughput classifiers disagree when read size differs")
    public void latencyAndThroughputClassifiersAreDistinct() {
        // Same throughput, wildly different per-operation latency: 4 KiB served in 100 us and
        // 4 MiB served in 100 ms both move about 40 MiB/s. The throughput classifier cannot tell
        // them apart, and a cost model that multiplies a per-fetch wait needs it to.
        StorageLatencyProbe.reset();
        for (int i = 0; i < 32; i++) {
            StorageLatencyProbe.record(100_000L, 4L * 1024L);
        }
        StorageLatencyProbe.Device fastSmall = StorageLatencyProbe.classifyByLatency();

        StorageLatencyProbe.reset();
        for (int i = 0; i < 32; i++) {
            StorageLatencyProbe.record(100_000_000L, 4L * MIB);
        }
        StorageLatencyProbe.Device slowLarge = StorageLatencyProbe.classifyByLatency();

        assertEquals(StorageLatencyProbe.Device.NVME, fastSmall);
        assertEquals(StorageLatencyProbe.Device.SLOW, slowLarge);
    }

    /** Feeds enough constant-latency samples to pass the classification floor. */
    private static StorageLatencyProbe.Device classifyByLatencyAt(long nanosPerRead) {
        StorageLatencyProbe.reset();
        for (int i = 0; i < 32; i++) {
            StorageLatencyProbe.record(nanosPerRead, MIB);
        }
        return StorageLatencyProbe.classifyByLatency();
    }

    @Test
    @DisplayName("concurrent recorders lose no samples and no bytes")
    public void concurrentRecordingIsAccurate() throws InterruptedException {
        int threads = 8;
        int perThread = 2000;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        StorageLatencyProbe.record(1_000_000L, MIB);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            thread.setDaemon(true);
            thread.start();
        }
        start.countDown();
        assertTrue(done.await(30L, TimeUnit.SECONDS), "recorders must finish");

        // Counters are exact under contention; only the EWMA tolerates lost updates by design.
        assertEquals((long) threads * perThread, StorageLatencyProbe.samples());
        assertEquals(1_000_000L, StorageLatencyProbe.meanNanosPerRead());
    }

    @Test
    @DisplayName("snapshot is self-consistent with the individual accessors")
    public void snapshotAgreesWithAccessors() {
        for (int i = 0; i < 32; i++) {
            StorageLatencyProbe.record(2_000_000L, 2L * MIB);
        }
        StorageLatencyProbe.Snapshot s = StorageLatencyProbe.snapshot();
        assertEquals(StorageLatencyProbe.samples(), s.samples());
        assertEquals(StorageLatencyProbe.meanNanosPerRead(), s.meanNanosPerRead());
        assertEquals(StorageLatencyProbe.bytesPerSecond(), s.bytesPerSecond());
        assertEquals(StorageLatencyProbe.classify(), s.device());
        assertEquals(2.0d, s.ewmaMillis(), 1e-9d);
    }

    @Test
    @DisplayName("reset returns the probe to its inert state")
    public void resetIsComplete() {
        for (int i = 0; i < 64; i++) {
            StorageLatencyProbe.record(3_000_000L, MIB);
        }
        StorageLatencyProbe.reset();
        unsampledIsInert();
    }
}
