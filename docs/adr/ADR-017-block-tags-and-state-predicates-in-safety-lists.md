# ADR-017 — Block Tags and Block-State Predicates in Safety Lists

**Status:** Accepted
**Date:** 2026-04-19

## Context

The `unsafeBlocks` and `airBlocks` lists in `safety.yml` are presently
flat `Set<String>` of `org.bukkit.Material.name()` identifiers.
`SafetyKeys.unsafeBlocks` and `SafetyKeys.airBlocks` are declared as
`List`; `ConfigParser` coerces the YAML scalars to `Set<String>` via
`.toString()`; `RTPChunk.isSafe(int, int, int, Set<String>)` compares
those reconciled names against the live block's `Material.name()`. Every
caller in the pipeline — `LocationGenerator`, `TeleportPipelineTask.isStillUnsafe`,
`LinearAdjustor`, `JumpAdjustor`, `ScanTask`, `AnvilChunkView.isSafe` —
passes `unsafeBlocks` forward as `Set<String>`.

This representation cannot express two admin intents that are frequently
requested:

1. **Block tags** — Minecraft data-pack tag references such as
   `#minecraft:leaves`, `#minecraft:logs`, `#minecraft:wool`,
   `#rtp:hazardous`. Without tag support, an admin who wants "all leaves
   are unsafe" must enumerate every wood variant and re-edit on every
   Mojang release that adds a new wood type.
2. **Block states** — properties encoded on the `BlockData`, not on the
   `Material`. The canonical example is `WATERLOGGED=true` on fences,
   slabs, stairs, trapdoors, and signs; today `OAK_SLAB` is uniformly
   safe or uniformly unsafe with no way to distinguish "slab, but only
   when waterlogged". Other routinely-useful state predicates are
   `LEVEL` (liquids), `LIT` (campfires), `POWERED`/`ATTACHED` (tripwire),
   `AGE` (crops, fire), `OPEN` (doors/trapdoors), and `FACE=CEILING`
   (buttons).

The originating user report cites `WATERLOGGED` specifically; see also
`docs/dev/SAFETY_TAGS_AND_STATES_PLAN.md`, which enumerates the affected
layers and weighs the two design options below.

A key architectural constraint is that `rtp-api` must not take a
compile-time dependency on `org.bukkit.*` (see ADR-011). Any solution
that compiles a Bukkit `BlockData` at config-load time is therefore
ineligible to live in `rtp-api`.

A second constraint is that `/rtp config set/add/remove` must continue
to round-trip every configured value as a leaf string (see
`docs/dev/EMPTY_LIST_CONFIG_PLAN.md`). Nested YAML maps cannot be
addressed by the command surface today.

## Decision

RTP shall extend the safety-list grammar to accept three token shapes
within the existing flat `Set<String>` wire format:

```
LAVA                                 # bare material (unchanged)
#minecraft:leaves                    # tag reference (namespace required)
OAK_SLAB[waterlogged=true]           # material + state predicate(s)
#minecraft:slabs[waterlogged=true]   # tag + state predicate(s)
*[waterlogged=true]                  # wildcard material + state predicate(s)
```

This corresponds to **Option A** of the plan document. The `Set<String>`
wire type on `RTPChunk.isSafe` is preserved; an additional
`isSafe(int, int, int, CompiledUnsafeSet)` overload is introduced and
the legacy overload delegates through a compiler.

### 1. Token grammar (normative)

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

- Bare materials remain **UPPER_SNAKE** to match `Material.name()`.
- Tag namespaces and paths are **lowercase** to match Mojang convention.
- A token such as `leaves` (lowercase identifier, no `#`) is treated as
  a malformed material name and dropped at FINE by the existing
  `PaletteNormalizer` unknown-material path.
- `propertyName` and `propertyValue` are treated as **case-insensitive
  lowercase strings**. At evaluation time the live block's property is
  compared as the lowercase `String` produced by `BlockData.getAsString()`
  parsing (Bukkit path) or the NBT palette `Properties` compound value
  (Anvil path). No typed resolution against `BlockData.getMaterial()` is
  performed. **Rationale:** string-only comparison keeps the compiler
  pure and Bukkit-free, which is a prerequisite for placing it in
  `rtp-api` and unit-testing it without a server.
- Multiple predicates inside `[ … ]` combine with **logical AND**. A
  candidate block matches the predicated token iff every listed property
  equals (case-insensitively) the live block's property of the same
  name.
- Negation, disjunction, and numeric ranges are **out of scope** (see
  §"Alternatives Considered" and the plan's "Out of Scope" section).
- **Wildcard material.** The identifier `*` is reserved as a wildcard
  material that matches any block regardless of `Material.name()`. A
  bare `*` (no predicate) is **rejected at parse time with a WARNING**
  — it would unsafe-mark every block in every world and is almost
  certainly a configuration mistake. Only predicated wildcards are
  meaningful: `*[waterlogged=true]` reads as "any block with
  `waterlogged=true`", which is the canonical motivating example.
  Wildcards are never valid as the target of a tag reference (`#*:foo`
  is nonsense and is rejected).
- **Wildcard hot-path cost.** A wildcard predicated token forces every
  candidate block to have its property map extracted. The compiler
  records whether any wildcard token exists in a boolean flag
  (`hasWildcardStatePredicate`); when the flag is `false` the fast path
  in §4 remains unchanged. Admins who configure a wildcard are
  opting into the cost.

### 2. Compiled form and module placement

`PaletteNormalizer.reconcileAll(Set<String>)` gains a compile step that
returns a `CompiledUnsafeSet` with four fields:

- `Set<String>` — plain material names (fast path, unchanged).
- `Set<String>` — resolved tag keys (as `namespace:path` strings; no
  `NamespacedKey` import — keeps `rtp-api` Bukkit-free).
- `Map<String, List<StatePredicate>>` — per-material state filters,
  keyed by `Material.name()`.
- `Map<String, List<StatePredicate>>` — per-tag state filters, keyed by
  `namespace:path`.
- `List<StatePredicate>` — wildcard state filters (applied to every
  candidate when non-empty).

A `StatePredicate` is an immutable `Map<String, String>` of
case-insensitive lowercase key/value pairs plus the original token for
diagnostics.

The compiled structure and grammar parser live in **`rtp-api`**. They
have no Bukkit dependency and no knowledge of which concrete materials
a tag expands to.

Tag expansion (`namespace:path` → `Set<String>` of material names) is a
separate concern and lives in the platform adapter:

- **`rtp-spigot-common`** provides a `TagResolver` that delegates to
  `Bukkit.getTag(registry, key, Material.class)` on plugin enable,
  snapshots the expansion into an immutable `Map<String, Set<String>>`,
  and hands the snapshot to `rtp-anvil` for off-tick use.
- **`rtp-paper`** and **`rtp-folia`** inherit the Spigot resolver;
  they override `RTPChunk.isSafe` only if their native
  `Tag`/`BlockData` lookups diverge.
- **`rtp-anvil`** consumes the snapshot via a setter invoked at plugin
  enable. The snapshot is read-only and thread-safe by construction.

### 3. Failure policy (REQ-RTP-S-004)

- **Unknown tag** at compile time: a single startup `WARNING` is logged
  naming the token; the tag is excluded from the compiled set; pipeline
  continues.
- **Unknown property name** on a given material at compile time: a
  single startup `WARNING` is logged; the predicate is dropped;
  remaining predicates on the token are retained. **Rationale:**
  fail-open on an *unsafe* list is the conservative choice — dropping a
  predicate can never cause an unsafe teleport (the Material-level rule
  still applies), whereas over-rejection blocks teleports.
- **Malformed token** (unbalanced `[...]`, empty predicate, reserved
  character in value): single startup `WARNING` with the offending
  substring and offset; token is dropped.
- **Never silent**: no parse failure returns without a log line. This is
  a REQ-RTP-S-004 surface; see the `ReqRtpS004UnknownTagWarnTest` row in
  `TRACEABILITY.md` added alongside the implementation PR.

### 4. Evaluation (hot path)

`RTPChunk.isSafe(int, int, int, CompiledUnsafeSet)` proceeds in
ascending cost order:

1. Compare the block's `Material.name()` against the plain-material set.
   Hit → unsafe. *Miss, the set has no tag or state entries applicable
   to this material, AND `hasWildcardStatePredicate == false` → safe
   without touching `BlockData`.* This fast path covers the
   overwhelmingly common case where an admin has only flat material
   names configured.
2. Look up the material's tag membership in the snapshot; if any tag
   has no state predicate → unsafe.
3. If state predicates exist for the material or its tags, or
   `hasWildcardStatePredicate == true`, fetch `BlockData` once, extract
   a `Map<String, String>` of lowercase properties, and AND-compare
   against each applicable `StatePredicate` (material-scoped,
   tag-scoped via tag-snapshot membership, and wildcard-scoped).
   Matching a property that the live block does not carry (e.g.
   `waterlogged=true` on a block with no `waterlogged` property) is a
   miss, not a match — per the fail-open policy the candidate is not
   marked unsafe by that predicate.

The `Anvil` path mirrors this: palette entries carry the same
`Properties` compound, read from NBT directly without allocating a
Bukkit `BlockData`.

### 5. Anvil pre-filter implication (ADR-016)

`AnvilChunkView.isSafe` shall read the palette entry's NBT `Properties`
compound when evaluating state predicates. An `AnvilPrefilter` verdict
of `REJECT` on a predicated match is legitimate; an `ACCEPT` remains
advisory per ADR-016 §3 and is re-checked live at teleport commit.

### 6. Configuration migration

No schema change. `safety.yml` remains a flat list. The file header
gains a §"Block tags and states" subsection with three worked examples
(bare material, tag, predicated material). Existing configs keep
working verbatim; `legacyConfigSupport.yml` emits no SUPERSEDED notice.

The `/rtp config set/add/remove` surface must round-trip the new tokens
verbatim. Because every token is a single string containing no YAML
structural characters other than `[`, `]`, `,`, and `=`, the existing
string-list serializer handles them; the implementation PR shall add a
test that verifies
`rtp config set safety unsafeBlocks add "OAK_SLAB[waterlogged=true]"`
round-trips through `SubConfigCmd` without quoting damage.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| **Option B — structured YAML** (`unsafeBlocks: { materials: [...], tags: [...], states: [...] }`) | Breaks every `SafetyKeys.unsafeBlocks` consumer in `rtp-api` addons; nested maps are not addressable by `/rtp config` (worsens the gap documented in `EMPTY_LIST_CONFIG_PLAN.md`); `legacyConfigSupport.yml` must perform a first-boot schema rewrite. Option A provides the same expressive power with zero API break. |
| **Typed property resolution via `BlockData.createBlockData().getMatchedProperties()`** | Requires `org.bukkit.*` on the compiler's classpath, forcing the compiler into `rtp-spigot-common` and duplicating the grammar parser into Folia/Paper/Fabric adapters. Lowercase-string comparison gives equivalent admin-facing behaviour with no loss of accuracy that we care about (property values are already stable lowercase identifiers in the Bukkit string form). |
| **Disjunction / negation in the grammar** (e.g. `OAK_SLAB[waterlogged=true|waterlogged=false]`, `!OAK_SLAB`) | Requires a full expression parser and conflict resolution against the `airBlocks`/`unsafeBlocks` split. Deferred; if requested, a follow-up ADR can add a third safety-list key rather than complicating the token grammar. |
| **Regex-style material matching** (e.g. `.*_LEAVES`) | Collides with the `[A-Za-z0-9_]+` identifier rule and would interact badly with legacy palette aliases. Tags cover the same admin need canonically. |
| **Compile at `ConfigParser.check()` time** | Bukkit tag registries are not populated until `onEnable`; compiling eagerly would force a partial compile with tag resolution deferred to first use. The implementation compiles the pure grammar at `check()` and defers tag expansion to first matcher use (lazy, cached, invalidated on config reload). |

## Consequences

- **Positive:**
  - Zero API break. `RTPChunk.isSafe(int, int, int, Set<String>)` still
    compiles and still works; addons using `SafetyKeys.unsafeBlocks`
    directly are unaffected.
  - Admin edits stay in one flat YAML list; `/rtp config` keeps
    functioning on every token.
  - The grammar is additive: future tokens (e.g. biome predicates, if
    ever wanted) would fit the same `name[key=value]` shape without
    another schema break.
  - Compiler is pure logic in `rtp-api` — unit-testable without a
    server, which keeps CI coverage honest.
  - Fast path in the hot evaluation loop means the feature has no
    measurable cost for admins who only use plain material names.

- **Negative / Trade-offs:**
  - A mini-grammar to document and teach. The `safety.yml` header and
    the RTP GLOSSARY must both gain entries; otherwise the token
    `OAK_SLAB[waterlogged=true]` is opaque to new admins.
  - `AnvilChunkView.isSafe` grows a second data source (the NBT
    `Properties` compound). This is local complexity in one class but
    expands the Anvil test matrix.
  - Tag-expansion snapshot adds a one-shot cross-module hand-off
    (`rtp-spigot-common` → `rtp-anvil`) at enable time. Lifecycle
    wiring must be explicit; a null snapshot must degrade gracefully to
    "no tag match" (fail-open, logged once).
  - The `airBlocks` key is unchanged for this ADR. The compiler is
    symmetric so a follow-up can enable the same grammar on `airBlocks`
    with a one-call wiring change; this is deliberately out of scope
    here to keep the PR tight.
  - Lowercase-string property comparison means a config line that
    capitalises the value (`FACE=CEILING`) will not match at runtime
    unless normalised; the compiler lowercases both sides to remove
    that footgun, and the documentation calls it out.

## References

- `docs/dev/SAFETY_TAGS_AND_STATES_PLAN.md` — full design plan,
  current-data-flow inventory, work breakdown, and risk table.
- `docs/dev/EMPTY_LIST_CONFIG_PLAN.md` — `/rtp config` round-trip
  constraints that ruled out Option B.
- `docs/dev/REQUIREMENTS.md §3` — REQ-RTP-S-001 (unsafe-block
  prohibition) and REQ-RTP-S-004 (never-silent failure).
- [ADR-011](ADR-011-rtp-api-separate-module.md) — no Bukkit on the
  `rtp-api` classpath.
- [ADR-016](ADR-016-anvil-subsystem.md) — Anvil pre-filter advisory
  semantics; this ADR extends the pre-filter to consult palette
  `Properties`.
- `rtp-api/src/main/java/io/github/dailystruggle/rtp/api/substitutions/RTPChunk.java`
  — `isSafe` contract to be overloaded.
- `PaletteNormalizer` / `PaletteIdentifierNormalizer` in `rtp-api` —
  home of the new grammar compiler.
- `BukkitRTPChunk`, `FoliaRTPChunk`, `AnvilChunkView` — live and
  off-tick `isSafe` implementations to be updated.
