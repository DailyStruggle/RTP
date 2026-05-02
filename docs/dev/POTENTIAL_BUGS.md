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

### 2026-05-01 — `RTP.redisManager` field hard-references `RedisManager` in `rtp-core` (ADR-024 lite hazard) — RESOLVED 2026-05-01
- **Discovered during:** ADR-024 lite assembly wiring (Phase A, helper extraction).
- **Location:** `rtp-core/src/main/java/io/github/dailystruggle/rtp/common/RTP.java` line 145 (field declaration), 187/196 (construction), 607–609 (shutdown). The lite shadow excludes `io/github/dailystruggle/rtp/common/network/Redis*.class`.
- **Symptom / hypothesis:** Even though `RedisManager` is only constructed when network YAML enables it (and lite never enables it), the field type and `instanceof`/method-ref linkage in `RTP.java` mean any class-loader that resolves `RTP.class` must be able to find `RedisManager.class`. Lite drops `RedisManager` outright, so on any verifier-strict JVM the first `RTP.class` resolve risks `NoClassDefFoundError`. In practice HotSpot's lazy linking masks this until a code path touches `redisManager` — but `RTP.stop()` line 607 (`if (instance.redisManager != null)`) does, on every shutdown, force the field-type class to resolve.
- **Impact:** lite shutdown could throw `NoClassDefFoundError: …/network/RedisManager` after the regression first manifests on a strict JVM (post-Java 21 verifier tightening, or with `-Xverify:all`). Today's HotSpot tolerates the missing class because `redisManager` is always null in lite.
- **Resolution (2026-05-01):** introduced `io.github.dailystruggle.rtp.common.network.RTPNetworkManager` interface; `RedisManager implements RTPNetworkManager`; renamed/retyped `RTP.redisManager` → `RTP.networkManager` (interface type). Construction is now reflective via `RTP.createRedisNetworkManager` (`Class.forName("…RedisManager")`), so the only symbolic reference to `RedisManager` in `rtp-core/RTP.java` lives inside a string literal. Verified via `:rtp-core:test`, `:rtp-plugin:test`, and `:rtp-plugin:liteJarStructureCheck` — all BUILD SUCCESSFUL. `DatabaseAccessor` was reviewed and confirmed not adaptable for this role (no TTL primitive, no pub/sub, batched-query lifecycle vs. long-lived async subscriber).

### 2026-05-01 — Two ADR files share the number ADR-022
- **Discovered during:** Fabric multiversion support work (ADR-027 drafting).
- **Location:** `docs/adr/ADR-022-shape-cache-key-seed-plus-config-hash.md` and `docs/adr/ADR-022-fabric-platform-in-scope.md`.
- **Symptom / hypothesis:** Both files exist with the same `ADR-022-` prefix, so any cross-reference written as just "ADR-022" is ambiguous. `AGENTS.md`, `MULTI_PLATFORM_PLAN.md`, and `rtp-fabric/**/build.gradle` comments all use "ADR-022" to mean the Fabric-in-scope decision; the shape-cache ADR has the same number.
- **Impact:** Documentation/navigation only — no runtime effect. Search-by-number returns two hits; future ADRs that supersede "ADR-022" must disambiguate.
- **Suggested next step:** Renumber one of them (likely the shape-cache ADR, which has narrower external references) to the next free slot, update its filename, internal `ADR-NNN` line, and any in-repo links. Out of scope for the current Fabric multiversion task.

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
