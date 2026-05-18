/**
 * Runtime cartography SPI root. Public entry-points: {@link io.github.dailystruggle.mapsapi.MapBinding},
 * {@link io.github.dailystruggle.mapsapi.MapCanvas}, {@link io.github.dailystruggle.mapsapi.MapHandle},
 * {@link io.github.dailystruggle.mapsapi.MapAllocationRequest},
 * {@link io.github.dailystruggle.mapsapi.Cancellation}.
 *
 * <p>Governed by:
 * <ul>
 *   <li>{@code docs/adr/ADR-046-maps-api-module.md} — module bootstrap.</li>
 *   <li>{@code maps-api/docs/adr/maps-api-ADR-001-bootstrap.md} — package
 *       layout + palette policy + Lite assembly trim.</li>
 *   <li>{@code docs/dev/REQUIREMENTS.md} §1.7 — REQ-RTP-MAP-001..005.</li>
 * </ul>
 *
 * <p>Inherited prohibition requirements: REQ-RTP-S-004 (no silent teleport /
 * binding failure — every entry-point throws {@link java.lang.IllegalStateException}
 * via {@link io.github.dailystruggle.mapsapi.noop.NoopMapBinding} until
 * {@code RTPHooks} wires a real binding), REQ-RTP-S-005 (no chunk loading on
 * the main thread — refresh dispatch hops to the platform async scheduler),
 * REQ-RTP-S-006 (require-by-contract API), REQ-RTP-S-007 (configurable busy /
 * invalid-command messages — {@code mapBusy} etc. land in Stage 2), and
 * REQ-RTP-F-013 (all user-facing messages configurable via {@code messages.yml}).
 *
 * <p>This package may not import {@code org.bukkit.*} or {@code net.minecraft.*}.
 * The only subpackage permitted {@code org.bukkit.*} imports is
 * {@code mapsapi.bukkit} (Stage 2).
 */
package io.github.dailystruggle.mapsapi;
