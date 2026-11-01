package io.github.dailystruggle.rtp.common.commands.test;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.bit.BitComponent;
import io.github.dailystruggle.bit.BitReport;
import io.github.dailystruggle.bit.BitSideEffect;
import io.github.dailystruggle.bit.BitTier;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.action.ActionCbitCounters;
import io.github.dailystruggle.rtp.common.bit.RtpBit;
import io.github.dailystruggle.rtp.common.bit.RtpBitComponents;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import java.io.File;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("REQ-API-F-009 /rtp test BIT status output")
class TestStatusCmdTest {
  @TempDir File tempDir;
  private MockRTPServerAccessor accessor;

  @BeforeEach
  void setUp() {
    accessor = RTPTestSetup.install(tempDir);
    new TestAccessorCmd(null);
    new TestChunkTicketCmd(null);
  }

  @Test
  void statusShowsNotRunAndCachedHealthForRegisteredComponents() {
    String id = "status-test-not-run";
    BitComponent component = new BitComponent(id, "test.status", BitTier.PBIT, BitSideEffect.SAFE,
        1_000, () -> BitReport.of(List.of()));
    RtpBit.RUNNER.register(component);
    try {
      new TestStatusCmd(null).onCommand(RTPAPI.serverId, Map.of(), null);
      assertTrue(accessor.logMessages.stream().anyMatch(message ->
          message.contains(BitTableOutput.PREFIX) && message.contains("component")));
      assertTrue(accessor.logMessages.stream().anyMatch(message ->
          message.contains(BitTableOutput.PREFIX) && message.contains(id) && message.contains("NOT_RUN")));

      accessor.logMessages.clear();
      RtpBit.RUNNER.run(component, Runnable::run);
      new TestStatusCmd(null).onCommand(RTPAPI.serverId, Map.of(), null);
      assertTrue(accessor.logMessages.stream().anyMatch(message ->
          message.contains(BitTableOutput.PREFIX) && message.contains(id) && message.contains("HEALTHY")));
      assertTrue(accessor.logMessages.stream().noneMatch(message ->
          message.contains("[RTP test/accessor]") || message.contains("[RTP test/chunk-ticket]")));
    } finally {
      RtpBit.RUNNER.unregister(id);
    }
  }

  @Test
  void jsonStatusUsesBitPrefixNotLegacyAcceptancePrefix() {
    new TestStatusCmd(null).onCommand(RTPAPI.serverId, Map.of("json", List.of("true")), null);

    assertTrue(accessor.logMessages.stream().anyMatch(message ->
        message.contains("[RTP bit/test.") && message.contains("\"component\":\"accessor\"")));
    assertTrue(accessor.logMessages.stream().noneMatch(message ->
        message.contains("[RTP test/accessor]")));
  }

  @Test
  void statusRefreshesActionCbitFromLiveCounters() {
    RtpBitComponents.registerDefaults();
    ActionCbitCounters.reset();
    ActionCbitCounters.recordUnknownAction("status_test_unknown");
    try {
      new TestStatusCmd(null).onCommand(RTPAPI.serverId, Map.of(), null);

      assertTrue(accessor.logMessages.stream().anyMatch(message ->
          message.contains("action-cbit") && message.contains("DEGRADED")));
    } finally {
      ActionCbitCounters.reset();
    }
  }
}
