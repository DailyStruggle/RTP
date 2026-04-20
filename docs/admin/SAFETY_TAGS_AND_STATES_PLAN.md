# Plan: Block Tags and Block States in `safety.yml`

*Status:* **Draft — not yet scheduled.** No ADR written. Sibling of
`YAML_SIMPLIFICATION_PLAN.md` and `EMPTY_LIST_CONFIG_PLAN.md`.

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
material   := identifier                          # e.g. OAK_LEAVES
tag        := '#' namespace ':' path              # e.g. #minecraft:leaves
predicated := (material | tag) '[' pred (',' pred)* ']'
pred       := propertyName '=' propertyValue
identifier := [A-Za-z0-9_]+
namespace  := [a-z0-9_.-]+
path       := [a-z0-9_/.-]+
```

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

1. **ADR-0xx — Block-tag and block-state predicates in safety lists.**
   Record Option A, the grammar, the fail-open-on-unknown-property
   policy, and the Anvil pre-filter implication. Must land before any
   code PR.
2. **Grammar + compiler** in `rtp-api` (`PaletteIdentifierNormalizer` /
   `PaletteNormalizer`): produce a `CompiledUnsafeSet`. Pure logic,
   pure unit tests. Keep `Set<String>` pass-through overload.
3. **Platform matcher implementation:**
   - `BukkitRTPChunk.isSafe` — use `BlockData` and `Tag` lookups from
     `Bukkit.getTag(registry, key, Material.class)`.
   - `FoliaRTPChunk.isSafe` — identical API, different thread-check
     wrapper (`Bukkit.isOwnedByCurrentRegion`).
   - `AnvilChunkView.isSafe` — consult the palette entry `Properties`
     compound; tag resolution requires a static snapshot of
     `Bukkit.getTag(...)` taken at plugin enable (off-tick safe because
     tags are static post-load).
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
