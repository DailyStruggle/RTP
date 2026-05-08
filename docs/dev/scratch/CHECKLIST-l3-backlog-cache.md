# Checklist — L3 Backlog Cache (ADR-028) Implementation

**Effective Issue:** Implement the L3 backlog cache (`backlogLocations`) per [ADR-028](../../adr/ADR-028-l3-backlog-cache.md), now **Accepted** (2026-05-07).
**Mode:** `[CODE]` (multi-module — D-005 already cleared by the accepted ADR for the design itself; sub-decisions still gated).
**Source of truth:** ADR-028 *Implementation map* and *Follow-ups* sections.

> Tick a box only after the step is verified (compile + test green, file saved, command succeeded). Re-emit this list in `<UPDATE>` each step. Delete this file once the task is submitted.

---

## Phase 0 — Pre-flight

- [x] 0.1 Re-read ADR-028 end-to-end — no new open questions; *Implementation map* and *Follow-ups* sections remain authoritative.
- [x] 0.2 Anvil API surface confirmed (`rtp-anvil/AnvilPrefilter`): `probeSync(worldFolder, dimensionSubpath, cx, cz, rawUnsafeBlocks)` → `Verdict` is the per-entry primitive intended to run on `AnvilIoPool`; `probeSyncDetailed` reuses an internal `AnvilRegionByteCache` so all entries that fall in the same `.mca` bin amortize a single region-file read. No blocker; no D-005 sub-decision needed.
- [x] 0.3 Buffer decision: **new `BacklogLocationBuffer`** (per ADR-028). Rationale: existing `LockFreeLocationBuffer` is a flat FIFO with `offer/poll/peek/clear/get/set/size/isEmpty` only — it has no per-entry `verified` flag, no head-contiguous-verified poll, and no oldest-unverified peek. Extending it would overload its single-purpose contract used by L1/L2/login; a sibling type keeps invariants clean.

## Phase 1 — Config & keys (no behavior change yet)

- [x] 1.1 `RegionKeys.backlogCacheCap` enum constant added (between `cacheCap` and `activeChunkCap`).
- [x] 1.2 `backlogCacheCap` (long) added to `RegionSettings` record + javadoc; default `1000L` plumbed through `RegionConfigLoader`, `Region` (both rebuild sites), `SelectionAPI`, and 12 test files (22 callsites updated; user-approved Path A).
- [x] 1.3 `%rtp_backlogCacheCap%` placeholder documented in `docs/admin/COMMANDS.md`; resolution is generic via `RegionKeys` enum, so adding the enum constant is sufficient at the code level.
- [x] 1.4 `rtp-plugin/src/main/resources/regions/default.yml`: `backlogCacheCap: 1000` added with ADR-028 comment.
- [x] 1.5 `rtp-plugin/src/main/resources/lang/regions.lang.yml`: identity row added.
- [x] 1.6 Lite override added at `rtp-plugin/src/lite/resources/regions/default.yml` (full-file overlay via Shadow `DuplicatesStrategy.EXCLUDE`); `backlogCacheCap` line is **intentionally omitted** so lite users never see the knob (per user direction 2026-05-07: "ship lite without the config setting"). Runtime fallback in `RegionConfigLoader` still resolves to `1000L` — out of scope per user "do not modify code". See *Notes on lite runtime default* below.
- [x] 1.7 Round-trip verified at unit-test level: `:rtp-core` suite 503/503 green. Live-server `/rtp info` deferred to Phase 7.

## Phase 2 — Buffer type & bin index

- [x] 2.1 New value type `RegionFileCoord` (record `(String worldName, int rx, int rz)` with `of(RTPCoords) -> block>>9`) in `rtp-core`. World name included to avoid cross-world bin collisions.
- [x] 2.2 New `BacklogLocationBuffer` in `rtp-core` — order-preserving FIFO over `ArrayDeque`, capacity-bounded by `backlogCacheCap`. Nested `BacklogEntry { RTPLocation loc; volatile Validity validity; Object pinnedBinList; }`; nested `Validity ∈ { UNVERIFIED, VALIDATED, INVALIDATED }`.
- [x] 2.3 Operations: `offerUnverified(loc) -> BacklogEntry|null` (capacity rejection, no eviction), `BacklogEntry.setValidity(Validity)`, `pollContiguousValidatedHead(maxN)` (drops `INVALIDATED` head, stops on `UNVERIFIED`), `peekOldestUnverified`, `clear`, `size`, `validatedSize`, `isEmpty`, `capacity`.
- [x] 2.4 New `WorldBacklogBinIndex` (world-scoped holder; one instance per world managed externally — keyed by world name via `RegionFileCoord`): `ConcurrentHashMap<RegionFileCoord, WeakReference<List<BacklogEntry>>>`. Each entry is pinned via `BacklogEntry.pinBinList` so the bin survives while *any* contributing entry is live; bin becomes GC-eligible once every contributor is unreachable.
- [x] 2.5 Insertion path: `Region.execute()` refill adds the new entry to *both* `backlogLocations` (storage) and the world bin index (lookup index). Implemented in Phase 3 step 3.2.1 (`Region.processBacklog`).
- [x] 2.6 Thread-safety: buffer is single-writer from `Region.execute()`; world bin index uses `ConcurrentHashMap` + `synchronized(list)` for per-bin mutation and snapshot-copy reads; `volatile` validity for cross-thread visibility.
- [x] 2.7 Unit tests: `BacklogLocationBufferTest` (14 cases: order preservation, head-blocking on UNVERIFIED, INVALIDATED-head drop, capacity rejection, peekOldestUnverified, validatedSize, clear-preserves-tags, null/negative-arg rejection, maxN=0 side effect) + `WorldBacklogBinIndexTest` (7 cases: insert, cross-region shared bin, snapshot-of-unknown, validity-visibility-via-snapshot, clear, weak-ref GC smoke, reapStale no-op). All 21 new + 503 existing = 524/524 green via `run_test` over `rtp-core/.../selection/region`.

## Phase 3 — Wire into `RegionQueueManager` / `Region`

- [x] 3.1 Added nullable `RegionQueueManager.backlogLocations` (instantiated only when `backlogCacheCap > 0`); also added static `RegionQueueManager.binIndexFor(worldName)` registry of `WorldBacklogBinIndex` instances shared per world.
- [x] 3.2 Extended `Region.execute()` pulse via private `processBacklog(availableTime, startNanos)` called immediately after the dormant-world guard and before the L1 deficit loop — three steps inline (no producer/consumer split):
  - [x] 3.2.1 Refill L3 via `shape.select()` only (S-005 safe — chunk coords → `(c<<4)+8` block coords; Y = vert mid); time-sliced at `availableTime/4`; insert into both `BacklogLocationBuffer` and the world bin index.
  - [x] 3.2.2 `peekOldestUnverified()` → `RegionFileCoord.of(coords)` → `binIndex.snapshot(key)`.
  - [x] 3.2.3 Run `RTPHooks.anvilPrefilter().current().classify(world, cx, cz)` on every `BacklogEntry` in the snapshot (cross-region amortization). `ACCEPT/UNKNOWN`/(no provider) → `VALIDATED`; `REJECT` → `INVALIDATED`.
  - [x] 3.2.4 `pollContiguousValidatedHead(L2-free)` → `unkeptLocations.offer(...)`; mid-drain L2-full halts the rest (acceptable per checklist note).
- [x] 3.3 L2 deficit loop confirmed untouched (insertion is a self-contained block before line 386).
- [x] 3.4 Fallback wired: null hooks/provider, classify exceptions, `null` decision, and `UNKNOWN` all map to `VALIDATED` so L3 never stalls when no Anvil prefilter is bound.

## Phase 4 — Lifecycle integration

- [x] 4.1 `RegionQueueManager.shutDown` calls `backlogLocations.clear()` (null-guarded). No DB callbacks are attached to L3 (it has no persistence by design — ADR-028); no reservations to close (entries are shape-only, no chunk tickets). World-level `WorldBacklogBinIndex` becomes GC-eligible automatically once the per-region buffer is cleared (weak refs + strong-pin discipline).
- [x] 4.2 Active-GC sweep is **N/A** for L3 by construction. The ADR-008 sweep in `MemoryTracker` targets *chunk-ticket* leaks (orphaned `activeChunkTickets` vs. `keptLocations` reservations); L3 entries hold no chunk tickets, no reservations, and no `TeleportPipelineTask`. There is nothing the sweep can release. Capacity-bounded `BacklogLocationBuffer` + tri-state head-blocking + per-pulse drain provides the only liveness guarantee L3 needs; stale `INVALIDATED` heads are dropped on the next pulse's `pollContiguousValidatedHead`.
- [x] 4.3 `MemoryTracker.run()` (lines ~214–223): `totalCacheCap += settings.backlogCacheCap()`; `totalLocationQueueSize += backlogLocations.size()` (null-guarded). The diagnostic `[RTP] Diagnostic: Locations=[Queue:{0}/{1}, …]` line now reflects L3 in the Queue/Cap totals.
- [x] 4.4 Confirmed by inspection: `Region.processBacklog` step 1 builds `RTPLocation(coords, 0L)` with no reservation, no `MemoryTracker.track(...)`, no `TeleportPipelineTask` allocation. Pure shape-only producer (S-005 safe). Step 2 calls `provider.classify(world, cx, cz)` (Anvil prefilter is by-spec NBT-only, no chunk load). Step 3 just hands the `RTPLocation` to `unkeptLocations.offer` exactly like the existing pipeline does — chunk-ticket allocation, if any, happens later in the kept-promotion path, not in L3.

## Phase 5 — Persistence (negative confirmation)

- [x] 5.1 Verified by inspection: `RegionQueueManager.installDatabaseCallbacks` (lines 118–148) only attaches add/remove callbacks to `keptLocations` and `unkeptLocations`. `backlogLocations` is never passed to `setCallbacks(...)` and `BacklogLocationBuffer` does not expose a callback API. L3 is correctly **not** persisted, per ADR-028.
- [ ] 5.2 Test: start with backlog populated, restart server, assert no DB rows for backlog entries; spiral re-selects fresh entries.

## Phase 6 — Tests (REQ-traceable)

- [ ] 6.1 `BacklogCacheOrderPreservationTest` — verified-out-of-order entries do not advance past unverified head.
- [ ] 6.2 `BacklogCacheSingleBinPerPulseTest` — exactly one bin verified per `Region.execute()` pulse.
- [ ] 6.3 `BacklogCacheHeadBlockingTest` — unverifiable head stalls promotion until its bin runs.
- [ ] 6.4 Add a REQ-* row (or extend an existing one) in [`docs/dev/TRACEABILITY.md`](../TRACEABILITY.md) linking the new tests.
- [ ] 6.5 `.\gradlew :rtp-core:test --tests "*Backlog*"` green.
- [ ] 6.6 Full-module sanity: `.\gradlew :rtp-core:build` and `:rtp-spigot:build` green.

## Phase 7 — Docs & rollout

- [ ] 7.1 Update [`docs/dev/DESIGN.md`](../DESIGN.md) L3 row from *proposed* → *implemented*.
- [ ] 7.2 Update [`.junie/AGENTS.md`](../../../.junie/AGENTS.md) *Domain Analogies & Aliases* row: drop "Proposed", add the now-real `RegionQueueManager.backlogLocations` symbol path.
- [ ] 7.3 Add `CHANGELOG.md` entry under the unreleased heading — per *CHANGELOG Hygiene*, diff against the last released tag, not the working tree.
- [ ] 7.4 Admin docs: `docs/admin/CONFIGURATION.md` and `REGIONS.md` — document `backlogCacheCap`.
- [ ] 7.5 Resolve [`docs/dev/POTENTIAL_BUGS.md`](../POTENTIAL_BUGS.md) row "ADR-028 missing from `docs/adr/README.md`" by adding the index row.
- [ ] 7.6 Remove the *Proposed* hedge from any other docs that reference ADR-028 as a proposal (`TODO.md`, etc.).

## Phase 8 — Deferred / out of v1 scope

- [ ] 8.1 Localized lang files (de/es/fr/nl) — staged after English row lands.
- [ ] 8.2 Telemetry: `backlog-bin-hits` / `backlog-bin-rejects` under `AnvilPrefilterMetrics`, surfaced via `/rtp info`.
- [ ] 8.3 Auto-sizing shorthand `backlogCacheCapMode = MULTIPLIER × cacheCap`.
- [ ] 8.4 Fabric: revisit when `rtp-fabric-ADR-002` blockers (S-005 in `FabricWorld.getChunkAt`, anvil bridge) are cleared.

## Phase 9 — Submit

- [ ] 9.1 All Phase 1–7 boxes ticked or explicitly deferred under *Notes* in submit summary.
- [ ] 9.2 Delete this checklist file.
- [ ] 9.3 Submit.

---

## Open Questions

- (none yet — populate during Phase 0)

## Clarifications from user (2026-05-07 / 2026-05-08)

### ~~2026-05-07 — read-shim on poll~~ **SUPERSEDED 2026-05-08**

- ~~L3 sits next to L2, not upstream of it. `backlogLocations` and `unkeptLocations` are siblings; coupling is read-side via `unkeptLocations.poll()` consulting L3 first.~~ Superseded by the 2026-05-08 design below: promotion happens inline inside `Region.execute()` (head-contiguous-`VALIDATED` drain at the end of the pulse), not on the L2 poll path. ADR-028 *Refill loop* now reflects this.

### 2026-05-08 — world-shared bin index + tri-state validity (Q1–Q6 answers)

User answered the six Phase 2 design questions on 2026-05-08; recording verbatim so they survive history truncation:

- **Q1 (storage of truth).** → Per-RTP-region `BacklogLocationBuffer` is the storage. The world-level structure is a *weak-reference index* of bin lists (cross-RTP-region anvil amortization), not a second storage. Note: "region" overloaded — use `RegionFileCoord` for `.mca` keys, "RTP region" for the plugin concept.
- **Q2 (region queue element type).** → Insertion-ordered pair of `RTPLocation` + validity — either as a `BacklogEntry` record or by adding the validity tag to `RTPLocation` itself. Implementation chooses `BacklogEntry` to avoid mutating `RTPLocation`.
- **Q3 (multi-region sharing).** → Yes, shared bins. Two RTP regions targeting the same world contributing to the same `.mca` bin get verified in one anvil pass.
- **Q4 (cap accounting).** → `backlogCacheCap` caps the **per-RTP-region buffer** size. World-bin total is unbounded (transitively bounded by Σ region caps for that world).
- **Q5 (promotion target).** → At `Region.execute()`, drain head while validity is `VALIDATED`; stop at first `UNVERIFIED`; tri-state extended to add `INVALIDATED` (drop on head, do not stall). Promotes into `unkeptLocations`. *This supersedes the 2026-05-07 read-shim model.*
- **Q6 (ADR amendment).** → Necessary; performed 2026-05-08. Scope: tri-state validity replaces `verified` boolean; world-level weak-ref bin index added; promotion clarified as inline in `Region.execute()` (no producer/consumer framing); cross-region bin sharing documented. *Not* changed: per-region buffer-as-storage, order preservation, head-blocking semantics, single-bin-per-pulse, `backlogCacheCap` per-region scope, lite default 0, no DB persistence.

### 2026-05-08 reaffirmations (delta against my earlier amendment proposal)

- L3 is **not** reordered by bin (the existing alternatives-table entry stands as-is).
- Per-RTP-region `BacklogLocationBuffer` remains the sole storage — the bin index does *not* hold entries, only weak refs to lists of refs.
- Producer/consumer framing is irrelevant: refill, verify, drain all run linearly inside `Region.execute()`.
- Bin selection by *this region's* oldest-`UNVERIFIED` entry remains correct (the original framing was right; the optimization is the world-shared *list*, not the bin selector).

## Decisions Awaiting User Approval (D-005)

- ~~2026-05-07 — Phase 1 sub-decision: where does `backlogCacheCap` live in code?~~ **Resolved 2026-05-07: user approved Path A** (extend the `RegionSettings` record). 22 callsites updated; `:rtp-core` suite 503/503 green.
- ~~2026-05-07 — Phase 1.6 sub-decision: how does the lite assembly express `backlogCacheCap = 0` default?~~ **Resolved 2026-05-07: user directed "ship lite without the config setting" + "do not modify code, only configuration for lite".** Implemented as a full-file lite overlay at `rtp-plugin/src/lite/resources/regions/default.yml` that omits the `backlogCacheCap` key entirely. ADR-028's "lite default 0" framing is therefore reinterpreted as "lite has no user-visible knob"; the runtime default `1000L` from `RegionConfigLoader` still applies but is invisible to lite admins.

## Notes on lite runtime default (2026-05-07)

- The user's direction is **config-only**: hide `backlogCacheCap` from the lite YAML so non-pro users aren't confused.
- **Update 2026-05-07 (later same day):** user followed up with "update the code to use 0 if the value is not configured". `RegionConfigLoader.load` in-code fallback changed from `1000L` → `0L` (single-line edit at line 107). Combined with the lite YAML omission this now yields a true `backlogCacheCap = 0` runtime default on lite installs (L3 disabled, no allocation). Main jar still ships `backlogCacheCap: 1000` in `regions/default.yml`, so non-lite installs are unaffected — the explicit YAML value wins over the code fallback.
- Mechanical effect on lite: `ConfigParser.update()` (lines 880–887) auto-injects missing keys from the *jar's own* `default.yml` resource. The lite jar's overlay omits the key, so auto-fill cannot re-introduce it; `RegionConfigLoader` then falls through to the new `0L` default. End-to-end: lite admins see no knob, runtime allocates nothing for L3.
- `:rtp-core` test suite re-run: 503/503 green after the default change (no test fixture asserted on the prior `1000L` fallback).
