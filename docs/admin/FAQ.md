# Frequently Asked Questions

**Current Plugin Version:** `@version@`

Answers to the most common questions from server administrators and contributors.

---

## Installation & Setup

### Does RTP work on Paper / Folia, or only Spigot?

The Bukkit family is all supported by one jar: download the same jar for Spigot, Paper, Paper forks, and Folia, and RTP auto-detects the platform at startup and loads the correct adapter. No separate jar is needed across the Bukkit family.

Fabric and NeoForge are also supported as first-class platforms (Minecraft 1.21.x / 26.1.x); those run as a mod, so you download the Fabric or NeoForge artifact rather than the Bukkit jar. Legacy Forge has no native build: run the Bukkit jar under Arclight or Mohist.

### Do I need to restart the server after changing config files?

No. Run `/rtp reload` after editing any config file. The only exception is plugin.yml entries, such as adding a new permission node that another plugin reads at startup, as those require a full restart.

### Can I run RTP alongside other teleport plugins?

Yes. RTP does not interfere with other teleport plugins. It only acts on `/rtp` commands and its own subcommands.

### Does RTP support BungeeCord / Velocity cross-server teleportation?

Velocity is supported. The `proxy-direct` transport lets a Velocity proxy send a player to a random region on a backend server over a lightweight TCP socket, with no Redis or SQL database required. Configure it in `network.yml`.

BungeeCord is not supported yet. A single backend server still works fully on its own with no proxy configuration.

---

## Behaviour

### Why did the first teleport take a few seconds, but later ones were instant?

RTP pre-generates a queue of safe locations in the background. On a cold start (server just started, or after `/rtp reload`), the queue is empty and the first few teleports must generate locations on-demand, which requires loading chunks. Once the queue is warm, all teleports are instant.

Run `/rtp scan` to pre-warm the queue before players join, or increase `cacheCap` in the region config to keep more locations ready.

### Players keep landing in the ocean / nether / end. How do I stop that?

Open `plugins/RTP/safety.yml` and add the unwanted biomes to the biome blacklist. Common entries:

```yaml
biomeBlacklist:
  - OCEAN
  - DEEP_OCEAN
  - FROZEN_OCEAN
  - WARM_OCEAN
  - NETHER_WASTES
  - SOUL_SAND_VALLEY
  - THE_END
```

After saving, run `/rtp reload`.

### Players are landing too close to spawn / inside the exclusion zone.

Check the `centerRadius` value in the region's `shape:` block. This is measured in **chunks**, not blocks. A `centerRadius: 64` means players cannot land within 64 × 16 = 1 024 blocks of the centre. Increase it if needed.

### Players are always landing in the same area.

This can happen if:
- `uniquePlacements: 0` and the region is small; try increasing `radius`.
- The bad-sector memory has marked most of the region as invalid. Reset it by deleting `rtp-core/database/regionData/<regionName>` and restarting.
- `mode: NEAREST` is set, which can cluster landings near unblocked edges. Switch to `mode: ACCUMULATE` for even distribution.

### "No safe location found" — what does this mean?

RTP exhausted its `maxAttempts` (set in `performance.yml`) without finding a valid location. Common causes include:
1. The region radius is too small relative to the number of excluded biomes.
2. `minY` / `maxY` are too restrictive for the world's terrain, such as nether with `maxY: 255`.
3. The entire region has been marked bad in the database. Delete the region's database entry and restart.

### What does `spatialResolution` do?

It controls how precisely the plugin stores spatial memory. Higher values result in a finer grid and more memory usage, while lower values create a coarser grid with less memory but more imprecision. The default (`3`) suits most servers. Increase it only if players are reporting they can't land in areas that should be valid.

---

## Performance

### How many locations should I pre-generate (`cacheCap`)?

A good rule of thumb:
- Small server (< 20 concurrent players): `cacheCap: 10–20`
- Medium server (20–100 players): `cacheCap: 50–100`
- Large server (> 100 players): `cacheCap: 100–200`

Set it too high and you waste memory holding locations nobody will use. Set it too low and players may occasionally wait for the queue to refill.

### Will RTP lag my server?

RTP is designed to be lag-free. All chunk loading and location validation happens asynchronously off the main thread. The only main-thread work is the final teleport itself, which Bukkit requires. If you notice lag spikes, check:
- `minTPS` in `performance.yml`; RTP throttles background work when TPS drops below this value.
- `syncAllottedTime`, which limits how long per tick the synchronous portion of a teleport can take.

### My TPS is dropping when players teleport. What do I check?

1. Ensure you are on Paper or Folia; both provide async chunk loading APIs that reduce main-thread pressure compared to Spigot.
2. Lower `viewDistanceSelect` in `performance.yml` (default `0` is fine, as higher values pre-load more surrounding chunks).
3. Increase `cacheCap` so fewer on-demand generations happen during peak play.

---

## Economy & Permissions

### I have Vault installed but economy isn't working.

Vault alone does not provide an economy; it is a bridge. You also need an economy plugin like EssentialsX Economy, CMI, or Gringotts. RTP detects both at startup. If economy is still not working after installing both, check the console for a "Vault economy not found" message and restart cleanly.

### How do I make certain players bypass the economy cost?

Grant the `rtp.free` permission node. Players with this node are never charged.

### How do I restrict a region to specific groups?

Set `requirePermission: true` in the region's config, then grant `rtp.regions.<regionName>` to the groups that should have access. Players without the permission are redirected to the region specified by `override`.

---

## Configuration

### Why does `/rtp` always send me to the overworld, even when I'm in the nether or the end?

This is the most common first-run confusion. RTP ships with a single region (`default`) that targets the overworld (`world: "[0]"`). **There is no nether or end region out of the box**, so you have to create them.

Here's the full setup for a nether region:

**Step 1 — Create the region file** `plugins/RTP/definitions/regions/nether.yml`:
```yaml
world: "world_nether"
cacheCap: 10
shape:
  name: "CIRCLE"
  mode: "ACCUMULATE"
  radius: 128
  centerRadius: 16
vert:
  name: "JUMP"
  minY: 32
  maxY: 120
  requireSkyLight: false
```

**Step 2 — Map the nether world to that region** in `plugins/RTP/definitions/worlds/world_nether.yml`:
```yaml
region: "nether"
```

**Step 3** — Run `/rtp reload`.

Now players who run `/rtp` while standing in the nether will land in the nether. Without Step 2, every world falls back to the `default` region (overworld).

> **Why isn't this automatic?** RTP cannot know your world names, nether height limits, or whether you even want nether teleportation enabled. Explicit configuration prevents accidental teleports into void or bedrock.

### Can I have different regions for different worlds?

Yes. Create a file in `plugins/RTP/definitions/worlds/<worldName>.yml` and set:

```yaml
region: "myRegionName"
```

That world will now use `myRegionName` as its default region when players run `/rtp` there.

### Can I disable the invulnerability period?

Set `invulnerabilityTime: 0` in `plugins/RTP/safety.yml`.

### Can I use a normal distribution instead of a flat spread?

Yes. Change `name` in the region's `shape:` block to `CIRCLE_NORMAL` or `SQUARE_NORMAL`, then configure `mean` and `deviation` to place the peak of the distribution where you want most players to land.

### Can I make the teleport land near a specific player or location?

No, and this is intentional. RTP's performance guarantee depends entirely on **pre-generating locations before anyone asks for them**. The background queue fills up with validated, safe coordinates while the server is idle.

If teleport destinations were relative to a moving player, pre-generation would be impossible. The plugin would have to generate a fresh location at the moment of the command, which means loading chunks on-demand and potentially stalling the server. That is exactly the naive reroll problem RTP was designed to eliminate.

If you need player-relative placement, such as landing within 500 blocks of a friend, that is a fundamentally different feature with different performance characteristics. It is out of scope for RTP. Consider a dedicated plugin for that use case, or implement it as an addon that bypasses the queue and accepts the latency trade-off explicitly.

---

## Development & Addons

### How does RTP load addons, and how do I turn the bundled ones off?

Addons can arrive three ways - as a standalone plugin/mod, dropped into `plugins/RTP/addons/`, or bundled inside the RTP jar and auto-extracted to that folder on first run (this is how the on-by-default GUI picker ships). To turn a bundled addon off, delete its individual jar from `plugins/RTP/addons/` (do **not** delete the whole folder - a missing folder is treated as a fresh install and re-extracts the bundled jars) or set its config knob. Full details, including the `/rtp gui` behavior and a troubleshooting table, are in [ADDONS.md](ADDONS.md).

### I want to add a custom claim plugin integration. Where do I start?

RTP already bundles claim-plugin support for the common protection plugins (GriefPrevention, WorldGuard, Towny, etc.) directly in the plugin jar — there is no separate integration jar to install (see [ADR-019](../adr/ADR-019-claim-plugin-integrations-folded-into-plugin.md)). To add your *own* integration, look at the `addons/RTP_ExampleAddon/` directory for a working example: your addon compiles against `rtp-api` only, registers a safety verifier through `RTPAPI.hooks()` (the same seam the bundled claim checks use), and registers itself on plugin enable. See [../../addons/REQUIREMENTS.md](../../addons/REQUIREMENTS.md) for the addon API contract.

### I added a requirement to a `REQUIREMENTS.md` file and CI is failing.

The `check_traceability.sh` script enforces that every `REQ-*` ID in a requirements file has a corresponding row in `TRACEABILITY.md`. Add the row before pushing. See [../../CONTRIBUTING.md](../../CONTRIBUTING.md) for the full four-step workflow.

### Where are the automated tests?

Unit and architecture tests live in `rtp-core/src/test/java/`. The architecture tests (`RTPArchitectureTest.java`) enforce that the core module has no platform imports and makes no blocking async calls. Run all tests with:

```
./gradlew test
```

---

## Still Stuck?

- Open an issue on [GitHub](https://github.com/DailyStruggle/RTP/issues) using the bug report template.
- For security vulnerabilities, follow the private disclosure process in [../../SECURITY.md](../../SECURITY.md).
