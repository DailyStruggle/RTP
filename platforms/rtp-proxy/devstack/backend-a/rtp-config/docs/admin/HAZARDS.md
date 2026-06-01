# Hazard Register

This document lists known hazards for the RTP plugin: conditions that could cause harm to players,
degrade server stability, or corrupt persistent state. Each hazard records its severity, the
mitigation implemented, and the governing requirement or architecture decision.

A **hazard** is any condition that, if left unmitigated, produces an undesirable outcome. Severity
is rated on four levels:

| Level | Meaning |
|-------|---------|
| **Critical** | Causes irreversible player harm or server crash with no recovery path |
| **High** | Causes significant server degradation or data loss requiring operator intervention |
| **Medium** | Causes a degraded experience recoverable without restart |
| **Low** | Cosmetic or minor inconvenience with no lasting effect |

For the failure-mode view (detection + response details) see [Failure Modes](#failure-modes) below.
For operator diagnosis and recovery steps see [`RUNBOOK.md`](RUNBOOK.md).

---

## Player Hazards

### H-001 — Player Teleported Into a Lethal Block
**Severity:** Critical

**Description:** A teleport location is selected whose landing block, or a block immediately
surrounding the player, is lava, fire, magma, void air, or another instantly lethal material.
The player takes fatal damage with no opportunity to escape.

**Mitigation (primary):** The safety check layer evaluates the candidate location before it is
placed in the queue. Any location whose landing column contains a disqualifying block is marked
as a bad sector in `MemoryShape` and is never served to a player. The set of disqualifying
blocks is configurable per region via `safety.yml`.

**Mitigation (secondary):** When a player is placed on a block type that is not recognised as
solid by the safety checker (e.g. a modded or unknown block), the plugin generates a platform
of a configurable radius beneath the player so they have a safe surface to land on. Any blocks
occupying the space above the platform — up to a configurable height — are broken naturally,
clearing the landing column. Because natural block-breaking can affect bedrock at the world
boundary, it is strongly recommended to constrain the region's placement Y-range so teleport
destinations are never within breaking distance of the bedrock floor or ceiling.

**Governing:** `REQ-RTP-S-001`, `REQ-RTP-F-007`

---

### H-002 — Player Teleported Into a Suffocating Block
**Severity:** Critical

**Description:** A teleport location is selected where the player's head or body would be
inside a solid block, causing suffocation damage.

**Mitigation (primary):** The vertical adjustor scans upward from the candidate surface to
confirm at least two air blocks exist at the target Y-level before the location is accepted
into the queue.

**Mitigation (secondary):** Same platform-generation and block-clearing fallback as H-001 (secondary); see H-001 for full details and the bedrock Y-range operator warning.

**Governing:** `REQ-RTP-S-001`, `REQ-RTP-F-007`

---

### H-003 — Player Teleported Into a Protected or Claimed Region
**Severity:** High

**Description:** A teleport location falls inside a land-protection claim, faction territory,
or world-guard region where the player has no build/access rights, causing grief potential or
trapping the player.

**Mitigation:** The claim-check layer (populated via `rtp-api` addon integrations) validates
candidate locations against registered protection plugins before they are accepted into the
queue. See `REQ-RTP-F-011`.

**Governing:** `REQ-RTP-S-003`, `REQ-RTP-F-011`

---

## Server Stability Hazards

### H-004 — Chunk Permanently Force-Loaded (Memory Leak)
**Severity:** High

**Description:** A chunk is marked force-loaded to support teleport validation but is never
released — for example because the plugin crashes, the task is abandoned, or an exception
escapes the reservation scope. Over time, accumulated force-loaded chunks cause heap and
world-save bloat requiring a server restart that does not fully resolve the issue because
the chunks are re-force-loaded on startup.

**Mitigation (primary):** `ChunkReservation` implements `AutoCloseable`; `try-with-resources`
guarantees ticket release even when an exception is thrown mid-validation.

**Mitigation (secondary):** `MemoryTracker` actively monitors in-flight reservations. Any
reservation that exceeds its configured window is forcibly closed and the chunk coordinates
are logged at ERROR level for operator review.

**Mitigation (tertiary):** `WeakReference` semantics allow the JVM to deallocate un-ticketed
reservation objects that go out of scope without an explicit close.

**Governing:** `REQ-RTP-S-002`, `REQ-RTP-NF-002`, ADR-008

---

### H-005 — Teleport Request Flood / Queue Exhaustion
**Severity:** High

**Description:** A burst of simultaneous teleport requests (e.g. a coordinated player flood or
automated script) exhausts the pre-generated queue faster than the replenishment task can
refill it. Subsequent requests find an empty queue and either fail silently or block.

**Mitigation:** Per-user isolated queues (see ADR-007) ensure that permissioned players
(operators, VIPs) are served from a private queue unaffected by the global pool. The global
queue size and replenishment rate are configurable in `performance.yml`. All failures are
logged and the player receives an explicit message (`REQ-RTP-S-004`).

**Governing:** `REQ-RTP-S-004`, ADR-006, ADR-007

---

### H-006 — Main Thread Blocked by Chunk I/O
**Severity:** High

**Description:** Chunk loading or validation is performed synchronously on the main server
thread, causing tick-rate degradation (TPS drop) proportional to the number of concurrent
teleport requests.

**Mitigation:** All chunk loading and location validation runs asynchronously via
platform-appropriate schedulers (`TaskPipe` implementations). The Spigot adapter uses a
bounded synchronous fallback only for final teleport dispatch, not for validation.

**Governing:** `REQ-RTP-S-005`, `REQ-RTP-F-008`, ADR-004

---

### H-007 — Platform API Mismatch at Runtime
**Severity:** High

**Description:** The plugin loads on a server version whose NMS (net.minecraft.server) or
Paper/Folia API differs from the compiled adapter, causing `ClassNotFoundException`,
`NoSuchMethodError`, or undefined behaviour at runtime.

**Mitigation:** Platform adapters are compiled as separate versioned submodules
(`rtp-bukkit-v1_21_R1`, `rtp-paper-v26_1_R1`, etc.) so each binary is valid for exactly
one API surface. There is no runtime reflection or version detection that can silently
fall back to broken behaviour.

**Governing:** `REQ-RTP-SYS-002`, ADR-010

---

### H-008 — Database Corruption on Unclean Shutdown
**Severity:** Medium

**Description:** The spatial memory database (H2/SQLite) is not flushed cleanly on server
crash or forced kill, leaving the bad-sector map in a partially written state. On next startup
the map may be unreadable, requiring a full fill rebuild.

**Mitigation:** H2 and SQLite provide ACID write-ahead logging. An unclean shutdown leaves the
database in a recoverable state; on next open H2 replays the write-ahead log automatically.
If the database is unreadable, the plugin logs a WARN-level message and rebuilds the map from
scratch (at the cost of a fill operation).

**Governing:** `REQ-RTP-NF-001`, ADR-002

---

## API Hazards

### H-009 — Addon Calls `rtp-api` Before Core Is Loaded
**Severity:** Medium

**Description:** An addon plugin calls `RTPAPI.addShape()` or `RTPAPI.addVerticalAdjustor()`
before `rtp-core` has registered its delegate, resulting in an `IllegalStateException` that
disables the addon without a clear operator-facing explanation.

**Mitigation:** `RTPAPI` throws `IllegalStateException` with an explicit message
(`[RTP API] Cannot add shape: Core implementation is not loaded.`) rather than a
`NullPointerException`. Addon developers are directed to call `rtp-api` methods inside their
own `onEnable` after declaring RTP as a `depend` (not `softdepend`) in `plugin.yml`.

**Governing:** `REQ-RTP-S-006`, `REQ-RTP-F-010`, ADR-011

---

### H-010 — `ChunkReservation` Constructed by Addon (Unmanaged Ticket)
**Severity:** Medium

**Description:** An addon directly constructs a `ChunkReservation`, taking ownership of a
chunk ticket outside the managed lifecycle. The ticket may never be released because
`MemoryTracker` only monitors reservations it created.

**Mitigation:** `ChunkReservation` is documented as an internal API class not intended to be
constructed by addon code (see ADR-012). Addon developers should request locations through
`ILocationGenerator` and consume `GenerationResult.reservation()` rather than managing
tickets directly.

**Governing:** `REQ-RTP-S-002`, ADR-012

---

## Hazard Summary

| ID | Hazard | Severity | Status |
|----|--------|----------|--------|
| H-001 | Teleport into lethal block | Critical | Mitigated |
| H-002 | Teleport into suffocating block | Critical | Mitigated |
| H-003 | Teleport into protected region | High | Mitigated (addon required) |
| H-004 | Chunk permanently force-loaded | High | Mitigated |
| H-005 | Teleport flood / queue exhaustion | High | Mitigated |
| H-006 | Main thread blocked by chunk I/O | High | Mitigated |
| H-007 | Platform API mismatch at runtime | High | Mitigated |
| H-008 | Database corruption on unclean shutdown | Medium | Mitigated |
| H-009 | Addon calls API before Core is loaded | Medium | Mitigated |
| H-010 | `ChunkReservation` constructed by addon | Medium | Mitigated |

---

## Failure Modes

Failure modes describe specific component-level failures, how they are detected, and the system's defined response.

| ID | Component | Failure | Response | Req |
|----|-----------|---------|----------|-----|
| FM-001 | `RegionQueueManager` | Queue empty | Queue player UUID for deferred teleport; fulfilled on next replenishment. | `REQ-RTP-S-004` |
| FM-002 | `MemoryShape` | All sectors bad | Log ERROR; operator must reconfigure or reset scan. | `REQ-RTP-S-004` |
| FM-003 | Safety check | Location unsafe at dispatch | Discard location, mark bad in memory, serve next in queue. | `REQ-RTP-S-001/003` |
| FM-004 | `MemoryTracker` | Pipeline timeout | Force cancel and cleanup; release chunk tickets; log SEVERE. | `REQ-RTP-S-002` |
| FM-005 | Platform adapter | Chunk load timeout | Handled by FM-004; retry location in next scan cycle. | `REQ-RTP-S-002`, `REQ-RTP-F-008` |
| FM-006 | DB Accessor | Database unreadable | Log WARN; initialize empty memory; rebuild via new scan. | `REQ-RTP-NF-001` |
| FM-007 | Config loader | Malformed config | Log ERROR; skip affected region; others continue. | `REQ-RTP-S-004` |
| FM-008 | Platform adapter | API linkage failure | Plugin disabled by Bukkit; operator must fix JAR version. | `REQ-RTP-SYS-002` |
| FM-010 | `RTPAPI` | Early API call | Throw `IllegalStateException` with clear explanation. | `REQ-RTP-S-006` |
