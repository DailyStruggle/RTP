package io.github.dailystruggle.bit;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Dependency-free coverage provider that measures executed vs. registered {@link BitComponent}s by
 * {@link BitSideEffect} and observed vs. unobserved {@link BitTier#CBIT} components.
 */
public final class SurfaceCoverageProvider implements BitCoverageProvider {
    public static final String ID = "surface";
    public static final SurfaceCoverageProvider INSTANCE = new SurfaceCoverageProvider();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public BitCoverageSnapshot snapshot(BitRunner runner) {
        Objects.requireNonNull(runner, "runner must not be null");
        EnumMap<BitSideEffect, Integer> executedBySideEffect = new EnumMap<>(BitSideEffect.class);
        EnumMap<BitSideEffect, Integer> registeredBySideEffect = new EnumMap<>(BitSideEffect.class);
        for (BitSideEffect sideEffect : BitSideEffect.values()) {
            executedBySideEffect.put(sideEffect, 0);
            registeredBySideEffect.put(sideEffect, 0);
        }

        List<BitComponent> components = runner.components();
        int executedComponents = 0;
        int registeredComponents = components.size();
        int observedCbitComponents = 0;
        int registeredCbitComponents = 0;

        for (BitComponent component : components) {
            BitSideEffect sideEffect = component.sideEffect();
            registeredBySideEffect.put(sideEffect, registeredBySideEffect.get(sideEffect) + 1);
            BitResult last = runner.last(component.id());
            boolean executed = runner.hasExecuted(component.id()) || last != null;
            if (executed) {
                executedComponents++;
                executedBySideEffect.put(sideEffect, executedBySideEffect.get(sideEffect) + 1);
            }
            if (isContinuousObservedComponent(component, last)) {
                registeredCbitComponents++;
                if (executed && isCbitObserved(last)) {
                    observedCbitComponents++;
                }
            }
        }

        return new BitCoverageSnapshot(
                ID,
                executedComponents,
                registeredComponents,
                executedBySideEffect,
                registeredBySideEffect,
                observedCbitComponents,
                registeredCbitComponents,
                false,
                0L,
                0L,
                Map.of());
    }

    /**
     * Returns true when a CBIT component has produced a result that does not contain an explicit
     * {@link BitCodes#CBIT_UNOBSERVED} finding.
     */
    public static boolean isCbitObserved(BitResult result) {
        if (result == null) return false;
        return result.report().findings().stream()
                .noneMatch(finding -> BitCodes.CBIT_UNOBSERVED.key().equals(finding.code()));
    }

    /**
     * Returns true when a component is registered in {@link BitTier#CBIT} or is the platform-hook
     * fallback sensor component ({@code platform-hooks}) executed on the runner.
     */
    public static boolean isContinuousObservedComponent(BitComponent component, BitResult last) {
        if (component == null) return false;
        if (component.tier() == BitTier.CBIT) return true;
        return "platform-hooks".equals(component.id()) && last != null;
    }

    /**
     * Builds {@link BitCodes#CBIT_UNOBSERVED} findings for every registered CBIT component on
     * {@code runner} that has not yet been executed or observed.
     */
    public static List<BitFinding> unobservedCbitFindings(BitRunner runner) {
        Objects.requireNonNull(runner, "runner must not be null");
        List<BitFinding> findings = new ArrayList<>();
        for (BitComponent component : runner.components()) {
            if (component.tier() != BitTier.CBIT) continue;
            BitResult last = runner.last(component.id());
            if (!isCbitObserved(last)) {
                findings.add(BitFinding.of(
                        BitCodes.CBIT_UNOBSERVED,
                        component.id(),
                        component.subsystem(),
                        "Continuous BIT component '" + component.id() + "' has no recorded observations.",
                        "at least 1 observed sample",
                        BitCodes.CBIT_UNOBSERVED.remediation()));
            }
        }
        return List.copyOf(findings);
    }
}
