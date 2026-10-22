package io.github.dailystruggle.rtp.common.commands.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.bit.BitHealth;
import io.github.dailystruggle.bit.BitReport;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.bit.RtpBit;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests verifying {@link TestAccessorCmd} ({@code rtp test accessor}).
 */
@DisplayName("REQ-API-F-009 accessor probe BIT report")
class TestAccessorCmdTest {

  @TempDir File tempDir;
  private MockRTPServerAccessor accessor;

  @BeforeEach
  void setUp() {
    accessor = RTPTestSetup.install(tempDir);
  }

  private static Map<String, List<String>> noArgs() {
    return new HashMap<>();
  }

  @Test
  void metadata_isStable() {
    TestAccessorCmd cmd = new TestAccessorCmd(null);
    assertEquals("accessor", cmd.name());
    assertEquals("rtp.test.accessor", cmd.permission());
    assertNotNull(cmd.description());
    assertFalse(cmd.description().isEmpty());
  }

  @Test
  void runProbe_mockSetup_passes() {
    TestAccessorCmd.Result r = TestAccessorCmd.runProbe(RTPAPI.serverId);
    assertTrue(r.pass, () -> "failed: " + r.message);
    assertTrue(r.materialsValid);
    assertTrue(r.tagsValid);
    assertTrue(r.senderValid);
    assertTrue(r.formatValid);
    assertTrue(r.threadValid);
    assertTrue(r.biomeValid);
    assertTrue(r.menuValid);
    assertTrue(r.versionValid);
    assertTrue(r.worldValid);
    assertTrue(r.messagingValid);
    assertTrue(r.subsystemValid);
    assertTrue(r.worldBorderValid);
    assertEquals("ok", r.message);
  }

  @Test
  void registeredComponentRunsItsCheckThroughTheRunner() {
    assertEquals(BitHealth.HEALTHY,
        RtpBit.RUNNER.run(TestAccessorCmd.COMPONENT, Runnable::run).report().health());
  }

  @Test
  void runProbe_nullServerAccessor_failsGracefully() {
    RTPRuntime.setServerAccessor(null);
    TestAccessorCmd.Result r = TestAccessorCmd.runProbe(RTPAPI.serverId);
    assertFalse(r.pass);
    assertTrue(r.message.contains("RTPServerAccessor is null"));
  }

  @Test
  void runProbe_emptyMaterials_fails() {
    MockRTPServerAccessor emptyMatsAccessor = new MockRTPServerAccessor(tempDir) {
      @Override
      public Set<String> materials() {
        return new HashSet<>();
      }
    };
    RTPRuntime.setServerAccessor(emptyMatsAccessor);
    RTPAPI.serverAccessor = emptyMatsAccessor;

    TestAccessorCmd.Result r = TestAccessorCmd.runProbe(RTPAPI.serverId);
    assertFalse(r.pass);
    assertFalse(r.materialsValid);
    assertTrue(r.message.contains("materials"));
  }

  @Test
  void runProbe_namespacedMaterials_passes() {
    MockRTPServerAccessor namespacedAccessor = new MockRTPServerAccessor(tempDir) {
      @Override
      public Set<String> materials() {
        Set<String> out = new HashSet<>();
        out.add("MINECRAFT:AIR");
        out.add("MINECRAFT:STONE");
        out.add("MINECRAFT:GRASS_BLOCK");
        return out;
      }
    };
    RTPRuntime.setServerAccessor(namespacedAccessor);
    RTPAPI.serverAccessor = namespacedAccessor;

    TestAccessorCmd.Result r = TestAccessorCmd.runProbe(RTPAPI.serverId);
    assertTrue(r.materialsValid, () -> "details: " + r.details);
  }

  @Test
  void runProbe_unknownMaterialsOnly_fails() {
    MockRTPServerAccessor unknownMatsAccessor = new MockRTPServerAccessor(tempDir) {
      @Override
      public Set<String> materials() {
        Set<String> out = new HashSet<>();
        out.add("EXAMPLEMOD:WEIRD_BLOCK");
        return out;
      }
    };
    RTPRuntime.setServerAccessor(unknownMatsAccessor);
    RTPAPI.serverAccessor = unknownMatsAccessor;

    TestAccessorCmd.Result r = TestAccessorCmd.runProbe(RTPAPI.serverId);
    assertFalse(r.materialsValid);
    assertFalse(r.pass);
  }

  @Test
  void toBitReport_identifiesFailedMaterialsSurface() {
    TestAccessorCmd.Result result = new TestAccessorCmd.Result();
    result.pass = false;
    result.details.add("materials empty or missing common materials");

    BitReport report = TestAccessorCmd.toBitReport(result);

    assertEquals(BitHealth.FAILED, report.health());
    assertTrue(report.findings().stream().anyMatch(finding ->
        finding.code().equals("TEST_ACCESSOR_MATERIALS")
            && finding.path().equals("accessor.materials")));
  }

  @Test
  void onCommand_emitsSummaryToPlayer() {
    MockRTPPlayer player = new MockRTPPlayer(UUID.randomUUID(), "player1", null);
    accessor.addPlayer(player);

    boolean ret = new TestAccessorCmd(null).onCommand(player.uuid(), noArgs(), null);
    assertTrue(ret);
    assertTrue(
        player.sentMessages.stream().anyMatch(m -> m.contains("accessor")),
        player.sentMessages.toString());
  }

  @Test
  void onCommand_console_logsSummary() {
    boolean ret = new TestAccessorCmd(null).onCommand(RTPAPI.serverId, noArgs(), null);
    assertTrue(ret);
    assertTrue(
        accessor.logMessages.stream().anyMatch(m -> m.contains("accessor")),
        accessor.logMessages.toString());
  }

  @Test
  void onCommand_jsonEmitsBitReportAndKeepsLegacySummary() {
    MockRTPPlayer player = new MockRTPPlayer(UUID.randomUUID(), "json-player", null);
    accessor.addPlayer(player);

    boolean ret = new TestAccessorCmd(null).onCommand(
        player.uuid(), Map.of("json", List.of("true")), null);

    assertTrue(ret);
    assertTrue(player.sentMessages.stream().anyMatch(m -> m.contains("[RTP test/accessor] pass=")));
    assertTrue(player.sentMessages.stream().anyMatch(m -> m.contains("[RTP bit/test.accessor]")));
    assertTrue(accessor.logMessages.stream().anyMatch(m -> m.contains("[RTP bit/test.accessor]")));
    assertEquals(BitHealth.HEALTHY, ProbeBitReport.last("accessor").health());
  }

  @Test
  void onCommand_defaultEmitsTableWithoutTouchingLegacyGrepLine() {
    new TestAccessorCmd(null).onCommand(RTPAPI.serverId, noArgs(), null);

    assertTrue(accessor.logMessages.stream().anyMatch(m ->
        m.contains(BitTableOutput.PREFIX) && m.contains("accessor") && m.contains("HEALTHY")));
    assertTrue(accessor.logMessages.stream()
        .filter(m -> m.contains(BitTableOutput.PREFIX))
        .noneMatch(m -> m.contains("[RTP test/accessor]")));
    assertEquals(1, accessor.logMessages.stream()
        .filter(m -> m.contains("[RTP test/accessor] pass=")).count());
  }

  @Test
  void nextCommandNonNull_shortCircuits() {
    assertTrue(
        new TestAccessorCmd(null)
            .onCommand(RTPAPI.serverId, noArgs(), new TestAccessorCmd(null)));
  }
}
