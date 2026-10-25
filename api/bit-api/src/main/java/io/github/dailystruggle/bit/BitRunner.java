package io.github.dailystruggle.bit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Registry and caller-driven runner for PBIT, CBIT and IBIT components. */
public final class BitRunner {
    private final Map<String, BitComponent> components = new ConcurrentHashMap<>();
    private final Map<String, BitResult> lastResults = new ConcurrentHashMap<>();
    private final Set<String> executedComponents = ConcurrentHashMap.newKeySet();
    private volatile BitCoverageProvider coverageProvider;

    public BitRunner() {
        this(BitCoverageDiscovery.discover("auto"));
    }

    public BitRunner(BitCoverageProvider coverageProvider) {
        this.coverageProvider = coverageProvider == null ? BitCoverageDiscovery.off() : coverageProvider;
    }

    public BitCoverageProvider coverageProvider() {
        return coverageProvider;
    }

    public void setCoverageProvider(BitCoverageProvider coverageProvider) {
        this.coverageProvider = coverageProvider == null ? BitCoverageDiscovery.off() : coverageProvider;
    }

    public BitCoverageSnapshot coverageSnapshot() {
        return coverageProvider.snapshot(this);
    }

    /** Returns true if the component with the given id has been executed on this runner. */
    public boolean hasExecuted(String id) {
        return id != null && executedComponents.contains(normalize(id));
    }

    /** Returns an immutable snapshot of normalized component ids executed on this runner. */
    public Set<String> executedComponentIds() {
        return Set.copyOf(executedComponents);
    }

    /** Registers a component unless its normalized id is already in use. */
    public boolean register(BitComponent component) {
        Objects.requireNonNull(component, "component must not be null");
        return components.putIfAbsent(component.id(), component) == null;
    }

    /** Removes a component and its cached result. */
    public boolean unregister(String id) {
        if (id == null) return false;
        String key = normalize(id);
        boolean removed = components.remove(key) != null;
        lastResults.remove(key);
        executedComponents.remove(key);
        return removed;
    }

    /** Returns registered metadata in stable id order. */
    public List<BitComponent> components() {
        return components.values().stream().sorted(Comparator.comparing(BitComponent::id)).toList();
    }

    /** Runs one check using the caller-supplied executor, then caches its result by component id. */
    public BitResult run(BitComponent component, Executor executor) {
        Objects.requireNonNull(component, "component must not be null");
        Objects.requireNonNull(executor, "executor must not be null");
        BitComponent resultComponent = components.getOrDefault(component.id(), component);
        long startedNanos = System.nanoTime();
        FutureTask<BitReport> task = new FutureTask<>(component.check());
        BitReport report;
        try {
            executor.execute(task);
            report = Objects.requireNonNull(task.get(component.timeoutMillis(), TimeUnit.MILLISECONDS),
                    "component returned a null report");
        } catch (TimeoutException timeout) {
            task.cancel(true);
            report = failure(component, BitCodes.COMPONENT_TIMEOUT,
                    "Component exceeded timeout of " + component.timeoutMillis() + " ms.");
        } catch (InterruptedException interrupted) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            report = failure(component, BitCodes.COMPONENT_THROWN, "Interrupted while waiting for component.");
        } catch (Throwable failure) {
            task.cancel(true);
            Throwable cause = failure instanceof ExecutionException && failure.getCause() != null
                    ? failure.getCause() : failure;
            report = failure(component, BitCodes.COMPONENT_THROWN, message(cause));
        }
        long durationMillis = TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - startedNanos));
        BitResult result = new BitResult(resultComponent, report, durationMillis, System.currentTimeMillis());
        String key = normalize(component.id());
        lastResults.put(key, result);
        executedComponents.add(key);
        return result;
    }

    /** Runs components matching a selector; grouped selectors run only SAFE checks. */
    public List<BitResult> run(BitSelector selector, Executor executor) {
        Objects.requireNonNull(selector, "selector must not be null");
        Objects.requireNonNull(executor, "executor must not be null");
        List<BitComponent> selected = components().stream().filter(component -> matches(selector, component)).toList();
        if (selected.isEmpty()) return List.of(unknown(selector.value()));
        List<BitResult> results = new ArrayList<>(selected.size());
        for (BitComponent component : selected) results.add(run(component, executor));
        return List.copyOf(results);
    }

    /** Most recent result for a component id, or {@code null} if it has not run. */
    public BitResult last(String id) {
        return id == null ? null : lastResults.get(normalize(id));
    }

    /** Combines findings and preserves the highest explicit health of the component reports. */
    public BitReport aggregate(List<BitResult> results) {
        Objects.requireNonNull(results, "results must not be null");
        List<BitFinding> findings = new ArrayList<>();
        BitHealth health = BitHealth.HEALTHY;
        for (BitResult result : results) {
            Objects.requireNonNull(result, "result must not be null");
            findings.addAll(result.report().findings());
            if (result.report().health().ordinal() > health.ordinal()) health = result.report().health();
        }
        return new BitReport(BitReport.CURRENT_SCHEMA, health, findings);
    }

    private static boolean matches(BitSelector selector, BitComponent component) {
        // Only a selector naming one component may run a check with side effects.
        boolean safe = component.sideEffect() == BitSideEffect.SAFE;
        return switch (selector.kind()) {
            case COMPONENT -> component.id().equals(selector.value());
            case ALL -> safe;
            case TIER -> safe && component.tier() == selector.tier();
            case SUBSYSTEM -> safe && component.subsystem().equals(selector.value());
        };
    }

    private static BitResult unknown(String selector) {
        BitComponent component = new BitComponent(selector, "bit", BitTier.IBIT, BitSideEffect.SAFE, 1,
                () -> BitReport.of(List.of()));
        BitFinding finding = BitFinding.of(BitCodes.COMPONENT_UNKNOWN, "bit runner", selector,
                "No registered component matched the selector.", "a registered component", "");
        return new BitResult(component, BitReport.of(List.of(finding)), 0, System.currentTimeMillis());
    }

    private static BitReport failure(BitComponent component, BitCode code, String message) {
        return BitReport.of(List.of(BitFinding.of(code, component.id(), "", message,
                "completed within the component contract", code.remediation())));
    }

    private static String message(Throwable failure) {
        if (failure.getMessage() != null && !failure.getMessage().isBlank()) return failure.getMessage();
        return failure.getClass().getName();
    }

    private static String normalize(String id) {
        return id.trim().toLowerCase(Locale.ROOT);
    }
}
