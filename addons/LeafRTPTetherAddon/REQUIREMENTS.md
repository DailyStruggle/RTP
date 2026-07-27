# LeafRTPTetherAddon Requirements

Requirements for the tether (region-confinement) addon. These extend, and do not override, the shared
constraints in [`../REQUIREMENTS.md`](../REQUIREMENTS.md).

A "tether" confines a player to an existing RTP region. "Zone" is an informal synonym used by other
plugins; this addon reuses an RTP region as the bounded area rather than defining a parallel area
model.

## 1. Scope

### 1.1
The addon shall keep a tethered player within the bounds of the RTP region the player is tethered to.

### 1.2
The addon shall determine tether membership from the core's teleport events, arming a tether when a
player is teleported into a tethered region and disarming it when a subsequent teleport removes the
player from that region or when the tether is explicitly released.

### 1.3
The addon shall not introduce a parallel bounded-area model; a tether shall reference an existing RTP
region as its area.

### 1.4
The addon shall determine whether a coordinate lies within a region using the region's own geometry,
without loading chunks.

## 2. Enforcement

### 2.1
When a tethered player crosses the boundary of the tethered region, the addon shall return the player
to a destination inside the region.

### 2.2
A return destination shall satisfy the same safety guarantees as a solo teleport (S-001 through
S-005); the addon shall not return a player to an unverified destination.

### 2.3
The addon shall enforce a tether by relocating the player, and shall not rely on vetoing player
movement, so that enforcement behaves identically across platforms.

### 2.4
The addon shall observe player movement only for tethered players, so that the cost of enforcement is
bounded by the number of tethered players rather than the total player count.

### 2.5
A failure to return a player shall not be silently discarded (S-004); the outcome of an enforcement
action shall be reported.

## 3. Safety and Isolation

### 3.1
The addon shall not perform chunk I/O on a tick or region thread and shall not block such a thread
while awaiting a return destination.

### 3.2
The addon shall execute in isolation such that an error enforcing one player's tether does not fail
the core teleport queue or the enforcement of other players' tethers.

## 4. Persistence and Lifecycle

### 4.1
The addon shall be able to persist active tethers through the core database interface so that a tether
survives a server restart or a player relog, and shall re-arm persisted tethers when a player becomes
available again.

### 4.2
All operator-facing options shall be configurable and shall be re-read on reload without a restart.

### 4.3
When disabled by configuration, the addon shall register no behavior and shall act as a no-op.

### 4.4
On unload, the addon shall stop observing movement, cancel any scheduled enforcement work, and flush
or close any tether state so that it can be added or removed without leaving the core in a partial
state.

## 5. Platform Neutrality

### 5.1
The addon shall remain platform-neutral and shall not depend on any single platform's API or on any
third-party region/claim plugin; it shall observe movement, test containment, and relocate players
through core abstractions only.

### 5.2
Where an external region/claim source is present, the addon may use it to further constrain a tether
through the shared hook surface (ADR-026), but shall not require one.
