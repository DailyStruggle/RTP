package io.github.dailystruggle.metrics.api;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Platform-portable metrics facade. The single read-only entry point for runtime health
 * signals.
 *
 * <p>Implementations shall be:
 * <ul>
 *     <li>Snapshot-based &mdash; each call returns a fresh immutable {@link MetricsSnapshot};
 *         no streaming, no callbacks.</li>
 *     <li>Non-blocking &mdash; readers shall not perform I/O, acquire contended locks,
 *         or call platform APIs that may park the calling thread.</li>
 *     <li>Side-effect free for the caller &mdash; calling {@link #snapshot()} shall not
 *         mutate the underlying samplers.</li>
 * </ul>
 *
 * <p>Sibling plugins in a monorepo register a {@link MetricsBinding} (and optional
 * {@link MetricsExtension} suppliers) via the static registry methods below. The host
 * plugin's metrics aggregator (e.g. RTP's {@code CoreMetrics}) is expected to mirror its
 * own binding into this registry so cross-plugin readers see live values.
 */
public interface Metrics {

    /** Returns the current health snapshot. Cheap to call repeatedly. */
    MetricsSnapshot snapshot();

    /**
     * Default no-op implementation used before any platform binding has been installed.
     * Heap fields default to {@code 0} (no internal heap sampler is consulted from this
     * module to keep {@code metrics-api} platform-agnostic; the host plugin may wrap this
     * with its own heap-aware NOOP).
     */
    Metrics NOOP = () -> new MetricsSnapshot(
            MetricsSnapshot.UNSAMPLED,
            MetricsSnapshot.UNSAMPLED,
            MetricsSnapshot.UNSAMPLED,
            MetricsSnapshot.UNSAMPLED,
            0,
            0,
            0L,
            0L,
            System.currentTimeMillis(),
            java.util.Collections.emptyList()
    );

    // ---------------------------------------------------------------------
    // Cross-plugin static registry (monorepo reuse).
    //
    // Semantics:
    //  - Last-writer-wins on registerBinding; the prior binding is logged at WARNING.
    //  - registerExtension is additive; extensions compose.
    //  - currentBinding() returns MetricsBinding.NOOP when nothing is registered.
    // ---------------------------------------------------------------------

    /**
     * Registers (or replaces) the active {@link MetricsBinding}. Last-writer-wins with a
     * WARNING log when an existing non-NOOP binding is displaced. Pass {@code null} to
     * clear back to {@link MetricsBinding#NOOP}. Safe to call from any thread.
     *
     * @return the previously installed binding (never {@code null}).
     */
    static MetricsBinding registerBinding(MetricsBinding binding) {
        MetricsBinding next = (binding == null) ? MetricsBinding.NOOP : binding;
        MetricsBinding prev = Registry.BINDING.getAndSet(next);
        if (prev != MetricsBinding.NOOP && prev != next) {
            Logger.getLogger("Metrics-API").log(Level.WARNING,
                    "MetricsBinding replaced: {0} -> {1}. Last-writer-wins; prior binding will no longer be sampled.",
                    new Object[]{prev.getClass().getName(), next.getClass().getName()});
        }
        return prev;
    }

    /** Returns the currently registered binding, or {@link MetricsBinding#NOOP} if none. */
    static MetricsBinding currentBinding() {
        return Registry.BINDING.get();
    }

    /**
     * Registers a supplier that produces a {@link MetricsExtension} per snapshot.
     * Suppliers are invoked in registration order; returned extensions are folded onto
     * the snapshot via {@link MetricsSnapshot#withExtension(MetricsExtension)}.
     */
    static void registerExtension(Supplier<? extends MetricsExtension<?>> supplier) {
        if (supplier == null) return;
        Registry.EXTENSIONS.add(supplier);
    }

    /** Returns the live list of registered extension suppliers (read-only iteration safe). */
    static List<Supplier<? extends MetricsExtension<?>>> registeredExtensions() {
        return Registry.EXTENSIONS;
    }

    /** Test-only hook: clears the registry. */
    static void resetRegistryForTesting() {
        Registry.BINDING.set(MetricsBinding.NOOP);
        Registry.EXTENSIONS.clear();
    }

    /** Holder for the static registry state. */
    final class Registry {
        private Registry() {}
        static final AtomicReference<MetricsBinding> BINDING =
                new AtomicReference<>(MetricsBinding.NOOP);
        static final List<Supplier<? extends MetricsExtension<?>>> EXTENSIONS =
                new CopyOnWriteArrayList<>();
    }
}
