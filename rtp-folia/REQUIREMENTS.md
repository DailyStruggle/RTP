# RTP Folia Adapter Requirements

This document outlines the strict requirements for the `rtp-folia` module. Folia's region-based multithreading necessitates a fundamentally different concurrency model compared to standard Spigot or Paper environments.

## 1. Functional Requirements

### 1.1 Region-Based Multithreading Compliance
- **Thread Isolation:** The adapter must ensure all teleportations, chunk loads, and entity manipulations execute exclusively on the correct regional thread associated with the target location.
- **Task Scheduling:** The module must utilize Folia's `RegionScheduler`, `GlobalRegionScheduler`, and `EntityScheduler` to dispatch synchronous tasks. The standard Bukkit `BukkitScheduler` must not be used for regional operations.

### 1.2 Data Integrity and Safety
- **State Mutation:** Concurrent state mutations must be strictly managed to guarantee data integrity across disparate region threads.
- **Cross-Region Operations:** Teleportation between different Folia regions (e.g., cross-world or long-distance) must be safely pipelined to prevent thread violations, state corruption, or race conditions.