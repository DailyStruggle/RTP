package io.github.dailystruggle.bit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Threading-neutral registry that runs registered PBITs on its caller's thread. */
public final class BitRegistry {
    private final Map<String, BuiltInTestable> components = new ConcurrentHashMap<>();

    /** Registers a component unless the same id is already in use. */
    public boolean register(String id, BuiltInTestable component) {
        String key = Objects.requireNonNull(id, "id must not be null").trim();
        if (key.isEmpty()) throw new IllegalArgumentException("id must not be blank");
        return components.putIfAbsent(key, Objects.requireNonNull(component, "component must not be null")) == null;
    }

    public boolean unregister(String id) {
        return id != null && components.remove(id) != null;
    }

    public BitReport runPbit() {
        List<BitFinding> findings = new ArrayList<>();
        components.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .forEach(entry -> runOne(entry.getKey(), entry.getValue(), findings));
        return BitReport.of(findings);
    }

    public BitHealth getHealthStatus() {
        BitHealth aggregate = BitHealth.HEALTHY;
        for (Map.Entry<String, BuiltInTestable> entry : components.entrySet()) {
            try {
                BitHealth health = entry.getValue().getHealthStatus();
                if (health == null) return BitHealth.FAILED;
                if (health == BitHealth.FAILED) return BitHealth.FAILED;
                if (health == BitHealth.DEGRADED) aggregate = BitHealth.DEGRADED;
            } catch (Throwable failure) {
                return BitHealth.FAILED;
            }
        }
        return aggregate;
    }

    private static void runOne(String id, BuiltInTestable component, List<BitFinding> findings) {
        try {
            PbitResult result = Objects.requireNonNull(component.executePbit(), "PBIT result must not be null");
            if (!result.passed()) {
                String message = result.diagnosticMessage();
                if (result.error() != null && result.error().getMessage() != null) {
                    message = message.isBlank() ? result.error().getMessage() : message + ": " + result.error().getMessage();
                }
                findings.add(BitFinding.of(BitCodes.PBIT_FAILED, id, "", message, "passed", ""));
            }
            BitHealth health = Objects.requireNonNull(component.getHealthStatus(), "health must not be null");
            if (health == BitHealth.FAILED && result.passed()) {
                findings.add(BitFinding.of(BitCodes.HEALTH_FAILED, id, "",
                        "Component reports failed health", "HEALTHY or DEGRADED", ""));
            } else if (health == BitHealth.DEGRADED && result.passed()) {
                findings.add(BitFinding.of(BitCodes.HEALTH_DEGRADED, id, "",
                        "Component reports degraded health", "HEALTHY", ""));
            }
        } catch (Throwable failure) {
            String message = failure.getMessage() == null ? failure.getClass().getName() : failure.getMessage();
            findings.add(BitFinding.of(BitCodes.PBIT_THROWN, id, "", message, "non-throwing PBIT", ""));
        }
    }
}
