# LeafRTPGroupAddon Requirements

Requirements for the multi-entity group and subspace teleport addon. These extend, and do not override, the shared constraints in [`../REQUIREMENTS.md`](../REQUIREMENTS.md).

## 1. Scope

### 1.1
The addon shall teleport multiple players in a single coordinated operation across configurable placement profiles, including cooperative party clustering, 1v1 PvP duels, squad skirmishes, and target pursuit.

### 1.2
The addon shall perform relative candidate selection within a local subspace centered at a pre-resolved anchor point.

### 1.3
The addon shall capture chunk-granularity spatial memory (bad-location data) directly from the parent `Region` rather than initiating un-cached live search loops.

## 2. Selection & Capacity Invariant

### 2.1
The addon shall bound the subspace to an `NxN` chunk footprint (`subspaceChunkRadius`) and select candidates in two stages: a chunk-granularity Stage 1 pre-filter that discards known-bad chunks from the inherited `MemoryShape`, followed by a block-granularity Stage 2 bin that screens columns within the surviving chunks for a standable landing block.

### 2.2
Capacity shall be measured against the count of block-validated Stage 2 slots, not chunk-granularity memory bits. Locations with insufficient block-validated candidates to satisfy the profile's participant count, separation distance (blocks), and elevation tolerance (blocks) shall be denied fail-closed.

### 2.3
Denied subspace selections shall emit structured telemetry (S-004 audited) without silently dropping any participant or forcing unsafe placements (S-001).

## 3. Safety & Isolation

### 3.1
Every participant destination shall satisfy the full safety contract (S-001 through S-005) of a solo teleport; co-location or proximity constraints shall never bypass safety checks.

### 3.2
The addon shall not perform synchronous chunk I/O on the main thread (S-005) and shall execute all subspace evaluation off-tick.

### 3.3
The failure of one participant's teleport shall not corrupt the core teleport queue or the state of other participants.

## 4. Configuration & Lifecycle

### 4.1
All profile parameters (subspace chunk radius, block sampling step, min separation, elevation tolerance, max group size, distribution) shall be configured as independent `.yml` definitions under `definitions/groups/*.yml` and hot-reloaded on `/rtp reload` via `MultiConfigParser`.

### 4.2
When disabled by configuration, the addon shall register no listeners or commands and remain a strict no-op.

### 4.3
On unload, all scheduled subspace tasks and coordinate reservations shall be cancelled and released.

## 5. Platform Neutrality

### 5.1
The addon shall remain platform-neutral and dispatch teleports solely through `rtp-api` and `rtp-core` scheduler and server accessor SPIs.
