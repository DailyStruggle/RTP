# maps-api

Platform-neutral runtime cartography and diagnostics rendering SPI (`io.github.dailystruggle.mapsapi`). Pure Java 17 on the core model, renderers, and `ImageMapCanvas` bridge (no Bukkit, Fabric, NeoForge, or `rtp-core` dependency; `spigot-api` is `compileOnly` for the optional `.bukkit.BukkitMapBinding`), enabling any standalone plugin or mod (e.g. `TreeGen`, `WorldGuardLite`) to render 2D heatmaps, dual sparklines, and region overlays onto 128x128 in-game map items, PNG/BMP files, or asynchronous Web Mercator tiles.

## Coordinates

- **Maven Central:** `io.github.dailystruggle:maps-api:<version>`
- **JitPack:** `com.github.DailyStruggle.RTP:maps-api:<tag>`

```gradle
dependencies {
    implementation 'io.github.dailystruggle:maps-api:3.3.1'
    // or via JitPack:
    // implementation 'com.github.DailyStruggle.RTP:maps-api:3.3.1'
}
```

## Surface

- `MapBinding` - host-runtime SPI (`allocate(MapAllocationRequest)`, `renderEphemeral(handle, renderer, model)`, `bindLive(handle, renderer, modelSupplier)`, `deliverTo(handle, viewer)`).
- `MapCanvas` - 128x128 (or custom dimension) drawing surface (`setPixel`, `setPixelRgb`, `fillRect`, `drawText`, `clear`, `commit`) backed by logical 32-slot `PaletteIndex` bytes or 24-bit RGB. `ImageMapCanvas` in `.image` renders directly to a `BufferedImage` (`writeToFile`, `toByteArray("png")`) without a running server.
- `MapHandle` - opaque record `(String chartId, UUID viewer, int mapId)` identifying an allocated map slot, paired with `MapAllocationRequest` and `Cancellation`.
- `NoopMapBinding` - fail-closed fallback implementation in `.noop` that throws `IllegalStateException` before a platform binding is wired.
- `ChartModel` hierarchy (`.model`) - sealed interface permitting immutable, defensively copied records: `Heatmap2D`, `CategoryDistribution`, `TimeSeries`, `RegionCoverage`, `RegionBadLocations`, `RegionBiomesRgb`, `MermaidChart`, `DualSparkline`, `CompositeRegionModel`, `RegionWalkPath`, and `SelectionHeatmap`.
- `ChartRenderer` & built-in renderers (`.render`) - deterministic, chunk-I/O-free (`REQ-RTP-MAP-002`) pixel producers: `HeatmapRenderer`, `DualSparklineRenderer`, `CompositeRegionRenderer`, `RegionBiomesRgbRenderer`, `RegionBadLocationsRenderer`, `RegionWalkPathRenderer`, `SelectionHeatmapRenderer`, and bulkhead-guarded `WebTileRenderer` (`renderTilePng(...)` with `AsyncRenderLimiter`).

## Usage

```java
import io.github.dailystruggle.mapsapi.image.ImageMapCanvas;
import io.github.dailystruggle.mapsapi.model.DualSparkline;
import io.github.dailystruggle.mapsapi.model.Heatmap2D;
import io.github.dailystruggle.mapsapi.render.DualSparklineRenderer;
import io.github.dailystruggle.mapsapi.render.HeatmapRenderer;
import io.github.dailystruggle.mapsapi.render.WebTileRenderer;
import java.io.File;
import java.util.concurrent.CompletableFuture;

// Standalone plugin (e.g. TreeGen) rendering a canopy density heatmap to PNG and a web tile:
double[] densityGrid = new double[64 * 64];
densityGrid[0] = 12.5;
Heatmap2D heatmap = new Heatmap2D(64, 64, densityGrid, 0.0, 20.0);

// 1. Render to a standalone 128x128 PNG file off-thread
ImageMapCanvas canvas = new ImageMapCanvas();
HeatmapRenderer.INSTANCE.render(canvas, heatmap);
canvas.writeToFile(new File("treegen-density.png"), "png");

// 2. Render an async 512x512 Web Mercator PNG tile with 500 ms bulkhead protection
WebTileRenderer tileRenderer = new WebTileRenderer();
CompletableFuture<byte[]> pngBytes = tileRenderer.renderTilePng(
        HeatmapRenderer.INSTANCE, heatmap, 512, 512, WebTileRenderer.DEFAULT_TIMEOUT_MS);

// 3. Render a dual MSPT + Heap sparkline onto a canvas
DualSparkline sparkline = new DualSparkline(
        "MSPT (ms)", new double[] {12.0, 14.5, 19.2}, 0.0, 50.0,
        "Heap (MB)", new double[] {512.0, 540.0, 580.0}, 0.0, 2048.0);
DualSparklineRenderer.INSTANCE.render(canvas, sparkline);
```

## Build

```
./gradlew :maps-api:build
```

## Docs

- ADR-046: [`../../docs/adr/ADR-046-maps-api-module.md`](../../docs/adr/ADR-046-maps-api-module.md)
- Subproject ADRs: [`docs/adr/maps-api-ADR-001-bootstrap.md`](docs/adr/maps-api-ADR-001-bootstrap.md), [`docs/adr/maps-api-ADR-002-gui-slot-render-and-offmain-packet-path.md`](docs/adr/maps-api-ADR-002-gui-slot-render-and-offmain-packet-path.md)
