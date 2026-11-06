package io.github.dailystruggle.bit.config;

import java.util.List;

/**
 * Compile-time typed diagnostic telemetry contract implemented by {@code LeafSleuth} and its
 * cooperative forensic plugins so {@code LeafConfig} can ingest chunk lag hotspots, 4-pillar
 * identity comparisons, and moderator investigation cases with zero runtime reflection
 * ({@code ADR-108}, {@code ADR-115}, {@code ADR-126}).
 *
 * @governedBy ADR-108, ADR-115, ADR-126
 * @trace REQ-CFG-ATLAS-003, REQ-SLEUTH-LAG-001, S-004, S-005
 */
public interface SleuthDiagnosticSnapshotSource
        extends io.github.dailystruggle.rtp.common.bit.config.SleuthDiagnosticSnapshotSource {

    @Override
    default List<ChunkHotspotRecord> diagnosticHotspots() {
        return List.of();
    }

    @Override
    default List<IdentityComparisonRecord> diagnosticIdentityComparisons() {
        return List.of();
    }

    @Override
    default List<ForensicCaseRecord> diagnosticForensicCases() {
        return List.of();
    }
}
