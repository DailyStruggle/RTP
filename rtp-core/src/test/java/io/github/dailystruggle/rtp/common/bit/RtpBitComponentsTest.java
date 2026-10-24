package io.github.dailystruggle.rtp.common.bit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.bit.BitComponent;
import io.github.dailystruggle.bit.BitHealth;
import io.github.dailystruggle.bit.BitReport;
import io.github.dailystruggle.bit.BitSideEffect;
import io.github.dailystruggle.bit.BitTier;
import io.github.dailystruggle.bit.BuiltInTestable;
import io.github.dailystruggle.bit.PbitResult;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.action.ActionCbitCounters;
import io.github.dailystruggle.rtp.common.action.ActionConfigLoader;
import io.github.dailystruggle.rtp.common.action.ActionManager;
import io.github.dailystruggle.rtp.common.mock.MockRTPScheduler;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("REQ-API-F-009: action and anvil checks register as SAFE PBIT components")
class RtpBitComponentsTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void clearLoadedDefinitions() {
        ActionConfigLoader.loadActions(tempDir.resolve("missing").toFile(), new ActionManager());
    }

    @Test
    void defaultsAreSafePbitComponentsAndRegistrationIsIdempotent() throws Exception {
        RtpBitComponents.registerDefaults();
        List<BitComponent> registered = RtpBit.RUNNER.components();

        assertEquals(5, RtpBitComponents.defaults().size());
        for (BitComponent expected : RtpBitComponents.defaults()) {
            BitComponent actual = registered.stream()
                    .filter(component -> component.id().equals(expected.id()))
                    .findFirst().orElse(null);
            assertNotNull(actual, expected.id());
            assertEquals(BitTier.PBIT, actual.tier());
            assertEquals(BitSideEffect.SAFE, actual.sideEffect());
        }
        BitComponent actionCbit = registered.stream()
                .filter(component -> component.id().equals("action-cbit"))
                .findFirst().orElse(null);
        assertNotNull(actionCbit, "action runtime counters must be registered");
        assertEquals(BitTier.CBIT, actionCbit.tier());
        assertEquals(BitSideEffect.SAFE, actionCbit.sideEffect());
        ActionCbitCounters.reset();
        assertEquals(BitHealth.HEALTHY, actionCbit.check().call().health());
        ActionCbitCounters.recordUnknownAction("components_test_unknown");
        assertEquals(BitHealth.DEGRADED, actionCbit.check().call().health());
        ActionCbitCounters.reset();

        RtpBitComponents.registerDefaults();
        assertEquals(registered, RtpBit.RUNNER.components());
    }

    @Test
    void actionComponentReportsFindingsFromTheFileLoadOverload() throws Exception {
        Path actions = tempDir.resolve("definitions/actions");
        Files.createDirectories(actions);
        Files.writeString(actions.resolve("bad.yml"), "lifecycle:\n  onStart:\n    - CONSOL: say hello\n");

        ActionConfigLoader.loadActions(tempDir.toFile(), new ActionManager());
        BitReport report = RtpBitComponents.actionDefinitions().check().call();

        assertTrue(report.findings().stream().anyMatch(finding ->
                finding.code().equals("ACTION_UNKNOWN_STEP_KEY")));
        assertEquals(BitHealth.DEGRADED, report.health());
        assertTrue(report.findings().stream().allMatch(finding -> finding.severity() == Level.WARNING));
    }

    @Test
    @DisplayName("REQ-API-F-009 a prefix reserved by two owners reports BIT_NAMESPACE_CONFLICT")
    void namespaceConflictIsAStartupError() {
        io.github.dailystruggle.bit.BitCodeRegistry registry = new io.github.dailystruggle.bit.BitCodeRegistry();
        registry.reserve("GUI", "LeafRTPGuiAddon");
        registry.reserve("GUI", "LeafRTPGuiAddon");
        registry.reserve("SHOP");
        registry.reserve("SHOP", "ShopAddon");
        assertEquals(BitHealth.HEALTHY, RtpBitComponents.namespaceReport(registry).health());

        registry.reserve("GUI", "OtherGui");
        BitReport report = RtpBitComponents.namespaceReport(registry);

        assertEquals(BitHealth.FAILED, report.health());
        assertEquals(1, report.findings().size());
        var finding = report.findings().get(0);
        assertEquals("BIT_NAMESPACE_CONFLICT", finding.code());
        assertEquals("GUI", finding.path());
        assertTrue(finding.message().contains("LeafRTPGuiAddon") && finding.message().contains("OtherGui"));
        assertEquals(BitHealth.HEALTHY, RtpBitComponents.namespaceReport(RtpBitCodes.REGISTRY).health(),
                "core namespaces must not conflict");
    }

    @Test
    void emptyActionSnapshotIsHealthy() throws Exception {
        Files.createDirectories(tempDir.resolve("definitions/actions"));
        ActionConfigLoader.loadActions(tempDir.toFile(), new ActionManager());

        BitReport report = RtpBitComponents.actionDefinitions().check().call();

        assertEquals(BitHealth.HEALTHY, report.health());
        assertTrue(report.findings().isEmpty());
    }

    @Test
    void degradedAnvilFailureIsWarningAndFailedHealthIsError() {
        BitReport degraded = RtpBitComponents.bitReport("stub-degraded", stub(false, BitHealth.DEGRADED));
        BitReport failed = RtpBitComponents.bitReport("stub-failed", stub(false, BitHealth.FAILED));
        BitReport passed = RtpBitComponents.bitReport("stub-healthy", stub(true, BitHealth.HEALTHY));

        assertEquals(BitHealth.DEGRADED, degraded.health());
        assertEquals(Level.WARNING, degraded.findings().get(0).severity());
        assertEquals(BitHealth.FAILED, failed.health());
        assertEquals(Level.ERROR, failed.findings().get(0).severity());
        assertEquals(BitHealth.HEALTHY, passed.health());
        assertTrue(passed.findings().isEmpty());
    }

    @Test
    void optionalUnavailableAnvilEngineIsInformationalAndHealthy() {
        BitReport report = RtpBitComponents.bitReport("stub-optional", stub(false, BitHealth.DEGRADED), true);

        assertEquals(BitHealth.HEALTHY, report.health());
        assertEquals(1, report.findings().size());
        assertEquals(io.github.dailystruggle.bit.BitCodes.OPTIONAL_UNAVAILABLE.key(),
                report.findings().get(0).code());
        assertEquals(Level.INFO, report.findings().get(0).severity());
    }

    @Test
    void startupWaitsTwoTicksAndLogsExactlyOneSummary() {
        RTPScheduler previousScheduler = RTPRuntime.scheduler();
        RTPServerAccessor previousAccessor = RTPRuntime.serverAccessor();
        Logger logger = Logger.getLogger("RTP");
        Handler previousHandlers[] = logger.getHandlers();
        boolean useParentHandlers = logger.getUseParentHandlers();
        java.util.logging.Level previousLevel = logger.getLevel();
        CapturingHandler handler = new CapturingHandler();
        MockRTPScheduler scheduler = new MockRTPScheduler();
        try {
            RTPRuntime.setServerAccessor(null);
            RTPRuntime.setScheduler(scheduler);
            logger.setUseParentHandlers(false);
            logger.setLevel(java.util.logging.Level.ALL);
            for (Handler previous : previousHandlers) logger.removeHandler(previous);
            logger.addHandler(handler);

            RtpBitStartup.schedule();
            scheduler.tick(1);
            assertTrue(handler.summaries().isEmpty());
            scheduler.tick(1);

            assertEquals(1, handler.summaries().size());
            assertTrue(handler.summaries().get(0).contains("[RTP bit] startup PBIT health="));
        } finally {
            RTPRuntime.setScheduler(previousScheduler);
            RTPRuntime.setServerAccessor(previousAccessor);
            logger.removeHandler(handler);
            for (Handler previous : previousHandlers) logger.addHandler(previous);
            logger.setUseParentHandlers(useParentHandlers);
            logger.setLevel(previousLevel);
        }
    }

    @Test
    void summaryIncludesAggregateHealthAndFindingCounts() {
        BitComponent component = new BitComponent("test-degraded", "anvil", BitTier.PBIT,
                BitSideEffect.SAFE, 1_000, () -> BitReport.of(List.of()));
        BitReport report = new BitReport(BitReport.CURRENT_SCHEMA, BitHealth.DEGRADED,
                List.of(new io.github.dailystruggle.bit.BitFinding(Level.WARNING, "WARN", "test", "", "warning", "", "")));
        io.github.dailystruggle.bit.BitResult result =
                new io.github.dailystruggle.bit.BitResult(component, report, 1, 1);

        assertEquals("[RTP bit] startup PBIT health=DEGRADED components=1 errors=0 warnings=1 nonHealthy=[test-degraded]",
                RtpBitStartup.summary(List.of(result)));
    }

    private static BuiltInTestable stub(boolean passed, BitHealth health) {
        return new BuiltInTestable() {
            @Override
            public PbitResult executePbit() {
                return passed ? PbitResult.ok("stub", 0) : PbitResult.failed("stub", 0, "canary failed", null);
            }

            @Override
            public BitHealth getHealthStatus() {
                return health;
            }
        };
    }

    private static final class CapturingHandler extends Handler {
        private final java.util.ArrayList<String> messages = new java.util.ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            if (record != null && record.getMessage() != null) messages.add(record.getMessage());
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}

        List<String> summaries() {
            return messages.stream().filter(message -> message.startsWith("[RTP bit] startup PBIT ")).toList();
        }
    }
}
