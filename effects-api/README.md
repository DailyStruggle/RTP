# effects-api

Small Bukkit-facing framework that turns named, permission-addressable effects (particles, sounds, potions, fireworks, notes) into runnable tasks. Used by `rtp-plugin` to fire visuals and audio at each stage of the teleport pipeline, but self-contained enough to be reused standalone.

> Sibling of [`commands-api`](../commands-api/README.md). Effects expose themselves as parameter-driven `BukkitTreeCommand`s wired through the Commands API.

---

## 1. Module layout

```
effects-api/
  EffectsAPI.java              entry point + version probing + listener registration
  Effect<T extends Enum<T>>    abstract BukkitRunnable, EnumMap-backed data, clone/permission
  EffectFactory.java           prototype registry + permission-to-effects builder
  LocalEffects/                bundled effect implementations
      FireworkEffect, NoteEffect(_1_12), ParticleEffect, PotionEffect, SoundEffect
      enums/                   per-effect parameter keys (TYPE, NUMBER, DURATION, …)
  commands/                    CommandsAPI wrappers (one per effect + test/main)
  SpigotListeners/
      FireworkSafetyListener   cancels firework explosion damage for tagged entities
```

No `rtp-core` / `rtp-api` dependency — only Bukkit and `commands-api`.

---

## 2. Core concepts

### 2.1 `Effect<T>` — parameterised, runnable action
- Extends `BukkitRunnable`; `run()` is where the actual Bukkit call happens (`spawnParticle`, `playSound`, `addPotionEffect`, …).
- Parameters are held in an `EnumMap<T, Object>` (`data`) with a sibling `defaults` map. Keys are the effect's own enum (e.g. `ParticleTypeNames.TYPE`, `ParticleTypeNames.NUMBER`).
- Target is set via `setTarget(Object)` and must be a `Location` or an `Entity` — anything else throws `IllegalArgumentException`. Most `run()` implementations coerce an Entity target to its current `Location`.
- Two ways to push data in:
    - `setData(EnumMap<T,Object>)` — typed, replaces `data` then calls `fixData`.
    - `setData(String...)` — positional string form; each subclass decides the slot order (e.g. `ParticleEffect` = `TYPE, NUMBER`). Used by the permission-node parser.
- `fixData(EnumMap)` coerces string values back to the default's runtime type (`valueOf` → `getByName` fallback, with Colour-as-hex and Number-as-parse support). This is why partial permission nodes like `PARTICLE.FLAME` still produce a valid effect.
- `toPermission()` serialises the current data back to a permission-node suffix — round-trips with `EffectFactory.buildEffects` below.
- `clone()` is deep-ish: `Cloneable` values are reflectively cloned, `Location` targets are copied, everything else is shared by reference. The prototype pattern in `EffectFactory` relies on this.

### 2.2 `EffectFactory` — prototype registry
- Static `ConcurrentHashMap<String, Effect<?>>` of **prototype instances**, keyed uppercase. Seeded at class-load with `FIREWORK`, `NOTE` (1.12 or modern), `PARTICLE` (only on 1.9+), `POTION`, `SOUND`.
- `addEffect(name, effect)` / `removeEffect(name)` register third-party effects.
- `buildEffect(name)` returns a **clone** of the prototype (never the prototype itself).
- `buildEffect(name, data)` clones then applies the typed EnumMap.
- `buildEffects(permissionPrefix, permissions)` is the permission-driven factory used by RTP:
    - Iterates each granted permission starting with the prefix.
    - Splits the suffix on `.` → first token selects the prototype, remaining tokens become `setData(String...)` arguments.
    - Returns every matching cloned effect. Example grant `rtp.effect.postload.PARTICLE.FLAME.40` → one `ParticleEffect` with `TYPE=FLAME, NUMBER=40`.
- `addPermissions(permissionPrefix)` auto-registers Bukkit `Permission` nodes for every enum constant of each effect's `TYPE` key (plus every `PotionEffectType`), so the standard permissions UI and completion tools see them.

### 2.3 `EffectsAPI.init(Plugin)`
- Idempotent: captures the owning `Plugin` and registers a single `FireworkSafetyListener`. Repeat calls are no-ops.
- Also exposes `getServerIntVersion()` — the integer minor version used by `EffectFactory`'s static block and by effects that branch on capability (e.g. Particle dust options).

### 2.4 `FireworkSafetyListener`
- Registered by `EffectsAPI.init`. Not optional — `FireworkEffect` relies on it to avoid killing players with its own display fireworks.
- On `FireworkExplodeEvent`, if the firework was registered via `addFirework(id, count, safe)`:
    - Optionally tags nearby entities (5-block radius) as safe for one tick.
    - Spawns `count-1` extra explosions from the same meta.
- `onFireworkDamage` cancels `ENTITY_EXPLOSION` damage for tagged entities only — no blanket invulnerability, no state that leaks past a tick.

---

## 3. Command layer (`commands/`)

Each built-in effect has a matching `GenericEffectCommand<T>` subclass (`ParticleCommand`, `PotionCommand`, …). They plug into the Commands API tree and are intended for admin/testing use.

- `GenericEffectCommand<T>` extends `BukkitTreeCommand` and auto-builds its parameter children from the prototype's defaults:
    - `Integer`/`Long` → `IntegerParameter`
    - `Float`/`Double` → `FloatParameter`
    - `Boolean` → `BooleanParameter`
    - `Color` → `ColorParameter`
    - `PotionEffectType` → `PotionParameter`
    - any other `Enum<?>` → an anonymous `BukkitParameter` whose `values()` is the enum's constants.
    Anything else throws `IllegalArgumentException` at construction — add a Commands API parameter type before registering a new effect with an unusual data type.
- Parameter permission is `effectsapi.see`; command permission is `EffectsAPI.test` (capitalised — the plugin registers it exactly like this; don't silently lowercase it).
- Concrete `onCommand` handlers (e.g. `ParticleCommand`) translate the `parameterValues` map (Commands API's `key -> List<String>`) back into `EnumMap` data, mint effects via `new XxxEffect()`, target the sender, and schedule them with `effect.runTask(plugin)`.
- `TestCommand` + `EffectsAPIMainCommand` wire the suite together for standalone testing; RTP does not use them in production.

---

## 4. RTP integration (how it's actually used)

`rtp-plugin/.../BukkitEffectsHandler.setupEffects(plugin)` hooks the same eight teleport-pipeline action lists:

| Pipeline stage | Permission prefix |
|---|---|
| preSetup   | `rtp.effect.presetup`  |
| postSetup  | `rtp.effect.postsetup` |
| preLoad    | `rtp.effect.presetup`*  |
| postLoad   | `rtp.effect.postload`  |
| preTeleport/postTeleport/cancel | see `BukkitEffectsHandler` |

*preLoad reuses `presetup` — intentional, not a typo.

Each hook:
1. Gated by `performance.yml` → `effectParsing` (default off). If disabled, nothing is built.
2. Calls `EffectFactory.buildEffects(prefix, player.getEffectivePermissions())` — permission scan happens per teleport.
3. Enqueues `effect.setTarget(player); effect.run()` onto `RTP.miscAsyncTasks`. Effects may not be thread-safe for Bukkit calls; `miscAsyncTasks` drains on the appropriate scheduler lane — rely on that, do not call `run()` from an async context directly.

On Folia, follow the project-wide rule: any Bukkit world mutation goes through the entity/region scheduler. `ParticleEffect.run()` calls `World#spawnParticle`, which is region-bound; `miscAsyncTasks` handles the dispatch.

---

## 5. Adding a new effect

1. Pick an enum of parameter keys, e.g. `MyTypeNames { TYPE, AMOUNT, COLOR }`. Place it in `LocalEffects/enums/`.
2. Subclass `Effect<MyTypeNames>`:
    - Call `super(new EnumMap<>(MyTypeNames.class))` then populate `data` and re-clone into `defaults` in the constructor (see `ParticleEffect` for the pattern — the defaults map drives both `fixData` coercion and the Commands API parameter wiring).
    - Implement `run()` with the Bukkit call; coerce Entity → Location if relevant.
    - Implement `setData(String...)` mapping positional args to enum keys (order defines the permission-node syntax).
    - Implement `toPermission()` so `buildEffects` round-trips are predictable.
3. Register the prototype: `EffectFactory.addEffect("MYEFFECT", new MyEffect())`. Do this before any permission scan.
4. (Optional) Subclass `GenericEffectCommand<MyEffect>` to expose it as a `/effects myeffect …` command. Defaults drive parameter types — see Section 3 for the supported value types.
5. (Optional) Call `EffectFactory.addPermissions("your.prefix")` so the TYPE constants materialise as concrete `Permission` nodes.

### Pitfalls
- **Prototype mutation**: `addEffect` stores the exact instance you pass. `buildEffect` returns a `clone()`, so the prototype's `data` must stay at defaults. Never `setTarget`/`setData` on the instance you register.
- **Cloneable contract**: values held in `data` that implement `Cloneable` will be cloned reflectively via `clone()`. If you store a mutable object that is **not** `Cloneable`, clones will share it — not necessarily a bug, but document it.
- **`IllegalArgumentException` in `GenericEffectCommand`**: if your default map contains a type not handled in Section 3, command construction fails. Either add an explicit `BukkitParameter` registration by overriding the constructor, or extend `GenericEffectCommand` with the new type mapping.
- **Thread safety**: `EffectFactory`'s maps are concurrent, but Bukkit API calls inside `run()` are not. Always schedule via `runTask` / `miscAsyncTasks`.
- **Version gates**: `ParticleEffect` is not registered below 1.9; `NoteEffect_1_12` is used below 1.17. Guard any new effect the same way using `EffectsAPI.getServerIntVersion()`.
- **`FireworkEffect` requires `EffectsAPI.init(plugin)`**: without the listener, multi-explosion fireworks damage players. Don't short-circuit `init` in tests that spawn firework effects.
- **`printStackTrace` is used internally** in `Effect.fixData` / `EffectFactory.buildEffect` for historical reasons. New code in `rtp-core`/`rtp-api` must route through `RTP.log(Level.WARNING, msg, e)` per project guidelines — prefer adding proper logging when you touch these paths.

---

## 6. Cheat-sheet

```java
// standalone bootstrap
EffectsAPI.init(plugin);

// build a single effect from code
ParticleEffect e = (ParticleEffect) EffectFactory.buildEffect("PARTICLE");
EnumMap<ParticleTypeNames, Object> d = e.getData();
d.put(ParticleTypeNames.TYPE,   Particle.FLAME);
d.put(ParticleTypeNames.NUMBER, 40);
e.setData(d);
e.setTarget(player);
e.runTask(plugin);

// build effects from a player's permissions
List<Effect<?>> effects = EffectFactory.buildEffects(
        "rtp.effect.postload", player.getEffectivePermissions());
effects.forEach(fx -> { fx.setTarget(player); fx.run(); });

// permission node shape:
//   <prefix>.<EFFECT_NAME>.<arg0>.<arg1>...
//   e.g. rtp.effect.postload.PARTICLE.FLAME.40
```
