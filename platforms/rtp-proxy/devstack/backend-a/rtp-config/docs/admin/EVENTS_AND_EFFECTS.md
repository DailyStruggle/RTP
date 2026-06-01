# Events & Effects

Two related but distinct extension points in RTP:

- **Part 1 — Effect listeners** are for **server operators** who want visual / audio feedback (sounds, particles, fireworks, potions, notes) when players move through the teleport pipeline — configured entirely through permissions, no code.
- **Part 2 — Event listeners** are for **plugin developers** writing an addon that wants to react to RTP's teleport lifecycle (logging, analytics, economy hooks, custom safety checks, etc.).

> Supported server versions: **Minecraft 1.20.1 and above**. Anything in this document assumes that baseline — legacy-version caveats have been omitted.

> See also: [`COMMANDS.md`](COMMANDS.md) for permission reference, [`CONFIGURATION.md`](CONFIGURATION.md) for `performance.yml` toggles.

---

## Part 1 — Effect listeners (for operators)

RTP bundles the `effects-api` module (shaded as `io.github.dailystruggle.rtp.effectsapi`). `BukkitEffectsHandler.setupEffects(...)` hooks each teleport pipeline stage and — when enabled — dispatches effects driven entirely by **permission nodes** granted to the player (directly, via group, or via a permissions plugin).

Operators write **no code**. Just:

1. Enable effect parsing.
2. Grant `rtp.effect.<stage>.<TYPE>[.<arg>...]` nodes to the players / groups who should see them.

### Enable / disable globally

In `performance.yml`:

```yaml
# Scan player permissions for rtp.effect.* on every teleport pipeline stage.
# MEDIUM impact. Leave false unless you actually use rtp.effect.* nodes.
effectParsing: false
```

Set to `true` to activate effect dispatch. A `/rtp reload` picks the change up without a restart.

> Unrelated but sometimes confused: `onEventParsing` gates the **auto-teleport on lifecycle events** listener (`rtp.onevent.join`, `rtp.onevent.respawn`, etc.). That is not an effect.

### Pipeline stage → permission prefix

Each stage scans a specific permission prefix (source of truth: `rtp-plugin/.../bukkit/effects/BukkitEffectsHandler.java`):

| Pipeline stage | Permission prefix |
|----------------|-------------------|
| Pre-setup (pipeline start) | `rtp.effect.presetup.*` |
| Post-setup | `rtp.effect.postsetup.*` |
| Pre-load (before async chunk load) | `rtp.effect.presetup.*` *(shared with pre-setup — see note)* |
| Post-load (chunks ready) | `rtp.effect.postload.*` |
| Pre-teleport | `rtp.effect.preteleport.*` |
| Post-teleport | `rtp.effect.postteleport.*` |
| Teleport cancelled | `rtp.effect.cancel.*` |
| Queue push (player enters region queue) | `rtp.effect.queuepush.*` |
| Queue pop (player leaves region queue) | `rtp.effect.queuepop.*` |

> **Note — pre-load reuse.** The `PreLoadChunksEvent` stage currently calls `buildEffects("rtp.effect.presetup", ...)` rather than a dedicated `preload` prefix. This means effects granted at `rtp.effect.presetup.*` will fire twice (once at pipeline start, once before chunk load). If operators need a separate pre-load trigger, open an issue.

### Permission node grammar

Each `.`-separated permission granted under a stage prefix is parsed as:

```
rtp.effect.<stage>.<TYPE>[.<arg1>[.<arg2>[...]]]
```

- `<TYPE>` is one of the registered effect tokens (see table below).
- `<arg1>`, `<arg2>`, … are **positional** values, filled into the effect's parameter enum **in declaration order**. Missing arguments fall back to the effect's defaults.

### Built-in effect types

Registered in `effects-api/.../EffectFactory.java`. All five are available on every supported server version (MC ≥ 1.20.1):

| `<TYPE>` | Class | Safety / extras |
|----------|-------|-----------------|
| `FIREWORK` | `FireworkEffect` | `FireworkSafetyListener` auto-suppresses damage from effect-spawned fireworks. |
| `NOTE` | `NoteEffect` | Plays a note block sound at the player. |
| `PARTICLE` | `ParticleEffect` | Spawns particles at the player. |
| `POTION` | `PotionEffect` | Applied to the teleporting player. |
| `SOUND` | `SoundEffect` | Played at the player's location. |

#### Positional arguments per type

Order and names come directly from the matching `*TypeNames` enum in `effects-api/.../LocalEffects/enums/`.

**`FIREWORK`** — `FireworkTypeNames`

Canonical positional order matches `FireworkEffect.KEY_ORDER` in `effects-api`. Since effects-api-ADR-002 (3.0.0-beta.2), `Effect.setData(String...)` walks `KEY_ORDER` with a non-rewinding cursor and assigns each token to the *first remaining key whose default type accepts it* — so trailing booleans land on `FLICKER` / `TRAIL` / `SAFE` even when given before the offsets. For predictability, supply tokens in the order below.

| Pos | Variable | Typical values |
|-----|----------|----------------|
| 1 | `TYPE` | `BALL`, `BALL_LARGE`, `BURST`, `CREEPER`, `STAR` |
| 2 | `NUMBER` | integer, fireworks to spawn |
| 3 | `POWER` | 0–3 (flight duration) |
| 4 | `COLOR` | Bukkit `Color` name or `#RRGGBB` |
| 5 | `FADE` | fade-out `Color` |
| 6 | `FLICKER` | `true` / `false` |
| 7 | `TRAIL` | `true` / `false` |
| 8 | `SAFE` | `true` disables damage (recommended) |
| 9 | `DX` | x-offset from player |
| 10 | `DY` | y-offset |
| 11 | `DZ` | z-offset |

**`NOTE`** — `NoteTypeNames`

| Pos | Variable | Typical values |
|-----|----------|----------------|
| 1 | `TYPE` | instrument name (`PIANO`, `BASS_DRUM`, `SNARE_DRUM`, `STICKS`, `BASS_GUITAR`, …) |
| 2 | `TONE` | integer `0`–`24` (Bukkit `Note` two-octave id, **not** a letter). Common references: `0` = F♯ low, `6` = C, `8` = D, `12` = F♯ middle, `18` = C high, `24` = F♯ high. |

**`PARTICLE`** — `ParticleTypeNames`

| Pos | Variable | Typical values |
|-----|----------|----------------|
| 1 | `TYPE` | any `org.bukkit.Particle` constant (`PORTAL`, `FLAME`, `END_ROD`, …) |
| 2 | `NUMBER` | count (int) |

**`POTION`** — `PotionTypeNames`

| Pos | Variable | Typical values |
|-----|----------|----------------|
| 1 | `TYPE` | `PotionEffectType` name (`BLINDNESS`, `SPEED`, `NIGHT_VISION`, …) |
| 2 | `DURATION` | ticks (20 = 1 s) |
| 3 | `AMPLIFIER` | 0 = level I |
| 4 | `AMBIENT` | `true` / `false` |
| 5 | `PARTICLES` | `true` / `false` |
| 6 | `ICON` | `true` / `false` |

**`SOUND`** — `SoundTypeNames`

| Pos | Variable | Typical values |
|-----|----------|----------------|
| 1 | `TYPE` | any `org.bukkit.Sound` constant (`ENTITY_ENDERMAN_TELEPORT`, `BLOCK_ANVIL_LAND`, …) |
| 2 | `VOLUME` | integer, **scaled by / 100** (so `100` = volume 1.0) |
| 3 | `PITCH` | integer, **scaled by / 100** (so `100` = pitch 1.0) |
| 4 | `DX` | x-offset from player |
| 5 | `DY` | y-offset |
| 6 | `DZ` | z-offset |

Missing trailing arguments keep the effect's built-in defaults (see each `*Effect` class constructor).

### Worked examples

Each node is one complete effect. Grant multiple to stack them on the same stage.

```yaml
# plugin.yml / permissions.yml / LuckPerms / PEX — same grammar everywhere
permissions:
  # Classic "teleport whoosh" on arrival
  rtp.effect.postteleport.SOUND.ENTITY_ENDERMAN_TELEPORT: true

  # Portal particles when the player is about to be moved
  rtp.effect.preteleport.PARTICLE.PORTAL.50: true

  # Brief blindness while chunks load (immersion for async delay)
  rtp.effect.postsetup.POTION.BLINDNESS.40.0: true

  # Celebratory firework at the destination (safe = no damage)
  # Token order: TYPE.NUMBER.POWER.COLOR.FADE.FLICKER.TRAIL.SAFE.DX.DY.DZ
  rtp.effect.postteleport.FIREWORK.BALL.1.1.BLUE.WHITE.true.true.true.0.0.0: true

  # Note blip when player enters a queue (TONE is 0-24, see NoteTypeNames table above)
  rtp.effect.queuepush.NOTE.PIANO.12: true

  # Audible cue when a teleport is cancelled
  rtp.effect.cancel.SOUND.BLOCK_ANVIL_LAND.80.100: true
```

### Operator verification commands

`effects-api` registers standalone test commands so operators can preview an effect **without** triggering a teleport:

| Command | Purpose |
|---------|---------|
| `/effectsapi` | Base command (help / listing). |
| `/firework`, `/note`, `/particle`, `/potion`, `/sound` | Trigger one instance of that effect on yourself with the given args. |
| `/effectsapi test` (`TestCommand`) | Generic parameterised test. |

Use these to validate an enum name and argument order before baking it into a permission node.

### Troubleshooting

| Symptom | Likely cause |
|---------|--------------|
| Nothing happens, no errors | `performance.yml → effectParsing: false`. |
| Only some players see effects | Permission is on a group they're not in, or a `*` wildcard is over-matching and resolving to `false`. |
| Effect fires at the wrong stage | Stage prefixes are literal — `postsetup` ≠ `postteleport`. Check the table above. |
| Unknown enum name in a node | Bukkit `Sound` / `Particle` / `PotionEffectType` constants vary slightly between 1.20.1, 1.21.x, and newer versions. Verify with `/sound`, `/particle`, `/potion` on your actual server. |
| `rtp.effect.presetup.*` seems to fire twice | Pre-load stage currently reuses the pre-setup prefix (see note above). |

---

## Part 2 — Event listeners (for developers)

RTP publishes Bukkit-style events under `io.github.dailystruggle.rtp.bukkit.events` (module `rtp-plugin`). An addon consumes them the same way as any other Bukkit event: implement `Listener`, annotate with `@EventHandler`, register with the plugin manager.

### Reference addon — `addons/RTP_ExampleAddon`

A complete, compilable template ships in the repository. It demonstrates the four API touch-points every addon typically needs:

1. **Config registration** — `ConfigParser<ExampleKeys>` participates in `/rtp reload`.
2. **Safety contribution** — `GlobalRegionVerifiers.addGlobalRegionVerifier(...)` predicate, invoked asynchronously by the teleport pipeline (S-003 / S-005 compliant).
3. **Event handling** — a Bukkit `Listener` for one of RTP's lifecycle events.
4. **Reload hook** — `Configs.onReload(Runnable)` so operator `/rtp reload` picks up addon changes without a restart.

Relevant files:

| File | Role |
|------|------|
| `addons/RTP_ExampleAddon/src/main/java/io/github/dailystruggle/rtp/example/RTPExampleAddon.java` | Plugin main class, wires all four touch-points. |
| `.../ExampleTeleportListener.java` | Minimal `PostTeleportEvent` listener. |
| `.../ExampleKeys.java` | Enum backing `example.yml`. |
| `addons/RTP_ExampleAddon/README.md` | Step-by-step walkthrough. |

#### Listener shape (`ExampleTeleportListener`)

```java
public final class ExampleTeleportListener implements Listener {
  @EventHandler
  public void onPostTeleport(PostTeleportEvent event) {
    ConfigParser<ExampleKeys> parser =
        (ConfigParser<ExampleKeys>) RTP.configs.getParser(ExampleKeys.class);
    if (parser == null) return;

    Object flag = parser.getConfigValue(ExampleKeys.announceTeleport, false);
    boolean enabled = (flag instanceof Boolean) ? (Boolean) flag
                                                 : Boolean.parseBoolean(String.valueOf(flag));
    if (!enabled) return;

    RTP.log(Level.INFO, "[RTP_ExampleAddon] PostTeleportEvent observed: " + event.getDoTeleport());
  }
}
```

Registration (from `RTPExampleAddon.onEnable`):

```java
Bukkit.getPluginManager().registerEvents(new ExampleTeleportListener(), this);
```

### Public event catalog

All events live in `rtp-plugin/src/main/java/io/github/dailystruggle/rtp/bukkit/events/`. Pipeline-stage events are fired from `BukkitEffectsHandler.setupEffects(...)`; queue events are fired from `Region.onPlayerQueuePush` / `onPlayerQueuePop`; command events are fired from the `/rtp` command pipeline.

| Event | When it fires |
|-------|---------------|
| `PreSetupTeleportEvent` | Before `TeleportPipelineTask` setup stage. **Cancellable** — setting cancelled aborts the teleport. |
| `PostSetupTeleportEvent` | Setup stage completed successfully. |
| `PreLoadChunksEvent` | Before async chunk load of the destination. |
| `PostLoadChunksEvent` | Destination chunks are loaded (still async-safe — S-005). |
| `PreTeleportEvent` | Immediately before the entity teleport call. |
| `PostTeleportEvent` | Immediately after a successful teleport. |
| `TeleportCancelEvent` | Player/pipeline cancelled a scheduled teleport (`RTPTeleportCancel`). |
| `TeleportCommandSuccessEvent` | `/rtp` command accepted, pipeline started. |
| `TeleportCommandFailEvent` | `/rtp` command rejected (cooldown, parse error, etc.). |
| `PlayerQueuePushEvent` | Player added to a region's waiting queue. |
| `PlayerQueuePopEvent` | Player popped off a region's waiting queue. |
| `RandomSelectQueueEvent` | A queued candidate location was selected from the buffer. |

### Developer do / don't

Pulled from [`REQUIREMENTS.md §3`](../dev/REQUIREMENTS.md) (S-00x rules):

- **Do** put chunk / claim / biome checks inside a `GlobalRegionVerifiers` lambda — it runs asynchronously and is S-005 safe.
- **Do** log via `RTP.log(Level, msg[, throwable])`. Never `Bukkit.getLogger()`, never `printStackTrace()`.
- **Don't** perform synchronous `world.getChunkAt(...)` inside an event handler (S-005).
- **Don't** silently `return` on a teleport failure (S-004) — surface via the event or log.
- **Don't** import `org.bukkit.*` from `rtp-core` or `rtp-api`; keep platform code in the adapter module or in the addon.

---

## Cross-references

- Source of truth, effect dispatch: `rtp-plugin/src/main/java/io/github/dailystruggle/rtp/bukkit/effects/BukkitEffectsHandler.java`
- Source of truth, effect registry: `effects-api/src/main/java/io/github/dailystruggle/effectsapi/EffectFactory.java`
- Parameter enums: `effects-api/src/main/java/io/github/dailystruggle/effectsapi/LocalEffects/enums/`
- Developer walkthrough: `addons/RTP_ExampleAddon/README.md`
- Safety rules every listener must follow: [`../dev/REQUIREMENTS.md §3`](../dev/REQUIREMENTS.md)
- Auto-teleport (`rtp.onevent.*`) permissions: [`COMMANDS.md`](COMMANDS.md)
