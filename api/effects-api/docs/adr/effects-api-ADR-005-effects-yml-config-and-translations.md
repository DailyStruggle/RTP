# effects-api-ADR-005 — `effects.yml`: Group-Centric, Stage-Bound Effect Configuration

**Status:** Proposed (2026-05-07; amended 2026-05-07 — directory layout, see *Amendment: Directory Layout & `MultiConfigParser` Fit*)
**Date:** 2026-05-07
**Extends:** [`effects-api-ADR-003`](effects-api-ADR-003-platform-split-bukkit-fabric.md) (does not supersede)
**Related:** [`effects-api-ADR-002`](effects-api-ADR-002-type-driven-reading-order.md), [`effects-api-ADR-004`](effects-api-ADR-004-value-coercer-spi.md)
**Implementation checklist:** `docs/dev/scratch/CHECKLIST-effects-api-platform-split.md` step 11 (11a–11f)

## Amendment: Directory Layout & `MultiConfigParser` Fit

### Why not a single file with admin keys?

`rtp-core`'s `ConfigParser<E extends Enum<E>>` and `MultiConfigParser<E extends Enum<E>>`
both require an enum-typed keyset for the values they expose
(`getConfigValue(E, Object)`, `getMap(E)`, `set(E, Object)`). The original
ADR-005 schema put admin-chosen group names as the *outer* YAML keys of a
single `effects.yml`, which has no enum representation, forcing a bespoke
SnakeYAML parser sitting outside the project's standard config plumbing.

### Layout

Effects ship as a **directory of declarative files** under
`rtp-plugin/src/main/resources/effects/`, exactly like `regions/` and
`worlds/`:

```
rtp-plugin/src/main/resources/
├── effects/
│   ├── default.yml          # hardcoded fallback (when: postteleport)
│   ├── default-pre.yml      # convention: default-<stage> for other stages
│   ├── default-cancel.yml
│   └── <admin-group>.yml    # one file per admin-named group
└── lang/<locale>/effects.lang.yml   # unchanged — maps fixed inner field keys
```

The filename (without `.yml`) **is** the group name. Each file holds the
fixed inner schema:

```yaml
when: postteleport       # required; one of the 11 stage tokens
permission: rtp.x        # optional
players: []              # optional
inherit: [default]       # optional; defaults to stage's default group
effects:                 # required (may be empty for placeholder groups)
  - SOUND.ENTITY_ENDERMAN_TELEPORT.1.0.1.0
version: "1.0"
```

### `MultiConfigParser<EffectsGroupKeys>` binding

```java
public enum EffectsGroupKeys {
    when, permission, players, inherit, effects, version
}
```

`EffectsConfig` (in `effectsapi.common`) wraps a
`MultiConfigParser<EffectsGroupKeys>` rooted at `effects/`. Each YAML file in
the directory becomes one `ConfigParser<EffectsGroupKeys>` keyed by filename;
all five schema fields are pulled via the enum, preserving the per-locale
`effects.lang.yml` field-name translation already shipped (the `lang/<l>/effects.lang.yml`
files map only the inner field keys, which are now the *only* admin-visible
keys, so nothing changes there).

### Hardcoded `default`

The `default` group is guaranteed by the loader, not by the filesystem:

- If `effects/default.yml` is present, it is loaded normally.
- If absent or unparseable, `EffectsConfig.load()` synthesises an in-memory
  `default` group with `when: postteleport` and an empty `effects` list, and
  logs a `messages.yml`-routed warning (S-007).

This guarantees `resolveTokensFor(player, postteleport)` never throws when
no group matches and no file exists — fulfilling the user's "default case is
hardcoded in there" requirement and matching the S-006 contract: never
silently no-op, but also never fail loudly for a missing optional file.

### What stays the same

- Resolution algorithm (`resolveTokensFor(player, stage)`), stage vocabulary
  (11 sites), inheritance + cycle detection, `players:` allowlist semantics,
  union-with-permissions on Bukkit, S-005/S-006/S-007 alignment.
- `EffectFactory.buildEffects(prefix, Collection<String>)` seam — unchanged.
- Per-locale `effects.lang.yml` files — unchanged (they already only mapped
  the inner field set).

### Forward-compat: `rtp test <group>`

Unchanged from the body. The test command resolves a group by exact filename
(minus `.yml`), bypasses gating, applies `inherit:`, and dispatches via
`EffectFactory`.

---

## Context

`effects-api-ADR-003` step 7 wired `FabricEffectsHandler` so that all nine
teleport-pipeline lifecycle hooks attach on Fabric, but landed with an
explicit Phase-1 limitation: **Fabric servers without a permissions manager
have empty `RTPCommandSender#getEffectivePermissions()`, so 0 effects fire
per player.** The Bukkit-style `rtp.effect.<stage>.<token>` permission node
— historically the only way to scope an effect to a player or context — is
unusable on most Fabric deployments.

Beyond Fabric, the permission-only model has an ergonomic ceiling on Bukkit
too: defining a "VIP arrival fanfare" requires an `effect`-shaped permission
node per token (`rtp.effect.postteleport.sound.UI_TOAST_CHALLENGE_COMPLETE.…`),
which is awkward for admins to author and audit, and impossible to express
ordering or inheritance.

A config-driven equivalent that:

- Works without a permission manager (Fabric default).
- Coexists with the existing permission-derived path on Bukkit (purely
  additive; no migration required).
- Reuses the `EffectFactory.buildEffects(prefix, Collection<String>)` seam
  introduced by ADR-003 step 3 (no new factory entry point, no parallel
  parser).
- Stays inside `effectsapi.common` so the `EffectsApiCommonNoPlatformImportsTest`
  (ADR-003 / ADR-004 invariant) continues to pass.

…is the missing piece. This ADR specifies it.

The user-approved schema (over two design rounds in chat) is **group-centric**:
top-level keys are admin-chosen group names; each group declares which stage
it fires at via `when:`, and optionally restricts membership via `permission:`
or a `players:` allowlist. A group literally named `default` must exist.

## Decision

Introduce `effects.yml` with the schema, resolution algorithm, and module
placement specified below. Implementation lands as checklist step 11 (11a–11f);
no `EffectFactory` signature changes.

### Schema

```yaml
# effects.yml — declarative effect groups bound to teleport-pipeline stages.
#
# Each top-level key is a *group name* (admin-chosen, free-form).
# A group declares:
#   when:        which pipeline stage fires it (required)
#   permission:  permission node that gates membership (optional)
#   players:     explicit UUID/name allowlist for permission-less servers (optional)
#   inherit:     other group names whose effects are prepended (optional)
#   effects:     ordered list of <effect-name>.<arg1>.<arg2>... tokens (required)
#
# Token grammar is identical to the suffix of an rtp.effect.<stage>.* permission
# node — see effects-api README and effectsapi/{bukkit,fabric}/LocalEffects/
# enums/* for enumerated effect names and argument orders.
#
# Stage vocabulary (matches code attachment sites in BukkitEffectsHandler /
# FabricEffectsHandler):
#   firstjoin | join | presetup | postsetup | preload | postload
#   | preteleport | postteleport | cancel | queuepush | queuepop
#
# A group named `default` MUST exist. It is the unconditional fallback for
# its declared `when:`. Additional default groups for other stages use the
# convention `default-<stage>` (e.g. `default-cancel`).

version: 1

# ───── required default group(s) ─────

default:
  when: postteleport
  effects:
    - SOUND.ENTITY_ENDERMAN_TELEPORT.1.0.1.0
    - PARTICLE.PORTAL.32

default-pre:
  when: preteleport
  effects:
    - POTION.BLINDNESS.40.0

default-cancel:
  when: cancel
  effects:
    - SOUND.BLOCK_NOTE_BLOCK_BASS.1.0.0.5

# ───── arbitrary admin-named groups ─────

vip-arrival:
  when: postteleport
  permission: rtp.group.vip
  inherit: [default]            # implicit if omitted; list explicitly to override
  effects:
    - SOUND.UI_TOAST_CHALLENGE_COMPLETE.1.0.1.0
    - FIREWORK.BALL_LARGE.RED.WHITE.true.true

staff-departure:
  when: preteleport
  permission: rtp.staff
  inherit: []                   # explicit opt-out of default-pre
  effects:
    - PARTICLE.SMOKE_LARGE.16

queue-poke:
  when: queuepush
  players:                      # permission-less Fabric path
    - 11111111-2222-3333-4444-555555555555
    - SomePlayerName
  effects:
    - SOUND.BLOCK_NOTE_BLOCK_HAT.1.0.1.0
```

Schema rules (validated at load):

- **`when:`** is the only stage reference. Group names never imply a stage.
- **`permission:`** is the only permission-shaped knob. Absent ⇒ ungated
  within its `when:` (alongside `default`).
- **`default` is mandatory** — at minimum, a group literally named `default`
  must exist. For each *other* stage referenced by any group, a corresponding
  default-for-stage must exist (convention: name it `default-<stage>` and
  give it `when: <stage>`). Schema validation fails fast at load if a group
  declares `when: postteleport` but no group with `when: postteleport` is
  marked default-for-stage. The error flows through `messages.yml` (S-007).
- **`players:`** is the permission-less escape hatch. Accepts UUID strings
  and player names. Works on Bukkit too but rarely needed there.
- **`inherit:`** defaults to `[default-for-this-stage]` when omitted. Pass
  `inherit: []` to opt out. Cycles are detected at load (warn-and-skip the
  cyclic edge, fail-soft).
- **Unknown `when:`** values are warn-and-skipped at load with the offending
  group name and the accepted-vocabulary list.
- **Unknown effect names** in tokens are warn-and-skipped at load (validated
  against `EffectFactory.registeredNames()`).

### Resolution algorithm

```
resolveTokensFor(player, stage):
    result = []                                 # ordered, de-duplicated
    matchedNames = []
    for groupName, group in effectsYml.groups():
        if group.when != stage:        continue
        if isDefaultForStage(group):    continue   # applied last via inherit
        if not gates(player, group):    continue
        matchedNames.add(groupName)

    if matchedNames.isEmpty():
        matchedNames.add(defaultGroupName(stage))   # may be "default" or "default-<stage>"

    for name in matchedNames:
        for parent in resolveInherit(name):    # depth-first, cycle-guarded
            result.addAll(parent.effects)
        result.addAll(group(name).effects)

    return result

gates(player, group):
    if group.permission != null and player.hasPermission(group.permission): return true
    if group.players.contains(player.uuid) or group.players.contains(player.name): return true
    if group.permission == null and group.players.isEmpty(): return true   # ungated
    return false
```

Output is `Collection<String>` and feeds directly into the existing
`EffectFactory.buildEffects(prefix, Collection<String>)` seam (ADR-003 step 3).
No factory-side changes.

### Bukkit-side union with permissions

Bukkit retains the existing permission-derived path; `effects.yml` is
**unioned** with it (additive, backwards compatible). A single boolean knob
in `performance.yml` (`effectsConfigOverridesPermissions: false`) lets
admins flip to override semantics if they migrate fully off permissions.
Fabric ignores the knob — `effects.yml` is the sole source.

### Forward-compatibility hook: `rtp test <group>`

Schema is forward-compatible with a future `rtp test <group>` operator
diagnostic that resolves a group by exact YAML name, **bypasses `permission:`
and `players:` gating**, applies `inherit:`, builds the token list against
the issuing player as `EffectTarget`, and dispatches via the existing
`EffectFactory` path. Implementation lands separately under `commands-api`
as a new `RTPCmd` subcommand; this ADR notes it only so the schema is not
re-litigated when that command lands.

### Module placement

| Concern | Location |
|---|---|
| Default `effects.yml` resource | `rtp-plugin/src/main/resources/effects.yml` |
| `EffectsConfig` parser/loader / `resolveTokensFor` | `effectsapi.common` |
| `effects.lang.yml` per locale | `rtp-plugin/src/main/resources/lang/<locale>/` |
| Hot-reload trigger (`/rtp reload`) | `rtp-plugin` |

Rationale:

- `effects-api` is a library and should not ship runtime config defaults.
  `rtp-plugin` already owns `regions.yml`, `messages.yml`, etc.
- `EffectsConfig` itself **must** live in `effectsapi.common` because both
  `BukkitEffectsHandler` and `FabricEffectsHandler` consume it; placing it
  under `rtp-plugin` would force `effects-api → rtp-plugin` callbacks.
- `effectsapi.common` already transitively pulls SnakeYAML through
  `rtp-core`, so no new dependency.
- The `EffectsApiCommonNoPlatformImportsTest` invariant holds:
  `EffectsConfig` must not import `org.bukkit.*` or `net.minecraft.*`. Player
  identity is carried via `RTPPlayer` (`rtp-api`); the "does this player
  match this group" check uses the existing `RTPCommandSender#hasPermission`
  abstraction.

### Safety-rule alignment

- **S-005** — `EffectsConfig` parses YAML on the calling thread of
  `setupEffects()` (startup) and on `/rtp reload`; no chunk I/O, no
  main-thread file I/O once loaded. In-memory map serves all
  `resolveTokensFor` calls thereafter.
- **S-006** — `EffectsConfig.resolveTokensFor` called before load throws
  `IllegalStateException`, matching `EffectFactory.getCoercer`'s S-006
  pattern (ADR-004). Never returns `null` or empty silently.
- **S-007** — All parse / unknown-effect / unknown-stage / missing-default
  / cycle-detected messages flow through `messages.yml` keys
  (`effects.parse_error`, `effects.unknown_effect`, `effects.unknown_stage`,
  `effects.missing_default`, `effects.inherit_cycle`,
  `effects.reload_ok`, `effects.reload_failed`). New `effects.lang.yml` per
  locale (checklist 11d).

### Validation surface (parse-time)

For each token under each group, `EffectsConfig.load` will:

1. Confirm `<effect-name>` is in `EffectFactory.registeredNames()` —
   warn-and-skip with line/column on miss.
2. Dry-clone the prototype and call `setData(args)` — surfaces ADR-002
   type-coercion errors at startup, not at first teleport (ADR-004's
   `ValueCoercer` must be bound first; `EffectsConfig.load` runs *after*
   the platform initializer's `registerAll()` per checklist step 11c).
3. Detect `inherit:` cycles and undefined parent group names.

This collapses what would otherwise be nine sporadic teleport-time warnings
into a single startup log block.

### Stage vocabulary (locked)

`firstjoin`, `join`, `presetup`, `postsetup`, `preload`, `postload`,
`preteleport`, `postteleport`, `cancel`, `queuepush`, `queuepop` — exactly
the attachment sites in `BukkitEffectsHandler` and `FabricEffectsHandler`.
New stages require an ADR amendment plus a parallel attachment in both
handlers.

## Consequences

### Positive

- Fabric admins gain a usable configuration surface for effects (resolves
  the ADR-003 step 7 Phase-1 limitation).
- Bukkit admins gain a more ergonomic alternative to permission-token
  authoring without losing the permission path.
- Schema validation moves error reporting from teleport-time to startup-time.
- `inherit:` makes "extend the default" trivial; `inherit: []` makes
  "replace the default" explicit and auditable.
- `rtp test <group>` is naturally accommodated without further schema
  churn.

### Negative

- One more YAML file for admins to learn. Mitigated by the default
  `effects.yml` shipping a working example covering the most common stages.
- `default-<stage>` convention is mildly awkward compared to a nested
  `<stage>:\n  default:\n    …` shape, but preserves the user-approved
  "groups are first-class, named anything" framing. The alternative was
  considered and rejected in design v2.
- Bukkit semantics now have two sources of truth (permissions + YAML).
  The default is union; admins who want a single source flip
  `effectsConfigOverridesPermissions: true` in `performance.yml`.

### Neutral

- No changes to `EffectFactory`, `Effect`, `ValueCoercer`, or any concrete
  effect class. The seam is purely additive.
- `EffectsApiCommonNoPlatformImportsTest` continues to pass:
  `EffectsConfig` lives in `effectsapi.common` and routes platform
  identity through `RTPPlayer`.

## Alternatives Considered

1. **Stage-keyed schema** (design v1): `postteleport:\n  default:\n    - …\n  vip:\n    - …`.
   Rejected per user direction in favor of group-centric v2 — groups are
   first-class, with `when:` selecting the stage. Avoids the awkward case
   where a group needs to fire on multiple stages (it can't in v1 without
   duplication; in v2 it would be two groups with the same `effects:` and
   different `when:`).
2. **Nested defaults under each stage** (`default:` as a child of each
   stage key). Conflicts with v2's flat group-centric layout.
3. **Single global `default` only**, with stages without their own default
   silently producing no effects. Rejected — typos in `when:` would
   silently disable effects. The mandated default-per-stage rule fails
   loudly instead.
4. **Place `EffectsConfig` in `rtp-plugin`** to keep `effects-api` free of
   YAML parsing. Rejected — `FabricEffectsHandler` (in `rtp-plugin/.../rtp/fabric/effects/`)
   and `BukkitEffectsHandler` both need it; locating it in `rtp-plugin` is
   fine for handlers but `effectsapi.common` is the canonical "shared
   between platforms" home. SnakeYAML is already on the transitive classpath.
5. **Per-file `mode: union | override`** knob (design v1). Rejected in v2:
   admins rarely need per-file granularity; a single `performance.yml`
   boolean is simpler and matches the existing config-knob pattern.

## Implementation order

Follows checklist step 11 (a–f) verbatim:

1. **11a** — Default `effects.yml` resource in `rtp-plugin`.
2. **11b** — `EffectsConfig` loader/parser in `effectsapi.common`, with
   `resolveTokensFor(RTPPlayer, EffectStage)`. Hot-reload mirrors
   `regions.yml` shape.
3. **11c** — Wire `BukkitEffectsHandler` and `FabricEffectsHandler` to feed
   `EffectsConfig.resolveTokensFor(...)` into
   `EffectFactory.buildEffects(prefix, Collection<String>)`. Bukkit unions
   with permission-derived nodes (gated by
   `effectsConfigOverridesPermissions`); Fabric uses YAML alone.
4. **11d** — `effects.lang.yml` per shipped locale (`en`, `zh`, `ko`, …)
   for parse / unknown-effect / unknown-stage / missing-default /
   cycle-detected / reload messages.
5. **11e** — CHANGELOG bullet under `[3.0.0-beta.2] — Unreleased` →
   `### Added`, framed in absolute terms per AGENTS.md *CHANGELOG Hygiene*.
6. **11f** — Tests: schema-fixtures parser test, `resolveTokensFor` matrix
   test (group present / absent / `inherit:` chain / `inherit:` cycle /
   `players:` allowlist hit), hot-reload test. The
   `EffectsApiCommonNoPlatformImportsTest` invariant continues to pass.

The `rtp test <group>` operator command is **not** part of this ADR's
implementation scope — it is documented here only to lock the schema's
forward compatibility.

## References

- `effects-api-ADR-003` — platform-split that introduced the
  `Collection<String>` `EffectFactory` overload this ADR consumes.
- `effects-api-ADR-004` — `ValueCoercer` SPI; `EffectsConfig.load`'s
  dry-clone validation (step 2 above) requires the coercer to be bound
  first.
- `docs/dev/scratch/CHECKLIST-effects-api-platform-split.md` step 11 —
  implementation tracker.
- `BukkitEffectsHandler` / `FabricEffectsHandler` — canonical stage
  attachment sites.
- AGENTS.md *Stay-On-Task Policy* and *CHANGELOG Hygiene* — apply to the
  implementation slice (11a–11f).
