# metrics-api docs index

Task router for the `metrics-api` subproject. Read only what your task needs.

| Task | Read |
|------|------|
| Orientation: what the module is, who consumes it | [`../README.md`](../README.md) |
| Why the SPI was split out of `rtp-core`; what stayed behind | [`adr/metrics-api-ADR-001-module-extraction.md`](adr/metrics-api-ADR-001-module-extraction.md) |
| Implementing a platform `MetricsBinding` | [`../README.md`](../README.md) (*How to implement a binding*) |
| Adding plugin-specific counters | `MetricsExtension` Javadoc + `MetricsSnapshot#withExtension` |
| Project-wide metrics design, catalogue, `/rtp info` surface | [`../../docs/dev/METRICS_PLAN.md`](../../docs/dev/METRICS_PLAN.md) |
| Requirement traceability (REQ-RTP-OBS-001/002/003) | [`../../docs/dev/TRACEABILITY.md`](../../docs/dev/TRACEABILITY.md) |

## Module contents

- `io.github.dailystruggle.metrics.api.Metrics` &mdash; read-only facade + static registry.
- `io.github.dailystruggle.metrics.api.MetricsBinding` &mdash; host-runtime contract.
- `io.github.dailystruggle.metrics.api.MetricsSnapshot` &mdash; immutable snapshot.
- `io.github.dailystruggle.metrics.api.MetricsExtension` &mdash; typed plugin-counter slot.
- `io.github.dailystruggle.metrics.api.FoliaRegionSample` &mdash; per-region detail carrier.

Concrete bindings live in the platform adapters; the host aggregator (`CoreMetrics`)
and RTP-specific counters (`RTPMetricsExtension`) live in `rtp-core`.
