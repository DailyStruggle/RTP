# leafrtp-party-addon-ADR-001 - Party Teleport via Shared Prepared Destination (Reuse the Supply Pipeline, Do Not Search Per Member)

**Status:** Proposed
**Date:** 2026-07-23

## Context

Group/"party" random teleport is a common request on event and social servers: a party runs one
command and everyone lands together. Some competitor plugins approach this through a matchmaking /
zone system layered on a search-per-player engine, where each member triggers its own location
search and the results are then reconciled - which multiplies the per-teleport cost by the party
size and complicates safety.

LeafRTP already prepares locations ahead of time in its supply pipeline (active-loaded / prefiltered
/ selected, backed by the L1/L2/L3 tiers). A prepared coordinate is already safety-verified and
chunk-ready. This changes the shape of the party problem: the expensive part (finding and verifying a
safe destination) is already done once, so serving that single coordinate to N members, or drawing a
small adjacent set of prepared coordinates, is cheap and requires no new search.

This addon is an optional, platform-neutral `RTPAddon` (ADR-057). It must not add a search mode to
the core, must not weaken safety to co-locate members, and must not let one large party starve
concurrent solo requests.

## Decision

Implement party teleport as a consumer of the existing supply pipeline, in two placement modes:

1. **Shared-coordinate (`SAME`).** Draw one prepared destination and deliver every party member to
   it. Cheapest; members stack on arrival.
2. **Cluster (`CLUSTER`).** Draw one prepared destination per member from the prepared supply,
   preferring coordinates adjacent to the first, so members land near each other without stacking.

Bounds and guarantees:

- A configurable `maxPartySize` caps how many prepared coordinates one operation consumes, so a
  large party cannot drain the supply.
- Every member's destination passes the same safety verification as a solo teleport; co-location
  never bypasses a check.
- When the prepared supply is insufficient, the operation degrades gracefully (serve who can be
  served / defer) rather than forcing synchronous generation on a tick thread.
- Each member's teleport outcome is reported; no member's failure is silently dropped (S-004), and an
  error affecting one member is isolated from the others and from the core queue.
- Teleport dispatch routes through the core scheduler and server-accessor abstractions only; no
  platform imports. Under network mode, cross-backend coordinate allocation defers to the core
  reservation mechanism.

Configuration (`addons/party.yml`): `enabled`, `placement` (`SAME` | `CLUSTER`), `maxPartySize`,
registered through the core `ConfigParser` and refreshed on `/rtp reload`.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Search per member, then reconcile results | Multiplies per-teleport cost by party size and re-solves a problem the supply pipeline already solved; contradicts the project's throughput-over-search design. |
| Add a party/zone concept to `rtp-core` | Party teleport is optional presentation/orchestration, not core distribution logic; it belongs in an addon (ADR-013 / ADR-057). No core ADR is warranted. |
| Co-locate members by relaxing safety near the anchor | Violates S-001/S-005; unsafe co-location is worse than a small spread. Cluster mode draws additional *verified* coordinates instead. |
| Reserve the whole party's coordinates synchronously up front | Risks blocking and supply starvation; graceful degradation with bounded consumption is preferred. |

## Consequences

- **Positive:** Group teleport at near solo-teleport cost by reusing prepared destinations; no new
  search path; safety preserved; naturally composes with network-mode reservation for cross-backend
  parties.
- **Negative / Trade-offs:** Cluster mode consumes several prepared coordinates at once, so it draws
  down the supply faster than a solo teleport; `maxPartySize` and graceful degradation mitigate this.
  Party membership itself is sourced externally, so behavior depends on the quality of that source.
- This ADR is confined to the addon; it introduces no core-wide decision and adds no core ADR.

## References

- [ADR-057](../../../../docs/adr/ADR-057-platform-agnostic-addon-spi.md) - Platform-agnostic addon SPI (`RTPAddon` + ServiceLoader).
- [ADR-013](../../../../docs/adr/ADR-013-addons-as-external-gradle-projects.md) - Addons as external Gradle projects.
- [ADR-028](../../../../docs/adr/ADR-028-l3-backlog-cache.md) - L3 backlog cache (the prepared-supply tiers this addon consumes).
- [ADR-036](../../../../docs/adr/ADR-036-network-mode-multi-server-multi-proxy.md) - Network mode (cross-backend reservation this addon defers to).
- [`REQUIREMENTS.md`](../../REQUIREMENTS.md) - the addon's requirements.
