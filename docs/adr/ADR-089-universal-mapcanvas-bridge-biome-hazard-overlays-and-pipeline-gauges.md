# ADR-089 — Universal MapCanvas Bridge, Biome-Hazard Composite Overlays, and Visual Pipeline Gauges

**Status:** Accepted  
**Date:** 2026-09-08
**Deciders:** Core Maintainers  
**Consulted:** `maps-api`, `commands-api`, `rtp-core`  

---

## 1. Context

RTP possesses an extensive runtime cartography framework (`maps-api`, [ADR-046](ADR-046-maps-api-module.md), [ADR-047](ADR-047-declarative-chart-composition-bridge.md)), an in-memory spatial memory engine (`MemoryShape`, [ADR-085](ADR-085-spiral-addressed-hilbert-key-space.md), [ADR-088](ADR-088-configurable-downsampling-stride-filter.md)), and an off-tick Anvil prefilter (`rtp-anvil`, [ADR-016](ADR-016-anvil-subsystem.md)).

However, diagnostic visualization surfaces suffer from three architectural decouplings:
1. **Isolated Visual Surfaces:** Biome maps (`/rtp visualization biomes`), bad-locations safety maps (`/rtp visualization bad-locations`), and queue state are separate views. An operator inspecting a region cannot see where bad locations or queue candidates lie in relation to actual biome geography.
2. **Asymmetric In-Game vs External Export Paths:** In-game maps write to `MapCanvas` (`maps-api`), while external test or benchmark visualization scripts generate custom `BufferedImage` or standalone raster plots. There is no unified mechanism for the plugin to export its in-memory cartography, queue states, and candidate arrival distributions directly to PNG/BMP files for web documentation, discord bots, or operator inspection.
3. **Queue & Pipeline Observability:** The multi-tier location queue (L1 Hot / L2 Cold / L3 Backlog, [ADR-028](ADR-028-l3-backlog-cache.md), [ADR-078](ADR-078-composable-cache-pipeline-stages.md)) and candidate arrival trajectories lack an integrated graphical status display. Operators cannot visually monitor queue fullness or candidate dispersion alongside the map.

Existing administrative RTP map tooling (such as EzRTP's `/rtp heatmap` and `/rtp heatmap save`) focuses strictly on plotting post-teleport coordinate frequency grids (often populated via simulated `/rtp fake` samples) without spatial terrain context. They lack integration with underlying world geography, safety run tables, or active pipeline queue metrics. LeafRTP's $O(1)$ memory run tables (`SegmentedKeyRunTable`), off-tick Anvil prefilter (`rtp-anvil`), and unified `MapCanvas` bridge enable arbitrary-resolution composite cartography (desaturated biomes, red hazard washes, outlined candidate vectors, and L1/L2/L3 queue gauges) with zero main-thread chunk I/O and zero TPS impact.

---

## 2. Decision

We establish a unified, bidirectional visualization and export architecture across `maps-api` and `rtp-core`:

### A. Universal `ImageMapCanvas` Bridge
Introduce `ImageMapCanvas` implementing the platform-neutral `MapCanvas` SPI backed by an ARGB `BufferedImage`.
- **Bidirectional Symmetry:** Every `ChartRenderer<M>` authored against `MapCanvas` automatically renders to both in-game Minecraft map items (`BukkitMapCanvas`) and exported high-resolution image files (PNG/BMP).
- **Resolution Agnostic:** Supports native $128 \times 128$ map dimensions or scaled raster buffers ($512 \times 512$, $1024 \times 1024$) while preserving pixel clipping and palette translation.
- **Zero Chunk I/O (Rule S-005):** Renderers write purely to canvas memory buffers asynchronously off-tick.

### B. Biome-Hazard Composite Overlay (`CompositeRegionRenderer`)
Combine terrain geography, safety ground truth, and candidate points into a unified composite chart:
1. **Layer 1 (Backdrop): Desaturated Biome Field:**
   - Samples regional biomes via `MemoryShape.biomeAt()` and maps to RGB via `BiomeColorSource`.
   - Palette is desaturated by $\approx 40\text{--}50\%$ to provide readable geographic context without visual noise.
2. **Layer 2 (Hazards): Translucent Red Overlay:**
   - Chunks flagged hazardous/invalid in `MemoryShape` run tables (oceans, lava, void, claims) are tinted with a red wash:
     $$\text{rgb}_{\text{hazard}} = \text{blend}(\text{rgb}_{\text{desaturated}}, \text{RED}, 0.45)$$
   - Safe terrain remains in its clear desaturated biome tone.
3. **Layer 3 (Candidates & Teleports): Outlined High-Contrast Points:**
   - Candidate locations and arrival trails are rendered as outlined point markers (e.g. $3 \times 3$ pixels with a 1-pixel dark border) ensuring crisp contrast over any backdrop:
     - **Emerald with dark border:** L1 Hot Queue pre-warmed chunks.
     - **Cyan with dark border:** L2 Cold Queue pre-verified locations.
     - **Purple with dark border:** L3 Backlog candidate bins.
     - **Gold with dark border:** Recent player landing locations.
     - **Yellow/Cyan Vector lines:** Trajectory vectors connecting consecutive arrivals.

### C. Pipeline Queue Capacity Health Bars
Include color-synchronized queue capacity gauge bars along the canvas margin:
- **L1 Hot Queue:** Chunky health bar `[████████--]` indicating warm chunk occupancy (Emerald).
- **L2 Cold Queue:** Mid-weight bar indicating pre-verified locations (Cyan).
- **L3 Backlog:** Fine / sub-pixel-thin high-capacity gauge indicating screened candidates (Purple).
- **Visual Sync:** Marker colors on the 2D world map strictly correspond to the colors of the queue capacity bars.

### D. Command Architecture: `/rtp visualization` & `/rtp visualization export`
Standardize the command tree under `VisualizationRootCmd` with typed sub-command leaves:
```
/rtp visualization <type> [region=<name>]
/rtp visualization export <type> [region=<name>] [samples=<count>] [format=png|bmp]
```
Where `<type>` is registered as a concrete command leaf:
- `composite` / `pipeline` — Desaturated biomes + red hazard wash + queue dots + health bars.
- `distribution` — Candidate scatter & consecutive hop trajectory lines.
- `biomes` — Raw full-saturation biome map.
- `bad-locations` — Two-tone red/green safety mask.
- `heatmap` — Visual overlap & collision frequency heatmap.
- `sparkline` — Server health (MSPT / heap) time series.

All export operations execute asynchronously off-tick via `RTP.scheduler.runTaskAsynchronously()` and output to `plugins/RTP/charts/<name>.<format>`.

---

## 3. Criteria & Invariants

- **C1 (Universal MapCanvas Compatibility):** 100% of chart renderers writing to `MapCanvas` must be exportable to image files, and all exportable visualizations must be capable of rendering to in-game map items.
- **C2 (Thread Safety & Zero Chunk I/O - S-005):** Image export, candidate simulation, and canvas rasterization shall never execute on the main server thread or perform synchronous chunk I/O.
- **C3 (No Silent Swallowing - S-004):** Export commands must provide positive feedback with file paths and execution duration, or explicit error messages on failure.
- **C4 (Visual Hierarchy & Contrast):** Outlined markers shall maintain readability across both light (sand/snow) and dark (forest/ocean/red hazard) terrain backgrounds.
- **C5 (Color Synchronization):** Spatial map dots and pipeline queue health bars shall share an identical color mapping.

---

## 4. Alternatives Considered

| Alternative | Pros | Cons / Reason for Rejection |
|:---|:---|:---|
| **Separate Export Service (independent of MapCanvas)** | Custom high-res graphics | Code duplication; improvements to in-game maps do not benefit export and vice versa. |
| **Direct Web Server Streaming** | Browser accessibility | Excessive overhead, opens port security attack surface, violates minimal dependency boundary. |
| **Single-Pixel Unoutlined Points** | Minimal pixel footprint | Points blend into biome pixels and disappear completely against ocean/lava backdrops. |
| **Parameter Syntax `/rtp exportchart <region> <type>`** | Short command name | Inconsistent with existing `/rtp visualization` command tree (ADR-050) and established `region=<name>` parameter conventions. |

---

## 5. Consequences

### Positive:
- **Reproducible Front-Page Visuals:** Front-page distribution charts, heatmaps, and hazard overlays can be generated directly by the plugin in-game or via headless tasks.
- **Unified Cartography Stack:** One single renderer interface (`ChartRenderer`) serves both in-game handheld maps and disk file exports.
- **Enhanced Operator Observability:** Admins can visually verify Anvil pre-filtering, queue fullness, and candidate spacing in real-time.

### Neutral / Trade-offs:
- **File I/O off-tick:** Writing PNG/BMP files involves off-tick disk writes in `plugins/RTP/charts/`. Bounded by asynchronous scheduling and file path sanitization.
- **Palette Quantization in-game:** Handheld Minecraft maps are constrained to vanilla's ~144 map palette colors, whereas exported PNGs display full 24-bit ARGB. `ImageMapCanvas` seamlessly preserves 24-bit RGB for file export while `BukkitMapCanvas` quantizes via `MapPalette`.
