package io.github.dailystruggle.rtp.common.bit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.bit.BitComponent;
import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.bit.BitHealth;
import io.github.dailystruggle.bit.BitReport;
import io.github.dailystruggle.bit.BitSideEffect;
import io.github.dailystruggle.bit.BitTier;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CoreFallbackCbitCounters} ({@code core-fallback-cbit}) (ADR-121, REQ-RTP-S-004).
 */
@DisplayName("REQ-API-F-009 & REQ-RTP-S-004 — CoreFallbackCbitCounters CBIT component and lock parity")
class CoreFallbackCbitCountersTest {

    @BeforeEach
    void setUp() {
        BitSeverityPolicy.resetDefaults();
        CoreFallbackCbitCounters.reset();
    }

    @AfterEach
    void tearDown() {
        CoreFallbackCbitCounters.reset();
        BitSeverityPolicy.resetDefaults();
    }

    @Test
    @DisplayName("Clean baseline reports HEALTHY with zero findings")
    void cleanBaseline_reportsHealthy() {
        BitReport report = CoreFallbackCbitCounters.report();
        assertEquals(BitHealth.HEALTHY, report.health());
        assertTrue(report.findings().isEmpty());
        assertEquals(0L, CoreFallbackCbitCounters.lifecycleCleanupCount());
        assertEquals(0L, CoreFallbackCbitCounters.actionDispatchCount());
        assertEquals(0L, CoreFallbackCbitCounters.menuRedeemCount());
        assertEquals(0L, CoreFallbackCbitCounters.editorSessionCount());
        assertEquals(0L, CoreFallbackCbitCounters.setupWizardCount());
        assertEquals(0L, CoreFallbackCbitCounters.pregenExtractorCount());
        assertEquals(0L, CoreFallbackCbitCounters.configLookupCount());
        assertEquals(0L, CoreFallbackCbitCounters.testProbeCleanupCount());
    }

    @Test
    @DisplayName("All eight record* methods increment counters, populate report findings, and clear on reset")
    void allEightCounters_recordAndResetCleanly() {
        CoreFallbackCbitCounters.recordLifecycleCleanupDegraded(
                "RTP.stop.futureComplete", new IllegalStateException("stop error"));
        CoreFallbackCbitCounters.recordActionDispatchDegraded(
                "ActionManager.reservationClose", new RuntimeException("close failed"));
        CoreFallbackCbitCounters.recordMenuRedeemDegraded(
                "MenuRedeemSubcommand.getData:config", new IllegalArgumentException("bad key"));
        CoreFallbackCbitCounters.recordEditorSessionDegraded(
                "EditorSessionManager.telemetry.snapshot", new RuntimeException("metrics unavailable"));
        CoreFallbackCbitCounters.recordSetupWizardDegraded(
                "SetupHandlerSupport.collectWorldNames", new RuntimeException("world list error"));
        CoreFallbackCbitCounters.recordPregenExtractorDegraded(
                "PregenBiomeExtractor.readColumnProbe", new RuntimeException("corrupt column"));
        CoreFallbackCbitCounters.recordConfigLookupDegraded(
                "RTP.getAirBlocks", new RuntimeException("parser failure"));
        CoreFallbackCbitCounters.recordTestProbeCleanupDegraded(
                "chunk-ticket.forgetChunkAt", new RuntimeException("ticket release error"));

        assertEquals(1L, CoreFallbackCbitCounters.lifecycleCleanupCount());
        assertEquals(1L, CoreFallbackCbitCounters.actionDispatchCount());
        assertEquals(1L, CoreFallbackCbitCounters.menuRedeemCount());
        assertEquals(1L, CoreFallbackCbitCounters.editorSessionCount());
        assertEquals(1L, CoreFallbackCbitCounters.setupWizardCount());
        assertEquals(1L, CoreFallbackCbitCounters.pregenExtractorCount());
        assertEquals(1L, CoreFallbackCbitCounters.configLookupCount());
        assertEquals(1L, CoreFallbackCbitCounters.testProbeCleanupCount());

        assertTrue(CoreFallbackCbitCounters.lastLifecycleCleanupDetail()
                .contains("RTP.stop.futureComplete (IllegalStateException: stop error)"));
        assertTrue(CoreFallbackCbitCounters.lastActionDispatchDetail()
                .contains("ActionManager.reservationClose (RuntimeException: close failed)"));
        assertTrue(CoreFallbackCbitCounters.lastMenuRedeemDetail()
                .contains("MenuRedeemSubcommand.getData:config (IllegalArgumentException: bad key)"));
        assertTrue(CoreFallbackCbitCounters.lastEditorSessionDetail()
                .contains("EditorSessionManager.telemetry.snapshot (RuntimeException: metrics unavailable)"));
        assertTrue(CoreFallbackCbitCounters.lastSetupWizardDetail()
                .contains("SetupHandlerSupport.collectWorldNames (RuntimeException: world list error)"));
        assertTrue(CoreFallbackCbitCounters.lastPregenExtractorDetail()
                .contains("PregenBiomeExtractor.readColumnProbe (RuntimeException: corrupt column)"));
        assertTrue(CoreFallbackCbitCounters.lastConfigLookupDetail()
                .contains("RTP.getAirBlocks (RuntimeException: parser failure)"));
        assertTrue(CoreFallbackCbitCounters.lastTestProbeCleanupDetail()
                .contains("chunk-ticket.forgetChunkAt (RuntimeException: ticket release error)"));

        BitReport report = CoreFallbackCbitCounters.report();
        assertEquals(BitHealth.DEGRADED, report.health());
        assertEquals(8, report.findings().size());

        List<String> codes = report.findings().stream().map(BitFinding::code).sorted().toList();
        assertEquals(
                List.of(
                        CoreFallbackCbitCounters.ACTION_DISPATCH_DEGRADED.key(),
                        CoreFallbackCbitCounters.CONFIG_LOOKUP_DEGRADED.key(),
                        CoreFallbackCbitCounters.EDITOR_SESSION_DEGRADED.key(),
                        CoreFallbackCbitCounters.LIFECYCLE_CLEANUP_DEGRADED.key(),
                        CoreFallbackCbitCounters.MENU_REDEEM_DEGRADED.key(),
                        CoreFallbackCbitCounters.PREGEN_EXTRACTOR_DEGRADED.key(),
                        CoreFallbackCbitCounters.SETUP_WIZARD_DEGRADED.key(),
                        CoreFallbackCbitCounters.TEST_PROBE_CLEANUP_DEGRADED.key()),
                codes);

        CoreFallbackCbitCounters.reset();
        BitReport afterReset = CoreFallbackCbitCounters.report();
        assertEquals(BitHealth.HEALTHY, afterReset.health());
        assertTrue(afterReset.findings().isEmpty());
        assertEquals("", CoreFallbackCbitCounters.lastLifecycleCleanupDetail());
        assertEquals("", CoreFallbackCbitCounters.lastTestProbeCleanupDetail());
    }

    @Test
    @DisplayName("String detail overloads format subject and detail and log only on first trip (S-004)")
    void stringDetailOverloadsAndFirstTripRateLimiting() {
        var previousAccessor = RTPRuntime.serverAccessor();
        Logger logger = Logger.getLogger("RTP");
        Handler[] previousHandlers = logger.getHandlers();
        boolean useParentHandlers = logger.getUseParentHandlers();
        java.util.logging.Level previousLevel = logger.getLevel();
        List<LogRecord> records = new ArrayList<>();
        Handler capturingHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record != null) records.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };

        try {
            RTPRuntime.setServerAccessor(null);
            logger.setUseParentHandlers(false);
            logger.setLevel(java.util.logging.Level.ALL);
            for (Handler h : previousHandlers) logger.removeHandler(h);
            logger.addHandler(capturingHandler);

            CoreFallbackCbitCounters.recordConfigLookupDegraded("RTP.getAirBlocks", "first failure");
            CoreFallbackCbitCounters.recordConfigLookupDegraded("RTP.getAirBlocks", "second failure");
            CoreFallbackCbitCounters.recordConfigLookupDegraded("RTP.getAirBlocks", "third failure");

            assertEquals(3L, CoreFallbackCbitCounters.configLookupCount());
            assertEquals("RTP.getAirBlocks (third failure)", CoreFallbackCbitCounters.lastConfigLookupDetail());

            long cbitLogs = records.stream()
                    .filter(r -> r.getMessage() != null
                            && r.getMessage().contains("RTP_CBIT_CONFIG_LOOKUP_DEGRADED"))
                    .count();
            assertEquals(1L, cbitLogs, "first trip must log once at WARNING without repeating on subsequent trips");

            CoreFallbackCbitCounters.recordLifecycleCleanupDegraded("RTP.stop", "detail-1");
            CoreFallbackCbitCounters.recordActionDispatchDegraded("ActionManager", "detail-2");
            CoreFallbackCbitCounters.recordMenuRedeemDegraded("MenuRedeemSubcommand", "detail-3");
            CoreFallbackCbitCounters.recordEditorSessionDegraded("EditorSessionManager", "detail-4");
            CoreFallbackCbitCounters.recordSetupWizardDegraded("SetupConfirmCmd", "detail-5");
            CoreFallbackCbitCounters.recordPregenExtractorDegraded("PregenBiomeExtractor", "detail-6");
            CoreFallbackCbitCounters.recordTestProbeCleanupDegraded("TestEventsCmd", "detail-8");
            assertEquals(8, CoreFallbackCbitCounters.report().findings().size());
        } finally {
            RTPRuntime.setServerAccessor(previousAccessor);
            logger.removeHandler(capturingHandler);
            for (Handler h : previousHandlers) logger.addHandler(h);
            logger.setUseParentHandlers(useParentHandlers);
            logger.setLevel(previousLevel);
        }
    }

    @Test
    @DisplayName("BitSeverityPolicy overrides apply to CoreFallbackCbitCounters findings")
    void severityPolicyOverrides_suppressAndElevateFindings() {
        CoreFallbackCbitCounters.recordMenuRedeemDegraded(
                "MenuRedeemSubcommand.resolveConfigParser", new RuntimeException("boom"));
        CoreFallbackCbitCounters.recordTestProbeCleanupDegraded(
                "events.closeListener", new RuntimeException("close boom"));

        BitSeverityPolicy.load(
                false,
                false,
                Map.of(
                        CoreFallbackCbitCounters.MENU_REDEEM_DEGRADED.key(), "OFF",
                        CoreFallbackCbitCounters.TEST_PROBE_CLEANUP_DEGRADED.key(), "ERROR"));

        BitReport report = CoreFallbackCbitCounters.report();
        assertEquals(BitHealth.FAILED, report.health());
        assertEquals(1, report.findings().size());
        BitFinding finding = report.findings().get(0);
        assertEquals(CoreFallbackCbitCounters.TEST_PROBE_CLEANUP_DEGRADED.key(), finding.code());
        assertEquals(Level.ERROR, finding.severity());
    }

    @Test
    @DisplayName("CORE_FALLBACK_CBIT registers in RtpBitComponents and matches docs/dev/bit-codes.lock")
    void componentRegistrationAndLockFileAlignment() throws Exception {
        RtpBitComponents.registerDefaults();
        BitComponent registered = RtpBit.RUNNER.components().stream()
                .filter(c -> c.id().equals("core-fallback-cbit"))
                .findFirst()
                .orElse(null);
        assertNotNull(registered);
        assertEquals(BitTier.CBIT, registered.tier());
        assertEquals(BitSideEffect.SAFE, registered.sideEffect());

        var registry = RtpBitCodes.initialiseOwners();
        Path lockPath = repositoryRoot().resolve("docs/dev/bit-codes.lock");
        List<RtpBitCodeLock.Entry> lockEntries = RtpBitCodeLock.parse(Files.readString(lockPath));
        List<String> violations = RtpBitCodeLock.violations(registry, lockEntries, "RTP");
        assertTrue(violations.isEmpty(), () -> "Lock violations: " + violations);
        for (int id = 1811; id <= 1818; id++) {
            final int codeId = id;
            assertTrue(
                    lockEntries.stream().anyMatch(e -> "RTP".equals(e.namespace()) && e.id() == codeId),
                    "docs/dev/bit-codes.lock must contain RTP " + codeId);
        }
    }

    @Test
    @DisplayName("Target rtp-core catch sites wire into CoreFallbackCbitCounters when fallbacks trip")
    void wiredCatchSites_incrementMatchingCbitCounters() {
        var previousAccessor = RTPRuntime.serverAccessor();
        try {
            RTPRuntime.setServerAccessor(new io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor(new java.io.File("build/tmp/cbit-test")) {
                @Override
                public List<io.github.dailystruggle.rtp.api.world.RTPWorld<?>> getRTPWorlds() {
                    throw new RuntimeException("world list boom");
                }

                @Override
                public boolean executeCommand(java.util.UUID callerId, String command) {
                    throw new RuntimeException("command dispatch boom");
                }
            });

            io.github.dailystruggle.rtp.common.commands.setup.SetupHandlerSupport.collectWorldNames();
            assertEquals(1L, CoreFallbackCbitCounters.setupWizardCount());

            new io.github.dailystruggle.rtp.common.action.ActionManager().sweepStartupOrphans();
            assertEquals(1L, CoreFallbackCbitCounters.actionDispatchCount());

            // Verify RuntimeSignalSource once-per-session CBIT counter latch on reflective OS MXBean failures
            var signalSource = io.github.dailystruggle.rtp.common.tasks.tick.RuntimeSignalSource.instance();
            signalSource.resetDegradedLatches();
            java.lang.reflect.Method failingMethod =
                    CoreFallbackCbitCountersTest.class.getDeclaredMethod("throwingOsBeanMethod");
            failingMethod.setAccessible(true);
            signalSource.sampleProcessCpuLoad(failingMethod, new CoreFallbackCbitCountersTest());
            signalSource.sampleProcessCpuLoad(failingMethod, new CoreFallbackCbitCountersTest());
            assertEquals(1L, CoreFallbackCbitCounters.lifecycleCleanupCount(),
                    "Reflective OS MXBean failure must trip CBIT counter once per session");
            assertTrue(CoreFallbackCbitCounters.lastLifecycleCleanupDetail()
                    .contains("RuntimeSignalSource.sampleProcessCpuLoad (UnsupportedOperationException: os bean boom)"));

            signalSource.sampleRtpAsyncCpuShare(failingMethod, new CoreFallbackCbitCountersTest());
            signalSource.sampleRtpAsyncCpuShare(failingMethod, new CoreFallbackCbitCountersTest());
            assertEquals(2L, CoreFallbackCbitCounters.lifecycleCleanupCount(),
                    "sampleRtpAsyncCpuShare reflective failure must trip CBIT counter once per session");
            assertTrue(CoreFallbackCbitCounters.lastLifecycleCleanupDetail()
                    .contains("RuntimeSignalSource.sampleRtpAsyncCpuShare (UnsupportedOperationException: os bean boom)"));
            signalSource.resetDegradedLatches();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        } finally {
            RTPRuntime.setServerAccessor(previousAccessor);
        }
    }

    @SuppressWarnings("unused")
    private double throwingOsBeanMethod() {
        throw new UnsupportedOperationException("os bean boom");
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("could not locate repository root");
        return current;
    }
}
