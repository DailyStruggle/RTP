# ADR-052 — Always-on generation outcome metrics and cause-tagged bad-location persistence

**Status:** Accepted
**Date:** 2026-05-29

## Context

The location-generation pipeline (`PregenTask`) already attributes every rejected
attempt to a `LocationGenerator.FailTypes` cause, but that attribution was only
materialised when the per-invocation `verbose` flag was set, and it was only ever
emitted to the console on attempt-cap exhaustion (`completeExhausted`). There was
no durable, always-on record of success / failure rates or of the per-cause
rejection breakdown, so the plugin could not answer "how often does RTP fail, and
why" without enabling verbose logging and scraping the console.

A competitor (EzRTP) surfaces this as `/rtp stats performance` and `/rtp unsafe-stats`,
including a `/rtp fake` command to synthesise data for claim/safety statistics. The
RTP pipeline already produces the same signal organically: the `safetyExternal`
cause is populated by the real `GlobalRegionVerifiers` (claim/protection) path, so
no traffic simulation is required — the data only needs to be captured always, not
just under `verbose`.

Separately, the `MemoryShape` bad-location cache persists runs as
`(start, run-length)` prefix-sum pairs in a `.bin` file with no record of *why* a
run was marked bad, so a heatmap or audit of rejection causes over the persisted
region was impossible.

## Decision

1. Add a process-global, wait-free accumulator `RtpOutcomeStats` (in `rtp-core`
   `metrics` package) tracking total successes and per-`FailTypes` failures, with
   success-rate and breakdown accessors.
2. Feed it from the single always-on chokepoint `PregenTask.recordOutcome(...)`
   (called on every reject path and on success), by parsing the leading cause
   token. This is independent of `verbose`; the existing verbose `failMap` console
   dump is unchanged.
3. Tag each persisted bad-location run with one rejection cause. `MemoryShape`
   gains a `badCauseCache` byte array aligned 1:1 with `badKeysCache`, cause-aware
   `addBadLocation(long, FailTypes)` / `addBadChunk(long, FailTypes)` overloads,
   and threads the cause through the RLE rebuild with **first-cause-wins** when
   adjacent runs coalesce (small-scale information loss is acceptable).
4. Version the `.bin` format: a `BIN_MAGIC` + `BIN_VERSION` header precedes the
   legacy layout and each bad-run carries a trailing cause byte. Legacy files
   (no magic) load with cause `misc`; no migration step is required. The debug
   JSON export gains a `cause` field per run.

Only the four chunk-attributable `PregenTask.addBadChunk` sites are tagged with a
specific cause (vert, biome, safety, safetyExternal); all other callers
(ScanTask, shape `uniqueplacements`, Chunky, polygon) keep the default `misc`.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Always-populate the verbose `failMap` string-keyed map | Unbounded key cardinality (per-location keys) grows memory without bound; the accumulator is O(#FailTypes). |
| Per-attempt site edits (16 reject sites) to feed metrics | More edit surface and drift risk than the single `recordOutcome` chokepoint that already fires on every outcome. |
| Hard `.bin` migration to the new format | Backward-compatible magic/version detection with `misc` fallback avoids a migration pass and tolerates mixed on-disk versions. |
| Store cause per individual index (no coalescing) | Defeats the RLE compression that the bad-location cache exists to provide. |
| `/rtp fake` data synthesis (EzRTP approach) | The live pipeline already fills `safetyExternal`/`safety`/`biome` causes from real attempts, so simulation is unnecessary. |

## Consequences

- **Positive:** Success/failure rates and a per-cause rejection breakdown are
  available always, from real traffic, with no verbose logging and no traffic
  simulation. Persisted bad-location runs now carry a cause, enabling cause-aware
  heatmaps/audits. The verbose console dump and S-004 attribution are unchanged.
- **Negative / Trade-offs:** The `.bin` format grows by 8 bytes (header) + 1 byte
  per bad-run. Coalesced runs lose per-index cause fidelity (first-cause-wins).
  `MemoryShape` now depends on `LocationGenerator.FailTypes` (same module).

## References

- `rtp-core` `metrics/RtpOutcomeStats.java`, `selection/region/PregenTask.java`
  (`recordOutcome` / `recordOutcomeMetric`), `selectors/memory/shapes/MemoryShape.java`
  (`badCauseCache`, `save`/`load`, `exportDebugJson`).
- Test: `metrics/RtpOutcomeStatsAndCauseTaggingTest.java`.
- Regression guard preserved: `ReqRtpS004NullChunkAttributionTest`.
- `docs/dev/METRICS_PLAN.md`.
