# Quick Start Guide

**Applies to Plugin Version:** `3.0.0-beta.1`

Get RTP running on your server in under 5 minutes.

---

## Step 1 — Install the Plugin

1. Download the latest `RTP-<version>.jar` from the [SpigotMC resource page](https://www.spigotmc.org/resources/rtp.94812/).
2. Drop the jar into your server's `plugins/` folder.
3. Restart the server (rather than using `/reload`), as a full restart ensures all hooks register correctly.

After the first start, RTP creates its config folder at `plugins/RTP/`.

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
plugins/RTP/regions/default.yml
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

All shape settings live directly inside the region file. Open `plugins/RTP/regions/default.yml`
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

Save the file and run `/rtp reload` to apply changes without restarting.

---

## Step 5 — Set Up Permissions

RTP uses a permission-based system. Assign these to your permission plugin (e.g., LuckPerms):

| Permission | What it grants |
|---|---|
| `rtp.use` | Use `/rtp` to teleport yourself |
| `rtp.free` | Bypass economy cost |
| `rtp.noCooldown` | Bypass cooldown |
| `rtp.other` | Teleport another player (`/rtp player:<player>`) |
| `rtp.reload` | Use `/rtp reload` |
| `rtp.scan` | Use `/rtp scan` to pre-generate locations |

Example LuckPerms command to grant basic use to all players:

```
/lp group default permission set rtp.use true
```

---

## Step 6 — (Optional) Enable Economy

If you have **Vault** and an economy plugin installed, RTP will automatically detect them.

Edit `plugins/RTP/economy.yml`:

```yaml
price: 50.0          # cost per /rtp
priceOther: 200.0    # cost to teleport another player
refundOnCancel: true # refund if teleport is cancelled
```

Set `price: 0.0` to disable the cost entirely.

---

## Step 7 — (Optional) Add More Regions

To create a second region (e.g., for a nether world):

1. Copy `plugins/RTP/regions/default.yml` to `plugins/RTP/regions/nether.yml`.
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
3. Run `/rtp reload` to apply the new region.

Players can now target it with `/rtp region:nether` (requires `rtp.region` + `rtp.regions.nether` permissions).

### Programmatic / In-Game Edits with `/rtp config`

Instead of editing files by hand, you can read or change any region key at runtime using the `/rtp config` command (requires `rtp.config`):

```
/rtp config <file> <key>:<value>
/rtp config <multifile> <subfile> <key>:<value>
```

For example, to update the nether region's world after the file already exists:

```
/rtp config regions nether world:world_nether
/rtp config regions nether shape.radius:128
/rtp config regions nether shape.centerRadius:16
```

Each `/rtp config` write is saved to disk immediately. Follow it with `/rtp reload` (or `/rtp reload nether`) to rebuild the region queue using the new values.

> **Tip for automation:** Scripts, RCON clients, and addon plugins can issue `/rtp config` commands programmatically to adjust region settings on the fly, with no manual file editing or server restart required. See [COMMANDS.md](COMMANDS.md) for the full syntax.

---

## Common First-Run Issues

| Symptom | Likely cause | Fix |
|---|---|---|
| `/rtp` does nothing | Plugin failed to load | Check console for errors on startup |
| "No safe location found" | Radius too small or all biomes excluded | Increase `radius` in the region file, or relax biome filters in `safety.yml` |
| Economy not working | Vault not installed | Install Vault + an economy plugin and restart |
| Teleport is slow | Cache empty on first run | Wait 30–60 seconds for the background queue to fill, or run `/rtp scan` |
| Players land in ocean | Biome filter not active | Check `safety.yml` biome blacklist includes ocean biomes |
| Players land too close to spawn | `centerRadius` too small | Increase `centerRadius` in the region's `shape:` block |

---

## Next Steps

- [CONCEPTS.md](../dev/CONCEPTS.md) — how RTP works under the hood (queue, shapes, pipeline)
- [COMMANDS.md](COMMANDS.md) — full command and permission reference
- [CONFIGURATION.md](CONFIGURATION.md) — every config key explained
- [FAQ.md](FAQ.md) — common questions and gotchas
- [CONTRIBUTING.md](../../CONTRIBUTING.md) — how to build and extend the plugin
- [../addons/](../addons/) — example addons for claim plugin integration, Iris, and more
