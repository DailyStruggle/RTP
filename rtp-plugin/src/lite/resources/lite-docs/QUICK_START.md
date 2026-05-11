# Quick Start (RTP-lite)

> Stripped from `docs/admin/QUICK_START.md` to match the lite scope (ADR-024).
> No economy, no claim plugins, no language packs, no SQL — just region setup,
> safety, and verification.

## 0. Prerequisites

1. **Java 21+** (REQ-RTP-SYS-001).
2. **Pick a platform:** Spigot, Paper, or Fabric. Folia operators must use
   **RTP-Pro** instead.
3. **Pregenerate** every world you'll add to RTP, sized to match the region
   radius you'll configure. RTP loads chunks asynchronously on demand
   (S-005 — never sync chunk I/O on the main thread); a cold world makes the
   first teleports slow and the first `/rtp scan` chunkgen-bound.
   - Chunky (Paper/Spigot): `/chunky world <world>`, `/chunky radius <blocks>`,
     `/chunky start`.

## 1. Install

1. Drop `RTP-<version>.jar` into `plugins/`.
2. Restart the server (not `/reload`).
3. After first start the data folder is `plugins/RTP/`, containing
   `regions/default.yml`, `worlds/<world>.yml` per loaded world, plus
   `safety.yml`, `performance.yml`, `messages.yml`, `config.yml`, and this
   `lite-docs/` folder.

## 2. Verify

```
/rtp
```

If you land somewhere random, the plugin works. If you see a permission error,
make sure your account has `rtp.use` (operators have all permissions).

## 3. Default region

`plugins/RTP/regions/default.yml`:

- Targets your main world.
- **Circle** shape, radius 256 chunks (~4 096 blocks), centerRadius 64 chunks.
- Excludes ocean / nether / end biomes (see `safety.yml`).
- 5 s of invulnerability on landing.
- Pregen queue fills in the background.
- Spatial memory learns which coordinates are unsafe.

## 4. Adjust the teleport area

Open `regions/default.yml`, look for the `shape:` block:

```yaml
shape:
  name: "CIRCLE"       # CIRCLE, SQUARE, RECTANGLE, ...
  mode: "ACCUMULATE"
  radius: 256          # in CHUNKS (256 chunks = ~4096 blocks)
  centerRadius: 64
  centerX: 0
  centerZ: 0
  weight: 1.0
```

Save, then `/rtp reload`.

## 5. Worlds

`plugins/RTP/worlds/<world>.yml`:

```yaml
region: "default"            # which region this world's /rtp uses
requirePermission: false     # true = require rtp.worlds.<world>
override: "[0]"              # fallback world if permission missing
```

Replace `[0]/[1]/[2]` placeholders with case-sensitive world names.

## 6. Permissions

| Permission | Grants |
|---|---|
| `rtp.use` | Use `/rtp` |
| `rtp.see` | See RTP messages and `/rtp help` |
| `rtp.noCooldown` | Bypass cooldown |
| `rtp.other` | Teleport another player |
| `rtp.reload` | `/rtp reload` |
| `rtp.config` | `/rtp config` |
| `rtp.info` | `/rtp info` |
| `rtp.scan` | `/rtp scan` |
| `rtp.test` | `/rtp test` |

`rtp.free` exists in lite but is a no-op — economy is **not** shipped in lite.

## 7. Pre-warm with `/rtp scan`

After pregeneration finishes, map the region so future teleports skip
known-bad coordinates:

```
/rtp scan start  region:default
/rtp scan pause  region:default
/rtp scan resume region:default
/rtp scan cancel region:default
/rtp scan reset  region:default     # required after safety.yml edits
```

## 8. Verify

```
/rtp info
/rtp info region:default
/rtp test stress player:Alice iterations:50 intervalTicks:60
```

Per-iteration failures log at `WARNING` (REQ-RTP-S-004) — watch the console.

## Common first-run issues

| Symptom | Likely cause | Fix |
|---|---|---|
| `/rtp` does nothing | Plugin failed to load | Check console on startup |
| "No safe location found" | Radius too small / biomes excluded | Increase `radius`, relax `safety.yml` |
| Teleport is slow | Cache empty on first run | Wait 30–60s for queue, or run `/rtp scan` |
| Players land in ocean | Biome filter not active | Edit `safety.yml`; then `/rtp scan reset` + `/rtp scan start` |
| `/rtp scan` very slow | World not pregenerated | Pregenerate first, then resume |

## Operational cadence

- Every config edit → `/rtp reload`.
- Every `safety.yml` edit → `/rtp scan reset <region>` then `/rtp scan start <region>`.
- Suspected chunk leak → `/rtp scan cancel`, lower `scanTaskCount`, resume.
- Regular: `/rtp info`, server `mspt`, console `WARNING`s from RTP.
