package io.github.dailystruggle.rtp.common.bit;

import io.github.dailystruggle.bit.BitCoverageDiscovery;
import io.github.dailystruggle.bit.BitCoverageProvider;
import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.bit.BitHealth;
import io.github.dailystruggle.bit.BitReport;
import io.github.dailystruggle.bit.BitResult;
import io.github.dailystruggle.bit.BitRunner;
import io.github.dailystruggle.bit.BitSelector;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.BitKeys;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;

/** Schedules the shared SAFE BIT sweep and coverage provider after platform startup (ADR-121, ADR-103). */
public final class RtpBitStartup {
    public static final String DEFAULT_STARTUP_SWEEP = "pbit";
    public static final String DEFAULT_COVERAGE = "auto";

    private final BitRunner runner;
    private final String startupSweep;
    private final BitCoverageProvider coverageProvider;

    public RtpBitStartup() {
        this(RtpBit.RUNNER, configuredStartupSweep(), discoverCoverageProvider());
    }

    public RtpBitStartup(BitRunner runner, @Nullable ConfigParser<BitKeys> bitConfig) {
        this(
                runner,
                readStartupSweep(bitConfig),
                discoverCoverageProvider(readCoverage(bitConfig)));
    }

    public RtpBitStartup(
            BitRunner runner,
            @Nullable String startupSweep,
            @Nullable BitCoverageProvider coverageProvider) {
        this.runner = Objects.requireNonNull(runner, "runner must not be null");
        this.startupSweep = normalizeStartupSweep(startupSweep);
        this.coverageProvider = coverageProvider != null
                ? coverageProvider
                : discoverCoverageProvider(DEFAULT_COVERAGE);
    }

    public BitRunner runner() {
        return runner;
    }

    public String startupSweep() {
        return startupSweep;
    }

    public BitCoverageProvider coverageProvider() {
        return coverageProvider;
    }

    /** Applies the configured coverage provider and executes the configured startup sweep. */
    public List<BitResult> execute() {
        runner.setCoverageProvider(coverageProvider);
        BitSeverityPolicy.reportUnresolvedOverrides();
        if ("off".equals(startupSweep)) {
            return List.of();
        }
        BitSelector selector = "all".equals(startupSweep)
                ? BitSelector.parse("all")
                : BitSelector.parse("tier:pbit");
        List<BitResult> results = runner.run(selector, Runnable::run);
        if (!"all".equals(startupSweep)
                && runner.components().stream()
                        .anyMatch(c -> c.id().equals(CoreFallbackCbitCounters.CORE_FALLBACK_CBIT.id()))) {
            runner.run(CoreFallbackCbitCounters.CORE_FALLBACK_CBIT, Runnable::run);
        }
        BitReport report = runner.aggregate(results);
        RTPRuntime.log(report.health() == BitHealth.HEALTHY ? Level.INFO : Level.WARNING, summary(runner, results));
        return results;
    }

    /** Queues PBIT for tick +2 so the tick +1 database-accessor startup completes first. */
    public static void schedule() {
        RtpBitComponents.registerDefaults();
        RtpBitStartup startup = new RtpBitStartup();
        startup.runner.setCoverageProvider(startup.coverageProvider);
        if ("off".equals(startup.startupSweep)) {
            BitSeverityPolicy.reportUnresolvedOverrides();
            return;
        }
        RTPScheduler scheduler = RTPRuntime.scheduler();
        if (scheduler == null) {
            RTPRuntime.log(Level.WARNING, "[RTP bit] startup PBIT was not scheduled: RTP.scheduler is unavailable.");
            return;
        }
        try {
            scheduler.runTaskLater(() -> scheduler.runTaskAsynchronously(RtpBitStartup::runNow), 2);
        } catch (Throwable failure) {
            RTPRuntime.log(Level.WARNING, "[RTP bit] startup PBIT scheduling failed.", failure);
        }
    }

    public static List<BitResult> runNow() {
        return new RtpBitStartup().execute();
    }

    public static String configuredStartupSweep() {
        return readStartupSweep(activeBitConfig());
    }

    public static String configuredCoverage() {
        return readCoverage(activeBitConfig());
    }

    public static BitCoverageProvider discoverCoverageProvider() {
        return discoverCoverageProvider(configuredCoverage());
    }

    public static BitCoverageProvider discoverCoverageProvider(@Nullable String configuredCoverage) {
        return BitCoverageDiscovery.discover(configuredCoverage, RtpBitStartup::logDiscoveryFinding);
    }

    static String summary(List<BitResult> results) {
        return summary(RtpBit.RUNNER, results);
    }

    static String summary(BitRunner targetRunner, List<BitResult> results) {
        BitReport report = targetRunner.aggregate(results);
        long errors = report.findings().stream()
                .filter(finding -> finding.severity() == java.lang.System.Logger.Level.ERROR).count();
        long warnings = report.findings().stream()
                .filter(finding -> finding.severity() == java.lang.System.Logger.Level.WARNING).count();
        String nonHealthy = results.stream()
                .filter(result -> result.report().health() != BitHealth.HEALTHY)
                .map(result -> result.component().id())
                .sorted()
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        return "[RTP bit] startup PBIT health=" + report.health()
                + " components=" + results.size()
                + " errors=" + errors
                + " warnings=" + warnings
                + " nonHealthy=" + nonHealthy;
    }

    @SuppressWarnings("unchecked")
    static @Nullable ConfigParser<BitKeys> activeBitConfig() {
        try {
            var configs = ActiveConfigs.get();
            if (configs == null) return null;
            return (ConfigParser<BitKeys>) configs.getParser(BitKeys.class);
        } catch (RuntimeException e) {
            CoreFallbackCbitCounters.recordConfigLookupDegraded("RtpBitStartup.activeBitConfig", e);
            return null;
        }
    }

    static String readStartupSweep(@Nullable ConfigParser<BitKeys> parser) {
        if (parser == null) return DEFAULT_STARTUP_SWEEP;
        try {
            Object raw = parser.getConfigValue(BitKeys.startupSweep, DEFAULT_STARTUP_SWEEP);
            return normalizeStartupSweep(raw == null ? null : String.valueOf(raw));
        } catch (RuntimeException e) {
            CoreFallbackCbitCounters.recordConfigLookupDegraded("RtpBitStartup.readStartupSweep", e);
            return DEFAULT_STARTUP_SWEEP;
        }
    }

    static String readCoverage(@Nullable ConfigParser<BitKeys> parser) {
        if (parser == null) return DEFAULT_COVERAGE;
        try {
            Object raw = parser.getConfigValue(BitKeys.coverage, DEFAULT_COVERAGE);
            return normalizeCoverage(raw == null ? null : String.valueOf(raw));
        } catch (RuntimeException e) {
            CoreFallbackCbitCounters.recordConfigLookupDegraded("RtpBitStartup.readCoverage", e);
            return DEFAULT_COVERAGE;
        }
    }

    static String normalizeStartupSweep(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_STARTUP_SWEEP;
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if ("false".equals(normalized) || "none".equals(normalized) || "off".equals(normalized)) {
            return "off";
        }
        if ("all".equals(normalized)) {
            return "all";
        }
        return "pbit";
    }

    static String normalizeCoverage(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_COVERAGE;
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if ("false".equals(normalized) || "none".equals(normalized)) {
            return "off";
        }
        return normalized;
    }

    private static void logDiscoveryFinding(BitFinding finding) {
        RTPRuntime.log(
                Level.WARNING,
                "[RTP bit] " + finding.code() + " path=" + finding.path() + " message=" + finding.message());
    }
}
