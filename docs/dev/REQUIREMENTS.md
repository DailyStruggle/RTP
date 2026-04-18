# RTP Requirements Overview

**Current Plugin Version:** `3.0.0-beta`

This document outlines the high-level functional and non-functional requirements for the RTP (Random Teleport) plugin. These requirements guide the system's architecture, ensuring it meets strict performance, safety, and reliability standards.

For actors and their goals, see [STAKEHOLDERS.md](STAKEHOLDERS.md).
For term definitions, see [GLOSSARY.md](GLOSSARY.md).
For specific code-level and platform-specific requirements, please refer to the individual module specifications:
- [rtp-api Requirements](../../rtp-api/REQUIREMENTS.md)
- [rtp-core Requirements](../../rtp-core/REQUIREMENTS.md)
- [rtp-spigot Requirements](../../rtp-spigot/REQUIREMENTS.md)
- [rtp-paper Requirements](../../rtp-paper/REQUIREMENTS.md)
- [rtp-folia Requirements](../../rtp-folia/REQUIREMENTS.md)

## 0. Scope

### In Scope
- Random teleportation of players to pre-validated locations within configurable geometric regions.
- Pre-generation and queuing of safe locations to guarantee sub-tick response times.
- Per-region configuration of shapes, statistical distributions, biome filters, and permission nodes.
- Integration hooks for third-party land-protection plugins (GriefPrevention, WorldGuard, Towny) and economy plugins (Vault).
- A stable, versioned public API (`rtp-api`) for addon developers to register custom shapes, vertical adjustors, and validation checks.
- Platform adapters for Spigot, Paper, and Folia ensuring correct thread-safety on each server type.

### Out of Scope
- **World generation:** RTP does not generate or modify terrain. It selects locations within existing worlds only.
- **Economy management:** RTP does not implement an economy system. It delegates cost checks to Vault if present.
- **Anti-cheat:** RTP does not detect or prevent cheating. It is the responsibility of the server operator to configure compatible anti-cheat plugins.
- **GUI / inventory menus:** RTP does not provide a graphical interface. All interaction is command- and config-based.
- **Cross-server teleportation:** RTP operates within a single server instance. BungeeCord/Velocity network teleportation is out of scope.
- **Non-Bukkit platforms:** RTP targets Bukkit-derived software only (Spigot, Paper, Folia). Forge, Fabric, and other mod loaders are not supported.

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

## 2. Non-Functional Requirements

### 2.1 Fault Tolerance
- **REQ-RTP-NF-001 — State Persistence:** The system shall maintain algorithmic efficiency across server restarts without rebuilding state from scratch.

### 2.2 Platform Compatibility
- **REQ-RTP-NF-002 — Cross-Platform Thread Safety:** The concurrency model shall be adaptable to the underlying server platform (Spigot, Paper, Folia), ensuring strict thread safety and adherence to platform-specific asynchronous APIs or region-based multithreading constraints.

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

- **REQ-RTP-S-005 — No Synchronous Chunk I/O on the Main Thread:** The system shall not perform
  chunk loading or validation on the main server thread. All such operations shall be dispatched
  through the platform-appropriate async scheduler.

- **REQ-RTP-S-006 — No Undefined Behaviour on Early API Access:** The system shall not produce
  a `NullPointerException` or undefined state when an addon calls `rtp-api` before `rtp-core`
  has finished loading. An `IllegalStateException` with a descriptive message shall be thrown
  instead.

## 4. System Requirements
- **REQ-RTP-SYS-001 — Runtime Environment:** The system shall require Java 21 or higher.
- **REQ-RTP-SYS-002 — Server Software:** The system shall be compatible with Bukkit-derived server software, specifically Spigot, Paper, and Folia implementations.
