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
 * surface — see PROPOSAL-metrics-api-extraction.md §1.1 / §2.1.
 *
 * <p>This package intentionally has no {@code org.bukkit} / {@code net.minecraft} /
 * Folia imports. Consumers depend on this module via
 * {@code api project(':metrics-api')}; sibling plugins consume via
 * {@code compileOnly} so the lite-jar shading rule prevents dual-loading
 * (per PROPOSAL §1.1).
 *
 * <p>Phase 1 (this session): module skeleton + neutral package root +
 * Gradle wiring. The actual SPI move ({@code Metrics}, {@code MetricsBinding},
 * {@code MetricsSnapshot}, {@code FoliaRegionSample}) and the extension-model
 * reshape land in a follow-up session — see the proposal's revised
 * implementation order and the audit recorded in
 * {@code docs/dev/scratch/CHECKLIST-metrics-api-extraction.md}.
 */
package io.github.dailystruggle.metrics.api;
