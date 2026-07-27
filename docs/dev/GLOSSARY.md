# Glossary

This glossary defines domain-specific terms used throughout the RTP codebase and documentation. Any contributor or addon developer should be able to read the requirements, architecture, and design documents using only this reference — without needing to read source code first.

---

## 👤 Audience Roles / Personas

RTP documentation is written for several distinct audiences. Because these words are used loosely in everyday speech, this section fixes their **RTP-specific** meaning so requirements, ADRs, wiki pages, and `messages.yml` copy can address the right reader unambiguously. A single human can wear more than one hat (a solo server owner is often operator, administrator, and player at once), but the roles describe *responsibilities*, not people.

| Role | RTP-Specific Meaning |
|------|----------------------|
| **Developer** | A programmer who writes or modifies code. Split into two sub-roles when the distinction matters: a **contributor** works inside this repository (`rtp-core`, platform adapters, `commands-api`, etc.) and is bound by the S-00x prohibitions and ADR process; an **addon developer** builds an external plugin against `rtp-api` only (see *Addon*) and never touches core internals. Reads `REQUIREMENTS.md`, `DESIGN.md`, ADRs, and this glossary. |
| **Operator** | The person who installs, configures, and runs the RTP plugin on a live server: edits `config.yml` / `regions.yml` / `messages.yml`, tunes queue depth and safety lists, and reads the wiki and in-game admin messages. Does not write Java. The default audience for shipped config comments and `messages.yml` values (see *Locale Parity Maintenance* in `.junie/AGENTS.md`). Also called *server owner* or *admin* informally, but prefer *operator* for the config/runtime role. |
| **Administrator** | An in-game or console actor holding elevated RTP permissions (e.g. `rtp.scan`, `rtp.other`, `rtp.reload`) who performs privileged runtime actions — starting a world scan, teleporting other players, reloading configuration — via `/rtp` sub-commands. This is a *permission-scoped runtime role*, distinct from the *operator* who edits files on disk, though the same person often fills both. |
| **User** / **Player** | An ordinary player who triggers a teleport with `/rtp` (or an event trigger such as first-join) and holds only baseline permissions. The end recipient of a teleport. "Player" is the concrete Minecraft actor (wrapped as `RTPPlayer`); "user" is the same actor viewed as the consumer of the RTP feature. Never means an API caller — that is a *developer*. |

---

## ⚠ Multipurpose / Overloaded Terms

The words below have common meanings in Java, Minecraft, or software engineering that differ from their **RTP-specific** meanings. Always use the RTP definition when reading or writing code and documentation in this repository.

| Term | Common / Generic Meaning | **RTP-Specific Meaning** |
|------|--------------------------|---------------------------|
| **Region** | Any bounded area of space | A *named, independently configured teleport zone* with its own shape, queue, and permissions. Not a Minecraft world region or a Folia region thread. See *Region* entry below. |
| **Queue** | A generic FIFO data structure | The *per-region buffer of pre-validated teleport locations* ready for instant dequeue. Not a generic task queue. See *Queue* entry below. |
| **Pipeline** | Any multi-stage data flow | The *fixed five-step candidate-location validation sequence* (sample → load → adjust → validate → enqueue). Do not use loosely to mean "process". See *Pipeline* entry below. |
| **Shape** | A visual or geometric figure | A *pluggable spatial boundary and random-sampling algorithm* registered with `rtp-api`. Not just a geometry class. See *Shape* entry below. |
| **World** | A Minecraft `World` object | Always wrapped as `RTPWorld` inside `rtp-core`/`rtp-api`. Never pass a raw Bukkit `World` across module boundaries. See *RTPWorld* entry below. |
| **Ticket** | Generic token or pass | A *Plugin Chunk Ticket* (`world.addPluginChunkTicket`) tracked by `ChunkReservation`. Must be released after validation. See *Plugin Chunk Ticket* entry below. |
| **Adapter** | Generic design-pattern adapter | A *Platform Adapter module* (`rtp-bukkit`, `rtp-paper`, `rtp-folia`, `rtp-fabric`). Refers specifically to module boundary, not the GoF adapter pattern. See *Platform Adapter* entry below. |
| **Task** | Any `Runnable` or scheduled work | A `TeleportPipelineTask` — a stateful object that owns a `ChunkReservation` and must be registered with `MemoryTracker`. See *TeleportPipelineTask* entry (DESIGN.md). |
| **Scan** | A generic word meaning "to examine sequentially" | The *administrative world-scan lifecycle* (`/rtp scan start`/`pause`/`resume`/`reset`/`cancel`) that pre-populates a region's spatial memory without teleporting any player. Supersedes the legacy term *Fill*. See *Scan Task* and *Scan Lifecycle* entries below. |
| **Backend** | Any back-of-house server in generic web terminology | A *Minecraft server instance that hosts world data and runs the RTP teleport pipeline locally*, sitting behind a proxy in network mode. Not interchangeable with "the database" or "the rtp-core module". See *Backend* entry below. |
| **Proxy** | A generic relay or stand-in | A *Velocity, BungeeCord, or Waterfall coordinator* that fronts one or more backends and dispatches `/rtp` requests across the network. Distinct from Bukkit's plugin-message "BungeeCord proxy" channel and from the GoF proxy pattern. See *Proxy* entry below. |
| **Transport** | Any byte-level shipping channel | A *named binding for network-mode coordination state* (`RedisNetworkStateBinding`, `PostgresNetworkStateBinding`, `GenericSqlNetworkStateBinding`, `InMemoryNetworkStateBinding`). Not a Minecraft entity transport, vehicle, or plugin-message channel. See *Transport* entry below. |

---

## A

**Addon**
An external plugin that extends RTP functionality by implementing interfaces from `rtp-api`. Addons are compiled against `rtp-api` only and must not depend on `rtp-core` or any platform adapter module.

**Asynchronous Task**
A unit of work executed off the main server thread. In RTP, location discovery and chunk pre-loading are always asynchronous to avoid stalling the tick thread.

---

## B

**Biome Filter**
A validation check that rejects candidate teleport locations based on their Minecraft biome (e.g., disallowing ocean or nether_wastes). Implemented as a pluggable hook in the selection pipeline.

**Bounded Execution**
A design constraint requiring that all algorithms complete within a deterministic, pre-calculated time or step budget. RTP explicitly forbids unbounded retry loops ("rerolling") in favor of preemptive sector subtraction.

**Brigadier Bridge**
The `BrigadierCommandAdapter` inside `commands-api` that converts the shared `commands-api` command tree into Brigadier nodes for Minecraft platforms (notably Fabric) that dispatch through Brigadier natively. Platform adapters (e.g., `rtp-fabric`) are thin registration shims that delegate to this adapter. Brigadier is a `compileOnly` dependency and is not loaded on Bukkit platforms. See commands-api-ADR-001.

**Bundle Plugin**
The `rtp-plugin` module — a dedicated bridge module that combines `rtp-core` logic, a `JavaPlugin` entry point, and the active platform adapter into the final shaded distribution JAR. It is the only module permitted to depend simultaneously on `rtp-core` and Bukkit-family server classes. See ADR-003.

**Backend**
In network mode, a Minecraft server instance running RTP that hosts world data, owns one or more `Region` configurations, and runs the local teleport pipeline. The proxy never owns world state (REQ-RTP-NET-005); coordinates are always produced on a backend. A network deployment typically has multiple backends fronted by one or more proxies. Identified by `network.serverId` in `network.yml`. See [`MULTI_SERVER_PLAN.md`](MULTI_SERVER_PLAN.md).

**Backend Selector**
The `rtp-core` interface (`BackendSelector`) that, given an `RtpRequest` and a `NetworkSnapshot`, returns the backend that should serve the request. Implementations are **pure functions** of the snapshot — no I/O, no blocking — so the selector is safe to call from any thread and produces deterministic decisions for a given snapshot. The shipped v1 implementation is a configurable weighted average over published telemetry metrics; see *Load-Balancing Heuristics* in [`MULTI_SERVER_PLAN.md`](MULTI_SERVER_PLAN.md). Governed by REQ-RTP-NET-010.

---

## C

**Candidate Location**
A randomly generated (x, y, z) coordinate that has passed geometric validation but has not yet been confirmed safe (chunk loaded, surface found, claim-free). Candidates are held in the pre-generation queue until fully validated.

**ChunkReservation**
An internal record tracking which chunks have been loaded by RTP for pre-generation purposes. Used to ensure chunks are released (ticket removed) after validation completes, preventing memory leaks.

**Claim Check**
A validation step that queries a third-party land-protection plugin (e.g., GriefPrevention, WorldGuard) to determine whether a candidate location falls inside a protected or claimed area.

**CompletableFuture**
A Java concurrency primitive used to represent an asynchronous result. RTP's architectural rules forbid calling `.get()` or `.join()` on a `CompletableFuture` from the core or API packages, as these calls block the calling thread.

---

## D

**Distribution**
The mathematical probability function governing how random coordinates are sampled within a shape's bounds. Supported distributions include:
- **Flat** — uniform probability across the entire region.
- **Normal** — Gaussian bell-curve weighting toward the center.
- **Exponential** — configurable decay rate, producing rings of varying density.

---

## E

**Economy Delegation**
The mechanism by which Folia's region-threaded scheduler dispatches economy (Vault) calls to the correct regional thread. Required because Folia prohibits cross-region API calls from arbitrary threads.

**Entry Point**
The single class per platform that the server's plugin loader instantiates (on Bukkit-family platforms, `RTPBukkitPlugin` extending `JavaPlugin`; on Fabric, `RTPFabric` implementing `ModInitializer`). Entry points are restricted to lifecycle wiring — they shall not contain business logic (REQ-RTP-NF-003). Database wiring, effects wiring, and server-accessor selection are delegated to dedicated handler classes (`BukkitDatabaseHandler`, `BukkitEffectsHandler`, `BukkitServerProvider`).

---

## F

**Folia**
A fork of Paper that implements region-based multithreading, where different areas of the world run on separate threads. RTP's `rtp-folia` adapter handles the additional scheduling constraints this imposes.

**Fill Task** *(legacy alias for Scan Task)*
Historical term for the admin-triggered pre-population task. Renamed to *Scan Task* (see *Scan Task* below); surviving references in external configurations, old documentation, or issue history shall be read as *Scan Task*.

---

## G

**Generation Context**
An immutable snapshot of the parameters (region config, shape, distribution, world) used for a single location generation run. Passed through the pipeline to ensure thread-safe, stateless processing.

---

## I

**ILocationGenerator**
The primary API interface for generating a validated teleport location. Implementations must be non-blocking and thread-safe. External addons may provide custom implementations.

---

## J

**JavaPlugin**
The Bukkit/Spigot entry-point class. RTP's entry point lives in `rtp-plugin` (`RTPBukkitPlugin.java`) and bridges core logic with the active platform adapter.

---

## L

**Land Protection Plugin**
A third-party Minecraft plugin (e.g., GriefPrevention, WorldGuard, Towny) that marks areas of the world as claimed or restricted. RTP integrates with these via the claim-check hook in `rtp-api`.

**Lock-Free Read**
A concurrency pattern where shared data is read without acquiring a mutex, typically using `volatile` fields, `ConcurrentHashMap`, or `EnumMap`. RTP requires lock-free reads on configuration data to avoid synchronization bottlenecks under high teleport load.

---

## M

**Main Thread / Tick Thread**
The single thread on which Bukkit/Spigot processes game logic each tick. Operations on this thread must complete within ~50 ms (20 TPS). RTP must never block this thread with IO or heavy computation.

**MemoryShape**
The in-memory representation of a teleport region's geometric boundary and its pre-computed valid sector map. Persisted across restarts to avoid rebuilding state from scratch.

---

## N

**Network Snapshot**
In network mode, the immutable value object (`NetworkSnapshot`) that aggregates the most recently observed backend telemetry rows readable by the proxy: per-backend availability fields (`pluginState`, `acceptingRequests`, `regionsAvailable[]`, `worldsLoaded[]`) and performance fields (`mspt`, `queueDepth`, `pendingTeleports`, `avgPipelineMs`, `chunkLoadBacklog`, `heapUsedMb`/`heapMaxMb`, `databaseLatencyMs`, `lastSeenEpochMs`). A `BackendSelector` consumes one snapshot per request and uses it for the entire capped-retry chain so retry decisions are internally consistent. Snapshots are *snapshots*, not deltas — the consumer does the math. See *Backend Telemetry Publication* in [`MULTI_SERVER_PLAN.md`](MULTI_SERVER_PLAN.md).

---

## P

**Proxy**
In network mode, a Velocity (primary), BungeeCord, or Waterfall instance that fronts one or more backends, receives `/rtp` requests, runs the `BackendSelector` against the latest `NetworkSnapshot`, claims a reservation token, and commits the player transfer. Proxies do not own world state, do not run the teleport pipeline, and do not talk to each other directly — all cross-proxy coordination flows through the durable shared store (REQ-RTP-NET-014). Multiple concurrent proxies are supported and identified by `network.proxyId` in `network.yml`. Distinct from the Bukkit *plugin-message "BungeeCord" channel* (a legacy backend-side relay) and from the GoF proxy pattern. See *Multi-Proxy Deployment* in [`MULTI_SERVER_PLAN.md`](MULTI_SERVER_PLAN.md).

**Paper**
A high-performance fork of Spigot that provides additional async APIs (e.g., `getChunkAtAsync`). RTP's `rtp-paper` adapter uses these APIs for more efficient chunk pre-loading.

**Pipeline**
The ordered sequence of steps a candidate location passes through: (1) geometric sampling, (2) chunk loading, (3) surface/Y adjustment, (4) biome/claim validation, (5) queue insertion. Each step may reject the candidate.

**Platform Adapter**
A module (`rtp-bukkit`, `rtp-paper`, `rtp-folia`) that implements platform-specific APIs while delegating all core logic to `rtp-core`. Adapters must not contain business logic.

**Plugin Chunk Ticket**
A Bukkit API mechanism (`world.addPluginChunkTicket`) that keeps a chunk loaded without forcing it permanently. RTP uses tickets during validation and removes them immediately after, preventing memory retention.

**Pulse**
A single execution cycle of the RTP background scheduler — one iteration of the fill loop that attempts to generate and validate a batch of candidate locations for queued regions.

---

## Q

**Queue**
A per-region buffer of pre-validated teleport locations. When a player requests a teleport, a location is dequeued instantly (0–2 ticks). The fill task replenishes the queue asynchronously.

**Queue Depth**
The configured maximum number of pre-validated locations held in a region's queue at any time. Tunable per region.

---

## R

**Region**
A named, independently configured teleport zone within a world. Each region has its own shape, distribution, queue, permissions, and validation rules. A single world may contain multiple regions.

**Region Config**
The YAML configuration file for a specific teleport region, defining its shape, bounds, distribution, queue depth, biome filters, and permission nodes.

**Rerolling**
The naive pattern of repeatedly generating random coordinates until a valid one is found. RTP explicitly forbids this in favor of bounded algorithms that preemptively subtract invalid sectors.

**RTPLocation**
A platform-agnostic value object representing a teleport destination (world name, x, y, z, yaw, pitch). The base interface is defined in `rtp-api`; a concrete implementation with queue and reservation support also lives in `rtp-core`. Addon developers should depend only on the `rtp-api` type.

**RTPPlayer**
A platform-agnostic wrapper around a player reference. Defined in `rtp-api` to decouple core logic from Bukkit's `Player` class.

**RTPScheduler**
The abstraction over platform-specific task scheduling. Implementations live in platform adapter modules (`rtp-bukkit`, `rtp-paper`, `rtp-folia`) and must never reside in `rtp-core`.

**RTPWorld**
A platform-agnostic wrapper around a world reference. Defined in `rtp-api`.

**Reservation Token**
In network mode, a single durable row in the network-state member of `AbstractSQLDatabaseAccessor` that earmarks one already-resolved coordinate from a destination backend's `keptLocations` (fallback `unkeptLocations`) buffer as "promised to a cross-network player." Carries `token`, `playerUuid`, `targetServerId`, `worldKey`, `x/y/z/yaw/pitch`, `issuedAt`, `expiresAt`, and a lifecycle `state` ∈ {`PENDING`, `CLAIMED`, `CONSUMED`, `EXPIRED`}. The state machine is row-count atomic: `PENDING → CLAIMED` is owned by the proxy, `CLAIMED → CONSUMED` by the destination's join handler, and the reaper transitions stale rows to `EXPIRED` and releases the underlying buffer entry plus its `MemoryTracker` row. Tokens claimed by a dead proxy are reanimated to `PENDING` after `claimReanimateMs` so a surviving proxy can pick them up. Governed by REQ-RTP-NET-011 (deterministic expiry), REQ-RTP-NET-012 (exactly-once claim), and REQ-RTP-NET-014 (multi-proxy reanimation). See *Reservation Tokens* in [`MULTI_SERVER_PLAN.md`](MULTI_SERVER_PLAN.md).

---

## S

**Scan Lifecycle**
The operator-visible state machine for a world scan: `start` → (`pause` ⇌ `resume`) → `cancel` or `reset`. Exposed via `/rtp scan <subcommand>` (`ScanCmd` tree) and governed by `REQ-RTP-F-012`. Replaces the legacy `/rtp fill` command family.

**Scan Task**
The background task that pre-generates and validates candidate locations for a region, populating its spatial memory without issuing teleports. Runs asynchronously and respects the region's configured queue depth and scan bounds. Implemented by `ScanTask` and driven by `ScanTaskProcessing`. Formerly called *Fill Task*.

**Semantic Versioning (SemVer)**
A versioning scheme (`MAJOR.MINOR.PATCH`) where breaking API changes increment MAJOR, backward-compatible additions increment MINOR, and bug fixes increment PATCH. Required for `rtp-api` releases.

**Shape**
A mathematical geometry (circle, square, rectangle, or custom) that defines the spatial boundary of a teleport region and the algorithm used to sample random points within it. Custom shapes can be registered via `rtp-api`.

**Spigot**
The baseline Bukkit-derived server software. RTP's `rtp-bukkit` adapter targets Spigot and serves as the fallback for servers not running Paper or Folia.

**Surface Adjustor / Vertical Adjustor**
A pluggable component that determines the correct Y coordinate for a candidate (x, z) location — e.g., finding the highest solid block, the lowest cave floor, or a custom elevation. Registered via `rtp-api`.

---

## T

**Tick**
The fundamental unit of Minecraft server time. One tick = 50 ms at 20 TPS. RTP targets 0–2 ticks (0–100 ms) for teleport response time.

**TeleportData**
An internal record bundling the player, destination, and metadata for a single pending or completed teleport operation.

**TeleportPipelineTask**
The stateful object that drives a single player's teleport request through the pipeline. Owns a `ChunkReservation`, must be registered with `MemoryTracker` on creation, and must call `reservation.close()` on every exit path. Lives in `rtp-core`; platform adapters must not subclass it.

**Traceability Matrix**
The document (`TRACEABILITY.md`) linking each requirement ID to its design reference, implementing class(es), and automated test(s).

**Transport**
In network mode, a named binding that implements the `NetworkTransport` SPI by writing the network-state tables (`backend_state`, `proxy_state`, reservation tokens, network cooldowns, network wait queue) to a concrete coordination store. Shipped bindings: `RedisNetworkStateBinding` (Lettuce; also covers RESP-compatible drop-ins such as DragonflyDB and KeyDB), `PostgresNetworkStateBinding` (`LISTEN/NOTIFY` + `SELECT … FOR UPDATE SKIP LOCKED`), `GenericSqlNetworkStateBinding` (MySQL/MariaDB polling), and `InMemoryNetworkStateBinding` (tests, and the no-op default when `network.enabled: false`). The `plugin-message` transport is dev-only and excluded from production (D2). All transports must be non-blocking on tick / region / netty threads (REQ-RTP-NET-007). Not to be confused with the Minecraft entity transport mechanic or with the plugin-message channel itself; here "transport" specifically names the coordination-state binding.

---

## V

**Vault**
A Bukkit economy abstraction plugin. RTP optionally integrates with Vault to charge players for teleports. On Folia, Vault calls must be dispatched to the correct regional thread.

**Vertical Adjustor**
See *Surface Adjustor*.

---

## W

**WorldGuard**
A popular land-protection plugin. RTP supports WorldGuard as a claim-check integration via the `rtp-api` validation hook.
