package io.github.dailystruggle.rtp.bukkit.commands.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.GlobalRegionVerifiers;
import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("REQ-RTP-S-003 safety-verifier registration isolation")
class SafetyVerifierIsolationTest {
  @TempDir File tempDir;
  private AutoCloseable claimVerifier;

  @BeforeEach
  void setUp() {
    RTPTestSetup.install(tempDir);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (claimVerifier != null) claimVerifier.close();
    RTPTestSetup.cleanUp();
  }

  @Test
  @DisplayName("probe cleanup preserves a previously registered claim verifier")
  void preservesExistingClaimVerifier() throws Exception {
    AtomicBoolean invoked = new AtomicBoolean();
    claimVerifier =
        GlobalRegionVerifiers.addGlobalRegionVerifierAsync(
            SafetyVerifierIsolationTest.class,
            coords -> {
              invoked.set(true);
              return CompletableFuture.completedFuture(true);
            });
    int expectedRegisteredCount = GlobalRegionVerifiers.registeredCount();

    SafetyVerifierTestJob.Result result = SafetyVerifierTestJob.runProbe();

    assertTrue(result.verifierDispatched, "the probe must reach its synthetic async verifier");
    assertTrue(invoked.get(), "the existing claim verifier must remain in the check chain");
    assertEquals(
        expectedRegisteredCount,
        GlobalRegionVerifiers.registeredCount(),
        "probe cleanup must remove only the verifier it registered");
    assertTrue(
        GlobalRegionVerifiers.checkGlobalRegionVerifiers(
                new RTPCoords(result.sampledWorld, 0, 64, 0))
            .join(),
        "the previously registered verifier must still participate after the probe");
  }
}
