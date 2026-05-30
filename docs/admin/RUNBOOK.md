# Operator Runbook

**Applies to Plugin Version:** `3.0.0-beta.1`

This document provides step-by-step diagnosis and resolution procedures for common operational
problems. Each section follows the pattern: **Symptom → Diagnosis → Resolution**.

For the full list of failure modes and their defined system responses see
[`FAILURE_MODES.md`](FAILURE_MODES.md).
For the hazard register see [`HAZARDS.md`](HAZARDS.md).

---

## Server TPS Drops After RTP Activity

**Symptom:** Server tick-rate (TPS) noticeably degrades shortly after players use `/rtp`, or
during scheduled scan operations.

**Diagnosis:**
1. Check the active chunk count with a monitoring tool (e.g. `/paper chunklist` or a TPS plugin).
   A high number of force-loaded chunks suggests a chunk leak (see H-004 in `HAZARDS.md`).
2. Check the server console for SEVERE-level messages from `MemoryTracker` in the form
   `[RTP] Memory leak detected for object: <label>. Alive <ms>ms past its expected lifespan.`,
   as these indicate pipeline tasks that were force-cancelled by the watchdog.
3. Check `performance.yml`: if `queueSize` or `scanTaskCount` are set very high relative to
   server hardware, the scan task may be loading too many chunks per cycle.

**Resolution:**
- If chunk leak is confirmed: run `/rtp scan cancel` for all active regions to stop new
  reservations, then restart the server to clear any residual force-loaded chunks. After restart,
  lower `scanTaskCount` in `performance.yml` and run `/rtp scan resume` again.
- If spatial memory isn't populating: reduce `scanTaskCount` and/or increase `scanTaskDelay` in
  `performance.yml`, then run `/rtp reload`.
- If the problem recurs after adjustment, file an issue with the SEVERE log lines from
  `MemoryTracker` attached.

---

## Players Report Landing in Dangerous Locations

**Symptom:** Players report teleporting into lava, inside a solid block, underwater with no
air, or into a claimed region they cannot build in.

**Diagnosis:**
1. Confirm which region the player was teleported from (`/rtp info <player>` or server log).
2. Check `safety.yml` for that region: verify that the relevant unsafe block types are listed
   under `unsafeBlocks` and that the safety check is enabled (`safetyCheck: true`).
3. If the issue is claimed-land: confirm the relevant protection addon
   (GriefPrevention, WorldGuard, etc.) is installed, loaded **after** RTP in load order, and
   that the corresponding RTP addon jar (`RTP_ClaimPluginIntegrations` or equivalent) is present
   in the plugins folder.
4. Check if the region has been recently reconfigured or if `safety.yml` was edited manually,
   as a syntax error can silently disable safety checks.

**Resolution:**
- Add the offending block type to `unsafeBlocks` in the region's `safety.yml`.
- If the protection addon check is not firing: ensure the addon jar is present and that RTP
  declares it as a `softdepend` (or the addon declares RTP as a `depend`) so load order is
  correct.
- After any `safety.yml` change, run `/rtp scan reset <region>` to discard spatial memory
  that was validated under the old rules, then `/rtp scan start <region>` to rebuild the map
  with the corrected safety checks. Note that this affects spatial memory (the map), not the
  pre-generation queue directly.

---

## Plugin Fails to Enable on Startup

**Symptom:** The server console shows `[RTP] Disabling plugin` or a stack trace during startup.
`/rtp` is not available.

**Diagnosis:**
1. Find the first exception in the console output after `[RTP] Enabling`. The most common
   causes are:
   - `ClassNotFoundException` or `NoSuchMethodError`, usually the wrong adapter jar for this server
     version (see FM-008 in `FAILURE_MODES.md`).
   - `IllegalStateException: [RTP API] Cannot access hooks: Core implementation is not loaded`, which
     happens when an addon touches an `rtp-api` contract entry point before RTP core finishes loading
     (see FM-009).
   - YAML parse error, meaning a config file (`config.yml`, `performance.yml`, or a region file) has a
     syntax error.

**Resolution:**

*Wrong adapter version:*

| Server software | Supported version | Required jar suffix |
|-----------------|------------------|---------------------|
| Spigot | 1.20.x | `rtp-bukkit-v1_20_R1` |
| Spigot | 1.21.x | `rtp-bukkit-v1_21_R1` |
| Paper / Spigot | 26.1 | `rtp-bukkit-v26_1_R1` |
| Paper | 1.20.x | `rtp-paper-v1_20_R1` |
| Paper | 1.21.x | `rtp-paper-v1_21_R1` |
| Paper | 26.1 | `rtp-paper-v26_1_R1` |
| Folia | 1.20.x | `rtp-folia-v1_20_R1` |
| Folia | 1.21.x | `rtp-folia-v1_21_R1` |
| Folia | 26.1 | `rtp-folia-v26_1_R1` |

Replace the installed jar with the correct version and restart.

*Addon load order:* Move the offending addon to load after RTP by adding `depend: [RTP]` to
the addon's `plugin.yml`, or ensure RTP is listed before the addon alphabetically if relying
on default load order.

*YAML syntax error:* Run the config file through a YAML validator (e.g. <https://yaml-online-parser.appspot.com/>),
fix the reported line, and restart.

---

## Spatial Memory / Mapping Issues

**Symptom:** `/rtp info <region>` shows the scan task is making slow progress or "sector skipped" messages appear in console.

**Diagnosis:**
1. Check the server console for WARN or ERROR messages from the scan task. A high rate of
   "sector skipped" messages indicates the region geometry has very few valid land areas. This is
   expected behavior for spatial memory—it's learning that those sectors are bad.
2. Check if `MemoryTracker` is logging repeated SEVERE messages of the form
   `[RTP] Memory leak detected for object: <label>. Alive <ms>ms past its expected lifespan.`,
   where repeated entries for the same task label point to a chunk that consistently times out
   during loading (FM-005).
3. Check `performance.yml` for `scanTaskCount` and `scanTaskDelay`. If `scanTaskDelay` is
   very large, the mapping process may be making progress but slowly.
4. Run `/rtp info <region>` and verify that `minRadius` / `maxRadius` define a reachable land
   area in the target world.

**Resolution:**
- If most sectors are bad (e.g. ocean-heavy world): widen the region geometry or run
  `/rtp scan reset <region>` and reconfigure before re-mapping.
- If chunk load timeouts are the cause: reduce `scanTaskCount` to lower concurrency, allowing
  the server more time per chunk. Increase `scanTaskDelay` slightly to give the server
  recovery periods between scan cycles.
- If the mapping process has genuinely stalled (no log activity for > 5 minutes): run
  `/rtp scan cancel <region>` then `/rtp scan start <region>` to restart it.

---

## Database File Grows Unboundedly

**Symptom:** The H2 or SQLite database file in the plugin data folder grows continuously
across restarts, consuming significant disk space.

**Diagnosis:**
1. Locate the database file: `plugins/RTP/database/` (check `config.yml` for the configured
   path).
2. A growing database typically means the spatial memory is accumulating entries for a region
   whose geometry keeps changing, or that old region entries are never pruned after a region
   is removed.
3. Query the database with an H2 or SQLite client to count rows per region table and identify
   which region is growing.

**Resolution:**
- For a removed region: manually delete its table from the database, or delete the database
  file entirely and rebuild via `/rtp scan start` for all active regions.
- For an active region with excessive entries: run `/rtp scan reset <region>` to clear its
  spatial memory, then `/rtp scan start <region>` to rebuild from scratch with the current
  geometry.
- After pruning, restart the server so the plugin re-opens the database cleanly.

---

## Addon Reports `IllegalStateException` on Load

**Symptom:** An addon jar logs `[RTP API] Cannot access hooks: Core implementation is not loaded`
(or a similar `Core implementation is not loaded` message) and fails to enable.

**Diagnosis:** The addon is calling an `rtp-api` contract entry point (e.g. `RTPAPI.hooks()`) -- or an
`rtp-core` extension entry point such as `RTP.addShape()` / `RTP.addVerticalAdjustor()` -- before
`rtp-core` has finished its `onEnable`. This is a load-order problem (FM-009).

**Resolution:**
1. Open the addon's `plugin.yml`.
2. Add or update the `depend` list to include `RTP`:
   ```yaml
   depend: [RTP]
   ```
3. Restart the server. Bukkit will now guarantee RTP loads and enables before the addon.

If the addon is a third-party jar you cannot modify, contact its author and request the
`depend` entry be added, or use a load-order plugin to enforce the correct sequence.

---

## `/rtp reload` Does Not Apply Config Changes

**Symptom:** After editing a config file and running `/rtp reload`, the new values do not
appear to take effect.

**Diagnosis:**
1. Some configuration keys require a full server restart rather than a reload. Check
   [`CONFIGURATION.md`](CONFIGURATION.md) for the reload vs. restart annotation on the
   changed key.
2. Verify there are no YAML syntax errors in the edited file, as a parse failure causes the
   reload to silently retain the last valid config. Check the console for ERROR messages
   immediately after `/rtp reload`.

**Resolution:**
- If the key requires a restart: restart the server.
- If a syntax error is present: fix the file (validate with a YAML linter), then re-run
  `/rtp reload`.
