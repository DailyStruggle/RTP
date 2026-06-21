# LeafRTPRiftAddon

A tiny reference addon that demonstrates **effect registration**: how to add your own
teleport effect to LeafRTP so it sits alongside the built-ins (FIREWORK, PARTICLE, SOUND,
POTION, NOTE, GLIDE, TITLE) and is usable by name in config and permissions.

The effect it ships is the **"Virtual Rift" world-deconstruction** animation from the
roadmap: while a player stands through the teleport warmup, the terrain around them appears
to tear open into a void - a hollow sphere whose rim is dressed in a mix of sculk, obsidian,
and black concrete, wreathed in dark portal and squid-ink particles - then snaps back when the
warmup ends.

## What it shows

- Registering a new `Effect` prototype with `effects-api`'s `EffectFactory` from
  `RTPAddon.onLoad()` (`EffectFactory.addEffect("RIFT", new RiftEffect())`).
- Implementing `Effect<T>`: an argument-key enum, an `EnumMap` of defaults, `run()`,
  `setData(String...)`, and `toPermission()`.
- Doing **presentation only** work safely: the dissolve uses client-side fake block changes -
  no real blocks change, no physics fire, and no chunks load on any region thread. Because of
  that contract an effect can never compromise destination safety (S-001..S-007); the worst a
  buggy effect can do is fail to render.
- Staying **platform-neutral** (no `org.bukkit.*` and no platform dependency at all): the
  effect resolves its target through `effects-api`'s `HandleRegistry` (exactly like the
  built-in `GlideEffect`) and drives the whole animation through the RTP SPI on `RTPPlayer`:
  - `RTPPlayer#getClientBlock(RTPLocation)` returns the block currently shown to the client as
    a plain block-data string (e.g. `"minecraft:oak_log[axis=y]"`).
  - `RTPPlayer#sendClientBlockChanges(Map<RTPLocation, String>)` pushes the dissolve (and later
    the restore) as a bulk send. On Bukkit/Paper/Folia it is **binned** into one
    multi-block-change packet per chunk section (so a large rift costs a handful of packets, not
    thousands); on Fabric/NeoForge the adapter sends one block-update packet per block (vanilla
    exposes no public constructor to bin arbitrary fake states into a section packet).
- **Composing a built-in effect** for flair: the dark portal and squid-ink particle bursts are
  not hand-rolled per platform - the effect builds the registered `PARTICLE` effect by name
  (`EffectFactory.buildEffect("PARTICLE")`), aims it at the same target, and runs it. This keeps
  the particle code platform-neutral too (no `org.bukkit.Particle` / Mojmap `ParticleTypes`
  reference), and the bursts are pure decoration: a missing `PARTICLE` registration or an unknown
  token degrades to nothing rather than failing the teleport.
- Restoring state correctly: the original client-side blocks are re-sent after the configured
  duration through `RTP.scheduler.runTaskForPlayer(...)`, which hops onto a thread that owns
  the player (Folia entity-scheduler aware).
- Because the effect only touches the platform-neutral SPI, the same class loads and runs on
  every platform whose adapter implements that SPI - Bukkit / Paper / Folia, Fabric (including
  the deobf MC 26.x carriers), and NeoForge - with no per-platform gating code. It degrades to
  a harmless no-op on any future platform that has not yet wired the SPI.

## How `RIFT` is used once registered

`RIFT` behaves like any other effect. Effects are gated by `performance.yml` -> `effectParsing`
(default off), so enable that first. Then either:

**By permission** (`rtp.effect.<stage>.<EFFECT>.<args...>`):

```
rtp.effect.presetup.RIFT.4.3
```

opens a 4-block rift radius for 3 seconds at the start of the warmup.

**By an `effects/` config group** (one file per group):

```yaml
when: presetup
effects:
  - RIFT.4.3
```

The `.` separator splits arguments, so all arguments are whole numbers:
`RIFT.<radius>.<seconds>`. Radius is clamped to `[1, 8]`.

## Build

```
./gradlew :addons:LeafRTPRiftAddon:build
```

Drop the produced `LeafRTPRiftAddon-<version>.jar` into `plugins/RTP/addons/`.

## See also

- `wiki/Writing-an-Effect.md` - the full effect-authoring guide.
- `api/effects-api/README.md` - the effects framework reference.
- `addons/LeafRTPCountdownAddon` - the canonical reference addon (config, safety verifier,
  post-teleport observer, countdowns).
