package net.leaf.sleuth.common.resource;

import java.util.Locale;
import java.util.Objects;

/**
 * Post-GC per-class object retention finding produced by {@link ClassRetentionScreener}
 * ({@code ADR-125}, {@code leafsleuth-ADR-006}).
 *
 * @param className         normalized fully qualified Java class name
 * @param owningJar         unique owning plugin {@code .jar} name, {@code "shared"}, or {@code "server/jdk"}
 * @param slopePerEvent     OLS linear regression slope in instances per event ({@code > 0})
 * @param rSquared          coefficient of determination {@code R^2} in {@code [0.0, 1.0]}
 * @param pValue            one-sided Student-t p-value prior to Bonferroni family-wise threshold comparison
 * @param baselineInstances post-GC instance count at the initial checkpoint
 * @param peakInstances     maximum post-GC instance count observed across checkpoints
 * @param baselineBytes     post-GC retained heap bytes at the initial checkpoint
 * @param peakBytes         maximum post-GC retained heap bytes observed across checkpoints
 * @param eventBasis        independent variable basis ({@code "departure"} | {@code "checkpoint"} | {@code "command"})
 * @governedBy ADR-115
 * @governedBy ADR-125
 * @governedBy ADR-129
 * @governedBy leafsleuth-ADR-005
 * @governedBy leafsleuth-ADR-006
 * @trace REQ-SLEUTH-FUZZ-002
 * @trace REQ-SLEUTH-RES-001
 */
public record ClassRetentionFinding(
        String className,
        String owningJar,
        double slopePerEvent,
        double rSquared,
        double pValue,
        long baselineInstances,
        long peakInstances,
        long baselineBytes,
        long peakBytes,
        String eventBasis
) {
    public ClassRetentionFinding {
        Objects.requireNonNull(className, "className");
        owningJar = (owningJar == null || owningJar.isBlank()) ? "server/jdk" : owningJar;
        slopePerEvent = Double.isFinite(slopePerEvent) ? slopePerEvent : 0.0;
        rSquared = Double.isFinite(rSquared) ? Math.max(0.0, Math.min(1.0, rSquared)) : 0.0;
        pValue = Double.isFinite(pValue) ? Math.max(0.0, Math.min(1.0, pValue)) : 1.0;
        baselineInstances = Math.max(0L, baselineInstances);
        peakInstances = Math.max(baselineInstances, peakInstances);
        baselineBytes = Math.max(0L, baselineBytes);
        peakBytes = Math.max(baselineBytes, peakBytes);
        eventBasis = (eventBasis == null || eventBasis.isBlank()) ? "checkpoint" : eventBasis;
    }

    /**
     * Returns {@code true} if this class is uniquely attributed to a third-party plugin {@code .jar}.
     *
     * @return whether {@link #owningJar()} names a specific plugin archive
     */
    public boolean isPluginOwned() {
        return !owningJar.equals("server/jdk")
                && !owningJar.equals("shared")
                && !owningJar.equals("unknown");
    }

    /**
     * Returns the net instance growth from baseline to peak.
     *
     * @return net instance delta
     */
    public long netInstanceGrowth() {
        return Math.max(0L, peakInstances - baselineInstances);
    }

    /**
     * Returns the net retained byte growth from baseline to peak.
     *
     * @return net byte delta
     */
    public long netByteGrowth() {
        return Math.max(0L, peakBytes - baselineBytes);
    }

    /**
     * Formats a concise human-readable summary line for operator audit output.
     *
     * @return formatted summary line
     */
    public String formatSummaryLine() {
        return String.format(
                Locale.ROOT,
                "%s (%s): +%.2f inst/%s (R²=%.3f, p=%.2e, %d -> %d inst, %+.1f KB)",
                className,
                owningJar,
                slopePerEvent,
                eventBasis,
                rSquared,
                pValue,
                baselineInstances,
                peakInstances,
                netByteGrowth() / 1024.0
        );
    }
}
