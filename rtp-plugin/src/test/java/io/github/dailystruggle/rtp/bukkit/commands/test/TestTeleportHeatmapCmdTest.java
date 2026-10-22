package io.github.dailystruggle.rtp.bukkit.commands.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.bit.BitCodes;
import io.github.dailystruggle.bit.BitCode;
import io.github.dailystruggle.bit.BitReport;
import io.github.dailystruggle.bit.BitResult;
import io.github.dailystruggle.bit.BitSideEffect;
import io.github.dailystruggle.bit.BitTier;
import io.github.dailystruggle.rtp.common.bit.RtpBit;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("REQ-API-F-009 teleport heatmap is a named mocked IBIT probe")
class TestTeleportHeatmapCmdTest {
    @Test
    void componentIsHeavyAndRequiresMutatingPermission() {
        TestTeleportHeatmapCmd command = new TestTeleportHeatmapCmd(null);

        assertEquals("teleport-heatmap", command.name());
        assertEquals("rtp.test.mutating", command.permission());
        assertEquals("test.heatmap", TestTeleportHeatmapCmd.COMPONENT.subsystem());
        assertEquals(BitTier.IBIT, TestTeleportHeatmapCmd.COMPONENT.tier());
        assertEquals(BitSideEffect.HEAVY, TestTeleportHeatmapCmd.COMPONENT.sideEffect());
        assertEquals(4700, TestTeleportHeatmapCmd.HEATMAP_MOCKED.id());
        assertTrue(RtpBit.RUNNER.components().contains(TestTeleportHeatmapCmd.COMPONENT));
    }

    @Test
    void registeredComponentReportIncludesMockedInfo() {
        BitResult result = RtpBit.RUNNER.run(TestTeleportHeatmapCmd.COMPONENT, Runnable::run);

        assertTrue(result.report().findings().stream()
                .anyMatch(finding -> finding.codeId() == TestTeleportHeatmapCmd.HEATMAP_MOCKED.id()
                        && finding.severity().name().equals("INFO")));
    }

    @Test
    void sampleCountIsClampedToItsSupportedBounds() {
        assertEquals(TestTeleportHeatmapCmd.DEFAULT_SAMPLES, TestTeleportHeatmapCmd.samples(Map.of()));
        assertEquals(TestTeleportHeatmapCmd.MIN_SAMPLES,
                TestTeleportHeatmapCmd.samples(Map.of("samples", List.of("0"))));
        assertEquals(TestTeleportHeatmapCmd.MAX_SAMPLES,
                TestTeleportHeatmapCmd.samples(Map.of("samples", List.of("9999"))));
        assertEquals(32, TestTeleportHeatmapCmd.samples(Map.of("samples", List.of("32"))));
    }

    @Test
    void timeoutVerdictKeepsTimeoutAndAddsMockedInfo() throws Exception {
        BitResult timedOut = ProbeVerdict.record(
                        TestTeleportHeatmapCmd.COMPONENT, new CompletableFuture<>(), 20)
                .get(1, TimeUnit.SECONDS);

        BitResult completed = TestTeleportHeatmapCmd.withMockedFinding(timedOut);

        assertTrue(completed.report().findings().stream()
                .anyMatch(finding -> finding.codeId() == BitCodes.COMPONENT_TIMEOUT.id()));
        assertTrue(completed.report().findings().stream()
                .anyMatch(finding -> finding.codeId() == TestTeleportHeatmapCmd.HEATMAP_MOCKED.id()
                        && finding.severity().name().equals("INFO")));
    }

    @Test
    void everyHeatmapFailureCodeProducesAFindingAndMockedNotice() {
        List<BitCode> failureCodes = List.of(
                TestTeleportHeatmapCmd.HEATMAP_UNAVAILABLE,
                TestTeleportHeatmapCmd.HEATMAP_TELEPORT_FAILED,
                TestTeleportHeatmapCmd.HEATMAP_LANDING_MISSING,
                TestTeleportHeatmapCmd.HEATMAP_EXPORT_FAILED,
                TestTeleportHeatmapCmd.HEATMAP_CANCELLED);

        for (BitCode code : failureCodes) {
            BitReport report = TestTeleportHeatmapCmd.failureReport(code, "heatmap.test", "test failure");

            assertTrue(report.findings().stream().anyMatch(finding -> finding.codeId() == code.id()), code.key());
            assertTrue(report.findings().stream()
                    .anyMatch(finding -> finding.codeId() == TestTeleportHeatmapCmd.HEATMAP_MOCKED.id()
                            && finding.severity().name().equals("INFO")), code.key());
        }
    }
}
