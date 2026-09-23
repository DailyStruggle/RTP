package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.table;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class AnvilQuotaCandidateHarvesterTest {

  @Test
  public void testQuotaGatedEarlyExit() {
    AnvilQuotaCandidateHarvester harvester = new AnvilQuotaCandidateHarvester(3, 16);

    assertEquals(3, harvester.targetQuota());
    assertEquals(16, harvester.maxTrials());

    Path worldFolder = Path.of("C:\\GameServers\\Minecraft\\testServer\\RTP-Folia\\26.1\\world");
    String dim = "dimensions/minecraft/overworld";

    HybridHazardTable table = new HybridHazardTable(65536L);

    // Harvest from region (0, 0)
    AnvilQuotaCandidateHarvester.HarvestResult result = harvester.harvest(
        worldFolder, dim, 0, 0, Set.of("minecraft:water", "minecraft:lava"), null, table, 0L);

    assertNotNull(result);
    // On region (0,0), if region file exists, should hit quota or execute up to maxTrials
    if (!result.harvestedChunks().isEmpty()) {
      assertTrue(result.harvestedChunks().size() <= 3);
      assertTrue(result.probesExecuted() <= 16);
      if (result.quotaMet()) {
        assertEquals(3, result.harvestedChunks().size());
        // Early exit: probesExecuted must be <= 16
        assertTrue(result.probesExecuted() <= 16);
      }
    }
  }

  @Test
  public void testDiscardableBinOnNonExistentRegion() {
    AnvilQuotaCandidateHarvester harvester = new AnvilQuotaCandidateHarvester(3, 16);
    Path fakeFolder = Path.of("C:\\fake\\world");

    HybridHazardTable table = new HybridHazardTable(65536L);

    // Region (999, 999) doesn't exist
    AnvilQuotaCandidateHarvester.HarvestResult result = harvester.harvest(
        fakeFolder, "", 999, 999, Set.of("minecraft:water"), null, table, 0L);

    assertFalse(result.quotaMet());
    assertTrue(result.harvestedChunks().isEmpty());
  }

  @Test
  public void testGaussianStrideQuotaDistribution() {
    int strideCapacity = 100;
    long sum = 0;
    int runs = 1000;
    for (int i = 0; i < runs; i++) {
      int quota = AnvilQuotaCandidateHarvester.sampleGaussianStrideQuota(strideCapacity);
      assertTrue(quota >= 15, "Quota should be >= 15% (was " + quota + ")");
      assertTrue(quota <= 50, "Quota should be <= 50% (was " + quota + ")");
      sum += quota;
    }
    double mean = (double) sum / runs;
    // Mean should be centered close to 33% (within [31, 35])
    assertTrue(mean >= 31.0 && mean <= 35.0, "Gaussian mean should be near 33% (was " + mean + ")");
  }

  @Test
  public void testJitteredOffsetsVaryAcrossBins() {
    AnvilQuotaCandidateHarvester harvester = new AnvilQuotaCandidateHarvester(3, 16);
    int offsetA = harvester.jitteredOffset(0, 0, 0);
    int offsetB = harvester.jitteredOffset(0, 5, 10);
    int offsetC = harvester.jitteredOffset(0, -3, 8);

    assertTrue(offsetA >= 0 && offsetA < 1024);
    assertTrue(offsetB >= 0 && offsetB < 1024);
    assertTrue(offsetC >= 0 && offsetC < 1024);

    // Offsets across 16 trials should cover a wide range
    java.util.Set<Integer> distinctTrials = new java.util.HashSet<>();
    for (int i = 0; i < 16; i++) {
      distinctTrials.add(harvester.jitteredOffset(i, 2, 3));
    }
    assertTrue(distinctTrials.size() >= 8, "Dyadic jittered trials should produce well-dispersed offsets");
  }

  @Test
  public void testSelectNFromMBinSet() {
    AnvilQuotaCandidateHarvester harvester = new AnvilQuotaCandidateHarvester(3, 16);
    java.util.List<int[]> validBins = java.util.List.of(
        new int[]{0, 0},
        new int[]{1, 0},
        new int[]{0, 1},
        new int[]{1, 1}
    );

    int n = 10;
    java.util.List<int[]> selected = harvester.selectNFromMBinSet(validBins, n);
    assertEquals(n, selected.size(), "Should select exactly N points");

    for (int[] chunk : selected) {
      int cx = chunk[0];
      int cz = chunk[1];
      int rx = cx >> 5;
      int rz = cz >> 5;

      // Assert each selected point falls inside one of the validBins
      boolean inValidBin = validBins.stream().anyMatch(b -> b[0] == rx && b[1] == rz);
      assertTrue(inValidBin, "Chunk (" + cx + "," + cz + ") with region (" + rx + "," + rz + ") must be within valid bins");
    }
  }
}
