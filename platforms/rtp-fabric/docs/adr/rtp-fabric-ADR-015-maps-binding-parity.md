# rtp-fabric-ADR-015 - Maps API parity on Fabric (vanilla filled-map path)

- **Status:** Accepted (2026-05-31)
- **Scope:** `rtp-fabric` (`rtp-fabric-common`, the per-version carriers, and `rtp-plugin/.../fabric/RTPFabricMod`)
- **Supersedes:** none. Implements the Fabric clause of [ADR-046](../../../../docs/adr/ADR-046-maps-api-module.md) (maps-api module) and [ADR-047](../../../../docs/adr/ADR-047-declarative-chart-composition.md) (declarative chart composition).
- **Related:** [ADR-046](../../../../docs/adr/ADR-046-maps-api-module.md), [ADR-047](../../../../docs/adr/ADR-047-declarative-chart-composition.md), [maps-api-ADR-001](../../../../api/maps-api/docs/adr/maps-api-ADR-001-bootstrap.md), [rtp-fabric-ADR-007](rtp-fabric-ADR-007-mojmap-name-decoupling.md) (Mojmap-name decoupling: the version-volatile call sites cross the seam NM-free), [rtp-fabric-ADR-001](rtp-fabric-ADR-001-multiversion-submodule-layout.md) (per-version carrier layout). Requirements: REQ-RTP-MAP-001/002/003.

## Context

`maps-api` (`MapBinding`, `MapBindingLifecycle`, `MapHandle`, `MapCanvas`, `MapAllocationRequest`, the `ChartModel` hierarchy, and the renderers) is platform-neutral and ships `BukkitMapBinding`, `FoliaMapBinding`, and `NoopMapBinding`. There was no `FabricMapBinding` and no `MapDispatch.setMapBinding(...)` call in `RTPFabricMod`, so every `/rtp` visualization chart on a Fabric backend bottomed out on the `NoopMapBinding` sentinel and logged `... skipped: no concrete MapBinding installed (NoopMapBinding active)` (the symptom reported on Fabric 26.2-pre2). This ADR records the renderer-path decision required by MULTI_PLATFORM_PLAN Step K sub-item 1.

## Decision

Ship the **vanilla filled-map path** (option A): charts render onto a real `MapItemSavedData` and a `FILLED_MAP` item referencing it is delivered to the viewer. The chat-rendered ASCII fallback (option B) was rejected by the maintainer as too low-fidelity for heatmap / region-shape charts.

Because every map touch is `net.minecraft.*` and the names drift across the 1.20 -> 26.x window (and are intermediary-leaked on `rtp-fabric-common`, deobf on the 26.x carriers), the work is split across the rtp-fabric-ADR-007 NM-free seam:

- **`rtp-fabric-common`** hosts the platform-neutral, NM-free pieces:
  - `FabricMapCanvas` - writes the maps-api logical palette (0..31) into a row-major 128x128 ARGB `int[]` buffer (mirrors the `BukkitMapCanvas` ramp; `setPixelRgb` is a full-colour bypass). `drawText` is a no-op (no server-side font raster reachable here).
  - `FabricMapBinding implements MapBinding, MapBindingLifecycle` - owns the per-chart ARGB buffers and the live-refresh loop; resolves the `MinecraftServer` reflectively (no NM in its constant pool) and delegates all NM work to the active `FabricVersionAdapter`. `allocate` returns a synthetic `MapHandle.mapId` (the real `MapId` is carrier-owned, keyed by `chartId`); `renderEphemeral`/`bindLive` only paint the buffer; `deliverTo` triggers the carrier dispatch with `deliverItem=true`; the live loop re-renders and re-dispatches at ~1 Hz with `deliverItem=false`. `onPlayerQuit`/`onDisable` cancel live tasks and release carrier state (REQ-RTP-MAP-003).
- **`FabricVersionAdapter`** gains three NM-free default-method seams: `renderMapChart(server, viewer, chartKey, int[] argb, locked, deliverItem)`, `releaseMapChart(server, chartKey)`, and `supportsMapCharts()` (default `false`).
- **The 26.2_R1 carrier** (`V26_2_R1FabricVersionAdapter`) implements them: it hops to the server tick thread, allocates/reuses a `MapId` + `MapItemSavedData` per `chartKey`, matches each opaque ARGB pixel to the nearest vanilla `MapColor` packed byte (via `MapColor.byId` + `getColorFromPackedId` - `MATERIAL_COLORS`/`calculateRGBColor` are private on this runtime), pushes a full-canvas `ClientboundMapItemDataPacket` built from a 128x128 `MapPatch` (vanilla's `setColorsDirty`/`getUpdatePacket` are private), and on `deliverItem` adds a `FILLED_MAP` carrying the `MAP_ID` data component to the viewer's inventory (drop-at-feet fallback when full).

`RTPFabricMod.onInitialize` installs `FabricMapBinding` via `MapDispatch.setMapBinding(...)` **only when** `FabricVersionAdapterRegistry.peek().supportsMapCharts()` is true, and wires `MapDispatch.firePlayerQuit` onto `FabricPlayerLifecycleHook.onPlayerQuit`. Carriers that do not yet implement the seam (1.20.x, 1.21.x, 26.1_R1 at time of writing) return `false`, so `MapDispatch` stays on `NoopMapBinding` and the configurable `mapBindingMissing` message still surfaces - no half-working binding is installed.

## Consequences

- Fabric 26.2.x reaches feature parity with Bukkit for `/rtp` chart rendering (heatmap, region-shape, sparkline live charts).
- The other carriers (1.20.x, 1.21.x, 26.1_R1) are a mechanical follow-up: each overrides the three seam methods against its own mapping. The 26.2 implementation is the reference port.
- S-005 / REQ-RTP-MAP-002: no chunk I/O on any path; `FabricMapBinding` only mutates in-memory buffers and the carrier touches only map/inventory/connection state on the server thread.
- No `net.minecraft.*` reference leaks into `RTPFabricMod` (verified by `ReqRtpFabricEntrypointNoNetworkProtocolTest`) or into `FabricMapBinding`/`FabricMapCanvas`.

## Follow-ups

- Port `renderMapChart`/`releaseMapChart`/`supportsMapCharts` to the 1.20.x, 1.21.x, and 26.1_R1 carriers.
- Optional: pre-raster chart labels into the ARGB buffer so `drawText` is no longer a no-op on Fabric.
- Add a Fabric-flavoured S-005 sibling of `ReqRtpMap002NoChunkIoTest` once a headless map fixture exists.
