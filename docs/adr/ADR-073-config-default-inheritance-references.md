# ADR-073 - Config Default Inheritance via `@<file>` References

**Status:** Accepted
**Date:** 2026-06-23

## Context

Recurring operator feedback (the same public thread that motivated
[ADR-071](ADR-071-config-organization-and-discoverability.md)) asks for
"everything related to teleportation in one place" and an easier first-run
experience. ADR-071 addressed file-tier discoverability and relevance ordering,
but one concrete gap remains: the single knob a first-time operator most wants to
change - **how far out players land** - is not a global setting. It lives as
`shape.radius` (and `shape.centerRadius`) inside `regions/default.yml`, because
distance is inherently a per-region property. A server running a single world
still has to open a region file to change it, and a competitor like BetterRTP
exposes that same knob on its top-level config screen.

The naive fix - hoist a global `radius` scalar into `config.yml` - is wrong,
because RTP's shape and vertical-window selectors are a pluggable type catalog
(see [ADR-034](ADR-034-memory-shape-catalog.md) and
[ADR-009](ADR-009-configurable-spatial-distributions.md)): `Circle` has
`radius`/`centerRadius`, `Circle_Normal` adds a Gaussian sigma, `Ellipse` adds a
second semi-axis `radius2`, `Square`/`Polygon` carry their own parameters, and
the `vert` adjustors (`LINEAR`, etc.) have their own keys. A bare global `radius`
is meaningless without a named type, and inheriting an individual sub-key (a
`CIRCLE`'s `radius`) into a region of a different type (`ELLIPSE`) would produce
an internally inconsistent shape.

Two classes of region/world key therefore behave differently:

1. **Type-bearing blocks** (`shape`, `vert`) - their parameter set is only valid
   as a unit, relative to the block's `name`.
2. **Type-free scalars** (region `price`, `cacheCap`, `backlogCacheCap`,
   `activeChunkCap`, `spatialResolution`, world `requirePermission`) - meaningful
   independent of any type.

The existing per-instance file model is correct and matches the field (Paper's
`paper-world-defaults.yml` -> per-world override, HuskHomes, BetterRTP's
per-world split). What is missing is an inheritance seam so the common case
collapses back to one global knob without abandoning per-region power.

Any change here is bound by the same parity and migration contracts as ADR-071:
locale parity ([ADR-020](ADR-020-language-bootstrap-and-locale-aware-configparser.md)),
comment-as-hover ([ADR-064](ADR-064-config-comment-format-summary-line-as-menu-hover.md)),
and the non-destructive read-legacy-warn migration discipline (ADR-071 rule 4).

## Decision

Introduce a **config default inheritance** mechanism: selected region/world
settings may carry a `@<file>` reference token that resolves to a global default
defined in the file the token names (`config.yml`, `economy.yml`, ...), instead of
a literal value. The shipped `regions/default.yml` and `worlds/default.yml` use
the reference as their out-of-box value so the wiring is visible and the global
knob is the one an operator edits first.

### 1. Reference token names the source file

The reference token is `@<file>`, where `<file>` is the base name (no extension)
of the resource that owns the corresponding global default. The token therefore
points explicitly at the file the resolver must read - it is not a bare "inherit
from config" marker. Examples:

- `@config` resolves from `config.yml#defaults`,
- `@economy` resolves from `economy.yml`,
- `@safety` resolves from `safety.yml`.

This matters because the global default for a given setting does not always live
in `config.yml`: the default teleport `price` belongs in `economy.yml`, the
default safety radius in `safety.yml`, and so on. A region setting that wants to
inherit `price` therefore writes `price: "@economy"`, not `price: "@config"` - the
token must name the file so the resolver knows which parser to ask. Each setting
has exactly one legal source file (the file that owns that concern), so the
`@<file>` form is unambiguous: a value whose string is a recognized `@<file>`
token resolves from that file's corresponding global default; any other value is a
literal and overrides locally (current behavior).

There are no aliases for a given file (one canonical base name per file), keeping
parsing and the menu unambiguous.

### 2. Type-bearing blocks inherit at the named-block level; scalars individually

- `shape` and `vert` inherit as **whole named blocks**. A region sets
  `shape: "@config"` to adopt the entire global `defaultShape` block (its `name`
  and every type-specific parameter together). Per-key inheritance of shape/vert
  sub-parameters is **not** offered, because a parameter set is only coherent as a
  unit for a given type.
- Type-free scalars may carry a `@<file>` token individually, naming the file
  that owns that scalar's global default. Per setting:
  - region `price` -> `@economy` (the default price lives in `economy.yml`),
  - `cacheCap`, `backlogCacheCap`, `activeChunkCap`, `spatialResolution` ->
    `@config` (their defaults live in `config.yml#defaults`),
  - world `requirePermission` -> `@config`.
  The `shape`/`vert` blocks inherit via `@config` (their templates live in
  `config.yml#defaults`, see below).

### 3. Global defaults live in the file that owns the concern

Each referenceable setting's global default lives in the file the `@<file>` token
names - the default is not centralized into `config.yml`. `config.yml` gains a
`defaults` section for the settings it owns (the type-bearing blocks and the
config-owned scalars); the `economy`/`safety`/etc. defaults stay in their own
files.

```yaml
# config.yml
defaults:
  shape:
    name: "CIRCLE"
    radius: 5000
    centerRadius: 0
    # ... remaining CIRCLE params
  vert:
    name: "LINEAR"
    minY: 32
    maxY: 255
    direction: 2
    requireSkyLight: true
  cacheCap: 50
  backlogCacheCap: 1000
  activeChunkCap: 10
  spatialResolution: 3
  requirePermission: false
```

```yaml
# economy.yml
defaultPrice: 0.0   # resolved by a region's  price: "@economy"
```

A region writes `price: "@economy"` to inherit the economy file's default price,
`shape: "@config"` to inherit the config file's default shape block, and so on -
the token always names the file that holds the value.

### 4. Shipped defaults use the reference

`regions/default.yml` and `worlds/default.yml` ship with `shape: "@config"`,
`vert: "@config"`, region `price: "@economy"`, and the remaining referenceable
scalars set to their owning file's token (`@config`), so a fresh install
demonstrates the mechanism and the operator changes distance once, in
`config.yml`, and price once, in `economy.yml`. Custom regions remain free to
inline literal blocks/values.

### 5. Resolve at read time, fail safe

References resolve when the parser reads the value, not by rewriting files: a
`@<file>` token is resolved by reading the named file's corresponding global
default. If a reference names an unknown file, or resolves to a missing or blank
global default, the loader falls back to a hard-coded sane default and emits
`RTP.log(Level.WARNING, ...)` rather than throwing or producing a zero-radius
teleport. Reference cycles are impossible because the files that own defaults
(`config.yml`, `economy.yml`, ...) may not themselves carry `@<file>` tokens.

### 6. Non-rewriting migration and version bump

Existing installs carry literal values (e.g. `radius: 256`); these are left
exactly as-is - the mechanism is opt-in and only the *bundled* default ships as a
reference, so no operator's tuned value is silently changed (ADR-071 rule 4 in
spirit). The new value-type on `shape`/`vert`/the referenceable scalars is a
structural change, so `regions/default.yml`, `worlds/default.yml`, and
`config.yml` each get a `version` bump (ADR-071 rule 6).

### 7. Menu representation

The region/world key editor renders a referenceable row in one of two states:
"inheriting (`<resolved value>` from config)" versus "overridden (`<literal>`)",
with a toggle to switch between them rather than a free-text field, so an operator
cannot type an invalid token. This reuses the existing per-parser key page
(ADR-071 rule 7) and the comment-as-hover contract (ADR-064).

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Hoist a flat global `radius` scalar into `config.yml` | Meaningless without a named shape type; inheriting a `CIRCLE` radius into an `ELLIPSE` region yields an inconsistent shape. The type-bearing nature of `shape`/`vert` forces block-level inheritance. |
| Per-key inheritance of shape/vert sub-parameters | Same coherence problem - a parameter set is only valid as a unit for its type; mixing inherited and local sub-keys across types produces invalid shapes. |
| Rewrite existing region files to references on upgrade | Strands or silently changes operators' tuned values; violates the non-destructive migration discipline (ADR-071 rule 4). |
| A single bare `@config` token for everything (resolve all defaults from `config.yml`) | Not every setting's default belongs in `config.yml` - the default `price` lives in `economy.yml`, the default safety radius in `safety.yml`. A token that always meant `config.yml` would force unrelated defaults to be duplicated there, breaking the split-by-concern ownership ADR-071 preserves. The `@<file>` form names the owning file so each default stays in its own file. |
| Multiple aliases per file (`default`, `inherit`, `@config`) | Ambiguity in both parsing and the menu; one canonical base name per file is simpler and testable. |
| Full Paper-style cascading inheritance (defaults -> world -> region) | Larger blast radius than the feedback warrants; a single `config.yml` default tier solves the reported problem. A deeper cascade can supersede this ADR later if demanded. |
| Do nothing (leave radius per-region only) | Leaves the headline first-run knob behind a region file, which is the exact ease-of-use gap the feedback identifies. |

## Consequences

- **Positive:** The headline "how far out" knob (and the other common defaults)
  becomes a single global setting for the common single-world case, matching the
  BetterRTP first-run experience, while per-region override is fully preserved.
- **Positive:** Block-level inheritance for `shape`/`vert` keeps every resolved
  region internally consistent regardless of the selected type.
- **Positive:** The mechanism is opt-in and non-destructive; existing tuned
  installs are untouched.
- **Negative / Trade-offs:** Adds a value-type (literal-or-reference) to selected
  region/world keys, requiring the region parser to resolve references at read
  time and the menu to render an inherit/override toggle.
- **Negative / Trade-offs:** A second place (`config.yml#defaults`) now defines
  shape/vert defaults; the resolution rule and fail-safe must be documented so
  operators understand precedence (literal overrides reference; missing global
  falls back with a warning).
- **Negative / Trade-offs:** Locale/parity surface grows slightly (new
  `config.yml#defaults` keys and their comments mirror through the TSV pipeline).

## References

- [ADR-071](ADR-071-config-organization-and-discoverability.md) Config Organization and Discoverability (the discoverability thread this extends).
- [ADR-034](ADR-034-memory-shape-catalog.md) Memory Shape Catalog and Polygon Shape (shape type catalog and per-type parameters).
- [ADR-009](ADR-009-configurable-spatial-distributions.md) Configurable Spatial Distributions (Flat/Normal/Exponential).
- [ADR-020](ADR-020-language-bootstrap-and-locale-aware-configparser.md) Language Bootstrap and Locale-Aware ConfigParser.
- [ADR-064](ADR-064-config-comment-format-summary-line-as-menu-hover.md) Config-Comment Format: Summary Line as Menu Hover Text.
- [ADR-065](ADR-065-world-override-region-and-world-menu.md) World-Override Regions and the `/rtp` World Menu.
- `rtp-plugin/src/main/resources/`: `config.yml`, `economy.yml`, `regions/default.yml`, `worlds/default.yml`.
