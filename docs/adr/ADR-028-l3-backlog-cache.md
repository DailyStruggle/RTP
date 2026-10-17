# ADR-028 — L3 Backlog Cache (`backlogLocations`)

- **Status**: Accepted (amended 2026-05-08; amended 2026-10-13: prefilter outcomes and the cold fill)
- **Date**: 2026-05-08 (amended; accepted 2026-05-07; originally proposed 2026-05-02)
- **Supersedes**: —
- **Related**: ADR-006 (async queue pre-generation), ADR-015 (stale-chunk guard / count-bound pipes), ADR-016 (anvil subsystem), ADR-023 (login reserve cache), `REQ-RTP-S-005` (no chunk loading on the main thread)

> **Nomenclature note.** This ADR keeps its `L1` / `L2` / `L3` title and body as the historical record of the decision. The vocabulary itself is retired by [ADR-078](ADR-078-composable-cache-pipeline-stages.md) phase 2: production code, javadoc, and canonical documentation say **hot** (`keptLocations`), **cold** (`unkeptLocations`), and **backlog** (`backlogLocations`). Public config keys and database column names are unchanged.

## Context

The plugin currently maintains two general-purpose location buffers per region:

- **L1 — `keptLocations`** (hot): pre-verified locations whose chunks are
  currently loaded with `keep(true)` applied. Drained directly by `/rtp`.
- **L2 — `unkeptLocations`** (cold): pre-verified locations whose chunks have
  been released to save RAM. Promoted to L1 on demand.

Both buffers store *fully verified* locations. Verification (shape pick →
chunk load → vertical adjust → biome → safety) is dominated by chunk I/O,
which on a default region runs serially per candidate. When a region's L2 is
drained — for example after a join-storm or a server cold
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

### Amendment: Configurable Bin Harvesting Density & Modulated PRP Selection (2026-09-08)

To balance off-tick `.mca` Anvil I/O amortization against player spatial variety, the L3 generation and bin-screening pipeline is extended with configurable candidate harvesting proportions and modulated pseudorandom bin selection:

1. **Configurable Locations-per-Bin Density (`locationsPerBin` / `binDensity`):**
   - Expressed as an integer count of candidate locations selected per $32 \times 32$ chunk macro-bin ($1{,}024$ chunks per `.mca` region file).
   - **Default: `2` to `4` locations per bin** (e.g. `locationsPerBin: 3`, or proportionally $\approx 3 / 1024 \approx 0.29\%$ sampling density).
   - **`-1` (Unbinned Full-Random):** Disables bin-clustering entirely. L3 candidates are drawn across independent, unclustered random coordinates across the full shape domain without intra-bin reuse.
   - **Intra-Bin Separation Guarantee:** When $C \in [2, 4]$ locations are harvested from a single $32 \times 32$ bin, candidates are placed along the local Hilbert curve separated by dyadic bisection offsets ($d_{\text{min}} \ge 20$ chunks / $320$ blocks). Even within the same region file, candidates are placed at opposite corners, preventing mutual line-of-sight collisions.
   - **I/O Amortization:** Decompressing and inspecting a single `.mca` file off-tick verifies $2\text{--}4$ candidates at once, cutting background disk seeks and ZLIB decompression passes by $50\%\text{--}75\%$.

2. **Modulated PRP Bin Selection for Macro-Dispersion:**
   - Instead of picking bins sequentially or via memoryless random draws, the active working set of bins is selected via a **Modulated Keyed Pseudorandom Permutation (Feistel PRP)** across the world's macro-bin grid:
     $$\text{binIndex} = \text{FeistelPermute}(t_{\text{bin}}, \text{totalWorldBins}, \text{secretSeed})$$
   - Properties:
     - **Unique Bin Rotation:** Bins are visited without replacement ($0.0\%$ repeat bins during a rotation cycle).
     - **Cryptographic Unpredictability:** Macro-bins jump across non-linear, distant quadrants of the continent on successive refill pulses.
     - **Player Suspicion Protection:** Maintaining an active working set of $24\text{--}32$ bins ensures that even if a player uses `/rtp` repeatedly, round-robin dispatching across active bins prevents any player from landing in the same $512 \times 512$ block region twice in a single session.

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

### Amendment: Prefilter Outcomes and the Cold Fill (2026-10-13)

Supersedes the "verified by default" fallback (see *Negative / Trade-offs*) and the refill pacing in *Refill loop* above. Every prefilter outcome other than `REJECT` used to mark an entry `VALIDATED`, so with no provider bound, or on ungenerated land, picks that nobody had checked drained into cold, counted as backlog stock and recorded successes.

1. **The backlog reads region files only.** It never loads, generates or tickets a chunk (S-005, S-002); its only I/O is the binned region-file read (one `classifyBatch` per region file).
2. **Four validity states.** `UNVERIFIED`: not yet prefiltered. `VALIDATED`: `ACCEPT` on disk data. `UNKNOWN`: no disk data to decide from (chunk not generated, unreadable or unsupported file, failed or timed-out batch, or no provider bound). `INVALIDATED`: `REJECT`; removed in the same pulse, recorded as a `prefilterBlock` failure and a bad chunk in the owner's shape.
3. **Validated entries cost nothing more until promotion.** `VALIDATED` entries drain into cold up to `cacheCap` with no chunk load (contiguous head under `FLAT_STRIDE`, random under `BINNED_AMORTIZED`), recording one success each. `ACCEPT` only screens the surface blocks of every column for unsafe blocks, so it is not the full check (see item 8).
4. **Unknown entries wait for the cold fill.** `UNKNOWN` entries stay in the backlog and block the head drain until the public cold fill (`RegionCacheTask` -> `LocationGenerator` -> `PregenTask`, single-flight per region) takes the oldest one (`pollOldest(UNKNOWN)`) in place of a shape draw. The fill checks it through the existing speculative path: region-file read, else a native load (pinned when a kept slot is free, otherwise unpinned and validated on load; ADR-110). Under heap pressure the load is refused and the entry returns to the backlog as `UNKNOWN`. Because the fill takes one candidate at a time, a region file full of ungenerated chunks never turns into a burst of generation requests.
5. **Shape draws only without a backlog.** The fill draws from the shape when the backlog is disabled or empty. When the backlog holds entries but none is `UNKNOWN`, the run ends without a draw (`drained/backlog`, not counted as a failure). Per-player and observational fills keep drawing from the shape.
6. **Time-bounded refill.** Unverified picks are shape math only (no chunk I/O, ~100 B each), so refill runs toward `backlogCacheCap` each pulse with no count window. It stops when elapsed time plus the EWMA cost of one pick (7/8 decay, as in `TimeBoundTaskPipe`) would pass `Region.refillDeadline`: the remaining pulse budget (`asyncAllottedTime`) less a fixed 20% headroom (`Region.BACKLOG_HEADROOM`) for later stages and other plugins. The refill hysteresis (`backlogRefillThreshold`) still applies. Splitting this budget across Folia region threads is out of scope here.
7. **Pregenerated preference applies once.** Backlog candidates already passed `pregeneratedPreference` at refill; the fill does not draw it again.
8. **Promotion to hot is the full check for cold entries.** Cold-to-hot promotion already loads the chunk. After the vertical adjustor finds the real Y on it, `Region.promotionChecks` runs the world border, the biome filter, the safety-radius scan (`SafetyScan`; neighbours from resident chunks or region files, native load only on a miss) and claims (`GlobalRegionVerifiers`, async). A failure drops the location, purges its database row and records the cause. Validated backlog entries and stored locations used to reach hot with the vertical adjustor as their only check (S-001, S-003). Entries from the cold fill pass these checks twice; on the already loaded chunk the repeat costs block reads and a claim lookup, not a load.

Persistence is unchanged: backlog entries never reach the database.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Make L2 itself ~10× larger | Defeats the purpose: every L2 entry is fully verified (chunk I/O), so a larger L2 means proportionally more chunk loads, not fewer. The point of L3 is to *avoid* most of those loads via anvil pre-filtering. |
| Re-order L3 by bin to verify bin-at-a-time without head-blocking | Violates user requirement *"without changing their order in queue"*. Order preservation is the contract that makes L3 indistinguishable from a virtual L2 from the consumer's perspective; reordering would change spatial distribution semantics established by ADR-001 / ADR-009. |
| Verify all bins per pulse (no per-pulse cap) | Unbounded per-pulse work would violate the count-bound pipeline contract on Folia (ADR-004 / ADR-015). One bin per pulse keeps the work amortized and predictable. |
| Use the full pipeline (chunk + vert + safety) for L3 verification | Negates the cost win — L3 then duplicates L2's work. Anvil-only verification is a cheap *rejection filter*; only entries without disk data go through the cold fill's chunk-load check (2026-10-13 amendment). |
| Let the backlog load or generate chunks it cannot read from disk | One region-file batch would issue a generation request per ungenerated chunk at once. The cold fill already takes one candidate at a time, so it owns every chunk load. |
| Copy loaded chunks into detached snapshots for unpinned checks | Duplicates the region-file chunk view the anvil subsystem already provides; an unpinned load validated on load needs no copy (ADR-110). |
| Pace refill by a count window of unverified entries | Throttled a near-free step: 4 picks per region pulse at the default 20-tick `period` took ~4 minutes to reach 1000, which read as hidden verification work. Time-bounded refill reaches the cap in milliseconds; the reported count is gated by validity instead. |
| Count `UNKNOWN` entries as validated | Without a provider (or on ungenerated land) every pick reported as verified stock, so the backlog count meant nothing. |
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
  not yet flushed to disk, entries go `UNKNOWN` and the cold fill checks
  them with a chunk load (2026-10-13 amendment), so L3 never stalls on
  freshly generated worlds and never reports them as verified.

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
| Verify | new per-pulse step: pick this region's oldest-`UNVERIFIED` entry → look up world bin → anvil pre-filter every entry in the bin list → set validity (`VALIDATED` / `UNKNOWN` / `INVALIDATED`, 2026-10-13) |
| Promote | `Region.drainValidatedToCold` drains `VALIDATED` entries into cold; the cold fill (`RegionCacheTask` -> `PregenTask`) takes `UNKNOWN` entries through `BacklogLocationBuffer.pollOldest(UNKNOWN)`; `Region.promotionChecks` completes the check at cold-to-hot promotion (2026-10-13) |
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

## Amendment — Optional Memory Limits and Memory Cost Models

Cap settings (`activeChunkCap`, `cacheCap`, `backlogCacheCap`) support memory limits as an alternate bounding expression alongside basic counts:
- **Hot cache (`activeChunkCap`)**: Cost model is ~1 MiB per entry (active chunk ticket keeping the chunk loaded in memory). A limit of `64MiB` resolves to 64 chunks.
- **Cold cache (`cacheCap`)**: Cost model is ~128 bytes per entry (pre-verified `RTPLocation` POJO). A limit of `1MiB` resolves to 8,192 entries.
- **Backlog cache (`backlogCacheCap`)**: Cost model is ~128 bytes per entry (`BacklogEntry` candidate POJO). A limit of `2MiB` resolves to 16,384 entries.

When a data size unit (e.g. `64MB`, `256KiB`, `1GiB`) is specified, capacity is derived from `floor(memoryBytes / bytesPerEntry)`. Numeric values without data size units fall back to basic count.
