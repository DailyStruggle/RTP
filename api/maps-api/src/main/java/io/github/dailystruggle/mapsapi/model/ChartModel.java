package io.github.dailystruggle.mapsapi.model;

/**
 * Marker sealed interface for every chart-data shape carried into a
 * {@code ChartRenderer}. Concrete permitted subtypes are immutable records
 * with defensive copies of their array / collection fields so renderers can
 * be invoked from any thread without aliasing the producer's state
 * (REQ-RTP-MAP-002).
 *
 * <p>New chart shapes shall be added to the {@code permits} clause and
 * documented in {@code maps-api-ADR-001} section Chart models.
 */
public sealed interface ChartModel
        permits Heatmap2D, CategoryDistribution, TimeSeries, RegionCoverage,
                RegionBadLocations, RegionBiomesRgb, MermaidChart, DualSparkline {
}
