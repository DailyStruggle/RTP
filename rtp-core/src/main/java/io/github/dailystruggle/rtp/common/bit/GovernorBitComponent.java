package io.github.dailystruggle.rtp.common.bit;

import io.github.dailystruggle.bit.BitCode;
import io.github.dailystruggle.bit.BitComponent;
import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.bit.BitHealth;
import io.github.dailystruggle.bit.BitReport;
import io.github.dailystruggle.bit.BitSideEffect;
import io.github.dailystruggle.bit.BitTier;
import io.github.dailystruggle.rtp.common.governor.ComputeBudget;
import io.github.dailystruggle.rtp.common.governor.ComputeGovernor;
import io.github.dailystruggle.rtp.common.governor.ComputeSignals;
import io.github.dailystruggle.rtp.common.governor.GovernorReason;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Continuous Built-In Test (CBIT) component {@code compute-governor} under the {@code GOVERNOR}
 * namespace (ADR-087 Section 6 item 4, ADR-121).
 */
public final class GovernorBitComponent {
    private static final long COMPONENT_TIMEOUT_MILLIS = 5_000L;

    public static final BitCode GOVERNOR_KNOB_REDUCED = RtpBitCodes.GOVERNOR.register(
            1, "GOVERNOR_KNOB_REDUCED", Level.INFO, BitTier.CBIT, "governor",
            "", "Inspect /rtp info and server load; the governor reduced background compute below configured ceilings.");

    public static final BitCode GOVERNOR_KNOB_AT_FLOOR = RtpBitCodes.GOVERNOR.register(
            2, "GOVERNOR_KNOB_AT_FLOOR", Level.WARNING, BitTier.CBIT, "governor",
            "", "A background compute knob has been held at its floor for over 10 minutes; check server overload or metrics health.");

    public static final BitCode GOVERNOR_SIGNAL_STALE = RtpBitCodes.GOVERNOR.register(
            3, "GOVERNOR_SIGNAL_STALE", Level.WARNING, BitTier.CBIT, "governor",
            "", "A previously sampled telemetry signal has been stale for over 60 seconds; governed knobs are held.");

    public static final BitCode GOVERNOR_EVALUATION_FAILED = RtpBitCodes.GOVERNOR.register(
            4, "GOVERNOR_EVALUATION_FAILED", Level.WARNING, BitTier.CBIT, "governor",
            "", "Governor control-law evaluation threw an exception and retained the previous snapshot; inspect server logs.");

    public static final BitComponent COMPUTE_GOVERNOR = new BitComponent(
            "compute-governor", "governor", BitTier.CBIT, BitSideEffect.SAFE,
            COMPONENT_TIMEOUT_MILLIS, GovernorBitComponent::report);

    private GovernorBitComponent() {}

    public static BitReport report() {
        return reportFor(ComputeGovernor.active(), System.nanoTime());
    }

    public static BitReport reportFor(ComputeGovernor governor, long nowNanos) {
        if (governor == null) {
            return BitReport.of(List.of());
        }
        List<BitFinding> findings = new ArrayList<>();
        ComputeBudget b = governor.budget();

        if (governor.evaluationFailures() > 0L) {
            findings.add(BitFinding.of(
                    GOVERNOR_EVALUATION_FAILED,
                    "compute-governor",
                    "governor.evaluation",
                    "ComputeGovernor failed " + governor.evaluationFailures() + " evaluation(s): " + governor.lastFailureMessage(),
                    "0 evaluation failures",
                    GOVERNOR_EVALUATION_FAILED.remediation()
            ));
        }

        if (b != null && (b.autoTune() || b.observeOnly())) {
            if (b.effectiveAsyncNanos() < b.configuredAsyncNanos()) {
                findings.add(reducedFinding("asyncAllottedTime",
                        TimeUnit.NANOSECONDS.toMillis(b.effectiveAsyncNanos()) + "ms",
                        TimeUnit.NANOSECONDS.toMillis(b.configuredAsyncNanos()) + "ms",
                        b.asyncReason()));
            }
            if (b.effectiveSyncNanos() < b.configuredSyncNanos()) {
                findings.add(reducedFinding("syncAllottedTime",
                        TimeUnit.NANOSECONDS.toMillis(b.effectiveSyncNanos()) + "ms",
                        TimeUnit.NANOSECONDS.toMillis(b.configuredSyncNanos()) + "ms",
                        b.syncReason()));
            }
            if (b.effectivePromotionsPerTurn() < b.configuredPromotionsPerTurn()) {
                findings.add(reducedFinding("promotionsPerTurn",
                        String.valueOf(b.effectivePromotionsPerTurn()),
                        String.valueOf(b.configuredPromotionsPerTurn()),
                        b.promotionsReason()));
            }
            if (b.effectiveGenerationCap() < b.configuredGenerationCap()) {
                findings.add(reducedFinding("generationCap",
                        String.valueOf(b.effectiveGenerationCap()),
                        String.valueOf(b.configuredGenerationCap()),
                        b.generationReason()));
            }
            for (Map.Entry<String, Integer> e : b.regionHotCaps().entrySet()) {
                String region = e.getKey();
                int eff = e.getValue();
                int cfg = b.regionConfiguredHotCaps().getOrDefault(region, eff);
                if (eff < cfg) {
                    findings.add(reducedFinding("regions." + region + ".activeChunkCap",
                            String.valueOf(eff),
                            String.valueOf(cfg),
                            b.hotCapReason(region)));
                }
            }

            long floorDuration = governor.maxDurationAtFloorNanos(nowNanos);
            if (floorDuration >= ComputeGovernor.FLOOR_WARNING_NANOS) {
                findings.add(BitFinding.of(
                        GOVERNOR_KNOB_AT_FLOOR,
                        "compute-governor",
                        "governor.floor",
                        "A compute governor knob has been held at its floor for "
                                + TimeUnit.NANOSECONDS.toSeconds(floorDuration) + "s.",
                        "< 600s at floor",
                        GOVERNOR_KNOB_AT_FLOOR.remediation()
                ));
            }
        }

        ComputeSignals signals = governor.lastSignals();
        if (signals != null) {
            checkStale(findings, "mspt", signals.mspt());
            checkStale(findings, "meanTps", signals.meanTps());
            checkStale(findings, "processCpuLoad", signals.processCpuLoad());
            checkStale(findings, "postGcTenuredUsedBytes", signals.postGcTenuredUsedBytes());
            checkStale(findings, "chunkLoadLatencyMs", signals.chunkLoadLatencyMs());
        }

        boolean degraded = findings.stream().anyMatch(f -> f.severity() == Level.WARNING || f.severity() == Level.ERROR);
        return new BitReport(BitReport.CURRENT_SCHEMA, degraded ? BitHealth.DEGRADED : BitHealth.HEALTHY, findings);
    }

    private static BitFinding reducedFinding(String path, String effective, String configured, GovernorReason reason) {
        return BitFinding.of(
                GOVERNOR_KNOB_REDUCED,
                "performance.yml",
                path,
                "Effective " + path + " is " + effective + " (configured " + configured + ", reason: " + reason + ").",
                configured,
                GOVERNOR_KNOB_REDUCED.remediation()
        );
    }

    private static void checkStale(List<BitFinding> findings, String name, ComputeSignals.SampledSignal sig) {
        if (sig != null && sig.isStale() && sig.staleForNanos() >= ComputeGovernor.STALE_WARNING_NANOS) {
            findings.add(BitFinding.of(
                    GOVERNOR_SIGNAL_STALE,
                    "compute-governor",
                    "signals." + name,
                    "Signal '" + name + "' has been stale for "
                            + TimeUnit.NANOSECONDS.toSeconds(sig.staleForNanos()) + "s; governed knobs are held.",
                    "FRESH (< 60s)",
                    GOVERNOR_SIGNAL_STALE.remediation()
            ));
        }
    }
}
