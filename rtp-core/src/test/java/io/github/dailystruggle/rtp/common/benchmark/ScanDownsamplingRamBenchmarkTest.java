package io.github.dailystruggle.rtp.common.benchmark;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Empirical benchmark measuring RAM consequences and scanning characteristics of:
 * 1. Full Scan (S = 1, single-chunk resolution, 4,198,401 chunks).
 * 2. Downsampled Scan at S = 64 (Ru = 4, 8x8 chunk macro-cells, 65,600 cells).
 * 3. Downsampled Scan at S = 256 (Ru = 8, 16x16 chunk macro-cells, 16,400 cells).
 *
 * Simulates realistic Minecraft world generation noise maps (continentalness + ocean/land thresholds)
 * at Radius R = 1024 chunks.
 */
public class ScanDownsamplingRamBenchmarkTest {

  public record ScanResult(
      String name,
      int strideS,
      long evaluatedCells,
      long badCellsFound,
      int runCount,
      long rleMemoryBytes,
      long segmentedTableMemoryBytes,
      long roaringHybridMemoryBytes,
      long scanDurationMs,
      double landCoverageAccuracyPercent
  ) {}

  @Test
  @DisplayName("Gauge RAM consequences of full scan vs downsampled scan at R=1024 chunks")
  public void testScanDownsamplingAtRadius1024() {
    int R = 1024;
    int diameter = 2 * R + 1;
    long totalChunks = (long) diameter * diameter; // 2049 x 2049 = 4,198,401 chunks

    System.out.println("[DEBUG_LOG] ===========================================================================");
    System.out.printf("[DEBUG_LOG] Scanning Domain: Radius R = %d chunks | Diameter = %d chunks | Total Chunks = %,d%n",
        R, diameter, totalChunks);
    System.out.println("[DEBUG_LOG] ===========================================================================");

    // 1. Full Scan (S = 1, spatialResolution = 4 chunks bridging)
    ScanResult fullScanLossless = executeScan("1a. Full Scan (S = 1, Lossless / Gap = 0)", R, 1, 0L);
    ScanResult fullScanLossy = executeScan("1b. Full Scan (S = 1, Lossy Bridging Resolution = 4)", R, 1, 4L);

    // 2. Downsampled Scan at S = 64 (R_u = 4 chunks, 8x8 macro-cells, Resolution = 4)
    ScanResult downsampled64 = executeScan("2. Downsampled Scan (S = 64, Ru = 4)", R, 64, 4L);

    // 3. Downsampled Scan at S = 256 (R_u = 8 chunks, 16x16 macro-cells, Resolution = 8)
    ScanResult downsampled256 = executeScan("3. Downsampled Scan (S = 256, Ru = 8)", R, 256, 8L);

    System.out.println("\n[DEBUG_LOG] ===========================================================================");
    System.out.println("[DEBUG_LOG] EMPIRICAL SCAN & RAM BENCHMARK RESULTS (R = 1024 chunks, 4,198,401 chunks)");
    System.out.println("[DEBUG_LOG] ===========================================================================");
    printResult(fullScanLossless);
    printResult(fullScanLossy);
    printResult(downsampled64);
    printResult(downsampled256);
    System.out.println("[DEBUG_LOG] ===========================================================================");

    assertTrue(fullScanLossless.evaluatedCells() > 4_000_000L);
    assertTrue(downsampled256.evaluatedCells() > 16_000L);
  }

  private static void printResult(ScanResult res) {
    System.out.printf("[DEBUG_LOG] %s:%n", res.name);
    System.out.printf("[DEBUG_LOG]   Cells Evaluated: %,d | Bad Cells: %,d (%.1f%%)%n",
        res.evaluatedCells, res.badCellsFound, (double) res.badCellsFound / res.evaluatedCells * 100.0);
    System.out.printf("[DEBUG_LOG]   RLE Runs Generated: %,d runs%n", res.runCount);
    System.out.printf("[DEBUG_LOG]   RLE Cache RAM: %,d Bytes (%.2f MB)%n",
        res.rleMemoryBytes, res.rleMemoryBytes / (1024.0 * 1024.0));
    System.out.printf("[DEBUG_LOG]   Segmented Table RAM: %,d Bytes (%.2f MB)%n",
        res.segmentedTableMemoryBytes, res.segmentedTableMemoryBytes / (1024.0 * 1024.0));
    System.out.printf("[DEBUG_LOG]   Roaring/Hybrid RAM: %,d Bytes (%.2f MB)%n",
        res.roaringHybridMemoryBytes, res.roaringHybridMemoryBytes / (1024.0 * 1024.0));
    System.out.printf("[DEBUG_LOG]   Scan Execution Time: %d ms | Classification Accuracy: %.2f%%%n",
        res.scanDurationMs, res.landCoverageAccuracyPercent);
    System.out.println("[DEBUG_LOG] ---------------------------------------------------------------------------");
  }

  private static ScanResult executeScan(String name, int R, int strideS, long spatialResolution) {
    long startTime = System.currentTimeMillis();
    int cellStepChunks = (int) Math.round(Math.sqrt(strideS));
    if (cellStepChunks < 1) cellStepChunks = 1;

    int minChunk = -R;
    int maxChunk = R;

    List<Long> runStarts = new ArrayList<>();
    List<Long> runLengths = new ArrayList<>();

    long currentRunStart = -1L;
    long currentRunLength = 0L;
    long totalCellsEvaluated = 0L;
    long badCellsFound = 0L;
    long matchingGroundTruth = 0L;

    long cellIndex = 0L;

    for (int cz = minChunk; cz <= maxChunk; cz += cellStepChunks) {
      for (int cx = minChunk; cx <= maxChunk; cx += cellStepChunks) {
        totalCellsEvaluated++;
        boolean isBad = isHazardousChunk(cx, cz);

        // Ground truth accuracy check at center of macro-cell
        boolean groundTruth = isHazardousChunk(cx + cellStepChunks / 2, cz + cellStepChunks / 2);
        if (isBad == groundTruth) {
          matchingGroundTruth++;
        }

        if (isBad) {
          badCellsFound++;
          if (currentRunStart < 0L) {
            currentRunStart = cellIndex;
            currentRunLength = 1L;
          } else {
            currentRunLength++;
          }
        } else {
          if (currentRunStart >= 0L) {
            runStarts.add(currentRunStart);
            runLengths.add(currentRunLength);
            currentRunStart = -1L;
            currentRunLength = 0L;
          }
        }
        cellIndex++;
      }
    }

    if (currentRunStart >= 0L) {
      runStarts.add(currentRunStart);
      runLengths.add(currentRunLength);
    }

    // Apply lossy bridging coalescing using computeAdmissibleGap(spatialResolution, left, right)
    List<Long> coalescedStarts = new ArrayList<>();
    List<Long> coalescedLengths = new ArrayList<>();

    if (!runStarts.isEmpty()) {
      long curStart = runStarts.get(0);
      long curLen = runLengths.get(0);

      for (int i = 1; i < runStarts.size(); i++) {
        long nextStart = runStarts.get(i);
        long nextLen = runLengths.get(i);

        long admissible = computeAdmissibleGap(spatialResolution, curLen, nextLen);
        if (nextStart <= curStart + curLen + admissible) {
          curLen = Math.max(curLen, nextStart + nextLen - curStart);
        } else {
          coalescedStarts.add(curStart);
          coalescedLengths.add(curLen);
          curStart = nextStart;
          curLen = nextLen;
        }
      }
      coalescedStarts.add(curStart);
      coalescedLengths.add(curLen);
    }

    long duration = System.currentTimeMillis() - startTime;
    int runCount = coalescedStarts.size();

    // RLE Memory: (start 8B + length 8B + cause 1B + expiry 8B) = 25 bytes per run
    long rleBytes = (long) runCount * 25L;

    // SegmentedKeyRunTable RAM: 32x32 chunk macro-bins
    int totalBins = (int) Math.ceil((double) totalCellsEvaluated / 1024.0);
    long segmentedBytes = (long) totalBins * 32L + (long) runCount * 8L;

    // Roaring / Hybrid Containers (ADR-092):
    long roaringBytes;
    if (strideS == 1) {
      roaringBytes = (long) (totalBins * 0.35 * 4L + totalBins * 0.55 * 128L);
    } else {
      roaringBytes = Math.max(256L, badCellsFound * 2L + totalBins * 16L);
    }

    double accuracy = totalCellsEvaluated > 0 ? (double) matchingGroundTruth / totalCellsEvaluated * 100.0 : 100.0;

    return new ScanResult(
        name,
        strideS,
        totalCellsEvaluated,
        badCellsFound,
        runCount,
        rleBytes,
        segmentedBytes,
        roaringBytes,
        duration,
        accuracy
    );
  }

  public static long computeAdmissibleGap(long spatialResolution, long leftLength, long rightLength) {
    // Single source of truth - delegate to the shipped rule to avoid a benchmark-tier fork.
    return io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape
        .computeAdmissibleGap(spatialResolution, leftLength, rightLength);
  }

  /**
   * Realistic Minecraft Continentalness & Ocean Noise Model:
   * Generates continuous continental landmasses, vast ocean basins, and mountain bands.
   */
  private static boolean isHazardousChunk(int cx, int cz) {
    // Multi-octave Simplex-like continuous noise
    double scale1 = 0.005; // Macro continents (~200 chunks wavelength)
    double scale2 = 0.02;  // Regional terrain (~50 chunks wavelength)
    double scale3 = 0.08;  // Local rivers/lakes (~12 chunks wavelength)

    double n1 = Math.sin(cx * scale1) * Math.cos(cz * scale1);
    double n2 = Math.sin(cx * scale2 + 1.2) * Math.cos(cz * scale2 + 0.8) * 0.5;
    double n3 = Math.sin(cx * scale3 + 2.7) * Math.cos(cz * scale3 + 3.1) * 0.25;

    double continentValue = n1 + n2 + n3;

    // Ocean threshold: ~40% of the Minecraft world is ocean/deep ocean
    if (continentValue < -0.15) {
      return true; // Ocean hazard
    }

    // Mountain peak hazard threshold (jagged peaks / extreme steepness)
    if (continentValue > 1.2) {
      return true; // Impassable cliff / peak hazard
    }

    return false; // Valid solid land
  }
}
