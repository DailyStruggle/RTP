# Quick Start Guide

**Applies to Plugin Version:** `3.0.0-beta.1`

Get RTP running on your server in under 5 minutes, then follow the rest of the sequence to take it to production. The numbered steps below are the **recommended end-to-end setup sequence**: prerequisites → install → worlds → regions → permissions → economy → scan → verify. Each step summarises what to do and links to the canonical doc for full detail.

> **TL;DR sequence:** pregenerate → install → configure worlds → configure regions → set permissions → (optional) economy → `/rtp scan` to pre-warm → `/rtp info` + `/rtp test` to verify.

---

## Step 0 — Prerequisites & Pregenerate the World

Before installing RTP, get the environment ready. RTP does not ship its own pregenerator; it loads chunks asynchronously on demand (S-005 — never sync chunk I/O on the main thread), so a cold world makes the first teleports slow and the first `/rtp scan` (Step 9) chunkgen-bound.

1. **Java 21+** is required (REQ-RTP-SYS-001).
2. **Pick your platform:** Spigot, Paper, Folia, or Fabric. Fabric is unstable — see [`MULTI_PLATFORM_PLAN.md`](../dev/MULTI_PLATFORM_PLAN.md).
3. **Install Vault + an economy plugin first** if you plan to charge for `/rtp` (Step 7).
4. **Install your claim plugin first** (GriefDefender, GriefPrevention, Lands, WorldGuard, Towny, …) if you want claim-aware teleport (S-003). RTP autodetects them at load.
5. **Pregenerate** every world you'll add to RTP, sized to match the region you'll configure in Step 4. A region with `shape.radius: 256` (chunks) needs ~4 096 blocks pregenerated from `centerX, centerZ`, plus a small margin.
   - **Chunky** (Paper/Spigot/Folia): `/chunky world <world>`, `/chunky radius <blocks>`, `/chunky start`.
   - **WorldBorder** (legacy): `/wb <world> set <radius>`, `/wb fill`.

> Pregenerating now means Step 9's `/rtp scan` only validates safety, instead of also generating chunks. See [`docs/architecture/05-scan-task-crawler.md`](../architecture/05-scan-task-crawler.md) for why scan throughput is bound by chunk-load latency.

---

## Step 1 — Install the Plugin

1. Download the latest `RTP-<version>.jar` from the [SpigotMC resource page](https://www.spigotmc.org/resources/rtp.94812/).
2. Drop the jar into your server's `plugins/` folder.
3. Restart the server (rather than using `/reload`), as a full restart ensures all hooks register correctly.

After the first start, RTP creates its config folder at `plugins/RTP/` containing `regions/default.yml`, one `worlds/<world>.yml` per loaded world, plus `safety.yml`, `economy.yml`, `performance.yml`, `messages.yml`, and more.

---

## Step 2 — Verify It Loaded

Run this in your server console or as an operator:

```
/rtp
```

If you land somewhere random, the plugin is working. If you see a permission error, make sure your account has `rtp.use`, noting that operators have all permissions by default.

---

## Step 3 — Understand the Default Region

RTP ships with one region called `default`, configured in:

```
plugins/RTP/definitions/regions/default.yml
```

Out of the box it:
- Targets your main world (`[0]`, which Bukkit resolves to `world`).
- Uses a **circle** shape with a radius of **256 chunks** (~4 096 blocks).
- Enforces a **64-chunk inner exclusion zone** (or "donut hole") so players don't land at spawn.
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
  mode: "ACCUMULATE"   # selection algorithm (see CONFIGURATION.md for options)
  radius: 256          # maximum distance from centre in CHUNKS (256 chunks = ~4 096 blocks)
  centerRadius: 64     # minimum distance — players won't land closer than this
  centerX: 0
  centerZ: 0
  weight: 1.0          # >1.0 = centre-weighted; <1.0 = edge-weighted; 1.0 = flat
```

> **Tip:** `radius` is measured in **chunks**, not blocks. Multiply by 16 for the block distance.
> A radius of 256 chunks = 4 096 blocks from centre.

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

Full reference: [WORLDS.md](configuration/WORLDS.md).

---

## Step 6 — Set Up Permissions

RTP uses a permission-based system. Assign these to your permission plugin (e.g., LuckPerms):

| Permission | What it grants |
|---|---|
| `rtp.use` | Use `/rtp` to teleport yourself |
| `rtp.see` | See RTP messages and `/rtp help` |
| `rtp.free` | Bypass economy cost |
| `rtp.noCooldown` | Bypass cooldown |
| `rtp.other` | Teleport another player (`/rtp player:<player>`) |
| `rtp.reload` | Use `/rtp reload` |
| `rtp.config.view` | Use `/rtp config <file> view` (read-only inspection) |
| `rtp.config.set` | Use `/rtp config <file> …` to write any config file (umbrella) |
| `rtp.config.set.<section>` | Write only the named section (e.g. `rtp.config.set.regions`); see [COMMANDS.md](COMMANDS.md) §`/rtp config` |
| `rtp.config` | Legacy alias — grants `rtp.config.view` + `rtp.config.set` |
| `rtp.info` | Use `/rtp info` |
| `rtp.scan` | Use `/rtp scan` to pre-generate locations |
| `rtp.test` | Use `/rtp test` runtime self-tests |

Example LuckPerms command to grant basic use to all players:

```
/lp group default permission set rtp.use true
/lp group default permission set rtp.see true
```

Full permission reference (including world/region permissions like `rtp.worlds.<world>` and `rtp.regions.<name>`): [COMMANDS.md](COMMANDS.md) §"Full Permission Reference".

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
     radius: 128
     centerRadius: 16
     minY: 32
     maxY: 120
   ```
3. Update or create `plugins/RTP/worlds/world_nether.yml` so the world points at the new region (`region: "nether"`).
4. Run `/rtp reload` to apply the new region.

Players can now target it with `/rtp region:nether` (requires `rtp.region` + `rtp.regions.nether` permissions).

### Programmatic / In-Game Edits with `/rtp config`

> ⚠️ **Hardening in `3.0.0-beta.3`.** The `/rtp config` surface is being hardened against a normative spec ([`docs/dev/CONFIG_COMMAND_SPEC.md`](../dev/CONFIG_COMMAND_SPEC.md), decided in [ADR-037](../adr/ADR-037-harden-rtp-config-commands.md), implemented per [ADR-041](../adr/ADR-041-config-command-and-save-implementation.md)). Behavior described here is the **target**; earlier builds may still silently ignore unknown keys or skip validation. For production use on pre-beta.3 builds, prefer hand-editing the YAML files plus `/rtp reload`.

Instead of editing files by hand, you can read or change any region key at runtime using the `/rtp config` command:

```
/rtp config <file> <key>:<value> [<key>:<value> …] [--dry-run]
/rtp config <file> <list-key> add:<value> [remove:<value>] [--dry-run]
/rtp config <multifile> <subfile> <key>:<value> [--dry-run]
/rtp config <file> view              # inspect current state interactively
/rtp config <file> view <key>
```

Permissions are additive (see [COMMANDS.md](COMMANDS.md) §`/rtp config`): `rtp.config.set` grants all writes; `rtp.config.set.<section>` (e.g. `rtp.config.set.regions`) grants only that section; `rtp.config.view` grants the read-only `view` form; the legacy `rtp.config` continues to grant view + set.

For example, to update the nether region's world after the file already exists:

```
/rtp config regions nether world:world_nether
/rtp config regions nether shape.radius:128 --dry-run    # preview, no change
/rtp config regions nether shape.radius:128              # commit
/rtp config regions nether biomeWhitelist add:FOREST add:PLAINS remove:OCEAN
/rtp config regions nether view shape                    # show the current shape block
```

Each successful `/rtp config` write is **atomic** (write-to-temp → fsync → rename) and the affected parser is reloaded automatically — no `/rtp reload` is required after a `/rtp config` write. Use `/rtp reload` only when you have hand-edited YAML on disk. Every invocation — success or failure, live or dry-run — emits exactly one audit record (`INFO` on success, `WARNING` on failure) with a `reasonCode` you can match against [`messages.yml`](../dev/CONFIG_COMMAND_SPEC.md#5-validation-model-and-reasoncode-catalog) for translation.

> **Tip for automation:** Scripts, RCON clients, and addon plugins can issue `/rtp config` commands programmatically. Use `--dry-run` to preview the diff before committing; the audit record's `outcome` field (`COMMITTED` / `DRY_RUN_OK` / `REJECTED` / `ROLLED_BACK`) tells you what happened without parsing chat output. See [COMMANDS.md](COMMANDS.md) for the full syntax and the [spec](../dev/CONFIG_COMMAND_SPEC.md) for the error matrix.

---

## Step 9 — Pre-warm with `/rtp scan` (Spatial Memory)

> ⚠️ **Run scan only after pregeneration is complete.** `/rtp scan` (and the background scan that starts automatically when a region first loads) probes every spiral coordinate in the region; if the underlying chunks have not been pregenerated, each probe pays full chunkgen cost and competes with the server's chunk system for tick-thread I/O. On Fabric this can drag steady-state TPS noticeably; on any platform it makes scan throughput abysmal. **Always finish your Chunky / WorldBorder pregeneration pass first** (Step 0), then start the scan.

Once worlds are pregenerated (Step 0) and regions are configured (Steps 4 and 8), map them so future teleports skip known-bad coordinates instantly. Scanning is async on every platform and never blocks tick threads.

```
/rtp scan start                       # caller's region (player) or all (console)
/rtp scan start  region:mining
/rtp scan pause  region:mining
/rtp scan resume region:mining
/rtp scan cancel region:mining        # stop without clearing memory
/rtp scan reset  region:mining        # forget everything; required after safety.yml changes
```

- Requires the region's shape to be a `MemoryShape` (the built-in shapes are).
- Console without a `region:` argument scans **all** permanent regions.
- Monitor progress live via PlaceholderAPI: `%rtp_scan_chunks%`, `%rtp_scan_totalChunks%`, `%rtp_scan_cps%`, `%rtp_scan_eta%`, `%rtp_scan_landPercentage%`.
- If a scan is too heavy on the server, lower `performance.yml > scanTaskCount`, then `/rtp scan resume`.

Full sub-command reference: [COMMANDS.md](COMMANDS.md) §`/rtp scan`. Architecture: [`docs/architecture/05-scan-task-crawler.md`](../architecture/05-scan-task-crawler.md). Operational playbook (after-safety-edit, chunk-leak, oversized memory file): [RUNBOOK.md](RUNBOOK.md).

---

## Step 10 — Verify with `/rtp info` and `/rtp test`

Inspect runtime state:

```
/rtp info                              # all loaded worlds and permanent regions
/rtp info world:world_nether
/rtp info region:default               # queue depth, in-flight calcs, shape, cacheCap
```

Exercise the full pipeline against a real player (every safety guard remains active — cooldown, economy, claim verifiers, async chunk I/O):

```
/rtp test stress player:Alice
/rtp test stress player:Alice iterations:50 intervalTicks:60
/rtp test stress player:Alice player:Bob region:mining
```

- `iterations` is clamped to `[1, 1000]` (default `10`); `intervalTicks` to `[10, 6000]` (default `40`).
- Per-iteration failures log at `WARNING` (REQ-RTP-S-004) — watch the console.
- `stress` is the only sub-command available today; `queue`, `safety`, `verifiers`, `memory`, `platform`, `full` are planned — see [`RUNTIME_TEST_SUITE_PLAN.md`](../dev/RUNTIME_TEST_SUITE_PLAN.md).

Full reference: [COMMANDS.md](COMMANDS.md) §`/rtp test`.

Manual smoke checks:

```
/rtp                       # default region in current world
/rtp region:mining
/rtp world:world_nether
/rtp player:<name>         # other-player teleport (rtp.other)
```

---

## Operational Cadence (after setup)

Once Steps 0–10 are green, the recurring loop is small:

- Every config edit → `/rtp reload` (or `/rtp reload <region>` for a single region).
- Every `safety.yml` edit → `/rtp scan reset <region>` then `/rtp scan start <region>` ([RUNBOOK.md](RUNBOOK.md)).
- Suspected chunk leak / heavy load → `/rtp scan cancel` everywhere, lower `scanTaskCount`, resume.
- Regular checks: `/rtp info`, `%rtp_*%` placeholders, server `mspt`, console for any `WARNING` from RTP.

---

## Common First-Run Issues

| Symptom | Likely cause | Fix |
|---|---|---|
| `/rtp` does nothing | Plugin failed to load | Check console for errors on startup |
| "No safe location found" | Radius too small or all biomes excluded | Increase `radius` in the region file, or relax biome filters in `safety.yml` |
| Economy not working | Vault not installed | Install Vault + an economy plugin and restart |
| Teleport is slow | Cache empty on first run | Wait 30–60 seconds for the background queue to fill, or run `/rtp scan` (Step 9) |
| Players land in ocean | Biome filter not active | Check `safety.yml` biome blacklist includes ocean biomes; then `/rtp scan reset` + `/rtp scan start` |
| Players land too close to spawn | `centerRadius` too small | Increase `centerRadius` in the region's `shape:` block |
| `/rtp scan` very slow | World not pregenerated | Pregenerate first (Step 0), then `/rtp scan resume` |

---

## Next Steps

- [CONCEPTS.md](../dev/CONCEPTS.md) — how RTP works under the hood (queue, shapes, pipeline)
- [COMMANDS.md](COMMANDS.md) — full command and permission reference
- [WORLDS.md](configuration/WORLDS.md) — every `worlds/<world>.yml` key
- [REGIONS.md](configuration/REGIONS.md) — every `regions/<name>.yml` key
- [CONFIGURATION.md](configuration/CONFIGURATION.md) — every config key explained
- [SAFETY.md](configuration/SAFETY.md) — biome / block / hazard filters
- [ECONOMY.md](configuration/ECONOMY.md) — pricing and refunds
- [RUNBOOK.md](RUNBOOK.md) — incident response and operational procedures
- [FAQ.md](FAQ.md) — common questions and gotchas
- [ADDONS.md](ADDONS.md) — how RTP loads addons, the bundled GUI demo, and how to turn addons off
- [CONTRIBUTING.md](../../CONTRIBUTING.md) — how to build and extend the plugin
- [../addons/](../addons/) — example addons for claim plugin integration, Iris, and more
