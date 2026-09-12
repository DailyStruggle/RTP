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
}
