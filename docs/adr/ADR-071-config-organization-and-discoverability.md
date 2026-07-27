# ADR-071 - Config Organization and Discoverability

**Status:** Proposed (top-level directory layout in rule 1 superseded by [ADR-076](ADR-076-config-folder-consolidation.md); all other rules remain in force)
**Date:** 2026-06-22

## Context

RTP ships its operator-facing configuration as a split-by-concern tree of YAML
files under `rtp-plugin/src/main/resources/` (English baseline) mirrored under
`lang/<locale>/` for every shipped locale. As of this ADR the baseline tree is:

| File | Approx. size | Holds |
|------|--------------|-------|
| `config.yml` | 123 lines | `teleportDelay`, `cancelDistance`, `teleportCooldown`, `lockAfterUses`, `lockAfterResetSeconds`, `setRespawnOnTeleport`, `consoleCommands`, `playerCommands`, `database`, `network.redis`, `menu` |
| `safety.yml` | 263 lines | invulnerability window, `safetyRadius`, anvil prefilter, emergency platform, biome/block exclusions, vertical window |
| `economy.yml` | 41 lines | `price`, `priceOther`, `paramsPrice`, `biomePrice`, `refundOnCancel`, `balanceFloor` |
| `regions/default.yml` | template | `world`, `shape`, `vert`, `range`/cache caps, `price`, `spatialResolution` |
| `worlds/default.yml` | template | region binding, override |
| `effects/*.yml` | one file per effect group (`default.yml`, `default-pre.yml`, `default-cancel.yml`) | per-stage effect groups (`when`, `permission`, `players`, `inherit`, `effects` token lists); group name = filename |
| `network.yml` | 225 lines | `network.*`, `transport.*` (incl. `transport.redis.*`), heartbeat, routing, queue, reservation, load balancer |
| `performance.yml` | 275 lines | queue / cache / pipeline tuning |
| `messages.yml` | 614 lines | all user-facing strings (12 `# Section N:` headers already present) |
| `logging.yml` / `metrics.yml` / `language.yml` | 71 / 33 / 13 lines | log routing, bStats, locale selection |

Recurring operator feedback (public thread: a user who praised performance still
found the config "could use some work - there are a lot of different files and it
can get confusing") identifies three distinct discoverability problems:

1. **No relevance ordering.** Within each file, keys are grouped by internal
   concern, not by how often an operator touches them. The knobs changed most
   (range, shape, world, cooldown, cost) are not sorted to the top of their file
   or the in-game menu.
2. **Everyday knobs are mixed with advanced tuning.** A first-time operator
   opening `config.yml` or `performance.yml` is confronted with deep tuning
   (heap thresholds, pipeline allotments, audit cadences) alongside the handful
   of knobs they actually need to change. There is no "start here" tier and no
   deliberate "advanced" door.
3. **`messages.yml` has outgrown a flat file.** It has 614 lines, 12 `# Section`
   headers, and a single ~240-constant `MessagesKeys` enum that is unwieldy to
   read, edit, and reason about as one unit.

A concrete fourth symptom motivates this ADR now: `config.yml` carries a
`network.redis` block (`enabled`/`host`/`port`/`password`) that overlaps the
authoritative `network.yml` surface (`network.enabled`, `transport.redis.host`
/`port`/`password`). Two files describe the same concern, which is exactly the
confusion operators report.

Any change here is not casual. The per-file YAML tree is a parity contract:

- **Locale parity.** Every baseline key is mirrored into every shipped locale via
  the locale TSV pipeline and guarded by `LocaleParityTest`. Re-ordering a file
  changes the TSV `index` column; moving a key changes its `(relpath, base_key)`
  identity. Both must round-trip through `locale-files-to-csv` ->
  `reconcile-locale-csvs` -> `locale-files-from-csv`, never by hand-editing
  `lang/<locale>/*.yml`.
- **Locale bootstrap** ([ADR-020](ADR-020-language-bootstrap-and-locale-aware-configparser.md)):
  `language.yml` is read first and every other file is loaded from
  `lang/<locale>/`. File renames/splits ripple through the bootstrap loader.
- **Comment-as-hover** ([ADR-064](ADR-064-config-comment-format-summary-line-as-menu-hover.md))
  and **comment preservation** ([ADR-042](ADR-042-yaml-comment-preservation-block-only.md)):
  per-key comments are operator-facing documentation surfaced in the in-game menu;
  they must survive any reorganization.
- **Enum-keyed parsers.** Each baseline file is parsed against a Java enum
  (`ConfigKeys`, `EconomyKeys`, etc.); the parser reads keys by name, so physical
  key order does not affect parsing, but moving a key across files changes which
  enum/parser owns it.

This ADR exists to settle configuration organization *as a concept* and the
rules any future reorganization must follow. The already-shipped in-game
search/prefab surface is a near-term mitigation, not a substitute.

The project is nearing development completion and transitioning to maintenance.
That timing informs the decision below: a one-time structural refactor that
leaves the config tree uniform and self-similar is worth more than minimizing
short-term churn, because the resulting shape is the one maintainers live with
for the long tail.

## Decision

Adopt a **tiered, uniform directory** configuration model. Every configuration
file - whether an everyday knob file, an advanced-tuning file, or a message
file - is the *same shape*: one closed Java enum, one YAML file, one
`ConfigParser`. Directories are organizational only; a directory is simply a
folder of ordinary single-file parsers. There is no special per-directory
loader and no merged multi-file enum.

### 1. Tiered directory layout

The baseline tree is organized into a "start here" common tier at the root, the
existing per-instance directories, and a deliberate `advanced/` door:

```
plugins/RTP/
|-- config.yml        # common - everyday teleport knobs; opens first
|-- economy.yml       # common - price, perms
|-- language.yml      # common - locale selection
|-- safety.yml        # common - everyday safety knobs
|-- regions/          # per-region files (existing)
|-- worlds/           # per-world overrides (existing)
|-- advanced/         # opened deliberately
|   |-- performance.yml
|   |-- blocks.yml     # airBlocks, unsafeBlocks (own list-editor pages)
|   |-- biomes.yml     # safety biome list, recall/weighting
|   |-- network.yml    # proxy / multi-server transport
|   |-- database.yml   # storage backend
|   |-- logging.yml
|   `-- metrics.yml
|-- effects/   schematics/   bundled-addons/   # unchanged
`-- messages/         # per-concern message files (see rule 3)
```

`lang/<locale>/` mirrors the new nested paths exactly (e.g.
`lang/<locale>/advanced/performance.yml`, `lang/<locale>/messages/player.yml`).

### 2. Relevance ordering within each file and menu

Keys are ordered by **common operator relevance**, not alphabetically and not by
internal grouping. The high-touch knobs sort to the top of their file and their
menu page; advanced/rarely-touched settings sort below, under a divider comment.
The in-game menu mirrors the same order. The common/advanced *tiering* (rule 1)
is relevance ordering applied at file granularity: the knobs an operator changes
most live in the root tier, the rest behind the `advanced/` door.

### 3. `messages.yml` splits into per-concern files, each with its own enum

`messages.yml` is split into a `messages/` directory of per-concern files, and
the single `MessagesKeys` enum is split into one small enum per file so each
message file is exactly as modular and copyable as any other config file:

| File | Enum |
|------|------|
| `messages/placeholders.yml` | `PlaceholderMessages` |
| `messages/player.yml` | `PlayerMessages` |
| `messages/network.yml` | `NetworkMessages` |
| `messages/commands.yml` | `CommandMessages` |
| `messages/system.yml` | `SystemMessages` |

The same per-file-enum rule applies wherever an existing enum spans what are now
separate tier files:

- `SafetyKeys` -> `SafetyKeys` (common `safety.yml`) + `BlocksKeys`
  (`advanced/blocks.yml`) + `BiomesKeys` (`advanced/biomes.yml`).
- Performance knobs currently under `config.yml`/`ConfigKeys` move to the
  existing `PerformanceKeys` (`advanced/performance.yml`).

Consequences of the split that this ADR mandates:

- **No merge-loader.** The transitional merge-directory mode added to
  `ConfigParser` (a single closed `MessagesKeys` enum unioned across
  `messages/*.yml`) is **retired**. With per-file enums there is nothing to
  merge: each file is a plain single-file parser. `ConfigParser` returns to its
  clean one-enum-one-file invariant.
- **`ConfigParser` becomes subpath-aware.** A parser `name` may carry a relative
  subdirectory (`"advanced/blocks.yml"`, `"messages/player.yml"`). The
  JAR-resource extraction path and the `lang/<locale>/<name>` mirror path must
  tolerate the subdirectory.
- **Dynamic message lookups route through a small resolver.** Two call patterns
  cannot simply reference a single enum after the split: the dynamic
  `valueOf(name + "_description")` lookup in `BaseRTPCmd`, and the `lang()`
  helper that today returns "the messages parser". Both go through a thin
  `Messages` facade holding a `name -> (enum, parser)` index built once at load.
  Every other `MessagesKeys.someKey` reference is re-pointed at the enum that now
  owns the key (a mechanical, one-time edit across the call sites).

### 4. Key moves require a migration path, never silent relocation

When a key physically moves (across files, across enums, or under a new parent),
the loader **shall** continue to read the legacy location, apply the value at the
new location, and emit a one-time deprecation log via
`RTP.log(Level.WARNING, ...)`. Existing installs upgrade cleanly without operator
action. A silent relocation that strands an operator's tuned value is prohibited.
This covers both whole-file relocations (a root `performance.yml` superseded by
`advanced/performance.yml`) and per-key moves (a knob leaving `config.yml` for
`advanced/performance.yml`).

A legacy flat `messages.yml` present on disk is read once, its operator
customizations folded into the matching per-concern file, the file archived
(renamed, not deleted), and a one-time deprecation log emitted. No operator loses
a tuned string on upgrade.

### 5. First concrete action: de-duplicate `network.redis`

`network.yml` (now `advanced/network.yml`) is the authoritative network surface.
The `network.redis` block in `config.yml` is redundant and shall be removed, with
its settings served exclusively from the network file. The loader applies the
migration rule in (4): a value still present under `config.yml`'s `network.redis`
is read, honored, and logged as deprecated, with the network file taking
precedence. This is the pilot that proves the migration mechanism the broader
reorg relies on.

### 6. Config-file version bump accompanies any structural change

Every baseline file that changes structurally (key order, key move, removed key,
relocation into `advanced/`) has its `version` field incremented so the
bundled-vs-on-disk version check fires and the operator is notified per the
existing update path. `language.yml` and the network file currently carry no
`version` field; a structural change to either requires adding one first.

### 7. The admin config editor is a recursive directory walker

Because every file is an ordinary single-file parser and directories are plain
folders, the in-game config editor is a single recursive tree walk, identical for
every directory:

- A **directory node** lists its child directories (`regions/`, `worlds/`,
  `advanced/`, `messages/`) and its member files; selecting a child directory
  recurses.
- A **file node** opens the existing per-parser key page; editing and write-back
  use that parser's own `set`/`save` - unambiguous, because each key belongs to
  exactly one enum/parser.

`messages/` and `advanced/` are handled by the *same* code because they are the
same structure: folders of ordinary parsers. The existing `MultiConfigParser`
region/world/effect selectors slot in as their own directory nodes.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Keep `MessagesKeys` closed and serve it from `messages/*.yml` via a merge-loader (one enum unioned across many files) | Shrinks the *file* but leaves the ~240-constant enum exactly as unwieldy as before, which is the actual maintainability complaint. It also creates a second, special code path (merge logic) that `messages/` uses and the rest of the config tree does not, defeating the goal of one uniform directory primitive. With the project entering maintenance, the one-time cost of splitting the enum is acceptable in exchange for permanently uniform, self-similar, copyable config files and the deletion of the merge-loader special case. Keys are unlikely to move between well-placed files once maintenance begins, so the "moving a key is a recompile" downside is largely theoretical. |
| Keep grouping virtual only (menu/docs group, no physical tiering) | A virtual-only group co-locates the operator's mental model but leaves the everyday-vs-advanced mixing (problem 2) and the oversized message enum (problem 3) unaddressed. The tiered directory plus per-file enums fixes the structural complaints directly; relevance ordering and virtual menu grouping then layer on top. |
| Alphabetical ordering within files | Optimizes for lookup-by-name, which the in-game search surface already provides; does nothing for the actual complaint (the common knobs are buried). Relevance ordering serves the operator's real workflow. |
| Flatten everything into a single `config.yml` | Reintroduces the 600+ line monolith problem `messages.yml` already exhibits; the split-by-concern model is sound, the discoverability layer is what is missing. |
| Leave `config.yml`'s `network.redis` in place | Two files describing one concern is the exact confusion operators report; keeping both perpetuates drift between the redundant block and the authoritative network file. |
| Silently relocate keys on upgrade | Strands operators' tuned values with no warning; violates the operator-trust expectation and the migration discipline in ADR-020's spirit. |
| Split `messages.yml` using the existing `MultiConfigParser` (as `regions/`, `worlds/`, `effects/` do) | `MultiConfigParser` keys on admin-chosen *outer* names (one sub-parser per file, arbitrary file set) which is the right shape for region/world/effect-group files but the wrong shape for a fixed, code-referenced key set. Per-concern message files are a *fixed* set with *disjoint* key subsets, each a closed enum - ordinary single-file parsers, not a per-entry parser map. |

## Consequences

- **Positive:** Every config file is the same shape (one enum, one file, one
  parser), so files are modular and copyable, the message enum is no longer a
  240-constant monolith, and the admin editor is one recursive directory walker
  with no per-directory special cases. The transitional merge-loader is deleted
  rather than generalized.
- **Positive:** The common/advanced tiering gives first-time operators a "start
  here" surface and a deliberate advanced door, addressing the everyday-vs-tuning
  mixing complaint directly.
- **Positive:** The `network.redis` de-duplication establishes a reusable
  read-legacy-warn-prefer-new migration pattern for every future key/file move.
- **Negative / Trade-offs:** Splitting `MessagesKeys` (and `SafetyKeys`) into
  per-file enums is a one-time, large-blast-radius refactor: every
  `MessagesKeys.someKey` reference and every `getParser(MessagesKeys.class)`
  lookup across `rtp-core`, the platform adapters, and tests is re-pointed at the
  owning enum, and the dynamic `_description`/`lang()` lookups move behind a small
  `Messages` resolver. Accepted given the maintenance-phase timing.
- **Negative / Trade-offs:** Moving a key between files later becomes a code change
  (the constant moves to another enum) rather than a YAML edit. Acceptable because
  well-placed keys are not expected to move once maintenance begins.
- **Negative / Trade-offs:** The directory split multiplies the locale surface
  (per-concern message files and the `advanced/` tier, each with its own
  synthesized `.lang.yml`, across every shipped locale). The locale/TSV-pipeline
  migration - mirroring the nested paths and round-tripping every locale - is the
  dominant cost of the change; the Java refactor is mechanical by comparison. The
  pipeline scripts and `LocaleParityTest` must understand the `advanced/` and
  `messages/` sub-trees.

## References

- ROADMAP Tier 2: "Config-file organization + discoverability (operator feedback)".
- [ADR-020](ADR-020-language-bootstrap-and-locale-aware-configparser.md) Language bootstrap and locale-aware ConfigParser.
- [ADR-042](ADR-042-yaml-comment-preservation-block-only.md) YAML comment preservation.
- [ADR-064](ADR-064-config-comment-format-summary-line-as-menu-hover.md) Config-comment format: summary line as menu hover text; `docs/dev/CONFIG_COMMENT_STYLE.md`.
- [ADR-066](ADR-066-foreign-config-importer.md) Foreign config importer (precedent for non-destructive config migration).
- *Locale Config TSV Pipeline* in `.junie/AGENTS.md`; `LocaleParityTest` in `rtp-plugin`.
- `rtp-plugin/src/main/resources/`: `config.yml`, `network.yml`, `safety.yml`, `economy.yml`, `messages/`.
