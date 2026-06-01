package io.github.dailystruggle.mapsapi.model;

import java.util.Objects;

/**
 * Mermaid-source-text chart shape consumed by the Stage-4 {@code MermaidRenderer}.
 * Records the raw Mermaid source plus an optional title; parsing, layout, and
 * rasterisation happen at render time per REQ-RTP-MAP-005 (in-house subset,
 * no external runtime or scripting engine).
 *
 * @param source the raw Mermaid source text (subset locked in ADR-046
 *               §Mermaid output). Out-of-subset constructs surface as
 *               {@code MermaidParseException} at render time.
 * @param title  human-readable title shown above the diagram, or {@code ""}
 *               for an untitled chart. Never {@code null}.
 */
public record MermaidChart(String source, String title) implements ChartModel {

    public MermaidChart {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(title, "title");
    }
}
