package io.github.dailystruggle.rtp.neoforge.claims;

import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NeoForgeModClaimCheckersTest {

  @Test
  @DisplayName("NeoForgeOpenPartiesAndClaimsChecker fails open safely when mod is absent")
  void testOpacCheckerAbsent() {
    RTPCoords coords = new RTPCoords("world", 100, 64, 200);
    assertFalse(NeoForgeOpenPartiesAndClaimsChecker.isInClaim(coords));
  }

  @Test
  @DisplayName("NeoForgeFTBChunksChecker fails open safely when mod is absent")
  void testFtbChunksCheckerAbsent() {
    RTPCoords coords = new RTPCoords("world", 100, 64, 200);
    assertFalse(NeoForgeFTBChunksChecker.isInClaim(coords));
  }
}
