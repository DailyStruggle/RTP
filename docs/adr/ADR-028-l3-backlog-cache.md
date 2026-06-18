# ADR-028 — L3 Backlog Cache (`backlogLocations`)

- **Status**: Accepted (amended 2026-05-08)
- **Date**: 2026-05-08 (amended; accepted 2026-05-07; originally proposed 2026-05-02)
- **Supersedes**: —
- **Related**: ADR-006 (async queue pre-generation), ADR-015 (stale-chunk guard / count-bound pipes), ADR-016 (anvil subsystem), ADR-023 (login reserve cache), `REQ-RTP-S-005` (no chunk loading on the main thread)

## Context

The plugin currently maintains two general-purpose location buffers per region:

- **L1 — `keptLocations`** (hot): pre-verified locations whose chunks are
  currently loaded with `keep(true)` applied. Drained directly by `/rtp`.
- **L2 — `unkeptLocations`** (cold): pre-verified locations whose chunks have
  been released to save RAM. Promoted to L1 on demand.

Both buffers store *fully verified* locations. Verification (shape pick →
chunk load → vertical adjust → biome → safety) is dominated by chunk I/O,
which on a default region runs serially per candidate. When a region's L2 is
drained — for example after a join-storm or a deliberate `/rtp scan` cold
start — refill rate is bounded by the per-attempt chunk-load cost, even
though many of those chunks already exist on disk and could be cheaply
inspected via the Anvil pre-filter (ADR-016) without loading them into the
server.

Bulk pre-screening at the **region-file (32 × 32 chunk) granularity** is
known to be cheap when amortized across many candidates that fall inside the
same `.mca` file: a single `AnvilRegionByteCache` read can answer biome /
material questions for every candidate in that bin without involving the
chunk system.

A user request was raised to add a *third, larger, unverified buffer* that
over-selects coordinates ahead of L2, bins them by region file, and uses
the Anvil pre-filter to verify whole bins at a time — without disturbing
the buffer's FIFO order.

## Decision

Introduce an optional **L3 — `backlogLocations`** buffer per region, sitting
upstream of `unkeptLocations`. Conceptually:

```
shape pick ──▶ L3 backlog ──(anvil-verified, in original order)──▶ L2 unkept ──▶ L1 kept ──▶ /rtp
```

### Semantics

- **Storage.** Per-RTP-region `BacklogLocationBuffer` (order-preserving
  FIFO, capacity-bounded by `backlogCacheCap`). Entries are inserted in
  selection order and **are never reordered**. The buffer is the sole
  storage of an L3 entry.
- **Three-state validity.** Each entry carries a tri-state validity tag:
  `UNVERIFIED` (not yet probed), `VALIDATED` (anvil pre-filter passed),
  or `INVALIDATED` (anvil pre-filter rejected). This replaces the earlier
  two-state `verified` boolean.
- **Cross-region bin index (world-level).** Each `RTPWorld` carries a
  `Map<RegionFileCoord, WeakReference<List<BacklogEntry>>>` indexing every
  in-flight L3 entry across all RTP regions targeting that world by its
  `.mca` region-file coordinate. Entries are added to *both* the owning
  RTP region's buffer and the world-level bin list at insertion; the
  world map is **not** a second storage — bin lists hold references back
  into the region buffers' entries, so a single anvil pass over a bin
  updates the validity tag for every contributing region simultaneously.
  Weak references allow a bin's list to be GC'd when no contributing
  region still holds it; the next entry inserted into that bin re-creates
  the list. Bin-list size is implicitly bounded by Σ(`backlogCacheCap`)
  across regions sharing the world; `backlogCacheCap` itself caps only
  the per-region buffer, not the world map.
- **Head-blocking promotion.** Only contiguous `VALIDATED` entries at the
  head of an L3 buffer are eligible for promotion to L2. An `UNVERIFIED`
  or `INVALIDATED` entry at position 0 blocks all later entries from
  advancing, even if those later entries have already been validated.
  (`INVALIDATED` head entries are dropped on the next pulse, then the
  next contiguous-`VALIDATED` run is promoted.)
- **One bin per `Region.execute()` pulse.** On each pulse, the verifier
  picks the bin (32 × 32 chunks = one `.mca` region file) containing the
  *oldest* `UNVERIFIED` entry of *this region's* L3 buffer, looks up the
  world-level bin list for that `RegionFileCoord`, runs the Anvil
  pre-filter for every entry in the bin list (cross-region amortization),
  and updates each entry's validity tag to `VALIDATED` or `INVALIDATED`.
  This bounds per-pulse work, amortizes the region-file read across all
  candidates in the bin regardless of which RTP region contributed them,
  and preserves L3 buffer order (no reordering — the bin index is just a
  lookup keyed by `RegionFileCoord`).
- **Verification path is anvil-prefilter only.** Full pipeline verification
  (chunk-load + vert + safety) still happens later, at L2 → L1 promotion,
  unchanged. L3 verification is purely a cheap *rejection* pass — entries
  that pass anvil pre-filter are still subject to the existing kept-promotion
  pipeline. Entries that fail anvil pre-filter are dropped (no DB row, no
  reservation, no chunk I/O).

### Capacity

- New region-config key **`backlogCacheCap`** (Integer, default **`1000`**),
  configurable per region in the same style as `cacheCap`.
- Intended to be sized **much larger** than `cacheCap` so that binning
  yields meaningful amortization (`≈ 10 ×` `cacheCap` is the design target
  but is not enforced as a ratio).
- `backlogCacheCap = 0` disables L3 entirely (the buffer is `null`,
  selection feeds L2 directly as today).

### Persistence

L3 entries are **not persisted** to the database. The DB save / delete
callbacks installed by `RegionQueueManager.installDatabaseCallbacks` are
*not* attached to `backlogLocations`. Rationale:

- L3 entries are unverified. Persisting them would require either marking
  rows as "tentative" (new schema) or risking re-loading rejected
  coordinates after restart.
- Re-selection from the spiral after restart is cheap; anvil verification
  is cheap; the value of persistence is negligible compared to the
  schema cost.

### Lite assembly

**Amendment (2026-06-18):** `rtp-lite` (ADR-024) now ships the **same**
`regions/default.yml` as the full assembly and therefore **does expose**
`backlogCacheCap` (default `1000`). The dedicated lite override that previously
omitted the key has been removed so the lite jar inherits the full assembly's
region config verbatim (the lite shadow jar's `DuplicatesStrategy.EXCLUDE`
falls through to `src/main/resources/regions/default.yml`). L3 is therefore
enabled by default in lite as well; an operator may still set
`backlogCacheCap: 0` to disable it.

### Refill loop

`Region.execute()` is extended to refill L3 *before* the existing L2 deficit
loop. The L3 refill simply over-selects (shape-pick only, no chunk I/O)
until the buffer reaches `backlogCacheCap`. This is S-005-safe because
shape selection is pure math — no chunk loads occur during L3 refill.

The existing L2 deficit loop is **not modified**, with one addition: at the
top of each pulse, after the L3 refill and the bin-verification step,
`Region.execute()` drains the head of this region's L3 buffer while the
head entry's validity is `VALIDATED`, promoting each into L2 (subject to
L2's `cacheCap`); `INVALIDATED` head entries are discarded; iteration
stops at the first `UNVERIFIED` head entry or when L2 reaches cap. If L2
is already at cap, head-of-L3 validated entries simply wait — the buffer
is intentionally allowed to sit idle when downstream cannot drain it.
All three steps (refill, verify-one-bin, drain-head-validated) execute
linearly within `Region.execute()`; there is no producer/consumer split.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Make L2 itself ~10× larger | Defeats the purpose: every L2 entry is fully verified (chunk I/O), so a larger L2 means proportionally more chunk loads, not fewer. The point of L3 is to *avoid* most of those loads via anvil pre-filtering. |
| Re-order L3 by bin to verify bin-at-a-time without head-blocking | Violates user requirement *"without changing their order in queue"*. Order preservation is the contract that makes L3 indistinguishable from a virtual L2 from the consumer's perspective; reordering would change spatial distribution semantics established by ADR-001 / ADR-009. |
| Verify all bins per pulse (no per-pulse cap) | Unbounded per-pulse work would violate the count-bound pipeline contract on Folia (ADR-004 / ADR-015). One bin per pulse keeps the work amortized and predictable. |
| Use the full pipeline (chunk + vert + safety) for L3 verification | Negates the cost win — L3 then duplicates L2's work. Anvil-only verification is a cheap *rejection filter*; survivors still face the full pipeline at L2 → L1. |
| Persist L3 to the database | Schema cost + tentative-row semantics outweigh the value, since shape re-selection and anvil verification are both cheap. See *Persistence* above. |
| Bin at single-chunk granularity | Loses the amortization win — one anvil read per chunk is not appreciably cheaper than a chunk-load. The 32 × 32 region-file is the natural amortization unit because it matches the on-disk `.mca` file. |
| Bin at world-section / 16 × 16 granularity | No on-disk grouping below the `.mca` level; anvil reads are already region-file scoped. |

## Consequences

### Positive

- Drains-then-refills cycles pay one `.mca` read per bin instead of one
  chunk-load per candidate during the cheap rejection phase.
- L1 / L2 semantics, persistence, and stale-chunk guards (ADR-015) are
  unchanged — the existing tested promotion path is bit-for-bit preserved.
- Gracefully disabled at any tier by `backlogCacheCap = 0`.
- Anvil pre-filter is read-only and off the main thread by design
  (`AnvilIoPool`), preserving REQ-RTP-S-005.
- Order preservation means downstream (`/rtp`, addons polling L2) sees no
  behavioral change beyond *more* locations becoming available.

### Negative / Trade-offs

- Memory cost: an L3 of 1000 entries per region holds 1000 × `RTPLocation`
  + per-entry `verified` flag bookkeeping. At ~64 B per entry this is
  ~64 KiB/region — negligible, but multiplied across regions on a
  multi-world server it should be documented.
- Head-of-line blocking: a single hard-to-bin entry at L3 head can stall
  promotion until its bin is reached. Mitigated by the per-pulse
  oldest-bin selector — the head will always belong to the next bin
  scheduled.
- New buffer to maintain in `RegionQueueManager.shutDown` and the active
  GC sweep (ADR-008). The buffer must be drained on shutdown without
  invoking the (absent) DB callbacks.
- Anvil pre-filter availability is platform-dependent. On Fabric (which
  remains unstable per ADR-022) and on worlds whose region files are
  not yet flushed to disk, anvil verification falls back to "verified
  by default" (do not block on absent anvil data). This avoids stalling
  L3 on freshly generated worlds.

### Neutral

- Fabric: stubbed until the platform stabilizes per ADR-022. The L3
  buffer + refill are platform-agnostic; only the anvil-prefilter
  bridge needs platform support, and Fabric currently has none.
- Configuration: existing region files continue to work without change;
  absence of `backlogCacheCap` defaults to `1000` on both the full and lite
  assemblies (lite inherits the full `regions/default.yml` as of 2026-06-18).
  The in-code runtime fallback when the key is absent remains `0`.

## Configuration

`regions/default.yml`:

```yaml
cacheCap: 50
activeChunkCap: 10
backlogCacheCap: 1000   # ADR-028 — L3 unverified backlog (set 0 to disable)
```

Lite: ships the same `regions/default.yml` as the full assembly (no lite
override as of 2026-06-18), so `backlogCacheCap: 1000` is exposed and L3 is
enabled by default.

## Implementation map (forward-looking)

This ADR is documentation-only. Implementation will follow in a separate
change. Anticipated touch points:

| Concern | Class / file |
|---|---|
| Config key | `RegionKeys.backlogCacheCap` |
| Settings field | `RegionSettings.backlogCacheCap` (record component) |
| Buffer (per RTP region) | `RegionQueueManager.backlogLocations` (nullable) |
| Buffer type | New `BacklogLocationBuffer` (order-preserving, per-entry tri-state validity tag) |
| Cross-region bin index | New `RTPWorld.backlogBinsByRegionFile` — `Map<RegionFileCoord, WeakReference<List<BacklogEntry>>>`; populated on insert, weakly held |
| Bin coord type | New `RegionFileCoord` value type (`(cx>>5, cz>>5)`) |
| Refill | extension to `Region.execute()` ahead of the existing L2 deficit loop |
| Verify | new per-pulse step: pick this region's oldest-`UNVERIFIED` entry → look up world bin → anvil pre-filter every entry in the bin list → set tri-state validity |
| Promote | head-only contiguous-`VALIDATED` promotion to `unkeptLocations` (drop `INVALIDATED` head, stop on `UNVERIFIED`), inline within `Region.execute()` |
| Templates | `rtp-plugin/src/main/resources/regions/default.yml`, `rtp-plugin/src/main/resources/lang/regions.lang.yml` (+ de/es/fr/nl) |
| Placeholder | `%rtp_backlogCacheCap%` (mirror of `%rtp_cacheCap%`) |
| Memory accounting | `MemoryTracker.totalCacheCap` includes `backlogCacheCap` |
| Lite | omit key from `src/lite/resources/regions/default.yml`; runtime default `0` |

## Follow-ups (out of v1 scope)

- REQ-traceable test classes: `BacklogCacheOrderPreservationTest`,
  `BacklogCacheSingleBinPerPulseTest`, `BacklogCacheHeadBlockingTest`.
- Localized lang files (de/es/fr/nl) — staged after the English row lands.
- Admin doc updates (`docs/admin/CONFIGURATION.md`, `REGIONS.md`,
  `FAQ.md`) — staged with implementation.
- Telemetry: `backlog-bin-hits` / `backlog-bin-rejects` counters under
  `AnvilPrefilterMetrics`, surfaced via `/rtp info`.
- Auto-sizing: an optional `backlogCacheCapMode = MULTIPLIER × cacheCap`
  shorthand if the absolute-integer form proves awkward in practice.
- Fabric: revisit when ADR-022 phase blockers are cleared.

## References

- ADR-006 — Async queue pre-generation (the L1/L2 design L3 extends).
- ADR-015 — Stale-chunk guard / count-bound pipes (per-pulse work bound).
- ADR-016 — Anvil subsystem (the verification primitive used by L3).
- ADR-023 — Login reserve cache (precedent for an optional, nullable,
  separately-managed third buffer in `RegionQueueManager`).
- `REQ-RTP-S-005` — No chunk loading on the main thread.
- `.junie/AGENTS.md` — Domain Analogies & Aliases table (L1/L2/L3 nicknames).
