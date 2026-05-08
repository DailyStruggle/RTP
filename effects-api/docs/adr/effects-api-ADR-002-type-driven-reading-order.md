# effects-api-ADR-002 — Type-Driven Adaptive Reading Order in `effects-api`

*(Renumbered from project-wide ADR-030 on 2026-05-05 when subproject ADRs were given per-directory numbering. Prior commits and historical references may still say "ADR-030".)*

**Status:** Accepted
**Date:** 2026-05-05
**Implemented:** 2026-05-05 (`Effect.applyByType` + per-effect `KEY_ORDER` constants in all six bundled effects; tests in `EffectsApiAdaptiveReadingOrderTest`)
**Amended by:** [`effects-api-ADR-004`](effects-api-ADR-004-value-coercer-spi.md) (Accepted, 2026-05-07) — replaces the four `org.bukkit.*` FQNs in §"Type acceptance (`canParse`)" with platform-neutral `TypeKey` constants and adds a "Platform binding" paragraph; the ordering and cache-promotion semantics in this ADR are unchanged. The leaf operations of `canParse` / `parse` / reflective fallback are now per-platform, supplied by a `ValueCoercer` implementation bound at platform-init time (`BukkitValueCoercer` on Bukkit-family, `FabricValueCoercer` on Fabric).

### Implementation note (post-acceptance)

The "record unparsed tokens" requirement is satisfied via an injectable
`Consumer<String>` warn sink on `Effect`, mirroring the
`commands-api`-style "pass in a message function" pattern rather than
wiring `effects-api` to `rtp-core`'s `RTP.log`. The host plugin can
swap the sink with `Effect.setDefaultWarn(...)`; the library default
prints to `System.err` (a strict improvement over the prior
`printStackTrace()` paths in `Effect#str2Obj`/`Effect#fixData`,
consistent with AGENTS.md "Zero `printStackTrace()`"). This keeps
`effects-api` independent of any host-plugin logger while still
satisfying S-004 (no silent failures).

## Context

`effects-api` parses effect arguments through `Effect.setData(String... data)`.
Each `Effect<T>` declares an enum `T` of typed keys (e.g. `FireworkTypeNames`,
`ParticleTypeNames`) whose declaration order doubles as the **positional
order** consumed by `setData(String...)`. This is documented in
`effects-api/src/main/java/io/github/dailystruggle/effectsapi/LocalEffects/Readme.md`:

> Key-order in the table = positional order expected by `setData(String...)`.

Concrete examples of the rigid positional contract:

- `FireworkEffect`: tokens are read as `[TYPE, POWER, FLICKER, TRAIL,
  COLOR, FADECOLOR]`.
- A hypothetical `GlideEffect` (see effects-api-ADR-001 (formerly ADR-029)) is intended to expose
  `[MATERIAL, STARTHEIGHT, WORLD, ...]`.

The rigid order has a practical failure mode that has surfaced repeatedly in
addon configs and in user-supplied permission strings:

- An operator wants only a custom `startHeight` and a `world` filter; they
  omit `material`. With positional parsing, the first token (intended as
  `startHeight`) is misread as `material` and rejected; the parser either
  crashes or silently degrades to defaults for later fields.
- Permission-node round-trips (`toPermission()` / parse) compound the
  problem because users hand-edit those nodes.

What we actually want, and what this ADR specifies, is a **type-driven
adaptive reading order**: when a token does not match the type of the next
expected key, fall through to the next key whose declared type does accept
it. Concretely, given keys `[MATERIAL, STARTHEIGHT, WORLD]` and input
`["64", "world_nether"]`, the parser should assign `STARTHEIGHT=64` (because
`"64"` is not a valid `Material` but is a valid integer) and `WORLD=world_nether`
(because the remaining token is not an integer but is a loaded world name),
rather than failing on `MATERIAL="64"`.

This is **complementary to** but **separate from** effects-api-ADR-001 (formerly ADR-029) (which folds
glide into `effects-api`). effects-api-ADR-001 (formerly ADR-029) introduces fields whose typical
real-world configurations exhibit exactly this "skipped earlier field"
shape, which made the long-standing parsing brittleness acute enough to
warrant its own decision record.

Per Rule D-005, this ADR documents the plan only; implementation requires
explicit approval before code lands.

## Decision

We will replace the strictly-positional `setData(String...)` contract with
a **type-aware, order-preserving, fall-through parser** in `effects-api`.

### Parser contract

For an `Effect<T>` whose enum `T` declares keys `k0, k1, …, kn` with
declared types `Type(k0), Type(k1), …, Type(kn)`, given input tokens
`t0, t1, …, tm`:

1. Initialise a cursor `i = 0` over keys.
2. For each input token `t`:
   - Walk forward from `k_i` until the first key `k_j` (`j ≥ i`) whose
     declared type **accepts** `t` (`canParse(Type(k_j), t) == true`).
   - If found, assign `data[k_j] = parse(Type(k_j), t)`, set `i = j + 1`,
     and continue to the next token.
   - If no remaining key accepts `t`, record an entry in a per-effect
     `unparsedTokens` list and continue. Do not advance `i`.
3. Keys that are never assigned retain their constructor-set defaults
   (already the case today).
4. Order of *acceptance* is preserved: the parser never rewinds (a token
   cannot fill a key earlier than the previous token's key). This keeps
   `toPermission()` round-trips deterministic and avoids combinatorial
   ambiguity.

### Type acceptance (`canParse`)

`canParse(type, token)` is implemented per-type and returns `true` only on
exact, side-effect-free recognition:

- `Material` / similar enums — `Material.matchMaterial(token) != null`.
- `Integer` / `Long` — `token` parses with `Integer.parseInt` / `Long.parseLong`
  and (for keys that declare a range) lies within range.
- `Double` / `Float` — `token` parses and is finite.
- `Boolean` — `token` matches `true|false|yes|no|on|off` (case-insensitive),
  using the existing `Boolean` parser already used elsewhere in
  `effects-api` for symmetry.
- `World` — `Bukkit.getWorld(token) != null` **or** the token equals `*`
  / a configured world filter sentinel.
- `Color` — current `Effect#fixData` color parser returns non-null.
- `Particle`, `Sound`, enum constants — `Enum.valueOf` succeeds (or the
  effect's own `*Names` lookup table accepts it).
- `String` (free-form) — always accepts. Keys typed as free-form `String`
  are therefore "greedy": they will swallow any token that no earlier
  key accepted up to that point. Effect authors are warned in
  `LocalEffects/Readme.md` that free-form `String` keys should appear
  late in the enum order to avoid masking later typed keys.

`canParse` is pure: no Bukkit world load, no chunk I/O, no scheduler call.
This is required for S-005 compliance — config parsing runs on the main
thread today and must remain synchronous and cheap.

### Backwards compatibility

- All existing configs that already provided tokens in the declared order
  continue to parse identically: the fall-through never triggers because
  the very first key always accepts the very first token.
- All existing configs that previously errored on a skipped earlier field
  now succeed if the user's intent matches the next type-accepting key.
  This is a strict superset of previous accepted inputs.
- The pathological case of a token that is genuinely valid for *two*
  consecutive keys (e.g. `"1"` valid as both `int POWER` and `int FLICKER`)
  still resolves left-to-right exactly as today — no semantic change.
- `toPermission()` continues to emit tokens in declared key order.
  Round-tripping a permission node parses bit-identically because every
  emitted token matches its key's type.

### Free-form `String` keys (caveat)

Effects whose keys include a `String`-typed field followed by typed
fields (e.g. a hypothetical `[NAME(String), POWER(int)]`) must reorder
the enum so the `String` key sits at the end, or use a typed wrapper
(e.g. an enum of allowed names). This is a pre-existing latent issue
made explicit by this ADR. `LocalEffects/Readme.md` will be updated to
state: *"`String`-typed keys greedily accept any unmatched token; place
them last in the enum."*

### Failure & safety

- S-004: tokens that match no remaining key are recorded under
  `unparsedTokens` and logged at `WARNING` via `RTP.log` once per
  `setData` call (collapsed list). Never silently dropped.
- S-005: parsing performs no chunk I/O. `World` recognition uses
  `Bukkit.getWorld(name)` which is a map lookup, not a load.
- REQ-RTP-F-013: any user-facing diagnostic string (e.g. an
  `/effectsapi` admin command listing unparsed tokens) is keyed in
  `messages.yml`; nothing hardcoded.

### Testing

- Unit test `EffectsApiAdaptiveReadingOrderTest`:
  - `[MATERIAL, STARTHEIGHT, WORLD]` with input `["64", "world_nether"]`
    yields `STARTHEIGHT=64`, `WORLD=world_nether`, default `MATERIAL`.
  - `[MATERIAL, STARTHEIGHT, WORLD]` with input `["STONE", "64",
    "world_nether"]` yields the same as today (no regression).
  - `[MATERIAL, STARTHEIGHT, WORLD]` with input `["world_nether", "64"]`
    yields `WORLD=world_nether`, `STARTHEIGHT=64` only if `STARTHEIGHT`
    appears *before* `WORLD` in the enum **and** the token order also
    permits it; otherwise records `world_nether` as unparsed for the
    `MATERIAL` slot — i.e. the parser does not rewind.
  - Token `"banana"` produces an `unparsedTokens` entry plus a single
    `WARNING` log line.
- Round-trip test `EffectsApiPermissionRoundTripTest`: every existing
  fixture in `effects-api` parses → `toPermission()` → re-parses
  bit-identically.
- Per-effect smoke tests for `FireworkEffect`, `ParticleEffect`,
  `PotionEffect`, `SoundEffect`, `NoteEffect` confirm no regression.
- `TRACEABILITY.md` rows added once a REQ-* ID is authored for the
  parser contract (out of scope for this ADR).

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Keep strict positional order; require `null` / `-` placeholders | Already supported partially via `Effect#fixData`, but operators do not use it; UX remains poor. Forcing placeholders in permission nodes is hostile to hand-editing. |
| Switch to a fully named-key parser (`material=STONE startHeight=64`) | Larger UX break; permission-node format is positional today and round-tripping breaks for everyone. Could be added later as an additive syntax on top of the fall-through parser. |
| Backtracking parser (try every assignment, pick the most-fields-filled) | Combinatorial blow-up for effects with many keys; non-deterministic round-trips when two parses fill the same number of fields; harder to debug. |
| Fall-through but allow rewind (token may fill an earlier key) | Breaks round-trip determinism: a single token list can produce two valid assignments depending on which key the parser tries first. Rejected. |
| Per-effect bespoke parsers | Duplicates logic; every new effect (including `GlideEffect` from effects-api-ADR-001 (formerly ADR-029)) re-implements the same fall-through. The contract belongs in `Effect` / `EffectFactory`. |

## Consequences

- **Positive:**
  - Operators can omit optional earlier fields without shifting later
    fields out of place; the most common config-error class disappears.
  - effects-api-ADR-001's `GlideEffect` (with `[MATERIAL, STARTHEIGHT, WORLD, ...]`)
    becomes pleasant to configure: dropping `material` no longer
    breaks `startHeight`.
  - Permission-node round-trips remain deterministic.
  - Diagnostic surface improves: unparsed tokens are surfaced in logs
    instead of vanishing into wrong slots.
- **Negative / Trade-offs:**
  - One-time documentation task: `LocalEffects/Readme.md` must call out
    the "free-form `String` keys go last" rule, and the `effects-api`
    README's parsing section needs a paragraph on the new contract.
  - Subtle semantic change for ill-typed effect enums that placed
    `String` keys early — those will start swallowing tokens. Audit
    of bundled `LocalEffects/*` enums is part of the implementation
    task; none of the current bundled effects appear affected.
  - Parser cost grows from O(tokens) to O(tokens × remainingKeys) in
    the worst case. Both are tiny constants; negligible at runtime.

## References

- Existing parser contract: `effects-api/src/main/java/io/github/dailystruggle/effectsapi/Effect.java`
  (`setData(String...)`), `EffectFactory.java`.
- Positional-order documentation:
  `effects-api/src/main/java/io/github/dailystruggle/effectsapi/LocalEffects/Readme.md`.
- Bundled effects whose enums define the canonical key order:
  `LocalEffects/FireworkEffect.java`, `ParticleEffect.java`,
  `PotionEffect.java`, `SoundEffect.java`, `NoteEffect.java`.
- Companion ADR motivating the immediate need: `effects-api-ADR-001-glide-effect.md`.
- Safety rules: `.junie/AGENTS.md` → *Prohibition Requirements (S-00x)*,
  in particular S-004 (no silent failure) and S-005 (no main-thread
  chunk I/O).
- Architectural placement: `.junie/AGENTS.md` → *Architecture Boundaries*
  (effects-api owns this contract; not `rtp-core` / `rtp-api`).
- Message configurability: REQ-RTP-F-013 in `docs/dev/REQUIREMENTS.md`.
