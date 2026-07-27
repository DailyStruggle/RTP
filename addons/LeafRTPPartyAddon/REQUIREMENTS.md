# LeafRTPPartyAddon Requirements

Requirements for the party/group teleport addon. These extend, and do not override, the shared
constraints in [`../REQUIREMENTS.md`](../REQUIREMENTS.md).

## 1. Scope

### 1.1
The addon shall teleport the members of a single party to a common random destination in one
operation, where a party is a set of players grouped by an external party/party-plugin source or by
an explicit invite accepted at request time.

### 1.2
The addon shall support two placement modes: a shared-coordinate mode in which all members arrive at
one prepared coordinate, and a cluster mode in which each member arrives at a distinct prepared
coordinate drawn adjacent to the others.

### 1.3
The addon shall not introduce a new location-search mechanism; it shall consume destinations already
prepared by the core supply pipeline.

## 2. Supply and Bounding

### 2.1
The addon shall bound the number of destinations a single party operation consumes by a configurable
maximum party size.

### 2.2
The addon shall not exhaust the shared location supply on behalf of one party to the detriment of
concurrent solo requests; when insufficient prepared destinations are available, it shall degrade
gracefully rather than force synchronous generation.

## 3. Safety and Isolation

### 3.1
The addon shall deliver every member to a destination that satisfies the same safety guarantees
applied to a solo teleport (S-001 through S-005), and shall not bypass any safety verification to
co-locate members.

### 3.2
The addon shall not perform chunk I/O on a tick thread and shall not block a region or tick thread
while awaiting a destination.

### 3.3
A failure to place any one member shall not silently drop that member's teleport (S-004); the
outcome of each member's teleport shall be reported.

### 3.4
The addon shall execute in strict isolation such that an error affecting one member does not fail the
core teleport queue or the placement of other members.

## 4. Configuration and Lifecycle

### 4.1
All operator-facing options shall be configurable and shall be re-read on reload without a restart.

### 4.2
When disabled by configuration, the addon shall register no behavior and shall act as a no-op.

### 4.3
On unload, the addon shall release any reserved destinations and cancel any scheduled work so that it
can be added or removed without leaving the core in a partial state.

## 5. Platform Neutrality

### 5.1
The addon shall remain platform-neutral and shall not depend on any single platform's API; it shall
dispatch teleports through the core scheduler and server-accessor abstractions.

### 5.2
Where the core supports network mode, the addon shall be compatible with a party whose members are
distributed across backends, deferring cross-backend coordinate allocation to the core reservation
mechanism.
