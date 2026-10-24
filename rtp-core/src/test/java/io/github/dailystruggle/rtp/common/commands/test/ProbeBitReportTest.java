package io.github.dailystruggle.rtp.common.commands.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.bit.BitHealth;
import io.github.dailystruggle.bit.BitComponent;
import io.github.dailystruggle.bit.BitResult;
import io.github.dailystruggle.bit.BitReport;
import io.github.dailystruggle.bit.BitSideEffect;
import io.github.dailystruggle.bit.BitTier;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.bit.RtpBit;
import io.github.dailystruggle.rtp.common.bit.RtpBitCodes;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import java.io.File;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("REQ-API-F-009 BIT reports for runtime probes")
class ProbeBitReportTest {
  @TempDir File tempDir;
  private MockRTPServerAccessor accessor;

  @BeforeEach
  void setUp() {
    accessor = RTPTestSetup.install(tempDir);
  }

  @Test
  void passingChecksProduceHealthyReport() {
    BitReport report = ProbeBitReport.forProbe("probe")
        .check(TestAccessorCmd.TEST_ACCESSOR_MATERIALS, true, "accessor.materials", "ok")
        .build();

    assertEquals(BitHealth.HEALTHY, report.health());
    assertTrue(report.findings().isEmpty());
  }

  @Test
  void failingCheckProducesLocatedRegisteredFinding() {
    BitReport report = ProbeBitReport.forProbe("probe")
        .check(TestAccessorCmd.TEST_ACCESSOR_MATERIALS, false, "accessor.materials", "missing materials")
        .build();

    assertEquals(BitHealth.FAILED, report.health());
    assertEquals(1, report.findings().size());
    assertEquals(TestAccessorCmd.TEST_ACCESSOR_MATERIALS.id(), report.findings().get(0).codeId());
    assertEquals("TEST_ACCESSOR_MATERIALS", report.findings().get(0).code());
    assertEquals("rtp test probe", report.findings().get(0).source());
    assertEquals("accessor.materials", report.findings().get(0).path());
    assertEquals("missing materials", report.findings().get(0).message());
  }

  @Test
  void lastDelegatesToTheRunnerCache() {
    BitReport first = BitReport.of(List.of());
    BitReport second = ProbeBitReport.forProbe("unique-probe")
        .check(TestAccessorCmd.TEST_ACCESSOR_MATERIALS, false, "accessor.materials", "failed")
        .build();
    BitComponent component = new BitComponent("unique-probe", "test", BitTier.IBIT,
        BitSideEffect.SAFE, 1_000, () -> first);
    RtpBit.RUNNER.register(component);
    try {
      RtpBit.RUNNER.run(component.withCheck(() -> first), Runnable::run);
      assertEquals(first, ProbeBitReport.last("unique-probe"));

      RtpBit.RUNNER.run(component.withCheck(() -> second), Runnable::run);

      assertEquals(second, ProbeBitReport.last("unique-probe"));
    } finally {
      RtpBit.RUNNER.unregister("unique-probe");
    }
  }

  @Test
  void jsonOutputUsesBitPrefixAndDoesNotReuseAcceptancePrefix() {
    BitReport report = ProbeBitReport.forProbe("unique-probe")
        .check(TestAccessorCmd.TEST_ACCESSOR_MATERIALS, true, "accessor.materials", "ok")
        .build();

    BitResult result = new BitResult(new BitComponent("unique-probe", "test", BitTier.IBIT,
        BitSideEffect.SAFE, 1_000, () -> report), report, 0, System.currentTimeMillis());
    ProbeBitReport.emitJson(RTPAPI.serverId, result);

    String output = accessor.logMessages.stream()
        .filter(message -> message.contains("[RTP bit/test.unique-probe]"))
        .findFirst()
        .orElseThrow();
    assertTrue(output.contains(report.toJson()));
    assertFalse(output.contains("[RTP test/"));
  }

  @Test
  void jsonParameterRequiresTrueValue() {
    assertTrue(ProbeBitReport.isJson(java.util.Map.of("json", List.of("true"))));
    assertTrue(ProbeBitReport.isJson(java.util.Map.of("json", List.of("TRUE"))));
    assertFalse(ProbeBitReport.isJson(java.util.Map.of("json", List.of("false"))));
    assertFalse(ProbeBitReport.isJson(java.util.Map.of()));
    assertEquals(java.lang.System.Logger.Level.ERROR, TestAccessorCmd.TEST_ACCESSOR_MATERIALS.defaultSeverity());
  }

  @Test
  void probeCodesUseTheRegisteredTestNamespace() {
    assertEquals("TEST", TestAccessorCmd.TEST_ACCESSOR_UNAVAILABLE.namespace());
    assertEquals("TEST", TestAccessorCmd.TEST_ACCESSOR_WORLD_BORDER.namespace());
    assertEquals("TEST", TestChunkTicketCmd.TEST_CHUNK_TICKET_OPEN.namespace());
    assertEquals("TEST", TestChunkTicketCmd.TEST_CHUNK_TICKET_STALE_SENTINEL.namespace());
    assertEquals(3001, TestAccessorCmd.TEST_ACCESSOR_UNAVAILABLE.id());
    assertEquals(3101, TestChunkTicketCmd.TEST_CHUNK_TICKET_OPEN.id());
  }

  @Test
  @DisplayName("ADR-121 teleport probe codes occupy 3201-3207 in the TEST namespace without collisions")
  void teleportProbeCodesUseTheirOwnTestBlock() {
    List<io.github.dailystruggle.bit.BitCode> codes = List.of(
        TestTeleportCmd.TEST_TELEPORT_MOCKED,
        TestTeleportCmd.TEST_TELEPORT_UNAVAILABLE,
        TestTeleportCmd.TEST_TELEPORT_REJECTED,
        TestTeleportCmd.TEST_TELEPORT_SET_LOCATION_NOT_REACHED,
        TestTeleportCmd.TEST_TELEPORT_PROCESSING_LEAK,
        TestTeleportCmd.TEST_TELEPORT_TRACKER_LEAK,
        TestTeleportCmd.TEST_TELEPORT_TIMEOUT);
    for (int i = 0; i < codes.size(); i++) {
      io.github.dailystruggle.bit.BitCode code = codes.get(i);
      assertEquals(3201 + i, code.id(), code.key());
      assertEquals("TEST", code.namespace(), code.key());
      assertTrue(code.key().startsWith("TEST_TELEPORT_"), code.key());
      assertEquals(code, RtpBitCodes.REGISTRY.byId("TEST", code.id()).orElseThrow());
      assertEquals(code, RtpBitCodes.REGISTRY.byKey(code.key()).orElseThrow());
      assertEquals("test.teleport", code.subsystem());
      java.lang.System.Logger.Level expected = i == 0
          ? java.lang.System.Logger.Level.INFO : java.lang.System.Logger.Level.ERROR;
      assertEquals(expected, code.defaultSeverity(), code.key());
    }
    assertEquals(codes.size(), codes.stream().map(io.github.dailystruggle.bit.BitCode::key).distinct().count());
    assertFalse(codes.stream().anyMatch(code -> code.id() == TestAccessorCmd.TEST_ACCESSOR_UNAVAILABLE.id()
        || code.id() == TestChunkTicketCmd.TEST_CHUNK_TICKET_OPEN.id()));
  }
}
