# Failure Mode Catalog

This document defines the system's specified behaviour for each known failure mode. For every
component that can fail, it records: what the failure looks like, its effect on players and the
server, how the system detects it, and the defined response.

For the hazard-level view (severity + mitigation) see [`HAZARDS.md`](HAZARDS.md).
For operator diagnosis and recovery steps see [`RUNBOOK.md`](RUNBOOK.md).

---

## Queue and Location Generation

### FM-001 — Queue Empty at Teleport Time
**Component:** `RegionQueueManager`
**Failure:** The pre-generated location queue for the target region contains no valid entries
when a player issues `/rtp`.
**Effect:** The teleport request cannot be fulfilled immediately.
**Detection:** Pre-teleport queue size check returns zero before dispatching.
**Response:** The player's UUID is added to a deferred-teleport queue for the target region.
When the periodic replenishment task next produces a valid location for that region, the
player is teleported automatically without requiring them to re-issue the command. The player
receives a message informing them they are queued. The queue replenishment task is not
interrupted; it continues its normal cycle and fulfils pending requests as locations become
available.
**Requirement:** `REQ-RTP-S-004`

---

### FM-002 — All Sectors Marked Bad (Scan Exhaustion)
**Component:** `MemoryShape`
**Failure:** Every coordinate in the region's spatial memory is marked invalid, leaving no candidate coordinates for location generation.
**Effect:** The queue cannot be replenished. All teleport requests to this region will encounter FM-001 indefinitely.
**Detection:** Scan task reports zero valid locations remaining; spatial memory remains empty after a full mapping cycle.
**Response:** The plugin logs an ERROR with the region name. The operator must either reconfigure the region geometry to include valid land, clear the spatial memory via `/rtp scan reset`, or disable the region.
**Requirement:** `REQ-RTP-S-004`

---

### FM-003 — Location Fails Safety Check After Dequeue
**Component:** Safety check layer (runtime re-validation)
**Failure:** A pre-generated location passes initial validation at scan time but fails a re-validation check at teleport time (e.g. a claim was placed over it since generation).
**Effect:** The candidate location is discarded; the teleport does not proceed with an unsafe destination.
**Detection:** Runtime safety check returns false during final dispatch.
**Response:** The location is marked as invalid in spatial memory and removed from the queue. The system attempts to serve the next queued location if one is available; otherwise FM-001 behaviour applies. The player is notified.
**Requirement:** `REQ-RTP-S-001`, `REQ-RTP-S-003`

---

## Chunk Management

### FM-004 — Pipeline Task Exceeds Lifespan (Watchdog Trigger)
**Component:** `MemoryTracker`
**Failure:** A `TeleportPipelineTask` registered with `MemoryTracker` is not completed within
its configured maximum lifespan, which indicates an abandoned or stalled pipeline.
**Effect:** Without intervention, the stalled task holds any chunk tickets it has acquired,
preventing them from being released (see H-004 in [`HAZARDS.md`](HAZARDS.md)).
**Detection:** `MemoryTracker.runDiagnostics()` fires on schedule and finds a tracked pipeline
task alive past its expected lifespan.
**Response:** The tracker calls `pipelineTask.setCancelled(true)` and reschedules it via
`RTP.scheduler.runTask(pipelineTask)`, forcing the task into its cleanup phase where it releases
any held chunk tickets. The task label and elapsed time are logged at SEVERE level in the format:
`[RTP] Memory leak detected for object: <label>. Alive <ms>ms past its expected lifespan.`
The entry is then removed from the tracker to prevent repeated alerts for the same task.
**Requirement:** `REQ-RTP-S-002`

---

### FM-005 — Chunk Load Timeout
**Component:** Platform adapter (`rtp-paper`, `rtp-folia`, `rtp-spigot`)
**Failure:** An async chunk load request does not complete within the expected window, such as
when the server is under extreme I/O load.
**Effect:** The validation task stalls and the queue slot is not filled.
**Detection:** `CompletableFuture` for the chunk load does not complete before the task's
scheduled window expires; `MemoryTracker` detects the stalled pipeline task (FM-004 path).
**Response:** The stalled task is cancelled and rescheduled for cleanup by FM-004 handling. Since the
location was not evaluated, it is not marked as invalid, and the scan task will retry it in the next cycle.
**Requirement:** `REQ-RTP-S-002`, `REQ-RTP-F-008`

---

## Persistent State

### FM-006 — Database Unreadable on Startup
**Component:** H2 / SQLite spatial memory store
**Failure:** The spatial memory database file is corrupt or incompatible after an unclean shutdown or manual file modification.
**Effect:** The spatial memory cannot be loaded; the plugin cannot resume from prior state.
**Detection:** Exception thrown during database open or schema migration at `onEnable`.
**Response:** The plugin logs a WARN-level message identifying the database file and the exception. The spatial memory is initialised empty, as if no scan has been run. The plugin continues to operate, and a new scan operation will rebuild the map. The corrupt file is not deleted automatically, allowing the operator to inspect it.
**Requirement:** `REQ-RTP-NF-001`

---

### FM-007 — Region Configuration Missing or Malformed
**Component:** Configuration loader (`rtp-core`)
**Failure:** A region's YAML configuration file is absent, has invalid syntax, or is missing
required keys.
**Effect:** The region cannot be initialised; teleport requests targeting it fail.
**Detection:** YAML parse exception or missing-key check during config load at `onEnable` or
`/rtp reload`.
**Response:** The plugin logs an ERROR identifying the file and the missing/invalid key. The
affected region is skipped; all other regions continue to operate. The operator is directed to
correct the file and run `/rtp reload`.
**Requirement:** `REQ-RTP-S-004`

---

## Platform and API

### FM-008 — Plugin Fails to Enable (Platform API Mismatch)
**Component:** Platform adapter (`onEnable`)
**Failure:** A `ClassNotFoundException`, `NoSuchMethodError`, or similar linkage error occurs
during adapter initialisation because the server version does not match the compiled adapter.
**Effect:** The plugin is disabled; no RTP commands are available.
**Detection:** Exception propagates to `onEnable`; Bukkit catches it and marks the plugin as
disabled.
**Response:** The server console shows the exception. The operator should verify that the
correct platform adapter jar is installed for the running server version and consult
[`RUNBOOK.md`](RUNBOOK.md#plugin-fails-to-enable-on-startup) for the matching version table.
**Requirement:** `REQ-RTP-SYS-002`

---

### FM-009 — Addon Calls `rtp-api` Before Core Is Ready
**Component:** `RTPAPI` static delegates
**Failure:** An addon plugin calls `RTPAPI.addShape()` or `RTPAPI.addVerticalAdjustor()` before
`rtp-core` has registered its delegate functions.
**Effect:** `IllegalStateException` is thrown inside the addon's `onEnable`, disabling the addon.
**Detection:** Null-check on the static delegate inside `RTPAPI.addShape()` /
`RTPAPI.addVerticalAdjustor()`.
**Response:** `RTPAPI` throws `IllegalStateException("[RTP API] Cannot add shape: Core implementation is not loaded.")`. The server console identifies the offending addon, and the addon developer should declare RTP as a hard `depend` (not `softdepend`) in their `plugin.yml`.
**Requirement:** `REQ-RTP-S-006`

---

## Failure Mode Summary

| ID | Component | Failure | Severity | Response |
|----|-----------|---------|----------|----------|
| FM-001 | `RegionQueueManager` | Queue empty | Medium | Queue player UUID for deferred teleport; fulfilled on next replenishment cycle |
| FM-002 | `MemoryShape` | All locations bad | High | Log ERROR, operator action required |
| FM-003 | Safety check layer | Location unsafe at dispatch | Medium | Discard, retry next, notify player |
| FM-004 | `MemoryTracker` | Pipeline task timeout | High | Cancel task, reschedule for cleanup, log SEVERE |
| FM-005 | Platform adapter | Chunk load timeout | Medium | Force-cleanup via FM-004, retry location |
| FM-006 | H2 / SQLite | Database unreadable | Medium | Log WARN, start empty, continue |
| FM-007 | Config loader | Region config malformed | Medium | Log ERROR, skip region, continue |
| FM-008 | Platform adapter | API linkage failure | High | Plugin disabled, operator action required |
| FM-009 | `RTPAPI` | Early addon API call | Medium | `IllegalStateException` with message |
