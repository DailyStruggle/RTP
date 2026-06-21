# LeafRTPRiftAddon

A tiny reference addon that demonstrates **effect registration**: how to add your own
teleport effect to LeafRTP so it sits alongside the built-ins (FIREWORK, PARTICLE, SOUND,
POTION, NOTE, GLIDE, TITLE) and is usable by name in config and permissions.

The effect it ships is the **"Virtual Rift" world-deconstruction** animation from the
roadmap: while a player stands through the teleport warmup, the terrain around them tears open
into a void - a hollow sphere carved out of the real world, ringed by a dark sculk/obsidian/
black-concrete rim, floored with a starry `end_gateway` the player falls toward, wreathed in dark
portal, reverse-portal, squid-ink and smoke particles, and veiled in the warden's `DARKNESS`
screen-dimming - so the player genuinely falls into the rift, then the world snaps back when the
warmup ends.

The lower hemisphere of the rim is a **real** obsidian bowl, painted (client-side only) with a
fake `minecraft:end_gateway` over the top: the gateway renders the deep starfield on every face
(`end_portal` only renders its star texture on the top face, so it would be invisible from below
as the player falls), while the real obsidian underneath catches the player so they can't drop
through the rift into a cave (or the void) below it. The bowl even seals cave-mouth air right
under the rift. Obsidian is effectively un-minable in the brief warmup window, and the whole bowl
is reverted with everything else when the warmup ends, so nothing stays obtainable.

## What it shows

- Registering a new `Effect` prototype with `effects-api`'s `EffectFactory` from
  `RTPAddon.onLoad()` (`EffectFactory.addEffect("RIFT", new RiftEffect())`).
- Implementing `Effect<T>`: an argument-key enum, an `EnumMap` of defaults, `run()`,
  `setData(String...)`, and `toPermission()`.
- Mixing a **real carve** with **client-side fakes** for the right reasons: the void is carved
  into real air so the server agrees there is a hole and the player actually falls in (a purely
  client-side fake desyncs - the client predicts a fall the server rejects, leaving the player
  floating). The themed dark rim, by contrast, is presentation only: it is layered over the
  carved air as client-side fake blocks, so it never becomes a real, collidable, mineable block
  and the player falls straight through it. Gravity blocks (sand, gravel, concrete powder,
  anvils, ...) are never carved for real - that would drop them as falling-block entities; they
  are vanished client-side only while their real block stays put, so nothing ever falls. The
  lower shell of the sphere is instead set to **real obsidian** (a catch-bowl that seals any cave
  underneath so the player can't fall through it), reverted with everything else. The
  carve is a no-physics, no-drop write, so nothing is dropped and nothing obtainable is
  introduced, and the originals are always written back when the warmup ends - even if the
  player logged off mid-fall - so a missed restore can never leave a permanent hole. No chunks
  load on any region thread (S-005).
- Staying **platform-neutral** (no `org.bukkit.*` and no platform dependency at all): the
  effect resolves its target through `effects-api`'s `HandleRegistry` (exactly like the
  built-in `GlideEffect`) and drives the whole animation through the RTP SPI on `RTPPlayer`:
  - `RTPPlayer#getClientBlock(RTPLocation)` returns the real block currently at a position as a
    plain block-data token (e.g. `"minecraft:oak_log[axis=y]"`), used to snapshot the originals
    so they can be put back. It returns `null` for unloaded positions, which are skipped (S-005).
  - `RTPWorld#setBlocks(List<BlockDelta>)` performs the real bulk block write that carves the
    void to air (and later restores the snapshot). The adapter writes with no physics and no
    item drops, and on a platform that has not wired the SPI it degrades to a no-op (the rift
    simply does nothing rather than failing the teleport).
  - `RTPPlayer#sendClientBlockChanges(Map<RTPLocation, String>)` pushes the presentation-only
    layer - the dark rim and the vanished gravity blocks - as client-side fakes (binned into one
    multi-block packet per chunk section on Bukkit/Paper/Folia). These never touch the world.
- **Composing a built-in effect** for flair: the dark portal and squid-ink particle bursts are
  not hand-rolled per platform - the effect builds the registered `PARTICLE` effect by name
  (`EffectFactory.buildEffect("PARTICLE")`), aims it at the same target, and runs it. This keeps
  the particle code platform-neutral too (no `org.bukkit.Particle` / Mojmap `ParticleTypes`
  reference), and the bursts are pure decoration: a missing `PARTICLE` registration or an unknown
  token degrades to nothing rather than failing the teleport. The same composition trick applies
  the warden's `DARKNESS` screen-veil by building the registered `POTION` effect by name (falling
  back to its `BLINDNESS` default on server versions without `DARKNESS`), so no platform potion
  type is named here either.
- Restoring state correctly: the original blocks are written back after the configured duration
  through `RTP.scheduler.runTaskLater(world, cx, cz, ...)`, which hops onto the thread that owns
  the footprint chunk (Folia region thread; main thread elsewhere). The real restore is ungated
  on the player still being online, so the world is always closed back up; the rim fakes clear
  automatically when the real block updates broadcast, and the gravity-block fakes are cleared
  with a follow-up client send only while the player is online.
- Because the effect only touches the platform-neutral SPI, the same class loads and runs on
  every platform whose adapter implements that SPI - Bukkit / Paper / Folia, Fabric (including
  the deobf MC 26.x carriers), and NeoForge - with no per-platform gating code. It degrades to
  a harmless no-op on any future platform that has not yet wired the SPI.

## Demo group shipped by default

So the effect is demonstrable out of the box, the addon installs a ready-to-use
`effects/rift.yml` group the first time it loads (it never overwrites an existing file, so
operator edits and deletions stick):

```yaml
when: preload
effects:
  - RIFT.6.2
```

With `effectParsing: true` (the shipped default in `performance.yml`) every `/rtp` then plays
the rift. Delete `plugins/RTP/addons/` and `plugins/RTP/effects/rift.yml`, or set
`effects: []`, to turn it off.

## How `RIFT` is used once registered

`RIFT` behaves like any other effect. Effects are gated by `performance.yml` -> `effectParsing`
(shipped `true`), so leave that enabled. Then either:

**By permission** (`rtp.effect.<stage>.<EFFECT>.<args...>`):

```
rtp.effect.preload.RIFT.6.2
```

opens a 6-block rift radius for 2 seconds.

**By an `effects/` config group** (one file per group):

```yaml
when: preload
effects:
  - RIFT.6.2
```

Use the `preload` stage rather than `presetup`: `preload` fires only once a destination has
been committed and its chunks are loading (so a cancelled or failed request never plays the
animation), and the remaining teleport warmup (`config.yml` -> `teleportDelay`, default 2s)
elapses between that stage and the actual teleport, which is the window the animation plays in.
Keep `seconds` at or below `teleportDelay` so the rift has time to render. The effect also
refuses to fire when the effective teleport delay is below 1 second. The delay is read from the
same authoritative source the teleport itself uses, so every factor counts: the `rtp.nodelay` /
`rtp.noDelay` permissions force an instant teleport, an `rtp.delay.<n>` permission overrides the
config, and only then does `config.yml` -> `teleportDelay` apply. An instant teleport leaves no
warmup window to fall through (and the preload dispatch is async, so the rift could otherwise carve
at the destination and suffocate the player on restore), so the rift simply does not open.

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
