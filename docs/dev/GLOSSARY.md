# Glossary

This glossary defines domain-specific terms used throughout the RTP codebase and documentation. Any contributor or addon developer should be able to read the requirements, architecture, and design documents using only this reference — without needing to read source code first.

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
| **Adapter** | Generic design-pattern adapter | A *Platform Adapter module* (`rtp-spigot`, `rtp-paper`, `rtp-folia`, `rtp-fabric`). Refers specifically to module boundary, not the GoF adapter pattern. See *Platform Adapter* entry below. |
| **Task** | Any `Runnable` or scheduled work | A `TeleportPipelineTask` — a stateful object that owns a `ChunkReservation` and must be registered with `MemoryTracker`. See *TeleportPipelineTask* entry (DESIGN.md). |
| **Scan** | A generic word meaning "to examine sequentially" | The *administrative world-scan lifecycle* (`/rtp scan start`/`pause`/`resume`/`reset`/`cancel`) that pre-populates a region's spatial memory without teleporting any player. Supersedes the legacy term *Fill*. See *Scan Task* and *Scan Lifecycle* entries below. |

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
The `BrigadierCommandAdapter` inside `commands-api` that converts the shared `commands-api` command tree into Brigadier nodes for Minecraft platforms (notably Fabric) that dispatch through Brigadier natively. Platform adapters (e.g., `rtp-fabric`) are thin registration shims that delegate to this adapter. Brigadier is a `compileOnly` dependency and is not loaded on Bukkit platforms. See ADR-014.

**Bundle Plugin**
The `rtp-plugin` module — a dedicated bridge module that combines `rtp-core` logic, a `JavaPlugin` entry point, and the active platform adapter into the final shaded distribution JAR. It is the only module permitted to depend simultaneously on `rtp-core` and Bukkit-family server classes. See ADR-003.

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

## P

**Paper**
A high-performance fork of Spigot that provides additional async APIs (e.g., `getChunkAtAsync`). RTP's `rtp-paper` adapter uses these APIs for more efficient chunk pre-loading.

**Pipeline**
The ordered sequence of steps a candidate location passes through: (1) geometric sampling, (2) chunk loading, (3) surface/Y adjustment, (4) biome/claim validation, (5) queue insertion. Each step may reject the candidate.

**Platform Adapter**
A module (`rtp-spigot`, `rtp-paper`, `rtp-folia`) that implements platform-specific APIs while delegating all core logic to `rtp-core`. Adapters must not contain business logic.

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
The abstraction over platform-specific task scheduling. Implementations live in platform adapter modules (`rtp-spigot`, `rtp-paper`, `rtp-folia`) and must never reside in `rtp-core`.

**RTPWorld**
A platform-agnostic wrapper around a world reference. Defined in `rtp-api`.

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
The baseline Bukkit-derived server software. RTP's `rtp-spigot` adapter targets Spigot and serves as the fallback for servers not running Paper or Folia.

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
