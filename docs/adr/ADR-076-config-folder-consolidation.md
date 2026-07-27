# ADR-076 - Config Folder Consolidation

**Status:** Accepted
**Date:** 2026-07-26
**Supersedes:** the top-level directory layout of [ADR-071](ADR-071-config-organization-and-discoverability.md) (rule 1). ADR-071's other decisions (per-file enums, relevance ordering, migration discipline, version bumps, recursive editor) remain in force.

## Context

[ADR-071](ADR-071-config-organization-and-discoverability.md) established a tiered
directory model with an `advanced/` door and split `messages.yml` into a
`messages/` directory. After that work landed, the operator-facing runtime tree
(`plugins/RTP/`, mirrored from `rtp-plugin/src/main/resources/`) has grown to
**12 top-level objects**:

- 4 YAML files: `config.yml`, `economy.yml`, `language.yml`, `safety.yml`
- 8 folders: `advanced/`, `addons/`, `effects/`, `lang/`, `messages/`,
  `regions/`, `schematics/`, `worlds/`

(`addons/` is the runtime addon-jar folder; `lang/` is the operator-visible
locale mirror tree. `bundled-addons/` is a jar-internal payload extracted into
`addons/` and is not a top-level runtime object.)

Twelve top-level objects is the discoverability problem ADR-071 set out to
reduce, now expressed one level up: a new operator opening the data folder is
confronted with a wall of folders and cannot tell the "start here" surface from
the machinery. The goal of this ADR is a small, tiered top level where the
everyday knobs are immediately visible and everything else is grouped behind a
small number of deliberate doors.

Most of the parity contract from ADR-071 still applies: the ADR-020 language
bootstrap, comment-as-hover (ADR-064), comment preservation (ADR-042), and
one-enum-one-file-one-parser. One element changes here: the flat
`lang/*.lang.yml` rename-map layout is replaced by co-located dotfile maps that
mirror the English default folder structure (section 7). The per-locale
translated value tree stays at the root `lang/` folder (it is not moved behind
`advanced/`; operators selecting or editing a translation expect it there). The
locale TSV pipeline (`locale-files-to-csv.py`
-> `reconcile` -> `locale-files-from-csv.py`) is retired by this ADR and will be
replaced separately; it is no longer a parity gate. `LocaleParityTest` remains
the runtime parity gate and is updated to the new map scheme.

## Decision

Consolidate the top level to a small tiered surface: the four everyday YAML files
at the root, one `definitions/` folder for the admin-authored definition sets,
one `addons/` folder, and one `advanced/` door for everything rarely hand-edited.

### 1. Target layout

```
plugins/RTP/
|-- config.yml        # common - everyday teleport knobs; opens first
|-- economy.yml       # common - price, perms
|-- language.yml      # common - locale selection (read first; stays visible)
|-- safety.yml        # common - everyday safety knobs
|-- addons/           # runtime addon jars
|-- definitions/      # admin-authored definition sets (MultiConfigParser)
|   |-- regions/
|   |-- worlds/
|   `-- effects/
|-- advanced/         # opened deliberately
|   |-- performance.yml  network.yml  database.yml  logging.yml
|   |-- metrics.yml      biomes.yml   blocks.yml
|   |-- schematics/      # .schem assets referenced by effects/regions
|   `-- messages/        # per-concern user-facing strings
|-- lang/             # locale mirror tree (all locales); stays at root
`-- database/         # runtime state (H2/SQLite); not config
```

Top-level config surface = **7 objects** (`config.yml`, `economy.yml`,
`language.yml`, `safety.yml`, `addons/`, `definitions/`, `advanced/`) plus the
operator-visible `lang/` locale mirror at root, plus the runtime-generated
`database/` folder which is state, not configuration. `advanced/` and
`definitions/` collapse the former `regions/`/`worlds/`/`effects/`/`schematics/`/
`messages/` folders while keeping the everyday files and the locale mirror
visible.

### 2. `definitions/` groups the `MultiConfigParser` sets

`regions/`, `worlds/`, and `effects/` are the admin-authored, arbitrarily-named
definition sets loaded by `MultiConfigParser`. They move under a single
`definitions/` parent. `definitions/` is an ordinary directory node in the
recursive editor (ADR-071 rule 7); the `MultiConfigParser` selectors slot in as
its children unchanged. The parser *kind* string stays `regions` / `worlds` /
`effects` (the menu, removal-guards, and `OpenMultiConfigSelector` resolve
parsers by that literal kind); `MultiConfigParser` gains a separate directory
parameter (`definitions/regions`) so the on-disk/in-jar path moves without
renaming the kind.

`language.yml` and `economy.yml` remain separate root files. `language.yml` must
be read before the locale-aware load (ADR-020), so folding it into `config.yml`
would obscure the language-switch path; both stay in the everyday tier.

### 3. `schematics/` and `messages/` move under `advanced/`; `lang/` stays at root

These two folders are rarely hand-edited by the average operator (`schematics/`
holds `.schem` binaries referenced by effects/regions; `messages/` is text most
operators reword through the in-game menu). They move behind the `advanced/`
door. `messages/` and the `lang/` mirror stay distinct, honoring ADR-071's
per-concern message split and keeping the baseline strings separate from the
translations.

The `lang/` per-locale translated value tree stays at the root. It is not moved
behind `advanced/`: operators who select a locale (via `language.yml`) or edit a
translation expect the tree where it is, and hiding live translated content
behind the "advanced" door works against that. The tree's internal structure
mirrors the full baseline config tree, so when a baseline file moves (e.g.
`messages/` -> `advanced/messages/`) its mirror follows within `lang/` (e.g.
`lang/<locale>/advanced/messages/...`). The ADR-020 bootstrap base path stays
rooted at `lang/`.

### 4. `database/` stays at root as runtime state

`database/` is generated runtime state, not operator configuration, and is not
counted against the config surface. It stays at the root where it is plainly
distinguishable from config, avoiding a data-loss-risk relocation of an operator's
live region/world database.

### 5. Migration path (ADR-071 rule 4 applies unchanged)

Every relocation reads the legacy location, applies the value at the new location,
and emits a one-time `RTP.log(Level.WARNING, ...)` deprecation notice. Whole-folder
moves (`regions/` -> `definitions/regions/`, `messages/` -> `advanced/messages/`,
`schematics/` -> `advanced/schematics/`) relocate an existing operator folder
verbatim on first upgrade rather than stranding it, in
the same style as the existing `advanced/network.yml` relocation in
`NetworkModeBootstrap`. No operator loses a tuned file on upgrade.

### 6. Version bump and uniform editor (ADR-071 rules 6 and 7 apply)

Every baseline file whose parser subpath changes has its `version` field
incremented so the update path fires. The recursive directory-walker editor is
unchanged: `definitions/` and the deeper `advanced/` sub-folders are ordinary
directory nodes.

### 7. Locale rename-maps are co-located dotfile siblings mirroring the default tree

The key-rename maps (`<name>.lang.yml`) leave the flat `lang/` root. Each map is
named `.<name>.lang.yml` and lives in the same folder as the baseline file (or
file-set) it describes, so the rename-map tree has the same folder structure as
the English default resource tree:

- `config.yml` -> sibling `.config.lang.yml` at the root.
- `advanced/messages/player.yml` -> sibling `advanced/messages/.player.lang.yml`.
- `definitions/regions/*.yml` (one shared `RegionKeys` enum) -> one
  `definitions/.regions.lang.yml` beside the `regions/` folder.

The per-locale trees under `lang/<locale>/` follow the same folder
structure, each with its own co-located `.<name>.lang.yml`. The leading dot keeps
the maps out of a default `ls` on the target platform (Linux is the assumed
deployment, ~98% of installs per bStats), so the operator-visible surface stays
clean while the map sits next to the file it renames.

The lookup chain is unchanged (`localeLangMap` -> `baselineLangMap` -> identity);
only the on-disk/in-jar location of the map files changes. `ConfigParser`
(`loadLangFile`), `MultiConfigParser`, and `LocaleParityTest` resolve the map as
the dotfile sibling of the value file/folder instead of `lang/<name>.lang.yml`.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Keep the ADR-071 layout (12 top-level objects) | Reproduces the discoverability complaint one level up; a wall of folders hides the "start here" surface. |
| Name the grouping folder `content/` | Rejected as vague; `definitions/` names what the folder holds (admin-authored region/world/effect definition sets). |
| Fold `economy.yml` / `language.yml` into `config.yml` to reach 6 without `advanced/` moves | `language.yml` must be read before the locale-aware load (ADR-020); folding it obscures the language-switch path. Keeps the everyday tier honest and self-describing. |
| Fold `messages/` into `lang/` | They serve different purposes: `messages/` is the baseline string set (per-concern, code-referenced); `lang/` is the locale mirror. Merging them conflates baseline authoring with translation. |
| Move `database/` under `advanced/database/` | Requires a copy-then-verify migration of live operator state; the payoff (root purity) does not justify the data-loss risk. `database/` is obviously not config where it sits. |
| Put `regions/` + `worlds/` at root, only nest `effects/`+`schematics/` | Honest about tiers but lands at 8, missing the target; per operator feedback, an admin willing to hand-edit config is advanced enough to open `definitions/`. |

## Consequences

- **Positive:** The top level is a small tiered surface (four everyday files plus
  `addons/`, `definitions/`, `advanced/`); a first-time operator sees the
  start-here knobs immediately and everything else grouped behind two doors.
- **Positive:** `definitions/` gives the `MultiConfigParser` sets a single home;
  the recursive editor needs no new primitive.
- **Negative / Trade-offs:** Moving every rename-map to a co-located dotfile
  sibling is the dominant cost: `ConfigParser.loadLangFile`, `MultiConfigParser`,
  and `LocaleParityTest` all move to the new map scheme, and every shipped locale
  tree gains co-located maps. The `lang/` value tree stays at root, so its
  internal structure only shifts to follow the relocated baseline files
  (`messages/`, `schematics/`). The Java parser-subpath edits are mechanical by
  comparison.
- **Negative / Trade-offs:** The legacy TSV pipeline is retired by this ADR and
  will be replaced separately; until its replacement lands, locale edits are made
  directly against the co-located files with `LocaleParityTest` as the gate.
- **Negative / Trade-offs:** `messages/` moving behind `advanced/` slightly cuts
  against ADR-071's everyday-tier placement of user-facing strings; accepted
  because the in-game menu and search surface message keys regardless of on-disk
  location, and folder count reduction is the priority.
- **Negative / Trade-offs:** Operators who learned the old paths (`regions/`,
  `messages/`, `schematics/`) must relearn them; the read-legacy relocation and a
  header comment in `config.yml` mitigate the transition. `lang/` stays at root,
  so locale selection and translation edits are undisturbed.

## References

- [ADR-071](ADR-071-config-organization-and-discoverability.md) Config organization and discoverability (superseded top-level layout; other rules retained).
- [ADR-020](ADR-020-language-bootstrap-and-locale-aware-configparser.md) Language bootstrap and locale-aware ConfigParser.
- [ADR-042](ADR-042-yaml-comment-preservation-block-only.md) YAML comment preservation.
- [ADR-064](ADR-064-config-comment-format-summary-line-as-menu-hover.md) Config-comment format: summary line as menu hover.
- *Locale Parity Maintenance* in `.junie/AGENTS.md`; `LocaleParityTest` in `rtp-plugin`. (The *Locale Config TSV Pipeline* is retired by this ADR, pending a replacement.)
- `rtp-core/.../configuration/Configs.java` (parser construction + legacy migration); `NetworkModeBootstrap` (`advanced/network.yml` relocation precedent).
