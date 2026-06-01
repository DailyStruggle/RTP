/**
 * Default {@link io.github.dailystruggle.mapsapi.MapBinding} implementation
 * installed before {@code RTPHooks} wires a real platform binding, and the
 * binding shipped by the Lite assembly variant. Every entry-point throws
 * {@link java.lang.IllegalStateException} (REQ-RTP-MAP-001 extends
 * REQ-RTP-S-006; REQ-RTP-MAP-004 Lite-only Noop).
 *
 * @see io.github.dailystruggle.mapsapi.noop.NoopMapBinding
 */
package io.github.dailystruggle.mapsapi.noop;
