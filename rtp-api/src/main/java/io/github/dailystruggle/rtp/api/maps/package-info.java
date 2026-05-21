/**
 * Renderer-neutral declarative chart specification surface for RTP.
 *
 * <p>This package hosts the public {@code rtp-api} types that let callers
 * (admin commands, info menu actions, addons) request a chart without
 * coupling to a specific renderer, a specific {@code MapBinding}
 * implementation, or any particular data source. The dispatch and resolver
 * implementations live in {@code rtp-core}; the platform delivery callback
 * lives in the platform adapters.
 *
 * <p>Governing documents:
 * <ul>
 *   <li><b>ADR-046</b> ({@code docs/adr/ADR-046-maps-api-module.md}) — the
 *       {@code maps-api} module that supplies the {@code ChartModel},
 *       {@code ChartRenderer}, and {@code MapBinding} surfaces this package
 *       eventually feeds.</li>
 *   <li><b>ADR-047</b> ({@code docs/adr/ADR-047-...}) — the declarative
 *       chart-spec bridge (resolver SPI, dispatch contract).</li>
 *   <li><b>REQ-RTP-MAP-006</b> ({@code docs/dev/REQUIREMENTS.md} §1.7) — the
 *       renderer-neutral chart-spec requirement.</li>
 *   <li><b>REQ-RTP-OBS-001</b> — non-blocking metric publication. Resolvers
 *       reading metric state shall remain non-blocking.</li>
 * </ul>
 *
 * <p>Inherited prohibitions from {@code docs/dev/REQUIREMENTS.md §3}: S-004
 * (no silently discarded failures — dispatch failures surface configurable
 * messages), S-005 (no synchronous chunk I/O — resolvers read only in-memory
 * state), S-006 (require-by-contract: dispatching against a missing binding
 * throws {@code IllegalStateException} inside the binding itself, with the
 * pre-check surfaced via {@code mapBindingMissing}), S-007 (configurable
 * failure messages).
 */
package io.github.dailystruggle.rtp.api.maps;
