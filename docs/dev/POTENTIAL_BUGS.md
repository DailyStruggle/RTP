# Potential Bugs Backlog

A queue of incidental discoveries — suspected bugs, latent races, missing validations, stale comments — that were spotted while working on an **unrelated** task and deliberately **not** fixed in-line, per the *Stay-On-Task Policy* in [`.junie/AGENTS.md`](../../.junie/AGENTS.md).

This file is a backlog, not a tracker. Promote an entry to a real issue (or fold it into a future task's `Effective Issue`) when it is ready to be worked on. Strike entries through (`~~…~~`) or remove them once resolved.

## How to add an entry

Append to the *Open* section below using the template. Keep entries short — one paragraph each. If a deeper analysis is warranted, link to a separate doc rather than inlining it here.

### Template

```markdown
### YYYY-MM-DD — <short title>

- **Discovered during:** <issue ref / short task description>
- **Location:** `<path/to/File.java>` line <N> (or symbol name)
- **Symptom / hypothesis:** <one or two sentences>
- **Impact:** <user-visible effect, best guess>
- **Suggested next step:** <minimal investigation or fix sketch>
```

## Open

### 2026-04-30 — Region shape cache key (geometry/vert subset) — RESOLVED via ADR-022
- ~~Discovered during: discussion on tying region shape data to a hash of seed plus configurable values that change location validity or spiral start.~~
- **Resolution:** [ADR-022](../adr/ADR-022-shape-cache-key-seed-plus-config-hash.md) implemented 2026-04-30. `.bin`/`.scan` files are now keyed `<regionName>_<seed>_<12hex>.bin`; the legacy `rtp_cached_locations.seed BIGINT` column carries the 64-bit truncation of the same hash so no schema migration was needed. `Region.setSettings(...)` deletes the stale on-disk artefacts when the hash changes.
- **Follow-up still open:** `safety.yml` validity fields (`unsafeBlocks`, `platform`, `requireSkyLight`, tag/state predicates) and biome whitelist/blacklist are **not** yet folded into the hash. A separate entry below tracks that gap.

### 2026-04-30 — Region cache hash does not yet cover safety.yml / biome filters
- **Discovered during:** ADR-022 implementation; deferred per scope decision.
- **Location:** `rtp-core/.../selection/region/RegionCacheKey.java` — `canonicalize(...)` only consumes shape and vertical-adjustor data.
- **Symptom / hypothesis:** edits to `safety.yml` (`unsafeBlocks`, `platform`, `requireSkyLight`, ADR-017 tag/state predicates) or to a region's biome whitelist/blacklist still do not invalidate the persisted shape data. Stale "bad" flags can survive a validity-rule tightening or relaxation.
- **Impact:** narrower than the original report (geometry edits are now caught), but a tightened safety rule that the cache silently ignores remains possible. Likely rare; admins seldom flip safety predicates post-deployment.
- **Suggested next step:** extend `RegionCacheKey.canonicalize(...)` to fold sorted `safety.yml` validity keys and the region's biome lists, and bump `SCHEMA_VERSION`. A unit test enumerating each candidate config key (in or out of the hash) keeps the boundary honest.

<!-- Append new entries above this comment, newest first. -->

## Resolved

<!-- Move entries here (or delete) once addressed. Keep last 10 for context. -->
