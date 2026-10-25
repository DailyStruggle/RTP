package io.github.dailystruggle.bit;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable snapshot of BIT component surface execution, CBIT counter observation, and optional
 * live JaCoCo bytecode probe coverage (ADR-121, ADR-103).
 */
public record BitCoverageSnapshot(
        String providerId,
        int executedComponents,
        int registeredComponents,
        Map<BitSideEffect, Integer> executedBySideEffect,
        Map<BitSideEffect, Integer> registeredBySideEffect,
        int observedCbitComponents,
        int registeredCbitComponents,
        boolean bytecodeAgentPresent,
        long hitProbes,
        long totalProbes,
        Map<String, ProbeRatio> subsystemProbes) {

    public BitCoverageSnapshot {
        providerId = providerId == null || providerId.isBlank()
                ? "off"
                : providerId.trim().toLowerCase(Locale.ROOT);
        if (executedComponents < 0) {
            throw new IllegalArgumentException("executedComponents must not be negative");
        }
        if (registeredComponents < 0) {
            throw new IllegalArgumentException("registeredComponents must not be negative");
        }
        executedBySideEffect = normalizeSideEffects(executedBySideEffect);
        registeredBySideEffect = normalizeSideEffects(registeredBySideEffect);
        if (observedCbitComponents < 0) {
            throw new IllegalArgumentException("observedCbitComponents must not be negative");
        }
        if (registeredCbitComponents < 0) {
            throw new IllegalArgumentException("registeredCbitComponents must not be negative");
        }
        if (hitProbes < 0) {
            throw new IllegalArgumentException("hitProbes must not be negative");
        }
        if (totalProbes < 0) {
            throw new IllegalArgumentException("totalProbes must not be negative");
        }
        subsystemProbes = subsystemProbes == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(subsystemProbes));
    }

    public static BitCoverageSnapshot off() {
        return new BitCoverageSnapshot(
                "off",
                0,
                0,
                Map.of(),
                Map.of(),
                0,
                0,
                false,
                0L,
                0L,
                Map.of());
    }

    public boolean isEnabled() {
        return !"off".equalsIgnoreCase(providerId);
    }

    public int unobservedCbitComponents() {
        return Math.max(0, registeredCbitComponents - observedCbitComponents);
    }

    public double componentRatio() {
        return registeredComponents <= 0 ? 0.0 : (double) executedComponents / registeredComponents;
    }

    public double cbitRatio() {
        return registeredCbitComponents <= 0 ? 0.0 : (double) observedCbitComponents / registeredCbitComponents;
    }

    public double probeRatio() {
        return totalProbes <= 0 ? 0.0 : (double) hitProbes / totalProbes;
    }

    /** Formats a single-line human-readable coverage summary suitable for table footers and logs. */
    public String summary() {
        if (!isEnabled()) return "coverage=off";
        StringBuilder sb = new StringBuilder("coverage=")
                .append(providerId)
                .append(" components=").append(executedComponents).append('/').append(registeredComponents)
                .append(" cbit=").append(observedCbitComponents).append('/').append(registeredCbitComponents)
                .append(" (observed=").append(observedCbitComponents)
                .append(",unobserved=").append(unobservedCbitComponents()).append(')');
        if (bytecodeAgentPresent || totalProbes > 0) {
            sb.append(String.format(
                    Locale.ROOT,
                    " probes=%d/%d (%.1f%%)",
                    hitProbes,
                    totalProbes,
                    probeRatio() * 100.0));
        }
        return sb.toString();
    }

    /** Serializes the coverage snapshot to JSON without external library dependencies. */
    public String toJson() {
        StringBuilder json = new StringBuilder("{\"providerId\":\"")
                .append(BitJson.escape(providerId))
                .append("\",\"executedComponents\":").append(executedComponents)
                .append(",\"registeredComponents\":").append(registeredComponents)
                .append(",\"observedCbitComponents\":").append(observedCbitComponents)
                .append(",\"registeredCbitComponents\":").append(registeredCbitComponents)
                .append(",\"bytecodeAgentPresent\":").append(bytecodeAgentPresent)
                .append(",\"hitProbes\":").append(hitProbes)
                .append(",\"totalProbes\":").append(totalProbes)
                .append(",\"subsystemProbes\":{");
        int idx = 0;
        for (Map.Entry<String, ProbeRatio> entry : subsystemProbes.entrySet()) {
            if (idx++ > 0) json.append(',');
            ProbeRatio ratio = entry.getValue();
            json.append('"').append(BitJson.escape(entry.getKey())).append("\":{\"hitProbes\":")
                    .append(ratio.hitProbes())
                    .append(",\"totalProbes\":").append(ratio.totalProbes())
                    .append(",\"loadedClasses\":").append(ratio.loadedClasses())
                    .append('}');
        }
        return json.append("}}").toString();
    }

    private static Map<BitSideEffect, Integer> normalizeSideEffects(Map<BitSideEffect, Integer> raw) {
        EnumMap<BitSideEffect, Integer> normalized = new EnumMap<>(BitSideEffect.class);
        for (BitSideEffect sideEffect : BitSideEffect.values()) {
            normalized.put(sideEffect, 0);
        }
        if (raw != null) {
            for (Map.Entry<BitSideEffect, Integer> entry : raw.entrySet()) {
                BitSideEffect key = Objects.requireNonNull(entry.getKey(), "sideEffect key must not be null");
                int value = entry.getValue() == null ? 0 : entry.getValue();
                if (value < 0) {
                    throw new IllegalArgumentException("sideEffect count must not be negative: " + key);
                }
                normalized.put(key, value);
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(normalized));
    }

    /** Bytecode probe coverage totals for one package/subsystem prefix. */
    public record ProbeRatio(long hitProbes, long totalProbes, int loadedClasses) {
        public ProbeRatio {
            if (hitProbes < 0) throw new IllegalArgumentException("hitProbes must not be negative");
            if (totalProbes < 0) throw new IllegalArgumentException("totalProbes must not be negative");
            if (loadedClasses < 0) throw new IllegalArgumentException("loadedClasses must not be negative");
        }

        public double ratio() {
            return totalProbes <= 0 ? 0.0 : (double) hitProbes / totalProbes;
        }
    }
}
