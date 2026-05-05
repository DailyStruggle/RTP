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

### 2026-05-03 — Pre-existing unresolved Javadoc links in `rtp-anvil`

- **Discovered during:** comment-stripping triage pass (analyzer top-15 offenders)
- **Location:** `rtp-anvil/src/main/java/io/github/dailystruggle/rtp/anvil/AnvilPrefilter.java` — class Javadoc and `probeSync` Javadoc
- **Symptom / hypothesis:** Javadoc references `{@link #probe(World, int, int, Set)}` and `{@link PaletteNormalizer#reconcileAll}`, but neither `org.bukkit.World` nor `PaletteNormalizer` is importable from `rtp-anvil` (zero-dep module per ADR-016). The references were already in the original (pre-trim) file and resolve to nothing.
- **Impact:** Javadoc link warnings only; no runtime effect, no compile failure. Slightly misleading IDE navigation.
- **Suggested next step:** replace with prose ("the asynchronous {@code probe} method" / "the platform reconciler") or delete the broken anchors. Two-line fix.

### 2026-05-03 — `effects-api` `SoundEffect` unparseable on MC ≥ 1.21.3 — RESOLVED 2026-05-03

- **Discovered during:** Folia 1.21.11 demo of `rtp.effect.*` permission nodes.
- **Location:** `effects-api/src/main/java/io/github/dailystruggle/effectsapi/Effect.java` (`str2Obj`); `effects-api/.../LocalEffects/SoundEffect.java` (default seed).
- **Resolution (2026-05-03):** `Effect.str2Obj` now special-cases `Sound`, mapping legacy underscored names (`ENTITY_ENDERMAN_TELEPORT`) and namespaced keys (`minecraft:entity.enderman.teleport` / `entity.enderman.teleport`) to a `Registry.SOUNDS.get(NamespacedKey)` lookup, with a reflective `Sound.valueOf` fallback for pre-1.21.3 enum builds. `SoundEffect.defaultSound()` replaces the enum-only `Sound.values()[0]` seed: tries reflective `values()`, then iterates `Registry.SOUNDS`, then resolves `minecraft:entity.enderman.teleport`. `:effects-api:compileJava` and `:effects-api:test` BUILD SUCCESSFUL.

### 2026-05-03 — `effects-api` `PotionEffect.run` violates Folia threading — RESOLVED 2026-05-03

- **Discovered during:** Folia 1.21.11 demo of `rtp.effect.*` permission nodes.
- **Location:** `effects-api/src/main/java/io/github/dailystruggle/effectsapi/LocalEffects/PotionEffect.java`.
- **Resolution (2026-05-03):** `PotionEffect.run` now routes every `Player.addPotionEffect(...)` through a new `applyOnEntityThread(Player, PotionEffect)` helper. The helper invokes `Player#getScheduler().run(plugin, task, retired)` reflectively when present (Folia / modern Paper EntityScheduler) and falls back to `Bukkit.getScheduler().runTask` otherwise. Reflection keeps `effects-api` free of a Folia compile-time dependency. `:effects-api:compileJava` and `:effects-api:test` BUILD SUCCESSFUL.

### 2026-05-03 — `effects-api` `FireworkEffect.run` uses non-Folia scheduler — RESOLVED 2026-05-03

- **Discovered during:** Folia 1.21.11 demo of `rtp.effect.*` permission nodes.
- **Location:** `effects-api/src/main/java/io/github/dailystruggle/effectsapi/LocalEffects/FireworkEffect.java`.
- **Resolution (2026-05-03):** `FireworkEffect.run` now branches on a `RegionizedServer` class-probe. On Folia it dispatches to `Bukkit.getRegionScheduler().run(plugin, location, Consumer)` (resolved reflectively to avoid a Folia compile-time dependency) and spawns via the new `spawnFirework(Location)` helper. On Spigot/Paper the existing `Bukkit.isPrimaryThread()` / `Bukkit.getScheduler().runTask(...)` path is preserved. `:effects-api:compileJava` and `:effects-api:test` BUILD SUCCESSFUL.

### 2026-05-03 — `EVENTS_AND_EFFECTS.md` documents wrong types for SOUND / NOTE arguments

- **Discovered during:** Folia 1.21.11 demo of `rtp.effect.*` permission nodes.
- **Location:** `docs/admin/EVENTS_AND_EFFECTS.md` Part 1 — argument tables for `SOUND` and `NOTE`; example block lines 144 and 156.
- **Symptom / hypothesis:** (a) `NOTE` table claims `TONE` is a letter (`A`–`G`), but `NoteEffect`'s default for `NoteTypeNames.TONE` is `Integer 0` (line 31), and `Effect.str2Obj` therefore calls `Integer.parseInt("A")` → `NumberFormatException`. Tones are integers `0–24`. (b) `SOUND` examples use legacy enum names (`ENTITY_ENDERMAN_TELEPORT`, `BLOCK_ANVIL_LAND`, `BLOCK_ENCHANTMENT_TABLE_USE`) that no longer parse on MC 1.21.3+ (see `Sound`-registry entry above). (c) `FIREWORK` example trailing booleans (`…true.true.true`) hit `Float.parseFloat("TRUE")` because at least one preceding positional default is `Float`, breaking the documented column order.
- **Impact:** Operators following the doc see `NumberFormatException` / `unexpected input` warnings on every teleport stage they granted; no effect plays. Increases support load and erodes trust in the doc.
- **Suggested next step:** correct the `NOTE` `TONE` column to `int 0–24` with a small lookup table for common tones; add a "MC ≥ 1.21.3" warning to the `SOUND` row pending the registry fix; verify the `FIREWORK` argument order matches `FireworkEffect.setData(...)` actual positional reads and either fix the doc or fix the parser. Cross-link this entry from the doc when corrected.

<!-- Append new entries above this comment, newest first. -->

## Resolved

<!-- Move entries here (or delete) once addressed. Keep last 10 for context. -->
