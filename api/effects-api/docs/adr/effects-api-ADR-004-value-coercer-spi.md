# effects-api-ADR-004 — `ValueCoercer` SPI: Per-Platform Type Binding for the Adaptive Reading Order

**Status:** Accepted (2026-05-07)
**Date:** 2026-05-06
**Amends:** [`effects-api-ADR-002`](effects-api-ADR-002-type-driven-reading-order.md) (does not supersede)
**Refines:** [`effects-api-ADR-003`](effects-api-ADR-003-platform-split-bukkit-fabric.md) step 3 (de-Bukkit-ifying `Effect`)

---

## Context

`effects-api-ADR-003` (Accepted 2026-05-06) split `effects-api` internally into
`effectsapi/common`, `effectsapi/bukkit`, and `effectsapi/fabric` subpackages.
Step 3 of the implementation checklist landed `Effect` and `EffectFactory` in
`effectsapi.common`, but **the body of `Effect.java` still imports
`org.bukkit.{Color, Sound, Particle, PotionEffectType}`** because
`effects-api-ADR-002`'s adaptive reading order
(`Effect#fixData` / `Effect#str2Obj` / `Effect#canParse` /
`Effect#resolveSound` / `Effect#resolveNamedColor` /
`Effect#resolveViaRegistry`) was authored against those Bukkit types as the
canonical leaf parsers. That kept Phase 1 of ADR-003 small but means
`effectsapi.common` cannot yet pass a "no `org.bukkit.*` imports" bytecode
test, and Fabric currently has no ADR-002-compliant path at all.

The user question that triggered this ADR — *"can ADR-002 still work as it
did with variant behavior on each platform via supplier or similar?"* —
answers itself once the three responsibilities ADR-002 actually bundles are
separated:

1. **Ordering & cache policy** — try declared-type predicates in declared
   order, promote the winner per `(effectName, fieldName)` key. Pure logic;
   no platform types. The regression guard
   `EffectsApiAdaptiveReadingOrderTest` exercises only this.
2. **Type predicates / parsers** — `canParse(type, raw)` and
   `parse(type, raw)`. Today implemented with `instanceof`-switches over
   `Color.class`, `Sound.class`, `Particle.class`, etc.
3. **Registry resolvers** — `Registry.SOUNDS.get(NamespacedKey.minecraft(raw))`
   and friends.

(1) is platform-neutral. (2) and (3) are the only platform-bound parts.
Therefore ADR-002 can keep working unchanged on each platform if the
leaf operations become a per-platform supplier.

Per Rule D-005, this ADR is `Proposed` until accepted; no code lands until
then.

## Decision

Introduce a `ValueCoercer` SPI in `effectsapi.common.spi` that owns the
per-platform leaf operations of ADR-002's adaptive reading order. `Effect`
moves from `instanceof`-on-`org.bukkit.*` switches to delegating those
checks and parses to a `ValueCoercer` instance bound at platform-init time
by `BukkitEffectsInitializer.registerAll()` (Bukkit) and
`FabricEffectsInitializer.registerAll()` (Fabric).

### SPI shape

```java
package io.github.dailystruggle.effectsapi.common.spi;

public interface ValueCoercer {
    /** Cheapest, side-effect-free check: can the raw token be read as the given logical type? */
    boolean canParse(TypeKey type, String raw);

    /** Parse the raw token into the platform-native object the concrete effect consumes. */
    Object parse(TypeKey type, String raw);

    /** Declared reading order for this platform; drives ADR-002's adaptive ladder. */
    java.util.List<TypeKey> readingOrder();
}
```

`TypeKey` is a closed enum (sealed against drift) in
`effectsapi.common.spi`:

```java
public enum TypeKey {
    SOUND, PARTICLE, COLOR, POTION_EFFECT, MATERIAL, WORLD,
    STRING, INT, LONG, DOUBLE, FLOAT, BOOLEAN
}
```

### Binding

- `EffectFactory` exposes a single static setter:
  `EffectFactory.setCoercer(ValueCoercer)`. Idempotent; second call replaces
  the first (initializers are themselves idempotent per ADR-003).
- `BukkitEffectsInitializer.registerAll()` calls
  `EffectFactory.setCoercer(new BukkitValueCoercer())` **before** the
  six legacy registrations.
- `FabricEffectsInitializer.registerAll()` calls
  `EffectFactory.setCoercer(new FabricValueCoercer())` before any Fabric
  registrations.
- If `Effect` is asked to coerce before any initializer runs (addon
  misordering), it throws `IllegalStateException` per S-006 — never null,
  never silent (S-004).

### Concrete coercers

- `effectsapi.bukkit.BukkitValueCoercer implements ValueCoercer` — wraps the
  current `Effect#resolveSound` / `Effect#resolveNamedColor` /
  `Effect#resolveViaRegistry` / `Effect#canParse` / `Effect#str2Obj`
  bodies verbatim, just relocated. No behavioural change on Bukkit.
- `effectsapi.fabric.FabricValueCoercer implements ValueCoercer` — uses
  `Registries.SOUND_EVENT.get(ResourceLocation.tryParse(raw))`,
  `BuiltInRegistries.PARTICLE_TYPE`, `MobEffects` lookup, etc. Phase-1
  scope: `SOUND`, `PARTICLE`, `POTION_EFFECT`, plus the type-agnostic
  primitives (`STRING`/`INT`/`LONG`/`DOUBLE`/`FLOAT`/`BOOLEAN`,
  identical to Bukkit). `COLOR`, `MATERIAL`, `WORLD` may return
  `Optional.empty()` / `false` from `canParse` until concrete Fabric
  effects need them (deferred to Phase 2).

### Effect body changes

Net delta inside `Effect.java`:

- Replace each `instanceof Color`/`Sound`/`Particle`/`PotionEffectType`
  branch in `fixData`/`str2Obj`/`canParse` with a `TypeKey` lookup
  (`Map<Class<?>, TypeKey>` populated lazily per `defaults` map; the lookup
  is the only place ADR-004 touches reflection).
- Delete `resolveSound`, `resolveNamedColor`, `resolveViaRegistry`. The
  bodies move into `BukkitValueCoercer` unchanged.
- Remove `org.bukkit.*` imports from `Effect.java`.
- Keep `extends BukkitRunnable` deferred to a future ADR (out of scope
  here — ADR-003 step 3 is unblocked without it; concrete effects on
  Bukkit still extend `Effect` and `runTask(plugin)`; Fabric concrete
  effects implement `Runnable` and are scheduled via `EffectRuntime`).

### What ADR-002 needs to say (amendment, not supersedence)

ADR-002 is amended in two places only:

1. §"Type acceptance (`canParse`)" — replace the four `org.bukkit.*` FQNs
   with the equivalent `TypeKey` constants (`SOUND`, `PARTICLE`, `COLOR`,
   `POTION_EFFECT`). The semantic descriptions ("non-null lookup",
   "registry hit", etc.) carry over verbatim.
2. New one-paragraph §"Platform binding": *"On any platform, the concrete
   object returned by `ValueCoercer.parse` is the type the platform's
   `Effect` subclasses consume. ADR-002 governs the **order** and the
   **cache-promotion** rule; the **binding** of `TypeKey` to a runtime
   object is per-platform and lives in the `ValueCoercer` implementation
   selected by the platform initializer (see ADR-004)."*

The "Implementation note (post-acceptance)" header at the top of ADR-002
gains one bullet pointing at this ADR.

### Cache invariants preserved

The `(effectName, fieldName) → winningTypeKey` cache that
`EffectsApiAdaptiveReadingOrderTest` pins survives unchanged:

- The cache key remains `(effectName, fieldName)`.
- The cache value's *type* changes from `Class<?>` (Bukkit class literal)
  to `TypeKey` (platform-neutral). The test does not inspect the value
  type — it asserts only that the second parse skips predicates that
  failed the first.
- Determinism per platform is now contractual: `ValueCoercer.canParse`
  must be a pure function of `(type, raw)`. Documented in the SPI
  Javadoc.

## Alternatives Considered

1. **Supersede ADR-002 with a string-keyed model.** Rejected — would force
   every concrete effect (`SoundEffect`, `ParticleEffect`,
   `PotionEffect`, `FireworkEffect`, `NoteEffect`, `GlideEffect`) to
   carry its own string-to-object resolver, duplicating the registry
   lookups ADR-002 deliberately centralised. Also breaks the existing
   test asset `EffectsApiAdaptiveReadingOrderTest` and every addon's
   `defaults` map.
2. **Push all coercion into per-platform `Effect` subclasses, keep
   `defaults` typed per platform.** Rejected — duplicates the reading
   order ladder per platform and makes the regression guard
   platform-specific. Also reintroduces the `org.bukkit.*` imports the
   ADR-003 split set out to remove.
3. **Keep `Effect`/`EffectFactory` Bukkit-typed under `effectsapi/bukkit/`
   and let Fabric provide a parallel `FabricEffectFactory` (option (iv)
   in the ADR-003 step-3 menu).** Currently shipped as the interim
   posture. Works, but doubles the surface area, prevents
   `effectsapi.common` from passing a "no platform imports" bytecode
   test, and leaves Fabric without an ADR-002 path. Acceptable as a
   bridge; not acceptable as the end state.
4. **Use Java's `ServiceLoader` instead of an explicit setter.** Rejected
   — initializer order is part of the public API surface (ADR-003 makes
   `BukkitEffectsInitializer.registerAll()` the only documented entry
   point), and `ServiceLoader` makes that order observably non-explicit
   on shaded jars.

## Consequences

### Positive

- `effectsapi.common` becomes free of `org.bukkit.*` imports — the
  bytecode test queued in ADR-003 step 9b can be written and run.
- ADR-002's adaptive reading order works on Fabric without a single
  text change to ADR-002's algorithm description.
- Fabric gains an ADR-002-compliant parser path the day
  `FabricValueCoercer` lands (Phase 1: `SOUND` / `PARTICLE` /
  `POTION_EFFECT` cover the four planned Fabric concrete effects).
- The existing `EffectsApiAdaptiveReadingOrderTest` becomes the
  cross-platform regression guard for free.
- `EffectFactory.setCoercer(...)` is the only new public API; one
  method, fail-fast on misuse.

### Negative / costs

- One new SPI surface (`ValueCoercer` + `TypeKey`) addons may consume.
  Documented, versioned with `effects-api`.
- `Effect.java` body shrinks but every concrete effect still has to
  understand the platform-native object returned by
  `ValueCoercer.parse(...)`. This is unavoidable on any platform-split
  design; `ValueCoercer` does not pretend to make concrete effects
  platform-portable, only `Effect`/`EffectFactory`.
- Addon authors who reflectively reached into `Effect#str2Obj` /
  `Effect#resolveSound` (none known in-tree, none documented in
  `EXTERNAL_HOOKS.md`) will break. CHANGELOG entry required.

### Neutral

- The interim Bukkit-typed `BukkitEffectsInitializer.buildEffects(...)`
  shipped with ADR-003 step 3 stays — it's an addon-facing convenience
  layer, orthogonal to coercion.

## Implementation order (informational; lands in a follow-up checklist)

1. `effectsapi/common/spi/TypeKey.java` (enum).
2. `effectsapi/common/spi/ValueCoercer.java` (interface).
3. `EffectFactory.setCoercer(...)` + `IllegalStateException`-throwing
   accessor.
4. `effectsapi/bukkit/BukkitValueCoercer.java` — relocate
   `Effect#resolveSound` / `Effect#resolveNamedColor` /
   `Effect#resolveViaRegistry` bodies verbatim.
5. Refactor `Effect.java`: switch `canParse`/`fixData`/`str2Obj` to
   `TypeKey` lookup; delete the moved methods; remove `org.bukkit.*`
   imports.
6. `BukkitEffectsInitializer.registerAll()` — first line:
   `EffectFactory.setCoercer(new BukkitValueCoercer())`.
7. ADR-002 amendment commit (text-only, two edits described above).
8. `effectsapi/fabric/FabricValueCoercer.java` (Phase-1 types only).
9. `FabricEffectsInitializer.registerAll()` — first line:
   `EffectFactory.setCoercer(new FabricValueCoercer())`.
10. Bytecode test: `EffectsApiCommonNoPlatformImportsTest` (ADR-003 step
    9b — now achievable).
11. CHANGELOG entry under the current unreleased version.

Verification gates: existing `EffectsApiAdaptiveReadingOrderTest` green,
new `EffectsApiCommonNoPlatformImportsTest` green,
`.\gradlew :effects-api:build :rtp-plugin:compileJava` green.

## References

- [`effects-api-ADR-002`](effects-api-ADR-002-type-driven-reading-order.md) — the contract this ADR amends.
- [`effects-api-ADR-003`](effects-api-ADR-003-platform-split-bukkit-fabric.md) — the platform split this ADR completes.
- [`docs/dev/scratch/CHECKLIST-effects-api-platform-split.md`](../../../docs/dev/scratch/CHECKLIST-effects-api-platform-split.md) — step 3 deferred work this ADR resolves.
- AGENTS.md *Prohibition Requirements* — S-004 (no silent failures), S-006 (fail-fast on early API use).
