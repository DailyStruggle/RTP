# Multi-Platform Support Roadmap

This document outlines the comprehensive plan for transitioning RTP to a multi-platform project, starting with Fabric and potentially expanding to Forge in the future.

## Phase 1: Infrastructure & Core Consolidation (COMPLETED/IN-PROGRESS)

- [x] **Consolidate APIs**: Pull `CommandsAPI` and `EffectsAPI` back into the main repository as sub-modules.
- [x] **Refactor Dependencies**: Update `rtp-core` and `rtp-plugin` to use local project dependencies for APIs.
- [x] **Initial Fabric Skeleton**: Create `rtp-fabric` module with basic server, player, and world wrappers.
- [ ] **Fix Fabric Build System**: Resolve Minecraft dependency resolution issues in Gradle by correctly integrating Fabric Loom.

## Phase 2: Fabric Feature Parity

The goal of this phase is to ensure the Fabric version has all the essential features available in the Bukkit/Paper/Folia versions.

### 1. Database & Persistence
- [ ] **Fabric Database Handler**: Implement a database handler for Fabric to manage player data (teleport history, cooldowns) using the same SQLite/H2 backend as the core.
- [ ] **Config Migration**: Ensure configuration files are correctly located in the Fabric `config` directory.

### 2. Permissions & Integration
- [ ] **LuckPerms-Fabric Integration**: Implement a bridge for permissions, primarily targeting LuckPerms-Fabric as the industry standard.
- [ ] **PlaceholderAPI Alternative**: Investigate and implement support for a Fabric-native placeholder system (e.g., PlaceholderAPI for Fabric).

### 3. Event Mapping
- [ ] **Complete Lifecycle Events**: Map all critical events (PlayerJoin, PlayerQuit, WorldLoad, WorldUnload, Teleport) to Fabric's event hooks.
- [ ] **Cancelable Events**: Ensure that RTP can correctly intercept and cancel teleports when necessary.

### 4. Asynchronous Chunk Loading
- [ ] **Native NMS/API Implementation**: Implement efficient asynchronous chunk loading for Fabric to maintain the "instant teleport" performance RTP is known for.

## Phase 3: Command System Refinement

- [ ] **Brigadier Bridge**: Fully integrate the `CommandsAPI` with Fabric's Brigadier system.
- [ ] **Tab Completion**: Implement advanced tab completion that mirrors the Bukkit experience but leverages Brigadier's client-side capabilities.
- [ ] **Command Feedback**: Ensure all RTP messages and command feedback are correctly sent to Fabric players/console.

## Phase 4: Stabilization & Testing

- [ ] **Fabric Test Suite**: Adapt existing unit and integration tests to run in a Fabric environment.
- [ ] **Memory Leak Audit**: Perform a thorough audit for memory leaks, specifically targeting the new Fabric-specific wrappers.
- [ ] **Concurrency Verification**: Ensure that the region-based task scheduling is safe on Fabric's threading model.

## Phase 5: Documentation & Release

- [ ] **Admin Documentation**: Update the `docs/admin` files to include Fabric-specific installation and configuration instructions.
- [ ] **Developer Documentation**: Update `docs/dev` to reflect the multi-platform architecture and how to contribute to platform-specific modules.
- [ ] **Beta Release**: Release the first public beta of RTP for Fabric.

## Future: Forge Support

- [ ] **Evaluation**: Once Fabric is stable, evaluate the effort to add a `rtp-forge` module using the established abstraction patterns.
- [ ] **Architectury?**: Re-evaluate if moving to a common abstraction layer like Architectury is beneficial for long-term maintenance of both Fabric and Forge versions.
