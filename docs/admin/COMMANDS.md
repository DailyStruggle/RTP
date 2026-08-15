# Command Reference

**Current Plugin Version:** `@version@`

All RTP commands are subcommands of `/rtp`. Parameters are passed in `key=value` format and can be combined freely unless noted otherwise. Tab-completion is available for all parameters and reflects live server state.

---

## `/rtp` — Random Teleport

Initiates a random teleportation sequence for the executing player (or a named target). Guards (permission, cooldown, duplicate-processing, reload-lock) run synchronously on the calling thread; the location search and teleport pipeline are dispatched asynchronously and never block the main server thread.

**Syntax**
```
/rtp
/rtp [player=<name>] [world=<name>] [region=<name>]
/rtp [biome=<name>]
/rtp [shape=<name> [<shape-key>=<value> …]]
/rtp [vert=<name>  [<vert-key>=<value>  …]]
/rtp [worldBorderOverride=<true|false>]
/rtp [toggletargetperms=<true|false>]
```

**Parameters**

| Parameter | Type | Required Permission | Description |
|---|---|---|---|
| *(none)* | — | `rtp.use` | Teleport the executing player using the default region for their current world. |
| `player=<name>` | String (online player name) | `rtp.use` | Teleport a named online player instead of (or in addition to) yourself. Repeat for multiple targets: `player=Alice player=Bob`. |
| `world=<name>` | String (loaded world name) | `rtp.use` | Derive the target region from this world's configuration rather than the sender's current world. Ignored when `region` is also supplied. |
| `region=<name>` | String (region name) | `rtp.use` | Teleport into a specific, named region. Takes priority over `world`. Repeat for multiple values; one is chosen at random each time. |
| `biome=<name>` | String (Minecraft biome key, upper-case) | `rtp.use` | Restrict the destination to locations inside the specified biome. Repeat to allow multiple biomes. Forces a fresh async search — pre-cached locations are not used. |
| `shape=<name>` | String (factory key) | `rtp.params` | Override the region's default shape for this single teleport. The region is cloned; its permanent definition is not changed. Sub-keys for the chosen shape type (e.g. `radius`, `weight`) may follow immediately. |
| `vert=<name>` | String (factory key) | `rtp.params` | Override the region's vertical adjustor for this single teleport. Sub-keys for the chosen vert type may follow. |
| `worldBorderOverride=<bool>` | Boolean | `rtp.params` | When `true`, replaces the region shape with the server's current world-border shape for this teleport. Incurs the `paramsPrice` economy charge. |
| `toggletargetperms=<bool>` | Boolean | `rtp.params` | When `true`, cooldown, delay, and economy checks are evaluated against each *target* player's attributes rather than the sender's. |

**Examples**
```
/rtp
/rtp player=Steve
/rtp world=world_nether
/rtp region=mining
/rtp region=default biome=BADLANDS
/rtp region=default shape=SQUARE radius=256
/rtp player=Steve toggletargetperms=true
/rtp worldBorderOverride=true
```

> **Threading note:** Permission, cooldown, and duplicate-processing guards run synchronously. The `TeleportPipelineTask` is always dispatched via the async scheduler. Synchronous execution only occurs when a pre-cached (globally queued) location is available for the player **and** the teleport delay is ≤ 0. In that case `syncLoading` is implicitly treated as `true` because no chunk loading is required — the cached location is used directly without any I/O.

---

## `/rtp reload` — Reload Configuration

Reloads configuration files from disk without restarting the server. Sets a reload-lock that rejects incoming teleport requests for the duration of the reload; the lock is released via a synchronous 1-tick delayed task after a successful reload.

**Syntax**
```
/rtp reload
/rtp reload <config>
```

**Required permission:** `rtp.reload`

| Form | Description |
|---|---|
| `/rtp reload` | Reload **all** configuration files. Region queues are rebuilt; any in-progress teleports that pass the reload-lock check will continue to their completion. |
| `/rtp reload <config>` | Reload a single config file or multi-config group by name (strip `.yml`). For `regions`, all `Region` objects are shut down and rebuilt from fresh file data. For `worlds`, each currently-loaded world's parser is refreshed. |

**Examples**
```
/rtp reload
/rtp reload performance
/rtp reload regions
/rtp reload worlds
/rtp reload economy
```

---

## `/rtp config` — Edit Configuration at Runtime

Reads and writes individual keys in any loaded configuration file. One child sub-command is registered for every loaded `ConfigParser` and `MultiConfigParser` (file names with `.yml` stripped); tab-complete at position 1 enumerates the live set. Each successful write is **atomic** (temp + fsync + rename) and the affected parser is reloaded automatically — `/rtp reload` is only needed after hand-edits on disk.

> ⚠️ **Hardening in `3.0.0-beta.3`.** This section describes the **target** behavior. Pre-beta.3 builds may still silently ignore unknown keys or skip validation; for production use on those builds, prefer hand-editing the YAML files followed by `/rtp reload`.

**Syntax**
```
/rtp config <file> <key>=<value> [<key>=<value> …] [--dry-run]
/rtp config <file> <list-key> add=<value> [add=<value> …] [remove=<value> …] [--dry-run]
/rtp config <multifile> <subfile> <key>=<value> [--dry-run]
/rtp config <file> view
/rtp config <file> view <key> [<key> …]
```

One invocation targets **exactly one** file. Multiple `<key>=<value>` pairs (or `add=` / `remove=` operators on the same list key) within that file form one all-or-nothing transaction. The `--dry-run` token (default literal, configurable via `commands.config.dryRunFlag`) runs validation and renders the would-be diff without touching disk.

**Required permissions** (additive; the most-specific node wins):

| Node | Grants |
|---|---|
| `rtp.config` | Legacy alias retained for back-compat — equivalent to `rtp.config.view` + `rtp.config.set`. |
| `rtp.config.view` | The `view` sub-form against any file. |
| `rtp.config.set` | Any write against any file (umbrella). |
| `rtp.config.set.<section>` | Writes against the named section only (e.g. `rtp.config.set.regions`, `rtp.config.set.performance`). `set` implies `view` for the same section. |

| Form | Description |
|---|---|
| `/rtp config <file> <key>=<value>` | Set one or more scalar keys in the named config file. Validation is uniform; unknown keys, type mismatches, out-of-range values, and unknown region / world names are rejected with a `reasonCode` (see below) before any state mutates. |
| `/rtp config <file> <list-key> add=<value>` / `remove=<value>` | Append to / remove from a YAML list field. Duplicate `add=` and `remove=` of non-members are no-ops, not failures. |
| `/rtp config <file> view` | Interactive read-only inspection of every key in the file. Each rendered entry carries hover-text (when YAML comments are available; this is a substrate-dependent best-effort) and a click-suggest action that pre-fills an update command. Degrades to plain text on consoles. |
| `/rtp config <file> view <key>` | As above, but for one key (or one list-key's members). |
| trailing `--dry-run` | Validate and compute the diff, but do not commit. The audit record is emitted with `outcome = DRY_RUN_OK`. |

**Audit & error reporting.** Every invocation — success or failure, live or dry-run — emits exactly one audit record at `INFO` (success) or `WARNING` (failure) in the server log, carrying the actor, command, target file, the per-mutation old/new value diff, the outcome, and (on failure) a `reasonCode`. Failure messages render from the `config.error.<reasonCode>` entries in `messages.yml`; the codes include `UNKNOWN_FILE`, `UNKNOWN_KEY`, `WRONG_TYPE`, `OUT_OF_RANGE`, `UNKNOWN_REGION`, `UNKNOWN_WORLD`, `SCHEMA_INVARIANT`, `NO_PERMISSION`, `RELOAD_IN_PROGRESS`, `PERSIST_IO`, and others.

> **World-aware vertical clamping** (applies on every write **and** every reload): when updating a region config that targets a `_nether` world, `maxY` is automatically clamped to 128, `vert` is forced to `LINEAR`, and `requireskylight` is set to `false`. For `_the_end` worlds, `requireskylight` is set to `false`. In all cases `maxY`/`minY` are clamped to the world's actual height limits. A violation aborts the transaction with `reasonCode = SCHEMA_INVARIANT` and the on-disk file is unchanged.

> **`language.yml` is not addressable** through the generic `/rtp config language …` form because a locale change requires re-initializing every parser. The dedicated `LanguageCmd` path handles it (subject to the same audit, permission, and atomic-write contracts as this surface). Attempts via the generic path fail with `reasonCode = USE_DEDICATED_COMMAND`.

**Examples**
```
/rtp config performance maxAttempts=20
/rtp config economy price=100 --dry-run
/rtp config regions nether world=world_nether
/rtp config regions nether maxY=128
/rtp config regions default biomeWhitelist add=FOREST add=PLAINS remove=OCEAN
/rtp config regions default view shape
/rtp config performance view
```

---

## `/rtp scan` — Map the Teleport Region (Spatial Memory)

Iterates through every possible coordinate in a region's shape to identify and record safe vs. unsafe areas. This populates the "spatial memory" database so that the plugin knows where it can and cannot teleport players before it even tries. A bare `/rtp scan` (no sub-command) behaves identically to `/rtp scan resume`.

**Actual Purpose:**
The `scan` command is used to proactively map out the world. While the standard teleport logic finds locations on-the-fly, a `scan` performs a comprehensive sweep of the entire region. It stores known-bad locations (oceans, solid blocks, claimed land) in spatial memory so that all future teleport selections, whether from the cache or a fresh search, can instantly skip these areas.

**Required permission:** `rtp.scan`  
**Target region resolution:** If `region` is omitted and the caller is a player, the player's current region is used. If the caller is the console, all permanent regions are targeted.

> **Scannable-shape requirement:** All scan sub-commands require the region to use a shape type that supports spatial memory. Regions backed by other shape types are silently skipped with an "invalid argument" message.

---

### `/rtp scan start`

Discards any existing spatial memory for the region and begins a full-space enumeration from the first coordinate. Use this when you have radically changed your `safety.yml` or added new protection plugins and want to re-verify the entire world.

**Syntax**
```
/rtp scan start [region=<name>]
```

| Parameter | Description |
|---|---|
| `region=<name>` | Target a specific region. Omit to use the caller's current region (player) or all regions (console). |

- If a scan is already running for the region, an announcement is sent to all `rtp.scan` holders and the command aborts.
- The `ScanTask` is dispatched via the async scheduler; it never blocks the main thread or region tick threads.

**Example**
```
/rtp scan start
/rtp scan start region=mining
```

---

### `/rtp scan reset`

Clears all spatial memory and cached locations for a region without starting a new scan pass. This effectively "forgets" everything the plugin knows about safe/unsafe spots in that region.

**Syntax**
```
/rtp scan reset [region=<name>]
```

- If a scan task is currently running for the region, it is cancelled, paused, and its persistence file deleted before the shape data is cleared.
- On completion, `MessagesKeys.scanReset` is broadcast to all `rtp.scan` holders.

**Example**
```
/rtp scan reset region=default
```

---

### `/rtp scan pause`

Suspends an active scan task, preserving progress so it can be resumed later. The current `MemoryShape` state is persisted to disk immediately.

**Syntax**
```
/rtp scan pause [region=<name>]
```

- If no scan task is running for the region, `MessagesKeys.scanNotRunning` is broadcast and no further action is taken.

**Example**
```
/rtp scan pause region=mining
```

---

### `/rtp scan resume`

Resumes a paused scan task from its last saved position. If no task exists for the region, delegates automatically to `scan start`.

**Syntax**
```
/rtp scan resume [region=<name>]
/rtp scan [region=<name>]
```

- Clears the `pause` flag on the existing `ScanTask` and re-schedules it via the async scheduler.

**Example**
```
/rtp scan resume
/rtp scan resume region=mining
```

---

### `/rtp scan cancel`

Permanently stops an active or paused scan task and deletes its persistence file. Progress is lost.

**Syntax**
```
/rtp scan cancel [region=<name>]
```

- If no scan task is running, `MessagesKeys.scanNotRunning` is broadcast.
- On success, the task is removed from the scan task registry and `MessagesKeys.scanCancel` is broadcast to all `rtp.scan` holders.

**Example**
```
/rtp scan cancel region=mining
```

---

## `/rtp info` — Plugin Information

Displays the current runtime state of the plugin: loaded worlds, permanent regions, queue statistics, and performance metrics.

**Syntax**
```
/rtp info
/rtp info world=<name>
/rtp info region=<name>
```

**Required permission:** `rtp.info`

| Parameter | Description |
|---|---|
| *(none)* | List all loaded worlds and permanent regions. In-game players receive clickable suggest-click entries; console receives full inline detail. |
| `world=<name>` | Display the `worldInfo` message template for each named world. Inactive or non-existent worlds are silently skipped. |
| `region=<name>` | Display the `regionInfo` message template for each named region, including queue depth, in-flight calculations, shape, cache cap, and a persistent learned-state summary (coverage, bad fraction, top rejection cause). |

**Persistent learned-state placeholders** (usable in the `regionInfo` template in `messages.yml`): these summarize the region's persisted learned state - the same data written to `database/regionData/debug/<region>.json` on each scan. They resolve to `N/A` when the region's shape does not keep learned state or has not been scanned yet. No chunk loading is performed.

| Placeholder | Description |
|---|---|
| `[memCoveragePct]` | Percentage of the region's candidate cells that have been learned (flagged bad + recorded good). |
| `[memBadPct]` | Percentage of candidate cells currently flagged bad. |
| `[memBadCount]` | Number of cells currently flagged bad. |
| `[memTopCause]` | Rejection cause covering the most flagged cells (e.g. `safety`, `biome`, `worldBorder`), or `none`. |
| `[memTopCausePct]` | That cause's share of all flagged-bad cells. |

> Players with `rtp.admin` or `rtp.support` additionally see DRM/licensing metadata (downloader ID and download nonce) appended at the end of every `/rtp info` response.

**Examples**
```
/rtp info
/rtp info world=world_nether
/rtp info region=default
```

---

## `/rtp test` — Runtime Test Suite

Operator-facing self-test commands that exercise the teleport pipeline, queue, safety checks, verifiers, and scheduler against the live server.

**Required permission:** `rtp.test`

| Sub-command | Status | Description |
|---|---|---|
| `/rtp test stress player=<name> [iterations=N] [intervalTicks=T] [region=<name>]` | Available | Repeatedly teleports the listed player(s) through the real `/rtp` pipeline. `iterations` is clamped to `[1, 1000]` (default `10`); `intervalTicks` to `[10, 6000]` (default `40`). |
| `/rtp test queue`, `safety`, `verifiers`, `memory`, `platform`, `full` | Planned | Not yet available. |

**Examples**
```
/rtp test stress player=leaf26
/rtp test stress player=leaf26 iterations=50 intervalTicks=60
/rtp test stress player=Alice player=Bob region=mining
```

> **Threading note:** The stress loop runs asynchronously and delegates each iteration to the standard `/rtp` pipeline, so every safety guard (cooldown, economy, claim verifiers, async chunk I/O) remains active. Per-iteration failures are logged at `WARNING` in the server log.

---

## `/rtp help` — Help

Displays a clickable, permission-filtered list of all available `/rtp` sub-commands. Only sub-commands for which the sender holds the required permission **and** which have a matching `MessagesKeys` entry are shown.

**Syntax**
```
/rtp help
```

**Required permission:** `rtp.see`

Each displayed line is a clickable chat message; clicking it runs `/rtp <subcommand>`.

---

## PlaceholderAPI Placeholders

When PlaceholderAPI is installed, the following `%rtp_<key>%` placeholders are available. All placeholders resolve against the requesting player's UUID at call time, reflecting the player's current/last region context. The **same keys** also resolve inside `messages.yml` as `[key]` (square brackets) without PlaceholderAPI installed. A key with no value resolves to an empty string.

| Placeholder | Description |
|---|---|
| `%rtp_delay%` | Remaining teleport delay in ticks |
| `%rtp_cooldown%` | Full cooldown period (ms) |
| `%rtp_remainingCooldown%` | Remaining cooldown time |
| `%rtp_queueLocation%` | Whether a cached location is queued for the player |
| `%rtp_teleports%` | Total teleports performed by this player |
| `%rtp_mspt%` | Current server MSPT |
| `%rtp_attempts%` | Location-search attempts for the current teleport |
| `%rtp_processingTime%` | Processing time for the current teleport |
| `%rtp_spot%` | Current target coordinates (`x y z`) |
| `%rtp_player%` | Player UUID |
| `%rtp_player_name%` | Player display name |
| `%rtp_player_status%` | Player teleport status |
| `%rtp_world%` | Player's current world |
| `%rtp_name%` | Player name |
| `%rtp_region%` | Player's current region name |
| `%rtp_displayName%` | Region's cosmetic display name (falls back to the region name) |
| `%rtp_shape%` | Region shape type |
| `%rtp_requirePermission%` | Whether the region requires `rtp.regions.<name>` |
| `%rtp_override%` | The region's no-permission redirect target |
| `%rtp_worldBorderOverride%` | Whether the region honors the vanilla world border |
| `%rtp_pluginForced%` | Whether a plugin forced the region |
| `%rtp_serverForced%` | Whether the server forced the region |
| `%rtp_cacheCap%` | Region cache capacity |
| `%rtp_backlogCacheCap%` | Backlog (unverified) cache capacity; 0 disables it |
| `%rtp_cached%` | Total currently cached locations |
| `%rtp_keptCache%` | Locations whose chunks are kept loaded (hot/ready-to-serve) |
| `%rtp_unkeptCache%` | Verified locations whose chunks were released (warm) |
| `%rtp_backlogCache%` | Unverified backlog buffer depth |
| `%rtp_locationQueue%` | Number of players waiting on a coordinate |
| `%rtp_inFlightCalculations%` | In-flight async calculations |
| `%rtp_pipelineMsP50%` | Teleport-pipeline latency, 50th percentile (ms) |
| `%rtp_pipelineMsP75%` | Pipeline latency, 75th percentile (ms) |
| `%rtp_pipelineMsP90%` | Pipeline latency, 90th percentile (ms) |
| `%rtp_pipelineMsP95%` | Pipeline latency, 95th percentile (ms) |
| `%rtp_pipelineMsP99%` | Pipeline latency, 99th percentile (ms) |
| `%rtp_pipelineSampleCount%` | Number of latency samples in the current window |
| `%rtp_slowPipelineCount%` | Count of pipeline runs over the slow threshold |
| `%rtp_slowPipelineThresholdMs%` | The slow-pipeline threshold (ms) |
| `%rtp_queueGrowthWarnCount%` | How many times queue growth tripped the warning |
| `%rtp_queueGrowthWarnThreshold%` | The queue-growth warning threshold |
| `%rtp_memCoveragePct%` | Percentage of the region mapped by spatial memory |
| `%rtp_memBadPct%` | Percentage of mapped area marked unsafe |
| `%rtp_memBadCount%` | Count of known-bad cells |
| `%rtp_memTopCause%` | Most common rejection cause (biome, unsafe block, claim, ...) |
| `%rtp_memTopCausePct%` | Share of rejections attributable to `memTopCause` |
| `%rtp_scan_chunks%` | Chunks processed across all active scans |
| `%rtp_scan_totalChunks%` | Total chunks to process across all active scans |
| `%rtp_scan_cps%` | Chunks processed per second (all active scans) |
| `%rtp_scan_regions%` | Comma-separated list of regions currently being scanned |
| `%rtp_scan_eta%` | Human-readable ETA for active scans (e.g. `2m 30s`) |
| `%rtp_scan_landPercentage%` | Percentage of valid land locations found so far |

---

## Full Permission Reference

| Permission | Default | Description |
|---|---|---|
| `rtp.use` | op | Use `/rtp` to teleport yourself |
| `rtp.see` | op | See RTP related messages and `/rtp help` |
| `rtp.free` | op | Bypass all economy charges |
| `rtp.noCooldown` | op | Bypass teleport cooldown |
| `rtp.noDelay` | op | Bypass teleport delay |
| `rtp.noDelay.chunks` | op | Bypass chunk-load delay |
| `rtp.noCancel` | op | Prevent teleport from being cancelled |
| `rtp.other` | op | Teleport another player with `player=<name>` |
| `rtp.notme` | op | Make yourself untargetable by other players' `/rtp player=<name>` - prevents forced RTP by other ops. Console is exempt and can always target any player. |
| `rtp.reload` | op | Use `/rtp reload` |
| `rtp.config` | op | Legacy alias — grants `rtp.config.view` + `rtp.config.set`. Retained for back-compat; new deployments should grant the more specific nodes below. |
| `rtp.config.view` | op | Use `/rtp config <file> view` (read-only inspection). |
| `rtp.config.set` | op | Use `/rtp config <file> …` to write any config file (umbrella). |
| `rtp.config.set.<section>` | op | Write only the named section (e.g. `rtp.config.set.regions`, `rtp.config.set.performance`). `set` implies `view` for the same section. |
| `rtp.update` | op | Write individual config keys or list items (legacy node; subsumed by `rtp.config.set`). |
| `rtp.info` | op | Use `/rtp info` |
| `rtp.admin` | op | See DRM info in `/rtp info`; elevated admin access |
| `rtp.support` | op | See DRM info in `/rtp info` |
| `rtp.scan` | op | Use all `/rtp scan` sub-commands |
| `rtp.test` | op | Use all `/rtp test` runtime-test sub-commands |
| `rtp.params` | op | Override `shape`, `vert`, and `worldBorderOverride` parameters |
| `rtp.unqueued` | op | Teleport without consuming a pre-generated cached location |
| `rtp.personalqueue` | op | Use a personal (per-player) location queue |
| `rtp.world` | op | Use the `world` parameter |
| `rtp.worlds.<name>` | op | Target a specific world by name |
| `rtp.worlds.*` | op | Target any world |
| `rtp.region` | op | Use the `region` parameter |
| `rtp.regions.<name>` | op | Target a specific region by name |
| `rtp.regions.*` | op | Target any region |
| `rtp.biome` | op | Use the `biome` parameter |
| `rtp.biome.<name>` | op | Restrict to a specific biome |
| `rtp.biome.free` | op | Bypass extra economy cost for biome filtering |
| `rtp.biome.*` | op | Use any biome |
| `rtp.onevent.join` | false | Auto-RTP on first join |
| `rtp.onevent.firstJoin` | false | Auto-RTP on every join |
| `rtp.onevent.respawn` | false | Auto-RTP on death/respawn |
| `rtp.onevent.changeWorld` | false | Auto-RTP on world change |
| `rtp.onevent.move` | false | Auto-RTP on movement trigger |
| `rtp.onevent.teleport` | false | Auto-RTP on teleport event |
| `rtp.onevent.*` | false | All auto-RTP event triggers |

> **Typical setup:** Grant `rtp.use` to your default player group. Grant `rtp.free` + `rtp.noCooldown` to your VIP/donor group. Grant the full `rtp.*` tree to server operators and administrators.
