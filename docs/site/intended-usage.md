# Intended usage

LeafRTP is built around one idea: **a region is a pre-defined area with its own parameters and its own memory.** Caching, safety scanning, and per-world behavior all hang off that. Read this first - the rest of the documentation assumes you have this model.

!!! note "The mental model in one sentence"
    You define **regions** (where players land and how), you point each **world** at a region, and players run a bare `/rtp`. The engine pre-generates and remembers safe spots per region, so teleports are instant and never freeze the server.

---

## The happy path

1. **Install** and start the server once to write the default config.
2. **Configure** a region's geometry (shape, size, center) - see [Regions](../admin/configuration/REGIONS.md).
3. **Point each world at a region** in its config - see [Worlds](../admin/configuration/WORLDS.md).
4. **Pre-scan for safety**: optionally run `/rtp scan start region=<name>` per reasonably-sized region to build the bad-location map ahead of time - see the [Quick start](../admin/QUICK_START.md) scan step.
5. Players run a plain **`/rtp`**, which resolves to their world's region.

!!! tip "Plain `/rtp` is the supported path"
    The everyday command for players, signs, and portals is a bare `/rtp` (or `/rtp world=<name>` to target a specific world's region). Those resolve to a configured region, so they utilize the pre-generated location queue, safety caching, and persistent spatial memory. *Override* parameters (`shape=`, `radius=`, `centerX=`, ...) under `rtp.params` create temporary, uncached regions - see [the warning below](#teleport-parameters-are-for-testing).

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

Then point the overworld at that region ([Worlds](../admin/configuration/WORLDS.md)), optionally pre-scan it with `/rtp scan start region=overworld` (watch `/rtp scan info`) to map known-bad spots in advance, and you are done. A player typing `/rtp` gets a pre-verified coordinate from the active queue.

!!! note "`radius`, `centerX`, and `centerZ` units"
    They live *inside* the `shape:` block (not at the top level). By default, raw numbers are in **chunks** (1 chunk = 16 blocks), so `radius: 625` reaches 10,000 blocks. You can also specify spatial unit suffixes directly (e.g. `10000b`, `10km`, `625c`, `20r`). See [Regions](../admin/configuration/REGIONS.md) for the full field and unit reference.

!!! note "That is the whole loop"
    Configure a region -> point a world at it -> pre-scan safety -> players run plain `/rtp`. The rest of this page explains *why*.

---

## How locations are prepared and verified

Finding a *safe* spot requires checking candidate coordinates: is there a solid surface? lava? claim-protected land? void? Doing that synchronously the instant a player runs `/rtp` is what freezes legacy plugins.

LeafRTP handles this cleanly and asynchronously off the main thread:

1. **Location Queue (Cache)**: A background task (`QueueTask` / `RegionQueueManager`) continuously generates, validates, and stores verified safe coordinates in the region's cache queues (hot and cold caches). When a player types `/rtp`, a pre-verified coordinate is served immediately from the queue.
2. **Spatial Memory & Safety Pre-Scanning (`/rtp scan`)**: The region maintains persistent spatial memory recording coordinates that fail safety checks so the selection algorithm skips them in future searches. The `/rtp scan` command acts as a background safety pre-scanner: it walks the region's spiral off-tick to map out unsafe sectors ahead of time. It operates independently of the location queues, building spatial memory so future generation and teleport selection avoid known-bad areas.

This spatial memory is tied to the region's specific shape and bounds - which is why changing a region's shape resets its memory.

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
    3. Pre-scan it once with `/rtp scan start region=<name>` to map safety, and you are done.

    !!! tip "You do not need extra regions"
        A single-world server never needs more than the default region. Skip the nether/end/custom-dimension regions below until you actually add those worlds.

=== "Big / multi-world / parallel server"

    Larger servers commonly run **many worlds at once** - separate overworld, nether, end, a resource world that resets, minigame worlds, and modded dimensions - and on regionised-threading platforms those worlds (and even areas within one world) run on **parallel threads**.

    - Define **one region per place you want `/rtp` to send players** (overworld, resource world, each dimension), then point each world file at the right region. See [Worlds](../admin/configuration/WORLDS.md) and [Regions](../admin/configuration/REGIONS.md).
    - Pre-scan each static region with its own `/rtp scan start region=<name>` to build spatial memory. Skip scanning worlds that reset often (a resource world) - their memory would be invalidated on every reset.
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
| Pre-compute spatial safety | `/rtp scan start region=<name>` after configuring |
| Change where/how a region lands players | Edit the region (it rebuilds and clears that region's memory) |
| New worlds "just work" | Keep sane, complete **default** config |
| Test a one-off shape/radius | Use override parameters - never bind them to player commands/signs/portals |

---

## Where to go next

- [Regions](../admin/configuration/REGIONS.md) / [Worlds](../admin/configuration/WORLDS.md) - configure the model.
- [Quick start](../admin/QUICK_START.md) - the recommended end-to-end setup sequence.
- [Commands](../admin/COMMANDS.md) - the command reference including `/rtp scan`.
- [What NOT to do!](what-not-to-do.md) - the anti-patterns.
- [Why LeafRTP exists](why.md) - the runtime story and distribution algorithm in depth.
