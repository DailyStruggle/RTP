package io.github.dailystruggle.rtp.common.selection.region;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * REQ-RTP-S-004 - a throwing global region verifier must never be silently treated as a pass.
 * Both the synchronous and asynchronous verifier paths in
 * {@link GlobalRegionVerifiers#checkGlobalRegionVerifiers} log the throwable at WARNING and reject
 * the candidate location (treated as {@code false}), per the documented contract in
 * docs/dev/EXTERNAL_HOOKS.md. This guards the regression where a throwing sync verifier merely
 * logged and continued the loop, silently accepting the location.
 */
class ReqRtpS004VerifierFailSafeTest {

  @BeforeEach
  void setUp() {
    GlobalRegionVerifiers.clearGlobalRegionVerifiers();
  }

  @AfterEach
  void tearDown() {
    GlobalRegionVerifiers.clearGlobalRegionVerifiers();
  }

  @Test
  @DisplayName("throwing sync verifier -> location rejected (fail safe, S-004)")
  void throwingSyncVerifierRejects() {
    GlobalRegionVerifiers.addGlobalRegionVerifier(c -> {
      throw new RuntimeException("simulated claim-plugin API failure");
    });
    assertFalse(GlobalRegionVerifiers.checkGlobalRegionVerifiers((io.github.dailystruggle.rtp.api.world.RTPCoords) null).join());
  }

  @Test
  @DisplayName("throwing async verifier -> location rejected (fail safe, S-004)")
  void throwingAsyncVerifierRejects() {
    GlobalRegionVerifiers.addGlobalRegionVerifierAsync(c -> {
      throw new RuntimeException("simulated async claim-plugin API failure");
    });
    assertFalse(GlobalRegionVerifiers.checkGlobalRegionVerifiers((io.github.dailystruggle.rtp.api.world.RTPCoords) null).join());
  }

  @Test
  @DisplayName("no verifiers -> location accepted")
  void noVerifiersAccepts() {
    assertTrue(GlobalRegionVerifiers.checkGlobalRegionVerifiers((io.github.dailystruggle.rtp.api.world.RTPCoords) null).join());
  }
}
