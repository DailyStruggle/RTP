# Frequently Asked Questions

**Current Plugin Version:** `@version@`

Answers to the most common questions from server administrators and contributors.

---

## Installation & Setup

### Does RTP work on Paper / Folia, or only Spigot?

One jar covers every platform: Spigot, Paper and its forks, Folia, Fabric (needs Fabric API), NeoForge, and Velocity. RTP detects the platform at startup and loads the matching adapter. Put it in `plugins/` on Bukkit-family servers and Velocity, or `mods/` on Fabric and NeoForge.

Legacy Forge has no native adapter: run the same jar under Arclight or Mohist.

### Do I need to restart the server after changing config files?

No. Run `/rtp reload` after editing any config file. The only exception is plugin.yml entries, such as adding a new permission node that another plugin reads at startup, as those require a full restart.

### Can I run RTP alongside other teleport plugins?

Yes. RTP does not interfere with other teleport plugins. It only acts on `/rtp` commands and its own subcommands.

### Does RTP support BungeeCord / Velocity cross-server teleportation?

Velocity is supported, over one of three transports:

- **proxy-direct**: the proxy and backends talk directly; no database needed.
- **SQL**: servers share reservations through a MySQL / PostgreSQL database.
- **Redis**: servers share reservations through Redis.

Configure it in `advanced/network.yml`; setup guides are in [proxies](proxies/CONFIGURATION.md).

BungeeCord is not supported yet. A single backend server still works fully on its own with no proxy configuration.

---

## Behaviour

### Why did the first teleport take a few seconds, but later ones were instant?

RTP pre-generates a queue of safe locations in the background. On a cold start (server just started, or after `/rtp reload`), the queue starts empty and the first few teleports may generate locations on-demand if the queue has not yet finished filling. Once the background queue populates, all teleports are instant.

You can increase `cacheCap` in the region config to maintain a larger pool of ready locations, or tune replenishment intervals in `advanced/performance.yml`.

### Players keep landing in the ocean / nether / end. How do I stop that?

Open `plugins/RTP/advanced/biomes.yml`. With `biomeWhitelist: false` (the default), `biomes` is a list of biomes players never land in. Oceans, rivers and beaches are already on it; add any others:

```yaml
biomeWhitelist: false
biomes:
  - OCEAN
  - DEEP_OCEAN
  - FROZEN_OCEAN
  - WARM_OCEAN
  - SOUL_SAND_VALLEY
```

Set `biomeWhitelist: true` to flip it, so players only land in the listed biomes. For Nether or End, give that world its own region (or a world `override`) rather than listing whole dimensions here.

After saving, run `/rtp reload`, then `/rtp scan reset` and `/rtp scan start` so terrain checked under the old list is checked again.

### Players are landing too close to spawn / inside the exclusion zone.

Check `centerRadius` in the region's `shape:` block and raise it. Write it with a unit, e.g. `centerRadius: 1024b` or `centerRadius: 1km`, so players never land closer than that to the centre. (A bare number like `64` means chunks: 64 x 16 = 1,024 blocks.)

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

It trades precision for speed and memory:
- **Higher values** remember bad terrain in coarser patches and check fewer, more widely spaced spots. `/rtp scan` finishes much faster, memory use drops, and landings spread further apart, but small safe pockets between hazards can be skipped.
- **Lower values** check terrain chunk by chunk. More precise, slower to scan.
- **Default (`3`):** right for most servers. With `spatialResolution: 1`, `expand: true` and `uniquePlacements: auto`, RTP picks the spacing from player view distance for you.

Details: [REGIONS.md](configuration/REGIONS.md).

---

## Performance

### How many locations should I pre-generate (`cacheCap`)?

A good rule of thumb:
- Small server (< 20 concurrent players): `cacheCap: 10–20`
- Medium server (20–100 players): `cacheCap: 50–100`
- Large server (> 100 players): `cacheCap: 100–200`

Set it too high and you waste memory holding locations nobody will use. Set it too low and players may occasionally wait for the queue to refill.

### Will RTP lag my server?

Candidate checks and chunk loading run off the main thread. The main-thread work left is the teleport itself and the arriving player's first ticks. In my Paper benchmark there were no ticks over 50 ms across 4,096 paced teleports, but your hardware and world will differ. If you notice lag spikes, check:
- `minTPS` and `maxMSPT` in `performance.yml`; RTP pauses background cache filling when TPS drops below `minTPS` or a tick takes longer than `maxMSPT`. On Folia a slow region is handled on its own: RTP slows its work there and fills from other regions instead.
- `syncAllottedTime`, which limits how long per tick RTP's main-thread work can take (capped at 25 ms). On Folia the limit applies to each region thread separately.

### My TPS is dropping when players teleport. What do I check?

1. Ensure you are on Paper or Folia; both provide async chunk loading APIs that reduce main-thread pressure compared to Spigot.
2. Lower `viewDistanceSelect` in `performance.yml` (default `0` is fine, as higher values pre-load more surrounding chunks).
3. Increase `cacheCap` so fewer on-demand generations happen during peak play.

### Console logs `Heap usage ... exceeds maxHeapPercent threshold 85.0%; pausing background cache generation`. How do I fix it?

`maxHeapPercent: 85.0` in `plugins/RTP/advanced/performance.yml` pauses background cache filling whenever JVM heap usage passes `85.0%` so chunk pre-loading does not trigger an out-of-memory stall. If your JVM normally runs above 85% heap between GC cycles (or during early chunk generation), either lower `cacheCap` / `activeChunkCap` in your region files, raise the server's `-Xmx` heap allocation, or adjust `maxHeapPercent` in `advanced/performance.yml` (`/rtp config performance maxHeapPercent=90.0`, or `0` to disable the check).

---

## Economy & Permissions

### Why does `/rtp player=<name>` say `invalid command argument player:<name>` when targeting an operator?

By default, the `rtp.notme` permission node is granted to server operators (`op`) so one operator or command block cannot forcibly random-teleport another operator against their will. Because your OP account inherits `rtp.notme: true`, targeting an OP player with `player=<name>` (from console or in-game) is rejected. Test `player=<name>` on a non-operator player, or negate the node (`rtp.notme: false` in LuckPerms) for accounts that should be targetable.

### I have Vault installed but economy isn't working.

Vault alone does not provide an economy; it is a bridge. You also need an economy plugin like EssentialsX Economy, CMI, or Gringotts. RTP detects both at startup. If economy is still not working after installing both, check the console for a "Vault economy not found" message and restart cleanly.

### How do I make certain players bypass the economy cost?

Grant the `rtp.free` permission node. Players with this node are never charged.

### How do I restrict a region to specific groups?

Set `requirePermission: true` in the region's config, then grant `rtp.regions.<regionName>` to the groups that should have access. Players without the permission are redirected to the region specified by `override`.

---

## Configuration

### Why does `/rtp` (or `/rtp world=world_nether`) always send me to the overworld, even when I'm in the nether or the end?

This is the most common first-run confusion. RTP ships with a single region (`default` in `definitions/regions/default.yml`) that targets the overworld (`world: "[0]"`), and `definitions/worlds/default.yml` routes every unconfigured world to `region: "default"`. **Do not change `default.yml` to `world_nether`** (that would send overworld players into the nether); instead, **create a region for that world and map the world to it** using `/rtp editor` (the browser map workspace), `/rtp admin setup`, `/rtp config`, or YAML files:

**Option A — Visual Web Editor (`/rtp editor`) or Guided Setup (`/rtp admin setup`):**
- Run `/rtp editor` to create a `nether` region with `world: "world_nether"`, `vert.maxY: 120`, and `vert.requireSkyLight: false`, and point `definitions/worlds/world_nether.yml` at `region: "nether"`, or run `/rtp admin setup` and choose `multi` worlds.

**Option B — Config files (`definitions/regions/` + `definitions/worlds/`):**

**Step 1 — Create the region file** `plugins/RTP/definitions/regions/nether.yml`:
```yaml
world: "world_nether"
cacheCap: 10
shape:
  name: "CIRCLE"
  mode: "ACCUMULATE"
  radius: 2048b
  centerRadius: 256b
vert:
  name: "JUMP"
  minY: 32
  maxY: 120
  requireSkyLight: false
```

**Step 2 — Map the nether world to that region** in `plugins/RTP/definitions/worlds/world_nether.yml` (or run `/rtp config worlds world_nether region=nether`):
```yaml
region: "nether"
```

**Step 3** — Run `/rtp reload`.

Now players who run `/rtp` while standing in the nether (or run `/rtp world=world_nether` / `/rtp region=nether`) will land in the nether. Without Step 2, every world falls back to the `default` region (overworld).

> **Why isn't this automatic?** RTP cannot know your world names, nether height limits, or whether you even want nether teleportation enabled. Explicit configuration prevents accidental teleports into void or bedrock.

### Can I have different regions for different worlds?

Yes. Create a region in `plugins/RTP/definitions/regions/<regionName>.yml` (or via `/rtp editor`) and map the world to it in `plugins/RTP/definitions/worlds/<worldName>.yml`:

```yaml
region: "myRegionName"
```

That world will now use `myRegionName` as its default region when players run `/rtp` there (or `/rtp world=<worldName>`).

### How do I change the teleport radius for one world? There is no `radius` in `worlds/<world>.yml`.

Correct, and it is not missing - radius is not a world setting. A world file only names the region that answers `/rtp` there; the **region** (`definitions/regions/<region>.yml`) owns the destination, including distance (`shape.radius` / `shape.centerRadius`), shape, vertical range (`vert.minY` / `vert.maxY` / `vert.requireSkyLight`), and price. So you change the region, not the world:

1. **Use the Web Editor (`/rtp editor`) or edit the region in `definitions/regions/<region>.yml`:** Run `/rtp editor` to drag the region boundary on the map, or run `/rtp config regions <region> shape.radius=5000b` (add `--dry-run` to preview), or edit the `shape:` block in `plugins/RTP/definitions/regions/<region>.yml` with explicit unit suffixes (`5000b`, `5km`, `256c`).
2. **If that region is shared** by other worlds and you only want this one to change, create a new region file in `definitions/regions/` and point `definitions/worlds/<world>.yml` at it (`region: "<newRegion>"`).

> **Note on `config.yml` vs `definitions/regions/`:** On a fresh install `definitions/regions/default.yml` sets `shape: "@config"` and `vert: "@config"` as a starter fallback to `config.yml`, but in general you should configure regions via `/rtp editor` and `definitions/regions/<region>.yml` (`shape` and `vert` settings) rather than treating `config.yml` as the primary place for region geometry. Also prefer setting `shape.radius` with a unit suffix (e.g. `2500b` or `5km`) over enabling `worldBorderOverride: true` (which is generally not recommended because it replaces your entire `shape` block and discards `centerRadius`). Full walkthrough: [Where to set the radius](configuration/REGIONS.md#where-to-set-the-radius).

The payoff for the extra hop: because distance belongs to the region, one world can offer several distances at the same time. Ship `wild` at `radius: 10km` and `wild_far` at `radius: 50km`, gate the second with `requirePermission: true`, and players who have it use `/rtp region=wild_far`. A radius attached to the world could not do that.

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

RTP already ships claim-plugin support for the common protection plugins (GriefPrevention, WorldGuard, Towny, etc.) as a bundled addon that unpacks on first run - there is nothing extra to install. To add your *own* integration, look at the `addons/RTP_ExampleAddon/` directory for a working example: your addon compiles against `rtp-api` only, registers a safety verifier through `RTPAPI.hooks()` (the same seam the bundled claim checks use), and registers itself on plugin enable. See [the addon API contract](https://github.com/dailystruggle/RTP/blob/V3/addons/REQUIREMENTS.md) for details.

### I added a requirement to a `REQUIREMENTS.md` file and CI is failing.

The `check_traceability.sh` script enforces that every `REQ-*` ID in a requirements file has a corresponding row in `TRACEABILITY.md`. Add the row before pushing. See [CONTRIBUTING.md](https://github.com/dailystruggle/RTP/blob/V3/CONTRIBUTING.md) for the full four-step workflow.

### Where are the automated tests?

Unit and architecture tests live in `rtp-core/src/test/java/`. The architecture tests (`RTPArchitectureTest.java`) enforce that the core module has no platform imports and makes no blocking async calls. Run all tests with:

```
./gradlew test
```

---

## Still Stuck?

- Open an issue on [GitHub](https://github.com/DailyStruggle/RTP/issues) using the bug report template.
- For security vulnerabilities, follow the private disclosure process in [SECURITY.md](https://github.com/dailystruggle/RTP/blob/V3/SECURITY.md).
