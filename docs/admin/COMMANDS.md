# Command Reference

**Applies to Plugin Version:** `3.0.0-beta.1`

All RTP commands are subcommands of `/rtp`. Parameters are passed in `key:value` format and can be combined freely unless noted otherwise. Tab-completion is available for all parameters and reflects live server state.

---

## `/rtp` — Random Teleport

Initiates a random teleportation sequence for the executing player (or a named target). Guards (permission, cooldown, duplicate-processing, reload-lock) run synchronously on the calling thread; the location search and teleport pipeline are dispatched asynchronously and never block the main server thread.

**Syntax**
```
/rtp
/rtp [player:<name>] [world:<name>] [region:<name>]
/rtp [biome:<name>]
/rtp [shape:<name> [<shape-key>:<value> …]]
/rtp [vert:<name>  [<vert-key>:<value>  …]]
/rtp [worldBorderOverride:<true|false>]
/rtp [toggletargetperms:<true|false>]
```

**Parameters**

| Parameter | Type | Required Permission | Description |
|---|---|---|---|
| *(none)* | — | `rtp.use` | Teleport the executing player using the default region for their current world. |
| `player:<name>` | String (online player name) | `rtp.use` | Teleport a named online player instead of (or in addition to) yourself. Repeat for multiple targets: `player:Alice player:Bob`. |
| `world:<name>` | String (loaded world name) | `rtp.use` | Derive the target region from this world's configuration rather than the sender's current world. Ignored when `region` is also supplied. |
| `region:<name>` | String (region name) | `rtp.use` | Teleport into a specific, named region. Takes priority over `world`. Repeat for multiple values; one is chosen at random each time. |
| `biome:<name>` | String (Minecraft biome key, upper-case) | `rtp.use` | Restrict the destination to locations inside the specified biome. Repeat to allow multiple biomes. Forces a fresh async search — pre-cached locations are not used. |
| `shape:<name>` | String (factory key) | `rtp.params` | Override the region's default shape for this single teleport. The region is cloned; its permanent definition is not changed. Sub-keys for the chosen shape type (e.g. `radius`, `weight`) may follow immediately. |
| `vert:<name>` | String (factory key) | `rtp.params` | Override the region's vertical adjustor for this single teleport. Sub-keys for the chosen vert type may follow. |
| `worldBorderOverride:<bool>` | Boolean | `rtp.params` | When `true`, replaces the region shape with the server's current world-border shape for this teleport. Incurs the `paramsPrice` economy charge. |
| `toggletargetperms:<bool>` | Boolean | `rtp.params` | When `true`, cooldown, delay, and economy checks are evaluated against each *target* player's attributes rather than the sender's. |

**Examples**
```
/rtp
/rtp player:Steve
/rtp world:world_nether
/rtp region:mining
/rtp region:default biome:BADLANDS
/rtp region:default shape:SQUARE radius:256
/rtp player:Steve toggletargetperms:true
/rtp worldBorderOverride:true
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

Dynamically reads and writes individual keys in any loaded configuration file. One child sub-command is registered for every loaded `ConfigParser` and `MultiConfigParser` (file names with `.yml` stripped). All file writes are dispatched asynchronously; a targeted reload is triggered automatically after each write.

**Syntax**
```
/rtp config <file> <key>:<value> [<key>:<value> …]
/rtp config <file> <list-key> add:<value> [add:<value> …] [remove:<value> …]
```

**Required permission:** `rtp.config`

| Form | Description |
|---|---|
| `/rtp config <file> <key>:<value>` | Set one or more scalar keys in the named config file and reload. Only keys present in the file's own parameter registry are accepted; unrecognised keys are silently ignored. |
| `/rtp config <file> <list-key> add:<value>` | Append one or more values to a YAML list field. |
| `/rtp config <file> <list-key> remove:<value>` | Remove one or more values from a YAML list field. |

> **World-aware vertical clamping:** When updating a region config that targets a `_nether` world, `maxY` is automatically clamped to 128, `vert` is forced to `LINEAR`, and `requireskylight` is set to `false`. For `_the_end` worlds, `requireskylight` is set to `false`. In all cases `maxY`/`minY` are clamped to the world's actual height limits.

**Examples**
```
/rtp config performance maxAttempts:20
/rtp config economy price:100
/rtp config regions nether world:world_nether
/rtp config regions nether maxY:128
/rtp config regions default biomeWhitelist add:FOREST add:PLAINS
/rtp config regions default biomeWhitelist remove:OCEAN
```

---

## `/rtp scan` — Map the Teleport Region (Spatial Memory)

Iterates through every possible coordinate in a region's shape to identify and record safe vs. unsafe areas. This populates the "spatial memory" database so that the plugin knows where it can and cannot teleport players before it even tries. A bare `/rtp scan` (no sub-command) behaves identically to `/rtp scan resume`.

**Actual Purpose:**
The `scan` command is used to proactively map out the world. While the standard teleport logic finds locations on-the-fly, a `scan` performs a comprehensive sweep of the entire region. It stores known-bad locations (oceans, solid blocks, claimed land) in spatial memory so that all future teleport selections, whether from the cache or a fresh search, can instantly skip these areas.

**Required permission:** `rtp.scan`  
**Target region resolution:** If `region` is omitted and the caller is a player, the player's current region is used. If the caller is the console, all permanent regions are targeted.

> **MemoryShape requirement:** All scan sub-commands require the region's shape to be a `MemoryShape` implementation. Regions backed by other shape types are silently skipped with a `badArg` message.

---

### `/rtp scan start`

Discards any existing spatial memory for the region and begins a full-space enumeration from the first coordinate. Use this when you have radically changed your `safety.yml` or added new protection plugins and want to re-verify the entire world.

**Syntax**
```
/rtp scan start [region:<name>]
```

| Parameter | Description |
|---|---|
| `region:<name>` | Target a specific region. Omit to use the caller's current region (player) or all regions (console). |

- If a scan is already running for the region, an announcement is sent to all `rtp.scan` holders and the command aborts.
- The `ScanTask` is dispatched via the async scheduler; it never blocks the main thread or region tick threads.

**Example**
```
/rtp scan start
/rtp scan start region:mining
```

---

### `/rtp scan reset`

Clears all spatial memory and cached locations for a region without starting a new scan pass. This effectively "forgets" everything the plugin knows about safe/unsafe spots in that region.

**Syntax**
```
/rtp scan reset [region:<name>]
```

- If a scan task is currently running for the region, it is cancelled, paused, and its persistence file deleted before the shape data is cleared.
- On completion, `MessagesKeys.scanReset` is broadcast to all `rtp.scan` holders.

**Example**
```
/rtp scan reset region:default
```

---

### `/rtp scan pause`

Suspends an active scan task, preserving progress so it can be resumed later. The current `MemoryShape` state is persisted to disk immediately.

**Syntax**
```
/rtp scan pause [region:<name>]
```

- If no scan task is running for the region, `MessagesKeys.scanNotRunning` is broadcast and no further action is taken.

**Example**
```
/rtp scan pause region:mining
```

---

### `/rtp scan resume`

Resumes a paused scan task from its last saved position. If no task exists for the region, delegates automatically to `scan start`.

**Syntax**
```
/rtp scan resume [region:<name>]
/rtp scan [region:<name>]
```

- Clears the `pause` flag on the existing `ScanTask` and re-schedules it via the async scheduler.

**Example**
```
/rtp scan resume
/rtp scan resume region:mining
```

---

### `/rtp scan cancel`

Permanently stops an active or paused scan task and deletes its persistence file. Progress is lost.

**Syntax**
```
/rtp scan cancel [region:<name>]
```

- If no scan task is running, `MessagesKeys.scanNotRunning` is broadcast.
- On success, the task is removed from the scan task registry and `MessagesKeys.scanCancel` is broadcast to all `rtp.scan` holders.

**Example**
```
/rtp scan cancel region:mining
```

---

## `/rtp info` — Plugin Information

Displays the current runtime state of the plugin: loaded worlds, permanent regions, queue statistics, and performance metrics.

**Syntax**
```
/rtp info
/rtp info world:<name>
/rtp info region:<name>
```

**Required permission:** `rtp.info`

| Parameter | Description |
|---|---|
| *(none)* | List all loaded worlds and permanent regions. In-game players receive clickable suggest-click entries; console receives full inline detail. |
| `world:<name>` | Display the `worldInfo` message template for each named world. Inactive or non-existent worlds are silently skipped. |
| `region:<name>` | Display the `regionInfo` message template for each named region, including queue depth, in-flight calculations, shape, and cache cap. |

> Players with `rtp.admin` or `rtp.support` additionally see DRM/licensing metadata (downloader ID and download nonce) appended at the end of every `/rtp info` response.

**Examples**
```
/rtp info
/rtp info world:world_nether
/rtp info region:default
```

---

## `/rtp test` — Runtime Test Suite

Operator-facing self-test commands that exercise the teleport pipeline, queue, safety checks, verifiers, and scheduler against the live server. See `docs/dev/RUNTIME_TEST_SUITE_PLAN.md` for the full design and roadmap.

**Required permission:** `rtp.test`

| Sub-command | Status | Description |
|---|---|---|
| `/rtp test stress player:<name> [iterations:N] [intervalTicks:T] [region:<name>]` | Available | Repeatedly teleports the listed player(s) through the real `/rtp` pipeline. `iterations` is clamped to `[1, 1000]` (default `10`); `intervalTicks` to `[10, 6000]` (default `40`). |
| `/rtp test queue`, `safety`, `verifiers`, `memory`, `platform`, `full` | Planned | Documented in `RUNTIME_TEST_SUITE_PLAN.md §4`. |

**Examples**
```
/rtp test stress player:leaf26
/rtp test stress player:leaf26 iterations:50 intervalTicks:60
/rtp test stress player:Alice player:Bob region:mining
```

> **Threading note:** The stress loop runs on `runTaskTimerAsynchronously` and delegates each iteration to the standard `/rtp` pipeline, so every safety guard (cooldown, economy, claim verifiers, async chunk I/O) remains active. Per-iteration failures are logged at `Level.WARNING` to satisfy REQ-RTP-S-004.

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

When PlaceholderAPI is installed, the following `%rtp_<key>%` placeholders are available. All placeholders resolve against the requesting player's UUID at call time.

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
| `%rtp_shape%` | Region shape type |
| `%rtp_cacheCap%` | Region cache capacity |
| `%rtp_cached%` | Number of currently cached locations |
| `%rtp_locationQueue%` | Location queue depth |
| `%rtp_inFlightCalculations%` | In-flight async calculations |
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
| `rtp.other` | op | Teleport another player with `player:<name>` |
| `rtp.notme` | op | Exclude yourself when others teleport players (`priceOther` exemption) |
| `rtp.reload` | op | Use `/rtp reload` |
| `rtp.config` | op | Use `/rtp config` to read/write configuration |
| `rtp.update` | op | Write individual config keys or list items via `SubConfigCmd` |
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
