/**
 * Sealed {@link io.github.dailystruggle.mapsapi.model.ChartModel} hierarchy
 * and its permitted record shapes: {@link io.github.dailystruggle.mapsapi.model.Heatmap2D},
 * {@link io.github.dailystruggle.mapsapi.model.CategoryDistribution},
 * {@link io.github.dailystruggle.mapsapi.model.TimeSeries},
 * {@link io.github.dailystruggle.mapsapi.model.RegionCoverage}, and
 * {@link io.github.dailystruggle.mapsapi.model.MermaidChart}.
 *
 * <p>Every model is an immutable record with defensive copies of array /
 * collection fields so a {@code ChartRenderer} may be invoked from any thread
 * without aliasing the producer's state - see REQ-RTP-MAP-002.
 *
 * <p>New chart shapes shall be added to the {@code permits} clause on
 * {@code ChartModel} and documented in
 * {@code maps-api/docs/adr/maps-api-ADR-001-bootstrap.md} section Chart models.
 */
package io.github.dailystruggle.mapsapi.model;
