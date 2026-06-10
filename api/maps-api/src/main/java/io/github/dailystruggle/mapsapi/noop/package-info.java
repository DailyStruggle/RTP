/**
 * Default {@link io.github.dailystruggle.mapsapi.MapBinding} implementation,
 * installed before {@code RTPHooks} wires a real platform binding and used as
 * the fallback when no platform binding is available. Every entry-point throws
 * {@link java.lang.IllegalStateException} (REQ-RTP-MAP-001 extends
 * REQ-RTP-S-006). Per REQ-RTP-MAP-004 (amended 2026-06-04) the Lite assembly
 * ships the same platform-backed binding as the full assembly, so this Noop is
 * the fallback only, not the sole binding on Lite.
 *
 * @see io.github.dailystruggle.mapsapi.noop.NoopMapBinding
 */
package io.github.dailystruggle.mapsapi.noop;
