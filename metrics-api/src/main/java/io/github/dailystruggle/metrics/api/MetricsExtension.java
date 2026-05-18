package io.github.dailystruggle.metrics.api;

/**
 * Marker interface for plugin-specific extension payloads carried inside a
 * {@link MetricsSnapshot}. Sibling plugins consuming this {@code metrics-api}
 * module shall define their own implementations rather than extending
 * {@link MetricsSnapshot} itself.
 *
 * <p>The host-runtime fields on {@code MetricsSnapshot} (TPS, MSPT, players, softCap,
 * heap, Folia regions, timestamp) are plugin-agnostic. Everything domain-specific
 * (e.g. RTP teleport pipeline counters, network/proxy counters) lives on an
 * extension reachable via {@link MetricsSnapshot#extension(Class)}.
 *
 * <p>Implementations shall be immutable and contain only scalar / primitive /
 * unmodifiable-collection fields. No platform types.
 *
 * @param <SELF> the concrete extension type, used as the key for type-safe lookup
 *               via {@link MetricsSnapshot#extension(Class)}.
 */
public interface MetricsExtension<SELF extends MetricsExtension<SELF>> {
}
