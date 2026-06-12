# Config Comment Style

Conventions for comments that document options in shipped YAML config files
(e.g. `config.yml`, `safety.yml`, `performance.yml`, `economy.yml`,
`logging.yml`, `messages.yml`, per-region and per-world configs, and the
`lite` variants under `rtp-plugin/src/lite/resources/`).

These rules apply to admin-facing config resources only. Internal Java/Kotlin
code comments and KDoc are unaffected.

The governing decision is recorded in
[ADR-064](../adr/ADR-064-config-comment-format-summary-line-as-menu-hover.md);
this document is its canonical, example-rich style guide. The key architectural
constraint: the **first comment line** of each option doubles as the option's
**hover tooltip** in the config menu (ADR-035 / ADR-044), so it must read as a
standalone summary.

## Rule (the pattern)

Every documented option **shall** be preceded by a comment block with two
parts, in this order:

1. **Summary line** — exactly one line, starting with a capital letter,
   stating *what the option does*. No trailing period required. Aim for
   under ~80 characters.
2. **Details block** — zero or more follow-up comment lines giving the
   information an admin needs to actually set the value. Required content
   when applicable:
   - **Valid options** — enumerate them (`"yaml"`, `"sqlite"`, `"mysql"`,
     `"postgresql"`), give the type and range (`integer, >= 0`,
     `seconds, 0 disables`), or point at the authoritative list (tag
     resolver, Bukkit Material enum, etc.).
   - **Default** — only if it differs from the shipped value on the next
     line, or if the shipped value is platform-conditional.
   - **Units** — seconds, blocks, ticks, bytes, percent, etc.
   - **Side effects / dependencies** — other options this interacts with,
     S-00x prohibitions it touches, or admin docs to read first.

If an option is fully self-explanatory from its key name and a single
sentence, the details block may be omitted — the summary line alone is
enough.

## Machine-readable enumeration tags (for the menu system)

Admin-facing menus (the generalized menu framework, ADR-035 / ADR-044) need
to *programmatically* discover the valid values, type, and range of each
option without re-parsing English prose. To support this, the details
block **may** include one or more structured directive lines. Each
directive lives on its own comment line, starts with `# @`, uses
`key: value` form, and the value is YAML-flow syntax (so the existing
SnakeYAML parser can consume it directly).

Reserved directive keys:

- `@type` — one of `enum`, `integer`, `number`, `boolean`, `string`,
  `material`, `biome`, `world`, `tag`, or a `list<…>` of any of the
  preceding (e.g. `list<material>`, `list<string>`). Required when any
  other directive is present.
- `@options` — exhaustive YAML-flow list of valid scalar values for
  `enum`-typed options. Strings are quoted in the list exactly as they
  must appear in the YAML. Example: `# @options: ["yaml", "sqlite",
  "mysql", "postgresql"]`.
- `@range` — `[min, max]` for `integer` / `number`. Either bound may be
  `null` to denote open-ended. Example: `# @range: [0, null]`.
- `@unit` — short token: `seconds`, `ticks`, `blocks`, `chunks`,
  `percent`, `bytes`, `ms`. Free-form prose units stay in the prose
  details block, not here.
- `@default` — the canonical default value (YAML scalar). Only emit when
  the shipped value differs from the default or the default is
  platform-conditional.
- `@source` — when the valid values come from a runtime registry rather
  than a fixed enumeration, name the registry: `material` → Bukkit
  `Material` enum, `biome` → Bukkit `Biome` registry, `world` → loaded
  worlds, `tag` → `#minecraft:<tag>` resolver. Mutually exclusive with
  `@options`.

Rules:

- Directive lines **shall** appear *after* the prose summary line and
  before the key they document. They may be interleaved with prose
  detail lines; menus ignore prose, prose readers ignore directives.
- Directive values **shall** be valid YAML-flow expressions so that
  parsing the substring after `: ` with a YAML loader yields the
  intended Java value. Do not invent ad-hoc syntax.
- An option without directives is treated by the menu as a free-form
  text field — acceptable for genuinely unconstrained strings, but
  prefer to add `@type` + (`@options` | `@range` | `@source`) for
  anything an admin might mis-type.
- Do not duplicate prose: if `@options: ["yaml", "sqlite", "mysql",
  "postgresql"]` is present, the prose may omit the enumeration and
  just describe semantics ("file-backed vs. networked", etc.).

### Examples

Enum-valued:

```yaml
# Database backend used for persistence.
# yaml/sqlite are file-backed and require no extra config; mysql and
# postgresql use the host/port/name/username/password fields below.
# @type: enum
# @options: ["yaml", "sqlite", "mysql", "postgresql"]
# @default: "sqlite"
database:
  type: "sqlite"
```

Bounded integer with unit:

```yaml
# Wait time before a queued teleport executes.
# 0 teleports immediately (skips the cancel-on-move window).
# Interacts with cancelDistance below.
# @type: integer
# @range: [0, null]
# @unit: seconds
# @default: 2
teleportDelay: 2
```

Boolean:

```yaml
# Refill the region cache immediately after a successful teleport.
# @type: boolean
# @default: false
postTeleportQueueing: false
```

Registry-sourced scalar:

```yaml
# Material used for the emergency landing platform.
# @type: material
# @source: material
platformMaterial: GLASS
```

Registry-sourced list (with optional tag references):

```yaml
# Blocks treated as passable air for landing-safety checks.
# Accepts Material names and #minecraft:<tag> references.
# @type: list<material>
# @source: material
airBlocks:
  - AIR
  - CAVE_AIR
```

Bounded fraction:

```yaml
# Probability of rejecting an ungenerated chunk per selection attempt.
# 0.0 = no bias, 1.0 = strictly avoid ungenerated land. Generated
# chunks always pass.
# @type: number
# @range: [0.0, 1.0]
# @default: 0.0
pregeneratedPreference: 0.0
```

### Parsing contract

The menu adapter **shall** treat the directive grammar as the single
source of truth:

1. Collect contiguous `#`-prefixed lines immediately above a key.
2. For each line matching `^#\s*@(\w+):\s*(.*)$`, parse the captured
   value with a YAML scalar/flow loader; reject (log + skip the option
   for the menu) on parse failure.
3. Validate consistency: `@options` requires `@type: enum` (or
   `list<enum>`); `@range` requires `@type: integer` or `number`;
   `@source` and `@options` are mutually exclusive.
4. Prose lines (non-directive `#` lines) are concatenated into a
   tooltip string in source order and surfaced to the admin verbatim.

Directives are intentionally minimal so the same grammar can be reused
by future tooling (config validators, `/rtp config set` autocompletion,
docs generators) without further extension.

## Layout

- Put the comment block immediately above the key it documents, with no
  blank line between the last comment line and the key.
- Separate adjacent documented options with a single blank line; this
  prevents the previous option's details from visually bleeding into the
  next option's summary.
- For grouped/nested keys, comment the parent map with a summary, then
  comment each child key the same way. Do not document children only via
  a single block above the parent.
- For list-valued options (`airBlocks:`, `unsafeBlocks:`, `biomes:`), the
  comment block goes above the key. Inline `# note` comments on individual
  list entries are allowed for entry-specific caveats (e.g. why a tag was
  excluded), but the option's own summary + details belong above the key.

## Examples

### Good — summary + details + valid options

```yaml
# Database backend used for persistence.
# Valid options: "yaml", "sqlite", "mysql", "postgresql".
# yaml/sqlite are file-backed and require no extra config; mysql/postgresql
# use the host/port/name/username/password fields below.
database:
  type: "sqlite"
```

### Good — summary + units + range

```yaml
# Wait time before a queued teleport executes.
# Units: seconds. Integer >= 0. 0 teleports immediately (skips the
# cancel-on-move window). Interacts with cancelDistance below.
teleportDelay: 2
```

### Good — summary only (self-explanatory)

```yaml
# Material used for the emergency landing platform.
platformMaterial: GLASS
```

### Good — list option with inline note on a specific entry

```yaml
# Blocks treated as passable air for landing-safety checks.
# Accepts Bukkit Material names and #minecraft:<tag> tag references.
airBlocks:
  - AIR
  - CAVE_AIR
  # Note: #minecraft:tall_flowers is an item-only tag in vanilla; its
  # members are already covered by #minecraft:flowers above.
  - "#minecraft:flowers"
```

### Bad — terse one-liner with no valid options

```yaml
# database type
type: "sqlite"
```

### Bad — details block with no summary line

```yaml
# Valid options: "yaml", "sqlite", "mysql", "postgresql". yaml/sqlite are
# file-backed; mysql/postgresql require the host/port fields below.
database:
  type: "sqlite"
```

### Bad — blank line between comment and key

```yaml
# Wait time before a queued teleport executes.
# Units: seconds.

teleportDelay: 2
```

## File header

The first comment block of a config file is a file-level header, not an
option comment. It **shall** contain:

1. A one-line title (`# --- RTP Safety Configuration ---`).
2. A link to the admin docs (`# Documentation: …/docs/admin/SAFETY.md`).

The file-level header is separate from the first option's summary line.

## Version sentinel

The `version:` key at the bottom of each config file is reserved for the
config-migration system. Its existing `# DO NOT TOUCH VERSION NUMBER`
comment is the canonical form; do not reword it.

## When updating existing files

- Touching an option's value is **not** a license to rewrite its comment;
  apply this style only when you are already editing that option's lines,
  introducing a new option, or the existing comment is actively misleading.
- Bulk reformat passes across a whole config file should be a dedicated
  change, not bundled with a behavior change. See *Stay-On-Task Policy* in
  `.junie/AGENTS.md`. For the priority order in which files should be brought
  into this format with minimal translation cost, see
  [`CONFIG_COMMENT_MINIMIZATION.md`](CONFIG_COMMENT_MINIMIZATION.md).
- When in doubt about valid options, link to the relevant admin doc
  (`docs/admin/CORE_CONFIG.md`, `docs/admin/SAFETY.md`, etc.) rather than
  duplicating an enumeration that will drift.

## Encoding

Config files are UTF-8, no BOM, LF line endings — same as markdown. See
*Markdown Encoding Hygiene* in `.junie/AGENTS.md` for the mojibake rules;
they apply identically to YAML comments.
