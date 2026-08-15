# Intended usage

LeafRTP is built around one idea: **a region is a pre-defined area with its own parameters and its own memory.** Caching, safety scanning, and per-world behavior all hang off that. Read this first - the rest of the documentation assumes you have this model.

!!! note "The mental model in one sentence"
    You define **regions** (where players land and how), you point each **world** at a region, and players run a bare `/rtp`. The engine pre-generates and remembers safe spots per region, so teleports are instant and never freeze the server.

---

## The happy path

1. **Install** and start the server once to write the default config.
2. **Configure** a region's geometry (shape, size, center) - see [Regions](https://github.com/DailyStruggle/RTP/wiki/Regions).
3. **Point each world at a region** in its config - see [Worlds](https://github.com/DailyStruggle/RTP/wiki/Worlds).
4. **Warm the cache**: `/rtp scan start region=<name>` per reasonably-sized region - see [Scan and spatial memory](https://github.com/DailyStruggle/RTP/wiki/Scan-and-Spatial-Memory).
5. Players run a plain **`/rtp`**, which resolves to their world's region.

!!! tip "Plain `/rtp` is the supported path"
    The everyday command for players, signs, and portals is a bare `/rtp` (or `/rtp world=<name>` to target a specific world's region). Those resolve to a configured region, so they are cached, safety-checked, and remember failures. *Override* parameters (`shape=`, `radius=`, `centerX=`, ...) are not - see [the warning below](#teleport-parameters-are-for-testing).

---

## A worked example

**Goal:** players type `/rtp` and scatter safely within 10,000 blocks of overworld spawn, instantly.

```yaml
# in the overworld region's config (see the Regions page for the file)
shape:
  name: CIRCLE
  radius: 625      # in CHUNKS: 625 * 16 = 10,000 blocks
  centerX: 0       # center, in chunks
  centerZ: 0
```

Then point the overworld at that region ([Worlds](https://github.com/DailyStruggle/RTP/wiki/Worlds)), warm it with `/rtp scan start region=overworld` (watch `/rtp scan info`), and you are done. A player typing `/rtp` gets a cached, pre-verified coordinate.

!!! note "`radius`, `centerX`, and `centerZ` are measured in chunks"
    They live *inside* the `shape:` block (not at the top level), and their unit is **chunks**, not blocks - 1 chunk is 16 blocks. A `radius` of `625` therefore reaches 10,000 blocks. See [Regions](https://github.com/DailyStruggle/RTP/wiki/Regions) for the full field reference.

!!! note "That is the whole loop"
    Configure a region -> point a world at it -> scan to warm it -> players run plain `/rtp`. The rest of this page explains *why*.

---

## Why it scans ahead of time

Finding a *safe* spot is expensive: the engine must load (and often generate) the chunk at some `(x, z)` and check it - solid surface? lava? claimed? void? Doing that the instant a player runs `/rtp` is what freezes legacy plugins.

LeafRTP does that work **ahead of time, off the main thread**: a background process pre-generates and safety-checks coordinates into the region's **cache**, and records bad spots in its **failure map** so they are never retried. By the time a player teleports, the answer is already waiting.

This pre-scanned data **is** the region's memory - which is why changing a region's shape is disruptive.

---

## Why a shape change wipes a region's memory

A region's **shape** is its geometry - the boundary (circle, square, ...), size, and center that decide which coordinates are eligible. The cache and failure map are indexed against the exact coordinate set the shape produces (the engine maps the 2D area onto a 1D Archimedean spiral, and every cached or failed spot is a position in that sequence).

Change the shape and that mapping changes - the stored data now describes an area that no longer exists. Reusing it would send players to wrong or unsafe spots, so LeafRTP **discards the region's memory on a shape change** and rebuilds.

!!! warning "Set/update commands rebuild the region"
    Commands that set or update region parameters work by removing and re-adding the region, which resets its cache and memory. Tune deliberately, not repeatedly.

---

## Defaults make it work out of the box

LeafRTP uses **default region/world data** as the template for any world or region created at runtime. These defaults are **configurable** but **required** - delete them and the plugin misbehaves. Keep them sane and new worlds just work.

---

## How a command resolves to a region

| Player runs | Teleports using |
|---|---|
| `/rtp` | The region configured for the player's **current** world |
| `/rtp world=<name>` | The region configured for the **named** world |
| `/rtp region=<name>` | The named region directly |

Commands use `key=value` parameters, not bare positionals (there is no `/rtp <world>` - it is `/rtp world=<name>`). In the common case you do not address regions directly - the world-to-region mapping does it.

---

## Match it to your server's size and shape

The same model scales from a one-world survival server to a large, multi-world, parallel-threaded network. What changes is how many regions you define and how aggressively you warm them.

=== "Small single-world server"

    If you run one survival world, you need exactly **one region** pointed at that world.

    1. Edit the default region: set its `world` to your world's name (e.g. `world`), pick a `shape`, `radius`, and center - see the [worked example](#a-worked-example) above.
    2. Leave the `default` world file pointed at it (the out-of-the-box mapping already does this).
    3. Warm it once with `/rtp scan start region=<name>` and you are done.

    !!! tip "You do not need extra regions"
        A single-world server never needs more than the default region. Skip the nether/end/custom-dimension regions below until you actually add those worlds.

=== "Big / multi-world / parallel server"

    Larger servers commonly run **many worlds at once** - separate overworld, nether, end, a resource world that resets, minigame worlds, and modded dimensions - and on regionised-threading platforms those worlds (and even areas within one world) run on **parallel threads**.

    - Define **one region per place you want `/rtp` to send players** (overworld, resource world, each dimension), then point each world file at the right region. See [Worlds](https://github.com/DailyStruggle/RTP/wiki/Worlds) and [Regions](https://github.com/DailyStruggle/RTP/wiki/Regions).
    - Warm each static region with its own `/rtp scan start region=<name>`. Skip warming worlds that reset often (a resource world) - their memory would be invalidated on every reset.
    - Because the engine does all chunk work off the main thread, it stays compatible with regionised, multi-threaded servers - no world's RTP traffic stalls another's.

    !!! note "New about big, parallel Minecraft servers?"
        For background on why modern servers split work across worlds and threads (and what "regionised multithreading" means), see PaperMC's documentation on [multi-world setups](https://docs.papermc.io/paper/reference/world-configuration/) and [Folia regionised threading](https://docs.papermc.io/folia/). LeafRTP is built to fit both models.

---

## Teleport parameters are for testing

You *can* pass *override* parameters (custom `shape`, `radius`, `centerX`/`centerZ`) in a command, but it is **not** the everyday path. Doing so builds a **temporary region** that is deleted after use, remembers no failures, and is therefore uncached - every call pays full generation cost. (`world=` and `region=` are different: they point at an existing configured region and stay cached.)

!!! warning "Never wire override parameters into player commands, signs, or portals"
    Override parameters skip caching and spatial memory, so they are far more expensive and can stall under load. Use them to test a region's settings, then bake them into a named region and point players at a plain `/rtp`. See [What NOT to do!](what-not-to-do.md).

---

## Key takeaways

| If you want to... | Do this |
|---|---|
| Fast, safe random teleport | Configure a region, point the world at it, players run plain `/rtp` |
| Avoid first-teleport lag | `/rtp scan start region=<name>` after configuring |
| Change where/how a region lands players | Edit the region (it rebuilds and clears that region's memory) |
| New worlds "just work" | Keep sane, complete **default** config |
| Test a one-off shape/radius | Use override parameters - never bind them to player commands/signs/portals |

---

## Where to go next

- [Regions](https://github.com/DailyStruggle/RTP/wiki/Regions) / [Worlds](https://github.com/DailyStruggle/RTP/wiki/Worlds) - configure the model.
- [Typical configuration order](https://github.com/DailyStruggle/RTP/wiki/Typical-Configuration-Order) - the recommended sequence.
- [Scan and spatial memory](https://github.com/DailyStruggle/RTP/wiki/Scan-and-Spatial-Memory) - warm the cache.
- [What NOT to do!](what-not-to-do.md) - the anti-patterns.
- [Behavior](https://github.com/DailyStruggle/RTP/wiki/Behavior) - the runtime story in depth.
