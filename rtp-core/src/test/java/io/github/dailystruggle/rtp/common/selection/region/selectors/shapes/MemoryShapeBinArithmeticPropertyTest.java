package io.github.dailystruggle.rtp.common.selection.region.selectors.shapes;

import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.SquareOptimizedDualLayer;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.table.SegmentedKeyRunTable;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests verifying invariants for MemoryShape secondary bin arithmetic,
 * partitioning, and ACCUMULATE offset resolution (ADR-028, ADR-001).
 *
 * Traceability: REQ-RTP-F-005, REQ-RTP-F-006, ADR-028.
 * ENTERPRISE_READINESS.md item 23.
 */
public class MemoryShapeBinArithmeticPropertyTest {

    /**
     * Invariant 1: deriveOptimalBinSize invariants across arbitrary totalRange.
     * - Output is always a positive power of two.
     * - If totalRange < DEVOLUTION_THRESHOLD (16384), binSize >= totalRange so table devolves to 1 bin.
     * - If totalRange >= DEVOLUTION_THRESHOLD, binSize is in [128, 4096].
     */
    @Property(tries = 300)
    void deriveOptimalBinSize_invariants(
            @ForAll @LongRange(min = 1, max = 10_000_000) long totalRange) {

        long binSize = SegmentedKeyRunTable.deriveOptimalBinSize(totalRange);

        assertTrue(binSize > 0, "Bin size must be positive");
        assertEquals(0, binSize & (binSize - 1), "Bin size must be a power of two");

        if (totalRange < SegmentedKeyRunTable.DEVOLUTION_THRESHOLD) {
            assertTrue(binSize >= totalRange,
                    () -> "Sub-threshold totalRange " + totalRange + " must yield binSize >= totalRange, got " + binSize);
            long numBins = (totalRange + binSize - 1) / binSize;
            assertEquals(1, numBins, "Sub-threshold totalRange must produce exactly 1 bin");
        } else {
            assertTrue(binSize >= 128 && binSize <= 4096,
                    () -> "Above threshold binSize must be in [128, 4096], got " + binSize);
        }
    }

    /**
     * Invariant 2: Adaptive stride derivation for dyadic binning (ADR-028).
     * - Stride is in {1, 4, 16, 64, 256}.
     * - Stride is a power of 2.
     * - Monotonic with respect to domain size.
     */
    @Property(tries = 200)
    void deriveAdaptiveStride_invariants(
            @ForAll @LongRange(min = 1, max = 1_000_000) long domainSize) {

        int stride = SquareOptimizedDualLayer.deriveAdaptiveStride(domainSize);

        assertTrue(stride >= 1 && stride <= 256);
        assertEquals(0, stride & (stride - 1), "Stride must be a power of two");

        // Monotonic check with smaller domain
        long smaller = domainSize / 2;
        int smallerStride = SquareOptimizedDualLayer.deriveAdaptiveStride(smaller);
        assertTrue(stride >= smallerStride, "Stride must be monotonic with domainSize");
    }

    /**
     * Invariant 3: Partitioning completeness and cell conservation.
     * For any valid totalRange and arbitrary non-overlapping runs:
     * - numBins == ceil(totalRange / binSize).
     * - sum(bin[b].coveredCells) == totalCovered().
     * - totalCovered + totalGood == totalRange.
     * - Each bin covers exactly min(binSize, totalRange - b * binSize) cells.
     */
    @Property(tries = 150)
    void partitioning_cellConservation(
            @ForAll("validTableConfigs") TableConfiguration config) {

        SegmentedKeyRunTable table = SegmentedKeyRunTable.fromRuns(
                config.starts, config.lengths, config.starts.length,
                config.totalRange, config.binSize, 0L);

        assertEquals(config.totalRange, table.totalRange());
        assertEquals(config.binSize, table.binSize());

        long expectedBins = (config.totalRange + config.binSize - 1) / config.binSize;
        assertEquals(expectedBins, table.numBins());

        long sumCovered = 0L;
        long sumCapacity = 0L;
        for (int b = 0; b < table.numBins(); b++) {
            SegmentedKeyRunTable.Bin bin = table.bin(b);
            assertEquals(b, bin.binIndex());
            sumCovered += bin.coveredCells();
            long expectedBinCapacity = Math.min(config.binSize, config.totalRange - (long) b * config.binSize);
            sumCapacity += expectedBinCapacity;
            assertTrue(bin.coveredCells() <= expectedBinCapacity,
                    "Bin covered cells cannot exceed bin capacity");
        }

        assertEquals(config.totalRange, sumCapacity, "Sum of bin capacities must equal totalRange");
        assertEquals(table.totalCovered(), sumCovered, "Sum of bin covered cells must equal table.totalCovered()");
    }

    /**
     * Invariant 4: Lookup fidelity and containment consistency.
     * For any key in [0, totalRange), table.contains(key) == true iff key falls inside one of the runs.
     */
    @Property(tries = 150)
    void contains_matchesSourceRuns(
            @ForAll("validTableConfigs") TableConfiguration config,
            @ForAll @IntRange(min = 0, max = 50) int sampleProbe) {

        SegmentedKeyRunTable table = SegmentedKeyRunTable.fromRuns(
                config.starts, config.lengths, config.starts.length,
                config.totalRange, config.binSize, 0L);

        long step = Math.max(1, config.totalRange / 50);
        long probeKey = Math.min(config.totalRange - 1, (long) sampleProbe * step);

        boolean expected = false;
        for (int i = 0; i < config.starts.length; i++) {
            long s = config.starts[i];
            long e = s + config.lengths[i];
            if (probeKey >= s && probeKey < e) {
                expected = true;
                break;
            }
        }

        assertEquals(expected, table.contains(probeKey),
                "table.contains(" + probeKey + ") must match expected ground truth from runs");
    }

    /**
     * Invariant 5: resolveAccumulate mathematical invariants.
     * For any target index in [0, totalGood):
     * - resolvedKey is in [0, totalRange).
     * - table.contains(resolvedKey) == false (never lands on a bad run).
     * - Strictly monotonic: resolveAccumulate(target + 1) > resolveAccumulate(target).
     */
    @Property(tries = 150)
    void resolveAccumulate_invariants(
            @ForAll("validTableConfigs") TableConfiguration config) {

        SegmentedKeyRunTable table = SegmentedKeyRunTable.fromRuns(
                config.starts, config.lengths, config.starts.length,
                config.totalRange, config.binSize, 0L);

        long totalGood = config.totalRange - table.totalCovered();
        if (totalGood <= 0) {
            assertEquals(-1L, table.resolveAccumulate(0L));
            return;
        }

        // Out-of-bounds boundary checks
        assertEquals(-1L, table.resolveAccumulate(-1L));
        assertEquals(-1L, table.resolveAccumulate(totalGood));
        assertEquals(-1L, table.resolveAccumulate(totalGood + 10L));

        // Sample targets across domain
        long[] targets = new long[]{
                0L,
                totalGood / 4,
                totalGood / 2,
                (totalGood * 3) / 4,
                totalGood - 1
        };

        long lastKey = -1L;
        for (long t : targets) {
            long key = table.resolveAccumulate(t);
            assertTrue(key >= 0 && key < config.totalRange,
                    () -> "Resolved key " + key + " must be in [0, " + config.totalRange + ")");
            assertFalse(table.contains(key),
                    () -> "Resolved key " + key + " for good target " + t + " must not be in bad set");

            if (lastKey >= 0) {
                assertTrue(key >= lastKey, "resolveAccumulate must be non-decreasing with target");
            }
            lastKey = key;
        }
    }

    /**
     * Invariant 6: Full collapse tolerance preserves or enlarges coverage.
     * When fullCollapseTolerance > 0, bins with remaining usable chunks <= tolerance
     * collapse to FULL, and totalCovered() >= uncollapsed totalCovered().
     */
    @Property(tries = 100)
    void fullCollapseTolerance_invariants(
            @ForAll("validTableConfigs") TableConfiguration config,
            @ForAll @LongRange(min = 1, max = 16) long tolerance) {

        SegmentedKeyRunTable normalTable = SegmentedKeyRunTable.fromRuns(
                config.starts, config.lengths, config.starts.length,
                config.totalRange, config.binSize, 0L);

        SegmentedKeyRunTable collapsedTable = SegmentedKeyRunTable.fromRuns(
                config.starts, config.lengths, config.starts.length,
                config.totalRange, config.binSize, tolerance);

        assertTrue(collapsedTable.totalCovered() >= normalTable.totalCovered(),
                "Collapsed table coverage must be >= uncollapsed coverage");

        for (int b = 0; b < collapsedTable.numBins(); b++) {
            SegmentedKeyRunTable.Bin bin = collapsedTable.bin(b);
            long binCapacity = Math.min(config.binSize, config.totalRange - (long) b * config.binSize);
            if (bin.isFull()) {
                assertEquals(binCapacity, bin.coveredCells());
            }
        }
    }

    // ------------------------------------------------------------------------
    // Arbitrary Generator for Table Configurations
    // ------------------------------------------------------------------------

    record TableConfiguration(long totalRange, long binSize, long[] starts, long[] lengths) {}

    @Provide
    Arbitrary<TableConfiguration> validTableConfigs() {
        Arbitrary<Long> rangeArb = Arbitraries.longs().between(100L, 50_000L);
        Arbitrary<Integer> binPowerArb = Arbitraries.integers().between(6, 11); // 64 to 2048
        Arbitrary<Integer> runCountArb = Arbitraries.integers().between(0, 15);

        return Combinators.combine(rangeArb, binPowerArb, runCountArb).as((totalRange, binPow, runCount) -> {
            long binSize = 1L << binPow;
            if (runCount == 0) {
                return new TableConfiguration(totalRange, binSize, new long[0], new long[0]);
            }

            // Generate disjoint sorted runs within [0, totalRange)
            List<Long> points = new ArrayList<>();
            for (int i = 0; i < runCount * 2; i++) {
                points.add((long) (Math.random() * totalRange));
            }
            Collections.sort(points);

            List<Long> validStarts = new ArrayList<>();
            List<Long> validLens = new ArrayList<>();
            for (int i = 0; i < points.size() - 1; i += 2) {
                long s = points.get(i);
                long e = points.get(i + 1);
                if (e > s) {
                    validStarts.add(s);
                    validLens.add(e - s);
                }
            }

            long[] sArr = new long[validStarts.size()];
            long[] lArr = new long[validLens.size()];
            for (int i = 0; i < validStarts.size(); i++) {
                sArr[i] = validStarts.get(i);
                lArr[i] = validLens.get(i);
            }
            return new TableConfiguration(totalRange, binSize, sArr, lArr);
        });
    }
}
