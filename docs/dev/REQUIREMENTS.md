# RTP Requirements Overview

**Current Plugin Version:** `3.0.0-beta.1`

This document outlines the high-level functional and non-functional requirements for the RTP (Random Teleport) plugin. These requirements guide the system's architecture, ensuring it meets strict performance, safety, and reliability standards.

For actors and their goals, see [STAKEHOLDERS.md](STAKEHOLDERS.md).
For term definitions, see [GLOSSARY.md](GLOSSARY.md).
For specific code-level and platform-specific requirements, please refer to the individual module specifications:
- [rtp-api Requirements](../../rtp-api/REQUIREMENTS.md)
- [rtp-core Requirements](../../rtp-core/REQUIREMENTS.md)
- [rtp-bukkit Requirements](../../rtp-bukkit/REQUIREMENTS.md)
- [rtp-paper Requirements](../../rtp-paper/REQUIREMENTS.md)
- [rtp-folia Requirements](../../rtp-folia/REQUIREMENTS.md)
- [rtp-fabric Requirements](../../rtp-fabric/REQUIREMENTS.md)

## 0. Scope

### In Scope
- Random teleportation of players to pre-validated locations within configurable geometric regions.
- Pre-generation and queuing of safe locations to guarantee sub-tick response times.
- Per-region configuration of shapes, statistical distributions, biome filters, and permission nodes.
- Integration hooks for third-party land-protection plugins (GriefPrevention, WorldGuard, Towny) and economy plugins (Vault).
- A stable, versioned public API (`rtp-api`) for addon developers to register custom shapes, vertical adjustors, and validation checks.
- Platform adapters for Spigot, Paper, Folia, Fabric, and NeoForge ensuring correct thread-safety on each server type. NeoForge support is deferred until the Fabric adapter stabilizes; see [ADR-033](../adr/ADR-033-neoforge-platform-in-scope.md) and [MULTI_PLATFORM_PLAN.md](MULTI_PLATFORM_PLAN.md).

### Out of Scope
- **World generation:** RTP does not generate or modify terrain. It selects locations within existing worlds only.
- **Economy management:** RTP does not implement an economy system. It delegates cost checks to Vault if present.
- **Anti-cheat:** RTP does not detect or prevent cheating. It is the responsibility of the server operator to configure compatible anti-cheat plugins.
- **GUI / inventory menus:** RTP does not provide a graphical interface. All interaction is command- and config-based.
- **Cross-server teleportation:** RTP operates within a single server instance. BungeeCord/Velocity network teleportation is out of scope.
- **Unsupported platforms:** RTP shall target Bukkit-derived software (Spigot, Paper, Folia), Fabric, and NeoForge. Legacy Forge, Sponge, Minestom, hybrid servers (Mohist, Magma, Arclight), and Bedrock-native servers (PocketMine-MP, Nukkit, BDS) shall not be supported. Rationale and the phased adapter plans are recorded in [rtp-fabric-ADR-002](../../rtp-fabric/docs/adr/rtp-fabric-ADR-002-platform-in-scope.md), [ADR-033](../adr/ADR-033-neoforge-platform-in-scope.md), and [MULTI_PLATFORM_PLAN.md](MULTI_PLATFORM_PLAN.md).
- **Legacy Minecraft and Java versions:** RTP targets Java 21+ (REQ-RTP-SYS-001) and the Minecraft versions enumerated by the shipped versioned platform adapter submodules (ADR-010). Older Minecraft versions and older Java runtimes are out of scope; users on legacy servers shall be directed to the last RTP release that supported their server. Revisit conditions and rationale are recorded in [ADR-021](../adr/ADR-021-legacy-mc-and-java-support-scope.md).

## 1. Functional Requirements

### 1.1 Core Teleportation
- **REQ-RTP-F-001 — Instant Execution:** The system shall provide response times at 0-2 gameticks (0-100ms) on average upon command execution, or postpone teleportation to maintain the backend rhythm.
- **REQ-RTP-F-002 — Configurable Geometry:** The system shall support various spatial boundaries, including but not limited to circles, squares, and rectangles.
- **REQ-RTP-F-003 — Statistical Distributions:** The system shall allow server administrators to configure the mathematical distribution of teleport locations (e.g., Flat, Normal, Exponential).
- **REQ-RTP-F-004 — Region Management:** The system shall allow the server to be divided into multiple teleport regions, each with independent configurations, rules, and permissions.
- **REQ-RTP-F-012 — Administrative World-Scan Lifecycle:** The system shall expose a world-scan lifecycle (`start`, `pause`, `resume`, `reset`, `cancel`) that allows operators to pre-populate a region's spatial memory without teleporting players.

### 1.2 Computational Safety and Performance
- **REQ-RTP-F-005 — Bounded Selection Complexity:** Location selection tasks shall operate within deterministic time complexity bounds (e.g., O(log(n))), ensuring predictable computational overhead during background generation.
- **REQ-RTP-F-006 — Strict Execution Time Guarantees:** Location generation algorithms shall be strictly bounded in execution time. Naive "rerolling" upon hitting invalid regions shall not be used.
- **REQ-RTP-F-007 — Algorithmic Uniformity:** The system shall ensure uniform spatial distribution and preemptively subtract known invalid sectors.

### 1.3 Resource Management
- **REQ-RTP-F-008 — Non-Blocking Execution:** Location discovery and chunk validation shall not block the main server thread.
- **REQ-RTP-F-009 — Redundant Calculation Elimination:** Redundant calculations during future teleport selections shall be eliminated.

### 1.4 Integrations and Extensibility
- **REQ-RTP-F-010 — API Access:** The system shall expose a robust, decoupled API (`rtp-api`) allowing external plugins to register custom shapes, vertical adjustors, and validation checks.
- **REQ-RTP-F-011 — Claim and Protection Checks:** The system shall support integrations with third-party land protection plugins (e.g., GriefPrevention, WorldGuard) to prevent players from teleporting into claimed or restricted areas.

### 1.5 Configuration
- **REQ-RTP-F-013 — Configurable User Messages:** The system shall allow all user-facing messages to be configurable via the `messages.yml` configuration file.

### 1.6 Network / Proxy Support

These requirements establish the overarching contract for operating RTP across multiple backend servers connected through a proxy. They are deliberately implementation-agnostic: transport choice, persistence schema, routing algorithms, reservation semantics, and proxy-vendor specifics (Velocity, BungeeCord, Waterfall) are design-level concerns and shall be specified in the relevant ADR, the [Multi-Server Plan](MULTI_SERVER_PLAN.md), and forthcoming proxy-adapter module requirements.

- **REQ-RTP-NET-001 — Optional Network Mode:** The system shall provide a network mode that coordinates teleportation across multiple backend servers connected through a proxy. Network mode shall be disabled by default.

- **REQ-RTP-NET-002 — Behavioural Parity When Disabled:** When network mode is disabled, the system's externally observable behaviour shall be indistinguishable from a single-server deployment.

- **REQ-RTP-NET-003 — Single Distribution Artifact:** The system shall ship a single distribution artifact that activates the appropriate role — backend or proxy coordinator — based on the host runtime, without requiring operators to select between separate proxy and backend builds.

- **REQ-RTP-NET-004 — Safety Preservation Across the Network:** A network-mediated teleport shall preserve every prohibition in §3 end-to-end. No prohibition shall be weakened, deferred, or transferred to the proxy.

- **REQ-RTP-NET-005 — Authoritative World State on Backends:** The proxy shall not own world state, region definitions, or chunk data. Location validation, chunk handling, and safety checks shall execute on a backend server.

- **REQ-RTP-NET-006 — Configurable Network Messaging:** All user-facing messages produced during network-mediated teleportation, including failure, queueing, and routing messages, shall be configurable through the message-configuration mechanism defined by REQ-RTP-F-013.

- **REQ-RTP-NET-007 — Non-Blocking Network I/O:** Network-mode communication, persistence, and coordination shall not perform blocking I/O on a server tick thread, a region thread, or a proxy event-loop thread. This requirement extends REQ-RTP-F-008 and REQ-RTP-S-005 to the network surface.

- **REQ-RTP-NET-008 — Cross-Network Fairness:** When network mode is enabled, the system shall preserve the fairness contract of single-server queueing. A player whose request cannot be served immediately shall be enrolled in a deterministic wait order, and bypass permissions defined for single-server operation shall retain equivalent semantics across the network.

- **REQ-RTP-NET-009 — Authenticated, Versioned Inter-Server Data Relay:** The system shall relay teleport-coordination data between participating servers — backend-to-proxy, proxy-to-backend, and where required backend-to-backend — through a defined relay channel. The relay channel shall carry a protocol version identifier and shall authenticate every message; messages that fail version negotiation or authentication shall be rejected and audited under REQ-RTP-S-004.

- **REQ-RTP-NET-010 — Proxy Load-Balancing Policy:** The proxy coordinator shall offer operator-configurable load-balancing of teleport requests across participating backend servers. The available policies shall include at minimum a default round-robin or least-loaded strategy and shall allow operators to disable load-balancing in favour of a fixed routing rule.

- **REQ-RTP-NET-011 — Reservation Token Deterministic Expiry:** Every cross-network teleport reservation shall carry a deterministic expiry. A reservation that is not consumed within its declared lifetime shall be released, and the resources it earmarked shall be returned to their originating buffer. No allocation tracked through resource bookkeeping shall remain attributable to an expired reservation.

- **REQ-RTP-NET-012 — Exactly-Once Reservation Claim:** A cross-network reservation shall be consumed at most once. A second attempt to consume an already-consumed reservation shall be refused and audited under REQ-RTP-S-004; no player shall be teleported as a result of a duplicate consumption.

- **REQ-RTP-NET-013 — Multi-Flavour Persistence Compatibility:** Backend telemetry, reservation state, and any other network-mode persistence shall be writable to every relational database flavour that the system supports for single-server persistence. Operators shall not be required to deploy a dedicated database technology in order to enable network mode.

- **REQ-RTP-NET-014 — Multi-Proxy Concurrency and Reanimation:** The system shall support the concurrent operation of more than one proxy coordinator against a single shared coordination store. No correctness path shall depend on the participation of a specific proxy instance. A reservation claimed by a proxy that subsequently becomes unreachable shall, within a bounded interval, become claimable by another proxy without operator intervention.

- **REQ-RTP-NET-015 — Shared Network Waitlist for Cross-Server `/rtp`:** When a cross-server `/rtp` enrolment cannot be dispatched to a qualifying backend at the moment of request, the system shall park the enrolment on a shared network-visible waitlist rather than fail the request. The waitlist shall guarantee at-most-one live entry per player identifier, shall expose the player's one-indexed position to the originating server, and shall accept point-removal of an entry by player identifier without scanning the entire waitlist. While a player has a live waitlist entry, the originating server shall reject subsequent cross-server `/rtp` invocations by that player through the message-configuration mechanism defined by REQ-RTP-F-013. A proxy coordinator shall periodically drain the waitlist in batches whose per-backend size is bounded by that backend's currently available network-reserve coordinate count, and entries that depart the waitlist for any reason other than successful dispatch shall be audited under REQ-RTP-S-004.

### 1.7 Maps / Runtime Cartography

These requirements establish the overarching contract for the runtime cartography chart-generation surface (in-world 2D imagery delivered through the native Minecraft cartography map). Module shape, palette policy, binding lifecycle, and renderer catalogue are design-level concerns and shall be specified in [ADR-046](../adr/ADR-046-maps-api-module.md), in `maps-api/docs/adr/`, and in [`docs/dev/scratch/CHECKLIST-maps-api.md`](scratch/CHECKLIST-maps-api.md) until that checklist is retired.

- **REQ-RTP-MAP-001 — Require-by-Contract Map Binding:** A `MapBinding` implementation shall throw `IllegalStateException` when invoked before RTP core is loaded. The exception message shall identify the missing initialization step so addons can self-diagnose without inspecting RTP internals. This requirement extends REQ-RTP-S-006 to the map-binding surface.

- **REQ-RTP-MAP-002 — Non-Blocking Chart Rendering:** A `ChartRenderer` shall not perform chunk I/O, shall not block on `CompletableFuture.get()` or `CompletableFuture.join()`, and shall not invoke any platform API that performs synchronous chunk loading. This requirement extends REQ-RTP-F-008 and REQ-RTP-S-005 to the chart-rendering surface.

- **REQ-RTP-MAP-003 — Map Binding Lifecycle Accounting:** A live `MapBinding` shall release every `MemoryTracker` allocation it acquired on cancel, on viewer disconnect, and on plugin disable. No allocation tracked through resource bookkeeping shall remain attributable to a cancelled, disconnected, or shut-down binding.

- **REQ-RTP-MAP-004 — Lite Assembly Surface:** The Lite assembly variant shall ship `NoopMapBinding` as the sole `MapBinding` implementation. The Lite variant shall not expose any platform-backed binding nor any renderer that depends on platform-backed pixel delivery.

- **REQ-RTP-MAP-005 — Mermaid Renderer Subset and Self-Containment:** A `MermaidRenderer` shall accept the documented Mermaid subset (flowchart `LR` / `TD` direction, rectangular / rounded / diamond node shapes, directed labelled edges) and shall rasterize to the active `MapCanvas` palette. The renderer shall not invoke any external process, scripting engine, or third-party graph-layout library at runtime. Mermaid source outside the documented subset shall be rejected at parse time with a message routed through the message-configuration mechanism defined by REQ-RTP-F-013, and the rejection shall be audited under REQ-RTP-S-004.

- **REQ-RTP-MAP-006 — Declarative Chart Composition Bridge:** The system shall expose a renderer-neutral chart specification and a resolver SPI so that admin commands, menu actions, and addons can request a `ChartModel` for any in-memory diagnostic source without coupling to a specific renderer or `MapBinding` implementation. Resolvers shall execute off the main server thread and shall not perform synchronous chunk I/O; this requirement extends REQ-RTP-F-008 and REQ-RTP-S-005 to the chart-composition surface. Dispatch failure paths (missing binding, missing resolver, resolver exception) shall surface through the message-configuration mechanism defined by REQ-RTP-F-013 and shall be audited under REQ-RTP-S-004.

### 1.8 Observability and Metrics

- **REQ-RTP-OBS-001 - Non-Blocking Metrics Snapshot:** The system shall expose a metrics snapshot operation that is non-blocking on every caller thread (main, region, async) and that returns an immutable carrier. The snapshot operation shall not perform chunk I/O, shall not block on `CompletableFuture.get()` or `CompletableFuture.join()`, and shall not synchronize on a server-wide lock. This requirement extends REQ-RTP-F-008.

- **REQ-RTP-OBS-002 - Single-Sample Pipeline Recording:** Each completed teleport pipeline task shall contribute exactly one sample to the pipeline-latency histogram. No exit path, including success, failure, cancellation, and exception, shall record zero samples or more than one sample for a single task instance.

- **REQ-RTP-OBS-003 - Bounded Sampling Cost:** JVM heap, server TPS, and server MSPT sampling shall operate at bounded cost. The system shall not allocate, per snapshot, a data structure whose size depends on server uptime, online player count, or cumulative teleport history.

## 2. Non-Functional Requirements

### 2.1 Fault Tolerance
- **REQ-RTP-NF-001 — State Persistence:** The system shall maintain algorithmic efficiency across server restarts without rebuilding state from scratch.

### 2.2 Platform Compatibility
- **REQ-RTP-NF-002 — Cross-Platform Thread Safety:** The concurrency model shall be adaptable to the underlying server platform (Spigot, Paper, Folia, Fabric, NeoForge), ensuring strict thread safety and adherence to platform-specific asynchronous APIs or region-based multithreading constraints.

### 2.3 Architectural Isolation
- **REQ-RTP-NF-003 — Entry-Point Logic Isolation:** The plugin entry point shall not contain business logic. Database wiring, effects wiring, and server-accessor selection shall be delegated to dedicated handler classes, each with a single responsibility.

## 3. Prohibition Requirements

These requirements describe prohibited behaviours. Each maps to one or more hazards in
[`HAZARDS.md`](../admin/HAZARDS.md).

- **REQ-RTP-S-001 — No Lethal Teleport Destination:** The system shall not teleport a player to
  a location where the landing block or surrounding blocks are lava, fire, magma, void air, or
  any other block designated as unsafe in the active region configuration.

- **REQ-RTP-S-002 — No Persistent Force-Loaded Chunks:** The system shall not leave a chunk in
  a force-loaded state beyond the configured reservation window. Every chunk ticket acquired shall
  be released either by explicit close, by watchdog, or by JVM weak-reference collection.

- **REQ-RTP-S-003 — No Teleport Into Protected Territory:** The system shall not teleport a
  player into a location that a registered claim or protection addon has marked as inaccessible
  to that player.

- **REQ-RTP-S-004 — No Silent Failure:** The system shall not silently discard a teleport
  request. Every failure (empty queue, invalid region, permission denied, safety rejection) shall
  produce a player-visible message and a log entry at WARN level or higher.
  - *Compliance Note:* Malformed command inputs are intentionally tested by `rtp test commands-live`
    to ensure they produce these required warnings. Expect `Level.WARNING` logs during test execution.

- **REQ-RTP-S-005 — No Synchronous Chunk I/O on the Main Thread:** The system shall not perform
  chunk loading or validation on the main server thread. All such operations shall be dispatched
  through the platform-appropriate async scheduler.

- **REQ-RTP-S-006 — No Undefined Behaviour on Early API Access:** The system shall not produce
  a `NullPointerException` or undefined state when an addon calls `rtp-api` before `rtp-core`
  has finished loading. An `IllegalStateException` with a descriptive message shall be thrown
  instead.

## 4. System Requirements
- **REQ-RTP-SYS-001 — Runtime Environment:** The system shall require Java 21 or higher.
- **REQ-RTP-SYS-002 — Server Software:** The system shall be compatible with Bukkit-derived server software (Spigot, Paper, and Folia), with Fabric, and with NeoForge. NeoForge compatibility shall be delivered through a dedicated platform adapter whose activation is gated on the stabilization of the Fabric adapter; rationale is recorded in [ADR-033](../adr/ADR-033-neoforge-platform-in-scope.md).
