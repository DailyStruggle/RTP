# LeafRTPGroupAddon

Multi-entity subspace teleportation via parent region memory capture for LeafRTP.

## Why this fits LeafRTP

Competitor plugins implement separate, ad-hoc search loops for party teleport, PvP duels, squad skirmishes, and pursuit/bounty drops, bypassing caching and incurring high chunk-loading latency.

LeafRTP unifies all multi-player teleportation into a single spatial primitive:
* An **Anchor Location** $(X_0, Z_0)$ drawn from the parent `Region`'s pre-warmed L1/L2 queue (or a live entity/claim target).
* A **Subspace Shape** bounded to a small `NxN` **chunk** footprint that inherits the parent region's chunk-granularity `MemoryShape` as a cheap pre-filter.
* **Two-stage selection:** a chunk-unit pre-filter discards known-bad chunks, then a block-unit bin screens columns inside the survivors for a real standable `Y` (reusing the L3 bin model). Capacity denial is measured against **block-validated** slots (fail-closed, S-004 audited), not chunk bits - so the two units are never conflated.

## Status

Scaffold. This module wires configuration and lifecycle; the core `SubspaceShape` and profile dispatch engine are specified in [`REQUIREMENTS.md`](REQUIREMENTS.md) and [`docs/adr/leafrtp-group-addon-ADR-001-subspace-group-teleport.md`](docs/adr/leafrtp-group-addon-ADR-001-subspace-group-teleport.md).

## Configuration (`definitions/groups/*.yml`)

Each group placement profile is configured as an independent `.yml` file under `definitions/groups/`.

| Key | Default (`default.yml`) | Meaning |
|-----|-------------------------|---------|
| `distribution` | `CLUSTER` | Spatial distribution pattern (`CLUSTER`, `OPPOSING_POLES`, `RING`, `GRID`). |
| `subspaceChunkRadius` | `2` | Footprint half-width in **chunks** (Stage 1). Spans `(2n+1)^2` chunks; `1` = 3x3 chunks = 48x48 blocks. |
| `minSeparation` | `3` | Minimum clearance in **blocks** between any two participants. Also drives the internal Stage 2 sampling stride, so there is no separate `blockStep` key. |
| `elevationTolerance` | `4` | Maximum allowable elevation difference in **blocks** across participants. |
| `maxGroupSize` | `8` | Maximum participants placed per operation. |

### Shipped Presets
* `default.yml`: General-purpose multi-player default.
* `party.yml`: Tight co-op cluster for friendly group teleports.
* `duel.yml`: 1v1 PvP duel placement at opposing poles ($\Delta \theta = 180^\circ$).
* `skirmish.yml`: Squad vs squad team skirmish grid layout.
* `pursuit.yml`: Perimeter ring placement around target entity/coordinate.

## Install

Drop `LeafRTPGroupAddon-<version>.jar` into `plugins/RTP/addons/`.
