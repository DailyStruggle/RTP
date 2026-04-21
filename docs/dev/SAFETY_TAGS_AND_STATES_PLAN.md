# Plan: Block Tags and Block States in `safety.yml`

*Status:* **Accepted — implementation in progress.** Option A ratified by
[ADR-017](../adr/ADR-017-block-tags-and-state-predicates-in-safety-lists.md)
on 2026-04-19. Sibling of `YAML_SIMPLIFICATION_PLAN.md` and
`EMPTY_LIST_CONFIG_PLAN.md`.

**Clarifications ratified by ADR-017** (supersede any conflicting text
below):
- Property values are compared as **case-insensitive lowercase strings**;
  no typed `BlockData` resolution at compile time. Compiler therefore
  lives in `rtp-api` with no Bukkit dependency.
- Tag resolution is a platform concern: `rtp-spigot-common` snapshots
  `Bukkit.getTag(...)` at plugin enable and hands the immutable
  `Map<String, Set<String>>` to `rtp-anvil` via a setter.
- Multiple predicates inside `[ ... ]` combine with **logical AND**.
- Negation / disjunction / numeric ranges are explicitly out of scope
  for this ADR; a follow-up ADR may add them.
- A wildcard material `*` is accepted **only when predicated** (e.g.
  `*[waterlogged=true]` — "any block with `waterlogged=true`"). Bare
  `*` is rejected at parse time with a WARNING. See ADR-017 §1.

## Motivation

The `unsafeBlocks` and `airBlocks` lists in `safety.yml` are presently flat
**`Set<String>` of Material names** (see `SafetyKeys.unsafeBlocks`,
`SafetyKeys.airBlocks`, consumed by `RTPChunk.isSafe(int,int,int,Set<String>)`
in `rtp-api`). This representation cannot express two categories of intent
admins routinely want:

1. **Block tags** — Minecraft data-pack tags such as `#minecraft:leaves`,
   `#minecraft:logs`, `#minecraft:wool`, `#rtp:hazardous`. Today, any admin
   wanting "all leaves are unsafe" must enumerate every variant
   (`OAK_LEAVES`, `BIRCH_LEAVES`, `CHERRY_LEAVES`, …) and re-edit on every
   Mojang update that adds a new wood type.
2. **Block states** — properties encoded on the `BlockData`, not on the
   Material. The canonical example is `WATERLOGGED=true` on fences, slabs,
   stairs, trapdoors, signs, etc. Today `OAK_SLAB` is either entirely safe
   or entirely unsafe; there is no way to say "slab, but only when
   waterlogged". Other useful state predicates: `LEVEL` (liquids),
   `LIT` (campfires), `POWERED`/`ATTACHED` (tripwire), `AGE` (crops,
   fire), `OPEN` (doors/trapdoors), `FACE=CEILING` (buttons).

Both gaps have been raised by users (the originating issue cites
`WATERLOGGED` specifically). The present doc scopes the work and records
known blockers so implementation can begin without redoing the design pass.

## Current Data Flow (inventory before redesign)

| Layer | Class / File | Today's contract |
|---|---|---|
| Config | `rtp-plugin/src/main/resources/safety.yml` | `airBlocks: [AIR, …]`, `unsafeBlocks: [LAVA, …]` — flat YAML lists of strings. |
| Keys | `SafetyKeys` (enum) | `airBlocks`, `unsafeBlocks` declared as `List` with `Collections.emptyList()` defaults. |
| Loader | `ConfigParser` → `FactoryValue<SafetyKeys>` | Coerces YAML scalars to `Set<String>` via `.toString()`. |
| Reconciliation | `PaletteNormalizer.reconcileAll(Set<String>)` / `.matches(String, Set<String>)` | Back-compat palette aliases (`LEGACY_*`, cross-version renames such as `GRASS` ↔ `SHORT_GRASS`). |
| API | `RTPChunk.isSafe(int x, int y, int z, Set<String> unsafeBlocks)` | Material-name-only predicate. |
| Live impl | `BukkitRTPChunk.isSafe`, `FoliaRTPChunk.isSafe` | `chunk.getBlock(...).getType().name()` compared against reconciled set. |
| Anvil impl | `AnvilChunkView.isSafe` (read-only pre-filter) | Compares the palette entry's material name only; the palette *does* carry properties but they are currently discarded. |
| Callers | `LocationGenerator`, `TeleportPipelineTask.isStillUnsafe`, `LinearAdjustor`, `JumpAdjustor`, `ScanTask` | All pass `unsafeBlocks` as `Set<String>` forward. |

Any state/tag support requires touching every row above. That is why this
is a plan document rather than a patch.

## Design Options

### Option A — **Extended string grammar, no API break**

Keep the `Set<String>` wire type on `RTPChunk.isSafe` but teach the
reconciliation layer to recognise three token shapes:

```
LAVA                          # bare material (today's behaviour)
#minecraft:leaves             # tag reference (namespace required)
OAK_SLAB[waterlogged=true]    # material + state predicate(s)
#minecraft:slabs[waterlogged=true]   # tag + state predicate(s)
```

Parsing happens once at config-load time inside
`PaletteNormalizer.reconcileAll(...)`, which returns an opaque
`CompiledUnsafeSet` wrapping:
- `Set<String>` — plain material names (fast path, unchanged)
- `Set<NamespacedKey>` — resolved tag keys
- `Map<Material, List<StatePredicate>>` — per-material state filters
- `Map<NamespacedKey, List<StatePredicate>>` — per-tag state filters

`RTPChunk.isSafe` is **overloaded** with a new
`isSafe(int, int, int, CompiledUnsafeSet)` that platform adapters
implement natively; the legacy `Set<String>` overload wraps the input
through `reconcileAll` and delegates. No caller is forced to migrate.

**Pros:**
- Zero API break — addon authors who stored `Set<String>` keep working.
- Admins edit a single YAML list; no schema restructure in `safety.yml`.
- Tokens round-trip through `/rtp config set …` via the existing string
  list serializer (critical for REQ-RTP-F-013 surface parity; see
  `EMPTY_LIST_CONFIG_PLAN.md`).

**Cons:**
- Non-trivial parser: must tolerate quoted values (`name="oak stairs"`
  — unlikely but legal), escape commas inside `[...]`, and reject
  malformed tokens with a logged WARN (REQ-RTP-S-004).
- `AnvilChunkView.isSafe` must learn to consult the NBT `Properties`
  compound in the palette entry; this is a real change to the pre-filter
  (ADR-016 is advisory, so a REJECT on a waterlogged state is fine, an
  ACCEPT remains non-authoritative and re-checked live).

### Option B — **Structured YAML, API break**

Replace the flat list with a tagged-union map:

```yaml
unsafeBlocks:
  materials:
    - LAVA
    - FIRE
  tags:
    - minecraft:leaves
  states:
    - material: OAK_SLAB
      when: { waterlogged: true }
    - tag: minecraft:slabs
      when: { waterlogged: true }
```

New typed key `SafetyKeys.unsafeBlockMatchers` returning a
`List<BlockMatcher>` bean. `RTPChunk.isSafe` gains a new overload that
accepts the bean list; the string overload is `@Deprecated` and routed
through a migration adapter for one major version.

**Pros:**
- Self-documenting in YAML; no mini-grammar to learn.
- Natural extension point for future predicates (biome, light level)
  without revisiting the token grammar.

**Cons:**
- Hard schema migration: `legacyConfigSupport.yml` must rewrite old
  `unsafeBlocks: [ … ]` into the new structure on first boot, logging
  a SUPERSEDED note per REQ-RTP-F-013.
- `/rtp config` cannot currently render nested maps as a leaf command
  (see `EMPTY_LIST_CONFIG_PLAN.md` — empty collections already drop out
  of the command surface; a nested bean list would worsen the gap).
- Every addon consuming `SafetyKeys.unsafeBlocks` via
  `RTP.getInstance().configs.getParser(SafetyKeys.class)` breaks.

### Recommendation

**Option A.** Rationale:
- Preserves the `Set<String>` API surface that `rtp-api` addon authors
  consume.
- Keeps `/rtp config` functional (each matcher token is a single
  string — already renderable as a list element).
- Extending `PaletteNormalizer` is localised; the grammar is the *only*
  new surface to test.
- Reversible: if Option B is ever needed, the compiled form can be
  persisted alongside the token list.

## Token Grammar (Option A, normative)

```
token      := material | tag | predicated
material   := identifier | '*'                    # '*' is the wildcard
tag        := '#' namespace ':' path              # e.g. #minecraft:leaves
predicated := (material | tag) '[' pred (',' pred)* ']'
pred       := propertyName '=' propertyValue
identifier := [A-Za-z0-9_]+
namespace  := [a-z0-9_.-]+
path       := [a-z0-9_/.-]+
```

Bare `*` (no `[ ... ]`) is rejected at parse time with a WARNING —
see ADR-017 §1.

- Case: bare materials remain **UPPER_SNAKE** (matches Bukkit
  `Material.name()`); tag keys are **lower** (matches Mojang convention).
- Unknown tag → logged WARN once at startup, excluded from compiled set,
  pipeline continues (REQ-RTP-S-004: never silent).
- Unknown property name for a given material → logged WARN once,
  predicate dropped (fail-open is the conservative choice for an
  *unsafe* list: over-reject would block teleports).
- Unknown material → existing behaviour: reconciliation logs at FINE
  and drops it.

## Work Breakdown

1. ~~**ADR-0xx — Block-tag and block-state predicates in safety lists.**~~
   **Done** — landed as
   [ADR-017](../adr/ADR-017-block-tags-and-state-predicates-in-safety-lists.md).
2. ~~**Grammar + compiler** in `rtp-api` (`PaletteIdentifierNormalizer` /
   `PaletteNormalizer`): produce a `CompiledUnsafeSet`. Pure logic,
   pure unit tests. Keep `Set<String>` pass-through overload.~~
   **Done (Slice 1 + Slice 2)** — `io.github.dailystruggle.rtp.api.safety`
   package delivers `SafetyToken`, `StatePredicate`, `SafetyTokenParser`,
   `CompiledUnsafeSet`, and `SafetyCompilationCache` (Slice 2 adds the
   memoizing adapter between `Set<String>` callers and the compiled
   form; see `RTPChunk.isSafe(CompiledUnsafeSet)` overload).
3. **Platform matcher implementation:**
   - **Slice 2 — state predicates on the live path (done).**
     `BukkitRTPChunk.isSafe(int,int,int,CompiledUnsafeSet)` and
     `FoliaRTPChunk.isSafe(int,int,int,CompiledUnsafeSet)` overrides
     extract the candidate's material name and — only when a state
     predicate could apply for that material or any wildcard — parse
     `BlockData.getAsString()` into a lowercase property map and
     delegate to `CompiledUnsafeSet.isUnsafe(...)`. Tag membership is
     passed as an empty collection pending Slice 3.
   - **Slice 3a — tag-snapshot hand-off (done, 2026-04-20).**
     Implementation chose the **hybrid Option γ** (see ADR-017 update
     note): Bukkit-family adapters expose
     `RTPServerAccessor.blockTagSnapshot()` as a lazy-built immutable
     `Map<String,Set<String>>` sourced from
     `Bukkit.getTags(Tag.REGISTRY_BLOCKS, Material.class)`, and a new
     standalone module `rtp-tags` provides a zero-dependency disk
     resolver (`TagResolver`, `DiskTagSource`, `TagFileParser`,
     `TinyJsonReader`) so that non-Bukkit platforms (Fabric, future
     ports) can read the same JSON data pack tag files at boot. The
     disk resolver is shipped but not yet wired into any platform
     adapter — it exists to unblock Fabric without duplicating logic.
     `SafetyCompilationCache.getOrCompile(rawTokens, tagSnapshot,
     rejectionSink)` post-expands tag tokens via the new
     `CompiledUnsafeSet.withTagsExpanded(...)` helper so the hot path
     never consults the snapshot per candidate. `LocationGenerator`
     (both the queued-poll and the pregen path) now feeds the
     rejection sink into `RTP.log(Level.WARNING, ...)` — closing the
     last REQ-RTP-S-004 gap opened by ADR-017.
   - **Slice 3b (pending) — Anvil state-predicate + E2E tests.**
     Extend `AnvilChunkView` to carry packed block-state properties
     from the palette NBT so `CompiledUnsafeSet.isUnsafe(...)` can
     evaluate state predicates off-tick on pure Spigot. Add
     `ReqRtpS001StatePredicateBukkitTest` (MockBukkit) and
     `ReqRtpS001WildcardStateAnvilTest` (Anvil fixture). Flip ADR-017
     + this plan to *Complete*.
4. **Config migration** — no schema change under Option A, but
   `legacyConfigSupport.yml` should add a SUPERSEDED-free annotation
   explaining the new token shapes. Bump `safety.yml` header version.
5. **`/rtp config` round-trip** — verify that
   `rtp config set safety unsafeBlocks add "OAK_SLAB[waterlogged=true]"`
   parses back identically (single-quote / double-quote handling in
   `SubConfigCmd`).
6. **Tests** (traceable):
   - `ReqRtpS001TagPredicateTest` — tag expansion covers every Material
     the live server reports for the tag.
   - `ReqRtpS001StatePredicateTest` — `OAK_SLAB` safe, `OAK_SLAB` with
     `waterlogged=true` unsafe.
   - `ReqRtpS001AnvilPrefilterStateTest` — pre-filter REJECT on
     waterlogged slab palette entry.
   - `ReqRtpS004UnknownTagWarnTest` — unknown tag key produces a single
     startup WARN, pipeline continues.
   - Update `docs/dev/TRACEABILITY.md` with the new rows.
7. **Docs:**
   - Add a §"Block tags and states" subsection to the `safety.yml`
     preamble with three worked examples.
   - Add a glossary entry for *Block Tag* and *State Predicate* in
     `docs/dev/GLOSSARY.md` (Multipurpose/Overloaded table — "state"
     collides with pipeline state).
   - Update `REQ-RTP-S-001` satisfied-by text in `.junie/AGENTS.md`
     to mention the compiled matcher set.

## Risks & Trade-offs

| Risk | Mitigation |
|---|---|
| Grammar parser bugs silently accept a malformed token and the admin never learns. | Compile at config-load; WARN-log each rejected token with the exact offset in the string. |
| Tag resolution depends on Bukkit registries that are not populated until `onEnable`. | Compile the matcher set lazily on first use, not at `ConfigParser.check()`. Cache the result until a config reload invalidates it. |
| Anvil pre-filter cannot evaluate a tag predicate off-tick if the tag registry is accessed from a non-main thread on some legacy server forks. | Snapshot the `tag → Set<Material>` mapping once at enable into an immutable map and consult that from `ForkJoinPool.commonPool()`. |
| Performance regression: `BlockData` allocation inside the hot safety loop. | Fast path — if the compiled set has zero state predicates for the candidate's Material and zero tag hits, skip `getBlockData()` entirely. Most servers will hit this path. |
| Addon authors reach into `Set<String>` via reflection. | `Set<String>` overload remains; new functionality is opt-in via the new overload. Document in CHANGELOG. |

## Out of Scope

- NBT predicates beyond `BlockData` properties (e.g. `CustomName` on
  signs). Servers mis-using RTP to avoid signs should add the Material
  to `unsafeBlocks` flatly.
- Biome/light/entity predicates — call site is wrong; those belong on
  `GlobalRegionVerifiers`, not `isSafe`.
- `airBlocks` tag/state extension. Same grammar trivially applies, but
  defer to a follow-up PR so the scope stays tight.

## Execution Checklist (per PR)

- [ ] ADR-0xx landed and linked from this plan.
- [ ] Grammar unit tests in `rtp-api` (no Bukkit on classpath).
- [ ] Platform parity tests for `BukkitRTPChunk` / `FoliaRTPChunk` /
      `AnvilChunkView`.
- [ ] `safety.yml` header updated with worked examples; legacy flat
      lists still accepted.
- [ ] `MIGRATION.md` entry — "no action required, existing configs
      keep working; new syntax available."
- [ ] `TRACEABILITY.md` rows added for new REQ-* coverage.
- [ ] `GLOSSARY.md` rows added for *Block Tag*, *State Predicate*.
- [ ] `.junie/AGENTS.md` REQ-RTP-S-001 "Already satisfied by" text
      updated.
- [ ] `/rtp config set/add/remove` round-trips a `[state=value]` token
      without quoting damage.
- [ ] No new `printStackTrace()`, all failure paths log at WARN with
      context (REQ-RTP-S-004).
- [ ] No new synchronous chunk I/O; Anvil path remains off-tick
      (REQ-RTP-S-005).
