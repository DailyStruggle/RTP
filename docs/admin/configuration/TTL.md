# Spatial Memory TTL Reference (`advanced/ttl.yml`)

`advanced/ttl.yml` configures time-to-live (TTL) retention durations and expiration tiers for invalid coordinate segments stored in spatial memory (`MemoryShape`).

---

## Overview

When RTP evaluates candidate coordinates (during pre-scanning or live `/rtp` lookups), invalid locations are recorded in spatial memory to prevent redundant re-evaluations.

Historically, all bad-location entries were permanent: once marked bad, an area remained blacklisted until an operator ran `/rtp scan reset` or reconfigured region shapes. While this permanence is ideal for static natural terrain (such as oceans or lava lakes), it creates problems for dynamic world state:
- **Player claims:** If a player claims land, RTP records the area as an invalid destination under `safetyExternal`. If the player later abandons or unclaims the plot, the land would remain locked out permanently.
- **Unique placement buffers:** Landing spots reserved under `uniquePlacements` would stay blacklisted indefinitely.

With cause-based TTL (ADR-079), spatial memory segments are assigned retention lifecycles based on their rejection cause. When dynamic entries reach their expiration threshold, they enter a staged probation window and naturally cycle back into circulation without requiring manual cache resets.

---

## Updating Settings

You can update TTL configuration through:
1. **Direct editing**: Edit `advanced/ttl.yml` on disk and run `/rtp reload`.
2. **Command line**: Use `/rtp reload` after updating `advanced/ttl.yml` to apply new retention durations.

---

## Duration Format Syntax

Durations in `advanced/ttl.yml` accept standard human-readable time strings parsed via RTP's duration framework:
- `t` / `tick` / `ticks` = Minecraft game ticks (1 tick = 50ms)
- `ms` / `milli` / `millis` / `millisecond` / `milliseconds` = milliseconds
- `s` / `sec` / `second` / `seconds` = seconds (e.g. `3600s`)
- `m` / `min` / `minute` / `minutes` = minutes (e.g. `30m`, `60m`)
- `h` / `hr` / `hour` / `hours` = hours (e.g. `12h`, `24h`)
- `d` / `day` / `days` = days (e.g. `14d`, `30d`)
- `w` / `week` / `weeks` = weeks (e.g. `2w`, `4w`)
- `-1` or `infinite` / `permanent` = never expires (static tier)

Durations can be combined without punctuation (e.g. `1d12h`, `2h30m`, `1w2d`). If a plain integer is provided without a unit, it is interpreted as seconds.

---

## Configuration Keys

```yaml
# --- RTP Spatial Memory TTL Settings ---
# Documentation: plugins/RTP/docs/admin/configuration/TTL.md

causes:
  # Natural terrain & geometry (permanent retention)
  biome: -1
  worldBorder: -1
  vert: -1
  safety: -1
  prefilterBiome: -1
  prefilterBlock: -1

  # Dynamic player and server state
  uniquePlacement: 30d
  safetyExternal: 14d

verifiers:
  # Optional overrides by verifier class name
  # WorldGuardChecker: -1
  # GriefPreventionChecker: 14d

# DO NOT TOUCH VERSION NUMBER
version: 1.0
```

### 1. `causes` (Base Retention Durations)

Base causes map directly to RTP's internal failure categories:

| Cause Key | Default | Tier | Description |
|---|---|---|---|
| `biome` | `-1` (infinite) | Static | Candidate rejected by biome whitelist/blacklist. |
| `worldBorder` | `-1` (infinite) | Static | Candidate falls outside vanilla world border. |
| `vert` | `-1` (infinite) | Static | No suitable vertical landing surface found. |
| `safety` | `-1` (infinite) | Static | Unsafe landing block (lava, void, hazard blocks). |
| `prefilterBiome` | `-1` (infinite) | Static | Filtered out during Anvil/Linear NBT biome pre-scan. |
| `prefilterBlock` | `-1` (infinite) | Static | Filtered out during Anvil/Linear NBT block pre-scan. |
| `uniquePlacement` | `30d` | Dynamic | Temporary exclusion zone created around a recent player teleport. |
| `safetyExternal` | `14d` | Dynamic | Base fallback duration for external claim/protection plugins. |

### 2. `verifiers` (Per-Checker Overrides)

The `verifiers` section allows overriding retention durations for specific claim or protection checkers by their Java class name:
- **Automatic Fallback:** Any verifier not explicitly listed under `verifiers` automatically inherits `causes.safetyExternal`.
- **Targeting Checkers:** Keys match the simple class name (or fully qualified class name) of the registered checker.
- **Permanent Admin Areas:** To make server administrative claims (e.g. WorldGuard warps, spawns, minigame arenas) permanent while allowing player claims to expire, assign `-1` to the admin checker.

#### Example:
```yaml
verifiers:
  WorldGuardChecker: -1       # Treat server WorldGuard regions as permanently invalid
  GriefPreventionChecker: 7d  # Expire player claims after 7 days
  TownyAdvancedChecker: 14d   # Towny town boundaries checked every 14 days
```

---

## How Expiration Works

### Selective Coalescing
RTP compresses spatial memory using Run-Length Encoded (RLE) intervals on an Archimedean spiral.
To prevent data corruption:
- **Static runs ($\text{TTL} = \infty$) never merge with dynamic runs.** A permanent ocean boundary will never accidentally inherit a temporary claim's expiration date.
- **Dynamic runs merge cleanly:** When two dynamic runs touch, the combined interval inherits the maximum TTL of both runs ($\max(\text{TTL}_A, \text{TTL}_B)$), minimizing memory fragmentation while preventing premature expiration.

### Staged Probation Buffer
When a dynamic entry reaches its expiration date ($t \ge \text{TTL}$):
1. **Phase 1 (Active -> Probation):** The segment is removed from the active candidate-avoidance index and moved to a parallel sorted probation array. The coordinate becomes candidate-eligible again for players.
2. **Phase 2 (Candidate Selection):**
   - **If still claimed:** If RTP selects a candidate in that area and the claim plugin rejects it again, the entire probationary interval is immediately restored with a refreshed TTL in $O(\log M)$ time with zero heap allocation.
   - **If unclaimed / safe:** If the candidate passes verification, the location is used for teleportation and the probation record is naturally dropped on the next off-tick rebuild.
3. **Phase 3 (Eviction):** Segments that pass twice their TTL ($t \ge 2 \times \text{TTL}$) without re-occurrence are permanently purged from memory.

### Disk Persistence (`BIN_VERSION 3`)
Spatial memory cache files (`.bin` under region storage) store non-overlapping 1D spiral intervals alongside their 64-bit expiration timestamps.
- **Offline Decay:** If the server is offline or shut down for weeks, expired claims are evaluated against real-world wall-clock time on server startup and cleanly routed to probation or pruned immediately.
- **Backward Compatibility:** Legacy cache files (`BIN_VERSION 1` or `2`) are loaded seamlessly, treating all entries as static ($\text{TTL} = \infty$).
