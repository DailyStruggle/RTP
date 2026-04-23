# QueueTask Probe-First Plan

Extends `BIOME_LOOKUP_PERF_PLAN.md` Stage 1 (loose prefilter on unkept chunks)
to the cache-fill + player-attempt path.

Single insertion point: `QueueTask.handlePair`. Both producers benefit:

- `RegionCacheTask` → `LocationGenerator.getLocation` → `QueueTask` (fills
  `unkeptLocations`).
- Player-triggered `LocationGenerator.getLocation` → `QueueTask` (teleport
  attempts).

The authoritative safety verification stays post-load: existing
`QueueTask.evaluateSafety` + `Region.execute` unkept→kept recheck against
`LocationGenerator.unsafeBlocksCache`.

---

## Slice 1 — probe-first gate (SHIP)

Scope: rtp-core only. No rtp-api / platform / ADR change.

1. Insertion in `QueueTask.handlePair` after the null-pair guard, before the
   `preReservation != null` branch and before `world.getOrLoadChunk(cx, cz)`.
2. Skip probe when `pair.reservation() != null` (chunk already loaded/pinned).
3. Probe call: `world.probeChunkColumn(cx, cz, vert.minY() - 1, vert.maxY(),
   vert.requiresSkyLight())` — mirrors `ScanTask.tryProbeFirstScan` PR-18
   widening.
4. On probe-complete:
   - biome-filter mismatch (when `biomeNames != null && !biomeNames.isEmpty()`
     and probe biome known) → `pollNext()` (no load, no DB write).
   - `vert.adjustFromProbe(probe, ...) == null` → `pollNext()`.
   - accept / probe null / exception → fall through to the original
     `getOrLoadChunk` path verbatim.
5. `pollNext()` reuses `reenterAsync` when the probe future is incomplete at
   time of dispatch, matching the existing hot-path policy.
6. No new FailTypes accounting — drain telemetry unchanged. Slice 2 wires
   `FailTypes.prefilter*`.

### Tests (Slice 1)

`QueueTaskProbeFirstTest` (rtp-core):

- `probeReject_biomeFilterMismatch_pollsNextWithoutLoading`
- `probeReject_adjustFromProbeNull_pollsNextWithoutLoading`
- `probeAccept_fallsThroughToLoadAndEvaluateSafety`
- `probeNull_fallsThroughToLegacyPath`
- `probeException_fallsThroughToLegacyPath`
- `preReservationPresent_probeIsSkipped`

Plus: existing region suite (466 tests) stays green.

---

## Slice 2 — telemetry + biome exemption (FOLLOW-UP)

1. Wire `LocationGenerator.FailTypes.prefilterBiome` /
   `FailTypes.prefilterBlock` / `FailTypes.prefilterRange` from the Stage-1
   rejects in `QueueTask`.
2. ADR-016 §13.3 vanilla-generator biome exemption: skip the probe biome
   filter when the world's generator triggers the exemption (same carve-out
   `PregenTask.tryProbeFirst` already honours).
3. Additional tests for the exemption carve-out (biome mismatch on an
   exempt world falls through to full load, NOT `pollNext`).

---

## Non-goals

- No change to `afterChunkResolved` / `evaluateSafety` — probe-accept lands
  there unchanged.
- No change to `RegionCacheTask` — it inherits the benefit via `QueueTask`.
- No `rtp-api` surface change.
- No ADR supersede.
