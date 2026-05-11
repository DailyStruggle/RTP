# Potential Bugs Backlog

A queue of incidental discoveries — suspected bugs, latent races, missing validations, stale comments — that were spotted while working on an **unrelated** task and deliberately **not** fixed in-line, per the *Stay-On-Task Policy* in [`.junie/AGENTS.md`](../../.junie/AGENTS.md).

This file is a backlog, not a tracker. Promote an entry to a real issue (or fold it into a future task's `Effective Issue`) when it is ready to be worked on. Once an entry is resolved, **delete it** — this file does not maintain a resolved-bug archive.

## What this file is — and is not

**Yes:** "I was doing X, I noticed Y looks broken, Y is *not* part of X, and I am walking away from Y. Recording it here so a future task can pick it up."

**No** — do not use this file for any of:

- Work you are doing or just finished as part of the current task. Use the `<UPDATE>` checklist, the `submit` summary, the commit message, and `CHANGELOG.md` for user-visible changes.
- A diary of your own fix attempts, build outputs, packaging chains, or per-session follow-ups. If you opened the entry and resolved it in the same session, **delete the entry** — it never belonged here. Do not annotate it with `**Resolved:**` / `**Follow-up:**` bullets.
- Durable engineering lore or repro recipes → [`LESSONS_LEARNED.md`](LESSONS_LEARNED.md).
- Roadmap items or deferred design → the relevant plan doc or an ADR.
- Session resumption state → your `<UPDATE>` checklist or `docs/dev/scratch/CHECKLIST-<slug>.md`.
- Test failures or CI noise from the current change → fix them or escalate; not here.

A correct entry describes **someone else's future problem** that the current task is choosing not to solve. If you catch yourself writing a multi-paragraph resolution log on an entry you authored this session, that is the misuse signature — remove the entry instead.

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


### 2026-05-03 — `EVENTS_AND_EFFECTS.md` documents wrong types for SOUND / NOTE arguments

- **Discovered during:** Folia 1.21.11 demo of `rtp.effect.*` permission nodes.
- **Location:** `docs/admin/EVENTS_AND_EFFECTS.md` Part 1 — argument tables for `SOUND` and `NOTE`; example block lines 144 and 156.
- **Symptom / hypothesis:** (a) `NOTE` table claims `TONE` is a letter (`A`–`G`), but `NoteEffect`'s default for `NoteTypeNames.TONE` is `Integer 0` (line 31), and `Effect.str2Obj` therefore calls `Integer.parseInt("A")` → `NumberFormatException`. Tones are integers `0–24`. (b) `SOUND` examples use legacy enum names (`ENTITY_ENDERMAN_TELEPORT`, `BLOCK_ANVIL_LAND`, `BLOCK_ENCHANTMENT_TABLE_USE`) that no longer parse on MC 1.21.3+ (see `Sound`-registry entry above). (c) `FIREWORK` example trailing booleans (`…true.true.true`) hit `Float.parseFloat("TRUE")` because at least one preceding positional default is `Float`, breaking the documented column order.
- **Impact:** Operators following the doc see `NumberFormatException` / `unexpected input` warnings on every teleport stage they granted; no effect plays. Increases support load and erodes trust in the doc.
- **Suggested next step:** correct the `NOTE` `TONE` column to `int 0–24` with a small lookup table for common tones; add a "MC ≥ 1.21.3" warning to the `SOUND` row pending the registry fix; verify the `FIREWORK` argument order matches `FireworkEffect.setData(...)` actual positional reads and either fix the doc or fix the parser. Cross-link this entry from the doc when corrected.


### 2026-05-05 — `docs/adr/README.md` Index missing rows for ADR-023, ADR-024, ADR-026, ADR-028

- **Discovered during:** ADR audit / subproject-ADR migration (this task).
- **Location:** `docs/adr/README.md` "Index" table.
- **Symptom / hypothesis:** Several existing ADRs (`ADR-023-login-reserve-cache.md`, `ADR-024-rtp-lite-assembly-variant.md`, `ADR-026-external-hook-api-surface.md`, `ADR-028-l3-backlog-cache.md`) are present on disk but never had a row added to the index table; the table jumps from ADR-022 straight to ADR-025. ADR-027 is now in `rtp-fabric/docs/adr/` and is captured under the new *Subproject ADRs* section, so it doesn't need a main-table row, but the four others do.
- **Impact:** Documentation/navigation only — search by ADR number still works, but the README claims to be the catalog and isn't. Risks future ADRs being added in the wrong slot or tools that scrape the table missing entries.
- **Suggested next step:** insert the four missing rows in the existing chronological table; double-check status (Accepted vs. Proposed) against each ADR file's header. Out of scope for the current ADR-relocation task.

### 2026-05-06 — Pre-existing unresolved Javadoc link `BuiltInRegistries#BIOME` in `V1_21_R1FabricVersionAdapter`

- **Discovered during:** ADR-007 (Mojmap-name decoupling) refactor — surfaced when linting the migrated adapter.
- **Location:** `rtp-fabric/rtp-fabric-v1_21_R1/.../V1_21_R1FabricVersionAdapter.java` line 51-52, class Javadoc: `{@link BuiltInRegistries#BIOME}`.
- **Symptom / hypothesis:** `BuiltInRegistries.BIOME` does not exist on 1.21.1 Mojmap (biome registry is accessed via the level's registry access, not a static `BuiltInRegistries` field). The Javadoc reference resolves to nothing. Pre-existed ADR-007 work — not introduced by the wrapper migration.
- **Impact:** Javadoc warning only; no compile failure, no runtime effect. Slightly misleading IDE navigation.
- **Suggested next step:** replace the broken `{@link …#BIOME}` with prose ("the biome registry, accessed via {@code level.registryAccess()}") or remove the bullet entirely. One-line fix.

### 2026-05-06 — `ReqApiArch005BrigadierBridgeTest` carries 2 unsatisfied assertions in HEAD adapter

- **Discovered during:** Fabric live `/rtp <TAB>` blank + `/rtp scan <TAB>` showing `default` instead of `region:` investigation.
- **Location:** `commands-api/src/test/.../ReqApiArch005BrigadierBridgeTest.java` — `parameterDispatchReconstructsWireFormat` (line 206) and `subCommandDispatchRoutesThroughRootForParity` (line 172).
- **Symptom / hypothesis:** Both tests pin a `name=value` Brigadier-side wire-format reconstruction (e.g., `args[0] == "count=7"`) and a sub-command `i=1` post-literal cursor contract. Running the tests against the **HEAD** `BrigadierCommandAdapter` reproduces both failures (`expected: <count=7> but was: <7>`; `expected: <1> but was: <0>`). The tests were added in the prior session as forward-looking pins; the adapter hasn't been updated to satisfy them yet. Out of scope for the current Fabric tab-complete issue.
- **Impact:** None on running Bukkit/Fabric servers (the adapter still routes execution and the Bukkit-style `name:value` wire-format is what `TreeCommand.onCommand` actually consumes); CI noise only when the suite is run.
- **Suggested next step:** either (a) align the adapter's `reconstructArgs(...)` to emit `name=value` and adjust `execute(...)` to enter sub-commands at `i=1`, or (b) relax the test assertions to accept either format. The "real" wire format used end-to-end is `name:value` (Bukkit `TabCompleter` historic + REQ-RTP-S-007 `msgInvalidCommand` parser), so option (b) is likely correct — but confirm with the parser before flipping.

### 2026-05-06 — `emitsExpectedNodeStructure` regresses when shadow `_` greedyString is attached at every literal

- **Discovered during:** Fabric `/rtp scan <TAB>` flat-fallback recursion attempt.
- **Location:** `commands-api/src/main/java/.../BrigadierCommandAdapter.java` — `attachFlatFallback(...)` recursion call inside `attachChildren` sub-command loop.
- **Symptom / hypothesis:** When `attachFlatFallback` is called for a sub-command literal (e.g., `reload`) BEFORE that literal is `parentBuilder.then(reloadLiteral)`-attached to the root, the root's `getChildren()` no longer contains `reload` (`children.containsKey("reload") == false`). Suggests Brigadier's `LiteralArgumentBuilder.build()` interaction with a `RequiredArgumentBuilder("_", greedyString())` child that `executes(...)` is collapsing the parent literal in some merge path. Need to verify against `1.2.9` Brigadier `addChild` semantics — the LinkedHashMap should preserve both, but maybe `redirect` is being inferred.
- **Impact:** Only when the recursive flat-fallback pattern is enabled. Currently disabled (only the root-level shadow remains). Without the recursion, `/rtp scan <TAB>` will continue to surface region-name suggestions like `default` instead of the Bukkit-parity `region:` prefix.
- **Suggested next step:** investigate whether attaching the shadow as a `LiteralArgumentBuilder("__rtp_flat__")` (literal name, not RequiredArgument) avoids the displacement; or whether emitting subcommand-name tokens directly into the per-parameter `SuggestionProvider` (so `RegionParameter.relevantValues(...)` returns `["region:DEFAULT", "region:nether", ...]` rather than `["default", "nether"]`) is the cleaner fix. The latter aligns with Bukkit `TabCompleter` historical wire format.

<!-- Append new entries above this comment, newest first. Resolved entries are deleted, not archived. -->
