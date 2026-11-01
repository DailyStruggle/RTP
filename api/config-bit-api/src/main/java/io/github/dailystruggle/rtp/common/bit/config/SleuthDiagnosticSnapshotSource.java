package io.github.dailystruggle.rtp.common.bit.config;

import java.util.List;
import java.util.Objects;

/**
 * Compile-time typed diagnostic telemetry contract implemented by {@code LeafSleuth} and its
 * cooperative forensic plugins so {@code LeafConfig} can ingest chunk lag hotspots, 4-pillar
 * identity comparisons, and moderator investigation cases with zero runtime reflection
 * ({@code leafrtp-ADR-088}, {@code leafsleuth-ADR-003}).
 *
 * @governedBy ADR-108
 * @governedBy ADR-115
 * @governedBy ADR-126
 * @trace REQ-CFG-ATLAS-003
 * @trace REQ-SLEUTH-LAG-001
 * @trace S-004
 * @trace S-005
 */
public interface SleuthDiagnosticSnapshotSource {

    /**
     * Typed summary of an attributed chunk lag hotspot.
     *
     * @param worldName        world name
     * @param chunkX           chunk X coordinate
     * @param chunkZ           chunk Z coordinate
     * @param estimateMspt     estimated marginal MSPT contribution
     * @param halfWidth95Mspt  95% confidence interval half-width in MSPT
     * @param dominantPattern  dominant lag pattern identifier
     * @param clockPeriodTicks detected redstone clock period in ticks ({@code <= 0} if aperiodic)
     */
    record ChunkHotspotRecord(
            String worldName,
            int chunkX,
            int chunkZ,
            double estimateMspt,
            double halfWidth95Mspt,
            String dominantPattern,
            int clockPeriodTicks
    ) {
        public ChunkHotspotRecord {
            worldName = Objects.requireNonNullElse(worldName, "world");
            dominantPattern = Objects.requireNonNullElse(dominantPattern, "STEADY_LOAD");
        }
    }

    /**
     * Typed summary of a single behavioral or stylometric tell supporting or refuting an identity match.
     *
     * @param pillar             evidence pillar name
     * @param logLikelihoodRatio signed log-likelihood ratio in nats
     * @param headline           human-readable tell headline
     * @param metricComparison   formatted metric comparison string
     */
    record IdentityTellRecord(
            String pillar,
            double logLikelihoodRatio,
            String headline,
            String metricComparison
    ) {
        public IdentityTellRecord {
            pillar = Objects.requireNonNullElse(pillar, "BEHAVIOR");
            headline = Objects.requireNonNullElse(headline, "");
            metricComparison = Objects.requireNonNullElse(metricComparison, "");
        }
    }

    /**
     * Typed summary of a 4-pillar Bayesian identity comparison between two player accounts.
     *
     * @param nameA                   first account name
     * @param nameB                   second account name
     * @param sharedIpObserved        whether a shared IP hash was observed
     * @param estimateProbability     posterior same-person probability in {@code [0.0, 1.0]}
     * @param lowerBound95Probability lower 95% credible bound in {@code [0.0, 1.0]}
     * @param upperBound95Probability upper 95% credible bound in {@code [0.0, 1.0]}
     * @param totalLogBayesFactor     combined log Bayes factor in nats
     * @param verdict                 categorical verdict name
     * @param topTells                ranked supporting and exonerating tells
     */
    record IdentityComparisonRecord(
            String nameA,
            String nameB,
            boolean sharedIpObserved,
            double estimateProbability,
            double lowerBound95Probability,
            double upperBound95Probability,
            double totalLogBayesFactor,
            String verdict,
            List<IdentityTellRecord> topTells
    ) {
        public IdentityComparisonRecord {
            nameA = Objects.requireNonNullElse(nameA, "");
            nameB = Objects.requireNonNullElse(nameB, "");
            verdict = Objects.requireNonNullElse(verdict, "INCONCLUSIVE");
            topTells = topTells == null ? List.of() : List.copyOf(topTells);
        }
    }

    /**
     * Typed summary of a unified moderator investigation case.
     *
     * @param playerName            suspect player name
     * @param caseType              case type identifier
     * @param confidenceTier        confidence tier identifier
     * @param worldName             world name
     * @param blockX                block X coordinate
     * @param blockY                block Y coordinate
     * @param blockZ                block Z coordinate
     * @param estimateSuspicion     estimated suspicion score in {@code [0.0, 1.0]}
     * @param lowerBound95Suspicion lower 95% confidence bound in {@code [0.0, 1.0]}
     * @param upperBound95Suspicion upper 95% confidence bound in {@code [0.0, 1.0]}
     * @param operatorSummary       concise operator summary
     * @param supportingTells       corroborating forensic evidence lines
     * @param exoneratingTells      innocent-baseline or exonerating evidence lines
     */
    record ForensicCaseRecord(
            String playerName,
            String caseType,
            String confidenceTier,
            String worldName,
            int blockX,
            int blockY,
            int blockZ,
            double estimateSuspicion,
            double lowerBound95Suspicion,
            double upperBound95Suspicion,
            String operatorSummary,
            List<String> supportingTells,
            List<String> exoneratingTells
    ) {
        public ForensicCaseRecord {
            playerName = Objects.requireNonNullElse(playerName, "");
            caseType = Objects.requireNonNullElse(caseType, "");
            confidenceTier = Objects.requireNonNullElse(confidenceTier, "");
            worldName = Objects.requireNonNullElse(worldName, "world");
            operatorSummary = Objects.requireNonNullElse(operatorSummary, "");
            supportingTells = supportingTells == null ? List.of() : List.copyOf(supportingTells);
            exoneratingTells = exoneratingTells == null ? List.of() : List.copyOf(exoneratingTells);
        }
    }

    /**
     * Returns the current ranked chunk lag hotspots.
     *
     * @return immutable list of chunk hotspots
     */
    default List<ChunkHotspotRecord> diagnosticHotspots() {
        return List.of();
    }

    /**
     * Returns the current ranked 4-pillar identity comparisons.
     *
     * @return immutable list of identity comparisons
     */
    default List<IdentityComparisonRecord> diagnosticIdentityComparisons() {
        return List.of();
    }

    /**
     * Returns the current active moderator investigation cases.
     *
     * @return immutable list of forensic cases
     */
    default List<ForensicCaseRecord> diagnosticForensicCases() {
        return List.of();
    }
}
