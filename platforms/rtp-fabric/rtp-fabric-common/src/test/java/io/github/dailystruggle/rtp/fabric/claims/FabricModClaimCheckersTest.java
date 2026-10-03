package io.github.dailystruggle.rtp.fabric.claims;

import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FabricModClaimCheckersTest {

  @Test
  @DisplayName("OpenPartiesAndClaimsChecker fails open safely when mod is absent")
  void testOpacCheckerAbsent() {
    RTPCoords coords = new RTPCoords("world", 100, 64, 200);
    assertFalse(OpenPartiesAndClaimsChecker.isInClaim(coords));
  }

  @Test
  @DisplayName("FTBChunksChecker fails open safely when mod is absent")
  void testFtbChunksCheckerAbsent() {
    RTPCoords coords = new RTPCoords("world", 100, 64, 200);
    assertFalse(FTBChunksChecker.isInClaim(coords));
  }
}
