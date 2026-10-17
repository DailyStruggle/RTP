package io.github.dailystruggle.rtp.common.predict;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("REQ-PRED-F-001: EwmaEstimator Statistical Mean, Variance, and Bitwise Parity")
class EwmaEstimatorTest {

    @Test
    @DisplayName("Fractional: Cold-start early sample unbiasing sets first sample as exact mean")
    void fractional_coldStartFirstSample() {
        EwmaEstimator estimator = EwmaEstimator.ofWindow(64);
        assertTrue(Double.isNaN(estimator.mean()));
        assertEquals(0L, estimator.sampleCount());

        estimator.recordSample(42.0);
        assertEquals(42.0, estimator.mean(), 1e-9);
        assertEquals(0.0, estimator.variance(), 1e-9);
        assertEquals(1L, estimator.sampleCount());
    }

    @Test
    @DisplayName("Fractional: Constant series maintains constant mean and zero variance")
    void fractional_constantSeriesZeroVariance() {
        EwmaEstimator estimator = EwmaEstimator.ofWindow(32);
        for (int i = 0; i < 50; i++) {
            estimator.recordSample(100.0);
        }

        assertEquals(100.0, estimator.mean(), 1e-6);
        assertEquals(0.0, estimator.variance(), 1e-6);
        assertEquals(0.0, estimator.standardDeviation(), 1e-6);
        assertEquals(50L, estimator.sampleCount());
    }

    @Test
    @DisplayName("Fractional: Variance and standard deviation track alternating signals accurately")
    void fractional_varianceTracking() {
        EwmaEstimator estimator = EwmaEstimator.ofWindow(64);
        // Alternating between 10 and 20 (true mean 15, expected variance ~ 25)
        for (int i = 0; i < 200; i++) {
            estimator.recordSample((i % 2 == 0) ? 10.0 : 20.0);
        }

        assertEquals(15.0, estimator.mean(), 0.5);
        assertTrue(estimator.variance() > 15.0 && estimator.variance() < 30.0,
                "Variance should be near 25: " + estimator.variance());
        assertTrue(estimator.standardDeviation() > 3.5 && estimator.standardDeviation() < 6.0);
    }

    @Test
    @DisplayName("Fractional: Initialized mean skips early unbiasing and incorporates prior immediately")
    void fractional_initializedMean() {
        EwmaEstimator estimator = EwmaEstimator.ofWindow(64, 15.0);
        assertEquals(15.0, estimator.mean(), 1e-9);
        assertEquals(0L, estimator.sampleCount());

        estimator.recordSample(25.0);
        assertEquals(1L, estimator.sampleCount());
        // alpha = 2 / (64 + 1) = 2 / 65 ~ 0.030769
        // mean = 15.0 + (2/65) * (25.0 - 15.0) = 15.0 + 0.30769 ~ 15.30769
        double expected = 15.0 + (2.0 / 65.0) * (25.0 - 15.0);
        assertEquals(expected, estimator.mean(), 1e-6);
    }

    @Test
    @DisplayName("Fractional: Numerical stability rejects NaN, infinite, and negative inputs")
    void fractional_numericalStability() {
        EwmaEstimator estimator = EwmaEstimator.ofWindow(16, 50.0);
        estimator.recordSample(Double.NaN);
        estimator.recordSample(Double.POSITIVE_INFINITY);
        estimator.recordSample(Double.NEGATIVE_INFINITY);
        estimator.recordSample(-100.0);

        assertEquals(50.0, estimator.mean(), 1e-9);
        assertEquals(0L, estimator.sampleCount());
    }

    @Test
    @DisplayName("Integer: Bitwise parity with historical ((avg * 7) / 8) + (diff / 8) over 1,000 synthetic samples")
    void integer_bitwiseParityAcrossOneThousandSamples() {
        long initialNanos = 50_000_000L;
        EwmaEstimator estimator = EwmaEstimator.ofTaskPipe(initialNanos);
        assertEquals(initialNanos, estimator.meanNanos());
        assertEquals(EwmaEstimator.DecayMode.INTEGER_SEVEN_EIGHTHS, estimator.decayMode());

        Random rnd = new Random(42);
        long historical = initialNanos;

        for (int i = 0; i < 1_000; i++) {
            long diff = (long) (rnd.nextDouble() * 20_000_000L); // 0 to 20ms
            historical = ((historical * 7L) / 8L) + (diff / 8L);

            estimator.recordNanos(diff);
            assertEquals(historical, estimator.meanNanos(), "Mismatch at sample " + i);
        }
    }

    @Test
    @DisplayName("Integer: Zero initial value initializes from first recorded sample")
    void integer_zeroInitialValueInitializesFromFirstSample() {
        EwmaEstimator estimator = EwmaEstimator.ofIntegerDecay(7, 1, 0L);
        assertEquals(0L, estimator.meanNanos());
        assertEquals(0L, estimator.sampleCount());

        estimator.recordNanos(5_000_000L);
        assertEquals(5_000_000L, estimator.meanNanos());
        assertEquals(1L, estimator.sampleCount());

        estimator.recordNanos(1_000_000L);
        long expected = ((5_000_000L * 7L) / 8L) + (1_000_000L / 8L);
        assertEquals(expected, estimator.meanNanos());
        assertEquals(2L, estimator.sampleCount());
    }

    @Test
    @DisplayName("Integer: Large value does not suffer integer overflow")
    void integer_overflowProtection() {
        long large = Long.MAX_VALUE / 2L;
        EwmaEstimator estimator = EwmaEstimator.ofIntegerDecay(7, 1, large);
        estimator.recordNanos(1_000_000L);
        assertTrue(estimator.meanNanos() > 0L, "Mean must remain positive without overflow");
    }

    @Test
    @DisplayName("Integer: Concurrent multi-threaded updates maintain atomic consistency")
    void integer_concurrentUpdates() throws InterruptedException {
        EwmaEstimator estimator = EwmaEstimator.ofTaskPipe(50_000_000L);
        int threads = 4;
        int perThread = 10_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        estimator.recordNanos(1_000_000L);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(threads * perThread, estimator.sampleCount());
        assertTrue(estimator.meanNanos() > 0L);
    }
}
