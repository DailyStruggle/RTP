# ADR-028 — L3 Backlog Cache (`backlogLocations`)

- **Status**: Proposed
- **Date**: 2026-05-02
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

- **Order-preserving FIFO with a `verified` flag.** Each entry carries a
  boolean `verified` flag. Entries are inserted in selection order and **are
  never reordered**.
- **Head-blocking promotion.** Only contiguous verified entries at the head
  of L3 are eligible for promotion to L2. An unverified entry at position 0
  blocks all later entries from advancing, even if those later entries have
  already been verified.
- **One bin per `Region.execute()` pulse.** On each pulse, the verifier
  picks the bin (32 × 32 chunks = one `.mca` region file) containing the
  *oldest* unverified L3 entry, runs the Anvil pre-filter for every L3
  entry whose chunk lies inside that bin, and marks each entry's `verified`
  flag accordingly. This bounds per-pulse work and amortizes the
  region-file read across all candidates in the bin.
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

`rtp-lite` (ADR-024) **does not expose** `backlogCacheCap` in its packaged
`regions/default.yml`. The runtime default in lite is `0` (disabled), so the
trimmed assembly retains its smaller-memory profile.

### Refill loop

`Region.execute()` is extended to refill L3 *before* the existing L2 deficit
loop. The L3 refill simply over-selects (shape-pick only, no chunk I/O)
until the buffer reaches `backlogCacheCap`. This is S-005-safe because
shape selection is pure math — no chunk loads occur during L3 refill.

The existing L2 deficit loop is **not modified**, with one addition: at the
top of each pulse, after the L3 refill, the verifier promotes any
contiguous head-of-L3 verified entries into L2 (subject to L2's
`cacheCap`). If L2 is already at cap, head-of-L3 entries simply wait — the
buffer is intentionally allowed to sit idle when downstream cannot drain
it.

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
- Default-off in lite, gracefully disabled by `backlogCacheCap = 0`.
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
  absence of `backlogCacheCap` defaults to `1000` on the full assembly
  and `0` on lite.

## Configuration

`regions/default.yml`:

```yaml
cacheCap: 50
activeChunkCap: 10
backlogCacheCap: 1000   # ADR-028 — L3 unverified backlog (set 0 to disable)
```

Lite (`rtp-plugin/src/lite/resources/regions/default.yml`): key omitted;
runtime default is `0`.

## Implementation map (forward-looking)

This ADR is documentation-only. Implementation will follow in a separate
change. Anticipated touch points:

| Concern | Class / file |
|---|---|
| Config key | `RegionKeys.backlogCacheCap` |
| Settings field | `RegionSettings.backlogCacheCap` (record component) |
| Buffer | `RegionQueueManager.backlogLocations` (nullable) |
| Buffer type | New `BacklogLocationBuffer` (order-preserving, per-entry `verified` flag) |
| Refill | extension to `Region.execute()` ahead of the existing L2 deficit loop |
| Verify | new per-pulse step: pick oldest-unverified bin → anvil pre-filter → mark `verified` |
| Promote | head-only contiguous-verified promotion to `unkeptLocations` |
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
