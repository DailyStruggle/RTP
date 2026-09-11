package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

class PredictorAgreementTest {

  @BeforeAll
  static void setUp() {
    MockRTPServerAccessor accessor =
        new MockRTPServerAccessor(new File("target/test-data"));
    RTP.serverAccessor = accessor;
    io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = accessor;
  }

  @ParameterizedTest
  @ValueSource(longs = {1024L, 256L})
  @DisplayName("keyForCounter matches selectL3Candidate stream for 100,000 draws")
  void testKeyForCounterStreamAgreement(long radius) {
    SquareOptimizedDualLayer shape = new SquareOptimizedDualLayer("TEST_STREAM_" + radius, 32);
    shape.set(GenericMemoryShapeParams.radius, radius);
    shape.set(GenericMemoryShapeParams.centerRadius, 0L);

    long startCounter = shape.currentSweepCounter();
    assertEquals(0L, startCounter);

    int count = 100_000;
    long[] streamKeys = new long[count];
    for (int i = 0; i < count; i++) {
      streamKeys[i] = shape.selectL3Candidate();
    }

    assertEquals(count, shape.currentSweepCounter());

    // Verify keyForCounter(t) directly reproduces each emitted key
    for (int i = 0; i < count; i++) {
      long predictedKey = shape.keyForCounter(startCounter + i);
      assertEquals(streamKeys[i], predictedKey, "Mismatch at draw index " + i);
    }
  }

  @ParameterizedTest
  @ValueSource(longs = {1024L, 256L})
  @DisplayName("predictUpcomingBins matches distinct bins in order of next N actual draws")
  void testPredictUpcomingBinsAgreement(long radius) {
    SquareOptimizedDualLayer shape = new SquareOptimizedDualLayer("TEST_UPCOMING_" + radius, 32);
    shape.set(GenericMemoryShapeParams.radius, radius);
    shape.set(GenericMemoryShapeParams.centerRadius, 0L);

    // Test for multiple lookahead values, both at start and after some draws
    int[] lookaheads = {1, 5, 50, 256, 1000, 5000};
    for (int N : lookaheads) {
      long currentSweep = shape.currentSweepCounter();
      long[] predictedUpcomingBins = shape.predictUpcomingBins(N);

      // Now actually draw N candidates
      LinkedHashSet<Long> actualDistinctBins = new LinkedHashSet<>();
      for (int i = 0; i < N; i++) {
        long key = shape.selectL3Candidate();
        assertTrue(key >= 0);
        actualDistinctBins.add(key / 1024L);
      }

      long[] actualArray = actualDistinctBins.stream().mapToLong(Long::longValue).toArray();
      assertArrayEquals(actualArray, predictedUpcomingBins,
          "Mismatch in upcoming bins for radius " + radius + ", N=" + N + ", starting at sweep=" + currentSweep);
    }
  }

  @ParameterizedTest
  @ValueSource(longs = {1024L, 256L})
  @DisplayName("predictNextKeyForBin returns counter in bin and no earlier counter in the window hits it")
  void testPredictNextKeyForBinAccuracy(long radius) {
    SquareOptimizedDualLayer shape = new SquareOptimizedDualLayer("TEST_NEXT_KEY_" + radius, 32);
    shape.set(GenericMemoryShapeParams.radius, radius);
    shape.set(GenericMemoryShapeParams.centerRadius, 0L);

    long range = shape.getRange();
    assertTrue(range > 0);

    // Sample candidate keys from the sweep to discover valid bins
    List<Long> sampleBins = new ArrayList<>();
    Set<Long> seenBins = new HashSet<>();
    for (long t = 0; t < 500; t++) {
      long key = shape.keyForCounter(t);
      long bin = key / 1024L;
      if (seenBins.add(bin)) {
        sampleBins.add(bin);
        if (sampleBins.size() >= 20) break;
      }
    }

    // Also test a non-existent / out-of-range bin
    long impossibleBin = (range / 1024L) + 500L;

    long[] checkPoints = {0L, 50L, 256L, 1000L};
    for (long fromCounter : checkPoints) {
      for (long targetBin : sampleBins) {
        long predictedCounter = shape.predictNextKeyForBin(targetBin, fromCounter);
        if (predictedCounter != -1L) {
          assertTrue(predictedCounter >= fromCounter,
              "Predicted counter must be >= fromCounter");
          long keyAtCounter = shape.keyForCounter(predictedCounter);
          assertEquals(targetBin, keyAtCounter / 1024L,
              "Key at predicted counter must fall into target bin");

          // Assert NO earlier counter in [fromCounter, predictedCounter) hits targetBin
          for (long c = fromCounter; c < predictedCounter; c++) {
            long k = shape.keyForCounter(c);
            assertNotEquals(targetBin, k / 1024L,
                "Earlier counter " + c + " hit bin " + targetBin + " before predicted " + predictedCounter);
          }
        } else {
          // If -1 returned, assert that across the entire rotation window [fromCounter, fromCounter + range),
          // no counter produces targetBin
          for (long c = fromCounter; c < fromCounter + range; c++) {
            long k = shape.keyForCounter(c);
            assertNotEquals(targetBin, k / 1024L,
                "predictNextKeyForBin returned -1, but counter " + c + " falls in target bin");
          }
        }
      }

      // Impossible bin must return -1
      assertEquals(-1L, shape.predictNextKeyForBin(impossibleBin, fromCounter));
    }
  }
}
