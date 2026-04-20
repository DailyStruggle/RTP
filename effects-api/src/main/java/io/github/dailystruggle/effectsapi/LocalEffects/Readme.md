# LocalEffects catalog

Bundled `Effect<T>` implementations. Registered automatically in `EffectFactory`'s static initialiser (some gated by server version). Framework mechanics live in [`effects-api/README.md`](../../../../../../../../README.md); this file is just the catalog.

## Bundled effects

| Name (registry key) | Class | Enum keys (`setData(String...)` order) | Version gate | `run()` calls |
|---|---|---|---|---|
| `FIREWORK` | `FireworkEffect` | `TYPE, NUMBER, POWER, DX, DY, DZ, COLOR, FADE, FLICKER, TRAIL, SAFE` | — | `World#spawnEntity(FIREWORK)` + `FireworkSafetyListener.addFirework` |
| `NOTE`     | `NoteEffect` (>=1.17) / `NoteEffect_1_12` (<1.17) | `TYPE, TONE` | ≥1.17 vs <1.17 | `World#playNote` |
| `PARTICLE` | `ParticleEffect` | `TYPE, NUMBER` | **≥1.9 only** | `World#spawnParticle` |
| `POTION`   | `PotionEffect`   | `TYPE, DURATION, AMPLIFIER, AMBIENT, PARTICLES, ICON` | — | `LivingEntity#addPotionEffect` |
| `SOUND`    | `SoundEffect`    | `TYPE, VOLUME, PITCH, DX, DY, DZ` | — | `World#playSound` |

Key-order in the table = positional order expected by `setData(String...)` — it is also the order permission-node tokens are consumed by `EffectFactory.buildEffects`, e.g. `...PARTICLE.FLAME.40` → `TYPE=FLAME, NUMBER=40`.

## Target rules

- `FIREWORK`, `PARTICLE`, `SOUND`, `NOTE` accept an `Entity` or `Location`; Entity targets are coerced to `entity.getLocation()` at `run()`-time.
- `POTION` requires a `LivingEntity` target; passing a `Location` is a no-op (safe but silent).

## Safety

- `FIREWORK.SAFE=true` requires `EffectsAPI.init(plugin)` so `FireworkSafetyListener` is live. Without the listener, `SAFE` is a no-op and nearby entities take explosion damage.
- Multi-explosion fireworks (`NUMBER>1`) spawn additional explosions from the original meta; the safety window is one tick per detonation.

## Adding a new effect

See Section 5 of [`effects-api/README.md`](../../../../../../../../README.md). Short version:
1. Add an enum under `enums/` (first constant is conventionally `TYPE`).
2. Subclass `Effect<YourEnum>`: constructor seeds `data` + re-clones to `defaults`, implement `run()`, `setData(String...)`, `toPermission()`.
3. Register via `EffectFactory.addEffect("NAME", new YourEffect())` before any permission scan.
4. (Optional) `GenericEffectCommand<YourEffect>` auto-wires Commands API parameters from the defaults map — only types listed in the README Section 3 table are supported without extra wiring.
