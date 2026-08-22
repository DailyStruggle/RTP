# Metrics Settings Reference (`advanced/metrics.yml`)

`advanced/metrics.yml` holds **reporting-only** knobs for the runtime-health metrics SPI (TPS / MSPT / heap / queue samples). Throttle and tuning knobs live in [`advanced/performance.yml`](PERFORMANCE.md).

Most keys affect **Folia only**: single-region runtimes (Paper / Bukkit / Fabric / NeoForge) return an empty per-region list, so the Folia aggregation knobs have no effect there.

---

## Updating Settings

You can update metrics configuration through:
1. **In-game admin menu**: Run `/rtp admin` or `/rtp menu` -> click **Admin Panel**.
2. **Command line**: Use `/rtp config metrics <key>=<value>`.
3. **Direct editing**: Edit `advanced/metrics.yml` on disk and run `/rtp reload`.

> 📎 See [IN_GAME_CONFIG.md](IN_GAME_CONFIG.md) for full menu and command navigation details.

---

## Keys

| Key | Type | Default | Options | Description |
|---|---|---|---|---|
| `foliaIncludeRegions` | Boolean | `true` | (n/a) | Populate the snapshot's per-region detail on Folia. No effect on Paper / Bukkit / Fabric / NeoForge. |
| `foliaAggregationTps` | Enum | `mean` | `mean`, `max` | Strategy used to fold per-region samples into the scalar `tps{1,5,15}m` fields on Folia. |
| `foliaAggregationMspt` | Enum | `max` | `mean`, `max` | Strategy used to fold per-region samples into the scalar `mspt` field on Folia. |
| `foliaAggregationTickBudget` | Enum | `max` | `mean`, `max` | Strategy used to fold per-region samples into the scalar `tickBudgetUtilisation` field on Folia. |

## Versioning

- `version`: Internal config version (do not change).
