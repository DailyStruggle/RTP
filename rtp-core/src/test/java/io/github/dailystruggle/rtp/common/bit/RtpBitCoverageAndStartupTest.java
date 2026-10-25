package io.github.dailystruggle.rtp.common.bit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.dailystruggle.bit.BitCodes;
import io.github.dailystruggle.bit.BitComponent;
import io.github.dailystruggle.bit.BitCoverageDiscovery;
import io.github.dailystruggle.bit.BitHealth;
import io.github.dailystruggle.bit.BitReport;
import io.github.dailystruggle.bit.BitResult;
import io.github.dailystruggle.bit.BitRunner;
import io.github.dailystruggle.bit.BitSideEffect;
import io.github.dailystruggle.bit.BitTier;
import io.github.dailystruggle.bit.JacocoCoverageProvider;
import io.github.dailystruggle.bit.SurfaceCoverageProvider;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.action.ActionCbitCounters;
import io.github.dailystruggle.rtp.common.commands.test.TestApiCompatCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestStatusCmd;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.BitKeys;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("REQ-API-F-009 BIT live coverage DI, startupSweep configuration, CBIT observations, and API probe ratio")
class RtpBitCoverageAndStartupTest {
    @TempDir
    File tempDir;

    private MockRTPServerAccessor accessor;

    @BeforeEach
    void setUp() {
        BitCoverageDiscovery.clearFindings();
        PipelineCbitCounters.reset();
        ActionCbitCounters.reset();
        CoreFallbackCbitCounters.reset();
        PlatformHookBitComponent.reset();
        accessor = RTPTestSetup.install(tempDir);
    }

    @AfterEach
    void tearDown() {
        BitCoverageDiscovery.clearFindings();
        PipelineCbitCounters.reset();
        ActionCbitCounters.reset();
        CoreFallbackCbitCounters.reset();
        PlatformHookBitComponent.reset();
        RtpBit.configureCoverage("auto");
        RTPTestSetup.cleanUp();
    }

    @Test
    @SuppressWarnings("unchecked")
    void startupConfiguresCoverageAndHonorsStartupSweepModes() {
        BitRunner isolatedRunner = new BitRunner(BitCoverageDiscovery.off());
        BitComponent pbitComponent = new BitComponent(
                "startup-pbit", "config", BitTier.PBIT, BitSideEffect.SAFE, 1_000, () -> BitReport.of(List.of()));
        BitComponent cbitComponent = new BitComponent(
                "startup-cbit", "pipeline", BitTier.CBIT, BitSideEffect.SAFE, 1_000, () -> BitReport.of(List.of()));
        BitComponent ibitSafeComponent = new BitComponent(
                "startup-ibit-safe", "test", BitTier.IBIT, BitSideEffect.SAFE, 1_000, () -> BitReport.of(List.of()));
        BitComponent ibitHeavyComponent = new BitComponent(
                "startup-ibit-heavy", "test", BitTier.IBIT, BitSideEffect.HEAVY, 1_000, () -> BitReport.of(List.of()));
        isolatedRunner.register(pbitComponent);
        isolatedRunner.register(cbitComponent);
        isolatedRunner.register(ibitSafeComponent);
        isolatedRunner.register(ibitHeavyComponent);

        ConfigParser<BitKeys> offConfig = mock(ConfigParser.class);
        when(offConfig.getConfigValue(BitKeys.startupSweep, RtpBitStartup.DEFAULT_STARTUP_SWEEP)).thenReturn("off");
        when(offConfig.getConfigValue(BitKeys.coverage, RtpBitStartup.DEFAULT_COVERAGE)).thenReturn("surface");
        RtpBitStartup offStartup = new RtpBitStartup(isolatedRunner, offConfig);
        assertEquals("off", offStartup.startupSweep());
        assertEquals("surface", offStartup.coverageProvider().id());
        assertTrue(offStartup.execute().isEmpty());
        assertEquals(0, isolatedRunner.coverageSnapshot().executedComponents());

        ConfigParser<BitKeys> pbitConfig = mock(ConfigParser.class);
        when(pbitConfig.getConfigValue(BitKeys.startupSweep, RtpBitStartup.DEFAULT_STARTUP_SWEEP)).thenReturn("pbit");
        when(pbitConfig.getConfigValue(BitKeys.coverage, RtpBitStartup.DEFAULT_COVERAGE)).thenReturn("surface");
        RtpBitStartup pbitStartup = new RtpBitStartup(isolatedRunner, pbitConfig);
        List<BitResult> pbitResults = pbitStartup.execute();
        assertEquals(1, pbitResults.size());
        assertEquals("startup-pbit", pbitResults.get(0).component().id());
        assertEquals(1, isolatedRunner.coverageSnapshot().executedComponents());

        ConfigParser<BitKeys> allConfig = mock(ConfigParser.class);
        when(allConfig.getConfigValue(BitKeys.startupSweep, RtpBitStartup.DEFAULT_STARTUP_SWEEP)).thenReturn("all");
        when(allConfig.getConfigValue(BitKeys.coverage, RtpBitStartup.DEFAULT_COVERAGE)).thenReturn("surface");
        RtpBitStartup allStartup = new RtpBitStartup(isolatedRunner, allConfig);
        List<BitResult> allResults = allStartup.execute();
        assertEquals(3, allResults.size());
        assertEquals(3, isolatedRunner.coverageSnapshot().executedComponents());
        assertEquals(4, isolatedRunner.coverageSnapshot().registeredComponents());
    }

    @Test
    void testStatusCmdConstructorDiEmitsSurfaceAndJacocoCoverageSummary() throws IOException {
        byte[] sampleExec = singleClassExecStream("io/github/dailystruggle/rtp/common/RTP", new boolean[] {true, true, false, true});
        JacocoCoverageProvider jacocoProvider = new JacocoCoverageProvider(() -> sampleExec);
        BitRunner isolatedRunner = new BitRunner(jacocoProvider);

        BitComponent pbit = new BitComponent(
                "status-pbit", "config", BitTier.PBIT, BitSideEffect.SAFE, 1_000, () -> BitReport.of(List.of()));
        BitComponent observedCbit = new BitComponent(
                "status-cbit-observed", "pipeline", BitTier.CBIT, BitSideEffect.SAFE, 1_000, () -> BitReport.of(List.of()));
        BitComponent unobservedCbit = new BitComponent(
                "status-cbit-unobserved",
                "action",
                BitTier.CBIT,
                BitSideEffect.SAFE,
                1_000,
                () -> BitReport.of(List.of(io.github.dailystruggle.bit.BitFinding.of(
                        BitCodes.CBIT_UNOBSERVED,
                        "status-cbit-unobserved",
                        "action",
                        "No observations yet.",
                        "at least 1 observation",
                        BitCodes.CBIT_UNOBSERVED.remediation()))));
        isolatedRunner.register(pbit);
        isolatedRunner.register(observedCbit);
        isolatedRunner.register(unobservedCbit);
        isolatedRunner.run(pbit, Runnable::run);

        TestStatusCmd statusCmd = new TestStatusCmd(isolatedRunner);
        assertSame(isolatedRunner, statusCmd.runner());
        accessor.logMessages.clear();
        statusCmd.onCommand(RTPAPI.serverId, Map.of(), null);

        assertTrue(
                accessor.logMessages.stream().anyMatch(line ->
                        line.contains("coverage=jacoco")
                                && line.contains("components=3/3")
                                && line.contains("cbit=1/2 (observed=1,unobserved=1)")
                                && line.contains("probes=3/4 (75.0%)")),
                accessor.logMessages.toString());

        accessor.logMessages.clear();
        statusCmd.onCommand(RTPAPI.serverId, Map.of("json", List.of("true")), null);
        assertTrue(
                accessor.logMessages.stream().anyMatch(line ->
                        line.contains("[RTP bit/test.coverage]")
                                && line.contains("\"providerId\":\"jacoco\"")
                                && line.contains("\"hitProbes\":3")
                                && line.contains("\"totalProbes\":4")),
                accessor.logMessages.toString());
    }

    @Test
    void pipelineAndActionCbitCountersTrackObservationsAndClearUnobservedFinding() {
        PipelineCbitCounters.reset();
        assertFalse(PipelineCbitCounters.isObserved());
        assertEquals(0L, PipelineCbitCounters.observations());
        BitReport unobservedPipeline = PipelineCbitCounters.report();
        assertEquals(BitHealth.HEALTHY, unobservedPipeline.health());
        assertTrue(unobservedPipeline.findings().stream()
                .anyMatch(f -> BitCodes.CBIT_UNOBSERVED.key().equals(f.code())));

        PipelineCbitCounters.recordCandidateEvaluated();
        PipelineCbitCounters.recordCandidateEvaluated(3L);
        assertTrue(PipelineCbitCounters.isObserved());
        assertEquals(4L, PipelineCbitCounters.candidateEvaluations());
        assertEquals(4L, PipelineCbitCounters.observations());
        BitReport observedPipeline = PipelineCbitCounters.report();
        assertTrue(observedPipeline.findings().isEmpty());

        ActionCbitCounters.reset();
        assertFalse(ActionCbitCounters.isObserved());
        assertEquals(0L, ActionCbitCounters.observations());
        BitReport unobservedAction = ActionCbitCounters.report();
        assertEquals(BitHealth.HEALTHY, unobservedAction.health());
        assertTrue(unobservedAction.findings().stream()
                .anyMatch(f -> BitCodes.CBIT_UNOBSERVED.key().equals(f.code())));

        ActionCbitCounters.recordUnresolvedPlaceholders("say clean command without unresolved tokens");
        ActionCbitCounters.observe(2L);
        assertTrue(ActionCbitCounters.isObserved());
        assertEquals(3L, ActionCbitCounters.observations());
        BitReport observedAction = ActionCbitCounters.report();
        assertTrue(observedAction.findings().isEmpty());
    }

    @Test
    void testApiCompatProbeReportComputesResolvedToApplicableCoverageRatio() {
        List<TestApiCompatCmd.ApiProbe> probes = List.of(
                new TestApiCompatCmd.ApiProbe("java.lang.String", "length", List.of(), "ok-1"),
                new TestApiCompatCmd.ApiProbe("java.lang.String", "isEmpty", List.of(), "ok-2"),
                new TestApiCompatCmd.ApiProbe("java.lang.String", "missingRequired", List.of(), "missing-req", false),
                new TestApiCompatCmd.ApiProbe("com.example.NotOnClasspath", "skipMe", List.of(), "skipped-probe"));

        TestApiCompatCmd.ProbeReport report = TestApiCompatCmd.runProbes(probes);
        assertEquals(2, report.resolved());
        assertEquals(3, report.applicable());
        assertEquals(2.0 / 3.0, report.coverageRatio(), 1e-6);
        assertTrue(report.summary().contains("resolved=2/3"), report.summary());
    }

    @Test
    void advancedBitLocaleParityAndBundledDirectivesHaveZeroGaps() throws IOException {
        java.nio.file.Path current = java.nio.file.Path.of("").toAbsolutePath();
        while (current != null && !java.nio.file.Files.isDirectory(current.resolve("rtp-plugin/src/main/resources"))) {
            current = current.getParent();
        }
        assertTrue(current != null, "repository root must be resolvable");
        java.nio.file.Path resources = current.resolve("rtp-plugin/src/main/resources");
        var reader = io.github.dailystruggle.rtp.common.bit.config.MessageKeyParity.ResourceReader.directory(resources);
        var categories = io.github.dailystruggle.rtp.common.bit.config.MessageKeyParity.categories(reader);
        assertTrue(categories.contains("advanced/bit"), "advanced/bit must be discovered as a baseline category");
        var gaps = io.github.dailystruggle.rtp.common.bit.config.MessageKeyParity.gaps(reader, categories);
        assertTrue(gaps.isEmpty(), () -> "Expected zero locale parity gaps, found: " + gaps);

        String bundledBitYaml = java.nio.file.Files.readString(resources.resolve("advanced/bit.yml"));
        var snapshot = io.github.dailystruggle.rtp.common.bit.config.ConfigSnapshot.of(
                Map.of("advanced/bit.yml", bundledBitYaml));
        var findings = io.github.dailystruggle.rtp.common.bit.config.DirectiveValidator.validate(
                snapshot.document("advanced/bit.yml").orElseThrow(),
                bundledBitYaml,
                io.github.dailystruggle.rtp.common.bit.config.DirectiveConfigCheck.CODES);
        assertTrue(findings.isEmpty(), () -> "Expected zero directive findings on advanced/bit.yml, found: " + findings);
    }

    @Test
    @SuppressWarnings("unchecked")
    void startupConfigLookupExceptionsRecordDegradationAndSurfaceCoverageObservesFallbackComponents() throws Exception {
        CoreFallbackCbitCounters.reset();
        PlatformHookBitComponent.reset();

        // Pre-init null ActiveConfigs must not record degradation
        var previousConfigs = io.github.dailystruggle.rtp.common.configuration.ActiveConfigs.get();
        try {
            io.github.dailystruggle.rtp.common.configuration.ActiveConfigs.set(null);
            assertEquals(RtpBitStartup.DEFAULT_STARTUP_SWEEP, RtpBitStartup.configuredStartupSweep());
            assertEquals(RtpBitStartup.DEFAULT_COVERAGE, RtpBitStartup.configuredCoverage());
            assertEquals(0L, CoreFallbackCbitCounters.configLookupCount());
        } finally {
            io.github.dailystruggle.rtp.common.configuration.ActiveConfigs.set(previousConfigs);
        }

        // Unexpected RuntimeExceptions in readStartupSweep and readCoverage must record degradation
        ConfigParser<BitKeys> throwingParser = mock(ConfigParser.class);
        when(throwingParser.getConfigValue(BitKeys.startupSweep, RtpBitStartup.DEFAULT_STARTUP_SWEEP))
                .thenThrow(new IllegalStateException("sweep lookup boom"));
        when(throwingParser.getConfigValue(BitKeys.coverage, RtpBitStartup.DEFAULT_COVERAGE))
                .thenThrow(new IllegalStateException("coverage lookup boom"));

        RtpBitStartup degradedStartup = new RtpBitStartup(new BitRunner(SurfaceCoverageProvider.INSTANCE), throwingParser);
        assertEquals(RtpBitStartup.DEFAULT_STARTUP_SWEEP, degradedStartup.startupSweep());
        assertEquals(2L, CoreFallbackCbitCounters.configLookupCount());
        assertTrue(CoreFallbackCbitCounters.lastConfigLookupDetail()
                .contains("RtpBitStartup.readCoverage (IllegalStateException: coverage lookup boom)"));

        // Verify RtpBitStartup executes and SurfaceCoverageProvider observes CoreFallbackCbitCounters (1811..1818)
        // and PlatformHookBitComponent (4021..4032, including runtime-tripped 4025..4032)
        CoreFallbackCbitCounters.reset();
        PlatformHookBitComponent.reset();
        PlatformHookBitComponent.recordSamplerDegraded("EntitySchedulerSampler#cancelAndClear", "IllegalStateException: boom");

        BitRunner startupRunner = new BitRunner(SurfaceCoverageProvider.INSTANCE);
        startupRunner.register(CoreFallbackCbitCounters.CORE_FALLBACK_CBIT);
        startupRunner.register(PlatformHookBitComponent.PLATFORM_HOOKS);

        RtpBitStartup pbitStartup = new RtpBitStartup(startupRunner, "pbit", SurfaceCoverageProvider.INSTANCE);
        List<BitResult> pbitResults = pbitStartup.execute();
        assertEquals(1, pbitResults.size());
        assertEquals(PlatformHookBitComponent.PLATFORM_HOOKS.id(), pbitResults.get(0).component().id());
        assertTrue(pbitResults.get(0).report().findings().stream()
                .anyMatch(f -> f.code().equals(PlatformHookBitComponent.SAMPLER_DEGRADED.key())));

        assertTrue(CoreFallbackCbitCounters.isObserved(), "CoreFallbackCbitCounters must be observed after RtpBitStartup");
        assertTrue(PlatformHookBitComponent.isObserved(), "PlatformHookBitComponent must be observed after RtpBitStartup");

        var cov = startupRunner.coverageSnapshot();
        assertEquals(2, cov.executedComponents());
        assertEquals(2, cov.registeredComponents());
        assertEquals(2, cov.observedCbitComponents());
        assertEquals(2, cov.registeredCbitComponents());

        // Verify lockfile & BIT_CODES.md parity across BIT, CFG, RTP, and TEST namespaces
        var registry = RtpBitCodes.initialiseOwners();
        java.nio.file.Path root = java.nio.file.Path.of("").toAbsolutePath();
        while (root != null && !java.nio.file.Files.isRegularFile(root.resolve("settings.gradle"))) {
            root = root.getParent();
        }
        assertTrue(root != null);
        String lockContent = java.nio.file.Files.readString(root.resolve("docs/dev/bit-codes.lock"));
        String mdContent = java.nio.file.Files.readString(root.resolve("docs/dev/BIT_CODES.md"));
        var lockEntries = RtpBitCodeLock.parse(lockContent);
        for (String ns : List.of("BIT", "RTP")) {
            assertTrue(RtpBitCodeLock.violations(registry, lockEntries, ns).isEmpty());
        }
        var coreLockEntries = lockEntries.stream()
                .filter(e -> registry.byId(e.namespace(), e.id()).isPresent())
                .toList();
        assertTrue(RtpBitCodeLock.violations(registry, coreLockEntries).isEmpty());
        Set<String> productionNamespaces = Set.of("BIT", "CFG", "RTP", "TEST");
        for (var code : registry.codes()) {
            if (!productionNamespaces.contains(code.namespace())) continue;
            assertTrue(lockEntries.stream()
                    .anyMatch(e -> e.namespace().equals(code.namespace())
                            && e.id() == code.id()
                            && e.key().equals(code.key())
                            && e.severity() == code.defaultSeverity()),
                    "docs/dev/bit-codes.lock missing or mismatched row for " + code.namespace() + ":" + code.id() + " " + code.key());
            assertTrue(mdContent.contains("| " + code.id() + " | `" + code.key() + "` |"),
                    "docs/dev/BIT_CODES.md missing row for " + code.namespace() + ":" + code.id() + " " + code.key());
        }
        assertEquals("CFG", io.github.dailystruggle.rtp.common.bit.config.DirectiveCodes.CFG.prefix());
        assertEquals(7, io.github.dailystruggle.rtp.common.bit.config.DirectiveCodes.class.getRecordComponents().length);
        assertEquals(7, io.github.dailystruggle.rtp.common.bit.config.MessageParityCodes.class.getRecordComponents().length);
        assertEquals(2, io.github.dailystruggle.rtp.common.bit.config.ReferenceSettingCheck.Codes.class.getRecordComponents().length);
    }

    private static byte[] singleClassExecStream(String className, boolean[] probes) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(raw)) {
            out.writeByte(JacocoCoverageProvider.BLOCK_HEADER);
            out.writeChar(JacocoCoverageProvider.MAGIC_NUMBER);
            out.writeChar(0x1007);
            out.writeByte(JacocoCoverageProvider.BLOCK_SESSIONINFO);
            out.writeUTF("status-test");
            out.writeLong(1L);
            out.writeLong(2L);
            out.writeByte(JacocoCoverageProvider.BLOCK_EXECUTIONDATA);
            out.writeLong(42L);
            out.writeUTF(className);
            out.writeByte(probes.length);
            int mask = 0;
            for (int i = 0; i < probes.length; i++) {
                if (probes[i]) mask |= (1 << i);
            }
            out.writeByte(mask);
        }
        return raw.toByteArray();
    }
}
