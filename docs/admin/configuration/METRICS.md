# Metrics Settings Reference (`metrics.yml`)

`metrics.yml` holds **reporting-only** knobs for the runtime-health metrics SPI (TPS / MSPT / heap / queue samples). Throttle and tuning knobs continue to live in [`performance.yml`](PERFORMANCE.md).

Most keys affect **Folia only**: single-region runtimes (Paper / Bukkit / Fabric) always return an empty per-region list, which is the correct answer for those platforms, so the Folia knobs have no effect there.

---

## Keys

| Key | Type | Default | Options | Description |
|---|---|---|---|---|
| `foliaIncludeRegions` | Boolean | `true` | (n/a) | Populate the snapshot's per-region detail on Folia. No effect on Paper / Bukkit / Fabric. |
| `foliaAggregationTps` | Enum | `mean` | `mean`, `max` | Strategy used to fold per-region samples into the scalar `tps{1,5,15}m` fields on Folia. |
| `foliaAggregationMspt` | Enum | `max` | `mean`, `max` | Strategy used to fold per-region samples into the scalar `mspt` field on Folia. |
| `foliaAggregationTickBudget` | Enum | `max` | `mean`, `max` | Strategy used to fold per-region samples into the scalar `tickBudgetUtilisation` field on Folia. |

## Versioning

- `version`: Internal config version (do not change).
