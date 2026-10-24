# Quick Start Guide

**Current Plugin Version:** `@version@`

**Minimum:** drop the jar in `plugins/` (or `mods/`), restart, type `/rtp`. That works as-is. RTP learns the terrain as players teleport; the only cost of skipping the rest is that its memory of bad terrain builds up over time instead of up front.

**Primary setup paths (pick either - both are self-contained):**

- **Browser map workspace (`/rtp editor`):** Opens your config and world map in the browser with no port forwarding. Draw and resize region borders directly on the map, edit settings with each key's docs beside it, and review a diff before applying ([Web Editor Guide](WEB_EDITOR_GUIDE.md)).
- **In-game guided wizard ([`/rtp admin setup`](#guided-setup-rtp-admin-setup)):** Walks through world layout, gameplay presets, region radius (via clickable presets or Anvil input), performance profile, and addon toggles, then previews every file change before writing.

**Recommended after setup:**

1. `/rtp scan start` to check the terrain ahead of time ([Step 9](#step-9-build-spatial-memory-with-rtp-scan)).
2. A stress test if you expect heavy traffic ([Step 10](#step-10-verify-with-rtp-info-and-rtp-test)).

Everything else below is optional: pregeneration, manual YAML or `/rtp config` edits, per-world files, permissions beyond the defaults, economy, extra regions. Each step links to the full doc.

---

## Guided setup (`/rtp admin setup`)

Run `/rtp admin setup` in game (permission `rtp.admin.setup`) for the guided wizard, or `/rtp editor` (permission `rtp.editor`) to configure visually in your browser. `/rtp admin setup` walks you through:

1. **Worlds:** `single` or `multi`.
2. **Gameplay & Region Radius:** `survival`, `arena`, `skyblock` or `oneblock`, plus your region `radius` and `centerRadius` (via clickable distance presets or Anvil input).
3. **Performance:** `high`, `low` or `folia`.
4. **Extras:** bare-`/rtp` GUI behavior (`openOnBareRtp`), claim checks, teleport effects, economy on or off.
5. **Preview:** `/rtp admin setup preview` shows every file change before anything is written.
6. **Confirm:** `/rtp admin setup confirm` writes the files (with backups) and reloads.

`back`, `next`, `status` and `cancel` move around or abandon the wizard.

You can also adjust region distances at any time by dragging the border on the map in `/rtp editor`, running a one-line command, or editing the file by hand ([Step 4](#step-4-adjust-the-teleport-area)):

```
/rtp config regions default shape.radius=10km --dry-run   # preview
/rtp config regions default shape.radius=10km shape.centerRadius=1km
```

Once your region radius is set, run `/rtp scan start`.

---

## Step 0 — Prerequisites & Pregenerate the World

**Optional.** Only Java 17+ is required. The rest makes the first teleports and the first `/rtp scan` faster: RTP loads chunks off the main thread on demand, so on a world that was never generated those first runs wait on chunk generation.

1. **Java 17+** is required, or whatever your Minecraft version needs (Java 21 from 1.20.5, Java 25 on 26.x).
2. **Pick your platform:** Paper or Spigot 1.18+, Folia 1.21.11+, Fabric 1.20.1 or 1.21+, or NeoForge 1.21.1+, up to 26.3. Bug reports are accepted for 1.21.11 through the latest release; older versions are best effort, except Folia, which is experimental and not supported below 1.21.11 ([support matrix](../dev/SUPPORT_MATRIX.md)).
3. **Install Vault + an economy plugin first** if you plan to charge for `/rtp` (Step 7).
4. **Install your claim plugin first** (GriefDefender, GriefPrevention, Lands, WorldGuard, Towny, …) if you want claim-aware teleport. RTP autodetects them at load.
5. **Pregenerate** (optional) each world you'll add to RTP, sized to the region from Step 4. A region with `radius: 4096b` needs 4,096 blocks pregenerated from `centerX, centerZ`, plus a small margin.
   - **Chunky** (Paper/Spigot/Folia): `/chunky world <world>`, `/chunky radius <blocks>`, `/chunky start`.
   - **WorldBorder** (legacy): `/wb <world> set <radius>`, `/wb fill`.

> With the world pregenerated, Step 9's `/rtp scan` only checks safety instead of also generating chunks, so it finishes much sooner.

---

## Step 1 — Install the Plugin

1. Download the jar from [Modrinth](https://modrinth.com/plugin/leafrtp) (free build) or [BuiltByBit](https://builtbybit.com/resources/leafrtp-pro.105418/) (Pro).
2. Drop the jar into your server's `plugins/` folder (or `mods/` on Fabric / NeoForge).
3. Restart the server (rather than using `/reload`), as a full restart ensures all hooks register correctly.

After the first start, RTP creates its config folder at `plugins/RTP/` containing `definitions/regions/default.yml`, one `definitions/worlds/<world>.yml` per loaded world, plus `safety.yml`, `economy.yml`, `performance.yml`, `messages.yml`, and more. (On Fabric / NeoForge the folder is `config/rtp/` instead of `plugins/RTP/`.)

---

## Step 2 — Verify It Loaded

Run this in your server console or as an operator:

```
/rtp
```

With the bundled GUI addon active, regular players typing `/rtp` only see the regions they have permission to use, while the admin panel and map visualization links are strictly permission-gated (`rtp.admin` / OP) and hidden from regular players. Pick a region to teleport.

If you want bare `/rtp` to teleport straight away instead of opening the menu (while keeping `/rtp gui` available), set `openOnBareRtp: false` in `plugins/RTP/addons/guimenu.yml` or toggle it in `/rtp admin setup` - you do not need to delete `LeafRTPGuiAddon.jar`.

If you land somewhere random, the plugin is working. If you see a permission error, make sure your account has `rtp.use` (operators have all permissions by default).

---

## Step 3 — Understand the Default Region

RTP ships with one region called `default`, configured in:

```
plugins/RTP/definitions/regions/default.yml
```

Out of the box it:
- Targets your main world (`[0]`, which Bukkit resolves to `world`).
- Uses a **circle** shape with a radius of **4,096 blocks** (256 chunks).
- Keeps players at least **1,024 blocks** (64 chunks) from the centre so nobody lands at spawn.
- Excludes ocean, nether, and end biomes.
- Grants 5 seconds of invulnerability on landing.
- Pre-generates a queue of safe locations in the background.
- Spatial Memory: Maps the region to learn which coordinates are unsafe (e.g., oceans) and skips them in future searches.

You can use this region as-is for most servers.

---

## Step 4 — Adjust the Teleport Area

All shape settings live directly inside the region file. Open `plugins/RTP/definitions/regions/default.yml`
and look for the `shape:` block:

```yaml
shape:
  name: "CIRCLE"       # CIRCLE, SQUARE, or RECTANGLE
  mode: "REROLL"       # default selection mode (see CONFIGURATION.md for REROLL vs ACCUMULATE)
  radius: 4096b        # maximum distance from centre (4096b, 4km, 256c all work)
  centerRadius: 1024b  # minimum distance - players won't land closer than this
  centerX: 0
  centerZ: 0
  weight: 1.0          # >1.0 = centre-weighted; <1.0 = edge-weighted; 1.0 = flat
```

> **Tip:** write distances with a unit: `b` (blocks), `c` (chunks), `m`, `km`. A bare number means chunks, so `radius: 256` is 4,096 blocks.
>
> Pick the radius for how players will use it: a few km for a busy survival world where players should meet, 10km+ for resource worlds or servers where bases should stay hidden. Bigger regions take longer to scan.

For the full key list (vertical clamps, biome filters, cache cap, mode options, etc.) see [REGIONS.md](configuration/REGIONS.md). For safety filters (biome blacklist, unsafe blocks, hazards) see [SAFETY.md](configuration/SAFETY.md) — and remember that any change to `safety.yml` must be followed by `/rtp scan reset` + `/rtp scan start` in Step 9 to discard memory validated under the old rules ([RUNBOOK.md](RUNBOOK.md)).

Save the file and run `/rtp reload` to apply changes without restarting.

---

## Step 5 — Configure Worlds (`plugins/RTP/definitions/worlds/<world>.yml`)

Each world that should accept `/rtp` needs a file in `definitions/worlds/`. RTP creates one per loaded world on first run; review each:

```yaml
region: "default"            # which region this world's /rtp uses
requirePermission: false     # true = require rtp.worlds.<world>
override: "[0]"              # fallback world if the player lacks permission
```

- **Replace `[0]` / `[1]` / `[2]` placeholders with case-sensitive world names** (e.g. `override: "world"`). Placeholders resolve by load order and can shift when worlds are added or removed.
- Set `requirePermission: true` to gate the world behind `rtp.worlds.<world>`.
- Point `override` at the world to redirect to when permission is missing.

**`/rtp world=<world>` vs `/rtp region=<region>` (and BetterRTP permissions):**
- `/rtp world=<world>` routes through `<world>`'s world file (`definitions/worlds/<world>.yml`) to whichever region that world is configured to use. `/rtp region=<region>` targets a specific region file (`definitions/regions/<region>.yml`) directly, using that region's own `world`, shape, and radius.
- **Migrating from BetterRTP:** LeafRTP does **not** require a second per-world permission node (like `betterrtp.world.<world>`) alongside `rtp.use` out of the box. Because `requirePermission: false` is the default in both world and region files, `rtp.use` alone is enough for players to use `/rtp` in any allowed world. You only grant `rtp.worlds.<world>` or `rtp.regions.<region>` if you set `requirePermission: true` on that file or want players to pass explicit `world=<world>` / `region=<region>` command arguments (`rtp.world` / `rtp.region`).

Full reference: [WORLDS.md](configuration/WORLDS.md).

---

## Step 6 — Set Up Permissions

**Optional.** Players get `rtp.use` by default. Assign the rest with your permission plugin (e.g., LuckPerms):

| Permission | What it grants |
|---|---|
| `rtp.use` | Use `/rtp` to teleport yourself |
| `rtp.see` | See RTP messages and `/rtp help` |
| `rtp.free` | Bypass economy cost |
| `rtp.noCooldown` | Bypass cooldown |
| `rtp.other` | Teleport another player (`/rtp player=<player>`) |
| `rtp.reload` | Use `/rtp reload` |
| `rtp.config.view` | Use `/rtp config <file> view` (read-only inspection) |
| `rtp.config.set` | Use `/rtp config <file> …` to write any config file (umbrella) |
| `rtp.config.set.<section>` | Write only the named section (e.g. `rtp.config.set.regions`); see [COMMANDS.md](COMMANDS.md) section `/rtp config` |
| `rtp.config` | Legacy alias — grants `rtp.config.view` + `rtp.config.set` |
| `rtp.info` | Use `/rtp info` |
| `rtp.scan` | Use `/rtp scan` to build spatial memory |
| `rtp.test` | Use `/rtp test` runtime self-tests |

Example LuckPerms command to grant basic use to all players:

```
/lp group default permission set rtp.use true
/lp group default permission set rtp.see true
```

Full permission reference (including world/region permissions like `rtp.worlds.<world>` and `rtp.regions.<name>`): [COMMANDS.md](COMMANDS.md) section "Full Permission Reference".

---

## Step 7 — (Optional) Enable Economy

If you have **Vault** and an economy plugin installed, RTP will automatically detect them.

Edit `plugins/RTP/economy.yml`:

```yaml
price: 50.0          # cost per /rtp
refundOnCancel: true # refund if teleport is cancelled
```

Set `price: 0.0` to disable the cost entirely. Full options: [ECONOMY.md](configuration/ECONOMY.md).

---

## Step 8 — (Optional) Add More Regions

To create a second region (e.g., for a nether world):

1. Copy `plugins/RTP/definitions/regions/default.yml` to `plugins/RTP/definitions/regions/nether.yml`.
2. Edit `nether.yml` — at minimum change `world` and tune the shape:
   ```yaml
   world: "world_nether"
   requirePermission: false
   shape:
     name: "CIRCLE"
     radius: 2048b
     centerRadius: 256b
     minY: 32
     maxY: 120
   ```
3. Update or create `plugins/RTP/definitions/worlds/world_nether.yml` so the world points at the new region (`region: "nether"`).
4. Run `/rtp reload` to apply the new region.

Players can now target it with `/rtp region=nether` (requires `rtp.region` + `rtp.regions.nether` permissions).

### Programmatic / In-Game Edits with `/rtp config`

Instead of editing files by hand, you can read or change any region key at runtime using the `/rtp config` command:

```
/rtp config <file> <key>=<value> [<key>=<value> …] [--dry-run]
/rtp config <file> <list-key> add=<value> [remove=<value>] [--dry-run]
/rtp config <multifile> <subfile> <key>=<value> [--dry-run]
/rtp config <file> view              # inspect current state interactively
/rtp config <file> view <key>
```

Permissions are additive (see [COMMANDS.md](COMMANDS.md) section `/rtp config`): `rtp.config.set` grants all writes; `rtp.config.set.<section>` (e.g. `rtp.config.set.regions`) grants only that section; `rtp.config.view` grants the read-only `view` form; the legacy `rtp.config` continues to grant view + set.

For example, to update the nether region's world after the file already exists:

```
/rtp config regions nether world=world_nether
/rtp config regions nether shape.radius=2048b --dry-run  # preview, no change
/rtp config regions nether shape.radius=2048b            # commit
/rtp config regions nether biomeWhitelist add=FOREST add=PLAINS remove=OCEAN
/rtp config regions nether view shape                    # show the current shape block
```

Each successful `/rtp config` write is **atomic** (write-to-temp → fsync → rename) and the affected parser is reloaded automatically — no `/rtp reload` is required after a `/rtp config` write. Use `/rtp reload` only when you have hand-edited YAML on disk. Every invocation — success or failure, live or dry-run — emits exactly one audit record (`INFO` on success, `WARNING` on failure) with a `reasonCode` you can match against `messages.yml` for translation.

> **Tip for automation:** Scripts, RCON clients, and addon plugins can issue `/rtp config` commands programmatically. Use `--dry-run` to preview the diff before committing; the audit record's `outcome` field (`COMMITTED` / `DRY_RUN_OK` / `REJECTED` / `ROLLED_BACK`) tells you what happened without parsing chat output. See [COMMANDS.md](COMMANDS.md) for the full syntax and error codes.

---

## Step 9 — Build Spatial Memory with `/rtp scan`

Once regions are set up (Steps 4 and 8), scan them so RTP records bad terrain ahead of time instead of while players teleport. Scanning runs off the main thread.

> **On a world that isn't pregenerated,** scan generates chunks as it goes. That is much slower and competes with the server's own chunk loading (noticeable on Fabric). If you can, pregenerate first (Step 0); otherwise run the scan at a quiet time and pause it if TPS drops.

```
/rtp scan start                       # caller's region (player) or all (console)
/rtp scan start  region=mining
/rtp scan pause  region=mining
/rtp scan resume region=mining
/rtp scan cancel region=mining        # stop without clearing memory
/rtp scan reset  region=mining        # forget everything; required after safety.yml changes
```

- Requires a region shape that supports spatial memory (the built-in shapes do).
- Console without a `region=` argument scans **all** permanent regions.
- Monitor progress live via PlaceholderAPI: `%rtp_scan_chunks%`, `%rtp_scan_totalChunks%`, `%rtp_scan_cps%`, `%rtp_scan_eta%`, `%rtp_scan_landPercentage%`.
- If a scan is too heavy on the server, lower `performance.yml > scanTaskCount`, then `/rtp scan resume`.

Full sub-command reference: [COMMANDS.md](COMMANDS.md) section `/rtp scan`. Operational playbook (after-safety-edit, chunk-leak, oversized memory file): [RUNBOOK.md](RUNBOOK.md).

---

## Step 10 — Verify with `/rtp info` and `/rtp test`

### Diagnostics (`/rtp info`)

Shows queue depth, latency and per-region state:

```
/rtp info                              # all loaded worlds and permanent regions
/rtp info world=world_nether
/rtp info region=default               # queue depth, in-flight calcs, shape, cacheCap
```

### Stress test

Optional; useful when checking either the pipeline without moving a real account or real-player platform behavior. Mock mode exercises the RTP pipeline; `player=<name>` selects real teleports with normal checks (cooldown, economy, claims):

```
/rtp test stress count=4 iterations=3
/rtp test stress player=Alice
/rtp test stress player=Alice iterations=50 intervalTicks=60
/rtp test stress player=Alice player=Bob region=mining
```

- Mock `count` is clamped to `[1, 16]` (default `4`); `iterations` is clamped to `[1, 1000]` (default `10`); `intervalTicks` to `[10, 6000]` (default `40`). Use `player=<name>` for the real-player variant.
- Per-iteration failures log at `WARNING` — watch the console.
- Additional diagnostics are listed in [COMMANDS.md](COMMANDS.md) section `/rtp test`.

Full reference: [COMMANDS.md](COMMANDS.md) section `/rtp test`.

Manual smoke checks:

```
/rtp                       # default region in current world
/rtp region=mining
/rtp world=world_nether
/rtp player=<name>         # other-player teleport (rtp.other)
```

---

## Operational Cadence (after setup)

After setup, the recurring loop is small:

- Every config edit → `/rtp reload` (or `/rtp reload <region>` for a single region).
- Every `safety.yml` edit → `/rtp scan reset <region>` then `/rtp scan start <region>` ([RUNBOOK.md](RUNBOOK.md)).
- Suspected chunk leak / heavy load → `/rtp scan cancel` everywhere, lower `scanTaskCount`, resume.
- Regular checks: `/rtp info`, `%rtp_*%` placeholders, server `mspt`, console for any `WARNING` from RTP.

---

## Common First-Run Issues

| Symptom | Likely cause | Fix |
|---|---|---|
| `/rtp` does nothing | Plugin failed to load | Check console for errors on startup |
| "No safe location found" | Radius too small or all biomes excluded | Increase `radius` in the region file, or relax the biome list in `advanced/biomes.yml` |
| Economy not working | Vault not installed | Install Vault + an economy plugin and restart |
| Teleport is slow | Cache empty on first run | Wait a few moments for the background queue to fill, or check `cacheCap` in the region config |
| Players land in ocean | Biome filter not active | Check `biomes` in `advanced/biomes.yml` lists the ocean biomes (with `biomeWhitelist: false`); then `/rtp scan reset` + `/rtp scan start` |
| Players land too close to spawn | `centerRadius` too small | Increase `centerRadius` in the region's `shape:` block |
| `/rtp scan` very slow | World not pregenerated | Expected; let it run at a quiet time, or pregenerate (Step 0) and `/rtp scan resume` |

---

## Next Steps

- [Intended usage](../site/intended-usage.md) — how RTP works under the hood (queue, shapes, pipeline)
- [COMMANDS.md](COMMANDS.md) — full command and permission reference
- [WORLDS.md](configuration/WORLDS.md) — every `worlds/<world>.yml` key
- [REGIONS.md](configuration/REGIONS.md) — every `regions/<name>.yml` key
- [CONFIGURATION.md](configuration/CONFIGURATION.md) — every config key explained
- [SAFETY.md](configuration/SAFETY.md) — biome / block / hazard filters
- [ECONOMY.md](configuration/ECONOMY.md) — pricing and refunds
- [RUNBOOK.md](RUNBOOK.md) — incident response and operational procedures
- [FAQ.md](FAQ.md) — common questions and gotchas
- [ADDONS.md](ADDONS.md) — how RTP loads addons, the bundled GUI demo, and how to turn addons off
- [CONTRIBUTING.md](https://github.com/dailystruggle/RTP/blob/V3/CONTRIBUTING.md) — how to build and extend the plugin
- [example addons](https://github.com/dailystruggle/RTP/tree/V3/addons) — example addons for claim plugin integration, Iris, and more
