# ADR-022 — Region Shape Cache Key: Seed + Canonical Config Hash

**Status:** Accepted
**Date:** 2026-04-30
**Implemented:** 2026-04-30

## Context

Region shape data (the per-cell "known / known-bad" bitmap maintained by `MemoryShape` and persisted by `ScanTask` and the `scan*` commands) is currently written to disk under the filename pattern:

```
<regionName>_<worldSeed>.bin
```

Call sites:

- `rtp-core/.../selection/region/Region.java:718` — runtime save.
- `rtp-core/.../selection/region/RegionConfigLoader.java:119` — startup load.
- `rtp-core/.../tasks/ScanTask.java` — six save points during scans.
- `rtp-core/.../commands/scan/Scan{Start,Pause,Reset}Cmd.java` — operator-driven saves.

Cached `RTPLocation` rows in the H2/SQLite database (see [ADR-002](ADR-002-h2-sqlite-over-flat-file-cache.md)) carry the world seed and are invalidated on mismatch in `Region.hydrateCacheFromDatabase` (`Region.java:238`).

The seed-only key correctly invalidates the cache when a world is regenerated under a different seed. It does **not** invalidate when an admin edits configuration values that change either:

1. **The Archimedean spiral 1D→2D mapping** (see [ADR-001](ADR-001-archimedean-spiral-1d-mapping.md)) — i.e., which world chunk corresponds to cell index *N*. Examples: `centerX`, `centerZ`, `minRadius`, `maxRadius`, the shape class itself (`Circle`, `Square`, `Rectangle`, `*_Normal`), and shape-specific parameters (rectangle width/length, normal sigma).
2. **The validity predicate** that decided a cell was "bad" — i.e., whether a cell that was previously rejected would still be rejected today. Examples: biome whitelist/blacklist, `safety.yml` fields (`unsafeBlocks`, `platform`, `requireSkyLight`, etc.), the active vertical adjustor and its bounds, world-border respect.

After such an edit the spiral marches over indices whose cached "bad" flags refer either to the wrong world chunk (geometry change) or to a stale validity rule (predicate change). The DB seed-mismatch guard at `Region.java:238` does not help here — it only protects cached `RTPLocation` rows, not the per-cell shape bitmap.

In practice this is masked because most operators rarely edit region geometry post-deployment. It remains a latent correctness hazard and is the right thing to fix before it surfaces as bug reports of the form "RTP teleported a player into lava after I edited `safety.yml`".

## Decision

> **Implementation note (2026-04-30).** The accepted scope is narrower than the original proposal: the validity-defining `safety.yml` keys and biome whitelist/blacklist are **deferred** to a follow-up. The hash inputs cover seed plus shape (class + parameters) plus vertical adjustor (class + parameters) plus `SCHEMA_VERSION`. The DB schema is **not** migrated; instead the existing `rtp_cached_locations.seed BIGINT` column is repurposed to carry the 64-bit truncation of the same hash. Pre-release alpha/beta does not migrate or preserve old data — orphan files and rows are abandoned. Region hydration is deferred onto the async scheduler.

Replace the cache key with a composite of the world seed and a stable hash of every configuration input that influences shape geometry or candidate validity:

```
<regionName>_<seed>_<cfgHash>.bin
```

(Equivalently `<regionName>_<cfgHash>.bin` if `seed` is folded into the hash input. The two-part form is preferred for human readability and easier orphan cleanup.)

`cfgHash` shall be computed as `SHA-256(canonicalSerialization(inputs))`, truncated to the first 12 hex characters. The hash inputs shall be:

1. `world.getSeed()`.
2. The shape class FQCN.
3. All numeric and enumerated shape parameters affecting the spiral mapping or bounds: `centerX`, `centerZ`, `minRadius`, `maxRadius`, plus shape-specific extras (`Rectangle` width/length, `*_Normal` sigma, etc.).
4. The vertical adjustor FQCN and its parameters (e.g. `LinearAdjustor` min/max Y).
5. Validity-defining configuration: biome whitelist/blacklist (sorted), the relevant `safety.yml` fields, world-border bounds when respected by the region.
6. A `SCHEMA_VERSION` integer, bumped whenever a new validity-affecting field is added so that older caches auto-invalidate on upgrade.

`spatialResolution` shall **not** be a hash input. It is the run-length coalescing distance applied when new marks are folded into the learned bad-run array; it does not participate in the cell-index→chunk function, so a stored run array remains addressed against the same geometry whichever value produced it. Reading data coalesced at a coarser resolution under a finer one yields a valid, merely conservative, superset — some cells inside a merged run were never individually rejected, which costs a little candidate area and nothing else — and the reverse direction only changes how subsequent marks merge. Neither direction can admit a cell the validity predicate would reject, so neither is a safety concern under the safety-first invalidation principle above. Operators wanting the finer precision retroactively may rescan; that is a precision choice, not an invalidation requirement.

Canonicalization rules:

- Map entries serialized in sorted key order, lower-case, trimmed.
- Use a stable serializer (e.g. `key=value;` joined). Never `Map.toString()` — its order is undefined for non-`LinkedHashMap` instances.
- Lists serialized in sorted order with a stable separator.

The same `cfgHash` shall be added as a column to the database `cached_locations` schema and applied as an additional invalidation predicate alongside the existing seed check at `Region.java:238`. The `ScanTask` progress file (currently keyed by region name only via `ScanTask.loadProgress`) shall also include `cfgHash` in its path so that progress counters cannot resume into a freshly invalidated shape.

When `Region.setSettings(...)` (`Region.java:209`) detects a `cfgHash` change relative to the loaded shape, the in-memory shape state shall be dropped and a fresh `ScanTask` started rather than overwriting the existing `MemoryShape` with stale flags.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Keep the seed-only key; document the limitation | Leaves a correctness hazard: stale "bad" flags can hide currently-valid cells, or worse, allow currently-invalid cells through if the validity rule was relaxed. Unsafe by default. |
| Hash the entire `regions.yml` block verbatim | Trivially defeats the cache on cosmetic edits (whitespace, comment edits, message strings). Forces unnecessary full re-scans on large regions. |
| Track a monotonic `configRevision` counter that operators bump manually | Relies on operator discipline. Silent failure when forgotten. The whole point of the cache is to be safe without human intervention. |
| Detect specific config edits at runtime and patch the in-memory shape incrementally | Fragile: requires per-field invalidation logic for every shape and adjustor variant. Does not help with cold-start loads from disk. |
| Embed the hash *inside* the `.bin` payload instead of the filename | Requires opening every candidate `.bin` to test for staleness; complicates orphan detection. Filename-based keying is cheaper and self-describing. |

## Consequences

- **Positive:**
  - Eliminates a class of latent correctness bugs where stale shape flags survive validity-affecting config edits.
  - Single, principled invalidation point — no per-field bespoke logic.
  - `SCHEMA_VERSION` bumps give a forward-compatible upgrade path when new validity fields are added.
  - Operators can edit cosmetic config (messages, scan rates, queue caps) without losing the shape cache.

- **Negative / Trade-offs:**
  - Every validity-affecting config edit now triggers a full re-scan. For regions with `maxRadius > 50000` this is non-trivial. A `--keep-shape` operator opt-out may be added later for advisedly cosmetic-only edits, but the default must be safety-first invalidation.
  - The boundary between "validity-affecting" and "cosmetic" must be drawn explicitly and maintained. A unit test enumerating every config key and asserting whether it participates in the hash will keep this honest over time. `spatialResolution` is the instructive case: it is neither cosmetic nor validity-affecting, but a storage-precision knob over already-collected data, and hashing it would force a full re-scan for no correctness gain.
  - Database schema migration is required (`cached_locations` gains a `cfgHash` column). Old rows must be invalidated on first load post-upgrade.
  - Existing `<regionName>_<seed>.bin` files become orphans. Either delete on first load when no `<regionName>_<seed>_<cfgHash>.bin` exists, or ship a one-shot pruner. To be documented in `CHANGELOG.md`.

## References

- [ADR-001](ADR-001-archimedean-spiral-1d-mapping.md) — spiral 1D mapping; the cell-index→chunk function whose stability this ADR protects.
- [ADR-002](ADR-002-h2-sqlite-over-flat-file-cache.md) — persistence layer; the DB schema affected by this change.
- [ADR-009](ADR-009-configurable-spatial-distributions.md) — configurable distributions; their parameters are part of the hash input set.
- `rtp-core/.../selection/region/Region.java` (`hydrateCacheFromDatabase`, `setSettings`, runtime save call site).
- `rtp-core/.../selection/region/RegionConfigLoader.java` — startup load call site.
- `rtp-core/.../tasks/ScanTask.java`, `commands/scan/Scan*Cmd.java` — save call sites.
- `docs/dev/POTENTIAL_BUGS.md` — backlog entry recording the latent hazard this ADR addresses.
