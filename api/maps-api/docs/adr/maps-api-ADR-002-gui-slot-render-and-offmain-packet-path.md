# maps-api-ADR-002 - GUI-Slot Map Delivery and a Future Off-Main Raw-Packet Render Path

- **Status:** Proposed (2026-06-14)
- **Supersedes:** -
- **Superseded by:** -
- **Related:**
  - [maps-api-ADR-001](maps-api-ADR-001-bootstrap.md) - module bootstrap, package layout, palette policy. This ADR is additive to it.
  - [ADR-046](../../../docs/adr/ADR-046-maps-api-module.md) - umbrella `maps-api` module ADR (REQ-RTP-MAP-001..005).
  - [ADR-047](../../../docs/adr/ADR-047-declarative-chart-composition-bridge.md) - `ChartSpec` + `MapDispatch` composition bridge.
  - [ADR-039](../../../docs/adr/ADR-039-rtpadmin-diagnostic-surfaces.md) - biome map / bad-selection visualizations these renders reuse.
  - `addons/RTP_GuiAddon/` - the destination-picker addon that motivates the GUI-slot use case (region icon rendered as a live biome map).

---

## Context

The `RTP_GuiAddon` destination picker wants the per-region icon to be the region's biome render (the same chart `/rtp visualization biomes` produces), shown as a `FILLED_MAP` in a chest-GUI slot rather than dumped into the player's inventory. Investigating feasibility surfaced two gaps between what `maps-api` does today and what that use case needs:

1. **Delivery target.** The only delivery primitive on `MapBinding` is `deliverTo(handle, viewer)`, which on the Bukkit family drops a `FILLED_MAP` item at the viewer or into their inventory (see `BukkitMapBinding.deliverTo`). A GUI renderer needs the rendered `FILLED_MAP` **as an `ItemStack`** to place into a specific menu slot, not an inventory drop.

2. **Render thread.** The current Bukkit path rides Bukkit's `MapView` + `MapRenderer` machinery: `Bukkit.createMap` (allocate) and the item stamp mutate server/world state and must run on the main / region thread, and the actual paint happens inside `OneShotChartRenderer.render`, which the server invokes on tick when a viewer can see the map. The chart pixel data, however, is built from in-memory state only (`MemoryShape.biomeKeysCache`, packed-long bad-location sets) with no chunk I/O, so the *frame computation* is pure CPU and is safe to do off-thread. A fully off-main delivery is only achievable by bypassing `MapView` and sending a raw map-data packet (`ClientboundMapItemDataPacket`) with a self-managed map id - a second, lower-level render path.

This ADR records the intended direction for both gaps **without** implementing the second path yet, and pins the threading posture to use in the meantime. It does not change any current behavior or REQ.

## Decision

### 1. A second render path is anticipated, not yet built

`maps-api` is expected to grow a second render/delivery path optimized for the GUI-slot use case:

- A `MapBinding` method that returns the rendered map as a platform item handle for placement in an arbitrary container slot (e.g. `itemFor(handle)` / a `deliverToSlot`-style variant), instead of only the inventory-drop `deliverTo`.
- An off-main render path that builds the 128x128 palette byte buffer off-thread from the in-memory chart model and pushes it to viewers via a raw map-data packet with a binding-managed map id, sidestepping `Bukkit.createMap` / `MapView` / `MapRenderer`.

This path is **deferred**. It is more version- and NMS-sensitive (or requires ProtocolLib), it must hand-manage map ids so they do not collide with real cartography maps, and it forfeits the `MapView`-backed `MemoryTracker` and REQ-RTP-MAP-* guarantees that the current path inherits for free. Until there is a measured need, it stays documented potential rather than code.

### 2. Until then, prefer the existing `MapView` path and route to the render thread via the RTP scheduler abstraction

For any near-term consumer (including the GUI addon region-icon feature when it lands):

- Reuse the existing `MapView` + `renderEphemeral` path. "Show the latest render, never perpetually update" is exactly `renderEphemeral` (one-shot), **not** `bindLive` (the refresh-subscription path with its own lifecycle).
- Do **not** assume any particular thread. The caller is responsible for hopping to the correct thread to allocate / paint / stamp the map, and shall do so through the **RTP scheduler abstraction** (`RTP.scheduler`) - the same way `MapDispatch` already dispatches `deliverTo` (Paper: main thread; Folia: the viewer's region / entity scheduler). The scheduler abstraction is the single sanctioned way to "navigate to the correct place to render a map"; raw `Executors` / `new Thread` for this work is prohibited per the project's *Scheduler Usage* rule.
- The heavy, in-memory frame computation may be prepared off-thread and snapshotted into the immutable chart model before the scheduled render hop, keeping the on-thread step cheap.

### 3. Caching posture for per-entity (e.g. per-region) icons

When a consumer needs one map per logical entity (e.g. one biome render per region):

- Allocate **one map id per entity**, viewer-independent (biome layout does not vary by viewer), and reuse it across opens (the `chartId` idempotency hint on `allocate` already returns the cached handle).
- Refresh lazily behind a staleness TTL (e.g. repaint on parent-menu open only when stale), not on every open and not via a live subscription.
- Note the persistence side effect: `Bukkit.createMap` writes a `map_<n>.dat` to world data that survives restarts; a fresh id each boot accumulates orphaned map files slowly. Persisting the entity->id mapping (or accepting the cosmetic leak) is the consumer's call and out of scope here.

## Alternatives Considered

| Alternative | Why deferred / rejected (for now) |
|-------------|-----------------------------------|
| Build the off-main raw-packet path immediately | More NMS/version surface and self-managed map-id collision risk than the GUI feature currently justifies; the `MapView` path already meets the need with a cheap once-per-TTL on-thread paint. Documented as future work instead. |
| Extend `deliverTo` to accept a slot rather than add a new method | `deliverTo`'s contract is inventory/world delivery with its own threading note; overloading it with GUI-slot semantics would muddy a stable SPI. A distinct item-returning accessor is cleaner when the path is actually built. |
| Let consumers call `Bukkit.createMap` / send packets directly off a raw thread | Violates the project *Scheduler Usage* rule (bypasses Folia region ownership, `MemoryTracker`, and the `RTPRunnable` drain) and the REQ-RTP-MAP-002 no-block / correct-thread contract. The scheduler abstraction is mandatory. |
| Use `bindLive` for the icon | Perpetual refresh loop and lifecycle the icon does not need; the icon only ever shows the latest snapshot. `renderEphemeral` is the right primitive. |

## Consequences

- **Positive:**
  - The GUI-slot biome-icon feature can proceed on the existing, contract-compliant `MapView` path with a clear threading rule (route via `RTP.scheduler`), no new SPI required first.
  - The off-main optimization is captured as an explicit, bounded future path rather than rediscovered ad hoc, with its costs (NMS surface, map-id management, lost `MemoryTracker` coverage) written down up front.
  - No current behavior, REQ, or palette decision changes; this ADR is purely additive direction.

- **Negative / Trade-offs:**
  - The near-term path keeps a cheap but real main / region-thread touch per TTL-bounded repaint. Acceptable given the in-memory, once-per-staleness-window cost.
  - When the second path is eventually built it will need its own ADR amendment (or successor) to lock the new `MapBinding` method shape, the map-id allocation scheme, and the per-platform packet send.

## References

- [maps-api-ADR-001](maps-api-ADR-001-bootstrap.md) - bootstrap, package layout, palette policy.
- [ADR-046](../../../docs/adr/ADR-046-maps-api-module.md) - umbrella `maps-api` ADR (REQ-RTP-MAP-001..005).
- [ADR-047](../../../docs/adr/ADR-047-declarative-chart-composition-bridge.md) - `ChartSpec` + `MapDispatch`.
- [`.junie/AGENTS.md`](../../../.junie/AGENTS.md) - *Scheduler Usage* (no raw threads on backend JVMs) and the S-005 / REQ-RTP-MAP-002 threading rules this ADR defers to.
