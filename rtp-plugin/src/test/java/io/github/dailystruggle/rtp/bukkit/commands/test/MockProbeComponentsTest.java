package io.github.dailystruggle.rtp.bukkit.commands.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.dailystruggle.bit.BitCodes;
import io.github.dailystruggle.bit.BitComponent;
import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.bit.BitReport;
import io.github.dailystruggle.bit.BitResult;
import io.github.dailystruggle.bit.BitSideEffect;
import io.github.dailystruggle.bit.BitTier;
import io.github.dailystruggle.rtp.api.RTPResult;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.bit.RtpBit;
import io.github.dailystruggle.rtp.common.bit.RtpBitCodes;
import io.github.dailystruggle.rtp.common.mock.MockPlayerRegistry;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.mock.RuntimeMockRTPPlayer;
import java.util.List;
import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MockProbeComponentsTest {
  @TempDir File tempDir;

  @BeforeEach
  void setUp() {
    RTPTestSetup.install(tempDir);
    assertTrue(MockPlayerRegistry.isEmpty());
  }

  @AfterEach
  void tearDown() {
    RTPTestSetup.cleanUp();
  }

  @Test
  void probeCodesCarryTheReservedNamespace() {
    assertEquals("PROBE", TestStressCmd.STRESS_MOCKED.namespace());
    assertEquals("PROBE", TestHologramCmd.CONSOLE_STATUS.namespace());
    assertSame(TestStressCmd.STRESS_MOCKED, RtpBitCodes.REGISTRY.byId("PROBE", TestStressCmd.STRESS_MOCKED.id()).orElseThrow());
    assertSame(TestHologramCmd.CONSOLE_STATUS, RtpBitCodes.REGISTRY.byId("PROBE", TestHologramCmd.CONSOLE_STATUS.id()).orElseThrow());
  }

  @Test
  void mockStressClosesEverySessionAfterSuccessfulTeleports() {
    @SuppressWarnings("unchecked")
    RTPWorld<?> world = mock(RTPWorld.class);
    when(world.name()).thenReturn("test-world");
    TestStressCmd.TeleportFn teleport =
        (playerId, target) -> {
          RuntimeMockRTPPlayer player =
              (RuntimeMockRTPPlayer) MockPlayerRegistry.get(playerId);
          assertTrue(player != null, "pipeline receives a registered BIT mock");
          RTPLocation destination = new RTPLocation(world, 12, 80, -4);
          player.setLocation(destination);
          return CompletableFuture.completedFuture(RTPResult.success(destination));
        };

    BitReport report =
        TestStressCmd.runMockProbe("default", world, 2, 3, teleport, 1_000, 5_000, () -> false);

    assertTrue(
        report.findings().stream()
            .anyMatch(finding -> finding.code().equals(TestStressCmd.STRESS_MOCKED.key())));
    assertTrue(MockPlayerRegistry.isEmpty(), "all opened mock sessions must be unregistered");
  }

  @Test
  void timeoutIsRecordedAsCachedComponentTimeout() throws Exception {
    BitComponent component = component("mock-probe-timeout-test");
    RtpBit.RUNNER.register(component);
    try {
      BitResult result =
          ProbeVerdict.record(component, new CompletableFuture<>(), 20)
              .get(1, TimeUnit.SECONDS);

      assertEquals(
          List.of(BitCodes.COMPONENT_TIMEOUT.key()),
          result.report().findings().stream().map(BitFinding::code).toList());
      assertSame(result, RtpBit.RUNNER.last(component.id()));
    } finally {
      RtpBit.RUNNER.unregister(component.id());
    }
  }

  @Test
  void disconnectResultFailuresMapToProbeCodes() {
    TestDisconnectMidflightCmd.Result failed = new TestDisconnectMidflightCmd.Result();
    failed.processingCleared = false;
    failed.invulnerableCleared = false;
    failed.nextTaskCancelled = false;
    failed.teleportDataCleared = false;
    failed.threw = "synthetic failure";

    List<String> codes =
        TestDisconnectMidflightCmd.toBitReport(failed).findings().stream()
            .map(BitFinding::code)
            .toList();

    assertTrue(codes.contains(TestDisconnectMidflightCmd.PROCESSING.key()));
    assertTrue(codes.contains(TestDisconnectMidflightCmd.INVULNERABLE.key()));
    assertTrue(codes.contains(TestDisconnectMidflightCmd.NEXT_TASK.key()));
    assertTrue(codes.contains(TestDisconnectMidflightCmd.TELEPORT_DATA.key()));
    assertTrue(codes.contains(TestDisconnectMidflightCmd.THREW.key()));
  }

  @Test
  void exceptionalCompletionBecomesThrownFinding() throws Exception {
    BitComponent component = component("mock-probe-thrown-test");
    RtpBit.RUNNER.register(component);
    try {
      CompletableFuture<BitReport> failed = new CompletableFuture<>();
      failed.completeExceptionally(new IllegalStateException("synthetic check failure"));

      BitResult result = ProbeVerdict.record(component, failed, 1_000).get(1, TimeUnit.SECONDS);

      assertEquals(
          List.of(BitCodes.COMPONENT_THROWN.key()),
          result.report().findings().stream().map(BitFinding::code).toList());
      assertSame(result, RtpBit.RUNNER.last(component.id()));
    } finally {
      RtpBit.RUNNER.unregister(component.id());
    }
  }

  @Test
  void asyncReplyMockTimeoutUsesTheGlobalTimeoutCode() {
    List<String> codes =
        AsyncReplyTestJob.timeoutReport("mock teleport timed out").findings().stream()
            .map(BitFinding::code)
            .toList();

    assertEquals(List.of(BitCodes.COMPONENT_TIMEOUT.key()), codes);
  }

  private static BitComponent component(String id) {
    return new BitComponent(id, "test.mock-probe", BitTier.IBIT, BitSideEffect.SAFE, 1_000,
        () -> BitReport.of(List.of()));
  }
}
