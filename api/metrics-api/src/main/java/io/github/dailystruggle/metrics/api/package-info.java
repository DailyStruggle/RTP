/**
 * Platform-portable metrics SPI shared across the monorepo.
 *
 * <p>This module exposes the contract that platform adapters
 * ({@code rtp-bukkit}, {@code rtp-paper}, {@code rtp-folia}, {@code rtp-fabric},
 * and any sibling plugin in the same monorepo) implement to publish runtime
 * health signals (TPS / MSPT / player count / soft cap / heap / per-region
 * detail). The contract is deliberately plugin-agnostic: it carries
 * host-runtime fields only. Plugin-specific counters (e.g. RTP's teleport
 * pipeline queue depth) belong on a typed extension slot, not on the shared
 * surface - see {@code metrics-api-ADR-001} (docs/adr/).
 *
 * <p>This package intentionally has no {@code org.bukkit} / {@code net.minecraft} /
 * Folia imports. Consumers depend on this module via
 * {@code api project(':metrics-api')}; sibling plugins consume via
 * {@code compileOnly} so the lite-jar shading rule prevents dual-loading
 * (per {@code metrics-api-ADR-001}).
 *
 * <p>Surface: {@code Metrics} (read-only facade + static registry),
 * {@code MetricsBinding} (host-runtime contract), {@code MetricsSnapshot}
 * (immutable snapshot + typed extension slot), {@code MetricsExtension}
 * (typed plugin-counter slot), {@code FoliaRegionSample} (per-region detail),
 * {@code RegionQueueRow} and {@code RegionQueueStatus} (per-region queue and cache status).
 * Rationale and the rtp-core extraction history are recorded in
 * {@code metrics-api/docs/adr/metrics-api-ADR-001-module-extraction.md}.
 */
package io.github.dailystruggle.metrics.api;
