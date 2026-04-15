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

### FM-002 — All Sectors Marked Bad (Fill Exhaustion)
**Component:** `MemoryShape`
**Failure:** Every sector in the region's bad-sector map is marked invalid, leaving no
candidate coordinates for location generation.
**Effect:** The queue cannot be replenished. All teleport requests to this region will encounter
FM-001 indefinitely.
**Detection:** Fill task reports zero valid sectors remaining; queue size stays at zero after a
full replenishment cycle.
**Response:** The plugin logs an ERROR with the region name. The operator must either reconfigure
the region geometry to include valid land, clear the bad-sector map via `/rtp fill reset`, or
disable the region.
**Requirement:** `REQ-RTP-S-004`

---

### FM-003 — Location Fails Safety Check After Dequeue
**Component:** Safety check layer (runtime re-validation)
**Failure:** A pre-generated location passes initial validation at fill time but fails a
re-validation check at teleport time (e.g. a claim was placed over it since generation).
**Effect:** The candidate location is discarded; the teleport does not proceed with an unsafe
destination.
**Detection:** Runtime safety check returns false during final dispatch.
**Response:** The location is marked bad in `MemoryShape` and removed from the queue. The system
attempts to serve the next queued location if one is available; otherwise FM-001 behaviour
applies. The player is notified.
**Requirement:** `REQ-RTP-S-001`, `REQ-RTP-S-003`

---

## Chunk Management

### FM-004 — Chunk Reservation Exceeds Window (Watchdog Trigger)
**Component:** `MemoryTracker`
**Failure:** A `ChunkReservation` is not closed within its configured maximum window, indicating
an abandoned or stalled validation task.
**Effect:** Without intervention the chunk remains force-loaded indefinitely (see H-004 in
[`HAZARDS.md`](HAZARDS.md)).
**Detection:** `MemoryTracker` watchdog fires on schedule and finds an open reservation older
than the threshold.
**Response:** The tracker forcibly calls `reservation.close()`, releasing the chunk ticket. The
chunk coordinates, world name, and elapsed time are logged at ERROR level so the operator can
identify the pattern.
**Requirement:** `REQ-RTP-S-002`

---

### FM-005 — Chunk Load Timeout
**Component:** Platform adapter (`rtp-paper`, `rtp-folia`, `rtp-spigot`)
**Failure:** An async chunk load request does not complete within the expected window (e.g.
the server is under extreme I/O load).
**Effect:** The validation task stalls; the queue slot is not filled.
**Detection:** `CompletableFuture` for the chunk load does not complete before the task's
scheduled window expires; `MemoryTracker` detects the open reservation (FM-004 path).
**Response:** The reservation is forcibly closed by FM-004 handling. The sector is not marked
bad (it was not evaluated); the fill task retries it in the next cycle.
**Requirement:** `REQ-RTP-S-002`, `REQ-RTP-F-008`

---

## Persistent State

### FM-006 — Database Unreadable on Startup
**Component:** H2 / SQLite bad-sector store
**Failure:** The spatial memory database file is corrupt or incompatible after an unclean
shutdown or manual file modification.
**Effect:** The bad-sector map cannot be loaded; the plugin cannot resume from prior state.
**Detection:** Exception thrown during database open or schema migration at `onEnable`.
**Response:** The plugin logs a WARN-level message identifying the database file and the
exception. The bad-sector map is initialised empty (as if no fill has been run). The plugin
continues to operate; a new fill operation will rebuild the map. The corrupt file is not
deleted automatically — the operator may inspect it.
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
**Response:** `RTPAPI` throws `IllegalStateException("[RTP API] Cannot add shape: Core
implementation is not loaded.")`. The server console identifies the offending addon. The addon
developer should declare RTP as a hard `depend` (not `softdepend`) in their `plugin.yml`.
**Requirement:** `REQ-RTP-S-006`

---

## Failure Mode Summary

| ID | Component | Failure | Severity | Response |
|----|-----------|---------|----------|----------|
| FM-001 | `RegionQueueManager` | Queue empty | Medium | Queue player UUID for deferred teleport; fulfilled on next replenishment cycle |
| FM-002 | `MemoryShape` | All sectors bad | High | Log ERROR, operator action required |
| FM-003 | Safety check layer | Location unsafe at dispatch | Medium | Discard, retry next, notify player |
| FM-004 | `MemoryTracker` | Reservation timeout | High | Force-close ticket, log ERROR |
| FM-005 | Platform adapter | Chunk load timeout | Medium | Force-close via FM-004, retry sector |
| FM-006 | H2 / SQLite | Database unreadable | Medium | Log WARN, start empty, continue |
| FM-007 | Config loader | Region config malformed | Medium | Log ERROR, skip region, continue |
| FM-008 | Platform adapter | API linkage failure | High | Plugin disabled, operator action required |
| FM-009 | `RTPAPI` | Early addon API call | Medium | `IllegalStateException` with message |
