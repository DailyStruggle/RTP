# anvil-api

Platform-neutral, off-tick Minecraft region-file decoder and read-only surface/biome pre-filter (`io.github.dailystruggle.rtp.anvil`). Pure Java 17 layered only on `:bit-api` (no Bukkit, Paper, Folia, Fabric, NeoForge, or `rtp-core` dependency), allowing any standalone world tool, structure generator, or claim scanner (e.g. `TreeGen`, `WorldGuardLite`) to inspect unloaded chunks from disk without triggering main-thread chunk loads (S-005).

## Coordinates

- **Maven Central:** `io.github.dailystruggle:anvil-api:<version>`
- **JitPack:** `com.github.DailyStruggle.RTP:anvil-api:<tag>`

```gradle
dependencies {
    implementation 'io.github.dailystruggle:anvil-api:3.3.1'
    // or via JitPack:
    // implementation 'com.github.DailyStruggle.RTP:anvil-api:3.3.1'
}
```

## Surface

- `AnvilPrefilter` - off-tick region-file probe entry point. Provides `probeDetailed(...)`, `probeSync(...)`, `probeSyncDetailed(...)`, batched `probeBatchSyncDetailed(...)`, `openSession(...)` (`RegionProbeSession`), and single-column `probeColumn(...)` / `probeColumnAsync(...)` / `squareJobAsync(...)` coalesced on `AnvilIoPool`.
- `RegionFileReader` - format-neutral SPI (`readChunk(byte[], int, int)`, `isChunkGenerated(byte[], int, int)`) that unpacks raw region bytes into an `AnvilReader.ChunkEntry`. Built-in `AnvilReader.INSTANCE` supports compression modes 1 (GZIP), 2 (Zlib/Deflate), 3 (uncompressed), 4 (in-house `Lz4BlockDecoder`), and 5 (`DynamicZstdBinding`).
- `RegionFormatRegistry` - thread-safe registry mapping file extensions (`.mca` built in; custom formats such as `.linear` registered via `register(extension, reader)` or `ServiceLoader<RegionFileReaderProvider>`) to their `RegionFileReader`.
- `AnvilChunkView` - immutable decoded chunk view over block sections (`List<PaletteSection>`) and biome sections (`List<BiomePaletteSection>`), exposing `blockIdAt(x, worldY, z)`, `isAir(...)`, `isSafe(...)`, `getBiomeAt(x, worldY, z)`, and `getBiomesPresent()`.
- `ColumnProbe` - low-allocation center-column (`lx=8, lz=8`) and section view over a bounded `[minY, maxY]` window (`blockAt(worldY)`, `blockAt(localX, localZ, worldY)`, `biomeAt(worldY)`, `heightmapTopY()`, `hasHeightmap()`), skipping entities, block entities, and light arrays during NBT decode.
- `ChunkCoord` - value record `(int x, int z)` with `regionX()`, `regionZ()`, and packed 64-bit `regionKey()` for grouping chunk probes by region file.
- `Nbt` - zero-dependency, depth- and allocation-bounded (`MAX_DEPTH = 512`, `MAX_ARRAY_LENGTH = 16M`) binary NBT reader/writer (`readNamedRoot`, `readRootCompound`, `readRootCompoundSelective`, `NbtList`, `NamedTag`).

## Usage

```java
import io.github.dailystruggle.rtp.anvil.AnvilChunkView;
import io.github.dailystruggle.rtp.anvil.AnvilPrefilter;
import io.github.dailystruggle.rtp.anvil.ChunkCoord;
import io.github.dailystruggle.rtp.anvil.ColumnProbe;
import io.github.dailystruggle.rtp.anvil.Verdict;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Standalone plugin (e.g. TreeGen) scanning unloaded chunks off-thread before placement:
Path worldFolder = Path.of("world");
Set<String> unsafeBlocks = Set.of("LAVA", "WATER", "MAGMA_BLOCK");

// 1. Coalesced async center-column probe over Y = [60, 160]
AnvilPrefilter.probeColumnAsync(worldFolder, "", 12, -8, 60, 160)
        .thenAccept(probe -> {
            if (probe != null && probe.hasHeightmap()) {
                int surfaceY = probe.heightmapTopY() - 1;
                String groundId = probe.blockAt(surfaceY);
                String biomeId = probe.biomeAt(surfaceY + 1);
            }
        });

// 2. Batched detailed probe sharing a single channel open per r.X.Z.mca file
List<ChunkCoord> batch = List.of(new ChunkCoord(12, -8), new ChunkCoord(13, -8));
Map<ChunkCoord, AnvilPrefilter.ProbeResult> results = AnvilPrefilter.probeBatchSyncDetailed(
        worldFolder, "", batch, unsafeBlocks, AnvilPrefilter.DEFAULT_RECONCILER);

AnvilPrefilter.ProbeResult res = results.get(new ChunkCoord(12, -8));
if (res != null && res.verdict() == Verdict.ACCEPT) {
    AnvilChunkView view = res.view();
    Set<String> biomes = view.getBiomesPresent();
}
```

## Build

```
./gradlew :anvil-api:build
```

## Docs

- ADR-016: [`../../docs/adr/ADR-016-anvil-subsystem.md`](../../docs/adr/ADR-016-anvil-subsystem.md)
- ADR-077: [`../../docs/adr/ADR-077-multi-format-region-support.md`](../../docs/adr/ADR-077-multi-format-region-support.md)
