package io.github.dailystruggle.rtp.claimaddon;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReflectiveClaimCheckersTest {

  @Test
  @DisplayName("HuskTownsChecker safely fails-open when HuskTowns is not present")
  void testHuskTownsCheckerGracefulAbsent() {
    RTPCoords coords = new RTPCoords("world", 100, 64, 200);
    // In test environment without Bukkit server or HuskTowns API, returns false safely
    Boolean inClaim = HuskTownsChecker.isInClaim(coords);
    assertNotNull(inClaim);
    assertFalse(inClaim);
  }

  @Test
  @DisplayName("PlotSquaredChecker safely fails-open when PlotSquared is not present")
  void testPlotSquaredCheckerGracefulAbsent() {
    RTPCoords coords = new RTPCoords("world", 100, 64, 200);
    // In test environment without Bukkit server or PlotSquared API, returns false safely
    Boolean inClaim = PlotSquaredChecker.isInClaim(coords);
    assertNotNull(inClaim);
    assertFalse(inClaim);
  }
}
