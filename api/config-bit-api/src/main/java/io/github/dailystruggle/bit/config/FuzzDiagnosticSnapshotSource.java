package io.github.dailystruggle.bit.config;

import java.util.List;

/**
 * Compile-time typed diagnostic telemetry contract implemented by {@code LeafFuzz} and its
 * cooperative quality-control plugins so {@code LeafConfig} can ingest 5-stage QC scorecards,
 * bytecode config/command wiring findings, and runtime hazard reproducers with zero runtime
 * reflection ({@code ADR-108}, {@code ADR-115}, {@code ADR-125}, {@code ADR-126}).
 *
 * @governedBy ADR-108, ADR-115, ADR-125, ADR-126, leaffuzz-ADR-001, leafconfig-ADR-001
 * @trace REQ-CFG-ATLAS-003, REQ-SLEUTH-FUZZ-004, S-004, S-005
 */
public interface FuzzDiagnosticSnapshotSource
        extends io.github.dailystruggle.rtp.common.bit.config.FuzzDiagnosticSnapshotSource {

    @Override
    default List<FuzzScorecardSummaryRecord> diagnosticFuzzScorecards() {
        return List.of();
    }

    @Override
    default List<FuzzWiringFindingRecord> diagnosticFuzzWiringFindings() {
        return List.of();
    }

    @Override
    default List<FuzzRuntimeHazardRecord> diagnosticFuzzRuntimeHazards() {
        return List.of();
    }
}
