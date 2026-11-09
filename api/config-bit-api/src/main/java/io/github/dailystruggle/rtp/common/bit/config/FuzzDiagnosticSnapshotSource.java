package io.github.dailystruggle.rtp.common.bit.config;

import java.util.List;
import java.util.Objects;

/**
 * Compile-time typed diagnostic telemetry contract implemented by {@code LeafFuzz} and its
 * cooperative quality-control plugins so {@code LeafConfig} can ingest 5-stage QC scorecards,
 * bytecode config/command wiring findings, and runtime hazard reproducers with zero runtime
 * reflection ({@code ADR-108}, {@code ADR-125}, {@code leaffuzz-ADR-001}, {@code leafconfig-ADR-001}).
 *
 * @governedBy ADR-108
 * @governedBy ADR-115
 * @governedBy ADR-125
 * @governedBy ADR-126
 * @governedBy leaffuzz-ADR-001
 * @governedBy leafconfig-ADR-001
 * @trace REQ-CFG-ATLAS-003
 * @trace REQ-SLEUTH-FUZZ-004
 * @trace S-004
 * @trace S-005
 */
public interface FuzzDiagnosticSnapshotSource {

    /**
     * Typed summary of a 5-stage {@code LeafFuzz} quality-control scorecard.
     *
     * @param targetName          tested plugin/mod name or {@code "all"}
     * @param platformName        server platform identifier ({@code "paper"}, {@code "folia"}, {@code "fabric"}, {@code "neoforge"})
     * @param serverVersion       server version string
     * @param overallVerdict      overall verdict ({@code "PASS"}, {@code "WARN"}, {@code "FAIL"})
     * @param stage1Verdict       Stage 1 (Command &amp; Permission Surface) verdict
     * @param stage2Verdict       Stage 2 (Boundary &amp; Lifecycle Fuzzing) verdict
     * @param stage3Verdict       Stage 3 (Main-Thread Stall &amp; Exception Oracle) verdict
     * @param stage4Verdict       Stage 4 (Post-GC Memory &amp; Session Leak Screen) verdict
     * @param stage5Verdict       Stage 5 (Bytecode Causal Config/Command Wiring Audit) verdict
     * @param indexedJars         number of plugin/mod JARs indexed
     * @param auditedCommands     number of distinct command nodes audited
     * @param defaultTierCommands number of command nodes executable by {@code default} players
     * @param episodesExecuted    number of synthetic fuzz episodes executed
     * @param distinctArmsProbed  number of distinct arms exercised
     */
    record FuzzScorecardSummaryRecord(
            String targetName,
            String platformName,
            String serverVersion,
            String overallVerdict,
            String stage1Verdict,
            String stage2Verdict,
            String stage3Verdict,
            String stage4Verdict,
            String stage5Verdict,
            int indexedJars,
            int auditedCommands,
            int defaultTierCommands,
            int episodesExecuted,
            int distinctArmsProbed
    ) {
        public FuzzScorecardSummaryRecord {
            targetName = Objects.requireNonNullElse(targetName, "all");
            platformName = Objects.requireNonNullElse(platformName, "paper");
            serverVersion = Objects.requireNonNullElse(serverVersion, "unknown");
            overallVerdict = Objects.requireNonNullElse(overallVerdict, "PASS");
            stage1Verdict = Objects.requireNonNullElse(stage1Verdict, "PASS");
            stage2Verdict = Objects.requireNonNullElse(stage2Verdict, "PASS");
            stage3Verdict = Objects.requireNonNullElse(stage3Verdict, "PASS");
            stage4Verdict = Objects.requireNonNullElse(stage4Verdict, "PASS");
            stage5Verdict = Objects.requireNonNullElse(stage5Verdict, "PASS");
            indexedJars = Math.max(0, indexedJars);
            auditedCommands = Math.max(0, auditedCommands);
            defaultTierCommands = Math.max(0, defaultTierCommands);
            episodesExecuted = Math.max(0, episodesExecuted);
            distinctArmsProbed = Math.max(0, distinctArmsProbed);
        }
    }

    /**
     * Typed summary of a bytecode causal config or command wiring defect discovered by {@code LeafFuzz}.
     *
     * @param artifactId      canonical artifact identifier (e.g., {@code "plugin:myplugin"})
     * @param displayName     human-readable plugin/mod name
     * @param kind            causal finding kind (e.g., {@code "CAUSAL_DEAD_CONFIG_KEY"}, {@code "CAUSAL_UNWIRED_COMMAND"})
     * @param confidenceTier  confidence tier ({@code "PROVEN"}, {@code "CORROBORATED"}, {@code "HEURISTIC"})
     * @param configFilePath  workspace-relative configuration file path (e.g., {@code "plugins/MyPlugin/config.yml"})
     * @param yamlKeyPath     dot-separated YAML key path for 1-click editor jump
     * @param declaredSurface declared command, listener, or config surface identifier
     * @param summary         concise operator remediation summary
     */
    record FuzzWiringFindingRecord(
            String artifactId,
            String displayName,
            String kind,
            String confidenceTier,
            String configFilePath,
            String yamlKeyPath,
            String declaredSurface,
            String summary
    ) {
        public FuzzWiringFindingRecord {
            artifactId = Objects.requireNonNullElse(artifactId, "plugin:unknown");
            displayName = Objects.requireNonNullElse(displayName, "");
            kind = Objects.requireNonNullElse(kind, "CAUSAL_DEAD_CONFIG_KEY");
            confidenceTier = Objects.requireNonNullElse(confidenceTier, "PROVEN");
            configFilePath = Objects.requireNonNullElse(configFilePath, "");
            yamlKeyPath = Objects.requireNonNullElse(yamlKeyPath, "");
            declaredSurface = Objects.requireNonNullElse(declaredSurface, "");
            summary = Objects.requireNonNullElse(summary, "");
        }
    }

    /**
     * Typed summary of a runtime hazard, unguarded permission surface, {@code ddmin} shrunk
     * reproducer, or post-GC class retention slope discovered by {@code LeafFuzz}.
     *
     * @param targetName        plugin/mod name
     * @param signatureId       canonical hazard/fault signature identifier (e.g., {@code "hazard:sync-chunk:..."})
     * @param category          hazard category ({@code "LEAST_PRIVILEGE"}, {@code "BOUNDARY_REPRODUCER"}, {@code "MAIN_THREAD_HAZARD"}, {@code "MEMORY_RETENTION"})
     * @param severity          severity level ({@code "WARN"}, {@code "FAIL"})
     * @param reproducerCommand minimal reproducer command or trigger description
     * @param summary           concise operator explanation and mitigation hint
     * @param configFilePath    optional associated configuration file path
     * @param yamlKeyPath       optional associated YAML key path
     */
    record FuzzRuntimeHazardRecord(
            String targetName,
            String signatureId,
            String category,
            String severity,
            String reproducerCommand,
            String summary,
            String configFilePath,
            String yamlKeyPath
    ) {
        public FuzzRuntimeHazardRecord {
            targetName = Objects.requireNonNullElse(targetName, "all");
            signatureId = Objects.requireNonNullElse(signatureId, "");
            category = Objects.requireNonNullElse(category, "MAIN_THREAD_HAZARD");
            severity = Objects.requireNonNullElse(severity, "WARN");
            reproducerCommand = Objects.requireNonNullElse(reproducerCommand, "");
            summary = Objects.requireNonNullElse(summary, "");
            configFilePath = Objects.requireNonNullElse(configFilePath, "");
            yamlKeyPath = Objects.requireNonNullElse(yamlKeyPath, "");
        }
    }

    /**
     * Returns the current 5-stage QC scorecard summaries.
     *
     * @return immutable list of scorecard summaries
     */
    default List<FuzzScorecardSummaryRecord> diagnosticFuzzScorecards() {
        return List.of();
    }

    /**
     * Returns the current bytecode causal config and command wiring findings.
     *
     * @return immutable list of wiring findings
     */
    default List<FuzzWiringFindingRecord> diagnosticFuzzWiringFindings() {
        return List.of();
    }

    /**
     * Returns the current runtime hazards, shrunk reproducers, and retention findings.
     *
     * @return immutable list of runtime hazards
     */
    default List<FuzzRuntimeHazardRecord> diagnosticFuzzRuntimeHazards() {
        return List.of();
    }
}
