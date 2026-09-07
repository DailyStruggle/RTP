# ADR-086 - External Web Map Integration via `anvil-api`, `maps-api`, and Spatial Memory

**Status:** Proposed

**Date:** 2026-09-06

**Context:**
- [ADR-016](ADR-016-anvil-subsystem.md) — Anvil Read-Only Subsystem (Prefilter, Backed Chunk View, Shared Module).
- [ADR-046](ADR-046-maps-api-module.md) — `maps-api` Module for Runtime Cartography Chart Generation.
- [ADR-077](ADR-077-multi-format-region-support.md) — Multi-Format Region Support: Linear (ZSTD) and Pluggable Region Readers.
- [ADR-085](ADR-085-spiral-addressed-hilbert-key-space.md) — Spiral-Addressed Hilbert Key Space for Learned State.
- [`maps-api-ADR-001`](../../api/maps-api/docs/adr/maps-api-ADR-001-bootstrap.md) — Module Bootstrap, Package Layout, and Palette Policy.

---

## 1. Context and Problem Statement

Modern Minecraft servers frequently run external web-based cartography engines—predominantly **BlueMap**, **Pl3xMap**, and **Dynmap**—to provide high-resolution, browser-viewable maps of their worlds.

Concurrently, server operators require spatial visualization of their random teleport subsystems:
1. **Region boundaries and deadzones:** Verifying configured shapes (`Circle`, `Square`, `Polygon`), center offsets, and world border clamps.
2. **Safety and hazard topologies:** Visualizing safe landing zones, hazardous terrain (oceans, lava pools, void, steep slopes), and claim exclusions.
3. **Player teleport distribution and dispersion:** Auditing destination randomness to verify uniform dispersion across the border and detect clustering anomalies.

### The Problem with Existing Cartography Integration

Existing `/rtp` plugins (e.g. EzRTP, JustRTP, BetterRTP) approach mapping in one of two flawed ways:
1. **Synchronous or Heavy Tile Rendering:** Attempting to render custom map tiles by querying the Minecraft server engine (`world.getChunkAt()` or `world.getHighestBlockAt()`), which violates safety prohibition **S-005** (no chunk loading on the main thread) and triggers catastrophic region-scheduler thrashing on Folia and multi-second MSPT spikes on Paper.
2. **Primitive Marker Overlays:** Injecting simple vector outlines or single coordinate markers via map APIs. While lightweight, vector markers cannot depict pixel-level safety terrain, biome classifications, or continuous spatial densities without thousands of discrete DOM/marker objects overloading client browsers.

Furthermore, general web map tile renderers spend hours rendering empty deep-ocean chunks because they possess no prior knowledge of world topology.

---

## 2. Decision

We establish an architecture for on-demand, non-blocking external web map integration composed entirely of existing LeafRTP subsystem primitives: **`anvil-api`**, **`MemoryShape` (ADR-085 key space)**, and **`maps-api`**.

This integration is partitioned into three functional tiers:

### 2a. On-Demand Raster Tile Provider (Pl3xMap / Dynmap)

For 2D raster web map engines (such as Pl3xMap's Leaflet tile layer and Dynmap's custom map types):
- **On-Demand Slippy Map Tile Interface:** Implement a custom tile provider serving standard Web Mercator raster tiles ($512 \times 512$ or $256 \times 256$ pixels) mapped to chunk coordinates.
- **Zero-Chunk-Load Pipeline:** When the browser requests tile `(z, x, y)`:
  1. The tile bounds are converted to world chunk bounds $[cx_{\min}..cx_{\max}, cz_{\min}..cz_{\max}]$.
  2. For chunks already evaluated: The safety verdict and biome are resolved in memory via `MemoryShape.isBadLocation(k)` in **$O(\log N)$ binary search time ($208\text{ ns}$ per chunk)**.
  3. For unverified chunks: The tile provider queries `anvil-api` / `RegionFileReader` (ADR-016 / ADR-077) off-tick directly from `.mca` / `.linear` files on disk, bypassing the server's chunk loading pipeline entirely (**Zero main-thread blocking, S-005 compliant**).
  4. The raster image is painted into an off-main buffer using `maps-api`'s `HeatmapRenderer` and streamed directly to the browser as PNG/WebP bytes.

### 2b. Vector Volume and Marker Sets (BlueMap / Dynmap / Pl3xMap)

For 3D WebGL and vector overlay layers:
- **Shape & Region Overlays:** Register lightweight `MarkerSet` instances representing configured RTP regions. Region perimeters (circles, squares, polygons) and inner `centerRadius` exclusion zones are projected as semi-transparent extruded polygonal volumes.
- **Dispersion Radar:** Provide an optional "Recent Teleports" layer rendering player landing markers with decaying opacity, allowing server administrators to visually audit real-world spatial dispersion.

### 2c. Reverse Integration: Cartography Pre-Filter (`BlueMapMap.setTileFilter`)

Leverage LeafRTP's `anvil-api` and spatial memory in reverse to accelerate web map generation:
- Provide an optional `TileFilter` predicate to BlueMap and Dynmap that rejects ungenerated chunks or pure deep-ocean/void regions where no safe terrain exists.
- This prevents the external map engine from allocating disk space and CPU cycles rendering empty ocean tiles across a 10,000-block border.

---

## 3. Structural Boundaries & Packaging

Per **ADR-013**, **ADR-019**, and **ADR-057**, integrations with third-party plugins must not pollute `rtp-core` or platform adapters with external dependencies:

```
[ rtp-core ]
     │ (Spatial Memory / MemoryShape)
     ▼
[ api/maps-api ] ──► [ api/anvil-api ]
     │ (Abstract Render Pipeline / Canvas)
     ▼
[ addons/LeafRTP-Pl3xMapAddon ]   [ addons/LeafRTP-BlueMapAddon ]   [ addons/LeafRTP-DynmapAddon ]
  (Optional Addon Modules depending strictly on rtp-api and map-specific APIs)
```

1. **`maps-api` Extension:** `maps-api` gains an abstract `WebTileRenderer` capable of translating chunk bounding boxes into raster image streams without referencing any specific web map class.
2. **Addon Delivery:** Concrete integrations (`LeafRTP-Pl3xMapAddon`, etc.) live as optional addon modules discovering LeafRTP via the `RTPAddon` SPI (ADR-057).
3. **Fail-Closed Contract:** If the host map plugin is updated, absent, or disabled, the addon unregisters its layers cleanly without affecting core teleport services (S-006).

---

## 4. Consequences

### Positive
- **True Zero-Tick-Cost Web Heatmaps:** Operators can inspect full-world RTP safety, biome distributions, and landing densities in their web browser with zero server MSPT degradation and zero chunk loading.
- **Instantaneous Streaming:** Because `MemoryShape` stores the mathematical complement of bad space in a compact, Hilbert-ordered run table (ADR-085), tile generation operates directly out of CPU cache lines without disk I/O for all scanned areas.
- **Proof of Dispersion:** Provides operators with an intuitive visual verification of LeafRTP's uniform landing distribution versus competitor clumping.
- **Map Generation Speedup:** Web map plugins using the `TileFilter` hook can skip rendering uninhabitable void/ocean tiles, drastically reducing initial map render times.

### Negative / Trade-offs
- **API Volatility in Downstream Map Plugins:** Web map plugins (particularly Pl3xMap and BlueMap) update internal layer APIs across major Minecraft releases; isolating these into optional addons mitigates core stability risk.
- **Memory Overhead for Dynamic Tiles:** Streaming dynamic tile rasters requires transient image buffer allocations on the web server worker pool, which must be throttled to prevent memory exhaustion under concurrent browser requests.

---

## 5. References
- [ADR-016: Anvil Subsystem](ADR-016-anvil-subsystem.md)
- [ADR-046: maps-api Module](ADR-046-maps-api-module.md)
- [ADR-077: Multi-Format Region Support](ADR-077-multi-format-region-support.md)
- [ADR-085: Spiral-Addressed Hilbert Key Space](ADR-085-spiral-addressed-hilbert-key-space.md)
- [`maps-api-ADR-001`](../../api/maps-api/docs/adr/maps-api-ADR-001-bootstrap.md)
