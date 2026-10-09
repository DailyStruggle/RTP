# ADR-099 — Zero-Overhead Visual Region Creation via Ecosystem Selection Bridges and Cartography Models

**Status:** Proposed
**Date:** 2026-09-30

**Extends:** [ADR-034](ADR-034-memory-shape-catalog.md) (Memory Shape Catalog and Polygon Shape), [ADR-046](ADR-046-maps-api-module.md) (`maps-api` Module for Runtime Cartography Chart Generation), [ADR-086](ADR-086-external-web-map-integration.md) (External Web Map Integration)
**Related:** [ADR-026](ADR-026-external-hook-api-surface.md) (External Hook API Surface), [ADR-058](ADR-058-region-specific-schematic-paste.md) (WorldEdit Seam), [ADR-070](ADR-070-platform-neutral-rtp-command-root.md) (Platform-Neutral Command Root)

---

## 1. Context

Server operators configure LeafRTP regions across diverse shapes: bounding squares, circles, and arbitrary non-convex polygons ([ADR-034](ADR-034-memory-shape-catalog.md)). While mathematical and YAML configurations in `regions/<name>.yml` are robust, spatial boundary creation is historically error-prone:
1. **Manual Coordinate Entry:** Operators must manually sample corner coordinates $(X, Z)$ in-game or via third-party web maps and write them into YAML configuration files.
2. **Lack of In-Game Verification:** Operators cannot easily inspect whether an authored polygon bounds the expected biome or terrain without executing trial teleports.
3. **Ecosystem Fragmentation:** Modern servers use established in-game selection tools (WorldEdit/FAWE on Bukkit/Folia/Fabric/NeoForge, or FTB Chunks on modpacks) and external interactive cartography renderers (Pl3xMap, BlueMap, Dynmap).

A naive proposal would embed a local HTTP web server inside LeafRTP to provide a custom browser drawing canvas, spawn custom selection wand items in-game, or render floating particle vectors in the world. However:
- **Embedded Web Servers are Prohibited:** Hosting an internal HTTP/WebSocket server inside the Minecraft plugin runtime adds attack surface, port conflicts, firewall/reverse-proxy overhead, thread management complexity, and violates the zero-overhead architecture.
- **Redundant Selection Wands Cause Clutter:** Introducing an `/rtp wand` forces operators to juggle yet another item alongside WorldEdit wands, claiming tools, and core gameplay items.
- **In-World Particles Cause Client Lag:** Spawning thousands of boundary particles across large radii ($R \ge 5,000$ blocks) thrashes network bandwidth, drops client framerates, and is invisible across unloaded chunks.

A zero-overhead, multi-platform solution is required to let operators draw or capture regions visually and immediately translate them into LeafRTP region configurations.

---

## 2. Decision

LeafRTP shall provide visual region creation and interactive cartography inspection through three unified, zero-server primitives:

### 2a. In-Game Selection Bridges (WorldEdit/FAWE & FTB Chunks)

LeafRTP shall bridge existing spatial selection tools into LeafRTP regions without introducing custom wand items:

1. **Platform-Neutral Selection SPI (`SelectionBridge`):**
   Defined in `rtp-core` / `rtp-api`:
   ```java
   public interface SelectionBridge {
       boolean isAvailable();
       Optional<RegionSelection> getSelection(UUID playerUuid);
   }
   public record RegionSelection(
       String worldName,
       SelectionShapeType shapeType, // POLYGON, SQUARE, CIRCLE
       List<int[]> vertices,         // [x, z] pairs
       int centerX,
       int centerZ,
       int radius
   ) {}
   ```
2. **WorldEdit / FAWE Seam (`WorldEditSelectionBridge`):**
   - Soft-hooks `com.sk89q.worldedit.WorldEdit` across Bukkit, Paper, Folia, Fabric, and NeoForge (mirroring the soft-hook architecture of [ADR-058](ADR-058-region-specific-schematic-paste.md) and [ADR-026](ADR-026-external-hook-api-surface.md)).
   - Converts `Polygonal2DRegion` selections directly into LeafRTP `Polygon` vertices (`ADR-034`), running Collinear Simplification to prune redundant points.
   - Converts `CuboidRegion` selections into `Square` shapes (center + radius).
   - Converts `CylinderRegion` / `EllipsoidRegion` into `Circle` shapes.
3. **Modded Chunk-Claim Seam (`FtbChunksSelectionBridge`):**
   - In Fabric and NeoForge environments running FTB Chunks, extracts the bounding polygon of an admin or team claim contour into an RTP `Polygon`.
4. **Command Surface:**
   ```text
   /rtp region fromselection <regionName> [--world <world>] [--shape <POLYGON|SQUARE|CIRCLE>]
   ```
   Validates polygon edges for self-intersection via `Polygon.setVertices`, writes `regions/<regionName>.yml`, and hot-reloads the region into memory without server restarts.

### 2b. Zero-Backend Interactive Web Map Integration (Pl3xMap / Dynmap / BlueMap)

LeafRTP shall integrate with external web maps without hosting any local HTTP listener:

1. **Client-Side Vector Tooling (`leaf-rtp-map-tool.js`):**
   - For Leaflet-based maps (Pl3xMap, Dynmap): A client-side script loaded into the web map frontend provides drawing controls (powered by `leaflet-geoman` / `Leaflet.draw`).
   - For BlueMap: A lightweight frontend extension intercepts terrain clicks/raycasts to draw 3D boundary loops.
2. **Clipboard Export Hand-off:**
   When the operator finishes drawing a boundary, a browser modal generates:
   - A ready-to-paste YAML snippet for `plugins/RTP/regions/<name>.yml`.
   - A one-click in-game console command:
     ```text
     /rtp region create <name> <world> POLYGON --vertices x1,z1;x2,z2;x3,z3...
     ```
3. **Static Web Configurator:**
   Hosted on the static documentation site (`https://dailystruggle.github.io/RTP/`), operators can visually configure shapes over coordinate grids and export valid configuration blocks.

### 2c. Cartography Map Item Inspection (`maps-api`)

Instead of in-world particle projections, visual inspection shall route through LeafRTP's native cartography subsystem ([ADR-046](ADR-046-maps-api-module.md)):

1. **New Chart Model `RegionBoundary` (`maps-api/.../model/`):**
   ```java
   public record RegionBoundary(
       String regionName,
       int minX, int minZ, int maxX, int maxZ,
       List<int[]> vertices,
       int centerX, int centerZ, int radius,
       String shapeType
   ) implements ChartModel {}
   ```
2. **`RegionBoundaryRenderer` (`maps-api/.../render/`):**
   - Renders the region boundary, deadzone inner radius (`centerRadius`), and center crosshair onto a 128x128 Minecraft map canvas.
   - Emits coordinate and dimension callouts on the map canvas header/footer.
3. **Command Surface:**
   ```text
   /rtp map create region_boundary --region <regionName>
   ```
   Delivers a persistent or ephemeral map item to the operator, providing immediate, zero-lag, in-game visual confirmation of the configured region boundaries.

---

## 3. Alternatives Considered

| Alternative | Why Rejected |
| :--- | :--- |
| **Embedded HTTP/WebSocket Server** | Rejected. Introduces port configuration, firewall traversal hurdles, security attack surface, and thread scheduling complexity inside the server process. |
| **Custom RTP Selection Wand (`/rtp wand`)** | Rejected. Operators already use WorldEdit wands, FAWE, Axiom, or claim wands. Adding another wand item causes inventory clutter, interaction conflicts, and redundant keybinds. |
| **In-World Particle Mesh Projections** | Rejected. Ineffective over large regions ($R > 5,000$), invisible in unloaded chunks, and consumes significant client frame time and server network bandwidth. Map items (`maps-api`) provide persistent, full-scale 2D inspection with zero tick cost. |
| **Native In-Map Interactive Drawing via Packet Hacks** | Rejected. Intercepting right/left clicks on native Minecraft map items to manipulate vertices in-game has severe UX resolution limits ($128 \times 128$ grid) and high packet maintenance overhead across MC versions. |

---

## 4. Consequences

### Positive
- **Zero Server Footprint:** No extra open ports, background HTTP threads, or socket listeners on the server.
- **Operator Muscle Memory:** Leverages tools operators already know (`//wand`, `//sel poly`, FTB Chunks map, and browser web maps).
- **Instant Visual Audit:** Map items via `maps-api` let operators hold and inspect the exact mathematical boundary and deadzone of any configured region in real time.
- **Multi-Platform Parity:** WorldEdit's identical core API on Paper, Folia, Fabric, and NeoForge provides a single unified selection pipeline across all supported platforms.
- **Safe by Contract:** Fails closed if WorldEdit or web maps are absent (Rule S-006).

### Negative / Trade-offs
- **Soft-Dependency Maintenance:** Updates to WorldEdit's or FTB Chunks' internal selection APIs must be tracked across major Minecraft versions. Isolating them behind `SelectionBridge` ensures failures never impact core teleportation.
- **Manual Command Paste for Web Tool:** The zero-server web drawing tool requires the operator to copy-paste the generated command or YAML file into the server console or files.

---

## 5. References

- [ADR-026: External Hook API Surface](ADR-026-external-hook-api-surface.md)
- [ADR-034: Memory Shape Catalog and Polygon Shape](ADR-034-memory-shape-catalog.md)
- [ADR-046: `maps-api` Module for Runtime Cartography Chart Generation](ADR-046-maps-api-module.md)
- [ADR-058: Region-Specific Schematic Paste](ADR-058-region-specific-schematic-paste.md)
- [ADR-086: External Web Map Integration](ADR-086-external-web-map-integration.md)
