package io.github.dailystruggle.bit;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Platform-neutral discovery of {@link BitCoverageProvider} implementations via
 * {@link ServiceLoader} with built-in {@code "surface"}, {@code "jacoco"}, {@code "auto"}, and
 * {@code "off"} resolution (ADR-121, ADR-103).
 */
public final class BitCoverageDiscovery {
    private static final List<BitFinding> RECORDED_FINDINGS = new CopyOnWriteArrayList<>();

    private static final BitCoverageProvider OFF_PROVIDER = new BitCoverageProvider() {
        @Override
        public String id() {
            return "off";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public BitCoverageSnapshot snapshot(BitRunner runner) {
            Objects.requireNonNull(runner, "runner must not be null");
            return BitCoverageSnapshot.off();
        }
    };

    private BitCoverageDiscovery() {}

    public static BitCoverageProvider off() {
        return OFF_PROVIDER;
    }

    public static BitCoverageProvider discover(String configuredId) {
        return discover(
                configuredId,
                BitCoverageProvider.class.getClassLoader(),
                JacocoCoverageProvider::isAgentPresent,
                null);
    }

    public static BitCoverageProvider discover(String configuredId, ClassLoader classLoader) {
        return discover(
                configuredId,
                classLoader,
                JacocoCoverageProvider::isAgentPresent,
                null);
    }

    public static BitCoverageProvider discover(String configuredId, Consumer<BitFinding> findingSink) {
        return discover(
                configuredId,
                BitCoverageProvider.class.getClassLoader(),
                JacocoCoverageProvider::isAgentPresent,
                findingSink);
    }

    public static BitCoverageProvider discover(
            String configuredId,
            ClassLoader classLoader,
            BooleanSupplier jacocoAgentPresent,
            Consumer<BitFinding> findingSink) {
        Objects.requireNonNull(jacocoAgentPresent, "jacocoAgentPresent must not be null");
        ClassLoader effectiveLoader = classLoader != null
                ? classLoader
                : BitCoverageProvider.class.getClassLoader();
        String normalized = normalizeId(configuredId);
        if ("off".equals(normalized) || "none".equals(normalized) || "false".equals(normalized)) {
            return OFF_PROVIDER;
        }

        Map<String, BitCoverageProvider> byId = discoverProviders(effectiveLoader, jacocoAgentPresent);
        if ("auto".equals(normalized)) {
            BitCoverageProvider jacoco = byId.get(JacocoCoverageProvider.ID);
            if (jacoco != null && safeIsAvailable(jacoco)) {
                return jacoco;
            }
            return byId.getOrDefault(SurfaceCoverageProvider.ID, SurfaceCoverageProvider.INSTANCE);
        }

        if (JacocoCoverageProvider.ID.equals(normalized)) {
            BitCoverageProvider jacoco = byId.get(JacocoCoverageProvider.ID);
            if (jacoco != null && safeIsAvailable(jacoco)) {
                return jacoco;
            }
            BitFinding finding = BitFinding.of(
                    BitCodes.COVERAGE_AGENT_UNAVAILABLE,
                    "advanced/bit.yml",
                    "coverage",
                    "coverage: jacoco was requested, but no JaCoCo runtime agent is attached; falling back to surface coverage.",
                    "attached -javaagent:jacocoagent.jar or coverage: surface",
                    BitCodes.COVERAGE_AGENT_UNAVAILABLE.remediation());
            RECORDED_FINDINGS.add(finding);
            if (findingSink != null) {
                findingSink.accept(finding);
            }
            return byId.getOrDefault(SurfaceCoverageProvider.ID, SurfaceCoverageProvider.INSTANCE);
        }

        BitCoverageProvider requested = byId.get(normalized);
        if (requested != null && safeIsAvailable(requested)) {
            return requested;
        }
        return byId.getOrDefault(SurfaceCoverageProvider.ID, SurfaceCoverageProvider.INSTANCE);
    }

    /** Discovers all available providers on {@code classLoader} merged with built-in providers. */
    public static Map<String, BitCoverageProvider> discoverProviders(
            ClassLoader classLoader, BooleanSupplier jacocoAgentPresent) {
        Map<String, BitCoverageProvider> byId = new LinkedHashMap<>();
        ServiceLoader<BitCoverageProvider> loader = ServiceLoader.load(BitCoverageProvider.class, classLoader);
        for (BitCoverageProvider provider : loadProvidersSafely(loader)) {
            String id = safeId(provider);
            if (id != null && !id.isBlank()) {
                byId.putIfAbsent(id.trim().toLowerCase(Locale.ROOT), provider);
            }
        }
        byId.putIfAbsent(SurfaceCoverageProvider.ID, SurfaceCoverageProvider.INSTANCE);
        byId.putIfAbsent(
                JacocoCoverageProvider.ID,
                new JacocoCoverageProvider(
                        SurfaceCoverageProvider.INSTANCE,
                        jacocoAgentPresent,
                        JacocoCoverageProvider::readAgentExecutionData));
        byId.putIfAbsent("off", OFF_PROVIDER);
        return byId;
    }

    /** Safely iterates a {@link ServiceLoader} without aborting on broken provider classes. */
    public static <T> List<T> loadProvidersSafely(ServiceLoader<T> loader) {
        Objects.requireNonNull(loader, "loader must not be null");
        List<T> providers = new ArrayList<>();
        Iterator<T> iterator = loader.iterator();
        boolean more = true;
        while (more) {
            try {
                more = iterator.hasNext();
                if (more) {
                    providers.add(iterator.next());
                }
            } catch (ServiceConfigurationError | LinkageError | RuntimeException ignored) {
                // Skip unloadable service provider and continue discovery.
            }
        }
        return providers;
    }

    /** Returns findings recorded during coverage provider discovery (such as agent fallback). */
    public static List<BitFinding> findings() {
        return List.copyOf(RECORDED_FINDINGS);
    }

    /** Clears recorded discovery findings for test isolation. */
    public static void clearFindings() {
        RECORDED_FINDINGS.clear();
    }

    private static String normalizeId(String configuredId) {
        if (configuredId == null || configuredId.isBlank()) {
            return "auto";
        }
        return configuredId.trim().toLowerCase(Locale.ROOT);
    }

    private static String safeId(BitCoverageProvider provider) {
        try {
            return provider.id();
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static boolean safeIsAvailable(BitCoverageProvider provider) {
        try {
            return provider.isAvailable();
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }
}
